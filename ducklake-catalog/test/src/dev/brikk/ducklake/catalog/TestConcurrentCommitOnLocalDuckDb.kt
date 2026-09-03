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
 * Concurrent commits on a LOCAL DUCKDB catalog file (TODO-rectify-from-eval.md C-B1). DuckDB's
 * JDBC driver reports constraint / transaction conflicts with `SQLState = null`, `errorCode = 0`
 * and messages unlike PostgreSQL's — and it auto-aborts the transaction, so a naive `rollback()`
 * afterwards throws and would mask the real error. Both must be handled for the optimistic
 * retry loop to work on this backend at all.
 *
 * The loser is parked (before any write) until the winner has committed from the same base
 * snapshot; DuckDB's snapshot isolation means the loser's in-transaction lineage check still sees
 * the old snapshot, so the collision surfaces as a DuckDB constraint / commit conflict that has to
 * be classified as retryable.
 */
class TestConcurrentCommitOnLocalDuckDb {
    companion object {
        private var fixture: TestingDucklakeLocalDuckDbCatalogFixture? = null
        private lateinit var catalog: JdbcDucklakeCatalog

        @JvmStatic
        @BeforeAll
        @Throws(Exception::class)
        fun setUpClass() {
            fixture = TestingDucklakeLocalDuckDbCatalogFixture()
            val catalogDir: Path = fixture!!.catalogDirectory("local-duckdb-concurrent")
            Files.createDirectories(catalogDir)
            val catalogFile = catalogDir.resolve("lake.db")
            val dataDir = catalogDir.resolve("data")
            Files.createDirectories(dataDir)
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSTALL ducklake")
                    stmt.execute("LOAD ducklake")
                    stmt.execute(
                        "ATTACH 'ducklake:" + catalogFile.toAbsolutePath() + "' AS lake " +
                            "(DATA_PATH '" + dataDir.toAbsolutePath() + "')",
                    )
                    stmt.execute("DETACH lake")
                }
            }
            catalog = JdbcDucklakeCatalog(
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
            if (::catalog.isInitialized) {
                catalog.close()
            }
            fixture?.close()
        }
    }

    @Test
    fun loserRetriesAfterWinnerCommitsFromTheSameBaseSnapshot() {
        val before = catalog.currentSnapshotId
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.createSchema("duck_winner") },
            Runnable { catalog.createSchema("duck_loser") },
        )
        assertThat(result.loserException)
            .`as`("loser's DuckDB conflict must be classified as retryable and succeed on retry")
            .isNull()
        assertThat(result.loserAttemptCount).`as`("parked attempt + one retry").isEqualTo(2)

        val after = catalog.currentSnapshotId
        assertThat(after).isEqualTo(before + 2)
        val schemas = catalog.listSchemas(after).map { it.schemaName }
        assertThat(schemas).contains("duck_winner", "duck_loser")
        // Distinct ids: the loser re-read next_catalog_id on retry.
        assertThat(catalog.getSchema("duck_winner", after)!!.schemaId)
            .isNotEqualTo(catalog.getSchema("duck_loser", after)!!.schemaId)
    }

    @Test
    fun duelingTableStatsUpdatesRetryToo() {
        // Two inserts into the same table both UPDATE ducklake_table_stats — DuckDB raises
        // "Conflict on update!" for the loser (a write-write conflict, not a duplicate key).
        catalog.createSchema("duck_stats")
        catalog.createTable(
            "duck_stats", "t",
            listOf(TableColumnSpec.leaf("id", "int32", true)),
            null, null,
        )
        val snapshot = catalog.currentSnapshotId
        val tableId = catalog.getTable("duck_stats", "t", snapshot)!!.tableId
        val idCol = catalog.getTableColumns(tableId, snapshot).single().columnId
        fun fragment(tag: String) = DucklakeWriteFragment(
            "duck_stats/t/$tag.parquet", 100L, 0L, 1L,
            listOf(DucklakeFileColumnStats(idCol, 8L, 1L, 0L, "1", "1", false)),
        )
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.commitInsert(tableId, listOf(fragment("winner"))) },
            Runnable { catalog.commitInsert(tableId, listOf(fragment("loser"))) },
        )
        assertThat(result.loserException).`as`("write-write conflict classified as retryable").isNull()
        assertThat(result.loserAttemptCount).isEqualTo(2)
        assertThat(catalog.getDataFiles(tableId, catalog.currentSnapshotId)).hasSize(2)
        assertThat(catalog.getTableStats(tableId)!!.recordCount).isEqualTo(2L)
    }
}
