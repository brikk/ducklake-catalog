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

import org.jooq.SQLDialect
import java.util.Locale

/** Closed type grammar: physical metadata is parsed as data, never interpolated as SQL. */
internal object Final11InlineTypes {
    const val MAX_DEPTH = 64
    const val MAX_NODES = 4096
    const val MAX_TYPE_LENGTH = 16384
    private val DECIMAL = Regex("decimal\\(\\s*([0-9]{1,2})\\s*,\\s*([0-9]{1,2})\\s*\\)")
    private val SQL_SCALARS = mapOf(
        "BOOLEAN" to "boolean", "TINYINT" to "int8", "SMALLINT" to "int16", "INTEGER" to "int32", "BIGINT" to "int64",
        "HUGEINT" to "int128", "UTINYINT" to "uint8", "USMALLINT" to "uint16", "UINTEGER" to "uint32", "UBIGINT" to "uint64",
        "UHUGEINT" to "uint128", "FLOAT" to "float32", "DOUBLE" to "float64", "VARCHAR" to "varchar", "JSON" to "json",
        "BLOB" to "blob", "UUID" to "uuid", "DATE" to "date", "TIMESTAMP" to "timestamp", "TIMESTAMP_S" to "timestamp_s",
        "TIMESTAMP_MS" to "timestamp_ms", "TIMESTAMP_NS" to "timestamp_ns", "TIMESTAMP WITH TIME ZONE" to "timestamptz",
        "TIMESTAMPTZ_NS" to "timestamptz_ns", "TIME" to "time", "TIME_NS" to "time_ns",
        "TIME WITH TIME ZONE" to "timetz", "INTERVAL" to "interval", "NULL" to "null",
    )
    private val SCALARS = SQL_SCALARS.values.toSet() + setOf("timestamp_us")
    private val SIGNED_WIDTHS = mapOf("int8" to 8, "int16" to 16, "int32" to 32, "int64" to 64, "int128" to 128)
    private val UNSIGNED_WIDTHS = mapOf("uint8" to 8, "uint16" to 16, "uint32" to 32, "uint64" to 64, "uint128" to 128)
    private val DECIMAL_DIGITS = mapOf("boolean" to 1, "int8" to 3, "int16" to 5, "int32" to 10, "int64" to 19,
        "uint8" to 3, "uint16" to 5, "uint32" to 10, "uint64" to 20)
    private val TIMESTAMP_PRECISION = mapOf("timestamp_s" to 0, "timestamp_ms" to 1, "timestamp" to 2, "timestamp_ns" to 3)

    fun validate(type: InlinedTypeNode) {
        var nodes = 0
        fun visit(node: InlinedTypeNode, depth: Int) {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) invalid("inline type exceeds the qualification budget")
            validateNode(node)
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(type, 0)
    }

    private fun validateNode(node: InlinedTypeNode) {
        val expectedChildren = when (node.type) {
            "struct" -> node.children.size.also { if (it == 0) invalid("inline STRUCT has no fields") }
            "list" -> 1
            "map" -> 2
            else -> 0.also {
                if (node.type !in SCALARS && !supportedDecimal(node.type)) invalid("inline type ${node.type} is not qualified")
            }
        }
        if (node.children.size != expectedChildren) invalid("inline type ${node.type} has invalid children")
        if (node.type == "struct" && node.children.map { identifierKey(it.name) }.distinct().size != node.children.size) {
            invalid("inline STRUCT has ambiguous field names")
        }
    }

    fun physicalSql(type: InlinedTypeNode): String {
        validate(type)
        val sql = InlinedDataTables.physicalType(storageType(type), SQLDialect.DUCKDB)
        if (sql.length > MAX_TYPE_LENGTH) invalid("physical payload type exceeds the qualification budget")
        return sql
    }

    private fun storageType(type: InlinedTypeNode): InlinedTypeNode = type.copy(
        type = if (type.type == "null") "\"NULL\"" else type.type,
        children = type.children.map(::storageType),
    )

    /** Native text preserves TIME 24:00/infinity boundaries, packed TIMETZ and nested intervals. */
    fun requiresText(type: InlinedTypeNode): Boolean = type.type in TEXT_TYPES || type.children.any(::requiresText)

    fun parsePhysical(sql: String): InlinedTypeNode {
        if (sql.length > MAX_TYPE_LENGTH) invalid("physical payload type exceeds the qualification budget")
        return Parser(sql).parse().also(::validate)
    }

    /** Qualified native widening semantics only; no text-to-number or narrowing metadata casts. */
    fun requireConversion(source: String, target: String) {
        val from = canonical(source)
        val to = canonical(target)
        if (from == to || numericPromotion(from, to) || temporalPromotion(from, to)) return
        if (from == "null") return
        invalid("scalar conversion $source to $target is not qualified")
    }

    private fun numericPromotion(from: String, to: String): Boolean {
        if (!isNumeric(from)) return false
        if (to == "float64") return true
        if (to == "float32") return from != "float64"
        SIGNED_WIDTHS[to]?.let { width ->
            return from == "boolean" || (SIGNED_WIDTHS[from]?.let { it <= width } == true) ||
                (UNSIGNED_WIDTHS[from]?.let { it < width } == true)
        }
        UNSIGNED_WIDTHS[to]?.let { width -> return from == "boolean" || (UNSIGNED_WIDTHS[from]?.let { it <= width } == true) }
        return decimalPromotion(from, to)
    }

    private fun isNumeric(type: String): Boolean = type in SIGNED_WIDTHS || type in UNSIGNED_WIDTHS ||
        type in setOf("boolean", "float32", "float64") || supportedDecimal(type)

    private fun decimalPromotion(from: String, to: String): Boolean {
        val target = DECIMAL.matchEntire(to) ?: return false
        val scale = target.groupValues[2].toInt()
        val integral = target.groupValues[1].toInt() - scale
        val source = DECIMAL.matchEntire(from)
        if (source != null) return scale >= source.groupValues[2].toInt() &&
            integral >= source.groupValues[1].toInt() - source.groupValues[2].toInt()
        return DECIMAL_DIGITS[from]?.let { integral >= it } == true
    }

    private fun temporalPromotion(from: String, to: String): Boolean {
        val precision = TIMESTAMP_PRECISION[to] ?: return false
        return from == "date" || (TIMESTAMP_PRECISION[from]?.let { it <= precision } == true)
        // Timezone-dependent promotions need an explicit session-zone contract, not an implicit UTC cast.
    }

    /** Only STRUCT ordering is immaterial. Missing/extra/renamed fields and leaf-type drift fail. */
    fun samePhysical(expected: InlinedTypeNode, actual: InlinedTypeNode): Boolean {
        val left = canonical(expected.type)
        if (left != canonical(actual.type)) return false
        if (left == "struct") {
            val fields = actual.children.associateBy { identifierKey(it.name) }
            return fields.size == expected.children.size && expected.children.all { child ->
                fields[identifierKey(child.name)]?.let { samePhysical(child, it) } == true
            }
        }
        return expected.children.size == actual.children.size && expected.children.zip(actual.children).all { (a, b) -> samePhysical(a, b) }
    }

    /** DuckDB folds ASCII identifier letters, not arbitrary Unicode case equivalents. */
    fun identifierKey(name: String): String = name.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")

    private fun canonical(type: String): String {
        if (type == "timestamp_us") return "timestamp"
        val match = DECIMAL.matchEntire(type) ?: return type
        return "decimal(${match.groupValues[1].toInt()},${match.groupValues[2].toInt()})"
    }

    private fun supportedDecimal(type: String): Boolean {
        val match = DECIMAL.matchEntire(type) ?: return false
        val precision = match.groupValues[1].toInt()
        return precision in 1..38 && match.groupValues[2].toInt() in 0..precision
    }

    private fun invalid(detail: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 $detail")

    private class Parser(private val sql: String) {
        private var position = 0
        private var nodes = 0

        fun parse(): InlinedTypeNode = type("", 0).also {
            whitespace()
            if (position != sql.length) invalid("physical inline type has trailing syntax")
        }

        private fun type(name: String, depth: Int): InlinedTypeNode {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) invalid("physical inline type exceeds the qualification budget")
            val keyword = identifier().uppercase(Locale.ROOT)
            var result = when (keyword) {
                "STRUCT" -> struct(name, depth)
                "MAP" -> {
                    expect('(')
                    val key = type("key", depth + 1)
                    expect(',')
                    val value = type("value", depth + 1)
                    expect(')')
                    InlinedTypeNode("map", name, listOf(key, value))
                }
                "DECIMAL" -> {
                    expect('(')
                    val precision = digits()
                    expect(',')
                    val scale = digits()
                    expect(')')
                    InlinedTypeNode("decimal($precision,$scale)", name)
                }
                else -> scalar(keyword, name)
            }
            whitespace()
            while (peek() == '[') {
                expect('[')
                expect(']') // DuckLake stores native arrays as LIST; do not guess fixed-array layouts.
                if (++nodes > MAX_NODES) invalid("physical inline type exceeds the qualification budget")
                result = InlinedTypeNode("list", name, listOf(result.copy(name = "element")))
                whitespace()
            }
            return result
        }

        private fun struct(name: String, depth: Int): InlinedTypeNode {
            expect('(')
            val children = mutableListOf<InlinedTypeNode>()
            do {
                val field = identifier()
                children.add(type(field, depth + 1))
                whitespace()
                if (peek() != ',') break
                position++
            } while (true)
            expect(')')
            return InlinedTypeNode("struct", name, children)
        }

        private fun scalar(keyword: String, name: String): InlinedTypeNode {
            whitespace()
            val complete = if (keyword in setOf("TIMESTAMP", "TIME") && sql.startsWith("WITH TIME ZONE", position)) {
                position += "WITH TIME ZONE".length
                "$keyword WITH TIME ZONE"
            } else keyword
            val logical = SQL_SCALARS[complete] ?: invalid("physical inline type $complete is not qualified")
            return InlinedTypeNode(logical, name)
        }

        private fun identifier(): String {
            whitespace()
            if (peek() == '"') {
                position++
                val value = StringBuilder()
                while (position < sql.length) {
                    val char = sql[position++]
                    if (char != '"') value.append(char)
                    else if (peek() == '"') { value.append('"'); position++ }
                    else return value.toString()
                }
                invalid("physical inline type has an unterminated field name")
            }
            val start = position
            while (position < sql.length && !sql[position].isWhitespace() && sql[position] !in "(),[]") position++
            if (position == start) invalid("physical inline type has an invalid identifier")
            return sql.substring(start, position)
        }

        private fun digits(): String {
            whitespace()
            val start = position
            while (peek().isDigit()) position++
            if (position == start || position - start > 2) invalid("physical inline DECIMAL is not qualified")
            return sql.substring(start, position)
        }

        private fun whitespace() { while (position < sql.length && sql[position].isWhitespace()) position++ }
        private fun peek(): Char = sql.getOrNull(position) ?: '\u0000'
        private fun expect(char: Char) {
            whitespace()
            if (peek() != char) invalid("physical inline type expected '$char'")
            position++
        }
    }

    private val TEXT_TYPES = setOf("date", "time", "time_ns", "timetz", "interval", "timestamp", "timestamp_us", "timestamp_s",
        "timestamp_ms", "timestamp_ns", "timestamptz", "timestamptz_ns")
}
