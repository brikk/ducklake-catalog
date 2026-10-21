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

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.QueryPart
import org.jooq.impl.DSL
import java.util.Locale
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_COLUMN

/** Pure read expressions: identity mapping first, strict casts only after physical validation. */
internal class Final11InlineProjection(
    private val ctx: DSLContext, private val source: Final11InlineSchema, private val target: Final11InlineSchema,
) {
    data class Projection(val field: Field<*>, val type: InlinedTypeNode)
    private var variable = 0

    fun plan(tableName: String, ids: List<Long>): Map<Long, Projection> = ids.distinct().associateWith { id ->
        val destination = target.roots[id] ?: throw DucklakeInvalidOperationException("Unknown final-1.1 requested column $id")
        if (Final11InlineTypes.identifierKey(destination.column.columnName).startsWith("_ducklake_")) {
            invalid("inline payload column uses a reserved metadata name")
        }
        val origin = source.roots[id]
        if (origin != null) {
            if (Final11InlineTypes.identifierKey(origin.column.columnName).startsWith("_ducklake_")) {
                invalid("inline payload column uses a reserved metadata name")
            }
            Final11InlineTypes.validate(origin.type)
            Final11InlineTypes.validate(destination.type)
            requirePhysical(ctx, tableName, origin)
        }
        val field = evolve(origin, destination, origin?.let { DSL.field(DSL.quotedName(it.column.columnName)) })
        if (ctx.render(field).length > MAX_PROJECTION_LENGTH) invalid("inline projection exceeds the qualification budget")
        Projection(field, destination.type)
    }

    private fun evolve(origin: Final11InlineSchema.Node?, destination: Final11InlineSchema.Node, field: Field<*>?): Field<*> {
        if (origin == null) {
            if (destination.column.columnId in source.byId) invalid("field identity moved between parents")
            return missing(destination)
        }
        val value = requireNotNull(field)
        val oldType = origin.type.type
        val newType = destination.type.type
        if (oldType in CONTAINERS || newType in CONTAINERS) {
            if (oldType != newType) invalid("container-kind conversion $oldType to $newType is not qualified")
            return when (oldType) {
                "struct" -> structure(origin, destination, value)
                "list" -> list(origin, destination, value)
                else -> map(origin, destination, value)
            }
        }
        Final11InlineTypes.requireConversion(oldType, newType)
        return if (oldType == newType) value else cast(value, destination.type)
    }

    private fun missing(destination: Final11InlineSchema.Node): Field<*> {
        val value = destination.column.initialDefault
        // Whitelist non-NULL literals before any query/type text is used (also in no-connection tests).
        val result = if (value == null) DSL.inline(null as Any?) else {
            if (destination.type.type in CONTAINERS) invalid("initial default for nested column ${destination.column.columnId} is not qualified")
            cast(DSL.`val`(value), destination.type)
        }
        val col = DUCKLAKE_COLUMN
        val first = ctx.select(DSL.substring(col.DEFAULT_VALUE_TYPE, 1, 33)).from(col)
            .where(col.TABLE_ID.eq(destination.column.tableId)).and(col.COLUMN_ID.eq(destination.column.columnId))
            .orderBy(col.BEGIN_SNAPSHOT).limit(1).fetchOne()
            ?: throw DucklakeCatalogCorruptionException("Missing final-1.1 initial-default history for column ${destination.column.columnId}")
        val kind = first.value1()?.lowercase(Locale.ROOT)
        if (value == null && kind == "expression") {
            invalid("non-literal initial-default history for field ${destination.column.columnId} cannot be projected safely")
        }
        if (value == null && kind !in setOf(null, "", "literal")) invalid("unknown initial-default history kind for field ${destination.column.columnId}")
        return result
    }

    private fun structure(origin: Final11InlineSchema.Node, destination: Final11InlineSchema.Node, field: Field<*>): Field<*> {
        val old = origin.children.associateBy { it.column.columnId }
        val arguments = mutableListOf<QueryPart>()
        val members = destination.children.map { child ->
            val previous = old[child.column.columnId]
            val nested = previous?.let { extract(field, it.column.columnName) }
            val offset = arguments.size
            arguments.add(DSL.quotedName(child.column.columnName))
            arguments.add(evolve(previous, child, nested))
            "{$offset} := {${offset + 1}}"
        }
        val packed = DSL.field("struct_pack(${members.joinToString(", ")})", *arguments.toTypedArray())
        return cast(DSL.field("CASE WHEN {0} IS NULL THEN NULL ELSE {1} END", field, packed), destination.type)
    }

    private fun list(origin: Final11InlineSchema.Node, destination: Final11InlineSchema.Node, field: Field<*>): Field<*> {
        val old = origin.children.single()
        val child = destination.children.single()
        requireRole(old, child)
        val name = "_catalog_read_element_${variable++}"
        val converted = evolve(old, child, DSL.field(DSL.quotedName(name)))
        return cast(DSL.field("list_transform({0}, lambda {1}: {2})", field, DSL.quotedName(name), converted), destination.type)
    }

    private fun map(origin: Final11InlineSchema.Node, destination: Final11InlineSchema.Node, field: Field<*>): Field<*> {
        origin.children.zip(destination.children).forEach { (a, b) -> requireRole(a, b) }
        val name = "_catalog_read_entry_${variable++}"
        val entry = DSL.field(DSL.quotedName(name))
        val key = evolve(origin.children[0], destination.children[0], extract(entry, "key"))
        val value = evolve(origin.children[1], destination.children[1], extract(entry, "value"))
        val entries = DSL.field("list_transform(map_entries({0}), lambda {1}: struct_pack(key := {2}, value := {3}))",
            field, DSL.quotedName(name), key, value)
        return cast(DSL.field("map_from_entries({0})", entries), destination.type)
    }

    private fun requireRole(origin: Final11InlineSchema.Node, destination: Final11InlineSchema.Node) {
        if (origin.column.columnId != destination.column.columnId) invalid("list/map element identity changed")
    }

    private fun requirePhysical(ctx: DSLContext, tableName: String, origin: Final11InlineSchema.Node) {
        val columns = DSL.table(DSL.sql("information_schema.columns")).`as`("payload_columns")
        fun text(name: String) = DSL.field(DSL.name("payload_columns", name), String::class.java)
        val rows = ctx.select(DSL.substring(text("data_type"), 1, Final11InlineTypes.MAX_TYPE_LENGTH + 1))
            .from(columns).where(text("table_catalog").eq(DSL.field("current_database()", String::class.java)))
            .and(text("table_schema").eq(DSL.field("current_schema()", String::class.java)))
            .and(text("table_name").eq(tableName)).and(text("column_name").eq(origin.column.columnName))
            .and(DSL.field(DSL.name("payload_columns", "ordinal_position"), Int::class.javaObjectType).ge(4))
            .limit(2).fetch { it.value1() }
        if (rows.size != 1 || rows.single() == null || !Final11InlineTypes.samePhysical(origin.type,
                Final11InlineTypes.parsePhysical(requireNotNull(rows.single())))) {
            invalid("Unsupported final-1.1 physical payload type for column ${origin.column.columnName}")
        }
    }

    private fun extract(field: Field<*>, name: String): Field<*> = DSL.field("struct_extract({0}, {1})", field, DSL.inline(name))
    private fun cast(field: Field<*>, type: InlinedTypeNode): Field<*> =
        DSL.field("CAST({0} AS {1})", field, DSL.sql(Final11InlineTypes.physicalSql(type)))

    private fun invalid(detail: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 $detail")

    companion object {
        private val CONTAINERS = setOf("struct", "list", "map")
        private const val MAX_PROJECTION_LENGTH = 1_048_576
    }
}
