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
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Pins the read-side contract for delete files (TODO-rectify-from-eval.md R-B1): a reader must
 * apply `_ducklake_internal_snapshot_id <= S` to EVERY delete file that carries the column, and
 * must not use `ducklake_delete_file.partial_max` as the gate.
 *
 * The spec fact this rests on is demonstrated with stock DuckDB: `flush_inlined_data` writes a
 * delete file whose embedded snapshot ids span several snapshots while `partial_max` stays NULL.
 * If upstream ever starts recording `partial_max` there, this test fails and the contract can be
 * revisited.
 */
class TestJdbcDucklakeCatalogDeleteFileSnapshotFilterContract {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "delete-snapshot-filter")
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

    /** Stock DuckDB with this catalog ATTACHed as `lake`, inlining small writes. */
    private fun <T> withDuckDb(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake")
                statement.execute("LOAD ducklake")
                statement.execute("INSTALL postgres")
                statement.execute("LOAD postgres")
                statement.execute(
                    "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                        isolated.dataDir.toAbsolutePath().toString().replace("'", "''") +
                        "', DATA_INLINING_ROW_LIMIT 100)",
                )
            }
            block(connection)
        }

    private fun resolve(tableDataPath: Path, file: DucklakeDataFile): Path =
        if (file.deleteFilePathIsRelative == true) tableDataPath.resolve(file.deleteFilePath!!) else Path.of(file.deleteFilePath!!)

    /** Distinct `_ducklake_internal_snapshot_id` values in [file]'s delete file, ascending. */
    private fun embeddedDeleteSnapshots(table: DucklakeTable, file: DucklakeDataFile, snapshot: Long): List<Long> {
        // Table paths are schema-relative (`<schema path>/<table path>/`).
        val schema = catalog.getSchema("test_schema", snapshot)!!
        val tableDir = isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(table.path)
        val deleteFile = resolve(tableDir, file)
        return DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT DISTINCT _ducklake_internal_snapshot_id FROM read_parquet('" +
                        deleteFile.toString().replace("'", "''") + "') ORDER BY 1",
                ).use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
            }
        }
    }

    @Test
    fun flushWrittenDeleteFileSpansSnapshotsWithNullPartialMax() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.inl (id INTEGER, v VARCHAR)")
                st.execute("INSERT INTO lake.test_schema.inl VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')")
                st.execute("DELETE FROM lake.test_schema.inl WHERE id = 1")
                st.execute("DELETE FROM lake.test_schema.inl WHERE id = 2")
                st.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'inl')")
            }
        }

        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "inl", snapshot)!!
        val files = catalog.getDataFiles(table.tableId, snapshot)
        assertThat(files).`as`("flush materialised exactly one data file").hasSize(1)
        val file = files.single()
        assertThat(file.deleteFilePath).`as`("flush also emitted a delete file for the two deletes").isNotNull()
        assertThat(file.deleteFileFormat?.lowercase()).isEqualTo("parquet")

        // The spec fact: multi-snapshot embedded ids, partial_max NULL, begin = MIN(embedded).
        val embedded = embeddedDeleteSnapshots(table, file, snapshot)
        assertThat(embedded).`as`("one deletion snapshot per DELETE statement").hasSize(2)
        assertThat(file.deleteFilePartialMax)
            .`as`("upstream leaves partial_max NULL on flush-written delete files — so it cannot be the filter gate")
            .isNull()

        // The delete file is ACTIVE at the earlier deletion snapshot (begin = MIN embedded), yet it
        // physically holds the later deletion too. A reader at that snapshot that does not filter
        // by _ducklake_internal_snapshot_id <= S applies the future delete and loses a row.
        val earlier = embedded.first()
        val later = embedded.last()
        val atEarlier = catalog.getDataFiles(table.tableId, earlier)
        assertThat(atEarlier).hasSize(1)
        assertThat(atEarlier.single().deleteFilePath).isEqualTo(file.deleteFilePath)
        assertThat(atEarlier.single().deleteFilePartialMax).isNull()

        // And DuckDB itself (the oracle) sees 3 rows at the earlier snapshot, 2 at the later.
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM lake.test_schema.inl AT (VERSION => $earlier)").use { rs ->
                    rs.next(); assertThat(rs.getLong(1)).isEqualTo(3)
                }
                st.executeQuery("SELECT count(*) FROM lake.test_schema.inl AT (VERSION => $later)").use { rs ->
                    rs.next(); assertThat(rs.getLong(1)).isEqualTo(2)
                }
            }
        }
    }

    @Test
    fun changeFeedOffersMultiSnapshotDeleteFileForEveryWindowItMayTouch() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.feed (id INTEGER, v VARCHAR)")
                st.execute("INSERT INTO lake.test_schema.feed VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')")
                st.execute("DELETE FROM lake.test_schema.feed WHERE id = 1")
                st.execute("DELETE FROM lake.test_schema.feed WHERE id = 2")
                st.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'feed')")
            }
        }
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "feed", snapshot)!!
        val file = catalog.getDataFiles(table.tableId, snapshot).single()
        val embedded = embeddedDeleteSnapshots(table, file, snapshot)
        assertThat(embedded).hasSize(2)
        val sA = embedded[0]
        val sB = embedded[1]

        // Window at the LATER deletion: the single delete file began at sA (< start) yet holds
        // sB's deletion — it must be offered, with previous == itself (2-column self-cancel /
        // 3-column per-row windowing).
        val atLater = catalog.getDeletionsBetween(table.tableId, sB, sB).filter { !it.fullFileDelete }
        assertThat(atLater).hasSize(1)
        with(atLater.single()) {
            assertThat(snapshotId).`as`("reported at the file's begin_snapshot, which is BEFORE the window").isEqualTo(sA)
            assertThat(currentDeletePath).isEqualTo(file.deleteFilePath)
            assertThat(previousDeletePath).`as`("began before the window -> previous is the file itself").isEqualTo(currentDeletePath)
            assertThat(currentDeletePartialMax).isNull()
        }
        // Window at the EARLIER deletion: begin in window, no predecessor.
        val atEarlier = catalog.getDeletionsBetween(table.tableId, sA, sA).filter { !it.fullFileDelete }
        assertThat(atEarlier).hasSize(1)
        assertThat(atEarlier.single().snapshotId).isEqualTo(sA)
        assertThat(atEarlier.single().previousDeletePath).isNull()
        // A window entirely before the first deletion sees nothing.
        assertThat(catalog.getDeletionsBetween(table.tableId, sA - 1, sA - 1).filter { !it.fullFileDelete }).isEmpty()
    }

    @Test
    fun changeFeedPrunesOnlyWhatTheCatalogCanProve() {
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "simple_table", snapshot)!!
        val dataFile = catalog.getDataFiles(table.tableId, snapshot).first()
        val ids = listOf(888801L, 888802L, 888803L)
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st ->
                fun row(id: Long, end: String, partialMax: String) =
                    "($id, ${table.tableId}, 1, $end, ${dataFile.dataFileId}, 'd-$id.parquet', true, 'parquet', 1, 10, 4, NULL, $partialMax)"
                st.executeUpdate(
                    "INSERT INTO ducklake_delete_file (delete_file_id, table_id, begin_snapshot, end_snapshot, " +
                        "data_file_id, path, path_is_relative, format, delete_count, file_size_bytes, footer_size, " +
                        "encryption_key, partial_max) VALUES " +
                        row(ids[0], "2", "NULL") + ", " + // retired before the window -> pruned
                        row(ids[1], "NULL", "2") + ", " + // recorded max before the window -> pruned
                        row(ids[2], "NULL", "NULL"), // unknown span -> offered
                )
            }
        }
        try {
            val offered = catalog.getDeletionsBetween(table.tableId, 10, 10)
                .filter { !it.fullFileDelete && it.currentDeletePath?.startsWith("d-8888") == true }
            assertThat(offered.map { it.currentDeletePath }).containsExactly("d-${ids[2]}.parquet")
            assertThat(offered.single().previousDeletePath).isEqualTo("d-${ids[2]}.parquet")
        }
        finally {
            DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("DELETE FROM ducklake_delete_file WHERE delete_file_id IN (${ids.joinToString()})")
                }
            }
        }
    }

    @Test
    fun unknownFormatGateIgnoresPartialMax() {
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "simple_table", snapshot)!!
        val dataFile = catalog.getDataFiles(table.tableId, snapshot).first()
        assertThat(catalog.hasPartialDeleteFilesRequiringSnapshotFilter(table.tableId, snapshot)).isFalse()

        // A foreign-format delete file with partial_max NULL must still trip the gate: NULL does
        // not mean "no deletions newer than S".
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO ducklake_delete_file (delete_file_id, table_id, begin_snapshot, end_snapshot, " +
                        "data_file_id, path, path_is_relative, format, delete_count, file_size_bytes, footer_size, " +
                        "encryption_key, partial_max) VALUES (999999, ${table.tableId}, $snapshot, NULL, " +
                        "${dataFile.dataFileId}, 'ducklake-deletes-foreign.bin', true, 'orc', 1, 10, NULL, NULL, NULL)",
                )
            }
        }
        try {
            assertThat(catalog.hasPartialDeleteFilesRequiringSnapshotFilter(table.tableId, snapshot)).isTrue()
        }
        finally {
            DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
                c.createStatement().use { st -> st.executeUpdate("DELETE FROM ducklake_delete_file WHERE delete_file_id = 999999") }
            }
        }
        assertThat(catalog.hasPartialDeleteFilesRequiringSnapshotFilter(table.tableId, snapshot)).isFalse()
    }
}
