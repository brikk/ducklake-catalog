# 1.1 scope and code-quality checkpoint

**Current evaluation: October 25, 2026.** This is the current status and next-work
list. The original requirements remain in [R01–R07 / W01–W06](TODO-DUCKLAKE-1.1-SUPPORT.md).
Historical reports are evidence, not additional active queues.

## Decision in plain language

**Keep the implementation. Do not restart or rebuild the history commit by commit.**
The declared library read features are implemented and have recorded qualification.
Both latest connector adoption rounds are finished. Three concrete connector
quality findings require a narrow correction/verification batch. This evaluation
did not establish a need for another catalog API or a library rewrite.

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
| R07 read acceptance | Library qualification `58ef4d7`, later APIs through `6730bb8`; both connector adoption rounds finished | Three connector quality findings below; broad rollout/engine requirements remain separately open |
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

## Findings and exact next fix batch

### QG-01 — Trino cancellation under registry locks; introduced in `cd4afc1`

At final `83cb24b`, `candidate/src/.../DucklakeCandidateAdapter.kt:86–91` calls
driver cancellation within `ConcurrentHashMap.computeIfPresent`. An exception
aborts the remaining controls; a blocking call holds a lock required by same-query
registration/cleanup. The library explicitly documents that cancellation can fail
or block.

**Reproduced at source-slice level:** first failing control leaves the second
unattempted; same-query registration waits while the driver stub blocks.
Fix narrowly: snapshot/register state under the map lock, perform driver calls
outside it, attempt all captured controls, and preserve failure diagnostics.
Regression must exercise the actual adapter with failure and concurrent lifecycle
cases. This does not promise cancellation of late workers or remote nodes.

### QG-02 — Doris proxy lifecycle forces capture; introduced in `3228a80`

At final `3bad03f`, `read-tiers/r07/.../DuckLakeFinal11.kt:40–49` loads the plan before
every proxy dispatch. The plan has no equals/hashCode implementation; close and
toString also force capture. Logging/closing an unused facade can issue SQL/S3 work
and fail.

**Reproduced at source-slice level:** equals/hashCode each capture then fail;
toString/close each capture, and close propagates a capture failure. Fix narrowly:
identity-safe Object methods and no-load lifecycle dispatch before accessing the
lazy plan. The actual-facade regression must prove zero capture calls, including a
supplier that would fail, and preserve statement ownership/memoization.

### QG-05 — Trino Unicode name lookup changes identity; introduced in `83cb24b`

The new `getSchema`/`getTable` overrides at `DucklakeCandidateAdapter.kt:127–133`
use Kotlin `equals(ignoreCase=true)`. That equates Unicode long-s `ſ` with ASCII
`s`; DuckDB and the library hierarchy contract distinguish them. With only `ſ`
present, a request for `s` selects it; with both present, the new lookup reports a
false ambiguity. `DucklakeMetadata.getTableHandle` calls this lookup.

**Reproduced comparison and independent engine control:** the exact filter
expressions match `ſ` for request `s`; DuckDB 2.0 creates both tables and returns
their distinct values (1 and 2). This is not yet an executed Trino SQL regression.
Add actual-adapter/SQL tests for schema and table names, existing ASCII case rules,
distinct Unicode names and real duplicate rejection; use the established ASCII-only
identifier folding rather than a new name policy. Prior library SQL LOWER behavior
is a separate older compatibility concern, not proof the new comparator is correct.

### QG-03/04 — Structural assessment and bounded disposition

The JDBC class concentrates admission/session orchestration and growing SQL mapping
responsibilities. However, projection, schema, metadata, hierarchy, backend decoding
and page-value logic already have separate components. There is no evidence from
this evaluation that reconstructing the project would improve correctness.

Consumer schema/budget validation is duplicated. Some boundary validation is
legitimate; byte/work totals have different units and scopes. QG-05 is a concrete
policy divergence to repair now. Do not create a shared validation framework to fix
three local defects. Trino's cursor-last inequality is weaker than Doris's per-row
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

No connector runtime fix, new candidate, broad test run, deployment or full
concurrency/security audit was performed. Source-slice reproductions establish
local behavior, not a production incident or a substitute for actual-adapter tests.

## Future sequence and finish line

1. **Next bounded batch:** actual-component regressions and narrow fixes for
   QG-05 (name identity), QG-01 (cancellation), QG-02 (proxy lifecycle). Separate
   attributable commits in their owning repositories. Reuse catalog `6730bb8`.
2. **Final verification:** affected connector tests, required lint/runtime/compatibility
   checks and the affected guarded SQL lanes on final code. Reuse unchanged library
   evidence. No catalog rebuild unless a demonstrated library defect requires it.
3. **Close the limited-read implementation milestone:** existing declared contracts
   plus the corrected connector boundaries pass; update this checkpoint with commits.
   Pending upstream tests and unsupported cases remain separately named. Broader R07
   rollout is not automatically approved.
4. **Subsequent decisions:** upstream artifact verification when available; older
   backlog by impact; engine expansion/retention/fleet and API-publication design;
   then separately scoped W01–W06 write work. All six write steps remain pending.

No automatic assignments or expanding API/refactor batches. A new major requirement
returns as an explicit scope decision. Preserve current work, original tests/goldens,
exclusions, immutable bundles and pins. No push/release/publication/deployment,
default-on reads, migration, 1.1 writes, physical cleanup, vendor workaround, new
skip or full connector corpus campaign is authorized. Respect session-owned
infrastructure and scoped Doris FE Maven-local artifacts; no global service stops.
