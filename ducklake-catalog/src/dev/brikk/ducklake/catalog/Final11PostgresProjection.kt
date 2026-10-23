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
import org.jooq.impl.DSL

/** Validate native physical storage, then evolve decoded values by stable field identity. */
internal class Final11PostgresProjection(
    private val ctx: DSLContext, private val source: Final11InlineSchema, private val target: Final11InlineSchema,
) {
    fun plan(table: String, ids: List<Long>): Map<Long, Final11InlineProjection.Projection> = ids.distinct().associateWith { projection(table, it) }

    private fun projection(table: String, id: Long): Final11InlineProjection.Projection {
        val destination = target.roots[id] ?: invalid("unknown requested field $id")
        supportedType(destination.type)
        val origin = source.roots[id]
        if (origin == null && id in source.byId) invalid("field identity moved between parents")
        if (origin != null) supportedType(origin.type)
        val plan = Final11PostgresEvolution(ctx, source).plan(origin, destination)
        val storage = origin?.let { physical(ctx, table, it) }
        val field = if (origin == null) DSL.inline(null as Any?) else selectValue(origin, storage!!)
        val values = Values(origin, destination, plan)
        return Final11InlineProjection.Projection(field, destination.type, Final11InlineTypes.requiresText(destination.type), values::apply)
    }

    private class Values(
        private val origin: Final11InlineSchema.Node?, private val destination: Final11InlineSchema.Node,
        private val plan: Final11PostgresEvolution.Plan,
    ) {
        fun apply(raw: Any?, decode: Boolean): Any? {
            val old = origin?.let { Final11PostgresValues.decode(raw, it.type) }
            val value = plan.apply(old)
            return when {
                decode -> value
                value == null -> null
                plan.unchanged -> raw
                destination.type.type in TEXT || Final11InlineTypes.requiresText(destination.type) -> Final11PostgresText().encode(value, destination.type)
                else -> storageValue(value, destination.type)
            }
        }
    }

    private fun selectValue(origin: Final11InlineSchema.Node, storage: String): Field<*> {
        val raw = DSL.field(DSL.quotedName(origin.column.columnName))
        return when {
            storage == "bytea" || storage == "character varying" ->
                DSL.field("substring({0} from 1 for {1})", raw, DSL.inline(Final11PostgresValues.MAX_BYTES + 1))
            Final11InlineTypes.requiresText(origin.type) -> DSL.cast(raw, org.jooq.impl.SQLDataType.VARCHAR)
            else -> raw
        }
    }

    private fun physical(ctx: DSLContext, table: String, origin: Final11InlineSchema.Node): String {
        val columns = DSL.table(DSL.sql("information_schema.columns")).`as`("p")
        fun text(name: String) = DSL.field(DSL.name("p", name), String::class.java)
        fun number(name: String) = DSL.field(DSL.name("p", name), Int::class.javaObjectType)
        val rows = ctx.select(text("data_type"), number("numeric_precision"), number("numeric_scale"), number("datetime_precision"),
            number("character_maximum_length"), text("domain_name")).from(columns)
            .where(text("table_catalog").eq(DSL.field("current_database()", String::class.java)))
            .and(text("table_schema").eq(DSL.field("current_schema()", String::class.java)))
            .and(text("table_name").eq(table)).and(text("column_name").eq(origin.column.columnName))
            .and(number("ordinal_position").ge(4)).limit(2).fetch()
        val row = rows.singleOrNull() ?: invalid("missing/ambiguous physical payload column ${origin.column.columnName}")
        val expected = storageType(origin.type.type)
        if (row.value6() != null) invalid("domain-typed PostgreSQL inline storage is not qualified")
        if (row.value1() != expected) invalid("mismatched PostgreSQL physical payload type for ${origin.column.columnName}")
        if (expected == "character varying" && row.value5() != null) invalid("bounded VARCHAR is not native PostgreSQL inline storage")
        if (expected in setOf("time without time zone", "time with time zone", "interval") && row.value4() != 6) invalid("temporal storage precision mismatch")
        if (expected == "numeric") {
            val decimal = Regex("decimal\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*\\)").matchEntire(origin.type.type)!!
            if (row.value2() != decimal.groupValues[1].toInt() || row.value3() != decimal.groupValues[2].toInt()) {
                invalid("decimal storage precision/scale mismatch")
            }
        }
        return expected
    }

    private fun supportedType(type: InlinedTypeNode) {
        Final11InlineTypes.validate(type)
        if (Final11InlineTypes.identifierKey(type.name).startsWith("_ducklake_")) invalid("reserved metadata payload name")
        fun visit(node: InlinedTypeNode) {
            if (node.type == "map" && node.children[0].type in setOf("struct", "list", "map")) {
                invalid("composite MAP keys have no qualified Java equality contract")
            }
            node.children.forEach(::visit)
        }
        visit(type)
    }

    companion object {
        private val TEXT = setOf("struct", "list", "map", "date", "time_ns", "timestamp", "timestamp_us", "timestamp_s", "timestamp_ms",
            "timestamp_ns", "timestamptz", "timestamptz_ns", "uint64", "int128", "uint128")
        private fun storageType(type: String): String = when {
            type in TEXT -> "character varying"
            type in setOf("varchar", "blob", "json") -> "bytea"
            type in setOf("int8", "int16") -> "smallint"
            type in setOf("int32", "uint8", "uint16", "null") -> "integer"
            type in setOf("int64", "uint32") -> "bigint"
            type == "boolean" -> "boolean"
            type == "float32" -> "real"
            type == "float64" -> "double precision"
            type.startsWith("decimal") -> "numeric"
            type == "time" -> "time without time zone"
            type == "timetz" -> "time with time zone"
            else -> type
        }
        private fun storageValue(value: Any?, type: InlinedTypeNode): Any? = when {
            value == null -> null
            type.type in setOf("varchar", "json") -> (value as String).toByteArray(Charsets.UTF_8)
            type.type in setOf("int8", "int16") -> (value as Number).toShort()
            type.type in setOf("int32", "uint8", "uint16") -> (value as Number).toInt()
            type.type in setOf("int128", "uint128", "uint64") -> value.toString()
            else -> value
        }
        private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 PostgreSQL $message")
    }
}
