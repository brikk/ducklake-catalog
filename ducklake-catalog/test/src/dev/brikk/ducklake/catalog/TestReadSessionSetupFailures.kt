package dev.brikk.ducklake.catalog

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.sql.DataSource

/** Review R04: a session whose promised SQL-server isolation was not established never runs its action. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TestReadSessionSetupFailures {
    private lateinit var server: TestingDucklakePostgreSqlCatalogServer
    private lateinit var root: Path
    private val names = AtomicInteger()

    @BeforeAll fun start() {
        root = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-review-r04-")
        server = TestingDucklakePostgreSqlCatalogServer()
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            s.execute("ATTACH '${server.getDuckDbAttachUri()}' AS lake (DATA_PATH '$root'," +
                "DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE); DETACH lake")
        } }
    }

    @AfterAll fun stop() {
        try { if (::server.isInitialized) server.close() }
        finally {
            if (::root.isInitialized) Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun open(strict: Boolean = false) = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = server.getJdbcUrl()
        catalogDatabaseUser = server.getUser()
        catalogDatabasePassword = server.getPassword()
        dataPath = root.toString()
        maxCatalogConnections = 1
        enableFinal11Reads = strict
    })

    @Test fun legacyIsolationErrorsRejectInsteadOfObservingACompetingSnapshot() {
        for (fault in listOf(SQLException("controlled isolation transport failure", "08006"),
            SQLException("controlled isolation policy failure", "HY000"),
            SQLFeatureNotSupportedException("controlled unsupported isolation", "0A000"))) {
            open().use { reader -> open().use { writer ->
                Fault(reader, "setTransactionIsolation", fault).use { probe ->
                    val before = writer.currentSnapshotId
                    val seen = mutableListOf<Long>()
                    assertThatThrownBy { reader.readSession(Supplier {
                        seen += reader.currentSnapshotId
                        writer.createSchema("r04_competing_${names.incrementAndGet()}")
                        seen += reader.currentSnapshotId
                    }) }.`as`("a rejected setup must not run a callback; observed snapshots=$seen").isSameAs(fault)
                    assertThat(seen).isEmpty()
                    assertThat(writer.currentSnapshotId).isEqualTo(before)
                    assertThat(probe.isolationCalls).isEqualTo(1)
                    assertThat(probe.closed).isEqualTo(1)
                    probe.assertIdle()
                }
            } }
        }
    }

    @Test fun readOnlySetupFailureAlsoRejectsBeforeTheCallbackOrIsolationSetter() {
        open().use { reader ->
            val fault = SQLException("controlled read-only setup failure", "08006")
            Fault(reader, "setReadOnly", fault).use { probe ->
                var entered = false
                assertThatThrownBy { reader.readSession(Supplier { entered = true }) }.isSameAs(fault)
                assertThat(entered).isFalse()
                assertThat(probe.readOnlyCalls).isEqualTo(1)
                assertThat(probe.isolationCalls).isZero()
                assertThat(probe.closed).isEqualTo(1)
                probe.assertIdle()
            }
        }
    }

    @Test fun controlledOverloadReleasesTheConnectionAndFinishesItsControlAfterSetupFailure() {
        open().use { reader ->
            val fault = SQLException("controlled setup failure", "08006")
            Fault(reader, "setTransactionIsolation", fault).use { probe ->
                val control = DucklakeReadSessionControl(30)
                var entered = false
                assertThatThrownBy { reader.readSession(control, Supplier { entered = true }) }.isSameAs(fault)
                assertThat(entered).isFalse()
                assertThat(control.cancel()).isFalse()
                assertThatThrownBy { reader.readSession(control, Supplier { error("must not reuse a control") }) }
                    .isInstanceOf(IllegalStateException::class.java)
                assertThat(probe.closed).isEqualTo(1)
                probe.assertIdle()
                assertThat(reader.readSession(DucklakeReadSessionControl(30), Supplier { reader.currentSnapshotId }))
                    .isGreaterThanOrEqualTo(0)
            }
        }
    }

    @Test fun theNextSessionReestablishesIsolationAndNestingDoesNotRepeatSetup() {
        open().use { reader -> open().use { writer ->
            val fault = SQLException("controlled one-shot isolation fault", "08006")
            Fault(reader, "setTransactionIsolation", fault).use { probe ->
                assertThatThrownBy { reader.readSession(Supplier { error("callback must not run") }) }.isSameAs(fault)
                probe.assertIdle()
                val before = writer.currentSnapshotId
                reader.readSession(Supplier {
                    assertThat(probe.current!!.transactionIsolation).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
                    assertThat(probe.current!!.isReadOnly).isTrue()
                    assertThat(reader.currentSnapshotId).isEqualTo(before)
                    writer.createSchema("r04_recovered_${names.incrementAndGet()}")
                    assertThat(reader.currentSnapshotId).isEqualTo(before)
                    assertThat(reader.readSession(Supplier { reader.currentSnapshotId })).isEqualTo(before)
                    assertThatThrownBy { reader.createSchema("must_not_write") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                })
                assertThat(probe.isolationCalls).isEqualTo(2)
                assertThat(probe.closed).isEqualTo(2)
                probe.assertIdle()
                assertThat(reader.currentSnapshotId).isEqualTo(before + 1)
                probe.assertReset()
            }
        } }
    }

    @Test fun callerFailureDoesNotLeakAThreadLocalOrPinnedConnection() {
        open().use { reader ->
            val callerFailure = IllegalStateException("controlled callback failure")
            Fault(reader, "unused", SQLException("must not throw")).use { probe ->
                assertThatThrownBy { reader.readSession(Supplier { reader.currentSnapshotId; throw callerFailure }) }.isSameAs(callerFailure)
                probe.assertIdle()
                probe.assertReset()
                assertThat(reader.readSession(Supplier { reader.currentSnapshotId })).isGreaterThanOrEqualTo(0)
                assertThat(probe.isolationCalls).isEqualTo(2)
            }
        }
    }

    @Test fun strictOptInAlreadyRejectsTheSameFailureAndRetainsThatBoundary() {
        open(strict = true).use { reader ->
            val fault = SQLException("controlled strict setup failure", "08006")
            Fault(reader, "setTransactionIsolation", fault).use { probe ->
                assertThatThrownBy { reader.readSession(Supplier { error("strict callback must not run") }) }.isSameAs(fault)
                assertThat(probe.closed).isEqualTo(1)
                probe.assertIdle()
            }
        }
    }

    @Test fun legacyMySqlUsesRequiredSqlServerSettingsAsWell() {
        TestingDucklakeMySqlCatalogServer().use { mysql ->
            JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = mysql.getJdbcUrl()
                catalogDatabaseUser = mysql.getUser()
                catalogDatabasePassword = mysql.getPassword()
                dataPath = root.toString()
                maxCatalogConnections = 1
            }).use { reader ->
                for (method in listOf("setReadOnly", "setTransactionIsolation")) {
                    val fault = SQLFeatureNotSupportedException("controlled MySQL $method failure", "0A000")
                    Fault(reader, method, fault).use { probe ->
                        var entered = false
                        assertThatThrownBy { reader.readSession(Supplier { entered = true }) }.isSameAs(fault)
                        assertThat(entered).isFalse()
                        probe.assertIdle()
                    }
                }
                Fault(reader, "unused", SQLException("must not throw")).use { probe ->
                    val isolation = reader.readSession(Supplier { probe.current!!.transactionIsolation })
                    assertThat(isolation).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
                    probe.assertIdle()
                }
            }
        }
    }

    /** Test-only proxy around a real one-connection pool, never a replacement production extension point. */
    private class Fault(private val catalog: JdbcDucklakeCatalog, methodName: String, failure: SQLException) : AutoCloseable {
        private val field = JdbcDucklakeCatalog::class.java.getDeclaredField("dataSource").apply { isAccessible = true }
        private val pool = field.get(catalog) as HikariDataSource
        private var remaining = 1
        var current: Connection? = null
            private set
        var closed = 0
            private set
        var isolationCalls = 0
            private set
        var readOnlyCalls = 0
            private set

        init {
            val source = object : DataSource by pool {
                override fun getConnection(): Connection {
                    val delegate = pool.connection
                    return (Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                        when (method.name) {
                            "setTransactionIsolation" -> isolationCalls++
                            "setReadOnly" -> readOnlyCalls++
                            "close" -> closed++
                        }
                        if (method.name == methodName && remaining-- > 0) throw failure
                        invoke(method, delegate, args)
                    } as Connection).also { current = it }
                }
            }
            field.set(catalog, source)
        }

        fun assertIdle() { assertThat(pool.hikariPoolMXBean.activeConnections).isZero() }

        fun assertReset() {
            pool.connection.use { c ->
                assertThat(c.autoCommit).isTrue()
                assertThat(c.isReadOnly).isFalse()
                assertThat(c.transactionIsolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED)
            }
        }

        override fun close() { field.set(catalog, pool) }

        private fun invoke(method: Method, target: Any, args: Array<out Any?>?): Any? =
            try { method.invoke(target, *(args ?: emptyArray())) }
            catch (e: InvocationTargetException) { throw e.targetException }
    }
}
