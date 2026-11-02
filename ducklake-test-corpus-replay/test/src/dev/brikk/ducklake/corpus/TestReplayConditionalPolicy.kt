package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** H11: audit real native effects and measured oracle/mirror calls, not just expanded text. */
internal class TestReplayConditionalPolicy {
    private fun statement(sql: String) = "statement ok\n$sql\n\n"
    private fun query(sql: String, expected: String, types: String = "I") = "query $types\n$sql\n----\n$expected\n\n"
    private fun loop(name: String, values: String, body: String) = "foreach $name $values\n\n${body}endloop\n\n"
    private fun replay(text: String) = ReplayDriver().replay(SltParser.parse("condition-policy.test", text)).also {
        assertThat(it.fileSkipReason).isNull()
        assertThat(it.failed).isEmpty()
    }

    @Test fun contradictorySkipTermsDoNotInsertOrProducePhantomPasses() {
        val text = statement("CREATE TABLE audit(i INTEGER)") +
            loop("i", "0 1", "skipif i=0&&i=1\n" + statement("INSERT INTO audit VALUES(\${i})")) +
            query("SELECT count(*) FROM audit", "0")
        val result = replay(text)
        assertThat(result.passed).isEqualTo(2)
        assertThat(result.skipped).hasSize(2)
    }

    @Test fun twoVariableTruthTableAllowsExactlyOneNativeMutation() {
        val body = "skipif i=0&&j=0\n" + statement("INSERT INTO audit VALUES(\${i},\${j})")
        val text = statement("CREATE TABLE audit(i INTEGER,j INTEGER)") + loop("i", "0 1", loop("j", "0 1", body)) +
            query("SELECT i,j FROM audit ORDER BY i,j", "1\t1", "II")
        val result = replay(text)
        assertThat(result.passed).isEqualTo(3)
        assertThat(result.skipped).hasSize(3)
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(1)
    }

    @Test fun numericSkipTermsKeepExactlyTheTwoInteriorValues() {
        val text = statement("CREATE TABLE audit(i INTEGER)") +
            loop("i", "-1 0 1 2", "skipif i<0&&i>1\n" + statement("INSERT INTO audit VALUES(\${i})")) +
            query("SELECT i FROM audit ORDER BY i", "0\n1")
        val result = replay(text)
        assertThat(result.passed).isEqualTo(4)
        assertThat(result.skipped).hasSize(2)
    }

    @Test fun anUnreachableMissingBindingDoesNotCrashOrRunTheGuardedSql(@TempDir root: Path) {
        val text = statement("CREATE TABLE audit(i INTEGER)") +
            loop("i", "0", "skipif i=0&&missing=1\n" + statement("INSERT INTO audit VALUES(1)")) +
            query("SELECT count(*) FROM audit", "0")
        val file = Files.writeString(root.resolve("unreachable.test"), text)
        val report = CorpusRunner(root).run(listOf(file))
        assertThat(report.crashes).isEmpty()
        assertThat(report.files.single().fileSkipReason).isNull()
        assertThat(report.failures).isEmpty()
        assertThat(report.totalPassed).isEqualTo(2)
        assertThat(report.totalRecordSkips).isEqualTo(1)
    }

    @Test fun stackedOnlyAndSkipGuardsValidateOnlyTheAllowedQuery() {
        val body = "onlyif i>=0&&j>=0\nskipif i=0&&j=0\n" + query("SELECT \${i},\${j}", "1\t1", "II")
        val result = replay(loop("i", "0 1", loop("j", "0 1", body)))
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.skipped).hasSize(3)
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(1)
        assertThat(result.queryCoverage!!.identityControlPasses).isEqualTo(1)
    }

    @Test fun guardedOutQueriesNeverReachTheMirrorOrInflateItsCoverage() {
        var connects = 0
        var accepts = 0
        var calls = 0
        val engine = object : ReplayReadEngine {
            override val name = "condition-policy-control"
            override fun connect(attachment: OracleAttachment) { connects++ }
            override fun accepts(sql: String): Boolean { accepts++; return true }
            override fun executeQuery(sql: String): List<List<String?>> { calls++; return listOf(listOf("1")) }
            override fun close() = Unit
        }
        val setup = statement("LOAD ducklake") + statement("ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake " +
            "(DATA_PATH '{TEST_DIR}/data',DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)")
        val body = "skipif i=0&&j=0\n" + query("SELECT 1", "1")
        val result = ReplayDriver(engine).replay(SltParser.parse("condition-mirror.test", setup +
            loop("i", "0 1", loop("j", "0 1", body))))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).isEmpty()
        assertThat(result.skipped).hasSize(3)
        assertThat(connects).isEqualTo(1)
        assertThat(accepts).isEqualTo(1)
        assertThat(calls).isEqualTo(1)
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorAttempts).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorSkips).isZero()
    }

    @Test fun systemGuardsStillWorkOutsideLoops() {
        val result = replay("onlyif DuCkDb\nskipif POSTGRES\n" + query("SELECT 42", "42"))
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.skipped).isEmpty()
    }

    @Test fun onlyifConjunctionStillRequiresAllTermsForARealInsert() {
        val body = "onlyif i=0&&j=0\n" + statement("INSERT INTO audit VALUES(\${i},\${j})")
        val text = statement("CREATE TABLE audit(i INTEGER,j INTEGER)") + loop("i", "0 1", loop("j", "0 1", body)) +
            query("SELECT i,j FROM audit", "0\t0", "II")
        val result = replay(text)
        assertThat(result.passed).isEqualTo(3)
        assertThat(result.skipped).hasSize(3)
    }
}
