package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class TestMirrorCoverage {
    private val attach = "statement ok\nLOAD ducklake\n\nstatement ok\n" +
        "ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake (DATA_PATH '{TEST_DIR}/data', " +
        "DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)\n\n"
    private val query = "query I\nSELECT 1\n----\n1\n\n"

    private class Engine(private val accepts: Boolean = true) : ReplayReadEngine {
        override val name = "coverage-control"
        var calls = 0
        override fun connect(attachment: OracleAttachment) = Unit
        override fun accepts(sql: String) = accepts
        override fun executeQuery(sql: String): List<List<String?>> { calls++; return listOf(listOf("1")) }
        override fun close() = Unit
    }

    private fun replay(text: String, engine: ReplayReadEngine? = null, cap: Int = 40): CorpusReport =
        CorpusReport(listOf(ReplayDriver(engine, mirrorCapPerFile = cap).replay(SltParser.parse("coverage.test", text))))

    @Test fun anAlwaysRejectingEngineReportsNoVerifiedMirrorsAndItsReason() {
        val engine = Engine(false)
        val report = replay(attach + query, engine)
        assertThat(engine.calls).isZero()
        assertThat(report.failures).isEmpty()
        assertThat(report.summary()).contains("1 oracle/golden passes", "mirrors: 0 attempted, 0 passed, 0 failed, 1 skipped", "dialect-rejected")
        assertThatThrownBy { report.requireMirrorPasses(1) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun anUnattachedFileCannotClaimMirrorCoverage() {
        val engine = Engine()
        val report = replay(query, engine)
        assertThat(engine.calls).isZero()
        assertThat(report.summary()).contains("mirrors: 0 attempted, 0 passed, 0 failed, 1 skipped", "not-attached")
    }

    @Test fun actualMirrorPassesAndCapSkipsAreSeparateFromOraclePasses() {
        val engine = Engine()
        val report = replay(attach + query + query, engine, cap = 1)
        assertThat(engine.calls).isEqualTo(1)
        assertThat(report.summary()).contains("2 oracle/golden passes", "mirrors: 1 attempted, 1 passed, 0 failed, 1 skipped", "mirror-cap")
        report.requireMirrorPasses(1)
    }

    @Test fun transactionGatesAreVisibleAndIdentityControlRemainsExplicit() {
        val engine = Engine()
        val report = replay(attach + "statement ok\nBEGIN\n\n" + query + "statement ok\nROLLBACK\n\n", engine)
        assertThat(engine.calls).isZero()
        assertThat(report.summary()).contains("transaction-open", "mirrors: 0 attempted, 0 passed, 0 failed, 1 skipped")
        val identity = replay(query)
        assertThat(identity.totalPassed).isEqualTo(1)
        assertThat(identity.summary()).contains("1 identity-control", "mirrors: 0 attempted, 0 passed, 0 failed, 0 skipped")
    }

    @Test fun failedAndExplicitlySkippedMirrorsStillCountAsActualAttempts() {
        for (outcome in listOf("wrong", "throw", "skip")) {
            val engine = object : ReplayReadEngine {
                override val name = "outcome-control"
                override fun connect(attachment: OracleAttachment) = Unit
                override fun executeQuery(sql: String): List<List<String?>> = when (outcome) {
                    "throw" -> error("controlled query failure")
                    "skip" -> throw ReplayEngineSkip("known query gap")
                    else -> listOf(listOf("2"))
                }
                override fun close() = Unit
            }
            val report = replay(attach + query, engine)
            assertThat(report.totalOracleQueryPasses).isEqualTo(1)
            assertThat(report.totalMirrorAttempts).isEqualTo(1)
            assertThat(report.totalMirrorPasses).isZero()
            assertThat(report.totalPendingMirrors).isZero()
            if (outcome == "skip") {
                assertThat(report.totalMirrorSkips).isEqualTo(1)
                assertThat(report.files.single().skipped).hasSize(1)
                assertThat(report.totalMirrorFailures).isZero()
            } else assertThat(report.totalMirrorFailures).isEqualTo(1)
            assertThatThrownBy { report.requireMirrorPasses(1) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test fun acceptanceFaultIsNotAnAttemptOrADialectSkipAndBadGoldensNeverReachTheEngine() {
        val engine = object : ReplayReadEngine {
            override val name = "gate-control"
            override fun connect(attachment: OracleAttachment) = Unit
            override fun accepts(sql: String): Boolean = error("controlled acceptance failure")
            override fun executeQuery(sql: String): List<List<String?>> = error("must not execute")
            override fun close() = Unit
        }
        val report = replay(attach + query, engine)
        assertThat(report.totalMirrorAttempts).isZero()
        assertThat(report.totalMirrorGateFailures).isEqualTo(1)
        assertThat(report.totalMirrorSkips).isZero()
        assertThat(report.failures).hasSize(1)
        val wrong = replay(attach + query.replace("----\n1", "----\n2"), engine)
        assertThat(wrong.totalOracleQueryPasses).isZero()
        assertThat(wrong.totalMirrorGateFailures).isZero()
        assertThat(wrong.failures).hasSize(1)
    }

    @Test fun manualOrCopiedResultsAreUnmeasuredNotFabricatedMirrorSuccess() {
        val original = replay(attach + query, Engine()).files.single()
        assertThat(original.queryCoverage!!.mirrorPasses).isEqualTo(1)
        val copy = original.copy()
        assertThat(copy).isEqualTo(original)
        assertThat(copy.queryCoverage).isNull()
        val report = CorpusReport(listOf(copy))
        assertThat(report.unmeasuredFiles).isEqualTo(1)
        assertThat(report.totalMirrorPasses).isZero()
        assertThatThrownBy { report.requireMirrorPasses(1) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { report.requireMirrorPasses(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(FileResult::class.java.getConstructor(String::class.java, String::class.java, List::class.java)).isNotNull()
    }

    @Test fun coverageSurvivesRequirementHaltsAndDriverReuseDoesNotAccumulateIt() {
        val engine = Engine()
        val driver = ReplayDriver(engine)
        val result = driver.replay(SltParser.parse("halt.test", attach + query + "require unsupported_fixture\n\n"))
        assertThat(result.fileSkipReason).startsWith("require ")
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(1)
        val next = driver.replay(SltParser.parse("next.test", query))
        assertThat(next.queryCoverage!!.mirrorAttempts).isZero()
        assertThat(next.queryCoverage!!.mirrorSkipReasons).containsEntry("not-attached", 1)
    }
}
