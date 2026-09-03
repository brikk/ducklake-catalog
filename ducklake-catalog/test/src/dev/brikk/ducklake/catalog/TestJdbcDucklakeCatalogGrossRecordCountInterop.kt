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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * `ducklake_table_stats.record_count` is the GROSS row count (TODO-rectify-from-eval.md W-B2).
 * DuckDB folds `SELECT MIN(c)` / `MAX(c)` to the cached `ducklake_table_column_stats` bounds when
 * `record_count == live row count` — its proof that no row was ever deleted and the bounds are
 * still exact. A catalog that decrements `record_count` on DELETE keeps that equality true after
 * the row holding a bound is gone, and DuckDB returns the stale bound.
 *
 * Oracle: stock DuckDB, on a table it created itself, after a DELETE committed by this library.
 */
class TestJdbcDucklakeCatalogGrossRecordCountInterop {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "gross-record-count")
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

    private fun <T> withDuckDb(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake")
                statement.execute("LOAD ducklake")
                statement.execute("INSTALL postgres")
                statement.execute("LOAD postgres")
                statement.execute(
                    "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                        isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
                )
            }
            block(connection)
        }

    private fun q(sql: String): List<Long> =
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    (1..rs.metaData.columnCount).map { rs.getLong(it) }
                }
            }
        }

    private fun tableDir(schema: DucklakeSchema, table: DucklakeTable): Path =
        isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(table.path)

    @Test
    fun duckDbReturnsTrueMaxAfterLibraryDeleteOfTheMaxRow() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.mx (id INTEGER, v VARCHAR)")
                st.execute("INSERT INTO lake.test_schema.mx VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd'), (5, 'e')")
                // Small inserts are inlined by default (data_inlining_row_limit = 10); flush so the
                // rows live in a Parquet file we can write a positional delete against.
                st.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'mx')")
            }
        }
        val snapshot = catalog.currentSnapshotId
        val schema = catalog.getSchema("test_schema", snapshot)!!
        val table = catalog.getTable("test_schema", "mx", snapshot)!!
        val file = catalog.getDataFiles(table.tableId, snapshot).single()
        assertThat(catalog.getTableStats(table.tableId)!!.recordCount).isEqualTo(5L)
        assertThat(q("SELECT max(id), count(*) FROM lake.test_schema.mx")).containsExactly(5L, 5L)

        // Write a positional delete file for the row holding MAX (id = 5 is at position 4) with
        // DuckDB itself, then register it through the library — exactly what an engine DELETE does.
        val dir = tableDir(schema, table)
        val dataFilePath = dir.resolve(file.path).toAbsolutePath().toString()
        val deleteName = "ducklake-delete-max-row.parquet"
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "COPY (SELECT '" + dataFilePath.replace("'", "''") + "' AS file_path, 4::BIGINT AS pos) TO '" +
                        dir.resolve(deleteName).toString().replace("'", "''") + "' (FORMAT PARQUET)",
                )
            }
        }
        val deleteSize = Files.size(dir.resolve(deleteName))
        catalog.commitDelete(
            table.tableId,
            listOf(DucklakeDeleteFragment(file.dataFileId, deleteName, 1L, deleteSize, 0L, 1L)),
            snapshot,
        )

        // Gross count untouched; live count derived.
        assertThat(catalog.getTableStats(table.tableId)!!.recordCount).`as`("gross record_count").isEqualTo(5L)
        assertThat(catalog.getLiveRowCount(table.tableId, catalog.currentSnapshotId)).`as`("live rows").isEqualTo(4L)

        // The oracle: with record_count (5) != live (4) DuckDB must NOT trust the cached max (5).
        assertThat(q("SELECT max(id), count(*) FROM lake.test_schema.mx"))
            .`as`("DuckDB computes MAX from the surviving rows, not the stale cached bound")
            .containsExactly(4L, 4L)

        // ANALYZE keeps gross semantics (and rebuilds the column bounds from per-file stats, which
        // still say max = 5 — correct as a BOUND; exactness is what record_count != live denies).
        catalog.analyzeTable(table.tableId)
        assertThat(catalog.getTableStats(table.tableId)!!.recordCount).isEqualTo(5L)
        assertThat(q("SELECT max(id), count(*) FROM lake.test_schema.mx")).containsExactly(4L, 4L)
    }

    @Test
    fun liveRowCountMatchesDuckDbAcrossDeletesAndTruncate() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.lrc (id INTEGER)")
                st.execute("INSERT INTO lake.test_schema.lrc SELECT range FROM range(100)")
                st.execute("INSERT INTO lake.test_schema.lrc SELECT range FROM range(100, 150)")
                st.execute("DELETE FROM lake.test_schema.lrc WHERE id % 10 = 0")
            }
        }
        var snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "lrc", snapshot)!!
        assertThat(catalog.getTableStats(table.tableId)!!.recordCount).`as`("gross").isEqualTo(150L)
        assertThat(catalog.getLiveRowCount(table.tableId, snapshot))
            .isEqualTo(q("SELECT count(*) FROM lake.test_schema.lrc").single())
            .isEqualTo(135L)

        // A second delete on an already-deleted-from file (DuckDB consolidates the delete file).
        withDuckDb { c -> c.createStatement().use { st -> st.execute("DELETE FROM lake.test_schema.lrc WHERE id < 5") } }
        snapshot = catalog.currentSnapshotId
        assertThat(catalog.getLiveRowCount(table.tableId, snapshot))
            .isEqualTo(q("SELECT count(*) FROM lake.test_schema.lrc").single())
            .isEqualTo(131L) // 1..4 (0 was already gone)
        // Time travel: the earlier snapshot still reports 135.
        assertThat(catalog.getLiveRowCount(table.tableId, snapshot - 1)).isEqualTo(135L)

        // TRUNCATE end-snapshots the data files; their delete files must not be double-subtracted.
        catalog.truncateTable("test_schema", "lrc")
        assertThat(catalog.getLiveRowCount(table.tableId, catalog.currentSnapshotId)).isEqualTo(0L)
        assertThat(q("SELECT count(*) FROM lake.test_schema.lrc").single()).isEqualTo(0L)
    }
}
