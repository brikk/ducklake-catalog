package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltRecord

/**
 * Per-record replay outcomes and the per-file aggregate. These are the RUNNER's result types
 * (produced by [ReplayDriver]); the SLT format model itself lives in the standalone
 * `dev.brikk.ducklake:slt-format` module ([SltRecord] et al.).
 */
sealed interface RecordOutcome {
    val record: SltRecord?

    data class Pass(override val record: SltRecord) : RecordOutcome

    data class Fail(override val record: SltRecord, val reason: String) : RecordOutcome

    data class Skip(override val record: SltRecord?, val reason: String) : RecordOutcome
}

data class FileResult(
    val path: String,
    /** Non-null when the whole file was skipped before/without execution. */
    val fileSkipReason: String?,
    val outcomes: List<RecordOutcome>,
) {
    val passed: Int get() = outcomes.count { it is RecordOutcome.Pass }
    val failed: List<RecordOutcome.Fail> get() = outcomes.filterIsInstance<RecordOutcome.Fail>()
    val skipped: List<RecordOutcome.Skip> get() = outcomes.filterIsInstance<RecordOutcome.Skip>()
}
