# Remaining verification and rollout checkpoint

Current scope and unfinished work only. Completed catalog corrections and the
limited-read implementation/connector closeout are preserved in
[the archived checkpoint](archive/R07-SCOPE-AND-QUALITY-CHECKPOINT-2026-10-28.md).
Do not restart completed implementation because broader acceptance remains open.

Active queues: [review backlog](BACKLOG.md), [upstream follow-ups](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md)
and [1.1 acceptance/writes](TODO-DUCKLAKE-1.1-SUPPORT.md).

The [October 28 triage](archive/TRIAGE-2026-10-28.md) separates reproduced defects,
source-confirmed delivery gaps, deferred capabilities, qualification and optional
hardening. Missing engine/production evidence is not itself proof of a bug.

The latest repair batch is complete and fully verified; exact commits, scope and
checks are in [the archived evidence](archive/FIX-PRIORITY-FINDINGS-2026-10-28.md).
The active defect queue excludes completed repairs.

## Integration boundary

The accepted limited-read connector bundle remains catalog `6730bb8`, Trino
`3385429` and Doris `4efd9bc`. Subsequent library fixes have passed qualification,
but still need coordinated consumer adoption; do not claim those connector artifacts
were rebuilt or rerun on the newer library. Latest verified code revisions are in
the repair evidence above.
Exact accepted artifact hashes and verification scopes are in the archived report.

The October28 source audit saw Trino `fca7277` and Doris `9b44463`. Their
483/484 and API24 released-lane checks did not adopt the newer catalog candidate.
Doris's successful matching API24 FE/BE/restart qualification used catalog 0.12.0 /
JDBC1.5.6, not the R07 candidate. Preserve that success without treating it as new
candidate-lane live-engine evidence.

The [general read contract](../docs/READING-1.1.md),
[backend routes](../docs/READ-BACKENDS.md), [PostgreSQL tier](../docs/READ-POSTGRES-1.1.md)
and [existing-MySQL tier](../docs/READ-MYSQL-1.1.md) define the supported boundaries.
Metadata/inline descriptors are not physical file scans. Legacy nested-schema APIs
still refuse; the explicit hierarchy companion is supported. Empty ordinary
projections reject through the opt-in legacy list API, while decoded pages support
identity-only projections and non-opt-in ordinary reads preserve cardinality.

Doris planning remains bounded to 128 total pages, at most its configured
16-coordinate historical window, and existing physical/work/byte limits. Trino's
per-view totals are not whole-query or cross-node guarantees. Accepted scans assume
physical objects remain retained; a metadata transaction is not a physical-file lease.

## Remaining consumer and maintenance work

- **Bounded maintenance:** both orphan implementations still fetch unbounded
  catalog-wide references; Doris queued cleanup also uses an unbounded listing.
  Trino queued cleanup already uses bounded candidate/reference APIs. Scope and
  qualify the remaining contract/adoption with SQL/path/aggregate traversal budgets
  before deletion. An age-filtered scheduled batch is not all queued ownership;
  young/undated entries must not disappear from an orphan known set. See
  [namespace and migration limits](../docs/FILE-REFERENCE-NAMESPACES.md).
- **Trino:** the candidate source/report confirms unpaged view/tag/comment paths
  and incomplete internal/shared-global accounting; these are capability gaps in
  the broader requested resource guarantee, not just missing test runs or observed
  OOMs. Whole-query/cross-node budgets and driver/worker cancellation need defined
  scope plus qualification beyond the already-accepted per-view controls.
- **Doris:** the candidate still rejects unsupported inline/mixed-history/CDC
  cases and lacks qualified production inline/cancellation integration. These are
  explicit feature/acceptance boundaries; the newer API24 released-lane report
  does not establish candidate hook adoption or prove API24 itself lacks those hooks.
- **Shared acceptance:** aggregate pools, retention/leases, genuine 0.4/fleet/route
  coverage, versioned API/packaging/publication and enablement decisions. Decide
  the 0.13 public API and retirement of temporary 0.12 candidate/released compilation
  bridges before stable publication; do not create a permanent compatibility-shim
  project by default. Stricter defensive page/cursor validation remains hardening.
- **Consumer artifact adoption:** qualify affected behavior on exact new dependency
  artifacts. Preserve COMMIT outcome classifications and uncertain file ownership.
  Doris's repaired artifact still has no new live FE/BE run; do not relabel the
  earlier exact-artifact live result as rerun evidence.
- **Writer safety boundaries:** full MySQL inline evolution requires coordinated
  preparation/publication and qualified type/identifier rendering to replace the
  current refusal. Shared writer watermarks/history reclamation need a safe design.
  The [materialization contract](../docs/MATERIALIZATION-SNAPSHOTS.md) continues to
  define planning-snapshot, complete-payload and embedded-lineage obligations.

Each capability/qualification item needs its own bounded assignment. Remaining
defects and delivery controls have current evidence in [BACKLOG.md](BACKLOG.md);
resolved and optional items are classified in the archive rather than another bug list.

## External verification linked to each feature

**Checked October 28, 11:52 UTC:** latest stable DuckDB/JDBC remain 2.0.0/2.0.0.0.
Fresh official `v2.0.0/linux_amd64` DuckLake/Quack/PostgreSQL/MySQL extensions are
byte-identical to qualified pins; DuckLake remains `417a9117f5`. Main advanced to
`96c3a21f`, including snapshot tags/new `2.0-dev1` source work, but none of that
establishes a passing gate on published bytes. No install or repin occurred.
Evidence: `/tmp/opencode/catalog-upstream-review-2026-10-28/`.

| Follow-up | Defective operation / known fix | Exact remaining verification |
|---|---|---|
| R02-U1 | DuckLake legacy STRUCT order read/delete/flush; [fork #96](https://github.com/PeterVanHolland/ducklake/pull/96), `ffec2d6`, via [upstream #1806](https://github.com/duckdb/ducklake/pull/1806) | `upstreamR02NativeTest`: `v10_inlined_struct_field_order.test` |
| R02-U2 | DuckLake accepts non-literal added-field default; [fork #93](https://github.com/PeterVanHolland/ducklake/pull/93), `ef2fc34`, via #1806 | Same task: `add_struct_field_non_literal_default.test` |
| R02-U3 | DuckLake crashes on same-transaction STRUCT edits; [fork #90](https://github.com/PeterVanHolland/ducklake/pull/90), `337f390`, verified ancestor of main | Same task: `add_struct_alter_same_transaction.test` |
| R04-U1 | DuckLake migration retains unsafe bounds; fork #94 via #1806, merge `6ed5086` | `upstreamR04NativeTest`: `v10_retyped_bounds.test` |
| R03-U1 | DuckDB+DuckLake interval flush loses microseconds; exact faulty component/fix not established | `upstreamR03NativeTest`: `acknowledged inline interval components and microseconds survive native flush` |
| M01 | MySQL creation/upgrade/mutation/scanner/type failures; no blanket fixed-source claim | `:ducklake-catalog:final11MySqlNativeTest` (6) and `:ducklake-test-corpus-replay:upstreamMySqlNativeTest` (4); [per-case causes](../docs/READ-MYSQL-1.1-BLOCKERS.md) |
| Doris producer path | DuckLake produces object-store-rejected long partition component; [#1838](https://github.com/duckdb/ducklake/pull/1838) includes hashing fix, exact case unverified on updated bytes | Existing strict long-path producer test (1 failure), independent from ordinary Doris reads |

R02–R04 tasks are in `:ducklake-test-corpus-replay`. Original failure counts remain
3/1/1, not rerun or waived here. R02/R04 reader implementation is complete for its
declared scope; external verification awaits fixed artifacts. R03/M01 are unresolved
producer/backend failures, not established as merely waiting for shipping.

On an approved artifact change, qualify identity and changed contracts before
running these exact regressions. Assess Quack metadata ATTACH read-only behavior,
hashed partition paths, row groups/statistics, metadata attachment `TYPE duckdb`
and the recorded October 24/27/28 source deltas. Do not repin, build source, rewrite
goldens, weaken representations or restart reader implementation automatically.

## Deferred write phase

W01–W06 remain pending in the [1.1 TODO](TODO-DUCKLAKE-1.1-SUPPORT.md): versioned
writer contracts, version-aware metadata, mutation lifecycle, maintenance and
transaction outcomes, migration/restore, then interoperability and enablement.
All 1.1 writes stay guarded; read opens never migrate. Native MySQL creation,
upgrade, mutation, scanner and full-type acceptance remain separately deferred;
the qualified existing-catalog JDBC read subset does not depend on claiming them.

## Constraints

Reopen a completed read requirement only for a concrete failure in its declared
scope or an explicitly approved scope change. Reuse evidence for unchanged code;
do not restart qualification or expand APIs solely because a broader queue is open.

No automatic assignments or expanding API/refactor batches. A new major requirement
returns as an explicit scope decision. Preserve current work, original tests/goldens,
exclusions, immutable bundles and pins. Push/release/publication/deployment,
default-on reads, migration, 1.1 writes, physical cleanup, vendor workarounds, new
skips and full connector corpus campaigns require explicit authorization. Respect
session-owned infrastructure and scoped Doris FE Maven-local artifacts; no global
service stops.
