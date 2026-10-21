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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import java.util.function.Supplier

/** Lost-ack injection happens AFTER a real backend commit, not by guessing a metadata shape. */
class TestJdbcDucklakeCatalogCommitOutcomes {
    @Nested inner class Postgres : Contract(Backend.POSTGRES)
    @Nested inner class MySql : Contract(Backend.MYSQL)
    @Nested inner class Local : Contract(Backend.LOCAL)
    @Nested inner class NativeQuack : Contract(Backend.QUACK)
    @Nested inner class JavaQuack : Contract(Backend.JAVA_QUACK)

    enum class Backend { POSTGRES, MYSQL, LOCAL, QUACK, JAVA_QUACK }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract class Contract(private val backend: Backend) {
        private var server: AutoCloseable? = null
        private lateinit var catalog: JdbcDucklakeCatalog
        private lateinit var configuration: DucklakeCatalogConfig
        private lateinit var directory: Path
        private lateinit var table: DucklakeTable
        private lateinit var fragment: DucklakeWriteFragment
        private lateinit var output: Path
        private var sequence = 0

        @BeforeAll
        fun open(@TempDir root: Path) {
            directory = root
            val config = DucklakeCatalogConfig().apply {
                dataPath = root.resolve("data").toString()
                maxCatalogConnections = 1
            }
            when (backend) {
                Backend.POSTGRES -> {
                    val pg = TestingDucklakePostgreSqlCatalogServer().also { server = it }
                    config.catalogDatabaseUrl = pg.getJdbcUrl()
                    config.catalogDatabaseUser = pg.getUser()
                    config.catalogDatabasePassword = pg.getPassword()
                    bootstrap(pg.getDuckDbAttachUri(), "postgres", config.dataPath!!)
                }
                Backend.MYSQL -> {
                    val mysql = TestingDucklakeMySqlCatalogServer().also { server = it }
                    config.catalogDatabaseUrl = mysql.getJdbcUrl()
                    config.catalogDatabaseUser = mysql.getUser()
                    config.catalogDatabasePassword = mysql.getPassword()
                    bootstrap(mysql.getDuckDbAttachUri(), "mysql", config.dataPath!!)
                }
                Backend.LOCAL -> {
                    val file = root.resolve("meta.db")
                    config.catalogDatabaseUrl = "jdbc:duckdb:$file"
                    bootstrap("ducklake:$file", null, config.dataPath!!)
                }
                Backend.QUACK, Backend.JAVA_QUACK -> {
                    val quack = TestingDucklakeDuckDbQuackCatalogServer().also { server = it }
                    config.catalogDatabasePassword = quack.getToken()
                    config.catalogDatabaseUrl = "jdbc:duckdb:quack://${quack.getHost()}:${quack.getMappedPort()}?metadata_catalog=commit_meta"
                    if (backend == Backend.JAVA_QUACK) {
                        DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
                            val url = QuackBackedDuckDbCatalogUrl.parse(config.catalogDatabaseUrl!!, config.catalogDatabasePassword, config.dataPath)
                            st.execute(url.connectionInitSql())
                        } }
                        config.catalogDatabaseUrl = "jdbc:quack://${quack.getHost()}:${quack.getMappedPort()}"
                    }
                }
            }
            configuration = config
            catalog = JdbcDucklakeCatalog(config)
        }

        private fun bootstrap(uri: String, extension: String?, path: String) {
            DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
                st.execute("INSTALL ducklake; LOAD ducklake")
                if (extension != null) st.execute("INSTALL $extension; LOAD $extension")
                st.execute("ATTACH '$uri' AS lake (DATA_PATH '$path')")
            } }
        }

        @BeforeEach
        fun prepareOutput() {
            catalog.backendCommitAction = null
            catalog.beforeSnapshotInsertAction = Runnable {}
            catalog.beforeAnalyzePublicationAction = Runnable {}
            val name = "output_${sequence++}"
            catalog.createTable("main", name, listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
            val snapshot = catalog.currentSnapshotId
            table = catalog.getTable("main", name, snapshot)!!
            val schema = catalog.getSchema("main", snapshot)!!
            val column = catalog.getTableColumns(table.tableId, snapshot).single()
            output = Files.createDirectories(directory.resolve("data").resolve(schema.path).resolve(table.path)).resolve("one.parquet")
            DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
                st.execute("COPY (SELECT 7::INTEGER AS id) TO '$output' (FORMAT PARQUET, FIELD_IDS {id: ${column.columnId}})")
            } }
            fragment = DucklakeWriteFragment("one.parquet", Files.size(output), 0, 1, emptyList())
        }

        @AfterAll
        fun close() {
            if (::catalog.isInitialized) catalog.close()
            server?.close()
        }

        @Test
        fun readSessionHidesNewSnapshotsAndCdcEvenWhenFutureIdIsRequested() {
            catalog.commitInsert(table.tableId, listOf(fragment))
            Files.copy(output, output.resolveSibling("after-pin.parquet"))
            val before = catalog.currentSnapshotId
            var winner = -1L
            var winnerTime = Instant.EPOCH
            catalog.readSession(Supplier {
                assertThat(catalog.currentSnapshotId).isEqualTo(before) // Establish the remote MVCC snapshot.
                val snapshots = catalog.listSnapshots()
                val changes = catalog.listSnapshotChanges()
                JdbcDucklakeCatalog(configuration).use { other ->
                    other.commitInsert(table.tableId, listOf(fragment.copy(path = "after-pin.parquet")))
                    winner = other.currentSnapshotId
                    winnerTime = other.getSnapshot(winner)!!.snapshotTime
                }
                assertThat(winner).isEqualTo(before + 1)
                assertThat(catalog.currentSnapshotId).isEqualTo(before)
                assertThat(catalog.listSnapshots()).isEqualTo(snapshots)
                assertThat(catalog.listSnapshotChanges()).isEqualTo(changes)
                assertThat(catalog.getSnapshot(winner)).isNull()
                // MySQL's native bootstrap can retain fractional seconds while SQL CURRENT_TIMESTAMP
                // commits use whole seconds. A lower ID can therefore be the latest-TIMED snapshot.
                // Check against the pinned visible rows, not an assumed ID/time monotonicity.
                // At the winner's own stored time, a leaking query would necessarily select the
                // winner (newest ID at the cutoff); older future-dated bootstrap rows cannot mask it.
                val cutoff = winnerTime
                val expectedAtCutoff = snapshots.filter { !it.snapshotTime.isAfter(cutoff) }
                    .maxWithOrNull(compareBy<DucklakeSnapshot> { it.snapshotTime }.thenBy { it.snapshotId })
                assertThat(catalog.getSnapshotAtOrBefore(cutoff)?.snapshotId).isEqualTo(expectedAtCutoff?.snapshotId)
                assertThat(catalog.getDataFilesAddedBetween(table.tableId, winner, winner)).isEmpty()
                assertThat(catalog.getDeletionsBetween(table.tableId, winner, winner)).isEmpty()
                assertThat(catalog.getDataFiles(table.tableId, winner)).hasSize(1)
                assertThat(catalog.readSession(Supplier { catalog.currentSnapshotId })).isEqualTo(before)
            })
            assertThat(catalog.currentSnapshotId).isEqualTo(winner)
            assertThat(catalog.getSnapshot(winner)).isNotNull()
            assertThat(catalog.getSnapshotAtOrBefore(catalog.getSnapshot(winner)!!.snapshotTime)!!.snapshotId).isEqualTo(winner)
            assertThat(catalog.getSnapshotAtOrBefore(Instant.parse("1900-01-01T00:00:00Z"))).isNull()
            assertThat(catalog.getDataFilesAddedBetween(table.tableId, winner, winner).single().path).isEqualTo("after-pin.parquet")
            assertThat(catalog.getDataFiles(table.tableId, winner)).hasSize(2)
            assertThat(Files.exists(output.resolveSibling("after-pin.parquet"))).isTrue()
        }

        @Test
        fun concurrentAppendForcesAnalyzeToRecomputeWithoutLosingCounters() {
            catalog.commitInsert(table.tableId, listOf(fragment))
            val otherPath = output.resolveSibling("two.parquet")
            Files.copy(output, otherPath)
            val read = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attempts = AtomicInteger()
            catalog.beforeAnalyzePublicationAction = Runnable {
                if (attempts.getAndIncrement() == 0) {
                    read.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val analyzed = executor.submit { catalog.analyzeTable(table.tableId) }
                check(read.await(20, TimeUnit.SECONDS))
                JdbcDucklakeCatalog(configuration).use { other ->
                    other.commitInsert(table.tableId, listOf(fragment.copy(path = "two.parquet")))
                }
                release.countDown()
                analyzed.get(20, TimeUnit.SECONDS)
                assertThat(attempts.get()).isGreaterThanOrEqualTo(2)
                assertThat(catalog.getTableStats(table.tableId)!!.recordCount).isEqualTo(2L)
                assertThat(catalog.getDataFiles(table.tableId, catalog.currentSnapshotId)).hasSize(2)
                assertThat(Files.exists(output) && Files.exists(otherPath)).isTrue()
            }
            finally {
                release.countDown()
                executor.shutdownNow()
                check(executor.awaitTermination(20, TimeUnit.SECONDS))
                catalog.beforeAnalyzePublicationAction = Runnable {}
            }
        }

        @Test
        fun failureBeforeCommitLeavesNoPublishedFileOrSnapshot() {
            val before = catalog.currentSnapshotId
            var commits = 0
            catalog.backendCommitAction = { metadata, ctx, connection -> commits++; metadata.commit(ctx, connection) }
            catalog.beforeSnapshotInsertAction = Runnable { throw IllegalStateException("before COMMIT") }
            assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment)) }
                .isInstanceOf(DucklakeException::class.java).isNotInstanceOf(DucklakeCommitOutcomeUnknownException::class.java)
            assertThat(commits).isZero()
            assertThat(catalog.currentSnapshotId).isEqualTo(before)
            assertThat(catalog.getDataFiles(table.tableId, before)).isEmpty()
            assertThat(Files.exists(output)).isTrue() // Physical cleanup is the producer's responsibility.
        }

        @Test
        fun lostReplyFromSnapshotlessMaintenanceIsUnknownToo() {
            catalog.commitInsert(table.tableId, listOf(fragment))
            val expired = catalog.currentSnapshotId
            catalog.createSchema("maintenance_${sequence}")
            var commits = 0
            val lost = SQLException("Lost expiry COMMIT reply", "08006")
            catalog.backendCommitAction = { metadata, ctx, connection ->
                commits++
                metadata.commit(ctx, connection)
                throw lost
            }
            val error = runCatching { catalog.expireSnapshots(setOf(expired)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(DucklakeCommitOutcomeUnknownException::class.java).hasCause(lost)
            assertThat((error as DucklakeCommitOutcomeUnknownException).attemptedSnapshotId).isNull()
            assertThat(commits).isEqualTo(1)
            assertThat(catalog.getSnapshot(expired)).isNull() // It did apply; an exception isn't rollback proof.
            assertThat(Files.exists(output)).isTrue()
        }

        @Test
        fun acknowledgedCommitThenConnectionFinalizationFailureCannotBeRetried() {
            val before = catalog.currentSnapshotId
            var commits = 0
            val failure = SQLException("Connection finalization failed", "40001")
            catalog.backendCommitAction = { metadata, ctx, connection ->
                commits++
                if (backend == Backend.QUACK) metadata.commit(ctx, failedFinalization(connection, failure))
                else {
                    metadata.commit(ctx, connection)
                    throw DucklakePostCommitException("test connection finalization", failure)
                }
            }
            assertThatThrownBy { catalog.commitInsert(table.tableId, listOf(fragment)) }
                .isInstanceOf(DucklakePostCommitException::class.java).hasCause(failure)
            assertThat(commits).isEqualTo(1)
            assertThat(catalog.currentSnapshotId).isEqualTo(before + 1)
            assertThat(catalog.getDataFiles(table.tableId, before + 1)).hasSize(1)
            assertThat(Files.exists(output)).isTrue()
        }

        private fun failedFinalization(connection: Connection, failure: SQLException): Connection =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                if (method.name == "commit") {
                    connection.commit()
                    throw failure
                }
                try { method.invoke(connection, *(arguments ?: emptyArray())) }
                catch (e: InvocationTargetException) { throw e.targetException }
            } as Connection

        @Test
        fun aLostCommitReplyIsUnknownAndNeverAutomaticallyRepublished() {
            lostReply("Connection lost after COMMIT")
        }

        @Test
        fun transportFailureCannotBecomeRetryableBecauseItsMessageContainsConflict() {
            lostReply("Failed to commit: transaction conflict while receiving the reply")
        }

        private fun lostReply(message: String) {
            val before = catalog.currentSnapshotId
            var commits = 0
            val lost = SQLException(message, "08006")
            catalog.backendCommitAction = { metadata, ctx, connection ->
                commits++
                metadata.commit(ctx, connection)
                if (commits == 1) throw lost
            }
            val error = runCatching { catalog.commitInsert(table.tableId, listOf(fragment)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(DucklakeCommitOutcomeUnknownException::class.java).hasCause(lost)
            assertThat((error as DucklakeCommitOutcomeUnknownException).attemptedSnapshotId).isEqualTo(before + 1)
            assertThat(commits).`as`("unknown outcome is not a retryable conflict").isEqualTo(1)
            val now = catalog.currentSnapshotId
            assertThat(now).isEqualTo(before + 1)
            val files = catalog.getDataFiles(table.tableId, now)
            assertThat(files).hasSize(1)
            assertThat(files.single().path).isEqualTo(fragment.path)
            assertThat(catalog.getLiveRowCount(table.tableId, now)).isEqualTo(1L)
            assertThat(Files.exists(output)).isTrue()
            DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
                st.executeQuery("SELECT id FROM read_parquet('$output')").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getInt(1)).isEqualTo(7)
                    assertThat(rs.next()).isFalse()
                }
            } }
        }
    }
}
