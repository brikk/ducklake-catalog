package dev.brikk.ducklake.slt

/**
 * Model for DuckDB-dialect sqllogictest files as used by the DuckLake corpus
 * (the .test / .test_slow files under ducklake/test/sql).
 *
 * The dialect (superset of classic sqllogictest), as modeled — see [SltParser] for the exact
 * line rules (`#` and `----` count only at column 0; records are separated by empty lines):
 *  - `require <extension|setting>`
 *  - `test-env NAME value` (value may reference template vars)
 *  - `statement ok [conN]` + SQL lines; `statement error|maybe [conN]` + SQL + `----` +
 *    expected-error lines (substring, or `<REGEX>:` / `<!REGEX>:`-prefixed regex; an empty block
 *    accepts any error). `maybe` = the statement may also succeed ([SltStatement.mayError]).
 *  - `query <types> [nosort|rowsort|valuesort|conN] [label]` + SQL + `----` + expected rows
 *    (tab-separated columns, one row per line; empty block = empty result; a single
 *    `<FILE>:path` row = golden CSV, see [SltGoldenFile]); modifiers are positional as upstream
 *  - `loop var start end` … `endloop` (end exclusive), vars referenced `${var}` or `{var}`
 *  - `foreach var v1 v2 …` … `endloop`, including the upstream special tokens (`<alltypes>`,
 *    `<numeric>`, `<integral>`, `<signed>`, `<unsigned>`, `<compression>`, `<all_types_columns>`,
 *    `!tok` — see [SltForeachTokens]) and comma iterators (`foreach a,b 1,x 2,y`)
 *  - `skipif <cond>` / `onlyif <cond>` guarding the next record (a whole loop if that is next);
 *    `<cond>` is an engine name or a loop condition — see [SltConditions]
 *  - `mode skip` … `mode unskip`: the records in between are dropped at parse time (upstream never
 *    turns them into commands either); no record is emitted for the `mode` lines themselves
 *  - template vars: `{NAME}`, `${NAME}`, and delimited bare keys like `__TEST_DIR__`
 *
 * Constructs deliberately not modeled (parser emits [SltUnsupported], the driver skips the whole
 * file with a reason): `concurrentloop` / `concurrentforeach` (their body is consumed so the rest
 * of the file still parses), `require-env`, `restart`, `reconnect`, `load`, `sleep`, `unzip`,
 * `hash-threshold`, `halt`, `set`, `reset`, `tags`, `continue`, `mode` other than `skip`/`unskip`
 * (or an unbalanced `unskip`, or a `mode` behind `skipif`/`onlyif`), `statement debug|debug_skip`,
 * a stray `endloop`, unknown directives, and malformed records that upstream rejects with
 * `parser.Fail` (`statement error|maybe` without `----`, `statement ok` with `----`, an empty
 * statement body, a `query` type string that is not `[TIR]+`, a `loop` without numeric bounds,
 * a `foreach` without values or with mismatched comma arity).
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
    /**
     * The expected-error lines behind `----` joined with `\n`, verbatim (not trimmed, no template
     * substitution): a substring the error message must contain, or a `<REGEX>:` /
     * `<!REGEX>:`-prefixed regex. Null = an empty block = any error is acceptable. Always null for
     * `statement ok`. Upstream matches it against the message raw and env-substituted (never
     * loop-substituted) — `result_helper.cpp` `CheckStatementResult`.
     */
    val expectedError: String?,
    val connection: String?,
    /**
     * `statement maybe`: success is acceptable too. When it does fail and [expectedError] is set,
     * the error must still match (upstream `result_helper.cpp:311-331`).
     */
    val mayError: Boolean = false,
) : SltRecord

enum class SortMode { NOSORT, ROWSORT, VALUESORT }

/**
 * `----` followed by a single `<FILE>:path` row: the expected rows live in a CSV file at [path]
 * (raw — may reference env and loop vars, which upstream substitutes before loading:
 * `result_helper.cpp` `ResultIsFile` / `LoadResultFromFile`). Only `.test_slow` corpus files use it.
 */
data class SltGoldenFile(val path: String)

/** `query <types>` with expected golden result block. */
data class SltQuery(
    override val line: Int,
    val sql: String,
    /** Column type string, e.g. "III" (I=int, T=text, R=real) — always non-empty and `[TIR]+`. */
    val types: String,
    val sortMode: SortMode,
    val connection: String?,
    /**
     * Result-sharing label (third token): the first occurrence stores its result, later
     * queries with the same label must produce the same result.
     */
    val label: String?,
    /** Raw expected lines between `----` and the empty terminator (verbatim, including a `<FILE>:` row). */
    val expected: List<String>,
    /** Set when [expected] is exactly one `<FILE>:path` row; consumers load the rows from that file. */
    val goldenFile: SltGoldenFile? = null,
) : SltRecord

data class SltRequire(override val line: Int, val requirement: String) : SltRecord

data class SltTestEnv(override val line: Int, val name: String, val value: String) : SltRecord

/**
 * `loop i 0 10` (end exclusive) or `foreach x a b c` — [values] pre-expanded for foreach (special
 * tokens resolved, see [SltForeachTokens]). For a comma iterator (`foreach a,b 1,x 2,y`) [variable]
 * is the raw `a,b` and every value is the raw `1,x`: the parser guarantees equal comma arity, and
 * the binder splits both (upstream `ReplaceLoopIterator`).
 */
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

/** A construct v1 does not execute (or a record upstream would reject); the driver skips the file. */
data class SltUnsupported(override val line: Int, val directive: String, val raw: String) : SltRecord

data class SltFile(
    val path: String,
    val records: List<SltRecord>,
)
