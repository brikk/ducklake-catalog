package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** H14: real native effects distinguish one execution from a retry/rebind or count-shaped guess. */
internal class TestDmlReturningExecution {
    private fun statement(sql: String) = "statement ok\n$sql\n\n"
    private fun query(sql: String, types: String = "I", expected: String = "", sort: String = "nosort") =
        "query $types $sort\n$sql\n----\n$expected\n\n"
    private fun replay(text: String) = ReplayDriver().replay(SltParser.parse("dml-returning.test", text)).also {
        assertThat(it.fileSkipReason).isNull()
        assertThat(it.skipped).isEmpty()
    }
    private fun clean(text: String) { assertThat(replay(text).failed).isEmpty() }

    @Test fun insertReturningProducesRowsAndExactlyOneSourceEffect() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY,v VARCHAR)") +
            query("INSERT INTO t VALUES(101,'one') RETURNING i,v", "IT", "101\tone") +
            query("SELECT count(*),sum(i) FROM t", "II", "1\t101"))
    }

    @Test fun updateReturningPreservesTypedRowsAndExactAuditEffects() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY,v INTEGER); INSERT INTO t VALUES(1,10),(2,20); " +
            "CREATE TABLE audit(i INTEGER,v INTEGER); CREATE TRIGGER upd AFTER UPDATE ON t REFERENCING NEW TABLE AS n " +
            "FOR EACH STATEMENT INSERT INTO audit SELECT i,v FROM n") +
            query("UPDATE t SET v=v+1 WHERE i=1 RETURNING i,v", "II", "1\t11") +
            query("SELECT count(*),sum(v) FROM t", "II", "2\t31") +
            query("SELECT count(*),sum(v) FROM audit", "II", "1\t11"))
    }

    @Test fun deleteReturningPreservesNullsAndOneAuditEffect() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY,v VARCHAR); INSERT INTO t VALUES(1,NULL),(2,'kept'); " +
            "CREATE TABLE audit(i INTEGER); CREATE TRIGGER del AFTER DELETE ON t REFERENCING OLD TABLE AS o " +
            "FOR EACH STATEMENT INSERT INTO audit SELECT i FROM o") +
            query("DELETE FROM t WHERE i=1 RETURNING i,v", "IT", "1\tNULL") +
            query("SELECT i,v FROM t", "IT", "2\tkept") +
            query("SELECT count(*),sum(i) FROM audit", "II", "1\t1"))
    }

    @Test fun mergeReturningDoesNotApplyMatchedUpdatesTwice() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY,v INTEGER); INSERT INTO t VALUES(1,10)") +
            query("MERGE INTO t AS dst USING(VALUES(1,11),(2,20)) AS src(i,v) ON dst.i=src.i " +
                "WHEN MATCHED THEN UPDATE SET v=dst.v+1 WHEN NOT MATCHED THEN INSERT VALUES(src.i,src.v) RETURNING dst.i,dst.v",
                "II", "1\t11\n2\t20", "rowsort") +
            query("SELECT count(*),sum(v) FROM t", "II", "2\t31"))
    }

    @Test fun emptyReturningResultsRetainTheirSchemaRatherThanBecomingNothing() {
        val result = replay(statement("CREATE TABLE t(i INTEGER,v VARCHAR)") +
            query("UPDATE t SET v='none' WHERE FALSE RETURNING i,v", "IT") +
            query("DELETE FROM t WHERE FALSE RETURNING i,v", "I") + // intentionally wrong width
            query("SELECT count(*) FROM t", expected = "0"))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("column count")
    }

    @Test fun countLikeAliasesAreStillActualReturningAndSelectValues() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY)") +
            query("INSERT INTO t VALUES(77) RETURNING i AS Count", expected = "77") +
            query("UPDATE t SET i=i WHERE i=77 RETURNING i AS rows_affected", expected = "77") +
            query("SELECT count(*) AS \"count_star()\" FROM t", expected = "1"))
    }

    @Test fun booleanAndVarcharReturningColumnsKeepTheirActualSqlTypes() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY,b BOOLEAN,v VARCHAR)") +
            query("INSERT INTO t VALUES(1,FALSE,'false') RETURNING b,v", "IT", "0\tfalse") +
            query("SELECT count(*) FROM t", expected = "1"))
    }

    @Test fun ordinaryDmlCountsIncludingZeroCtesCommentsMergeAndTruncateStayCorrect() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY,v VARCHAR)") +
            query("INSERT INTO t VALUES(1,'RETURNING'),(2,'Count')", expected = "2") +
            query("UPDATE t SET v='literal RETURNING; SELECT' WHERE i=1", expected = "1") +
            query("DELETE FROM t WHERE i=99", expected = "0") +
            query("WITH v(i,v) AS(VALUES(3,'cte')) INSERT INTO t SELECT * FROM v", expected = "1") +
            query("/* RETURNING is only a comment */ UPDATE t SET v=v WHERE i=1", expected = "1") +
            query("MERGE INTO t USING(VALUES(4,'merge'))src(i,v) ON t.i=src.i WHEN NOT MATCHED THEN INSERT VALUES(src.i,src.v)", expected = "1") +
            query("DELETE FROM t WHERE i=2", expected = "1") +
            query("TRUNCATE t", expected = "3") + query("SELECT count(*) FROM t", expected = "0"))
    }

    @Test fun commentedAndCteReturningNeedNoLexicalSpecialCase() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY)") +
            query("/* prefix */ INSERT INTO t VALUES(1) RETURNING i", expected = "1") +
            query("WITH v(i) AS(VALUES(2)) INSERT INTO t SELECT i FROM v RETURNING i", expected = "2") +
            query("SELECT count(*),sum(i) FROM t", "II", "2\t3"))
    }

    @Test fun returningInsideAnExplicitRollbackDoesNotLeakEffects() {
        clean(statement("CREATE TABLE t(i INTEGER PRIMARY KEY)") + statement("BEGIN") +
            query("INSERT INTO t VALUES(55) RETURNING i", expected = "55") + statement("ROLLBACK") +
            query("SELECT count(*) FROM t", expected = "0"))
    }

    @Test fun aWrongReturningShapeFailsAfterOneMutationWithoutRetry() {
        val result = replay(statement("CREATE TABLE t(i INTEGER PRIMARY KEY)") +
            query("INSERT INTO t VALUES(9) RETURNING i", "II", "9\t9") +
            query("SELECT count(*),sum(i) FROM t", "II", "1\t9"))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("column count")
    }

    @Test fun aWrongReturningGoldenFailsAfterOneMutationWithoutRetry() {
        val result = replay(statement("CREATE TABLE t(i INTEGER PRIMARY KEY)") +
            query("INSERT INTO t VALUES(9) RETURNING i", expected = "99") +
            query("SELECT count(*),sum(i) FROM t", "II", "1\t9"))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("expected", "got")
    }

    @Test fun readAdapterDialectGatesStillPreventDmlMirrorsAndCountOnlyRealReads() {
        val calls = mutableListOf<String>()
        val engine = object : ReplayReadEngine {
            override val name = "returning-gate-control"
            override fun connect(attachment: OracleAttachment) = Unit
            override fun accepts(sql: String) = sql.startsWith("SELECT")
            override fun executeQuery(sql: String): List<List<String?>> { calls += sql; return listOf(listOf("1")) }
            override fun close() = Unit
        }
        val text = statement("LOAD ducklake") + statement("ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake " +
            "(DATA_PATH '{TEST_DIR}/data',DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)") +
            statement("CREATE TABLE t(i INTEGER PRIMARY KEY)") + query("INSERT INTO t VALUES(1) RETURNING i", expected = "1") +
            query("SELECT count(*) FROM t", expected = "1")
        val result = ReplayDriver(engine).replay(SltParser.parse("returning-gate.test", text))
        assertThat(result.failed).isEmpty()
        assertThat(calls).containsExactly("SELECT count(*) FROM t")
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(2)
        assertThat(result.queryCoverage!!.mirrorAttempts).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("dialect-rejected" to 1))
    }
}
