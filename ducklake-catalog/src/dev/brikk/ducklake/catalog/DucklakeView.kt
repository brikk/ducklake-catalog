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
 * A view from `ducklake_view`, joined with its active `ducklake_tag` rows
 * (`object_id = view_id`) — the same shape upstream loads
 * (`ducklake_metadata_manager.cpp` view query + `ducklake_catalog.cpp` view entries).
 *
 * Views are snapshot-scoped: visible from [beginSnapshot] until [endSnapshot].
 *
 * @property sql the view's query text, in [dialect].
 * @property dialect the SQL dialect of [sql] (upstream writes `duckdb`; other engines
 *   write their own token). Readers must not execute [sql] in a dialect they don't speak.
 * @property columnAliases decoded `ducklake_view.column_aliases` — the spec quoted list
 *   of output column names (may be empty when the writer supplied none, or when the stored
 *   text was not a quoted list — see [malformedColumnAliases]).
 * @property malformedColumnAliases the raw `column_aliases` text when it is NOT a spec
 *   quoted list (a non-conformant writer's payload; upstream DuckDB refuses to load the
 *   whole catalog on it). Null when well-formed. Reported per view rather than thrown so one
 *   bad view cannot take down listing for a whole schema; consumers MUST refuse to serve
 *   such a view and should say why.
 * @property tags active `ducklake_tag` rows for this view. The key `comment` is the
 *   view comment upstream `COMMENT ON VIEW` reads/writes; other keys are engine-specific
 *   metadata upstream carries opaquely. Engine-specific keys SHOULD be namespaced
 *   (e.g. `trino.column_types`) so writers never collide.
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DucklakeView(
    val viewId: Long,
    val viewUuid: String,
    val schemaId: Long,
    val viewName: String,
    val sql: String,
    val dialect: String,
    val columnAliases: List<String>,
    val tags: Map<String, String>,
    val beginSnapshot: Long,
    val endSnapshot: Long?,
    val malformedColumnAliases: String? = null,
) {
    /** The upstream-interoperable view comment (`ducklake_tag` key `comment`), if any. */
    val comment: String?
        get() = tags[COMMENT_TAG_KEY]

    companion object {
        /** Upstream's tag key for `COMMENT ON` (tables, columns and views alike). */
        const val COMMENT_TAG_KEY: String = "comment"
    }
}
