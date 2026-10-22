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
  Guarded reader implementation is committed as `91af736`; closure is paused until
  an installable, qualified DuckLake build bundles the upstream fixes below. JVM
  passes do not waive those strict native failures.
- [ ] **R03 — Remaining inline types and edge values.** Qualify TIME/TIME_NS/TIMETZ,
  INTERVAL, VARIANT and other outstanding types, including nested values, nanoseconds,
  offsets, infinities and TIME 24:00. Define lossless Java values or explicit narrow
  rejection; do not pass through a lossy JDBC mapping as support.
  Guarded temporal/interval/NULL-type reader increment implemented; final verification
  is recorded below. Explicit edge/VARIANT rejection remains; broad closure is not
  claimed while the separate native interval persistence gate fails.
- [ ] **R04 — Rich read metadata contract.** Expose schema parentage, independent
  nullable bound exactness, nullable data/delete
  row-group counts and actual layout/provenance/capabilities. Prefer preserving
  existing Java/record/JSON contracts, but allow a justified documented change with
  coordinated Trino/Doris updates and verification. Missing metadata is
  unknown, never false/zero/exact. Keep old statistics/pruning fallbacks safe.
  Additive companion implemented and verified below. Raw metadata
  presence does not qualify optimization/hierarchy; the newer native migrated-bound
  regression remains a separate failing gate, detailed below.
- [x] **R05 — Parent-aware schema and path reads.** Resolve nested qualification by
  IDs across live/historical/retired metadata; reject missing parents, cycles and
  ambiguity with bounded depth/traversal. Preserve relative/absolute storage-path
  semantics and retained-file ownership. Legacy APIs must not flatten hierarchy.
  Explicit hierarchy companion implemented and verified below. This does not unlock
  legacy nested payload/descriptor APIs or physical scans.
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
  with observed output. See the [current consumer policy](DUCKLAKE-1.1-CONSUMER-CONTRACT.md).
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
  direct-Java final-1.1 reader. **R02 is paused at native acceptance; the R03 guarded
  reader increment is verified, with native interval persistence still failing**.
  The guarded R04 metadata increment is verified; its separate native gate remains
  failing. R05's explicit bounded hierarchy/path tier is verified; R06–R07 remain
  queued, not implicitly certified.
  The owner will revisit R02 when the fixes are
  available in an installable, qualified DuckLake build; no automatic upgrade is implied.
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

### R02 — guarded reader implemented; shipping-native acceptance remains open

- Source/target column trees map by stable field IDs, not names or positions. Nested
  add/drop/rename/reorder/reuse and LIST/MAP members are reconstructed as target-ordered
  typed values. NULL parents/elements, empty containers and existing stored NULLs are
  preserved; defaults apply only to genuinely missing fields.
- Physical native types are parsed with a closed grammar. STRUCT order alone may
  differ, but missing/extra/renamed fields and leaf-type drift fail before conversion.
  SQL never interpolates raw physical type text. ASCII identifier folding matches
  DuckDB while distinct Unicode names remain distinct; quoted names and lambda-like
  payload names have explicit tests.
- Qualified promotions cover native numeric widening (including signed/unsigned,
  Boolean, float and decimal cases) and non-timezone-dependent DATE/timestamp precision
  widening. Narrowing, text coercion, container-kind changes and timezone-sensitive
  conversions are rejected pending their own contracts/qualification. Failed casts
  propagate, never TRY_CAST to NULL. Historical/ordinary/raw/decoded/CDC projections
  retain their intended target schema and original row/snapshot identity.
- The planner bounds schema width to 4,096 nodes, depth to 64, physical type text to
  16,384 characters and each generated projection to 1 MiB. Cycles, missing parents,
  duplicate IDs/names and identity transplants fail explicitly. Final-1.1 column
  fetches bound SQL rows before reconstruction and reject NULL required identity/
  type/nullability metadata instead of inventing ID/order zero or a false flag.
- Recorded expression/unknown creation-default kinds for missing fields are rejected
  rather than inventing NULLs or evaluating SQL. A legitimate later expression insert
  default does not overwrite the known original initial default. This is not a repair
  mechanism for lost or already-corrupted native history.
- The genuine upstream 1.5.6 gzip catalog is checksum-verified and explicitly migrated
  on an owned copy. JVM reads independently verify the untouched seed's eight tables,
  including reordered nested/LIST/MAP/widened STRUCTs. This is **not** a claim that the
  native reader can run the full newer SLT or flush that catalog correctly.
- Eight additional pristine shipped-upstream files require **33 JVM mirrors**; together
  with R01, eleven files require 37 mirrors. Native goldens continue after the bounded
  pre-flush mirrors where applicable; those later native scans are not passed off as
  JVM file reads. No maintained corpus SQL/golden/pin/exclusion was changed.
- Final qualified-reader verification passed: **623 maintained JUnit tests** (517
  catalog, 51 SLT, 55 replay), **52 shipping direct-Java tests**, and **17,602 corpus
  records**, zero failed or JUnit-skipped. Existing 100 file/12 record exclusions are
  unchanged. Lint, selected frozen Java/record/JSON compatibility controls and Java 17
  `checkAbi` passed (7,737 runtime classes across 17 artifacts). The initial forced
  suite passed, followed by complete rechecks for the final public metadata guards;
  the final `check :ducklake-catalog:quackPreviewTest` invocation took 10m50s.
- New tests cover independent nested BLOB bytes/literal NULL strings, raw/decoded
  ordinary/CDC casts and cast errors, Unicode/quoted names, null/empty containers,
  schema history, original row/snapshot IDs, and bounded public schema-corruption
  rejection with recovery across sessions. Dependency/corpus/preview pins, DTOs,
  existing fragment JSON, write guards and automatic-migration defaults are unchanged.
- R02 stays **unchecked** because broad shipping-native acceptance is not green;
  the separate strict gate below still fails all three cases. Successful guarded
  JVM reads are not a waiver for native DDL/flush defects or a native SIGSEGV.

#### Strict newer-upstream native gate — NOT GREEN

`mise exec -- ./gradlew :ducklake-test-corpus-replay:upstreamR02NativeTest
--no-configuration-cache --no-daemon` runs three whole pristine cases at `ea543c71`
against official JDBC 2.0.0.0/core `d54cbbe3df` and shipped DuckLake `417a9117f5`.
It currently reports **3 failed, 0 skipped**:

1. Legacy STRUCT read/delete/flush: native rejects the genuine physical field order;
   the first failure is the original SLT line 19 DELETE. Later read/checkpoint goldens
   also fail. JVM reads of the untouched migrated seed pass independently.
2. Non-literal added STRUCT-field default: native wrongly accepts the first expected-
   error ALTER and later returns NULL for the added field. The JVM reader rejects
   the recorded ambiguous history rather than evaluating it or substituting NULL.
3. Same-transaction added/renamed/dropped STRUCT fields: the shipping native extension
   **SIGSEGVs** inside `std::vector<duckdb::Vector>::_M_realloc_append`. Child JVM
   isolation reports this as an ordinary failing gate, not an unfinished/skipped test.

The fixes are merged upstream in
[fork #96](https://github.com/PeterVanHolland/ducklake/pull/96),
[#93](https://github.com/PeterVanHolland/ducklake/pull/93) and
[#90](https://github.com/PeterVanHolland/ducklake/pull/90), respectively, but are not
in the shipped extension pin. Do not claim waiting for artifacts is the old blocker:
the artifacts exist; **these specific shipped-native regressions remain**.

The strict task is separate from `check` because it qualifies newer source tests,
not the maintained shipped corpus. It never accepts failures as passes and never
assumption-skips them. Bounded child workers retain full logs and `hs_err` under
`ducklake-test-corpus-replay/build/reports/upstream-r02-native/`; their temporary
catalogs/data are owned copies and removed after both normal exit and native crash.
No unsigned extension setting or maintained/native/preview pin is changed.

Preparation for R02 adds one command before the reproduction above:

```sh
python3 dev-docs/probes/prepare_11_read_fixtures.py
```

### R03 — guarded temporal reader; native interval persistence remains open

- Adds finite TIME/TIME_NS/TIMETZ, INTERVAL, TIMESTAMPTZ_NS and SQL NULL-type
  projections on the same signed direct-Java peer, recursively in STRUCT/LIST/MAP.
  Validated native evolution precedes lossless VARCHAR projection. Decoded values
  keep normal Java temporals and independent interval months/days/micros; no new
  edge-value DTOs or driver-internal VARIANT maps are invented.
- Nanoseconds, offset seconds, native BCE/large-year text, midnight/max finite clocks,
  temporal initial defaults/rename/history/CDC and nested BLOB/JSON/NULL values have
  independent expected checks. Controlled owned physical INTERVAL fixtures cover
  negative/extreme components; they do not claim native INSERT/flush accepts them.
- Strict bounded interval parsing rejects garbage, partial >99-hour matches,
  malformed fractions and arithmetic/component overflow; `ms` means milliseconds,
  not months. Known Quack interval records decode by accessors, not debug text.
- VARIANT (including nested NULL-valued cases), TIME 24:00 and temporal infinities
  remain explicit unsupported operations, including raw reads. Zone-dependent
  promotions stay rejected; shipped TIME → TIME_NS ALTER is also rejected, not
  silently added as a reader widening rule.
- **Intentional development-tier raw value contract change:** final-1.1 temporal
  roots, including entire containers with temporal leaves, return validated native
  text in ordinary reads and CDC. Non-temporal/legacy raw paths, public records,
  constructors and fragment JSON are unchanged. Both plugin agents have a precise
  [handoff](DUCKLAKE-1.1-CONSUMER-CONTRACT.md); no plugin rollout is claimed here.
- Three more entire pristine shipped fixtures require **11 additional JVM mirrors**,
  48 across fourteen R01/R02/R03 files. Original SQL/goldens/pins/exclusions remain;
  [adoption details](UPSTREAM-1.1-READ-TESTS.md) document the unqualified date-to-zone
  mirror exclusion and scratch-only source query-zone reproduction.
- The separate `upstreamR03NativeTest` compiles and reports **1 failed, 0 skipped**,
  for real native precision loss: acknowledged `1 day 100:00:00.123456` flushes to
  `5 days 04:00:00.123` (123000 rather than 123456 sub-minute micros). Components also
  normalize. This is a visible failing persistence gate, not a shipping pass or
  altered golden. Shipped [#1727](https://github.com/duckdb/ducklake/pull/1727) only
  addresses negative inline writes. R03 stays unchecked at broad native acceptance.
- Final forced maintained-reader verification passed: **632 maintained JUnit tests**
  (523 catalog, 51 SLT, 58 replay), **62 shipping direct-Java tests**, and **17,602
  corpus records**, zero failed or JUnit-skipped. Existing 100 file/12 record corpus
  exclusions remain explicit. All module checks/detekt, frozen Java/record/JSON
  controls and Java 17 `checkAbi` passed (7,739 runtime classes across 17 artifacts).
  The unchanged-source full run took 11m35s; the separate native gate remains red as
  described above. All 1.1 write guards, migration defaults, opt-in/owned-session
  rules and dependency pins remain. Session-owned test processes were reaped.

### R04 — rich metadata companion; native migrated-bound acceptance remains open

- `JdbcDucklakeCatalog.readMetadata()` exposes an additive `DucklakeReadMetadata`
  interface. Existing records, constructors, `DucklakeCatalog` and fragment JSON
  are unchanged. No new metadata is silently threaded into legacy writer models.
- All operations require opt-in final 1.1/direct Java Quack and an owned session.
  Actual physical column/type validation is distinct from the legacy flat-schema
  preflight: raw parent inspection does not mark legacy hierarchy safe or unlock
  old reads. Parent-aware qualification/path traversal remains R05.
- Capabilities report actual engine/core, loaded extension revisions, JDBC driver,
  unsigned setting, physical columns and the transaction's visible head snapshot.
  Unknown extension provenance stays NULL. Metadata-presence flags do not enable
  hierarchy resolution, pruning, exact aggregates or writes (all remain false).
- File/group queries preserve independent nullable data/delete row-group counts,
  joined delete IDs/format/count and gross physical record counts. File stats retain
  independent nullable endpoint flags, values/counts/NaNs without target-type casts.
  Native table stats are explicitly unversioned, not historical aggregates. New
  companion JSON fixtures preserve unknown/false/true and unknown/zero distinctly.
- SQL bounds results to 65,536 rows and text transfer to 16,385 characters (a guard
  character permits rejection instead of silent truncation). Oversize results/text,
  missing/wrong-type columns, NULL required values, negative counts and invisible
  snapshot requests fail explicitly. Same-session pinning/next-session refresh and
  genuine native migration unknowns have independent direct-Java checks.
- Three pristine shipped-upstream files require **17 new metadata JVM mirrors**, 65
  across seventeen R01–R04 files. Original SQL/goldens/pins/exclusions are unchanged.
  Scratch metadata is populated solely from public JVM read APIs; native Parquet
  and footer-dependent goldens are not misrepresented as JVM physical scans.
- The whole newer `v10_retyped_bounds.test` at `ea543c71` is adopted in separate
  `upstreamR04NativeTest`, with exact SQL/gzip/ten-Parquet inventory and bounded
  decompression/child isolation. It reports **1 failed JUnit gate, 0 skipped**:
  **14 original records pass and 10 fail**. Timestamp equality misses a row, a
  less-than predicate wrongly includes it, float filters lose matches and native
  MIN returns `0.1` rather than `0.10000000149011612`. Suspect bounds remain instead
  of being cleared. No expected value was replaced, skipped or accepted as a pass.
- [Fork #94](https://github.com/PeterVanHolland/ducklake/pull/94), included in merged
  [upstream #1806](https://github.com/duckdb/ducklake/pull/1806), repairs this native
  migration but is absent from signed `417a9117f5`. The genuine 1.5.6 seed separately
  verifies JVM raw metadata/unknown flags and legacy retain-all/suppressed-bound
  behavior; that is not a claim that the original native SLT passes or a physical
  Parquet scan is qualified. R04 remains unchecked at broad native acceptance.
- Final forced maintained-reader verification passed **639 maintained JUnit tests**
  (527 catalog, 51 SLT, 61 replay), **74 shipping direct-Java tests** and **17,602
  corpus records**, zero failed/errors/JUnit-skipped. Existing 100 file/12 record
  corpus exclusions remain unchanged. R04 contributes four JSON/round-trip tests,
  twelve direct-Java peer tests and three whole-file upstream adoption tests.
  Test-count auditing caught and corrected a non-void Kotlin test before the final
  run; all twelve R04 peer cases execute. All module checks/detekt, frozen Java/
  record/JSON controls and Java 17 `checkAbi` passed (7,751 classes, 17 artifacts).
  The final unchanged-source run took 11m50s; the separate strict native gate was
  rerun and still reports 1 failed/0 skipped, with 14 original passes/10 failures.
  Session-owned processes were reaped. All 1.1 write guards/migration defaults,
  opt-in/session rules and dependency pins remain unchanged.
  [Public API/nullability and consumer handoff](../docs/READ-METADATA.md) is documented;
  neither owned plugin has been updated or certified by this increment.

### R05 — parent-aware hierarchy/path reader

- `JdbcDucklakeCatalog.readHierarchy()` adds a separate `DucklakeHierarchyReader`;
  no existing interface/record/constructor/fragment shape changes. Every operation
  uses the owning opt-in final-1.1 direct-Java session. Legacy flat APIs still reject
  all nested-schema history and cannot be unlocked by a successful new lookup.
- Schemas resolve through stable IDs, returning separate ordered IDs/name components.
  Literal dots/quotes and same-named children of different parents stay distinct.
  ASCII folding matches native identifiers without merging distinct Unicode names.
  Missing parents, cycles, duplicate identities/names and overlapping table versions
  fail explicitly. Current/history/drop/name-reuse checks retain original identity.
- Physical schema directories use the catalog root, **not logical parent paths**;
  tables/files resolve from their own scopes. NULL directories inherit, absolute
  flags reset, and non-NULL paths require a known flag. Root-only scheduled paths
  stay separate from table paths. Stored bytes/percent escapes/slashes/dot segments
  are not normalized or trimmed; unqualified root separators fail, not guessed.
- Retained data/delete pages include ended files and dropped owners. Ownership is
  resolved at the original file begin snapshot, not the latest name/version, even
  if that snapshot row has expired. Missing historic metadata fails instead of
  dropping references through a join or reporting an empty successful known set.
  A bounded extra row catches duplicate IDs at keyset page edges.
- The complete scheduled path known set has a separate exact composite cursor
  `(file ID, encoded path, relative flag)`, preserving case variants and future/
  unknown-age references. Duplicate identical path references collapse only for
  ownership, never for acknowledgements. The separate age-gated queue batch is not
  misrepresented as a complete known set. NULL/negative retained IDs cannot hide
  beyond a keyset cursor. No file/queue mutation is added or enabled.
- Listings bound SQL to 4,097 rows/4,096 nodes, depth to 64 and text/composed paths to
  16,384 characters. Point/history/ambiguity queries transfer at most two rows;
  retained pages are at most 1,024 plus one sentinel, with 8,192 operation-local
  lookup/traversal units. Caches never cross session boundaries. This is read-only
  metadata/ownership, not a lease or permission for physical deletion.
- The existing `qualifiedNestedSchemaResolution` flag becomes true for the explicit
  new API after physical validation (including table/path fields); optimization and
  write flags stay false. R04 peer tests deliberately now assert that API-specific
  capability while retaining independent legacy hierarchy rejection checks.
- Four entire pristine shipped files require **seven new JVM hierarchy/path mirrors**,
  72 across twenty-one R01–R05 files. Native SQL/goldens/pins/exclusions are unchanged;
  exports, helpers, uncommitted rows and Parquet payload queries are never falsely
  claimed as JVM scans. [Exact digests/limits](UPSTREAM-1.1-READ-TESTS.md) are recorded.
- Final forced verification passed **651 maintained JUnit tests** (535 catalog,
  51 SLT, 65 replay), **86 shipping direct-Java tests** and **17,602 corpus records**,
  zero failed/errors/JUnit-skipped. Existing 100 file/12 record corpus exclusions
  remain unchanged. All eight R05 pure/JSON, twelve R05 peer and four R05 upstream
  tests execute. All module checks/detekt, frozen Java/record/JSON controls and Java
  17 `checkAbi` passed (7,768 classes across 17 artifacts). The unchanged-source run
  took 12m04s. Session-owned test processes were reaped.
- R05 is checked only for the explicit documented schema/path/ownership tier; it
  certifies no physical scan, lease or destructive workflow. Existing R02/R03/R04
  strict native failures remain parked and distinct, not rerun or waived here;
  there is no artifact upgrade or claimed native repair. All 1.1 write/migration
  guards and opt-in/session requirements remain unchanged. The
  [public API and consumer handoff](../docs/SCHEMA-HIERARCHY.md) are documented;
  plugin implementation and physical scans remain with their agents.

See [consumer compatibility contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md),
[acceptance gates](DUCKLAKE-1.1-ACCEPTANCE.md) and [agent guide](AGENT-GUIDE.md).
