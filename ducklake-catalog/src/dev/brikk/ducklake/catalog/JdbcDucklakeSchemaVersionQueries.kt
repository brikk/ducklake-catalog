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

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_INLINED_DATA_TABLES
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SCHEMA_VERSIONS
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SNAPSHOT
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL

/** All lookups are bounded and use the caller's pinned/owned connection. No version cache. */
internal class JdbcDucklakeSchemaVersionQueries(private val ctx: DSLContext, private val metadata: MetadataQuery) {
    private data class Change(val snapshot: Long, val version: Long)
    private data class InlineVersion(val table: Long, val version: Long)

    fun resolve(tableId: Long, version: Long, snapshotId: Long): Long? {
        val bound = globalVersion(snapshotId) ?: return null
        if (version > bound || version < 0) return null
        if (!hasTableScope()) return globalSnapshot(version, snapshotId)
        return resolveScoped(tableId, version, snapshotId)
    }

    private fun resolveScoped(tableId: Long, version: Long, snapshotId: Long): Long? {
        val last = change(tableId, version, snapshotId, next = false) ?: return globalSnapshot(version, snapshotId)
        if (last.version == version) return last.snapshot
        val next = change(tableId, version, snapshotId, next = true) ?: return last.snapshot
        // Old native commits can label inline tables below their own committing schema row.
        // The predecessor distinguishes that case from an unchanged table at an unrelated version.
        val previousVersion = globalVersion(next.snapshot - 1)
        if (previousVersion != null) return if (previousVersion < version) next.snapshot else last.snapshot
        val exactGlobal = globalSnapshot(version, snapshotId)
        if (exactGlobal != null && exactGlobal < next.snapshot) return last.snapshot
        // Expired predecessor + no durable alias is ambiguous: never guess the old column tree.
        return null
    }

    private fun change(tableId: Long, version: Long, snapshotId: Long, next: Boolean): Change? {
        val sv = DUCKLAKE_SCHEMA_VERSIONS
        val query = ctx.select(sv.BEGIN_SNAPSHOT, sv.SCHEMA_VERSION).from(sv)
            .where(sv.TABLE_ID.eq(tableId)).and(sv.BEGIN_SNAPSHOT.le(snapshotId))
            .and(if (next) sv.SCHEMA_VERSION.gt(version) else sv.SCHEMA_VERSION.le(version))
            .orderBy(if (next) sv.SCHEMA_VERSION.asc() else sv.SCHEMA_VERSION.desc(), sv.BEGIN_SNAPSHOT.desc())
            .limit(1)
        val row = metadata.fetchOne(ctx, query) ?: return null
        return Change(requireNotNull(row.value1()), requireNotNull(row.value2()))
    }

    private fun globalVersion(snapshotId: Long): Long? {
        val snap = DUCKLAKE_SNAPSHOT
        return metadata.fetchOne(ctx, ctx.select(snap.SCHEMA_VERSION).from(snap).where(snap.SNAPSHOT_ID.eq(snapshotId)).limit(1))?.value1()
    }

    private fun globalSnapshot(version: Long, snapshotId: Long): Long? {
        val snap = DUCKLAKE_SNAPSHOT
        return metadata.fetchOne(ctx, ctx.select(snap.SNAPSHOT_ID).from(snap)
            .where(snap.SCHEMA_VERSION.eq(version)).and(snap.SNAPSHOT_ID.le(snapshotId))
            .orderBy(snap.SNAPSHOT_ID.desc()).limit(1))?.value1()
    }

    /** Probe without an expected SQL error poisoning PostgreSQL/preview read transactions. */
    private fun hasTableScope(): Boolean {
        if (ctx.dialect() == SQLDialect.POSTGRES) {
            return metadata.fetchOne(ctx, ctx.selectOne().where(DSL.condition(
                "EXISTS (SELECT 1 FROM pg_catalog.pg_attribute WHERE attrelid = to_regclass({0}) " +
                    "AND attname = 'table_id' AND NOT attisdropped)", DSL.`val`("ducklake_schema_versions"),
            )).limit(1)) != null
        }
        val columns = DSL.table(DSL.sql("information_schema.columns"))
        val schema = DSL.field(DSL.sql("table_schema"), String::class.java)
        val namespace = if (ctx.dialect() == SQLDialect.MYSQL) schema.eq(DSL.field("DATABASE()", String::class.java))
        else schema.eq(DSL.field("CURRENT_SCHEMA()", String::class.java))
            .and(DSL.field(DSL.sql("table_catalog"), String::class.java).eq(DSL.field("CURRENT_CATALOG()", String::class.java)))
        return metadata.fetchOne(ctx, ctx.selectOne().from(columns)
            .where(DSL.field(DSL.sql("table_name"), String::class.java).eq("ducklake_schema_versions"))
            .and(DSL.field(DSL.sql("column_name"), String::class.java).eq("table_id"))
            .and(namespace).limit(1)) != null
    }

    /** Persist standard schema-version aliases BEFORE expiry deletes the evidence for old inline layouts. */
    fun preserveInlineBoundaries(snapshotId: Long, hasRows: (Long, Long) -> Boolean) {
        if (!hasTableScope()) return
        val bound = globalVersion(snapshotId) ?: return
        val registry = DUCKLAKE_INLINED_DATA_TABLES
        var after: InlineVersion? = null
        var inspected = 0
        val aliases = mutableListOf<Pair<InlineVersion, Long>>()
        while (true) {
            val cursor = after?.let {
                registry.TABLE_ID.gt(it.table).or(registry.TABLE_ID.eq(it.table).and(registry.SCHEMA_VERSION.gt(it.version)))
            } ?: DSL.noCondition()
            val page = metadata.fetch(ctx, ctx.selectDistinct(registry.TABLE_ID, registry.SCHEMA_VERSION).from(registry)
                .where(cursor).and(registry.SCHEMA_VERSION.le(bound))
                .orderBy(registry.TABLE_ID, registry.SCHEMA_VERSION).limit(PAGE_SIZE))
            if (page.isEmpty()) break
            inspected += page.size
            require(inspected <= MAX_INLINE_VERSIONS) { "Snapshot expiry exceeded the inline-schema traversal budget ($MAX_INLINE_VERSIONS)" }
            for (row in page) {
                val inline = InlineVersion(requireNotNull(row.value1()), requireNotNull(row.value2()))
                val last = change(inline.table, inline.version, snapshotId, next = false)
                if (last?.version == inline.version || !hasRows(inline.table, inline.version)) continue
                val resolved = resolveScoped(inline.table, inline.version, snapshotId)
                    ?: throw DucklakeCatalogCorruptionException(
                        "Cannot preserve ambiguous inline schema ${inline.version} for table ${inline.table} before expiry",
                    )
                aliases.add(inline to resolved)
            }
            after = InlineVersion(requireNotNull(page.last().value1()), requireNotNull(page.last().value2()))
        }
        // No metadata mutation until the bounded preflight has resolved every nonempty table.
        for ((inline, begin) in aliases) {
            val sv = DUCKLAKE_SCHEMA_VERSIONS
            metadata.execute(ctx, ctx.insertInto(sv, sv.TABLE_ID, sv.SCHEMA_VERSION, sv.BEGIN_SNAPSHOT)
                .select(ctx.select(DSL.`val`(inline.table), DSL.`val`(inline.version), DSL.`val`(begin))
                    .whereNotExists(ctx.selectOne().from(sv).where(sv.TABLE_ID.eq(inline.table)).and(sv.SCHEMA_VERSION.eq(inline.version)))))
        }
    }

    companion object {
        private const val PAGE_SIZE = 256
        private const val MAX_INLINE_VERSIONS = 65536
    }
}
