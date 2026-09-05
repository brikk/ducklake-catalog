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

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_DATA_FILE
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_DELETE_FILE
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_FILES_SCHEDULED_FOR_DELETION
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_METADATA
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SCHEMA
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_TABLE
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.ZoneOffset

/** Constructed with the calling thread's context so every query honors a pinned read session. */
internal class JdbcDucklakeCleanupQueries(private val dsl: DSLContext, private val metadata: MetadataQuery) {
    fun getCleanupDataPath(): String? {
        val meta = DUCKLAKE_METADATA
        val path = DSL.substring(meta.VALUE, 1, DucklakeCleanupLimits.MAX_PATH_LENGTH + 1)
        val query = dsl.select(path).from(meta).where(meta.KEY.eq("data_path")).and(meta.SCOPE.isNull).limit(2)
        val paths = metadata.fetch(dsl, query) { row -> boundedPath(row.get(path)) }
        if (paths.size > 1) throw DucklakeCatalogCorruptionException("Duplicate catalog data_path roots in cleanup metadata")
        return paths.singleOrNull()
    }

    fun listScheduledFileBatch(olderThan: Instant, limit: Int): List<DucklakeScheduledFileEntry> {
        validateLimit(limit)
        val sched = DUCKLAKE_FILES_SCHEDULED_FOR_DELETION
        val path = DSL.substring(sched.PATH, 1, DucklakeCleanupLimits.MAX_PATH_LENGTH + 1)
        // DuckLake stores schedule_start at no finer than microsecond precision. Ceiling a finer Instant keeps
        // the strict comparison exact; letting the backend round the bind can omit an older row.
        val remainder = olderThan.nano % NANOS_PER_MICROSECOND
        val cutoff = if (remainder == 0) olderThan else olderThan.plusNanos((NANOS_PER_MICROSECOND - remainder).toLong())
        // Bind the unqualified timezone type: DuckDB rejects the generated PostgreSQL (6) type
        // modifier. This does not cast the stored value or change its timestamp precision.
        val query = dsl.select(sched.DATA_FILE_ID, path, sched.PATH_IS_RELATIVE, sched.SCHEDULE_START)
            .from(sched)
            .where(sched.SCHEDULE_START.lt(DSL.`val`(cutoff.atOffset(ZoneOffset.UTC), SQLDataType.TIMESTAMPWITHTIMEZONE)))
            .orderBy(sched.SCHEDULE_START, sched.DATA_FILE_ID, exactPathBytes(sched.PATH), sched.PATH_IS_RELATIVE)
            .limit(limit)
        return metadata.fetch(dsl, query) { row ->
            DucklakeScheduledFileEntry(
                validId(row.get(sched.DATA_FILE_ID), "scheduled data_file_id"),
                filePath(row.get(path)),
                required(row.get(sched.PATH_IS_RELATIVE), "scheduled path_is_relative"),
                required(row.get(sched.SCHEDULE_START), "schedule_start"),
            )
        }
    }

    fun listRetainedFileReferencePage(
        kind: DucklakeFileKind,
        afterFileId: Long?,
        limit: Int,
    ): List<DucklakeRetainedFileReference> {
        validateLimit(limit)
        require(afterFileId == null || afterFileId >= 0) { "afterFileId must be non-negative" }
        val fileTable = when (kind) {
            DucklakeFileKind.DATA -> DUCKLAKE_DATA_FILE
            DucklakeFileKind.DELETE -> DUCKLAKE_DELETE_FILE
        }
        val id = when (kind) {
            DucklakeFileKind.DATA -> DUCKLAKE_DATA_FILE.DATA_FILE_ID
            DucklakeFileKind.DELETE -> DUCKLAKE_DELETE_FILE.DELETE_FILE_ID
        }
        val tableId = DSL.field(DSL.name(fileTable.name, "table_id"), Long::class.java)
        val path = DSL.substring(DSL.field(DSL.name(fileTable.name, "path"), String::class.java),
            1, DucklakeCleanupLimits.MAX_PATH_LENGTH + 1)
        val relative = DSL.field(DSL.name(fileTable.name, "path_is_relative"), Boolean::class.java)
        val query = dsl.select(id, tableId, path, relative).from(fileTable)
            .where(afterFileId?.let { id.gt(it) } ?: DSL.noCondition())
            .orderBy(id).limit(limit)
        val files = metadata.fetch(dsl, query) { row ->
            FileRow(validId(row.get(id), "file_id"), validId(row.get(tableId), "file table_id"),
                filePath(row.get(path)), required(row.get(relative), "file path_is_relative"))
        }
        if (files.isEmpty()) return emptyList()
        val tables = tablePaths(files.mapTo(linkedSetOf()) { it.tableId })
        val schemas = schemaPaths(tables.values.mapTo(linkedSetOf()) { it.schemaId })
        return files.map { file ->
            val table = tables.getValue(file.tableId)
            val schema = schemas.getValue(table.schemaId)
            DucklakeRetainedFileReference(file.id, DucklakeTableFilePathRef(
                schema.id, schema.path, schema.relative, table.id, table.path, table.relative, file.path, file.relative,
            ))
        }
    }

    private fun tablePaths(ids: Set<Long>): Map<Long, TablePath> {
        val tab = DUCKLAKE_TABLE.`as`("tab")
        val version = DUCKLAKE_TABLE.`as`("version")
        val path = DSL.substring(tab.PATH, 1, DucklakeCleanupLimits.MAX_PATH_LENGTH + 1)
        // Limit the projection as well as each latest-version lookup. Do not materialize history,
        // and do not join files to parents: missing/duplicate parents must fail, not drop files.
        val query = dsl.select(tab.TABLE_ID, tab.SCHEMA_ID, path, tab.PATH_IS_RELATIVE).from(tab)
            .where(tab.TABLE_ID.`in`(ids))
            .and(tab.BEGIN_SNAPSHOT.eq(dsl.select(version.BEGIN_SNAPSHOT).from(version)
                .where(version.TABLE_ID.eq(tab.TABLE_ID)).orderBy(version.BEGIN_SNAPSHOT.desc()).limit(1)))
            .limit(ids.size + 1)
        val rows = metadata.fetch(dsl, query) { row ->
            TablePath(validId(row.get(tab.TABLE_ID), "table_id"), validId(row.get(tab.SCHEMA_ID), "table schema_id"),
                boundedPath(row.get(path)), row.get(tab.PATH_IS_RELATIVE))
        }
        return uniqueParents(rows, ids) { it.id }
    }

    private fun schemaPaths(ids: Set<Long>): Map<Long, SchemaPath> {
        val sch = DUCKLAKE_SCHEMA.`as`("sch")
        val version = DUCKLAKE_SCHEMA.`as`("version")
        val path = DSL.substring(sch.PATH, 1, DucklakeCleanupLimits.MAX_PATH_LENGTH + 1)
        val query = dsl.select(sch.SCHEMA_ID, path, sch.PATH_IS_RELATIVE).from(sch)
            .where(sch.SCHEMA_ID.`in`(ids))
            .and(sch.BEGIN_SNAPSHOT.eq(dsl.select(version.BEGIN_SNAPSHOT).from(version)
                .where(version.SCHEMA_ID.eq(sch.SCHEMA_ID)).orderBy(version.BEGIN_SNAPSHOT.desc()).limit(1)))
            .limit(ids.size + 1)
        val rows = metadata.fetch(dsl, query) { row ->
            SchemaPath(validId(row.get(sch.SCHEMA_ID), "schema_id"), boundedPath(row.get(path)), row.get(sch.PATH_IS_RELATIVE))
        }
        return uniqueParents(rows, ids) { it.id }
    }

    fun removeSelectedScheduledFiles(entries: Collection<DucklakeScheduledFileEntry>) {
        require(entries.size <= DucklakeCleanupLimits.MAX_BATCH_SIZE) { "Too many selected scheduled files" }
        // Validate the entire input before the first chunk can mutate anything.
        entries.forEach { entry ->
            require(entry.dataFileId >= 0) { "dataFileId must be non-negative" }
            require(entry.path.isNotEmpty() && entry.path.length <= DucklakeCleanupLimits.MAX_PATH_LENGTH) {
                "Selected path must have 1..${DucklakeCleanupLimits.MAX_PATH_LENGTH} characters"
            }
        }
        val sched = DUCKLAKE_FILES_SCHEDULED_FOR_DELETION
        entries.chunked(MUTATION_CHUNK_SIZE).forEach { chunk ->
            val selected = chunk.map { entry ->
                sched.DATA_FILE_ID.eq(entry.dataFileId)
                    .and(exactPathBytes(sched.PATH).eq(exactPathBytes(DSL.`val`(entry.path))))
                    .and(sched.PATH_IS_RELATIVE.eq(entry.pathIsRelative))
                    .and(sched.SCHEDULE_START.eq(DSL.`val`(entry.scheduleStart, SQLDataType.TIMESTAMPWITHTIMEZONE)))
            }
            metadata.execute(dsl, dsl.deleteFrom(sched).where(DSL.or(selected)))
        }
    }

    // Compare/order the original bytes, never collation-sensitive text. This also bypasses
    // PostgreSQL nondeterministic collations and DuckDB/Quack NOCASE without normalizing paths.
    private fun exactPathBytes(path: org.jooq.Field<String>): org.jooq.Field<ByteArray> {
        val expression = when (dsl.dialect().family()) {
            SQLDialect.MYSQL -> "cast({0} as binary)"
            SQLDialect.POSTGRES -> "convert_to({0}, 'UTF8')"
            SQLDialect.DUCKDB -> "encode({0})"
            else -> throw DucklakeInvalidOperationException("Exact cleanup path comparison is unsupported for ${dsl.dialect()}")
        }
        return DSL.field(expression, SQLDataType.VARBINARY, path)
    }

    private fun validateLimit(limit: Int) {
        require(limit in 1..DucklakeCleanupLimits.MAX_BATCH_SIZE) {
            "limit must be in 1..${DucklakeCleanupLimits.MAX_BATCH_SIZE}"
        }
    }

    private fun <T : Any> required(value: T?, name: String): T =
        value ?: throw DucklakeCatalogCorruptionException("Missing $name in cleanup metadata")

    private fun validId(value: Long?, name: String): Long = required(value, name).also {
        if (it < 0) throw DucklakeCatalogCorruptionException("Negative $name in cleanup metadata")
    }

    private fun boundedPath(path: String?): String? {
        if (path != null && path.length > DucklakeCleanupLimits.MAX_PATH_LENGTH) {
            throw DucklakeCatalogCorruptionException("Cleanup path exceeds ${DucklakeCleanupLimits.MAX_PATH_LENGTH} characters")
        }
        return path
    }

    private fun filePath(path: String?): String = required(boundedPath(path), "file path").also {
        if (it.isEmpty()) throw DucklakeCatalogCorruptionException("Empty file path in cleanup metadata")
    }

    private fun <T> uniqueParents(rows: List<T>, ids: Set<Long>, id: (T) -> Long): Map<Long, T> {
        val parents = rows.associateBy(id)
        if (parents.size != rows.size || parents.keys != ids) {
            throw DucklakeCatalogCorruptionException("Missing or duplicate latest cleanup hierarchy for IDs $ids")
        }
        return parents
    }

    private data class FileRow(val id: Long, val tableId: Long, val path: String, val relative: Boolean)
    private data class TablePath(val id: Long, val schemaId: Long, val path: String?, val relative: Boolean?)
    private data class SchemaPath(val id: Long, val path: String?, val relative: Boolean?)

    companion object {
        private const val MUTATION_CHUNK_SIZE = 64
        private const val NANOS_PER_MICROSECOND = 1000
    }
}
