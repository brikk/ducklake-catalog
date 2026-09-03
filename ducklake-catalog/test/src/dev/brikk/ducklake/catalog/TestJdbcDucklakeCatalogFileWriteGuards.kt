/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

/**
 * File-writing operations are refused on catalogs this library cannot write correctly
 * (TODO-rectify-from-eval.md W-B7, W-D8): an ENCRYPTED lake (every file needs a per-file key we
 * do not produce — upstream then throws "Database is encrypted, but file ... does not have an
 * encryption key" for the whole table) and a pre-0.4 spec version (row shapes differ; upstream
 * migrates on ATTACH). Metadata-only DDL keeps working.
 */
class TestJdbcDucklakeCatalogFileWriteGuards {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "file-write-guards")
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::server.isInitialized) {
                server.close()
            }
        }
    }

    private fun fragment(columnId: Long) = DucklakeWriteFragment(
        "guard.parquet", 100L, 0L, 1L,
        listOf(DucklakeFileColumnStats(columnId, 8L, 1L, 0L, "1", "1", false)),
    )

    private fun openCatalog(jdbcUrl: String, dataPath: String) = JdbcDucklakeCatalog(
        DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = jdbcUrl
            catalogDatabaseUser = server.getUser()
            catalogDatabasePassword = server.getPassword()
            this.dataPath = dataPath
            maxCatalogConnections = 3
        },
    )

    @Test
    fun encryptedLakeRefusesFileWritesButAllowsDdl() {
        val db = "enc_lake"
        server.createDatabase(db)
        val dataDir = Files.createTempDirectory("enc-lake-data")
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { st ->
                st.execute("INSTALL ducklake")
                st.execute("LOAD ducklake")
                st.execute("INSTALL postgres")
                st.execute("LOAD postgres")
                st.execute(
                    "ATTACH '" + server.getDuckDbAttachUri(db).replace("'", "''") + "' AS enc (DATA_PATH '" +
                        dataDir.toAbsolutePath().toString().replace("'", "''") + "', ENCRYPTED)",
                )
                st.execute("CREATE TABLE enc.main.t (id INTEGER)")
                st.execute("INSERT INTO enc.main.t SELECT range FROM range(20)")
            }
        }
        openCatalog(server.getJdbcUrl(db), dataDir.toAbsolutePath().toString()).use { catalog ->
            assertThat(catalog.isEncrypted()).isTrue()
            assertThat(catalog.getSpecVersion()).isEqualTo("1.0")
            val snapshot = catalog.currentSnapshotId
            val table = catalog.getTable("main", "t", snapshot)!!
            val idCol = catalog.getTableColumns(table.tableId, snapshot).single().columnId
            val filesBefore = catalog.getDataFiles(table.tableId, snapshot).size

            assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment(idCol))) }
                .isInstanceOf(DucklakeEncryptedCatalogUnsupportedException::class.java)
                .hasMessageContaining("encrypted")
            assertThatThrownBy {
                catalog.commitDelete(
                    table.tableId,
                    listOf(DucklakeDeleteFragment(catalog.getDataFiles(table.tableId, snapshot).first().dataFileId, "d.parquet", 1, 10, 0, 1)),
                    snapshot,
                )
            }.isInstanceOf(DucklakeEncryptedCatalogUnsupportedException::class.java)
            assertThatThrownBy { catalog.commitAddFiles(table.tableId, listOf(fragment(idCol))) }
                .isInstanceOf(DucklakeEncryptedCatalogUnsupportedException::class.java)

            // Nothing was committed ...
            assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
            assertThat(catalog.getDataFiles(table.tableId, snapshot)).hasSize(filesBefore)
            // ... and metadata-only DDL is still allowed.
            catalog.createSchema("enc_ddl_ok")
            assertThat(catalog.getSchema("enc_ddl_ok", catalog.currentSnapshotId)).isNotNull()
        }
    }

    @Test
    fun preSpec04CatalogRefusesFileWrites() {
        fun setVersion(v: String) {
            DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
                c.createStatement().use { st -> st.executeUpdate("UPDATE ducklake_metadata SET value = '$v' WHERE key = 'version'") }
            }
        }
        setVersion("0.3")
        try {
            openCatalog(isolated.jdbcUrl, isolated.dataDir.toAbsolutePath().toString()).use { catalog ->
                assertThat(catalog.getSpecVersion()).isEqualTo("0.3")
                assertThat(catalog.isEncrypted()).isFalse()
                val snapshot = catalog.currentSnapshotId
                val table = catalog.getTable("test_schema", "simple_table", snapshot)!!
                val idCol = catalog.getTableColumns(table.tableId, snapshot).first().columnId
                assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment(idCol))) }
                    .isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
                    .hasMessageContaining("0.3")
                    .hasMessageContaining("1.0")
                assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
            }
        }
        finally {
            setVersion("1.0")
        }
        openCatalog(isolated.jdbcUrl, isolated.dataDir.toAbsolutePath().toString()).use { catalog ->
            val snapshot = catalog.currentSnapshotId
            val table = catalog.getTable("test_schema", "simple_table", snapshot)!!
            val idCol = catalog.getTableColumns(table.tableId, snapshot).first().columnId
            catalog.commitInsert(table.tableId, listOf(fragment(idCol)))
            assertThat(catalog.currentSnapshotId).isEqualTo(snapshot + 1)
        }
    }
}
