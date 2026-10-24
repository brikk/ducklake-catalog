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

import org.jooq.ExecuteContext
import org.jooq.impl.DefaultExecuteListener
import java.sql.SQLTimeoutException
import java.sql.Statement
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-use control for [JdbcDucklakeCatalog.readSession]. Another thread may [cancel] the active
 * JDBC statement. A positive deadline is mandatory: it supplies a remaining JDBC query timeout
 * and bounds a cancellation/execute race on drivers implementing that timeout. Unsupported timeout
 * configuration fails explicitly, never silently runs without a limit. Driver/network interruption
 * guarantees are backend-specific; this does not time out pool acquisition, connection setup,
 * JDBC prepare round trips, arbitrary callback work, or confer a physical-file lease.
 */
class DucklakeReadSessionControl(val timeoutSeconds: Int) {
    private val claimed = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val active = AtomicReference<Statement?>()
    @Volatile private var deadlineNanos = 0L

    init { require(timeoutSeconds in 1..3600) { "Read statement deadline must be in 1..3600 seconds" } }

    val isCancelled: Boolean get() = cancelled.get()

    /**
     * Marks cancellation permanently and calls Statement.cancel on the published statement, if any.
     * True means a cancel request was sent, NOT that the server stopped. JDBC failures propagate to
     * this caller; subsequent reads still refuse. The driver may block this call. No helper thread,
     * callback interruption, connection termination, or automatic query retry is hidden here.
     */
    fun cancel(): Boolean {
        cancelled.set(true)
        val statement = active.get() ?: return false
        statement.cancel()
        return true
    }

    internal fun claim() {
        check(claimed.compareAndSet(false, true)) { "Read session control is single-use" }
        deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
    }

    internal fun checkActive() {
        if (cancelled.get()) throw CancellationException("DuckLake read session was cancelled")
        if (System.nanoTime() - deadlineNanos >= 0) throw SQLTimeoutException("DuckLake read session statement deadline expired")
    }

    private fun publish(statement: Statement) {
        checkActive()
        active.set(statement)
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) throw SQLTimeoutException("DuckLake read session statement deadline expired")
        statement.queryTimeout = ((remaining + 999_999_999L) / 1_000_000_000L).toInt()
        checkActive()
    }

    internal fun finish() { active.set(null) }

    internal fun listener(): DefaultExecuteListener = object : DefaultExecuteListener() {
        override fun executeStart(ctx: ExecuteContext) {
            publish(ctx.statement() ?: throw DucklakeInvalidOperationException("Read cancellation requires an executing JDBC statement"))
        }

        override fun end(ctx: ExecuteContext) {
            // jOOQ can already have cleared ctx.statement() after closing it. The owned session
            // executes statements serially; do not leave a closed statement published until exit.
            active.set(null)
        }
    }
}
