package dev.brikk.ducklake.corpus

/** Measured query stages, separate from legacy record outcomes (which include setup statements). */
data class ReplayQueryCoverage(
    val engineName: String?,
    val oraclePasses: Int = 0,
    val mirrorAttempts: Int = 0,
    val mirrorPasses: Int = 0,
    val mirrorFailures: Int = 0,
    val mirrorAttemptSkips: Int = 0,
    val mirrorGateFailures: Int = 0,
    val mirrorSkipReasons: Map<String, Int> = emptyMap(),
) {
    val identityControlPasses: Int get() = if (engineName == null) oraclePasses else 0
    val mirrorSkips: Int get() = mirrorSkipReasons.values.sum()
    /** A timeout/crash may freeze an attempt before it produces any result. Never call it a pass. */
    val pendingMirrors: Int get() = mirrorAttempts - mirrorPasses - mirrorFailures - mirrorAttemptSkips
}
