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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.function.Supplier

/** Uses the published Java driver, not embedded DuckDB's native Quack client. */
class TestJdbcDucklakeCatalogDirectQuack {
    companion object {
        private lateinit var server: TestingDucklakeDuckDbQuackCatalogServer
        private lateinit var catalog: JdbcDucklakeCatalog
        private lateinit var dataDir: Path

        @BeforeAll
        @JvmStatic
        fun start() {
            server = TestingDucklakeDuckDbQuackCatalogServer()
            dataDir = Files.createTempDirectory("direct-quack-catalog-")
            DriverManager.getConnection("jdbc:duckdb:").use { native ->
                native.createStatement().use { statement ->
                    statement.execute("INSTALL quack; LOAD quack; INSTALL ducklake; LOAD ducklake")
                    statement.execute("CREATE SECRET (TYPE quack, TOKEN '${server.getToken()}')")
                    statement.execute("ATTACH '${server.getDucklakeAttachUri()}' AS lake " +
                        "(DATA_PATH '$dataDir', METADATA_CATALOG 'direct_driver_meta', DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)")
                    statement.execute("CREATE TABLE lake.main.native_seed(i INTEGER)")
                    statement.execute("INSERT INTO lake.main.native_seed VALUES (11), (22)")
                    statement.execute("CREATE TABLE lake.main.native_nested AS SELECT " +
                        "{'a': 42::INTEGER, 'b': [1::INTEGER, NULL]} AS s, " +
                        "MAP([1::INTEGER], [{'v': 'x'}]) AS m")
                }
            }
            catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = url()
                catalogDatabasePassword = server.getToken()
                dataPath = dataDir.toString()
                maxCatalogConnections = 3
            })
        }

        private fun url() = "jdbc:quack://${server.getHost()}:${server.getMappedPort()}"

        @AfterAll
        @JvmStatic
        fun stop() {
            if (::catalog.isInitialized) catalog.close()
            if (::server.isInitialized) server.close()
        }
    }

    @Test
    fun readsNativeCatalogAndInlinedDataThroughTheJavaDriver() {
        val snapshot = catalog.currentSnapshotId
        assertThat(catalog.getSpecVersion()).isEqualTo("1.0")
        val table = catalog.getTable("main", "native_seed", snapshot)!!
        val columns = catalog.getTableColumns(table.tableId, snapshot)
        val info = catalog.getInlinedDataInfos(table.tableId, snapshot).single()
        catalog.readSession(Supplier {
            assertThat(catalog.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns))
                .containsExactly(listOf(11), listOf(22))
            assertThat(catalog.readInlinedRowIds(table.tableId, info.schemaVersion, snapshot)).containsExactly(0L, 1L)
            assertThat(catalog.hasInlinedRows(table.tableId, Long.MAX_VALUE, snapshot)).isFalse()
            assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
            Unit
        })
    }

    @Test
    fun decodesNativeInlineStructListAndMapThroughStandardJdbcValues() {
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("main", "native_nested", snapshot)!!
        val columns = catalog.getTableColumns(table.tableId, snapshot)
        val info = catalog.getInlinedDataInfos(table.tableId, snapshot).single()
        val expected = listOf<Any>(
            linkedMapOf("a" to 42, "b" to listOf(1, null)),
            linkedMapOf(1 to linkedMapOf("v" to "x")),
        )
        assertThat(catalog.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns))
            .containsExactly(expected)
    }

    @Test
    fun commitsAndRollsBackCatalogWritesAndExactCleanupAcknowledgements() {
        catalog.createSchema("direct_writes")
        catalog.createTable("direct_writes", "t", listOf(TableColumnSpec.leaf("i", "int32", true)), null, null)
        val table = catalog.getTable("direct_writes", "t", catalog.currentSnapshotId)!!
        val before = catalog.currentSnapshotId
        catalog.beforeSnapshotInsertAction = Runnable { throw IllegalStateException("direct driver rollback") }
        try {
            assertThatThrownBy { catalog.addColumn(table.tableId, TableColumnSpec.leaf("bad", "int32", true)) }
                .hasRootCauseMessage("direct driver rollback")
        } finally { catalog.beforeSnapshotInsertAction = Runnable {} }
        assertThat(catalog.currentSnapshotId).isEqualTo(before)
        assertThat(catalog.getTableColumns(table.tableId, before).map { it.columnName }).containsExactly("i")
        catalog.addColumn(table.tableId, TableColumnSpec.leaf("good", "int32", true))
        assertThat(catalog.currentSnapshotId).isEqualTo(before + 1)

        val properties = Properties().apply { setProperty("token", server.getToken()) }
        DriverManager.getConnection(url(), properties).use { connection ->
            assertThat(connection.metaData.driverVersion).isEqualTo("0.11.1")
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO ducklake_files_scheduled_for_deletion " +
                    "VALUES (700000, 'raw.parquet', true, TIMESTAMPTZ '2020-01-01 00:00:00+00')")
            }
        }
        val batch = catalog.listScheduledFileBatch(Instant.now(), 1)
        assertThat(batch.single().path).isEqualTo("raw.parquet")
        catalog.removeSelectedScheduledFiles(batch)
        assertThat(catalog.listScheduledFileBatch(Instant.now(), 1)).isEmpty()
    }

    @Test
    fun fileInsertDeleteAndRewriteRemainReadableByNativeDuckDB() {
        catalog.createSchema("direct_files")
        catalog.createTable("direct_files", "t", listOf(TableColumnSpec.leaf("i", "int32", true)), null, null)
        val table = catalog.getTable("direct_files", "t", catalog.currentSnapshotId)!!
        val columnId = catalog.getTableColumns(table.tableId, catalog.currentSnapshotId).single().columnId
        val schema = catalog.getSchema("direct_files", catalog.currentSnapshotId)!!
        val directory = Files.createDirectories(dataDir.resolve(schema.path).resolve(table.path))
        val sourcePath = directory.resolve("source.parquet")
        withNative { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY (SELECT i::INTEGER AS i FROM range(1, 3) t(i)) TO '$sourcePath' " +
                    "(FORMAT PARQUET, FIELD_IDS {i: $columnId})")
            }
        }
        catalog.commitInsert(table.tableId, listOf(DucklakeWriteFragment("source.parquet", Files.size(sourcePath), 0, 2, emptyList())))
        val inserted = catalog.currentSnapshotId
        val source = catalog.getDataFiles(table.tableId, inserted).single()
        val deletePath = directory.resolve("delete.parquet")
        withNative { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY (SELECT '$sourcePath'::VARCHAR AS file_path, 0::BIGINT AS pos) " +
                    "TO '$deletePath' (FORMAT PARQUET)")
            }
        }
        catalog.commitDelete(table.tableId,
            listOf(DucklakeDeleteFragment(source.dataFileId, "delete.parquet", 1, Files.size(deletePath), 0, 1)), inserted)
        assertNativeFileRows(listOf(listOf(2L, 1L)))
        val output = directory.resolve("rewritten.parquet")
        withNative { connection ->
            connection.createStatement().use { statement ->
                statement.execute("COPY (SELECT 2::INTEGER AS i, 1::BIGINT AS _ducklake_internal_row_id) TO '$output' " +
                    "(FORMAT PARQUET, FIELD_IDS {i: $columnId, _ducklake_internal_row_id: 2147483540})")
            }
        }
        catalog.rewriteDataFiles(table.tableId, setOf(source.dataFileId),
            listOf(DucklakeWriteFragment("rewritten.parquet", Files.size(output), 0, 1, emptyList())), catalog.currentSnapshotId)
        assertThat(catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).single().rowIdStart).isNull()
        assertNativeFileRows(listOf(listOf(2L, 1L)))
    }

    private fun withNative(action: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection -> action(connection) }
    }

    private fun assertNativeFileRows(expected: List<List<Long>>) {
        withNative { connection ->
            connection.createStatement().use { statement ->
                statement.execute("LOAD quack; LOAD ducklake")
                statement.execute("CREATE SECRET (TYPE quack, TOKEN '${server.getToken()}')")
                statement.execute("ATTACH '${server.getDucklakeAttachUri()}' AS lake " +
                    "(DATA_PATH '$dataDir', METADATA_CATALOG 'direct_file_oracle')")
                statement.executeQuery("SELECT i, rowid FROM lake.direct_files.t ORDER BY i").use { rows ->
                    val actual = buildList { while (rows.next()) add(listOf(rows.getLong(1), rows.getLong(2))) }
                    assertThat(actual).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun concurrentCatalogIdCollisionRetriesOnTheDirectDriver() {
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            catalog,
            Runnable { catalog.createSchema("direct_race_winner") },
            Runnable { catalog.createSchema("direct_race_loser") },
        )
        assertThat(result.loserException).isNull()
        assertThat(result.loserAttemptCount).isEqualTo(2)
        val snapshot = catalog.currentSnapshotId
        assertThat(catalog.getSchema("direct_race_winner", snapshot)!!.schemaId)
            .isNotEqualTo(catalog.getSchema("direct_race_loser", snapshot)!!.schemaId)
    }

    @Test
    fun JavaDriverReportsTheReleasedServerVersion() {
        val properties = Properties().apply { setProperty("token", server.getToken()) }
        DriverManager.getConnection(url(), properties).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version()").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("v2.0.0")
                }
            }
        }
    }
}
