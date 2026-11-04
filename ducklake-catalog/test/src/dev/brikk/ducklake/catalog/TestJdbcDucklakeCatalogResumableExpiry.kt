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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/** R1: expiry removes snapshots first, then cleans up in bounded, resumable pages. */
class TestJdbcDucklakeCatalogResumableExpiry {
    @Test
    fun aCappedExpiryResumesToTheSameEndStateWithoutSchedulingLiveFiles(@TempDir directory: Path) {
        val file = directory.resolve("lake.db")
        val data = directory.resolve("data")
        DriverManager.getConnection("jdbc:duckdb:").use { native ->
            native.createStatement().use { st ->
                st.execute("INSTALL ducklake; LOAD ducklake")
                st.execute("ATTACH 'ducklake:$file' AS lake (DATA_PATH '$data', DUCKLAKE_VERSION '1.0')")
            }
        }
        val config = DucklakeCatalogConfig().apply { catalogDatabaseUrl = "jdbc:duckdb:$file" }
        // 2,500 data files (more than one cleanup page) plus one delete file each; table 30 is dropped.
        JdbcDucklakeCatalog(config).use { catalog -> seed(catalog, config.catalogDatabaseUrl!!) }
        JdbcDucklakeCatalog(config).use { catalog ->
            val removed = (1L..2999L).filter { it != 1000L && it != 2000L && it != 1500L }.toSet()
            val first = catalog.expireSnapshots(removed, 100)
            assertThat(first.expiredSnapshotCount).isEqualTo(removed.size)
            assertThat(first.scheduledFileCount).isLessThanOrEqualTo(100) // strict budget
            assertThat(first.cleanupComplete).isFalse()
            // The snapshots are gone even though cleanup is incomplete.
            assertThat(count(file, "ducklake_snapshot")).isEqualTo(5L) // native init 0, kept 1000/1500/2000, latest
            var scheduled = first.scheduledFileCount
            var rounds = 0
            while (true) {
                val next = catalog.cleanUpExpiredObjects(100)
                assertThat(next.scheduledFileCount).isLessThanOrEqualTo(100)
                scheduled += next.scheduledFileCount
                rounds++
                if (next.cleanupComplete) break
                check(rounds < 200) { "cleanup did not converge" }
            }
            assertThat(rounds).isGreaterThan(5)
            // Same end state as one uncapped expiry: 3/4 of the files (and their delete files) are dead.
            assertThat(scheduled).isEqualTo(3750)
            assertThat(count(file, "ducklake_data_file")).isEqualTo(625L)
            assertThat(count(file, "ducklake_delete_file")).isEqualTo(625L)
            assertThat(count(file, "ducklake_files_scheduled_for_deletion")).isEqualTo(3750L)
            assertThat(scalar(file, "SELECT count(DISTINCT data_file_id) FROM ducklake_files_scheduled_for_deletion")).isEqualTo(3750L)
            // Absolute, table-resolved paths; nothing still referenced by a live file was scheduled.
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_files_scheduled_for_deletion " +
                "WHERE path_is_relative OR NOT starts_with(path, '$data')")).isZero()
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_files_scheduled_for_deletion s " +
                "JOIN ducklake_data_file d ON d.data_file_id = s.data_file_id")).isZero()
            assertThat(catalog.getDataFiles(20, 1000).map { it.dataFileId })
                .containsExactlyElementsOf((4L..2500L step 4).toList())
            // The dropped table's metadata is gone; the live table's is not.
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_table WHERE table_id = 30")).isZero()
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_table WHERE table_id = 20")).isEqualTo(1L)
            // Idempotent: nothing left, nothing rescheduled.
            val again = catalog.cleanUpExpiredObjects(100)
            assertThat(again.scheduledFileCount).isZero()
            assertThat(again.cleanupComplete).isTrue()
        }
    }

    @Test
    fun aZeroBudgetExpiresSnapshotsAndDefersAllCleanup(@TempDir directory: Path) {
        val file = directory.resolve("lake.db")
        DriverManager.getConnection("jdbc:duckdb:").use { native ->
            native.createStatement().use { st ->
                st.execute("INSTALL ducklake; LOAD ducklake")
                st.execute("ATTACH 'ducklake:$file' AS lake (DATA_PATH '${directory.resolve("data")}', DUCKLAKE_VERSION '1.0')")
            }
        }
        val config = DucklakeCatalogConfig().apply { catalogDatabaseUrl = "jdbc:duckdb:$file" }
        JdbcDucklakeCatalog(config).use { catalog ->
            seed(catalog, config.catalogDatabaseUrl!!)
            val result = catalog.expireSnapshots((1L..2999L).filter { it != 1000L }.toSet(), 0)
            assertThat(result.cleanupComplete).isFalse()
            assertThat(result.scheduledFileCount).isZero()
            assertThat(count(file, "ducklake_data_file")).isEqualTo(2500L)
            assertThat(catalog.cleanUpExpiredObjects(Int.MAX_VALUE).cleanupComplete).isTrue()
            assertThat(count(file, "ducklake_data_file")).isLessThan(2500L)
        }
    }

    @Test
    fun aDataFileWithMoreDeleteFilesThanTheBudgetStillMakesProgress(@TempDir directory: Path) {
        val (file, config) = native(directory)
        JdbcDucklakeCatalog(config).use { catalog ->
            DriverManager.getConnection(config.catalogDatabaseUrl!!).use { conn ->
                val ctx = catalog.forConnection(conn)
                fun exec(sql: String) = DirectMetadataQuery().execute(ctx, ctx.query(sql))
                exec("INSERT INTO ducklake_snapshot (snapshot_id, schema_version, next_catalog_id, next_file_id) " +
                    "SELECT range, 0, 100, 100 FROM range(1, 11)")
                exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                    "VALUES (20, 0, 1, NULL, 'live/', true)")
                exec("INSERT INTO ducklake_data_file (data_file_id, table_id, begin_snapshot, end_snapshot, path, path_is_relative, file_format) " +
                    "VALUES (1, 20, 2, 5, 'd1.parquet', true, 'parquet'), (2, 20, 2, 5, 'd2.parquet', true, 'parquet')")
                exec("INSERT INTO ducklake_delete_file (delete_file_id, data_file_id, table_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                    "SELECT 10 + range, 1, 20, 2, NULL, 'x' || range || '.parquet', true FROM range(0, 7)")
            }
            val steps = ArrayList<Int>()
            var result = catalog.expireSnapshots((1L..9L).toSet(), 3)
            steps += result.scheduledFileCount
            while (!result.cleanupComplete) {
                result = catalog.cleanUpExpiredObjects(3)
                steps += result.scheduledFileCount
                check(steps.size < 20) { "no progress: $steps" }
            }
            // 7 delete files of file 1, then file 1, then file 2; never more than 3 per call.
            assertThat(steps).allMatch { it <= 3 }
            assertThat(steps.sum()).isEqualTo(9)
            assertThat(count(file, "ducklake_data_file")).isZero()
            assertThat(count(file, "ducklake_delete_file")).isZero()
        }
    }

    @Test
    fun aCleanupFailureAfterTheCommittedExpiryIsTypedAndResumable(@TempDir directory: Path) {
        val (file, config) = native(directory)
        JdbcDucklakeCatalog(config).use { catalog ->
            seed(catalog, config.catalogDatabaseUrl!!)
            val queue = "ducklake_files_scheduled_for_deletion"
            fun sql(statement: String) =
                DriverManager.getConnection(config.catalogDatabaseUrl!!).use { c -> c.createStatement().use { it.execute(statement) } }
            sql("ALTER TABLE $queue RENAME TO parked_queue")
            val removed = (1L..2999L).filter { it != 1000L }.toSet()
            assertThatThrownBy { catalog.expireSnapshots(removed) }
                .isInstanceOf(DucklakeExpiryCleanupIncompleteException::class.java)
                .hasMessageContaining("expiry is committed")
                .satisfies({ assertThat((it as DucklakeExpiryCleanupIncompleteException).expiredSnapshotCount).isEqualTo(removed.size) })
            assertThat(count(file, "ducklake_snapshot")).isEqualTo(3L) // native init 0, kept 1000, latest
            sql("ALTER TABLE parked_queue RENAME TO $queue")
            val finish = catalog.cleanUpExpiredObjects(Int.MAX_VALUE)
            assertThat(finish.cleanupComplete).isTrue()
            assertThat(finish.scheduledFileCount).isGreaterThan(0)
        }
    }

    @Test
    fun the014ResultCopySignaturesStillLink() {
        val type = ExpireSnapshotsResult::class.java
        val copy = type.getMethod("copy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val incomplete = ExpireSnapshotsResult(1, 2, false)
        assertThat(copy.invoke(incomplete, 3, 4)).isEqualTo(ExpireSnapshotsResult(3, 4, false))
        val bridge = type.getMethod("copy\$default", type, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Any::class.java)
        assertThat(java.lang.reflect.Modifier.isStatic(bridge.modifiers)).isTrue()
        assertThat(bridge.invoke(null, incomplete, 9, 0, 2, null)).isEqualTo(ExpireSnapshotsResult(9, 2, false))
        assertThat(type.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(1, 2).cleanupComplete).isTrue()
    }

    private fun native(directory: Path): Pair<Path, DucklakeCatalogConfig> {
        val file = directory.resolve("lake.db")
        DriverManager.getConnection("jdbc:duckdb:").use { native ->
            native.createStatement().use { st ->
                st.execute("INSTALL ducklake; LOAD ducklake")
                st.execute("ATTACH 'ducklake:$file' AS lake (DATA_PATH '${directory.resolve("data")}', DUCKLAKE_VERSION '1.0')")
            }
        }
        return file to DucklakeCatalogConfig().apply { catalogDatabaseUrl = "jdbc:duckdb:$file" }
    }

    private fun seed(catalog: JdbcDucklakeCatalog, url: String) {
        DriverManager.getConnection(url).use { conn ->
            val ctx = catalog.forConnection(conn)
            fun exec(sql: String) = DirectMetadataQuery().execute(ctx, ctx.query(sql))
            // Test-only seed: no actual Parquet files or destructive filesystem operations.
            exec("INSERT INTO ducklake_snapshot (snapshot_id, schema_version, next_catalog_id, next_file_id) " +
                "SELECT range, 0, 100, 6000 FROM range(1, 3001)")
            exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                "VALUES (20, 0, 1, NULL, 'live/', true), (30, 0, 1, 10, 'dropped/', true)")
            exec("INSERT INTO ducklake_data_file (data_file_id, table_id, begin_snapshot, end_snapshot, path, path_is_relative, file_format) " +
                "SELECT range, CASE WHEN range % 4 = 3 THEN 30 ELSE 20 END, 2, " +
                "CASE range % 4 WHEN 0 THEN 3000 WHEN 1 THEN 50 WHEN 2 THEN 1000 ELSE NULL END, " +
                "'data-' || range || '.parquet', true, 'parquet' FROM range(1, 2501)")
            exec("INSERT INTO ducklake_delete_file (delete_file_id, data_file_id, table_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                "SELECT data_file_id + 2500, data_file_id, table_id, 2, NULL, 'delete-' || data_file_id || '.parquet', true FROM ducklake_data_file")
        }
        assertThat(catalog.currentSnapshotId).isEqualTo(3000L)
    }

    private fun count(file: Path, table: String): Long = scalar(file, "SELECT count(*) FROM $table")

    private fun scalar(file: Path, sql: String): Long = DriverManager.getConnection("jdbc:duckdb:$file").use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } }
    }
}
