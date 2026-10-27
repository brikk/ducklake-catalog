package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@Suppress("DEPRECATION")
internal class TestLegacyFlushSnapshotBoundary {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun legacyLiveFlushCannotRetireAnUnmaterializedInsert() { verifyLegacy(0) }
        @Test fun deprecatedSnapshotFlushCannotSubstituteTheCommitTimeSnapshot() { verifyLegacy(1) }
        @Test fun deprecatedSingleStartFlushCannotSubstituteTheCommitTimeSnapshot() { verifyLegacy(2) }

        private fun verifyLegacy(kind: Int) {
            val root = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-c13-flush-")
            try { fixture().use { f ->
                f.native("CREATE TABLE lake.t(id INTEGER); INSERT INTO lake.t VALUES(1),(2)")
                val planned = capture(f)
                val file = materialize(f, root.resolve("stale.parquet"), planned)
                f.native("INSERT INTO lake.t VALUES(3)")
                val committed = snapshot(f)
                val failure = f.reader().use { c ->
                    attemptLegacy(c, planned.table, file, kind)
                }
                // The pre-fix operation returned success but made the unmaterialized third row disappear.
                assertThat(f.native("SELECT id,rowid FROM lake.t ORDER BY id"))
                    .containsExactly(listOf("1", "0"), listOf("2", "1"), listOf("3", "2"))
                assertThat(snapshot(f)).isEqualTo(committed)
                assertThat(failure).isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSnapshotId")
                assertThat(f.native("SELECT id FROM lake.t AT(VERSION=>${planned.snapshot}) ORDER BY id"))
                    .containsExactly(listOf("1"), listOf("2"))
                f.reader().use { c ->
                    assertThat(c.getDataFiles(planned.table, committed)).isEmpty()
                    assertThat(c.countInlinedRows(planned.table, planned.version, committed)).isEqualTo(3L)
                    assertThat(c.getTableStats(planned.table)!!.recordCount).isEqualTo(3L)
                }
            } } finally { remove(root) }
        }

        private fun attemptLegacy(c: JdbcDucklakeCatalog, table: Long, file: FlushedInlinedFile, kind: Int): Throwable? = catchThrowable {
            when (kind) {
                0 -> c.flushInlinedData(table, listOf(file.fragment), file.rowIdStart)
                1 -> c.flushInlinedDataWithSnapshots(table, listOf(file))
                else -> c.flushInlinedDataWithSnapshots(table, listOf(file), file.rowIdStart)
            }
        }

        @Test fun explicitSnapshotRejectsStaleOutputAndAcceptsRematerializedRows() {
            val root = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-c13-safe-")
            try { fixture().use { f ->
                f.native("CREATE TABLE lake.t(id INTEGER); INSERT INTO lake.t VALUES(1),(2)")
                val old = capture(f)
                val stale = materialize(f, root.resolve("stale.parquet"), old)
                f.native("INSERT INTO lake.t VALUES(3)")
                val before = snapshot(f)
                f.reader().use { c ->
                    assertThatThrownBy { c.flushInlinedDataWithSnapshots(old.table, listOf(stale), emptyList(), old.snapshot) }
                        .isInstanceOf(LogicalConflictException::class.java)
                    assertThat(c.currentSnapshotId).isEqualTo(before)
                    assertThat(c.countInlinedRows(old.table, old.version, before)).isEqualTo(3L)
                }
                val fresh = capture(f)
                val output = materialize(f, root.resolve("fresh.parquet"), fresh)
                f.reader().use { c ->
                    c.flushInlinedDataWithSnapshots(fresh.table, listOf(output), emptyList(), fresh.snapshot)
                    assertThat(c.getDataFiles(fresh.table, c.currentSnapshotId)).hasSize(1)
                    assertThat(c.countInlinedRows(fresh.table, fresh.version, c.currentSnapshotId)).isZero()
                }
                assertThat(f.native("SELECT id,rowid FROM lake.t ORDER BY id"))
                    .containsExactly(listOf("1", "0"), listOf("2", "1"), listOf("3", "2"))
                assertThat(f.native("SELECT id,rowid FROM lake.t AT(VERSION=>${old.snapshot}) ORDER BY id"))
                    .containsExactly(listOf("1", "0"), listOf("2", "1"))
            } } finally { remove(root) }
        }
    }

    companion object {
        private data class Captured(val table: Long, val column: Long, val snapshot: Long, val version: Long, val rows: List<DucklakeInlinedChangeRow>)

        private fun capture(f: LegacyWriteFixture): Captured = f.reader().use { c ->
            val snapshot = c.currentSnapshotId
            val table = c.getTable("main", "t", snapshot)!!.tableId
            val column = c.getTableColumns(table, snapshot).single().columnId
            val version = c.getInlinedDataInfos(table, snapshot).single().schemaVersion
            Captured(table, column, snapshot, version, c.getInlinedChangesBetweenDecoded(table, version, 0, snapshot, listOf(column)))
        }

        private fun materialize(f: LegacyWriteFixture, path: Path, plan: Captured): FlushedInlinedFile {
            val values = plan.rows.joinToString(" UNION ALL ") {
                "SELECT ${it.values.single()}::INTEGER AS id,${it.rowId}::BIGINT AS _ducklake_internal_row_id," +
                    "${it.beginSnapshot}::BIGINT AS _ducklake_internal_snapshot_id"
            }
            f.native("COPY ($values ORDER BY _ducklake_internal_row_id) TO '$path' (FORMAT PARQUET, FIELD_IDS {id: ${plan.column}," +
                "_ducklake_internal_row_id: 2147483540,_ducklake_internal_snapshot_id: 2147483539})")
            val count = plan.rows.size.toLong()
            val stats = DucklakeFileColumnStats(plan.column, count * 4, count, 0L,
                plan.rows.minOf { (it.values.single() as Number).toInt() }.toString(),
                plan.rows.maxOf { (it.values.single() as Number).toInt() }.toString(), false)
            val fragment = DucklakeWriteFragment(path.toString(), false, "parquet", Files.size(path), 0L, count, listOf(stats), emptyMap(), null, null)
            return FlushedInlinedFile(fragment, plan.rows.minOf { it.beginSnapshot }, plan.rows.maxOf { it.beginSnapshot }, plan.rows.minOf { it.rowId })
        }

        private fun snapshot(f: LegacyWriteFixture) = f.reader().use { it.currentSnapshotId }
        private fun remove(root: Path) = Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
