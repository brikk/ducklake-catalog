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

/**
 * The physical `ducklake_inlined_data_<tableId>_<schemaVersion>` tables, as upstream creates them
 * (`DuckLakeMetadataManager::GetInlinedTableQuery` / `InlinedTableDdlSql` / `GetColumnType`).
 *
 * DuckDB picks the inlined table to INSERT into by `MAX(schema_version)` in
 * `ducklake_inlined_data_tables` (`LatestInlinedTableQuery`), so every column-schema change on a
 * table that already has inlined tables must register a new one for the new schema version —
 * otherwise DuckDB's next inlined INSERT targets the old-schema table (upstream
 * `ducklake_transaction_state.cpp` `column_schema_change` → `WriteNewInlinedTables`).
 *
 * Column types are the backend's PHYSICAL types, which is what [InlinedValues] decodes on the way
 * back out:
 *  - PostgreSQL (`PostgresMetadataManager::TypeIsNativelySupported` / `GetColumnTypeInternal`):
 *    nested types, `uint64`, `int128`, `uint128`, `date`, every `timestamp*` → `VARCHAR`;
 *    `varchar` / `blob` / `json` → `BYTEA`; `float64` → `DOUBLE PRECISION`; `int8` → `SMALLINT`;
 *    `uint8` / `uint16` → `INTEGER`; `uint32` → `BIGINT`; `float32` → `REAL`.
 *  - DuckDB (and any other backend, like upstream's base manager): DuckDB type names, nested as
 *    `STRUCT(a T, …)`, `T[]`, `MAP(K, V)`.
 */
internal object InlinedDataTables {
    /** Upstream `DuckLakeUtil::IsInlinedSystemColumn`. */
    private val SYSTEM_COLUMNS = setOf("row_id", "begin_snapshot", "end_snapshot", "_ducklake_internal_snapshot_id", "_ducklake_internal_row_id")

    /** PostgreSQL `NAMEDATALEN - 1`; DuckDB has no practical limit (upstream `MaxIdentifierLength`). */
    private const val POSTGRES_MAX_IDENTIFIER = 63

    /** DuckLake type name → DuckDB type name (`DuckLakeTypes::FromString` reversed). */
    private val DUCKDB_TYPE_NAMES = mapOf(
        "boolean" to "BOOLEAN", "int8" to "TINYINT", "int16" to "SMALLINT", "int32" to "INTEGER", "int64" to "BIGINT",
        "int128" to "HUGEINT", "uint8" to "UTINYINT", "uint16" to "USMALLINT", "uint32" to "UINTEGER", "uint64" to "UBIGINT",
        "uint128" to "UHUGEINT", "float32" to "FLOAT", "float64" to "DOUBLE", "time" to "TIME", "time_ns" to "TIME_NS",
        "date" to "DATE", "timestamp" to "TIMESTAMP", "timestamp_us" to "TIMESTAMP", "timestamp_ms" to "TIMESTAMP_MS",
        "timestamp_ns" to "TIMESTAMP_NS", "timestamp_s" to "TIMESTAMP_S", "timestamptz" to "TIMESTAMP WITH TIME ZONE",
        "timetz" to "TIME WITH TIME ZONE", "interval" to "INTERVAL", "varchar" to "VARCHAR", "blob" to "BLOB",
        "uuid" to "UUID", "json" to "JSON", "variant" to "VARIANT",
    )

    /** Types PostgreSQL stores as text (`TypeIsNativelySupported` = false, non-nested → `GetColumnTypeInternal`). */
    private val POSTGRES_TEXT_TYPES = setOf(
        "uint64", "int128", "uint128", "date", "timestamp", "timestamp_us", "timestamp_ms", "timestamp_ns", "timestamp_s",
        "timestamptz",
        // Upstream would emit DuckDB's `TIME_NS`, which PostgreSQL does not have; text is lossless.
        "time_ns",
    )

    private val POSTGRES_NATIVE_OVERRIDES = mapOf(
        "float64" to "DOUBLE PRECISION", "int8" to "SMALLINT", "uint8" to "INTEGER", "uint16" to "INTEGER",
        "uint32" to "BIGINT", "float32" to "REAL", "varchar" to "BYTEA", "blob" to "BYTEA", "json" to "BYTEA",
    )

    fun tableName(tableId: Long, schemaVersion: Long): String = "ducklake_inlined_data_${tableId}_$schemaVersion"

    /**
     * Upstream `CanInlineColumns`: no reserved system column names, identifiers within the backend's
     * limit, and no type the backend cannot inline (`geometry` anywhere; `variant` outside DuckDB).
     */
    fun canInline(columns: List<InlinedTypeNode>, dialect: SQLDialect): Boolean {
        val maxIdentifier = if (dialect == SQLDialect.POSTGRES) POSTGRES_MAX_IDENTIFIER else Int.MAX_VALUE
        return columns.all { c ->
            c.name.lowercase() !in SYSTEM_COLUMNS && c.name.length <= maxIdentifier && supportsInlining(c, dialect)
        }
    }

    private fun supportsInlining(node: InlinedTypeNode, dialect: SQLDialect): Boolean =
        when {
            node.type == "geometry" -> false
            node.type == "variant" && dialect != SQLDialect.DUCKDB -> false
            else -> node.children.all { supportsInlining(it, dialect) }
        }

    /** Upstream `InlinedTableDdlSql`: `CREATE TABLE IF NOT EXISTS <name>(row_id BIGINT, begin_snapshot BIGINT, end_snapshot BIGINT, <cols>)`. */
    fun createTableSql(name: String, columns: List<InlinedTypeNode>, dialect: SQLDialect): String {
        val columnDefs = columns.joinToString(", ") { "${quote(it.name)} ${physicalType(it, dialect)}" }
        return "CREATE TABLE IF NOT EXISTS ${quote(name)}(row_id BIGINT, begin_snapshot BIGINT, end_snapshot BIGINT, $columnDefs)"
    }

    /** Upstream `GetColumnType` for the backend behind [dialect]. */
    fun physicalType(node: InlinedTypeNode, dialect: SQLDialect): String {
        val nested = node.type == "struct" || node.type == "list" || node.type == "map"
        if (dialect == SQLDialect.POSTGRES) {
            if (nested || node.type in POSTGRES_TEXT_TYPES) {
                return "VARCHAR"
            }
            POSTGRES_NATIVE_OVERRIDES[node.type]?.let { return it }
        }
        return when (node.type) {
            "struct" -> "STRUCT(" + node.children.joinToString(", ") { "${quote(it.name)} ${physicalType(it, dialect)}" } + ")"
            "list" -> physicalType(node.children[0], dialect) + "[]"
            "map" -> "MAP(${physicalType(node.children[0], dialect)}, ${physicalType(node.children[1], dialect)})"
            else -> duckDbTypeName(node.type)
        }
    }

    /** Named types via the table; `decimal(p,s)` (and anything unknown) is already a DuckDB spelling. */
    private fun duckDbTypeName(ducklakeType: String): String = DUCKDB_TYPE_NAMES[ducklakeType] ?: ducklakeType.uppercase()

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
