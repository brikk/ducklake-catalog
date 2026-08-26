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
 * Write-side `contains_nan` convention, mirroring upstream DuckLake
 * (`ducklake_transaction_state.cpp`: `has_contains_nan == is_float`).
 *
 *  - FLOAT/DOUBLE column: persist the explicit boolean. Writing explicit **FALSE** (not SQL NULL)
 *    is what lets a reader keep max-side range pruning — an unknown/NULL `contains_nan` forces the
 *    NaN prune guard to fail open and disables float max pruning entirely.
 *  - FLOAT/DOUBLE column that DOES contain NaN: `contains_nan = TRUE` and min/max are dropped to
 *    SQL NULL (a NaN-excluding max is not a true upper bound, so no misleading finite value is
 *    stored — matches upstream not recording float min/max when NaN is present).
 *  - non-float column: `contains_nan` is not applicable → SQL NULL.
 */
class TestJdbcDucklakeCatalogContainsNanWrite {
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
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "contains-nan-write")

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

    @Test
    fun floatWithoutNanStoresExplicitFalseAndKeepsBounds() {
        val (tableId, colId, fileId) = seedFile("float64", min = "1.0", max = "10.0", containsNan = false)
        val row = statsOf(tableId, colId, fileId)
        assertThat(row.containsNan)
            .`as`("float column without NaN must store explicit FALSE (enables max-side pruning), not NULL")
            .isEqualTo(false)
        assertThat(row.min).`as`("min preserved for non-NaN float").isEqualTo("1.0")
        assertThat(row.max).`as`("max preserved for non-NaN float").isEqualTo("10.0")
    }

    @Test
    fun floatWithNanStoresTrueAndDropsBounds() {
        val (tableId, colId, fileId) = seedFile("float64", min = "1.0", max = "10.0", containsNan = true)
        val row = statsOf(tableId, colId, fileId)
        assertThat(row.containsNan).`as`("float column with NaN stores TRUE").isEqualTo(true)
        assertThat(row.min).`as`("min dropped when NaN present (not a true bound)").isNull()
        assertThat(row.max).`as`("max dropped when NaN present (NaN-excluding max is not an upper bound)").isNull()
    }

    @Test
    fun nonFloatStoresNullContainsNan() {
        val (tableId, colId, fileId) = seedFile("int32", min = "1", max = "10", containsNan = false)
        val row = statsOf(tableId, colId, fileId)
        assertThat(row.containsNan)
            .`as`("contains_nan is not applicable to a non-float column → SQL NULL")
            .isNull()
        assertThat(row.min).`as`("min preserved for non-float").isEqualTo("1")
        assertThat(row.max).`as`("max preserved for non-float").isEqualTo("10")
    }

    // -- helpers ------------------------------------------------------------------------------

    private data class StatsRow(val containsNan: Boolean?, val min: String?, val max: String?)

    private fun seedFile(
        ducklakeType: String,
        min: String,
        max: String,
        containsNan: Boolean,
    ): Triple<Long, Long, Long> {
        val name = "t_${tableSeq.incrementAndGet()}"
        catalog!!.createTable(
            "test_schema",
            name,
            listOf(TableColumnSpec.leaf("v", ducklakeType, false)),
            null,
            null,
        )
        val snapshotId = catalog!!.currentSnapshotId
        val table = catalog!!.getTable("test_schema", name, snapshotId)!!
        val colId = catalog!!.getTableColumns(table.tableId, snapshotId)
            .first { it.columnName == "v" }
            .columnId
        val stats = DucklakeFileColumnStats(colId, 16L, 2L, 0L, min, max, containsNan)
        catalog!!.commitInsert(
            table.tableId,
            listOf(DucklakeWriteFragment("cn/file.parquet", 1024L, 64L, 2L, listOf(stats))),
        )
        val fileId = fileIdForPath(table.tableId, "cn/file.parquet")
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

    private fun statsOf(tableId: Long, colId: Long, dataFileId: Long): StatsRow {
        val colstats = DUCKLAKE_FILE_COLUMN_STATS.`as`("colstats")
        openConnection().use { conn ->
            val row = CatalogTestSupport.dsl(conn)
                .select(colstats.CONTAINS_NAN, colstats.MIN_VALUE, colstats.MAX_VALUE)
                .from(colstats)
                .where(colstats.TABLE_ID.eq(tableId))
                .and(colstats.COLUMN_ID.eq(colId))
                .and(colstats.DATA_FILE_ID.eq(dataFileId))
                .fetchOne()!!
            return StatsRow(
                row.get(colstats.CONTAINS_NAN),
                row.get(colstats.MIN_VALUE),
                row.get(colstats.MAX_VALUE),
            )
        }
    }
}
