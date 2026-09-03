package dev.brikk.ducklake.slt

/**
 * Model for DuckDB-dialect sqllogictest files as used by the DuckLake corpus
 * (the .test files under ducklake/test/sql).
 *
 * The dialect (superset of classic sqllogictest):
 *  - `# comment`, blank-line record separation
 *  - `require <extension>`
 *  - `test-env NAME value` (value may reference template vars)
 *  - `statement ok|error|maybe [conN]` + SQL lines [+ `----` + expected-error substring]
 *  - `query <types> [rowsort|nosort|label] [conN]` + SQL + `----` + expected rows
 *    (tab-separated columns, one row per line; empty block = empty result)
 *  - `loop var start end` … `endloop` (end exclusive), vars referenced `${var}`
 *  - `foreach var v1 v2 …` … `endloop`
 *  - `skipif <cond>` / `onlyif <cond>` guarding the next record (a whole loop if that is next);
 *    `<cond>` is an engine name or a loop condition — see [SltConditions]
 *  - template vars: `{NAME}`, `${NAME}`, and `__TEST_DIR__`
 *
 * Constructs deliberately not modeled (parser emits [SltUnsupported], the driver skips the whole
 * file with a reason): `concurrentloop` / `concurrentforeach` (their body is consumed so the rest
 * of the file still parses), `require-env`, `restart`, `reconnect`, `sleep`, `mode`, `load`,
 * `hash-threshold`, `set`, `unzip`, a stray `endloop`, unknown directives.
 */
sealed interface SltRecord {
    val line: Int
}

/** `statement ok` / `statement error` / `statement maybe` with optional connection label. */
data class SltStatement(
    override val line: Int,
    val sql: String,
    /** True for `statement error` AND `statement maybe` (the statement is allowed to fail). */
    val expectError: Boolean,
    /** Substring (or `<REGEX>:`-prefixed regex) the error message must match; null = any error. */
    val expectedError: String?,
    val connection: String?,
    /**
     * `statement maybe`: success is acceptable too. When it does fail and [expectedError] is set,
     * the error must still match (upstream `result_helper.cpp`).
     */
    val mayError: Boolean = false,
) : SltRecord

enum class SortMode { NOSORT, ROWSORT, VALUESORT }

/** `query <types>` with expected golden result block. */
data class SltQuery(
    override val line: Int,
    val sql: String,
    /** Column type string, e.g. "III" (I=int, T=text, R=real). */
    val types: String,
    val sortMode: SortMode,
    val connection: String?,
    /**
     * Result-sharing label: the first occurrence stores its result, later
     * queries with the same label must produce the same result.
     */
    val label: String?,
    /** Raw expected lines between `----` and the blank terminator. */
    val expected: List<String>,
) : SltRecord

data class SltRequire(override val line: Int, val requirement: String) : SltRecord

data class SltTestEnv(override val line: Int, val name: String, val value: String) : SltRecord

/** `loop i 0 10` (end exclusive) or `foreach x a b c` — [values] pre-expanded for foreach. */
data class SltLoop(
    override val line: Int,
    val variable: String,
    val values: List<String>,
    val body: List<SltRecord>,
) : SltRecord

/** `skipif engine` / `onlyif engine` applied to the record that follows. */
data class SltConditional(
    override val line: Int,
    val skipIf: Boolean,
    val engine: String,
    val record: SltRecord,
) : SltRecord

/** A construct v1 does not execute; the driver skips the file. */
data class SltUnsupported(override val line: Int, val directive: String, val raw: String) : SltRecord

data class SltFile(
    val path: String,
    val records: List<SltRecord>,
)
