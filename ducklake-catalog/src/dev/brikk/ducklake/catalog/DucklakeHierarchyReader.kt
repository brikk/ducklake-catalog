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
import java.time.Instant
import java.time.OffsetDateTime

/** Parent-aware read-only companion. Every operation requires the owning opt-in final-1.1 readSession. */
interface DucklakeHierarchyReader {
    fun listSchemas(snapshotId: Long): List<DucklakeQualifiedSchema>
    fun getSchemaById(schemaId: Long, snapshotId: Long): DucklakeQualifiedSchema?

    /** Each component is one literal identifier: dots/quotes inside a component are never parsed as SQL. */
    fun findSchema(components: List<String>, snapshotId: Long): DucklakeQualifiedSchema?
    fun listTables(schemaId: Long, snapshotId: Long): List<DucklakeQualifiedTable>
    fun getTableById(tableId: Long, snapshotId: Long): DucklakeQualifiedTable?
    fun findTable(schemaComponents: List<String>, tableName: String, snapshotId: Long): DucklakeQualifiedTable?

    /** Keyset page of retained references, including ended files and dropped owners; NOT deletion permission. */
    fun listRetainedFiles(kind: DucklakeFileKind, afterFileId: Long?, limit: Int): List<DucklakeOwnedFile>

    /** All scheduled path references, including future/not-yet-eligible entries; no age filter or acknowledgements. */
    fun listScheduledReferences(after: DucklakeScheduledPathCursor?, limit: Int): List<DucklakeScheduledPathReference>

    /** Age-gated bounded queue batch; relative queue paths use the catalog root, never a table directory. */
    fun listScheduledFiles(olderThan: Instant, limit: Int): List<DucklakeResolvedScheduledFile>
}

@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeQualifiedSchema(
    val metadata: DucklakeSchemaMetadata,
    val schemaIds: List<Long>,
    val names: List<String>,
    val dataPath: String?,
)

@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeQualifiedTable(val table: DucklakeTable, val schema: DucklakeQualifiedSchema, val dataPath: String?)

/** Owner qualification uses the file's original begin snapshot, even when that snapshot has expired. */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeOwnedFile(
    val kind: DucklakeFileKind,
    val fileId: Long,
    val owner: DucklakeQualifiedTable,
    val beginSnapshot: Long,
    val endSnapshot: Long?,
    val path: String,
    val pathIsRelative: Boolean,
    val resolvedPath: String,
)

@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeResolvedScheduledFile(
    val dataFileId: Long,
    val path: String,
    val pathIsRelative: Boolean,
    val scheduleStart: OffsetDateTime,
    val resolvedPath: String,
)

/** Exact path-reference cursor, not a deletion-queue row identity (which additionally includes time). */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
data class DucklakeScheduledPathCursor(val dataFileId: Long, val path: String, val pathIsRelative: Boolean)

@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
data class DucklakeScheduledPathReference(val cursor: DucklakeScheduledPathCursor, val resolvedPath: String)
