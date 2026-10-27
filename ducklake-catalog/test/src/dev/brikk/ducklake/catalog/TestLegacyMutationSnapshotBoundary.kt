package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
internal class TestLegacyMutationSnapshotBoundary {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun snapshotlessDeleteCannotReplaceANewerCumulativeDelete() { verify(false) }
        @Test fun snapshotlessMergeCannotReplaceDeletesOrPublishItsInserts() { verify(true) }

        private fun verify(merge: Boolean) = fixture().use { f -> f.seed(); f.reader().use { c ->
            c.createTable("main", "t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
            val table = c.getTable("main", "t", c.currentSnapshotId)!!.tableId
            val column = c.getTableColumns(table, c.currentSnapshotId).single().columnId
            val data = DucklakeWriteFragment("source.parquet", 100L, 0L, 3L,
                listOf(DucklakeFileColumnStats(column, 12L, 3L, 0L, "1", "3", false)))
            c.commitInsert(table, listOf(data))
            val planned = c.currentSnapshotId
            val source = c.getDataFiles(table, planned).single()
            val stale = DucklakeDeleteFragment(source.dataFileId, "stale-delete.parquet", 1L, 100L, 0L, 1L)
            c.commitDelete(table, listOf(stale.copy(path = "winner-delete.parquet")), planned)
            val before = c.currentSnapshotId
            val files = c.getDataFiles(table, before)
            val stats = c.getTableStats(table)
            val attempts = AtomicInteger()
            c.beforeWriteTransactionAction = Runnable { attempts.incrementAndGet() }
            val failure = try { catchThrowable {
                if (merge) c.commitMerge(table, listOf(stale), listOf(data.copy(path = "new-insert.parquet")))
                else c.commitDelete(table, listOf(stale))
            } } finally { c.beforeWriteTransactionAction = Runnable {} }
            assertThat(c.currentSnapshotId).describedAs("snapshotless mutation must not publish").isEqualTo(before)
            assertThat(c.getDataFiles(table, before)).isEqualTo(files)
            assertThat(c.getTableStats(table)).isEqualTo(stats)
            assertThat(attempts.get()).isZero()
            assertThat(failure).isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSnapshotId")
        } }
    }

    @Test fun retainedLegacyJvmMethodDescriptorsStillReturnVoid() {
        val long = java.lang.Long.TYPE
        val list = List::class.java
        val signatures = listOf(
            "flushInlinedData" to arrayOf(long, list, long),
            "flushInlinedDataWithSnapshots" to arrayOf(long, list),
            "flushInlinedDataWithSnapshots" to arrayOf(long, list, long),
            "commitDelete" to arrayOf(long, list),
            "commitMerge" to arrayOf(long, list, list),
        )
        for (owner in listOf(DucklakeCatalog::class.java, JdbcDucklakeCatalog::class.java)) {
            for ((name, parameters) in signatures) {
                assertThat(owner.getMethod(name, *parameters).returnType).describedAs("${owner.simpleName}.$name")
                    .isEqualTo(java.lang.Void.TYPE)
            }
        }
    }
}
