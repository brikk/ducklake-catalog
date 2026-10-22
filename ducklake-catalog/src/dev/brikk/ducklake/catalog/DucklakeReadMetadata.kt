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

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Optional read-only companion to [DucklakeCatalog]. Qualified for opt-in final-1.1 DuckDB metadata
 * over direct Java Quack, local read-only files and native Quack inside [DucklakeCatalog.readSession].
 * Other routes/formats fail explicitly.
 * Metadata is not permission to prune, aggregate exactly, resolve nested paths or write.
 */
interface DucklakeReadMetadata {
    fun getReadMetadataCapabilities(): DucklakeReadMetadataCapabilities

    /** Active schemas at a visible snapshot. Parent IDs are raw identity, not flattened names/paths. */
    fun listSchemaMetadata(snapshotId: Long): List<DucklakeSchemaMetadata>

    /** Active data/delete associations, keyed by IDs; row-group counts are advisory, not splits. */
    fun getFileReadMetadata(tableId: Long, snapshotId: Long): List<DucklakeFileReadMetadata>

    /** Stored file bounds for active files, NOT cast to the target snapshot's logical column type. */
    fun getFileColumnMetadata(tableId: Long, snapshotId: Long): List<DucklakeFileColumnMetadata>

    /** Unversioned native table statistics in this transaction, NOT historical snapshot statistics. */
    fun getTableColumnMetadata(tableId: Long): List<DucklakeTableColumnMetadata>
}

/** Actual required physical columns observed in the session, not inferred from a version string. */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
data class DucklakeMetadataColumn(val tableName: String, val columnName: String, val dataType: String)

@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeReadMetadataCapabilities(
    val formatVersion: String,
    val snapshotId: Long,
    val metadataBackend: String,
    val accessRoute: String,
    val engineVersion: String,
    val engineSourceId: String,
    val ducklakeExtensionVersion: String?,
    val quackExtensionVersion: String?,
    val unsignedExtensionsAllowed: Boolean,
    val jdbcDriverVersion: String,
    val observedColumns: List<DucklakeMetadataColumn>,
    val schemaParentIds: Boolean,
    val boundExactness: Boolean,
    val dataFileRowGroups: Boolean,
    val deleteFileRowGroups: Boolean,
    val qualifiedNestedSchemaResolution: Boolean,
    val qualifiedRangePruning: Boolean,
    val qualifiedExactAggregates: Boolean,
    val qualifiedWrites: Boolean,
)

/** NULL parent means root only with the schemaParentIds capability from this same session. */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeSchemaMetadata(val schema: DucklakeSchema, val parentSchemaId: Long?)

/**
 * NULL row-group count means unknown; zero remains zero. NULL deleteFileId means no active delete.
 * recordCount is gross physical rows, not a live count; deleteCount is the joined file's stored count.
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeFileReadMetadata(
    val tableId: Long,
    val dataFileId: Long,
    val recordCount: Long,
    val rowGroupCount: Long?,
    val deleteFileId: Long?,
    val deleteCount: Long?,
    val deleteFileFormat: String?,
    val deleteRowGroupCount: Long?,
)

/**
 * Exactness is independent per endpoint: NULL unknown, false inexact, true exact as recorded.
 * A missing bound stays missing even with true. Flags do not establish bound type, liveness of
 * individual rows, absence of NaNs, or suitability for an exact aggregate. No bound is cast here.
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeFileColumnMetadata(
    val tableId: Long,
    val dataFileId: Long,
    val stats: DucklakeFileColumnStats,
    val minIsExact: Boolean?,
    val maxIsExact: Boolean?,
)

/** Raw unversioned table bounds; same endpoint/type/NaN caveats as [DucklakeFileColumnMetadata]. */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeTableColumnMetadata(
    val tableId: Long,
    val columnId: Long,
    val containsNull: Boolean?,
    val containsNan: Boolean?,
    val minValue: String?,
    val maxValue: String?,
    val minIsExact: Boolean?,
    val maxIsExact: Boolean?,
)
