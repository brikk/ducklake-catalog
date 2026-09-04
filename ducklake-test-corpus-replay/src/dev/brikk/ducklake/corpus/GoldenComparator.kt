package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SortMode
import org.duckdb.DuckDBArray
import org.duckdb.DuckDBStruct
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Converts JDBC results to sqllogictest golden form and compares against a
 * query record's expected block, mirroring upstream `test/sqlite/result_helper.cpp`.
 *
 * Golden-form rules (`SQLLogicTestConvertValue` + `CompareValues`):
 *  - SQL NULL renders as `NULL`
 *  - empty string renders as `(empty)`; embedded NUL bytes as `\0`
 *  - booleans render `true`/`false` here (upstream renders `1`/`0`); either side may write
 *    `true`/`false` (any case) or `1`/`0` for a boolean column, whatever the type letter
 *  - everything else reproduces DuckDB's own `::VARCHAR` rendering from the JDBC objects:
 *    DOUBLE/FLOAT and the temporal infinities via [DuckDbText], nested types via
 *    [renderNested] (quoting rules of the nested→VARCHAR casts); engine adapters use the same
 *    entry points for their values
 *  - expected block: one row per line, columns tab-separated and taken verbatim (no trimming —
 *    a golden may legitimately start with a space); a legacy value-per-line layout
 *    (rows*cols single-column lines) is regrouped
 *  - `rowsort` sorts rows lexicographically on both sides; `valuesort` sorts
 *    all cells individually
 *  - an expected block of exactly `N values hashing to <md5>` is compared by MD5 of the
 *    sorted cells (`result_helper.cpp:129-136`); the same hash serves labelled queries
 */
object GoldenComparator {

    sealed interface Comparison {
        data object Match : Comparison

        data class Mismatch(val detail: String) : Comparison

        data class Unsupported(val reason: String) : Comparison
    }

    private val HASHING = Regex("^\\d+ values hashing to [0-9a-f]+$")

    fun readRows(rs: ResultSet): List<List<String?>> {
        val cols = rs.metaData.columnCount
        val rows = mutableListOf<List<String?>>()
        while (rs.next()) {
            rows +=
                (1..cols).map { i ->
                    try {
                        // Always the object path: the driver's getString is DuckDB's own text for
                        // plain LIST/STRUCT columns but Java's Map/List toString for VARIANT.
                        renderCell(rs.getObject(i))
                    } catch (_: Exception) {
                        // e.g. the JDBC driver cannot represent TIME '24:00:00'
                        // as java.time.LocalTime; keep the record comparable.
                        "<jdbc-unreadable>"
                    }
                }
        }
        return rows
    }

    /**
     * Renders a JDBC/engine value the way DuckDB's own test runner renders it
     * (the golden-text dialect), instead of Java's default toString forms.
     * Public so [ReplayReadEngine] adapters produce comparable cells without
     * re-deriving the rules.
     */
    fun renderCell(value: Any?): String? =
        when (value) {
            null -> null
            // DuckDB golden text escapes embedded NUL bytes.
            is String -> value.replace("\u0000", "\\0")
            is Timestamp, is LocalDateTime, is OffsetDateTime, is java.time.ZonedDateTime,
            is LocalTime, is LocalDate -> renderTemporal(value)
            is Double -> DuckDbText.double(value)
            // A FLOAT must render as a float: widening 0.1f to double gives 0.10000000149011612,
            // which DuckDB's `0.1` golden would reject even under the numeric tolerance.
            is Float -> DuckDbText.float(value)
            is List<*> -> value.joinToString(", ", prefix = "[", postfix = "]") { renderNested(it) }
            is DuckDBStruct ->
                value.map.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
                    // struct keys are always quoted (`CalculateEscapedStringLength<STRUCT_KEY=true>`)
                    "${quoteNested(k.toString())}: ${renderNested(v)}"
                }
            is DuckDBArray -> renderArray(value)
            is Map<*, *> ->
                value.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
                    "${renderNested(k)}=${renderNested(v)}"
                }
            is java.sql.Blob -> renderBlob(value.getBytes(1, value.length().toInt()))
            is ByteArray -> renderBlob(value)
            else -> value.toString()
        }

    private fun renderTemporal(value: Any): String =
        when (value) {
            is Timestamp -> renderDateTime(value.toLocalDateTime())
            is LocalDateTime -> renderDateTime(value)
            is OffsetDateTime ->
                renderDateTimeTz(value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
            is java.time.ZonedDateTime ->
                renderDateTimeTz(value.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())
            is LocalTime -> renderTime(value)
            is LocalDate -> DuckDbText.dateSpecial(value) ?: value.toString()
            else -> value.toString()
        }

    /** TIMESTAMPTZ: `infinity` has no zone suffix (`Timestamp::ToString` returns before formatting). */
    private fun renderDateTimeTz(dt: LocalDateTime): String =
        DuckDbText.timestampSpecial(dt) ?: (renderDateTime(dt) + "+00")

    /** DuckDB blob rendering: printable ASCII as-is, backslash escaped, else \xHH. */
    private fun renderBlob(bytes: ByteArray): String =
        buildString {
            for (b in bytes) {
                val i = b.toInt() and 0xFF
                when {
                    i == '\\'.code -> append("\\\\")
                    i in 0x20..0x7E -> append(i.toChar())
                    else -> append("\\x%02X".format(i))
                }
            }
        }

    private fun renderArray(value: DuckDBArray): String =
        (value.array as Array<*>).joinToString(", ", prefix = "[", postfix = "]") { renderNested(it) }

    /**
     * Values nested in LIST/STRUCT/MAP, per DuckDB's nested→VARCHAR casts
     * (`src/function/cast/{list_casts,struct_cast,map_cast}.cpp` +
     * `vector_cast_helpers.hpp` `CalculateEscapedStringLength`/`WriteEscapedString`):
     *  - a nested child (LIST/STRUCT/MAP/ARRAY) is written as-is;
     *  - any other child is cast to text first and then single-quoted only when that text is
     *    empty, starts or (when at least two characters long) ends with whitespace, equals `null`
     *    case-insensitively, or contains one of `" ' ( ) , : = [ ] { }`
     *    (`nested_to_varchar_cast.cpp` `LOOKUP_TABLE`); inside quotes `'` and `\` are
     *    backslash-escaped. Interior spaces stay bare: `[a b, 'x:y', '', NULL]`. Timestamps and
     *    times therefore end up quoted (`['2020-01-01 00:00:00']`), doubles do not (`[1e+20]`).
     *  - struct keys are always quoted.
     * Public so engine adapters can render THEIR nested values into the same
     * dialect (see TrinoReplayEngine's typed renderer).
     */
    fun renderNested(value: Any?): String =
        when (value) {
            null -> "NULL"
            is List<*>, is DuckDBStruct, is DuckDBArray, is Map<*, *> -> renderCell(value) ?: "NULL"
            // Quote on the raw text (a NUL is not special upstream), then escape NULs like scalars.
            is String -> quoteNestedIfNeeded(value).replace("\u0000", "\\0")
            else -> quoteNestedIfNeeded(renderCell(value) ?: "NULL")
        }

    /** `NestedToVarcharCast::LOOKUP_TABLE`: the characters that force quoting. */
    private val SPECIAL = "\"'(),:=[]{}".toCharArray().toSet()

    /** `StringUtil::CharacterIsSpace`: space, tab, LF, VT, FF, CR. */
    private val SPACES = " \t\n\u000B\u000C\r".toCharArray().toSet()

    private fun isSpace(c: Char): Boolean = c in SPACES

    /** `CalculateEscapedStringLength<STRUCT_KEY=false>` — when does a nested string need quotes. */
    fun requiresQuotes(s: String): Boolean =
        when {
            s.isEmpty() -> true
            isSpace(s[0]) -> true
            s.length >= 2 && isSpace(s[s.length - 1]) -> true
            s.equals("null", ignoreCase = true) -> true
            else -> s.any { it in SPECIAL }
        }

    private fun quoteNestedIfNeeded(s: String): String = if (requiresQuotes(s)) quoteNested(s) else s

    /** `WriteEscapedString` with quotes forced: `'` and `\` get a backslash. */
    private fun quoteNested(s: String): String =
        buildString(s.length + 2) {
            append('\'')
            for (c in s) {
                if (c == '\'' || c == '\\') append('\\')
                append(c)
            }
            append('\'')
        }

    private fun renderDateTime(dt: LocalDateTime): String {
        DuckDbText.timestampSpecial(dt)?.let { return it }
        val base = "%04d-%02d-%02d %02d:%02d:%02d".format(dt.year, dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute, dt.second)
        return base + fraction(dt.nano)
    }

    private fun renderTime(t: LocalTime): String =
        "%02d:%02d:%02d".format(t.hour, t.minute, t.second) + fraction(t.nano)

    /** Microsecond fraction, trailing zeros trimmed, omitted when zero (DuckDB style). */
    private fun fraction(nano: Int): String {
        if (nano == 0) return ""
        val micros = "%06d".format(nano / 1000).trimEnd('0')
        return if (micros.isEmpty()) "" else ".$micros"
    }

    fun toGoldenCell(value: String?): String =
        when {
            value == null -> "NULL"
            value.isEmpty() -> "(empty)"
            else -> value
        }

    /**
     * Upstream `result_helper.cpp:127-136`: MD5 over every cell of the (sort-style-sorted) result,
     * each followed by `\n`, reported as `<count> values hashing to <hex>`. Used for
     * `N values hashing to …` goldens and for labelled queries (which compare hashes only).
     * Caveat: upstream feeds booleans in as `1`/`0`; this side hashes `true`/`false`, so a hash
     * golden over a boolean column written by DuckDB's runner will not match (labels are
     * unaffected — both sides are rendered here).
     */
    fun resultHash(query: SltQuery, actual: List<List<String?>>): String {
        val cells = sortRows(actual.map { row -> row.map(::toGoldenCell) }, query.sortMode).flatten()
        val md5 = java.security.MessageDigest.getInstance("MD5")
        for (cell in cells) {
            md5.update(cell.toByteArray(Charsets.UTF_8))
            md5.update('\n'.code.toByte())
        }
        val hex = md5.digest().joinToString("") { "%02x".format(it) }
        return "${cells.size} values hashing to $hex"
    }

    /** `ResultIsHash`: the expected block is exactly one `N values hashing to <md5>` line. */
    fun isHashGolden(query: SltQuery): Boolean =
        query.expected.size == 1 && HASHING.matches(query.expected[0].trim())

    fun compare(query: SltQuery, actual: List<List<String?>>): Comparison {
        if (isHashGolden(query)) {
            val expectedHash = query.expected[0].trim()
            val hash = resultHash(query, actual)
            return if (hash == expectedHash) {
                Comparison.Match
            } else {
                Comparison.Mismatch("result hash: expected '$expectedHash', got '$hash'")
            }
        }
        val colCount = query.types.length
        val actualRows = actual.map { row -> row.map(::toGoldenCell) }

        // Cells verbatim, as upstream (`StringUtil::Split(values[i], "\t")`, `result_helper.cpp:198`).
        var expectedRows = query.expected.map { it.split('\t') }
        // Legacy value-per-line layout: regroup when tabs are absent and counts line up.
        if (colCount > 1 &&
            expectedRows.all { it.size == 1 } &&
            query.expected.size == actualRows.size * colCount
        ) {
            expectedRows = query.expected.chunked(colCount)
        }

        val a = sortRows(actualRows, query.sortMode)
        val e = sortRows(expectedRows, query.sortMode)

        if (a.size != e.size) {
            return Comparison.Mismatch("row count: expected ${e.size}, got ${a.size}\n${diffPreview(e, a)}")
        }
        for (i in a.indices) {
            if (!rowsEqual(e[i], a[i], query.types)) {
                return Comparison.Mismatch("row ${i + 1}: expected ${e[i]}, got ${a[i]}")
            }
        }
        return Comparison.Match
    }

    private fun sortRows(rows: List<List<String>>, mode: SortMode): List<List<String>> =
        when (mode) {
            SortMode.NOSORT -> rows
            SortMode.ROWSORT -> rows.sortedBy { it.joinToString("\u0001") }
            SortMode.VALUESORT -> {
                val flat = rows.flatten().sorted()
                if (rows.isEmpty()) rows else flat.chunked(rows[0].size)
            }
        }

    private fun rowsEqual(expected: List<String>, actual: List<String>, types: String): Boolean {
        if (expected.size != actual.size) return false
        for (i in expected.indices) {
            val t = types.getOrNull(i) ?: 'T'
            if (!cellEqual(expected[i], actual[i], t)) return false
        }
        return true
    }

    private const val REGEX_PREFIX = "<REGEX>:"
    private const val NOT_REGEX_PREFIX = "<!REGEX>:"

    private fun cellEqual(expected: String, actual: String, type: Char): Boolean {
        if (expected == actual) return true
        // Upstream cell-level regex escape hatches (analyzed_plan assertions etc.).
        if (expected.startsWith(REGEX_PREFIX)) {
            return Regex(expected.removePrefix(REGEX_PREFIX), RegexOption.DOT_MATCHES_ALL).matches(actual)
        }
        if (expected.startsWith(NOT_REGEX_PREFIX)) {
            return !Regex(expected.removePrefix(NOT_REGEX_PREFIX), RegexOption.DOT_MATCHES_ALL).matches(actual)
        }
        // Boolean column (`result_helper.cpp:533-549`): the golden may say true/false (any case)
        // or 1/0, whatever the declared type letter — upstream keys on the SQL type, not on it.
        // [renderCell] emits booleans as exactly `true`/`false`, so that text identifies the column.
        if (actual == "true" || actual == "false") {
            return booleanValue(expected) == (actual == "true")
        }
        return (type == 'R' || type == 'I') && numericEqual(expected, actual)
    }

    /** `CompareValues` boolean coercion: `true`/`false` case-insensitively, or exactly `1`/`0`. */
    private fun booleanValue(cell: String): Boolean? =
        when {
            cell == "1" || cell.equals("true", ignoreCase = true) -> true
            cell == "0" || cell.equals("false", ignoreCase = true) -> false
            else -> null
        }

    /**
     * Numeric tolerance: golden files write integers where engines may emit
     * decimal forms (and vice versa); R columns compare as doubles.
     */
    private fun numericEqual(expected: String, actual: String): Boolean {
        val de = expected.toDoubleOrNull() ?: return false
        val da = actual.toDoubleOrNull() ?: return false
        return de == da || (de != 0.0 && Math.abs(de - da) / Math.abs(de) < RELATIVE_TOLERANCE)
    }

    private const val RELATIVE_TOLERANCE = 1e-9

    private fun diffPreview(expected: List<List<String>>, actual: List<List<String>>): String {
        val e = expected.take(5).joinToString("\n") { it.joinToString("\t") }
        val a = actual.take(5).joinToString("\n") { it.joinToString("\t") }
        return "expected (head):\n$e\nactual (head):\n$a"
    }
}
