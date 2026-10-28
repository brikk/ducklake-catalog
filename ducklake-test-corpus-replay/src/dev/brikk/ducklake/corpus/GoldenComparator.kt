package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SortMode
import org.duckdb.DuckDBArray
import org.duckdb.DuckDBStruct
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneId
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
 *    (only retained SQL BOOLEAN metadata permits coercion; VARCHAR lookalikes are text)
 *  - integer/decimal comparisons are exact, without Double conversion; only SQL FLOAT/DOUBLE
 *    results use the floating tolerance. JDBC arity is checked even for empty/hash/label results
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
        val types = (1..cols).map { rs.metaData.getColumnTypeName(it) }
        val geometry = types.map(GeometryNormalizer::containsGeometry)
        val zone = if (types.any { "TIMESTAMP WITH TIME ZONE" in it || "TIMESTAMPTZ" in it }) {
            rs.statement.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_setting('TimeZone')").use { result ->
                    check(result.next())
                    ZoneId.of(result.getString(1))
                }
            }
        } else ZoneOffset.UTC
        val rows = mutableListOf<List<String?>>()
        while (rs.next()) {
            rows +=
                (1..cols).map { i ->
                    try {
                        // Geometry getString preserves typed WKT, including nested leaves. Temporal
                        // getString is Java object text in JDBC 2.0: render its value with session zone.
                        if (geometry[i - 1]) rs.getString(i)?.replace("\u0000", "\\0")
                        else renderCell(rs.getObject(i), zone)
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
    fun renderCell(value: Any?): String? = renderCell(value, ZoneOffset.UTC)

    private fun renderCell(value: Any?, zone: ZoneId): String? =
        when (value) {
            null -> null
            // DuckDB golden text escapes embedded NUL bytes.
            is String -> value.replace("\u0000", "\\0")
            is Timestamp, is LocalDateTime, is OffsetDateTime, is java.time.ZonedDateTime,
            is LocalTime, is LocalDate, is OffsetTime -> renderTemporal(value, zone)
            is Double -> DuckDbText.double(value)
            // A FLOAT must render as a float: widening 0.1f to double gives 0.10000000149011612,
            // which DuckDB's `0.1` golden would reject even under the numeric tolerance.
            is Float -> DuckDbText.float(value)
            is List<*> -> value.joinToString(", ", prefix = "[", postfix = "]") { renderNested(it, zone) }
            is DuckDBStruct ->
                value.map.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
                    // struct keys are always quoted (`CalculateEscapedStringLength<STRUCT_KEY=true>`)
                    "${quoteNested(k.toString())}: ${renderNested(v, zone)}"
                }
            is DuckDBArray -> renderArray(value, zone)
            is Map<*, *> ->
                value.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
                    "${renderNested(k, zone)}=${renderNested(v, zone)}"
                }
            is java.sql.Blob -> renderBlob(value.getBytes(1, value.length().toInt()))
            is ByteArray -> renderBlob(value)
            else -> value.toString()
        }

    private fun renderTemporal(value: Any, zone: ZoneId): String =
        when (value) {
            is Timestamp -> renderDateTime(value.toLocalDateTime())
            is LocalDateTime -> renderDateTime(value)
            is OffsetDateTime -> renderDateTimeTz(value, zone)
            is java.time.ZonedDateTime -> renderDateTimeTz(value.toOffsetDateTime(), zone)
            is OffsetTime -> renderTime(value.toLocalTime()) + renderOffset(value.offset)
            is LocalTime -> renderTime(value)
            is LocalDate -> DuckDbText.dateSpecial(value) ?: value.toString()
            else -> value.toString()
        }

    /** TIMESTAMPTZ: `infinity` has no zone suffix (`Timestamp::ToString` returns before formatting). */
    private fun renderDateTimeTz(value: OffsetDateTime, zone: ZoneId): String {
        DuckDbText.timestampSpecial(value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())?.let { return it }
        val local = value.atZoneSameInstant(zone)
        return renderDateTime(local.toLocalDateTime()) + renderOffset(local.offset)
    }

    private fun renderOffset(offset: ZoneOffset): String {
        val seconds = kotlin.math.abs(offset.totalSeconds)
        val sign = if (offset.totalSeconds < 0) "-" else "+"
        val hours = "%02d".format(seconds / 3600)
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return sign + hours + (if (minutes != 0 || remainder != 0) ":%02d".format(minutes) else "") +
            (if (remainder != 0) ":%02d".format(remainder) else "")
    }

    /** DuckDB 2.0 BLOB rendering: printable ASCII except quotes/backslash as-is, else \xHH. */
    private fun renderBlob(bytes: ByteArray): String =
        buildString {
            for (b in bytes) {
                val i = b.toInt() and 0xFF
                when {
                    i in 0x20..0x7E && i != '\\'.code && i != '\''.code && i != '"'.code -> append(i.toChar())
                    else -> append("\\x%02X".format(i))
                }
            }
        }

    private fun renderArray(value: DuckDBArray, zone: ZoneId): String =
        (value.array as Array<*>).joinToString(", ", prefix = "[", postfix = "]") { renderNested(it, zone) }

    /**
     * Values nested in LIST/STRUCT/MAP, per DuckDB's nested→VARCHAR casts
     * (`src/function/cast/{list_casts,struct_cast,map_cast}.cpp` +
     * `vector_cast_helpers.hpp` `CalculateEscapedStringLength`/`WriteEscapedString`):
     *  - a nested child (LIST/STRUCT/MAP/ARRAY) is written as-is;
     *  - any other child is cast to text first and then single-quoted only when that text is
     *    empty, starts or (when at least two characters long) ends with whitespace, equals `null`
     *    case-insensitively, or contains quotes, backslash, parentheses or `, : = [ ] { }`
     *    (`nested_to_varchar_cast.cpp` `LOOKUP_TABLE`); inside quotes `'` and `\` are
     *    backslash-escaped. Interior spaces stay bare: `[a b, 'x:y', '', NULL]`. Timestamps and
     *    times therefore end up quoted (`['2020-01-01 00:00:00']`), doubles do not (`[1e+20]`).
     *  - struct keys are always quoted.
     * Public so engine adapters can render THEIR nested values into the same
     * dialect (see TrinoReplayEngine's typed renderer).
     */
    fun renderNested(value: Any?): String = renderNested(value, ZoneOffset.UTC)

    private fun renderNested(value: Any?, zone: ZoneId): String =
        when (value) {
            null -> "NULL"
            is List<*>, is DuckDBStruct, is DuckDBArray, is Map<*, *> -> renderCell(value, zone) ?: "NULL"
            // Quote on the raw text (a NUL is not special upstream), then escape NULs like scalars.
            is String -> quoteNestedIfNeeded(value).replace("\u0000", "\\0")
            else -> quoteNestedIfNeeded(renderCell(value, zone) ?: "NULL")
        }

    /** `NestedToVarcharCast::LOOKUP_TABLE`: the characters that force quoting. */
    private val SPECIAL = "\"'\\(),:=[]{}".toCharArray().toSet()

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

    /** Preserve nanoseconds when supplied by JDBC; trim trailing zeros (DuckDB style). */
    private fun fraction(nano: Int): String {
        if (nano == 0) return ""
        val fraction = "%09d".format(nano).trimEnd('0')
        return ".$fraction"
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

    /** Untyped callers get conservative declared-letter semantics, never text-inferred Boolean types. */
    fun compare(query: SltQuery, actual: List<List<String?>>): Comparison = compareResult(query, actual, null)

    /** JDBC SQL types retain arity even for empty results and select the permitted coercions. */
    fun compare(query: SltQuery, actual: List<List<String?>>, sqlTypes: List<String>): Comparison =
        compareResult(query, actual, sqlTypes)

    internal fun shapeMismatch(query: SltQuery, actual: List<List<String?>>, sqlTypes: List<String>?): Comparison.Mismatch? {
        // Upstream labels seed a hash from the first result; their letter string is a placeholder,
        // not a width assertion. The driver separately retains/checks the label's actual width.
        val columns = if (query.label == null) query.types.length
            else sqlTypes?.size ?: actual.firstOrNull()?.size ?: query.types.length
        if (sqlTypes != null && sqlTypes.size != columns) {
            return Comparison.Mismatch("column count: declared $columns, JDBC returned ${sqlTypes.size}")
        }
        val ragged = actual.indexOfFirst { it.size != columns }
        return if (ragged < 0) null else Comparison.Mismatch("column count: declared $columns, row ${ragged + 1} has ${actual[ragged].size}")
    }

    private fun compareResult(query: SltQuery, actual: List<List<String?>>, sqlTypes: List<String>?): Comparison {
        shapeMismatch(query, actual, sqlTypes)?.let { return it }
        if (isHashGolden(query)) {
            val expectedHash = query.expected[0].trim()
            val hash = resultHash(query, actual)
            return if (hash == expectedHash) {
                Comparison.Match
            } else {
                Comparison.Mismatch("result hash: expected '$expectedHash', got '$hash'")
            }
        }
        val colCount = sqlTypes?.size ?: query.types.length
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

        if (expectedRows.any { it.size != colCount }) {
            return Comparison.Mismatch("expected column count does not match declaration ($colCount)")
        }

        val a = sortRows(actualRows, query.sortMode)
        val e = sortRows(expectedRows, query.sortMode)

        if (a.size != e.size) {
            return Comparison.Mismatch("row count: expected ${e.size}, got ${a.size}\n${diffPreview(e, a)}")
        }
        // Keep each cell's SQL type attached while VALUESORT moves it across column positions.
        val sorted = typedCells(query, actualRows, sqlTypes)
        val expected = e.flatten()
        for (i in sorted.indices) {
            if (!cellEqual(expected[i], sorted[i])) {
                return Comparison.Mismatch("row ${i / colCount + 1}: expected ${e[i / colCount]}, got ${a[i / colCount]}")
            }
        }
        return Comparison.Match
    }

    private fun typedCells(query: SltQuery, actualRows: List<List<String>>, sqlTypes: List<String>?): List<Cell> {
        val typed = actualRows.map { row -> row.mapIndexed { i, text -> Cell(text, sqlTypes?.get(i), query.types.getOrNull(i) ?: 'T') } }
        return when (query.sortMode) {
            SortMode.NOSORT -> typed.flatten()
            SortMode.ROWSORT -> typed.sortedWith { left, right -> compareRow(left.map { it.text }, right.map { it.text }) }.flatten()
            SortMode.VALUESORT -> typed.flatten().sortedBy { it.text }
        }
    }

    private data class Cell(val text: String, val sqlType: String?, val declared: Char)

    private fun sortRows(rows: List<List<String>>, mode: SortMode): List<List<String>> =
        when (mode) {
            SortMode.NOSORT -> rows
            SortMode.ROWSORT -> rows.sortedWith(::compareRow)
            SortMode.VALUESORT -> {
                val flat = rows.flatten().sorted()
                if (rows.isEmpty()) rows else flat.chunked(rows[0].size)
            }
        }

    private fun compareRow(left: List<String>, right: List<String>): Int {
        for (i in 0 until minOf(left.size, right.size)) {
            val order = left[i].compareTo(right[i])
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }

    private const val REGEX_PREFIX = "<REGEX>:"
    private const val NOT_REGEX_PREFIX = "<!REGEX>:"

    private fun cellEqual(expected: String, cell: Cell): Boolean {
        val actual = cell.text
        if (expected == actual) return true
        // Upstream cell-level regex escape hatches (analyzed_plan assertions etc.).
        if (expected.startsWith(REGEX_PREFIX)) {
            return Regex(expected.removePrefix(REGEX_PREFIX), RegexOption.DOT_MATCHES_ALL).matches(actual)
        }
        if (expected.startsWith(NOT_REGEX_PREFIX)) {
            return !Regex(expected.removePrefix(NOT_REGEX_PREFIX), RegexOption.DOT_MATCHES_ALL).matches(actual)
        }
        val type = cell.sqlType?.uppercase()?.substringBefore('(')?.trim()
        if (type == "BOOLEAN" || type == "BOOL") {
            return booleanValue(expected)?.let { it == booleanValue(actual) } ?: false
        }
        return when {
            type in EXACT_NUMERIC_TYPES -> exactNumericEqual(expected, actual)
            type in REAL_TYPES -> numericEqual(expected, actual)
            type != null -> false
            cell.declared == 'I' -> exactNumericEqual(expected, actual)
            cell.declared == 'R' -> numericEqual(expected, actual)
            else -> false
        }
    }

    private val EXACT_NUMERIC_TYPES = setOf("TINYINT", "SMALLINT", "INTEGER", "INT", "BIGINT", "HUGEINT", "BIGNUM",
        "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "DECIMAL", "NUMERIC")
    private val REAL_TYPES = setOf("FLOAT", "REAL", "DOUBLE", "DOUBLE PRECISION")

    private fun exactNumericEqual(expected: String, actual: String): Boolean =
        runCatching { BigDecimal(expected).compareTo(BigDecimal(actual)) == 0 }.getOrDefault(false)

    /** `CompareValues` boolean coercion: `true`/`false` case-insensitively, or exactly `1`/`0`. */
    private fun booleanValue(cell: String): Boolean? =
        when {
            cell == "1" || cell.equals("true", ignoreCase = true) -> true
            cell == "0" || cell.equals("false", ignoreCase = true) -> false
            else -> null
        }

    /**
     * Floating tolerance applies only to retained SQL real types (or an untyped R caller).
     * Exact integer/decimal comparisons use BigDecimal and never pass through Double.
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
