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
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** #1659: broad overlapping lifetimes, half-open endpoints and dropped-table GC at scale. */
class TestJdbcDucklakeCatalogExpirationScale {
    @Test
    fun localDuckDb(@TempDir directory: Path) {
        val file = directory.resolve("lake.db")
        DriverManager.getConnection("jdbc:duckdb:").use { native ->
            native.createStatement().use { st ->
                st.execute("INSTALL ducklake; LOAD ducklake")
                st.execute("ATTACH 'ducklake:$file' AS lake (DATA_PATH '$directory/data')")
            }
        }
        val config = DucklakeCatalogConfig().apply { catalogDatabaseUrl = "jdbc:duckdb:$file" }
        DriverManager.getConnection(config.catalogDatabaseUrl).use { conn -> exercise(config, conn, DirectMetadataQuery()) }
    }

    @Test
    fun nativeQuack(@TempDir directory: Path) {
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = "jdbc:duckdb:quack://${server.getHost()}:${server.getMappedPort()}?metadata_catalog=scale_meta"
                catalogDatabasePassword = server.getToken()
                dataPath = directory.resolve("data").toString()
            }
            val url = QuackBackedDuckDbCatalogUrl.parse(config.catalogDatabaseUrl!!, config.catalogDatabasePassword, config.dataPath)
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { it.execute(url.connectionInitSql()) }
                exercise(config, conn, QuackWrappedMetadataQuery(url.metadataCatalog()))
            }
        }
    }

    private fun exercise(config: DucklakeCatalogConfig, connection: Connection, metadata: MetadataQuery) {
        JdbcDucklakeCatalog(config).use { catalog ->
            val ctx = catalog.forConnection(connection)
            fun exec(sql: String) = metadata.execute(ctx, ctx.query(sql))
            fun count(table: String) = metadata.fetch(ctx, ctx.select(DSL.count()).from(DSL.table(DSL.name(table)))) { it.get(0, Long::class.java) }.single()
            // Test-only seed: no actual Parquet files or destructive filesystem operations.
            exec("INSERT INTO ducklake_snapshot (snapshot_id, schema_version, next_catalog_id, next_file_id) " +
                "SELECT range, 0, 100, 3000 FROM range(1, 3001)")
            exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                "VALUES (20, 0, 1, NULL, 'live/', true), (30, 0, 1, 10, 'dropped/', true)")
            exec("INSERT INTO ducklake_data_file (data_file_id, table_id, begin_snapshot, end_snapshot, path, path_is_relative, file_format) " +
                "SELECT range, CASE WHEN range % 4 = 3 THEN 30 ELSE 20 END, 2, " +
                "CASE range % 4 WHEN 0 THEN 3000 WHEN 1 THEN 50 WHEN 2 THEN 1000 ELSE NULL END, " +
                "'data-' || range || '.parquet', true, 'parquet' FROM range(1, 1001)")
            exec("INSERT INTO ducklake_delete_file (delete_file_id, data_file_id, table_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                "SELECT data_file_id + 1000, data_file_id, table_id, 2, NULL, 'delete-' || data_file_id || '.parquet', true FROM ducklake_data_file")
            assertThat(catalog.currentSnapshotId).isEqualTo(3000L)
            // Large survivor set must retain all files, including files of a dropped table.
            assertThat(catalog.expireSnapshots(setOf(1500L)).scheduledFileCount).isZero()
            assertThat(count("ducklake_data_file")).isEqualTo(1000L)
            // Keep begin endpoint 1000; end=1000 is NOT visible there. Latest is never expired.
            val removed = (1L..2999L).filter { it != 1000L && it != 2000L && it != 1500L }.toSet()
            assertThat(catalog.expireSnapshots(removed).scheduledFileCount).isEqualTo(1500)
            assertThat(count("ducklake_data_file")).isEqualTo(250L)
            assertThat(count("ducklake_delete_file")).isEqualTo(250L)
            assertThat(count("ducklake_files_scheduled_for_deletion")).isEqualTo(1500L)
            assertThat(catalog.getDataFiles(20, 1000).map { it.dataFileId }).containsExactlyElementsOf((4L..1000L step 4).toList())
            assertThat(catalog.getDataFiles(20, 2000)).hasSize(250)
            assertThat(catalog.expireSnapshots(setOf(999L)).scheduledFileCount).isZero() // repeat cannot reschedule
        }
    }
}
