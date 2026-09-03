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
     * message must match; null means "any error is acceptable" (`statement error` with no
     * expectation, or `statement maybe`). Always null for [RecordKind.OK]/[RecordKind.QUERY].
     */
    val expectedError: String?,
    /** Source file (the [SltFile.path] passed to the parser). */
    val file: String,
    /** 1-based line of the originating directive in the source file. */
    val line: Int,
)

/**
 * Flattens a parsed [SltFile] into concrete, fully-substituted [ConcreteRecord]s: `loop`/
 * `foreach` bodies are unrolled, `skipif`/`onlyif` guards are resolved against [expand]'s
 * `engine`, `test-env` definitions accumulate into the substitution environment, and template
 * vars (`${NAME}`, `{NAME}`, and delimited bare keys like `__TEST_DIR__`) are substituted.
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
                    record.expectedError?.let { substitute(it, env, bindings) },
                    path,
                    record.line,
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

    private fun substitute(text: String, env: Map<String, String>, bindings: Map<String, String>): String {
        val vars = if (bindings.isEmpty()) env else env + bindings // loop bindings win over env
        // ${NAME} and {NAME} first (so a braced ${__TEST_DIR__} is resolved as the name
        // "__TEST_DIR__" rather than mangled by the bare pass below); unknown names are left
        // verbatim (still a valid SQL string literal).
        var s = TEMPLATE.replace(text) { m -> vars[m.groupValues[1]] ?: m.value }
        // Then delimited bare keys (e.g. __TEST_DIR__) that the DuckDB SLT dialect references
        // without ${}/{} braces.
        for ((key, value) in vars) {
            if (key.length >= DELIMITED_KEY_MIN_LENGTH && key.startsWith("__") && key.endsWith("__")) {
                s = s.replace(key, value)
            }
        }
        return s
    }
}
