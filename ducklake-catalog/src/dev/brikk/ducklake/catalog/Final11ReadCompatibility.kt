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

    fun requireReadableLayout(ctx: DSLContext, version: String?) {
        if (version in setOf("0.4", "1.0")) return
        if (version != "1.1") {
            throw DucklakeInvalidOperationException("Opt-in final-1.1 reader cannot qualify catalog version $version")
        }
        if (ctx.dialect().family() == org.jooq.SQLDialect.POSTGRES) {
            Final11ReadMetadataQueries.observedLayout(ctx, Final11ReadRoute.POSTGRES)
        }
        if (ctx.dialect().family() == org.jooq.SQLDialect.MYSQL) {
            Final11ReadMetadataQueries.observedLayout(ctx, Final11ReadRoute.MYSQL)
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
            .from(columns).where(text("table_catalog").eq(Final11SqlBackend.catalog(ctx)))
            .and(text("table_schema").eq(Final11SqlBackend.schema(ctx))).and(text("table_name").eq(tableName))
            .orderBy(DSL.field(DSL.name("read_columns", "ordinal_position"))).limit(3)
            .fetch { listOf(it.value1(), it.value2()) }
        // Lazy/nonmaterialized optional tables remain absent. Existing tables must have the
        // actual metadata prefix, not user fields with matching names later in a legacy layout.
        val normalized = names.map { listOf(it[0], it[1]?.uppercase(java.util.Locale.ROOT)) }
        if (normalized.isNotEmpty() && normalized != INLINE_SYSTEM_NAMES.map { listOf(it, "BIGINT") }) {
            throw DucklakeInvalidOperationException("Unsupported final-1.1 physical inline metadata layout for $tableName")
        }
        if (normalized.isNotEmpty() && ctx.dialect().family() in setOf(org.jooq.SQLDialect.POSTGRES, org.jooq.SQLDialect.MYSQL)) {
            if (ctx.dialect().family() == org.jooq.SQLDialect.MYSQL) Final11MySqlLayout.inlinePrefix(ctx, tableName)
            fun id(name: String) = DSL.field(DSL.quotedName(name), Long::class.javaObjectType)
            val row = id(INLINE_SYSTEM_NAMES[0])
            val begin = id(INLINE_SYSTEM_NAMES[1])
            val end = id(INLINE_SYSTEM_NAMES[2])
            if (ctx.fetchExists(ctx.selectOne().from(DSL.table(DSL.quotedName(tableName)))
                    .where(row.isNull.or(row.lt(0L)).or(begin.isNull).or(begin.lt(0L)).or(end.lt(begin))).limit(1))) {
                throw DucklakeCatalogCorruptionException("Invalid SQL-backend inline row/snapshot identity")
            }
        }
    }

    fun projections(ctx: DSLContext, tableName: String, source: List<DucklakeColumn>, target: List<DucklakeColumn>, ids: List<Long>):
        Map<Long, Final11InlineProjection.Projection> =
        Final11InlineProjection(ctx, Final11InlineSchema(source), Final11InlineSchema(target)).plan(tableName, ids)
}
