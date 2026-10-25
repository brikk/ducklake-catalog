# 1.1 scope and code-quality checkpoint

**Current status: October 25, 2026; limited-read implementation milestone COMPLETE.** This is the current status and next-work
list. The original requirements remain in [R01–R07 / W01–W06](TODO-DUCKLAKE-1.1-SUPPORT.md).
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

The closeout checked source boundaries, retained XML and successful final build logs
against the committed revisions. There have been **only documentation changes** in
the catalog since `6730bb8`. Trino's production delta since its adopted `83cb24b` is
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

The library's current contracts are [general reads](../docs/READING-1.1.md),
[backend routes](../docs/READ-BACKENDS.md), [PostgreSQL](../docs/READ-POSTGRES-1.1.md)
and [existing MySQL](../docs/READ-MYSQL-1.1.md). Ordinary/historical/CDC inline values
use qualified types and stable identities; descriptors are not file scans. Legacy
nested-schema APIs still refuse, while the explicit hierarchy companion is supported.
Legacy empty ordinary projections reject; the new decoded page API supports
identity-only empty projections. Unsupported cases must remain explicit failures.

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
duplicates still reject. Prior library SQL LOWER behavior remains a separate older
compatibility concern, not a reason to change this corrected identifier contract.

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
| Catalog C08 defaults, C16 interval parsing, C21 empty projection | Scoped 1.1 behavior addressed by R01/R03/page APIs; this does not close every legacy-path part of those older findings |
| C07 pruning / C09 missing-table classification | Older candidates, not newly verified failures. Conservative 1.1 min/max pruning avoids that optimization; message-based missing-table fallback still exists. Dedicated current-code reachability/reproduction remains older-backlog work |
| C12 lineage / C18 partition ownership / C22 paths | Relevant scoped R07 lineage and R05 ownership evidence exists; broader legacy/mutation findings are not silently closed |
| C06 MySQL implicit-DDL atomicity, C10/C11/C17/C19 mutation rules | Relevant prerequisites for future write work, not a reason to reimplement approved read-only routes |
| Remaining C/H/B/R/N/Q and UF backlog | Retained in [BACKLOG](BACKLOG.md) and [upstream follow-ups](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md); not a completed whole-project audit |

Priority is severity and reachability, not age. A proven older wrong-row defect in
the declared read path must be fixed before that path is signed off, in a separately
attributable commit. This evaluation did not establish another such library defect.
Unknowns stay explicitly unknown rather than being called either fixed or blockers.

## Upstream verification linked to each feature

Availability checked October 25: DuckLake main remains
`947c8496c9becc53ed487b859384ce65e6edd2e3`; latest DuckDB/JDBC releases remain
2.0.0/2.0.0.0. Fresh official `v2.0.0/linux_amd64` DuckLake/Quack/PG-scanner/MySQL-scanner
downloads match the October 24 hashes. DuckLake is still `417a9117f5`; a source merge
has not changed these downloaded bytes. No installation or repin occurred.

| Follow-up | Defective operation / known fix | Exact remaining verification |
|---|---|---|
| R02-U1 | DuckLake legacy STRUCT order read/delete/flush; [fork #96](https://github.com/PeterVanHolland/ducklake/pull/96), `ffec2d6`, via [upstream #1806](https://github.com/duckdb/ducklake/pull/1806) | `upstreamR02NativeTest`: `v10_inlined_struct_field_order.test` |
| R02-U2 | DuckLake accepts non-literal added-field default; [fork #93](https://github.com/PeterVanHolland/ducklake/pull/93), `ef2fc34`, via #1806 | Same task: `add_struct_field_non_literal_default.test` |
| R02-U3 | DuckLake crashes on same-transaction STRUCT edits; [fork #90](https://github.com/PeterVanHolland/ducklake/pull/90), `337f390`, verified ancestor of current main | Same task: `add_struct_alter_same_transaction.test` |
| R04-U1 | DuckLake migration retains unsafe bounds; fork #94 via #1806, merge `6ed5086` | `upstreamR04NativeTest`: `v10_retyped_bounds.test` |
| R03-U1 | DuckDB+DuckLake interval flush loses microseconds; exact faulty component/fix not established | `upstreamR03NativeTest`: `acknowledged inline interval components and microseconds survive native flush` |
| M01 | MySQL creation/upgrade/mutation/scanner/type failures; no blanket fixed-source claim | `:ducklake-catalog:final11MySqlNativeTest` (6) and `:ducklake-test-corpus-replay:upstreamMySqlNativeTest` (4); [per-case causes](../docs/READ-MYSQL-1.1-BLOCKERS.md) |
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
   starts with writer contracts and applicable older writer defects (including C06
   MySQL atomicity), then metadata/mutation/maintenance/migration and write acceptance.

Reopen a completed read requirement only for a concrete failure in its declared
scope or an explicitly approved scope change. Reuse evidence for unchanged code;
do not restart qualification or expand APIs solely because a broader queue is open.

No automatic assignments or expanding API/refactor batches. A new major requirement
returns as an explicit scope decision. Preserve current work, original tests/goldens,
exclusions, immutable bundles and pins. No push/release/publication/deployment,
default-on reads, migration, 1.1 writes, physical cleanup, vendor workaround, new
skip or full connector corpus campaign is authorized. Respect session-owned
infrastructure and scoped Doris FE Maven-local artifacts; no global service stops.
