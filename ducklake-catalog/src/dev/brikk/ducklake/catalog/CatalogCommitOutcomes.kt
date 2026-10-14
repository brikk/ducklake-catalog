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

import java.sql.SQLException
import java.util.Locale

/** Classify at the COMMIT boundary, before generic SQL conflict/retry classification. */
internal object CatalogCommitOutcomes {
    // JDBC/RPC adapters may throw non-SQL exceptions after sending COMMIT; they are uncertain too.
    @Suppress("TooGenericExceptionCaught")
    fun commit(operation: String, snapshotId: Long?, action: () -> Unit) {
        try {
            action()
        }
        catch (e: Exception) {
            val classified = if (e is DucklakePostCommitException || e is DucklakeCommitOutcomeUnknownException || isDefiniteRejection(e)) e
            else DucklakeCommitOutcomeUnknownException(operation, snapshotId, e)
            throw classified
        }
    }

    private fun isDefiniteRejection(error: Throwable): Boolean {
        var current: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (current != null && seen.add(current)) {
            if (current is SQLException) {
                val state = current.sqlState
                // Transport failures/timeouts and statement-completion-unknown MUST NOT be
                // reclassified from conflict words in their messages (or nested causes).
                if (state != null && state !in setOf("HY000", "")) {
                    return state in setOf("40001", "40P01", "23505")
                }
                if (current.errorCode == 1213) return true // MySQL deadlock: server rolled back.
                // Maintained DuckDB/native Quack do not consistently supply SQLState.
                // Require the backend's explicit SQL error envelope, not a bare "conflict".
                val message = current.message?.lowercase(Locale.ROOT) ?: return false
                return (message.contains("transactioncontext error:") &&
                    (message.contains("conflict") || message.contains("primary key") || message.contains("unique"))) ||
                    (message.contains("constraint error: duplicate key") && message.contains("primary key"))
            }
            current = current.cause
        }
        return false
    }
}
