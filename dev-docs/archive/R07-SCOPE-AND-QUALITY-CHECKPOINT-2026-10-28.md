# 1.1 scope and code-quality checkpoint

> Archived October 28, 2026: completed catalog and limited-read verification
> evidence, including C01–C24 completion. Historical next-work and authorization
> statements below are not a second queue. Use the
> [current checkpoint](../R07-SCOPE-AND-QUALITY-CHECKPOINT.md) for remaining work.

**Read milestone closed October 25; C22 contract and C24 initialization corrections verified.** This is the current status and next-work
list. The original requirements remain in [R01–R07 / W01–W06](DUCKLAKE-1.1-SUPPORT-HISTORY-2026-10-28.md).
Historical reports are evidence, not additional active queues.

## Decision in plain language

**Keep the implementation. Do not restart or rebuild the history commit by commit.**
The declared library read features are implemented and have recorded qualification.
Both latest connector adoption rounds are finished. The three concrete connector
quality findings are now repaired and verified: Trino `3385429`, Doris `4efd9bc`.
The catalog candidate remains `6730bb8`; no library API or rewrite was needed.
Step 4 is complete: the declared limited-read implementation is accepted using the
verified unchanged library, prior scoped connector integration and final-code repair
tests. Follow-on verification and development remain separately tracked below.

Feature implementation, verification against upstream software, connector capability
and production release are different statuses. A feature can be implemented and
independently tested while its last interoperability test awaits an upstream fix.
That pending test is still pending, not a passing test or unfinished coding.

The earlier word **“native”** meant running SQL through DuckDB with its DuckLake
extension, sometimes using Quack or a PostgreSQL/MySQL scanner. It did not identify
the faulty component. Below, failures name the operation and known component;
unresolved attribution is explicit. Existing task names containing `Native` remain
unchanged so their original reproducers remain runnable.

## Original queue: implementation versus remaining acceptance

| Requirement | Our implementation | Remaining acceptance / scope |
|---|---|---|
| R01 initial defaults | Complete for qualified scalar missing-field defaults; `ce9608c`, later file API `3fd75dc` and history correction `10f81f9` | Preserve stored NULLs and initial rather than later INSERT defaults; whole-container defaults remain unsupported |
| R02 inline evolution | Qualified stable-ID reader implemented, `91af736`; PostgreSQL evolution completed through `1e01447` | Three DuckLake extension regressions have merged fixes awaiting artifact verification; unsupported conversions and MySQL evolution remain explicit |
| R03 types/edge values | Qualified finite temporal/interval/NULL-type reader implemented, `fa9a04d` | VARIANT, infinities and TIME 24:00 reject explicitly; interval persistence after DuckDB+DuckLake flush is unresolved, not known fixed awaiting release |
| R04 rich metadata | Complete scoped companion, `fb0c982`, PostgreSQL/MySQL ports in R06 | DuckLake migrated-bound fix awaits artifact verification; exposing metadata does not qualify pruning or exact aggregates |
| R05 hierarchy/paths | Complete explicit bounded metadata/path tier, `2d062e7` | Does not unlock legacy nested-schema scans or grant permission to delete files |
| R06 backend reads | Complete owner-approved five-route scope through `4dc319c` | MySQL reads existing catalogs only; M01 creation/migration/mutation/scanner acceptance is separate |
| R07 read acceptance | **Limited-read implementation milestone complete**: library through `6730bb8`, Trino `3385429`, Doris `4efd9bc`; scoped integration and three quality repairs accepted | Original broader rollout/engine requirements remain open; this is not full-format or production release certification |
| W01–W06 writes | Pending, deferred | Writer contracts, metadata mutation, lifecycle, maintenance/transactions, migration/restore and write acceptance |

R02–R04 and R07's original umbrella checkboxes remain open for their full stated
acceptance. They must not be interpreted as “implementation never completed.”
No original requirement or failing gate is removed by this status split.

## Exact current integration state

### October 26 library correctness follow-up

The first two-item batch, `05c6ca6`, fixes direct catalog schema/table lookup and C09 error
classification. The accepted `6730bb8` consumer bundle below is not overwritten or
silently presented as containing these newer source changes.

- **Identifier lookup:** actual API tests reproduced false ambiguity for distinct
  `Ä`/`ä` names on all five 1.1 routes. Schema/table point reads now reuse the
  hierarchy's ASCII-folding and binary-comparison rules, independent of metadata
  collation, with a two-row limit retaining duplicate detection. PostgreSQL ICU
  nondeterministic and MySQL case/accent-insensitive collations are exercised.
  Missing names stay absent; genuine duplicates reject; retired names do not collide.
- **C09:** actual 1.0 raw/decoded ordinary/CDC reads on local DuckDB, DuckDB/Quack
  and direct Java Quack no longer convert missing physical columns into empty data.
  An existing inline view's unrelated missing relation also propagates. The shared
  classifier reads only a recognized relation-not-found diagnostic, respects
  contradictory SQLStates, and requires the expected relation for optional-table
  fallback. Unknown/localized diagnostics propagate. Genuine lazy absence and
  missing-catalog initialization errors remain covered. Existing owned-session
  failure propagation already worked and is preserved.
- **Verification complete:** 26 focused tests plus lint pass. The final forced
  `check :ducklake-catalog:quackPreviewTest --rerun-tasks` with the full pinned corpus
  passes **1,006 maintained tests** (857 catalog / 51 SLT / 98 replay), **86 separate
  direct-Java tests** and **17,602 corpus records**. No JUnit failures/errors/skips;
  existing 100 file / 12 record corpus exclusions remain unchanged. Lint, frozen
  compatibility/wire controls and the Java17 check pass (7,830 classes / 17 artifacts).
  Full run: 28m22s, performed once on the final code.
  Before the fixes, 10 of the initial 20 cases failed with the expected API/diagnostic
  behavior. The additional genuine-initialization controls pass on all three
  DuckDB/Quack routes; the PostgreSQL inline-read interoperability regression also passes.
  Logs: `/tmp/opencode/catalog-read-corrections-red.log`,
  `catalog-read-corrections-focused.log`, `catalog-read-corrections-full.log`.
  Retained XML: `/tmp/opencode/catalog-read-corrections-full-xml/`.
- **Consumer impact:** no public interface/record/wire shape changes. Trino's
  guarded adapter already implements its own ASCII-only paged name lookup; Doris's
  guarded tier uses paged/captured metadata and the existing hierarchy rules. Neither
  guarded adapter uses the unowned legacy fallback that swallowed these errors.
  Their code and immutable candidate selection remain unchanged; these library-source
  fixes are not a new connector artifact qualification. No new integration bundle
  was needed for this internal correction; the old bundle remains unchanged. Legacy
  consumers can benefit when they adopt the corrected library in their next dependency update.

That first batch did not close C10 or change view-name lookup. The separate local
correctness batch below addresses those paths; upstream verification, reader type
support, 1.1 write guards and rollout approval stay independent.

### October 26 local correctness: views, namespaces, partitions and diagnostics

The owner authorized this finite follow-up. Initial actual-API regressions failed
22 of 32 cases on unchanged code: Unicode view ambiguity on all five routes,
accepted duplicate namespaces/columns and incomplete partition metadata on all five
1.0 write routes, and exposed synthetic URL credentials in catalog diagnostics.

- **View names:** read and write lookup use the shared ASCII-only/binary comparison,
  preserving distinct Unicode identities. Real 1.1 producers cover read lookup;
  1.0 API tests cover replace/rename/drop, historical tags and cross-kind collisions.
  These tests also exposed existing MySQL view/tag insertion errors: the reserved
  `sql` and `key`/`value` metadata targets are now explicitly quoted.
- **C10 namespaces:** schema and shared table/view admission runs inside the owned
  transaction; column and nested-field siblings reject duplicates under the same
  identifier rules. Duplicate input trees reject before metadata insertion. Existing
  IF-NOT-EXISTS field behavior recognizes ASCII-equivalent names without a snapshot.
  Race tests and existing controls preserve one-winner behavior and logical-conflict
  classification on retry; qualified field diagnostics and change-record ordering
  are retained. These repair existing 0.4/1.0 mutation paths, not 1.1 write support.
- **C18 partition payloads:** registration checks spec ownership/visibility and exact
  key coverage before any fragment is inserted. A present key with SQL NULL is
  distinct from an absent key; extra/missing keys, foreign/unknown specs and values
  without a spec reject. Queries fetch at most two ownership rows and the supplied
  key count plus one sentinel; validated spec keys are cached only inside the batch.
  Retired owned specs retain their immutable identities; this is structural payload
  validation, not recomputation of partition transforms or a new partition policy.
- **C23 diagnostic URLs:** catalog-owned log messages, error messages and diagnostic
  URL getters omit URL options/user-info rather than enumerating password aliases.
  The real connection URL and its properties remain unchanged. Diagnostic URL getters
  remain binary/source-compatible but their redacted values are not connection strings.
  This covers library-owned output; it does not rewrite third-party driver/pool logs
  or arbitrary exception causes.

**Verified complete:** focused checks cover all four repairs plus existing concurrency,
DDL, view-tag and DuckDB-oracle controls. The final forced
`check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all`
passes **1,030 maintained tests** (881 catalog / 51 SLT / 98 replay), **86 separate
direct-Java tests**, and **17,602 corpus records**. No JUnit failures/errors/skips;
the existing 100 file / 12 record corpus exclusions are unchanged. Lint, frozen
compatibility/wire controls and Java17 checks pass (7,834 classes / 17 artifacts).
Full run: 29m34s, once on the final code. Initial lint findings were fixed without
changing rules; the two focused runtime failures led to the MySQL identifier quoting
fix and restoration of the established qualified field-error message.

Evidence prefix: `/tmp/opencode/catalog-local-correctness-*` (initial red, focused
iterations, final targeted check, full log and full XML). C10/C18/C23 are closed for
the documented scopes. C11/C19 and the C06/C17 safe-refusal boundary were handled by
the subsequent batches below. No connector source, immutable
bundle, upstream/corpus pin, test golden/exclusion or 1.1 write guard changed. Consumers adopt these library-source
corrections in a later coordinated dependency update; no new integration bundle or
connector deployment is claimed here.

### October 27 correctness follow-up — C11 and C19

The owner requested the next local fixes if upstream availability did not unblock
our pending gates. The source/artifact review below established that condition.

- **C11:** `dropColumn` admits only top-level columns. `dropField` requires a nested
  STRUCT field, preserves at least one sibling and refuses required LIST/MAP roles.
  Nested changes cannot bypass partition/sort dependencies through their root.
  Valid subtree removals still preserve old snapshots and surviving identities.
- **C19:** top-level renames parse stored DuckDB sort expressions with the existing
  jOOQ dependency and rewrite only unqualified column references, not function names,
  types or literal text. The same transaction ends the previous sort-info version and
  allocates a new sort ID with copied directions/null ordering. Historical sort
  expressions remain unchanged; unrelated-column renames do not mint new sort IDs.
  Actual DuckDB inserts and time travel after rename pass on local and PostgreSQL lakes.
- **Explicit boundary:** unsupported dialects or parser syntax, subqueries, qualified
  references and bind parameters refuse before mutation. Nested renames under a
  sort-dependent root also refuse. Admission bounds are 4,096 sort keys, 16,384
  characters per metadata text/expression, 128 opening delimiters before parsing,
  and 8,192 rendering visits per expression. These are bounded interpretation limits,
  not a new SQL dialect or a promise of arbitrary-expression rewriting.
- **Verification complete:** 47 focused tests and lint pass. The final forced full
  check passes **1,059 maintained tests** (910 catalog / 51 SLT / 98 replay), **86
  separate direct-Java tests** and **17,602 corpus records**. No JUnit failures/errors/
  skips; the existing 100 file / 12 record corpus exclusions remain unchanged. Lint,
  frozen compatibility/wire controls and Java17 checks pass (7,839 classes / 17
  artifacts). Full run: 30m51s, once on final code.
  Initial failures reproduced destructive removals and stale sort metadata on all
  five 1.0 API routes, plus broken subsequent DuckDB INSERT on local/PostgreSQL.
  Five initial historical-DTO expectations were corrected to include the legitimate
  newly known end snapshot, then independently rerun red on the actual last-field bug.
  Evidence: `/tmp/opencode/catalog-c11-c19-*` (red, focused, full log and archived XML)
  and `catalog-c11-history-red.log`.

This is a library-source correction; no connector bundle, upstream dependency or
corpus pin was changed, and 1.1 writes remain guarded. C11/C19 corruption prevention
is complete within the explicit interpretation limits above; unsupported sort syntax
remains a refusal, not a claim of a general SQL rewriting engine.

### October 27 correctness follow-up — C06 and C17

The next bounded batch addresses MySQL inline-schema mutations through **safe
refusal**, the closure option recorded in the original C06/C17 findings.

- **Reproduced on current code:** an injected pre-publication failure with
  `ANSI_QUOTES,STRICT_TRANS_TABLES` leaves a dropped column's future `end_snapshot`
  and the new physical inline table committed. A controlled competing commit makes
  the failed DROP visible. Default MySQL SQL mode instead reaches invalid DuckDB
  identifier rendering; enabling ANSI quotes only exposes the atomicity defect.
- **Admission:** all seven column/field schema mutation entry points check for
  inline registrations on the owned transaction before column, sort or statistics
  writes. The check runs anew on retries. Empty/flushed registered tables also
  require physical schema evolution and reject. Existing-field IF-NOT-EXISTS
  remains a no-op; tables without registrations keep metadata-only mutations.
- **DDL backstop:** physical inline registration and the DDL/type renderer reject
  MySQL explicitly. MySQL cannot silently inherit DuckDB quoting or physical types,
  including when a later mutation entry point misses the early check. Returning
  `canInline=false` alone would incorrectly publish metadata against old inline storage.
- **Boundary:** qualified MySQL inline evolution still needs a coordinated physical
  table-preparation/publication protocol and backend rendering. Moving CREATE after
  metadata commit would introduce a missing-table window. These safeguards repair
  existing 0.4/1.0 APIs; M01 producer/scanner acceptance and W01–W06 remain separate.
- **Verification complete:** 61 focused tests and lint pass. The final forced full
  check passes **1,069 maintained tests** (920 catalog / 51 SLT / 98 replay), **86
  separate direct-Java tests**, and **17,602 corpus records**. Zero JUnit failures/
  errors/skips; existing 100 file / 12 record corpus exclusions are unchanged. Lint,
  frozen compatibility/wire controls and Java17 checks pass (7,839 classes / 17
  artifacts). Full run: 32m17s, once on final code.
  Authored 1.0 metadata/inline fixtures exercise both
  real MySQL SQL modes, injected failure, snapshot races, registration appearing on
  retry, and server-side mutation traps. A metadata-only control proves rollback and
  snapshot-PK retry still work. Initial trigger setup lacked MySQL's binary-log
  privilege; the disposable server's trigger setting was corrected and those cases
  rerun red on actual metadata mutation. Renderer test argument order was also
  corrected before its independent red rerun. Logs/XML: `/tmp/opencode/catalog-c06-c17-*`.

C06/C17 are closed for corruption prevention and explicit rejection of unsupported
DDL. Full MySQL inline evolution remains future writer work. Public signatures and
wire shapes are unchanged; the documented behavioral correction is an earlier,
typed refusal. Connector qualification still selects immutable `6730bb8`; this
library-source fix does not constitute new consumer adoption or deployment.

Final command (same pinned inputs and existing exclusions):

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-daemon --no-configuration-cache
```

### Legacy-read reconciliation and C08/C21 follow-up

The owner authorized this bounded batch after a current-code probe on `3c1e1dc`:
native DuckDB returned `(7,42)` after ADD COLUMN DEFAULT, while legacy ordinary reads
returned `(7,NULL)`; a live row's empty projection returned no rows. C16's original
large/signed interval, millisecond and quoted-CAST cases already pass in the shared
decoder (`fa9a04d` / `1e01447`), including outside the 1.1 opt-in path.

- **C08:** ordinary legacy reads reuse the existing checked default projections
  only for missing root IDs. Fresh bounded target metadata supplies type/default
  authority; rename/widening and later INSERT defaults do not change initial values.
  Stored NULLs and present-column behavior remain intact. DuckDB/Quack/PostgreSQL
  support qualified scalar values and NULL containers; unsupported default history,
  non-NULL containers and MySQL missing-column evolution reject explicitly.
- **CDC decision:** legacy raw/decoded feeds retain the documented NULL-fill for
  absent source fields. Their identity-only projections already retain cardinality.
  Final-1.1 ordinary/CDC/page behavior is retained, not extended to new types/routes.
- **C21:** non-opt-in ordinary reads select row identity for an empty payload,
  producing one empty value list per live row under the same snapshot predicate.
  Opt-in ordinary lists still reject empty projections; their page API supports
  identity-only projections as before.
- **C16:** reconciliation closes the existing decoder defects; no datatype
  representation or parser rewrite was needed. A maintained regression now also
  locks quoted literal `CAST(...)` preservation through the legacy nested decoder.
  The upstream R03 interval-flush persistence failure remains independently open.
- **Verification complete:** corrected JUnit Unit-return declarations ensure all
  18 targeted cases are discovered; 17 C08/C21 cases fail on unchanged production
  code, and the already-fixed C16 case passes. Mixed Int/Long expected lists were
  explicitly typed before the fixed-code run. **72 focused tests and lint pass**, with
  zero failures/errors/skips. The final forced full check passes **1,087 maintained
  tests** (938 catalog / 51 SLT / 98 replay), **86 separate direct-Java tests** and
  **17,602 corpus records**. Zero JUnit failures/errors/skips; existing 100 file /
  12 record corpus exclusions are unchanged. Lint, frozen compatibility/wire
  controls and Java17 checks pass (7,840 classes / 17 artifacts). Full run: 32m58s,
  once on final code, with the same full-check command and pins as C06/C17 above.
  Evidence: `/tmp/opencode/catalog-backlog-reconcile.log` and `catalog-c08-c21-*`.

C08/C21 are closed within these explicit read/representation boundaries; C16 is
closed by reconciliation of existing implementation and current evidence. C07
pruning was addressed by the subsequent batch below. Public signatures
and wire shapes are unchanged. The immutable connector qualification bundle remains
`6730bb8`; later library fixes await coordinated consumer adoption.

### C07 logical-type pruning correction

After pushing `fad3bd8`, the owner authorized C07. All **15 targeted cases failed**
against unchanged production code: five-route FLOAT32 and TIME pruning, two native
Parquet-file cases (local/PostgreSQL), and three comparator/unknown-value controls.
DuckDB's independent typed comparisons retain values the library was pruning.

- Float statistics and predicates now round to the declared FLOAT32/FLOAT64 binary
  precision before using the existing comparable decimal key. Equivalent decimal
  spellings compare equally, including signed zero/subnormal cases; adjacent values
  remain distinguishable. NaN, infinity, malformed text and binary overflow remain
  unknown. Integer/decimal key precision is retained.
- TIME/TIME_NS `24:00:00` uses the existing nanosecond key at the end of the day,
  distinct from midnight. Strict parsing prevents Java's SMART clock normalization;
  malformed/unhandled clocks become unknown. This does not introduce a new inline
  temporal representation or close broader temporal-edge support.
- Numeric swapped-bound detection and bound merging share the corrected comparator.
  Existing NaN/missing/swapped-stat protections and conservative 1.1 pruning remain
  covered. Tests require selective exclusion of genuinely nonmatching point files,
  rather than accepting a blanket retain-all implementation.
- **Verification complete:** 98 focused tests and lint pass, with zero failures/
  errors/skips. Actual API tests compare five metadata routes with
  DuckDB logical comparisons. Separate native local/PostgreSQL lakes use real
  Parquet files and independently confirm the matching query row. The final forced
  full check passes **1,102 maintained tests** (953 catalog / 51 SLT / 98 replay),
  **86 separate direct-Java tests** and **17,602 corpus records**. Zero JUnit
  failures/errors/skips; existing 100 file / 12 record corpus exclusions remain
  unchanged. Lint, frozen compatibility/wire controls and Java17 checks pass
  (7,840 classes / 17 artifacts). Full run: 33m49s, once on final code, using the
  same full-check command and pins recorded above. Evidence: `/tmp/opencode/catalog-c07-*`.

C07's two original correctness defects are closed. The comparison still uses the
existing public comparable key types and stored statistics text; no new wire/API
shape or data representation was introduced. Conservative 1.1 min/max pruning,
UF-04 bucket behavior and broader historical-type qualification retain their own
boundaries. The accepted consumer bundle remains `6730bb8`; this source correction
is not a new connector adoption or deployment. C12 was subsequently verified and
closed below.

### C12 partial-rewrite lineage reconciliation

The original overlap finding predates `0b6c31b`. Current partial rewrites use
`InsertMode.REWRITE`, register every output with NULL `row_id_start`, preserve the
existing allocator and require embedded original absolute row IDs in addition to
insert-snapshot IDs. A common source minimum no longer supplies a fallback range.

**Verified complete:** `TestPartialRewriteMultiOutputLineage` exercises the real API
on local and PostgreSQL 1.0 lakes. Two source snapshots and two partitions become
four output Parquet files with reordered, noncontiguous original row IDs. Native
DuckDB verifies current rows, both historical snapshots, partition filtering,
subsequent DELETE and next INSERT row-ID allocation. All four output descriptors
have NULL starts; source metadata is retired and the gross row count is preserved.
The two new cases plus existing rewrite/history/eligibility controls total **19
passing tests**, zero failures/errors/skips, and lint passes.

Trino `3385429`'s `DucklakeRewriteDataFilesProcedure.GroupWriter` reads the lineage
virtual column and writes it into every output, plus snapshot tags in partial mode.
Doris `4efd9bc` has no rewrite caller. Both clean consumer sources were inspected;
this is not a fresh connector execution/adoption claim. The `PartialMergedFile`
KDoc now repeats its enclosing API's embedded-ID requirement. Registration consumes
metadata fragments; callers remain responsible for materializing those columns.

Only tests and KDoc/documentation changed. The final `9dd4073` full-check evidence is reused for
unchanged code; this closeout adds targeted evidence rather than repeating that
full suite. Evidence: `/tmp/opencode/catalog-c12-verification.log` and
`catalog-c12-verification-xml/`. C12 was closed in `5141ad6`; C13 follows below.

### C13 materialization-boundary correction

After C12 closeout, current-code regressions reproduced actual row loss through all
three snapshotless flush forms on local/PostgreSQL lakes: an INSERT committed after
materializing two rows disappeared when the old flush retired/drained three rows.
Snapshotless DELETE/MERGE also published stale cumulative replacements on all five
metadata routes. **16 of 19 targeted cases failed**; the two snapshot-aware controls
and the retained-descriptor control already passed.

The 0.13 behavioral cutover is explicit: snapshotless flush, both compatibility
snapshot-flush forms, and snapshotless DELETE/MERGE reject before mutation with
`DucklakeInvalidOperationException`. Existing method signatures remain, including
`void` on interface defaults. Private DELETE/MERGE paths now require a non-null
planning snapshot; they cannot substitute the commit-time base. Empty payloads
through these old forms also refuse.

The [consumer migration contract](../../docs/MATERIALIZATION-SNAPSHOTS.md) describes
how to capture the snapshot before materialization, preserve historical flush
payloads and replan stale output. Clean Trino `3385429` already calls the explicit
snapshot overloads for all these operations; clean Doris `4efd9bc` has no mutation
callers. No consumer source change or new artifact adoption is needed for this
catalog-source correction. Coordinated qualification remains required when updating
their selected dependency bundle.

Tests formerly pinning successful weak legacy behavior explicitly change with the
documented 0.13 contract. Valid-write/concurrency/rollback controls now pass their
captured planning snapshots. Existing per-file overlapping-range validation is
tested directly through the supported flush API. No golden/exclusion is relaxed.

**Verification complete:** 89 focused tests and lint pass with zero failures/errors/
skips. New regressions check all five rejecting forms,
preserved rows/history, no mutation attempts or snapshot publication, unchanged JVM
descriptors, and successful rematerialized snapshot-aware flushes. The final forced
full check passes **1,123 maintained tests** (974 catalog / 51 SLT / 98 replay),
**86 separate direct-Java tests**, and **17,602 corpus records**. Zero JUnit failures/
errors/skips; existing 100 file / 12 record corpus exclusions are unchanged. Lint,
frozen compatibility/wire controls and Java17 checks pass (7,840 classes / 17
artifacts). Full run: 34m17s, once on final code, including the new C12 multi-output
tests, using the same full-check command/pins recorded above. Evidence:
`/tmp/opencode/catalog-c13-*` (red/focused/full logs and archived XML).

C13's snapshotless-mutation finding is closed through explicit refusal and the
documented migration to existing APIs. The ordinary rewrite API's concurrent
native inlined-delete limitation remains separate; no broader writer or 1.1 write
acceptance is implied. Connector artifacts and their immutable qualification bundle
remain independently versioned. The subsequent C22/C24 batch is recorded below.

### C22 path-namespace contract correction

The owner authorized current-code reproduction of C22 and C24 after `3fe5e5f`.
C22's raw list still combines table-relative retained files with catalog-root-relative
scheduled files. The new five-route relative-schedule regression demonstrates the
old documented resolution duplicating `schema/table/`, while bounded retained-file
pages and age-eligible queue batches retain their distinct bases. The separate
deprecation assertion failed on the original code.

The chosen original-review closure option is deprecation and an accurate contract,
not an incompatible normalization of the returned paths. Both JVM owners now warn
that `listReferencedFilePaths` is unsafe for orphan detection. Its descriptor,
DTO shape and raw results stay unchanged. The catalog-wide compatibility listing's
namespace separation remains tested, including a relative scheduled path, but its
documentation no longer promises an unqualified safe orphan sweep.

Source audits of clean Trino `3385429` and Doris `4efd9bc` find no production orphan
caller of the deprecated API: both use catalog-wide references and resolve queue
paths against the root separately. Trino queued cleanup already uses bounded
candidate/reference APIs. Doris queued cleanup and both orphan implementations
still use unbounded compatibility listings. That **bounded-maintenance adoption
remains open**, separately from C22's corrected namespace contract. An age-eligible
scheduled batch is not all queued ownership and cannot substitute for a full orphan
known set. No new bulk API or sibling edit was made; no consumer artifact was adopted.
See [the exact namespace and migration limits](../../docs/FILE-REFERENCE-NAMESPACES.md).

### C24 initialization-boundary correction

An isolated detached `3fe5e5f` checkout, with identical regression sources and no
production edits, reproduces the inconsistent boundary: **57 of 67 tests fail**,
while the ten missing-column negative controls pass. CREATE exposes generic
`DucklakeException`, file preflight exposes raw `DataAccessException`, and empty
snapshot reads can return zero. jOOQ's primitive `long.class` conversion turns
aggregate SQL NULL into zero, so the previous nullable Kotlin local did not actually
distinguish an empty catalog from valid snapshot 0.

The shared latest-snapshot query now requests boxed `Long`, preserving SQL NULL.
Snapshot acquisition inside each write attempt and version/encryption preflight
queries use the existing narrowly classified initialization guard. File preflight
also requires a snapshot before accepting empty fragment lists. Missing metadata
tables and an empty snapshot table therefore report
`DucklakeCatalogNotInitializedException` with the same redacted bootstrap guidance.
No metadata/bootstrap DDL is performed. The outer planning guard still includes
context/layout validation; unrelated missing columns are not initialization errors.

The five-route tests cover CREATE SCHEMA/TABLE, INSERT/add-files, empty payloads,
missing snapshot/metadata tables, and recovery on the same instance after restoring
the snapshot. Bare local databases and missing-column negative controls are included.
Initialization checks do not change legacy native-Quack ATTACH/bootstrap, writable
version/encryption guards, read-session rejection or COMMIT outcome classification.

**Verification complete:** 226 focused tests and lint pass. The forced full check
passes **1,196 maintained tests** (1,047 catalog / 51 SLT / 98 replay), **86 separate
direct-Java tests**, and **17,602 corpus records**. Zero JUnit failures/errors/skips;
the existing 100 file / 12 record corpus exclusions are unchanged. Lint, frozen
compatibility/wire controls and Java17 checks pass (7,840 classes / 17 artifacts).
Full run: 37m35s, once on final production/test code, using the same pinned inputs
and full-check command recorded above. Source hashes were checked against that run.
Evidence: `/tmp/opencode/catalog-c22-c24-focused-final{.log,-xml/}`,
`catalog-c22-c24-full{.log,-xml/}`, `catalog-c22-c24-source-hashes.json` and
`catalog-c24-baseline-writes-red{.log,-xml/}`, all under `/tmp/opencode/`.
Failed exploratory/build invocations are not passing verification.

C22's original deprecation/contract option and C24's initialization finding are
closed within these scopes. Bounded consumer-maintenance adoption remains open as
described above; no further batch was started. The accepted connector bundle
remains `6730bb8`; no push, publication, deployment or 1.1 writes are authorized by
this correction.

### Accepted October 25 integration baseline

All three main trees were clean at evaluation start. Catalog documentation HEAD was
`0cd5a48`; runtime source is still `6730bb871350fe27d6c926db2f1d8f50f41cb5e1`.
The immutable bundle is `/tmp/opencode/catalog-r07-6730bb8`. Both consumers used
catalog JAR SHA256 `9ae564720d48b78f8aa6d4349cc082a8ff11815dfe8a9e639a5c140cc95cbab6`.
All ten bundle manifest entries were rechecked successfully from `maven-repository`.

| Component | Final evidence | Scope, not a blanket support claim |
|---|---|---|
| Catalog | Existing full 985 maintained + 86 separate driver-only tests, 17,602 corpus records; lint/ABI/wire/Java17 | Metadata/inline reads and descriptors; physical file scanning belongs to connectors |
| Trino `83cb24b043d90eaad162e43ef5a51396e5b61ed8` | Final callback/report; retained XML independently confirms 143 candidate and 266 released tests, zero failures/errors/skips | Guarded opt-in five-route SQL/worker trials, inline/file/CDC; missing-file defaults on four routes, MySQL evolution rejected |
| Doris `3bad03f7c01864e85beda2e87abba7f92ecea518`, implementation `f025a93` | Final callback/report; retained XML confirms 17 live and 14 targeted tests, zero failures/errors/skips | Default-off PostgreSQL/S3 unencrypted ordinary file tier; inline/mixed-history/unsupported types reject |

Doris's Java25/17 candidate lanes are 262/236 and released lanes 253/227, with one
unchanged legacy skip per overlapping lane. Do not add overlapping lanes into a
unique-test total. Its released ZIP was restored byte-identically. Trino's 81-record
types run and both consumers' PostgreSQL cancellation/recovery evidence are in their
final reports. These are retained results, not newly rerun engine suites here.

Authoritative consumer reports:
- `../trino-ducklake/dev-docs/R07-TRINO-CANDIDATE-QUALIFICATION.md` (sibling repository).
- `../doris-ducklake/dev-docs/VALIDATION-R07-ADOPTION-2026-10-25.md` (sibling repository).

Both sessions are finished: Trino `ses_e8be2ebaeffeDrkOEHWirU0xs3`, Doris
`ses_e8be6ad39ffedAk2DgeiQf6A3j`. Earlier statements that they were pending or dirty
are superseded. Do not restart their completed assignments.

### Step 3 repair evidence — current connector revisions

- **Trino `3385429`: QG-01 and QG-05 fixed.** Six focused tests pass with zero
  failures/errors/skips, plus lint and candidate hash verification. Tests exercise
  actual adapter/control registration with JDBC cancellation faults, two-worker
  Unicode/ASCII name resolution, true duplicate rejection, and real PostgreSQL
  engine cancellation/deadline-before-unlock with pool recovery.
- **Doris `4efd9bc`: QG-02 fixed.** Three actual-facade lifecycle regressions pass
  on both Java25 and Java17. Full `check java17PluginTest` passes: 265/239 passing
  cases (266/240 totals, one identical pre-existing skip per overlapping lane),
  lint and the 24,767-class Java17 runtime scan. Capture failure memoization and
  shared statement ownership remain covered.
- These regressions failed against the original production code before the fixes.
  The repaired Doris artifact has not been staged into FE/BE; the previous 17-case
  live run keeps its own artifact identity, rather than being claimed as rerun.
- Both default released-lane JARs were rebuilt byte-identically to their previous
  hashes; candidate classes are absent. Doris's released ZIP remains unchanged.
  No catalog artifact, pin, test golden/exclusion or release default changed.

Commands, logs and exact verification scopes are in the existing consumer reports.
Evidence is retained under `/tmp/opencode/r07-step3-{trino,doris}-*`.

### Step 4 acceptance and evidence reuse

The October 25 closeout checked source boundaries, retained XML and successful final
build logs against the then-committed revisions. At that closeout there had been
**only documentation changes** in the catalog since `6730bb8`; the October 26 source
corrections are tracked separately above. Trino's production delta since its adopted `83cb24b` is
only `DucklakeCandidateAdapter.kt`; Doris's since `3bad03f` is only
`DuckLakeFinal11.kt`. No changed dependency, scan decoder or schema/row/delete
projection was found that would invalidate the reused library/scan evidence.

| Accepted contract | Final-code coverage and retained evidence | Closeout |
|---|---|---|
| Catalog's declared five-route metadata/inline reader | Unchanged `6730bb8`, exact bundle identity, full maintained/direct-Java/corpus/ABI/wire evidence | Accepted without rebuilding unchanged code |
| Trino schema/table identity | Actual adapter and two-worker SQL at `3385429`: Unicode distinction, ASCII equivalence, absent names, genuine duplicate refusal | Accepted |
| Trino cancellation and lifecycle | Actual adapter/control fault regressions plus real PostgreSQL cancellation/deadline-before-unlock and pool recovery at `3385429` | Accepted within documented driver/caller boundaries |
| Doris non-owning facade lifecycle | Actual facade no-load/identity/failure-memoization tests at `4efd9bc` on Java25/17; lint and runtime-floor checks | Accepted |
| Other declared Trino/Doris scan behavior | Prior exact-artifact scoped integration results; changed-code review confirms scan implementations unchanged by repair | Evidence reused, not represented as fresh end-to-end testing of the repaired Doris artifact |

The affected-contract review found no additional required run for this implementation
closeout. No test suite was rerun merely for bookkeeping. The machine-readable
source/test reconciliation is `/tmp/opencode/r07-step4-closeout/evidence.json`.
This accepts the documented opt-in development scope, not all DuckLake 1.1 cases,
every old backlog item, publication, production/default-on use, or concurrent cleanup.
The repaired Doris artifact still has no new live FE/BE run; its last live artifact
and the narrower final-code lifecycle verification remain explicitly distinguished.

### Declared read boundary

The library's current contracts are [general reads](../../docs/READING-1.1.md),
[backend routes](../../docs/READ-BACKENDS.md), [PostgreSQL](../../docs/READ-POSTGRES-1.1.md)
and [existing MySQL](../../docs/READ-MYSQL-1.1.md). Ordinary/historical/CDC inline values
use qualified types and stable identities; descriptors are not file scans. Legacy
nested-schema APIs still refuse, while the explicit hierarchy companion is supported.
In the opt-in tier, empty ordinary projections through the legacy list API reject;
the new decoded page API supports identity-only empty projections. Non-opt-in
legacy behavior is addressed by C21 above. Unsupported cases remain explicit failures.

Doris materializes a bounded plan: 128 total pages, at most the configured
16-coordinate historical window, and existing physical/work/byte limits. It does
not stream an unlimited catalog. Trino's per-view totals are likewise not whole-query
or cross-node guarantees. Accepted scans assume physical objects remain retained;
concurrent physical cleanup/lease safety is not qualified by a metadata transaction.

## Repaired findings and bounded design disposition

### QG-01 — Repaired in `3385429`; introduced in `cd4afc1`

At evaluated `83cb24b`, `candidate/src/.../DucklakeCandidateAdapter.kt:86–91` called
driver cancellation within `ConcurrentHashMap.computeIfPresent`. An exception
aborts the remaining controls; a blocking call holds a lock required by same-query
registration/cleanup. The library explicitly documents that cancellation can fail
or block.

**Reproduced at source-slice level:** first failing control leaves the second
unattempted; same-query registration waits while the driver stub blocks.
The repair snapshots controls under the map lock, performs driver calls outside it,
attempts all captured controls despite JDBC/runtime exceptions, and preserves the
first failure with later distinct failures suppressed. Actual-adapter/control tests
now cover failing fan-out and registration/final cleanup while a driver blocks.
A blocking driver can still delay the caller; this does not promise cancellation
of later registrations or remote nodes.

### QG-02 — Repaired in `4efd9bc`; introduced in `3228a80`

At evaluated `3bad03f`, `read-tiers/r07/.../DuckLakeFinal11.kt:40–49` loaded the plan before
every proxy dispatch. The plan has no equals/hashCode implementation; close and
toString also force capture. Logging/closing an unused facade can issue SQL/S3 work
and fail.

**Reproduced at source-slice level:** equals/hashCode each capture then fail;
toString/close each capture, and close propagates a capture failure. The repair
dispatches identity-safe Object methods and no-op close before accessing the lazy
plan. Actual-facade regressions now prove zero capture calls, including a context
that would fail, usable lifecycle after failure, and shared statement memoization.

### QG-05 — Repaired in `3385429`; introduced in `83cb24b`

The evaluated `83cb24b` `getSchema`/`getTable` overrides at `DucklakeCandidateAdapter.kt:127–133`
used Kotlin `equals(ignoreCase=true)`. That equated Unicode long-s `ſ` with ASCII
`s`; DuckDB and the library hierarchy contract distinguish them. With only `ſ`
present, a request for `s` selects it; with both present, the new lookup reports a
false ambiguity. `DucklakeMetadata.getTableHandle` calls this lookup.

**Reproduced comparison and independent engine control:** the exact filter
expressions match `ſ` for request `s`; DuckDB 2.0 creates both tables and returns
their distinct values (1 and 2). The repair uses Guava's ASCII-only comparison;
actual-adapter and two-worker Trino SQL regressions now pass for schema/table names,
existing ASCII case rules, distinct Unicode names and absent names. Authored true
duplicates still reject. The October 26 batch above also fixes the older direct
library schema/table SQL LOWER behavior. The subsequent local-correctness batch
also fixes view lookup and C10 admission; those results are recorded separately above.

### QG-03/04 — Structural assessment and bounded disposition

The JDBC class concentrates admission/session orchestration and growing SQL mapping
responsibilities. However, projection, schema, metadata, hierarchy, backend decoding
and page-value logic already have separate components. There is no evidence from
this evaluation that reconstructing the project would improve correctness.

Consumer schema/budget validation is duplicated. Some boundary validation is
legitimate; byte/work totals have different units and scopes. QG-05 was a concrete
policy divergence and is repaired locally. No shared validation framework was needed
for the three defects. Trino's cursor-last inequality is weaker than Doris's per-row
ordering check, but finite budgets bound repetition and no producer ordering defect
was demonstrated here. Track stricter defensive validation as hardening.

Before stable publication, decide the 0.13 public API and retirement of temporary
0.12 candidate/released compilation bridges. That design decision is separate from
finishing the current limited reads; no permanent compatibility-shim project follows.

## Older defects: disposition, not disappearance

| Existing work | Current disposition |
|---|---|
| Trino timezone partition false negatives | Older correctness defect; fix `9913cd6`, with continuing final-round regression evidence. Do not reopen as unfinished 1.1 format work |
| C08 defaults / C21 empty projection | Legacy ordinary paths corrected and fully verified above; documented legacy CDC NULL-fill preserved, unsupported defaults/MySQL evolution explicit |
| C16 interval / quoted CAST decoding | Reconciled as already fixed in shared decoders; current legacy-path evidence and exact quoted-CAST regression pass. Upstream interval-flush persistence remains separate |
| C07 pruning | Fixed and fully verified above: float binary precision and end-of-day TIME keys. Conservative 1.1 min/max policy and bucket/historical-type work remain separate |
| C09 missing-table classification | Fixed and fully verified October 26: actual legacy DuckDB/Quack silent-empty reproductions now propagate, while legitimate absence and existing owned-session behavior remain covered |
| C10 namespaces / C18 partition payloads / C23 diagnostic URLs | Fixed and fully verified in the October 26 local-correctness batch above, alongside view-name matching and MySQL view/tag target quoting |
| C12 lineage | Reconciled to existing `0b6c31b` fix and closed with actual multi-output partitioned rewrite verification above |
| C13 legacy materialization boundary | Fixed and verified above: snapshotless mutation APIs retain linking signatures but explicitly reject in 0.13; existing snapshot-aware replacements and owned caller usage documented |
| C22 paths | Original deprecation/contract option verified and closed above; known production orphan callers already use separated namespaces. Bounded-maintenance adoption remains open |
| C24 initialization | CREATE/file-preflight and empty-snapshot correction fully verified and closed above, with boxed NULL preservation and five-route recovery controls |
| C11 nested removals / C19 sorted renames | Fixed and fully verified October 27; structural admission and versioned parsed sort-reference updates with explicit unsupported-expression refusals |
| C06 MySQL implicit-DDL atomicity / C17 inline DDL rendering | Safe-refusal fix verified October 27: affected operations reject before metadata mutation; full inline evolution still requires a preparation/publication protocol and qualified backend rendering |
| Remaining C/H/B/R/N/Q and UF backlog | Retained in [BACKLOG](CATALOG-REVIEW-COMPLETION-2026-10-28.md) and [upstream follow-ups](UPSTREAM-FOLLOWUPS-HISTORY-2026-10-28.md); not a completed whole-project audit |

Priority is severity and reachability, not age. A proven older wrong-row defect in
the declared read path must be fixed before that path is signed off, in a separately
attributable commit. This evaluation did not establish another such library defect.
Unknowns stay explicitly unknown rather than being called either fixed or blockers.

## Upstream verification linked to each feature

**Refreshed October 27, 06:34 UTC:** latest stable DuckDB/JDBC remain 2.0.0/2.0.0.0.
Fresh official `v2.0.0/linux_amd64` DuckLake/Quack/PG-scanner/MySQL-scanner downloads
are byte-identical to the qualified pins; the published DuckLake is still `417a9117f5`.
No installation or repin occurred.

Source **has** advanced: DuckLake main is `4c8981b8519013a58cdfc173b03eeb2b47d45497`
(three new mainline merges, 58 reachable commits since `947c8496`); DuckDB main is
`3a8bf4bdf408f680115acbe2dfc41afb00a7572f`. Useful new source work includes:

- [DuckLake #1850](https://github.com/duckdb/ducklake/pull/1850): PostgreSQL flush
  avoids waiting on readers when dropping superseded inline tables; imported foreign
  field IDs use name mapping; bucket type/spec handling and option retry fixes.
  These inform future integration tests, not physical-file lease guarantees.
- [DuckLake #1852](https://github.com/duckdb/ducklake/pull/1852): timestamp-bound
  caching, change feeds starting after the newest snapshot, commit-time snapshot
  timestamps and batched stats work. [#1849](https://github.com/duckdb/ducklake/pull/1849)
  updates PostgreSQL/SQLite scanner source pins, not our shipped artifacts.
- [DuckDB #27901](https://github.com/duckdb/duckdb/pull/27901): VARIANT traversal
  depth admission, relevant to future upstream-compatible value decoding.
  [#27255](https://github.com/duckdb/duckdb/pull/27255) warns on plain DuckDB attachment
  of DuckLake metadata; deliberate metadata attachment may need explicit `TYPE duckdb`
  on a future upgrade, not a switch to a migrating DuckLake attachment.

No published change clears R02/R04 or establishes a passing interval/MySQL producer
gate today. New source benefits therefore do not replace the next local correctness
batch or waive old failures. Full API deltas, selected patch inspection and download
hashes are retained under `/tmp/opencode/catalog-upstream-review-2026-10-27/`.

| Follow-up | Defective operation / known fix | Exact remaining verification |
|---|---|---|
| R02-U1 | DuckLake legacy STRUCT order read/delete/flush; [fork #96](https://github.com/PeterVanHolland/ducklake/pull/96), `ffec2d6`, via [upstream #1806](https://github.com/duckdb/ducklake/pull/1806) | `upstreamR02NativeTest`: `v10_inlined_struct_field_order.test` |
| R02-U2 | DuckLake accepts non-literal added-field default; [fork #93](https://github.com/PeterVanHolland/ducklake/pull/93), `ef2fc34`, via #1806 | Same task: `add_struct_field_non_literal_default.test` |
| R02-U3 | DuckLake crashes on same-transaction STRUCT edits; [fork #90](https://github.com/PeterVanHolland/ducklake/pull/90), `337f390`, verified ancestor of current main | Same task: `add_struct_alter_same_transaction.test` |
| R04-U1 | DuckLake migration retains unsafe bounds; fork #94 via #1806, merge `6ed5086` | `upstreamR04NativeTest`: `v10_retyped_bounds.test` |
| R03-U1 | DuckDB+DuckLake interval flush loses microseconds; exact faulty component/fix not established | `upstreamR03NativeTest`: `acknowledged inline interval components and microseconds survive native flush` |
| M01 | MySQL creation/upgrade/mutation/scanner/type failures; no blanket fixed-source claim | `:ducklake-catalog:final11MySqlNativeTest` (6) and `:ducklake-test-corpus-replay:upstreamMySqlNativeTest` (4); [per-case causes](../../docs/READ-MYSQL-1.1-BLOCKERS.md) |
| Doris producer path | DuckLake produces object-store-rejected long partition component; [#1838](https://github.com/duckdb/ducklake/pull/1838) includes hashing fix, exact case unverified on updated bytes | Existing strict long-path producer test (1 failure), independent from ordinary Doris reads |

R02–R04 tasks are in `:ducklake-test-corpus-replay`. Original failure counts remain
3/1/1, not rerun or waived here. R02/R04 status is **implementation complete for
declared scope; external verification awaiting fixed artifact**. R03/M01 have
unresolved external failures; do not label them all “waiting for shipping.”

When an artifact changes, first qualify identity and changed contracts, then run
these exact regressions. Quack ATTACH read-only handling, hashed paths, row groups
and statistics require the already-recorded upgrade assessment. Only reopen our
specific feature if that verification demonstrates a mismatch.

## What was evaluated, and limits of the conclusion

Reviewed the catalog R01–R07 change inventory (`505aeac..6730bb8`) and the JDBC
read/session/default/inline/count/paging diff; sampled current compatibility,
projection/default/evolution/schema, page decoding, metadata and retained-path
implementations. Reviewed the final Trino production delta `cd4afc1..83cb24b` and
Doris `3448107..3bad03f`, their current lifecycle methods and final reports. Read
the original backlog for reachability/prior-fix triage, not a full re-audit of it.

Fresh evidence: bundle SHA checks; retained consumer XML inspection; isolated
Kotlin source-slice probes with stub cancellation/capture; DuckDB identifier control.
Probes and source hashes: `/tmp/opencode/r07-evaluation/quality_probe.py`,
`sources.json`, `quality-probe.log`, `duckdb-identifiers.log`.
Upstream download hashes: `/tmp/opencode/r07-recovery-upstream-2026-10-25/availability-curl.json`.
The earlier review copies remain `/tmp/opencode/r07-quality-review-2026-10-25/`.

The evaluation itself made no runtime changes or broad test run. The subsequent
step 3 repairs and actual-component verification are recorded above. Neither is a
full concurrency/security audit or a production-incident claim. No new catalog
candidate or deployment was performed.

## Closed milestone and explicit follow-ons

The recovery evaluation, three-defect repair and limited-read implementation
closeout are complete. Follow-on tracking is also in place; it is not a claim that
the follow-on work has run. The original R02–R04/R07 broad acceptance checkboxes
remain open for the specific external/rollout requirements they still contain.

1. **Await fixed artifacts:** R02-U1/U2/U3 and R04-U1 above. Verify the exact
   published bytes and named tests when a relevant approved artifact is available.
2. **Unresolved producer/backend cases:** R03 interval persistence, M01 MySQL
   operations/types, and Doris's long partition-path producer test. Establish exact
   fix/qualification status per case, not another general reader implementation round.
3. **Older correctness:** retain the existing backlog, prioritize demonstrated
   reachable defects, and keep their commits attributable to their original scope.
4. **Broader R07 engine/release work:** Trino view/tag/comment/internal/global resource
   accounting and driver/worker cancellation; Doris inline/CDC/snapshot/cancel hooks;
   aggregate pools, retention/leases, genuine 0.4/fleet coverage and versioned API/
   packaging/publication decisions. These need their own bounded assignments.
5. **W01–W06:** all six write steps remain pending. A separately scoped write phase
    starts with writer contracts and applicable older writer defects, including a safe
    MySQL inline preparation/publication protocol to replace C06/C17 refusal, then
    metadata/mutation/maintenance/migration and write acceptance.

Reopen a completed read requirement only for a concrete failure in its declared
scope or an explicitly approved scope change. Reuse evidence for unchanged code;
do not restart qualification or expand APIs solely because a broader queue is open.

No automatic assignments or expanding API/refactor batches. A new major requirement
returns as an explicit scope decision. Preserve current work, original tests/goldens,
exclusions, immutable bundles and pins. No push/release/publication/deployment,
default-on reads, migration, 1.1 writes, physical cleanup, vendor workaround, new
skip or full connector corpus campaign is authorized. Respect session-owned
infrastructure and scoped Doris FE Maven-local artifacts; no global service stops.
