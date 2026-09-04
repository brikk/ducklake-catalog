package dev.brikk.ducklake.slt

/**
 * Line-based parser for the DuckDB sqllogictest dialect (see [SltRecord] for
 * the modeled subset). Parsing never throws on unknown or malformed constructs —
 * they surface as [SltUnsupported] so the driver can skip with a reason and the
 * corpus report can count the frontier.
 *
 * Line semantics follow upstream `sqllogic_parser.cpp` exactly:
 *  - a line is a comment only when `#` is at column 0, and only a completely empty line
 *    (no whitespace) separates records (`EmptyOrComment`);
 *  - a SQL block runs until an empty line, a `#` line, or a line that is exactly `----`
 *    (`ExtractStatement`) — lines are kept verbatim, nothing is trimmed;
 *  - a result / expected-error block runs from `----` until the next empty line
 *    (`ExtractExpectedResult` / `ExtractExpectedError`), comments included;
 *  - `mode skip` … `mode unskip` drops the records in between at parse time, mirroring the
 *    runner's `skip_level` counter (`sqllogic_test_runner.cpp` `ExecuteFile`): under skip,
 *    every directive except `mode` is consumed with its contiguous lines and never modeled.
 */
object SltParser {

    fun parse(path: String, content: String): SltFile = Parse(path, content.lines()).parseFile()

    /** Sort styles accepted by upstream `TestConfiguration::TryParseSortStyle`. */
    private val SORT_STYLES = mapOf(
        "nosort" to SortMode.NOSORT,
        "none" to SortMode.NOSORT,
        "rowsort" to SortMode.ROWSORT,
        "sort" to SortMode.ROWSORT,
        "valuesort" to SortMode.VALUESORT,
    )
    private val STATEMENT_KINDS = setOf("ok", "error", "maybe")
    private val WHITESPACE = Regex("\\s+")
    private val TYPE_STRING = Regex("[TIR]+")
    private const val RESULT_SEPARATOR = "----"
    private const val GOLDEN_FILE_PREFIX = "<FILE>:"

    /** One parse of one file; holds the lexical `mode skip` nesting counter. */
    private class Parse(private val path: String, private val lines: List<String>) {

        private var skipLevel = 0

        fun parseFile(): SltFile {
            val records = mutableListOf<SltRecord>()
            parseInto(0, records, insideLoop = false)
            return SltFile(path, records)
        }

        private fun isEmptyOrComment(line: String): Boolean = line.isEmpty() || line.startsWith("#")

        private fun tokensOf(line: String): List<String> = line.split(WHITESPACE).filter { it.isNotEmpty() }

        /**
         * Parses from [from] into [out]; returns the index after the region. Inside a loop body
         * ([insideLoop]) the matching `endloop` ends the region and its index is returned; at top
         * level a stray `endloop` is reported as [SltUnsupported] and parsing continues — it must
         * never silently truncate the rest of the file.
         */
        private fun parseInto(from: Int, out: MutableList<SltRecord>, insideLoop: Boolean): Int {
            var i = from
            while (i < lines.size) {
                val tokens = tokensOf(lines[i])
                i = when {
                    // Whitespace-only lines are rejected upstream ("Empty line!?"); we read them as blank.
                    tokens.isEmpty() || lines[i].startsWith("#") -> i + 1
                    tokens[0] == "endloop" && insideLoop -> return i
                    skipLevel > 0 && tokens[0] != "mode" -> skipStatement(i)
                    else -> parseRecord(i, out)
                }
            }
            return i
        }

        /**
         * Upstream `NextStatement`: a record is its directive plus the contiguous lines up to the next
         * empty or `#` line. Used to drop records under `mode skip` and to consume malformed records.
         */
        private fun skipStatement(start: Int): Int {
            var i = start
            while (i < lines.size && !isEmptyOrComment(lines[i])) {
                i++
            }
            return i
        }

        /** Parses exactly one directive starting at the non-blank line [start]; returns the index after it. */
        @Suppress("CyclomaticComplexMethod")
        private fun parseRecord(start: Int, out: MutableList<SltRecord>): Int {
            val line = lines[start]
            val lineNo = start + 1
            val tokens = tokensOf(line)
            return when (tokens[0]) {
                "require" -> {
                    out += SltRequire(lineNo, tokens.drop(1).joinToString(" "))
                    start + 1
                }
                "test-env" -> {
                    val name = tokens.getOrNull(1) ?: ""
                    val value = tokens.drop(2).joinToString(" ")
                    out += SltTestEnv(lineNo, name, value)
                    start + 1
                }
                "statement" -> parseStatement(start, tokens, out)
                "query" -> parseQuery(start, tokens, out)
                "loop", "foreach" -> parseLoop(start, tokens, out)
                "concurrentloop", "concurrentforeach" -> {
                    // Not executed, but it OWNS a body up to its endloop: consume that body so the
                    // records after the loop are still parsed (S-B1), then report the directive.
                    val end = parseLoopBody(start, mutableListOf())
                    out += SltUnsupported(lineNo, tokens[0], line)
                    end
                }
                "endloop" -> {
                    out += SltUnsupported(lineNo, "endloop", "stray endloop without a matching loop/foreach")
                    start + 1
                }
                "skipif", "onlyif" -> parseConditional(start, tokens, out)
                "mode" -> parseMode(start, tokens, out)
                else -> {
                    out += SltUnsupported(lineNo, tokens[0], line)
                    start + 1
                }
            }
        }

        /**
         * `mode skip` / `mode unskip` maintain the lexical skip counter (`skip_level`, upstream
         * `sqllogic_test_runner.cpp`); nothing is emitted. Other modes (`output_hash`,
         * `output_result`, `debug`) and an unbalanced `unskip` are reported as [SltUnsupported].
         */
        private fun parseMode(start: Int, tokens: List<String>, out: MutableList<SltRecord>): Int {
            when {
                tokens.getOrNull(1) == "skip" && tokens.size == 2 -> skipLevel++
                tokens.getOrNull(1) == "unskip" && tokens.size == 2 && skipLevel > 0 -> skipLevel--
                else -> out += SltUnsupported(start + 1, "mode", lines[start])
            }
            return start + 1
        }

        /**
         * `skipif <cond>` / `onlyif <cond>` guard exactly the NEXT record — including a whole
         * `loop`/`foreach` (upstream applies the guard to the following command). Several guards may
         * be stacked; they nest. A guarded `mode` cannot be resolved without knowing the engine, so
         * it is reported as [SltUnsupported].
         */
        private fun parseConditional(start: Int, tokens: List<String>, out: MutableList<SltRecord>): Int {
            val lineNo = start + 1
            val condition = tokens.getOrNull(1) ?: ""
            var i = start + 1
            while (i < lines.size && isEmptyOrComment(lines[i])) {
                i++
            }
            if (i >= lines.size || tokensOf(lines[i]).firstOrNull() == "mode") {
                out += SltUnsupported(lineNo, tokens[0], lines[start])
                return if (i >= lines.size) i else i + 1
            }
            val inner = mutableListOf<SltRecord>()
            val end = parseRecord(i, inner)
            val guarded = inner.firstOrNull()
            if (guarded == null) {
                out += SltUnsupported(lineNo, tokens[0], lines[start])
            } else {
                out += SltConditional(lineNo, tokens[0] == "skipif", condition, guarded)
                inner.drop(1).forEach { out += it }
            }
            return end
        }

        /** Parses a loop body (into [body]) and returns the index just past its `endloop`. */
        private fun parseLoopBody(start: Int, body: MutableList<SltRecord>): Int {
            val after = parseInto(start + 1, body, insideLoop = true)
            return if (after < lines.size && tokensOf(lines[after]).firstOrNull() == "endloop") after + 1 else after
        }

        /**
         * `statement ok|error|maybe [conN]`. As upstream `ExtractExpectedError`, `statement
         * error`/`maybe` MUST carry a `----` block and `statement ok` must NOT; an empty SQL body is
         * rejected too (`Unexpected empty statement text`). Violations are [SltUnsupported].
         */
        private fun parseStatement(start: Int, tokens: List<String>, out: MutableList<SltRecord>): Int {
            val lineNo = start + 1
            val kind = tokens.getOrNull(1)
            if (kind !in STATEMENT_KINDS) {
                out += SltUnsupported(lineNo, "statement ${kind ?: ""}", lines[start])
                return skipStatement(start)
            }
            val connection = tokens.getOrNull(2)
            val (sql, afterSql) = readSqlBlock(start + 1)
            val hasBlock = afterSql < lines.size && lines[afterSql] == RESULT_SEPARATOR
            val (block, i) = if (hasBlock) readResultBlock(afterSql) else emptyList<String>() to afterSql
            val problem = when {
                sql.isEmpty() -> "Unexpected empty statement text"
                kind == "ok" && hasBlock -> "only statement error or maybe can have an expected error message"
                kind != "ok" && !hasBlock -> "statement error and maybe need to have an expected error message"
                else -> null
            }
            if (problem != null) {
                out += SltUnsupported(lineNo, "statement $kind", problem)
                return i
            }
            val expectedError: String? = block.joinToString("\n").ifEmpty { null }
            // `statement maybe` = may or may not error. Upstream still requires the error, if one
            // occurs, to match the expectation when one is given (result_helper.cpp), so the
            // message is kept; `mayError` distinguishes it from `statement error`.
            out += SltStatement(lineNo, sql, kind != "ok", expectedError, connection, mayError = kind == "maybe")
            return i
        }

        /**
         * `query <types> [sort-style|conN] [label]` — modifiers are POSITIONAL as upstream
         * (`sqllogic_test_runner.cpp` `ExecuteFile`): the second token is a sort style if it parses
         * as one (`nosort`/`none`/`rowsort`/`sort`/`valuesort`), otherwise it is the connection name;
         * the third token, when present, is always the result-sharing label. The type string must be
         * non-empty and consist of `T`/`I`/`R` only.
         */
        private fun parseQuery(start: Int, tokens: List<String>, out: MutableList<SltRecord>): Int {
            val lineNo = start + 1
            val types = tokens.getOrNull(1) ?: ""
            if (!TYPE_STRING.matches(types)) {
                out += SltUnsupported(lineNo, "query", lines[start])
                return skipStatement(start)
            }
            val second = tokens.getOrNull(2)
            val sortMode = second?.let { SORT_STYLES[it] }
            val connection = if (second != null && sortMode == null) second else null
            val label = tokens.getOrNull(3)
            val (sql, afterSql) = readSqlBlock(start + 1)
            val (expected, i) = readResultBlock(afterSql)
            val goldenFile = expected.singleOrNull()
                ?.takeIf { it.startsWith(GOLDEN_FILE_PREFIX) }
                ?.let { SltGoldenFile(it.removePrefix(GOLDEN_FILE_PREFIX)) }
            out += SltQuery(lineNo, sql, types, sortMode ?: SortMode.NOSORT, connection, label, expected, goldenFile)
            return i
        }

        /** Upstream `ExtractStatement`: verbatim lines until an empty line, a `#` line, or `----`. */
        private fun readSqlBlock(from: Int): Pair<String, Int> {
            var i = from
            val sql = StringBuilder()
            while (i < lines.size && !isEmptyOrComment(lines[i]) && lines[i] != RESULT_SEPARATOR) {
                if (i > from) sql.append('\n')
                sql.append(lines[i])
                i++
            }
            return sql.toString() to i
        }

        /**
         * Upstream `ExtractExpectedResult`: skip the `----` line if that is where we stopped, then
         * take verbatim lines until the next EMPTY line (comments included). For a `query` this runs
         * even without `----` — a `#` line ending the SQL block is read as a result row, as upstream.
         * `ExtractExpectedError` (statements) only reads a block behind `----`; see [parseStatement].
         */
        private fun readResultBlock(from: Int): Pair<List<String>, Int> {
            var i = from
            if (i < lines.size && lines[i] == RESULT_SEPARATOR) {
                i++
            }
            val block = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotEmpty()) {
                block += lines[i]
                i++
            }
            return block to i
        }

        /**
         * `loop var start end` (end exclusive, exactly three parameters) or `foreach var v1 v2 …`
         * (at least one value). Foreach values go through [SltForeachTokens.expand]; a comma
         * iterator (`foreach a,b 1,x 2,y`) requires every value to have as many comma-separated
         * parts as the iterator name (upstream `ReplaceLoopIterator` fails otherwise).
         */
        private fun parseLoop(start: Int, tokens: List<String>, out: MutableList<SltRecord>): Int {
            val lineNo = start + 1
            val variable = tokens.getOrNull(1) ?: ""
            val values = loopValues(tokens)
            val arity = variable.split(',').size
            val body = mutableListOf<SltRecord>()
            val end = parseLoopBody(start, body)
            if (values == null || arity > 1 && values.any { it.split(',').size != arity }) {
                // Malformed header (upstream `parser.Fail`): the body is still consumed so the
                // records after the loop parse normally.
                out += SltUnsupported(lineNo, tokens[0], lines[start])
            } else {
                out += SltLoop(lineNo, variable, values, body)
            }
            return end
        }

        /** Null when the directive is malformed (upstream `parser.Fail`). */
        private fun loopValues(tokens: List<String>): List<String>? {
            if (tokens[0] == "foreach") {
                return if (tokens.size >= 3) SltForeachTokens.expand(tokens.drop(2)) else null
            }
            if (tokens.size != 4) return null
            val lo = tokens[2].toLongOrNull() ?: return null
            val hi = tokens[3].toLongOrNull() ?: return null
            return (lo until hi).map { it.toString() }
        }
    }
}
