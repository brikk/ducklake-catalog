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

import dev.brikk.ducklake.catalog.SnapshotRange.activeAt
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_COLUMN
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** Bounded logical comparison types at one schema snapshot, independent of physical history-row order. */
internal object CatalogColumnTypes {
    private const val PAGE_SIZE = 1024
    private const val MAX_COLUMNS = 65_536
    private const val MAX_TYPE_TEXT = 256

    fun load(ctx: DSLContext, tableId: Long, snapshotId: Long): Map<Long, String> {
        val col = DUCKLAKE_COLUMN.`as`("coltype")
        val result = linkedMapOf<Long, String>()
        var after: Long? = null
        var more = true
        while (more) {
            val rows = ctx.select(col.COLUMN_ID, DSL.substring(col.COLUMN_TYPE, 1, MAX_TYPE_TEXT + 1))
                .from(col).where(col.TABLE_ID.eq(tableId)).and(activeAt(col, snapshotId))
                .and(after?.let { col.COLUMN_ID.gt(it) } ?: DSL.noCondition())
                .orderBy(col.COLUMN_ID.asc().nullsFirst()).limit(PAGE_SIZE + 1).fetch()
            val page = rows.map { row ->
                val id = row.get(0, Long::class.javaObjectType)
                val type = row.get(1, String::class.java)
                if (id == null || id < 0) corrupt("Missing/negative active column identity for table $tableId")
                if (type.isNullOrEmpty() || type.length > MAX_TYPE_TEXT) corrupt("Invalid active column type for column $id")
                id to type
            }
            // Lookahead catches an ambiguous identity even when it straddles a page boundary.
            if (page.zipWithNext().any { (left, right) -> left.first >= right.first }) {
                corrupt("Duplicate or unordered active column identity for table $tableId")
            }
            val batch = page.take(PAGE_SIZE)
            if (result.size + batch.size > MAX_COLUMNS) {
                throw DucklakeInvalidOperationException("Column-type traversal budget exceeded for table $tableId")
            }
            batch.forEach { (id, type) -> result[id] = type }
            after = batch.lastOrNull()?.first
            more = page.size > PAGE_SIZE
        }
        return result
    }

    private fun corrupt(message: String): Nothing = throw DucklakeCatalogCorruptionException(message)
}
