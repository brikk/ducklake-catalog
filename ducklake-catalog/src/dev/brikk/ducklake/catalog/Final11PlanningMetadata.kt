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
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_TABLE
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_COLUMN
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_PARTITION_INFO
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_PARTITION_COLUMN
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_FILE_PARTITION_VALUE
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_DATA_FILE
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_NAME_MAPPING
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_INLINED_DATA_TABLES
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SNAPSHOT
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL

internal class Final11PlanningMetadata(
    private val session: () -> DSLContext,
    private val inlineRegistryExists: () -> Boolean,
    private val inlineLiveness: (Long, Long, Long) -> Boolean,
) : DucklakePlanningMetadata {
    override fun listSchemasPage(snapshotId: Long, afterSchemaId: Long?, limit: Int): List<DucklakeSchema> {
        val ctx = context(snapshotId, 0, afterSchemaId, limit)
        val s = DUCKLAKE_SCHEMA.`as`("s")
        val rows = ctx.select(fields(s, limit)).from(s).where(active("s", snapshotId))
            .and(afterSchemaId?.let { s.SCHEMA_ID.gt(it) } ?: DSL.trueCondition())
            .orderBy(s.SCHEMA_ID).limit(limit + 1).fetch()
        return page(rows, limit, textLimit(s, limit)) { id(it, s.SCHEMA_ID) }.map { r ->
            DucklakeSchema(id(r, s.SCHEMA_ID), required(r, s.SCHEMA_UUID), id(r, s.BEGIN_SNAPSHOT), number(r.get(s.END_SNAPSHOT)),
                required(r, s.SCHEMA_NAME), r.get(s.PATH), r.get(s.PATH_IS_RELATIVE))
        }
    }

    override fun listTablesPage(schemaId: Long, snapshotId: Long, afterTableId: Long?, limit: Int): List<DucklakeTable> {
        val ctx = context(snapshotId, schemaId, afterTableId, limit)
        val t = DUCKLAKE_TABLE.`as`("t")
        val rows = ctx.select(fields(t, limit)).from(t).where(t.SCHEMA_ID.eq(schemaId)).and(active("t", snapshotId))
            .and(afterTableId?.let { t.TABLE_ID.gt(it) } ?: DSL.trueCondition()).orderBy(t.TABLE_ID).limit(limit + 1).fetch()
        return page(rows, limit, textLimit(t, limit)) { id(it, t.TABLE_ID) }.map { r ->
            DucklakeTable(id(r, t.TABLE_ID), required(r, t.TABLE_UUID), id(r, t.BEGIN_SNAPSHOT), number(r.get(t.END_SNAPSHOT)),
                id(r, t.SCHEMA_ID), required(r, t.TABLE_NAME), r.get(t.PATH), r.get(t.PATH_IS_RELATIVE))
        }
    }

    override fun getColumnsPage(tableId: Long, snapshotId: Long, afterColumnId: Long?, limit: Int): List<DucklakeColumn> {
        val ctx = context(snapshotId, tableId, afterColumnId, limit)
        val c = DUCKLAKE_COLUMN.`as`("c")
        val rows = ctx.select(fields(c, limit)).from(c).where(c.TABLE_ID.eq(tableId)).and(active("c", snapshotId))
            .and(afterColumnId?.let { c.COLUMN_ID.gt(it) } ?: DSL.trueCondition()).orderBy(c.COLUMN_ID).limit(limit + 1).fetch()
        return page(rows, limit, textLimit(c, limit)) { id(it, c.COLUMN_ID) }.map { r ->
            DucklakeColumn(id(r, c.COLUMN_ID), id(r, c.BEGIN_SNAPSHOT), number(r.get(c.END_SNAPSHOT)), id(r, c.TABLE_ID),
                id(r, c.COLUMN_ORDER), required(r, c.COLUMN_NAME), required(r, c.COLUMN_TYPE), required(r, c.NULLS_ALLOWED),
                number(r.get(c.PARENT_COLUMN)), r.get(c.INITIAL_DEFAULT))
        }
    }

    override fun getPartitionFieldsPage(
        tableId: Long, snapshotId: Long, after: DucklakePartitionFieldCursor?, limit: Int,
    ): List<DucklakePartitionFieldPageEntry> {
        val ctx = context(snapshotId, tableId, after?.partitionId, limit)
        require(after == null || after.partitionKeyIndex >= 0) { "Negative partition field cursor" }
        val p = DUCKLAKE_PARTITION_INFO.`as`("p")
        val f = DUCKLAKE_PARTITION_COLUMN.`as`("f")
        val transform = text(f.TRANSFORM).`as`("partition_transform")
        val seek = after?.let { p.PARTITION_ID.gt(it.partitionId).or(p.PARTITION_ID.eq(it.partitionId)
            .and(f.PARTITION_KEY_INDEX.gt(it.partitionKeyIndex.toLong()))) } ?: DSL.trueCondition()
        val rows = ctx.select(p.PARTITION_ID, p.TABLE_ID, f.PARTITION_KEY_INDEX, f.COLUMN_ID, transform).from(p).join(f)
            .on(p.PARTITION_ID.eq(f.PARTITION_ID)).and(p.TABLE_ID.eq(f.TABLE_ID))
            .where(p.TABLE_ID.eq(tableId)).and(active("p", snapshotId)).and(seek)
            .orderBy(p.PARTITION_ID, f.PARTITION_KEY_INDEX).limit(limit + 1).fetch()
        return page(rows, limit) { id(it, p.PARTITION_ID) to id(it, f.PARTITION_KEY_INDEX) }.map { r ->
            val index = index(id(r, f.PARTITION_KEY_INDEX))
            val parsed = DucklakePartitionTransform.parseCatalogTransform(required(r, transform))
            DucklakePartitionFieldPageEntry(DucklakePartitionFieldCursor(id(r, p.PARTITION_ID), index), id(r, p.TABLE_ID),
                DucklakePartitionField(index, id(r, f.COLUMN_ID), parsed.transform, if (parsed.arity.isPresent) parsed.arity.asInt else null))
        }
    }

    override fun getFilePartitionValuesPage(
        tableId: Long, snapshotId: Long, after: DucklakeFilePartitionCursor?, limit: Int,
    ): List<DucklakeFilePartitionValue> {
        val ctx = context(snapshotId, tableId, after?.dataFileId, limit)
        require(after == null || after.partitionKeyIndex >= 0) { "Negative file partition cursor" }
        val p = DUCKLAKE_FILE_PARTITION_VALUE.`as`("p")
        val f = DUCKLAKE_DATA_FILE.`as`("f")
        val value = text(p.PARTITION_VALUE).`as`("partition_value")
        val seek = after?.let { p.DATA_FILE_ID.gt(it.dataFileId).or(p.DATA_FILE_ID.eq(it.dataFileId)
            .and(p.PARTITION_KEY_INDEX.gt(it.partitionKeyIndex.toLong()))) } ?: DSL.trueCondition()
        val rows = ctx.select(p.DATA_FILE_ID, p.PARTITION_KEY_INDEX, value).from(p).join(f)
            .on(p.DATA_FILE_ID.eq(f.DATA_FILE_ID)).and(p.TABLE_ID.eq(f.TABLE_ID))
            .where(p.TABLE_ID.eq(tableId)).and(active("f", snapshotId)).and(seek)
            .orderBy(p.DATA_FILE_ID, p.PARTITION_KEY_INDEX).limit(limit + 1).fetch()
        return page(rows, limit) { id(it, p.DATA_FILE_ID) to id(it, p.PARTITION_KEY_INDEX) }.map { r ->
            DucklakeFilePartitionValue(id(r, p.DATA_FILE_ID), index(id(r, p.PARTITION_KEY_INDEX)), r.get(value))
        }
    }

    override fun getNameMappingPage(mappingId: Long, afterMappingColumnId: Long?, limit: Int): List<DucklakeNameMappingRow> {
        val ctx = context(null, mappingId, afterMappingColumnId, limit)
        val n = DUCKLAKE_NAME_MAPPING.`as`("n")
        val rows = ctx.select(fields(n, limit)).from(n).where(n.MAPPING_ID.eq(mappingId))
            .and(afterMappingColumnId?.let { n.COLUMN_ID.gt(it) } ?: DSL.trueCondition()).orderBy(n.COLUMN_ID).limit(limit + 1).fetch()
        return page(rows, limit, textLimit(n, limit)) { id(it, n.COLUMN_ID) }.map { r ->
            DucklakeNameMappingRow(id(r, n.COLUMN_ID), required(r, n.SOURCE_NAME), id(r, n.TARGET_FIELD_ID),
                number(r.get(n.PARENT_COLUMN)), r.get(n.IS_PARTITION))
        }
    }

    override fun getInlineVersionsPage(tableId: Long, snapshotId: Long, afterSchemaVersion: Long?, limit: Int): List<DucklakeInlinedDataInfo> {
        val ctx = context(snapshotId, tableId, afterSchemaVersion, limit)
        val visibleVersion = Final11ReadCompatibility.visibleSchemaVersion(ctx, snapshotId)
        if (!inlineRegistryExists()) return emptyList()
        val i = DUCKLAKE_INLINED_DATA_TABLES.`as`("i")
        val rows = ctx.select(fields(i, limit)).from(i).where(i.TABLE_ID.eq(tableId))
            .and(i.SCHEMA_VERSION.le(visibleVersion))
            .and(afterSchemaVersion?.let { i.SCHEMA_VERSION.gt(it) } ?: DSL.trueCondition())
            .orderBy(i.SCHEMA_VERSION).limit(limit + 1).fetch()
        return page(rows, limit, textLimit(i, limit)) { id(it, i.SCHEMA_VERSION) }.map { r ->
            val version = id(r, i.SCHEMA_VERSION)
            val name = required(r, i.TABLE_NAME)
            if (name != "ducklake_inlined_data_${tableId}_$version") corrupt("Inline registry name disagrees with stable identity")
            DucklakeInlinedDataInfo(id(r, i.TABLE_ID), name, version, inlineLiveness(tableId, version, snapshotId))
        }
    }

    private fun context(snapshot: Long?, scopeId: Long, after: Long?, limit: Int): DSLContext {
        require(limit in 1..1024 && scopeId >= 0 && (after == null || after >= 0)) { "Invalid metadata page scope/cursor/limit" }
        val ctx = session()
        if (snapshot != null) {
            val s = DUCKLAKE_SNAPSHOT
            if (snapshot < 0 || !ctx.fetchExists(ctx.selectOne().from(s).where(s.SNAPSHOT_ID.eq(snapshot)).limit(1))) {
                throw DucklakeInvalidOperationException("Metadata page snapshot is not visible in this owned view")
            }
        }
        return ctx
    }

    private fun fields(table: Table<*>, limit: Int): List<Field<*>> = table.fields().map { field ->
        if (field.dataType.isString) text(field.coerce(String::class.java), textLimit(table, limit)).`as`(field.name) else field
    }

    private fun textLimit(table: Table<*>, limit: Int): Int = minOf(Final11ReadMetadataQueries.MAX_TEXT,
        16_777_216 / ((limit + 1) * table.fields().count { it.dataType.isString }.coerceAtLeast(1)))

    private fun text(field: Field<String>, maximum: Int = Final11ReadMetadataQueries.MAX_TEXT): Field<String> = DSL.substring(field, 1, maximum + 1)

    private fun active(alias: String, snapshot: Long) = DSL.field(DSL.name(alias, "begin_snapshot"), Long::class.javaObjectType).le(snapshot)
        .and(DSL.field(DSL.name(alias, "end_snapshot"), Long::class.javaObjectType).let { it.isNull.or(it.gt(snapshot)) })

    private fun <R : Record, K> page(
        rows: List<R>, limit: Int, textMaximum: Int = Final11ReadMetadataQueries.MAX_TEXT, key: (R) -> K,
    ): List<R> {
        rows.forEach { r ->
            if (r.intoArray().any { it is String && it.length > textMaximum }) {
                throw DucklakeInvalidOperationException("Metadata page text exceeds the qualification budget")
            }
        }
        if (rows.zipWithNext().any { (left, right) -> key(left) == key(right) }) corrupt("Duplicate metadata page cursor")
        return rows.take(limit) // SQL already limited to limit+1; lookahead detects duplicate keys across the boundary.
    }

    private fun id(row: Record, field: Field<Long>): Long = number(required(row, field))!!
    private fun <T : Any> required(row: Record, field: Field<T>): T = row.get(field) ?: corrupt("NULL required ${field.name}")
    private fun number(value: Long?): Long? = value?.also { if (it < 0) corrupt("Negative metadata page identity") }
    private fun index(value: Long): Int = if (value <= Int.MAX_VALUE) value.toInt() else corrupt("Partition key index overflow")
    private fun corrupt(detail: String): Nothing = throw DucklakeCatalogCorruptionException(detail)
}
