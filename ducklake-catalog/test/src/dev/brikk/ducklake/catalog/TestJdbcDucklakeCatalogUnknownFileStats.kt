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
import java.sql.Connection
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
        ) { rs -> if (rs.next()) (1..4).map { rs.getString(it) } else emptyList() }

    private fun <T> withOracle(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
                statement.execute("ATTACH '${isolated.duckDbAttachUri.replace("'", "''")}' AS lake " +
                    "(DATA_PATH '${isolated.dataDir.toAbsolutePath()}')")
            }
            block(connection)
        }

    private fun oracleLongs(query: String): List<Long> = withOracle { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { result ->
                generateSequence { if (result.next()) result.getLong(1) else null }.toList()
            }
        }
    }

    private fun knownX(cols: Cols, value: Int?) = DucklakeFileColumnStats(
        cols.x, 8L, if (value == null) 0L else 1L, if (value == null) 1L else 0L,
        value?.toString(), value?.toString(), false,
    )

    private fun actualFragment(
        cols: Cols,
        name: String,
        value: Int?,
        xStats: DucklakeFileColumnStats?,
        empty: Boolean = false,
    ): DucklakeWriteFragment {
        val c = catalog!!
        val snapshot = c.currentSnapshotId
        val table = c.getTable("test_schema", cols.tableName, snapshot)!!
        val schema = c.getSchema("test_schema", snapshot)!!
        val path = isolated.dataDir.resolve(schema.path).resolve(table.path).resolve(name)
        Files.createDirectories(path.parent)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY (SELECT ${value ?: "NULL"}::DOUBLE AS x, 'v' AS s${if (empty) " WHERE FALSE" else ""}) " +
                    "TO '${path.toAbsolutePath().toString().replace("'", "''")}' " +
                    "(FORMAT PARQUET, FIELD_IDS {x: ${cols.x}, s: ${cols.s}})")
            }
        }
        val count = if (empty) 0L else 1L
        return DucklakeWriteFragment(name, Files.size(path), 0L, count,
            listOfNotNull(xStats, DucklakeFileColumnStats(cols.s, count, count, 0L, "v", "v", false)))
    }

    private fun assertUnknownX(cols: Cols, values: List<Long>, unknownCounts: Boolean) {
        val table = "lake.test_schema.${cols.tableName}"
        assertThat(oracleLongs("SELECT x FROM $table ORDER BY x")).containsExactlyElementsOf(values.sorted())
        assertThat(oracleLongs("SELECT max(x) FROM $table")).containsExactly(values.max())
        assertThat(oracleLongs("SELECT min(x) FROM $table")).containsExactly(values.min())
        assertThat(tableStats(cols.tableId, cols.x)).`as`("unsafe global row must be absent, not reloadable as empty").isEmpty()
        val stats = catalog!!.getColumnStats(cols.tableId, catalog!!.currentSnapshotId).single { it.columnId == cols.x }
        assertThat(stats.minValue).isNull()
        assertThat(stats.maxValue).isNull()
        if (unknownCounts) {
            assertThat(stats.totalValueCount).isNull()
            assertThat(stats.totalNullCount).isNull()
        }
        else {
            assertThat(stats.totalValueCount).isEqualTo(values.size.toLong())
            assertThat(stats.totalNullCount).isZero()
        }
    }

    @Test
    fun unknownBoundsNeverRecoverFromLaterKnownFiles() {
        assertUnknownContributions(missingRow = false)
    }

    @Test
    fun missingStatisticsRowsNeverRecoverFromLaterKnownFiles() {
        assertUnknownContributions(missingRow = true)
    }

    @Test
    fun unknownCountsAndBoundsAreNotAllNull() {
        assertUnknownContributions(missingRow = false, unknownCounts = true)
    }

    private fun assertUnknownContributions(missingRow: Boolean, unknownCounts: Boolean = false) {
        for (unknownFirst in listOf(false, true)) {
            for (sameBatch in listOf(false, true)) {
                val cols = newTable()
                val c = catalog!!
                val known = actualFragment(cols, "known.parquet", 1, knownX(cols, 1))
                val unknownStats = if (missingRow) null else knownX(cols, 100).copy(
                    minValue = null, maxValue = null,
                    valueCount = if (unknownCounts) null else 1L, nullCount = if (unknownCounts) null else 0L,
                )
                val unknown = actualFragment(cols, "unknown.parquet", 100, unknownStats)
                val fragments = if (unknownFirst) listOf(unknown, known) else listOf(known, unknown)
                if (sameBatch) c.commitInsert(cols.tableId, fragments)
                else fragments.forEach { c.commitInsert(cols.tableId, listOf(it)) }
                assertUnknownX(cols, listOf(1L, 100L), missingRow || unknownCounts)

                c.commitInsert(cols.tableId, listOf(actualFragment(cols, "later.parquet", 2, knownX(cols, 2))))
                assertUnknownX(cols, listOf(1L, 2L, 100L), missingRow || unknownCounts)
                c.analyzeTable(cols.tableId)
                assertUnknownX(cols, listOf(1L, 2L, 100L), missingRow || unknownCounts)
            }
        }
    }

    @Test
    fun aFileWithNoStatisticsRowsStillParticipatesInCoverage() {
        val cols = newTable()
        val c = catalog!!
        val unknown = actualFragment(cols, "no-stats.parquet", 100, null).copy(columnStats = emptyList())
        c.commitInsert(cols.tableId, listOf(unknown))
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "known.parquet", 1, knownX(cols, 1))))
        assertUnknownX(cols, listOf(1L, 100L), unknownCounts = true)
        assertThat(tableStats(cols.tableId, cols.s)).isEmpty()
        c.analyzeTable(cols.tableId)
        assertUnknownX(cols, listOf(1L, 100L), unknownCounts = true)
        assertThat(tableStats(cols.tableId, cols.s)).isEmpty()
    }

    @Test
    fun nulBoundsAreNormalizedBeforeGlobalInsertAndUpdate() {
        for (knownFirst in listOf(false, true)) {
            val cols = newTable()
            val c = catalog!!
            if (knownFirst) c.commitInsert(cols.tableId, listOf(knownFragment("known.parquet", cols)))
            val fragment = knownFragment("nul.parquet", cols)
            val nul = fragment.copy(columnStats = fragment.columnStats.map {
                if (it.columnId == cols.s) it.copy(minValue = "\u0000a", maxValue = "z\u0000") else it
            })
            c.commitInsert(cols.tableId, listOf(nul))
            assertThat(tableStats(cols.tableId, cols.s)).isEmpty()
            c.commitInsert(cols.tableId, listOf(knownFragment("later.parquet", cols)))
            assertThat(tableStats(cols.tableId, cols.s)).isEmpty()
            c.analyzeTable(cols.tableId)
            assertThat(tableStats(cols.tableId, cols.s)).isEmpty()
        }
    }

    @Test
    fun suppressedGlobalBoundsStayAbsentAfterNativeDuckDbInsert() {
        val cols = newTable()
        val c = catalog!!
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "known.parquet", 1, knownX(cols, 1))))
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "unknown.parquet", 100,
            knownX(cols, 100).copy(minValue = null, maxValue = null))))
        withOracle { connection ->
            connection.createStatement().use { it.execute("INSERT INTO lake.test_schema.${cols.tableName} VALUES (2, 'v')") }
        }
        assertThat(tableStats(cols.tableId, cols.x)).isEmpty()
        assertThat(oracleLongs("SELECT max(x) FROM lake.test_schema.${cols.tableName}")).containsExactly(100L)
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "later.parquet", 3, knownX(cols, 3))))
        assertThat(tableStats(cols.tableId, cols.x)).isEmpty()
        assertThat(oracleLongs("SELECT x FROM lake.test_schema.${cols.tableName} ORDER BY x")).containsExactly(1L, 2L, 3L, 100L)
        assertThat(oracleLongs("SELECT max(x) FROM lake.test_schema.${cols.tableName}")).containsExactly(100L)
    }

    @Test
    fun allNullAndEmptyFilesDoNotPoisonReadBounds() {
        for (nullFirst in listOf(false, true)) {
            val cols = newTable()
            val c = catalog!!
            val known = actualFragment(cols, "known.parquet", 1, knownX(cols, 1))
            val allNull = actualFragment(cols, "null.parquet", null, knownX(cols, null))
            (if (nullFirst) listOf(allNull, known) else listOf(known, allNull)).forEach { c.commitInsert(cols.tableId, listOf(it)) }
            c.commitInsert(cols.tableId, listOf(actualFragment(cols, "empty.parquet", null, null, empty = true)))
            val stats = c.getColumnStats(cols.tableId, c.currentSnapshotId).single { it.columnId == cols.x }
            assertThat(stats.minValue).isEqualTo("1")
            assertThat(stats.maxValue).isEqualTo("1")
            assertThat(stats.totalValueCount).isEqualTo(1L)
            assertThat(stats.totalNullCount).isEqualTo(1L)
            c.analyzeTable(cols.tableId)
            assertThat(tableStats(cols.tableId, cols.x)).containsExactly("true", "false", "1", "1")
            assertThat(oracleLongs("SELECT max(x) FROM lake.test_schema.${cols.tableName}")).containsExactly(1L)
        }
    }

    @Test
    fun analyzeDoesNotIgnoreInitialDefaultsInOlderFiles() {
        val cols = newTable()
        val c = catalog!!
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "old.parquet", 1, knownX(cols, 1))))
        withOracle { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE lake.test_schema.${cols.tableName} ADD COLUMN d INTEGER DEFAULT 100")
                statement.execute("INSERT INTO lake.test_schema.${cols.tableName} VALUES (2, 'v', 1)")
                statement.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => '${cols.tableName}')")
            }
        }
        val columnId = c.getTableColumns(cols.tableId, c.currentSnapshotId).single { it.columnName == "d" }.columnId
        assertThat(oracleLongs("SELECT d FROM lake.test_schema.${cols.tableName} ORDER BY d")).containsExactly(1L, 100L)

        c.analyzeTable(cols.tableId)

        assertThat(oracleLongs("SELECT max(d) FROM lake.test_schema.${cols.tableName}")).containsExactly(100L)
        assertThat(tableStats(cols.tableId, columnId)).isEmpty()
        val stats = c.getColumnStats(cols.tableId, c.currentSnapshotId).single { it.columnId == columnId }
        assertThat(stats.totalValueCount).isNull()
        assertThat(stats.minValue).isNull()
        assertThat(stats.maxValue).isNull()
    }

    @Test
    fun analyzeRestoresBoundsOnlyAfterUnknownFilesAreRetired() {
        val cols = newTable()
        val c = catalog!!
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "known.parquet", 1, knownX(cols, 1))))
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "unknown.parquet", 100, null)))
        val beforeTruncate = c.currentSnapshotId
        assertUnknownX(cols, listOf(1L, 100L), unknownCounts = true)
        assertThatThrownBy {
            withOracle { connection ->
                connection.createStatement().use { it.execute("ALTER TABLE lake.test_schema.${cols.tableName} ALTER COLUMN x SET NOT NULL") }
            }
        }.hasMessageContaining("no column stats are available")
        c.truncateTable("test_schema", cols.tableName)
        c.commitInsert(cols.tableId, listOf(actualFragment(cols, "new.parquet", 2, knownX(cols, 2))))
        c.analyzeTable(cols.tableId)
        assertThat(tableStats(cols.tableId, cols.x)).containsExactly("false", "false", "2", "2")
        assertThat(oracleLongs("SELECT max(x) FROM lake.test_schema.${cols.tableName}")).containsExactly(2L)
        val oldStats = c.getColumnStats(cols.tableId, beforeTruncate).single { it.columnId == cols.x }
        assertThat(oldStats.minValue).isNull()
        assertThat(oldStats.maxValue).isNull()
        withOracle { connection ->
            connection.createStatement().use { it.execute("ALTER TABLE lake.test_schema.${cols.tableName} ALTER COLUMN x SET NOT NULL") }
        }
    }

    @Test
    fun oneSidedGlobalBoundsAreSuppressed() {
        for (missingMin in listOf(false, true)) {
            val cols = newTable()
            val stats = knownX(cols, 100).let { if (missingMin) it.copy(minValue = null) else it.copy(maxValue = null) }
            catalog!!.commitInsert(cols.tableId, listOf(actualFragment(cols, "one-sided.parquet", 100, stats)))
            assertThat(tableStats(cols.tableId, cols.x)).isEmpty()
            assertThat(oracleLongs("SELECT min(x) FROM lake.test_schema.${cols.tableName}")).containsExactly(100L)
            assertThat(oracleLongs("SELECT max(x) FROM lake.test_schema.${cols.tableName}")).containsExactly(100L)
        }
    }

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
            .`as`("NaN is non-NULL data with unavailable bounds: do not retain or resurrect an incomplete global range")
            .isEmpty()
        assertThat(tableStats(cols.tableId, cols.s)).containsExactly("false", null, "a", "z")
        catalog!!.analyzeTable(cols.tableId)
        assertThat(tableStats(cols.tableId, cols.x)).`as`("rebuild from file rows agrees").isEmpty()
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
