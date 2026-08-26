# Catalog TODO — upstream survey 2026-08-26

Hand-off from the `trino-ducklake` upstream-tracking pass (see that repo's
`dev-docs/RESEARCH-upstreams.md`). These items are **catalog-side** (read/prune
correctness that lives in `dev.brikk.ducklake.catalog`), so a fix here benefits
**every** consumer — `trino-ducklake` AND `doris-ducklake` both call this catalog.

Baselines surveyed: DuckLake `main` @ `a92e65b8` (spec bumped to `V1_1_DEV_1`),
`v1.5-variegata` @ `5ef9e03d` (DuckDB **1.5.5 released** — already adopted here in
`5cfe3d6`); cross-check from `datafusion-ducklake` v0.7.0 CHANGELOG.

Priority order: **P1** = bug-shaped (silently-wrong reads), **P2** = verify/parity,
**WATCH** = future spec.

---

## P1 — float-nan-prune-guard (bug-shaped) — ✅ ALREADY IMPLEMENTED IN WORKING TREE

> **Status (2026-08-26):** found **uncommitted** in the working tree at survey time,
> matching this spec exactly — `DucklakeStatTypes.isFloatType()` added (`float32`/`float64`),
> `findDataFileIdsInRange` now selects `colstats.CONTAINS_NAN`, and `rangePruneRetainsFile`
> computes `nanInvalidatesMax = isFloatType(columnType) && containsNan != false` →
> `effectiveMax = null` (min side preserved), plus
> `TestJdbcDucklakeCatalogFloatNanPruneGuard.kt`. **Test suite run 2026-08-26: PASS**
> (`TestJdbcDucklakeCatalogFloatNanPruneGuard` 4/4, plus `TestDucklakeStatTypes` and
> `TestJdbcDucklakeCatalogStatsSwapGuard` — no regressions). **Action left: COMMIT.**
> The spec below is retained as the rationale of record.


**What upstream found.** DuckLake/Parquet float `min`/`max` statistics **exclude
NaN**, and NaN sorts **above every non-NaN value**. So a `REAL`/`DOUBLE`/`FLOAT`
file whose NaN state is unknown-or-present has a stored `max` that is NOT its true
upper bound. Pruning a `col > C` / `col >= C` / `col = <big>` predicate on that
stored `max` can drop a file that actually holds matching rows.
- Reference: `datafusion-ducklake` #203 ("Float pruning is NaN-aware" — "Stored
  float maxima are now gated on `contains_nan = false` at every consumption point").
- Upstream DuckLake ships the `contains_nan` column precisely so readers can gate
  on it; we already **store and read** it but do **not consult it when pruning**.

**Where we're wrong today.**
- `JdbcDucklakeCatalog.findDataFileIdsInRange` (`JdbcDucklakeCatalog.kt:988-1044`)
  selects only `colstats.MIN_VALUE` and `colstats.MAX_VALUE` — it never fetches
  `colstats.CONTAINS_NAN`.
- `rangePruneRetainsFile` (`:1059-1090`) → `isWithinBounds` (`:3931-3944`) uses the
  stored `max` unconditionally. The `lowerVsMax > 0 → prune` branch (`:3937-3939`)
  is the unsafe one for floats.

**Fix (precise).**
1. Add `colstats.CONTAINS_NAN` to the `select` in `findDataFileIdsInRange`.
2. Thread a `containsNan: Boolean?` (TRUE / null-unknown) into `rangePruneRetainsFile`.
3. For a **float column** (`REAL`/`FLOAT`/`DOUBLE` — add an `isFloatType()` to
   `DucklakeStatTypes` alongside `isNumericType`, `:47`), when `containsNan != false`
   (i.e. TRUE or unknown/null), treat the upper bound as **unbounded**: call
   `isWithinBounds(lowerBound, upperBound, minStat, maxStat = null)`. Passing
   `maxStat = null` makes `compareValues` return empty, so the `lowerVsMax` prune
   branch never fires — the min side still prunes correctly (NaN only affects the
   max side, since NaN is above all values, never below).
4. Do NOT gate on the min side; the stored `min` is still a valid lower bound.

**Note on the whole-integer/decimal path.** `contains_nan` is a float-only concept;
leave `numericStatsSwapped` (`:1073`, the DECIMAL guard) as-is.

**Tests.** Add a cross-engine case: a `DOUBLE` file with `contains_nan = TRUE` whose
recorded `max` is a finite value `M`, queried with `col > M` — the file MUST be
retained (it holds NaN rows / the true max is above `M`). Confirm a `contains_nan =
FALSE` file with the same bounds is still pruned (no regression to pruning power).

**Engine-semantics caveat (important).** Whether an excluded-NaN max is *unsafe* for a
given predicate depends on the CONSUMING engine's NaN comparison semantics:
- **Trino** uses a total ordering where **NaN is the greatest value** and `NaN = NaN`
  is true, so `x > C` matches NaN rows → pruning on the NaN-excluding max IS unsafe →
  real bug. (An earlier note in `trino-ducklake` claimed "`NaN > 5` is false"; that is
  being corrected — verify with `SELECT nan() > 5`.)
- **Doris** BE-native reader semantics should be confirmed similarly.
Because this catalog is engine-agnostic and does the pruning centrally, the safe design
is to fail-open on the max side whenever `contains_nan != false` for a float column —
correct for both engines regardless of their exact comparison rule. This is *at worst*
slightly over-conservative for an engine where `NaN > C` is false, and *correct* for
Trino/Arrow-style ordering. So the fix stands regardless; just cite the reason in the
comment as "engine-agnostic NaN safety" rather than a single engine's rule.

---

## P2 — widening-invalidated-bounds (verify our ALTER TYPE stats handling)

**What upstream found.** Two DuckLake `main` commits:
- `0c929bd7` "Keep column bounds across integral and decimal widenings" — after a
  widening `ALTER ... TYPE` (e.g. `INT`→`BIGINT`, `DECIMAL(10,2)`→`DECIMAL(20,2)`),
  the existing per-file bounds are still valid and should be preserved.
- `94f2cc53` "Keep invalidated column bounds unknown" — where a type change *does*
  invalidate a bound, it must become **unknown** (never pruned on), not stale-kept.

**Why it matters to us.** If our catalog keeps a pre-widening text bound and later
parses it under the NEW column type, a stale/incoherent bound could wrongly prune.
Our `parseStatValue` is type-aware and the `numericStatsSwapped` guard fails open on
`min > max`, which covers the *worst* corruption — but a widening that yields a
coherent-but-wrong bound would slip through.

**Action (verify, ~1h).** Trace what happens to `ducklake_file_column_stats` rows on
`ALTER TABLE ... ALTER COLUMN ... TYPE ...` in our write path (search `ALTER`/type-change
handling in `JdbcDucklakeCatalog.kt`), and how `findDataFileIdsInRange` reads them
afterwards. Decide: do pre-change files keep bounds (fine, if still valid) or get
their bounds invalidated? Mirror upstream: preserve on safe widenings, set to
unknown (null) otherwise. Add an `alter_type` cross-engine prune test if a gap is
found. Upstream tests to mirror: `test/sql/alter/alter_type_stale_stats_cache.test`,
`test/sql/stats/alter_type_stats_reseed.test`.

---

## P2 — missing-stats-keep-file (parity confirm — likely no-op)

`datafusion-ducklake` #250 ("Pruning survives missing statistics"): an absent
per-file bound must be a typed null so ONE statless file doesn't disable a column's
pruning for the whole candidate set. **We already do this** — `findDataFileIdsInRange`
uses a `LEFT JOIN` and `rangePruneRetainsFile` returns `true` when `!hasStatsRow`
(`:1070-1072`). Just confirm no consumer collapses the set on a single unknown, then
close. No code change expected.

---

## DONE — decimal-swapped-minmax-prune-guard (confirmation)

The item handed off 2026-07-21 is **implemented**: `numericStatsSwapped`
(`JdbcDucklakeCatalog.kt:1073`, `DucklakeStatTypes.numericStatsSwapped`) fails open
when a type-aware parse gives `min > max` (DuckDB ≤ 1.5.4's swapped 128-bit DECIMAL
`RETURN_STATS`, fixed upstream in 1.5.5 `7adf7a70b`). No further action; won't
self-heal until affected files are rewritten under ≥ 1.5.5 (expected).

---

## Consumer-side items owned elsewhere (context, not catalog work)

These were found in the same survey but are **consumer** responsibilities. Noted so
the catalog agent knows the shared APIs they lean on and doesn't duplicate them:

- **global-stats-time-travel** — `getTableStats(tableId)` is whole-table/global; a
  time-travel scan must instead use snapshot-scoped counts. The catalog **already**
  provides the correct source: `getColumnStats(tableId, snapshotId)` aggregates
  counts under `activeAt(snapshotId)` (`:1100-1161`). No catalog change needed unless
  consumers want a dedicated snapshot-scoped `getTableRowCount(tableId, snapshotId)`
  convenience — flag if both Trino and Doris end up re-deriving it. (Trino tracks
  this in `trino-ducklake` `TODO-READ-MODE.md`; Doris in `TODO-read.md`.)
- **cdc-field-id-at-window-end** — change-feed reads must resolve columns by field id
  at the window's END snapshot. The catalog already exposes
  `getTableColumns(tableId, snapshotId)`; consumers pass the end snapshot. No catalog
  change expected (Trino-side item).

---

## WATCH — DuckLake spec v1.1 (`V1_1_DEV_1`)

DuckLake `main` bumped `DUCKLAKE_LATEST_VERSION` to `V1_1_DEV_1` (new
`DuckLakeMetadataManagerV1_1` + `MigrateV10`). **Not** released — no `v1.6` branch,
not backported to `v1.5-variegata`, so DuckDB 1.5.5 keeps catalog spec `V1_0`. When it
ships, this catalog will need:
- `_ducklake_`-prefixed / centralized **inlined-metadata column names** (renamed from
  the current spelling) — affects inlined-data reads and conflict-token round-trips.
- New **epoch partition transforms**: `epoch_year` / `epoch_month` / `epoch_day` /
  `epoch_hour` (gated on DuckLake 1.1) — extend `DucklakePartitionTransform`.
- **Expire column tags on `DROP COLUMN`**.
- A migration step (`MigrateV10`) if we ever write a v1.1 catalog.

No action until a DuckDB line ships v1.1; just don't be surprised by the new columns.
