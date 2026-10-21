# DuckLake 1.1 support TODO

Canonical implementation queue, starting from maintained DuckDB 2.0.0 / JDBC
2.0.0.0 and signed DuckLake `417a9117f5`, Quack `f96420ed46`. **Read work is active;
write work is deferred.** The [existing reader](../docs/READING-1.1.md) remains
default-off until its claimed scope is qualified. This is not blanket 1.1 support.

## Read support

- [x] **R01 — Missing-column initial defaults.** Project qualified scalar initial
  defaults for top-level columns absent from older inline schemas in ordinary/raw/
  decoded reads and CDC; nested-field schema evolution belongs to R02. Preserve stored NULLs,
  literal `NULL` strings, BLOB bytes, snapshot boundaries and row identity; never
  execute default text as SQL. Fail on malformed or unqualified values/types.
- [ ] **R02 — Inline schema evolution.** Map source/target fields by stable IDs,
  implement qualified scalar conversions and nested add/drop/rename/reorder/type
  evolution, and handle physically reordered legacy STRUCTs without positional
  value swaps. Historical reads and CDC must use their intended target schema.
- [ ] **R03 — Remaining inline types and edge values.** Qualify TIME/TIME_NS/TIMETZ,
  INTERVAL, VARIANT and other outstanding types, including nested values, nanoseconds,
  offsets, infinities and TIME 24:00. Define lossless Java values or explicit narrow
  rejection; do not pass through a lossy JDBC mapping as support.
- [ ] **R04 — Rich read metadata contract.** Add opt-in companion/envelope APIs for
  schema parentage, independent nullable bound exactness, nullable data/delete
  row-group counts and actual layout/provenance/capabilities. Preserve every existing
  Java constructor/accessor/record component and JSON shape. Missing metadata is
  unknown, never false/zero/exact. Keep old statistics/pruning fallbacks safe.
- [ ] **R05 — Parent-aware schema and path reads.** Resolve nested qualification by
  IDs across live/historical/retired metadata; reject missing parents, cycles and
  ambiguity with bounded depth/traversal. Preserve relative/absolute storage-path
  semantics and retained-file ownership. Legacy APIs must not flatten hierarchy.
- [ ] **R06 — Backend coverage.** Extend/qualify final-1.1 reads on PostgreSQL, MySQL,
  local DuckDB and native Quack as well as direct Java Quack. Test native physical
  layouts/type representations, owned-session consistency, fresh and migrated lakes,
  and explicit rejection of malformed/unknown layouts on each claimed route.
- [ ] **R07 — Read acceptance and rollout.** Run maintained/frozen ABI/wire controls,
  matching pristine full corpus, strict native gates and real native↔JVM fixtures:
  file/inline mixes, delete vectors, snapshot-tagged rows/deletes, history/CDC,
  statistics/defaults/NULL/BLOB/geometry, concurrent publication/flush, failure
  propagation and bounded planning. Update public scope and consumer handoff; only
  enable claimed cases after their gates pass. Connector agents qualify their own
  Parquet scans, CDC and coordinator/worker behavior.

## Write support — do not enable during read work

- [ ] **W01 — Versioned writer contract.** Define 1.1-capable fragments/capability
  negotiation without altering existing record/JSON contracts; mixed old/new
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
  and frozen ABI/wire controls. Do not replace goldens with observed output.
- Adopt upstream 1.1 tests as each capability is implemented. Keep original SQL and
  independent expected results, record exact source revisions/digests, and distinguish
  corpus tests matching the shipped extension from newer upstream regressions. Newer
  native failures remain visible gates; do not silently repin the maintained corpus
  or rewrite expected values to make the shipping binary pass.
- Commit each fully verified implementation increment and record its evidence below;
  push only when requested. Check a box only when that entire item's stated scope passes.
- Leave Trino/Doris implementation to their agents and stop session-owned infrastructure.

## Execution evidence

- Baseline: [`505aeac`](UPGRADE-DUCKDB-2.0.0.md), 605 maintained JUnit tests,
  33 shipping direct-Java tests and 17,602 corpus records; exclusions remain explicit.
- Completed increment: **R01**, typed missing-column defaults in the existing qualified
  direct-Java final-1.1 reader. R02–R07 remain queued, not implicitly certified.
- [Upstream test adoption](UPSTREAM-1.1-READ-TESTS.md) records shipped full-file JVM
  mirrors and exact newer upstream regressions queued for subsequent capabilities.

### R01 — verified October 21, 2026

- Missing top-level fields use the authoritative target snapshot's initial default,
  never caller-supplied replacement metadata or a later insert default. Values remain
  bound VARCHAR literals with strictly whitelisted native casts, not executable SQL.
- Ordinary raw/decoded reads and raw/decoded CDC use the same projection. Stored SQL
  NULLs remain NULL; a literal string `NULL`, empty string/BLOB, false and zero remain
  real values. BLOB bytes, decimal/unsigned/UUID/date/timestamp values, nanoseconds,
  timezone instants and CDC row/snapshot identity retain independent expected checks.
- Genuine native 1.0 → 1.1 migration retains literal defaults, NULLs and row IDs.
  Malformed numeric values, overflow and SQL-looking default text cannot execute
  statements or silently become NULL. Unsupported whole-container/default types
  remain rejected; nested-field defaults require R02, not a relaxed layout guard.
- Three entire pristine upstream fixtures are replayed against native goldens and
  **four required JVM mirrors**. Their exact digests and newer regression queue are
  in the adoption document above. No corpus pin, SQL/golden or exclusion was changed.
- Forced verification passed: **608 maintained JUnit tests** (510 catalog, 51 SLT,
  47 replay), **40 shipping direct-Java tests** (including seven new R01 tests), and
  **17,602 corpus records**, zero failed. Existing 100 file/12 record exclusions stay
  explicit. All module checks/detekt/frozen ABI and wire tests passed; Java 17
  `checkAbi` scanned 7,729 classes across 17 artifacts. Build completed in 10m38s.
- Writer version guards, reader opt-in/owned-session requirements, automatic-migration
  defaults, public records/constructors and existing JSON shapes are unchanged.

Reproduce with a checksum-verified official 2.0 CLI (allow at least twenty minutes):

```sh
python3 dev-docs/probes/prepare_corpus_data.py
mise exec -- ./gradlew check :ducklake-catalog:quackPreviewTest --rerun-tasks \
  -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/absolute/path/to/official-2.0.0/duckdb \
  --no-configuration-cache --no-daemon
```

See [consumer compatibility contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md),
[acceptance gates](DUCKLAKE-1.1-ACCEPTANCE.md) and [agent guide](AGENT-GUIDE.md).
