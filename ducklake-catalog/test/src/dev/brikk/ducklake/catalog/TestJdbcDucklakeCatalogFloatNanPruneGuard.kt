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
import dev.brikk.ducklake.catalog.testing.CatalogTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-path guard for NaN-invalidated float maxima ("float-nan-prune-guard").
 *
 * DuckLake/Parquet float `min`/`max` statistics **exclude** `NaN`, and `NaN` sorts **above**
 * every non-NaN value. So a `FLOAT`/`DOUBLE` file whose `contains_nan` is not explicitly `FALSE`
 * has a stored `max` that is NOT its true upper bound: the file may hold `NaN` rows above it.
 * `findDataFileIdsInRange` must therefore NOT prune such a file on a `col > M` / `col >= M`
 * predicate (the max side), even when `M` is above the recorded `max`.
 *
 * This mirrors upstream DuckLake, which appends `OR contains_nan` to the pushed-down `> / >=`
 * filter (`src/storage/ducklake_metadata_manager.cpp`), and `datafusion-ducklake` #203.
 *
 * The `min` side is unaffected — `NaN` is never *below* a value — so a `col < m` predicate that
 * misses the stored `min` must still prune, `contains_nan` notwithstanding.
 *
 * The column type is the canonical DuckLake `float64` (== SQL `DOUBLE`); `contains_nan` is
 * overwritten directly on the `ducklake_file_column_stats` row to exercise all three states
 * (`TRUE`, `FALSE`, and unknown/`NULL`) independently of the write path.
 */
class TestJdbcDucklakeCatalogFloatNanPruneGuard {
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
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "float-nan-prune-guard")

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

    // -- max side: NaN invalidates the stored max ---------------------------------------------

    @Test
    fun floatFileWithNanIsRetainedOnAboveMaxPredicate() {
        // File's recorded range is [1.0, 10.0]; contains_nan = TRUE means the true max is above
        // 10.0 (NaN sorts above everything). A `col >= 20.0` predicate lies above the recorded
        // max, so a naive overlap test would prune — but the file may hold NaN, so it MUST stay.
        val (tableId, colId, fileId) = seedSingleDoubleFile("1.0", "10.0")
        setContainsNan(tableId, colId, fileId, true)

        val matches = catalog!!.findDataFileIdsInRange(
            tableId,
            catalog!!.currentSnapshotId,
            ColumnRangePredicate(colId, "20.0", null),
        )
        assertThat(matches)
            .`as`("float file with contains_nan = TRUE must not be pruned on the max side")
            .contains(fileId)
    }

    @Test
    fun floatFileWithUnknownNanIsRetainedOnAboveMaxPredicate() {
        // contains_nan = NULL (unknown — the default the write path stores when not-NaN). Unknown
        // must fail open on the max side, exactly like TRUE, since we cannot prove the max is real.
        val (tableId, colId, fileId) = seedSingleDoubleFile("1.0", "10.0")
        setContainsNan(tableId, colId, fileId, null)

        val matches = catalog!!.findDataFileIdsInRange(
            tableId,
            catalog!!.currentSnapshotId,
            ColumnRangePredicate(colId, "20.0", null),
        )
        assertThat(matches)
            .`as`("float file with contains_nan = NULL (unknown) must not be pruned on the max side")
            .contains(fileId)
    }

    @Test
    fun floatFileWithoutNanStillPrunesOnAboveMaxPredicate() {
        // contains_nan = FALSE proves the recorded max (10.0) is the true upper bound, so a
        // `col >= 20.0` predicate cannot match: the file MUST still be pruned (no lost selectivity).
        val (tableId, colId, fileId) = seedSingleDoubleFile("1.0", "10.0")
        setContainsNan(tableId, colId, fileId, false)

        val matches = catalog!!.findDataFileIdsInRange(
            tableId,
            catalog!!.currentSnapshotId,
            ColumnRangePredicate(colId, "20.0", null),
        )
        assertThat(matches)
            .`as`("float file with contains_nan = FALSE keeps full max-side pruning power")
            .doesNotContain(fileId)
    }

    // -- min side: NaN never invalidates the stored min ---------------------------------------

    @Test
    fun nanDoesNotDisableMinSidePruning() {
        // contains_nan = TRUE, but a `col <= 0.5` predicate is below the recorded min (1.0). NaN is
        // above all values, never below, so the min bound is still valid: the file MUST be pruned.
        val (tableId, colId, fileId) = seedSingleDoubleFile("1.0", "10.0")
        setContainsNan(tableId, colId, fileId, true)

        val matches = catalog!!.findDataFileIdsInRange(
            tableId,
            catalog!!.currentSnapshotId,
            ColumnRangePredicate(colId, null, "0.5"),
        )
        assertThat(matches)
            .`as`("contains_nan must not disable the min-side prune (NaN is never below the min)")
            .doesNotContain(fileId)
    }

    // -- helpers ------------------------------------------------------------------------------

    /** Creates a one-column `float64` table, inserts a single file spanning [min, max], returns ids. */
    private fun seedSingleDoubleFile(min: String, max: String): Triple<Long, Long, Long> {
        val name = "measurements_${tableSeq.incrementAndGet()}"
        catalog!!.createTable(
            "test_schema",
            name,
            listOf(TableColumnSpec.leaf("v", "float64", false)),
            null,
            null,
        )
        val snapshotId = catalog!!.currentSnapshotId
        val table = catalog!!.getTable("test_schema", name, snapshotId)!!
        val colId = catalog!!.getTableColumns(table.tableId, snapshotId)
            .first { it.columnName == "v" }
            .columnId

        val stats = DucklakeFileColumnStats(
            colId,
            /* columnSizeBytes */ 16L,
            /* valueCount */ 2L,
            /* nullCount */ 0L,
            min,
            max,
            /* containsNan */ false,
        )
        catalog!!.commitInsert(
            table.tableId,
            listOf(DucklakeWriteFragment("nan/file.parquet", 1024L, 64L, 2L, listOf(stats))),
        )
        val fileId = fileIdForPath(table.tableId, "nan/file.parquet")
        return Triple(table.tableId, colId, fileId)
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

    private fun setContainsNan(tableId: Long, colId: Long, dataFileId: Long, value: Boolean?) {
        val colstats = DUCKLAKE_FILE_COLUMN_STATS.`as`("colstats")
        openConnection().use { conn ->
            CatalogTestSupport.dsl(conn)
                .update(colstats)
                .set(colstats.CONTAINS_NAN, value)
                .where(colstats.TABLE_ID.eq(tableId))
                .and(colstats.COLUMN_ID.eq(colId))
                .and(colstats.DATA_FILE_ID.eq(dataFileId))
                .execute()
        }
    }
}
