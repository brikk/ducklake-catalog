package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** H12: malformed loop bodies and even the file prefix must not produce oracle/backend effects. */
internal class TestReplayLoopTermination {
    private fun statement(sql: String) = "statement ok\n$sql\n\n"
    private fun copy(path: Path) = statement("COPY (SELECT 1 AS value) TO '${path.toString().replace("'", "''")}' (FORMAT CSV)")

    private fun refused(root: Path, header: String, nested: Boolean = false) {
        val prefix = root.resolve("prefix.csv")
        val body = root.resolve("body.csv")
        val text = copy(prefix) + "$header\n\n" +
            (if (nested) "loop j 0 1\n\n" else "") + copy(body) + (if (nested) "endloop\n" else "")
        val result = ReplayDriver().replay(SltParser.parse("unterminated.test", text))
        assertThat(Files.exists(prefix)).describedAs("prefix SQL must not execute before unsupported preflight").isFalse()
        assertThat(Files.exists(body)).describedAs("an unclosed body must not execute").isFalse()
        assertThat(result.fileSkipReason).contains("unsupported construct 'loop'").doesNotContain("CRASH", "TIMEOUT")
        assertThat(result.outcomes).isEmpty()
        assertThat(result.queryCoverage!!.oraclePasses).isZero()
    }

    @Test fun aTruncatedNumericLoopCannotExecuteItsBodyOrPrefix(@TempDir root: Path) = refused(root, "loop i 0 1")

    @Test fun aTruncatedForeachCannotExecuteItsBodyOrPrefix(@TempDir root: Path) {
        val prefix = root.resolve("prefix.csv")
        val body = root.resolve("body.csv")
        val result = ReplayDriver().replay(SltParser.parse("foreach.test", copy(prefix) + "foreach i one\n\n" + copy(body)))
        assertThat(Files.exists(prefix)).isFalse()
        assertThat(Files.exists(body)).isFalse()
        assertThat(result.fileSkipReason).contains("unsupported construct 'foreach'")
        assertThat(result.outcomes).isEmpty()
    }

    @Test fun zeroIterationLoopsMustStillBeTerminatedBeforeAnythingExecutes(@TempDir root: Path) = refused(root, "loop i 1 1")
    @Test fun anInnerEndloopDoesNotPermitAnUnclosedOuterBodyToRun(@TempDir root: Path) = refused(root, "loop i 0 1", true)
    @Test fun aModelledConditionalDoesNotMakeTheMissingTerminatorSafe(@TempDir root: Path) =
        refused(root, "onlyif duckdb\nloop i 0 1")

    @Test fun staticUnsupportedPreflightPreventsBackendRewritingAndMirrorConnection() {
        var rewrites = 0
        var connects = 0
        var queries = 0
        val engine = object : ReplayReadEngine {
            override val name = "termination-preflight-control"
            override fun connect(attachment: OracleAttachment) { connects++ }
            override fun executeQuery(sql: String): List<List<String?>> { queries++; return listOf(listOf("1")) }
            override fun close() = Unit
        }
        val setup = statement("LOAD ducklake") + statement("ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake " +
            "(DATA_PATH '{TEST_DIR}/data',DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)")
        val text = setup + "loop i 0 1\n\nquery I\nSELECT 1\n----\n1\n\n"
        val result = ReplayDriver(engine, metadataRewriter = { rewrites++; it }).replay(SltParser.parse("preflight.test", text))
        assertThat(result.fileSkipReason).contains("unsupported construct 'loop'")
        assertThat(result.outcomes).isEmpty()
        assertThat(rewrites).isZero()
        assertThat(connects).isZero()
        assertThat(queries).isZero()
        assertThat(result.queryCoverage!!.oraclePasses).isZero()
        assertThat(result.queryCoverage!!.mirrorAttempts).isZero()
    }

    @Test fun theRunnerReportsMalformedFrontierAndStillRunsAnIndependentNextFile(@TempDir root: Path) {
        val marker = root.resolve("must-not-exist.csv")
        val bad = Files.writeString(root.resolve("bad.test"), copy(marker) + "loop i 0 1\n\n" + statement("SELECT 1"))
        val good = Files.writeString(root.resolve("good.test"), "query I\nSELECT 7\n----\n7\n\n")
        val result = CorpusRunner(root).run(listOf(bad, good))
        assertThat(Files.exists(marker)).isFalse()
        assertThat(result.files[0].fileSkipReason).contains("unsupported construct 'loop'")
        assertThat(result.files[0].outcomes).isEmpty()
        assertThat(result.files[1].fileSkipReason).isNull()
        assertThat(result.totalPassed).isEqualTo(1)
        assertThat(result.failures).isEmpty()
        assertThat(result.crashes).isEmpty()
    }

    @Test fun theSameFixtureWithARealTerminatorExecutesBothOwnedWrites(@TempDir root: Path) {
        val prefix = root.resolve("prefix.csv")
        val body = root.resolve("body.csv")
        val result = ReplayDriver().replay(SltParser.parse("closed.test", copy(prefix) + "loop i 0 1\n\n" + copy(body) + "endloop\n"))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).isEmpty()
        assertThat(result.skipped).isEmpty()
        assertThat(result.passed).isEqualTo(2)
        assertThat(Files.readString(prefix)).contains("1")
        assertThat(Files.readString(body)).contains("1")
    }
}
