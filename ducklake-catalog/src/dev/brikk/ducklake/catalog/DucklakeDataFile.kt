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
 * Represents a data file from the ducklake_data_file table.
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DucklakeDataFile(
    val dataFileId: Long,
    val tableId: Long,
    val beginSnapshot: Long,
    val endSnapshot: Long?,
    val fileOrder: Long,
    val path: String,
    val pathIsRelative: Boolean,
    val fileFormat: String,
    val recordCount: Long,
    val fileSizeBytes: Long,
    val footerSize: Long,
    val rowIdStart: Long,
    val partitionId: Long?,
    val deleteFilePath: String?,
    val deleteFilePathIsRelative: Boolean?,
    val deleteFileFooterSize: Long?,
    val deleteFileFormat: String?,
    val mappingId: Long?,
    /**
     * `ducklake_data_file.partial_max` — the MAX `_ducklake_internal_snapshot_id` in a
     * cross-snapshot compacted ("partial") DATA file (`begin_snapshot` is the implicit MIN). NULL
     * for ordinary files. A read at snapshot S must drop the file's rows whose internal snapshot id
     * exceeds S; upstream skips the filter when `partial_max <= S` (every row qualifies) — that
     * shortcut is valid for data files because upstream always records `partial_max` when it
     * writes the embedded column (`ducklake_metadata_manager.cpp` `SetSnapshotFilter`).
     */
    val partialMax: Long? = null,
    /**
     * `ducklake_delete_file.partial_max` for the joined delete file — the MAX
     * `_ducklake_internal_snapshot_id` upstream recorded for a consolidated delete file. NULL for
     * ordinary delete files (or none).
     *
     * **Advisory only — do NOT gate the read-side snapshot filter on it.** Upstream applies
     * `_ducklake_internal_snapshot_id <= S` to every delete file that physically carries that
     * column, unconditionally (`ducklake_multi_file_reader.cpp` `SetSnapshotFilter(read snapshot)`,
     * `ducklake_delete_filter.cpp` `check_snapshots`), and never consults `partial_max` for delete
     * files. It also writes delete files whose embedded ids span several snapshots while leaving
     * `partial_max` NULL: `flush_inlined_data` emits one per data file with
     * `begin_snapshot = MIN(embedded)` and no max (`ducklake_flush_inlined_data.cpp`,
     * `DeleteFileSource::FLUSH`). A reader that filters only when `partial_max > S` therefore
     * applies FUTURE deletions on a time-travel read between two embedded snapshots and loses
     * rows. The correct rule: when the delete file has the column, keep only positions with
     * `_ducklake_internal_snapshot_id <= S`; when it doesn't (2-column file), apply all positions.
     * `begin_snapshot <= S` (already guaranteed by [DucklakeCatalog.getDataFiles]) is the only
     * catalog-level gate.
     */
    val deleteFilePartialMax: Long? = null,
) {
    /**
     * Backwards-compatible constructor for call sites that don't carry a name-map id.
     */
    constructor(
        dataFileId: Long,
        tableId: Long,
        beginSnapshot: Long,
        endSnapshot: Long?,
        fileOrder: Long,
        path: String,
        pathIsRelative: Boolean,
        fileFormat: String,
        recordCount: Long,
        fileSizeBytes: Long,
        footerSize: Long,
        rowIdStart: Long,
        partitionId: Long?,
        deleteFilePath: String?,
        deleteFilePathIsRelative: Boolean?,
        deleteFileFooterSize: Long?,
        deleteFileFormat: String?,
    ) : this(
        dataFileId, tableId, beginSnapshot, endSnapshot, fileOrder, path, pathIsRelative,
        fileFormat, recordCount, fileSizeBytes, footerSize, rowIdStart, partitionId,
        deleteFilePath, deleteFilePathIsRelative, deleteFileFooterSize, deleteFileFormat,
        null,
    )
}

/**
 * A raw storage-path reference held by the catalog (relative or absolute per [pathIsRelative]).
 * Used by orphan-file detection to build the "known set" of paths the catalog owns for a table.
 */
data class DucklakeFilePathRef(
    val path: String,
    val pathIsRelative: Boolean,
)

/**
 * A file row from `ducklake_files_scheduled_for_deletion`. NOTE: per the DuckLake spec the
 * relative paths in this table are relative to the **catalog global `data_path` root** (not a
 * per-table directory — that's why the table carries no `table_id`). [dataFileId] is the id of the
 * data- or delete-file row that was retired (used only to delete the schedule row after the
 * physical file is removed).
 */
data class DucklakeScheduledFile(
    val dataFileId: Long,
    val path: String,
    val pathIsRelative: Boolean,
)

/** Outcome of [DucklakeCatalog.expireSnapshots]. */
data class ExpireSnapshotsResult(
    val expiredSnapshotCount: Int,
    val scheduledFileCount: Int,
)

/**
 * One output file of a partial-emitting compaction ([DucklakeCatalog.rewriteDataFilesPartial]):
 * the registration [fragment] plus the back-dated [beginSnapshot] (= MIN of the source
 * begin_snapshots of the rows it holds) and [partialMax] (= MAX). The merged file physically
 * carries a per-row `_ducklake_internal_snapshot_id` column so a read at S keeps rows whose value
 * is `<= S`; these bounds are persisted onto the `ducklake_data_file` row.
 */
data class PartialMergedFile(
    val fragment: DucklakeWriteFragment,
    val beginSnapshot: Long,
    val partialMax: Long,
)
