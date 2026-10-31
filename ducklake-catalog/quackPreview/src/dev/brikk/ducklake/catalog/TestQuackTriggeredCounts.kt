package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import java.sql.Statement

/** Driver adoption controls: real v3 SQL, no embedded JDBC, and exact effects rather than SQL replay. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TestQuackTriggeredCounts {
    private lateinit var peer: PreviewQuackServer

    @BeforeAll fun start() { peer = PreviewQuackServer("1.0", "driver-counts").also { it.start() } }
    @AfterAll fun stop() { if (::peer.isInitialized) peer.close() }

    private fun scalar(s: Statement, sql: String): Long = s.executeQuery(sql).use { rows ->
        assertThat(rows.next()).isTrue()
        val value = rows.getLong(1)
        assertThat(rows.next()).isFalse()
        value
    }

    @Test fun triggeredInsertStatementPreparedAndBatchCountsHaveExactAuditEffects() {
        peer.connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE TABLE ins_target(id INTEGER)")
            s.execute("CREATE TABLE ins_audit(id INTEGER)")
            s.execute("CREATE TRIGGER ins_ai AFTER INSERT ON ins_target FOR EACH ROW INSERT INTO ins_audit VALUES(NEW.id)")
            assertThat(s.execute("INSERT INTO ins_target VALUES(1),(2)")).isFalse()
            assertThat(s.updateCount).isEqualTo(2)
            assertThat(s.resultSet).isNull()
            assertThat(s.moreResults).isFalse()
            assertThat(s.updateCount).isEqualTo(-1)
            assertThat(s.executeUpdate("INSERT INTO ins_target VALUES(3),(4)")).isEqualTo(2)
            c.prepareStatement("INSERT INTO ins_target VALUES(?)").use { p ->
                p.setInt(1, 5)
                assertThat(p.execute()).isFalse()
                assertThat(p.updateCount).isEqualTo(1)
                p.setInt(1, 6)
                assertThat(p.executeUpdate()).isEqualTo(1)
                p.setInt(1, 7); p.addBatch()
                p.setInt(1, 8); p.addBatch()
                assertThat(p.executeBatch()).containsExactly(1, 1)
            }
            assertThat(scalar(s, "SELECT count(*) FROM ins_target")).isEqualTo(8)
            assertThat(scalar(s, "SELECT count(*) FROM ins_audit")).isEqualTo(8)
            assertThat(scalar(s, "SELECT sum(id) FROM ins_audit")).isEqualTo(36)
        } }
    }

    @Test fun triggeredPreparedUpdateDeleteAndZeroMatchCountsStayDistinctFromAuditRows() {
        peer.connection().use { c -> c.createStatement().use { s ->
            for (operation in listOf("UPDATE", "DELETE")) {
                val target = "${operation.lowercase()}_target"
                val audit = "${target}_audit"
                s.execute("CREATE TABLE $target(id INTEGER,v INTEGER)")
                s.execute("CREATE TABLE $audit(id INTEGER)")
                s.executeUpdate("INSERT INTO $target VALUES(1,10),(2,20),(3,30)")
                s.execute("CREATE TRIGGER ${target}_after AFTER $operation ON $target REFERENCING OLD TABLE AS old_rows " +
                    "FOR EACH STATEMENT INSERT INTO $audit SELECT id FROM old_rows")
                val sql = if (operation == "UPDATE") "UPDATE $target SET v=v+1 WHERE id<=?" else "DELETE FROM $target WHERE id<=?"
                c.prepareStatement(sql).use { p ->
                    p.setInt(1, 2)
                    assertThat(p.execute()).isFalse()
                    assertThat(p.updateCount).isEqualTo(2)
                    assertThat(p.resultSet).isNull()
                    p.setInt(1, 0)
                    assertThat(p.executeUpdate()).isZero()
                    p.setInt(1, if (operation == "UPDATE") 1 else 3)
                    assertThat(p.executeUpdate()).isEqualTo(1)
                }
                assertThat(scalar(s, "SELECT count(*) FROM $audit")).isEqualTo(3)
                assertThat(scalar(s, "SELECT sum(id) FROM $audit")).isEqualTo(if (operation == "UPDATE") 4 else 6)
                assertThat(scalar(s, "SELECT count(*) FROM $target")).isEqualTo(if (operation == "UPDATE") 3 else 0)
                if (operation == "UPDATE") assertThat(scalar(s, "SELECT sum(v) FROM $target")).isEqualTo(63)
            }
        } }
    }

    @Test fun selectCountsAndReturningAliasesRemainResultSetsIncludingEmptyResults() {
        peer.connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE TABLE shapes(id INTEGER)")
            s.executeUpdate("INSERT INTO shapes VALUES(1),(2)")
            for (projection in listOf("count(*)", "count(*) AS Count", "count(*) AS rows_affected", "count(*) AS \"count_star()\"")) {
                assertThat(s.execute("SELECT $projection FROM shapes")).isTrue()
                assertThat(s.updateCount).isEqualTo(-1)
                s.resultSet.use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isEqualTo(2)
                    assertThat(rows.next()).isFalse()
                }
            }
            s.execute("CREATE TABLE shapes_audit(id INTEGER)")
            for ((operation, reference) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) {
                s.execute("CREATE TRIGGER shapes_$operation AFTER $operation ON shapes REFERENCING $reference TABLE AS r " +
                    "FOR EACH STATEMENT INSERT INTO shapes_audit SELECT id FROM r")
            }
            for (sql in listOf("INSERT INTO shapes VALUES(3) RETURNING id AS Count",
                "UPDATE shapes SET id=id WHERE id=3 RETURNING id AS rows_affected",
                "DELETE FROM shapes WHERE id=3 RETURNING id AS \"count_star()\"")) {
                assertThat(s.execute(sql)).isTrue()
                assertThat(s.updateCount).isEqualTo(-1)
                s.resultSet.use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(3)
                    assertThat(rows.next()).isFalse()
                }
            }
            assertThat(s.execute("UPDATE shapes SET id=id WHERE false RETURNING id AS Count")).isTrue()
            s.resultSet.use { assertThat(it.next()).isFalse() }
            assertThat(scalar(s, "SELECT count(*) FROM shapes")).isEqualTo(2)
            assertThat(scalar(s, "SELECT count(*) FROM shapes_audit")).isEqualTo(3)
            assertThat(scalar(s, "SELECT sum(id) FROM shapes_audit")).isEqualTo(9)
        } }
    }

    @Test fun ctesNestedCommentsAndReturningInsideBoundStringsDoNotChangeDmlClassification() {
        peer.connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE TABLE context_target(id INTEGER,note VARCHAR)")
            s.execute("CREATE TABLE context_audit(id INTEGER)")
            s.execute("CREATE TRIGGER context_ai AFTER INSERT ON context_target FOR EACH ROW INSERT INTO context_audit VALUES(NEW.id)")
            assertThat(s.executeUpdate("/* outer /* RETURNING */ comment */ WITH \"select\"(id) AS MATERIALIZED " +
                "(SELECT 1) INSERT INTO context_target SELECT id, 'RETURNING; SELECT' FROM \"select\"")).isEqualTo(1)
            assertThat(s.executeUpdate("WITH RECURSIVE q(n) AS (VALUES(2) UNION ALL SELECT n+1 FROM q WHERE n<3) " +
                "INSERT INTO context_target SELECT n, 'literal' FROM q")).isEqualTo(2)
            c.prepareStatement("INSERT INTO context_target VALUES(?,?)").use { p ->
                p.setInt(1, 4)
                p.setString(2, "RETURNING 'quoted' /* text */ ;")
                assertThat(p.executeUpdate()).isEqualTo(1)
            }
            assertThat(scalar(s, "SELECT count(*) FROM context_target")).isEqualTo(4)
            assertThat(scalar(s, "SELECT count(*) FROM context_audit")).isEqualTo(4)
            assertThat(scalar(s, "SELECT sum(id) FROM context_audit")).isEqualTo(10)
        } }
    }

    @Test fun rowTriggerReturningErrorsAndTransactionRollbackDoNotPublishSuccessOrReplayEffects() {
        peer.connection().use { c -> c.createStatement().use { s ->
            s.execute("CREATE TABLE atomic_target(id INTEGER)")
            s.execute("CREATE TABLE atomic_audit(id INTEGER CHECK(id>0))")
            s.execute("CREATE TRIGGER atomic_ai AFTER INSERT ON atomic_target FOR EACH ROW INSERT INTO atomic_audit VALUES(NEW.id)")
            assertThatThrownBy { s.execute("INSERT INTO atomic_target VALUES(99) RETURNING id AS Count") }
                .isInstanceOf(SQLException::class.java).hasMessageContaining("RETURNING is not yet supported on tables with FOR EACH ROW triggers")
            assertThat(s.updateCount).isEqualTo(-1)
            assertThat(s.resultSet).isNull()
            assertThat(scalar(s, "SELECT count(*) FROM atomic_target")).isZero()
            assertThat(scalar(s, "SELECT count(*) FROM atomic_audit")).isZero()
            c.autoCommit = false
            assertThat(s.executeUpdate("INSERT INTO atomic_target VALUES(1)")).isEqualTo(1)
            assertThatThrownBy { s.executeUpdate("INSERT INTO atomic_target VALUES(-1)") }.isInstanceOf(SQLException::class.java)
            assertThat(s.updateCount).isEqualTo(-1)
            assertThat(s.resultSet).isNull()
            c.rollback()
            c.autoCommit = true
            assertThat(scalar(s, "SELECT count(*) FROM atomic_target")).isZero()
            assertThat(scalar(s, "SELECT count(*) FROM atomic_audit")).isZero()
            assertThat(s.executeUpdate("INSERT INTO atomic_target VALUES(2)")).isEqualTo(1)
            assertThat(scalar(s, "SELECT count(*) FROM atomic_target")).isEqualTo(1)
            assertThat(scalar(s, "SELECT sum(id) FROM atomic_audit")).isEqualTo(2)
        } }
    }
}
