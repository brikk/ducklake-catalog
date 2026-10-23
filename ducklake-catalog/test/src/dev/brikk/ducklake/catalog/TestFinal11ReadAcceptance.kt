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
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.function.Supplier
import java.nio.ByteBuffer
import java.util.zip.CRC32

internal class TestFinal11ReadAcceptance {
    @Nested inner class Local : Contract(Final11ReadRoute.LOCAL_DUCKDB, { ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract(Final11ReadRoute.NATIVE_QUACK, { ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract(Final11ReadRoute.JAVA_QUACK, { ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract(Final11ReadRoute.POSTGRES, { ReadAcceptanceFixture.Postgres() })
    @Nested inner class MySql : Contract(Final11ReadRoute.MYSQL, { ReadAcceptanceFixture.MySql() })

    abstract class Contract(private val route: Final11ReadRoute, private val fixture: () -> ReadAcceptanceFixture) {
        private fun <T> read(c: JdbcDucklakeCatalog, action: () -> T): T = c.readSession(Supplier { action() })

        @Test
        fun onePinnedPlanCombinesNativeFilesAndInlineValuesWithoutDoubleCounting() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',2); CREATE TABLE lake.t(i INTEGER,b BLOB,s VARCHAR); BEGIN; " +
                    "INSERT INTO lake.t VALUES(1,from_hex('005CFF'),NULL),(2,NULL,'NULL'); " +
                    "INSERT INTO lake.t VALUES(3,NULL,''),(4,NULL,'four'),(5,NULL,'five'),(6,NULL,'six'),(7,NULL,'seven'); COMMIT")
                if (route != Final11ReadRoute.MYSQL) {
                    assertThat(f.native("SELECT i FROM lake.t ORDER BY i")).containsExactlyElementsOf((1..7).map { listOf(it.toString()) })
                    val ids = f.native("SELECT rowid FROM lake.t ORDER BY rowid").map { it.single()!!.toLong() }
                    assertThat(ids).containsExactlyElementsOf((0L..6L).toList()) // Allocation is not SQL insertion order.
                }
                f.reader().use { c -> read(c) {
                    val profile = c.readBackendProfile()
                    assertThat(profile.accessRoute).isEqualTo(route.label)
                    val table = c.getTable("main", "t", profile.snapshotId)!!
                    val files = c.getDataFiles(table.tableId, profile.snapshotId)
                    assertThat(files).hasSize(1)
                    assertThat(files.single().recordCount).isEqualTo(5)
                    assertThat(c.getLiveRowCount(table.tableId, profile.snapshotId)).isEqualTo(7)
                    val columns = c.getTableColumns(table.tableId, profile.snapshotId)
                    val inline = c.getInlinedDataInfos(table.tableId, profile.snapshotId).flatMap {
                        c.readInlinedDataDecoded(table.tableId, it.schemaVersion, profile.snapshotId, columns)
                    }
                    assertThat(inline).hasSize(2)
                    assertThat(inline.map { it[0] }).containsExactly(1, 2)
                    assertThat(inline[0][1] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                    assertThat(inline[0][2]).isNull()
                    assertThat(inline[1]).containsExactly(2, null, "NULL")
                    val inlineIds = c.getInlinedDataInfos(table.tableId, profile.snapshotId).flatMap {
                        c.readInlinedRowIds(table.tableId, it.schemaVersion, profile.snapshotId)
                    }
                    val start = requireNotNull(files.single().rowIdStart)
                    val fileIds = (start until start + files.single().recordCount).toList()
                    assertThat((inlineIds + fileIds).sorted()).containsExactlyElementsOf((0L..6L).toList())
                    assertThat(inlineIds.toSet().intersect(fileIds.toSet())).isEmpty()
                    val owned = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 2).single()
                    assertThat(owned.fileId).isEqualTo(files.single().dataFileId)
                    assertThat(parquet("SELECT i FROM read_parquet(${q(owned.resolvedPath)}) ORDER BY i"))
                        .containsExactlyElementsOf((3..7).map { listOf(it.toString()) })
                    assertThat(c.getColumnStats(table.tableId, profile.snapshotId).map { it.minValue }).containsOnlyNulls()
                    assertThat(c.findDataFileIdsInRange(table.tableId, profile.snapshotId, ColumnRangePredicate(columns[0].columnId, "999", "1000")))
                        .containsExactly(files.single().dataFileId)
                    assertThat(c.readBackendProfile().snapshotId).isEqualTo(profile.snapshotId)
                } }
            }
        }

        @Test
        fun puffinDeleteVectorsKeepNullableCountsAndRealNativePathsWithoutInventingSplits() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); " +
                    "CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(100); DELETE FROM lake.t WHERE i<10")
                assertThat(f.native("SELECT i FROM lake.t ORDER BY i")).containsExactlyElementsOf((10..99).map { listOf(it.toString()) })
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val file = c.getDataFiles(table.tableId, snapshot).single()
                    assertThat(file.deleteFileFormat).isEqualTo("puffin")
                    val metadata = c.readMetadata().getFileReadMetadata(table.tableId, snapshot).single()
                    assertThat(metadata.recordCount).isEqualTo(100)
                    assertThat(metadata.deleteCount).isEqualTo(10)
                    assertThat(metadata.rowGroupCount).isEqualTo(1)
                    assertThat(metadata.deleteRowGroupCount).isNull()
                    val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 2).single().resolvedPath
                    assertThat(file.deleteFileFooterSize).isEqualTo(0)
                    assertBareDeletionVector(path)
                    assertThat(c.getLiveRowCount(table.tableId, snapshot)).isEqualTo(90)
                } }
                f.metadata("UPDATE ducklake_delete_file SET delete_count=NULL")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    assertThat(c.readMetadata().getFileReadMetadata(table.tableId, snapshot).single().deleteCount).isNull()
                    assertThatThrownBy { c.getLiveRowCount(table.tableId, snapshot) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("cannot assume zero")
                } }
                f.metadata("UPDATE ducklake_delete_file SET delete_count=200")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    assertThatThrownBy { c.getLiveRowCount(table.tableId, snapshot) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("cannot fabricate zero")
                } }
            }
        }

        @Test
        fun geometryIsDiscoverableAsFileMetadataWithoutClaimingInlineOrConnectorGeometryDecoding() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER,g GEOMETRY); " +
                    "INSERT INTO lake.t VALUES(1,'POINT(1 2)'::GEOMETRY),(2,NULL)")
                assertThat(f.native("SELECT i,g::VARCHAR FROM lake.t ORDER BY i"))
                    .containsExactly(listOf("1", "POINT (1 2)"), listOf("2", null))
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    assertThat(c.getTableColumns(table.tableId, snapshot).map { it.columnType.lowercase() }).containsExactly("int32", "geometry")
                    assertThat(c.getDataFiles(table.tableId, snapshot).single().recordCount).isEqualTo(2)
                    assertThat(c.readMetadata().getFileReadMetadata(table.tableId, snapshot).single().recordCount).isEqualTo(2)
                    assertThat(c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 2)).hasSize(1)
                } }
            }
        }

        @Test
        fun rollbackPreservesCallerFailureAndTheNextOwnedSessionCanPlanAgainWithoutWrites() {
            fixture().use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
                f.reader().use { c ->
                    val before = read(c) { c.readBackendProfile() }
                    val failure = IllegalStateException("R07 caller aborted planning")
                    assertThatThrownBy { read(c) { c.currentSnapshotId; throw failure } }.isSameAs(failure)
                    assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                        .hasMessageContaining("readSession")
                    read(c) {
                        assertThat(c.readBackendProfile()).isEqualTo(before)
                        assertThat(c.listSchemas(before.snapshotId).map { it.schemaName }).containsExactly("main")
                        assertThatThrownBy { c.createSchema("forbidden") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    }
                }
            }
        }

        @Test
        fun missingRequiredQueryColumnsPropagateAsFailuresNeverAsEmptySuccessfulPlans() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
                f.metadata("ALTER TABLE ducklake_data_file RENAME COLUMN file_format TO r07_held_format")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    assertThatThrownBy { c.getDataFiles(table.tableId, snapshot) }.isInstanceOf(DataAccessException::class.java)
                        .hasMessageContaining("file_format")
                } }
            }
        }

        @Test
        fun planningUsesBoundedPagesAndRejectsInvalidRequestsRatherThanTreatingThemAsKnownSets() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
                f.reader().use { c -> read(c) {
                    val profile = c.readBackendProfile()
                    val h = c.readHierarchy()
                    var cursor: Long? = null
                    val ids = mutableListOf<Long>()
                    var finished = false
                    repeat(3) {
                        if (!finished) {
                            val page = h.listRetainedFiles(DucklakeFileKind.DATA, cursor, 1)
                            assertThat(page.size).isLessThanOrEqualTo(1)
                            if (page.isEmpty()) finished = true else { ids.add(page.single().fileId); cursor = page.single().fileId }
                        }
                    }
                    assertThat(finished).isTrue()
                    assertThat(ids).hasSize(1).doesNotHaveDuplicates()
                    assertThatThrownBy { h.listRetainedFiles(DucklakeFileKind.DATA, null, 0) }.isInstanceOf(IllegalArgumentException::class.java)
                    assertThatThrownBy { h.listScheduledReferences(null, 1025) }.isInstanceOf(IllegalArgumentException::class.java)
                    assertThatThrownBy { h.listSchemas(profile.snapshotId + 1) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                        .hasMessageContaining("not visible")
                    assertThat(c.getSnapshot(profile.snapshotId + 1)).isNull()
                    val table = c.getTable("main", "t", profile.snapshotId)!!
                    assertThatThrownBy { c.getLiveRowCount(table.tableId, profile.snapshotId + 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("outside this owned read view")
                } }
            }
        }

        private fun q(value: String): String = "'${value.replace("'", "''")}'"
        private fun assertBareDeletionVector(path: String) {
            val bytes = Files.newInputStream(Path.of(path)).use { it.readNBytes(1_048_577) }
            assertThat(bytes.size).isBetween(12, 1_048_576)
            val length = ByteBuffer.wrap(bytes).int
            assertThat(length + 8).isEqualTo(bytes.size)
            assertThat(bytes.copyOfRange(4, 8)).containsExactly(0xd1.toByte(), 0xd3.toByte(), 0x39, 0x64)
            val checksum = CRC32().apply { update(bytes, 4, length) }.value
            val stored = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, 4 + length, 4).int)
            assertThat(stored).isEqualTo(checksum)
        }
        private fun parquet(sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList {
                while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
            } } }
        }
    }
}
