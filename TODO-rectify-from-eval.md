# TODO — rectify findings from the 2026-09 eval

Findings from a correctness / upstream-parity review of `ducklake-catalog` against the DuckDB
`ducklake` extension (`references/ducklake`, v1.5-variegata @ d8a1881) and pg_ducklake
(`references/pg_ducklake` @ e43c6b8). Line numbers are as of commit `b83d2ce`.

Each item has a stable ID so it can be picked up one at a time. Work an item by:
1. Re-verifying the claim against the cited library + upstream lines (drift happens).
2. Adding/adjusting a test that fails before the fix (for BUG items, ideally a DuckDB
   round-trip via the `ducklake` JDBC extension so DuckDB itself is the oracle).
3. Fixing, then checking the box and noting the commit here.

Severity legend:
- **BUG** — produces metadata DuckDB misreads, yields wrong query results, or can corrupt a
  shared lake.
- **DRIFT** — readable/valid but diverges from upstream, or a newer upstream behaviour is missing.
- **NIT** — cosmetic / defensive.

Sections:
- **W** write path / DDL / stats / `changes_made`
- **R** read path / inlined data / change feed / maintenance
- **C** commit protocol, conflict detection, retry, multi-backend SQL
- **Q** code quality, API hygiene, tests, resources
- **S** `slt-format` + `ducklake-test-corpus-replay`
- **X** build / CI / docs

Baseline at review time: `./gradlew test` green (slt-format 20, ducklake-catalog 166,
corpus-replay 1 identity-control test; full-corpus replay 431/471 files, 7777 records, 0 failures).
Generated jOOQ bindings verified in sync with upstream DDL (28/28 tables, all columns) — no action.

Suggested order of attack: W-B1, W-B4, W-B5 (catalog becomes unloadable for DuckDB) → R-B1, R-B2,
C-B3, W-B2, W-B3 (wrong results / data loss) → C-B1, C-B2, R-B5, C-B5 (backend-specific breakage)
→ everything else.

---

## W — Write path (verified against upstream source)

### W-B1 · BUG · `ducklake_view.column_aliases` written as opaque JSON / NULL — DuckDB cannot load the catalog
- [x] **Status:** RESOLVED (catalog side; trino-ducklake side pending its own test pass).
  `createView` / `replaceViewMetadata` now take `columnAliases: List<String>` + `tags: Map<String, String?>`;
  `insertViewRow` always writes `DucklakeQuotedList.encode(columnAliases)` (empty list → `""`, never NULL), so
  the invariant is type-enforced — no runtime check needed. Engine-private metadata goes to `ducklake_tag`.
  Readers report `DucklakeView.malformedColumnAliases` for legacy rows instead of throwing.
  Covered by `TestDucklakeQuotedList` + `TestJdbcDucklakeCatalogViewTags` (includes a stock-DuckDB ATTACH
  round-trip). Remaining nit: `renameView` (`:3051`) copies a legacy malformed `column_aliases` verbatim — fine
  (rename shouldn't silently rewrite), but a `REPLACE VIEW` is the way to heal such a row.
- Library: `JdbcDucklakeCatalog.kt:2317-2338` (`insertViewRow` → `COLUMN_ALIASES = viewMetadata`, nullable). Caller
  (trino-ducklake `DucklakeMetadata.kt:1506-1515`) passes a JSON blob.
- Upstream: writes `ToQuotedList(aliases)` (`""` when none, never NULL) at
  `ducklake_metadata_manager.cpp:2678`; reads via `ParseQuotedList(row.GetValue<string>(6))` at `:769`.
  `ParseQuotedValue` (`common/ducklake_util.cpp:17-19`) throws unless the first char is `"`; `GetValue<string>()`
  on SQL NULL yields the literal `"NULL"`, which also throws.
- Effect: `BuildCatalogForSnapshot` loads every view for a schema → a single library-created view makes **every
  DuckDB query on the lake fail** with `Failed to parse quoted value`.
- Fix direction: write `""` (or a real `"a","b"` quoted list of output column names) into `column_aliases`; move
  connector-private view metadata to `ducklake_tag` (object_id = view_id), which upstream already loads
  (`ducklake_metadata_manager.cpp:746-773`). Coordinate with trino-ducklake `DucklakeMetadata.kt` (create /
  replaceViewMetadata / getView readers). Add a DuckDB-oracle test: create view via library, then
  `SELECT * FROM lake.information_schema.tables` through DuckDB.

### W-B2 · BUG · `ducklake_table_stats.record_count` decremented on DELETE — DuckDB then folds MIN/MAX to stale bounds
- [x] **Status:** RESOLVED. `record_count` is now gross everywhere: `applyDeleteFragments` no longer decrements;
  `netRewriteStats` subtracts the retired sources' GROSS rows (not the merged count) and then rebuilds
  `ducklake_table_column_stats` from the surviving per-file stats (upstream `RecomputeGlobalStatsAfterRewrite`);
  `analyzeTable(tableId)` recomputes gross (Σ active file `record_count` + rows still in inlined tables) and skips
  the column-stat rebuild while live inlined rows exist (their values are in no per-file stats). New
  `getLiveRowCount(tableId, snapshotId)` implements upstream's net formula for engines' row-count estimates.
  `analyzeTable(tableId, rowCount)` is `@Deprecated` (rowCount ignored). KDoc on `DucklakeTableStats`,
  `DucklakeDeleteFragment.newDeleteCount` (informational), `getTableStats`, `commitDelete`, `rewriteDataFiles`.
  Tests: `TestJdbcDucklakeCatalogGrossRecordCountInterop` — pre-fix DuckDB returned `max(id)=5, count(*)=4`
  after a library DELETE of the max row; post-fix `4, 4`. `getLiveRowCount` cross-checked against DuckDB
  `count(*)` across deletes, consolidation, time travel and TRUNCATE.
  **trino-ducklake follow-up:** `DucklakeMetadata.getTableStatistics` (`:423-426`) must use
  `catalog.getLiveRowCount(tableId, snapshotId)` instead of `tableStats.recordCount`; `finishStatisticsCollection`
  should call `analyzeTable(tableId)`.
- Library: `JdbcDucklakeCatalog.kt:3946-3955` (`applyDeleteFragments` subtracts delete count), `:3879-3892`
  (`netRewriteStats`), `:1220-1226` (`analyzeTable` sets it to the live count). `DucklakeDeleteFragment.kt:18-24`
  documents the decrement as intended. `truncateTable` (`:2630-2678`) correctly leaves it alone — internally
  inconsistent.
- Upstream: `record_count` is gross rows ever inserted; `MergeFileStats` only adds (`ducklake_stats.cpp:206-215`);
  no stats write on delete. `ducklake_scan.cpp:199-213` computes `min_max_exact = record_count == net_count` and
  the optimizer folds `SELECT MIN(c)/MAX(c)` to `ducklake_table_column_stats` when exact.
- Effect: after a library DELETE removes the row holding a column's min or max, `record_count == net` still holds
  → DuckDB returns the **wrong MIN/MAX**.
- Fix direction: stop decrementing in `applyDeleteFragments` / `netRewriteStats`; make `analyzeTable` preserve
  gross semantics (recompute from Σ `ducklake_data_file.record_count` over all files ever begun, or leave
  `record_count` alone and refresh only `file_size_bytes` + column aggregates). Update the interface KDoc in
  `DucklakeCatalog.kt:213-235`. Check trino-ducklake's use of `getTableStats().recordCount` as a row-count
  estimate — it will need to compute net = Σ active file record_count − Σ delete_count instead.

### W-B3 · BUG · `ducklake_column_mapping.mapping_id` allocated from `next_catalog_id`; upstream uses `next_file_id`
- [x] **Status:** RESOLVED. `applyInsertFragments` allocates `mapping_id` via `tx.allocateFileId()`.
  `TestJdbcDucklakeCatalogAddFilesNameMapInterop` pins the invariant on the snapshot row (`next_catalog_id`
  unchanged, `next_file_id += files + maps`, `mapping_id` inside that range) and proves interop: a DuckDB session
  that had already cached the table's name maps reads the library-`add_files`'d file through the new map; DuckDB's
  own `ducklake_add_data_files` then REUSES the library-written map for an identical schema and allocates a
  non-colliding id for a different one.
- Library: `JdbcDucklakeCatalog.kt:3425` (`tx.allocateCatalogId()`).
- Upstream: `ducklake_transaction_state.cpp:532` (`MappingIndex new_map_id(commit_snapshot.next_file_id++)`).
  `LoadNameMaps` (`ducklake_catalog.cpp:719-733`) only reloads `mapping_id >= loaded_name_map_index`, watermarked
  by `snapshot.next_file_id`; a miss throws `Unknown name map id` (`ducklake_transaction.cpp:2090`).
- Effect: (a) a long-lived DuckDB session never sees a library-written mapping below its watermark →
  `add_files`'d files unreadable; (b) two counters → a later DuckDB-written mapping can **reuse the same
  `mapping_id`**, and `GetColumnMappings` merges the two maps' rows into one corrupt map.
- Fix direction: allocate from `next_file_id` (`tx.allocateFileId()` or equivalent). Adjust tests in
  `TestJdbcDucklakeCatalogAddFiles*` / name-map tests that assert id ranges.

### W-B4 · BUG · `dropColumn` end-snapshots only one level of children — grandchildren left dangling
- [x] **Status:** RESOLVED (uncommitted). `dropColumn` now loads the active column rows, throws
  `Column not found` for an inactive id (no snapshot minted), and end-snapshots `collectSubtreeIds(columnId)` —
  the same recursive cascade `dropField` already used. KDoc updated. Test:
  `TestJdbcDucklakeCatalogDropCascadesInterop.dropColumnEndSnapshotsWholeNestedSubtreeAndDuckDbStillLoads`
  (DuckDB-created `struct<a, mid struct<b, c>>` + `list<struct>` sibling; asserts no active row has an inactive
  parent, time travel intact, then stock DuckDB ATTACHes, DESCRIBEs, reads current and `AT (VERSION => …)`).
  Verified the test fails on the pre-fix predicate (`b`, `c` left active).
- Library: `JdbcDucklakeCatalog.kt:3030-3038` (`COLUMN_ID = id OR PARENT_COLUMN = id`). `dropField`
  (`:3279-3285`) already does the right recursive `collectSubtreeIds`.
- Upstream: `ducklake_table_entry.cpp:1190-1194` (`RemoveColumns` recurses); reader
  `ducklake_metadata_manager.cpp:736-741` throws `Could not find parent column for column %s`.
- Effect: after dropping any `struct<struct<…>>`, `list<struct<…>>`, `map<…,struct<…>>` column the **catalog is
  unloadable for DuckDB**.
- Fix direction: reuse `collectSubtreeIds` in `dropColumn`. Test with a 3-level nested column + DuckDB oracle read.

### W-B5 · BUG · `dropSchema` only checks for tables — active views/macros orphaned
- [x] **Status:** RESOLVED (uncommitted). `DucklakeWriteTransaction.activeObjectKindsInSchema` checks
  `ducklake_table` / `ducklake_view` / `ducklake_macro`; `dropSchema` throws the new typed
  `DucklakeSchemaNotEmptyException(schemaName, remainingObjectKinds)` (message: `… schema is not empty (still has
  views, macros)`). Test: `…DropCascadesInterop.dropSchemaRefusesWhileViewsOrMacrosRemainAndDuckDbStillLoads`
  (library view → refused; DuckDB `CREATE MACRO` → refused; both dropped → succeeds; DuckDB ATTACH still loads).
  Follow-up for trino-ducklake: map `DucklakeSchemaNotEmptyException` → `TrinoException(SCHEMA_NOT_EMPTY)` in
  `translateCatalogExceptions` (today it surfaces wrapped in the generic `Failed to drop schema` RuntimeException —
  see Q-1).
- Library: `JdbcDucklakeCatalog.kt:2388` (`hasTablesInSchema`), `DucklakeWriteTransaction.kt:128-140`.
- Upstream: `ducklake_schema_entry.cpp:416-470` (`TryDropSchema` refuses on tables, views, macros unless CASCADE);
  reader `ducklake_catalog.cpp:568-573` throws `could not find schema that corresponds to the view entry`.
- Effect: catalog unloadable for DuckDB.
- Fix direction: also check active `ducklake_view` and `ducklake_macro` rows for the schema; raise the same
  "schema is not empty" error.

### W-B6 · BUG · `renameColumn` / `setColumnType` / `setFieldType` discard `initial_default` / `default_value`
- [ ] **Status:** open
- Library: `JdbcDucklakeCatalog.kt:3076-3086`, `:3125-3135`, `:3215-3226` — re-inserted row hard-codes
  `initial_default = NULL`, `default_value = 'NULL'`.
- Upstream: `ducklake_transaction_state.cpp:1173-1177` carries `GetColumnInfo(field)` incl. defaults;
  `ducklake_table_entry.cpp:1048-1051, 1108-1123`. `initial_default` is what readers substitute for files written
  before `ADD COLUMN … DEFAULT x` (`ducklake_catalog.cpp:386`).
- Effect: changes values DuckDB returns for old rows of a column that had a default (DuckDB/pg_ducklake-created).
- Fix direction: copy `initial_default`, `default_value`, `default_value_type`, `default_value_dialect` from the
  end-snapshotted row into the replacement row.

### W-B7 · BUG · No encryption awareness — writes `encryption_key = NULL` into an encrypted lake
- [ ] **Status:** open
- Library: never reads `ducklake_metadata.encrypted`; `JdbcDucklakeCatalog.kt:3403-3436`, `:3920-3937` always
  write NULL.
- Upstream: `ducklake_metadata_manager.cpp:1044-1047` throws `Database is encrypted, but file %s does not have an
  encryption key`.
- Fix direction (minimum): read `encrypted` at open/first write and **refuse all writes** with a clear
  `CatalogException` when `encrypted = 'true'`. Full support (per-file keys) is a separate feature.

### W-B8 · BUG · Global column-stat merge tightens bounds incorrectly (and W-B2 makes DuckDB trust them)
- [ ] **Status:** open
- `mergedMinBound` / `mergedMaxBound` (`JdbcDucklakeCatalog.kt:4335-4347`): `existing == null → candidate`
  resurrects a bound upstream had marked unknown; `candidate == null → existing` keeps a bound when the new file has
  none. Upstream `ducklake_stats.cpp:129-183`: once `has_min = false` with values present it stays false, and a file
  lacking min forces `has_min = false`.
- `AggregatedColumnStats.merge` (`:3967-3985`) and `aggregateActiveColumnStats` (`:1305-1310`) skip NULL per-file
  bounds instead of poisoning the aggregate.
- `DucklakeStatTypes.compare` (`DucklakeStatTypes.kt:109-126`) compares temporal and boolean stats lexically;
  upstream `RequiresValueComparison` (`include/storage/ducklake_stats.hpp:18-20`) casts numerics, temporals and
  booleans. Mixed lake: DuckDB writes `2024-01-01 00:00:00`, Trino extractor writes `2024-01-01T00:00:00`
  (`trino-ducklake DucklakeStatsExtractor.kt:175-185`); `' ' < 'T'` → wrong extreme chosen.
- Fix direction: (1) NULL-poisoning merge semantics matching upstream; (2) typed comparison for
  timestamp/timestamptz/date/time/boolean (normalise `T` vs space; compare as `Instant`/`LocalDateTime` etc.);
  (3) same fix applies to the read-side pruning comparator (see P-1).

### W-D1 · DRIFT · Snapshot / `schema_version` bookkeeping
- [ ] **Status:** open
- `ducklake_schema_versions` rows with `table_id = NULL` for view/schema DDL (`JdbcDucklakeCatalog.kt:2101-2110`,
  `DucklakeWriteTransaction.kt:161-163`), and rows for *dropped* tables (`:2625`). Upstream writes per-table rows
  only for new/altered tables (`ducklake_transaction_state.cpp:1572-1586`); its V0.3→0.4 migration deletes NULL-table
  rows. Harmless (readers filter by table_id) but should stop emitting them.
- `setTableComment` / `setColumnComment` (`:2933-2991`) do **not** bump `schema_version`; upstream does for any ALTER
  (`ducklake_transaction_state.cpp:67-70, 1715-1718`). DuckDB caches the catalog per `schema_version`
  (`ducklake_catalog.cpp:348-360`) → stale comments until the next DDL. Bump it.

### W-D2 · DRIFT · `changes_made` vocabulary and conflict-matrix staleness
- [ ] **Status:** open
- Rename table emits `altered_table:<id>` (`:2786`); upstream emits `created_table:"s"."new"` and no drop
  (`ducklake_transaction.cpp:832-838`, `_state.cpp:331-341`). Same for rename view (`:2270` vs `created_view`).
  Consequence: an upstream concurrent `CREATE TABLE s.new` is **not detected as a name collision by either side** →
  two active rows with the same name. Emit `created_table`/`created_view` for the new name as upstream does.
- Library emits `flushed_inlined:`; upstream v1.5 emits `inline_flush:` (`_state.cpp:389`) and parses both
  (`ducklake_transaction_changes.cpp:82-84`). Switch to `inline_flush` for parity.
- Rewrite paths emit `deleted_from_table`+`inserted_into_table` (`:3697-3698`) / `inserted_into_table` (`:3738`)
  instead of `rewrite_delete` / `merge_adjacent` (`_state.cpp:394-395`). Safe (upstream is conservative) but consider
  emitting the upstream kinds now that they exist.
- Conflict matrix (`ConflictMatrix.kt:147-180`) is a port of an older upstream: v1.5 additionally conflicts
  insert-vs-`deleted_from_table`/`inlined_delete` and delete-vs-`inserted_into_table`/`inlined_insert`
  (`_state.cpp:203-224`). Re-port the matrix; fix source comments citing `ducklake_transaction.cpp:1184-1314` (moved
  to `ducklake_transaction_state.cpp`).
- `InterveningChanges.applyEntry` (`InterveningChanges.kt:173-207`) is case-sensitive; upstream parser is
  case-insensitive (`CIEquals`). NIT-level; make it case-insensitive.
- pg_ducklake's direct-insert path writes `changes_made = 'inlined_data_insert'` with no `:value`
  (`pg_ducklake/src/pgducklake_metadata_manager.cpp:812-816`). Both upstream and `InterveningChanges.parse`
  (`:138-142`) throw on it → a library retry whose intervening range includes such a snapshot fails
  non-retryably. Decide: tolerate bare kinds (treat as table-unknown conservative conflict) or document.
- Library never writes `author` / `commit_message` / `commit_extra_info` and ignores `require_commit_message`
  (`_state.cpp:49-56`). Consider exposing author/commit_message on the write API.

### W-D3 · DRIFT · `ducklake_data_file` — flush and rewrite shape
- [ ] **Status:** open
- Flush: library registers the file at `begin_snapshot = new`, `partial_max = NULL` and end-snapshots inlined rows
  (`:2680-2732`); upstream sets `begin_snapshot = min embedded snapshot`, `partial_max = max`
  (`ducklake_insert.cpp:137-147`, `metadata_manager.cpp:3749-3754`) and *deletes* the inlined rows (`:5105-5124`).
  Both yield consistent time travel; upstream's shape keeps `$snapshot_id` exact on flushed rows. Consider matching.
- Rewrite (`rewriteDataFiles`): library assigns fresh `row_id_start` and advances `next_row_id` (`:3690`); upstream
  preserves the first source's `row_id_start` (`functions/ducklake_compaction_functions.cpp:481, 657-665`) so row
  ids are stable across compaction. Upstream also recomputes global stats after `rewrite_data_files`
  (`_state.cpp:793-929`); library only nets sizes.

### W-D4 · DRIFT · `ducklake_file_column_stats`
- [ ] **Status:** open
- Library always writes non-NULL `value_count` / `null_count` / `column_size_bytes`; upstream writes NULL when
  unknown (`ducklake_transaction.cpp:1198-1212`). Allow nullable in `DucklakeWriteFragment` stats.
- Upstream `StatsToString` (`common/ducklake_util.cpp:92-99`) writes NULL for values containing `\0`; library stores
  verbatim → Postgres rejects NUL in text → commit fails. Mirror upstream.
- `extra_stats` / `ducklake_file_variant_stats` never written (no geometry/variant support). Document as
  unsupported.
- Trino extractor hard-codes `containsNan = false` (`DucklakeStatsExtractor.kt:70`) and the library writes explicit
  FALSE for float columns (`:3462-3463`) → asserts "no NaN" without checking; upstream trusts it for pruning
  (`ducklake_stats.cpp:325`). Either compute NaN presence in the extractor or write NULL (unknown).

### W-D5 · DRIFT · `ducklake_table_column_stats.contains_nan` NULL vs explicit `false`
- [ ] **Status:** open
- Library writes SQL NULL for float columns without NaN (`:3586`, `:1320`); upstream writes explicit `false`
  (`ducklake_transaction.cpp:1166-1169`, `metadata_manager.cpp:4441-4446`). DuckDB only builds float global stats
  when `has_contains_nan && !contains_nan` (`ducklake_stats.cpp:325`) → **DuckDB gets no global stats for
  library-written float columns**. Write explicit `false`.

### W-D6 · DRIFT · `ducklake_delete_file` superseding shape
- [ ] **Status:** open
- Library end-snapshots the prior active delete file and writes a plain `(file_path, pos)` union file
  (`:3908-3915`); upstream v1.5 DELETEs the prior row + schedules the file (`metadata_manager.cpp:3832-3869`) and
  writes a 3-column file with per-position `_ducklake_internal_snapshot_id`, `begin_snapshot = min`,
  `partial_max = max` (`ducklake_delete.cpp:406-471`). Library's form is spec-valid and readable by upstream
  (`:1674-1679`, `ducklake_delete_filter.cpp:153-200`). `path_is_relative` hard-coded `true` (`:3926`).
  Low priority; document the divergence.

### W-D7 · DRIFT · Catalog table columns
- [ ] **Status:** open
- `default_value_dialect` left NULL (`:2528-2539`); upstream writes `'duckdb'` (`:2397, 2426`). Write it.
- `dropTable` omits end-snapshotting `ducklake_tag`, `ducklake_column_tag`, `ducklake_sort_info` (`:2556-2627` vs
  `:2278-2290`); `dropView` omits `ducklake_tag` (`:2229-2237` vs `:2292-2298`). Orphan rows are inert but bloat.
- `ducklake_table.path` / `ducklake_schema.path` always `"<name>/"` (`:2417`, `:2375`); upstream falls back to
  `<uuid>/` when the name has chars outside `[A-Za-z0-9_-]` (`ducklake_catalog.cpp:236-255`). Match it (path-unsafe
  names otherwise produce odd directories).
- `ducklake_files_scheduled_for_deletion`: library writes absolute paths (`:920-924`); upstream writes
  data_path-relative when possible (`_state.cpp:34-47`). Both readable; consider matching.
- Never written: `ducklake_sort_info/expression`, `ducklake_inlined_data_tables`, `ducklake_file_variant_stats`,
  macros, partition-spec changes after create. Document as unsupported in `DucklakeCatalog.kt`.

### W-D8 · DRIFT · No `ducklake_metadata.version` check
- [ ] **Status:** open
- Library never reads or writes `version`. Upstream accepts `0.1, 0.2, 0.3-dev1, 0.3, 0.4-dev1, 0.4, 1.0` and
  migrates to `1.0` (`ducklake_initializer.cpp:151-188`, migrations `metadata_manager.cpp:234-350`). Library inserts
  assume the 1.0 shape; on `<0.4` they fail loudly, on `0.4` they succeed silently.
- Fix direction: read `version` at open; refuse writes (clear error) unless version ∈ {`0.4`, `1.0`}; expose
  `getSpecVersion()`.

### W-D9 · DRIFT · Type strings not validated on DDL
- [ ] **Status:** open
- `TableColumnSpec.ducklakeType` is passed through verbatim (`:2534`); only `struct/list/map` recognised
  (`:3249`, `:3994-4016`). Upstream vocabulary (`common/ducklake_types.cpp:17-48, 98-147`): lowercase names,
  `decimal(w,s)` case-sensitive prefix, list child named `element`, map children `key`/`value` in order
  (`ducklake_table_entry.cpp:1447-1462`). Add a validator (reject unknown type names / casing) at the write boundary.

### W-N1 · NIT · Misc
- [ ] `renameColumn` end-snapshot filters `PARENT_COLUMN IS NULL` (`:3070`) and re-inserts without `parent_column`;
  passing a nested column id inserts a duplicate root row. Reject nested ids or handle them.
- [ ] Name-map `column_id` numbering starts at 1 (`:3613`) vs upstream 0 (`_state.cpp:494`). Harmless; align.
- [ ] `partition_id` / `partition_values` consistency not validated (upstream asserts `:3783-3785`).
- [ ] `snapshot_changes` row skipped when no changes (`:2113`); upstream always writes one. Unreachable today.

---


## R — Read path, inlined data, change feed, maintenance (verified against upstream source)

Verified consistent with upstream (no action): `activeAt` predicate (`SnapshotRange.kt:46-49`) used
uniformly for schema/table/column/view/data/delete/partition/sort/tag/column_tag/inlined rows;
`getDataFiles` join + `partial_max` handling ≡ `GetFilesForTable` (mm.cpp:1670-1680, 1065-1072);
`getDataFilesAddedBetween` ≡ `GetTableInsertions` (mm.cpp:1767-1770); inlined table naming/DDL/row
filter (mm.cpp:2471-2478, 3040-3044); inlined-delete table (mm.cpp:2824-2840); expire survivor tests +
cascade (mm.cpp:4835-4994); cleanup `schedule_start <` (ducklake_cleanup_files.cpp:24); orphan known
set (mm.cpp:4549-4584); NaN handling in pruning (mm.cpp:1239-1255); partition transform strings.

### R-B1 · BUG · Delete-file snapshot filtering gated on `partial_max > S`; upstream filters embedded snapshot ids unconditionally
- [x] **Status:** RESOLVED (catalog side). Contract rewritten on `DucklakeDataFile.deleteFilePartialMax` ("advisory
  only — never the gate; filter `_ducklake_internal_snapshot_id <= S` whenever the column exists") and on
  `hasPartialDeleteFilesRequiringSnapshotFilter`, whose predicate no longer requires `partial_max > S`.
  `TestJdbcDucklakeCatalogDeleteFileSnapshotFilterContract` pins the upstream fact live (flush-written delete file:
  2 embedded snapshot ids, `partial_max` NULL, active at the earlier one; DuckDB reads 3 rows then 2).
  **trino-ducklake follow-up (the behavioural half):** `DucklakeSplitManager.needsPartialDeleteSnapshotFilter`
  must return true for every PARQUET/PUFFIN delete file (drop the `deleteFilePartialMax` gate) so
  `DucklakeSplit.deleteFileSnapshotFilters` always carries S; the delete reader already ignores it for 2-column
  files.
- Library: `DucklakeDataFile.kt:52-57`, `DucklakeCatalog.kt:118-126` — deletions are windowed to `<= S` "only when
  `deleteFilePartialMax` exceeds S".
- Upstream: always calls `SetSnapshotFilter(read snapshot)` (`ducklake_multi_file_reader.cpp:279-284`,
  `ducklake_delete_filter.cpp:69-75`); never reads `del.partial_max` in `GetFilesForTable` (mm.cpp:1018-1020).
  Delete files written by `flush_inlined_data` for deleted inlined rows carry `_ducklake_internal_snapshot_id`
  with back-dated `begin_snapshot` = min embedded snapshot but **`partial_max = NULL`**
  (`ducklake_flush_inlined_data.cpp:176-186`, `ducklake_delete.cpp:144-146`, mm.cpp:3891-3893).
- Effect: a time-travel read at S between two embedded deletion snapshots applies future deletions → **rows missing**.
- Fix direction: expose "delete file has embedded snapshot column" (3-column file) independent of `partial_max`;
  connectors must always apply `_ducklake_internal_snapshot_id <= S` when the column is present. Update KDoc.

### R-B2 · BUG · Change feed misses deletions in flush-written / consolidated delete files
- [x] **Status:** RESOLVED (catalog side). `incrementalDeletions` now offers every delete file with
  `begin_snapshot <= end` that the catalog cannot prove irrelevant (`end_snapshot <= start` or recorded
  `partial_max < start`), mirroring upstream `GetTableDeletions`. Files that began before the window get
  `previous == current` so a 2-column file self-cancels to ∅ while a 3-column file is windowed per row. Contract
  spelled out on `DucklakeChangeFeedDeletion` / `getDeletionsBetween`. Tests in
  `TestJdbcDucklakeCatalogDeleteFileSnapshotFilterContract` (`changeFeedOffers…`, `changeFeedPrunes…`).
  **trino-ducklake follow-up:** `DucklakePageSourceProvider.isConsolidatedDelete` must detect the 3-column file by
  reading its schema (or always try `readPositionsWithSnapshots` and fall back), not by `currentDeletePartialMax`;
  otherwise flush-written files (partial_max NULL) are still diffed as 2-column and their later-snapshot
  deletions mis-attributed to `begin_snapshot`.
- Library: `deleteFileOverlapsWindow` (`JdbcDucklakeCatalog.kt:516-523`) uses `[begin, partial_max]` only when
  `partialMax != null && > begin`, else `begin in window`. A flush-written file with begin=s2, embedded {s2,s4},
  `partial_max NULL` is dropped for window [s4,s4].
- Upstream: selects all delete files with `begin_snapshot <= end` (mm.cpp:1850-1854) and windows per embedded row
  (`ducklake_delete_filter.cpp:371-372`).
- Fix direction: return every delete file with `begin_snapshot <= endSnapshot` whose data file is relevant and let
  the caller window by embedded snapshot id when the column exists.

### R-B3 · BUG · Nested-leaf numeric stats merged lexically in `getColumnStats` / `analyzeTable`
- [x] **Status:** RESOLVED (with W-B2). `getColumnStats` and `aggregateActiveColumnStats` now take their type map
  from `loadColumnTypes(ctx, tableId)` (every `ducklake_column` row, nested leaves included) — the same source the
  write path already used. Needed anyway because `netRewriteStats` now calls the recompute.
- Library: `getColumnStats` (`:1118-1119, 1151`) and `aggregateActiveColumnStats`/`analyzeTable` (`:1271-1272, 1304`)
  build `columnTypes` from `getTableColumns` (top-level only, `:308-339`); `ducklake_file_column_stats` rows exist
  per leaf `column_id`, so leaves get type `""` → `DucklakeStatTypes.compare` lexical ("9" > "10"). The write path
  `loadColumnTypes` (`:4318-4331`) correctly uses all rows — inconsistent.
- Effect: `analyzeTable` persists wrong `ducklake_table_column_stats` min/max for nested leaves; upstream feeds
  those into DuckDB optimizer statistics (`ducklake_table_entry.cpp:272-281`, `ducklake_stats.cpp:222`) → possible
  wrong/empty results in DuckDB after a library ANALYZE.
- Fix direction: use `getAllColumnsWithParentage` / `loadColumnTypes` for the type map in both places.

### R-B4 · BUG · Newer-delete detection keyed on `begin_snapshot >` misses upstream v1.5 consolidated delete files
- [ ] **Status:** PARTIAL — `assertNoNewerDeleteOnRewriteSources` and the new `assertNoDeleteNewerThanRead` (C-B3)
  now also test `partial_max > readSnapshotId`, which upstream sets on every consolidated regular delete. Still open:
  `checkDeleteFileOverlap` (retry-path matrix) uses `begin_snapshot` only, and a table-level
  compaction-vs-`tables_deleted_from` conflict as upstream does is not implemented.
- Upstream's 2nd+ DELETE on a data file with a committed delete file writes a consolidated file whose row has
  `begin_snapshot` = OLD file's begin (`ducklake_delete.cpp:405-470, 96-146`) and **DELETEs** the old row
  (mm.cpp:3860-3867; `ducklake_transaction_state.cpp:1511-1519`).
- Library: `assertNoNewerDeleteOnRewriteSources` (`:3810-3815`) and `checkDeleteFileOverlap` (`:4162-4167`) test
  `begin_snapshot > readSnapshot` → invisible. `ConflictMatrix.checkDeletedFromTable` (`ConflictMatrix.kt:163-180`)
  has no table-level delete-vs-delete/compact check.
- Effect: `rewriteDataFiles` can **resurrect rows deleted by a concurrent DuckDB DELETE**.
- Fix direction: compare `delete_file_id` / `partial_max` / row identity (not just `begin_snapshot`) against what
  the caller read; or conflict at table level on `tables_deleted_from` like upstream
  (`ducklake_transaction_state.cpp:263-266`). See also C-B3.

### R-B5 · BUG · Inlined reads swallow every `DataAccessException` as "table absent"
- [x] **Status:** RESOLVED. All 14 inlined-table catch sites go through `rethrowUnlessMissingTable`, which rethrows
  anything that is not the backend's undefined-table error (PG `42P01`, MySQL `42S02`/`1146`, DuckDB message);
  `isMissingCatalogSchema` now trusts a non-null SQLState (so PG `42703 undefined_column` can no longer be
  mistaken for a missing table via its HINT text). Test: `TestJdbcDucklakeCatalogInlinedReadsInterop` — a physical
  column renamed under the reader now throws instead of returning an empty result.
- Library: `getInlinedDataInfos` (`:1523-1537`), `readInlinedData` (`:1805-1814`), `readInlinedBeginSnapshots` /
  `readInlinedRowIds` (`:1833-1840`, `:1860-1867`), `getInlinedDeletes` (`:1653-1660`), `getInlinedChangesBetween`
  (`:1712-1716`), `hasInlinedRows`/`countInlinedRows`/`hasInlinedDeletes` (`:1567-1574`, `:1586-1593`,
  `:1614-1621`) return empty/false/0 on **any** SQL error (connection loss, timeout, permission, bad identifier —
  see C-B4) with only a DEBUG log → **silently missing rows**.
- Upstream throws for inlined-data query errors (mm.cpp:3004-3006) and only error-probes the inlined-delete table
  (mm.cpp:2988-2999).
- Fix direction: match on undefined-table SQLState (PG `42P01`, MySQL `42S02`, DuckDB "does not exist") like
  `isMissingCatalogSchema` (`:4238-4253`); rethrow everything else.

### R-D1 · DRIFT · No read-consistency envelope
- [ ] **Status:** open
- `currentSnapshotId` + every subsequent read run as separate autocommit statements (HikariCP defaults; no
  isolation set, `:108-150`). Upstream runs all metadata reads of a transaction on one connection
  (`ducklake_transaction.cpp:759-778`). Safe under row-versioning except against physically destructive ops
  (`merge_adjacent` deleting source rows mm.cpp:4703+, `DeleteOverwrittenDeleteFiles` :3860,
  `DeleteFlushedInlinedData` :5105-5115, library `expireSnapshots` / `rewriteDataFilesPartial`). E.g.
  `getDataFiles` then `findDataFileIdsInRange` / `getFilePartitionValues` can disagree on the file set → file
  dropped from plan.
- Fix direction: add a `readSession(snapshotId) { ... }` scope that pins one connection (REPEATABLE READ on PG/
  MySQL) for a planning pass; connectors call it around table-handle creation.

### R-D2 · DRIFT · Delete-file history model differs from upstream
- [ ] **Status:** open
- Library end-snapshots the prior delete row and inserts a new one (`:3908-3915`); upstream keeps ≤1 row per data
  file (old row deleted, new back-dated with `partial_max`). `getDeletionsBetween`'s `previousDelete` (`:484-489`)
  is meaningful for library-written data only. Interop: upstream `table_deletions` computes `previous_delete` as
  latest with `begin < START` (mm.cpp:1865-1866), so on library-written multi-row histories with two deletes in one
  window upstream **double-reports** the earlier one. Incremental-arm `snapshotId = begin` (`:510`) is the first
  deletion snapshot for consolidated files.
- Fix direction: adopt upstream's model (write 3-column delete files with embedded snapshot ids, back-date, set
  `partial_max`, DELETE + schedule the superseded row). Pairs with W-D6.

### R-D3 · DRIFT · `getSnapshotAtOrBefore` orders by `snapshot_id DESC`
- [ ] **Status:** open
- `:241-245`; upstream orders by `snapshot_time DESC` (mm.cpp:4116-4126) — differs under non-monotonic clocks.
  No `>=`/ASC lower-bound variant (upstream `SnapshotBound::LOWER_BOUND`).

### R-D4 · DRIFT · Inlined value typing per backend not normalised
- [ ] **Status:** open
- `readInlinedData` / `getInlinedChangesBetween` return raw JDBC values. On Postgres upstream stores VARCHAR/BLOB as
  `BYTEA`, DATE/TIMESTAMP*/UBIGINT/HUGEINT/nested as `VARCHAR` text, TINYINT→SMALLINT
  (`postgres_metadata_manager.cpp:14-79`) and casts back in the SELECT (`CastColumnToTarget`,
  `ducklake_inlined_data_reader.cpp:71-80`). Nothing in this library decodes `byte[]`→UTF-8 or parses DuckDB text
  literals (`rg ByteArray|BYTEA` → nothing). Zero tests for `readInlinedData` (see Q-5).
- Fix direction: add a per-backend `InlinedValueDecoder` that maps physical JDBC value → logical DuckLake type value;
  document the contract on `DucklakeCatalog.readInlinedData`.

### R-D5 · DRIFT · Expire GC gaps
- [ ] **Status:** PARTIAL — `ducklake_inlined_delete_<tableId>` is now dropped for dead tables (C-B5). Still open:
  dead `ducklake_tag` rows (upstream mm.cpp:5043-5052) are not deleted. Metadata leak only.

### R-D6 · DRIFT · `getColumnStats` NULL min/max handling
- [ ] **Status:** open
- `:1154-1159` skips NULL and keeps other files' bound → reported min/max is not a valid bound; upstream sets
  `has_min=false` on any file lacking it (`ducklake_stats.cpp:150-183`). Read-side twin of W-B8.

### R-D7 · DRIFT · Pruning comparison model (`parseStat`)
- [ ] **Status:** open
- `DucklakeStatTypes.kt:84-103` is lexical for temporal/interval/blob/varchar; upstream `TRY_CAST`s
  numeric/temporal/boolean (mm.cpp:1167-1173, `ducklake_stats.hpp:18-20`) and never prunes BLOB
  (mm.cpp:1080-1081, 1278-1279). Blob stats are DuckDB escaped text (`\xHH`) whose order ≠ byte order → **false
  prune** if callers push blob ranges; temporal correctness depends on caller bound format exactly matching stored
  `Value::ToString` form; varchar compares UTF-16 order vs upstream byte order (supplementary chars).
- Fix direction: never prune on BLOB; typed parse for temporal/boolean (shared with W-B8); compare varchar by
  UTF-8 bytes.

### R-N1 · NIT · Misc read path
- [ ] `getTableStats` `fetchOne()` (`:1111-1113`) throws on duplicate `table_id` rows (no PK); upstream filters
  `record_count IS NOT NULL` (mm.cpp:986-993).
- [ ] `SortTypes.kt:49-56, 70-77` throw on unknown direction/null_order; upstream treats non-`DESC` as ASC,
  non-`NULLS_FIRST` as NULLS_LAST (mm.cpp:878-884).
- [ ] Docs claim Puffin blobs embed `ducklake-snapshot-id` (`DucklakeCatalog.kt:121`, `:601-602`); upstream DV files
  carry no snapshot ids and `partial_max` NULL (`ducklake_delete.cpp:370-395`).
- [ ] `resolveColumnType` (`:3992-4018`) emits `struct<name:type,...>` without escaping names containing `:`/`,`/`<`.
- [ ] `getInlinedDeletes` KDoc (`DucklakeCatalog.kt:324-326`) — rows are also physically removed by
  `flush_inlined_data` (`ducklake_flush_inlined_data.cpp:571-576`), not only compaction.
- [ ] `listExpirableSnapshots(null,null)` returns all non-latest; upstream `expire_snapshots` with no criteria is a
  no-op (`ducklake_expire_snapshots.cpp:52-55`).
- [ ] `default_value` / `default_value_type` never surfaced on `DucklakeColumn` (mm.cpp:644, 716-726). Related to W-B6.

---

## C — Commit protocol, conflict detection, retry, multi-backend SQL

### C-B1 · BUG (DuckDB backends) · Snapshot PK collision not recognised; real error masked by failing `rollback()`
- [x] **Status:** RESOLVED. `isDuplicateKeyViolation` recognises DuckDB's `violates primary key constraint` /
  `violates unique constraint` / `PRIMARY KEY or UNIQUE constraint violation`; new `isWriteWriteConflict` covers
  DuckDB `Conflict on update!` / `write-write conflict`, PG `40001`/`40P01`, MySQL `1213`/`1205`;
  `isMetadataPrimaryKeyConflict` = either, with no table-name gate (upstream `RetryOnError` parity). All three
  `conn.rollback()` sites go through `rollbackQuietly`, which attaches a failing rollback as a suppressed exception
  instead of letting it replace the original. `TestConcurrentCommitOnLocalDuckDb` (schema-id collision + dueling
  `ducklake_table_stats` UPDATE) fails pre-fix with the opaque `RuntimeException("Failed to …")`, passes post-fix.
- `isDuplicateKeyViolation` (`JdbcDucklakeCatalog.kt:4255-4281`) matches PG `23505`, SQLite 19, MySQL 1062 only.
  DuckDB JDBC 1.5.5 (`SQLState=null`, `errorCode=0`) emits: same-connection dup → `Constraint Error: Duplicate key
  "id: 1" violates primary key constraint.`; concurrent writers → `commit()` throws `TransactionContext Error:
  Failed to commit: PRIMARY KEY or UNIQUE constraint violation: duplicate key "1"`; concurrent `UPDATE
  ducklake_table_stats` → `TransactionContext Error: Conflict on update!`. None match. `isMetadataPrimaryKeyConflict`
  (`:4198-4219`) additionally requires `_pkey` / `ducklake_snapshot.snapshot_id` in the message, which DuckDB never
  emits.
- DuckDB auto-aborts the tx on these errors, so `conn.rollback()` at `:2123` throws `cannot rollback - no transaction
  is active`, escaping the catch → `:2135` `RuntimeException("Failed to …", <rollback error>)` — original conflict
  lost. Same pattern `analyzeTable :1194`, `expireSnapshots :723`.
- Effect: on DuckDB, concurrent commits are never retried and surface as opaque INTERNAL errors.
- Fix direction: add DuckDB patterns (`violates primary key constraint`, `PRIMARY KEY or UNIQUE constraint
  violation`, `Conflict on update`, `write-write conflict`; cf. upstream `RetryOnError`
  `ducklake_transaction.cpp:1268-1282`); drop the `_pkey`/table-name gate for the snapshot insert; wrap `rollback()`
  in try/catch so the original exception propagates. Add a concurrency test on the DuckDB backend (all
  `TestConcurrent*` are PG-only).

### C-B2 · BUG (MySQL) · Catalog-id PK collisions inside the action are non-retryable
- [x] **Status:** RESOLVED (with C-B1) — any duplicate-key violation inside a write transaction is a metadata
  conflict; the bogus PK list is gone. `TestConcurrentCommitOnMySql` (schema-id and data-file-id collisions) fails
  pre-fix, passes post-fix. First MySQL and DuckDB concurrency tests in the suite.
- `:4198-4219`: MySQL's message is `Duplicate entry '5' for key 'ducklake_schema.PRIMARY'` — contains neither `_pkey`
  nor `ducklake_schema.schema_id`, so a concurrent `createSchema` (same `schema_id` from `next_catalog_id`) throws
  `RuntimeException("Failed to create schema")` instead of retrying. Same for `ducklake_data_file` /
  `ducklake_delete_file` PKs.
- Fix direction: treat any `isDuplicateKeyViolation` on a `ducklake_*` table as retryable (upstream does). Note
  `:4212-4216` lists PKs that don't exist upstream (`ducklake_table.table_id`, `ducklake_view.view_id`,
  `ducklake_column.column_id`, `ducklake_partition_info.partition_id`; see DDL mm.cpp:195-224).

### C-B3 · BUG (data loss) · `commitDelete` / `commitMerge` have no caller read-snapshot
- [x] **Status:** RESOLVED (catalog side). New overloads `commitDelete(tableId, fragments, readSnapshotId)` /
  `commitMerge(…, readSnapshotId)`; the old signatures remain as `@Deprecated` delegates (guard degrades to the
  attempt's base snapshot). `applyDeleteFragments` → `assertNoDeleteNewerThanRead` aborts with a non-retryable
  `LogicalConflictException` when a touched data file has a delete file with `begin_snapshot > read` **or**
  `partial_max > read` (upstream's consolidated shape — closes the same blind spot in
  `assertNoNewerDeleteOnRewriteSources`, partially addressing R-B4). Tests:
  `TestJdbcDucklakeCatalogDeleteReadSnapshotGuard` (5); `TestConcurrentDeleteVsDelete` now fails the loser on its
  first attempt. **trino-ducklake follow-up:** pass `tableHandle.snapshotId` at `DucklakeMetadata.kt:1415/1418`.
- `DucklakeCatalog.kt:602-612` — no `readSnapshotId` (unlike `rewriteDataFiles :643`). `applyDeleteFragments
  :3908-3915` end-snapshots every active delete file for the touched `data_file_id`s and inserts a replacement whose
  positions are the union computed by the caller at its planning snapshot R. `checkDeleteFileOverlap :4139-4177`
  only scans `(transactionStartSnapshotId, current]`, where `transactionStartSnapshotId` is set at the first commit
  attempt (`:2051-2053`), not R.
- Effect: a delete file committed by another writer in `(R, base]` is silently superseded → **its deletions are
  resurrected**. Upstream avoids this because `transaction_snapshot` is the DuckDB transaction's read snapshot
  (`ducklake_transaction_state.cpp:1679-1689`).
- Fix direction: add `readSnapshotId` to `commitDelete` / `commitMerge` (and `flushInlinedData`?) and fail
  non-retryably if any touched `data_file_id` has a delete file with `begin_snapshot > readSnapshotId` (mirror
  `assertNoNewerDeleteOnRewriteSources :3804`), subject to R-B4's caveat about consolidated files. Coordinate the
  API change with trino-ducklake / doris-ducklake.

### C-B4 · BUG (silent data loss on read) · User column names rendered unquoted in dynamic inlined-data SELECTs
- [x] **Status:** RESOLVED. `readInlinedData` / `getInlinedChangesBetween` project user columns via
  `DSL.quotedName(...)` (all other `DSL.name` uses are fixed lowercase identifiers). Test: DuckDB-inlined table with
  columns `"My Col"`, `"select"`, `"Order"` — pre-fix the library returned 0 rows (swallowed 42703), post-fix 2/3
  rows with correct time travel and change feed.
- `Settings.withRenderQuotedNames(EXPLICIT_DEFAULT_UNQUOTED)` (`:160`) + `DSL.name(sourceColumn.columnName)` at
  `:1688` and `:1782`. Verified render: `select My Col, select, begin_snapshot from ducklake_inlined_data_1_2 …`.
  DuckDB creates the inlined table with quoted identifiers, so mixed-case (PG folds → column not found), spaces, or
  reserved words break the query; the failure is then swallowed by R-B5 → **inlined rows vanish**. Injection: a
  table owner can craft a column name containing `;`/quotes embedded verbatim (SELECT-only context).
- Fix direction: `DSL.quotedName(...)` for all user-derived identifiers (audit every `DSL.name(` use); fix R-B5 in
  the same change. Numeric-only `ducklake_inlined_data_<t>_<sv>` / `ducklake_inlined_delete_<t>` names
  (`:1604, 1926`) are safe.

### C-B5 · BUG (MySQL) · DDL inside `expireSnapshots` breaks atomicity
- [x] **Status:** RESOLVED. `deadInlinedTableNames` captures the physical table names inside the transaction;
  `dropDeadInlinedTables` runs after `commit()` (connection switched to autocommit — otherwise PG would roll the
  drops back on pool return). Also drops `ducklake_inlined_delete_<t>` for dead tables (closes that half of R-D5).
  Test: `TestJdbcDucklakeCatalogExpireSnapshotsDropsInlinedTables` (first `expireSnapshots` test) — verified it
  fails without the autocommit switch.
- `dropDeadInlinedDataTables :757-772` issues `DROP TABLE IF EXISTS` inside the `expireSnapshots` transaction
  (`:656-726`). MySQL DDL implicitly commits; a later failure can't roll back the already-deleted
  `ducklake_snapshot` / `ducklake_data_file` rows. PG/DuckDB DDL is transactional.
- Fix direction: collect the drops and run them after `conn.commit()` (all backends, for uniformity).

### C-D1 · DRIFT · Isolation level never set; `analyzeTable` is a lost-update race
- [ ] **Status:** open
- `:2033` only sets `autoCommit=false`. PG READ COMMITTED fine. MySQL REPEATABLE READ: `ensureSnapshotLineageUnchanged
  :2140` and `LogicalConflictCheck` read the tx's consistent snapshot and can never observe an intervening commit;
  retry is triggered only by the snapshot PK collision (works, wastes an attempt).
- `ducklake_table_stats` update at `:3510-3517` is `SET record_count = <value read at :3377> + n` (read-modify-write) —
  safe only because the snapshot PK forces a retry; `netRewriteStats :3879` and `applyDeleteFragments :3949` correctly
  use `record_count - n`. Make `:3510-3517` relative too.
- **`analyzeTable :1180-1232` writes `record_count = <caller value>` with no snapshot insert and no lineage check** →
  true lost-update race against a concurrent `commitInsert`/`commitDelete`. Interacts with W-B2 (which will change
  what `analyzeTable` may write at all).

### C-D2 · DRIFT · ConflictMatrix vs upstream v1.5 (`ducklake_transaction_state.cpp:203-262`) — beyond W-D2
- [ ] **Status:** open
- `ConflictMatrix.kt:147-156` insert lacks `× tablesDeletedInlined` (upstream :207).
- `:163-180` delete lacks `× tablesInsertedInlined` (upstream :223).
- `:199-205` flush is stricter than upstream (:258-262) — also conflicts on `alteredTables` and
  `tablesInsertedInlined`. Conservative; fix the header comment claiming "lock-step".
- ConflictMatrix runs only on retry attempts (`:2091`); acceptable because first-attempt races hit the PK/lineage
  check first — document.
- `InterveningChanges.kt:204-206` throws `IllegalArgumentException` on an unknown change type → wrapped
  `RuntimeException` (`:2131`). Any future DuckDB change type makes **all retried commits fail** with an INTERNAL
  error. Consider: unknown kind → conservative "conflicts with everything on that table / all tables" instead of throw.

### C-D3 · DRIFT · `schema_version` / `ducklake_schema_versions` bookkeeping — beyond W-D1
- [ ] **Status:** open
- `DucklakeWriteTransaction.kt:148-151` keeps a single `schemaVersionTableId` (last writer wins); `:2101-2110` writes
  one row. Upstream `InsertNewSchema` (mm.cpp:5127-5137) writes one row per *altered existing* table and none for
  brand-new tables. `renameSchema :2795-2930` alters N tables but records `table_id=NULL`; `createTable :2491` records
  a row upstream wouldn't. Reads still resolve via `getSnapshotIdForSchemaVersion :1870` fallback.

### C-D4 · DRIFT · `WriteTransactionRetry`
- [ ] **Status:** open
- Retryable: only `TransactionConflictException.retryable()`. PG `40001`/`40P01`, MySQL `1213` deadlocks are not
  retried (upstream retries on "concurrent").
- No jitter (`WriteTransactionRetry.kt:87`); upstream uses a 0.5–1.0 random multiplier
  (`ducklake_transaction_state.cpp:1761-1765`) → lockstep retries under N-writer contention.
- `:2125` `throw e as RuntimeException` after walking the cause chain — CCE if a checked exception wraps a TCE.
- `:83` `InterruptedException` → bare `RuntimeException`.
- `:2128` `readLatestSnapshotId(txDsl)` after `rollback()` with `autoCommit=false` opens an implicit tx left for
  Hikari to roll back.
- `beforeWriteTransactionAction :1989-1990` is a `@Volatile public var` test seam on the production class.

### C-D5 · DRIFT · Quack backend routing is inconsistent / unverified
- [ ] **Status:** open
- Upstream `QuackMetadataManager::Query` (`quack_metadata_manager.cpp:15-31`) routes every statement (incl. the whole
  commit batch) through `quack_query_by_name` as one server-side call. Library mixes two paths in one "transaction":
  INSERTs via the attached catalog on the local connection, UPDATE/DELETE via `metadata.execute` →
  `CALL system.main.quack_query_by_name(...)` (`QuackWrappedMetadataQuery.kt:83-96`). Whether the wrapped statement
  participates in the local tx (and rolls back on `:2123`) is untested (`TestJdbcDucklakeCatalogOnQuackSmoke` only
  does `createSchema`). If it autocommits server-side, a lineage-conflict rollback leaves `end_snapshot = N+1` rows
  pointing at a snapshot that never lands.
- Routing is inconsistent: `dropColumn :3030`, `renameColumn :3065`, `setColumnType :3115`, `dropSchema :2394`,
  `endSnapshotActiveView :2342`, `applyInsertFragments :3510,3566`, `applyDeleteFragments :3910,3949`,
  `netRewriteStats :3881`, `rewriteDataFilesPartial :3726`, `removeScheduledFileRows :960` use
  `ctx.update/delete…execute()` directly, which the code's own comments (`:2562-2564`) say the Quack binder rejects
  ("Can only update base table"). So on Quack every DML/ALTER path except create* would fail.
- Fix direction: decide whether Quack write support is a goal. If yes: route all DML through one path and add a
  Quack write-path test (insert + delete + drop). If no: document read-only and make writes throw a clear error.

### C-D6 · DRIFT · Backend assumptions to document
- [ ] **Status:** open
- Local-file DuckDB catalog: one process holds the file lock; effectively single-writer-process (no concurrent DuckDB
  CLI / second cluster). Document in `DucklakeCatalogConfig` KDoc.
- `{METADATA_CATALOG}` prefixing replaced by `withRenderSchema(false)` + `USE <catalog>.main` (`:161-167`); relies on
  PG `search_path` / MySQL default DB → non-`public` PG schema / upstream `METADATA_SCHEMA` option unsupported.
- `:153-154` comment says `JDBCUtils.dialect(url)` "returns DEFAULT for backends jOOQ OSS doesn't recognize"; jOOQ
  3.19.22 OSS has a first-class `SQLDialect.DUCKDB`. Fix the comment.
- MySQL smoke test (`TestJdbcDucklakeCatalogOnMySqlSmoke.kt:103-137`) covers only `createSchema/createTable/
  dropTable/dropSchema` — no `commitInsert/commitDelete`, no stats upserts, no inlined data, no retry path despite
  the class comment (`:34-35`). Extend.

---

## Q — Code quality, API hygiene, tests, resources

### Q-1 · BUG (API) · Most failure paths are untyped `RuntimeException`
- [ ] **Status:** open
- 25 `throw RuntimeException/IllegalStateException/IllegalArgumentException` sites: `DucklakeWriteTransaction.kt:95,
  117` ("Schema/Table not found"), `JdbcDucklakeCatalog.kt:2253, 2304, 2348, 2389, 2461, 2744, 2751, 2763, 2804,
  2814, 3058, 3107, 3250, 3255, 3307, 3313, 3998, 4012`, `:222, 2047, 4184`. Everything thrown inside the action is
  additionally wrapped by `:2131` `RuntimeException("Failed to $op", e)`. `TestConcurrentDropDueling.kt:28-34` pins
  that a dueling drop loses with a generic "Table not found" `RuntimeException`. `guardInitialized` is applied to 5
  read methods (`:216-254`), never to writes.
- Effect: downstream engines can't map to NOT_FOUND / ALREADY_EXISTS / CONFLICT; they surface as INTERNAL errors.
  18/40 detekt-baseline entries (`TooGenericExceptionThrown`) are exactly these sites — the baseline hides an API
  problem.
- Fix direction: add `CatalogEntityNotFoundException` / `CatalogEntityAlreadyExistsException` (extending
  `DucklakeException`) in `CatalogExceptions.kt`; rethrow `DucklakeException` subclasses unwrapped at `:2122-2131`;
  apply `guardInitialized` to writes; delete the corresponding baseline entries.

### Q-2 · DRIFT · `JdbcDucklakeCatalog.kt` structure (4439 lines, ~110 functions, `LargeClass` baselined)
- [ ] **Status:** open
- Natural seams: (a) snapshot/schema/table/view reads `:216-360, 1953-1971`; (b) data-file/stats/pruning reads
  `:358-1180, 1327-1490`; (c) inlined-data reads `:1491-1935`; (d) maintenance `:594-970, 1180-1326`; (e) write engine
  `:1973-2216`; (f) DDL writers `:2218-3336`; (g) DML/compaction `:3337-3956`; (h) record→model mappers `:4349-4437`.
- Duplication: `findDeadTableIds/findDeadViewIds/findDeadMacroIds :774-867` (3 copies of the survivor test);
  `checkTableActive/checkSchemaActive/checkViewActive` (`LogicalConflictCheck.kt:146-215`); `hasActiveTable/
  hasActiveView :4085-4105`; six near-identical "dynamic inlined table + swallow" blocks (fix with R-B5).
- `readInlinedData/readInlinedBeginSnapshots/readInlinedRowIds :1755-1868` issue three separate autocommit queries
  that must "line up positionally" — fragile (see R-D1). Return one row type instead.
- Dead code: `DucklakeWriteTransaction.getConnection() :171`; `QuackBackedDuckDbCatalogUrl.host()/port()` (tests
  only); `:105-106` `@Suppress("SENSELESS_COMPARISON") if (config == null)` unreachable.

### Q-3 · NIT · `_idioms/kit-notes.json` under `src/`
- [ ] **Status:** open
- 7.4 KB agent-tooling changelog at `ducklake-catalog/src/dev/brikk/ducklake/catalog/_idioms/kit-notes.json` (+ twin
  under `test/src/…/_idioms/`). Not in the current jar (build-logic sets resources to `resources/`), but stale
  `build/libs/ducklake-catalog-0.0.1*.jar` do contain it. Move out of `src/` or delete.

### Q-4 · NIT · Public API hygiene
- [ ] **Status:** open
- Leaks that are "package-private in Java" leftovers per their own visibility notes: `ListClone.kt` public top-level
  `List<T>.clone()` (→ `ListCloneKt.clone`), `forConnection :188`, `DucklakeWriteTransaction` (whole class),
  `InterveningChanges` (public class, `internal` members), `MetadataQuery` public interface,
  `beforeWriteTransactionAction :1989`. Make `internal` where possible (check trino/doris consumers first).
- `WriteChange.InsertedIntoTable/DeletedFromTable :106-165` private-ctor-with-`Unit` marker to fake a compact
  constructor — a plain `data class` + `init { toSet() }` would do.
- `DucklakeCatalogConfig.catalogDatabaseUrl!!` (`:109`) → messageless NPE; `viewUuid?.toString()!!` (`:4428`).
- Wire classes (`@JvmRecord data class`, `@JsonInclude(NON_NULL)`, goldens in `TestJacksonWireFormat`) are fine;
  `DucklakeNameMap/Entry` documented as non-round-tripping (`TestJacksonWireFormat.kt:35-40`) — consider fixing.

### Q-5 · DRIFT · Test coverage gaps
- [ ] **Status:** open
- 42 `DucklakeCatalog` methods with no reference under `test/` or `testFixtures/`: `addField, commitMerge,
  countInlinedRows, dropField, expireSnapshots, flushInlinedData, getAllColumnsWithParentage, getColumnComments,
  getDataFilesAddedBetween, getDataFilesByIds, getDataPath, getDeletionsBetween, getInlinedChangesBetween,
  getInlinedDataInfos, getInlinedDeletes, getInlinedFileDeletesBetween, getLatestDataFileFormat, getNameMaps,
  getPartitionNameMaps, getSortKeys, getTableComment, getTableDataFileFormat, hasInlinedDeletes, hasInlinedRows,
  hasPartialDeleteFilesRequiringSnapshotFilter, listExpirableSnapshots, listFilesScheduledForDeletion,
  listReferencedFilePaths, listSnapshotChanges, listViews, readInlinedBeginSnapshots, readInlinedData,
  readInlinedRowIds, removeScheduledFileRows, renameSchema, renameTable, renameView, resolveSchemaVersionSnapshot,
  setColumnComment, setFieldType, setTableComment, truncateTable`. The entire inlined-data read path (R-B5, C-B4,
  R-D4) and all of expire/GC (C-B5, R-D5) are untested here (coverage may exist in trino-ducklake — confirm).
- `ConcurrentWriterHarness` parks the loser *before* mutations so only the lineage-check path is exercised; the
  snapshot-PK-collision classification (C-B1/C-B2) is never hit on any backend. Add a harness mode that parks
  *after* the snapshot INSERT.
- **Add a DuckDB-oracle interop test**: library writes (schema, table with nested cols, view, add/drop column,
  insert, delete, add_files w/ name map, comments) → DuckDB `ATTACH 'ducklake:postgres:...'` → `SELECT` /
  `information_schema` / `ducklake_table_info`. Would have caught W-B1, W-B3, W-B4, W-B5, W-D5 outright.

### Q-6 · NIT · HikariCP / resources
- [ ] **Status:** open
- `:146-150`: no `poolName` (multiple catalogs → `HikariPool-1/2…`), no `leakDetectionThreshold`, no
  `maxLifetime`/`keepaliveTime`; `autoCommit` left at default `true` for the shared `dsl`. `catch → rollback → throw`
  at `:2122-2131`, `:1194-1197`, `:722-725` lets a failing `rollback()` replace the original exception (C-B1).

---

## S — `slt-format` + `ducklake-test-corpus-replay`

Verified: `slt-format` runtime classpath is kotlin-stdlib only; corpus-replay jar ships no corpus SQL; corpus
submodule pin matches `references/ducklake` (d8a1881). Full-corpus replay locally: 471 files, 431 ran, 7777 records,
0 failures; 40 file-skips (11 concurrentloop, 5 mode, 5 unzip, 3 require-env, 5 skip-list, 11 require-gated).
Default starter set (`TestIdentityControl.kt:71`) covers 58/471 files.

### S-B1 · BUG · `SltParser` silently truncates after an orphaned `endloop`
- [ ] **Status:** open
- `SltParser.kt:54` `"endloop" -> return i` at top level makes `parse()` (`:19`, return value ignored) drop everything
  after the first `endloop` not owned by a parsed loop. Triggered by every `concurrentloop` (12 files): the loop line
  becomes `SltUnsupported`, its body is emitted once with `${i}` unresolved, and all records after `endloop` vanish
  (verified on `snapshot_info/ducklake_last_commit.test`: last parsed record line 117, `query I … 29` at line 124
  gone). Same for `skipif`/`onlyif` placed before a `loop`/`foreach` (guardSpan `:81-94` ends at first blank line →
  empty `SltLoop` body, real body escapes). Contradicts "skip-don't-throw" (`SltParser.kt:4-7`). `ReplayDriver`
  is unaffected only because it file-skips on any `SltUnsupported`.
- Fix direction: treat `concurrentloop` as a loop for parsing purposes (emit `SltUnsupported` wrapping the body),
  and at top level emit `SltUnsupported("stray endloop")` and continue.

### S-B2 · BUG · Conditions with `>`, `<`, `<>`, `>=`, `<=`, `&&` never match
- [ ] **Status:** open
- `SltExpander.evalCondition` (`SltExpander.kt:116-127`) and `ReplayDriver.evalCondition` (`ReplayDriver.kt:217-226`)
  only understand `var=value`; others fall through to a system-name compare (`"i>25" == "duckdb"` → false) so
  `skipif i>25` never skips. Upstream: `sqllogic_command.cpp:203-276`. Verified: `loop i 0 2 / skipif i>0 / SELECT
  ${i}` expands to both statements. Corpus: `snapshot_info/ducklake_last_commit.test:113,117` (masked by S-B1).
  Also: unbound var silently degrades (upstream throws, `:256-259`).

### S-B3 · BUG · `ReplayDriver` engine attachment never reset on DETACH / re-ATTACH
- [ ] **Status:** open
- `engineConnected` is set on the first ducklake ATTACH and never reset (`ReplayDriver.kt:140, 246-249`); 12 corpus
  files attach >1 distinct `ducklake:` target, often re-using alias `ducklake` (e.g.
  `data_inlining/partition_inlining.test:14,64,100,135` after `DETACH ducklake` at :57). Mirrored queries hit the
  engine's stale lake → false "diverged" failures. Not visible in identity control (engine = null).

### S-B4 · BUG (latent) · `Float` widened via `toDouble()` in `renderCell`
- [ ] **Status:** open
- `GoldenComparator.kt:75`: `0.1::FLOAT` → `0.10000000149011612`; relative error 1.49e-8 > 1e-9 tolerance → mismatch
  vs golden `0.1`. Passes today only because corpus FLOAT goldens are dyadic. Use `Float.toString()`.

### S-D1 · DRIFT · Parser/expander semantics vs upstream runner
- [ ] `statement maybe` with a message drops the expected error (`SltParser.kt:115-116`); upstream still requires a
  match if an error occurs (`result_helper.cpp:311-331`). 3 corpus records.
- [ ] `mode skip`/`unskip` → whole-file skip instead of "skip records until unskip" (`sqllogic_test_runner.cpp:846,
  969-972`). 5 files never replayed.
- [ ] No foreach special tokens (`<alltypes>`, `<numeric>`, `<integral>`, `<signed>`, `<unsigned>`, `<compression>`,
  `<all_types_columns>`, `!tok`) or comma iterators (`SltParser.kt:209`). 0 in corpus; matters for the "DuckDB-dialect"
  claim of a published library.
- [ ] Precedence: `SltExpander.kt:130` lets loop bindings win over env; upstream env wins (replaced first).
- [ ] `expectedError` loop-substituted (`SltExpander.kt:84`); upstream env-only. Lenient superset.
- [ ] `query` modifier parsing (`SltParser.kt:141-155`) treats any `con\d+` as connection, other tokens as label;
  upstream is positional (`sqllogic_test_runner.cpp:931-950`).
- [ ] Parser trims lines and ignores `#` inside SQL (`SltParser.kt:29,161`); upstream only recognises `#`/`----` at
  column 0 and stops the SQL block at a `#` line (`sqllogic_parser.cpp:23,60-63`).
- [ ] `KNOWN_UNSUPPORTED` (`SltParser.kt:11-12`) is dead — branches at `:67-74` are identical.
- [ ] `<FILE>:` results (3 `.test_slow` records) unhandled; `.test_slow` never discovered (`CorpusRunner.kt:39-49`).

### S-D2 · DRIFT · Comparator rendering vs DuckDB
- [ ] `Double.toString` (`GoldenComparator.kt:154`) differs from DuckDB for |x| ≥ 1e7 or < 1e-3 (`1.2345678E7` vs
  `12345678.0`, `1.0E-4` vs `0.0001`, `1.0E20` vs `1e+20`); masked under `I`/`R` by `numericEqual`, breaks for `T`
  columns and doubles nested in LIST/STRUCT. `'infinity'::TIMESTAMP` renders `294247-01-10 04:00:54.775807`.
- [ ] Nested-string quoting (`GoldenComparator.kt:126-138`) doesn't match DuckDB (`nested_to_varchar_cast.cpp:5-40`,
  `vector_cast_helpers.hpp:185-251`): DuckDB quotes only on empty/leading-or-trailing space/`null`/special chars and
  escapes with backslash; comparator quotes on interior whitespace and doubles quotes. KDoc `:119-122` claims parity.
- [ ] Booleans: `1/0` accepted only under `I` (`:234-237`); upstream accepts for any boolean column.
- [ ] `require no_alternative_verify` treated as unmet (`DuckDbOracle.kt:62`); upstream it is PRESENT except in
  ALTERNATIVE_VERIFY builds (`sqllogic_test_runner.cpp:562-568`). 4 `geo/*` files skipped for the wrong reason.
- [ ] Golden rows loop-substituted (`ReplayDriver.kt:348`); `statement error` messages not env-substituted
  (`:262, 270-279`); internal errors accepted for `statement error` (upstream never accepts,
  `result_helper.cpp:302-305`).
- [ ] Unmet `require` mid-file returns `fileSkipReason` (`ReplayDriver.kt:176`) and `CorpusReport.failures` only
  counts `ranFiles` (`CorpusRunner.kt:97-101`) → earlier failures hidden.
- [ ] Labelled queries with golden rows would be value-compared (`ReplayDriver.kt:324`); upstream hash-compares the
  label only.
- [ ] `ReplayReadEngine.kt:10-13` says "live-vs-live typed values"; comparisons are sorted string comparisons
  (`ReplayDriver.kt:385-400`). Fix doc.
- [ ] `SltModel.kt:9` lists `require-env` as modeled but the parser emits `SltUnsupported` (`SltParser.kt:41-44`);
  `SltModel.kt:19-21` unsupported list omits `require-env`, `unzip`, `reconnect`, `concurrentforeach`; `:11` omits
  `statement maybe`.

### S-D3 · DRIFT · Harness is not run in CI
- [ ] **Status:** open
- `.github/workflows/` has only publish workflows; none run `test`/`check`; `publish-snapshot.yml:33-34` says the
  submodule isn't fetched and tests don't run. `TestIdentityControl` also `assumeTrue`s away when the extension can't
  be installed or the submodule is missing (`TestIdentityControl.kt:50-62, 67`). Add a `ci.yml` running
  `./gradlew check` (with Docker) on PRs, and a nightly full-corpus job (`-Dducklake.corpus.dirs=all`).

---

## X — Build / CI / docs

### X-1 · DRIFT · README out of date
- [x] **Status:** RESOLVED — versions bumped to 0.4.0 / 0.5.0-SNAPSHOT; Releasing section describes AUTOMATIC
  publish + the Publish Pending Deployment escape hatch.
- `README.md` says "Latest release: **0.2.0**" and all coordinates use 0.2.0; current is `0.5.0-SNAPSHOT`, last
  release 0.4.0 (`git log`: `4765e25 Release 0.4.0`).
- README "Releasing" section describes **manual** Central Portal publish ("finish it by clicking Publish");
  `publish-release.yml` and `build.gradle.kts` (`publishToMavenCentral(true, DeploymentValidation.NONE)`) now
  auto-release (commits `30937d9`, `225afdb`). Update the section and mention the manual
  "Publish Pending Deployment" workflow.
- README snapshot example says `0.3.0-SNAPSHOT`.

### X-2 · NIT · Build requires JDK 25 to *launch* Gradle
- [x] **Status:** RESOLVED (documented) — README "Building" now says to use `mise install` + `mise exec -- ./gradlew`
  and explains why a JDK 21 shell fails. Lowering `build-logic`'s JVM target remains optional.
- `build-logic` is compiled for JVM 25, so `./gradlew` on a JDK 21 shell fails at configuration
  ("Dependency requires at least JVM runtime version 25") even though foojay would fetch the toolchain. Either
  document `mise use` / `JAVA_HOME` in README "Building", or lower build-logic's target so any recent JDK can
  launch and the toolchain resolver handles the rest.

### X-3 · NIT · No PR/CI verification workflow
- [ ] **Status:** open
- See S-D3. `checkAbi` (`ducklake-catalog/build.gradle.kts`) and detekt are wired into `check` but nothing runs
  `check` in GitHub Actions.
