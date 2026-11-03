package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Forces unfinished server work with valid bounded SQL, never an injected SQL/protocol error. */
class TestQuackEarlyCloseControl {
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun earlyCloseMustNotAbortTheFollowingRead() {
        PreviewQuackServer("1.1", "early-close-control").use { server ->
            server.start()
            // Both are valid queries. The first returns a row before the bounded second one ends.
            val sql = "SELECT 1; SELECT sum(sqrt(i::DOUBLE)) FROM range(10000000) t(i)"
            server.connection().use { drained ->
                drained.autoCommit = false
                assertThat(PreviewQuackServer.jdbcRows(drained, sql)).containsExactly(listOf("1"))
                assertThat(PreviewQuackServer.jdbcRows(drained, "SELECT 42")).containsExactly(listOf("42"))
                drained.rollback()
            }
            server.connection().use { early ->
                early.autoCommit = false
                early.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isEqualTo(1)
                        // Deliberately no second next(): legal JDBC consumer behavior.
                    }
                }
                val output = Files.createDirectories(Path.of(System.getProperty("quack.diagnostic.output")))
                try {
                    assertThat(PreviewQuackServer.jdbcRows(early, "SELECT 42")).containsExactly(listOf("42"))
                } catch (e: Exception) {
                    Files.writeString(output.resolve("early-close-error.txt"), e.stackTraceToString())
                    throw e
                } finally {
                    early.rollback()
                }
            }
        }
    }
}
