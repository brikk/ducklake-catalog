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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * `commitDelete` / `commitMerge` supersede a data file's active delete file with the caller's
 * cumulative one. The caller built that union from the delete file active at ITS read snapshot,
 * so a delete file another writer committed after that read — but before this commit — would be
 * superseded by a file that lacks its positions, resurrecting rows. The commit must therefore
 * abort, non-retryably, when a touched data file has a delete file newer than the read snapshot
 * (TODO-rectify-from-eval.md C-B3). Upstream's consolidated delete files keep the OLD
 * `begin_snapshot` and carry the newest deletion in `partial_max`, so that column is checked too
 * (part of R-B4).
 *
 * Sequential — no concurrency harness needed: "another writer" is simply an earlier commit whose
 * snapshot is newer than the read snapshot the second caller claims.
 */
class TestJdbcDucklakeCatalogDeleteReadSnapshotGuard {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog
        private var tableId: Long = 0
        private var fileA: Long = 0
        private var fileB: Long = 0

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "delete-read-guard")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            }
            catalog = JdbcDucklakeCatalog(config)
            val snapshot = catalog.currentSnapshotId
            tableId = catalog.getTable("test_schema", "partitioned_table", snapshot)!!.tableId
            val files = catalog.getDataFiles(tableId, snapshot).map { it.dataFileId }.sorted()
            check(files.size >= 2) { "partitioned_table should have >= 2 data files, got $files" }
            fileA = files[0]
            fileB = files[1]
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::catalog.isInitialized) {
                catalog.close()
            }
            if (::server.isInitialized) {
                server.close()
            }
        }
    }

    private fun fragment(dataFileId: Long, tag: String, count: Long) =
        DucklakeDeleteFragment(dataFileId, "test_data/guard_$tag.parquet", count, 256L, 64L, /* newDeleteCount */ 1L)

    private fun activeDeletePath(dataFileId: Long): String? =
        catalog.getDataFiles(tableId, catalog.currentSnapshotId).single { it.dataFileId == dataFileId }.deleteFilePath

    @Test
    fun staleReadSnapshotAbortsNonRetryablyAndLeavesCatalogUntouched() {
        val read = catalog.currentSnapshotId
        // "Another writer" deletes from file A at read+1.
        catalog.commitDelete(tableId, listOf(fragment(fileA, "first", 1)), read)
        val afterFirst = catalog.currentSnapshotId
        assertThat(afterFirst).isEqualTo(read + 1)
        assertThat(activeDeletePath(fileA)).isEqualTo("test_data/guard_first.parquet")

        // Our commit was planned at `read`, before that delete: it must not supersede it.
        assertThatThrownBy { catalog.commitDelete(tableId, listOf(fragment(fileA, "stale", 2)), read) }
            .isInstanceOf(LogicalConflictException::class.java)
            .hasMessageContaining(fileA.toString())
            .hasMessageContaining("read snapshot $read")
            .satisfies({ e -> assertThat((e as TransactionConflictException).retryable()).isFalse() })
        assertThat(catalog.currentSnapshotId).`as`("aborted commit minted no snapshot").isEqualTo(afterFirst)
        assertThat(activeDeletePath(fileA)).`as`("the other writer's delete file is still the active one")
            .isEqualTo("test_data/guard_first.parquet")

        // Re-planned from the current snapshot, the same shape of commit goes through.
        catalog.commitDelete(tableId, listOf(fragment(fileA, "replanned", 2)), afterFirst)
        assertThat(activeDeletePath(fileA)).isEqualTo("test_data/guard_replanned.parquet")
        assertThat(catalog.currentSnapshotId).isEqualTo(afterFirst + 1)
    }

    @Test
    fun mergeDeleteHalfIsGuardedToo() {
        val read = catalog.currentSnapshotId
        catalog.commitDelete(tableId, listOf(fragment(fileB, "merge_first", 1)), read)
        assertThatThrownBy {
            catalog.commitMerge(tableId, listOf(fragment(fileB, "merge_stale", 2)), emptyList(), read)
        }.isInstanceOf(LogicalConflictException::class.java).hasMessageContaining(fileB.toString())
        assertThat(activeDeletePath(fileB)).isEqualTo("test_data/guard_merge_first.parquet")
    }

    @Test
    fun otherDataFilesAreNotContended() {
        val read = catalog.currentSnapshotId
        catalog.commitDelete(tableId, listOf(fragment(fileA, "a_only", 3)), read)
        // A stale read is fine for a data file whose delete state did not move.
        val files = catalog.getDataFiles(tableId, catalog.currentSnapshotId).map { it.dataFileId }.sorted()
        val untouched = files.first { it != fileA && it != fileB }
        catalog.commitDelete(tableId, listOf(fragment(untouched, "untouched", 1)), read)
        assertThat(activeDeletePath(untouched)).isEqualTo("test_data/guard_untouched.parquet")
    }

    @Test
    fun consolidatedDeleteFileIsDetectedViaPartialMax() {
        // Upstream's consolidated shape: the row keeps the OLD begin_snapshot and records the newest
        // folded deletion in partial_max. Simulate one via SQL on simple_table (no other delete
        // files in this class touch it, so only partial_max can trip the guard here).
        val snapshot = catalog.currentSnapshotId
        val simpleTableId = catalog.getTable("test_schema", "simple_table", snapshot)!!.tableId
        val target = catalog.getDataFiles(simpleTableId, snapshot).first().dataFileId
        val read = snapshot - 1
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO ducklake_delete_file (delete_file_id, table_id, begin_snapshot, end_snapshot, data_file_id, " +
                        "path, path_is_relative, format, delete_count, file_size_bytes, footer_size, encryption_key, partial_max) " +
                        "VALUES (777001, $simpleTableId, 1, NULL, $target, 'consolidated.parquet', true, 'parquet', 2, 10, 4, NULL, $snapshot)",
                )
            }
        }
        try {
            assertThatThrownBy { catalog.commitDelete(simpleTableId, listOf(fragment(target, "vs_consolidated", 3)), read) }
                .isInstanceOf(LogicalConflictException::class.java)
                .hasMessageContaining(target.toString())
            // Read at/after the newest folded deletion: allowed (and supersedes the consolidated row).
            catalog.commitDelete(simpleTableId, listOf(fragment(target, "vs_consolidated_ok", 3)), snapshot)
            val active = catalog.getDataFiles(simpleTableId, catalog.currentSnapshotId).single { it.dataFileId == target }
            assertThat(active.deleteFilePath).isEqualTo("test_data/guard_vs_consolidated_ok.parquet")
        }
        finally {
            DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
                c.createStatement().use { st -> st.executeUpdate("DELETE FROM ducklake_delete_file WHERE delete_file_id = 777001") }
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyOverloadStillCommits() {
        val read = catalog.currentSnapshotId
        catalog.commitDelete(tableId, listOf(fragment(fileB, "legacy_first", 1)), read)
        // No read snapshot -> guard degrades to the commit-time base: the concurrent delete is
        // NOT detected. This pins the (documented) legacy behaviour, not a desirable one.
        catalog.commitDelete(tableId, listOf(fragment(fileB, "legacy_second", 2)))
        assertThat(activeDeletePath(fileB)).isEqualTo("test_data/guard_legacy_second.parquet")
    }
}
