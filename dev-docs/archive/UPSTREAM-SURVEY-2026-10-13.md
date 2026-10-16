# Upstream survey — 2026-10-13

> Historical survey. Main-branch merges are not shipping-binary qualification;
> current work is tracked in the [upstream TODO](../TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md).

## Bottom line

**Current 1.0 correctness is still moving; this is not just 1.1 feature work.**
DuckLake main gained 32 merged PRs since our October 12 capture. Older catalogs,
inline schema resolution, row lineage and statistics are the most important
signals. The maintained release branch has not picked up these fixes.

This is source/history/release research, **not reproduced JVM failures**, a pin
update or implementation authorization. Catalog 0.12.0 / main 0.13.0-SNAPSHOT
remain unchanged; 1.1 writes remain disabled.

## Research locations and scope

- Here: `TODO-rectify-from-eval.md`, `dev-docs/REVIEW-2026-09-05.md`, the October 8
  assessment and October 12 prep results/capability/acceptance notes.
- Broader historical survey: `../trino-ducklake/dev-docs/RESEARCH-upstreams.md`,
  `TODO-READ-MODE.md`, `TODO-WRITE-MODE.md`, `TODO-uhoh.md` and
  `archive/RESEARCH-LOG.md`. The old `ducklake-integrations/jvm/...` paths in our
  October 8 report predate the separate checkouts.
- That survey's exact DataFusion baseline is ambiguous (`v0.7.0 + unreleased`,
  August 26); our catalog reference gitlink is older still. Counts below therefore
  say **which baseline they use**, not that every change is newly discovered today.
- Trino/Doris code and research files were read-only. No sibling edits, reference
  checkout updates, builds, running test infrastructure or production migrations.

## Captured heads and release lanes

| Target | Captured state | Comparison / release status |
|---|---|---|
| DuckLake main | `998082dcc3a54b50dad4bf1e92d7f2c27e9d6f84` | 32 first-parent merges after October 12 `d248aff8`; newest format still `1.1-dev1` |
| DuckLake v1.5 | `343df36a33954f40c8a92fd4b6d46a25577dd98e` | October 6; unchanged since our prior scan |
| DuckLake v1.4 | `41255d478dbfc6542da4c955993486d69bebfa87` | Last commit February 10; no October fix movement |
| DuckLake v1.3 | `8c8b2ba667577debd61665a46ac24f19c1f4f468` | Last commit August 27, 2025 |
| Maintained bundled DuckLake | `ac7595b0a1305bea3d4cfaca763b0ce964c763a2` | Our DuckDB 1.5.6/JDBC/corpus lane; unchanged |
| DuckDB 2.0 release branch | `d4355d01c59362d57036ad377735dd5340276a5b` | Bundled DuckLake still `80e6155e415b922ce27a0f67f02fc62edd508f37` (October 9) |
| pg_ducklake main | `5514fb815776d401d66bd6be92da47a0f2223a29` | 14 first-parent changes after catalog reference `e43c6b8`; latest change October 10 |
| pg_ducklake stable v1.0 | `526196b6b575e4862467ff7f9d81ff6419ce6575` | Latest tag/release remains `v1.0.2` (August 4); branch has an additional August 13 path fix |
| DataFusion DuckLake main | `e9411ad61dcfa9be3b2a63c44be250c2d02816d4` | 101 first-parent changes after reference `068cc80`; 63 after v0.7.0; eight after v0.9.0 |
| DataFusion released crate | `v0.9.0` / `b80aa95c56ea47ae6376ddbda40674d8ff8d4cd6` | crates.io confirms 0.9.0 published October 7, not yanked; recent fixes below are unreleased |
| DuckLake docs main | `cec7022652d64092961339b44136f56159a11d9e` | Compare historical `c7b5b19`; four stable documentation files changed |
| Quack main | `d929790ab0ce42cc56ab9456c9b1bd65753f81e0` | Three merges after October 8 `f964cec`; protocol still v3 |
| Quack v1.5 | `7e80f7ffcc98d0b3e81d0e1df8cc1c2da240a64b` | Unchanged maintained peer; protocol v1 |

DuckDB's public release calendar still lists **October 21 for 2.0.0** and November
18 for 2.0.1, explicitly tentative. Keep the October 17–18 RC planning window;
this is not evidence that the final JVM artifact or every fix will ship then.

## DuckLake: older-format fixes that matter now

### 1. Missing inline schema versions can destroy old values — highest priority

[Merged #1679](https://github.com/duckdb/ducklake/pull/1679) repairs missing
`ducklake_schema_versions` rows during **explicit** 1.0 → 1.1-dev1 migration.
The affected catalogs were written by DuckDB **1.4.4, 1.5.1 and 1.5.6**. Added or
renamed column values can read as NULL; a flush/checkpoint can write those NULLs
to Parquet and delete the original inline rows.

The repair needs the preceding snapshot to establish the version boundary.
Expiring it first can make the repair impossible. Plain attach to an existing
development catalog does not perform this repair; `AUTOMATIC_MIGRATION TRUE` is
required. It repairs metadata, not already-lost values.

The accompanying read-side [#1590](https://github.com/duckdb/ducklake/pull/1590)
is **still open**: use the table's latest schema row at or before the requested
version instead of exact-match/fallback-to-original-schema behavior.

**Our follow-up:** add old native-produced inline catalogs with unrelated DDL,
first inserts after ALTER, multiple physical versions, renames, expiration and
flushes to acceptance. Inspect `resolveSchemaVersionSnapshot` /
`getSnapshotIdForSchemaVersion`: it currently prefers an exact table-scoped row
and then an exact global snapshot version. That is an audit target, **not a
confirmed local bug or permission to change its fallback blindly**. Existing
simple mixed-inline migration tests don't exhaust these cases. Do not recommend
automatic production migration as a workaround before qualifying the full path.

### 2. Row lineage: newer upstream has fixed a behavior another engine still copies

[DuckLake #1611](https://github.com/duckdb/ducklake/pull/1611), merged October 9,
always embeds row IDs on adjacent merges. It closes
[#1602](https://github.com/duckdb/ducklake/issues/1602) (sorted tables) and
[#1605](https://github.com/duckdb/ducklake/issues/1605) (updated rows even without
sorting). Dropping embedded identity because catalog ranges look adjacent could
change row IDs in current reads, time travel and change feeds.

DataFusion's [#371](https://github.com/datafusion-contrib/datafusion-ducklake/pull/371),
merged October 8 and still in **unreleased** main, deliberately implements the
older official range-only behavior. Its current `src/compaction.rs` still drops
the row-ID column for adjacent ranges, including sorted output. The changelog's
statement that the upstream issue is outstanding is now stale: it was fixed the
following day.

**Do not adopt this parity regression.** Preserve embedded IDs over optional
contiguous starts, and NULL starts as NULL. Add sorted/updated-row merge tests
where source embedded IDs disagree with catalog ranges. The 2.0 bundle includes
#1611; maintained 1.5.6 and our older locked preview do not inherit it.

### 3. Statistics can give wrong answers, not just poor pruning

| Merged fix | Actual defect / relevance |
|---|---|
| [#1717](https://github.com/duckdb/ducklake/pull/1717) | Rewrite recomputed bounds from live inline rows, then flush widened them with retained deleted values while row count still looked exact. MIN could return 4 when the smallest live value was 51. Preserve inexactness until all stored inline rows are live. |
| [#1714](https://github.com/duckdb/ducklake/pull/1714) | DELETE/UPDATE of transaction-local inline rows kept original bounds but counted surviving rows. Recompute once at commit, rather than after every delete. No spec change. |
| [#1712](https://github.com/duckdb/ducklake/pull/1712) | Local DuckDB metadata exposed new back-dated merge files before ending their sources; merge+rewrite in one transaction counted rows twice. Commit-time recomputation excludes new file IDs and adds staged files separately. |
| [#1651](https://github.com/duckdb/ducklake/pull/1651) | HUGEINT/UHUGEINT bounds were not handed to DuckDB. More importantly, one missing numeric bound could leave an empty-range value and incorrectly discard rows; missing bounds must remain unknown. |
| [#1698](https://github.com/duckdb/ducklake/pull/1698) | NULL-first columns never recovered useful bounds. Before allowing new values to establish them, prove old live files and inline rows contain no non-NULL values; unknown bounds from retyping must remain unknown. |
| [#1690](https://github.com/duckdb/ducklake/pull/1690) | SET NOT NULL failed on empty/nested/missing-stat cases and could race a concurrent NULL insert. Scan when stats cannot prove zero NULLs and include insert conflicts. |

These were tested upstream with pinned 1.0 configurations; the fixes are not
inherent benefits of the new format. Our C03 conservative statistics fix and its
native SET NOT NULL caveat remain relevant. C05 concurrent ANALYZE publication
remains separately open. Do not infer stats exactness merely from row-count
equality or remove safe guards based on upstream performance improvements.

### 4. Session settings and import paths can corrupt ordinary 1.0 data

- [#1695](https://github.com/duckdb/ducklake/pull/1695): `binary_as_string=true`
  could reinterpret BLOB bytes (including escaped bytes/invalid UTF-8). A native
  merge/rewrite/checkpoint can persist changed values. DuckLake now binds Parquet
  with that option false. Include plain/nested BLOB controls; geometry normalization
  must still preserve non-geometry BLOB and NULL semantics.
- [#1710](https://github.com/duckdb/ducklake/pull/1710): a transform partition
  consuming hive key `year` displaced an identity partition to `year_year`.
  `add_data_files` read both through the old name, producing wrong rows and
  partition values. Resolve actual collision-adjusted partition keys, not guessed
  column names; distinguish engine import work from this library's registration API.
- Other October 13 merges cover fresh IDs for repeatedly updated local rows,
  replaced-twice delete files, struct field reorder/type/default handling, NULL
  options, renamed/dropped option scopes, bucket pruning/type promotion, remote
  separators, empty-table compaction and exposing `compacted_table` changes.
  These were triaged by commit/diff; not every scenario was independently run.

### Shipping pin versus main — checked by ancestry, not assumptions

| Fix | Maintained `ac7595b0` | 2.0 bundle `80e6155e` | Current main |
|---|---|---|---|
| Row-ID merge preservation #1611 | No | **Yes** | Yes |
| Change-feed deletion positions #1587 | No | No | Yes |
| Same-transaction delete/flush #1662 | No | No | Yes |
| Expiration fan-out #1659 / open-writer orphan protection #1669 | No | No | Yes |
| Older inline schema repair #1679 | No | No | Yes |
| #1690, #1695, #1712, #1714, #1717 | No | No | Yes |

Our previous failing native gates remain valid for their **sampled pins**. A fix
being merged on main is not evidence that the final shipping extension includes
it. Refresh this table at RC qualification.

## pg_ducklake: old branches and newer native publication

### Released/stable corrections missing from the old survey

Latest release remains **pg_ducklake v1.0.2** (August 4), whose notes include:

- [#227](https://github.com/relytcloud/pg_ducklake/pull/227): break tied snapshot
  timestamps by snapshot ID. A PostgreSQL transaction can give internal schema
  creation and table creation snapshots the same timestamp; the wrong tie chose
  a snapshot where the table didn't exist. **Parity:** our timestamp resolver
  already orders by timestamp DESC, then snapshot ID DESC. Current unpatched
  DuckLake main still orders that timestamp lookup by timestamp alone; pg_ducklake
  carries its own vendored patch.
- View-hook fixes (#231) and PostgreSQL parallel-mode/GUC/SPI longjmp fixes
  (#243/#244): backend hangs/crashes and lost C++ cleanup, not a JVM port. Relevant
  operational context for PG-hosted catalogs, not new format features.
- Stable `v1.0` additionally has
  [#259](https://github.com/relytcloud/pg_ducklake/pull/259), August 13, **after**
  v1.0.2: omit startup's default DATA_PATH on subsequent attach so relocating a
  catalog to S3 doesn't break reattachment. This is branch code, not a new tag.
  Only that source/test path fix differs from v1.0.2 on the stable branch.

### Main has a native writer, but it is still a DuckLake 1.0 writer

[#250](https://github.com/relytcloud/pg_ducklake/pull/250), August 7, implements
native VALUES/prepared UNNEST/COPY publication. Payload is inserted **once** in
the PG transaction; bounded retries/subtransactions rebase metadata rather than
reevaluating producer expressions. A shared reservation queue predicts snapshot
and row-ID allocations, but is advisory: correctness still reloads metadata,
validates schema/version/conflicts and claims the standard snapshot primary key.

Source observations:

- The native writer explicitly accepts **only metadata version 1.0**.
- Main's extension `default_version=1.1.0` is the **pg_ducklake extension version**,
  not DuckLake catalog-format 1.1 support and not the latest released tag.
- It records standard `inlined_insert:<table_id>` change tokens. The old research
  concern about bare `inlined_data_insert` is not how this new native path publishes.
- The vendored DuckLake gitlink is **unchanged** from reference `e43c6b8` on main
  and stable: `93cc490d9b5554f6fd5322dbef23f41d4fa91bb8` (June 2). New PG glue does
  **not** mean it automatically contains October DuckLake fixes.

Additional main changes:

- [#254](https://github.com/relytcloud/pg_ducklake/pull/254), August 11: forward a
  same-transaction DROP that metadata couldn't see before PRE_COMMIT. Otherwise
  CREATE+DROP left live DuckLake metadata without the PG relation. Prevents new
  stranded tables, does not repair existing ones.
- [#271](https://github.com/relytcloud/pg_ducklake/pull/271), October 10: send only
  the SELECT source to DuckDB for insertion into a real PG target. PostgreSQL
  retains defaults, constraints, triggers, RETURNING and ON CONFLICT semantics;
  DuckLake FDW targets keep their existing path. Main-only versus released v1.0.2.
- New coverage includes concurrent inline flush/retry, stats guards, producer
  semantics and catalog fingerprints. Useful regression scenarios; those upstream
  suites were not executed in this survey.

**Portable lessons:** transaction-owned publication checks, no user-expression
replay, advisory rather than authoritative ID reservations, explicit version gates,
and fresh table identity/schema validation. PG shared-memory/SPI internals are not
a JVM catalog feature to copy verbatim.

## DataFusion: released 0.9.0 versus eight unreleased changes

The historical Trino survey stopped around v0.7; our reference gitlink is v0.6-era.
Released **0.9.0** already adds multi-table statement snapshot consistency, live
catalog snapshot refresh, comments/tags, scoped settings, nested inline support,
NOT NULL write checks, nullable row-ID registration and broader metadata writers.
That is a useful parity baseline, not a dependency to add here.

The recent, **unreleased** corrections are particularly useful:

1. [#369](https://github.com/datafusion-contrib/datafusion-ducklake/pull/369):
   files upload as soon as finished, with bounded concurrency and abort cleanup.
   More importantly, a PostgreSQL/MySQL **COMMIT error can have an unknown outcome**.
   It now returns `CommitOutcomeUnknown` and keeps uploaded files, instead of
   deleting objects potentially referenced by a committed snapshot. Definite
   pre-COMMIT failures clean up; cleanup errors don't mask the commit error.
   Our catalog currently wraps write/commit failures in `DucklakeException`, without
   this distinct public outcome type. Audit caller behavior before treating any
   thrown commit as permission to delete output or blindly retry. This is a research
   gap, not a fault-injected reproduction of a local data-loss bug.
2. [#376](https://github.com/datafusion-contrib/datafusion-ducklake/pull/376):
   optional PostgreSQL commit guard runs on the owned transaction after its last
   mutation and immediately before COMMIT, allowing a locked fencing-token check.
   Refusal rolls back. This is more than our catalog-version guard and is not by
   itself an object-lifecycle/reader lease protocol. A guard must propagate every
   SQL failure: PG can acknowledge COMMIT of an aborted transaction as rollback.
3. [#375](https://github.com/datafusion-contrib/datafusion-ducklake/pull/375):
   conditional append/Replace now sees delete-file and inline-delete changes, not
   just data-file changes. Flush's conflict scope is deliberately narrower than
   Replace. Our retained change log/conflict matrix is the parity cross-check;
   don't replace it with latest-file-only checks.
4. [#373](https://github.com/datafusion-contrib/datafusion-ducklake/pull/373):
   PG generic prepared plans caused page×page×column fan-out (production reported
   74–87 seconds near 1,000 files). Use indexed per-file statistics probes, and a
   separate page-wide partition-value read where the index isn't file keyed.
   Upstream synthetic 4,000-file generic-plan time fell from 13.4s to 437ms.
   Test both custom and generic plans; don't add LATERAL blindly to Quack/MySQL.
5. Adjacent-merge [#371](https://github.com/datafusion-contrib/datafusion-ducklake/pull/371)
   has the **stale parity problem** described above. Newest isn't always safest.
6. Query-local catalog connection reuse (#366), delete-free row-ID scan fast paths
   (#368), and one empty-inline-registry read per snapshot (#378) are performance
   work; scoped absence caches must be invalidated by later writes/snapshots.

Early uploads keep uncommitted files visible for the **entire write duration**.
Orphan cutoffs must account for that duration, but age alone doesn't protect an old
object newly imported by another owner. Trino's shared-file-ownership design has
the stronger coordination requirements; the physical deletion agent owns that work.

## Other clients, documentation and transport

- **Spark:** `motherduckdb/ducklake-spark` main `d3ea0c92` / release **v0.4.0**,
  October 12. Since August 26, changes are release automation, Maven wrapper and
  Spark 3.5.8 / DuckDB JDBC 1.5.6.0 updates. No new catalog-protocol correctness
  fix appears in that interval's commit list. This is a release/source signal,
  not a Spark interoperability test.
- **awitten1 Trino:** main `40907de02c2d3a5e3f8ee7471474f535c077749f`, last change
  August 26. Added real DuckDB-built fixtures, DuckDB-file metadata tests and a
  driver-bundling decision; no newer movement found. Our Brikk Trino/Doris
  implementations remain their agents' responsibility and weren't edited.
- **DuckLake public docs:** stable conflict docs now explicitly distinguish
  concurrent inserts/deletes and allow deletes against different data files;
  expiration is **global/catalog-level**, not a schema/table-scoped retention
  setting. The docs list Spark, both Trino clients, DataFusion and pg_ducklake.
  This is public support guidance, not certification of those implementations.
- **Quack:** three main merges since our October 8 source capture cover the core
  bump, deprecated-API removal and database-size support; only small source changes
  in the reviewed delta. Main remains protocol v3; maintained v1.5 is unchanged.
  DuckLake still has server-side commit machinery; nothing here establishes a need
  to replace the maintained native wrapper or proves JVM/v3 qualification.
- **Orphan sweep watch:** current DuckLake main still sweeps `.parquet` and `.puffin`
  suffixes, with the newly known same-process writer protection from #1669. It
  still does not establish cross-process ownership/reader protection.

## Recommended follow-ups, without starting them in this survey

1. **Current-lane old-inline-schema regression and expiration/flush audit**
   (#1590/#1679), including missing durable schema-version rows. This is more urgent
   than enabling 1.1 feature fields.
2. **Commit-outcome classification plus downstream cleanup contract** (#369):
   fault-inject a lost COMMIT response and ensure no already-committed file is
   deleted or duplicate write blindly published. Coordinate caller semantics.
3. **Expand row-lineage and false-exact-stat/BLOB native canaries**, using independent
   expected rows/bytes rather than treating a known-buggy native aggregate as oracle.
4. **PG scale under generic prepared plans**, and follow up C05 separately. Preserve
   bounded SQL pages/path transfers and consumer traversal limits.
5. At T−3/T−4, recheck the **actual bundled extension**, not just current main,
   and rerun final artifact/migration/backend/transport acceptance gates.

## Verification and limitations

Checked GitHub branch/tag/release metadata, full first-parent history in isolated
filtered clones, targeted PR descriptions and source diffs, the public docs/calendar
and crates.io 0.9.0 publication. Fix inclusion in the maintained/bundled pins was
verified with Git ancestry. Key source corroboration includes both engines' merge
row-ID decisions, pg_ducklake's native version/token gates and unchanged vendor pin,
our timestamp/schema-version/commit exception paths, and current orphan suffixes.

No engine/connector test suites were run; upstream reported results are attributed
as such. No local correctness fix is claimed. Reference gitlinks, dependencies,
preview lock, production support and sibling repositories remain unchanged.
