# DuckDB 2.0 / DuckLake readiness — 2026-10-08 refresh

> Archived source/binary survey. Its hashes, pending work and line references
> describe October 8. Use the [active TODO](../TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md)
> and [acceptance runbook](../DUCKLAKE-1.1-ACCEPTANCE.md) for new qualification.

**Bottom line: do not treat this as a JDBC version bump.** There are two reproduced
breakages in our current SQL/layout assumptions, a new development catalog format,
and a mixed-client migration hazard. Keep the qualified 1.5 lane while preparing an
independent 2.0 lane. This report does not change dependencies or claim 2.0 support.

## Baselines and what changed since the preliminary analysis

| Component | Checked state |
|---|---|
| This repository | `e71c58c`; released library `0.10.0`, development `0.11.0-SNAPSHOT` |
| Our oracle/JDBC | `org.duckdb:duckdb_jdbc:1.5.5.0` |
| Our corpus/reference DuckLake | `d8a1881e22516ea3d186d73e83c65fe5bd1a1dc4`, release branch |
| Our Quack test server | Dockerfile still defaults to **DuckDB 1.5.4**, not our JDBC 1.5.5 |
| DuckDB release branch | `v2.0-cyanoptera` at `e4531611d9209dc3e67d81207ad2b54f4aee3faa` |
| DuckLake main | `9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe` (Oct 8) |
| DuckLake release branch | `343df36a33954f40c8a92fd4b6d46a25577dd98e` (Oct 6) |
| Quack main | `f964cece8ecfe9006a607aa0a3e9b9030296c54f` (Oct 6); wire version **3** |
| Latest released JDBC on Central | **1.5.6.0**; no 2.0 JDBC release listed in the checked metadata |

[The current release calendar](https://duckdb.org/release_calendar) lists DuckDB
**2.0.0 on October 21, 2026**, then 2.0.1 on November 18; dates remain tentative.
1.5.6 actually shipped September 28. The old September/fall-only timeline is stale.
The release branch was feature-frozen September 2, but fixes continue landing.

Earlier research exists outside this extracted repository:

- `../duckbridge/dev-docs/TODO-duckdb-2.0.md` — August 28 core/transport tracker.
- `../ducklake-integrations/jvm/trino-ducklake/dev-docs/TODO-WRITE-MODE.md` — July
  `V1_1_DEV_1` format-horizon notes, plus the accompanying read-mode research.

This is the **catalog/replay-specific update**; those sibling repositories were
not edited. Important corrections to the older notes:

1. **DuckDB 2.0 is not DuckLake catalog 2.0.** DuckLake main still names its newest
   writable format `1.1-dev1`; a final 1.1 release/spec is not established by this scan.
   Branching/RBAC/materialized-view ideas from the old DuckLake 2.0 wishlist are
   not demonstrated format requirements here.
2. **Existing 1.0 catalogs do not migrate by default.** Current options default
   `automatic_migration=false`; new catalogs default to latest, existing >=1.0
   catalogs retain their version unless a newer version/migration is requested.
   Already-development catalogs can receive best-effort additions on writable attach.
3. The new schema additions include **bound exactness and schema parentage**, not
   just row-group information. `encryption_key` was **already in our 1.0 schema**;
   it is not a newly discovered 1.1 column. The current additions persist
   `row_group_count`, not a newly observed comprehensive row-group-stat table.
4. The pure-Java `quack-jdbc` port is a sibling-project concern. **This catalog's
   Quack path uses DuckDB JDBC + its native Quack extension**, not that driver.
   It still needs a matched native client/server/protocol lane.
5. CI-02 nullable row lineage and CI-03 typed geometry rendering shipped in our
   0.10.0. They are not new unimplemented 2.0 tasks—but the geometry SQL now hits
   a separate, reproduced lambda-syntax break.

## Runtime probe provenance (not the same as source HEAD)

Used an isolated Python environment, with disposable local metadata/data directories:

```
python package: duckdb 2.0.0.dev2610011535
core:           v2.0.0-alpha43763 / 96063b9e39 / Cyanoptera
ducklake:       e8924cfc9c, installed from core
quack:          974927a394, installed from core
```

The Oct 8 DuckDB release branch **still pins DuckLake `e8924cfc9c` and Quack
`974927a394`**, whereas both extension development heads have moved on. Therefore
changes merged into DuckLake main on Oct 5–8 are **source-head risks/fixes**, not
proven to be inside the tested or final distributed extension.

Reproduce with [the diagnostic probe](../probes/duckdb2_readiness.py), using the pinned
preview in a virtual environment outside the project:

```
python3 -m venv /tmp/opencode/catalog-duckdb2-preview-20261008
/tmp/opencode/catalog-duckdb2-preview-20261008/bin/pip install duckdb==2.0.0.dev2610011535
/tmp/opencode/catalog-duckdb2-preview-20261008/bin/python \
  dev-docs/probes/duckdb2_readiness.py --temp-root /tmp/opencode
```

**All 12 sampled-behavior checks passed.** They confirm the breakages and migration
behavior below; they do not mean the connector passed qualification. No JVM 2.0
full test suite, mixed-protocol test, or production lake migration was run.

## Prioritized compatibility work

| ID | Priority | Risk | Evidence/status |
|---|---|---|---|
| UP2-01 | P0 | Fresh/dev catalogs versus our version gates and cached migration assumptions | Source + runtime; file writes intentionally reject fresh dev format, other mutation paths are not uniformly gated |
| UP2-02 | P0 | Renamed inlined metadata columns | **Runtime-reproduced** missing `row_id` projection |
| UP2-03 | P0 | Geometry normalizer emits disabled lambda syntax | **Runtime-reproduced** binder error on our emitted SQL shape |
| UP2-04 | P0 for dev format | Nested schema identity and cleanup ancestry | Current main adds `parent_schema_id`; our models/cleanup omit it |
| UP2-05 | P1 / gate before enabling 1.1 | Stats exactness, unknown counts and optimizer changes | Source; no 2.0 JVM stats interoperability qualification |
| UP2-06 | P0 for local/Quack deployments | Quack protocol and native metadata storage rollback | Source; current fixtures are already version-skewed |
| UP2-07 | P1 | NULL/timestamptz_ns/VARIANT inline types | Runtime types + source; mappings need version-aware coverage |
| UP2-08 | P1 | Aborted transactions, prepared statements and missing-table probes | **Runtime-reproduced** binder failure invalidates explicit transaction |
| UP2-09 | P1, also current-lane correctness | Expiration removes conflict history; newer concurrency rules | **History retention fixed/verified Oct 9**; remaining concurrency-rule audit stays open |
| UP2-10 | P1 for mixed-engine writes | Stricter file-format/delete-file contracts | Source-head behavior; audit producers/consumers, not just JDBC |

### UP2-01 — version detection and migration must protect every mutation

`DucklakeSpecVersions.WRITABLE` allows only `0.4` and `1.0`. A fresh preview lake
has `version='1.1-dev1'`, so file registration is correctly refused. **Do not add
`1.1-dev1` to the allowlist before the rest of this report is addressed.**

The guard in `JdbcDucklakeCatalog.requireFileWritesSupported()` is cached once per
instance, assuming migrations require that client to reattach. An independent
DuckDB writer can migrate the shared PostgreSQL catalog while our client stays
open. Worse, metadata DDL, ANALYZE, expiration and some destructive operations do
not share that file-write guard. A dev-format lake is not protected globally by
the current file-only refusal. Review both previously cached and fresh instances.

Temporary containment: explicitly create fixtures with `DUCKLAKE_VERSION '1.0'`,
leave `AUTOMATIC_MIGRATION` disabled, and enforce no migrations while old JVM
writers are connected. This preserves the catalog layout, **not necessarily the
native DuckDB file storage format or every SQL/transport behavior**.

Sources: [version definitions](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/include/common/ducklake_version.hpp),
[initializer/target-version resolution](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/storage/ducklake_initializer.cpp#L183-L312).

### UP2-02 — inlined table metadata columns are renamed

| 1.0 | 1.1-dev1 |
|---|---|
| `row_id` | `_ducklake_row_id` |
| `begin_snapshot` | `_ducklake_begin_snapshot` |
| `end_snapshot` | `_ducklake_end_snapshot` |

`InlinedDataTables.createTableSql`, the dynamic `InlinedDataTable` predicates in
`JdbcDucklakeCatalog.kt`, the row-ID readers, visibility/count probes, flush,
truncate, and schema-evolution paths currently assume the left-hand names.
Native migration renames existing physical columns. The probe's exact old-style
projection failed with `Referenced column "row_id" not found` after a fresh dev attach;
an explicitly pinned 1.0 lake kept old names, and explicit migration changed them.

Fix with a version/capability-aware metadata-column descriptor used consistently
by DDL and reads. Do not conflate these names with the Parquet lineage field
`_ducklake_internal_row_id`. Check user-column collisions/reserved-prefix rules,
old schema versions, read sessions, and concurrent migration. No generic
"missing table means empty" fallback should hide missing-column errors.

Source: [column-name descriptor](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/include/storage/ducklake_metadata_manager.hpp#L49-L68)
and [migration](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/storage/ducklake_metadata_manager.cpp#L428-L535).

### UP2-03 — our newly fixed geometry mirror has a concrete 2.0 syntax blocker

`GeometryNormalizer.SequenceType.fromWkb` and `MapType.fromWkb` emit:

```sql
list_transform(v, geo_0 -> ST_GeomFromWKB(geo_0))
```

The preview rejects this by default with `Deprecated lambda arrow (->) detected`.
The equivalent `list_transform(v, lambda geo_0: ST_GeomFromWKB(geo_0))` succeeded
and rendered `['POINT (1 2)']`. A separate **real JDBC 1.5.5.0 smoke probe also
passed** that exact new-syntax expression and an ordinary list lambda. So this
does not require dropping the maintained lane; the full nested geometry regression
suite still needs to run after the implementation changes. Do not enable a global
legacy mode as the production fix, or reintroduce blanket geometry skips.

Retest both WKT and WKB mirrors: normalization prepares the WKB statement even
when a cell later succeeds through the WKT path, so a prepare-time syntax failure
can affect more than binary-valued rows. Preserve BLOB/NULL semantics and all
0.10.0 negative controls. Parser/error-golden churn elsewhere is a separate corpus
refresh, not a reason to relax comparisons globally.

**Follow-up, 2026-10-09: UP2-03 fixed/verified.** The shared renderer now emits
explicit `lambda ...:` SQL. A strict-arrow-disabled regression and all existing
geometry golden/mirror controls pass on the maintained JDBC lane.

### UP2-04 — nested schemas also change storage-path ancestry

Current DuckLake main adds `ducklake_schema.parent_schema_id` and reconstructs a
schema tree. `DucklakeSchema`, case-insensitive name resolution, schema rename/drop,
and `DucklakeTableFilePathRef` currently model one flat schema layer.
`JdbcDucklakeCleanupQueries.schemaPaths` resolves only that layer. Cleanup that
resolves a child path as root-relative could misclassify a retained file.

Before allowing nested catalogs: represent ancestry without flattening ambiguous
leaf names, or reject nested schemas explicitly. Cleanup must walk bounded parent
pages, detect cycles/missing parents, and apply a traversal budget **before physical
deletion**. Include historical/dropped ancestors and quoted names containing dots.

The sampled **older bundled** DuckLake has no `parent_schema_id` column despite
also calling itself `1.1-dev1`; use current main's migration/schema as the source
for this feature, not the preview's version string alone.

Source: [actual dev schema](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/metadata_manager/ducklake_metadata_manager_v1_1.cpp).

### UP2-05 — statistics need requalification, not an additive-column shrug

The dev format adds `min_is_exact` and `max_is_exact` to file and global column
stats. String extrema can be truncated bounds, not actual values suitable for
MIN/MAX folding. Our public stats models and generated jOOQ bindings omit those
bits. `row_group_count` is also new on both file tables, and view column tags get
their own table. Regenerate against deliberate versioned shapes, not an accidental
preview bootstrap, and keep 1.0 compatibility.

Main also now distinguishes absent bounds on nonempty tables from an all-NULL
column, carries `record_count_unknown`, and supports `skip_stats_columns` while
retaining counts. These overlap our C03/C04 conservative suppression work. **Do
not remove suppression or its documented SET NOT NULL caveat without new native
reader/writer tests.** New optimizer pruning makes incorrect bounds more dangerous.

Minimum corpus: truncated long-string bounds; unknown/all-NULL/missing stats;
NaNs; nested leaves; old/new inline schemas; later native/JVM inserts; delete and
rewrite extrema; count-vs-allocator differences; timezone-offset bounds. Include
both bundled and source-head DuckLake behavior where they differ.

Source: [stats semantics](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/storage/ducklake_stats.cpp),
[metadata schema additions](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/metadata_manager/ducklake_metadata_manager_v1_1.cpp).

### UP2-06 — qualify the right Quack path and plan native-file rollback

Our synthetic URL initializes a native DuckDB connection with INSTALL/LOAD,
CREATE SECRET, ATTACH DuckLake-on-Quack and USE; metadata statements are wrapped
in `system.main.quack_query_by_name`. Current upstream still tests that function:
`CONNECT` is not evidence that we must immediately replace the working wrapper.

Wire v3 adds acknowledged batches/heartbeats and stricter version negotiation.
Upgrade the native client, server CLI and both native extensions as a tested set.
Current test Dockerfile defaults to 1.5.4 while our JDBC is 1.5.5. Record extension
hashes as well as engine versions; do not call this a direct `quack-jdbc` failure.

Separately, DuckDB 2.0 defaults to native storage v2.0. DuckLake's initializer
explicitly attaches local DuckDB metadata with **`STORAGE_VERSION 'latest'`**.
Even pinning DuckLake format 1.0 does not pin the `.db` container's storage version.
Test old-file read/write/checkpoint and attempted old-binary reopen on copies,
including inlined rows. Backup/stop writers before migration; do not promise
rollback by downgrading just the JDBC jar. PostgreSQL metadata avoids that native
file-format hazard, but not the catalog schema/layout hazards.

Sources: [Quack version negotiation](https://github.com/duckdb/duckdb-quack/blob/f964cece8ecfe9006a607aa0a3e9b9030296c54f/src/include/quack_message.hpp#L16-L17),
[metadata attach storage option](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/storage/ducklake_initializer.cpp#L29-L59),
[2.0 storage overview](https://duckdb.org/2026/08/17/duckdb-20-highlights#7-storage-format-v20).

### UP2-07 — new boundary types and PostgreSQL inlining

The preview reports untyped NULL as logical `"NULL"` rather than INTEGER; native
DuckLake persists `column_type='null'` in the dev format. `TIMESTAMPTZ_NS` is also
real and its string rendering preserves nine fractional digits. Current local
`InlinedDataTables`/`InlinedValues` explicitly handle `timestamptz`, not
`timestamptz_ns`; they cannot infer the latter from an arbitrary uppercase fallback.

Current main's PostgreSQL mapping stores timestamp variants as VARCHAR, NULL as
INTEGER, and permits VARIANT text inlining that our PostgreSQL `canInline` policy
currently rejects. Exercise scalar and nested decoding, schema-change DDL,
legacy physical tables, NaN/infinities and pre-epoch nanosecond rounding. Reader
support or clear rejection must precede enabling corresponding writes.
Plain JSON moving to VARIANT is still described upstream as **future work**, not
an established 2.0 catalog migration requirement.

Source: [catalog type vocabulary/version gate](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/common/ducklake_types.cpp),
[PostgreSQL physical types](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/metadata_manager/postgres_metadata_manager.cpp#L161-L220).

### UP2-08 — errors now poison explicit transactions

Runtime settings report `ALL_ERRORS_INVALIDATE_TRANSACTION`. BEGIN followed by a
missing-table binder error made SELECT 1 fail until ROLLBACK. The old pattern of
catching an expected missing dynamic table and continuing on the same pinned
connection must be checked. Several `getInlined*` methods still use that approach.
Our C04 maintenance path already uses a non-failing existence probe: expand the
same principle, rather than blanket-catching errors or disabling invalidation.

Run lifecycle/conflict tests over local and Quack read sessions, prepared statements,
schema changes, rollback failures and pool reuse. Preserve the original commit
error if rollback fails too (our `rollbackQuietly` already intends this). PEG
parsing changes error text, so prefer structured SQLState where supplied and
verify message-based DuckDB fallback classification deliberately.

### UP2-09 — new conflict-history fix exposes a current-lane gap

DuckLake main's Oct 5 commit
[`d49146c6`](https://github.com/duckdb/ducklake/commit/d49146c668b7b0da92070161e434b9af8f0f0171)
retains `ducklake_snapshot_changes` when snapshots expire, so writers that started
earlier still see intervening conflicts. Our `expireSnapshots` currently deletes
those rows. This is a concrete divergence worth a dedicated regression **now**,
not only when switching to 2.0. No live stale-write reproduction was run here.

**Follow-up, 2026-10-09: history-retention fix completed and verified.** The original
assessment above is historical. New regressions reproduced both native positional
and whole-file stale deletes committing without a conflict, plus a JVM ALTER retry
that bypassed its conflict matrix. Expiration now retains all conflict records,
while snapshot/file/metadata GC continues. Verification passed 491 JUnit tests and
the full oracle corpus. See the [fix report](FIX-expired-snapshot-conflict-history-2026-10-09.md)
for the API/rollout implications. The broader concurrency audit below is still open.

Main's Oct 6–8 changes also cover replaced-delete-file conflicts, inlined/file
cross-store deletes, file-granular compaction conflicts, and preserving concurrent
stats commits during rewrite. Match our logical conflict predicates against those
fixtures. Some are intentionally more permissive upstream; do not remove a safe
local restriction merely to match performance. C05 concurrent ANALYZE publication
and the remaining C14 diagnostic connection borrowing stay separate known tasks.

### UP2-10 — physical data/delete file contracts are tightening

Main now explicitly refuses non-Parquet data files. Our `CatalogFileFormat`
documents parquet-only writes but still strips historical `trino/<format>`
prefixes, and fragment metadata accepts a format string. Verify producer-side
rejection of legacy Lance/Vortex/DuckDB formats; do not mistake legacy decoding
for continued native DuckLake read support or revive the removed write modes.

Main also writes/checks the required delete-file columns more strictly. Puffin
deletion vectors remain labeled **experimental**; current source supports a bare
single vector (no container footer) and multiple cumulative vectors carrying
`ducklake-snapshot-id`, selected per read snapshot/change-feed window. Our
`hasPartialDeleteFilesRequiringSnapshotFilter` documentation currently treats
Puffin as lacking embedded snapshots. Audit that assumption and the **engine-side**
Puffin readers/change-feed logic against the new layout; never just union all
future vectors during time travel. No default Puffin adoption is recommended.

Sources: [file-format validation change](https://github.com/duckdb/ducklake/commit/d7854353),
[Puffin writer/reader](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/storage/ducklake_puffin.cpp),
[snapshot-aware delete reader](https://github.com/duckdb/ducklake/blob/9f0c2a395ebc232e7a7c5a55a5ee096df9811dfe/src/storage/ducklake_delete_filter.cpp#L78-L145).

## Recommended order and release gate

**2026-10-09 execution update:** the [five-step safe preparation log](DUCKDB-2.0-PREPARATION.md)
records the committed lambda fix, pinned native preview CI, fresh mutation guards,
versioned inline layout descriptors, and matched-peer/recovery preparation. It also
states the remaining JVM 2.0, protocol and 1.1-feature qualification limits explicitly.

1. **Containment + small compatibility changes first:** version/migration policy,
   versioned inline-column names, new lambda SQL, and conflict-history regression.
   Planning estimate: several focused days; not verified implementation estimates.
2. **Independent preview CI lane:** pin engine/JDBC artifact, extension hashes,
   catalog version and corpus SHA. Start PostgreSQL with explicit DuckLake 1.0;
   then enable a separate disposable 1.1-dev1 lane. Do not repin the release corpus
   just because latest main compiles.
3. **1.1 feature gates:** stats exactness and type mappings; nested schemas and
   bounded cleanup ancestry; migration/cache invalidation. At least a multi-day
   compatibility effort, longer if full nested-schema APIs are included.
4. **Separate local/Quack lane:** update both peers, prove read/write transaction
   ownership, cancellation/leases, metadata result coercion and storage rollback.
5. **Before shipping:** full catalog + replay checks and new upstream corpus;
   native↔JVM write/read round trips; both geometry comparison stages; snapshot
   and change-feed semantics; migration while a previous client is alive; final
   release-note reconciliation. Trino/Doris/parity-extension qualification belongs
   to their agents, not an inferred success from this catalog report.

Consider the released **1.5.6.0** JDBC maintenance bump separately, with aligned
Quack fixtures and its bundled DuckLake regression corpus. C++17/stable C API,
parity-extension rebuilds, triggers, new pushdown functions and CPU baselines
remain relevant to sibling engines/native extensions, but are **not mandatory
source ports for this Kotlin/jOOQ library itself**.

## Verification and limitations

- Inspected pinned source and diffs from our actual DuckLake baseline to current
  main; compared the distributed release-branch extension pins independently.
- Ran the disposable Python preview probe twice; the final run passed all 12
  assertions. Observed layout migration, disabled lambda syntax, transaction
  invalidation and real new type spellings. Output retained for this session at
  `/tmp/opencode/catalog-duckdb2-readiness-20261008.json`.
- Independently ran the new lambda SQL through the repository's real JDBC
  1.5.5.0 artifact: `v1.5.5 | [2, 3] | ['POINT (1 2)']`.
- Production code, dependency versions, git submodule pins and sibling repositories
  are unchanged. This work is analysis and a reproducible diagnostic, not the
  implementation of the listed fixes or certification of unreleased JDBC 2.0.
