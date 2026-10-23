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
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate

/** Compile stable-ID plans once; validate the whole source before applying any projection/default. */
internal class Final11PostgresEvolution(private val ctx: DSLContext, private val source: Final11InlineSchema) {
    class Plan(val unchanged: Boolean, private val evaluate: (Any?, Budget) -> Any?) {
        fun apply(value: Any?): Any? = nested(value, Budget())
        fun nested(value: Any?, budget: Budget): Any? { budget.spend(); return evaluate(value, budget) }
    }
    class Budget {
        private var nodes = 0
        fun spend() { if (++nodes > 65536) invalid("evolved inline value node budget exceeded") }
    }

    fun plan(origin: Final11InlineSchema.Node?, target: Final11InlineSchema.Node): Plan {
        if (origin == null) {
            if (target.column.columnId in source.byId) invalid("field identity moved between parents")
            val initial = Final11InlineDefaults.value(ctx, target)
            val value = Final11PostgresValues.decode(initial, target.type)
            return Plan(false) { _, _ -> if (value is ByteArray) value.copyOf() else value }
        }
        val from = origin.type.type
        val to = target.type.type
        if (from in CONTAINERS || to in CONTAINERS) {
            if (from != to) invalid("container-kind conversion $from to $to is not qualified")
            return if (from == "struct") structure(origin, target) else collection(origin, target)
        }
        Final11InlineTypes.requireConversion(from, to)
        if (from == "boolean" && (to.startsWith("decimal") || to in setOf("float32", "float64"))) {
            invalid("scalar conversion boolean to $to is native-rejected")
        }
        return Plan(from == to) { value, _ -> if (value == null || from == to) value else scalar(value, from, target.type) }
    }

    private fun structure(origin: Final11InlineSchema.Node, target: Final11InlineSchema.Node): Plan {
        val old = origin.children.associateBy { it.column.columnId }
        val fields = target.children.map { child -> Triple(child, old[child.column.columnId], plan(old[child.column.columnId], child)) }
        val unchanged = origin.children.map { it.column.columnId } == target.children.map { it.column.columnId } &&
            fields.all { (next, previous, transform) -> transform.unchanged && next.column.columnName == previous?.column?.columnName }
        return Plan(unchanged) { value, budget ->
            if (value == null) null else {
                val input = value as Map<*, *>
                fields.associateTo(linkedMapOf()) { (next, previous, transform) ->
                    next.column.columnName to transform.nested(previous?.let { input[it.column.columnName] }, budget)
                }
            }
        }
    }

    private fun collection(origin: Final11InlineSchema.Node, target: Final11InlineSchema.Node): Plan {
        origin.children.zip(target.children).forEach { (a, b) ->
            if (a.column.columnId != b.column.columnId) invalid("list/map element identity changed")
        }
        val children = origin.children.zip(target.children).map { (a, b) -> plan(a, b) }
        return Plan(children.all { it.unchanged }) { value, budget ->
            when {
                value == null -> null
                origin.type.type == "list" -> (value as List<*>).map { children.single().nested(it, budget) }
                else -> linkedMapOf<Any, Any?>().also { out ->
                    val identities = hashSetOf<Any>()
                    (value as Map<*, *>).forEach { (key, item) ->
                        val converted = children[0].nested(key, budget) ?: corrupt("NULL evolved MAP key")
                        if (!identities.add(Final11PostgresValues.keyIdentity(converted))) corrupt("duplicate evolved MAP key")
                        out[converted] = children[1].nested(item, budget)
                    }
                }
            }
        }
    }

    companion object {
        private val CONTAINERS = setOf("struct", "list", "map")
        private fun scalar(value: Any, source: String, target: InlinedTypeNode): Any? {
            val numeric = converted(value, source, target)
            if (infinite(numeric) && !infinite(value)) invalid("finite numeric conversion exceeds the target floating domain")
            return try { Final11PostgresValues.decode(numeric, target) }
            catch (e: DucklakeCatalogCorruptionException) {
                throw DucklakeInvalidOperationException("PostgreSQL value cannot convert within the qualified target domain ${target.type}")
                    .also { it.initCause(e) }
            }
        }
        private fun infinite(value: Any): Boolean = when (value) {
            is Float -> value.isInfinite()
            is Double -> value.isInfinite()
            else -> false
        }
        private fun converted(value: Any, source: String, target: InlinedTypeNode): Any = when {
            target.type in setOf("float32", "float64") -> Final11PostgresNumericCasts.floating(number(value), source, target.type == "float32")
            target.type.startsWith("decimal") -> if (value is BigDecimal) value else BigDecimal(value.toString())
            target.type.startsWith("timestamp") -> if (value is LocalDate) value.atStartOfDay() else value
            value is Boolean -> if (value) BigInteger.ONE else BigInteger.ZERO
            else -> BigInteger(value.toString())
        }
        private fun number(value: Any): Number = if (value is Boolean) (if (value) 1 else 0) else value as Number
        private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 PostgreSQL $message")
        private fun corrupt(message: String): Nothing = throw DucklakeCatalogCorruptionException("Final-1.1 PostgreSQL $message")
    }
}
