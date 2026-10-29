package dev.brikk.ducklake.corpus

import java.util.concurrent.CancellationException

/** One worker's completed outcomes. A watchdog freezes a stable prefix before cancellation. */
internal class ReplayProgress(private val engineName: String? = null) {
    private val outcomes = mutableListOf<RecordOutcome>()
    @Volatile private var cancelled = false
    private var oraclePasses = 0
    private var mirrorAttempts = 0
    private var mirrorPasses = 0
    private var mirrorFailures = 0
    private var mirrorAttemptSkips = 0
    private var mirrorGateFailures = 0
    private val mirrorSkips = linkedMapOf<String, Int>()

    @Synchronized operator fun plusAssign(outcome: RecordOutcome) {
        if (!cancelled) outcomes += outcome
    }

    @Synchronized fun snapshot(): List<RecordOutcome> = outcomes.toList()

    @Synchronized fun fileResult(path: String, reason: String?): FileResult =
        FileResult(path, reason, outcomes.toList()).withQueryCoverage(ReplayQueryCoverage(
            engineName, oraclePasses, mirrorAttempts, mirrorPasses, mirrorFailures,
            mirrorAttemptSkips, mirrorGateFailures, mirrorSkips.toMap(),
        ))

    @Synchronized fun oraclePassed() { if (!cancelled) oraclePasses++ }
    @Synchronized fun mirrorAttempted() { if (!cancelled) mirrorAttempts++ }
    @Synchronized fun mirrorGateFailed() { if (!cancelled) mirrorGateFailures++ }

    @Synchronized fun mirrorSkipped(reason: String) {
        if (!cancelled) mirrorSkips[reason] = (mirrorSkips[reason] ?: 0) + 1
    }

    @Synchronized fun mirrorCompleted(outcome: RecordOutcome) {
        if (cancelled) return
        when (outcome) {
            is RecordOutcome.Pass -> mirrorPasses++
            is RecordOutcome.Fail -> mirrorFailures++
            is RecordOutcome.Skip -> {
                mirrorAttemptSkips++
                mirrorSkipped(outcome.reason)
            }
        }
    }

    @Synchronized fun freeze(): List<RecordOutcome> {
        cancelled = true
        return outcomes.toList()
    }

    fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("corpus replay cancelled")
    }
}
