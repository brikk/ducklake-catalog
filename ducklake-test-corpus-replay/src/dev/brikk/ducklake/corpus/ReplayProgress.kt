package dev.brikk.ducklake.corpus

import java.util.concurrent.CancellationException

/** One worker's completed outcomes. A watchdog freezes a stable prefix before cancellation. */
internal class ReplayProgress {
    private val outcomes = mutableListOf<RecordOutcome>()
    @Volatile private var cancelled = false

    @Synchronized operator fun plusAssign(outcome: RecordOutcome) {
        if (!cancelled) outcomes += outcome
    }

    @Synchronized fun snapshot(): List<RecordOutcome> = outcomes.toList()

    @Synchronized fun freeze(): List<RecordOutcome> {
        cancelled = true
        return outcomes.toList()
    }

    fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("corpus replay cancelled")
    }
}
