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
 * Describes a delete Parquet file written during a DELETE/UPDATE/MERGE operation.
 * Each fragment corresponds to one data file's deletions.
 *
 * [deleteCount] is the TOTAL positions stored in the new parquet file (union of the
 * prior-active-delete-file positions plus this commit's new deletes) — persisted to
 * `ducklake_delete_file.delete_count`, which live-row-count math subtracts.
 * [newDeleteCount] is the DELTA added by THIS commit only. It is informational: the catalog no
 * longer subtracts it from `ducklake_table_stats.record_count`, which is the gross row count
 * (see [DucklakeTableStats]). Retained on the wire for compatibility.
 *
 * ### Two delete-file shapes
 *
 * **Snapshot-tagged (upstream v1.5, preferred)** — [embeddedSnapshotMin] / [embeddedSnapshotMax]
 * set: the file has a third column `_ducklake_internal_snapshot_id` giving each deleted
 * position's deletion snapshot. Positions carried over from the superseded delete file keep
 * their snapshot (the superseded file's own embedded ids, or its `begin_snapshot` when it was a
 * 2-column file); this commit's new positions carry the COMMIT snapshot. The catalog then
 * registers the row exactly as upstream does — `begin_snapshot = embeddedSnapshotMin`,
 * `partial_max = embeddedSnapshotMax` — and DELETES the superseded row (scheduling its file for
 * physical removal) instead of end-snapshotting it, so a data file has at most one delete-file
 * row in its whole history and readers window it by the embedded ids. Because the new
 * positions must carry the snapshot the commit actually lands at, the writer uses
 * `readSnapshotId + 1` and the catalog aborts non-retryably if the commit snapshot turns out
 * different (another writer committed first; the file must be rewritten from a fresh read).
 *
 * In either shape DuckDB requires the positions in the file to be sorted and strictly increasing.
 *
 * **Plain (2-column, legacy)** — both null: the catalog end-snapshots the superseded row and
 * inserts the new one at the commit snapshot. Spec-valid and readable by upstream, but the
 * per-data-file history grows one row per DELETE and upstream's `table_deletions` can
 * double-report deletions across such rows (see TODO R-D2).
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DucklakeDeleteFragment @JvmOverloads constructor(
    val dataFileId: Long,
    val path: String,
    val deleteCount: Long,
    val fileSizeBytes: Long,
    val footerSize: Long,
    val newDeleteCount: Long,
    /**
     * Delete-file format: `"parquet"` (the default — `(file_path, pos)` positional delete file) or
     * `"puffin"` (a DuckLake deletion-vector blob, written when `write_deletion_vectors` is on).
     * Persisted to `ducklake_delete_file.format`; both are read by Trino and DuckDB.
     */
    val format: String = "parquet",
    /** MIN `_ducklake_internal_snapshot_id` in the file (3-column shape), else null. */
    val embeddedSnapshotMin: Long? = null,
    /** MAX `_ducklake_internal_snapshot_id` in the file (3-column shape) — must equal the commit snapshot. */
    val embeddedSnapshotMax: Long? = null,
) {
    /** True for the upstream 3-column shape (see the class comment). */
    val hasEmbeddedSnapshots: Boolean
        @com.fasterxml.jackson.annotation.JsonIgnore get() = embeddedSnapshotMin != null && embeddedSnapshotMax != null
}
