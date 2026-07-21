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

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_DATA_FILE
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_FILE_COLUMN_STATS
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_TABLE_COLUMN_STATS
import dev.brikk.ducklake.catalog.testing.CatalogTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end coverage for the two swapped-min/max-stats fixes, both exercised over a
 * `DECIMAL(38,0)` column (128-bit, the type that surfaces the bugs):
 *
 *  - **#2 write merge.** `applyInsertFragments` aggregates per-file stats into the
 *    `ducklake_table_column_stats` cache. Stats are stored as text, so the previous lexical
 *    comparison mis-ordered numbers ("80" < "9", "100" < "80") and could persist min > max.
 *    These tests use values whose lexical order inverts their numeric order and assert the
 *    cached bounds are numerically correct.
 *  - **#1 read guard.** `findDataFileIdsInRange` must never prune on provably-corrupt bounds.
 *    We seed a `ducklake_file_column_stats` row with min > max (as DuckDB <= 1.5.4's 128-bit
 *    DECIMAL RETURN_STATS bug produced) and assert a file whose true range matches the predicate
 *    is retained — while a healthy, genuinely out-of-range file is still pruned.
 */
class TestJdbcDucklakeCatalogStatsSwapGuard {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private var catalog: JdbcDucklakeCatalog? = null
        private val tableSeq = AtomicInteger()

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "stats-swap-guard")

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
            catalog?.close()
            if (::server.isInitialized) {
                server.close()
            }
        }

        private fun openConnection(): Connection =
            DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password)
    }

    // -- #2: write-path merge is numeric, not lexical -----------------------------------------

    @Test
    fun firstInsertAggregatesFragmentsNumerically() {
        // Two single-value files. Lexically "80" < "9" (so a lexical merge would store min="80",
        // max="9" — swapped); numerically the range is [9, 80].
        val (tableId, colId) = newDecimalTable()
        catalog!!.commitInsert(
            tableId,
            listOf(
                decimalFragment("swap/first_lo.parquet", "9", colId),
                decimalFragment("swap/first_hi.parquet", "80", colId),
            ),
        )
        assertCachedBounds(tableId, colId, expectedMin = "9", expectedMax = "80")
    }

    @Test
    fun incrementalInsertMergesIntoExistingRowNumerically() {
        // Seed the cache at [9, 80], then merge a file holding 100. Lexically "100" < "80" and
        // "100" < "9", so the old SQL merge kept max="80" and could push min to "100"; the numeric
        // merge must widen to [9, 100].
        val (tableId, colId) = newDecimalTable()
        catalog!!.commitInsert(
            tableId,
            listOf(
                decimalFragment("swap/inc_lo.parquet", "9", colId),
                decimalFragment("swap/inc_hi.parquet", "80", colId),
            ),
        )
        assertCachedBounds(tableId, colId, expectedMin = "9", expectedMax = "80")

        catalog!!.commitInsert(tableId, listOf(decimalFragment("swap/inc_top.parquet", "100", colId)))
        assertCachedBounds(tableId, colId, expectedMin = "9", expectedMax = "100")
    }

    // -- #1: read-path guard retains files with corrupt (swapped) bounds ----------------------

    @Test
    fun swappedFileStatsAreNotPruned() {
        val (tableId, colId) = newDecimalTable()
        catalog!!.commitInsert(
            tableId,
            listOf(
                decimalFragment("guard/in_range.parquet", "9", "100", colId), // true range 9..100
                decimalFragment("guard/five.parquet", "5", colId), // single value 5
            ),
        )
        val snapshotId = catalog!!.currentSnapshotId
        val inRangeFileId = fileIdForPath(tableId, "guard/in_range.parquet")
        val fiveFileId = fileIdForPath(tableId, "guard/five.parquet")

        // Corrupt the in-range file exactly as DuckDB <= 1.5.4 could: swap min and max.
        overwriteFileStat(tableId, colId, inRangeFileId, min = "100", max = "9")

        // amount = 50 lies within the file's TRUE range [9, 100]. With swapped stats a naive
        // overlap test concludes "no overlap" and drops the file; the guard must retain it.
        val matches = catalog!!.findDataFileIdsInRange(
            tableId,
            snapshotId,
            ColumnRangePredicate(colId, "50", "50"),
        )
        assertThat(matches)
            .`as`("file with swapped (corrupt) bounds must be retained, not pruned")
            .contains(inRangeFileId)
        assertThat(matches)
            .`as`("healthy, genuinely out-of-range file [5,5] must still be pruned for amount=50")
            .doesNotContain(fiveFileId)
    }

    @Test
    fun healthyBoundsStillPruneAfterGuard() {
        // Guard must not over-fire: a normal [9,100] file is pruned when the predicate misses it.
        val (tableId, colId) = newDecimalTable()
        catalog!!.commitInsert(tableId, listOf(decimalFragment("healthy/range.parquet", "9", "100", colId)))
        val snapshotId = catalog!!.currentSnapshotId
        val fileId = fileIdForPath(tableId, "healthy/range.parquet")

        assertThat(catalog!!.findDataFileIdsInRange(tableId, snapshotId, ColumnRangePredicate(colId, "9", "100")))
            .`as`("matching predicate retains the file")
            .contains(fileId)
        assertThat(catalog!!.findDataFileIdsInRange(tableId, snapshotId, ColumnRangePredicate(colId, "500", "500")))
            .`as`("out-of-range predicate still prunes healthy stats")
            .doesNotContain(fileId)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun newDecimalTable(): Pair<Long, Long> {
        val name = "amounts_${tableSeq.incrementAndGet()}"
        catalog!!.createTable(
            "test_schema",
            name,
            listOf(TableColumnSpec.leaf("amount", "decimal(38,0)", false)),
            null,
            null,
        )
        val snapshotId = catalog!!.currentSnapshotId
        val table = catalog!!.getTable("test_schema", name, snapshotId)!!
        val colId = catalog!!.getTableColumns(table.tableId, snapshotId)
            .first { it.columnName == "amount" }
            .columnId
        return table.tableId to colId
    }

    private fun decimalFragment(path: String, value: String, colId: Long): DucklakeWriteFragment =
        decimalFragment(path, value, value, colId)

    private fun decimalFragment(path: String, min: String, max: String, colId: Long): DucklakeWriteFragment {
        val stats = DucklakeFileColumnStats(
            colId,
            /* columnSizeBytes */ 32L,
            /* valueCount */ 1L,
            /* nullCount */ 0L,
            min,
            max,
            /* containsNan */ false,
        )
        return DucklakeWriteFragment(
            path,
            /* fileSizeBytes */ 1024L,
            /* footerSize */ 64L,
            /* recordCount */ 1L,
            listOf(stats),
        )
    }

    private fun assertCachedBounds(tableId: Long, colId: Long, expectedMin: String, expectedMax: String) {
        val tabcolst = DUCKLAKE_TABLE_COLUMN_STATS.`as`("tabcolst")
        openConnection().use { conn ->
            val row = CatalogTestSupport.dsl(conn)
                .select(tabcolst.MIN_VALUE, tabcolst.MAX_VALUE)
                .from(tabcolst)
                .where(tabcolst.TABLE_ID.eq(tableId))
                .and(tabcolst.COLUMN_ID.eq(colId))
                .fetchOne()!!
            assertThat(row.get(tabcolst.MIN_VALUE)).`as`("cached min_value").isEqualTo(expectedMin)
            assertThat(row.get(tabcolst.MAX_VALUE)).`as`("cached max_value").isEqualTo(expectedMax)
        }
    }

    private fun fileIdForPath(tableId: Long, path: String): Long {
        val file = DUCKLAKE_DATA_FILE.`as`("file")
        openConnection().use { conn ->
            return CatalogTestSupport.dsl(conn)
                .select(file.DATA_FILE_ID)
                .from(file)
                .where(file.TABLE_ID.eq(tableId))
                .and(file.PATH.eq(path))
                .fetchOne(file.DATA_FILE_ID)!!
        }
    }

    private fun overwriteFileStat(tableId: Long, colId: Long, dataFileId: Long, min: String, max: String) {
        val colstats = DUCKLAKE_FILE_COLUMN_STATS.`as`("colstats")
        openConnection().use { conn ->
            CatalogTestSupport.dsl(conn)
                .update(colstats)
                .set(colstats.MIN_VALUE, min)
                .set(colstats.MAX_VALUE, max)
                .where(colstats.TABLE_ID.eq(tableId))
                .and(colstats.COLUMN_ID.eq(colId))
                .and(colstats.DATA_FILE_ID.eq(dataFileId))
                .execute()
        }
    }
}
