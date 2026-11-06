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

/**
 * A drop that started before a concurrent rename of the same table/view committed must not silently
 * remove the renamed object (upstream duckdb/ducklake#1904, PeterVanHolland/ducklake#210).
 */
class TestConcurrentDropVsRename {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var catalog: JdbcDucklakeCatalog

        @JvmStatic
        @BeforeAll
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            val isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "concurrent-drop-rename")
            catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            })
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::catalog.isInitialized) catalog.close()
            if (::server.isInitialized) server.close()
        }
    }

    @Test
    fun aDropCommittingAfterAConcurrentTableRenameDoesNotRemoveTheRenamedTable() {
        catalog.createTable("test_schema", "dvr_t", listOf(TableColumnSpec.leaf("id", "int32", false)), null, null)
        val tableId = catalog.getTable("test_schema", "dvr_t", catalog.currentSnapshotId)!!.tableId
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.renameTable(tableId, "test_schema", "dvr_u") },
            Runnable { catalog.dropTable("test_schema", "dvr_t") },
        )
        val latest = catalog.currentSnapshotId
        assertThat(catalog.getTable("test_schema", "dvr_u", latest)).`as`("renamed table survives").isNotNull
        assertThat(result.loserException).`as`("the stale drop must fail, not drop the renamed table").isNotNull
        // The loser really raced: it was parked once, then retried against the winner's commit.
        assertThat(result.loserAttemptCount).isGreaterThanOrEqualTo(2)
        assertThat(result.loserException).isInstanceOf(DucklakeEntityNotFoundException::class.java)
    }

    @Test
    fun aDropCommittingAfterAConcurrentViewRenameDoesNotRemoveTheRenamedView() {
        catalog.createView("test_schema", "dvr_v", "SELECT 1 AS x", "duckdb", emptyList(), emptyMap())
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.renameView("test_schema", "dvr_v", "test_schema", "dvr_w") },
            Runnable { catalog.dropView("test_schema", "dvr_v") },
        )
        val latest = catalog.currentSnapshotId
        assertThat(catalog.getView("test_schema", "dvr_w", latest)).`as`("renamed view survives").isNotNull
        assertThat(result.loserException).`as`("the stale drop must fail, not drop the renamed view").isNotNull
        // The loser really raced: it was parked once, then retried against the winner's commit.
        assertThat(result.loserAttemptCount).isGreaterThanOrEqualTo(2)
        assertThat(result.loserException).isInstanceOf(DucklakeEntityNotFoundException::class.java)
    }
}
