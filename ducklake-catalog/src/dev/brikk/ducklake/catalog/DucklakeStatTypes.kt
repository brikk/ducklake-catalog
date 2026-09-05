/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.ducklake.catalog

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Canonical DuckLake type vocabulary and the single type-aware comparator/parser
 * for the textual min/max statistics stored in `ducklake_file_column_stats`.
 *
 * Stats are stored as strings. Upstream (`ducklake_stats.hpp` `RequiresValueComparison`) compares
 * them as VALUES for numeric, temporal and boolean columns — casting the text back to the column
 * type — and as plain strings for everything else. Text comparison is NOT safe for the value
 * classes: `"12" < "8"`, and in a mixed lake DuckDB writes timestamps as `2024-01-01 00:00:00`
 * while other engines write `2024-01-01T00:00:00` (`' ' < 'T'`), so a lexical merge picks the
 * wrong extreme. This object is the one place the per-type rule lives:
 *  - [ComparisonClass.NUMERIC] → [BigDecimal];
 *  - [ComparisonClass.TEMPORAL] → a normalised epoch key (see [parseTemporal]) that accepts both
 *    DuckDB's and ISO-8601's spellings;
 *  - [ComparisonClass.BOOLEAN] → `false < true`;
 *  - [ComparisonClass.TEXT] → Unicode code-point order (= UTF-8 byte order, what DuckDB uses;
 *    `String.compareTo` is UTF-16 code-unit order and differs for supplementary characters);
 *  - [ComparisonClass.UNCOMPARABLE] (`blob`, `interval`, nested, unknown) → never pruned and never
 *    merged into a bound; upstream does not prune on BLOB either (`ducklake_metadata_manager.cpp`).
 *
 * Any value that fails to parse under its class is "unknown": [parseStat] returns `null` (do not
 * prune) and [BoundsAccumulator] drops the bound (upstream's `DefaultCastAs` would throw).
 *
 * The numeric vocabulary covers the full DuckLake spec — including `int128` and `uint128`
 * (HUGEINT / UHUGEINT), which Trino never emits but which appear in catalogs written by DuckDB
 * directly or via `add_files`. [BigDecimal] handles their full ±3.4e38 range with no overflow.
 */
object DucklakeStatTypes {
    /** How stored min/max text of a column type must be compared. */
    enum class ComparisonClass { NUMERIC, TEMPORAL, BOOLEAN, TEXT, UNCOMPARABLE }

    private val TEMPORAL_TYPES = setOf(
        "date", "time", "timetz", "time_ns",
        "timestamp", "timestamptz", "timestamp_s", "timestamp_ms", "timestamp_ns",
    )

    fun comparisonClass(canonicalType: String?): ComparisonClass {
        if (canonicalType == null) {
            return ComparisonClass.UNCOMPARABLE
        }
        val t = normalize(canonicalType)
        return when {
            isNumericType(t) -> ComparisonClass.NUMERIC
            t in TEMPORAL_TYPES -> ComparisonClass.TEMPORAL
            t == "boolean" -> ComparisonClass.BOOLEAN
            t == "varchar" || t == "uuid" || t == "json" -> ComparisonClass.TEXT
            else -> ComparisonClass.UNCOMPARABLE // blob, interval, struct/list/map, variant, unknown, ""
        }
    }

    /**
     * Whether the given canonical DuckLake type is numeric (integer, unsigned
     * integer, floating point, or decimal) and therefore must be compared
     * numerically rather than lexically.
     */
    fun isNumericType(canonicalType: String?): Boolean {
        if (canonicalType == null) {
            return false
        }
        return when (normalize(canonicalType)) {
            "int8", "int16", "int32", "int64", "int128",
            "uint8", "uint16", "uint32", "uint64", "uint128",
            "float32", "float64" -> true
            else -> normalize(canonicalType).startsWith("decimal")
        }
    }

    /**
     * Whether the given canonical DuckLake type is a floating-point type
     * (`FLOAT`/`REAL` → `float32`, `DOUBLE` → `float64`). Floats are the only
     * types for which `NaN` is representable, and DuckLake/Parquet `min`/`max`
     * statistics **exclude** `NaN`. Because `NaN` sorts above every non-NaN
     * value, a float file whose `contains_nan` is `TRUE` (or unknown) has a
     * stored `max` that is NOT its true upper bound, so the stored `max` cannot
     * be used to prune `col > C` / `col >= C` predicates. Callers gate the
     * max-side prune on this predicate; see `JdbcDucklakeCatalog.rangePruneRetainsFile`.
     */
    fun isFloatType(canonicalType: String?): Boolean {
        if (canonicalType == null) {
            return false
        }
        return when (normalize(canonicalType)) {
            "float32", "float64" -> true
            else -> false
        }
    }

    /**
     * Parse a stored stat string into a [Comparable] for range comparison, per its canonical type
     * (see the class comment for the per-class rule). Returns `null` for a `null` input, for an
     * [ComparisonClass.UNCOMPARABLE] type, or for any value that fails to parse — callers treat
     * `null` as "unknown, do not prune" to avoid false negatives. Two values of the same column
     * type always parse to mutually comparable objects.
     */
    fun parseStat(canonicalType: String?, value: String?): Comparable<*>? {
        if (value == null) {
            return null
        }
        return try {
            when (comparisonClass(canonicalType)) {
                ComparisonClass.NUMERIC -> BigDecimal(value)
                ComparisonClass.TEMPORAL -> parseTemporal(value)
                ComparisonClass.BOOLEAN -> parseBoolean(value)
                ComparisonClass.TEXT -> CodePointString(value)
                ComparisonClass.UNCOMPARABLE -> null
            }
        }
        catch (e: RuntimeException) {
            // Unparseable: signal "unknown" so the caller skips pruning on this value.
            null
        }
    }

    /**
     * Type-aware comparison of two stored stat strings, or `null` when they cannot be compared
     * under [canonicalType] (uncomparable class, or either value unparseable) — the caller must then
     * treat the bound as unknown rather than guess.
     */
    fun compareOrNull(a: String, b: String, canonicalType: String?): Int? {
        val pa = parseStat(canonicalType, a) ?: return null
        val pb = parseStat(canonicalType, b) ?: return null
        @Suppress("UNCHECKED_CAST")
        return (pa as Comparable<Any>).compareTo(pb)
    }

    /**
     * Type-aware comparison of two stored stat strings. Falls back to code-point order when the
     * values cannot be compared under [canonicalType]; prefer [compareOrNull] / [BoundsAccumulator]
     * where an unknown result must be preserved.
     */
    fun compare(a: String, b: String, canonicalType: String?): Int =
        compareOrNull(a, b, canonicalType) ?: CodePointString(a).compareTo(CodePointString(b))

    /**
     * Core comparison on a numeric/non-numeric flag (legacy callers): numeric stats compare as
     * [BigDecimal]; all others (and any numeric value that fails to parse) fall back to code-point
     * order.
     */
    fun compare(a: String, b: String, numeric: Boolean): Int {
        if (numeric) {
            try {
                return BigDecimal(a).compareTo(BigDecimal(b))
            }
            catch (ignored: NumberFormatException) {
                // Non-finite or malformed (e.g. "Infinity", "NaN"): conservative fallback.
            }
        }
        return CodePointString(a).compareTo(CodePointString(b))
    }

    fun min(a: String, b: String, canonicalType: String?): String =
        if (compare(a, b, canonicalType) <= 0) a else b

    fun max(a: String, b: String, canonicalType: String?): String =
        if (compare(a, b, canonicalType) >= 0) a else b

    fun min(a: String, b: String, numeric: Boolean): String =
        if (compare(a, b, numeric) <= 0) a else b

    fun max(a: String, b: String, numeric: Boolean): String =
        if (compare(a, b, numeric) >= 0) a else b

    /**
     * True when a stored `(min, max)` stat pair is provably corrupt: both values
     * are present, the column is numeric (so ordering is unambiguous and
     * well-defined), and the parsed min is strictly greater than the parsed max.
     * Such a pair can never arise legitimately for an ordered numeric column, so
     * callers treat it as unreliable and refuse to prune on it (fail-open).
     *
     * Deliberately restricted to numeric types. Textual bounds are ordered by code point, where a
     * surface `min > max` can be legitimate under a writer's collation — flagging those would risk
     * discarding good stats. The only known real-world producers of swapped stats are numeric:
     * DuckDB's pre-1.5.5 128-bit `DECIMAL` `RETURN_STATS` bug (swapped min/max for
     * multi-row-group columns), and any merge that mis-ordered text-encoded
     * numbers lexically.
     *
     * Returns `false` when either value is absent, the type is non-numeric, or
     * either value fails to parse as a finite number — never discard stats on
     * uncertainty.
     */
    fun numericStatsSwapped(canonicalType: String?, minValue: String?, maxValue: String?): Boolean {
        if (minValue == null || maxValue == null || !isNumericType(canonicalType)) {
            return false
        }
        val min = try {
            BigDecimal(minValue)
        }
        catch (e: NumberFormatException) {
            return false
        }
        val max = try {
            BigDecimal(maxValue)
        }
        catch (e: NumberFormatException) {
            return false
        }
        return min > max
    }

    /**
     * Folds per-file bounds using upstream's count-aware `AnyValid` / `MergeStats` semantics:
     *  - a file proven to contain no non-NULL values contributes nothing;
     *  - the first potentially non-NULL contribution seeds the accumulator, even without bounds;
     *  - afterwards a missing or unparseable bound on either side makes that bound UNKNOWN, and
     *    unknown never recovers — later files cannot resurrect a bound some earlier file lacked;
     *  - [ComparisonClass.UNCOMPARABLE] types never yield a bound.
     *
     * A persisted global row has no counts. Unlike upstream's `FromGlobalStats`, [seed] must treat
     * absent bounds as unknown, not proof of all-NULL data; otherwise later files resurrect them.
     */
    class BoundsAccumulator(private val canonicalType: String?) {
        internal var hasValues: Boolean = false
            private set
        var min: String? = null
            private set
        var max: String? = null
            private set

        /** Start from an already-persisted global bound (see the class comment). */
        fun seed(existingMin: String?, existingMax: String?): BoundsAccumulator {
            merge(existingMin, existingMax)
            return this
        }

        /** Without counts, an absent bound is unknown; this overload cannot prove all-NULL data. */
        fun merge(fileMin: String?, fileMax: String?) = merge(fileMin, fileMax, null, null)

        /** [valueCount] counts NON-NULL values, not total rows. Both counts must support the proof. */
        fun merge(fileMin: String?, fileMax: String?, valueCount: Long?, nullCount: Long?) {
            if (valueCount == 0L && nullCount != null && nullCount >= 0L) {
                return
            }
            val validMin = fileMin?.takeIf { parseStat(canonicalType, it) != null }
            val validMax = fileMax?.takeIf { parseStat(canonicalType, it) != null }
            if (!hasValues) {
                hasValues = true
                min = validMin
                max = validMax
                return
            }
            min = if (min != null && validMin != null) {
                compareOrNull(min!!, validMin, canonicalType)?.let { if (it > 0) validMin else min }
            } else null
            max = if (max != null && validMax != null) {
                compareOrNull(max!!, validMax, canonicalType)?.let { if (it < 0) validMax else max }
            } else null
        }
    }

    /** A string compared by Unicode code point (UTF-8 byte order), the order DuckDB uses for VARCHAR. */
    class CodePointString(val value: String) : Comparable<CodePointString> {
        override fun compareTo(other: CodePointString): Int {
            val a = value
            val b = other.value
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) {
                    return ca.compareTo(cb)
                }
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            return (a.length - i).compareTo(b.length - j)
        }

        override fun equals(other: Any?): Boolean = other is CodePointString && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = value
    }

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .toFormatter(Locale.ROOT)

    private val OFFSET_REGEX = Regex("([+-]\\d{2}(?::?\\d{2})?|Z)$")
    private val NANOS_PER_SECOND = BigDecimal(1_000_000_000L)
    private val NANOS_PER_DAY = BigDecimal(86_400L * 1_000_000_000L)
    private val POSITIVE_INFINITY = BigDecimal("1E+40")
    private val NEGATIVE_INFINITY = BigDecimal("-1E+40")

    /**
     * Normalises a temporal stat string to a comparable key: a [BigDecimal] count of nanoseconds
     * (since the epoch for dates / timestamps, since midnight for times). Accepts DuckDB's
     * `Value::ToString` spellings (`2024-01-01 00:00:00`, `2024-01-01 00:00:00.123456+00`,
     * `12:34:56`, `infinity`) and ISO-8601's (`2024-01-01T00:00:00`, `...Z`, `...+02:00`). Zone
     * offsets are applied, so `timestamptz` values written with different offsets compare correctly.
     * Values within one column are always the same kind, so mixing kinds is not a concern.
     *
     * @throws RuntimeException for anything it does not understand (BC dates, textual months, ...).
     */
    fun parseTemporal(value: String): BigDecimal {
        val v = value.trim()
        specialTemporal(v)?.let { return it }
        // Split off a trailing zone offset if present (never on a plain date, never before ':').
        var body = v
        var offsetSeconds = 0L
        val offset = OFFSET_REGEX.find(v)
        if (offset != null && v.length > 10 && v.indexOf(':') >= 0) {
            body = v.substring(0, offset.range.first)
            offsetSeconds = parseOffsetSeconds(offset.value)
        }
        return when {
            isDateOnly(body) -> BigDecimal(LocalDate.parse(body).toEpochDay()).multiply(NANOS_PER_DAY)
            isTimeOnly(body) -> timeOfDayNanos(body, offsetSeconds)
            isDateTime(body) -> epochNanos(body, offsetSeconds)
            else -> throw IllegalArgumentException("Unrecognised temporal stat value: $value")
        }
    }

    private fun specialTemporal(v: String): BigDecimal? =
        when (v.lowercase(Locale.ROOT)) {
            "infinity", "+infinity" -> POSITIVE_INFINITY
            "-infinity" -> NEGATIVE_INFINITY
            "epoch" -> BigDecimal.ZERO
            else -> null
        }

    private fun isDateOnly(body: String): Boolean = body.length == 10 && body[4] == '-' && body[7] == '-'

    private fun isTimeOnly(body: String): Boolean = body.length >= 5 && body[2] == ':'

    private fun isDateTime(body: String): Boolean =
        body.length > 10 && body[4] == '-' && body[7] == '-' && (body[10] == ' ' || body[10] == 'T')

    private fun timeOfDayNanos(body: String, offsetSeconds: Long): BigDecimal {
        val t = LocalTime.parse(body, TIME_FORMATTER)
        return BigDecimal(t.toNanoOfDay()).subtract(BigDecimal(offsetSeconds).multiply(NANOS_PER_SECOND))
    }

    /** Date + time separated by ' ' (DuckDB) or 'T' (ISO), interpreted at [offsetSeconds]. */
    private fun epochNanos(body: String, offsetSeconds: Long): BigDecimal {
        val date = LocalDate.parse(body.substring(0, 10))
        val time = LocalTime.parse(body.substring(11), TIME_FORMATTER)
        val local = LocalDateTime.of(date, time)
        val instant = OffsetDateTime.of(local, ZoneOffset.ofTotalSeconds(offsetSeconds.toInt())).toInstant()
        return BigDecimal(instant.epochSecond).multiply(NANOS_PER_SECOND).add(BigDecimal(instant.nano))
    }

    private fun parseOffsetSeconds(text: String): Long {
        if (text == "Z") {
            return 0
        }
        val sign = if (text[0] == '-') -1 else 1
        val digits = text.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toLong()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toLong() else 0
        return sign * (hours * 3600 + minutes * 60)
    }

    private fun parseBoolean(value: String): Boolean {
        if (value.equals("true", ignoreCase = true) || value == "1") {
            return true
        }
        if (value.equals("false", ignoreCase = true) || value == "0") {
            return false
        }
        throw IllegalArgumentException("Invalid boolean value: $value")
    }

    private fun normalize(type: String): String {
        return type.trim().lowercase(Locale.ROOT)
    }
}
