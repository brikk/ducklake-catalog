package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Review R01: physical order of an identical history must not choose the comparison type. */
internal class TestStatisticsLogicalTypeHistory {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun analyzeUsesTheVisibleDoubleTypeInsteadOfTheLastHistoryRow(@TempDir root: Path): Unit = fixture().use { f ->
            val table = prepare(f, root)
            f.reader().use { c ->
                c.analyzeTable(table.id)
                assertBounds(c, table.id, FIRST, SECOND)
            }
            assertNative(f, FIRST, SECOND, listOf(FIRST, SECOND))
            assertHistory(f, table)
        }

        @Test fun insertMergesExistingBoundsAtTheCurrentLogicalPrecision(@TempDir root: Path): Unit = fixture().use { f ->
            val table = prepare(f, root)
            val output = root.resolve("insert.parquet")
            f.native("COPY (SELECT $THIRD::DOUBLE AS x) TO '$output' (FORMAT PARQUET,FIELD_IDS {x:${table.column}})")
            f.reader().use { c ->
                c.commitInsert(table.id, listOf(fragment(output, table.column, THIRD)))
                assertBounds(c, table.id, FIRST, THIRD)
                assertThat(c.getTableStats(table.id)!!.recordCount).isEqualTo(3)
            }
            assertNative(f, FIRST, THIRD, listOf(FIRST, SECOND, THIRD))
            assertHistory(f, table)
        }

        @Test fun ordinaryRewriteRecomputesBoundsAtTheCurrentLogicalPrecision(@TempDir root: Path) {
            rewrite(root, partial = false)
        }

        @Test fun partialRewriteRecomputesBoundsAtTheCurrentLogicalPrecision(@TempDir root: Path) {
            rewrite(root, partial = true)
        }

        private fun rewrite(root: Path, partial: Boolean) = fixture().use { f ->
            val table = prepare(f, root)
            val source = f.reader().use { c -> c.getDataFiles(table.id, table.snapshot).single { it.rowIdStart == 0L } }
            val output = root.resolve("rewritten.parquet")
            f.native("COPY (SELECT x,rowid AS _ducklake_internal_row_id,${source.beginSnapshot}::BIGINT AS _ducklake_internal_snapshot_id " +
                "FROM lake.t WHERE rowid=0) TO '$output' (FORMAT PARQUET,FIELD_IDS {x:${table.column}," +
                "_ducklake_internal_row_id:2147483540,_ducklake_internal_snapshot_id:2147483539})")
            f.reader().use { c ->
                val fragment = fragment(output, table.column, FIRST)
                if (partial) c.rewriteDataFilesPartial(table.id, setOf(source.dataFileId),
                    listOf(PartialMergedFile(fragment, source.beginSnapshot, source.beginSnapshot)), table.snapshot)
                else c.rewriteDataFiles(table.id, setOf(source.dataFileId), listOf(fragment), table.snapshot)
                assertBounds(c, table.id, FIRST, SECOND)
                assertThat(c.getTableStats(table.id)!!.recordCount).isEqualTo(2)
            }
            assertNative(f, FIRST, SECOND, listOf(FIRST, SECOND))
            assertHistory(f, table)
        }
    }

    private data class Table(val id: Long, val column: Long, val snapshot: Long, val beforeTypeChange: Long)

    companion object {
        private const val FIRST = "1.00000001"
        private const val SECOND = "1.00000002"
        private const val THIRD = "1.00000003"

        private fun prepare(f: LegacyWriteFixture, root: Path): Table {
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(x FLOAT)")
            val original = f.reader().use { c ->
                val old = c.currentSnapshotId
                val id = c.getTable("main", "t", old)!!.tableId
                val column = c.getTableColumns(id, old).single().columnId
                c.setColumnType(id, column, "float64")
                Table(id, column, c.currentSnapshotId, old)
            }
            val files = listOf(FIRST, SECOND).mapIndexed { index, value ->
                val path = root.resolve("seed-$index.parquet")
                f.native("COPY (SELECT $value::DOUBLE AS x) TO '$path' (FORMAT PARQUET,FIELD_IDS {x:${original.column}})")
                fragment(path, original.column, value)
            }
            val table = f.reader().use { c ->
                c.commitInsert(original.id, files)
                val snapshot = c.currentSnapshotId
                original.copy(snapshot = snapshot)
            }
            assertNative(f, FIRST, SECOND, listOf(FIRST, SECOND))
            // Preserve every value and identity. Only physical row order changes, as a restore or
            // backend plan can do; logical type selection must not depend on it.
            f.metadata("CREATE TABLE reordered_columns AS SELECT * FROM ducklake_column ORDER BY begin_snapshot DESC")
            f.metadata("DELETE FROM ducklake_column")
            f.metadata("INSERT INTO ducklake_column SELECT * FROM reordered_columns ORDER BY begin_snapshot DESC")
            f.reader().use { c ->
                assertThat(c.getTableColumns(table.id, table.snapshot).single().columnType).isEqualTo("float64")
                assertThat(c.getTableColumns(table.id, table.beforeTypeChange).single().columnType).isEqualTo("float32")
            }
            return table
        }

        private fun fragment(path: Path, column: Long, value: String) = DucklakeWriteFragment(
            path.toString(), false, "parquet", Files.size(path), 0, 1,
            listOf(DucklakeFileColumnStats(column, 8, 1, 0, value, value, false)), emptyMap(), null, null,
        )

        private fun assertBounds(c: JdbcDucklakeCatalog, table: Long, minimum: String, maximum: String) {
            val stats = c.getColumnStats(table, c.currentSnapshotId).single()
            assertThat(stats.minValue).isEqualTo(minimum)
            assertThat(stats.maxValue).isEqualTo(maximum)
        }

        private fun assertNative(f: LegacyWriteFixture, minimum: String, maximum: String, values: List<String>) {
            assertThat(f.native("SELECT min(x),max(x) FROM lake.t")).containsExactly(listOf(minimum, maximum))
            assertThat(f.native("SELECT x FROM lake.t ORDER BY x").map { it.single() }).containsExactlyElementsOf(values)
            assertThat(f.native("SELECT rowid FROM lake.t ORDER BY x").map { it.single() })
                .containsExactlyElementsOf(values.indices.map { it.toString() })
        }

        private fun assertHistory(f: LegacyWriteFixture, table: Table) {
            assertThat(f.native("SELECT x FROM lake.t AT(VERSION=>${table.beforeTypeChange})")).isEmpty()
            assertThat(f.native("SELECT x FROM lake.t AT(VERSION=>${table.snapshot}) ORDER BY x"))
                .containsExactly(listOf(FIRST), listOf(SECOND))
        }
    }
}
