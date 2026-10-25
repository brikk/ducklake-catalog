/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

internal class TestFinal11DeletionPages {
    @Nested inner class Local : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    @Nested inner class NativeQuack : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) }
    @Nested inner class JavaQuack : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) }
    @Nested inner class Postgres : NativeContract { override fun fixture() = ReadAcceptanceFixture.Postgres() }
    @Nested inner class MySql : Contract { override fun fixture() = ReadAcceptanceFixture.MySql() }

    data class Seed(val tableId: Long, val snapshot: Long, val fileId: Long, val deleteId: Long, val deletePath: String)

    interface Contract {
        fun fixture(): ReadAcceptanceFixture

        @Test
        fun nativeVectorCandidatesUseBoundedSeekPagesWithoutChangingDescriptorsOrOriginalIdentity() {
            fixture().use { f ->
                val s = seed(f)
                f.reader().use { c -> c.readSession(Supplier {
                    val rows = candidates(c, s.tableId, s.snapshot, s.snapshot)
                    assertThat(rows).hasSize(1)
                    val row = rows.single()
                    assertThat(row.cursor).isEqualTo(DucklakeDeletionCursor(false, s.snapshot, s.fileId, s.deleteId))
                    assertThat(row.deletion.recordCount).isEqualTo(100)
                    assertThat(row.deletion.rowIdStart).isEqualTo(0)
                    assertThat(row.deletion.currentDeleteFormat).isEqualTo("puffin")
                    assertThat(row.deletion.previousDeletePath).isNull()
                    assertThat(rows.map { it.deletion }).containsExactlyElementsOf(c.getDeletionsBetween(s.tableId, s.snapshot, s.snapshot))
                    invalidRequests(c, s)
                    val snapshots = history({ c.listSnapshotsPage(it, 1) }, { it.snapshotId })
                    val changes = history({ c.listSnapshotChangesPage(it, 1) }, { it.snapshotId })
                    assertThat(snapshots).containsExactlyElementsOf(c.listSnapshots().sortedBy { it.snapshotId })
                    assertThat(changes).containsExactlyElementsOf(c.listSnapshotChanges().sortedBy { it.snapshotId })
                    Unit
                }) }
                assertThat(f.native("SELECT count(*) FROM lake.t")).containsExactly(listOf("90"))
            }
        }

        @Test
        fun oversizedPathsAndOrphanAssociationsCannotBecomeTruncatedOrEmptySuccessfulPages() {
            fixture().use { f ->
                val s = seed(f)
                f.metadata("UPDATE ducklake_delete_file SET path='${"x".repeat(16385)}'")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, s.snapshot, s.snapshot, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text")
                    assertThat(c.getSnapshot(s.snapshot)).isNotNull()
                }) }
                f.metadata("UPDATE ducklake_delete_file SET path=${q(s.deletePath)},data_file_id=9223372036854775807")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, s.snapshot, s.snapshot, null, 1) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                    assertThat(c.getSnapshot(s.snapshot)).isNotNull()
                }) }
            }
        }

        @Test
        fun largeRequestedPagesRespectTextAllowanceWithoutTruncatingSmallerValidMetadataPages() {
            fixture().use { f ->
                val s = seed(f)
                val path = "x".repeat(8000)
                f.metadata("UPDATE ducklake_delete_file SET path='$path'") // Query-bound fixture, not a valid physical-object claim.
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, s.snapshot, s.snapshot, null, 1024) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text")
                    assertThat(c.getDeletionsBetweenPage(s.tableId, s.snapshot, s.snapshot, null, 1).single().deletion.currentDeletePath).isEqualTo(path)
                }) }
            }
        }

        @Test
        fun positionalMetadataPagesAndExactRetainedLookupBatchesPreserveTheirSeparateRoles() {
            fixture().use { f ->
                val s = seed(f)
                val name = "ducklake_inlined_delete_${s.tableId}"
                f.reader().use { c -> c.readSession(Supplier {
                    assertThat(c.getInlinedFileDeletesBetweenPage(s.tableId, 0, s.snapshot, null, 1)).isEmpty()
                }) }
                // Authored positional metadata, not a native MySQL delete/flush success or a connector CDC scan.
                f.metadata("CREATE TABLE $name(file_id BIGINT,row_id BIGINT,begin_snapshot BIGINT)")
                f.metadata("INSERT INTO $name VALUES(${s.fileId},90,${s.snapshot}),(${s.fileId},70,${s.snapshot})")
                f.reader().use { c -> c.readSession(Supplier {
                    val first = c.getInlinedFileDeletesBetweenPage(s.tableId, 0, s.snapshot, null, 1).single()
                    val after = DucklakeInlinedFileDeleteCursor(first.dataFileId, first.position, first.snapshotId)
                    val second = c.getInlinedFileDeletesBetweenPage(s.tableId, 0, s.snapshot, after, 1).single()
                    assertThat(listOf(first, second)).containsExactly(DucklakeInlinedFileDelete(s.fileId, 70, s.snapshot),
                        DucklakeInlinedFileDelete(s.fileId, 90, s.snapshot))
                    assertThat(c.getInlinedFileDeletesBetweenPage(s.tableId, 0, s.snapshot,
                        DucklakeInlinedFileDeleteCursor(second.dataFileId, second.position, second.snapshotId), 1)).isEmpty()
                    val files = c.getDataFilesByIdsBatch(s.tableId, listOf(s.fileId, s.fileId))
                    assertThat(files).hasSize(1)
                    assertThat(files.single().recordCount).isEqualTo(100)
                    assertThat(files.single().deleteFilePath).isNull() // CDC lookup reads original rows, not a current-delete association.
                    assertThat(c.getDataFilesByIdsBatch(s.tableId, emptyList())).isEmpty()
                    assertThatThrownBy { c.getDataFilesByIdsBatch(s.tableId, listOf(Long.MAX_VALUE)) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("missing")
                }) }
                f.metadata("DROP TABLE $name")
                f.metadata("CREATE TABLE $name(file_id BIGINT,row_id VARCHAR(100),begin_snapshot BIGINT)")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.getInlinedFileDeletesBetweenPage(s.tableId, 0, s.snapshot, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("physical layout")
                }) }
            }
        }

        @Test
        fun fractionalPhysicalFooterMetadataCannotBeCoercedIntoAnApparentlyValidIntegerDescriptor() {
            fixture().use { f ->
                val s = seed(f)
                f.metadata("ALTER TABLE ducklake_data_file RENAME COLUMN footer_size TO held_footer")
                f.metadata("ALTER TABLE ducklake_data_file ADD COLUMN footer_size DECIMAL(20,1)")
                f.metadata("UPDATE ducklake_data_file SET footer_size=1.5")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, s.snapshot, s.snapshot, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("footer_size")
                    assertThatThrownBy { c.getDataFilesPage(s.tableId, s.snapshot, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("footer_size")
                    assertThatThrownBy { c.getDataFilesByIdsBatch(s.tableId, listOf(s.fileId)) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("footer_size")
                    assertThat(c.getSnapshot(s.snapshot)).isNotNull()
                }) }
            }
        }

        @Test
        fun negativeStoredRowIdentityCannotEscapeThroughFilePagesCdcOrLookupBatches() {
            fixture().use { f ->
                val s = seed(f)
                f.metadata("UPDATE ducklake_data_file SET row_id_start=-1")
                f.reader().use { c -> c.readSession(Supplier {
                    for (read in listOf<() -> Any?>(
                        { c.getDeletionsBetweenPage(s.tableId, s.snapshot, s.snapshot, null, 1) },
                        { c.getDataFilesPage(s.tableId, s.snapshot, null, 1) },
                        { c.getDataFilesAddedBetweenPage(s.tableId, 0, s.snapshot, null, 1) },
                        { c.getDataFilesByIdsBatch(s.tableId, listOf(s.fileId)) },
                    )) assertThatThrownBy { read() }.isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("identity")
                }) }
            }
        }

        @Test
        fun expiredSnapshotHistoryRemainsPagedWithoutFabricatingNullSnapshotCounters() {
            fixture().use { f ->
                val s = seed(f)
                f.metadata("DELETE FROM ducklake_snapshot WHERE snapshot_id<${s.snapshot}") // Owned retention fixture, not library1.1 expiry.
                f.reader().use { c -> c.readSession(Supplier {
                    assertThat(history({ c.listSnapshotsPage(it, 1) }, { it.snapshotId }).map { it.snapshotId }).containsExactly(s.snapshot)
                    val changes = history({ c.listSnapshotChangesPage(it, 1) }, { it.snapshotId })
                    assertThat(changes.any { it.snapshotId < s.snapshot }).isTrue()
                    assertThat(changes).containsExactlyElementsOf(c.listSnapshotChanges().sortedBy { it.snapshotId })
                }) }
                f.metadata("UPDATE ducklake_snapshot SET schema_version=NULL")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.listSnapshotsPage(null, 1) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                }) }
            }
        }

        fun invalidRequests(c: JdbcDucklakeCatalog, s: Seed) {
            for (limit in listOf(0, 1025)) {
                assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, 0, s.snapshot, null, limit) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, s.snapshot + 1, s.snapshot, null, 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, 0, s.snapshot, DucklakeDeletionCursor(false, 0, 0, null), 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { c.getDeletionsBetweenPage(s.tableId, 0, s.snapshot, DucklakeDeletionCursor(true, 0, 0, 1), 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { c.getInlinedFileDeletesBetweenPage(s.tableId, 0, s.snapshot, DucklakeInlinedFileDeleteCursor(0, -1, 0), 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { c.getDataFilesByIdsBatch(s.tableId, List(1025) { 0L }) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        fun seed(f: ReadAcceptanceFixture): Seed {
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); " +
                "CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(100); DELETE FROM lake.t WHERE i<10")
            return f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val file = c.getDataFiles(table.tableId, snapshot).single()
                val delete = requireNotNull(c.getDataFilesPage(table.tableId, snapshot, null, 1).single().cursor.deleteFileId)
                Seed(table.tableId, snapshot, file.dataFileId, delete, requireNotNull(file.deleteFilePath))
            }) }
        }

        fun candidates(c: JdbcDucklakeCatalog, table: Long, start: Long, end: Long): List<DucklakeDeletionPageEntry> {
            val result = mutableListOf<DucklakeDeletionPageEntry>()
            var after: DucklakeDeletionCursor? = null
            var complete = false
            repeat(16) {
                if (!complete) {
                    val page = c.getDeletionsBetweenPage(table, start, end, after, 1)
                    assertThat(page.size).isLessThanOrEqualTo(1)
                    if (page.isEmpty()) complete = true else {
                        assertThat(page.single().cursor).isNotEqualTo(after)
                        result.addAll(page)
                        after = page.single().cursor
                    }
                }
            }
            check(complete) { "Deletion page traversal incomplete within caller budget" }
            assertThat(result.map { it.cursor }).doesNotHaveDuplicates()
            return result
        }

        fun head(f: ReadAcceptanceFixture): Long = f.metadataRows("SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
        fun q(value: String): String = "'${value.replace("'", "''")}'"

        fun <T> history(fetch: (Long?) -> List<T>, key: (T) -> Long): List<T> {
            var after: Long? = null
            val rows = mutableListOf<T>()
            var complete = false
            repeat(16) {
                if (!complete) {
                    val page = fetch(after)
                    if (page.isEmpty()) complete = true else {
                        check(after == null || key(page.last()) > after!!) { "History cursor did not advance" }
                        rows.addAll(page)
                        after = key(page.last())
                    }
                }
            }
            check(complete) { "History traversal incomplete within caller budget" }
            return rows
        }
    }

    interface NativeContract : Contract {
        @Test
        fun cumulativePredecessorsAndFullRetirementsKeepBothCursorPhasesAndWindowStates() {
            fixture().use { f ->
                val first = seed(f)
                f.native("DELETE FROM lake.t WHERE i<20")
                val second = head(f)
                val summary = f.metadataRows("SELECT begin_snapshot,delete_count,footer_size FROM ducklake_delete_file")
                assertThat(summary).hasSize(1) // Native overwrites history into one snapshot-tagged cumulative container.
                assertThat(summary.single().take(2)).containsExactly(first.snapshot.toString(), "20")
                assertThat(summary.single()[2]!!.toLong()).isGreaterThan(0)
                f.native("TRUNCATE lake.t")
                val retired = head(f)
                f.reader().use { c -> c.readSession(Supplier {
                    val rows = candidates(c, first.tableId, first.snapshot, retired)
                    assertThat(rows.map { it.cursor.fullFileDelete }).containsExactly(false, true)
                    assertThat(rows.map { it.deletion }).containsExactlyInAnyOrderElementsOf(c.getDeletionsBetween(first.tableId, first.snapshot, retired))
                    assertThat(rows[0].deletion.snapshotId).isEqualTo(first.snapshot)
                    assertThat(rows[1].deletion.previousDeletePath).isEqualTo(rows[0].deletion.currentDeletePath)
                    assertThat(rows[1].deletion.currentDeletePath).isNull()
                    val later = candidates(c, first.tableId, second, second).single().deletion
                    assertThat(later.snapshotId).isEqualTo(first.snapshot)
                    assertThat(later.previousDeletePath).isEqualTo(later.currentDeletePath)
                    assertThat(c.getDataFilesByIdsBatch(first.tableId, listOf(first.fileId))).hasSize(1) // Ended file remains a retained CDC source.
                }) }
                assertThat(f.native("SELECT count(*) FROM lake.t")).containsExactly(listOf("0"))
            }
        }

        @Test
        fun previousLookupsRejectOversizedAndAmbiguousHistoryRatherThanGuessingAState() {
            fixture().use { f ->
                val first = seed(f)
                f.native("CREATE TABLE lake.marker(i INTEGER)")
                val second = head(f)
                // Authored previous/current query shape; no claim that the native writer persists this history.
                f.metadata("INSERT INTO ducklake_delete_file(delete_file_id,table_id,begin_snapshot,end_snapshot,data_file_id,path," +
                    "path_is_relative,format,delete_count,file_size_bytes,footer_size,encryption_key,partial_max,row_group_count) " +
                    "SELECT delete_file_id+100,table_id,$second,NULL,data_file_id,path,path_is_relative,format,delete_count,file_size_bytes," +
                    "footer_size,encryption_key,partial_max,row_group_count FROM ducklake_delete_file WHERE delete_file_id=${first.deleteId}")
                f.metadata("UPDATE ducklake_delete_file SET end_snapshot=$second WHERE delete_file_id=${first.deleteId}")
                invalidPredecessor(f, first, second)
            }
        }

        fun invalidPredecessor(f: ReadAcceptanceFixture, first: Seed, second: Long) {
            f.metadata("UPDATE ducklake_delete_file SET path='${"x".repeat(16385)}' WHERE delete_file_id=${first.deleteId}")
            f.reader().use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.getDeletionsBetweenPage(first.tableId, second, second, null, 1) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text")
            }) }
            f.metadata("UPDATE ducklake_delete_file SET path=${q(first.deletePath)} WHERE delete_file_id=${first.deleteId}")
            // Owned ambiguous history, not native consolidation success. Never guess an arbitrary predecessor.
            f.metadata("INSERT INTO ducklake_delete_file(delete_file_id,table_id,begin_snapshot,end_snapshot,data_file_id,path," +
                "path_is_relative,format,delete_count,file_size_bytes,footer_size,encryption_key,partial_max,row_group_count) " +
                "SELECT delete_file_id+200,table_id,begin_snapshot,end_snapshot,data_file_id,path," +
                "path_is_relative,format,delete_count,file_size_bytes,footer_size,encryption_key,partial_max,row_group_count " +
                "FROM ducklake_delete_file WHERE delete_file_id=${first.deleteId}")
            f.reader().use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.getDeletionsBetweenPage(first.tableId, second, second, null, 1) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("Ambiguous")
            }) }
        }

        @Test
        fun severalDataFilesDeletedInOneSnapshotDoNotDisappearBehindASnapshotOnlyCursor() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); " +
                    "BEGIN; CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1),(2); INSERT INTO lake.t VALUES(3),(4); " +
                    "INSERT INTO lake.t VALUES(5),(6); COMMIT; DELETE FROM lake.t WHERE i%2=0")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val rows = candidates(c, table.tableId, snapshot, snapshot)
                    assertThat(rows).hasSize(3)
                    assertThat(rows.map { it.cursor.snapshotId }).containsOnly(snapshot)
                    assertThat(rows.map { it.cursor.dataFileId }).doesNotHaveDuplicates()
                    assertThat(rows.map { it.deletion }).containsExactlyInAnyOrderElementsOf(c.getDeletionsBetween(table.tableId, snapshot, snapshot))
                }) }
                assertThat(f.native("SELECT i FROM lake.t ORDER BY i")).containsExactly(listOf("1"), listOf("3"), listOf("5"))
            }
        }

        @Test
        fun actualNativeInlineFilePositionsArePagedAndNeverConvertedToAbsoluteRowIds() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(100)")
                f.native("CALL lake.set_option('data_inlining_row_limit',10); DELETE FROM lake.t WHERE i IN(7,9)")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val first = c.getInlinedFileDeletesBetweenPage(table.tableId, snapshot, snapshot, null, 1).single()
                    val second = c.getInlinedFileDeletesBetweenPage(table.tableId, snapshot, snapshot,
                        DucklakeInlinedFileDeleteCursor(first.dataFileId, first.position, first.snapshotId), 1).single()
                    assertThat(listOf(first.position, second.position)).containsExactly(7, 9)
                    assertThat(c.getDataFilesByIdsBatch(table.tableId, listOf(first.dataFileId)).single().recordCount).isEqualTo(100)
                }) }
                assertThat(f.native("SELECT count(*) FROM lake.t")).containsExactly(listOf("98"))
            }
        }
    }
}
