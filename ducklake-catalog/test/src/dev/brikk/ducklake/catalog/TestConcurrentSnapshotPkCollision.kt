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
import java.sql.DriverManager

/**
 * Q-5: the snapshot-primary-key collision path (TODO C-B1/C-B2). Both writers read the same base
 * snapshot, both run their mutations, both PASS the lineage check — and then race on the
 * `ducklake_snapshot` INSERT. The loser's duplicate key must be classified as a retryable
 * [TransactionConflictException] and its retry must land on the next snapshot with fresh ids.
 * Exercised on PostgreSQL and on a local DuckDB catalog file, whose driver reports the collision
 * very differently (no SQLState, auto-aborted transaction).
 */
class TestConcurrentSnapshotPkCollision {
    companion object {
        private var server: TestingDucklakePostgreSqlCatalogServer? = null
        private var pgCatalog: JdbcDucklakeCatalog? = null
        private var fixture: TestingDucklakeLocalDuckDbCatalogFixture? = null
        private var duckCatalog: JdbcDucklakeCatalog? = null

        @JvmStatic
        @BeforeAll
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            val isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server!!, "snapshot-pk-collision")
            pgCatalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = isolated.jdbcUrl
                    catalogDatabaseUser = isolated.user
                    catalogDatabasePassword = isolated.password
                    dataPath = isolated.dataDir.toAbsolutePath().toString()
                    maxCatalogConnections = 5
                },
            )

            fixture = TestingDucklakeLocalDuckDbCatalogFixture()
            val catalogDir = fixture!!.catalogDirectory("snapshot-pk-collision")
            Files.createDirectories(catalogDir)
            val catalogFile = catalogDir.resolve("lake.db")
            val dataDir = catalogDir.resolve("data")
            Files.createDirectories(dataDir)
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSTALL ducklake")
                    stmt.execute("LOAD ducklake")
                    stmt.execute(
                        "ATTACH 'ducklake:${catalogFile.toAbsolutePath()}' AS lake (DATA_PATH '${dataDir.toAbsolutePath()}')",
                    )
                    stmt.execute("DETACH lake")
                }
            }
            duckCatalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = "jdbc:duckdb:" + catalogFile.toAbsolutePath()
                    dataPath = dataDir.toAbsolutePath().toString()
                    maxCatalogConnections = 3
                },
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            pgCatalog?.close()
            server?.close()
            duckCatalog?.close()
            fixture?.close()
        }
    }

    @Test
    fun postgres_loserCollidingOnTheSnapshotPrimaryKeyRetriesAndLands() {
        assertCollisionIsRetried(pgCatalog!!, "pg")
    }

    @Test
    fun localDuckDb_loserCollidingOnTheSnapshotPrimaryKeyRetriesAndLands() {
        assertCollisionIsRetried(duckCatalog!!, "duck")
    }

    private fun assertCollisionIsRetried(catalog: JdbcDucklakeCatalog, prefix: String) {
        catalog.createSchema("${prefix}_s")
        catalog.createTable("${prefix}_s", "t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val tableId = catalog.getTable("${prefix}_s", "t", catalog.currentSnapshotId)!!.tableId
        val colId = catalog.getTableColumns(tableId, catalog.currentSnapshotId).single().columnId
        val base = catalog.currentSnapshotId

        // Loser inserts a file (allocates from next_file_id); winner creates a schema (allocates
        // from next_catalog_id): the ONLY thing they collide on is the snapshot primary key.
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.createSchema("${prefix}_winner") },
            Runnable {
                catalog.commitInsert(
                    tableId,
                    listOf(
                        DucklakeWriteFragment(
                            "$prefix/loser.parquet", 100L, 0L, 3L,
                            listOf(DucklakeFileColumnStats(colId, 12L, 3L, 0L, "1", "3", null)),
                        ),
                    ),
                )
            },
            parkPoint = ConcurrentWriterHarness.ParkPoint.BEFORE_SNAPSHOT_INSERT,
        )

        assertThat(result.loserException)
            .`as`("the duplicate snapshot_id must be classified as a retryable conflict and the retry must succeed")
            .isNull()
        assertThat(result.loserAttemptCount).`as`("collided attempt + one retry").isEqualTo(2)

        val after = catalog.currentSnapshotId
        assertThat(after).`as`("winner at base+1, loser's retry at base+2").isEqualTo(base + 2)
        assertThat(catalog.getSchema("${prefix}_winner", after)).isNotNull()
        val files = catalog.getDataFiles(tableId, after)
        assertThat(files).extracting<String> { it.path }.containsExactly("$prefix/loser.parquet")
        assertThat(files.single().beginSnapshot).`as`("the retry re-read the base and committed on top of the winner").isEqualTo(after)
        // Snapshot ids are contiguous and the changes rows say who did what.
        val changes = catalog.listSnapshotChanges().associate { it.snapshotId to it.changesMade }
        assertThat(changes[base + 1]).contains("created_schema")
        assertThat(changes[after]).contains("inserted_into_table:$tableId")
    }
}
