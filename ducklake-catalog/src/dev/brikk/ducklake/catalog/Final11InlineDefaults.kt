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

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_COLUMN
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.Locale

/** Shared metadata-only initial-default validation. Default text is never executable SQL. */
internal object Final11InlineDefaults {
    fun value(ctx: DSLContext, destination: Final11InlineSchema.Node): String? {
        Final11InlineTypes.validate(destination.type)
        val value = destination.column.initialDefault
        if (value != null && destination.type.type == "null") invalid("NULL-typed field has a non-NULL initial default")
        if (value != null && destination.type.type in setOf("struct", "list", "map")) invalid("initial default for nested column is not qualified")
        val col = DUCKLAKE_COLUMN
        val first = ctx.select(DSL.substring(col.DEFAULT_VALUE_TYPE, 1, 33)).from(col)
            .where(col.TABLE_ID.eq(destination.column.tableId)).and(col.COLUMN_ID.eq(destination.column.columnId))
            .orderBy(col.BEGIN_SNAPSHOT).limit(1).fetchOne()
            ?: throw DucklakeCatalogCorruptionException("Missing final-1.1 initial-default history for column ${destination.column.columnId}")
        val kind = first.value1()?.lowercase(Locale.ROOT)
        if (value == null && kind == "expression") invalid("non-literal initial-default history cannot be projected safely")
        if (value == null && kind !in setOf(null, "", "literal")) invalid("unknown initial-default history kind")
        return value
    }

    private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 $message")
}
