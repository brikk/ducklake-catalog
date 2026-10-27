package dev.brikk.ducklake.catalog

import org.jooq.Param
import org.jooq.SQLDialect
import org.jooq.Select
import org.jooq.TableField
import org.jooq.VisitListener
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration

/** Rewrite parsed column references, never function/type names or literal text. No SQL is executed. */
internal object CatalogSortExpressions {
    const val MAX_TEXT = 16384
    data class Rewrite(val expression: String, val referenced: Boolean)

    fun rename(expression: String, dialect: String, oldName: String, newName: String): Rewrite {
        requireQualified(expression, dialect)
        val config = DefaultConfiguration().set(SQLDialect.DUCKDB).set(Settings()
            .withParseDialect(SQLDialect.DUCKDB).withRenderQuotedNames(RenderQuotedNames.ALWAYS))
        val key = Final11InlineTypes.identifierKey(oldName)
        var referenced = false
        var nodes = 0
        try {
            val field = DSL.using(config).parser().parseField(expression)
            config.set(VisitListener.onVisitStart { visit ->
                if (++nodes > 8192) invalid("Sort expression exceeds the bounded rewrite traversal")
                when (val part = visit.queryPart()) {
                    is Select<*> -> invalid("Subqueries are not qualified for sort-expression rewriting")
                    is Param<*> -> if (!part.isInline) invalid("Bind parameters are not stored sort expressions")
                    is TableField<*, *> -> {
                        if (part.qualifiedName.name.size != 1) invalid("Qualified sort column references are not supported")
                        if (Final11InlineTypes.identifierKey(part.name) == key) {
                            referenced = true
                            visit.queryPart(DSL.field(DSL.quotedName(newName), part.dataType))
                        }
                    }
                }
            })
            val rendered = DSL.using(config).renderInlined(field)
            if (rendered.length > MAX_TEXT) invalid("Renamed sort expression exceeds the text budget")
            return Rewrite(if (referenced) rendered else expression, referenced)
        } catch (e: DataAccessException) {
            throw DucklakeInvalidOperationException("Cannot safely parse/rewrite the stored DuckDB sort expression")
                .also { it.initCause(e) }
        }
    }

    private fun requireQualified(expression: String, dialect: String) {
        if (!dialect.equals("duckdb", ignoreCase = true)) invalid("Unsupported sort expression dialect: $dialect")
        if (expression.length > MAX_TEXT || expression.count { it in "([{" } > 128) {
            invalid("Sort expression exceeds the bounded rewrite complexity")
        }
    }

    private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException(message)
}
