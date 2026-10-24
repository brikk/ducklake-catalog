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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Supplier

internal class TestFinal11ReadFilePages {
    @Nested inner class Local : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    @Nested inner class NativeQuack : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) }
    @Nested inner class JavaQuack : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) }
    @Nested inner class Postgres : Contract { override fun fixture() = ReadAcceptanceFixture.Postgres() }
    @Nested inner class MySql : Contract { override fun fixture() = ReadAcceptanceFixture.MySql() }

    @Test
    fun associationCursorCannotSkipAnotherDeleteIdentityForTheSameDataFile() {
        ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB).use { f ->
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); " +
                "CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(100); DELETE FROM lake.t WHERE i<10")
            // Owned query-shape fixture only: duplicated active association is not certified as a valid native scan.
            f.metadata("INSERT INTO ducklake_delete_file BY NAME SELECT * REPLACE(delete_file_id+100 AS delete_file_id) FROM ducklake_delete_file")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val first = c.getDataFilesPage(id, snapshot, null, 1).single()
                val second = c.getDataFilesPage(id, snapshot, first.cursor, 1).single()
                assertThat(second.cursor.dataFileId).isEqualTo(first.cursor.dataFileId)
                assertThat(second.cursor.deleteFileId).isEqualTo(requireNotNull(first.cursor.deleteFileId) + 100)
                assertThat(second.dataFile).isEqualTo(first.dataFile)
                assertThat(c.getDataFilesPage(id, snapshot, second.cursor, 1)).isEmpty()
            }) }
        }
    }

    interface Contract {
        fun fixture(): ReadAcceptanceFixture

        @Test
        fun activeAndAddedPagesRetainExactNativeDescriptorsAndTerminateWithinCallerBudget() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); " +
                    "BEGIN; CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1),(2); INSERT INTO lake.t VALUES(3),(4); " +
                    "INSERT INTO lake.t VALUES(5),(6); COMMIT; DELETE FROM lake.t WHERE i=2")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val active = drain<DucklakeDataFileCursor, DucklakeDataFilePageEntry>(
                        { after -> c.getDataFilesPage(table.tableId, snapshot, after, 1) }, { it.cursor },
                    )
                    assertThat(active).hasSize(3)
                    assertThat(active.map { it.cursor }).doesNotHaveDuplicates()
                    assertThat(active.map { it.dataFile }).containsExactlyElementsOf(c.getDataFiles(table.tableId, snapshot))
                    assertThat(active.map { it.dataFile.rowIdStart }).containsExactlyInAnyOrder(0, 2, 4)
                    assertThat(active.map { it.dataFile.recordCount }).containsOnly(2)
                    assertThat(active.mapNotNull { it.cursor.deleteFileId }).hasSize(1)
                    assertThat(active.single { it.cursor.deleteFileId != null }.dataFile.deleteFileFormat).isEqualTo("puffin")

                    val added = drain<DucklakeAddedFileCursor, DucklakeAddedFilePageEntry>(
                        { after -> c.getDataFilesAddedBetweenPage(table.tableId, 0, snapshot, after, 1) }, { it.cursor },
                    )
                    assertThat(added.map { it.dataFile }).containsExactlyElementsOf(c.getDataFilesAddedBetween(table.tableId, 0, snapshot))
                    assertThat(added.map { it.dataFile.deleteFilePath }).containsOnlyNulls()
                    assertThat(c.currentSnapshotId).isEqualTo(snapshot)
                    Unit
                }) }
                assertThat(f.native("SELECT i FROM lake.t ORDER BY i"))
                    .containsExactly(listOf("1"), listOf("3"), listOf("4"), listOf("5"), listOf("6"))
            }
        }

        private fun <C, T> drain(fetch: (C?) -> List<T>, cursor: (T) -> C): List<T> {
            var after: C? = null
            var complete = false
            val rows = mutableListOf<T>()
            repeat(4) {
                val page = fetch(after)
                assertThat(page.size).isLessThanOrEqualTo(1)
                if (page.isEmpty()) complete = true else {
                    assertThat(complete).isFalse()
                    assertThat(cursor(page.single())).isNotEqualTo(after)
                    rows.addAll(page)
                    after = cursor(page.single())
                }
            }
            assertThat(complete).isTrue()
            return rows
        }

        @Test
        fun invalidLimitsCursorsAndSnapshotsCannotBecomeSuccessfulPartialPlans() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
                f.reader().use { c ->
                    assertThatThrownBy { c.getDataFilesPage(0, 0, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
                    c.readSession(Supplier {
                        val snapshot = c.currentSnapshotId
                        val id = c.getTable("main", "t", snapshot)!!.tableId
                        for (limit in listOf(0, 1025)) {
                            assertThatThrownBy { c.getDataFilesPage(id, snapshot, null, limit) }.isInstanceOf(IllegalArgumentException::class.java)
                            assertThatThrownBy { c.getDataFilesAddedBetweenPage(id, 0, snapshot, null, limit) }
                                .isInstanceOf(IllegalArgumentException::class.java)
                        }
                        assertThatThrownBy { c.getDataFilesPage(id, snapshot, DucklakeDataFileCursor(-1, null), 1) }
                            .isInstanceOf(IllegalArgumentException::class.java)
                        assertThatThrownBy { c.getDataFilesPage(id, snapshot, DucklakeDataFileCursor(0, -1), 1) }
                            .isInstanceOf(IllegalArgumentException::class.java)
                        assertThatThrownBy { c.getDataFilesPage(id, snapshot + 1, null, 1) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                        assertThatThrownBy { c.getDataFilesAddedBetweenPage(id, snapshot + 1, snapshot, null, 1) }
                            .isInstanceOf(IllegalArgumentException::class.java)
                        assertThat(c.getDataFilesPage(id, snapshot, null, 1)).hasSize(1)
                    })
                }
            }
        }

        @Test
        fun oversizedPathsAreSqlBoundedThenRejectedNeverSilentlyTruncated() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
                f.metadata("UPDATE ducklake_data_file SET path='${"x".repeat(16385)}'") // Owned negative fixture, not native writer success.
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val id = c.getTable("main", "t", snapshot)!!.tableId
                    assertThatThrownBy { c.getDataFilesPage(id, snapshot, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text")
                    assertThatThrownBy { c.getDataFilesAddedBetweenPage(id, 0, snapshot, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text")
                    assertThat(c.getSnapshot(snapshot)).isNotNull()
                }) }
            }
        }
    }
}
