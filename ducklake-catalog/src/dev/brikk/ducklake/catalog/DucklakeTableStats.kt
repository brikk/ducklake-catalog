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
 * A `ducklake_table_stats` row.
 *
 * @property recordCount the GROSS row count: rows ever written into the table's active data files
 *   (and inlined tables), never reduced by DELETE. Upstream defines it this way and DuckDB uses
 *   `record_count == live count` as the proof that no row was ever deleted and therefore that the
 *   cached global column min/max are exact (`ducklake_scan.cpp` `min_max_exact`). It is NOT a live
 *   row count — use [DucklakeCatalog.getLiveRowCount] for that.
 * @property fileSizeBytes Σ `file_size_bytes` of the active data files.
 */
@JvmRecord
data class DucklakeTableStats(
    val tableId: Long,
    val recordCount: Long,
    val fileSizeBytes: Long,
)
