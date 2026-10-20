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

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SCHEMA
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** Conservative legacy-DTO reader, not an implementation of parent-aware 1.1 envelopes. */
internal object Final11ReadCompatibility {
    private val INLINE_SYSTEM_NAMES = listOf("_ducklake_row_id", "_ducklake_begin_snapshot", "_ducklake_end_snapshot")
    private val SCALARS = setOf("boolean", "int8", "int16", "int32", "int64", "int128", "uint8", "uint16", "uint32", "uint64", "uint128",
        "float32", "float64", "varchar", "json", "blob", "uuid", "date", "timestamp", "timestamp_s", "timestamp_ms", "timestamp_us",
        "timestamp_ns", "timestamptz")

    fun requireReadableLayout(ctx: DSLContext, version: String?) {
        if (version in setOf("0.4", "1.0")) return
        if (version != "1.1") {
            throw DucklakeInvalidOperationException("Opt-in final-1.1 reader cannot qualify catalog version $version")
        }
        val schema = DUCKLAKE_SCHEMA.`as`("read_schema")
        val parent = DSL.field(DSL.name("read_schema", "parent_schema_id"), Long::class.javaObjectType)
        // Checks the actual required column, not just the version string. Bound the SQL result;
        // never flatten a child, including retired history used by CDC/path enumeration.
        if (ctx.fetchExists(ctx.selectOne().from(schema).where(parent.isNotNull).limit(1))) {
            throw DucklakeInvalidOperationException(
                "Final-1.1 legacy reader does not support nested-schema history; a parent-aware reader is required")
        }
    }

    fun requireInlineLayout(ctx: DSLContext, tableName: String) {
        // The catalog disables generated schema rendering; retain this trusted system qualifier.
        val columns = DSL.table(DSL.sql("information_schema.columns")).`as`("read_columns")
        fun text(name: String) = DSL.field(DSL.name("read_columns", name), String::class.java)
        val names = ctx.select(DSL.substring(text("column_name"), 1, 128), DSL.substring(text("data_type"), 1, 32))
            .from(columns).where(text("table_catalog").eq(DSL.field("current_database()", String::class.java)))
            .and(text("table_schema").eq(DSL.field("current_schema()", String::class.java))).and(text("table_name").eq(tableName))
            .orderBy(DSL.field(DSL.name("read_columns", "ordinal_position"))).limit(3)
            .fetch { listOf(it.value1(), it.value2()) }
        // Lazy/nonmaterialized optional tables remain absent. Existing tables must have the
        // actual metadata prefix, not user fields with matching names later in a legacy layout.
        if (names.isNotEmpty() && names != INLINE_SYSTEM_NAMES.map { listOf(it, "BIGINT") }) {
            throw DucklakeInvalidOperationException("Unsupported final-1.1 physical inline metadata layout for $tableName")
        }
    }

    fun requireProjection(source: List<DucklakeColumn>, requested: List<DucklakeColumn>, sourceTypes: Map<Long, String>) {
        val byId = source.filter { it.parentColumn == null }.associateBy { it.columnId }
        for (column in requested) {
            val original = byId[column.columnId]
            if (original == null) {
                requireAbsentColumnProjection(column)
                continue // A genuinely absent, no-default field is SQL NULL; never replace a stored NULL.
            }
            if (original.columnName.lowercase() in INLINE_SYSTEM_NAMES) {
                throw DucklakeInvalidOperationException("Final-1.1 inline payload column uses a reserved metadata name")
            }
            if (sourceTypes[column.columnId] != column.columnType) {
                throw DucklakeInvalidOperationException("Final-1.1 type-evolution projection is not qualified for column ${column.columnId}")
            }
            requireInlineType(InlinedValues.typeTree(original, source))
        }
    }

    private fun requireAbsentColumnProjection(column: DucklakeColumn) {
        if (column.initialDefault != null && !column.initialDefault.trim().equals("NULL", ignoreCase = true)) {
            throw DucklakeInvalidOperationException("Final-1.1 initial-default projection is not qualified for column ${column.columnId}")
        }
    }

    private fun requireInlineType(node: InlinedTypeNode) {
        val container = node.type in setOf("struct", "list", "map")
        if (!container && node.type !in SCALARS && !node.type.startsWith("decimal(")) {
            throw DucklakeInvalidOperationException("Final-1.1 inline type ${node.type} is not qualified by the conservative reader")
        }
        node.children.forEach(::requireInlineType)
    }
}
