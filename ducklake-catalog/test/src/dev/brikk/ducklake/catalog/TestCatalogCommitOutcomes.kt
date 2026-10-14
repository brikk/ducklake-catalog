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
import org.junit.jupiter.api.Test
import java.sql.SQLException

class TestCatalogCommitOutcomes {
    @Test
    fun explicitServerRejectionsKeepTheirRetryClassification() {
        val errors = listOf(
            SQLException("serialization", "40001"), SQLException("deadlock", "40P01"), SQLException("duplicate", "23505"),
            SQLException("deadlock", "HY000", 1213),
            SQLException("TransactionContext Error: Failed to commit: write-write conflict"),
            SQLException("Constraint Error: Duplicate key violates primary key constraint"),
        )
        errors.forEach { error ->
            assertThatThrownBy { CatalogCommitOutcomes.commit("test", 7) { throw error } }.isSameAs(error)
        }
    }

    @Test
    fun unknownStatesAndTimeoutsAreNotRollbackProof() {
        for (state in listOf("08006", "08007", "40003", "HYT00", "HYT01", "HY000", null)) {
            val error = SQLException("transaction conflict while waiting for COMMIT reply", state)
            assertThatThrownBy { CatalogCommitOutcomes.commit("test", 7) { throw error } }
                .isInstanceOf(DucklakeCommitOutcomeUnknownException::class.java).hasCause(error)
        }
    }

    @Test
    fun transportStateWinsOverAConflictingNestedCause() {
        val error = SQLException("TransactionContext Error: Failed to commit: conflict", "08006")
        error.initCause(SQLException("serialization", "40001"))
        assertThatThrownBy { CatalogCommitOutcomes.commit("test", 7) { throw error } }
            .isInstanceOf(DucklakeCommitOutcomeUnknownException::class.java).hasCause(error)
    }

    @Test
    fun nonSqlDriverFailureAtCommitIsUnknownToo() {
        val error = IllegalStateException("RPC response decoding failed")
        assertThatThrownBy { CatalogCommitOutcomes.commit("test", null) { throw error } }
            .isInstanceOf(DucklakeCommitOutcomeUnknownException::class.java).hasCause(error)
    }

    @Test
    fun unknownOutcomesAreNotRetriedEvenWithARetryableCause() {
        var attempts = 0
        val cause = TransactionConflictException("not sufficient commit evidence", null)
        val unknown = DucklakeCommitOutcomeUnknownException("test", 7, cause)
        assertThatThrownBy {
            WriteTransactionRetry.retryOnConflict(3, 1, 2.0, { }, "test") { attempts++; throw unknown }
        }.isSameAs(unknown)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun aTypedAcknowledgedCommitFailureRemainsAcknowledged() {
        val error = DucklakePostCommitException("test", SQLException("local conflict", "40001"))
        assertThatThrownBy { CatalogCommitOutcomes.commit("test", 7) { throw error } }.isSameAs(error)
    }
}
