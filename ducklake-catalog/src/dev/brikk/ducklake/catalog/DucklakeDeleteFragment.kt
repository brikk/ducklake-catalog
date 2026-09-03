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
 * Describes a delete Parquet file written during a DELETE/UPDATE/MERGE operation.
 * Each fragment corresponds to one data file's deletions.
 *
 * [deleteCount] is the TOTAL positions stored in the new parquet file (union of the
 * prior-active-delete-file positions plus this commit's new deletes) — persisted to
 * `ducklake_delete_file.delete_count`, which live-row-count math subtracts.
 * [newDeleteCount] is the DELTA added by THIS commit only. It is informational: the catalog no
 * longer subtracts it from `ducklake_table_stats.record_count`, which is the gross row count
 * (see [DucklakeTableStats]). Retained on the wire for compatibility.
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
data class DucklakeDeleteFragment(
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
)
