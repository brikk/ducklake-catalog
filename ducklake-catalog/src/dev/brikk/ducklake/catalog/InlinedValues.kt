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
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * A DuckLake column type as a tree — what [InlinedValues.decode] needs to interpret a nested
 * value. Built from the flat `ducklake_column` rows with [InlinedValues.typeTree].
 *
 * @property type canonical DuckLake type name (`int32`, `varchar`, `struct`, `list`, `map`, ...).
 * @property children `struct` fields in order; the single `list` element; the `map` key and value.
 */
data class InlinedTypeNode(val type: String, val name: String, val children: List<InlinedTypeNode> = emptyList())

/**
 * A DuckLake `interval` value: months, days and microseconds are independent components (a month
 * is not a fixed number of days), exactly as DuckDB stores them.
 */
data class DucklakeInterval(val months: Long, val days: Long, val micros: Long)

/**
 * Decodes the PHYSICAL values a DuckLake metadata backend hands back for inlined-data rows into one
 * canonical Java representation per DuckLake type (TODO-rectify-from-eval.md R-D4).
 *
 * Upstream stores inlined rows in backend-native types that differ from the logical DuckLake types
 * and reinterprets them on read (`postgres_metadata_manager.cpp` `GetColumnTypeInternal` /
 * `TransformInlinedData`, `ducklake_inlined_data_reader.cpp` `CastColumnToTarget`). On PostgreSQL:
 * `varchar` / `blob` / `json` are BYTEA (raw UTF-8 for text); `date`, every `timestamp*`,
 * `timestamptz`, `uint64`, `int128`, `uint128` and ALL nested types are VARCHAR holding DuckDB's
 * text form; `int8` is SMALLINT, `uint8`/`uint16` INTEGER, `uint32` BIGINT. On the DuckDB file /
 * Quack backends values arrive as the driver's native objects. Consumers must not have to know any
 * of this, so every read that goes through [decode] yields:
 *
 * | DuckLake type | Java value |
 * |---|---|
 * | `boolean` | `Boolean` |
 * | `int8` `int16` `int32` | `Int` |
 * | `int64` `uint8` `uint16` `uint32` | `Long` |
 * | `uint64` `int128` `uint128` | `BigInteger` |
 * | `float32` | `Float` — `float64` | `Double` |
 * | `decimal(w,s)` | `BigDecimal` (scale as stored) |
 * | `varchar` `json` | `String` |
 * | `blob` | `ByteArray` |
 * | `uuid` | `UUID` |
 * | `date` | `LocalDate` — `time` `time_ns` | `LocalTime` — `timetz` | `OffsetTime` |
 * | `timestamp` `timestamp_s` `timestamp_ms` `timestamp_ns` `timestamp_us` | `LocalDateTime` |
 * | `timestamptz` | `OffsetDateTime` (UTC) |
 * | `interval` | [DucklakeInterval] |
 * | `struct` | `LinkedHashMap<String, Any?>` in field order |
 * | `list` | `List<Any?>` — `map` | `LinkedHashMap<Any?, Any?>` |
 *
 * SQL `NULL` is `null` at every level. Text forms accepted: DuckDB's (`2024-02-29 12:34:56.123456`,
 * `…+02`, `[1, 2, NULL]`, `{'a': 1, 'b': 'x, y'}`, `{k=v}`, `\xHH` blobs) and ISO-8601 (`T`
 * separator, `Z`). Interval text is accepted in both DuckDB (`1 year 2 months 3 days 04:05:06.007`)
 * and PostgreSQL (`1 year 2 mons 3 days 04:05:06.007`, `1 years 2 mons …`) spellings. Anything that
 * cannot be interpreted under its declared type raises [DucklakeCatalogCorruptionException] rather
 * than passing a wrong value through; an unknown type name passes the raw value through unchanged.
 */
object InlinedValues {

    /** Builds the [InlinedTypeNode] for [column] from the flat column rows of its table (any snapshot). */
    fun typeTree(column: DucklakeColumn, allColumns: List<DucklakeColumn>): InlinedTypeNode {
        val byParent = allColumns.filter { it.parentColumn != null }.groupBy { it.parentColumn!! }
        fun build(c: DucklakeColumn): InlinedTypeNode =
            InlinedTypeNode(
                c.columnType.trim().lowercase(Locale.ROOT),
                c.columnName,
                (byParent[c.columnId] ?: emptyList()).sortedWith(compareBy({ it.columnOrder }, { it.columnId })).map(::build),
            )
        return build(column)
    }

    /** Decodes one physical [raw] value under [type]; `null` stays `null`. */
    fun decode(raw: Any?, type: InlinedTypeNode): Any? {
        if (raw == null) {
            return null
        }
        return try {
            decodeNonNull(raw, type)
        }
        catch (e: DucklakeCatalogCorruptionException) {
            throw e
        }
        catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // Parsers throw a zoo of unchecked types (NumberFormat, DateTime, Arithmetic, ClassCast,
            // IllegalArgument, ...); all of them mean the same thing here: the stored text does not
            // fit the declared type. Surface that as corruption WITH the cause attached.
            throw DucklakeCatalogCorruptionException(
                "Inlined value for column '${type.name}' (${type.type}) cannot be decoded from ${raw.javaClass.simpleName} " +
                    "'${truncate(raw)}': ${e.message}",
                e,
            )
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun decodeNonNull(raw: Any, type: InlinedTypeNode): Any {
        val t = type.type
        return when {
            t == "boolean" -> toBoolean(raw)
            t == "int8" || t == "int16" || t == "int32" -> checkedInteger(raw, t).intValueExact()
            t == "int64" || t == "uint8" || t == "uint16" || t == "uint32" -> checkedInteger(raw, t).longValueExact()
            t == "uint64" || t == "int128" || t == "uint128" -> checkedInteger(raw, t)
            t == "float32" -> toDouble(raw).toFloat()
            t == "float64" -> toDouble(raw)
            t.startsWith("decimal") -> if (raw is BigDecimal) raw else BigDecimal(text(raw))
            t == "varchar" || t == "json" -> text(raw)
            t == "blob" -> toBytes(raw)
            t == "uuid" -> if (raw is UUID) raw else UUID.fromString(text(raw))
            t == "date" -> if (raw is java.sql.Date) raw.toLocalDate() else if (raw is LocalDate) raw else LocalDate.parse(text(raw))
            t == "time" || t == "time_ns" -> toLocalTime(raw)
            t == "timetz" -> toOffsetTime(raw)
            t == "timestamptz" -> toOffsetDateTime(raw)
            t.startsWith("timestamp") -> toLocalDateTime(raw)
            t == "interval" -> toInterval(raw)
            t == "struct" || t == "list" || t == "map" -> decodeNested(raw, type)
            else -> raw // unknown / unsupported type: pass through untouched
        }
    }

    // ---- scalar conversions -------------------------------------------------------------------

    private fun text(raw: Any): String =
        when (raw) {
            is String -> raw
            is ByteArray -> String(raw, StandardCharsets.UTF_8)
            is java.sql.Blob -> String(raw.getBytes(1, raw.length().toInt()), StandardCharsets.UTF_8)
            else -> raw.toString()
        }

    private fun toBoolean(raw: Any): Boolean =
        when (raw) {
            is Boolean -> raw
            is Number -> raw.toLong() != 0L
            else -> when (text(raw).trim().lowercase(Locale.ROOT)) {
                "true", "t", "1" -> true
                "false", "f", "0" -> false
                else -> throw IllegalArgumentException("not a boolean")
            }
        }

    private val INTEGER_RANGES: Map<String, Pair<BigInteger, BigInteger>> = mapOf(
        "int8" to (BigInteger.valueOf(Byte.MIN_VALUE.toLong()) to BigInteger.valueOf(Byte.MAX_VALUE.toLong())),
        "int16" to (BigInteger.valueOf(Short.MIN_VALUE.toLong()) to BigInteger.valueOf(Short.MAX_VALUE.toLong())),
        "int32" to (BigInteger.valueOf(Int.MIN_VALUE.toLong()) to BigInteger.valueOf(Int.MAX_VALUE.toLong())),
        "int64" to (BigInteger.valueOf(Long.MIN_VALUE) to BigInteger.valueOf(Long.MAX_VALUE)),
        "int128" to (BigInteger.TWO.pow(127).negate() to BigInteger.TWO.pow(127).subtract(BigInteger.ONE)),
        "uint8" to (BigInteger.ZERO to BigInteger.valueOf(255)),
        "uint16" to (BigInteger.ZERO to BigInteger.valueOf(65535)),
        "uint32" to (BigInteger.ZERO to BigInteger.TWO.pow(32).subtract(BigInteger.ONE)),
        "uint64" to (BigInteger.ZERO to BigInteger.TWO.pow(64).subtract(BigInteger.ONE)),
        "uint128" to (BigInteger.ZERO to BigInteger.TWO.pow(128).subtract(BigInteger.ONE)),
    )

    /** The integer value of [raw], verified to lie within the declared DuckLake integer type's range. */
    private fun checkedInteger(raw: Any, type: String): BigInteger {
        val v = toBigInteger(raw)
        val (lo, hi) = INTEGER_RANGES.getValue(type)
        require(v >= lo && v <= hi) { "$v is out of range for $type [$lo, $hi]" }
        return v
    }

    private fun toBigInteger(raw: Any): BigInteger =
        when (raw) {
            is BigInteger -> raw
            is BigDecimal -> raw.toBigIntegerExact()
            is Int, is Long, is Short, is Byte -> BigInteger.valueOf((raw as Number).toLong())
            else -> BigInteger(text(raw).trim())
        }

    private fun toDouble(raw: Any): Double =
        when (raw) {
            is Number -> raw.toDouble()
            else -> when (val s = text(raw).trim().lowercase(Locale.ROOT)) {
                "nan" -> Double.NaN
                "inf", "infinity", "+inf", "+infinity" -> Double.POSITIVE_INFINITY
                "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
                else -> s.toDouble()
            }
        }

    private fun toBytes(raw: Any): ByteArray =
        when (raw) {
            is ByteArray -> raw
            is java.sql.Blob -> raw.getBytes(1, raw.length().toInt())
            else -> decodeBlobText(raw.toString())
        }

    /** DuckDB blob text: printable ASCII verbatim, everything else `\xHH` (a literal backslash is `\\`). */
    fun decodeBlobText(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            i += appendBlobUnit(text, i, out)
        }
        return out.toByteArray()
    }

    /** Appends the byte(s) encoded at [i] and returns how many characters were consumed. */
    private fun appendBlobUnit(text: String, i: Int, out: java.io.ByteArrayOutputStream): Int {
        val c = text[i]
        val isHex = c == '\\' && i + 3 < text.length && text[i + 1] == 'x'
        val isEscapedBackslash = c == '\\' && i + 1 < text.length && text[i + 1] == '\\'
        return when {
            isHex -> {
                out.write(text.substring(i + 2, i + 4).toInt(16))
                4
            }
            isEscapedBackslash -> {
                out.write('\\'.code)
                2
            }
            else -> {
                val bytes = c.toString().toByteArray(StandardCharsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                1
            }
        }
    }

    private fun toLocalTime(raw: Any): LocalTime =
        when (raw) {
            is LocalTime -> raw
            is java.sql.Time -> raw.toLocalTime()
            else -> LocalTime.parse(text(raw).trim().let { if (it.length == 5) "$it:00" else it })
        }

    private fun toOffsetTime(raw: Any): OffsetTime =
        when (raw) {
            is OffsetTime -> raw
            is java.sql.Time -> raw.toLocalTime().atOffset(ZoneOffset.UTC)
            else -> {
                val s = text(raw).trim()
                val (body, offset) = splitOffset(s)
                LocalTime.parse(body).atOffset(offset ?: ZoneOffset.UTC)
            }
        }

    private fun toLocalDateTime(raw: Any): LocalDateTime =
        when (raw) {
            is LocalDateTime -> raw
            is java.sql.Timestamp -> raw.toLocalDateTime()
            is OffsetDateTime -> raw.toLocalDateTime()
            else -> parseLocalDateTime(text(raw).trim().let { splitOffset(it).first })
        }

    private fun toOffsetDateTime(raw: Any): OffsetDateTime =
        when (raw) {
            is OffsetDateTime -> raw.withOffsetSameInstant(ZoneOffset.UTC)
            is java.time.Instant -> raw.atOffset(ZoneOffset.UTC)
            is java.sql.Timestamp -> raw.toInstant().atOffset(ZoneOffset.UTC)
            is LocalDateTime -> raw.atOffset(ZoneOffset.UTC)
            else -> {
                val (body, offset) = splitOffset(text(raw).trim())
                parseLocalDateTime(body).atOffset(offset ?: ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.UTC)
            }
        }

    private fun parseLocalDateTime(body: String): LocalDateTime {
        // DuckDB: `2024-02-29 12:34:56.123456`; ISO: `2024-02-29T12:34:56`; date-only is midnight.
        if (body.length == 10) {
            return LocalDate.parse(body).atStartOfDay()
        }
        val normalised = if (body.length > 10 && body[10] == ' ') body.substring(0, 10) + 'T' + body.substring(11) else body
        return LocalDateTime.parse(normalised)
    }

    private val OFFSET_SUFFIX = Regex("([+-]\\d{2}(?::?\\d{2})?|Z)$")

    /** Splits a trailing zone offset (`+02`, `+02:00`, `-0530`, `Z`) off a temporal text. */
    private fun splitOffset(s: String): Pair<String, ZoneOffset?> {
        val m = OFFSET_SUFFIX.find(s) ?: return s to null
        // A plain time `12:34:56` has no offset; a date `2024-02-29` ends in `-29` which is NOT an offset.
        if (m.range.first == 0 || s.length <= 10 || s.indexOf(':') < 0) {
            return s to null
        }
        val text = m.value
        val offset = if (text == "Z") ZoneOffset.UTC else {
            val sign = if (text[0] == '-') -1 else 1
            val digits = text.substring(1).replace(":", "")
            val hours = digits.substring(0, 2).toInt()
            val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
            ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
        }
        return s.substring(0, m.range.first) to offset
    }

    private val INTERVAL_UNIT = Regex(
        "([+-]?\\d+)\\s*(years?|yrs?|y|months?|mons?|mo|days?|d|hours?|h|minutes?|mins?|m|seconds?|secs?|s|" +
            "milliseconds?|ms|microseconds?|us)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val INTERVAL_CLOCK = Regex("([+-]?)(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,6}))?")
    private const val MICROS_PER_SECOND = 1_000_000L

    @Suppress("CyclomaticComplexMethod")
    private fun toInterval(raw: Any): DucklakeInterval {
        if (raw is DucklakeInterval) {
            return raw
        }
        val s = text(raw).trim()
        var months = 0L
        var days = 0L
        var micros = 0L
        for (m in INTERVAL_UNIT.findAll(s)) {
            val n = m.groupValues[1].toLong()
            when (m.groupValues[2].lowercase(Locale.ROOT).trimEnd('s')) {
                "year", "yr", "y" -> months += n * 12
                "month", "mon", "mo" -> months += n
                "day", "d" -> days += n
                "hour", "h" -> micros += n * 3600 * MICROS_PER_SECOND
                "minute", "min", "m" -> micros += n * 60 * MICROS_PER_SECOND
                "second", "sec" -> micros += n * MICROS_PER_SECOND
                "millisecond", "m" + "s" -> micros += n * 1000
                "microsecond", "u" -> micros += n
            }
        }
        INTERVAL_CLOCK.find(s)?.let { c ->
            val sign = if (c.groupValues[1] == "-") -1 else 1
            var clock = (c.groupValues[2].toLong() * 3600 + c.groupValues[3].toLong() * 60 + c.groupValues[4].toLong()) * MICROS_PER_SECOND
            if (c.groupValues[5].isNotEmpty()) {
                clock += c.groupValues[5].padEnd(6, '0').toLong()
            }
            micros += sign * clock
        }
        return DucklakeInterval(months, days, micros)
    }

    // ---- nested values ------------------------------------------------------------------------

    private fun decodeNested(raw: Any, type: InlinedTypeNode): Any {
        // Native driver objects (DuckDB backend) first; everything else is DuckDB's text form.
        nativeNested(raw)?.let { native ->
            when {
                native is Map<*, *> && type.type == "map" ->
                    return LinkedHashMap<Any?, Any?>().also { out ->
                        native.forEach { (k, v) -> out[decode(k, type.children[0])] = decode(v, type.children[1]) }
                    }
                native is Map<*, *> && type.type == "struct" ->
                    return LinkedHashMap<String, Any?>().also { out -> type.children.forEach { c -> out[c.name] = decode(native[c.name], c) } }
                native is List<*> && type.type == "list" -> return native.map { decode(it, type.children[0]) }
            }
        }
        val parser = NestedTextParser(text(raw))
        val value = parser.parseValue(type) ?: throw IllegalArgumentException("nested value text is NULL")
        parser.expectEnd()
        return value
    }

    /**
     * Unwraps the JDBC driver's native nested objects to plain `List` / `Map`: `java.sql.Array`,
     * `java.util.Map`, `List`, and DuckDB's `DuckDBStruct` (`getMap()`) / `DuckDBMap` — reached by
     * reflection so the catalog does not depend on the DuckDB driver at compile time.
     */
    private fun nativeNested(raw: Any): Any? =
        when {
            raw is java.sql.Array -> (raw.array as Array<*>).toList()
            raw is Map<*, *> -> raw
            raw is List<*> -> raw
            raw.javaClass.simpleName == "DuckDBStruct" -> raw.javaClass.getMethod("getMap").invoke(raw) as? Map<*, *>
            raw.javaClass.simpleName == "DuckDBArray" -> (raw.javaClass.getMethod("getArray").invoke(raw) as? Array<*>)?.toList()
            else -> null
        }

    /**
     * Recursive-descent parser for DuckDB's nested-value text (`Value::ToString` of STRUCT / LIST /
     * MAP): `[v, …]`, `{'field': v, …}`, `{k=v, …}`; strings are quoted with `'` when they contain
     * special characters (escaped with `\`), otherwise bare; a bare `NULL` is SQL NULL.
     */
    private val CAST_LITERAL = Regex("CAST\\('((?:[^']|'')*)' AS [A-Za-z_ ()0-9,]+\\)", RegexOption.IGNORE_CASE)

    private val NULL_TERMINATORS = setOf(',', ']', '}', ' ')

    private class NestedTextParser(private val text: String) {
        private var pos = 0

        fun parseValue(type: InlinedTypeNode): Any? {
            skipWs()
            if (peekBareNull()) {
                pos += "NULL".length
                return null
            }
            return when (type.type) {
                "list" -> parseList(type)
                "struct" -> parseStruct(type)
                "map" -> parseMap(type)
                else -> decodeNonNull(unwrapCastLiteral(readScalarToken()), type)
            }
        }

        /**
         * DuckDB's postgres writer renders a nested value of a type PostgreSQL lacks (DATE,
         * TIMESTAMP, HUGEINT, ...) as the SQL expression `CAST('literal' AS VARCHAR)` inside the
         * struct/list text — and DuckDB itself then fails to read the row back (INTERNAL error).
         * The literal is recoverable, so unwrap it and decode under the declared leaf type.
         */
        private fun unwrapCastLiteral(token: String): String {
            val m = CAST_LITERAL.matchEntire(token) ?: return token
            return m.groupValues[1].replace("''", "'")
        }

        private fun parseList(type: InlinedTypeNode): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue(type.children[0]))
                skipWs()
                if (peek() == ']') {
                    pos++
                    return out
                }
                expect(',')
            }
        }

        private fun parseStruct(type: InlinedTypeNode): LinkedHashMap<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                val key = readScalarToken(stopAtColon = true)
                skipWs()
                expect(':')
                val child = type.children.firstOrNull { it.name == key }
                    ?: throw IllegalArgumentException("struct field '$key' is not declared (fields: ${type.children.map { it.name }})")
                out[key] = parseValue(child)
                skipWs()
                if (peek() == '}') {
                    pos++
                    return out
                }
                expect(',')
            }
        }

        private fun parseMap(type: InlinedTypeNode): LinkedHashMap<Any?, Any?> {
            expect('{')
            val out = LinkedHashMap<Any?, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                val key = if (type.children[0].type in setOf("struct", "list", "map")) parseValue(type.children[0])
                else decodeNonNull(readScalarToken(stopAtEquals = true), type.children[0])
                skipWs()
                expect('=')
                out[key] = parseValue(type.children[1])
                skipWs()
                if (peek() == '}') {
                    pos++
                    return out
                }
                expect(',')
            }
        }

        /** A quoted (`'…'`, `\`-escaped) or bare token; bare tokens end at the structural delimiters. */
        private fun readScalarToken(stopAtColon: Boolean = false, stopAtEquals: Boolean = false): String {
            skipWs()
            return when {
                peek() == '\'' -> readQuoted()
                text.startsWith("CAST(", pos) -> readCastExpression()
                else -> readBare(stopAtColon, stopAtEquals)
            }
        }

        private fun readQuoted(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == '\\' && pos + 1 < text.length) {
                    sb.append(text[pos + 1])
                    pos += 2
                }
                else if (c == '\'') {
                    pos++
                    return sb.toString()
                }
                else {
                    sb.append(c)
                    pos++
                }
            }
            throw IllegalArgumentException("unterminated quoted value")
        }

        /** `CAST('…' AS T)` — up to the matching parenthesis, honouring the quoted literal. */
        private fun readCastExpression(): String {
            val start = pos
            var depth = 0
            var inQuote = false
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '\'') {
                    inQuote = !inQuote
                }
                else if (!inQuote && c == '(') {
                    depth++
                }
                else if (!inQuote && c == ')' && --depth == 0) {
                    break
                }
            }
            return text.substring(start, pos)
        }

        private fun readBare(stopAtColon: Boolean, stopAtEquals: Boolean): String {
            val start = pos
            while (pos < text.length && !isBareDelimiter(text[pos], stopAtColon, stopAtEquals)) {
                pos++
            }
            return text.substring(start, pos).trimEnd()
        }

        private fun isBareDelimiter(c: Char, stopAtColon: Boolean, stopAtEquals: Boolean): Boolean =
            c == ',' || c == ']' || c == '}' || (stopAtColon && c == ':') || (stopAtEquals && c == '=')

        private fun peekBareNull(): Boolean {
            if (!text.startsWith("NULL", pos)) {
                return false
            }
            val after = pos + "NULL".length
            return after >= text.length || text[after] in NULL_TERMINATORS
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        private fun expect(c: Char) {
            skipWs()
            if (peek() != c) {
                throw IllegalArgumentException("expected '$c' at $pos in: $text")
            }
            pos++
        }

        private fun skipWs() {
            while (pos < text.length && text[pos] == ' ') {
                pos++
            }
        }

        fun expectEnd() {
            skipWs()
            if (pos != text.length) {
                throw IllegalArgumentException("unexpected trailing text at $pos in: $text")
            }
        }
    }

    private fun truncate(raw: Any): String {
        val s = if (raw is ByteArray) String(raw, StandardCharsets.UTF_8) else raw.toString()
        return if (s.length > 80) s.substring(0, 80) + "…" else s
    }
}
