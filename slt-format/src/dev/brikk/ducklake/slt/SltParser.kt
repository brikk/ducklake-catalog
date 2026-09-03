package dev.brikk.ducklake.slt

/**
 * Line-based parser for the DuckDB sqllogictest dialect (see [SltRecord] for
 * the modeled subset). Parsing never throws on unknown constructs — they
 * surface as [SltUnsupported] so the driver can skip with a reason and the
 * corpus report can count the frontier.
 */
object SltParser {

    private val CONNECTION_TOKEN = Regex("con\\d+")

    fun parse(path: String, content: String): SltFile {
        val lines = content.lines()
        val records = mutableListOf<SltRecord>()
        parseInto(lines, 0, lines.size, records, path, insideLoop = false)
        return SltFile(path, records)
    }

    /**
     * Parses [from, until) into [out]; returns the index after the region. Inside a loop body
     * ([insideLoop]) the matching `endloop` ends the region and its index is returned; at top level
     * a stray `endloop` is reported as [SltUnsupported] and parsing continues — it must never
     * silently truncate the rest of the file.
     */
    private fun parseInto(
        lines: List<String>,
        from: Int,
        until: Int,
        out: MutableList<SltRecord>,
        path: String,
        insideLoop: Boolean,
    ): Int {
        var i = from
        while (i < until) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) {
                i++
                continue
            }
            if (line.split(Regex("\\s+"))[0] == "endloop" && insideLoop) {
                return i // handled by the caller (parseLoop)
            }
            i = parseRecord(lines, i, until, out, path)
        }
        return i
    }

    /** Parses exactly one directive starting at the non-blank line [start]; returns the index after it. */
    @Suppress("CyclomaticComplexMethod")
    private fun parseRecord(lines: List<String>, start: Int, until: Int, out: MutableList<SltRecord>, path: String): Int {
        val line = lines[start].trim()
        val lineNo = start + 1
        val tokens = line.split(Regex("\\s+"))
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
            "statement" -> parseStatement(lines, start, until, tokens, out)
            "query" -> parseQuery(lines, start, until, tokens, out)
            "loop", "foreach" -> parseLoop(lines, start, until, tokens, out, path)
            "concurrentloop", "concurrentforeach" -> {
                // Not executed, but it OWNS a body up to its endloop: consume that body so the
                // records after the loop are still parsed (S-B1), then report the directive.
                val discarded = mutableListOf<SltRecord>()
                val end = skipLoopBody(lines, start, until, discarded, path)
                out += SltUnsupported(lineNo, tokens[0], line)
                end
            }
            "endloop" -> {
                out += SltUnsupported(lineNo, "endloop", "stray endloop without a matching loop/foreach")
                start + 1
            }
            "skipif", "onlyif" -> parseConditional(lines, start, until, tokens, out, path)
            else -> {
                out += SltUnsupported(lineNo, tokens[0], line)
                start + 1
            }
        }
    }

    /**
     * `skipif <cond>` / `onlyif <cond>` guard exactly the NEXT record — including a whole
     * `loop`/`foreach` (upstream applies the guard to the following command). Several guards may be
     * stacked; they nest.
     */
    private fun parseConditional(
        lines: List<String>,
        start: Int,
        until: Int,
        tokens: List<String>,
        out: MutableList<SltRecord>,
        path: String,
    ): Int {
        val lineNo = start + 1
        val condition = tokens.getOrNull(1) ?: ""
        var i = start + 1
        while (i < until && lines[i].trim().let { it.isEmpty() || it.startsWith("#") }) {
            i++
        }
        if (i >= until) {
            out += SltUnsupported(lineNo, tokens[0], lines[start].trim())
            return i
        }
        val inner = mutableListOf<SltRecord>()
        val end = parseRecord(lines, i, until, inner, path)
        val guarded = inner.firstOrNull()
        if (guarded == null) {
            out += SltUnsupported(lineNo, tokens[0], lines[start].trim())
        } else {
            out += SltConditional(lineNo, tokens[0] == "skipif", condition, guarded)
            inner.drop(1).forEach { out += it }
        }
        return end
    }

    /** Parses a loop body (into [body]) and returns the index just past its `endloop`. */
    private fun skipLoopBody(lines: List<String>, start: Int, until: Int, body: MutableList<SltRecord>, path: String): Int {
        val after = parseInto(lines, start + 1, until, body, path, insideLoop = true)
        return if (after < until && lines[after].trim().split(Regex("\\s+"))[0] == "endloop") after + 1 else after
    }

    private fun parseStatement(
        lines: List<String>,
        start: Int,
        until: Int,
        tokens: List<String>,
        out: MutableList<SltRecord>,
    ): Int {
        val lineNo = start + 1
        val kind = tokens.getOrNull(1)
        if (kind != "ok" && kind != "error" && kind != "maybe") {
            out += SltUnsupported(lineNo, "statement ${kind ?: ""}", lines[start].trim())
            return start + 1
        }
        val connection = tokens.getOrNull(2)
        val (sql, afterSql) = readSqlBlock(lines, start + 1, until)
        val (block, i) = readResultBlock(lines, afterSql, until, trimLines = true)
        val expectedError: String? = block.joinToString("\n").ifEmpty { null }
        // `statement maybe` = may or may not error. Upstream still requires the error, if one
        // occurs, to match the expectation when one is given (result_helper.cpp), so the message
        // is kept; `mayError` distinguishes it from `statement error`.
        out += SltStatement(lineNo, sql, kind != "ok", expectedError, connection, mayError = kind == "maybe")
        return i
    }

    private fun parseQuery(
        lines: List<String>,
        start: Int,
        until: Int,
        tokens: List<String>,
        out: MutableList<SltRecord>,
    ): Int {
        val lineNo = start + 1
        val types = tokens.getOrNull(1) ?: ""
        val modifiers = parseQueryModifiers(tokens.drop(2))
        val (sql, afterSql) = readSqlBlock(lines, start + 1, until)
        val (expected, i) = readResultBlock(lines, afterSql, until, trimLines = false)
        out += SltQuery(lineNo, sql, types, modifiers.sortMode, modifiers.connection, modifiers.label, expected)
        return i
    }

    private data class QueryModifiers(val sortMode: SortMode, val connection: String?, val label: String?)

    private fun parseQueryModifiers(tokens: List<String>): QueryModifiers {
        var sortMode = SortMode.NOSORT
        var connection: String? = null
        var label: String? = null
        for (t in tokens) {
            when {
                t == "rowsort" -> sortMode = SortMode.ROWSORT
                t == "valuesort" -> sortMode = SortMode.VALUESORT
                t == "nosort" -> sortMode = SortMode.NOSORT
                t.matches(CONNECTION_TOKEN) -> connection = t
                else -> label = t // result-sharing label
            }
        }
        return QueryModifiers(sortMode, connection, label)
    }

    /** Multi-line SQL body, ending at a blank line or the `----` separator. */
    private fun readSqlBlock(lines: List<String>, from: Int, until: Int): Pair<String, Int> {
        var i = from
        val sql = StringBuilder()
        while (i < until && lines[i].trim().let { it.isNotEmpty() && it != "----" }) {
            if (sql.isNotEmpty()) sql.append('\n')
            sql.append(lines[i])
            i++
        }
        return sql.toString() to i
    }

    /** Optional `----`-introduced block, consumed until the blank terminator. */
    private fun readResultBlock(
        lines: List<String>,
        from: Int,
        until: Int,
        trimLines: Boolean,
    ): Pair<List<String>, Int> {
        var i = from
        if (i >= until || lines[i].trim() != "----") {
            return emptyList<String>() to i
        }
        i++
        val block = mutableListOf<String>()
        while (i < until && lines[i].isNotBlank()) {
            block += if (trimLines) lines[i].trim() else lines[i]
            i++
        }
        return block to i
    }

    private fun parseLoop(
        lines: List<String>,
        start: Int,
        until: Int,
        tokens: List<String>,
        out: MutableList<SltRecord>,
        path: String,
    ): Int {
        val lineNo = start + 1
        val variable = tokens.getOrNull(1) ?: ""
        val values: List<String> =
            if (tokens[0] == "loop") {
                val lo = tokens.getOrNull(2)?.toLongOrNull()
                val hi = tokens.getOrNull(3)?.toLongOrNull()
                if (lo == null || hi == null) {
                    out += SltUnsupported(lineNo, "loop", lines[start].trim())
                    return start + 1
                }
                (lo until hi).map { it.toString() }
            } else {
                tokens.drop(2)
            }
        val body = mutableListOf<SltRecord>()
        val end = skipLoopBody(lines, start, until, body, path)
        out += SltLoop(lineNo, variable, values, body)
        return end
    }
}
