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
import java.util.concurrent.atomic.AtomicInteger

/**
 * W-D4: a writer may not know `value_count` / `null_count` / `contains_nan` for a file. Upstream
 * (`DuckLakeColumnStatsInfo::FromColumnStats`) stores SQL NULL then, and `MergeStats` makes the
 * table-level `contains_null` / `contains_nan` unknown as long as such a file is active — a
 * later file with known statistics never "upgrades" the unknown back to a known value.
 */
class TestJdbcDucklakeCatalogUnknownFileStats {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private var catalog: JdbcDucklakeCatalog? = null
        private val tableSeq = AtomicInteger()

        @BeforeAll
        @JvmStatic
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "unknown-file-stats")
            catalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = isolated.jdbcUrl
                    catalogDatabaseUser = isolated.user
                    catalogDatabasePassword = isolated.password
                    dataPath = isolated.dataDir.toAbsolutePath().toString()
                    maxCatalogConnections = 5
                },
            )
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            catalog?.close()
            if (::server.isInitialized) {
                server.close()
            }
        }
    }

    private data class Cols(val tableId: Long, val x: Long, val s: Long, val tableName: String)

    private fun newTable(): Cols {
        val name = "t_${tableSeq.incrementAndGet()}"
        val c = catalog!!
        c.createTable(
            "test_schema", name,
            listOf(TableColumnSpec.leaf("x", "float64", true), TableColumnSpec.leaf("s", "varchar", true)),
            null, null,
        )
        val table = c.getTable("test_schema", name, c.currentSnapshotId)!!
        val cols = c.getTableColumns(table.tableId, c.currentSnapshotId).associateBy { it.columnName }
        return Cols(table.tableId, cols.getValue("x").columnId, cols.getValue("s").columnId, name)
    }

    private fun unknownFragment(path: String, cols: Cols) =
        DucklakeWriteFragment(
            path, 100L, 0L, 3L,
            listOf(
                DucklakeFileColumnStats(cols.x, 24L, null, null, "1.5", "2.5", null),
                DucklakeFileColumnStats(cols.s, 24L, null, null, "a", "b", null),
            ),
        )

    private fun knownFragment(path: String, cols: Cols) =
        DucklakeWriteFragment(
            path, 100L, 0L, 3L,
            listOf(
                DucklakeFileColumnStats(cols.x, 24L, 2L, 1L, "0.5", "3.5", false),
                DucklakeFileColumnStats(cols.s, 24L, 3L, 0L, "a", "c", false),
            ),
        )

    private fun <T> sql(query: String, read: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { conn ->
            conn.createStatement().use { st -> st.executeQuery(query).use(read) }
        }

    private fun fileStats(tableId: Long, columnId: Long): List<Triple<String?, String?, String?>> =
        sql(
            "SELECT fcs.value_count::text, fcs.null_count::text, fcs.contains_nan::text " +
                "FROM ducklake_file_column_stats fcs JOIN ducklake_data_file f USING (data_file_id) " +
                "WHERE fcs.table_id = $tableId AND fcs.column_id = $columnId ORDER BY f.path",
        ) { rs -> generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null }.toList() }

    /** `(contains_null, contains_nan, min, max)` of the table-level row, as text. */
    private fun tableStats(tableId: Long, columnId: Long): List<String?> =
        sql(
            "SELECT contains_null::text, contains_nan::text, min_value, max_value FROM ducklake_table_column_stats " +
                "WHERE table_id = $tableId AND column_id = $columnId",
        ) { rs -> rs.next(); (1..4).map { rs.getString(it) } }

    @Test
    fun unknownCountsAndNanAreStoredAsNullAndKeepBounds() {
        val cols = newTable()
        catalog!!.commitInsert(cols.tableId, listOf(unknownFragment("u/a.parquet", cols)))

        assertThat(fileStats(cols.tableId, cols.x)).containsExactly(Triple(null, null, null))
        assertThat(fileStats(cols.tableId, cols.s)).containsExactly(Triple(null, null, null))
        assertThat(tableStats(cols.tableId, cols.x))
            .`as`("float: contains_null and contains_nan unknown, bounds known")
            .containsExactly(null, null, "1.5", "2.5")
        assertThat(tableStats(cols.tableId, cols.s)).containsExactly(null, null, "a", "b")

        val stats = catalog!!.getColumnStats(cols.tableId, catalog!!.currentSnapshotId).associateBy { it.columnId }
        assertThat(stats.getValue(cols.x).totalValueCount).isNull()
        assertThat(stats.getValue(cols.x).totalNullCount).isNull()
        assertThat(stats.getValue(cols.x).totalSizeBytes).isEqualTo(24L)
    }

    @Test
    fun aKnownFileNeverUpgradesAnUnknownTableFlag() {
        val cols = newTable()
        catalog!!.commitInsert(cols.tableId, listOf(unknownFragment("uk/a.parquet", cols)))
        catalog!!.commitInsert(cols.tableId, listOf(knownFragment("uk/b.parquet", cols)))

        assertThat(fileStats(cols.tableId, cols.x)).containsExactly(Triple(null, null, null), Triple("2", "1", "false"))
        assertThat(tableStats(cols.tableId, cols.x))
            .`as`("UPDATE path: the unknown file is still active, so the flags stay NULL; bounds merge")
            .containsExactly(null, null, "0.5", "3.5")
        assertThat(tableStats(cols.tableId, cols.s)).containsExactly(null, null, "a", "c")

        catalog!!.analyzeTable(cols.tableId)
        assertThat(tableStats(cols.tableId, cols.x)).`as`("rebuild from files agrees").containsExactly(null, null, "0.5", "3.5")

        val stats = catalog!!.getColumnStats(cols.tableId, catalog!!.currentSnapshotId).associateBy { it.columnId }
        assertThat(stats.getValue(cols.x).totalNullCount).`as`("any unknown file → unknown total").isNull()
        assertThat(stats.getValue(cols.x).totalSizeBytes).isEqualTo(48L)
    }

    @Test
    fun anUnknownFileMakesAKnownTableFlagUnknown() {
        val cols = newTable()
        catalog!!.commitInsert(cols.tableId, listOf(knownFragment("ku/a.parquet", cols)))
        assertThat(tableStats(cols.tableId, cols.x)).containsExactly("true", "false", "0.5", "3.5")
        assertThat(tableStats(cols.tableId, cols.s)).containsExactly("false", null, "a", "c")

        catalog!!.commitInsert(cols.tableId, listOf(unknownFragment("ku/b.parquet", cols)))
        assertThat(tableStats(cols.tableId, cols.x))
            .`as`("UPDATE path: known TRUE/FALSE become NULL once a file lacks the statistic")
            .containsExactly(null, null, "0.5", "3.5")
        assertThat(tableStats(cols.tableId, cols.s)).containsExactly(null, null, "a", "c")
    }

    @Test
    fun knownFilesOnlyKeepExplicitFlagsAcrossInsertsAndAnalyze() {
        val cols = newTable()
        catalog!!.commitInsert(cols.tableId, listOf(knownFragment("kk/a.parquet", cols)))
        catalog!!.commitInsert(
            cols.tableId,
            listOf(
                DucklakeWriteFragment(
                    "kk/b.parquet", 100L, 0L, 1L,
                    listOf(
                        DucklakeFileColumnStats(cols.x, 8L, 1L, 0L, "9.0", "9.0", true),
                        DucklakeFileColumnStats(cols.s, 8L, 1L, 0L, "z", "z", false),
                    ),
                ),
            ),
        )
        assertThat(tableStats(cols.tableId, cols.x))
            .`as`(
                "NaN in a later file: contains_nan TRUE; the file has no bounds so it is skipped by the merge " +
                    "(upstream MergeStats !AnyValid) — its NaN-excluding 9.0 must NOT become the table max",
            )
            .containsExactly("true", "true", "0.5", "3.5")
        assertThat(tableStats(cols.tableId, cols.s)).containsExactly("false", null, "a", "z")
        catalog!!.analyzeTable(cols.tableId)
        assertThat(tableStats(cols.tableId, cols.x)).`as`("rebuild from file rows agrees").containsExactly("true", "true", "0.5", "3.5")
        assertThat(tableStats(cols.tableId, cols.s)).containsExactly("false", null, "a", "z")
    }

    @Test
    fun duckDbReadsATableWhoseFileStatsAreUnknown() {
        val cols = newTable()
        catalog!!.commitInsert(cols.tableId, listOf(unknownFragment("ddb/a.parquet", cols)))
        val tableName = cols.tableName
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { st ->
                st.execute("INSTALL ducklake")
                st.execute("LOAD ducklake")
                st.execute("INSTALL postgres")
                st.execute("LOAD postgres")
                st.execute(
                    "ATTACH '${isolated.duckDbAttachUri.replace("'", "''")}' AS lake " +
                        "(DATA_PATH '${isolated.dataDir.toAbsolutePath()}')",
                )
                // The data file does not exist, so no scan — but the metadata (stats included) must load.
                st.executeQuery(
                    "SELECT file_count, file_size_bytes FROM ducklake_table_info('lake') WHERE table_name = '$tableName'",
                ).use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isEqualTo(1L)
                    assertThat(rs.getLong(2)).isEqualTo(100L)
                }
                st.executeQuery("SELECT count(*) FROM ducklake_list_files('lake', '$tableName', schema => 'test_schema')")
                    .use { rs -> rs.next(); assertThat(rs.getLong(1)).isEqualTo(1L) }
            }
        }
    }
}
