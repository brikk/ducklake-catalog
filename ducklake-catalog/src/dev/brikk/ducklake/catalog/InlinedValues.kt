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
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

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
 * canonical Java representation per DuckLake type (dev-docs/archive/TODO-rectify-from-eval.md R-D4).
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
 * than passing a wrong value through. Unqualified native temporal edges raise
 * [DucklakeInvalidOperationException]; an unknown type name passes the raw value through unchanged.
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
    fun decode(raw: Any?, type: InlinedTypeNode): Any? = decodeValue(raw, type, false)

    /** Text-backed final-1.1 storage has no native STRUCT/leaf domain enforcement. */
    internal fun decodeQualified(raw: Any?, type: InlinedTypeNode): Any? = decodeValue(raw, type, true)

    private fun decodeValue(raw: Any?, type: InlinedTypeNode, strict: Boolean): Any? {
        if (raw == null) {
            return null
        }
        return try {
            decodeNonNull(raw, type, strict)
        }
        catch (e: DucklakeException) {
            throw e // Preserve corruption vs explicit unsupported-value classification.
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
    private fun decodeNonNull(raw: Any, type: InlinedTypeNode, strict: Boolean = false): Any {
        val t = type.type
        if (strict && t == "null") throw DucklakeCatalogCorruptionException("Non-NULL value in logical NULL storage")
        if (strict && raw is String && t in setOf("timetz", "timestamptz", "timestamptz_ns")) {
            val nonfinite = raw.trim().lowercase(Locale.ROOT) in setOf("infinity", "+infinity", "-infinity")
            require(nonfinite || InlinedTemporalValues.hasExplicitOffset(raw)) { "missing explicit temporal offset" }
        }
        return when {
            t == "boolean" -> toBoolean(raw)
            t == "int8" || t == "int16" || t == "int32" -> checkedInteger(raw, t).intValueExact()
            t == "int64" || t == "uint8" || t == "uint16" || t == "uint32" -> checkedInteger(raw, t).longValueExact()
            t == "uint64" || t == "int128" || t == "uint128" -> checkedInteger(raw, t)
            t == "float32" -> toDouble(raw).toFloat().also { requireQualifiedFloat(raw, it.isInfinite(), strict) }
            t == "float64" -> toDouble(raw).also { requireQualifiedFloat(raw, it.isInfinite(), strict) }
            t.startsWith("decimal") -> if (raw is BigDecimal) raw else BigDecimal(text(raw))
            t == "varchar" || t == "json" -> if (strict && raw is ByteArray) qualifiedUtf8(raw) else text(raw)
            t == "blob" -> toBytes(raw)
            t == "uuid" -> if (raw is UUID) raw else UUID.fromString(text(raw))
            t == "date" -> if (raw is java.sql.Date) raw.toLocalDate() else if (raw is LocalDate) raw else InlinedTemporalValues.date(text(raw))
            t == "time" || t == "time_ns" -> toLocalTime(raw)
            t == "timetz" -> toOffsetTime(raw)
            t == "timestamptz" || t == "timestamptz_ns" -> toOffsetDateTime(raw)
            t.startsWith("timestamp") -> toLocalDateTime(raw)
            t == "interval" -> toInterval(raw)
            t == "struct" || t == "list" || t == "map" -> decodeNested(raw, type, strict)
            else -> raw // unknown / unsupported type: pass through untouched
        }
    }

    private fun qualifiedUtf8(raw: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString()
    } catch (e: CharacterCodingException) {
        throw DucklakeCatalogCorruptionException("Invalid UTF-8 in qualified inline text storage", e)
    }

    private fun requireQualifiedFloat(raw: Any, infinite: Boolean, strict: Boolean) {
        if (strict && infinite && raw is String) {
            require(raw.trim().lowercase(Locale.ROOT) in setOf("inf", "+inf", "-inf", "infinity", "+infinity", "-infinity")) {
                "finite floating-point text overflows its declared type"
            }
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
            else -> InlinedTemporalValues.time(text(raw))
        }

    private fun toOffsetTime(raw: Any): OffsetTime =
        when (raw) {
            is OffsetTime -> raw
            is java.sql.Time -> raw.toLocalTime().atOffset(ZoneOffset.UTC)
            else -> InlinedTemporalValues.offsetTime(text(raw))
        }

    private fun toLocalDateTime(raw: Any): LocalDateTime =
        when (raw) {
            is LocalDateTime -> raw
            is java.sql.Timestamp -> raw.toLocalDateTime()
            is OffsetDateTime -> raw.toLocalDateTime()
            else -> InlinedTemporalValues.localTimestampText(text(raw))
        }

    private fun toOffsetDateTime(raw: Any): OffsetDateTime =
        when (raw) {
            is OffsetDateTime -> raw.withOffsetSameInstant(ZoneOffset.UTC)
            is java.time.Instant -> raw.atOffset(ZoneOffset.UTC)
            is java.sql.Timestamp -> raw.toInstant().atOffset(ZoneOffset.UTC)
            is LocalDateTime -> raw.atOffset(ZoneOffset.UTC)
            else -> InlinedTemporalValues.offsetTimestamp(text(raw))
        }

    private fun toInterval(raw: Any): DucklakeInterval {
        if (raw is DucklakeInterval) return raw
        if (raw.javaClass.name == "com.gizmodata.quack.jdbc.message.IntervalValue") {
            try {
                fun component(name: String) = (raw.javaClass.getMethod(name).invoke(raw) as Number).toLong()
                return DucklakeInterval(component("months"), component("days"), component("micros"))
            }
            catch (e: ReflectiveOperationException) {
                throw DucklakeCatalogCorruptionException("Cannot decode the qualified Quack interval representation", e)
            }
        }
        return InlinedTemporalValues.interval(text(raw))
    }

    // ---- nested values ------------------------------------------------------------------------

    private fun decodeNested(raw: Any, type: InlinedTypeNode, strict: Boolean): Any {
        // Native driver objects (DuckDB backend) first; everything else is DuckDB's text form.
        nativeNested(raw, type)?.let { native ->
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
        val parser = NestedTextParser(text(raw), strict)
        val value = parser.parseValue(type) ?: throw IllegalArgumentException("nested value text is NULL")
        parser.expectEnd()
        return value
    }

    /**
     * Unwraps the JDBC driver's native nested objects to plain `List` / `Map`: `java.sql.Array`,
     * standard `java.sql.Struct`, `java.util.Map`, `List`, and DuckDB's `DuckDBStruct` (`getMap()`) / `DuckDBMap` — reached by
     * reflection so the catalog does not depend on the DuckDB driver at compile time.
     */
    private fun nativeNested(raw: Any, type: InlinedTypeNode): Any? =
        when {
            raw is java.sql.Array -> (raw.array as Array<*>).toList()
            raw is Map<*, *> -> raw
            raw is List<*> -> raw
            raw.javaClass.simpleName == "DuckDBStruct" -> raw.javaClass.getMethod("getMap").invoke(raw) as? Map<*, *>
            raw.javaClass.simpleName == "DuckDBArray" -> (raw.javaClass.getMethod("getArray").invoke(raw) as? Array<*>)?.toList()
            raw is java.sql.Struct -> standardStructFields(raw, type)
            else -> null
        }

    private fun standardStructFields(raw: java.sql.Struct, type: InlinedTypeNode): Map<String, Any?> {
        val attributes = raw.attributes
        require(type.type == "struct" && attributes.size == type.children.size) {
            "Struct attribute count does not match declared fields"
        }
        return LinkedHashMap<String, Any?>().also { out ->
            type.children.forEachIndexed { index, child -> out[child.name] = attributes[index] }
        }
    }

    /**
     * Recursive-descent parser for DuckDB's nested-value text (`Value::ToString` of STRUCT / LIST /
     * MAP): `[v, …]`, `{'field': v, …}`, `{k=v, …}`; strings are quoted with `'` when they contain
     * special characters (escaped with `\`), otherwise bare; a bare `NULL` is SQL NULL.
     */
    private val CAST_LITERAL = Regex("CAST\\('((?:[^']|'')*)' AS [A-Za-z_ ()0-9,]+\\)", RegexOption.IGNORE_CASE)

    private val NULL_TERMINATORS = setOf(',', ']', '}', ' ')

    private class NestedTextParser(private val text: String, private val strict: Boolean) {
        private var pos = 0
        private var nodes = 0

        fun parseValue(type: InlinedTypeNode): Any? {
            if (strict && ++nodes > 65536) throw DucklakeInvalidOperationException("Inline value node budget exceeded")
            skipWs()
            if (peekBareNull()) {
                pos += "NULL".length
                return null
            }
            return when (type.type) {
                "list" -> parseList(type)
                "struct" -> parseStruct(type)
                "map" -> parseMap(type)
                else -> readLeaf(type)
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

        private fun readLeaf(type: InlinedTypeNode, key: Boolean = false): Any {
            skipWs()
            val quoted = peek() == '\''
            val token = readScalarToken(stopAtEquals = key)
            return decodeNonNull(if (quoted) token else unwrapCastLiteral(token), type, strict)
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
                require(!strict || type.children.isEmpty()) { "missing STRUCT fields" }
                return out
            }
            while (true) {
                skipWs()
                val key = readScalarToken(stopAtColon = true)
                skipWs()
                expect(':')
                val child = type.children.firstOrNull {
                    if (strict) Final11InlineTypes.identifierKey(it.name) == Final11InlineTypes.identifierKey(key) else it.name == key
                }
                    ?: throw IllegalArgumentException("struct field '$key' is not declared (fields: ${type.children.map { it.name }})")
                require(!strict || !out.containsKey(child.name)) { "duplicate STRUCT field" }
                out[if (strict) child.name else key] = parseValue(child)
                skipWs()
                if (peek() == '}') {
                    pos++
                    require(!strict || out.size == type.children.size) { "missing STRUCT fields" }
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
                val nullKey = peekBareNull() || text.startsWith("NULL=", pos)
                if (strict && nullKey) throw IllegalArgumentException("NULL MAP key")
                val key = if (type.children[0].type in setOf("struct", "list", "map")) parseValue(type.children[0])
                else readLeaf(type.children[0], key = true)
                if (strict && out.containsKey(key)) throw IllegalArgumentException("duplicate MAP key")
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
