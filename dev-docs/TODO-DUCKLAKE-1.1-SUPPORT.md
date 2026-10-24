# DuckLake 1.1 support TODO

**October24 R07 continuation:** Trino now has positive five-route two-worker SQL
with the legacy timezone pruning fix; Doris has a guarded PostgreSQL/S3 file tier.
Catalog3fd75dc/0058e8d/6146a3e/78c2a12 add decoded missing-file defaults, actual JDBC
statement cancellation/timeouts and SQL-paged file/planning metadata. Full latest
library checks pass898 maintained+86 direct-Java tests/17,602 records. Fresh
GPT6.1Sol connector sessions are adopting the new APIs. R07 is still OPEN: decoded
inline payload/CDC and deletion-candidate paging, complete consumer adoption and
engine/lease/route/deployment gates remain. Native failures are unchanged/unwaived.
[Current continuation and precise owner handoffs](R07-CONSUMER-CONTINUATION-2026-10-24.md).

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
- [x] **R06 — Scoped backend read coverage.** Qualify guarded final-1.1 reads on
  PostgreSQL, local DuckDB, native Quack, direct Java Quack and the narrower existing-
  MySQL read-only subset. Test native physical representations, owned-session
  consistency, fresh/migrated inputs where claimed, and malformed/unknown-layout
  rejection. **Owner scope decision, October 23:** MySQL reads of already-initialized
  1.1 catalogs are independent from native creation/upgrade/write/scanner support.
  The latter remains explicitly deferred below, not required to fabricate a full
  native pass before using the proven JDBC read subset. No default-on reads, 1.1
  writes, automatic migration or R07 rollout is implied. Final forced verification
  of this revised read scope is recorded below.
- [ ] **R07 — Read acceptance and rollout.** Run maintained/frozen ABI/wire controls,
  matching pristine full corpus, strict native gates and real native↔JVM fixtures:
  file/inline mixes, delete vectors, snapshot-tagged rows/deletes, history/CDC,
  statistics/defaults/NULL/BLOB/geometry, concurrent publication/flush, failure
  propagation and bounded planning. Update public scope and consumer handoff; only
  enable claimed cases after their gates pass. Connector agents qualify their own
  Parquet scans, CDC and coordinator/worker behavior.
  In-repository library acceptance/hardening and the handoff are verified below;
  R07 remains open for external consumer/rollout evidence and broader native release
  approval. Strict native failures are recorded, not skipped or waived.

## Deferred native MySQL work — outside the approved R06 read-only scope

- [ ] **M01 — Native MySQL creation/upgrade/mutation/scanner acceptance.** Preserve
  `:ducklake-catalog:final11MySqlNativeTest` and
  `:ducklake-test-corpus-replay:upstreamMySqlNativeTest`: 6 and 4 native failures,
  respectively, zero skips. Fixes/build/pin decisions need separate approval before
  reopening native migration, schema mutation, scanner SQL or lossy/unmaterializable
  inline types. Existing direct-JDBC reads do not certify these operations. See
  [reproducers](../docs/READ-MYSQL-1.1-BLOCKERS.md); no native failure is waived.

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
  queued, not implicitly certified. R06 now separates the qualified existing-MySQL
  JDBC read subset from deferred native MySQL creation/upgrade/write/scanner work.
  R07 rollout remains independent.
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

### R06 — partial: qualified DuckDB-backed route expansion

- Extends the existing opt-in final-1.1 reader/metadata/hierarchy tier to local
  DuckDB JDBC and native Quack, retaining direct Java Quack. No public record,
  constructor or JSON shape changes, dependency upgrades or write/migration guard
  changes. At this stage PostgreSQL/MySQL rejected opt-in before connecting; later
  PostgreSQL increments are recorded below. **R06 stays open**.
- Local mode requires an existing ordinary metadata file and opens DuckDB with
  database-level `duckdb.read_only=true`, with matching Hikari read-only state.
  Missing files and empty databases cannot be bootstrapped by opening/reading. A
  differently configured local RW instance is refused; native publication resumes
  after closing readers and reopening. This is not local multi-writer certification.
- Native Quack's opt-in initializer attaches raw `quack:` metadata READ_ONLY, with
  no DuckLake load/ATTACH, format selection, data-path override or migration. Its
  parser needs no data path. The flag-off writable 1.0 initializer and exact unit
  SQL expectations remain unchanged. Remote reads still activate the owned native
  transaction using `use_transaction := true`, never an unrelated RPC BEGIN.
- Capabilities preserve actual configured access-route labels and query engine/core
  on the executing metadata peer. Missing loaded extension revisions remain NULL.
  DuckDB JDBC advertises driverVersion `1.0` despite artifact 2.0.0.0; qualification
  checks the independently fetched Maven Central JAR SHA-256
  `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`, core
  `d54cbbe3df` and signed extension pins, not that misleading metadata version.
- Nine shared tests execute on each of three routes: real fresh/migrated values,
  initial defaults/nested evolution, temporal/container/BLOB/JSON/NULL/unsigned/
  decimal values, raw/decoded CDC identities, metadata/hierarchy/file paths,
  non-bootstrap/session/publication/refresh boundaries, native temporal-edge and
  physical-layout/type corruption rejection, and unknown/dev-format rejection.
- Four existing pristine upstream files require **eleven additional native-Quack
  transport mirrors**, alongside the previous 72 direct-Java mirrors, across the
  same twenty-one unique files. Two entire pristine default fixtures additionally
  finish their native replay before independent drained local read-only assertions;
  those are not simultaneous RW/RO mirrors or claimed JVM file scans. Original
  SQL/goldens/pins/exclusions remain unchanged.
- Final forced verification passed **685 maintained JUnit tests** (563 catalog,
  51 SLT, 71 replay), **86 separate shipping direct-Java qualification tests** and
  **17,602 corpus records**, zero failed/errors/JUnit-skipped. Existing 100 file/
  12 record corpus exclusions remain unchanged. All 27 shared route cases, the
  metadata-only initializer unit case and six pristine transport/local tests execute.
  All module checks/detekt, frozen Java/record/JSON controls and Java 17 `checkAbi`
  passed (7,770 classes, 17 artifacts). The unchanged-source forced run took 12m51s;
  session-owned test workers were reaped. R02–R04 native defects remain parked and
  distinct, not rerun or waived here; no artifact upgrade or SQL-backend acceptance
  is implied.
- **Remaining at this checkpoint before R06 closure:** PostgreSQL/MySQL physical type mapping and
  layout validation, safe inline default/evolution handling over their text/BYTEA/
  narrow physical types, portable rich metadata/hierarchy SQL and truthful nullable/
  non-applicable provenance, with fresh/migrated/session/negative tests. Do not
  remove URL guards, fabricate capabilities or treat old 1.0 tests as 1.1 acceptance.
  Model changes may follow the existing documented/versioned coordination policy;
  compatibility is not a design veto. [Public route/handoff contract](../docs/READ-BACKENDS.md).

### R06 — next increment: direct PostgreSQL subset

- Opens `jdbc:postgresql:` only for a documented root-schema subset: unchanged
  nested shape, scalar initial defaults, root renames and exact Boolean/integer
  widening. Every nested ID/name/type/order must be unchanged, recursively, so a
  same-named replaced grandchild cannot inherit old values. Other conversions fail
  before payload SQL; counts/identity-only operations can retain their narrow scope.
- Observes actual native PG physical types, including BYTEA text/JSON/BLOB,
  unbounded VARCHAR nested/wide/temporal values, native TIME/TIMETZ/INTERVAL and
  INTEGER logical NULL. Metadata/identity/physical-type and precision validation
  precede decoding; source integer/decimal/temporal domains are checked before any
  target widening. Raw reads validate too. No DuckDB-only expression reaches PG.
- Requires read-only REPEATABLE READ ownership instead of best-effort driver
  settings. `SET LOCAL intervalstyle='postgres'` is transaction-local and rolls back;
  role/session defaults are not rewritten. Native DDL/inserts remain outside the
  pin and next-session refresh preserves added initial defaults.
- Adds portable `readBackendProfile()` with actual server/driver observations and
  nullable unavailable/not-applicable source/extension/unsigned facts. Old DuckDB
  capability records, constructors/components/JSON remain intact. At this stage PG
  rich metadata/hierarchy companions were unavailable; the follow-up below ports them.
- Strict value decoding preserves complete STRUCT fields, ordered NULL/empty
  containers, exact UTF-8/BLOB/JSON/literal NULL values and original CDC identities.
  Duplicate fields/keys, logical non-NULL NULL-type values, source-domain corruption,
  malformed defaults and budget overflow fail; defaults are never SQL. Mutable BLOB
  defaults are independently copied per row. The shared native default path now also
  rejects unqualified missing types with NULL defaults, covered by a regression test.
- Text/BYTEA projections have a 1 MiB plus guard-unit SQL transfer bound; value/JSON
  traversal is bounded to 65,536 nodes/tokens and JSON depth 64. Numeric dimensions,
  native temporal precision and domain/narrowed VARCHAR declarations are checked.
  MAP key families outside Boolean/integer/VARCHAR/UUID/DECIMAL stay unqualified.
- Required opt-in file descriptor facts cannot become zero/false, while nullable
  row-ID starts remain NULL. `file_order` is the native reserved/unused NULL column;
  the historical legacy placeholder is not treated as a real ordering fact.
- Native setup keeps existing pins and additionally records the already-used signed
  PostgreSQL scanner `c0f5a3c0c4`. Native physical-layout observations and JVM reader
  assertions are independent. Actual 1.0→1.1 migration is explicit fixture setup,
  not a version retag or reader-side action. Existing native R02–R04 failures remain
  parked and are not waived by this scoped PostgreSQL increment.
- Four entire pristine shipped files require **six PostgreSQL JDBC mirrors**, in
  addition to previous route mirrors; original SQL/goldens/digests remain unchanged.
  See [adoption details](UPSTREAM-1.1-READ-TESTS.md). Focused pure/native/JDBC/upstream
  checks pass. Final forced verification passed **711 maintained JUnit tests**
  (585 catalog, 51 SLT, 75 replay), **86 separate shipping direct-Java tests** and
  **17,602 corpus records**, zero failed/errors/JUnit-skipped. Existing 100 file/
  12 record exclusions are unchanged. All twelve PG native/JDBC cases, eight strict
  value/profile cases, two schema/default regressions and four PG upstream fixtures
  execute. Lint, frozen Java/record/JSON and Java 17 `checkAbi` pass (7,779 classes,
  17 artifacts). The final unchanged-source run took 13m31s; owned test workers and
  containers were reaped. The six PG mirrors bring total transport mirrors to 89
  across the same twenty-one pristine files, plus the separate local final-state checks.
- **R06 remains unchecked.** At this checkpoint broader PostgreSQL evolution,
  rich metadata/hierarchy and MySQL remained, followed by R07. No plugin implementation or
  rollout, dependency/corpus upgrade, write guard or migration-default change.
  [Public subset/provenance/raw contract](../docs/READ-POSTGRES-1.1.md).

### R06 — PostgreSQL rich metadata and bounded hierarchy

- Qualifies the `readMetadata()` data operations and `readHierarchy()` companion on
  direct PostgreSQL. Keeps raw bounds, independent nullable exactness, nullable
  data/delete row groups, unversioned table statistics and conservative legacy
  pruning. Real native 1.0→1.1 migration preserves unknown old metadata.
- The portable backend profile reports metadata/hierarchy availability after layout
  validation. The old DuckDB-specific capability record remains unchanged and its
  accessor explicitly rejects PostgreSQL with guidance to `readBackendProfile()`;
  no invented source IDs, extension revisions or unsigned flags.
- Ports hierarchy byte expressions and DISTINCT/keyset ordering to PostgreSQL.
  Component/ID lookup, historical rename/drop/reuse and retained data/delete
  ownership use original file begin snapshots even after snapshot-row expiry.
  Schema paths remain catalog-root-relative, not logical-parent-relative; nullable
  directories, absolute resets and missing roots retain their exact meaning.
- Complete scheduled references include future/NULL ages and compare UTF-8 path
  bytes plus file ID/relative flag. Nondeterministic PostgreSQL collation tests
  preserve distinct case and composed/decomposed Unicode keys. Age-gated batches
  remain separate and are not complete known sets or acknowledgement cursors.
- Existing SQL page/text/depth/traversal bounds remain; duplicate boundary IDs,
  missing/cyclic/ambiguous owners, oversized paths/results, negative/missing counts,
  domain/narrowed physical metadata and non-microsecond scheduling fail explicitly.
  All operations require owned read-only REPEATABLE READ; independent native
  publication stays outside the pin and the next session refreshes.
- Adds **15 native/JDBC cases** and **seven whole pristine shipped files requiring
  24 additional PostgreSQL mirrors** (17 metadata, 7 hierarchy). Together these bring
  transport mirrors to **113 across the same 21 pristine files**; two drained-local
  final-state checks remain separate. Original SQL/goldens/digests and exclusions
  are unchanged. Focused PostgreSQL, all three DuckDB routes, pristine replay and
  86 separate direct-Java tests pass. Final forced verification passed **733
  maintained JUnit tests** (600 catalog, 51 SLT, 82 replay), **86 separate direct-Java
  tests** and **17,602 corpus records**, zero failed/errors/JUnit-skipped; unchanged
  exclusions remain 100 files/12 records. Lint, frozen Java/record/JSON and Java 17
  controls pass (`checkAbi`: 7,779 classes, 17 artifacts). The final run took 14m32s;
  owned test workers/containers were reaped. Log:
  `/tmp/opencode/catalog-r06-pg-metadata-full.log`.
- **R06 stays open:** broader PostgreSQL payload evolution and MySQL still need
  implementation/qualification. Legacy nested payload/descriptor guards, writes,
  migration defaults and all maintained pins are unchanged. Existing strict native
  R02–R04 failures remain parked, not waived. No connector implementation, physical
  scan/cleanup lease or rollout is certified.

### R06 — PostgreSQL exact root numeric widening

- Extends the unchanged-nested-shape PostgreSQL payload tier to native-allowed
  integer-to-DECIMAL, exact DECIMAL precision/scale widening and FLOAT-to-DOUBLE.
  Source domains and actual physical dimensions are validated before conversion;
  no decimal rounding, string-to-infinity coercion or approximate intermediates.
  Raw/decoded evolved decimals return target-scale BigDecimal; evolved floats
  return the exact widened binary value (FLOAT 0.1 → DOUBLE 0.10000000149011612).
- Missing-field initial float defaults preserve native literal intent (0.1), while
  actual stored FLOAT uses its binary value and stored NULL stays NULL. Historical
  target schemas and raw/decoded CDC preserve original row/begin/end identity.
  Real native 1.0→1.1 migration and concurrent publication/pinned refresh pass.
- Native probes reject Boolean-to-DECIMAL; the PostgreSQL reader explicitly keeps
  it closed. Approximate integer/decimal-to-float, narrowing, temporal and nested
  conversions stay unqualified. Invalid old integer domains cannot masquerade as
  wider decimal values, even via raw reads. Incorrect source numeric dimensions
  fail before a wider target could hide corruption.
- The descriptive backend-profile label advances to
  `postgres-same-shape-exact-numeric-widening`; record/constructor/JSON-field shapes
  stay unchanged. The label is not writer or worker capability negotiation.
- Adds **nine native/JDBC cases**, one pure schema-guard regression and three
  whole pristine corpus fixtures requiring **four additional payload mirrors**.
  The decimal source uses a separate stock-threshold native control, followed by
  an explicitly configured 1000-row inline profile with unchanged SQL/goldens;
  its JVM mirror requires no Parquet candidates. No default-threshold file scan is
  misrepresented as an inline JVM read. Total mirrors are **117 across 23 pristine
  source files**, plus two separate local final-state checks. Pins/exclusions stay fixed.
- Final forced verification passed **746 maintained JUnit tests** (610 catalog,
  51 SLT, 85 replay), **86 separate direct-Java tests** and **17,602 corpus records**,
  zero failed/errors/JUnit-skipped. Unchanged corpus exclusions: 100 files/12 records.
  Lint, frozen Java/record/JSON and Java 17 checks pass (`checkAbi`: 7,779 classes,
  17 artifacts). The final run took 15m10s; owned test workers/containers were reaped.
  Log: `/tmp/opencode/catalog-r06-pg-numeric-full.log`.
- **R06 stays open:** broader PostgreSQL payload evolution and MySQL remain.
  Writes, automatic-migration defaults, dependency/corpus/layout pins and legacy
  nested payload guards are unchanged. Parked native R02–R04 failures were not
  rerun or waived. No plugin implementation or rollout was performed.

### R06 — guarded PostgreSQL evolution qualified; native MySQL acceptance blocked

Historical checkpoint: the owner-approved read-only decision below supersedes the
blanket native-MySQL prerequisite, without waiving any native failure.

- Continues beyond the numeric checkpoint through the remaining PostgreSQL guarded
  read tier: stable-ID STRUCT add/drop/rename/reorder/replacement, LIST/MAP role
  identity, nested scalar defaults and source-domain-checked numeric/unzoned temporal
  promotions. NULL parents/elements and empty containers survive. Same-named replaced
  grandchildren take their new initial defaults; moved IDs and non-literal or
  non-NULL whole-container defaults stay rejected. Legacy parented SQL-schema
  payload/descriptor guards remain independent and unchanged.
- Full source decoding precedes projection, including dropped leaves. Expanded
  outputs have a 65,536-node budget and evolved raw text an additional 1 MiB bound.
  Raw changed containers/temporals return canonical native-compatible target text,
  independently tested through native casts, not source text mislabeled as evolved
  values. Quoted CAST-looking strings remain literal data; only unquoted native
  storage wrappers are unwrapped and none are executed as SQL.
- Scalar MAP keys now include binary, floating, temporal and interval domains.
  Native equality checks retain binary content, NaN/signed-zero and normalized
  interval duplicate detection; source/evolved duplicates fail instead of collapse.
  Composite MAP keys remain an explicit Java-equality representation boundary.
- Approximate numeric casts follow locked core `d54cbbe3df`, not older reference
  code: 128-bit integer double intermediates and correctly rounded nonzero-scale
  decimal casts. A bit-level live native matrix covers midpoint/extreme cases plus
  independent fixed expected values. Finite-overflow-to-infinity and target temporal
  range overflow are explicit unsupported conversions. Implicit zones/narrowing and
  native-rejected Boolean-to-DECIMAL/FLOAT/DOUBLE remain closed.
- Actual native PostgreSQL 1.0→1.1 nested fixtures and concurrent publication verify
  owned repeatable-read pinning/refresh. Raw/decoded history/CDC preserve original
  row/begin/end identities. The descriptive profile label is now
  `postgres-stable-id-evolution`; public records/constructors/JSON fields are intact.
- Adds ten whole pristine shipped files requiring **42 additional PostgreSQL
  payload mirrors** (33 nested/integer, 4 NULL, 5 literal/temporal). All original
  native SQL, errors, scratch query zones and goldens execute unchanged; no alleged
  post-flush JVM scans or uncommitted mirrors. Total transport mirrors: **159 across
  the same 23 unique source files**, plus the two separate drained-local checks.
- Native MySQL exploration records real fresh scalar BIGINT/INTEGER/TEXT/BLOB/DATE
  storage and the already-installed signed scanner `f7fc0d4271`, without upgrading
  existing artifacts. The reader guard stays closed. A separately reproducible
  strict task, `:ducklake-catalog:final11MySqlNativeTest`, fails **2 tests, 0 skips**:
  genuine 1.0→1.1 migration prepares an ambiguous default-restoration UPDATE, and
  fresh native inline STRUCT commit is unsupported. No retagging, hand-authored
  storage substitute or expected-error assertion turns those into passes.
- Final forced maintained verification passes **774 JUnit tests** (628 catalog,
  51 SLT, 95 replay), **86 separate driver-only Java Quack tests** and **17,602 corpus
  records**, zero failures/errors/JUnit skips. Existing exclusions remain 100 files/
  12 records. Lint, frozen Java/record/JSON and Java 17 checks pass (7,786 classes,
  17 artifacts). The final run took 16m19s. The separate final MySQL native task is
  explicitly **2 failures, 0 errors, 0 skips**. Logs:
  `/tmp/opencode/catalog-r06-evolution-full-final.log` and
  `/tmp/opencode/catalog-r06-mysql-strict-final.log`.
- **R06 remains unchecked because of native MySQL acceptance**, not another voluntary
  checkpoint stop. Its migration/nested contract must be fixed in an approved,
  separately pinned native build before the MySQL reader/metadata/hierarchy port
  can be qualified. No automatic upgrade, plugin rollout, 1.1 library writes or
  migration-default change is made. Existing R02–R04 failures remain parked and
  separate. [Exact repro/decision boundary](../docs/READ-MYSQL-1.1-BLOCKERS.md).

### R06 — existing MySQL 1.1 direct-JDBC reads, independent of native upgrades/writes

- Implements the owner's explicit read-only scope choice after distinguishing
  native mutation/migration/scanner defects from JDBC read feasibility. MySQL keeps
  legacy 0.4/1.0 and gains opt-in reads of already-initialized final-1.1 metadata.
  The proposed 1.0-only read ceiling was not adopted. Without opt-in, MySQL now
  refuses 1.1/unknown formats instead of accidentally leaking legacy metadata reads.
  Read open never creates, migrates, repairs, retags or downgrades a catalog.
- Requires server-enforced read-only REPEATABLE READ, UTC and utf8mb4, with actual
  server-variable checks. All native metadata/used inline tables must be InnoDB;
  physical signedness, character sets, UUIDs and TINYINT boolean domains are checked.
  SQL pages/paths stay bounded. MySQL byte ordering and ASCII-only name folding
  preserve Unicode/case/space path distinctions and complete queue cursors.
- Qualifies unchanged scalar native storage: signed/unsigned integers through uint128
  (except signed int128), finite FLOAT/DOUBLE, exact DECIMAL, VARCHAR/BLOB/UUID/NULL,
  DATE, second timestamps and lossless TEXT clocks/intervals/nanosecond zoned values.
  FLOAT widens on the server before MySQL text-protocol display can round its bits.
  Raw/decoded values preserve SQL NULL, literal NULL, embedded NUL and opaque bytes.
- Rejects unproven schema evolution/default projection and native lossy/unmaterializable
  inline forms (HUGEINT/JSON/composites/fractional DATETIME forms), even on NULL or
  missing-table projections. No PostgreSQL capability is guessed onto MySQL.
  Identity/count-only operations remain narrower than value materialization.
- Rich metadata preserves nullable independent exactness/row groups and actual raw
  bounds; legacy pruning retains all candidates. Hierarchy/retained ownership/path
  namespaces and complete scheduled references are qualified. Native DATETIME(0)
  schedule values are exposed as actually stored UTC seconds, not invented writer
  microseconds. Owned-snapshot publication/refresh and non-UTC-JVM tests pass.
- Native insert/history fixtures and clearly labeled owned row-end metadata verify
  raw/decoded CDC identity. The latter is not a claimed native DELETE pass or format
  retag. Existing no-arg test-fixture construction remains binary/source compatible;
  optional `main` databases let pristine native SQL use the generic backend's default
  namespace, while direct-JDBC tests also qualify the ordinary `ducklake` database.
- Three whole pristine shipped files require **six additional MySQL JDBC mirrors**:
  DOUBLE payloads (3), embedded NUL text (2) and nullable ownership paths (1).
  Original SQL/goldens/digests remain unchanged. Total mirrors: **165 across 25
  pristine source files**, plus the two separate drained-local final-state checks.
- Native fixture acceptance expands to **6 failures, 0 skips** (migration, composite
  storage, mutation, HUGEINT precision, timestamp precision, JSON). Four original
  whole native files separately fail on integer scanner types, boolean expression
  formatting and mutation/flush lifecycle. These **4 failures, 0 skips** remain
  strict gates, never rewritten goldens or green read-mirror counts. They now gate
  deferred native operations/types, not proven existing-catalog direct-JDBC reads.
- Final forced maintained verification passes **797 JUnit tests** (648 catalog,
  51 SLT, 98 replay), **86 separate driver-only direct-Java tests** and **17,602 corpus
  records**, zero failures/errors/JUnit skips; exclusions stay 100 files/12 records.
  All fifteen MySQL read/domain/session cases, five format-policy cases and three
  pristine read fixtures execute. Lint, frozen ABI/record/JSON and Java 17 checks pass
  (7,795 classes across 17 artifacts). The final run took 19m03s. Deferred native
  tasks were rerun separately: **6 + 4 failures, 0 errors, 0 skips**, not part of the
  green count and not waived. Owned workers/containers were reaped. Logs:
  `/tmp/opencode/catalog-r06-mysql-existing-read-full-final.log` and
  `/tmp/opencode/catalog-r06-mysql-deferred-native-read-scope-final.log`.
- **R06 is complete for the explicit owner-approved read scope.** The original
  blanket native MySQL creation/migration/scanner prerequisite is superseded only
  by the recorded existing-catalog read-only choice; M01 retains that native work
  and its failures. R02–R04 and R07 are not implicitly checked or certified.
- All 1.1 writes and automatic-migration defaults remain disabled on every engine;
  artifact/layout/corpus versions are unchanged. The same pinned MySQL Connector/J
  is added only to corpus test runtime. No connector changes, scan/cleanup lease,
  default-on read release or R07 rollout is performed. Shared R02–R04 failures remain
  separately parked. [Exact read contract](../docs/READ-MYSQL-1.1.md).

### R07 — guarded library acceptance complete; external rollout approval pending

- Adds **42 passing native/JDBC acceptance cases**: six common cases across all
  five routes (mixed file/inline plans, Puffin vector descriptors, geometry file
  metadata, caller abort/rollback, query failure propagation and bounded companion
  planning), plus three native lineage cases across local DuckDB, both Quack routes
  and PostgreSQL. MySQL's native mutation/flush cases remain deferred M01, not
  silently skipped. Native physical inspection is an independent test oracle,
  never advertised as a JVM/connector Parquet or geometry scan.
- Real tagged Parquet deletes retain multiple deletion snapshots despite NULL
  partial_max; later-window candidates retain current=previous paths. Native merge
  output spans distinct source snapshots/file-ID ranges separated by a real inline
  gap, retaining embedded absolute IDs/source tags and nullable rowIdStart at
  current/historical metadata views. Snapshot filtering remains consumer work.
- Real Puffin acceptance distinguishes a single bare deletion-vector-v1 blob
  (bounded length/magic/CRC check) from PFA1 containers with cumulative, snapshot-
  tagged blobs. Same current/previous paths do not blindly self-cancel container
  changes. Format recognition is not proof of a downstream decoder.
- Hardens opt-in final-1.1 `getLiveRowCount`: historical file-backed counts require
  a scan; unknown active delete counts cannot become zero; negative net counts
  fail as corruption instead of clamp to zero. Historical merged files can include
  later rows and delete files later deletions, so totals can overcount OR undercount.
  Latest known-count and inline-only historical paths remain. Public record/Java/
  JSON shapes and old 0.4/1.0 behavior are unchanged; the 0.13.0 opt-in narrowing is
  documented for consumers.
- Final forced maintained verification passes **839 JUnit tests** (690 catalog,
  51 SLT, 98 replay), **86 separate driver-only direct-Java tests** and **17,602 corpus
  records**, zero failures/errors/JUnit skips. Existing exclusions stay 100 files/
  12 records, and the 165 mirrors/25 pristine inputs plus two drained-local checks
  remain intact. Lint, frozen ABI/record/JSON and Java 17 checks pass (7,795 classes,
  17 artifacts). The completed run took 21m25s; an earlier externally stopped
  Gradle run is not counted. The rerun isolated its daemon registry without
  changing toolchain/artifacts.
- Strict native tasks were rerun on the final code: **R02 3 failures, R03 1 failure,
  R04 1 failure**, plus deferred MySQL **6 + 4 failures**, all zero errors/skips.
  Existing original SQL/goldens/crash isolation are preserved, not waived or
  treated as present fixes based on source merges. No dependency/corpus/layout
  repin, default-on reads, 1.1 writes, automatic migration or deployment is enabled.
- **R07 remains unchecked as a whole.** Its library work and handoff are done;
  downstream scan/CDC/worker/cancellation/lease evidence and any production/broader
  native approval have not been supplied. Neither owned plugin was changed or
  certified. This is an explicit external gate, not a voluntary incremental stop.
  [Decision, exact commands, matrix and consumer checklist](FINAL-1.1-R07-READ-ACCEPTANCE.md).
  Logs: `/tmp/opencode/catalog-r07-full-acceptance-final.log` and
  `/tmp/opencode/catalog-r07-strict-native-final.log`.

See [consumer compatibility contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md),
[acceptance gates](DUCKLAKE-1.1-ACCEPTANCE.md) and [agent guide](AGENT-GUIDE.md).
