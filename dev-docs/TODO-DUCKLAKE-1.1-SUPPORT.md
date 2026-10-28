# DuckLake 1.1 support TODO

Remaining acceptance, native-backend and write work only. The declared limited-read
implementation is complete; do not restart it merely because broader acceptance
remains open. Current scope and exact external gates are in
[the checkpoint](R07-SCOPE-AND-QUALITY-CHECKPOINT.md). Completed requirements and
dated execution evidence are preserved in
[the history](archive/DUCKLAKE-1.1-SUPPORT-HISTORY-2026-10-28.md).

Maintained pins remain DuckDB 2.0.0 / JDBC 2.0.0.0, signed DuckLake `417a9117f5`
and Quack `f96420ed46`. Reads remain explicit/default-off; all 1.1 writes remain
disabled. A completed implementation is not blanket format or production acceptance.

**Classification after triage:** R02/R04 are external-artifact acceptance gates;
R03 includes a reproduced native producer failure; R07 is remaining engine/rollout
qualification; M01 is native-backend acceptance; W01–W06 are deferred features.
These are not eleven confirmed JVM bugs. Current reproduced defects are in
[the triaged queue](BACKLOG.md); evidence and non-bug dispositions are in
[the triage report](archive/TRIAGE-2026-10-28.md).

## Remaining read acceptance

- [ ] **R02 — Inline schema evolution.** Map source/target fields by stable IDs,
  implement qualified scalar conversions and nested add/drop/rename/reorder/type
  evolution, and handle physically reordered legacy STRUCTs without positional
  value swaps. Historical reads and CDC must use their intended target schema.
  Guarded reader implementation is committed as `91af736`; closure is paused until
  an installable, qualified DuckLake build bundles the fixes listed in the checkpoint. JVM
  passes do not waive those strict native failures.
- [ ] **R03 — Remaining inline types and edge values.** Qualify TIME/TIME_NS/TIMETZ,
  INTERVAL, VARIANT and other outstanding types, including nested values, nanoseconds,
  offsets, infinities and TIME 24:00. Define lossless Java values or explicit narrow
  rejection; do not pass through a lossy JDBC mapping as support.
  The qualified temporal/interval/NULL-type reader is implemented. Explicit edge/
  VARIANT rejection remains; broad closure is not claimed while the separate native
  interval persistence gate fails. The faulty producer component/fix is unresolved.
- [ ] **R04 — Rich read metadata contract.** Expose schema parentage, independent
  nullable bound exactness, nullable data/delete row-group counts and actual
  layout/provenance/capabilities. Prefer preserving existing Java/record/JSON contracts,
  but allow a justified documented change with coordinated Trino/Doris updates and
  verification. Missing metadata is unknown, never false/zero/exact. Keep old
  statistics/pruning fallbacks safe. The additive companion is implemented and
  verified; raw metadata presence does not qualify optimization/hierarchy. Native
  migrated-bound acceptance remains a separate failing gate.
- [ ] **R07 — Read acceptance and rollout.** Run maintained/frozen ABI/wire controls,
  matching pristine full corpus, strict native gates and real native↔JVM fixtures:
  file/inline mixes, delete vectors, snapshot-tagged rows/deletes, history/CDC,
  statistics/defaults/NULL/BLOB/geometry, concurrent publication/flush, failure
  propagation and bounded planning. Update public scope and consumer handoff; only
  enable claimed cases after their gates pass. Connector agents qualify their own
  Parquet scans, CDC and coordinator/worker behavior.
  Library and declared connector implementation acceptance is complete. Remaining
  work is broader engine/rollout evidence and upstream release verification; strict
  external failures remain recorded, not skipped or waived.

## Deferred native MySQL work — outside the approved read-only scope

- [ ] **M01 — Native MySQL creation/upgrade/mutation/scanner acceptance.** Preserve
  `:ducklake-catalog:final11MySqlNativeTest` and
  `:ducklake-test-corpus-replay:upstreamMySqlNativeTest`: 6 and 4 native failures,
  respectively, zero skips. Fixes/build/pin decisions need separate approval before
  reopening native migration, schema mutation, scanner SQL or lossy/unmaterializable
  inline types. Existing direct-JDBC reads do not certify these operations. See
  [reproducers](../docs/READ-MYSQL-1.1-BLOCKERS.md); no native failure is waived.

## Write support — deferred; do not enable during read work

- [ ] **W01 — Versioned writer contract.** Define 1.1-capable fragments and a safe
  rollout/capability strategy, preserving existing record/JSON contracts where practical or documenting
  a coordinated owned-plugin cutover where a break is justified; mixed old/new
  producers must be rejected before side effects when unsupported.
- [ ] **W02 — Version-aware metadata writes.** Implement final inline DDL/system
  names/reserved-name rules, schema parentage and statistics/exactness/row-group
  semantics. Unknown stays unknown; never inherit stale exactness from older rows.
- [ ] **W03 — Mutation lifecycle.** Implement/qualify create/alter/drop, file
  registration, insert/delete, inline flush, rewrite/compaction and partition/schema
  evolution. Preserve field IDs, nullable row-ID starts, embedded absolute row-ID
  precedence, original snapshots and per-position deletion semantics.
- [ ] **W04 — Maintenance and transaction outcomes.** Qualify ANALYZE, expiration,
  bounded cleanup/acknowledgements, concurrent native/JVM writers and retries on all
  claimed backends. Preserve unknown-COMMIT and acknowledged-post-commit classifications;
  never retry or delete committed/uncertain output automatically.
- [ ] **W05 — Migration, storage and restore.** Coordinate explicit native migration
  externally with writers stopped; qualify reserved-name collisions and PG/MySQL/
  local/Quack restore on consistent metadata+data backups. Reader open never migrates.
  A driver downgrade is not rollback after 2.0 upgrades local storage.
- [ ] **W06 — Write acceptance and enablement.** Real native↔JVM read/write/maintenance
  round trips on fresh/migrated lakes, backend failure/DDL semantics, mixed consumers,
  strict corpus and connector qualification. Add `1.1` to writable versions only
  after the approved write scope is implemented and verified; no version-string edit
  or blanket guard removal.

## Execution constraints

- Keep dependency/corpus/preview/layout pins explicit; no moving `latest` targets.
- Read work must leave **all 1.1 write guards and automatic-migration defaults unchanged**.
- Preserve independently expected rows/bytes/IDs, native failures, existing exclusions
  and semantic wire controls. Keep ABI/shape tests as compatibility detectors until
  an intentional contract change is documented and coordinated with both plugins;
  compatibility is best effort, not a design veto. Do not replace correctness goldens
  with observed output. See the [consumer policy](DUCKLAKE-1.1-CONSUMER-CONTRACT.md).
- Adopt upstream 1.1 tests as each capability is implemented. Keep original SQL and
  independent expected results, record exact source revisions/digests, and distinguish
  corpus tests matching the shipped extension from newer upstream regressions. Newer
  native failures remain visible gates; do not silently repin the maintained corpus
  or rewrite expected values to make the shipping binary pass.
- Commit each fully verified implementation increment and record its evidence in
  the archive; push only when requested. Check a box only when its entire scope passes.
- Leave Trino/Doris implementation to their agents and stop session-owned infrastructure.
