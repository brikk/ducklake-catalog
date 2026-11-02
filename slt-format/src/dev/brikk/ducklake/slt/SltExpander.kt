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
 * Resource refusals are different: [SltLimitExceeded] aborts expansion without returning a prefix.
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
        SltLimits.validate(file)
        return Expansion(file, engine, env).expand()
    }

    private class Expansion(private val file: SltFile, private val engine: String, env: Map<String, String>) {
        private val budget = SltExpansionBudget().also { it.environment(env) }
        private val out = mutableListOf<ConcreteRecord>()
        // Per-file, mutable: `test-env` accumulates here (as the driver's oracle does), visible to
        // all subsequent records. Loop frames retain duplicate names in outermost-first order.
        private val accumulatedEnv = LinkedHashMap(env)

        fun expand(): List<ConcreteRecord> {
            walk(file.records, emptyList())
            return out
        }

        private fun walk(records: List<SltRecord>, bindings: List<Pair<String, String>>) {
            for (record in records) {
                when (record) {
                    is SltTestEnv -> accumulatedEnv[record.name] = budget.retain(substitute(record.value, bindings))
                    is SltStatement -> out += ConcreteRecord(
                        budget.retain(substitute(record.sql, bindings)),
                        if (record.expectError) RecordKind.ERROR else RecordKind.OK,
                        record.expectedError?.let { budget.retain(substituteEnv(it)) },
                        file.path,
                        record.line,
                        mayError = record.mayError,
                    )
                    is SltQuery -> out += ConcreteRecord(
                        budget.retain(substitute(record.sql, bindings)),
                        RecordKind.QUERY,
                        null,
                        file.path,
                        record.line,
                    )
                    is SltLoop -> for (value in record.values) {
                        walk(record.body, bindings + (record.variable to value))
                    }
                    is SltConditional -> if (shouldRun(record, engine, bindings)) {
                        walk(listOf(record.record), bindings)
                    }
                    is SltRequire -> {} // not SQL
                    is SltUnsupported -> {} // skip-don't-throw: nothing runnable to emit
                }
            }
        }

        /** `skipif`/`onlyif` resolution — see [SltConditions] for the two dialects. */
        private fun shouldRun(conditional: SltConditional, engine: String, bindings: List<Pair<String, String>>): Boolean {
            budget.inspect(conditional.engine)
            // Native CheckLoopCondition scans every matching frame, leaving the inner value.
            return SltConditions.shouldRun(conditional.engine, engine, bindings.toMap(), conditional.skipIf)
        }

        /** Env first (upstream `ReplaceKeywords`), then loop bindings outermost-first (`LoopReplacement`). */
        private fun substitute(text: String, bindings: List<Pair<String, String>>): String {
            var s = substituteEnv(text)
            for ((iterator, value) in bindings) {
                s = substituteLoop(s, iterator, substituteEnv(value))
            }
            return s
        }

        private fun substituteEnv(text: String): String {
            // ${NAME} and {NAME} first (so a braced ${__TEST_DIR__} is resolved as the name
            // "__TEST_DIR__" rather than mangled by the bare pass below); unknown names stay verbatim.
            var s = budget.replace(text, TEMPLATE) { m -> accumulatedEnv[m.groupValues[1]] ?: m.value }
            for ((key, value) in accumulatedEnv) {
                if (key.length >= DELIMITED_KEY_MIN_LENGTH && key.startsWith("__") && key.endsWith("__")) {
                    s = budget.replace(s, key, value)
                }
            }
            return s
        }

        /**
         * Upstream `ReplaceLoopIterator`: `${it}` then `{it}`, literal; comma iterator names bind
         * each matching part. Arity mismatch remains a hard error, not a resource refusal.
         */
        private fun substituteLoop(text: String, iterator: String, value: String): String {
            val names = SltLimits.commaParts(iterator)
            val values = if (names.size == 1) listOf(value) else SltLimits.commaParts(value)
            require(names.size == values.size) {
                "foreach loop: number of commas in loop iterator ($iterator) does not match number of commas in " +
                    "replacement ($value)"
            }
            var s = text
            for (i in names.indices) {
                s = budget.replace(s, "\${${names[i]}}", values[i])
                s = budget.replace(s, "{${names[i]}}", values[i])
            }
            return s
        }
    }
}
