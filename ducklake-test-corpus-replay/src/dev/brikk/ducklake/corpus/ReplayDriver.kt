package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltConditional
import dev.brikk.ducklake.slt.SltFile
import dev.brikk.ducklake.slt.SltLoop
import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SltRecord
import dev.brikk.ducklake.slt.SltRequire
import dev.brikk.ducklake.slt.SltStatement
import dev.brikk.ducklake.slt.SltTestEnv
import dev.brikk.ducklake.slt.SltUnsupported
import java.sql.SQLException

/**
 * Executes one parsed corpus file against a fresh [DuckDbOracle], producing a
 * [FileResult]. Optionally mirrors lake reads through a [ReplayReadEngine]
 * (live-vs-live comparison); with no engine this is the identity-control mode
 * where the oracle's results are validated against the golden text — which is
 * what proves the parser, templating, and comparator are faithful.
 *
 * Substitution order follows upstream: env/template vars first (`ReplaceKeywords`, at parse
 * time there), then loop bindings outermost-first with env-substituted values
 * (`LoopReplacement`); expected-error text and golden rows are env-substituted only.
 *
 * Skip policy (never fail on the un-runnable):
 *  - unsatisfiable `require` → the rest of the file is skipped with a reason; records already
 *    executed keep their outcomes (see [FileResult])
 *  - [SltUnsupported] anywhere → whole-file skip (v1 keeps semantics honest
 *    rather than running around unknown constructs)
 *  - guarded-out conditional records, and a `<FILE>:` golden that is not on disk → record skip
 */
class ReplayDriver(
    private val engine: ReplayReadEngine? = null,
    private val repoRoot: java.nio.file.Path? = null,
    /**
     * Metadata-backend axis: maps the DuckLake connection remainder of an
     * ATTACH (the part after `ducklake:`, post template-substitution) to a
     * replacement remainder — e.g. a local `.db` path to
     * `postgres:dbname=corpus_1 host=...` so an external engine can reach the
     * same catalog. Called once per distinct original within a file
     * (memoized: DETACH/re-ATTACH cycles hit the same backend). Null = attach
     * as written (duckdb-local).
     */
    private val metadataRewriter: ((original: String) -> String)? = null,
    /** Max engine mirrors per file (loop-heavy files repeat queries; see [mirrorsThisFile]). */
    private val mirrorCapPerFile: Int = DEFAULT_MIRROR_CAP,
) {

    companion object {
        const val DEFAULT_MIRROR_CAP: Int = 40

        private val ATTACH_PATTERN =
            Regex(
                "ATTACH\\s+(?:OR\\s+REPLACE\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?'ducklake:([^']+)'(?:\\s+AS\\s+(\\w+))?" +
                    "(?:\\s*\\(([^)]*)\\))?",
                RegexOption.IGNORE_CASE,
            )
        private val DATA_PATH_OPTION = Regex("DATA_PATH\\s+'([^']*)'", RegexOption.IGNORE_CASE)
        private val SNAPSHOT_PIN = Regex("SNAPSHOT_(VERSION|TIME)", RegexOption.IGNORE_CASE)
        private val DETACH_PATTERN = Regex("^\\s*DETACH\\s+(?:DATABASE\\s+)?(?:IF\\s+EXISTS\\s+)?([A-Za-z_][A-Za-z0-9_]*)", RegexOption.IGNORE_CASE)
        private val TXN_BEGIN = Regex("^\\s*BEGIN\\b", RegexOption.IGNORE_CASE)
        private val TXN_END = Regex("^\\s*(COMMIT|ROLLBACK|ABORT)\\b", RegexOption.IGNORE_CASE)
        private val DML = Regex("^\\s*(insert|update|delete|merge|truncate)\\b", RegexOption.IGNORE_CASE)
        private const val REGEX_PREFIX = "<REGEX>:"
        private const val NOT_REGEX_PREFIX = "<!REGEX>:"

        /** Upstream `always_fail_error_messages`: errors a `statement error`/`maybe` never accepts. */
        val ALWAYS_FAIL_ERROR_MESSAGES: Set<String> = setOf("differs from original result!", "INTERNAL")
    }

    fun replay(file: SltFile): FileResult {
        val unsupported = findUnsupported(file.records)
        if (unsupported != null) {
            return FileResult(
                file.path,
                "unsupported construct '${unsupported.directive}' at line ${unsupported.line}",
                emptyList(),
            )
        }
        return DuckDbOracle(repoRoot = repoRoot).use { oracle ->
            val outcomes = mutableListOf<RecordOutcome>()
            labelResults.clear()
            rewrittenAttachments.clear()
            attachDataPaths.clear()
            openTransactions.clear()
            mirrorsThisFile = 0
            engineConnected = false
            engineAlias = null
            engineTarget = null
            val halted = executeAll(file.records, oracle, emptyMap(), outcomes)
            FileResult(file.path, fileSkipReason = halted, outcomes = outcomes)
        }
    }

    /** Result hash per label (upstream `hash_label_map`), per replayed file. */
    private val labelResults = mutableMapOf<String, String>()

    /** Per-file memo of original → rewritten DuckLake connection remainders. */
    private val rewrittenAttachments = mutableMapOf<String, String>()

    /** Per-file memo of original → DATA_PATH (re-attaches often omit options). */
    private val attachDataPaths = mutableMapOf<String, String>()
    private var engineConnected = false

    /** Alias and (rewritten) metadata target of the lake the engine currently mirrors. */
    private var engineAlias: String? = null
    private var engineTarget: String? = null
    private var pendingAlias: String? = null
    private var pendingTarget: String? = null

    /**
     * Connections (by label, null = root) with an open oracle transaction.
     * Queries inside one see the oracle's UNCOMMITTED state; the engine reads
     * committed state — divergence there is correct isolation, so the mirror
     * is gated off until COMMIT/ROLLBACK.
     */
    private val openTransactions = mutableSetOf<String?>()

    /**
     * Engine mirrors executed for the current file. Loop-heavy corpus files
     * repeat the same query hundreds of times; the oracle validates every
     * iteration against golden text, but mirroring each one through an
     * external engine multiplies runtime for no additional signal. The cap
     * samples the first N mirrors per file.
     */
    private var mirrorsThisFile = 0

    /**
     * Detects a DuckLake ATTACH, applies the metadata rewrite, and returns the
     * SQL to execute plus the attachment to announce on success (null when the
     * statement is not a ducklake ATTACH or the engine is already connected).
     */
    private fun interceptAttach(sql: String): Pair<String, OracleAttachment?> {
        if (interceptDetach(sql)) return sql to null
        val m = ATTACH_PATTERN.find(sql) ?: return sql to null
        val options = m.groupValues[3]
        val dataPath = DATA_PATH_OPTION.find(options)?.groupValues?.get(1)
        val original = m.groupValues[1]
        val known = rewrittenAttachments[original]
        if (known == null && dataPath == null) {
            // FIRST attach of a lake without DATA_PATH: DuckLake derives a
            // default from a LOCAL metadata path — a rewritten backend has no
            // equivalent. Attach as written; the file replays oracle-only.
            // (RE-attaches without options must keep hitting the rewritten
            // backend — the memo check above handles them.)
            return sql to null
        }
        val rewritten =
            known ?: rewrittenAttachments.getOrPut(original) { metadataRewriter?.invoke(original) ?: original }
        if (dataPath != null) attachDataPaths[original] = dataPath
        val effectiveSql =
            if (rewritten == original) sql else sql.replace("'ducklake:$original'", "'ducklake:$rewritten'")
        if (SNAPSHOT_PIN.containsMatchIn(options)) {
            // Pinned (time-travel) attach: the oracle now reads an old
            // snapshot; the engine would read latest. Stop mirroring for the
            // rest of the file — divergence is expected, not a bug.
            engineConnected = false
            return effectiveSql to null
        }
        val alias =
            m.groupValues[2].ifEmpty {
                original.substringAfterLast('/').substringBefore('.') // duckdb derives from filename
            }
        if (engine == null || !mirrorCanStart(rewritten)) return effectiveSql to null
        val effectiveDataPath = dataPath ?: attachDataPaths[original] ?: ""
        pendingAlias = alias
        pendingTarget = rewritten
        return effectiveSql to OracleAttachment(rewritten, effectiveDataPath, alias)
    }

    /**
     * `DETACH <alias>` of the lake the engine mirrors: a later ATTACH (often re-using the alias for a
     * DIFFERENT lake) must reconnect the engine rather than mirror against the stale one. Returns
     * true when [sql] is a DETACH (mirroring state updated as needed).
     */
    private fun interceptDetach(sql: String): Boolean {
        val d = DETACH_PATTERN.find(sql) ?: return false
        if (engineConnected && d.groupValues[1].equals(engineAlias, ignoreCase = true)) {
            engineConnected = false
            engineAlias = null
        }
        return true
    }

    /**
     * Whether a new mirror connection may be announced for [rewrittenTarget]. While already
     * connected, a SECOND, different lake attached alongside the mirrored one stops mirroring for
     * the rest of the file — the engine knows only the first, so mirrored queries against the new
     * alias would diverge for the wrong reason.
     */
    private fun mirrorCanStart(rewrittenTarget: String): Boolean {
        if (!engineConnected) return true
        if (rewrittenTarget != engineTarget) {
            engineConnected = false
            engineAlias = null
        }
        return false
    }

    private fun findUnsupported(records: List<SltRecord>): SltUnsupported? {
        for (r in records) {
            when (r) {
                is SltUnsupported -> return r
                is SltLoop -> findUnsupported(r.body)?.let { return it }
                is SltConditional -> findUnsupported(listOf(r.record))?.let { return it }
                else -> {}
            }
        }
        return null
    }

    /**
     * Executes records in order. Returns a file-skip reason when a `require`
     * is unsatisfiable — upstream `SKIP_TEST`s at that point while streaming the
     * file (`sqllogic_test_runner.cpp:1084-1091`), so the remainder is skipped but
     * everything already appended to [outcomes] stands — null otherwise.
     */
    private fun executeAll(
        records: List<SltRecord>,
        oracle: DuckDbOracle,
        bindings: Map<String, String>,
        outcomes: MutableList<RecordOutcome>,
    ): String? {
        for (record in records) {
            when (record) {
                is SltRequire -> {
                    val miss = oracle.require(record.requirement)
                    if (miss != null) return "require ${record.requirement}: $miss"
                }
                is SltTestEnv -> oracle.defineEnv(record.name, bindLoops(record.value, bindings, oracle))
                is SltStatement -> outcomes += runStatement(record, oracle, bindings)
                is SltQuery -> outcomes += runQuery(record, oracle, bindings)
                is SltLoop -> {
                    for (v in record.values) {
                        val halted = executeAll(record.body, oracle, bindings + (record.variable to v), outcomes)
                        if (halted != null) return halted
                    }
                }
                is SltConditional -> {
                    val halted = executeConditional(record, oracle, bindings, outcomes)
                    if (halted != null) return halted
                }
                is SltUnsupported -> error("unsupported records are filtered before execution")
            }
        }
        return null
    }

    /**
     * Two condition dialects: `skipif/onlyif <engine>` (oracle is duckdb) and
     * `skipif/onlyif var=value` over loop bindings.
     */
    private fun executeConditional(
        record: SltConditional,
        oracle: DuckDbOracle,
        bindings: Map<String, String>,
        outcomes: MutableList<RecordOutcome>,
    ): String? {
        val cond = evalCondition(record.engine, bindings)
        val run = if (record.skipIf) !cond else cond
        if (run) {
            return executeAll(listOf(record.record), oracle, bindings, outcomes)
        }
        outcomes += RecordOutcome.Skip(record.record, "${if (record.skipIf) "skipif" else "onlyif"} ${record.engine}")
        return null
    }

    /** True when the guarded record applies (engine name or loop condition — see [SltConditions]). */
    private fun evalCondition(expr: String, bindings: Map<String, String>): Boolean =
        dev.brikk.ducklake.slt.SltConditions.evaluate(expr, "duckdb", bindings)

    /** Upstream order: `ReplaceKeywords` (env, TEST_DIR, UUID) first, then `LoopReplacement`. */
    private fun substituteAll(text: String, bindings: Map<String, String>, oracle: DuckDbOracle): String =
        bindLoops(oracle.substitute(text), bindings, oracle)

    /**
     * `LoopReplacement` / `ReplaceLoopIterator` (`sqllogic_test_runner.cpp:198-235`): active loops
     * outermost-first; each replacement value is env-substituted before insertion; `${it}` then
     * `{it}` are replaced literally. A comma iterator `a,b` bound to `1,x` binds `a`→`1`, `b`→`x`
     * (the parser guarantees equal arity for raw values; env substitution could still break it,
     * which upstream `FAIL`s on — here it escapes [replay] and the runner reports a CRASH file).
     */
    private fun bindLoops(text: String, bindings: Map<String, String>, oracle: DuckDbOracle): String {
        var s = text
        for ((iterator, rawValue) in bindings) {
            val names = iterator.split(',')
            val value = oracle.substitute(rawValue)
            val values = if (names.size == 1) listOf(value) else value.split(',')
            check(names.size == values.size) {
                "foreach loop: number of commas in loop iterator ($iterator) does not match number of commas in " +
                    "replacement ($value)"
            }
            for (i in names.indices) {
                s = s.replace("\${${names[i]}}", values[i]).replace("{${names[i]}}", values[i])
            }
        }
        return s
    }

    private fun runStatement(
        record: SltStatement,
        oracle: DuckDbOracle,
        bindings: Map<String, String>,
    ): RecordOutcome {
        val substituted = substituteAll(record.sql, bindings, oracle)
        val (sql, pendingAttachment) = interceptAttach(substituted)
        val conn = oracle.connection(record.connection)
        return try {
            conn.createStatement().use { it.execute(sql) }
            if (pendingAttachment != null && !engineConnected) {
                engine?.connect(pendingAttachment)
                engineConnected = true
                engineAlias = pendingAlias
                engineTarget = pendingTarget
            }
            trackTransaction(sql, record.connection)
            statementSucceeded(record)
        } catch (e: SQLException) {
            statementErrored(record, e, oracle)
        }
    }

    /** `statement ok` and `statement maybe` accept success; `statement error` must fail (any message). */
    private fun statementSucceeded(record: SltStatement): RecordOutcome =
        if (record.expectError && !record.mayError) {
            val expectation = record.expectedError?.let { " containing '$it'" } ?: ""
            RecordOutcome.Fail(record, "expected an error$expectation but the statement succeeded")
        } else {
            RecordOutcome.Pass(record)
        }

    /**
     * Upstream `CheckStatementResult` (`result_helper.cpp:296-339`): for `statement error` AND
     * `statement maybe`, an internal error is never acceptable (`always_fail_error_messages`,
     * `:302-305`), and when an expectation is given the message must match it (`:311-331`).
     */
    private fun statementErrored(record: SltStatement, e: SQLException, oracle: DuckDbOracle): RecordOutcome {
        val message = e.message ?: ""
        return when {
            !record.expectError -> RecordOutcome.Fail(record, "unexpected error: ${firstLine(e)}")
            isInternalError(message) ->
                RecordOutcome.Fail(record, "internal error is never an expected error: ${firstLine(e)}")
            errorMatches(record.expectedError, message, oracle) -> RecordOutcome.Pass(record)
            else -> RecordOutcome.Fail(record, "error mismatch: expected '${record.expectedError}', got: ${firstLine(e)}")
        }
    }

    /** `sqllogic_test_runner.hpp:78` `always_fail_error_messages`. */
    private fun isInternalError(message: String): Boolean =
        ALWAYS_FAIL_ERROR_MESSAGES.any { message.contains(it) }

    /**
     * `result_helper.cpp:311-331`: substring match against the raw AND the env-substituted
     * expectation (never loop-substituted), else `<REGEX>:` / `<!REGEX>:` full-match over the
     * whole error text (`MatchesRegex`, dot matches newline).
     */
    private fun errorMatches(expected: String?, message: String, oracle: DuckDbOracle): Boolean {
        if (expected == null) return true
        if (message.contains(expected) || message.contains(oracle.substitute(expected))) return true
        return when {
            expected.startsWith(REGEX_PREFIX) ->
                Regex(expected.removePrefix(REGEX_PREFIX), RegexOption.DOT_MATCHES_ALL).matches(message)
            expected.startsWith(NOT_REGEX_PREFIX) ->
                !Regex(expected.removePrefix(NOT_REGEX_PREFIX), RegexOption.DOT_MATCHES_ALL).matches(message)
            else -> false
        }
    }

    private fun runQuery(
        record: SltQuery,
        oracle: DuckDbOracle,
        bindings: Map<String, String>,
    ): RecordOutcome {
        val sql = substituteAll(record.sql, bindings, oracle)
        // runCatching: unchecked escapes happen too (e.g. the JDBC driver throws
        // DateTimeException on TIME '24:00:00'); a record failure must never
        // abort the corpus.
        val actual =
            runCatching { executeQuerySql(oracle.connection(record.connection), sql) }
                .getOrElse { e -> return RecordOutcome.Fail(record, "query errored: ${firstLine(e)}") }
        val label = record.label
        val outcome =
            if (label != null) {
                labeledOutcome(record, label, actual)
            } else {
                goldenOutcome(record, oracle, bindings, actual)
            }
        if (outcome !is RecordOutcome.Pass || engine == null || !shouldMirror(record, sql)) {
            return outcome
        }
        mirrorsThisFile++
        return mirrorOutcome(engine, record, sql, actual)
    }

    private fun executeQuerySql(conn: java.sql.Connection, sql: String): List<List<String?>> =
        conn.createStatement().use { st ->
            // DML under a `query` directive expects the changed-row count as
            // the single-cell result — the DuckDB JDBC driver only reports it
            // via executeUpdate (execute() leaves it at -1).
            if (DML.containsMatchIn(sql)) {
                listOf(listOf(st.executeUpdate(sql).toString()))
            } else {
                // Generic execute: some corpus `query` records wrap CALL/PRAGMA
                // shapes the driver refuses via executeQuery.
                val hasResult = st.execute(sql)
                when {
                    hasResult -> st.resultSet.use { rs -> GoldenComparator.readRows(rs) }
                    st.updateCount >= 0 -> listOf(listOf(st.updateCount.toString()))
                    else -> emptyList()
                }
            }
        }

    /**
     * Labelled query (`result_helper.cpp:86-87, 250-263`): upstream switches to hash comparison —
     * the first occurrence of a label stores its result hash, later ones must reproduce it — and
     * any golden rows written under the query are IGNORED (only an explicit
     * `N values hashing to …` golden is still checked, `:264-269`).
     */
    private fun labeledOutcome(record: SltQuery, label: String, actual: List<List<String?>>): RecordOutcome {
        val hash = GoldenComparator.resultHash(record, actual)
        val stored = labelResults.putIfAbsent(label, hash)
        if (stored != null && stored != hash) {
            return RecordOutcome.Fail(record, "labeled result '$label' diverged from first occurrence: $stored vs $hash")
        }
        if (!GoldenComparator.isHashGolden(record)) return RecordOutcome.Pass(record)
        return when (val cmp = GoldenComparator.compare(record, actual)) {
            is GoldenComparator.Comparison.Match -> RecordOutcome.Pass(record)
            is GoldenComparator.Comparison.Mismatch -> RecordOutcome.Fail(record, cmp.detail)
            is GoldenComparator.Comparison.Unsupported -> RecordOutcome.Skip(record, cmp.reason)
        }
    }

    private fun goldenOutcome(
        record: SltQuery,
        oracle: DuckDbOracle,
        bindings: Map<String, String>,
        actual: List<List<String?>>,
    ): RecordOutcome {
        val effective =
            try {
                resolveExpected(record, oracle, bindings, actual)
            } catch (e: DuckDbOracle.GoldenFileMissing) {
                // An environment gap (e.g. the `duckdb` submodule the tpch answers live in), not a mismatch.
                return RecordOutcome.Skip(record, e.message ?: "golden file missing")
            } catch (e: SQLException) {
                return RecordOutcome.Fail(record, "could not read golden CSV: ${firstLine(e)}")
            }
        return when (val cmp = GoldenComparator.compare(effective, actual)) {
            is GoldenComparator.Comparison.Match -> RecordOutcome.Pass(record)
            is GoldenComparator.Comparison.Mismatch -> RecordOutcome.Fail(record, cmp.detail)
            is GoldenComparator.Comparison.Unsupported -> RecordOutcome.Skip(record, cmp.reason)
        }
    }

    /**
     * The rows to compare against. A `<FILE>:path` golden (`result_helper.cpp:111-122`) has its
     * path env-substituted THEN loop-substituted and the rows loaded from the CSV
     * ([DuckDbOracle.loadGoldenCsv]); inline golden text is env-substituted only — upstream
     * compares each cell raw and `ReplaceKeywords`-substituted (`CompareValues`, `:496`), never
     * loop-substituted.
     */
    private fun resolveExpected(
        record: SltQuery,
        oracle: DuckDbOracle,
        bindings: Map<String, String>,
        actual: List<List<String?>>,
    ): SltQuery {
        val goldenFile = record.goldenFile ?: return record.copy(expected = record.expected.map { oracle.substitute(it) })
        val path = substituteAll(goldenFile.path, bindings, oracle)
        val columnCount = actual.firstOrNull()?.size ?: record.types.length
        val rows = oracle.loadGoldenCsv(path, columnCount)
        return record.copy(expected = rows.map { it.joinToString("\t") }, goldenFile = null)
    }

    /**
     * Mirror gate: engineConnected is per-file — never mirror against a
     * previous file's catalog, a pinned attach, an un-rewritten lake, inside
     * an open oracle transaction (uncommitted state is invisible to the
     * engine by design), past the per-file cap, or SQL the engine rejects.
     */
    private fun shouldMirror(record: SltQuery, sql: String): Boolean =
        engineConnected &&
            record.connection !in openTransactions &&
            mirrorsThisFile < mirrorCapPerFile &&
            engine != null &&
            engine.accepts(sql)

    /** Live-vs-live mirror through the engine. */
    private fun mirrorOutcome(
        engine: ReplayReadEngine,
        record: SltQuery,
        sql: String,
        actual: List<List<String?>>,
    ): RecordOutcome {
        val result = runCatching { engine.executeQuery(sql) }
        val error = result.exceptionOrNull()
        if (error is ReplayEngineSkip) {
            return RecordOutcome.Skip(record, "engine '${engine.name}': ${error.reason}")
        }
        if (error != null) {
            return RecordOutcome.Fail(record, "engine '${engine.name}' errored: ${firstLine(error)}")
        }
        val engineRows = result.getOrThrow().map { row -> row.map(GoldenComparator::toGoldenCell) }
        val oracleRows = actual.map { row -> row.map(GoldenComparator::toGoldenCell) }
        return if (engineRows.toSortedComparable() == oracleRows.toSortedComparable()) {
            RecordOutcome.Pass(record)
        } else {
            RecordOutcome.Fail(
                record,
                "engine '${engine.name}' diverged from oracle\n" +
                    "  oracle (head): ${oracleRows.take(3)}\n" +
                    "  engine (head): ${engineRows.take(3)}",
            )
        }
    }

    private fun List<List<String>>.toSortedComparable(): List<String> =
        map { it.joinToString("\u0001") }.sorted()

    private fun trackTransaction(sql: String, connection: String?) {
        when {
            TXN_BEGIN.containsMatchIn(sql) -> openTransactions.add(connection)
            TXN_END.containsMatchIn(sql) -> openTransactions.remove(connection)
        }
    }

    private fun firstLine(e: Throwable): String = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
}
