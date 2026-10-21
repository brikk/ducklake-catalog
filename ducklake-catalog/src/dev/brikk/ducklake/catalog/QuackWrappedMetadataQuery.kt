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
import org.jooq.Query
import org.jooq.Record
import org.jooq.RecordMapper
import org.jooq.ResultQuery
import java.util.stream.Collectors

/**
 * [MetadataQuery] for the Quack catalog backend. Renders the caller's
 * jOOQ query to bind-inlined SQL and routes execution through
 * `CALL system.main.quack_query_by_name('<metadata_catalog>', '<inner_sql>')`.
 *
 * The inner SQL executes server-side as plain DuckDB SQL against base tables.
 * This originally avoided the 1.5 client's multi-scan restriction. Quack 2.0
 * also supports direct multi-scans, but retaining the wrapper keeps joins,
 * mutations and literal parameters on the same predictable server-side path.
 *
 * Mirrors the wrapper shape used by upstream's C++
 * `QuackMetadataManager::Query`
 * (in `vendor/ducklake/src/metadata_manager/quack_metadata_manager.cpp`).
 *
 * **Result-type fidelity:** `quack_query_by_name` is a polymorphic
 * table function whose output columns are resolved at JDBC prepare time via a
 * server PREPARE round trip — empirically confirmed: wrapping
 * `SELECT snapshot_id, schema_version FROM ducklake_snapshot WHERE ...`
 * produces a prepared statement that advertises
 * `(snapshot_id BIGINT, schema_version BIGINT)`. jOOQ's reflective
 * `Record.into(Class)` mapping picks the columns up by name into the
 * caller's generated record type without further coercion hints.
 */
internal class QuackWrappedMetadataQuery(val metadataCatalogName: String) : MetadataQuery {

    override fun <R : Record> fetchOne(dsl: DSLContext, query: ResultQuery<R>): R? {
        val wrapped = wrap(dsl.renderInlined(query))
        val row = dsl.fetchOne(wrapped) ?: return null
        return row.into(query.recordType)
    }

    override fun <R : Record> fetch(dsl: DSLContext, query: ResultQuery<R>): List<R> {
        val wrapped = wrap(dsl.renderInlined(query))
        val recordType: Class<out R> = query.recordType
        return dsl.fetch(wrapped)
                .stream()
                .map { row -> row.into(recordType) }
                .collect(Collectors.toList())
    }

    override fun <T> fetch(dsl: DSLContext, query: ResultQuery<*>, mapper: RecordMapper<in Record, T>): List<T> {
        val fields = query.fields()
        // Multi-table JOIN projections frequently have name collisions across
        // sides (e.g. `file.path` + `delfile.path`). The wrapper's polymorphic
        // output is forced to flat column names — duplicates throw
        // "Binder Error: table quack_query_by_name has duplicate column name".
        // Sidestep it by wrapping the inner SQL in a derived-table SELECT *
        // with positional aliases (c0, c1, …), then coerce back to the
        // original Field<?>[] (coerce is positional, so the mapper's
        // r.get(originalField) still works).
        val aliasedInner = aliasColumnsPositionally(dsl.renderInlined(query), fields.size)
        val wrapped = wrap(aliasedInner)
        return dsl.resultQuery(wrapped).coerce(*fields).fetch().map(mapper)
    }

    override fun execute(dsl: DSLContext, mutation: Query): Int {
        // The Quack server runs the inner DML via DuckDB's SendQuery and the
        // table-function surfaces DuckDB's standard `Count BIGINT` shape — i.e.
        // one row, one column carrying the affected-row count (or 0 for DDL).
        // Read it back and return as int so callers preserve the
        // Query.execute() contract on PG / local DuckDB.
        val wrapped = wrap(dsl.renderInlined(mutation))
        val rows = dsl.fetch(wrapped)
        if (rows.isEmpty()) {
            return 0
        }
        val value = rows[0].get(0)
        return if (value is Number) value.toInt() else 0
    }

    /**
     * Force Quack 2.0's native transaction owner before any wrapper read or append. A bare RPC
     * BEGIN is invisible to that owner and a later native append would issue a nested BEGIN.
     * use_transaction starts the remote transaction AND records ownership in QuackTransaction;
     * JDBC commit/rollback then finish it exactly once, including direct appends.
     */
    override fun begin(dsl: DSLContext) {
        dsl.fetch("CALL system.main.quack_query_by_name(${sqlLiteral(metadataCatalogName)}, 'SELECT 1', use_transaction := true)")
    }

    override fun commit(dsl: DSLContext, connection: java.sql.Connection) {
        connection.commit() // Native owner performs the remote COMMIT; the outer boundary classifies failures.
    }

    override fun rollback(dsl: DSLContext, connection: java.sql.Connection) {
        connection.rollback()
    }

    private fun wrap(innerSql: String): String = wrap(metadataCatalogName, innerSql)

    /**
     * jOOQ [org.jooq.ExecuteListener] that routes every rendered `SELECT` on the Quack backend
     * through `quack_query_by_name`, executing the whole statement server-side as plain DuckDB.
     * This also avoids native 2.0 scan parameter forwarding failures on prepared SQL.
     * Requires `StatementType.STATIC_STATEMENT` so the SQL
     * seen at [renderEnd] has its bind values inlined (the wrapper takes one text literal). Column
     * names that collide across joined tables are aliased positionally (`_q(c0, c1, …)`); jOOQ maps
     * typed selects by position so the caller's fields still resolve. Mutations are NOT rewritten
     * here — they go through [MetadataQuery.execute] explicitly, and direct INSERTs (Quack appends)
     * take part in the server transaction on their own.
     */
    class ReadWrappingListener(private val metadataCatalogName: String) : org.jooq.impl.DefaultExecuteListener() {
        override fun renderEnd(ctx: org.jooq.ExecuteContext) {
            val query = ctx.query() as? ResultQuery<*> ?: return
            val sql = ctx.sql() ?: return
            if (sql.trimStart().startsWith("CALL ", ignoreCase = true)) {
                return // already wrapped (MetadataQuery paths and native transaction activation)
            }
            val fields = query.fields()
            val names = fields.map { it.name }
            val inner = if (fields.isNotEmpty() && names.toSet().size != names.size) aliasColumnsPositionally(sql, fields.size) else sql
            ctx.sql(wrap(metadataCatalogName, inner))
        }
    }

    companion object {
        private fun wrap(metadataCatalogName: String, innerSql: String): String =
            "CALL system.main.quack_query_by_name(${sqlLiteral(metadataCatalogName)}, ${sqlLiteral(innerSql)})"

        private fun aliasColumnsPositionally(innerSql: String, columnCount: Int): String {
            val aliases = StringBuilder()
            for (i in 0 until columnCount) {
                if (i > 0) {
                    aliases.append(", ")
                }
                aliases.append("c").append(i)
            }
            return "SELECT * FROM ($innerSql) AS _q($aliases)"
        }

        private fun sqlLiteral(value: String): String =
            "'" + value.replace("'", "''") + "'"
    }
}
