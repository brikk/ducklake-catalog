# 1.1 scope and code-quality checkpoint

October25,2026. **This is the current coordination checkpoint**, not another
implementation plan or a new claim that all R07 work is complete. Earlier dated
reports remain evidence. New assignments/candidates are paused while the two
current rounds finish and their final diffs are reviewed. No code was changed by
this review; active connector checkouts were not edited or formatted.

## Where we actually are

| Component | Last committed, verified checkpoint | Current6730bb8 round |
|---|---|---|
| Catalog | `6730bb8`:985 maintained+86 driver-only tests,17,602 records; lint/ABI/wire/Java17 pass | Candidate immutable; no new source round assigned |
| Trino | `cd4afc1`, candidate10f81f9:128 candidate/265 released tests, lint,81 records; actual PostgreSQL engine cancellation/deadline before unlock | Working changes uncommitted. Latest returned message is **interim**: qualification reported running, no final result/commit |
| Doris | `3448107`, candidate10f81f9:16 live/14 targeted; candidate262/236 and released253/227 Java25/17 lanes, lint/ABI/assembly; default ZIP restored | Working changes uncommitted; no final6730bb8 result yet |

These are different candidate bytes and qualification scopes, not additive unique
test totals. Do not call the latest connector round done or infer integration from
the catalog test count. Original supported scopes remain: Trino's explicit guarded
read-only trials, and Doris's default-off PostgreSQL/S3 native-file tier. Unsupported
inline/types/historical filtering still refuse; no blanket1.1 or production sign-off.

## Separate origin from release impact

| Track | What belongs here | Completion meaning |
|---|---|---|
| **1.1 interoperability** | Changed inline layout/schema/default/type contracts, format admission, required descriptor/row/delete semantics and actual connector scans for the declared scope | Exact candidate integration passes and no known wrong-row case in the claimed tier; unsupported cases explicit |
| **Pre-existing correctness** | TIMESTAMPTZ partition false negatives affecting1.0 too; other older identity/count/schema defects where proven | Independently tracked/regression-tested fixes, not described as new1.1 format changes |
| **General hardening/quality** | SQL paging, traversal/memory budgets, cancellation/deadlines, lifecycle/resource handling and consolidation | Its own reviewed behavior/limits; not an endlessly expanding definition of format compatibility |
| **Engine/operations/rollout** | Missing Doris inline/CDC/snapshot/cancel interfaces, retention/leases, packaging/fleets/aggregate pools and artifact upgrades | Explicit separate scope/owner decisions, not automatically a veto on every already-qualified read subset |

Some changes span tracks: owned sessions are necessary for our1.1 read contract,
while stronger whole-query cancellation/resource guarantees are broader work. Old
deletion/lineage behavior can also affect1.1. An old or general defect **can still
block shipping if reachable/serious**; classification is not a waiver. Keep native
R02/R03/R04/M01 and the long-path producer failure visible against their affected
operations, without turning them into an undifferentiated “1.1 doesn't work.”

The historical R07 umbrella remains open; this checkpoint does not silently tick
or remove its original constraints. The agreed limited-read milestone, quality
gate and broader engine/production work must now be reported separately.

## Bounded first-pass quality review

Reviewed the highest-risk reader/adapter/lifecycle boundaries from committed
snapshots, not every line of every changed file. Baselines: catalog4dc319c→7e73bae,
Trino20af905→cd4afc1, Doriscf09dcb→3448107. This window covers R07/consumer trials,
**not the whole earlier R01–R06 implementation**. Active uncommitted changes are
not reviewed/approved as finished. Copies are retained under
`/tmp/opencode/r07-quality-review-2026-10-25/`.

Inventory:3,258 added production lines across57 files,4,656 test lines and3,524
documentation lines, excluding small build/harness changes. Tests/docs explain
much activity, but the production change is still substantial; volume or green
tests do not establish design quality.

### QG-01 — Trino cancellation fan-out / registry locking

At `cd4afc1`, `candidate/src/dev/brikk/ducklake/trino/plugin/DucklakeCandidateAdapter.kt:77-83`
calls each driver's `cancel()` **inside ConcurrentHashMap.computeIfPresent**.
One exception aborts the remaining fan-out; a blocking driver holds the registry
bin lock while registrations/final cleanup also need that map. The catalog control
explicitly allows driver cancel to block/fail, so this is not an impossible input.

**Source-confirmed failure behavior, not a reproduced engine incident.** Recheck
the final6730bb8 diff (the same pattern was still visible in the live working file
at review time). Require a focused multi-control/failing-cancel test, attempt all
registered controls, and keep driver calls outside registry locks. Do not silently
claim that also solves late worker/task-close or cross-node cancellation.

### QG-02 — Doris proxy Object/lifecycle methods trigger capture

At `3448107`, `read-tiers/r07/dev/brikk/ducklake/doris/plugin/DuckLakeFinal11.kt:40-49`
loads the full plan before dispatching **every** proxy call. `equals`/`hashCode`
have no plan-dispatch implementation and can throw; `toString` and the nominally
no-op `close` can trigger SQL/S3 capture merely to log or close an unused facade.
This conflicts with the intended deferred/no-SQL construction/lifecycle boundary.

**Source-confirmed proxy behavior, no production incident asserted.** The file was
unchanged in the active diff at review time. Recheck the final round, then require
identity-safe Object methods and lifecycle methods that do not load the plan,
with tests that prove the capture supplier is not called. Do not patch active
verification underneath the running session.

### QG-03 — Catalog API and responsibility growth

`JdbcDucklakeCatalog.kt` was already5,845 lines at4dc319c; it is now6,280, with12
additional concrete public entry points and further SQL/mapping helpers. This did
not create the entire large class, but it worsens its concentration of query,
layout, transaction, paging, projection and cancellation responsibilities. I own
that choice. Review the public read boundary and extraction of cohesive query
components before stable publication; do not begin a blind rewrite during this gate.

### QG-04 — Duplicated schema/budget policy and temporary compatibility machinery

Trino reconstructs/validates column trees in `DucklakeCandidateAdapter.kt:115-166`;
the catalog and Doris have their own schema validation. This is partly legitimate
boundary validation, but policies/units/limits can drift. Byte budgets also have
different scopes/units (encoded cell, decoded page, outer view, physical transfer).
Name them clearly and test contract agreement rather than treating them as one
global guarantee. Trino's minimum last-cursor inequality check is weaker than
Doris's strict per-row order check; recheck the final implementation.

The typed `candidate/released` and `read-tiers` split is reasonable **temporary
trial isolation**, but keeping0.12 compilation must not force permanent bridges or
become an absolute design veto. Decide the versioned0.13 API/retirement boundary
deliberately; no new reflection/internal-access layer should grow by accident.

## What is good / what this review does not prove

Typed public adapters, explicit default-off/write barriers, modular Doris physical
readers, same-view budgets and independently expected SQL/negative tests are good
foundations. No vendor/FE source patch or shared-storage staging workaround was
introduced in these reviewed commits. We should retain that work, not reset it.

This is a first-pass source review, **not a full concurrency/security audit or new
test run**. Healthy-driver SQL tests don't cover QG-01's failure case; passing scan
tests don't cover QG-02's Object contract. Findings must be verified against final
diffs and fixed narrowly where still present. No current code is certified “clean”
just because a prior suite passed, and no unknown working edit is discarded.

## Coordination decision

Let the existing qualification runs finish; require actual final commits/evidence,
not interim completion messages. Then review those final diffs and address introduced
defects as a bounded quality batch. **No more feature/candidate expansion by default.**
Keep separate backlogs for older correctness/hardening and engine/rollout work;
still fix them, but stop attributing all of them to1.1. Use one reviewed candidate
and one coherent integration checkpoint rather than another serial feature handoff.
No release/push/deployment/default-on/migration/1.1-write/cleanup approval follows.
