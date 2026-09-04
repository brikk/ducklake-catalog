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

/**
 * One corpus file's replay. Three shapes:
 *  - ran to the end: [fileSkipReason] null, [outcomes] = every record;
 *  - skipped before anything ran (skip-list, unsupported construct, unmet `require` at the top,
 *    crash/timeout): [fileSkipReason] set, [outcomes] empty;
 *  - halted MID-FILE by an unmet `require` (upstream `SKIP_TEST` while streaming the file,
 *    `sqllogic_test_runner.cpp:1084-1091`): [fileSkipReason] set AND [outcomes] holds the records
 *    executed before it — their failures are real failures ([failuresBeforeSkip]), exactly as
 *    upstream's already-recorded `REQUIRE`s stay failed when the rest of the file is skipped.
 */
data class FileResult(
    val path: String,
    /** Non-null when the file (or its remainder) was skipped; see the class doc for the two cases. */
    val fileSkipReason: String?,
    val outcomes: List<RecordOutcome>,
) {
    val passed: Int get() = outcomes.count { it is RecordOutcome.Pass }
    val failed: List<RecordOutcome.Fail> get() = outcomes.filterIsInstance<RecordOutcome.Fail>()
    val skipped: List<RecordOutcome.Skip> get() = outcomes.filterIsInstance<RecordOutcome.Skip>()

    /** True when records ran and then an unmet `require` skipped the rest of the file. */
    val haltedMidFile: Boolean get() = fileSkipReason != null && outcomes.isNotEmpty()

    /** Failures recorded before a mid-file skip (empty unless [haltedMidFile]). */
    val failuresBeforeSkip: List<RecordOutcome.Fail> get() = if (haltedMidFile) failed else emptyList()
}
