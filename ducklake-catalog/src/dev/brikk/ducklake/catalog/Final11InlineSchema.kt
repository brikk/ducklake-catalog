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

/** Snapshot-local field identities, independent of names and physical STRUCT ordering. */
internal class Final11InlineSchema(columns: List<DucklakeColumn>) {
    data class Node(val column: DucklakeColumn, val children: List<Node>) {
        val type: InlinedTypeNode = InlinedTypeNode(column.columnType.trim().lowercase(Locale.ROOT), column.columnName, children.map { it.type })
    }

    val roots: Map<Long, Node>
    val byId: Map<Long, Node>

    init {
        if (columns.size > Final11InlineTypes.MAX_NODES) corrupt("column count exceeds the qualification budget")
        val columnsById = columns.associateBy { it.columnId }
        if (columnsById.size != columns.size) corrupt("duplicate column IDs")
        if (columns.map { it.tableId }.distinct().size > 1) corrupt("columns from multiple tables")
        columns.forEach { if (it.parentColumn != null && it.parentColumn !in columnsById) corrupt("missing column parent ${it.parentColumn}") }
        val children = columns.groupBy { it.parentColumn }
        if (children[null].orEmpty().map { Final11InlineTypes.identifierKey(it.columnName) }.distinct().size != children[null].orEmpty().size) {
            corrupt("ambiguous top-level names")
        }
        val nodes = mutableMapOf<Long, Node>()
        val visiting = mutableSetOf<Long>()
        fun build(column: DucklakeColumn, depth: Int): Node {
            if (depth > Final11InlineTypes.MAX_DEPTH) corrupt("column depth exceeds the qualification budget")
            if (!visiting.add(column.columnId)) corrupt("cycle in column parentage")
            val nested = children[column.columnId].orEmpty().sortedWith(compareBy({ it.columnOrder }, { it.columnId }))
            if (nested.map { Final11InlineTypes.identifierKey(it.columnName) }.distinct().size != nested.size) corrupt("ambiguous sibling names")
            val node = Node(column, nested.map { build(it, depth + 1) })
            nodes[column.columnId] = node
            visiting.remove(column.columnId)
            return node
        }
        roots = children[null].orEmpty().associate { it.columnId to build(it, 0) }
        if (nodes.size != columns.size) corrupt("cycle in column parentage")
        byId = nodes
    }

    private fun corrupt(detail: String): Nothing = throw DucklakeCatalogCorruptionException("Invalid final-1.1 inline schema: $detail")
}
