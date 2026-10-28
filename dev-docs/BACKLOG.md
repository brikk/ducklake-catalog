# Review backlog

Remaining work only. Reproduce historical findings against current code before
choosing a fix; they are not all confirmed defects at HEAD. Original evidence and
closure criteria remain in the [September review](archive/REVIEW-2026-09-05.md).
Completed work and verification reports live in [the archive](archive/README.md).

For native/RC follow-ups use the [upstream TODO](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md);
for 1.1 acceptance and writes use the [1.1 TODO](TODO-DUCKLAKE-1.1-SUPPORT.md).
Do not edit sibling connectors without a separately scoped assignment.

## Maintenance and consumer follow-ups

- **MySQL inline evolution:** supporting column/field changes on tables with
  registered inline storage requires coordinated physical-table preparation and
  publication, plus qualified backend type/identifier rendering. Keep the existing
  safe refusal until that writer work is implemented and qualified.
- **Native correctness / RC:** existing strict native failures remain gates;
  UF-05–09 need investigation. Keep 1.1 writes disabled.
- **Consumer adoption:** coordinate and qualify newer catalog fixes in Trino and
  Doris. Preserve COMMIT classifications and uncertain output ownership; verify
  affected cleanup/error mapping and CDC/pruning behavior for the adopted artifact.
- **Bounded maintenance:** both connector orphan implementations still use
  unbounded catalog-wide references; Doris queued cleanup also uses an unbounded
  listing. Trino queued cleanup already uses bounded candidates/reference pages.
  Design and qualify the remaining adoption with SQL/path and aggregate traversal
  budgets before deletion. Age-filtered candidate batches omit young/undated queued
  ownership and are not a complete orphan known set. See
  [namespace and migration limits](../docs/FILE-REFERENCE-NAMESPACES.md).
- **Retention and rewrite boundaries:** shared writer watermarks/history reclamation
  still need a safe design. Ordinary rewrites' concurrent native inline-delete
  limitation remains documented in the
  [materialization contract](../docs/MATERIALIZATION-SNAPSHOTS.md).

## Harness, parser and delivery

| IDs | Scope |
|---|---|
| H01 | Oracle temporary-directory ownership and symlink-safe teardown |
| H02–H03 | Typed/shape-aware goldens and collision-free nullable mirror comparison |
| H04–H05 | Preserve failures through timeout; terminate/isolate workers; expose actual engine coverage |
| H06–H08 | Parse/expansion budgets, successful attachment state and oracle-versus-adapter error boundaries |
| H09–H10 | Explicit JDBC conversion failures and nanosecond timestamp fidelity |
| H11–H14 | Conditional parity, truncated loops, iterator shadowing and single-execution DML RETURNING |
| H15–H16 | Java 17 runtime-independent float formatting and raw/substituted golden alternatives |
| B01 | Release-aware Java API/runtime checks; classfile floors and selected frozen descriptors are not complete compatibility proof |
| B02–B04 | Verify exact publication SHA, enforce version/tag agreement and isolate nightly cancellation |

The [replay](archive/REVIEW-2026-09-05.md#replay-findings) and
[build](archive/REVIEW-2026-09-05.md#build-findings) sections retain each individual
H/B ID. Check what later code already addresses before implementing a candidate.

## Residual risks / smaller work

Preserve the original **R01–R12** (historical types/counts, transport/isolation,
payload completeness, CDC identity, scalability, cleanup recovery/lifetime and
replay isolation), **N01–N27** and **Q01–Q10** IDs in the archived
[residual risks](archive/REVIEW-2026-09-05.md#residual-risks),
[remaining backlog](archive/REVIEW-2026-09-05.md#remaining-backlog) and
[quality assessment](archive/REVIEW-2026-09-05.md#quality-assessment).
These remaining areas have not been re-audited or closed by the catalog cleanup.
