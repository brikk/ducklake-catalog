package dev.brikk.ducklake.catalog

import com.gizmodata.quack.jdbc.message.QuackMessage
import com.gizmodata.quack.jdbc.sql.QuackDriver
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport
import com.gizmodata.quack.jdbc.transport.QuackTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** Opt-in investigation only: published driver and signed server, no recovery or query replay. */
class TestQuackTransactionDiagnosis {
    private var writer: java.io.BufferedWriter? = null
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun traceOwnedCatalogAndDirectSessions() {
        val output = Files.createDirectories(Path.of(System.getProperty("quack.diagnostic.output")))
        val log = output.resolve("transport.log")
        writer = Files.newBufferedWriter(log)
        val serial = AtomicInteger()
        val delegate = QuackDriver()
        val originals = DriverManager.getDrivers().toList().filterIsInstance<QuackDriver>()
        originals.forEach(DriverManager::deregisterDriver)
        val driver = object : Driver by delegate {
            override fun connect(url: String?, info: Properties?) = delegate.connect(url, info) { uri ->
                val http = QuackHttpTransport.from(uri)
                val session = serial.incrementAndGet()
                object : QuackTransport {
                    override fun setProtocolVersion(version: Long) = http.setProtocolVersion(version)
                    override fun send(request: QuackMessage): QuackMessage {
                        val id = "$session/${request.header().clientQueryId().orElse(-1)}"
                        // Never serialize a connection request, URI, headers, properties or tokens.
                        write(log, "$id SEND ${request.javaClass.simpleName}" +
                            if (request is QuackMessage.PrepareRequest) " SQL=${request.sql()}" else "")
                        try {
                            return http.send(request).also { response ->
                                write(log, "$id RECV ${response.javaClass.simpleName}" +
                                    if (response is QuackMessage.PrepareResponse) " more=${response.needsMoreFetch()} rows=${response.results().sumOf { it.rowCount() }}" else "")
                            }
                        } catch (e: RuntimeException) {
                            write(log, "$id ERROR ${e.javaClass.simpleName}: ${e.message}\n" +
                                e.stackTrace.take(18).joinToString("\n"))
                            throw e
                        }
                    }
                }
            }
        }
        DriverManager.registerDriver(driver)
        try {
            PreviewQuackServer("1.1", "transaction-diagnosis").use { server ->
                server.start()
                server.native("CREATE TABLE lake.diagnostic_inline(i INTEGER); INSERT INTO lake.diagnostic_inline VALUES (1),(2),(3)")
                server.catalog(enableFinal11Reads = true).use { reader ->
                    repeat(600) { iteration ->
                        write(log, "CATALOG iteration=$iteration")
                        reader.readSession(Supplier {
                            val snapshot = reader.currentSnapshotId
                            val table = reader.getTable("main", "diagnostic_inline", snapshot)!!
                            val columns = reader.getTableColumns(table.tableId, snapshot)
                            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
                            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns))
                                .containsExactly(listOf(1), listOf(2), listOf(3))
                            assertThat(reader.getLiveRowCount(table.tableId, snapshot)).isEqualTo(3)
                        })
                    }
                }
                server.connection().use { connection ->
                    connection.autoCommit = false
                    repeat(300) { iteration ->
                        write(log, "DIRECT iteration=$iteration")
                        assertThat(PreviewQuackServer.jdbcRows(connection, "SELECT value FROM ducklake_metadata WHERE key='version'"))
                            .containsExactly(listOf("1.1"))
                        assertThat(PreviewQuackServer.jdbcRows(connection, "SELECT 1")).containsExactly(listOf("1"))
                        connection.rollback()
                    }
                    repeat(600) { iteration ->
                        write(log, "DIRECT_EARLY_CLOSE iteration=$iteration")
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT 1 FROM information_schema.tables LIMIT 1").use { rows ->
                                assertThat(rows.next()).isTrue()
                                assertThat(rows.getInt(1)).isEqualTo(1)
                            }
                        }
                        assertThat(PreviewQuackServer.jdbcRows(connection, "SELECT 1")).containsExactly(listOf("1"))
                        connection.rollback()
                    }
                }
            }
        } finally {
            DriverManager.deregisterDriver(driver)
            originals.forEach(DriverManager::registerDriver)
            writer?.close()
        }
    }

    private fun write(path: Path, line: String) {
        synchronized(this) {
            check(path.fileName.toString() == "transport.log")
            writer!!.appendLine(line)
        }
    }
}
