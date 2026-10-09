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
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Upstream duckdb/ducklake#1472: expiration must not erase an open writer's conflicts. */
class TestJdbcDucklakeCatalogExpiredConflictHistory {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        fun start() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "expired-conflict-history")
            catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            })
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            if (::catalog.isInitialized) catalog.close()
            if (::server.isInitialized) server.close()
        }
    }

    @Test
    fun expiredAlterStillRejectsTheParkedJvmWriterOnRetry() {
        catalog.createTable("test_schema", "alter_expiry", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val table = catalog.getTable("test_schema", "alter_expiry", catalog.currentSnapshotId)!!
        var expired = -1L
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable {
                catalog.addColumn(table.tableId, TableColumnSpec.leaf("winner", "int32", true))
                expired = catalog.currentSnapshotId
                catalog.createSchema("alter_expiry_marker") // keep the latest snapshot unexpired
                catalog.expireSnapshots(setOf(expired))
            },
            Runnable { catalog.addColumn(table.tableId, TableColumnSpec.leaf("loser", "int32", true)) },
        )

        assertThat(result.loserException).isInstanceOf(LogicalConflictException::class.java)
            .hasMessageContaining("altered it")
        assertThat((result.loserException as TransactionConflictException).retryable()).isFalse()
        assertThat(result.loserAttemptCount).isEqualTo(2)
        assertThat(catalog.getTableColumns(table.tableId, catalog.currentSnapshotId).map { it.columnName })
            .containsExactly("id", "winner")
        assertThat(catalog.getSnapshot(expired)).isNull()
        assertThat(catalog.listSnapshotChanges().single { it.snapshotId == expired }.changesMade)
            .isEqualTo("altered_table:${table.tableId}")
    }

    @Test
    fun retainedExpiredChangesDoNotRejectAnUnrelatedJvmWriter() {
        var expired = -1L
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable {
                catalog.createSchema("compatible_winner")
                expired = catalog.currentSnapshotId
                catalog.createSchema("compatible_marker")
                catalog.expireSnapshots(setOf(expired))
            },
            Runnable { catalog.createSchema("compatible_loser") },
        )
        assertThat(result.loserException).isNull()
        assertThat(result.loserAttemptCount).isEqualTo(2)
        assertThat(catalog.listSchemas(catalog.currentSnapshotId).map { it.schemaName })
            .contains("compatible_winner", "compatible_loser")
        assertThat(catalog.getSnapshot(expired)).isNull()
        assertThat(catalog.listSnapshotChanges().single { it.snapshotId == expired }.changesMade)
            .isEqualTo("created_schema:\"compatible_winner\"")
    }

    @Test
    fun repeatedExpirationAndRejectedLatestExpirationLeaveHistoryIntact() {
        catalog.createSchema("history_retention")
        val expired = catalog.currentSnapshotId
        catalog.createSchema("history_retention_marker")
        val latest = catalog.currentSnapshotId
        val changes = catalog.listSnapshotChanges()

        assertThatThrownBy { catalog.expireSnapshots(setOf(expired, latest)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("latest snapshot")
        assertThat(catalog.getSnapshot(expired)).isNotNull()
        assertThat(catalog.listSnapshotChanges()).isEqualTo(changes)

        catalog.expireSnapshots(setOf(expired))
        catalog.expireSnapshots(setOf(expired))
        catalog.expireSnapshots(emptySet())
        assertThat(catalog.getSnapshot(expired)).isNull()
        assertThat(catalog.listSnapshotChanges()).isEqualTo(changes)
        assertThat(catalog.currentSnapshotId).isEqualTo(latest)
        assertThat(catalog.listExpirableSnapshots(null, null)).doesNotContain(expired, latest)
    }

    @Test
    fun nativePositionalDeleteOfACompactedFileCannotSilentlyDisappearAfterExpiry() =
        assertNativeDeleteConflicts("positional_expiry", initialRows = 2)

    @Test
    fun nativeWholeFileDeleteOfACompactedFileCannotSilentlyDisappearAfterExpiry() =
        assertNativeDeleteConflicts("whole_file_expiry", initialRows = 1)

    private fun assertNativeDeleteConflicts(name: String, initialRows: Int) {
        withDuckDb { stale ->
            stale.exec("CREATE TABLE lake.test_schema.$name(id INTEGER)")
            stale.exec("INSERT INTO lake.test_schema.$name SELECT range FROM range(1, ${initialRows + 1})")
            val read = catalog.currentSnapshotId
            val table = catalog.getTable("test_schema", name, read)!!
            val sourceIds = catalog.getDataFiles(table.tableId, read).map { it.dataFileId }
            stale.exec("BEGIN")
            assertThat(stale.ids(name)).isEqualTo((1..initialRows).map { it.toLong() })

            withDuckDb { competitor ->
                competitor.exec("INSERT INTO lake.test_schema.$name VALUES (${initialRows + 1})")
                competitor.exec("CALL ducklake_merge_adjacent_files('lake', '$name', schema => 'test_schema')")
            }
            val compacted = catalog.currentSnapshotId
            assertThat(compacted).isEqualTo(read + 2)
            assertThat(catalog.getDataFilesByIds(table.tableId, sourceIds)).isEmpty()
            catalog.createSchema("${name}_marker")
            val latest = catalog.currentSnapshotId
            val expired = (read + 1..compacted).toSet()
            assertThat(catalog.expireSnapshots(expired).expiredSnapshotCount).isEqualTo(2)
            assertThat(catalog.listSnapshots().map { it.snapshotId }).doesNotContainAnyElementsOf(expired)

            // Both the positional and whole-file delete are planned from the pre-compaction view.
            stale.exec("DELETE FROM lake.test_schema.$name WHERE id = 1")
            val commitError = runCatching { stale.exec("COMMIT") }.exceptionOrNull()
            val rowsAfterCommit = withDuckDb { it.ids(name) }
            assertThat(rowsAfterCommit).isEqualTo((1..initialRows + 1).map { it.toLong() })
            assertThat(commitError).`as`("COMMIT must conflict instead of succeeding with rows $rowsAfterCommit unchanged")
                .isInstanceOf(SQLException::class.java).hasMessageContaining("conflict")
            assertThat(catalog.currentSnapshotId).isEqualTo(latest)
            assertThat(catalog.getDataFiles(table.tableId, latest).all { it.deleteFilePath == null }).isTrue()
            assertThat(catalog.listSnapshotChanges().map { it.snapshotId }).containsAll(expired)
        }
    }

    private fun <T> withDuckDb(action: (Connection) -> T): T = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        connection.exec("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
        connection.exec("ATTACH '${isolated.duckDbAttachUri.replace("'", "''")}' AS lake " +
            "(DATA_PATH '${isolated.dataDir.toAbsolutePath().toString().replace("'", "''")}', DATA_INLINING_ROW_LIMIT 0)")
        action(connection)
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql); Unit }

    private fun Connection.ids(name: String): List<Long> = createStatement().use { statement ->
        statement.executeQuery("SELECT id FROM lake.test_schema.$name ORDER BY id").use { rows ->
            buildList { while (rows.next()) add(rows.getLong(1)) }
        }
    }
}
