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

import org.jooq.SQLDialect

/**
 * Secondary indexes this library recommends on a DuckLake metadata database.
 *
 * The DuckLake specification defines the `ducklake_*` tables and their columns but says nothing
 * about indexes, and the DuckDB extension's bootstrap script creates none beyond a handful of
 * primary keys. On a small catalog that is fine; on a large one every per-table lookup becomes a
 * sequential scan of a table whose size is proportional to the whole lake. Secondary indexes are
 * invisible to every DuckLake client — they change plans, never results — so adding them is
 * fully within the format.
 *
 * The set is tuned to this library's own query shapes:
 *  - `ducklake_table_stats` / `ducklake_table_column_stats` have no key at all, yet every insert
 *    probes them with `WHERE table_id = ?`. Under create/drop-heavy workloads these tables fill
 *    with dead rows between expiry passes (see `dropTable`), and the probe scans all of them.
 *  - `ducklake_data_file`, `ducklake_delete_file` and `ducklake_column` are read per table and
 *    snapshot on every plan and every write.
 *  - `ducklake_file_column_stats` and `ducklake_file_partition_value` are joined to data files by
 *    `data_file_id`.
 *  - `ducklake_snapshot(snapshot_time)` serves time travel by timestamp and `expire older than`;
 *    `ducklake_files_scheduled_for_deletion(schedule_start)` serves bounded cleanup batches.
 *
 * Only tables present in every writable catalog version (0.4 and 1.0) are indexed, and only
 * non-unique indexes are used: the metadata tables carry no uniqueness guarantees in the spec,
 * and a constraint could make another client's otherwise valid write fail.
 *
 * Apply them once, right after a new catalog has been bootstrapped, via
 * [DucklakeCatalog.ensureRecommendedIndexes], or run [createStatements] from whatever creates
 * the catalog. The library never applies them implicitly when opening an existing catalog.
 * Backends without a supporting dialect ([supports]) — the embedded DuckDB file and both Quack
 * routes — are skipped: DuckDB's ART indexes do not accelerate the range predicates used here
 * and add write overhead, and the Quack transports do not accept DDL.
 */
object DucklakeRecommendedIndexes {

    /** One recommended index. [columns] are in key order. */
    data class RecommendedIndex(
        val name: String,
        val table: String,
        val columns: List<String>,
        val rationale: String,
    ) {
        /**
         * `CREATE INDEX` DDL for this index. [ifNotExists] adds `IF NOT EXISTS` for backends that
         * accept it (PostgreSQL does; MySQL 8 does not).
         */
        fun createStatement(ifNotExists: Boolean): String {
            val guard = if (ifNotExists) "IF NOT EXISTS " else ""
            return "CREATE INDEX $guard$name ON $table (${columns.joinToString(", ")})"
        }
    }

    private fun index(table: String, suffix: String, vararg columns: String, rationale: String) =
        RecommendedIndex("idx_${table}_$suffix", table, columns.toList(), rationale)

    val ALL: List<RecommendedIndex> = listOf(
        index(
            "ducklake_table_stats", "table_id", "table_id",
            rationale = "every insert probes the table's stats row; the table has no key",
        ),
        index(
            "ducklake_table_column_stats", "table_id", "table_id",
            rationale = "every insert merges the table's column stats rows; the table has no key",
        ),
        index(
            "ducklake_data_file", "table_snapshots", "table_id", "begin_snapshot", "end_snapshot",
            rationale = "active-file listing per table and snapshot on every plan and write",
        ),
        index(
            "ducklake_delete_file", "table_snapshots", "table_id", "begin_snapshot", "end_snapshot",
            rationale = "active-delete-file listing per table and snapshot",
        ),
        index(
            "ducklake_delete_file", "data_file_id", "data_file_id",
            rationale = "delete files are joined to their data file on scans, deletes and drops",
        ),
        index(
            "ducklake_file_column_stats", "data_file_id", "data_file_id",
            rationale = "per-file statistics are joined to data files for pruning and ANALYZE",
        ),
        index(
            "ducklake_file_partition_value", "data_file_id", "data_file_id",
            rationale = "partition values are joined to data files for partition pruning",
        ),
        index(
            "ducklake_column", "table_id", "table_id",
            rationale = "column listing per table; the table has no key",
        ),
        index(
            "ducklake_table", "table_id", "table_id",
            rationale = "table lookup by id; the table has no key",
        ),
        index(
            "ducklake_table", "schema_id", "schema_id",
            rationale = "table resolution by schema and name",
        ),
        index(
            "ducklake_partition_info", "table_id", "table_id",
            rationale = "partition spec lookup per table",
        ),
        index(
            "ducklake_tag", "object_id", "object_id",
            rationale = "comments and tags are read per table or view",
        ),
        index(
            "ducklake_column_tag", "table_id", "table_id",
            rationale = "column comments are read per table",
        ),
        index(
            "ducklake_snapshot", "snapshot_time", "snapshot_time",
            rationale = "time travel by timestamp and expiry by age",
        ),
        index(
            "ducklake_files_scheduled_for_deletion", "schedule_start", "schedule_start",
            rationale = "bounded cleanup batches select the oldest scheduled files",
        ),
    )

    /** Whether the library applies these indexes on [dialect]. */
    fun supports(dialect: SQLDialect): Boolean =
        dialect.family() == SQLDialect.POSTGRES || dialect.family() == SQLDialect.MYSQL

    /** Whether `CREATE INDEX IF NOT EXISTS` is accepted on [dialect]. */
    fun supportsIfNotExists(dialect: SQLDialect): Boolean = dialect.family() == SQLDialect.POSTGRES

    /**
     * The DDL for [ALL] on [dialect], for callers that bootstrap the catalog with their own SQL.
     * Empty when [supports] is false.
     */
    fun createStatements(dialect: SQLDialect): List<String> =
        if (!supports(dialect)) emptyList()
        else ALL.map { it.createStatement(ifNotExists = supportsIfNotExists(dialect)) }
}

/** Outcome of [DucklakeCatalog.ensureRecommendedIndexes]. Index names are those of [DucklakeRecommendedIndexes.ALL]. */
data class DucklakeRecommendedIndexesResult(
    /** Indexes this call created. */
    val created: List<String>,
    /** Indexes that already existed. */
    val existing: List<String>,
    /** Why nothing was applied on this backend; null when the backend is supported. */
    val skippedReason: String? = null,
)
