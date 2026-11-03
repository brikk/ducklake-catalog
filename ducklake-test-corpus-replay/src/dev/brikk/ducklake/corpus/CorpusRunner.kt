package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltFile
import dev.brikk.ducklake.slt.SltLimitExceeded
import dev.brikk.ducklake.slt.SltLimits
import dev.brikk.ducklake.slt.SltParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.extension
import kotlin.io.path.relativeTo

/**
 * Discovers and replays corpus files, aggregating a [CorpusReport].
 *
 * The corpus root is the `test/sql` directory of the pinned `duckdb/ducklake`
 * submodule (system property `ducklake.corpus.root` in tests). [skipList]
 * carries per-file skips with reasons — the curation surface, seeded over time
 * from upstream's own per-backend test-config skip lists (test/configs).
 */
class CorpusRunner @Suppress("LongParameterList") constructor(
    private val corpusRoot: Path,
    private val skipList: Map<String, String> = emptyMap(),
    private val engine: ReplayReadEngine? = null,
    /** See [ReplayDriver]'s `metadataRewriter` — the backend axis. */
    private val metadataRewriter: ((String) -> String)? = null,
    /**
     * Per-file watchdog covers source reading, parsing and replay: timeout freezes completed
     * outcomes, interrupts the worker and waits a bounded grace period. Timeout remains a file-skip,
     * NOT permission to erase earlier failures.
     * No later file (or subsequent run on this runner) may reuse shared engine/backend callbacks
     * after a timeout. A driver ignoring interruption may remain active until JVM exit; this is
     * quarantine, not a claim that arbitrary native/backend operations can be forcibly stopped.
     * Zero or negative disables the watchdog.
     */
    private val fileTimeoutSeconds: Long = DEFAULT_FILE_TIMEOUT_SECONDS,
    /** DuckLake format for lakes the corpus creates without an explicit version; see [ReplaySetupFormat]. */
    private val setupFormat: ReplaySetupFormat? = null,
    /** See [ReplayDriver]'s `mirrorFormatScope`: mirror only lakes whose stored format is in scope. */
    private val mirrorFormatScope: Set<ReplaySetupFormat>? = null,
    /** See [MetadataRewriteScope]. */
    private val metadataRewriteScope: MetadataRewriteScope = MetadataRewriteScope.ALL_ATTACHES,
    /** See [ReplayOracleSqlBinder]. */
    private val oracleSqlBinder: ReplayOracleSqlBinder? = null,
    /** See [ReplayDriver]'s `executionFormatScope`: halt files once they attach an out-of-scope stored format. */
    private val executionFormatScope: Set<ReplaySetupFormat>? = null,
) {

    /** 0.13.1/0.13.2 binary signature (including its default-argument bridge). */
    @Deprecated("Retained for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
        corpusRoot: Path,
        skipList: Map<String, String> = emptyMap(),
        engine: ReplayReadEngine? = null,
        metadataRewriter: ((String) -> String)? = null,
        fileTimeoutSeconds: Long = DEFAULT_FILE_TIMEOUT_SECONDS,
        setupFormat: ReplaySetupFormat? = null,
    ) : this(corpusRoot, skipList, engine, metadataRewriter, fileTimeoutSeconds, setupFormat, null)

    /** 0.13.0 binary signature (including its default-argument bridge). */
    @Deprecated("Retained for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
        corpusRoot: Path,
        skipList: Map<String, String> = emptyMap(),
        engine: ReplayReadEngine? = null,
        metadataRewriter: ((String) -> String)? = null,
        fileTimeoutSeconds: Long = DEFAULT_FILE_TIMEOUT_SECONDS,
    ) : this(corpusRoot, skipList, engine, metadataRewriter, fileTimeoutSeconds, null, null)

    companion object {
        const val DEFAULT_FILE_TIMEOUT_SECONDS: Long = 300
        val CORPUS_EXTENSIONS: Set<String> = setOf("test", "test_slow")
        private const val WORKER_GRACE_MILLIS = 1000L
    }

    private var quarantineReason: String? = null
    // Keep ownership of an unresponsive worker; never close/reuse its shared engine under it.
    private var timedOutWorker: Thread? = null

    /** Upstream runs both `.test` and `.test_slow` files (the latter carry the `<FILE>:` goldens). */
    fun discover(subdir: String? = null): List<Path> {
        val base = if (subdir == null) corpusRoot else corpusRoot.resolve(subdir)
        if (!Files.isDirectory(base)) return emptyList()
        Files.walk(base).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.extension in CORPUS_EXTENSIONS }
                .sorted()
                .toList()
        }
    }

    @Synchronized fun run(files: List<Path>): CorpusReport {
        val results = mutableListOf<FileResult>()
        for (file in files) {
            val rel = file.relativeTo(corpusRoot).toString()
            val blocked = quarantineReason
            val curated = skipList[rel]
            results += when {
                blocked != null -> {
                    val worker = if (timedOutWorker?.isAlive == true) "worker still active" else "worker stopped"
                    ReplayProgress(engine?.name).fileResult(rel, "BLOCKED: $blocked; $worker")
                }
                curated != null -> ReplayProgress(engine?.name).fileResult(rel, "skip-list: $curated")
                else -> replayGuarded(rel) { SltParser.parse(rel, readSource(file)) }
            }
        }
        return CorpusReport(results)
    }

    private fun readSource(file: Path): String = Files.newBufferedReader(file).use { reader ->
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("SLT source read interrupted")
            val count = reader.read(buffer)
            if (count < 0) break
            if (out.length.toLong() + count > SltLimits.MAX_SOURCE_CHARS) {
                throw SltLimitExceeded(1, "source characters exceed ${SltLimits.MAX_SOURCE_CHARS}")
            }
            out.append(buffer, 0, count)
        }
        out.toString()
    }

    /** Preparation is lazy so file IO and parser work live INSIDE the same quarantined watchdog. */
    internal fun replayGuarded(rel: String, prepare: () -> SltFile): FileResult {
        // Per-file mutable driver state, plus a frozen progress prefix on timeout/crash. The
        // engine/rewriter are still shared; quarantine prevents their reuse after cancellation.
        // corpusRoot = <repo>/test/sql → repo root two levels up (for `data/` refs).
        val driver = ReplayDriver(
            engine, repoRoot = corpusRoot.parent?.parent, metadataRewriter = metadataRewriter, setupFormat = setupFormat,
            mirrorFormatScope = mirrorFormatScope, metadataRewriteScope = metadataRewriteScope, oracleSqlBinder = oracleSqlBinder,
            executionFormatScope = executionFormatScope,
        )
        val progress = ReplayProgress(engine?.name)
        val body = {
            runCatching { driver.replay(prepare(), progress) }
                .getOrElse { e ->
                    val kind = if (e is SltLimitExceeded) "RESOURCE LIMIT" else "CRASH"
                    progress.fileResult(rel, "$kind: ${e.message?.lineSequence()?.firstOrNull() ?: e}")
                }
        }
        if (fileTimeoutSeconds <= 0) {
            return body()
        }
        val future = FutureTask(body)
        val worker = Thread(future, "corpus-replay-$rel").apply { isDaemon = true; start() }
        return try {
            future.get(fileTimeoutSeconds, TimeUnit.SECONDS)
        }
        catch (_: TimeoutException) {
            progress.freeze()
            quarantineReason = "corpus halted after timeout in $rel; shared engine/backend state is quarantined"
            cancelWorker(future, worker)
            val state = if (worker.isAlive) "worker still active (quarantined)" else "worker stopped"
            progress.fileResult(rel, "TIMEOUT: replay exceeded ${fileTimeoutSeconds}s; $state; remaining files blocked")
        }
        catch (e: InterruptedException) {
            progress.freeze()
            quarantineReason = "corpus interrupted in $rel; shared engine/backend state is quarantined"
            cancelWorker(future, worker)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun cancelWorker(future: FutureTask<FileResult>, worker: Thread) {
        timedOutWorker = worker
        future.cancel(true)
        try { worker.join(WORKER_GRACE_MILLIS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}

/**
 * Aggregate over [FileResult]s. Record counts ([failures], [totalPassed], [totalRecordSkips]) span
 * EVERY file that executed records — including files a requirement, crash or timeout halted mid-way
 * ([haltedFiles]); a failure recorded before such a skip is still a failure, as upstream.
 */
class CorpusReport(val files: List<FileResult>) {

    /** Files that ran to the end. */
    val ranFiles: List<FileResult> get() = files.filter { it.fileSkipReason == null }

    /** Files with a skip reason — whole-file skips AND mid-file halts (see [haltedFiles]). */
    val skippedFiles: List<FileResult> get() = files.filter { it.fileSkipReason != null }

    /** Files that executed some records before a requirement, crash or timeout halted the remainder. */
    val haltedFiles: List<FileResult> get() = files.filter { it.haltedMidFile }

    /** Every record failure, whether its file ran to the end or was halted afterwards. */
    val failures: List<Pair<FileResult, RecordOutcome.Fail>>
        get() = files.flatMap { f -> f.failed.map { f to it } }

    /** Files whose replay threw (harness bug or driver escape) — always a defect, never a skip. */
    val crashes: List<FileResult> get() = skippedFiles.filter { it.fileSkipReason!!.startsWith("CRASH:") }

    val totalPassed: Int get() = files.sumOf { it.passed }
    val totalRecordSkips: Int get() = files.sumOf { it.skipped.size }

    private val measured: List<ReplayQueryCoverage> get() = files.mapNotNull { it.queryCoverage }
    val unmeasuredFiles: Int get() = files.count { it.queryCoverage == null }
    val totalOracleQueryPasses: Int get() = measured.sumOf { it.oraclePasses }
    val totalIdentityControlPasses: Int get() = measured.sumOf { it.identityControlPasses }
    val totalMirrorAttempts: Int get() = measured.sumOf { it.mirrorAttempts }
    val totalMirrorPasses: Int get() = measured.sumOf { it.mirrorPasses }
    val totalMirrorFailures: Int get() = measured.sumOf { it.mirrorFailures }
    val totalMirrorSkips: Int get() = measured.sumOf { it.mirrorSkips }
    val totalMirrorGateFailures: Int get() = measured.sumOf { it.mirrorGateFailures }
    val totalPendingMirrors: Int get() = measured.sumOf { it.pendingMirrors }

    /** Conformance callers choose a meaningful positive threshold; oracle/record passes never satisfy it. */
    fun requireMirrorPasses(minimum: Int) {
        require(minimum > 0) { "Mirror coverage requires a positive threshold" }
        check(totalMirrorPasses >= minimum) { "Insufficient verified mirror coverage: need $minimum, got $totalMirrorPasses\n${summary()}" }
    }

    fun summary(maxFailures: Int = 20): String {
        val sb = StringBuilder()
        sb.appendLine("corpus replay: ${files.size} files")
        sb.appendLine(
            "  ran ${ranFiles.size} (records: $totalPassed passed, ${failures.size} failed, $totalRecordSkips skipped)",
        )
        sb.appendLine("  queries: $totalOracleQueryPasses oracle/golden passes ($totalIdentityControlPasses identity-control)")
        sb.appendLine("  mirrors: $totalMirrorAttempts attempted, $totalMirrorPasses passed, $totalMirrorFailures failed, $totalMirrorSkips skipped")
        if (totalMirrorGateFailures > 0 || totalPendingMirrors > 0 || unmeasuredFiles > 0) {
            sb.appendLine("    $totalMirrorGateFailures gate failures, $totalPendingMirrors pending, $unmeasuredFiles files unmeasured")
        }
        measured.flatMap { it.mirrorSkipReasons.entries }.groupBy({ it.key }, { it.value })
            .toSortedMap().forEach { (reason, counts) -> sb.appendLine("    ${counts.sum()}\t$reason") }
        val requireHalts = haltedFiles.count { it.fileSkipReason!!.startsWith("require ") }
        if (requireHalts > 0) {
            sb.appendLine("  halted mid-file by an unmet require $requireHalts (records above include theirs)")
        }
        val resourceHalts = haltedFiles.count { it.fileSkipReason!!.startsWith("RESOURCE LIMIT:") }
        if (resourceHalts > 0) sb.appendLine("  halted mid-file by resource limit $resourceHalts (records above include theirs)")
        val formatHalts = haltedFiles.count { it.fileSkipReason!!.startsWith("format-") }
        if (formatHalts > 0) sb.appendLine("  halted mid-file by format scope $formatHalts (records above include theirs)")
        val otherHalts = haltedFiles.size - requireHalts - resourceHalts - formatHalts
        if (otherHalts > 0) {
            sb.appendLine("  halted mid-file by crash/timeout $otherHalts (records above include theirs)")
        }
        sb.appendLine("  file-skips ${skippedFiles.size}:")
        skippedFiles
            .groupBy { it.fileSkipReason!!.substringBefore(" at line") }
            .entries
            .sortedByDescending { it.value.size }
            .forEach { (reason, fs) -> sb.appendLine("    ${fs.size}\t$reason") }
        if (failures.isNotEmpty()) {
            sb.appendLine("  failures (first $maxFailures):")
            failures.take(maxFailures).forEach { (f, fail) ->
                val line = fail.record?.line ?: 0
                val reasonLines = fail.reason.lines()
                sb.appendLine("    ${f.path}:$line  ${reasonLines.first()}")
                reasonLines.drop(1).forEach { sb.appendLine("      $it") }
            }
        }
        return sb.toString()
    }
}
