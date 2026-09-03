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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Concurrent commits on a MYSQL catalog (TODO-rectify-from-eval.md C-B2). Two writers that start
 * from the same base snapshot allocate the same `schema_id` from `next_catalog_id`; under
 * REPEATABLE READ the loser's in-transaction lineage check cannot see the winner, so the collision
 * surfaces as `Duplicate entry '<id>' for key 'ducklake_schema.PRIMARY'` on the loser's INSERT —
 * a message that names neither `_pkey` nor `ducklake_schema.schema_id`. It must still be
 * classified as a retryable conflict.
 */
class TestConcurrentCommitOnMySql {
    companion object {
        private var server: TestingDucklakeMySqlCatalogServer? = null
        private lateinit var catalog: JdbcDucklakeCatalog
        private lateinit var dataDir: Path

        @JvmStatic
        @BeforeAll
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakeMySqlCatalogServer()
            dataDir = Files.createTempDirectory("mysql-concurrent-data")
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSTALL ducklake")
                    stmt.execute("LOAD ducklake")
                    stmt.execute("INSTALL mysql")
                    stmt.execute("LOAD mysql")
                    stmt.execute(
                        "ATTACH '" + server!!.getDuckDbAttachUri() + "' AS lake " +
                            "(DATA_PATH '" + dataDir.toAbsolutePath() + "')",
                    )
                    stmt.execute("DETACH lake")
                }
            }
            catalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = server!!.getJdbcUrl()
                    catalogDatabaseUser = server!!.getUser()
                    catalogDatabasePassword = server!!.getPassword()
                    dataPath = dataDir.toAbsolutePath().toString()
                    maxCatalogConnections = 3
                },
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::catalog.isInitialized) {
                catalog.close()
            }
            server?.close()
        }
    }

    @Test
    fun catalogIdCollisionIsRetried() {
        val before = catalog.currentSnapshotId
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.createSchema("my_winner") },
            Runnable { catalog.createSchema("my_loser") },
        )
        assertThat(result.loserException)
            .`as`("MySQL 'Duplicate entry ... for key ducklake_schema.PRIMARY' must be retried")
            .isNull()
        assertThat(result.loserAttemptCount).`as`("parked attempt + one retry").isEqualTo(2)

        val after = catalog.currentSnapshotId
        assertThat(after).isEqualTo(before + 2)
        assertThat(catalog.listSchemas(after).map { it.schemaName }).contains("my_winner", "my_loser")
        assertThat(catalog.getSchema("my_winner", after)!!.schemaId)
            .isNotEqualTo(catalog.getSchema("my_loser", after)!!.schemaId)
    }

    @Test
    fun fileIdCollisionIsRetried() {
        catalog.createSchema("my_files")
        catalog.createTable("my_files", "t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val snapshot = catalog.currentSnapshotId
        val tableId = catalog.getTable("my_files", "t", snapshot)!!.tableId
        val idCol = catalog.getTableColumns(tableId, snapshot).single().columnId
        fun fragment(tag: String) = DucklakeWriteFragment(
            "my_files/t/$tag.parquet", 100L, 0L, 1L,
            listOf(DucklakeFileColumnStats(idCol, 8L, 1L, 0L, "1", "1", false)),
        )
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.commitInsert(tableId, listOf(fragment("winner"))) },
            Runnable { catalog.commitInsert(tableId, listOf(fragment("loser"))) },
        )
        assertThat(result.loserException).`as`("ducklake_data_file.PRIMARY collision retried").isNull()
        assertThat(result.loserAttemptCount).isEqualTo(2)
        assertThat(catalog.getDataFiles(tableId, catalog.currentSnapshotId)).hasSize(2)
    }
}
