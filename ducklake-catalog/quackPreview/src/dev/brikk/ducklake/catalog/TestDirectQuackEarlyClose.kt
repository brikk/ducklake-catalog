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
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * A result closed while the server statement is unfinished must not poison the read transaction
 * (catalog probes read one row then close). Quack JDBC 0.12.0 abandoned such results and the native
 * peer's next-statement interrupt aborted the transaction; see QUACK-TRANSACTION-DIAGNOSIS-2026-11-03.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDirectQuackEarlyClose {
    private lateinit var server: PreviewQuackServer

    @BeforeAll
    fun start() {
        server = PreviewQuackServer("1.1", "early-close").also { it.start() }
    }

    @AfterAll
    fun stop() {
        if (::server.isInitialized) server.close()
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun closingAnUnfinishedResultKeepsTheTransactionUsable() {
        // Valid SQL: the first statement's row is delivered while the bounded second one still runs.
        val sql = "SELECT 1; SELECT sum(sqrt(i::DOUBLE)) FROM range(10000000) t(i)"
        server.connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isEqualTo(1)
                        // Deliberately no further next(): legal JDBC consumer behavior.
                    }
                }
                assertThat(PreviewQuackServer.jdbcRows(connection, "SELECT 42")).containsExactly(listOf("42"))
                assertThat(PreviewQuackServer.jdbcRows(connection, "SELECT value FROM ducklake_metadata WHERE key='version'"))
                    .containsExactly(listOf("1.1"))
            }
            finally {
                connection.rollback()
            }
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun closingSingleRowProbesRepeatedlyKeepsTheTransactionUsable() {
        server.connection().use { connection ->
            connection.autoCommit = false
            try {
                repeat(200) {
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT 1 FROM information_schema.tables LIMIT 1").use { rows ->
                            assertThat(rows.next()).isTrue()
                        }
                    }
                    assertThat(PreviewQuackServer.jdbcRows(connection, "SELECT 2")).containsExactly(listOf("2"))
                }
            }
            finally {
                connection.rollback()
            }
        }
    }
}
