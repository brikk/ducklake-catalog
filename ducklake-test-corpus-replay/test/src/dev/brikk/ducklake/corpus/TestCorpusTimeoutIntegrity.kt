package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import dev.brikk.ducklake.slt.SltParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** H04: controlled blocking callback, no real backend lock or permanently leaked test worker. */
internal class TestCorpusTimeoutIntegrity {
    private val attach = "statement ok\nATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake (DATA_PATH '{TEST_DIR}/data')\n\n"
    private val earlier = "query I\nSELECT 1\n----\n2\n\nstatement ok\nSELECT 1\n\nskipif duckdb\nquery I\nSELECT 1\n----\n1\n\n"

    @Test fun timeoutPreservesCompletedFailurePassAndSkipAndStopsLaterFiles(@TempDir root: Path) {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val calls = AtomicInteger()
        val first = Files.writeString(root.resolve("first.test"), earlier + attach)
        val later = Files.writeString(root.resolve("later.test"), attach)
        val runner = CorpusRunner(root, metadataRewriter = {
            calls.incrementAndGet()
            worker.set(Thread.currentThread())
            entered.countDown()
            var waiting = true
            while (waiting) {
                try { release.await(); waiting = false }
                catch (_: InterruptedException) { interrupted.countDown() } // model a driver ignoring interrupt
            }
            throw IllegalStateException("released disposable worker")
        }, fileTimeoutSeconds = 1)
        try {
            val report = runner.run(listOf(first, later))
            assertThat(entered.count).isZero()
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(report.failures).hasSize(1)
            assertThat(report.totalPassed).isEqualTo(1)
            assertThat(report.totalRecordSkips).isEqualTo(1)
            assertThat(report.files[0].fileSkipReason).startsWith("TIMEOUT:").contains("worker still active")
            assertThat(report.files[0].outcomes).hasSize(3)
            assertThat(report.files[1].fileSkipReason).startsWith("BLOCKED:")
            assertThat(report.files[1].outcomes).isEmpty()
            assertThat(calls.get()).isEqualTo(1)
            release.countDown()
            worker.get().join(5000)
            assertThat(worker.get().isAlive).isFalse()
            assertThat(report.failures).hasSize(1) // worker completion cannot mutate a returned report
            assertThat(report.files[0].outcomes).hasSize(3)
            val again = runner.run(listOf(later))
            assertThat(again.files.single().fileSkipReason).startsWith("BLOCKED:")
            assertThat(calls.get()).isEqualTo(1)
        } finally {
            release.countDown()
            worker.get()?.join(5000)
        }
    }

    @Test fun cooperativeWorkerIsInterruptedAndJoinedBeforeReturning(@TempDir root: Path) {
        val worker = AtomicReference<Thread>()
        val release = CountDownLatch(1)
        val file = Files.writeString(root.resolve("interruptible.test"), earlier + attach)
        try {
            val report = CorpusRunner(root, metadataRewriter = {
                worker.set(Thread.currentThread())
                release.await()
                error("must not continue after cancellation")
            }, fileTimeoutSeconds = 1).run(listOf(file))
            assertThat(worker.get()).isNotNull()
            assertThat(worker.get().isAlive).isFalse()
            assertThat(report.files.single().fileSkipReason).startsWith("TIMEOUT:").contains("worker stopped")
            assertThat(report.failures).hasSize(1)
        } finally {
            release.countDown()
            worker.get()?.join(5000)
        }
    }

    @Test fun disabledWatchdogAndNormalCompletionRetainTheirOutcomes(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("normal.test"), earlier)
        for (timeout in listOf(0L, -1L, 5L)) {
            val report = CorpusRunner(root, fileTimeoutSeconds = timeout).run(listOf(file))
            assertThat(report.failures).hasSize(1)
            assertThat(report.totalPassed).isEqualTo(1)
            assertThat(report.totalRecordSkips).isEqualTo(1)
            assertThat(report.files.single().fileSkipReason).isNull()
        }
    }

    @Test fun engineBlockedDuringMirrorIsNotReusedOrClosedByTheWatchdog(@TempDir root: Path) {
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val queries = AtomicInteger()
        val connects = AtomicInteger()
        val closes = AtomicInteger()
        val engine = object : ReplayReadEngine {
            override val name = "blocking-engine"
            override fun connect(attachment: OracleAttachment) { connects.incrementAndGet() }
            override fun executeQuery(sql: String): List<List<String?>> {
                queries.incrementAndGet()
                worker.set(Thread.currentThread())
                release.await()
                return listOf(listOf("1"))
            }
            override fun close() { closes.incrementAndGet() }
        }
        val text = earlier + "statement ok\nLOAD ducklake\n\n" + attach + "query I\nSELECT 1\n----\n1\n\n"
        val file = Files.writeString(root.resolve("engine.test"), text)
        val next = Files.writeString(root.resolve("next.test"), text)
        try {
            val report = CorpusRunner(root, engine = engine, fileTimeoutSeconds = 1).run(listOf(file, next))
            assertThat(report.failures).hasSize(1)
            assertThat(report.files[0].fileSkipReason).startsWith("TIMEOUT:")
            assertThat(report.files[1].fileSkipReason).startsWith("BLOCKED:")
            assertThat(connects.get()).isEqualTo(1)
            assertThat(queries.get()).isEqualTo(1)
            assertThat(report.totalMirrorAttempts).isEqualTo(1)
            assertThat(report.totalMirrorPasses).isZero()
            assertThat(report.totalPendingMirrors).isEqualTo(1)
            assertThat(report.unmeasuredFiles).isZero()
            assertThat(closes.get()).isZero() // the caller owns the engine; no concurrent close under a worker
            assertThat(worker.get().isAlive).isFalse()
        } finally {
            release.countDown()
            worker.get()?.join(5000)
        }
    }

    @Test fun crashAlsoKeepsPreviouslyCompletedOutcomes(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("crash.test"), earlier + attach)
        val report = CorpusRunner(root, metadataRewriter = { error("controlled callback fault") }).run(listOf(file))
        assertThat(report.crashes).hasSize(1)
        assertThat(report.failures).hasSize(1)
        assertThat(report.totalPassed).isEqualTo(1)
        assertThat(report.totalRecordSkips).isEqualTo(1)
        assertThat(report.summary()).contains("1 failed", "halted mid-file by crash/timeout 1")
    }

    @Test fun interruptingTheCallerCancelsItsWorkerAndQuarantinesTheRunner(@TempDir root: Path) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val thrown = AtomicReference<Throwable>()
        val interruptRestored = CountDownLatch(1)
        val file = Files.writeString(root.resolve("caller.test"), attach)
        val runner = CorpusRunner(root, metadataRewriter = {
            worker.set(Thread.currentThread()); entered.countDown(); release.await(); it
        }, fileTimeoutSeconds = 30)
        val caller = Thread {
            try { runner.run(listOf(file)) }
            catch (e: InterruptedException) {
                thrown.set(e)
                if (Thread.currentThread().isInterrupted) interruptRestored.countDown()
            }
        }
        try {
            caller.start()
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            caller.interrupt()
            caller.join(5000)
            assertThat(caller.isAlive).isFalse()
            assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
            assertThat(interruptRestored.count).isZero()
            assertThat(worker.get().isAlive).isFalse()
            assertThat(runner.run(listOf(file)).files.single().fileSkipReason).startsWith("BLOCKED:")
        } finally {
            release.countDown()
            caller.join(5000)
            worker.get()?.join(5000)
        }
    }

    @Test fun progressFreezeIsAnImmutablePrefixAndRejectsFurtherWork() {
        val record = SltParser.parse("p.test", "statement ok\nSELECT 1\n\n").records.single()
        val progress = ReplayProgress()
        progress += RecordOutcome.Fail(record, "original failure")
        val frozen = progress.freeze()
        progress += RecordOutcome.Pass(record)
        assertThat(frozen).hasSize(1)
        assertThat(progress.snapshot()).isEqualTo(frozen)
        assertThatThrownBy { progress.checkActive() }.isInstanceOf(java.util.concurrent.CancellationException::class.java)
    }
}
