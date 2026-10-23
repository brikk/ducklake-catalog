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

/** Lossless scalar forms actually emitted by the maintained native MySQL backend. */
internal class Final11MySqlProjection(private val ctx: DSLContext, private val source: Final11InlineSchema, private val target: Final11InlineSchema) {
    fun plan(table: String, ids: List<Long>): Map<Long, Final11InlineProjection.Projection> = ids.distinct().associateWith { id ->
        val destination = target.roots[id] ?: invalid("unknown requested field $id")
        supported(destination.type)
        val origin = source.roots[id] ?: invalid("missing-column/schema evolution has no qualified native MySQL path")
        supported(origin.type)
        if (origin.type.type != destination.type.type || origin.column.columnName != destination.column.columnName) {
            invalid("scalar schema evolution has no qualified native MySQL path")
        }
        val storage = physical(table, origin)
        val field = select(origin, storage)
        val values = Values(origin, storage)
        Final11InlineProjection.Projection(field, destination.type, Final11InlineTypes.requiresText(destination.type), values::apply)
    }

    private class Values(private val origin: Final11InlineSchema.Node, private val storage: String) {
        fun apply(raw: Any?, decoded: Boolean): Any? {
            val physical = if (storage == "text") Final11PostgresValues.decode(raw, InlinedTypeNode("varchar", "utf8")) else raw
            if (origin.type.type == "boolean" && physical != null && physical !in listOf(0L, 1L)) {
                throw DucklakeCatalogCorruptionException("MySQL inline boolean outside 0/1 domain")
            }
            val value = Final11PostgresValues.decode(physical, origin.type)
            if (nonfinite(value)) invalid("non-finite floating inline storage is not qualified")
            return if (decoded || origin.type.type == "float32") value else physical
        }
    }

    private fun select(origin: Final11InlineSchema.Node, storage: String): Field<*> {
        val field = DSL.field(DSL.quotedName(origin.column.columnName))
        return when {
            storage in setOf("text", "blob") -> DSL.field("substring(cast({0} as binary),1,{1})", field, DSL.inline(Final11PostgresValues.MAX_BYTES + 1))
            origin.type.type == "boolean" -> DSL.field("cast({0} as signed)", Long::class.javaObjectType, field)
            origin.type.type == "float32" -> DSL.field("cast({0} as double)", Double::class.javaObjectType, field)
            origin.type.type in setOf("date", "timestamp_s") -> DSL.field("cast({0} as char)", String::class.java, field)
            else -> field
        }
    }

    private fun physical(table: String, origin: Final11InlineSchema.Node): String {
        val row = ctx.fetch("SELECT column_type,character_set_name,datetime_precision FROM information_schema.columns " +
            "WHERE table_schema=binary database() AND binary table_name=binary ? AND binary column_name=binary ? AND ordinal_position>=4 LIMIT 2",
            table, origin.column.columnName)
            .singleOrNull() ?: invalid("missing/ambiguous physical payload column")
        val expected = storage(origin.type.type)
        if (row.get(0, String::class.java) != expected) invalid("physical payload mismatch for ${origin.column.columnName}: expected $expected")
        if (expected == "text" && row.get(1, String::class.java) != "utf8mb4") invalid("inline text requires utf8mb4")
        if (expected == "datetime" && row.get(2, Int::class.javaObjectType) != 0) invalid("TIMESTAMP_S storage precision mismatch")
        return expected
    }

    companion object {
        private fun nonfinite(value: Any?): Boolean = when (value) {
            is Float -> !value.isFinite()
            is Double -> !value.isFinite()
            else -> false
        }
        private val TEXT = setOf("varchar", "uuid", "uint128", "time", "time_ns", "timetz", "interval", "null", "timestamptz_ns")
        private val TYPES = setOf("boolean", "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64", "float32", "float64",
            "blob", "date", "timestamp_s") + TEXT
        private val PHYSICAL = mapOf("boolean" to "tinyint(1)", "int8" to "tinyint", "int16" to "smallint", "int32" to "int", "int64" to "bigint",
            "uint8" to "tinyint unsigned", "uint16" to "smallint unsigned", "uint32" to "int unsigned", "uint64" to "bigint unsigned",
            "float32" to "float", "float64" to "double", "timestamp_s" to "datetime")
        fun requireTargetTypes(target: Final11InlineSchema, ids: List<Long>) {
            ids.distinct().forEach { supported(target.roots[it]?.type ?: invalid("unknown requested field $it")) }
        }
        private fun supported(type: InlinedTypeNode) {
            Final11InlineTypes.validate(type)
            if (type.type !in TYPES && !type.type.startsWith("decimal")) invalid("inline type ${type.type} has no qualified lossless native MySQL storage")
            if (Final11InlineTypes.identifierKey(type.name).startsWith("_ducklake_")) invalid("reserved metadata payload name")
        }
        private fun storage(type: String): String = when {
            type in TEXT -> "text"
            type.startsWith("decimal") -> type.replace(" ", "")
            else -> PHYSICAL[type] ?: type
        }
        private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 MySQL $message")
    }
}
