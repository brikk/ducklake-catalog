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
 * (dev-docs/archive/TODO-rectify-from-eval.md W-B7, W-D8): an ENCRYPTED lake (every file needs a per-file key we
 * do not produce — upstream then throws "Database is encrypted, but file ... does not have an
 * encryption key" for the whole table) and a pre-0.4 spec version (row shapes differ; upstream
 * migrates on ATTACH). Metadata-only DDL keeps working on supported encrypted catalogs,
 * but every mutation rejects unsupported catalog versions.
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
                        dataDir.toAbsolutePath().toString().replace("'", "''") + "', ENCRYPTED, DUCKLAKE_VERSION '1.0')",
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
    fun aLiveClientDoesNotReuseItsSupportedVersionAfterExternalMigration() {
        openCatalog(isolated.jdbcUrl, isolated.dataDir.toString()).use { catalog ->
            val table = catalog.getTable("test_schema", "simple_table", catalog.currentSnapshotId)!!
            val column = catalog.getTableColumns(table.tableId, catalog.currentSnapshotId).first().columnId
            catalog.commitInsert(table.tableId, listOf(fragment(column))) // warm the old cached guard
            val before = catalog.currentSnapshotId
            setMetadata("version", "1.1-dev1")
            try {
                assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment(column))) }
                    .isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
                assertThat(catalog.currentSnapshotId).isEqualTo(before)
            } finally { setMetadata("version", "1.0") }
        }
    }

    @Test
    fun finalAndUnknownVersionsCannotBeEnabledByChangingOnlyTheVersionString() {
        openCatalog(isolated.jdbcUrl, isolated.dataDir.toString()).use { catalog ->
            val before = catalog.currentSnapshotId
            val table = catalog.getTable("test_schema", "simple_table", before)!!
            for (version in listOf("1.1", "1.1-dev2", "2.0")) {
                setMetadata("version", version)
                try {
                    assertThat(catalog.getSpecVersion()).isEqualTo(version)
                    assertThatThrownBy { catalog.createSchema("candidate_only") }
                        .isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
                    assertThatThrownBy { catalog.analyzeTable(table.tableId) }
                        .isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
                    assertThat(catalog.currentSnapshotId).isEqualTo(before)
                    assertThat(catalog.getSchema("candidate_only", before)).isNull()
                } finally { setMetadata("version", "1.0") }
            }
        }
    }

    @Test
    fun unsupportedFormatsRefuseDdlStatsExpiryAndCleanupMutations() {
        openCatalog(isolated.jdbcUrl, isolated.dataDir.toString()).use { catalog ->
            val before = catalog.currentSnapshotId
            val table = catalog.getTable("test_schema", "simple_table", before)!!
            val expired = catalog.listExpirableSnapshots(null, null).first()
            val operations = listOf<() -> Unit>(
                { catalog.createSchema("unsupported_ddl") },
                { catalog.addColumn(table.tableId, TableColumnSpec.leaf("unsupported", "int32", true)) },
                { catalog.truncateTable("test_schema", "simple_table") },
                { catalog.analyzeTable(table.tableId) },
                { catalog.expireSnapshots(setOf(expired)) },
                { catalog.removeScheduledFileRows(listOf(0)) },
                { catalog.removeSelectedScheduledFiles(listOf(DucklakeScheduledFileEntry(0, "p", true, java.time.OffsetDateTime.now()))) },
            )
            setMetadata("version", "1.1-dev1")
            try {
                operations.forEach { operation ->
                    assertThatThrownBy { operation() }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
                }
                assertThat(catalog.currentSnapshotId).isEqualTo(before)
                assertThat(catalog.getSnapshot(expired)).isNotNull()
                assertThat(catalog.getSchema("unsupported_ddl", before)).isNull()
            } finally { setMetadata("version", "1.0") }
        }
    }

    @Test
    fun migrationBetweenPreflightAndMutationIsRejectedOnTheOwnedConnection() {
        openCatalog(isolated.jdbcUrl, isolated.dataDir.toString()).use { catalog ->
            val before = catalog.currentSnapshotId
            val table = catalog.getTable("test_schema", "simple_table", before)!!
            val column = catalog.getTableColumns(table.tableId, before).first().columnId
            catalog.beforeWriteTransactionAction = Runnable { setMetadata("version", "1.1-dev1") }
            try {
                assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment(column))) }
                    .isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
                assertThat(catalog.currentSnapshotId).isEqualTo(before)
            } finally {
                catalog.beforeWriteTransactionAction = Runnable {}
                setMetadata("version", "1.0")
            }
        }
    }

    private fun setMetadata(key: String, value: String) {
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { connection ->
            connection.prepareStatement("UPDATE ducklake_metadata SET value = ? WHERE key = ? AND scope IS NULL").use {
                it.setString(1, value)
                it.setString(2, key)
                it.executeUpdate()
            }
        }
    }

    @Test
    fun expectedMissingInlineTablesDoNotAbortThePinnedReadSession() {
        openCatalog(isolated.jdbcUrl, isolated.dataDir.toString()).use { catalog ->
            val table = catalog.getTable("test_schema", "simple_table", catalog.currentSnapshotId)!!
            catalog.readSession(java.util.function.Supplier {
                val snapshot = catalog.currentSnapshotId
                assertThat(catalog.hasInlinedRows(table.tableId, Long.MAX_VALUE, snapshot)).isFalse()
                assertThat(catalog.countInlinedRows(table.tableId, Long.MAX_VALUE, snapshot)).isZero()
                assertThat(catalog.hasInlinedDeletes(table.tableId + 1000000, snapshot)).isFalse()
                assertThat(catalog.getInlinedDeletes(table.tableId + 1000000, snapshot)).isEmpty()
                assertThat(catalog.getInlinedFileDeletesBetween(table.tableId + 1000000, 0, snapshot)).isEmpty()
                assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
                Unit
            })
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
