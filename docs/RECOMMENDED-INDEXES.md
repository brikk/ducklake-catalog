# Recommended metadata indexes

The DuckLake specification defines the `ducklake_*` tables and their columns and
says nothing about indexes. The DuckDB extension's bootstrap script creates only a
few primary keys (`ducklake_snapshot`, `ducklake_snapshot_changes`, `ducklake_schema`,
`ducklake_data_file`, `ducklake_delete_file`). Every other table — including
`ducklake_table`, `ducklake_column`, `ducklake_table_stats` and
`ducklake_table_column_stats` — has no key at all, so a per-table lookup is a
sequential scan of a table whose size grows with the whole lake.

Secondary indexes are invisible to DuckLake clients: they change plans, never
results, and stock DuckDB keeps reading and writing the catalog unchanged. Adding
them is fully within the format. This library ships a recommended set tuned to its
own query shapes and applies it on request; it never adds, drops or alters anything
when merely opening a catalog.

## Applying them

Run once, right after the catalog has been bootstrapped (the library itself never
bootstraps a catalog; a DuckDB `ATTACH` or your own tooling does):

```kotlin
val result = catalog.ensureRecommendedIndexes()
// result.created  – indexes this call added
// result.existing – indexes that were already there
// result.skippedReason – non-null on backends where nothing is applied
```

The call is idempotent and safe to repeat or race: existing indexes are looked up
through JDBC metadata first; PostgreSQL then uses `CREATE INDEX IF NOT EXISTS`, and
on MySQL a concurrent `Duplicate key name` is treated as "already exists". Each index is one autocommit
DDL statement outside any snapshot transaction. On a large existing catalog, prefer
a maintenance window: `CREATE INDEX` takes a share lock on the table for the
duration of the build (use `CREATE INDEX CONCURRENTLY` by hand on PostgreSQL if that
matters).

Tooling that bootstraps the catalog with its own SQL can take the DDL from
`DucklakeRecommendedIndexes.createStatements(dialect)` instead.

Backends:

| Backend | `ensureRecommendedIndexes()` |
| --- | --- |
| PostgreSQL | creates the indexes below |
| MySQL | creates the indexes below (without `IF NOT EXISTS`) |
| DuckDB file, `jdbc:duckdb:quack://`, `jdbc:quack://` | no-op with `skippedReason` set — DuckDB's ART indexes do not accelerate the range predicates used here and add write overhead; the Quack transports do not accept DDL |

## The indexes

Only tables present in every writable catalog version (0.4 and 1.0) are indexed,
and every index is non-unique: the spec gives these tables no uniqueness guarantee,
and a constraint could make another client's otherwise valid write fail.

```sql
CREATE INDEX IF NOT EXISTS idx_ducklake_table_stats_table_id ON ducklake_table_stats (table_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_table_column_stats_table_id ON ducklake_table_column_stats (table_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_data_file_table_snapshots ON ducklake_data_file (table_id, begin_snapshot, end_snapshot);
CREATE INDEX IF NOT EXISTS idx_ducklake_delete_file_table_snapshots ON ducklake_delete_file (table_id, begin_snapshot, end_snapshot);
CREATE INDEX IF NOT EXISTS idx_ducklake_delete_file_data_file_id ON ducklake_delete_file (data_file_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_file_column_stats_data_file_id ON ducklake_file_column_stats (data_file_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_file_partition_value_data_file_id ON ducklake_file_partition_value (data_file_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_column_table_id ON ducklake_column (table_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_table_table_id ON ducklake_table (table_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_table_schema_id ON ducklake_table (schema_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_partition_info_table_id ON ducklake_partition_info (table_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_tag_object_id ON ducklake_tag (object_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_column_tag_table_id ON ducklake_column_tag (table_id);
CREATE INDEX IF NOT EXISTS idx_ducklake_snapshot_snapshot_time ON ducklake_snapshot (snapshot_time);
CREATE INDEX IF NOT EXISTS idx_ducklake_files_scheduled_for_deletion_schedule_start ON ducklake_files_scheduled_for_deletion (schedule_start);
```

Why each one:

| Index | Serves |
| --- | --- |
| `ducklake_table_stats (table_id)`, `ducklake_table_column_stats (table_id)` | Every insert probes and updates the table's stats rows. These tables have no key, and under create/drop-heavy workloads they fill with rows of dropped tables between expiry passes — the scan is of the whole lake's history, not the one table. (`dropTable` now removes its own rows immediately, which shrinks the problem; the index removes it.) |
| `ducklake_data_file (table_id, begin_snapshot, end_snapshot)` | Active-file listing per table and snapshot on every plan and every write. |
| `ducklake_delete_file (table_id, begin_snapshot, end_snapshot)`, `ducklake_delete_file (data_file_id)` | Active-delete-file listing per table; the join to the data file on scans, deletes and drops. |
| `ducklake_file_column_stats (data_file_id)`, `ducklake_file_partition_value (data_file_id)` | Per-file statistics and partition values joined to data files for pruning and `ANALYZE`. |
| `ducklake_column (table_id)`, `ducklake_table (table_id)`, `ducklake_table (schema_id)` | Column listing and table resolution; neither table has a key. |
| `ducklake_partition_info (table_id)`, `ducklake_tag (object_id)`, `ducklake_column_tag (table_id)` | Per-table partition spec, comments and tags. |
| `ducklake_snapshot (snapshot_time)` | Time travel by timestamp and expiry by age. |
| `ducklake_files_scheduled_for_deletion (schedule_start)` | Bounded cleanup batches select the oldest scheduled files. |

A DuckDB-side migration that rebuilds a table (rather than altering it in place)
silently drops that table's indexes; rerun `ensureRecommendedIndexes()` after
upgrading the catalog format.
