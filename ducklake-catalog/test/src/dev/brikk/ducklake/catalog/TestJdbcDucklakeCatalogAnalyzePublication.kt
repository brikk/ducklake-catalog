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
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** C05: use a real native filtered read to expose stale global bounds, not only metadata assertions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestJdbcDucklakeCatalogAnalyzePublication {
    private lateinit var server: TestingDucklakePostgreSqlCatalogServer
    private lateinit var fixture: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
    private lateinit var analyzer: JdbcDucklakeCatalog
    private lateinit var writer: JdbcDucklakeCatalog

    @BeforeAll
    fun open() {
        server = TestingDucklakePostgreSqlCatalogServer()
        fixture = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "analyze-publication")
        analyzer = openCatalog()
        writer = openCatalog()
    }

    private fun openCatalog() = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = fixture.jdbcUrl
        catalogDatabaseUser = fixture.user
        catalogDatabasePassword = fixture.password
        dataPath = fixture.dataDir.toAbsolutePath().toString()
        maxCatalogConnections = 1
    })

    @AfterAll
    fun close() {
        if (::analyzer.isInitialized) analyzer.close()
        if (::writer.isInitialized) writer.close()
        if (::server.isInitialized) server.close()
    }

    private fun <T> native(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:duckdb:").use { conn ->
        conn.createStatement().use { st ->
            st.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            st.execute("ATTACH '${fixture.duckDbAttachUri}' AS lake (DATA_PATH '${fixture.dataDir.toAbsolutePath()}')")
            st.execute("CALL lake.set_option('data_inlining_row_limit',0)")
        }
        block(conn)
    }

    private fun create(name: String): DucklakeTable {
        native { conn -> conn.createStatement().use { st ->
            st.execute("CREATE TABLE lake.test_schema.$name(id INTEGER)")
            st.execute("INSERT INTO lake.test_schema.$name VALUES (1),(2)")
        } }
        return analyzer.getTable("test_schema", name, analyzer.currentSnapshotId)!!
    }

    @Test fun concurrentJvmInsertDoesNotGetHiddenByStaleAnalyze() = race("analyze_jvm", nativeWriter = false)
    @Test fun concurrentNativeInsertDoesNotGetHiddenByStaleAnalyze() = race("analyze_native", nativeWriter = true)

    @Test
    fun aDroppedTargetIsNotRepublishedByAnAnalyzeRetry() {
        val table = create("analyze_dropped")
        var winner = -1L
        val attempts = AtomicInteger()
        analyzer.beforeAnalyzePublicationAction = Runnable {
            if (attempts.getAndIncrement() == 0) {
                writer.dropTable("test_schema", "analyze_dropped")
                winner = writer.currentSnapshotId
            }
        }
        try {
            assertThatThrownBy { analyzer.analyzeTable(table.tableId) }
                .isInstanceOf(DucklakeEntityNotFoundException::class.java)
            assertThat(analyzer.currentSnapshotId).isEqualTo(winner)
            assertThat(analyzer.getTable("test_schema", "analyze_dropped", winner)).isNull()
        } finally { analyzer.beforeAnalyzePublicationAction = Runnable {} }
    }

    @Test
    fun concurrentAnalyzesPublishSeriallyWithUnchangedDataAndSchema() {
        val table = create("analyze_twice")
        val before = analyzer.getSnapshot(analyzer.currentSnapshotId)!!
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val hook = Runnable {
            if (attempts.getAndIncrement() < 2) {
                ready.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        analyzer.beforeAnalyzePublicationAction = hook
        writer.beforeAnalyzePublicationAction = hook
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { analyzer.analyzeTable(table.tableId) }
            val second = executor.submit { writer.analyzeTable(table.tableId) }
            check(ready.await(20, TimeUnit.SECONDS))
            release.countDown()
            first.get(20, TimeUnit.SECONDS)
            second.get(20, TimeUnit.SECONDS)
            val after = analyzer.getSnapshot(analyzer.currentSnapshotId)!!
            assertThat(after.snapshotId).isEqualTo(before.snapshotId + 2)
            assertThat(after.schemaVersion).isEqualTo(before.schemaVersion)
            assertThat(after.nextFileId).isEqualTo(before.nextFileId)
            assertThat(after.nextCatalogId).isEqualTo(before.nextCatalogId)
            assertThat(analyzer.getTableStats(table.tableId)!!.recordCount).isEqualTo(2L)
            assertThat(attempts.get()).isGreaterThanOrEqualTo(3)
        }
        finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(20, TimeUnit.SECONDS))
            analyzer.beforeAnalyzePublicationAction = Runnable {}
            writer.beforeAnalyzePublicationAction = Runnable {}
        }
    }

    @Test
    fun snapshotCollisionAfterPublicationRetriesWithoutBorrowingASecondConnection() {
        val table = create("analyze_pk")
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        analyzer.beforeSnapshotInsertAction = Runnable {
            if (attempts.getAndIncrement() == 0) {
                reached.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "snapshot release timed out" }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val analyzed = executor.submit { analyzer.analyzeTable(table.tableId) }
            check(reached.await(20, TimeUnit.SECONDS)) { "ANALYZE didn't reach snapshot publication" }
            writer.createSchema("c05_pk_winner") // No overlap with the statistics rows held by ANALYZE.
            val winner = writer.currentSnapshotId
            release.countDown()
            analyzed.get(20, TimeUnit.SECONDS)
            assertThat(attempts.get()).isEqualTo(2)
            assertThat(analyzer.currentSnapshotId).isEqualTo(winner + 1)
            assertThat(analyzer.getTableStats(table.tableId)!!.recordCount).isEqualTo(2L)
        }
        finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(20, TimeUnit.SECONDS))
            analyzer.beforeSnapshotInsertAction = Runnable {}
        }
    }

    @Test
    fun analyzePublishesAnEmptyChangeSnapshotWithoutMovingDataSchemaOrAllocators() {
        val table = create("analyze_shape")
        val before = analyzer.getSnapshot(analyzer.currentSnapshotId)!!
        val files = analyzer.getDataFiles(table.tableId, before.snapshotId)
        analyzer.analyzeTable(table.tableId)
        val after = analyzer.getSnapshot(analyzer.currentSnapshotId)!!
        assertThat(after.snapshotId).isEqualTo(before.snapshotId + 1)
        assertThat(after.schemaVersion).isEqualTo(before.schemaVersion)
        assertThat(after.nextCatalogId).isEqualTo(before.nextCatalogId)
        assertThat(after.nextFileId).isEqualTo(before.nextFileId)
        assertThat(analyzer.getDataFiles(table.tableId, after.snapshotId)).isEqualTo(files)
        assertThat(analyzer.listSnapshotChanges().single { it.snapshotId == after.snapshotId }.changesMade).isEqualTo("")
        native { conn -> conn.createStatement().use { st ->
            st.executeQuery("SELECT cardinality(changes) FROM ducklake_snapshots('lake') WHERE snapshot_id=${after.snapshotId}").use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isZero()
            }
            val changes = "SELECT count(*) FROM ducklake_table_changes('lake','test_schema','analyze_shape',${after.snapshotId},${after.snapshotId})"
            st.executeQuery(changes).use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getLong(1)).isZero()
            }
        } }
    }

    @Test
    fun aNativeTransactionOpenedBeforeAnalyzeRebasesItsCachedStatistics() {
        val table = create("analyze_open_native")
        // Repairable drift is already an ANALYZE contract. A native transaction caches these
        // absolute counters/bounds before ANALYZE; it must reload them after our publication.
        DriverManager.getConnection(fixture.jdbcUrl, fixture.user, fixture.password).use { conn -> conn.createStatement().use { st ->
            st.execute("UPDATE ducklake_table_stats SET record_count=999 WHERE table_id=${table.tableId}")
            st.execute("UPDATE ducklake_table_column_stats SET min_value='-999',max_value='999' WHERE table_id=${table.tableId}")
        } }
        native { conn -> conn.createStatement().use { st ->
            st.execute("BEGIN")
            st.executeQuery("SELECT id FROM lake.test_schema.analyze_open_native ORDER BY id").use { rs ->
                assertThat(generateSequence { if (rs.next()) rs.getInt(1) else null }.toList()).containsExactly(1, 2)
            }
            st.execute("INSERT INTO lake.test_schema.analyze_open_native VALUES (1000)")
            analyzer.analyzeTable(table.tableId)
            val analyzed = analyzer.currentSnapshotId
            st.execute("COMMIT")
            assertThat(analyzer.currentSnapshotId).isEqualTo(analyzed + 1)
        } }
        assertThat(analyzer.getTableStats(table.tableId)!!.recordCount).isEqualTo(3L)
        DriverManager.getConnection(fixture.jdbcUrl, fixture.user, fixture.password).use { conn -> conn.createStatement().use { st ->
            st.executeQuery("SELECT min_value,max_value FROM ducklake_table_column_stats WHERE table_id=${table.tableId}").use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString(1)).isEqualTo("1")
                assertThat(rs.getString(2)).isEqualTo("1000")
            }
        } }
    }

    @Test
    fun analyzeTraversesMultipleBoundedStatisticsPages() {
        val table = create("analyze_pages")
        val column = analyzer.getTableColumns(table.tableId, analyzer.currentSnapshotId).single()
        DriverManager.getConnection(fixture.jdbcUrl, fixture.user, fixture.password).use { conn -> conn.createStatement().use { st ->
            // Metadata-only traversal fixture; its synthetic file paths are never scanned or deleted.
            st.execute("INSERT INTO ducklake_data_file (data_file_id,table_id,begin_snapshot,path,path_is_relative,file_format,record_count,file_size_bytes) " +
                "SELECT 1000000+i,${table.tableId},0,'unused-'||i||'.parquet',true,'parquet',1,8 FROM generate_series(1,2050) AS g(i)")
            st.execute("INSERT INTO ducklake_file_column_stats " +
                "(data_file_id,table_id,column_id,column_size_bytes,value_count,null_count,min_value,max_value) " +
                "SELECT 1000000+i,${table.tableId},${column.columnId},4,1,0,'10','10' FROM generate_series(1,2050) AS g(i)")
        } }
        val before = analyzer.currentSnapshotId
        DriverManager.getConnection(fixture.jdbcUrl, fixture.user, fixture.password).use { conn -> conn.createStatement().use { st ->
            // One native stats row + 1,023 synthetic rows puts this duplicate across the 1,024-row boundary.
            st.execute("INSERT INTO ducklake_file_column_stats SELECT * FROM ducklake_file_column_stats " +
                "WHERE table_id=${table.tableId} AND data_file_id=1001023")
        } }
        assertThatThrownBy { analyzer.analyzeTable(table.tableId) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThat(analyzer.currentSnapshotId).isEqualTo(before)
        assertThat(analyzer.getTableStats(table.tableId)!!.recordCount).isEqualTo(2L)
        DriverManager.getConnection(fixture.jdbcUrl, fixture.user, fixture.password).use { conn -> conn.createStatement().use { st ->
            st.execute("DELETE FROM ducklake_file_column_stats WHERE ctid = " +
                "(SELECT ctid FROM ducklake_file_column_stats WHERE table_id=${table.tableId} AND data_file_id=1001023 LIMIT 1)")
        } }
        analyzer.analyzeTable(table.tableId)
        assertThat(analyzer.getTableStats(table.tableId)!!.recordCount).isEqualTo(2052L)
        val stats = analyzer.getColumnStats(table.tableId, analyzer.currentSnapshotId).single()
        assertThat(stats.totalValueCount).isEqualTo(2052L)
        assertThat(stats.minValue).isEqualTo("1")
        assertThat(stats.maxValue).isEqualTo("10")
    }

    private fun race(name: String, nativeWriter: Boolean) {
        val table = create(name)
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        analyzer.beforeAnalyzePublicationAction = Runnable {
            if (attempts.getAndIncrement() == 0) {
                read.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "ANALYZE release timed out" }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val analyzed = executor.submit { analyzer.analyzeTable(table.tableId) }
            check(read.await(20, TimeUnit.SECONDS)) { "ANALYZE didn't reach publication barrier" }
            if (nativeWriter) native { conn -> conn.createStatement().use { st ->
                st.execute("INSERT INTO lake.test_schema.$name VALUES (1000)")
            } } else insertFile(table)
            release.countDown()
            analyzed.get(20, TimeUnit.SECONDS)
            // Read after ANALYZE with a FRESH native catalog cache. A stale global max can fold
            // this predicate to FALSE, although both Parquet files and the row still exist.
            val filtered = native { conn -> conn.createStatement().use { st ->
                st.executeQuery("SELECT id FROM lake.test_schema.$name WHERE id=1000").use { rs ->
                    generateSequence { if (rs.next()) rs.getInt(1) else null }.toList()
                }
            } }
            assertThat(filtered).`as`("C05: stale global bounds must not eliminate committed rows").containsExactly(1000)
            assertThat(analyzer.getTableStats(table.tableId)!!.recordCount).isEqualTo(3L)
            assertThat(analyzer.getLiveRowCount(table.tableId, analyzer.currentSnapshotId)).isEqualTo(3L)
            assertThat(attempts.get()).`as`("ANALYZE recomputes after the competing snapshot").isGreaterThanOrEqualTo(2)
        }
        finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(20, TimeUnit.SECONDS)) { "ANALYZE worker failed to stop" }
            analyzer.beforeAnalyzePublicationAction = Runnable {}
        }
    }

    private fun insertFile(table: DucklakeTable) {
        val snapshot = writer.currentSnapshotId
        val schema = writer.listSchemas(snapshot).single { it.schemaId == table.schemaId }
        val column = writer.getTableColumns(table.tableId, snapshot).single()
        val directory = Files.createDirectories(fixture.dataDir.toAbsolutePath().resolve(schema.path).resolve(table.path))
        val path = directory.resolve("concurrent.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
            st.execute("COPY (SELECT 1000::INTEGER AS id) TO '$path' (FORMAT PARQUET, FIELD_IDS {id: ${column.columnId}})")
        } }
        writer.commitInsert(table.tableId, listOf(DucklakeWriteFragment(
            path.fileName.toString(), Files.size(path), 0, 1,
            listOf(DucklakeFileColumnStats(column.columnId, 4, 1, 0, "1000", "1000", false)),
        )))
    }
}
