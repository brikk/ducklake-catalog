/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.ducklake.catalog

import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** SQL-bound physical cells followed by the same stable-ID/domain projection used by existing reads. */
internal class Final11InlinePage(
    private val projections: List<Final11InlineProjection.Projection>, source: Final11InlineSchema, ids: List<Long>,
    route: Final11ReadRoute, private val limit: Int,
) {
    private val cellLimit = minOf(Final11PostgresValues.MAX_BYTES, MAX_BYTES / ((limit + 1) * ids.size.coerceAtLeast(1)))
    val fields: List<Field<*>> = projections.mapIndexed { index, projection ->
        requirePageType(projection.type)
        val field = when (route) {
            Final11ReadRoute.POSTGRES -> Final11PostgresProjection.boundedPageField(projection.field, source.roots[ids[index]], cellLimit)
            Final11ReadRoute.MYSQL -> Final11MySqlProjection.boundedPageField(projection.field, source.roots[ids[index]], cellLimit)
            else -> DSL.substring(DSL.cast(projection.field, SQLDataType.VARCHAR), 1, cellLimit + 1)
        }
        field.`as`("page_c$index")
    }

    fun decode(rows: List<Record>): List<DucklakeInlinedChangeRow> {
        rows.forEach { row ->
            key(row)
            val end = row.get(2, Long::class.javaObjectType)
            if (end != null && end < key(row).beginSnapshot) corrupt("Inline row end precedes begin")
            for (index in projections.indices) {
                val raw = row.get(index + 3)
                val size = when (raw) { is String -> raw.length; is ByteArray -> raw.size; else -> 0 }
                if (size > cellLimit) invalid("Inline page cell exceeds its text/byte budget; reduce page size")
            }
        }
        if (rows.zipWithNext().any { (left, right) -> key(left) == key(right) }) corrupt("Duplicate inline row-version cursor")
        val budget = Budget()
        return rows.take(limit).map { row ->
            val identity = key(row)
            val values = projections.mapIndexed { index, projection ->
                val raw = row.get(index + 3)
                val transform = projection.transform
                val pageTransform = projection.pageTransform
                val decoded = when {
                    pageTransform != null -> pageTransform(raw, budget.remainingBytes)
                    transform != null -> transform(raw, true)
                    else -> Final11PostgresValues.decode(raw, projection.type)
                }
                budget.spend(decoded)
                decoded
            }
            budget.spend(identity.rowId)
            budget.spend(identity.beginSnapshot)
            val end = row.get(2, Long::class.javaObjectType)
            budget.spend(end)
            DucklakeInlinedChangeRow(identity.rowId, identity.beginSnapshot, end, values)
        }
    }

    private fun key(row: Record): DucklakeInlineCursor {
        val id = row.get(0, Long::class.javaObjectType) ?: corrupt("NULL inline row ID")
        val begin = row.get(1, Long::class.javaObjectType) ?: corrupt("NULL inline begin snapshot")
        if (id < 0 || begin < 0) corrupt("Negative inline identity")
        return DucklakeInlineCursor(id, begin)
    }

    private class Budget {
        private var nodes = 0
        private var bytes = 0L
        val remainingBytes: Int get() = (MAX_BYTES - bytes).toInt()
        fun spend(value: Any?, depth: Int = 0) {
            if (++nodes > 65536 || depth > 64) invalid("Decoded inline page exceeds its node/depth budget")
            bytes += when (value) { is String -> 4L * value.length; is ByteArray -> value.size.toLong(); else -> 32L }
            if (bytes > MAX_BYTES) invalid("Decoded inline page exceeds its logical byte budget; reduce page size")
            when (value) {
                is Map<*, *> -> value.forEach { (key, item) -> spend(key, depth + 1); spend(item, depth + 1) }
                is List<*> -> value.forEach { spend(it, depth + 1) }
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 16_777_216
        private fun requirePageType(type: InlinedTypeNode) {
            Final11InlineTypes.validate(type)
            fun visit(node: InlinedTypeNode) {
                if (node.type == "map" && node.children[0].type in setOf("struct", "list", "map")) {
                    invalid("Paged inline composite MAP keys have no qualified equality contract")
                }
                node.children.forEach(::visit)
            }
            visit(type)
        }
        private fun invalid(detail: String): Nothing = throw DucklakeInvalidOperationException(detail)
        private fun corrupt(detail: String): Nothing = throw DucklakeCatalogCorruptionException(detail)
    }
}
