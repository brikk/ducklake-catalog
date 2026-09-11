package dev.brikk.ducklake.corpus

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Locale

/**
 * GEOMETRY is exposed as WKB by JDBC's object API (and by VARBINARY engine adapters), but
 * DuckDB's typed string API renders WKT. Normalize only geometry leaves, using the oracle's
 * logical result types, never by guessing from a BLOB's contents or a field's name.
 * DuckDB's own WKB decoder handles geometry kinds, dimensions and byte order.
 */
internal object GeometryNormalizer {
    fun containsGeometry(sqlType: String): Boolean = parse(sqlType).hasGeometry

    fun normalizeRows(connection: Connection, types: List<String>, rows: List<List<String?>>): List<List<String?>> {
        if (rows.isEmpty() || rows.any { it.size != types.size }) return rows
        val result = rows.map { it.toMutableList() }
        types.forEachIndexed { index, sqlType ->
            val type = parse(sqlType)
            if (type.hasGeometry) normalizeColumn(connection, type, result, index)
        }
        return result
    }

    private fun normalizeColumn(connection: Connection, type: ValueType, rows: List<MutableList<String?>>, index: Int) {
        // Both WKT adapters and the existing BLOB-text adapters are supported. Bind values; the
        // type expressions come only from the oracle ResultSetMetaData, not engine cell text.
        connection.prepareStatement("SELECT CAST(CAST(? AS ${type.sql}) AS VARCHAR)").use { wkt ->
            val expression = type.fromWkb("v", 0)
            connection.prepareStatement("SELECT CAST($expression AS VARCHAR) FROM (SELECT CAST(? AS ${type.storage}) AS v)").use { wkb ->
                for (row in rows) {
                    val value = row[index] ?: continue
                    row[index] = try {
                        castText(wkt, value)
                    } catch (_: SQLException) {
                        // A malformed WKB must fail comparison, never become NULL or a skip.
                        castText(wkb, value)
                    }
                }
            }
        }
    }

    private fun castText(statement: PreparedStatement, value: String): String? {
        statement.setString(1, value)
        return statement.executeQuery().use { rs ->
            check(rs.next())
            rs.getString(1)?.replace("\u0000", "\\0")
        }
    }

    private sealed interface ValueType {
        val sql: String
        val storage: String
        val hasGeometry: Boolean
        fun fromWkb(value: String, depth: Int): String
    }

    private data class Scalar(override val sql: String, override val hasGeometry: Boolean = false) : ValueType {
        override val storage = if (hasGeometry) "BLOB" else sql
        override fun fromWkb(value: String, depth: Int) = if (hasGeometry) "ST_GeomFromWKB($value)" else value
    }

    private data class SequenceType(override val sql: String, val child: ValueType, val suffix: String) : ValueType {
        override val storage = child.storage + suffix
        override val hasGeometry = child.hasGeometry
        override fun fromWkb(value: String, depth: Int): String =
            "list_transform($value, geo_$depth -> ${child.fromWkb("geo_$depth", depth + 1)})"
    }

    private data class StructType(override val sql: String, val fields: List<Pair<String, ValueType>>) : ValueType {
        override val storage = fields.joinToString(", ", "STRUCT(", ")") { (name, type) -> "${identifier(name)} ${type.storage}" }
        override val hasGeometry = fields.any { it.second.hasGeometry }
        override fun fromWkb(value: String, depth: Int): String {
            val children = fields.joinToString(", ") { (name, type) ->
                "${identifier(name)} := ${type.fromWkb("($value).${identifier(name)}", depth + 1)}"
            }
            // struct_pack(NULL children) is not a NULL struct. Preserve NULL containers explicitly.
            return "CASE WHEN $value IS NULL THEN NULL ELSE struct_pack($children) END"
        }
    }

    private data class MapType(override val sql: String, val key: ValueType, val value: ValueType) : ValueType {
        override val storage = "MAP(${key.storage}, ${value.storage})"
        override val hasGeometry = key.hasGeometry || value.hasGeometry
        override fun fromWkb(value: String, depth: Int): String =
            "map(list_transform(map_keys($value), geo_k$depth -> ${key.fromWkb("geo_k$depth", depth + 1)}), " +
                "list_transform(map_values($value), geo_v$depth -> ${this.value.fromWkb("geo_v$depth", depth + 1)}))"
    }

    private val ARRAY = Regex("^(.*)(\\[\\d*])$", RegexOption.DOT_MATCHES_ALL)
    private val FIELD = Regex("^(\"(?:[^\"]|\"\")*\"|\\S+)\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)

    private fun parse(name: String): ValueType {
        val sql = name.trim()
        ARRAY.matchEntire(sql)?.let { return SequenceType(sql, parse(it.groupValues[1]), it.groupValues[2]) }
        val upper = sql.uppercase(Locale.ROOT)
        return when {
            upper == "GEOMETRY" || upper.startsWith("GEOMETRY(") -> Scalar(sql, true)
            upper.startsWith("STRUCT(") -> StructType(sql, splitChildren(sql.substring(7, sql.length - 1)).map { field ->
                val match = requireNotNull(FIELD.matchEntire(field.trim())) { "Invalid oracle struct field: $field" }
                val key = match.groupValues[1].removeSurrounding("\"").replace("\"\"", "\"")
                key to parse(match.groupValues[2])
            })
            upper.startsWith("MAP(") -> {
                val children = splitChildren(sql.substring(4, sql.length - 1))
                require(children.size == 2) { "Invalid oracle map type: $sql" }
                MapType(sql, parse(children[0]), parse(children[1]))
            }
            else -> Scalar(sql)
        }
    }

    /** Split type parameters without splitting DECIMALs, nested types, CRS strings or quoted keys. */
    private fun splitChildren(sql: String): List<String> {
        val children = mutableListOf<String>()
        var depth = 0
        var start = 0
        var index = 0
        while (index < sql.length) {
            when (sql[index]) {
                '\'', '"' -> index = quotedEnd(sql, index)
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    children += sql.substring(start, index).trim()
                    start = index + 1
                }
            }
            index++
        }
        children += sql.substring(start).trim()
        return children
    }

    private fun quotedEnd(sql: String, start: Int): Int {
        var index = start + 1
        while (index < sql.length) {
            if (sql[index] == sql[start]) {
                if (sql.getOrNull(index + 1) != sql[start]) return index
                index++
            }
            index++
        }
        error("Unterminated quoted oracle type: $sql")
    }

    private fun identifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""
}
