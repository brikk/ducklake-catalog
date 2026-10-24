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
import org.jooq.exception.DataAccessException
import org.jooq.ExecuteContext
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.SQLFeatureNotSupportedException
import java.sql.PreparedStatement
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class TestReadSessionCancellation {
    @Test
    fun controlsRequirePositiveBoundedTimeoutsAndAreSingleUse() {
        for (seconds in listOf(-1, 0, 3601)) {
            assertThatThrownBy { DucklakeReadSessionControl(seconds) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1)")
            f.reader(maxConnections = 1).use { c ->
                val control = DucklakeReadSessionControl(30)
                assertThat(control.cancel()).isFalse()
                assertThat(control.isCancelled).isTrue()
                assertThatThrownBy { c.readSession(control, Supplier { c.currentSnapshotId }) }.isInstanceOf(CancellationException::class.java)
                assertThatThrownBy { c.readSession(control, Supplier { c.currentSnapshotId }) }.isInstanceOf(IllegalStateException::class.java)
                val second = DucklakeReadSessionControl(30)
                assertThatThrownBy { c.readSession(second, Supplier {
                    val snapshot = c.currentSnapshotId
                    assertThat(c.readSession(second, Supplier { c.currentSnapshotId })).isEqualTo(snapshot)
                    assertThat(c.readSession(Supplier { c.currentSnapshotId })).isEqualTo(snapshot)
                    assertThatThrownBy { c.readSession(DucklakeReadSessionControl(30), Supplier { c.currentSnapshotId }) }
                        .isInstanceOf(IllegalArgumentException::class.java)
                    assertThat(second.cancel()).isFalse() // A completed statement must already be unpublished.
                    Unit
                }) }.isInstanceOf(CancellationException::class.java)
                assertThat(second.cancel()).isFalse() // Must not cancel a statement subsequently using the pooled connection.
                val failure = IllegalStateException("original caller failure")
                assertThatThrownBy { c.readSession(DucklakeReadSessionControl(30), Supplier {
                    c.currentSnapshotId
                    throw failure
                }) }.isSameAs(failure)
                c.readSession(Supplier { assertThat(c.getTable("main", "t", c.currentSnapshotId)).isNotNull() })
            }
        }
    }

    @Test
    fun unsupportedJdbcTimeoutCannotSilentlyBecomeAnUnboundedQuery() {
        val control = DucklakeReadSessionControl(30)
        control.claim()
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
            if (method.name == "setQueryTimeout") throw SQLFeatureNotSupportedException("test unsupported timeout")
            null
        } as PreparedStatement
        val context = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExecuteContext::class.java)) { _, method, _ ->
            if (method.name == "statement") statement else null
        } as ExecuteContext
        assertThatThrownBy { control.listener().executeStart(context) }.isInstanceOf(SQLFeatureNotSupportedException::class.java)
            .hasMessageContaining("unsupported timeout")
        control.finish()
        assertThat(control.cancel()).isFalse()
    }

    @Test
    fun postgresStatementCancelStopsTheActualLockedQueryBeforeTheFixtureUnlocksAndPoolRecovers() {
        lockedQuery(cancel = true)
    }

    @Test
    fun postgresJdbcTimeoutStopsTheActualLockedQueryBeforeTheFixtureUnlocksAndPoolRecovers() {
        lockedQuery(cancel = false)
    }

    private fun lockedQuery(cancel: Boolean) {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
            f.reader(maxConnections = 1).use { c ->
                val before = c.readSession(Supplier { c.currentSnapshotId })
                assertBlockedRead(f, c, cancel)
                c.readSession(Supplier {
                    assertThat(c.currentSnapshotId).isEqualTo(before)
                    assertThat(c.getTable("main", "t", before)).isNotNull()
                })
            }
        }
    }

    private fun assertBlockedRead(f: Final11PostgresFixture, c: JdbcDucklakeCatalog, cancel: Boolean) {
        f.metadataConnection().use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE ducklake_snapshot IN ACCESS EXCLUSIVE MODE") }
            val executor = Executors.newSingleThreadExecutor()
            val control = DucklakeReadSessionControl(if (cancel) 30 else 3)
            try {
                val query = executor.submit<Long> { c.readSession(control, Supplier { c.currentSnapshotId }) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var waiting = false
                while (System.nanoTime() < deadline && !waiting) {
                    waiting = waitingQueries(f) > 0
                    if (!waiting) TimeUnit.MILLISECONDS.sleep(20)
                }
                assertThat(waiting).isTrue()
                if (cancel) assertThat(control.cancel()).isTrue()
                assertThatThrownBy { query.get(6, TimeUnit.SECONDS) }.isInstanceOf(ExecutionException::class.java)
                    .hasCauseInstanceOf(DataAccessException::class.java)
                // The lock is STILL HELD: completion must come from actual cancel/timeout, not fixture release.
                assertThat(waitingQueries(f)).isZero()
            } finally {
                lock.rollback()
                executor.shutdownNow()
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
            }
        }
    }

    private fun waitingQueries(f: Final11PostgresFixture): Long = f.rows(
        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() " +
            "AND wait_event_type='Lock' AND query LIKE '%ducklake_snapshot%'",
    ).single().single()!!.toLong()
}
