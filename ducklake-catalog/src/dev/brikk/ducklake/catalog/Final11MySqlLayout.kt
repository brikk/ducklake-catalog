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
import org.jooq.impl.DSL

/** Existing native MySQL metadata only: no schema creation, migration or guessed boolean/charset facts. */
internal object Final11MySqlLayout {
    fun requireSession(ctx: DSLContext) {
        val row = ctx.fetch("SELECT @@session.time_zone,@@character_set_connection,@@transaction_isolation,@@transaction_read_only").single()
        if (row.get(0, String::class.java) !in setOf("+00:00", "UTC") || row.get(1, String::class.java) != "utf8mb4") {
            invalid("reads require a UTC/utf8mb4 Connector/J session")
        }
        if (row.get(2, String::class.java) != "REPEATABLE-READ" || row.get(3, Int::class.javaObjectType) != 1) {
            invalid("reads require server-enforced read-only REPEATABLE READ")
        }
    }

    fun validate(ctx: DSLContext, required: Map<String, Map<String, String>>) {
        val tables = ctx.fetch("SELECT table_name,engine FROM information_schema.tables WHERE table_schema=binary database() " +
            "AND binary left(table_name,9)=binary 'ducklake_' AND NOT regexp_like(table_name," +
            "'^ducklake_inlined_(data_[0-9]+_[0-9]+|delete_[0-9]+)$','c') LIMIT 257")
        val names = tables.map { it.get(0, String::class.java) }.toSet()
        if (tables.size > 256 || !names.containsAll(required.keys) || tables.any { it.get(1, String::class.java) != "InnoDB" }) {
            invalid("metadata must use InnoDB tables within the table budget")
        }
        val columns = ctx.fetch("SELECT table_name,column_name,column_type,character_set_name,datetime_precision " +
            "FROM information_schema.columns WHERE table_schema=binary database() AND binary table_name IN " +
            "(${required.keys.joinToString(",") { "'${it}'" }}) LIMIT 257")
        if (columns.size > 256) invalid("metadata column budget exceeded")
        columns.forEach { row ->
            val table = row.get(0, String::class.java)
            val name = row.get(1, String::class.java)
            val expected = required[table]?.get(name) ?: return@forEach
            val actual = row.get(2, String::class.java)
            val valid = validColumn(expected, actual, row.get(3, String::class.java), row.get(4, Int::class.javaObjectType))
            if (!valid) invalid("unsupported physical metadata column $table.$name ($actual)")
            if (expected == "BOOLEAN") requireBooleanDomain(ctx, table, name)
            if (expected == "UUID") requireUuidDomain(ctx, table, name)
        }
    }

    private fun validColumn(expected: String, actual: String?, charset: String?, precision: Int?): Boolean = when (expected) {
        "BIGINT" -> actual == "bigint"
        "BOOLEAN" -> actual == "tinyint(1)"
        "VARCHAR", "UUID" -> actual == "text" && charset == "utf8mb4"
        "TIMESTAMP WITH TIME ZONE" -> actual == "datetime" && precision == 0
        else -> false
    }

    fun inlinePrefix(ctx: DSLContext, table: String) {
        val rows = ctx.fetch("SELECT column_type FROM information_schema.columns WHERE table_schema=binary database() " +
            "AND binary table_name=binary ? AND ordinal_position<=3 ORDER BY ordinal_position LIMIT 3", table)
        if (rows.size != 3 || rows.any { it.get(0, String::class.java) != "bigint" }) invalid("inline identity prefix requires signed BIGINT")
        if (!dynamicTableExists(ctx, table)) invalid("missing inline storage")
    }

    fun dynamicTableExists(ctx: DSLContext, table: String): Boolean {
        val engine = ctx.fetch("SELECT engine FROM information_schema.tables WHERE table_schema=binary database() " +
            "AND binary table_name=binary ? LIMIT 2", table)
        if (engine.isEmpty()) return false
        if (engine.size != 1 || engine.single().get(0, String::class.java) != "InnoDB") invalid("inline storage must use InnoDB")
        return true
    }

    private fun requireBooleanDomain(ctx: DSLContext, table: String, column: String) {
        val flag = DSL.field(DSL.quotedName(column), Int::class.javaObjectType)
        if (ctx.fetchExists(ctx.selectOne().from(DSL.table(DSL.quotedName(table))).where(flag.isNotNull.and(flag.notIn(0, 1))).limit(1))) {
            throw DucklakeCatalogCorruptionException("MySQL metadata boolean outside 0/1 domain: $table.$column")
        }
    }
    private fun requireUuidDomain(ctx: DSLContext, table: String, column: String) {
        val value = DSL.field(DSL.quotedName(column), String::class.java)
        val valid = DSL.condition("regexp_like({0},{1},'c')", value,
            DSL.`val`("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
        if (ctx.fetchExists(ctx.selectOne().from(DSL.table(DSL.quotedName(table)))
                .where(value.isNull.or(DSL.length(value).ne(36)).or(valid.not())).limit(1))) {
            throw DucklakeCatalogCorruptionException("MySQL metadata UUID is not canonical: $table.$column")
        }
    }
    private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 MySQL $message")
}
