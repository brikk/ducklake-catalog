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

/** R3: after a catalog-wide expiry with no cleanup, tables are cleaned up one at a time. */
class TestJdbcDucklakeCatalogTableScopedCleanup {
    @Test
    fun tablesAreCleanedIndependentlyAndListedUntilDone(@TempDir directory: Path) {
        val file = directory.resolve("lake.db")
        DriverManager.getConnection("jdbc:duckdb:").use { native ->
            native.createStatement().use { st ->
                st.execute("INSTALL ducklake; LOAD ducklake")
                st.execute("ATTACH 'ducklake:$file' AS lake (DATA_PATH '${directory.resolve("data")}', DUCKLAKE_VERSION '1.0')")
            }
        }
        val url = "jdbc:duckdb:$file"
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply { catalogDatabaseUrl = url }).use { catalog ->
            DriverManager.getConnection(url).use { conn ->
                val ctx = catalog.forConnection(conn)
                fun exec(sql: String) = DirectMetadataQuery().execute(ctx, ctx.query(sql))
                exec("INSERT INTO ducklake_snapshot (snapshot_id, schema_version, next_catalog_id, next_file_id) " +
                    "SELECT range, 0, 100, 9000 FROM range(1, 101)")
                // Tables 20 and 21 are live with half their files retired at 50; table 30 was dropped at 10.
                exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                    "VALUES (20, 0, 1, NULL, 't20/', true), (21, 0, 1, NULL, 't21/', true), (30, 0, 1, 10, 't30/', true)")
                exec("INSERT INTO ducklake_data_file (data_file_id, table_id, begin_snapshot, end_snapshot, path, path_is_relative, file_format) " +
                    "SELECT range, CASE range % 3 WHEN 0 THEN 20 WHEN 1 THEN 21 ELSE 30 END, 2, " +
                    "CASE WHEN range % 3 = 2 THEN NULL WHEN range % 2 = 0 THEN 50 ELSE NULL END, " +
                    "'f' || range || '.parquet', true, 'parquet' FROM range(1, 3001)")
            }
            assertThat(catalog.listTablesWithExpiredFiles(null, 10)).isEmpty()
            val expired = catalog.expireSnapshots((1L..99L).toSet(), 0)
            assertThat(expired.cleanupComplete).isFalse()
            assertThat(catalog.listTablesWithExpiredFiles(null, 10)).containsExactly(20L, 21L, 30L)
            assertThat(catalog.listTablesWithExpiredFiles(20L, 1)).containsExactly(21L)

            val t20 = catalog.cleanUpExpiredObjects(20, Int.MAX_VALUE)
            assertThat(t20.cleanupComplete).isTrue()
            assertThat(t20.scheduledFileCount).isEqualTo(500)
            // Only table 20 was touched.
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_files_scheduled_for_deletion s " +
                "WHERE NOT starts_with(s.path, '${directory.resolve("data")}') OR s.path NOT LIKE '%/t20/%'")).isZero()
            assertThat(catalog.listTablesWithExpiredFiles(null, 10)).containsExactly(21L, 30L)

            // A capped per-table run resumes.
            var scheduled = 0
            do {
                val step = catalog.cleanUpExpiredObjects(30, 250)
                scheduled += step.scheduledFileCount
            } while (!step.cleanupComplete)
            assertThat(scheduled).isEqualTo(1000)
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_table WHERE table_id = 30")).isZero()
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_table WHERE table_id IN (20, 21)")).isEqualTo(2L)
            assertThat(catalog.listTablesWithExpiredFiles(null, 10)).containsExactly(21L)

            assertThat(catalog.cleanUpExpiredObjects(Int.MAX_VALUE).scheduledFileCount).isEqualTo(500)
            assertThat(catalog.listTablesWithExpiredFiles(null, 10)).isEmpty()
            assertThat(scalar(file, "SELECT count(*) FROM ducklake_data_file")).isEqualTo(1000L)
            assertThat(catalog.cleanUpExpiredObjects(20, 10).scheduledFileCount).isZero()
            assertThatThrownBy { catalog.listTablesWithExpiredFiles(null, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun scalar(file: Path, sql: String): Long = DriverManager.getConnection("jdbc:duckdb:$file").use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } }
    }
}
