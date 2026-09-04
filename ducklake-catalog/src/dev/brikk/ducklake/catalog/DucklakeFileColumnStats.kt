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
 * Per-file, per-leaf-column statistics (`ducklake_file_column_stats`).
 *
 * Counts and the NaN flag are OPTIONAL, exactly as in upstream DuckLake
 * (`DuckLakeColumnStatsInfo::FromColumnStats`): a writer that does not know a statistic passes
 * `null` and the row stores SQL NULL. Readers then treat the statistic as unknown — a NULL
 * `contains_nan` disables max-side pruning for float columns, NULL counts leave the table-level
 * `contains_null` unknown, etc. — rather than as a (wrong) zero or false. `columnSizeBytes` is
 * always written (upstream never NULLs it).
 *
 * @property valueCount number of NON-NULL values in the file for this column, or `null` if unknown.
 * @property nullCount number of NULL values, or `null` if unknown. Written together with
 *   [valueCount]: if either is unknown both are stored as NULL (upstream stores neither).
 * @property minValue lower bound in DuckLake text form (NULL = unknown; NUL-containing values are
 *   stored as NULL as upstream `StatsToString` does).
 * @property maxValue upper bound; for float columns it must EXCLUDE NaN — when [containsNan] is
 *   `true` neither bound is stored, since the max is then not a true upper bound.
 * @property containsNan for FLOAT/DOUBLE columns: `true` / `false` when the writer inspected the
 *   values, `null` when unknown. Ignored (stored as NULL) for every other type.
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DucklakeFileColumnStats(
    val columnId: Long,
    val columnSizeBytes: Long,
    val valueCount: Long?,
    val nullCount: Long?,
    val minValue: String?,
    val maxValue: String?,
    val containsNan: Boolean?,
) {
    /** Both counts are known (and therefore both are persisted). */
    val hasCounts: Boolean get() = valueCount != null && nullCount != null
}
