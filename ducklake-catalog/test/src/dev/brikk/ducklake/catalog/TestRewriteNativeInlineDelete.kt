package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** N18: ordinary rewrites must fence native inlined deletes, not only delete files. */
internal class TestRewriteNativeInlineDelete {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun newerNativeInlineDeleteRejectsStaleOutputWithoutPublication(@TempDir root: Path): Unit = fixture().use { f ->
            val plan = prepare(f, root)
            f.native("DELETE FROM lake.t WHERE id=2")
            f.reader().use { c ->
                assertThat(c.getInlinedDeletes(plan.table, c.currentSnapshotId)).containsKey(plan.source.dataFileId)
                rejectUnchanged(c, plan, LogicalConflictException::class.java)
            }
            assertRows(f, plan.snapshot, listOf("1"))
        }

        @Test fun deletesAlreadyIncludedInPlanningRemainSupported(@TempDir root: Path): Unit = fixture().use { f ->
            val original = prepare(f, root)
            f.native("DELETE FROM lake.t WHERE id=2")
            val snapshot = f.reader().use { it.currentSnapshotId }
            val output = output(f, root.resolve("fresh.parquet"), original.column, original.source.beginSnapshot, 1)
            f.reader().use { c ->
                assertThat(c.getInlinedDeletes(original.table, snapshot)).containsKey(original.source.dataFileId)
                c.rewriteDataFiles(original.table, setOf(original.source.dataFileId), listOf(output), snapshot)
                assertThat(c.getDataFiles(original.table, c.currentSnapshotId).single().rowIdStart).isNull()
                assertThat(c.getTableStats(original.table)!!.recordCount).isEqualTo(1)
            }
            assertRows(f, original.snapshot, listOf("1"))
            val insert = insertion(f, root.resolve("next.parquet"), original.column, listOf(3))
            f.reader().use { it.commitInsert(original.table, listOf(insert)) }
            assertThat(f.native("SELECT rowid FROM lake.t WHERE id=3")).containsExactly(listOf("2"))
        }

        @Test fun anUnrelatedSourceDeleteDoesNotRejectTheRewrite(@TempDir root: Path): Unit = fixture().use { f ->
            val plan = prepare(f, root)
            val insert = insertion(f, root.resolve("unrelated.parquet"), plan.column, listOf(3, 4))
            f.reader().use { it.commitInsert(plan.table, listOf(insert)) }
            f.native("DELETE FROM lake.t WHERE id=4")
            f.reader().use { c ->
                val deletes = c.getInlinedDeletes(plan.table, c.currentSnapshotId)
                assertThat(deletes).isNotEmpty().doesNotContainKey(plan.source.dataFileId)
                c.rewriteDataFiles(plan.table, setOf(plan.source.dataFileId), listOf(plan.output), plan.snapshot)
            }
            assertRows(f, plan.snapshot, listOf("1", "2", "3"))
        }

        @Test fun absentInlineDeleteTableRemainsAValidRewrite(@TempDir root: Path): Unit = fixture().use { f ->
            val plan = prepare(f, root)
            f.metadata("DROP TABLE IF EXISTS ducklake_inlined_delete_${plan.table}")
            f.reader().use { c ->
                c.rewriteDataFiles(plan.table, setOf(plan.source.dataFileId), listOf(plan.output), plan.snapshot)
            }
            assertRows(f, plan.snapshot, listOf("1", "2"))
        }

        @Test fun malformedInlineDeleteIdentityCannotClaimSafeOutput(@TempDir root: Path): Unit = fixture().use { f ->
            val plan = prepare(f, root)
            f.native("DELETE FROM lake.t WHERE id=2")
            val deleted = f.reader().use { it.currentSnapshotId }
            for ((column, invalid) in listOf("begin_snapshot" to "NULL", "begin_snapshot" to "-1", "file_id" to "NULL", "file_id" to "-1")) {
                f.metadata("UPDATE ducklake_inlined_delete_${plan.table} SET $column=$invalid")
                try { f.reader().use { c -> rejectUnchanged(c, plan, DucklakeCatalogCorruptionException::class.java) } }
                finally {
                    val restored = if (column == "file_id") plan.source.dataFileId else deleted
                    f.metadata("UPDATE ducklake_inlined_delete_${plan.table} SET $column=$restored")
                }
            }
            assertRows(f, plan.snapshot, listOf("1"))
        }
    }

    @Test fun aRetryRechecksNativeInlineDeletesBeforeRegisteringOutput(@TempDir root: Path): Unit = LegacyWriteFixture.Postgres().use { f ->
        val plan = prepare(f, root)
        f.reader().use { c ->
            val attempts = AtomicInteger()
            val publication = AtomicInteger()
            c.beforeWriteTransactionAction = Runnable {
                if (attempts.incrementAndGet() == 2) f.native("DELETE FROM lake.t WHERE id=2")
            }
            c.beforeSnapshotInsertAction = Runnable {
                if (publication.getAndIncrement() == 0) throw TransactionConflictException("forced retry before publication", null)
            }
            try {
                assertThatThrownBy { c.rewriteDataFiles(plan.table, setOf(plan.source.dataFileId), listOf(plan.output), plan.snapshot) }
                    .isInstanceOf(LogicalConflictException::class.java).hasMessageContaining("inlined")
            } finally {
                c.beforeWriteTransactionAction = Runnable {}
                c.beforeSnapshotInsertAction = Runnable {}
            }
            assertThat(attempts.get()).isEqualTo(2)
            assertThat(publication.get()).isEqualTo(1)
            assertThat(c.getDataFiles(plan.table, c.currentSnapshotId).map { it.dataFileId }).containsExactly(plan.source.dataFileId)
        }
        assertRows(f, plan.snapshot, listOf("1"))
    }

    private data class Plan(val table: Long, val column: Long, val source: DucklakeDataFile, val snapshot: Long, val output: DucklakeWriteFragment)

    companion object {
        private fun prepare(f: LegacyWriteFixture, root: Path): Plan {
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(id INTEGER); " +
                "INSERT INTO lake.t VALUES(1),(2); CALL lake.set_option('data_inlining_row_limit',10)")
            val facts = f.reader().use { c ->
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!.tableId
                Plan(table, c.getTableColumns(table, snapshot).single().columnId,
                    c.getDataFiles(table, snapshot).single(), snapshot, DucklakeWriteFragment("unused", 0, 0, 0, emptyList()))
            }
            return facts.copy(output = output(f, root.resolve("stale.parquet"), facts.column, facts.source.beginSnapshot, 2))
        }

        private fun output(f: LegacyWriteFixture, path: Path, column: Long, begin: Long, count: Long): DucklakeWriteFragment {
            f.native("COPY (SELECT id,rowid AS _ducklake_internal_row_id,$begin::BIGINT AS _ducklake_internal_snapshot_id " +
                "FROM lake.t WHERE id IN (1,2) ORDER BY id DESC) TO '$path' (FORMAT PARQUET,FIELD_IDS {id:$column," +
                "_ducklake_internal_row_id:2147483540,_ducklake_internal_snapshot_id:2147483539})")
            return DucklakeWriteFragment(path.toString(), false, "parquet", Files.size(path), 0, count,
                listOf(DucklakeFileColumnStats(column, 8, count, 0, "1", if (count == 2L) "2" else "1", false)), emptyMap(), null, null)
        }

        private fun insertion(f: LegacyWriteFixture, path: Path, column: Long, ids: List<Int>): DucklakeWriteFragment {
            val values = ids.joinToString(",") { "($it)" }
            f.native("COPY (SELECT * FROM (VALUES $values) t(id)) TO '$path' (FORMAT PARQUET,FIELD_IDS {id:$column})")
            val count = ids.size.toLong()
            return DucklakeWriteFragment(path.toString(), false, "parquet", Files.size(path), 0, count,
                listOf(DucklakeFileColumnStats(column, 4 * count, count, 0, ids.min().toString(), ids.max().toString(), false)), emptyMap(), null, null)
        }

        private fun rejectUnchanged(c: JdbcDucklakeCatalog, plan: Plan, type: Class<out Throwable>) {
            val before = c.currentSnapshotId
            val files = c.getDataFiles(plan.table, before)
            val stats = c.getTableStats(plan.table)
            assertThatThrownBy { c.rewriteDataFiles(plan.table, setOf(plan.source.dataFileId), listOf(plan.output), plan.snapshot) }
                .isInstanceOf(type).hasMessageContaining("inlined")
            assertThat(c.currentSnapshotId).isEqualTo(before)
            assertThat(c.getDataFiles(plan.table, before)).isEqualTo(files)
            assertThat(c.getTableStats(plan.table)).isEqualTo(stats)
        }

        private fun assertRows(f: LegacyWriteFixture, old: Long, current: List<String>) {
            assertThat(f.native("SELECT id FROM lake.t ORDER BY id").map { it.single() }).containsExactlyElementsOf(current)
            assertThat(f.native("SELECT id FROM lake.t AT(VERSION=>$old) ORDER BY id")).containsExactly(listOf("1"), listOf("2"))
        }
    }
}
