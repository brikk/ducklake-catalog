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

/**
 * SQL-paged root-schema metadata companion. Every call requires its owner's opt-in final-1.1
 * readSession. Drain each scope in that SAME view with total caller work/node/path/byte budgets.
 * Empty page means end; partial column/name/spec pages do not attest a complete validated tree.
 * Legacy list methods are unchanged. No writes, cleanup, lease or scan capability follows.
 */
interface DucklakePlanningMetadata {
    fun listSchemasPage(snapshotId: Long, afterSchemaId: Long?, limit: Int): List<DucklakeSchema>
    fun listTablesPage(schemaId: Long, snapshotId: Long, afterTableId: Long?, limit: Int): List<DucklakeTable>
    fun getColumnsPage(tableId: Long, snapshotId: Long, afterColumnId: Long?, limit: Int): List<DucklakeColumn>
    fun getPartitionFieldsPage(tableId: Long, snapshotId: Long, after: DucklakePartitionFieldCursor?, limit: Int): List<DucklakePartitionFieldPageEntry>
    fun getFilePartitionValuesPage(tableId: Long, snapshotId: Long, after: DucklakeFilePartitionCursor?, limit: Int): List<DucklakeFilePartitionValue>

    /** Immutable mapping rows by registry column_id, not target field ID; preserve unknown flags. */
    fun getNameMappingPage(mappingId: Long, afterMappingColumnId: Long?, limit: Int): List<DucklakeNameMappingRow>

    /** Registry versions including absent physical tables (hasLiveRows=false), so a page never hides its seek position. */
    fun getInlineVersionsPage(tableId: Long, snapshotId: Long, afterSchemaVersion: Long?, limit: Int): List<DucklakeInlinedDataInfo>
}

@JvmRecord
data class DucklakePartitionFieldCursor(val partitionId: Long, val partitionKeyIndex: Int)

@JvmRecord
data class DucklakePartitionFieldPageEntry(val cursor: DucklakePartitionFieldCursor, val tableId: Long, val field: DucklakePartitionField)

@JvmRecord
data class DucklakeFilePartitionCursor(val dataFileId: Long, val partitionKeyIndex: Int)

@JvmRecord
data class DucklakeNameMappingRow(
    val mappingColumnId: Long, val sourceName: String, val targetFieldId: Long, val parentMappingColumnId: Long?, val isPartition: Boolean?,
)
