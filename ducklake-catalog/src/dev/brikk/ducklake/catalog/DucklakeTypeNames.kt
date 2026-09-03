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

import java.util.Locale

/**
 * The DuckLake `column_type` vocabulary (upstream `common/ducklake_types.cpp`, `DUCKLAKE_TYPES` +
 * `ParseBaseType` + `FromString`) and its validation for everything this library writes into
 * `ducklake_column.column_type`.
 *
 * Why validate: upstream parses every column's type string when it loads a table and throws
 * `Failed to parse DuckLake type - unsupported type '...'` on anything it does not know — which
 * makes the WHOLE catalog unloadable for DuckDB. A DuckDB-dialect name such as `integer` or
 * `bigint` is not a DuckLake type (`int32` / `int64` are).
 *
 * Rules mirrored from upstream:
 *  - base names match case-insensitively (`CIEquals`) and are stored lowercase, as upstream writes them;
 *  - `decimal(w,s)` must start with the exact lowercase prefix `decimal(` (upstream `StartsWith` is
 *    case-sensitive) and carry two integers, `1 <= w <= 38`, `0 <= s <= w`;
 *  - `struct` needs at least one child, `list` exactly one, `map` exactly two (upstream
 *    `TransformColumnType` fails the load otherwise); scalar types have no children.
 */
object DucklakeTypeNames {
    /** Every base type name upstream accepts, lowercase. */
    @JvmField
    val BASE_TYPES: Set<String> = setOf(
        "boolean",
        "int8", "int16", "int32", "int64", "int128",
        "uint8", "uint16", "uint32", "uint64", "uint128",
        "float32", "float64",
        "time", "time_ns", "timetz", "date",
        "timestamp", "timestamp_us", "timestamp_ms", "timestamp_ns", "timestamp_s", "timestamptz",
        "interval", "varchar", "blob", "uuid",
        "json", "variant", "geometry",
        "struct", "map", "list",
    )

    private val DECIMAL = Regex("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
    private const val DECIMAL_MAX_WIDTH = 38

    /**
     * The canonical (stored) form of [type], or throws [DucklakeInvalidOperationException] when
     * upstream would not be able to parse it.
     */
    fun canonical(type: String): String {
        val trimmed = type.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return when {
            lower in BASE_TYPES -> lower
            lower.startsWith("decimal") -> canonicalDecimal(type, trimmed)
            else -> throw DucklakeInvalidOperationException(
                "Invalid DuckLake type '$type' — not a DuckLake type name (DuckDB would fail to load the catalog: " +
                    "\"Failed to parse DuckLake type - unsupported type\"). Use the DuckLake vocabulary, e.g. int32/int64 " +
                    "instead of integer/bigint, float64 instead of double, decimal(w,s), varchar, blob, timestamp, " +
                    "timestamptz, date, struct/list/map with children.",
            )
        }
    }

    private fun canonicalDecimal(original: String, trimmed: String): String {
        val m = DECIMAL.matchEntire(trimmed)
        val width = m?.groupValues?.get(1)?.toIntOrNull()
        val scale = m?.groupValues?.get(2)?.toIntOrNull()
        val valid = width != null && scale != null && width in 1..DECIMAL_MAX_WIDTH && scale <= width
        if (!valid) {
            throw DucklakeInvalidOperationException(
                "Invalid DuckLake type '$original': decimals must be written exactly as lowercase decimal(width,scale) " +
                    "with width 1..$DECIMAL_MAX_WIDTH and scale <= width",
            )
        }
        return "decimal($width,$scale)"
    }

    /** Validates one [TableColumnSpec] subtree (type names and nesting shape); returns nothing on success. */
    fun validate(spec: TableColumnSpec, path: String = spec.name) {
        val canonical = canonical(spec.ducklakeType)
        val children = spec.children.size
        when (canonical) {
            "struct" -> require(children >= 1) { "struct column $path must have at least one field" }
            "list" -> require(children == 1) { "list column $path must have exactly one child (the element), got $children" }
            "map" -> require(children == 2) { "map column $path must have exactly two children (key, value), got $children" }
            else -> require(children == 0) { "scalar column $path of type $canonical cannot have children" }
        }
        for (child in spec.children) {
            validate(child, "$path.${child.name}")
        }
    }

    private inline fun require(condition: Boolean, message: () -> String) {
        if (!condition) {
            throw DucklakeInvalidOperationException(message())
        }
    }
}
