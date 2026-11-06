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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.function.Supplier

/**
 * R4: the PostgreSQL-backed catalog behind PgBouncer in **transaction** pooling mode, where every
 * statement outside an explicit transaction may run on a different server connection and session
 * state is shared between clients.
 */
class TestPgBouncerTransactionPooling {
    // The two supported setups. PgBouncer with max_prepared_statements = 0 AND default pgjdbc settings is
    // unsupported: pgjdbc's named statements (including its own BEGIN/COMMIT) fail loudly there.
    @Nested inner class ProtocolPreparedStatements : Lane(maxPreparedStatements = 200)
    @Nested inner class NoPreparedStatementsPrepareThresholdZero : Lane(maxPreparedStatements = 0, urlSuffix = "prepareThreshold=0")

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract class Lane(private val maxPreparedStatements: Int, private val urlSuffix: String = "") {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var bouncer: GenericContainer<*>
        private lateinit var bouncerUrl: String

        @BeforeAll
        fun start() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "pgbouncer-$maxPreparedStatements")
            val pgPort = Regex("://[^:/]+:(\\d+)/").find(isolated.jdbcUrl)!!.groupValues[1]
            val database = isolated.jdbcUrl.substringAfterLast('/')
            val ini = """
                [databases]
                * = host=host.docker.internal port=$pgPort
                [pgbouncer]
                listen_addr = 0.0.0.0
                listen_port = 6432
                auth_type = plain
                auth_file = /etc/pgbouncer/userlist.txt
                pool_mode = transaction
                default_pool_size = 2
                max_client_conn = 200
                max_prepared_statements = $maxPreparedStatements
                ignore_startup_parameters = extra_float_digits,options
            """.trimIndent()
            bouncer = GenericContainer(DockerImageName.parse(IMAGE))
                .withExtraHost("host.docker.internal", "host-gateway")
                .withCopyToContainer(Transferable.of(ini), "/etc/pgbouncer/pgbouncer.ini")
                .withCopyToContainer(Transferable.of("\"${isolated.user}\" \"${isolated.password}\"\n"), "/etc/pgbouncer/userlist.txt")
                .withExposedPorts(6432)
                .waitingFor(Wait.forListeningPort())
            bouncer.start()
            val base = "jdbc:postgresql://${bouncer.host}:${bouncer.getMappedPort(6432)}/$database"
            bouncerUrl = if (urlSuffix.isEmpty()) base else base + (if ('?' in base) "&" else "?") + urlSuffix
        }

        @AfterAll
        fun stop() {
            if (::bouncer.isInitialized) bouncer.stop()
            if (::server.isInitialized) server.close()
        }

        private fun catalog(url: String = bouncerUrl, connections: Int = 4) = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = url
            catalogDatabaseUser = isolated.user
            catalogDatabasePassword = isolated.password
            dataPath = isolated.dataDir.toAbsolutePath().toString()
            maxCatalogConnections = connections
        })

        @Test
        fun repeatedReadsAndWritesSurviveStatementPreparation() {
            catalog().use { c ->
                repeat(12) { i ->
                    val snapshot = c.currentSnapshotId
                    assertThat(c.getTable("test_schema", "simple_table", snapshot)).isNotNull
                    c.createSchema("pgb_${maxPreparedStatements}_$i")
                }
                assertThat(c.listSchemas(c.currentSnapshotId).map { it.schemaName }).contains("pgb_${maxPreparedStatements}_11")
            }
        }

        @Test
        fun aReadSessionStaysPinnedWhileAnotherWriterCommits() {
            catalog().use { reader -> catalog(isolated.jdbcUrl).use { writer ->
                reader.readSession(Supplier {
                    val before = reader.currentSnapshotId
                    writer.createSchema("pgb_pin_$maxPreparedStatements")
                    assertThat(reader.currentSnapshotId).`as`("REPEATABLE READ snapshot inside readSession").isEqualTo(before)
                    assertThat(reader.getSchema("pgb_pin_$maxPreparedStatements", reader.currentSnapshotId)).isNull()
                })
            } }
        }

        /**
         * Deterministic server-connection interleaving (pool of 2, LIFO reuse): another client holds server
         * connection A while the read session is set up, then releases it before the session's first read.
         * Session-level SET issued outside the transaction lands on B; the session's transaction then runs on A.
         */
        @Test
        fun readSessionIsolationIsAppliedToTheSessionsOwnTransaction() {
            val reader = catalog(connections = 1)
            val writer = catalog(isolated.jdbcUrl)
            val holder = DriverManager.getConnection(bouncerUrl, isolated.user, isolated.password)
            try {
                holder.autoCommit = false
                holder.createStatement().use { it.execute("SELECT 1") } // holds server connection A
                reader.readSession(Supplier {
                    holder.commit()
                    val before = reader.currentSnapshotId
                    writer.createSchema("pgb_lifo_$maxPreparedStatements")
                    assertThat(reader.currentSnapshotId).`as`("REPEATABLE READ snapshot inside readSession").isEqualTo(before)
                })
            } finally {
                runCatching { holder.rollback() }
                holder.close(); writer.close(); reader.close()
            }
            DriverManager.getConnection(bouncerUrl, isolated.user, isolated.password).use { raw ->
                val seen = (1..6).map {
                    raw.createStatement().use { s ->
                        s.executeQuery("SELECT current_setting('default_transaction_isolation')").use { r -> r.next(); r.getString(1) }
                    }
                }
                assertThat(seen).`as`("session default isolation seen by an unrelated client").containsOnly("read committed")
            }
        }

        @Test
        fun readSessionSettingsDoNotLeakToOtherClients() {
            catalog().use { c -> repeat(6) { c.readSession(Supplier { c.currentSnapshotId }) } }
            DriverManager.getConnection(bouncerUrl, isolated.user, isolated.password).use { raw ->
                val seen = (1..10).map {
                    raw.createStatement().use { s ->
                        s.executeQuery("SELECT current_setting('transaction_isolation'), current_setting('transaction_read_only')").use { r ->
                            r.next(); r.getString(1) + "/" + r.getString(2)
                        }
                    }
                }
                assertThat(seen).`as`("isolation/read-only seen by an unrelated client").containsOnly("read committed/off")
            }
        }

        @Test
        fun expiryCleanupAndRecommendedIndexesWork() {
            catalog().use { c ->
                c.createSchema("pgb_exp_$maxPreparedStatements")
                val expirable = c.listExpirableSnapshots(null, null)
                assertThat(expirable).isNotEmpty
                val result = c.expireSnapshots(expirable.toSet(), 50)
                assertThat(result.expiredSnapshotCount).isEqualTo(expirable.size)
                while (!c.cleanUpExpiredObjects(50).cleanupComplete) Unit
                assertThat(c.listTablesWithExpiredFiles(null, 10)).isEmpty()
                val indexes = c.ensureRecommendedIndexes()
                assertThat(indexes.skippedReason).isNull()
                assertThat(indexes.created + indexes.existing).isNotEmpty
            }
        }
    }

    companion object {
        /** edoburu/pgbouncer v1.23.1-p3 (PgBouncer 1.23.1), pinned by digest. */
        private const val IMAGE =
            "edoburu/pgbouncer@sha256:377dec3c0e4a66a1077ec043e16a26ed5702a6d954011a7983a1457c2e070b1d"
    }
}
