package dev.brikk.ducklake.slt

/**
 * `skipif` / `onlyif` condition evaluation, mirroring DuckDB's sqllogictest runner
 * (`test/sqlite/sqllogic_test_runner.cpp` `TryParseConditions` + `sqllogic_command.cpp`
 * `CheckLoopCondition`):
 *
 *  - a parameter containing any of `=`, `<`, `>` is a LOOP condition: one or more `var<op>value`
 *    terms joined by `&&`. `onlyif` requires every term to hold; `skipif` requires every term
 *    NOT to hold (one matching term blocks execution). `=` / `<>` compare the loop value as text;
 *    `>` / `>=` / `<` / `<=` compare as integers. The variable must be a currently-bound loop
 *    iterator — DuckDB errors otherwise, and so does [evaluate].
 *  - anything else is a SYSTEM name compared case-insensitively against the running engine
 *    (`skipif duckdb`, `onlyif postgres`).
 */
object SltConditions {

    private val OPERATORS = listOf("<>", ">=", ">", "<=", "<", "=") // longest first, as upstream

    /** Positive predicate/conjunction, retained for existing callers; guard policy is [shouldRun]. */
    fun evaluate(expr: String, engine: String, bindings: Map<String, String>): Boolean {
        if (!isLoopCondition(expr)) {
            return expr.equals(engine, ignoreCase = true)
        }
        return expr.splitToSequence("&&").all { term -> evaluateTerm(parseTerm(term.trim()), bindings) }
    }

    /** Upstream's per-term skip/only policy, not negation of the aggregate conjunction. */
    fun shouldRun(expr: String, engine: String, bindings: Map<String, String>, skipIf: Boolean): Boolean {
        if (!isLoopCondition(expr)) return expr.equals(engine, ignoreCase = true) != skipIf
        val terms = expr.splitToSequence("&&")
        // Native TryParseConditions validates ALL syntax before CheckLoopCondition evaluates
        // reached bindings. Use a separate streaming pass, never an unbounded parsed-term list.
        terms.forEach { parseTerm(it.trim()) }
        return terms.all { evaluateTerm(parseTerm(it.trim()), bindings) != skipIf }
    }

    fun isLoopCondition(expr: String): Boolean = expr.any { it == '=' || it == '<' || it == '>' }

    private data class Term(val variable: String, val operator: String, val value: String)

    private fun parseTerm(term: String): Term {
        val op = OPERATORS.firstOrNull { term.contains(it) }
            ?: throw IllegalArgumentException("skipif/onlyif must be in the form of x=y or x>y, potentially separated by &&: $term")
        val parts = term.split(op, limit = 3)
        require(parts.size == 2) { "skipif/onlyif must be in the form of x=y or x>y, potentially separated by &&: $term" }
        return Term(parts[0].trim(), op, parts[1].trim())
    }

    private fun evaluateTerm(term: Term, bindings: Map<String, String>): Boolean {
        val bound = bindings[term.variable]
            ?: throw IllegalArgumentException("Condition in onlyif/skipif not found: ${term.variable} must be a loop iterator name")
        return when (term.operator) {
            "=" -> bound == term.value
            "<>" -> bound != term.value
            else -> {
                val l = bound.toLong()
                val r = term.value.toLong()
                when (term.operator) {
                    ">" -> l > r
                    ">=" -> l >= r
                    "<" -> l < r
                    else -> l <= r // "<="
                }
            }
        }
    }
}
