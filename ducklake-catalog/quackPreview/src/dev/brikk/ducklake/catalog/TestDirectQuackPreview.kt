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
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** Runs only via quackPreviewTest. No skips, no embedded DuckDB JDBC, no 1.1 write enablement. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDirectQuackPreview {
    private lateinit var legacy: PreviewQuackServer
    private lateinit var modern: PreviewQuackServer
    private lateinit var catalog: JdbcDucklakeCatalog
    private val sequence = AtomicInteger()

    @BeforeAll
    fun start() {
        legacy = PreviewQuackServer("1.0").also { it.start() }
        modern = PreviewQuackServer("1.1").also { it.start() }
        catalog = legacy.catalog()
    }

    @AfterAll
    fun stop() {
        try { if (::catalog.isInitialized) catalog.close() }
        finally {
            try { if (::legacy.isInitialized) legacy.close() }
            finally { if (::modern.isInitialized) modern.close() }
        }
    }

    private fun name(prefix: String): String = "${prefix}_${sequence.incrementAndGet()}"

    private fun create(): DucklakeTable {
        val name = name("files")
        catalog.createTable("main", name, listOf(TableColumnSpec.leaf("i", "int32", true)), null, null)
        return catalog.getTable("main", name, catalog.currentSnapshotId)!!
    }

    private fun directory(table: DucklakeTable): Path {
        val schema = catalog.getSchema("main", catalog.currentSnapshotId)!!
        return Files.createDirectories(legacy.data.resolve(schema.path).resolve(table.path))
    }

    private fun file(table: DucklakeTable, name: String, sql: String, count: Long, embeddedId: Boolean = false): DucklakeWriteFragment {
        val id = catalog.getTableColumns(table.tableId, catalog.currentSnapshotId).single().columnId
        val path = directory(table).resolve(name)
        val fieldIds = "i: $id" + if (embeddedId) ", _ducklake_internal_row_id: 2147483540" else ""
        legacy.execute("COPY ($sql) TO ${PreviewQuackServer.literal(path.toString())} (FORMAT PARQUET,FIELD_IDS {$fieldIds})")
        return DucklakeWriteFragment(name, Files.size(path), 0, count, emptyList())
    }

    @Test
    fun nativeJsonResultsDoNotMistakeSetupSuccessForTheRequestedRows() {
        assertThat(PreviewQuackServer.parseRows("[{\"Success\":true}]\n[{\"i\":11},{\"i\":22}]"))
            .containsExactly(listOf("11"), listOf("22"))
        assertThat(PreviewQuackServer.parseRows("[{\"Success\":true}]\n[]")).isEmpty()
        assertThat(PreviewQuackServer.parseRows("[{\"a\":null,\"b\":\"\",\"c\":\"NULL\"}]"))
            .containsExactly(listOf(null, "", "NULL"))
    }

    @Test
    fun publishedDriverUsesTheExactSignedV3ServerWithoutDuckDbJdbc() {
        assertThatThrownBy { Class.forName("org.duckdb.DuckDBDriver") }.isInstanceOf(ClassNotFoundException::class.java)
        legacy.connection().use { c ->
            assertThat(c.metaData.driverVersion).isEqualTo("0.8.0")
            assertThat(PreviewQuackServer.jdbcRows(c, "PRAGMA version").single().take(2))
                .containsExactly(PreviewQuackServer.VERSION, PreviewQuackServer.CORE)
            assertThat(PreviewQuackServer.jdbcRows(c,
                "SELECT extension_name,extension_version FROM duckdb_extensions() " +
                    "WHERE extension_name IN ('quack','ducklake') AND loaded ORDER BY 1"))
                .containsExactly(listOf("ducklake", PreviewQuackServer.DUCKLAKE), listOf("quack", PreviewQuackServer.QUACK))
        }
        assertThat(catalog.getSpecVersion()).isEqualTo("1.0")
    }

    @Test
    fun nativeInlineScalarsNestedValuesNullsAndRowIdsRoundTrip() {
        val snapshot = catalog.currentSnapshotId
        val scalar = catalog.getTable("main", "scalar_seed", snapshot)!!
        val columns = catalog.getTableColumns(scalar.tableId, snapshot)
        val version = catalog.getInlinedDataInfos(scalar.tableId, snapshot).single().schemaVersion
        assertThat(catalog.readInlinedDataDecoded(scalar.tableId, version, snapshot, columns)).containsExactly(listOf(11), listOf(22))
        assertThat(catalog.readInlinedRowIds(scalar.tableId, version, snapshot)).containsExactly(0L, 1L)
        val nested = catalog.getTable("main", "nested_seed", snapshot)!!
        val nestedVersion = catalog.getInlinedDataInfos(nested.tableId, snapshot).single().schemaVersion
        assertThat(catalog.readInlinedDataDecoded(nested.tableId, nestedVersion, snapshot, catalog.getTableColumns(nested.tableId, snapshot)))
            .containsExactly(listOf(linkedMapOf("a" to 42, "b" to listOf(1, null)), linkedMapOf(1 to linkedMapOf("v" to "x"))))
    }

    @Test
    fun realParquetInsertDeleteRewriteAndCdcAreReadableByTheMatchingNativePeer() {
        val table = create()
        val fragment = file(table, "source.parquet", "SELECT i::INTEGER AS i FROM range(1,3) r(i)", 2)
        catalog.commitInsert(table.tableId, listOf(fragment))
        val inserted = catalog.currentSnapshotId
        val source = catalog.getDataFiles(table.tableId, inserted).single()
        val deletion = directory(table).resolve("delete.parquet")
        val sourcePath = directory(table).resolve(fragment.path)
        legacy.execute("COPY (SELECT ${PreviewQuackServer.literal(sourcePath.toString())}::VARCHAR AS file_path,0::BIGINT AS pos) " +
            "TO ${PreviewQuackServer.literal(deletion.toString())} (FORMAT PARQUET)")
        catalog.commitDelete(table.tableId,
            listOf(DucklakeDeleteFragment(source.dataFileId, "delete.parquet", 1, Files.size(deletion), 0, 1)), inserted)
        val deleted = catalog.currentSnapshotId
        assertThat(legacy.native("SELECT i,rowid FROM lake.${table.tableName} ORDER BY i")).containsExactly(listOf("2", "1"))
        assertThat(legacy.native("SELECT i,rowid FROM lake.${table.tableName} AT (VERSION=>$inserted) ORDER BY i"))
            .containsExactly(listOf("1", "0"), listOf("2", "1"))
        assertThat(legacy.native("SELECT rowid,change_type,i FROM ducklake_table_changes('lake','main','${table.tableName}',$deleted,$deleted)"))
            .containsExactly(listOf("0", "delete", "1"))
        assertThat(catalog.getDeletionsBetween(table.tableId, deleted, deleted).single().dataFilePath).isEqualTo(fragment.path)
        val rewritten = file(table, "rewritten.parquet", "SELECT 2::INTEGER AS i,1::BIGINT AS _ducklake_internal_row_id", 1, true)
        catalog.rewriteDataFiles(table.tableId, setOf(source.dataFileId), listOf(rewritten), deleted)
        assertThat(catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).single().rowIdStart).isNull()
        assertThat(legacy.native("SELECT i,rowid FROM lake.${table.tableName}")).containsExactly(listOf("2", "1"))
    }

    @Test
    fun aFailureBeforePublicationRollsBackDdlAndAllowsPoolReuse() {
        val table = create()
        val before = catalog.currentSnapshotId
        catalog.beforeSnapshotInsertAction = Runnable { error("preview rollback boundary") }
        try {
            assertThatThrownBy { catalog.addColumn(table.tableId, TableColumnSpec.leaf("bad", "int32", true)) }
                .hasRootCauseMessage("preview rollback boundary")
        }
        finally { catalog.beforeSnapshotInsertAction = Runnable {} }
        assertThat(catalog.currentSnapshotId).isEqualTo(before)
        assertThat(catalog.getTableColumns(table.tableId, before).map { it.columnName }).containsExactly("i")
        catalog.addColumn(table.tableId, TableColumnSpec.leaf("good", "int32", true))
        assertThat(catalog.currentSnapshotId).isEqualTo(before + 1)
    }

    @Test
    fun concurrentSnapshotAndAllocatorClaimsRetryOnAOneConnectionPool() {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        catalog.beforeWriteTransactionAction = Runnable {
            if (attempts.getAndIncrement() == 0) {
                parked.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        val loser = name("loser")
        val winner = name("winner")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit { catalog.createSchema(loser) }
            check(parked.await(20, TimeUnit.SECONDS))
            legacy.catalog().use { it.createSchema(winner) }
            release.countDown()
            result.get(20, TimeUnit.SECONDS)
            assertThat(attempts.get()).isEqualTo(2)
            val snapshot = catalog.currentSnapshotId
            assertThat(catalog.getSchema(loser, snapshot)!!.schemaId).isNotEqualTo(catalog.getSchema(winner, snapshot)!!.schemaId)
        }
        finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(20, TimeUnit.SECONDS))
            catalog.beforeWriteTransactionAction = Runnable {}
        }
    }

    @Test
    fun analyzeRecomputesAfterAConcurrentAppendWithoutLosingTheNativeRow() {
        val table = create()
        val columnId = catalog.getTableColumns(table.tableId, catalog.currentSnapshotId).single().columnId
        val first = file(table, "first.parquet", "SELECT 1::INTEGER AS i", 1).copy(
            columnStats = listOf(DucklakeFileColumnStats(columnId, 4, 1, 0, "1", "1", false)))
        catalog.commitInsert(table.tableId, listOf(first))
        val second = file(table, "second.parquet", "SELECT 1000::INTEGER AS i", 1).copy(
            columnStats = listOf(DucklakeFileColumnStats(columnId, 4, 1, 0, "1000", "1000", false)))
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        catalog.beforeAnalyzePublicationAction = Runnable {
            if (attempts.getAndIncrement() == 0) {
                parked.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit { catalog.analyzeTable(table.tableId) }
            check(parked.await(20, TimeUnit.SECONDS))
            legacy.catalog().use { it.commitInsert(table.tableId, listOf(second)) }
            release.countDown()
            result.get(20, TimeUnit.SECONDS)
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2)
            assertThat(catalog.getTableStats(table.tableId)!!.recordCount).isEqualTo(2L)
            assertThat(catalog.getColumnStats(table.tableId, catalog.currentSnapshotId).single().maxValue).isEqualTo("1000")
            assertThat(legacy.native("SELECT i FROM lake.${table.tableName} WHERE i=1000")).containsExactly(listOf("1000"))
        }
        finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(20, TimeUnit.SECONDS))
            catalog.beforeAnalyzePublicationAction = Runnable {}
        }
    }

    @Test
    fun lostCommitReplyIsNotRetriedAndRegisteredParquetIsRetained() {
        val table = create()
        val fragment = file(table, "uncertain.parquet", "SELECT 7::INTEGER AS i", 1)
        val before = catalog.currentSnapshotId
        val calls = AtomicInteger()
        val lost = SQLException("preview lost acknowledgement", "08006")
        catalog.backendCommitAction = { metadata, ctx, connection ->
            calls.incrementAndGet()
            metadata.commit(ctx, connection)
            throw lost
        }
        try {
            assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment)) }
                .isInstanceOf(DucklakeCommitOutcomeUnknownException::class.java).hasCause(lost)
        }
        finally { catalog.backendCommitAction = null }
        assertThat(calls.get()).isEqualTo(1)
        assertThat(catalog.currentSnapshotId).isEqualTo(before + 1)
        assertThat(Files.exists(directory(table).resolve(fragment.path))).isTrue()
        assertThat(legacy.native("SELECT i FROM lake.${table.tableName}")).containsExactly(listOf("7"))
    }

    @Test
    fun acknowledgedCommitFinalizationFailureIsNotRepublished() {
        val table = create()
        val fragment = file(table, "acknowledged.parquet", "SELECT 8::INTEGER AS i", 1)
        val before = catalog.currentSnapshotId
        val calls = AtomicInteger()
        catalog.backendCommitAction = { metadata, ctx, connection ->
            calls.incrementAndGet()
            metadata.commit(ctx, connection)
            throw DucklakePostCommitException("preview finalization", SQLException("after acknowledgement", "40001"))
        }
        try {
            assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment)) }.isInstanceOf(DucklakePostCommitException::class.java)
        }
        finally { catalog.backendCommitAction = null }
        assertThat(calls.get()).isEqualTo(1)
        assertThat(catalog.currentSnapshotId).isEqualTo(before + 1)
        assertThat(Files.exists(directory(table).resolve(fragment.path))).isTrue()
    }

    @Test
    fun pinnedSessionHidesNativeCommitsUntilReleaseIncludingTimestampAndCdcLookups() {
        val name = name("pinned")
        legacy.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1),(2)")
        val before = catalog.currentSnapshotId
        val table = catalog.getTable("main", name, before)!!
        val columns = catalog.getTableColumns(table.tableId, before)
        val version = catalog.getInlinedDataInfos(table.tableId, before).single().schemaVersion
        var winner = -1L
        catalog.readSession(Supplier {
            assertThat(catalog.currentSnapshotId).isEqualTo(before)
            val snapshots = catalog.listSnapshots()
            legacy.native("INSERT INTO lake.$name VALUES (3)")
            legacy.connection().use { winner = PreviewQuackServer.jdbcRows(it, "SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong() }
            assertThat(winner).isEqualTo(before + 1)
            assertThat(catalog.listSnapshots()).isEqualTo(snapshots)
            assertThat(catalog.getSnapshot(winner)).isNull()
            assertThat(catalog.getSnapshotAtOrBefore(Instant.now().plusSeconds(60))!!.snapshotId).isEqualTo(before)
            assertThat(catalog.getInlinedChangesBetweenDecoded(table.tableId, version, before, before, columns.map { it.columnId })
                .map { it.values }).containsExactly(listOf(1), listOf(2))
            assertThat(catalog.readInlinedDataDecoded(table.tableId, version, before, columns)).containsExactly(listOf(1), listOf(2))
        })
        assertThat(catalog.currentSnapshotId).isEqualTo(winner)
        assertThat(catalog.readInlinedDataDecoded(table.tableId, version, winner, columns)).containsExactly(listOf(1), listOf(2), listOf(3))
        assertThat(catalog.getInlinedChangesBetweenDecoded(table.tableId, version, winner, winner, columns.map { it.columnId })
            .map { it.values }).containsExactly(listOf(3))
        assertThat(catalog.getSnapshotAtOrBefore(catalog.getSnapshot(winner)!!.snapshotTime)!!.snapshotId).isEqualTo(winner)
    }

    @Test
    fun boundedQueueAcknowledgementPreservesTheUnselectedEntry() {
        legacy.connection().use { connection -> connection.createStatement().use { st ->
            st.execute("INSERT INTO ducklake_files_scheduled_for_deletion VALUES " +
                "(9000001,'preview-old.parquet',true,TIMESTAMPTZ '1990-01-01')," +
                "(9000002,'preview-next.parquet',true,TIMESTAMPTZ '1995-01-01')")
        } }
        val batch = catalog.listScheduledFileBatch(Instant.parse("2000-01-01T00:00:00Z"), 1)
        assertThat(batch.single().path).isEqualTo("preview-old.parquet")
        catalog.removeSelectedScheduledFiles(batch)
        assertThat(catalog.listScheduledFileBatch(Instant.parse("2000-01-01T00:00:00Z"), 1).single().path).isEqualTo("preview-next.parquet")
    }

    @Test
    fun expirationKeepsTheConflictLedgerWithoutCreatingANewSnapshot() {
        val old = catalog.currentSnapshotId
        catalog.createSchema(name("expiry"))
        val before = catalog.currentSnapshotId
        catalog.expireSnapshots(setOf(old))
        assertThat(catalog.currentSnapshotId).isEqualTo(before)
        assertThat(catalog.getSnapshot(old)).isNull()
        assertThat(catalog.listSnapshotChanges().map { it.snapshotId }).contains(old)
    }

    @Test
    fun failedRawServerTransactionRollsBackAndTheJavaConnectionCanBeReused() {
        legacy.connection().use { connection ->
            connection.autoCommit = false
            assertThatThrownBy { connection.createStatement().use { it.executeQuery("SELECT * FROM missing_preview_relation") } }
                .isInstanceOf(SQLException::class.java)
            connection.rollback()
            assertThat(PreviewQuackServer.jdbcRows(connection, "SELECT 42")).containsExactly(listOf("42"))
        }
    }

    @Test
    fun final11CatalogIsReachableButUnsupportedInlineReadsAndWritesRemainExplicitlyGuarded() {
        modern.catalog().use { c ->
            assertThat(c.getSpecVersion()).isEqualTo("1.1")
            val before = c.currentSnapshotId
            val table = c.getTable("main", "scalar_seed", before)!!
            val columns = c.getTableColumns(table.tableId, before)
            val version = modern.connection().use {
                PreviewQuackServer.jdbcRows(it, "SELECT schema_version FROM ducklake_inlined_data_tables WHERE table_id=${table.tableId}")
                    .single().single()!!.toLong()
            }
            assertThatThrownBy { c.readInlinedDataDecoded(table.tableId, version, before, columns) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("Unknown inlined metadata layout")
            assertThatThrownBy { c.createSchema("must_not_publish") }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
            assertThatThrownBy { c.analyzeTable(table.tableId) }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
            assertThatThrownBy { c.expireSnapshots(setOf(1L)) }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
            assertThat(c.currentSnapshotId).isEqualTo(before)
            assertThat(c.getSchema("must_not_publish", before)).isNull()
            assertThat(modern.native("SELECT i FROM lake.scalar_seed ORDER BY i")).containsExactly(listOf("11"), listOf("22"))
        }
    }
}
