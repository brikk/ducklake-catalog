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
import java.sql.DriverManager
import java.util.function.Supplier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.fasterxml.jackson.databind.ObjectMapper

/** MySQL native mutation/flush remains deferred M01, not an implicit skipped acceptance case. */
internal class TestFinal11NativeReadLineage {
    @Nested inner class Local : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    @Nested inner class NativeQuack : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) }
    @Nested inner class JavaQuack : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) }
    @Nested inner class Postgres : Contract { override fun fixture() = ReadAcceptanceFixture.Postgres() }

    interface Contract {
        fun fixture(): ReadAcceptanceFixture

        @Test
        fun flushMaterializesTaggedDeletesWhoseNullPartialMaxStillRequiresPerPositionSnapshotFiltering() {
            fixture().use { f ->
                f.native("CALL lake.set_option('write_deletion_vectors',false); CREATE TABLE lake.t(i INTEGER,b BLOB,s VARCHAR); " +
                    "INSERT INTO lake.t VALUES(1,from_hex('005CFF'),NULL),(2,from_hex('5C783431'),'NULL'),(3,NULL,'')," +
                    "(4,from_hex('FFFE'),'deleted'),(5,''::BLOB,'last')")
                val inserted = snapshot(f)
                f.native("DELETE FROM lake.t WHERE i=2")
                val earlier = snapshot(f)
                f.native("DELETE FROM lake.t WHERE i=4")
                val later = snapshot(f)
                f.native("CALL ducklake_flush_inlined_data('lake',table_name=>'t')")
                assertThat(f.native("SELECT i,rowid,hex(b),s FROM lake.t ORDER BY i"))
                    .containsExactly(listOf("1", "0", "005CFF", null), listOf("3", "2", null, ""), listOf("5", "4", "", "last"))
                assertThat(f.native("SELECT count(*) FROM lake.t AT(VERSION=>$earlier)")).containsExactly(listOf("4"))
                assertThat(f.native("SELECT count(*) FROM lake.t AT(VERSION=>$later)")).containsExactly(listOf("3"))
                f.reader().use { c -> c.readSession(Supplier {
                    val current = c.currentSnapshotId
                    val table = c.getTable("main", "t", current)!!
                    val file = c.getDataFiles(table.tableId, later).single()
                    assertThat(file.beginSnapshot).isEqualTo(inserted)
                    assertThat(file.recordCount).isEqualTo(5)
                    assertThat(file.deleteFileFormat).isEqualTo("parquet")
                    assertThat(file.deleteFilePartialMax).isNull()
                    val deleted = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 2).single()
                    assertThat(parquet("SELECT DISTINCT _ducklake_internal_snapshot_id FROM read_parquet(${q(deleted.resolvedPath)}) ORDER BY 1"))
                        .containsExactly(listOf(earlier.toString()), listOf(later.toString()))
                    val firstWindow = c.getDeletionsBetween(table.tableId, earlier, earlier).single { !it.fullFileDelete }
                    assertThat(firstWindow.snapshotId).isEqualTo(earlier)
                    assertThat(firstWindow.previousDeletePath).isNull()
                    val secondWindow = c.getDeletionsBetween(table.tableId, later, later).single { !it.fullFileDelete }
                    assertThat(secondWindow.snapshotId).isEqualTo(earlier)
                    assertThat(secondWindow.previousDeletePath).isEqualTo(secondWindow.currentDeletePath)
                    assertThat(secondWindow.currentDeletePartialMax).isNull()
                    val metadata = c.readMetadata().getFileReadMetadata(table.tableId, current).single()
                    assertThat(metadata.deleteCount).isEqualTo(2)
                    assertThat(metadata.deleteRowGroupCount).isEqualTo(1)
                    assertThat(c.getLiveRowCount(table.tableId, current)).isEqualTo(3)
                    assertThatThrownBy { c.getLiveRowCount(table.tableId, earlier) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("require a scan")
                    assertThat(parquet("SELECT i,hex(b),s FROM read_parquet(${q(c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 2)
                        .single().resolvedPath)}) ORDER BY i"))
                        .containsExactly(listOf("1", "005CFF", null), listOf("2", "5C783431", "NULL"), listOf("3", null, ""),
                            listOf("4", "FFFE", "deleted"), listOf("5", "", "last"))
                }) }
            }
        }

        @Test
        fun nativeMergeKeepsAbsoluteEmbeddedIdsOriginalSnapshotsAndNullableContiguousStart() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',2); CREATE TABLE lake.t(i INTEGER,b BLOB); " +
                    "INSERT INTO lake.t VALUES(1,from_hex('005CFF')),(2,NULL),(3,''::BLOB)")
                val first = snapshot(f)
                f.native("INSERT INTO lake.t VALUES(4,NULL)") // This inline row creates a real gap between file ID ranges.
                val inlined = snapshot(f)
                f.native("INSERT INTO lake.t VALUES(5,from_hex('5C783431')),(6,''::BLOB),(7,NULL)")
                val second = snapshot(f)
                f.native("CALL ducklake_merge_adjacent_files('lake','t')")
                assertThat(f.native("SELECT i,rowid FROM lake.t ORDER BY i"))
                    .containsExactlyElementsOf((1..7).map { listOf(it.toString(), (it - 1).toString()) })
                assertThat(f.native("SELECT i,rowid FROM lake.t AT(VERSION=>$first) ORDER BY i"))
                    .containsExactly(listOf("1", "0"), listOf("2", "1"), listOf("3", "2"))
                f.reader().use { c -> c.readSession(Supplier {
                    val current = c.currentSnapshotId
                    val table = c.getTable("main", "t", current)!!
                    val file = c.getDataFiles(table.tableId, current).single()
                    assertThat(file.rowIdStart).isNull()
                    val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 10).single { it.fileId == file.dataFileId }.resolvedPath
                    assertThat(parquet("SELECT i,_ducklake_internal_row_id,_ducklake_internal_snapshot_id,hex(b) " +
                        "FROM read_parquet(${q(path)}) ORDER BY i"))
                        .containsExactly(listOf("1", "0", first.toString(), "005CFF"), listOf("2", "1", first.toString(), null),
                            listOf("3", "2", first.toString(), ""), listOf("5", "4", second.toString(), "5C783431"),
                            listOf("6", "5", second.toString(), ""), listOf("7", "6", second.toString(), null))
                    val info = c.getInlinedDataInfos(table.tableId, current).single { it.hasLiveRows }
                    assertThat(c.readInlinedRowIds(table.tableId, info.schemaVersion, current)).containsExactly(3)
                    assertThat(c.readInlinedBeginSnapshots(table.tableId, info.schemaVersion, current)).containsExactly(inlined)
                    val historic = c.getDataFiles(table.tableId, first).single()
                    assertThat(historic.dataFileId).isEqualTo(file.dataFileId)
                    assertThat(historic.rowIdStart).isNull() // Same physical merge output; embedded row/snapshot tags control history.
                    assertThat(c.getDataFilesPage(table.tableId, first, null, 1).single().dataFile).isEqualTo(historic)
                    val added = c.getDataFilesAddedBetweenPage(table.tableId, second, second, null, 1).single().dataFile
                    assertThat(added.dataFileId).isEqualTo(file.dataFileId)
                    assertThat(added.beginSnapshot).isEqualTo(first)
                    assertThat(added.rowIdStart).isNull()
                    assertThat(c.getLiveRowCount(table.tableId, current)).isEqualTo(7)
                    assertThatThrownBy { c.getLiveRowCount(table.tableId, first) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("require a scan")
                    assertThat(c.getColumnStats(table.tableId, current).map { it.minValue }).containsOnlyNulls()
                }) }
            }
        }

        @Test
        fun multiSnapshotPuffinContainersRequireSnapshotSelectedCumulativeVectorsNotSelfCancellation() {
            fixture().use { f ->
                f.native("CALL lake.set_option('write_deletion_vectors',true); CREATE TABLE lake.t(i INTEGER); " +
                    "INSERT INTO lake.t VALUES(1),(2),(3),(4)")
                f.native("DELETE FROM lake.t WHERE i=2")
                val earlier = snapshot(f)
                f.native("DELETE FROM lake.t WHERE i=4")
                val later = snapshot(f)
                f.native("CALL ducklake_flush_inlined_data('lake',table_name=>'t')")
                assertThat(f.native("SELECT i FROM lake.t AT(VERSION=>$earlier) ORDER BY i"))
                    .containsExactly(listOf("1"), listOf("3"), listOf("4"))
                assertThat(f.native("SELECT i FROM lake.t AT(VERSION=>$later) ORDER BY i"))
                    .containsExactly(listOf("1"), listOf("3"))
                f.reader().use { c -> c.readSession(Supplier {
                    val current = c.currentSnapshotId
                    val table = c.getTable("main", "t", current)!!
                    val file = c.getDataFiles(table.tableId, earlier).single()
                    assertThat(file.deleteFileFormat).isEqualTo("puffin")
                    assertThat(file.deleteFileFooterSize).isGreaterThan(0)
                    val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 2).single().resolvedPath
                    val bytes = Files.newInputStream(Path.of(path)).use { it.readNBytes(1_048_577) }
                    assertThat(bytes.size).isLessThanOrEqualTo(1_048_576)
                    assertThat(String(bytes.copyOfRange(0, 4), Charsets.US_ASCII)).isEqualTo("PFA1")
                    assertThat(String(bytes.copyOfRange(bytes.size - 4, bytes.size), Charsets.US_ASCII)).isEqualTo("PFA1")
                    val footerSize = ByteBuffer.wrap(bytes, bytes.size - 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val begin = bytes.size - 12 - footerSize
                    val footer = ObjectMapper().readTree(bytes.copyOfRange(begin, bytes.size - 12))
                    assertThat(footer["blobs"].map { it["properties"]["ducklake-snapshot-id"].asText() })
                        .containsExactly(earlier.toString(), later.toString())
                    assertThat(footer["blobs"].map { it["properties"]["cardinality"].asText() }).containsExactly("1", "2")
                    val candidate = c.getDeletionsBetween(table.tableId, later, later).single { !it.fullFileDelete }
                    assertThat(candidate.snapshotId).isEqualTo(earlier)
                    assertThat(candidate.previousDeletePath).isEqualTo(candidate.currentDeletePath)
                    assertThat(c.readMetadata().getFileReadMetadata(table.tableId, current).single().deleteRowGroupCount).isNull()
                    assertThatThrownBy { c.getLiveRowCount(table.tableId, earlier) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                }) }
            }
        }

        private fun snapshot(f: ReadAcceptanceFixture): Long =
            f.native("SELECT max(snapshot_id) FROM ducklake_snapshots('lake')").single().single()!!.toLong()
        private fun q(value: String) = "'${value.replace("'", "''")}'"
        private fun parquet(sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList {
                while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
            } } }
        }
    }
}
