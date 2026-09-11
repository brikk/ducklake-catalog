# 0.10.0 — nightly row lineage and geometry comparison fixes

Applies to `dev.brikk.ducklake:ducklake-catalog`, `ducklake-test-corpus-replay`, and
`slt-format` (plus catalog test fixtures).

Evidence: [Trino nightly 34577075167](https://github.com/brikk/trino-ducklake/actions/runs/34577075167).
Fix commit: [`0b6c31b`](https://github.com/brikk/ducklake-catalog/commit/0b6c31ba5ec4dd68735eb9d2de2e688dde77f7bb).
The catalog/replay changes address CI-02 and CI-03. CI-01 statistics coverage and
CI-04 MAP translation are tracked by the Trino agent separately.

## CI-02: absent row-ID starts are valid

`ducklake_data_file.row_id_start` can legitimately be SQL NULL after native rewrites.
The actual row IDs then reside in the Parquet file. All file lookup paths and the
change-feed deletion model now retain NULL instead of rejecting it or substituting zero.

### Required consumer API changes

- `DucklakeDataFile.rowIdStart` and `DucklakeChangeFeedDeletion.rowIdStart` change from
  Kotlin `Long` to `Long?`; Java record accessors return boxed `java.lang.Long` instead of
  primitive `long`. Constructors and generated copy/component signatures change accordingly.
  **Recompile consumers against 0.10.0; this is a JVM binary API change.**
- Propagate the optional value through split/planning models, worker serialization, and
  change-feed readers. Avoid implicit unboxing, `?: 0`, `orElse(0)`, and primitive JSON defaults.
  `DucklakeDataFile` omits a NULL start in JSON (`NON_NULL`); missing and explicit-null values
  both decode to NULL. A present zero remains a real zero.
- Resolve row identity in this order, matching upstream `DuckLakeMultiFileReader`:
  1. Read the reserved Parquet row-ID field (`2147483540`, conventionally named
     `_ducklake_internal_row_id`) when present. Its values are **absolute** IDs; do not add
     `rowIdStart`. This field wins even when the catalog start is non-NULL (e.g. flushed files).
  2. Otherwise, if the start is present, compute `start + physical_file_position` using the
     position before predicate/deletion filtering, not the position within the returned batch.
  3. If row identity is required and neither source exists, fail explicitly. NULL catalog
     metadata alone is not corruption and must not prevent an ordinary data read.
- Positional delete entries remain **physical file positions**. When emitting change-feed
  row IDs or `$row_id`, resolve those positions through the same embedded/fallback rule.
- Both JVM rewrite variants already require preserved embedded IDs in output files. They now
  register `row_id_start = NULL`, rather than publishing a source minimum as an apparent
  contiguous output range. They can consume valid NULL-start sources, preserve ID gaps, and
  leave the table's `next_row_id` unchanged. A missing allocator is rejected before mutation;
  it cannot be reconstructed from an absent start and row count.

Read method signatures (`getDataFiles`, `getDataFilesByIds`, `getDataFilesAddedBetween`,
`getDeletionsBetween`) otherwise remain unchanged. No catalog schema migration is required.

## CI-03: nested geometry retains its logical type

Golden validation uses the JDBC typed string path for GEOMETRY-containing results, producing
DuckDB WKT and nested quoting rather than rendering WKB as ordinary BLOB bytes. Other types,
including BLOB and VARIANT, retain the existing object-rendering path.

The replay driver also retains oracle result types for mirror comparison. It accepts WKT or
the existing adapter's WKB rendered with `GoldenComparator.renderCell` / `renderNested` and
normalizes only geometry leaves, including LIST, fixed ARRAY, MAP keys/values and STRUCT fields.
DuckDB's own WKB decoder handles dimensions and byte order. Ordinary BLOB fields are not
interpreted as geometry; NULL parents, NULL children and empty containers remain distinct.
Malformed WKB is a failure, not NULL or a skip. The geometry rendering skip-list entries are removed.

**No `ReplayReadEngine` signature change is needed.** Existing adapters that use the shared
BLOB/nested text dialect can retain that rendering after upgrading corpus-replay to 0.10.0.
Adapters returning WKT are supported too. SQL NULL must still be a null cell, not the text `NULL`.
Actual Trino data-page/row-ID integration remains the consumer's verification responsibility;
the repository regressions use independent native readers and catalog API controls.

## Verification

- Pre-release [CI](https://github.com/brikk/ducklake-catalog/actions/runs/34624344010) and
  [0.10.0-SNAPSHOT publication](https://github.com/brikk/ducklake-catalog/actions/runs/34624343883)
  passed at the fix commit.

- Before the fixes, the native rewrite regression reproduced the NULL-start exception and
  geometry regressions reproduced golden mismatches before reaching the mirror.
- All ten reported CI-02 records across the five supplied fixtures now pass real PostgreSQL
  catalog API reads followed by independent native mirror reads.
- All three supplied nested geometry fixtures pass both golden validation and binary/WKT
  mirror controls, without file or record skips. Negative controls reject changed geometry,
  BLOB values, NULL values, and malformed WKB. Additional coverage includes quoted field names,
  fixed arrays, nested NULL containers, geometry map keys and big-endian WKB.
- Native/JVM rewrite regressions cover non-contiguous IDs, subsequent inserts, allocator
  preservation, rollback on missing allocator, historical reads and both change-feed arms.
- `mise exec -- ./gradlew check -Dducklake.corpus.dirs=all --no-configuration-cache` passed:
  **484 JUnit tests** (394 catalog, 51 SLT, 39 replay), zero failures or skipped JUnit tests;
  detekt and Java 17 `checkAbi` passed (7,720 runtime classes).
- Full oracle corpus: **480 files discovered, 449 run, 9,424 records passed, zero failures**.
  The remaining 31 file skips and 12 record skips concern existing harness/environment gaps;
  none is a geometry-rendering skip.
