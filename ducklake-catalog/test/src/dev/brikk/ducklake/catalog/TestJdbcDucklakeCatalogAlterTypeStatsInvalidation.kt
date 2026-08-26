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
 * `ALTER COLUMN ... TYPE` stats correctness ("widening-invalidated-bounds", P2).
 *
 * DuckLake permits only *widening* promotions — but "widen to VARCHAR" is one of them, and
 * ALTER TYPE is metadata-only (data files, and their `ducklake_file_column_stats`, are not
 * rewritten). A numeric column's bounds are stored as text (`min="5"`, `max="100"`); once the
 * column becomes `VARCHAR` those bounds are compared *lexically* (`"5" > "100"`), so a legitimate
 * `col = '7'` would be wrongly pruned (`'7' NOT BETWEEN '5' AND '100'`) — a silently-wrong read.
 *
 * `setColumnType` must therefore invalidate (NULL) the stored bounds when the type change flips
 * the comparison class (numeric ⇄ non-numeric), and must PRESERVE them across numeric widenings
 * (`INT`→`BIGINT`), where the text still parses to the same value. Mirrors upstream DuckLake
 * ("keep bounds across numeric/decimal widenings; keep invalidated bounds unknown otherwise").
 */
class TestJdbcDucklakeCatalogAlterTypeStatsInvalidation {
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
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "alter-type-stats")

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
    fun numericToVarcharWideningInvalidatesBoundsAndStopsWrongPrune() {
        val (tableId, colId, fileId) = seedIntFile("5", "100")

        // Before the ALTER: numeric pruning works — an out-of-range predicate prunes the file.
        var snapshotId = catalog!!.currentSnapshotId
        assertThat(catalog!!.findDataFileIdsInRange(tableId, snapshotId, ColumnRangePredicate(colId, "200", "200")))
            .`as`("numeric column: col = 200 is above [5,100] and must prune")
            .doesNotContain(fileId)

        // Widen INT -> VARCHAR. The stored numeric bounds become lexically incoherent.
        catalog!!.setColumnType(tableId, colId, "varchar")
        snapshotId = catalog!!.currentSnapshotId

        // The bounds must have been invalidated (NULL), not left as stale numeric text.
        val (min, max) = boundsOf(tableId, colId, fileId)
        assertThat(min).`as`("min_value invalidated on numeric->varchar").isNull()
        assertThat(max).`as`("max_value invalidated on numeric->varchar").isNull()

        // The critical read: col = '7' lies inside the file's TRUE range but OUTSIDE the stale
        // lexical bounds ["5","100"]. With invalidated bounds it must be retained, not pruned.
        assertThat(catalog!!.findDataFileIdsInRange(tableId, snapshotId, ColumnRangePredicate(colId, "7", "7")))
            .`as`("col = '7' must be retained after numeric->varchar (no wrong prune on stale bounds)")
            .contains(fileId)
    }

    @Test
    fun numericWideningPreservesBoundsAndKeepsPruning() {
        val (tableId, colId, fileId) = seedIntFile("5", "100")

        // Widen INT -> BIGINT: same comparison class, text parses to the same numeric value.
        catalog!!.setColumnType(tableId, colId, "int64")
        val snapshotId = catalog!!.currentSnapshotId

        val (min, max) = boundsOf(tableId, colId, fileId)
        assertThat(min).`as`("min_value preserved across numeric widening").isEqualTo("5")
        assertThat(max).`as`("max_value preserved across numeric widening").isEqualTo("100")

        assertThat(catalog!!.findDataFileIdsInRange(tableId, snapshotId, ColumnRangePredicate(colId, "50", "50")))
            .`as`("in-range predicate still retains after widening")
            .contains(fileId)
        assertThat(catalog!!.findDataFileIdsInRange(tableId, snapshotId, ColumnRangePredicate(colId, "200", "200")))
            .`as`("numeric widening keeps full pruning power: col = 200 still prunes")
            .doesNotContain(fileId)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun seedIntFile(min: String, max: String): Triple<Long, Long, Long> {
        val name = "ints_${tableSeq.incrementAndGet()}"
        catalog!!.createTable(
            "test_schema",
            name,
            listOf(TableColumnSpec.leaf("v", "int32", false)),
            null,
            null,
        )
        val snapshotId = catalog!!.currentSnapshotId
        val table = catalog!!.getTable("test_schema", name, snapshotId)!!
        val colId = catalog!!.getTableColumns(table.tableId, snapshotId)
            .first { it.columnName == "v" }
            .columnId
        val stats = DucklakeFileColumnStats(colId, 8L, 3L, 0L, min, max, /* containsNan */ false)
        catalog!!.commitInsert(
            table.tableId,
            listOf(DucklakeWriteFragment("alter/file.parquet", 1024L, 64L, 3L, listOf(stats))),
        )
        val fileId = fileIdForPath(table.tableId, "alter/file.parquet")
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

    private fun boundsOf(tableId: Long, colId: Long, dataFileId: Long): Pair<String?, String?> {
        val colstats = DUCKLAKE_FILE_COLUMN_STATS.`as`("colstats")
        openConnection().use { conn ->
            val row = CatalogTestSupport.dsl(conn)
                .select(colstats.MIN_VALUE, colstats.MAX_VALUE)
                .from(colstats)
                .where(colstats.TABLE_ID.eq(tableId))
                .and(colstats.COLUMN_ID.eq(colId))
                .and(colstats.DATA_FILE_ID.eq(dataFileId))
                .fetchOne()!!
            return row.get(colstats.MIN_VALUE) to row.get(colstats.MAX_VALUE)
        }
    }
}
