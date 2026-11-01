package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltConditional
import dev.brikk.ducklake.slt.SltFile
import dev.brikk.ducklake.slt.SltLimits
import dev.brikk.ducklake.slt.SltLimitExceeded
import dev.brikk.ducklake.slt.SltLoop
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltRecord
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class TestReplayResourceLimits {
    private val sql = SltStatement(1, "SELECT 1", false, null, null)
    private val earlier = "query I\nSELECT 1\n----\n2\n\nstatement ok\nSELECT 1\n\nskipif duckdb\nquery I\nSELECT 1\n----\n1\n\n"

    @Test fun oversizedSourceIsBoundedWithOrWithoutTheWatchdogAndDoesNotBlockLaterFiles(@TempDir root: Path) {
        val large = Files.writeString(root.resolve("large.test"), "statement ok\nSELECT 1\n\n" + "#".repeat(SltLimits.MAX_SOURCE_CHARS))
        val next = Files.writeString(root.resolve("next.test"), "statement ok\nSELECT 1\n\n")
        for (timeout in listOf(0L, -1L, 5L)) {
            val report = CorpusRunner(root, fileTimeoutSeconds = timeout).run(listOf(large, next))
            assertThat(report.files[0].fileSkipReason).startsWith("RESOURCE LIMIT:").contains("source characters")
            assertThat(report.files[0].outcomes).isEmpty()
            assertThat(report.files[1].fileSkipReason).isNull()
            assertThat(report.totalPassed).isEqualTo(1)
            assertThat(report.crashes).isEmpty()
        }
    }

    @Test fun hugeLoopParserRefusalNeverExecutesTheEarlierRunnablePrefix(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("range.test"), earlier + "loop i 0 1000000000\n\nendloop\n")
        val result = CorpusRunner(root).run(listOf(file)).files.single()
        assertThat(result.fileSkipReason).startsWith("RESOURCE LIMIT:").contains("loop values")
        assertThat(result.outcomes).isEmpty()
        assertThat(result.queryCoverage!!.oraclePasses).isZero()
    }

    @Test fun replayRejectsHandBuiltDeepOrCyclicAstBeforeRecursivePreflight() {
        var conditional: SltRecord = sql
        repeat(20_000) { conditional = SltConditional(1, false, "duckdb", conditional) }
        val cycle = mutableListOf<SltRecord>()
        cycle += SltLoop(1, "i", listOf("0"), cycle)
        for (records in listOf(listOf(conditional), cycle)) {
            val result = ReplayDriver().replay(SltFile("deep.test", records))
            assertThat(result.fileSkipReason).startsWith("RESOURCE LIMIT:").contains("nesting")
            assertThat(result.outcomes).isEmpty()
        }
    }

    @Test fun replayRejectsHandBuiltExpansionProductsIncludingEmptyBodyWork() {
        val inner = SltLoop(2, "j", List(1000) { "0" }, emptyList())
        val outer = SltLoop(1, "i", List(1000) { "0" }, listOf(inner))
        val result = ReplayDriver().replay(SltFile("product.test", listOf(sql, outer)))
        assertThat(result.fileSkipReason).startsWith("RESOURCE LIMIT:").contains("expanded visits")
        assertThat(result.outcomes).isEmpty()
    }

    @Test fun dynamicEnvGrowthKeepsEarlierFailuresPassesAndSkipsAndStopsBeforeLaterSql(@TempDir root: Path) {
        val env = "test-env X x\n\n" + "test-env X {X}{X}\n\n".repeat(30)
        val file = Files.writeString(root.resolve("env.test"), earlier + env + "statement ok\nSELECT 'must not run'\n\n")
        val report = CorpusRunner(root).run(listOf(file))
        assertThat(report.files.single().fileSkipReason).startsWith("RESOURCE LIMIT:")
        assertThat(report.failures).hasSize(1)
        assertThat(report.totalPassed).isEqualTo(1)
        assertThat(report.totalRecordSkips).isEqualTo(1)
        assertThat(report.files.single().outcomes).hasSize(3)
        assertThat(report.crashes).isEmpty()
        assertThat(report.summary()).contains("halted mid-file by resource limit 1")
    }

    @Test fun directOracleTemplateGrowthAndLoopReplacementAreSizeCheckedBeforeAllocation() {
        DuckDbOracle().use { oracle ->
            oracle.defineEnv("X", "x".repeat(100_000))
            assertThatThrownBy { oracle.substitute("{X}".repeat(1000)) }
                .isInstanceOf(SltLimitExceeded::class.java)
        }
        val file = SltFile("loop-text.test", listOf(SltLoop(1, "i", listOf("x".repeat(100_000)),
            listOf(sql.copy(sql = "SELECT '" + "{i}".repeat(1000) + "'")))))
        val result = ReplayDriver().replay(file)
        assertThat(result.fileSkipReason).startsWith("RESOURCE LIMIT:").contains("text characters")
        assertThat(result.outcomes).isEmpty()
    }

    @Test fun preparationIsInsideWatchdogAndCooperativeCancellationJoinsItsWorker(@TempDir root: Path) {
        val worker = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = CorpusRunner(root, fileTimeoutSeconds = 1)
        try {
            val result = runner.replayGuarded("preparation.test") {
                worker.set(Thread.currentThread()); entered.countDown(); release.await()
                error("must not replay after cancellation")
            }
            assertThat(entered.count).isZero()
            assertThat(worker.get().isAlive).isFalse()
            assertThat(result.fileSkipReason).startsWith("TIMEOUT:").contains("worker stopped")
            assertThat(result.outcomes).isEmpty()
            val next = Files.writeString(root.resolve("next.test"), "statement ok\nSELECT 1\n\n")
            assertThat(runner.run(listOf(next)).files.single().fileSkipReason).startsWith("BLOCKED:")
        } finally { release.countDown(); worker.get()?.join(5000) }
    }

    @Test fun unresponsivePreparationIsQuarantinedAndCannotPublishAfterTimeout(@TempDir root: Path) {
        val worker = AtomicReference<Thread>()
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = CorpusRunner(root, fileTimeoutSeconds = 1)
        try {
            val result = runner.replayGuarded("stuck-preparation.test") {
                worker.set(Thread.currentThread())
                var waiting = true
                while (waiting) {
                    try { release.await(); waiting = false }
                    catch (_: InterruptedException) { interrupted.countDown() }
                }
                SltParser.parse("stuck-preparation.test", "statement ok\nSELECT 1\n\n")
            }
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(result.fileSkipReason).startsWith("TIMEOUT:").contains("worker still active")
            assertThat(result.outcomes).isEmpty()
            release.countDown(); worker.get().join(5000)
            assertThat(worker.get().isAlive).isFalse()
            assertThat(result.outcomes).isEmpty()
            val next = Files.writeString(root.resolve("next.test"), "statement ok\nSELECT 1\n\n")
            assertThat(runner.run(listOf(next)).files.single().fileSkipReason).startsWith("BLOCKED:")
        } finally { release.countDown(); worker.get()?.join(5000) }
    }

    @Test fun preparationCrashesAreReportedRatherThanEscapingBeforeWatchdog(@TempDir root: Path) {
        val file = root.resolve("missing.test")
        val report = CorpusRunner(root).run(listOf(file))
        assertThat(report.crashes).hasSize(1)
        assertThat(report.files.single().outcomes).isEmpty()
    }
}
