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
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import java.util.UUID

internal class Final11ReadMetadataReader(private val queries: () -> Final11ReadMetadataQueries) : DucklakeReadMetadata {
    override fun getReadMetadataCapabilities(): DucklakeReadMetadataCapabilities = queries().capabilities()
    override fun listSchemaMetadata(snapshotId: Long): List<DucklakeSchemaMetadata> = queries().schemas(snapshotId)
    override fun getFileReadMetadata(tableId: Long, snapshotId: Long): List<DucklakeFileReadMetadata> = queries().files(tableId, snapshotId)
    override fun getFileColumnMetadata(tableId: Long, snapshotId: Long): List<DucklakeFileColumnMetadata> = queries().fileStats(tableId, snapshotId)
    override fun getTableColumnMetadata(tableId: Long): List<DucklakeTableColumnMetadata> = queries().tableStats(tableId)
}

/** Raw rich metadata, deliberately separate from the guarded legacy hierarchy/projection adapter. */
internal class Final11ReadMetadataQueries(private val ctx: DSLContext) {
    private val observed = validateLayout()

    fun hierarchy(): Final11HierarchyQueries = Final11HierarchyQueries(ctx, ::requireSnapshot)

    fun capabilities(): DucklakeReadMetadataCapabilities {
        val version = ctx.fetch("PRAGMA version").single()
        val extensions = ctx.fetch("SELECT extension_name,extension_version FROM duckdb_extensions() " +
            "WHERE loaded AND extension_name IN ('ducklake','quack')").associate { it.get(0, String::class.java) to it.get(1, String::class.java) }
        val unsigned = ctx.fetch("SELECT current_setting('allow_unsigned_extensions')").single().get(0, Boolean::class.javaObjectType)
            ?: corrupt("NULL unsigned-extension setting")
        val driver = ctx.connectionResult { it.metaData.driverVersion }
        val snapshot = ctx.fetch("SELECT max(snapshot_id) FROM ducklake_snapshot").single().get(0, Long::class.javaObjectType)
            ?: corrupt("missing visible snapshot")
        return DucklakeReadMetadataCapabilities("1.1", snapshot, "duckdb", "java-quack",
            version.get(0, String::class.java) ?: corrupt("NULL engine version"),
            version.get(1, String::class.java) ?: corrupt("NULL engine source ID"), extensions["ducklake"], extensions["quack"],
            unsigned, driver, observed, true, true, true, true, true, false, false, false)
    }

    fun schemas(snapshot: Long): List<DucklakeSchemaMetadata> {
        requireSnapshot(snapshot)
        val t = table("ducklake_schema", "s")
        val id = number("s", "schema_id")
        val uuid = DSL.field(DSL.name("s", "schema_uuid"), UUID::class.java)
        val begin = number("s", "begin_snapshot")
        val end = number("s", "end_snapshot")
        val name = text("s", "schema_name")
        val path = text("s", "path")
        val relative = flag("s", "path_is_relative")
        val parent = number("s", "parent_schema_id")
        return bounded(ctx.select(id, uuid, begin, end, name, path, relative, parent).from(t)
            .where(active("s", snapshot)).orderBy(id).limit(MAX_ROWS + 1).fetch()).map { r ->
            DucklakeSchemaMetadata(DucklakeSchema(required(r, id), required(r, uuid), required(r, begin), r.get(end),
                required(r, name), r.get(path), r.get(relative)), nonnegative(r.get(parent)))
        }
    }

    fun files(tableId: Long, snapshot: Long): List<DucklakeFileReadMetadata> {
        requireSnapshot(snapshot)
        val f = table("ducklake_data_file", "f")
        val d = table("ducklake_delete_file", "d")
        val id = number("f", "data_file_id")
        val count = number("f", "record_count")
        val groups = number("f", "row_group_count").`as`("data_row_groups")
        val delete = number("d", "delete_file_id")
        val deleteCount = number("d", "delete_count")
        val deleteFormat = text("d", "format")
        val deleteGroups = number("d", "row_group_count").`as`("delete_row_groups")
        return bounded(ctx.select(id, count, groups, delete, deleteCount, deleteFormat, deleteGroups).from(f).leftJoin(d)
            .on(number("d", "data_file_id").eq(id)).and(number("d", "table_id").eq(tableId)).and(active("d", snapshot))
            .where(number("f", "table_id").eq(tableId)).and(active("f", snapshot))
            .orderBy(id, delete).limit(MAX_ROWS + 1).fetch()).map { r ->
            DucklakeFileReadMetadata(tableId, required(r, id), nonnegative(required(r, count))!!, nonnegative(r.get(groups)),
                r.get(delete), nonnegative(r.get(deleteCount)), r.get(deleteFormat), nonnegative(r.get(deleteGroups)))
        }
    }

    fun fileStats(tableId: Long, snapshot: Long): List<DucklakeFileColumnMetadata> {
        requireSnapshot(snapshot)
        val s = table("ducklake_file_column_stats", "s")
        val f = table("ducklake_data_file", "f")
        val id = number("s", "data_file_id")
        val column = number("s", "column_id")
        val size = number("s", "column_size_bytes")
        val values = number("s", "value_count")
        val nulls = number("s", "null_count")
        val min = text("s", "min_value")
        val max = text("s", "max_value")
        val nan = flag("s", "contains_nan")
        val minExact = flag("s", "min_is_exact")
        val maxExact = flag("s", "max_is_exact")
        return bounded(ctx.select(id, column, size, values, nulls, min, max, nan, minExact, maxExact).from(s).join(f)
            .on(number("f", "data_file_id").eq(id)).and(number("f", "table_id").eq(tableId))
            .where(number("s", "table_id").eq(tableId)).and(active("f", snapshot))
            .orderBy(id, column).limit(MAX_ROWS + 1).fetch()).map { r ->
            DucklakeFileColumnMetadata(tableId, required(r, id), DucklakeFileColumnStats(required(r, column),
                nonnegative(required(r, size))!!, nonnegative(r.get(values)), nonnegative(r.get(nulls)),
                r.get(min), r.get(max), r.get(nan)), r.get(minExact), r.get(maxExact))
        }
    }

    fun tableStats(tableId: Long): List<DucklakeTableColumnMetadata> {
        val t = table("ducklake_table_column_stats", "s")
        val column = number("s", "column_id")
        val nulls = flag("s", "contains_null")
        val nan = flag("s", "contains_nan")
        val min = text("s", "min_value")
        val max = text("s", "max_value")
        val minExact = flag("s", "min_is_exact")
        val maxExact = flag("s", "max_is_exact")
        return bounded(ctx.select(column, nulls, nan, min, max, minExact, maxExact).from(t)
            .where(number("s", "table_id").eq(tableId)).orderBy(column).limit(MAX_ROWS + 1).fetch()).map { r ->
            DucklakeTableColumnMetadata(tableId, required(r, column), r.get(nulls), r.get(nan), r.get(min), r.get(max),
                r.get(minExact), r.get(maxExact))
        }
    }

    private fun validateLayout(): List<DucklakeMetadataColumn> {
        val columns = DSL.table(DSL.sql("information_schema.columns")).`as`("c")
        fun field(name: String) = DSL.field(DSL.name("c", name), String::class.java)
        val rows = ctx.select(DSL.substring(field("table_name"), 1, 129), DSL.substring(field("column_name"), 1, 129),
            DSL.substring(field("data_type"), 1, 129)).from(columns)
            .where(field("table_catalog").eq(DSL.field("current_database()", String::class.java)))
            .and(field("table_schema").eq(DSL.field("current_schema()", String::class.java)))
            .and(field("table_name").`in`(REQUIRED.keys)).limit(257)
            .fetch { DucklakeMetadataColumn(it.value1(), it.value2(), it.value3()) }
        if (rows.size > 256) invalid("physical layout exceeds the column budget")
        if (rows.any { listOf(it.tableName, it.columnName, it.dataType).any { text -> text.length > 128 } }) {
            invalid("physical layout name/type exceeds the text budget")
        }
        REQUIRED.forEach { (table, fields) -> fields.forEach { (name, type) ->
            if (rows.count { it.tableName == table && it.columnName == name && it.dataType == type } != 1)
                invalid("missing or mismatched physical column $table.$name ($type)")
        } }
        return rows.sortedWith(compareBy({ it.tableName }, { it.columnName }))
    }

    private fun requireSnapshot(snapshot: Long) {
        if (snapshot < 0 || !ctx.fetchExists(ctx.selectOne().from(DSL.table(DSL.name("ducklake_snapshot")))
                .where(DSL.field(DSL.name("snapshot_id"), Long::class.javaObjectType).eq(snapshot)).limit(1))) {
            invalid("snapshot $snapshot is not visible in this read session")
        }
    }

    private fun active(alias: String, snapshot: Long) = number(alias, "begin_snapshot").le(snapshot)
        .and(number(alias, "end_snapshot").isNull.or(number(alias, "end_snapshot").gt(snapshot)))
    private fun table(name: String, alias: String): Table<*> = DSL.table(DSL.name(name)).`as`(alias)
    private fun number(alias: String, name: String): Field<Long> = DSL.field(DSL.name(alias, name), Long::class.javaObjectType)
    private fun flag(alias: String, name: String): Field<Boolean> = DSL.field(DSL.name(alias, name), Boolean::class.javaObjectType)
    private fun text(alias: String, name: String): Field<String> = DSL.substring(DSL.field(DSL.name(alias, name), String::class.java), 1, MAX_TEXT + 1)
        .`as`(name)

    private fun <T : Record> bounded(rows: List<T>): List<T> {
        if (rows.size > MAX_ROWS) invalid("result exceeds the $MAX_ROWS row budget")
        if (rows.any { row -> row.intoArray().any { it is String && it.length > MAX_TEXT } }) invalid("text exceeds the $MAX_TEXT character budget")
        return rows
    }

    private fun <T : Any> required(row: Record, field: Field<T>): T = row.get(field) ?: corrupt("NULL required ${field.name}")
    private fun nonnegative(value: Long?): Long? = value?.also { if (it < 0) corrupt("negative count/identity $it") }
    private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 rich metadata: $message")
    private fun corrupt(message: String): Nothing = throw DucklakeCatalogCorruptionException("Final-1.1 rich metadata: $message")

    companion object {
        const val MAX_ROWS = 65536
        const val MAX_TEXT = 16384
        private val REQUIRED = mapOf(
            "ducklake_schema" to mapOf("schema_id" to "BIGINT", "schema_uuid" to "UUID", "begin_snapshot" to "BIGINT",
                "end_snapshot" to "BIGINT", "schema_name" to "VARCHAR", "path" to "VARCHAR", "path_is_relative" to "BOOLEAN", "parent_schema_id" to "BIGINT"),
            "ducklake_data_file" to mapOf("data_file_id" to "BIGINT", "table_id" to "BIGINT", "begin_snapshot" to "BIGINT",
                "end_snapshot" to "BIGINT", "record_count" to "BIGINT", "row_group_count" to "BIGINT", "path" to "VARCHAR", "path_is_relative" to "BOOLEAN"),
            "ducklake_delete_file" to mapOf("delete_file_id" to "BIGINT", "data_file_id" to "BIGINT", "table_id" to "BIGINT",
                "begin_snapshot" to "BIGINT", "end_snapshot" to "BIGINT", "delete_count" to "BIGINT", "format" to "VARCHAR",
                "row_group_count" to "BIGINT", "path" to "VARCHAR", "path_is_relative" to "BOOLEAN"),
            "ducklake_file_column_stats" to mapOf("data_file_id" to "BIGINT", "table_id" to "BIGINT", "column_id" to "BIGINT",
                "column_size_bytes" to "BIGINT", "value_count" to "BIGINT", "null_count" to "BIGINT", "contains_nan" to "BOOLEAN",
                "min_value" to "VARCHAR", "max_value" to "VARCHAR", "min_is_exact" to "BOOLEAN", "max_is_exact" to "BOOLEAN"),
            "ducklake_table_column_stats" to mapOf("table_id" to "BIGINT", "column_id" to "BIGINT", "contains_null" to "BOOLEAN",
                "contains_nan" to "BOOLEAN", "min_value" to "VARCHAR", "max_value" to "VARCHAR", "min_is_exact" to "BOOLEAN", "max_is_exact" to "BOOLEAN"),
            "ducklake_table" to mapOf("table_id" to "BIGINT", "table_uuid" to "UUID", "schema_id" to "BIGINT",
                "begin_snapshot" to "BIGINT", "end_snapshot" to "BIGINT", "table_name" to "VARCHAR", "path" to "VARCHAR", "path_is_relative" to "BOOLEAN"),
            "ducklake_files_scheduled_for_deletion" to mapOf("data_file_id" to "BIGINT", "path" to "VARCHAR", "path_is_relative" to "BOOLEAN"),
        )
    }
}
