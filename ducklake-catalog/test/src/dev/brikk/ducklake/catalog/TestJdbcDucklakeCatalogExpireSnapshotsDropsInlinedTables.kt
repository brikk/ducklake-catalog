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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * `expireSnapshots` GC of a fully-expired dropped table (TODO-rectify-from-eval.md C-B5, R-D5): the
 * metadata rows go in one transaction and the physical `ducklake_inlined_data_<t>_<sv>` /
 * `ducklake_inlined_delete_<t>` tables are DROPped afterwards — DDL cannot live inside the
 * transaction because MySQL commits implicitly on it. This test runs on PostgreSQL and proves the
 * post-commit drops actually take effect (they run on a connection that has just been switched
 * back to autocommit) and that both kinds of dynamic table are covered.
 */
class TestJdbcDucklakeCatalogExpireSnapshotsDropsInlinedTables {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "expire-inlined")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            }
            catalog = JdbcDucklakeCatalog(config)
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::catalog.isInitialized) {
                catalog.close()
            }
            if (::server.isInitialized) {
                server.close()
            }
        }
    }

    private fun physicalTables(prefix: String): List<String> =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE '$prefix%' ORDER BY 1",
                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun count(sql: String): Long =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

    @Test
    fun expiringADroppedTableRemovesItsMetadataAndDropsItsPhysicalInlinedTables() {
        // A table with an inlined-data table (small insert) AND an inlined-delete table (a small
        // DELETE against a flushed Parquet file with inlining on), then DROP it — all via DuckDB.
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { st ->
                st.execute("INSTALL ducklake")
                st.execute("LOAD ducklake")
                st.execute("INSTALL postgres")
                st.execute("LOAD postgres")
                st.execute(
                    "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                        isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
                )
                st.execute("CREATE TABLE lake.test_schema.gone (id INTEGER)")
                st.execute("INSERT INTO lake.test_schema.gone SELECT range FROM range(50)") // > limit -> parquet
                st.execute("DELETE FROM lake.test_schema.gone WHERE id = 7") // 1 row -> inlined delete
                st.execute("INSERT INTO lake.test_schema.gone VALUES (100), (101)") // < limit -> inlined data
            }
        }
        val liveSnapshot = catalog.currentSnapshotId
        val tableId = catalog.getTable("test_schema", "gone", liveSnapshot)!!.tableId
        val inlinedData = physicalTables("ducklake_inlined_data_${tableId}_")
        val inlinedDelete = physicalTables("ducklake_inlined_delete_$tableId")
        assertThat(inlinedData).`as`("precondition: an inlined-data table exists").isNotEmpty()
        assertThat(inlinedDelete).`as`("precondition: an inlined-delete table exists").hasSize(1)
        assertThat(catalog.getInlinedDeletes(tableId, liveSnapshot)).isNotEmpty()

        catalog.dropTable("test_schema", "gone")
        val afterDrop = catalog.currentSnapshotId

        // Expire every snapshot but the latest: the dropped table is then fully dead.
        val expirable = catalog.listExpirableSnapshots(null, null)
        assertThat(expirable).doesNotContain(afterDrop).isNotEmpty()
        val result = catalog.expireSnapshots(expirable.toSet())
        assertThat(result.expiredSnapshotCount).isEqualTo(expirable.size)
        assertThat(result.scheduledFileCount).`as`("the table's parquet file was scheduled for deletion").isGreaterThan(0)

        // Metadata rows gone ...
        assertThat(count("SELECT count(*) FROM ducklake_table WHERE table_id = $tableId")).isZero()
        assertThat(count("SELECT count(*) FROM ducklake_inlined_data_tables WHERE table_id = $tableId")).isZero()
        assertThat(count("SELECT count(*) FROM ducklake_data_file WHERE table_id = $tableId")).isZero()
        // ... and the physical dynamic tables were dropped AFTER the commit (and actually stuck).
        assertThat(physicalTables("ducklake_inlined_data_${tableId}_")).isEmpty()
        assertThat(physicalTables("ducklake_inlined_delete_$tableId")).isEmpty()
        // The rest of the catalog is intact.
        assertThat(catalog.getTable("test_schema", "simple_table", catalog.currentSnapshotId)).isNotNull()
        assertThat(catalog.listFilesScheduledForDeletion(java.time.Instant.now().plusSeconds(60))).isNotEmpty()
    }
}
