package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

internal class TestAdapterSetupFailures {
    private class Engine(private val fault: Exception? = null) : ReplayReadEngine {
        override val name = "setup-control"
        var connects = 0
        var queries = 0
        override fun connect(attachment: OracleAttachment) { connects++; if (fault != null) throw fault }
        override fun executeQuery(sql: String): List<List<String?>> { queries++; return listOf(listOf("1")) }
        override fun close() = Unit
    }

    private fun replay(expectation: String, engine: Engine, invalid: Boolean = false): FileResult {
        val options = if (invalid) ", NOT_AN_OPTION true" else ""
        val text = "statement ok\nLOAD ducklake\n\nstatement $expectation\n" +
            "ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake (DATA_PATH '{TEST_DIR}/data', " +
            "DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE$options)\n" +
            (if (expectation != "ok") "----\n${if (invalid) "" else "controlled setup failure"}\n\n" else "\n") +
            "query I\nSELECT 1\n----\n1\n\n"
        return ReplayDriver(engine).replay(SltParser.parse("setup.test", text)).also {
            assertThat(it.fileSkipReason).isNull()
        }
    }

    @Test fun expectedOracleErrorCannotBeSatisfiedByAnAdapterConnectionFault() {
        val engine = Engine(SQLException("controlled setup failure"))
        val result = replay("error", engine)
        assertThat(result.failed).hasSize(1)
        assertThat(result.failed.single().reason).contains("statement succeeded")
        assertThat(engine.connects).isZero() // an already-failed oracle expectation is never adapter setup
        assertThat(engine.queries).isZero()
    }

    @Test fun successfulOracleOkOrMaybeNeverAcceptsSqlAdapterErrors() {
        for (expectation in listOf("ok", "maybe")) {
            val engine = Engine(SQLException("controlled setup failure"))
            val result = replay(expectation, engine)
            assertThat(result.failed).singleElement().extracting { it.reason }.asString()
                .contains("engine 'setup-control' connect failed", "controlled setup failure")
            assertThat(engine.connects).isEqualTo(1)
            assertThat(engine.queries).isZero()
            assertThat(result.queryCoverage!!.mirrorSkipReasons).containsEntry("adapter-setup-failed", 1)
        }
    }

    @Test fun uncheckedOrQueryOnlySkipExceptionsDuringSetupAreFailuresNotCrashesOrSkips() {
        for (fault in listOf(IllegalStateException("controlled setup failure"), ReplayEngineSkip("controlled setup failure"))) {
            val result = replay("ok", Engine(fault))
            assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("connect failed")
            assertThat(result.skipped).isEmpty()
        }
    }

    @Test fun realExpectedOracleFailureNeverCallsTheAdapterAndSuccessfulSetupMirrors() {
        val failed = Engine(SQLException("must not run"))
        assertThat(replay("error", failed, invalid = true).failed).isEmpty()
        assertThat(failed.connects).isZero()
        val normal = Engine()
        assertThat(replay("ok", normal).failed).isEmpty()
        assertThat(normal.connects).isEqualTo(1)
        assertThat(normal.queries).isEqualTo(1)
    }
}
