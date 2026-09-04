package dev.brikk.ducklake.slt

/** The kind of a flattened, runnable record — mapped from the source [SltRecord]. */
enum class RecordKind {
    /** `statement ok` — the SQL must succeed. */
    OK,

    /** `statement error` / `statement maybe` — the SQL must (or may) fail; see [ConcreteRecord.expectedError]. */
    ERROR,

    /** `query …` — a result-producing statement; conformance consumers treat it as must-succeed. */
    QUERY,
}

/**
 * A single fully-substituted, runnable SQL record produced by [SltExpander.expand], with
 * provenance back to the source file/line. Loop variables and template vars are already
 * resolved; there is no remaining `${…}` / `{…}` to expand (unknown vars are left verbatim).
 */
data class ConcreteRecord(
    val sql: String,
    val kind: RecordKind,
    /**
     * For [RecordKind.ERROR] only: the substring (or `<REGEX>:`-prefixed regex) the error
     * message must match; null means "any error is acceptable" (`statement error` / `statement
     * maybe` with an empty expectation). Always null for [RecordKind.OK]/[RecordKind.QUERY].
     * Template (env) vars are substituted; loop variables are NOT (upstream only ever applies
     * `ReplaceKeywords` to the expected error, `result_helper.cpp:315-316`).
     */
    val expectedError: String?,
    /** Source file (the [SltFile.path] passed to the parser). */
    val file: String,
    /** 1-based line of the originating directive in the source file. */
    val line: Int,
    /** `statement maybe`: [kind] is [RecordKind.ERROR] but success is acceptable too. */
    val mayError: Boolean = false,
)

/**
 * Flattens a parsed [SltFile] into concrete, fully-substituted [ConcreteRecord]s: `loop`/
 * `foreach` bodies are unrolled, `skipif`/`onlyif` guards are resolved against [expand]'s
 * `engine`, `test-env` definitions accumulate into the substitution environment, and template
 * vars (`${NAME}`, `{NAME}`, and delimited bare keys like `__TEST_DIR__`) are substituted.
 *
 * Substitution order mirrors upstream: the environment is applied first (`ReplaceKeywords` at
 * parse time), then the active loop bindings outermost-first (`LoopReplacement` at execution
 * time), each loop value itself env-substituted before it is inserted. Hence when an env var and
 * a loop iterator share a name the env value wins. Comma iterators (`foreach a,b 1,x 2,y`) bind
 * each name to the corresponding comma-separated part of the value.
 *
 * Pure and IO-free (and deterministic): the only substitutions performed are those resolvable
 * from the supplied `env` plus loop bindings; unknown vars — including `{UUID}` unless the caller
 * supplies one — are left verbatim, which still yields valid SQL string literals.
 *
 * Non-SQL records ([SltRequire]) and constructs the parser could not model ([SltUnsupported])
 * are omitted rather than raised — callers that care about the unsupported frontier inspect the
 * parser output directly (skip-don't-throw).
 */
object SltExpander {

    private val TEMPLATE = Regex("\\$?\\{([A-Za-z_][A-Za-z0-9_]*)}")
    private const val DELIMITED_KEY_MIN_LENGTH = 4

    /**
     * @param file   the parsed source file.
     * @param engine engine name that `skipif <engine>` / `onlyif <engine>` resolve against
     *               (e.g. `"duckdb"`).
     * @param env    initial template variables (e.g. `__TEST_DIR__`); `test-env` directives add to
     *               a per-file copy of this map as expansion proceeds.
     */
    fun expand(file: SltFile, engine: String, env: Map<String, String> = emptyMap()): List<ConcreteRecord> {
        val out = mutableListOf<ConcreteRecord>()
        // Per-file, mutable: `test-env` accumulates here (as the driver's oracle does), visible to
        // all subsequent records. Loop bindings are layered on top per scope (see [walk]).
        val accumulatedEnv = LinkedHashMap(env)
        walk(file.path, file.records, engine, accumulatedEnv, emptyMap(), out)
        return out
    }

    private fun walk(
        path: String,
        records: List<SltRecord>,
        engine: String,
        env: MutableMap<String, String>,
        bindings: Map<String, String>,
        out: MutableList<ConcreteRecord>,
    ) {
        for (record in records) {
            when (record) {
                is SltTestEnv -> env[record.name] = substitute(record.value, env, bindings)
                is SltStatement -> out += ConcreteRecord(
                    substitute(record.sql, env, bindings),
                    if (record.expectError) RecordKind.ERROR else RecordKind.OK,
                    record.expectedError?.let { substituteEnv(it, env) },
                    path,
                    record.line,
                    mayError = record.mayError,
                )
                is SltQuery -> out += ConcreteRecord(
                    substitute(record.sql, env, bindings),
                    RecordKind.QUERY,
                    null,
                    path,
                    record.line,
                )
                is SltLoop -> for (value in record.values) {
                    walk(path, record.body, engine, env, bindings + (record.variable to value), out)
                }
                is SltConditional -> if (shouldRun(record, engine, bindings)) {
                    walk(path, listOf(record.record), engine, env, bindings, out)
                }
                is SltRequire -> {} // not SQL
                is SltUnsupported -> {} // skip-don't-throw: nothing runnable to emit
            }
        }
    }

    /** `skipif`/`onlyif` resolution — see [SltConditions] for the two dialects. */
    private fun shouldRun(conditional: SltConditional, engine: String, bindings: Map<String, String>): Boolean {
        val condition = SltConditions.evaluate(conditional.engine, engine, bindings)
        return if (conditional.skipIf) !condition else condition
    }

    /** Env first (upstream `ReplaceKeywords`), then loop bindings outermost-first (`LoopReplacement`). */
    private fun substitute(text: String, env: Map<String, String>, bindings: Map<String, String>): String {
        var s = substituteEnv(text, env)
        for ((iterator, value) in bindings) {
            s = substituteLoop(s, iterator, substituteEnv(value, env))
        }
        return s
    }

    private fun substituteEnv(text: String, env: Map<String, String>): String {
        // ${NAME} and {NAME} first (so a braced ${__TEST_DIR__} is resolved as the name
        // "__TEST_DIR__" rather than mangled by the bare pass below); unknown names are left
        // verbatim (still a valid SQL string literal).
        var s = TEMPLATE.replace(text) { m -> env[m.groupValues[1]] ?: m.value }
        // Then delimited bare keys (e.g. __TEST_DIR__) that the DuckDB SLT dialect references
        // without ${}/{} braces.
        for ((key, value) in env) {
            if (key.length >= DELIMITED_KEY_MIN_LENGTH && key.startsWith("__") && key.endsWith("__")) {
                s = s.replace(key, value)
            }
        }
        return s
    }

    /**
     * Upstream `ReplaceLoopIterator`: `${it}` then `{it}`, literal; a comma iterator name binds
     * each part to the matching comma-separated part of [value] (arity mismatch is a hard error
     * upstream — the parser already rejects it for raw values, so it can only arise from an env
     * substitution introducing commas).
     */
    private fun substituteLoop(text: String, iterator: String, value: String): String {
        val names = iterator.split(',')
        val values = if (names.size == 1) listOf(value) else value.split(',')
        require(names.size == values.size) {
            "foreach loop: number of commas in loop iterator ($iterator) does not match number of commas in " +
                "replacement ($value)"
        }
        var s = text
        for (i in names.indices) {
            s = s.replace("\${${names[i]}}", values[i]).replace("{${names[i]}}", values[i])
        }
        return s
    }
}
