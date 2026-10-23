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

import dev.brikk.ducklake.catalog.Final11SchemaHierarchy.Companion.corrupt
import dev.brikk.ducklake.catalog.Final11SchemaHierarchy.Companion.invalid
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.util.UUID

internal class Final11HierarchyReader(private val queries: () -> Final11HierarchyQueries) : DucklakeHierarchyReader {
    override fun listSchemas(snapshotId: Long): List<DucklakeQualifiedSchema> = queries().schemas(snapshotId).all()
    override fun getSchemaById(schemaId: Long, snapshotId: Long): DucklakeQualifiedSchema? = queries().schemas(snapshotId).get(schemaId)
    override fun findSchema(components: List<String>, snapshotId: Long): DucklakeQualifiedSchema? = queries().schemas(snapshotId).find(components)
    override fun listTables(schemaId: Long, snapshotId: Long): List<DucklakeQualifiedTable> = queries().tables(schemaId, snapshotId)
    override fun getTableById(tableId: Long, snapshotId: Long): DucklakeQualifiedTable? = queries().table(tableId, snapshotId)
    override fun findTable(schemaComponents: List<String>, tableName: String, snapshotId: Long): DucklakeQualifiedTable? =
        queries().findTable(schemaComponents, tableName, snapshotId)
    override fun listRetainedFiles(kind: DucklakeFileKind, afterFileId: Long?, limit: Int): List<DucklakeOwnedFile> =
        queries().retained(kind, afterFileId, limit)
    override fun listScheduledFiles(olderThan: Instant, limit: Int): List<DucklakeResolvedScheduledFile> = queries().scheduled(olderThan, limit)
    override fun listScheduledReferences(after: DucklakeScheduledPathCursor?, limit: Int): List<DucklakeScheduledPathReference> =
        queries().scheduledReferences(after, limit)
}

/** Each operation owns its bounded caches; no hierarchy or ownership survives the pinned session. */
internal class Final11HierarchyQueries(private val ctx: DSLContext, private val requireSnapshot: (Long) -> Unit) {
    private val cleanup = JdbcDucklakeCleanupQueries(ctx, DirectMetadataQuery())
    private val root by lazy { cleanup.getCleanupDataPath()?.also {
        if (!it.endsWith('/')) invalid("catalog data_path must use the qualified trailing '/' directory separator")
    } }
    private val schemaCache = mutableMapOf<Pair<Long, Long>, DucklakeSchemaMetadata>()
    private val tableCache = mutableMapOf<Pair<Long, Long>, DucklakeTable>()
    private var work = 0

    fun schemas(snapshot: Long): Final11SchemaHierarchy {
        requireSnapshot(snapshot)
        return Final11SchemaHierarchy(readSchemas(active("s", snapshot), Final11SchemaHierarchy.MAX_NODES + 1), root)
    }

    fun tables(schemaId: Long, snapshot: Long): List<DucklakeQualifiedTable> {
        Final11SchemaHierarchy.requireIdentity(schemaId)
        val graph = schemas(snapshot)
        val schema = graph.get(schemaId) ?: return emptyList()
        val rows = readTables(number("t", "schema_id").eq(schemaId).and(active("t", snapshot)), Final11SchemaHierarchy.MAX_NODES + 1)
        if (rows.size > Final11SchemaHierarchy.MAX_NODES) invalid("table node budget exceeded")
        if (rows.map { it.tableId }.distinct().size != rows.size || rows.map { key(it.tableName) }.distinct().size != rows.size) {
            corrupt("ambiguous table identities/names")
        }
        return rows.map { qualify(it, schema) }
    }

    fun table(tableId: Long, snapshot: Long): DucklakeQualifiedTable? {
        Final11SchemaHierarchy.requireIdentity(tableId)
        val graph = schemas(snapshot)
        val row = tableAt(tableId, snapshot) ?: return null
        val schema = graph.get(row.schemaId) ?: corrupt("missing active table schema ${row.schemaId}")
        return qualify(row, schema)
    }

    fun findTable(parts: List<String>, name: String, snapshot: Long): DucklakeQualifiedTable? {
        require(name.isNotEmpty() && name.length <= Final11SchemaHierarchy.MAX_TEXT) { "Invalid table identifier" }
        val schema = schemas(snapshot).find(parts) ?: return null
        return tables(schema.metadata.schema.schemaId, snapshot).singleOrNull { key(it.table.tableName) == key(name) }
    }

    fun retained(kind: DucklakeFileKind, after: Long?, limit: Int): List<DucklakeOwnedFile> {
        require(after == null || after >= 0) { "afterFileId must be non-negative" }
        require(limit in 1..DucklakeCleanupLimits.MAX_BATCH_SIZE) { "Invalid retained-file page size" }
        val table = if (kind == DucklakeFileKind.DATA) "ducklake_data_file" else "ducklake_delete_file"
        val id = number("f", if (kind == DucklakeFileKind.DATA) "data_file_id" else "delete_file_id")
        val owner = number("f", "table_id")
        val begin = number("f", "begin_snapshot")
        val end = number("f", "end_snapshot")
        val path = text("f", "path")
        val relative = flag("f", "path_is_relative")
        spend(1)
        if (ctx.fetchExists(ctx.selectOne().from(DSL.table(DSL.name(table)).`as`("f"))
                .where(id.isNull.or(id.lt(0L))).limit(1))) corrupt("NULL/negative retained file identity")
        val rows = ctx.select(id, owner, begin, end, path, relative).from(DSL.table(DSL.name(table)).`as`("f"))
            .where(after?.let { id.gt(it) } ?: DSL.noCondition()).orderBy(id).limit(limit + 1).fetch()
        spend(rows.size)
        if (rows.map { it.get(id) }.distinct().size != rows.size) corrupt("duplicate retained file identity")
        return rows.take(limit).map { row ->
            val fileId = identity(required(row, id))
            val tableId = identity(required(row, owner))
            val origin = identity(required(row, begin))
            val stored = required(row, path).also { if (it.isEmpty()) corrupt("empty retained file path") }
            val isRelative = required(row, relative)
            val tableRow = tableAt(tableId, origin) ?: corrupt("missing retained table $tableId at file origin $origin")
            val schema = retainedSchema(tableRow.schemaId, origin)
            val qualified = qualify(tableRow, schema)
            val resolved = Final11SchemaHierarchy.path(stored, isRelative, qualified.dataPath)
                ?: invalid("relative retained file has no catalog data_path root")
            DucklakeOwnedFile(kind, fileId, qualified, origin, row.get(end), stored, isRelative, resolved)
        }
    }

    fun scheduled(olderThan: Instant, limit: Int): List<DucklakeResolvedScheduledFile> =
        cleanup.listScheduledFileBatch(olderThan, limit).map { entry ->
            val path = Final11SchemaHierarchy.path(entry.path, entry.pathIsRelative, if (entry.pathIsRelative) root else null)
                ?: invalid("relative scheduled file has no catalog data_path root")
            DucklakeResolvedScheduledFile(entry.dataFileId, entry.path, entry.pathIsRelative, entry.scheduleStart, path)
        }

    fun scheduledReferences(after: DucklakeScheduledPathCursor?, limit: Int): List<DucklakeScheduledPathReference> {
        require(limit in 1..DucklakeCleanupLimits.MAX_BATCH_SIZE) { "Invalid scheduled-reference page size" }
        after?.let {
            require(it.dataFileId >= 0 && it.path.isNotEmpty() && it.path.length <= Final11SchemaHierarchy.MAX_TEXT) { "Invalid scheduled cursor" }
        }
        val t = DSL.table(DSL.name("ducklake_files_scheduled_for_deletion")).`as`("q")
        val id = number("q", "data_file_id")
        val rawPath = DSL.field(DSL.name("q", "path"), String::class.java)
        val path = encoded(DSL.substring(rawPath, 1, Final11SchemaHierarchy.MAX_TEXT + 1))
        val selectedPath = path.`as`("encoded_path")
        val relative = flag("q", "path_is_relative")
        spend(1)
        if (ctx.fetchExists(ctx.selectOne().from(t).where(id.isNull.or(id.lt(0L)).or(rawPath.isNull).or(DSL.length(rawPath).eq(0))
                .or(relative.isNull).or(DSL.length(rawPath).gt(Final11SchemaHierarchy.MAX_TEXT))).limit(1))) {
            corrupt("invalid scheduled path-reference identity/path/flag")
        }
        val afterCondition = if (after == null) DSL.noCondition() else {
            val lastPath = encoded(DSL.`val`(after.path))
            id.gt(after.dataFileId).or(id.eq(after.dataFileId).and(path.gt(lastPath)))
                .or(id.eq(after.dataFileId).and(path.eq(lastPath)).and(relative.gt(after.pathIsRelative)))
        }
        // Distinct encoded paths do not collapse case/Unicode variants under a text collation.
        val rows = ctx.selectDistinct(id, selectedPath, relative).from(t).where(afterCondition)
            .orderBy(id, selectedPath, relative).limit(limit).fetch()
        return rows.map { r ->
            val cursor = DucklakeScheduledPathCursor(identity(required(r, id)),
                Final11SchemaHierarchy.decodedPath(required(r, selectedPath)), required(r, relative))
            val resolved = Final11SchemaHierarchy.path(cursor.path, cursor.pathIsRelative, if (cursor.pathIsRelative) root else null)
                ?: invalid("relative scheduled reference has no catalog data_path root")
            DucklakeScheduledPathReference(cursor, resolved)
        }
    }

    private fun retainedSchema(id: Long, snapshot: Long): DucklakeQualifiedSchema {
        val rows = mutableListOf<DucklakeSchemaMetadata>()
        val seen = mutableSetOf<Long>()
        var next: Long? = id
        while (next != null) {
            if (!seen.add(next)) corrupt("cyclic retained schema parentage")
            if (rows.size == Final11SchemaHierarchy.MAX_DEPTH) invalid("retained schema depth budget exceeded")
            val key = next to snapshot
            val row = schemaCache.getOrPut(key) {
                val found = readSchemas(number("s", "schema_id").eq(next).and(active("s", snapshot)), 2)
                if (found.size != 1) corrupt("missing or ambiguous retained schema $next at $snapshot")
                uniqueSchemaName(found.single(), snapshot)
                found.single()
            }
            rows.add(row)
            next = row.parentSchemaId
        }
        return Final11SchemaHierarchy(rows, root).get(id) ?: corrupt("missing retained schema")
    }

    private fun tableAt(id: Long, snapshot: Long): DucklakeTable? {
        tableCache[id to snapshot]?.let { return it }
        val rows = readTables(number("t", "table_id").eq(id).and(active("t", snapshot)), 2)
        if (rows.size > 1) corrupt("overlapping table versions for $id")
        return rows.singleOrNull()?.also { table ->
            spend(1)
            val peers = ctx.select(number("t", "table_id")).from(DSL.table(DSL.name("ducklake_table")).`as`("t"))
                .where(number("t", "schema_id").eq(table.schemaId)).and(active("t", snapshot))
                .and(sameName("t", "table_name", table.tableName)).limit(2).fetch()
            if (peers.size != 1) corrupt("ambiguous table name ${table.tableName}")
            tableCache[id to snapshot] = table
        }
    }

    private fun uniqueSchemaName(row: DucklakeSchemaMetadata, snapshot: Long) {
        spend(1)
        val parent = number("s", "parent_schema_id")
        val peers = ctx.select(number("s", "schema_id")).from(DSL.table(DSL.name("ducklake_schema")).`as`("s"))
            .where(row.parentSchemaId?.let { parent.eq(it) } ?: parent.isNull).and(active("s", snapshot))
            .and(sameName("s", "schema_name", row.schema.schemaName)).limit(2).fetch()
        if (peers.size != 1) corrupt("ambiguous retained sibling schema name")
    }

    private fun readSchemas(condition: Condition, limit: Int): List<DucklakeSchemaMetadata> {
        spend(1)
        val s = DSL.table(DSL.name("ducklake_schema")).`as`("s")
        val id = number("s", "schema_id")
        val uuid = DSL.field(DSL.name("s", "schema_uuid"), UUID::class.java)
        val begin = number("s", "begin_snapshot")
        val end = number("s", "end_snapshot")
        val name = text("s", "schema_name")
        val path = text("s", "path")
        val relative = flag("s", "path_is_relative")
        val parent = number("s", "parent_schema_id")
        return ctx.select(id, uuid, begin, end, name, path, relative, parent).from(s).where(condition).orderBy(id).limit(limit).fetch { r ->
            DucklakeSchemaMetadata(DucklakeSchema(identity(required(r, id)), required(r, uuid), identity(required(r, begin)), r.get(end),
                required(r, name), checked(r.get(path)), r.get(relative)), r.get(parent)?.let(::identity))
        }
    }

    private fun readTables(condition: Condition, limit: Int): List<DucklakeTable> {
        spend(1)
        val t = DSL.table(DSL.name("ducklake_table")).`as`("t")
        val id = number("t", "table_id")
        val uuid = DSL.field(DSL.name("t", "table_uuid"), UUID::class.java)
        val begin = number("t", "begin_snapshot")
        val end = number("t", "end_snapshot")
        val schema = number("t", "schema_id")
        val name = text("t", "table_name")
        val path = text("t", "path")
        val relative = flag("t", "path_is_relative")
        return ctx.select(id, uuid, begin, end, schema, name, path, relative).from(t).where(condition).orderBy(id).limit(limit).fetch { r ->
            DucklakeTable(identity(required(r, id)), required(r, uuid), identity(required(r, begin)), r.get(end),
                identity(required(r, schema)), required(r, name).also { if (it.isEmpty()) corrupt("empty table name") }, checked(r.get(path)), r.get(relative))
        }
    }

    private fun qualify(table: DucklakeTable, schema: DucklakeQualifiedSchema): DucklakeQualifiedTable =
        DucklakeQualifiedTable(table, schema, Final11SchemaHierarchy.path(table.path, table.pathIsRelative, schema.dataPath))
    private fun key(name: String) = Final11SchemaHierarchy.key(name)
    private fun identity(id: Long): Long = id.also(Final11SchemaHierarchy::requireIdentity)
    private fun checked(value: String?): String? = Final11SchemaHierarchy.checkedText(value)
    private fun <T : Any> required(row: Record, field: Field<T>): T = row.get(field)?.also { if (it is String) checked(it) }
        ?: corrupt("NULL required ${field.name}")
    private fun number(alias: String, name: String) = DSL.field(DSL.name(alias, name), Long::class.javaObjectType)
    private fun flag(alias: String, name: String) = DSL.field(DSL.name(alias, name), Boolean::class.javaObjectType)
    private fun text(alias: String, name: String) = DSL.substring(DSL.field(DSL.name(alias, name), String::class.java), 1,
        Final11SchemaHierarchy.MAX_TEXT + 1).`as`(name)
    private fun active(alias: String, snapshot: Long) = number(alias, "begin_snapshot").le(snapshot)
        .and(number(alias, "end_snapshot").isNull.or(number(alias, "end_snapshot").gt(snapshot)))
    private fun sameName(alias: String, column: String, name: String): Condition {
        var folded = DSL.field(DSL.name(alias, column), String::class.java)
        if (ctx.dialect().family() == SQLDialect.MYSQL) {
            for (letter in 'A'..'Z') folded = DSL.replace(folded, DSL.`val`(letter.toString()), DSL.`val`(letter.lowercaseChar().toString()))
        } else folded = DSL.function("translate", SQLDataType.VARCHAR, folded,
            DSL.`val`("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), DSL.`val`("abcdefghijklmnopqrstuvwxyz"))
        return encoded(folded).eq(encoded(DSL.`val`(key(name))))
    }
    private fun encoded(value: Field<String>): Field<ByteArray> = Final11SqlBackend.bytes(ctx, value)
    private fun spend(amount: Int) { work += amount; if (work > MAX_WORK) invalid("hierarchy lookup/traversal budget exceeded") }

    companion object { private const val MAX_WORK = 8192 }
}
