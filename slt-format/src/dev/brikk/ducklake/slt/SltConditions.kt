package dev.brikk.ducklake.slt

/**
 * `skipif` / `onlyif` condition evaluation, mirroring DuckDB's sqllogictest runner
 * (`test/sqlite/sqllogic_test_runner.cpp` `TryParseConditions` + `sqllogic_command.cpp`
 * `CheckLoopCondition`):
 *
 *  - a parameter containing any of `=`, `<`, `>` is a LOOP condition: one or more `var<op>value`
 *    terms joined by `&&`, all of which must hold. `=` / `<>` compare the loop value as text;
 *    `>` / `>=` / `<` / `<=` compare as integers. The variable must be a currently-bound loop
 *    iterator — DuckDB errors otherwise, and so does [evaluate].
 *  - anything else is a SYSTEM name compared case-insensitively against the running engine
 *    (`skipif duckdb`, `onlyif postgres`).
 */
object SltConditions {

    private val OPERATORS = listOf("<>", ">=", ">", "<=", "<", "=") // longest first, as upstream

    /** True when the raw `skipif`/`onlyif` parameter [expr] holds for [engine] under loop [bindings]. */
    fun evaluate(expr: String, engine: String, bindings: Map<String, String>): Boolean {
        if (!isLoopCondition(expr)) {
            return expr.equals(engine, ignoreCase = true)
        }
        return expr.split("&&").all { term -> evaluateTerm(term.trim(), bindings) }
    }

    fun isLoopCondition(expr: String): Boolean = expr.any { it == '=' || it == '<' || it == '>' }

    private fun evaluateTerm(term: String, bindings: Map<String, String>): Boolean {
        val op = OPERATORS.firstOrNull { term.contains(it) }
            ?: throw IllegalArgumentException("skipif/onlyif must be in the form of x=y or x>y, potentially separated by &&: $term")
        val parts = term.split(op)
        require(parts.size == 2) { "skipif/onlyif must be in the form of x=y or x>y, potentially separated by &&: $term" }
        val variable = parts[0].trim()
        val value = parts[1].trim()
        val bound = bindings[variable]
            ?: throw IllegalArgumentException("Condition in onlyif/skipif not found: $variable must be a loop iterator name")
        return when (op) {
            "=" -> bound == value
            "<>" -> bound != value
            else -> {
                val l = bound.toLong()
                val r = value.toLong()
                when (op) {
                    ">" -> l > r
                    ">=" -> l >= r
                    "<" -> l < r
                    else -> l <= r // "<="
                }
            }
        }
    }
}
