package dev.brikk.ducklake.catalog

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** Name admission in the owning write transaction; tables and views share a namespace. */
internal class CatalogNamespaces(private val ctx: DSLContext, private val snapshot: Long) {
    fun requireSchemaAvailable(name: String) {
        if (exists("ducklake_schema", "schema_name", name, DSL.trueCondition())) {
            throw DucklakeEntityAlreadyExistsException("schema", name)
        }
    }

    fun requireRelationAvailable(schemaId: Long, name: String) {
        val scope = number("schema_id").eq(schemaId)
        if (exists("ducklake_table", "table_name", name, scope) || exists("ducklake_view", "view_name", name, scope)) {
            throw DucklakeEntityAlreadyExistsException("relation", name)
        }
    }

    fun requireColumnAvailable(tableId: Long, parent: Long?, name: String, except: Long? = null, displayName: String = name) {
        val scope = number("table_id").eq(tableId)
            .and(parent?.let { number("parent_column").eq(it) } ?: number("parent_column").isNull)
            .and(except?.let { number("column_id").ne(it) } ?: DSL.trueCondition())
        if (exists("ducklake_column", "column_name", name, scope)) {
            throw DucklakeEntityAlreadyExistsException(if (parent == null) "column" else "field", displayName)
        }
    }

    private fun exists(table: String, nameColumn: String, name: String, scope: Condition): Boolean =
        ctx.selectOne().from(DSL.table(DSL.name(table))).where(scope)
            .and(CatalogIdentifiers.matches(ctx, DSL.field(DSL.name(nameColumn), String::class.java), name))
            .and(number("begin_snapshot").le(snapshot))
            .and(number("end_snapshot").isNull.or(number("end_snapshot").gt(snapshot)))
            .limit(1).fetchOne() != null

    private fun number(name: String) = DSL.field(DSL.name(name), Long::class.javaObjectType)

    companion object {
        fun findColumn(columns: List<DucklakeColumn>, parent: Long?, name: String): DucklakeColumn? {
            val key = Final11InlineTypes.identifierKey(name)
            val matches = columns.filter { it.parentColumn == parent && Final11InlineTypes.identifierKey(it.columnName) == key }
            if (matches.size > 1) throw DucklakeCatalogCorruptionException("Ambiguous field name $name")
            return matches.singleOrNull()
        }

        fun requireDistinctColumns(columns: List<TableColumnSpec>, nested: Boolean = false) {
            val names = hashSetOf<String>()
            for (column in columns) {
                if (!names.add(Final11InlineTypes.identifierKey(column.name))) {
                    throw DucklakeEntityAlreadyExistsException(if (nested) "field" else "column", column.name)
                }
                requireDistinctColumns(column.children, nested = true)
            }
        }
    }
}
