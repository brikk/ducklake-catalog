# Review backlog

This is an index of remaining review work, **not a new correctness audit**. Original
evidence, severity and closure criteria are preserved in the
[September review](archive/REVIEW-2026-09-05.md); its line numbers are historical.
Reproduce against current code before choosing a fix. A documentation move does
not close a finding, and green corpus totals alone do not establish conformance.

For authorized upstream work and the next RC checkpoint, use the separate
[UF TODO](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md). Do not edit sibling connectors.

## Confirmed follow-up priorities

- **MySQL inline evolution:** C06/C17 corruption prevention is complete through
  safe refusal (below). Supporting these operations later requires coordinated
  physical-table preparation/publication and qualified backend type/identifier
  rendering; it remains part of the separately scoped writer work.
- **Native correctness / RC:** UF-01–04 investigations are complete but native
  failures remain gates; UF-05–09 are not implemented. Keep 1.1 writes disabled.
- **Consumer adoption:** preserve COMMIT classifications and retain uncertain output;
  connector agents must verify cleanup/error mapping and their CDC/pruning paths.

## Catalog candidates still requiring current-code verification

| ID | Work / closure criterion |
|---|---|
| C12 | Per-output partial-rewrite origins or required embedded IDs; no overlapping fallback row-ID ranges. |
| C13 | Migrate callers off weak legacy materialization boundaries without silently removing shipped APIs. |
| C22 | Legacy reference API path namespaces and downstream adoption of bounded replacements. |
| C24 | Consistent typed initialization errors for CREATE and file writes. |

Use the corresponding C heading in the archived review for the original reproduction
and expected external behavior; these candidates are not all re-proven at HEAD.

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
H/B ID. Recheck what later code already addresses before implementing a candidate.

## Residual risks / smaller work

Preserve the original **R01–R12** (historical types/counts, transport/isolation,
payload completeness, CDC identity, scalability, cleanup recovery/lifetime and
replay isolation), **N01–N27** and **Q01–Q10** IDs in the archived
[residual risks](archive/REVIEW-2026-09-05.md#residual-risks),
[remaining backlog](archive/REVIEW-2026-09-05.md#remaining-backlog) and
[quality assessment](archive/REVIEW-2026-09-05.md#quality-assessment).
R02 conflict-history retention has a verified scoped fix, but safe shared writer
watermark/reclamation remains a design boundary. Do not silently mark the rest fixed.

## Completed / superseded work

- **C07 — logical-type pruning:** FLOAT32/FLOAT64 bounds compare at declared binary
  precision, including equivalent decimal spellings and signed zero. TIME/TIME_NS
  `24:00:00` has an end-of-day key distinct from midnight; malformed clocks and
  non-finite/overflowing floats remain unknown. Shared bound merging and numeric
  swapped-bound checks use the corrected comparator. Five-route API tests agree
  with DuckDB logical comparisons and retain selectivity; native local/PostgreSQL
  Parquet cases retain matching rows. Full 1,102 maintained + 86 direct-Java /
  17,602-record checks pass. UF-04 bucket behavior and broader historical-type
  qualifications remain separate.
  [Scope and evidence](R07-SCOPE-AND-QUALITY-CHECKPOINT.md#c07-logical-type-pruning-correction).
- **C08 / C21 — legacy ordinary-read corrections:** missing root IDs now use the
  existing checked initial-default projection on DuckDB/Quack/PostgreSQL, with fresh
  target metadata and preserved stored NULLs. MySQL missing-column evolution and
  unqualified defaults reject explicitly. Non-opt-in empty ordinary projections
  return one empty value list per live row. Legacy CDC retains its documented
  NULL-fill contract; the 1.1 tier retains its separate default/page behavior.
  Full 1,087 maintained + 86 direct-Java / 17,602-record checks pass.
  [Exact scope and evidence](R07-SCOPE-AND-QUALITY-CHECKPOINT.md#legacy-read-reconciliation-and-c08c21-follow-up).
- **C16 — reconciled as already fixed:** shared interval decoding (`fa9a04d`, later
  `66caa29`) handles large/signed clocks and explicit millisecond units, rejecting
  malformed/overflow values. Quoted CAST-string provenance was fixed in `1e01447`.
  The current legacy-path probe and maintained tests confirm those fixes; this
  batch adds an exact legacy quoted-CAST regression. The separate upstream R03
  interval-flush persistence failure remains open.
- **C06 / C17 — October 27:** all column/field schema mutations on MySQL tables
  with registered inline storage reject before metadata writes, with fresh checks
  on retry. Physical registration/rendering also reject MySQL rather than running
  implicitly committing or DuckDB-dialect DDL. Empty registrations are guarded;
  metadata-only rollback/retry and existing-field no-ops remain supported. Actual
  MySQL regressions cover default/ANSI_QUOTES modes, injected failure, competing
  snapshots, new inline registrations and server-side mutation traps. Full 1,069
  maintained + 86 direct-Java / 17,602-record checks pass. This closes the original
  safe-refusal option; full MySQL inline evolution is still deferred.
  [Scope and evidence](R07-SCOPE-AND-QUALITY-CHECKPOINT.md#october-27-correctness-follow-up--c06-and-c17).
- **C11 / C19 — October 27:** nested removals preserve valid type trees and cannot
  bypass root dependency checks; supported DuckDB sort expressions are rewritten by
  parsed column reference and versioned on rename. Actual DuckDB INSERT and historical
  reads pass after library renames. Unknown dialects/syntax and unsafe nested sorted
  changes refuse before mutation. Full 1,059 maintained + 86 direct-Java /
  17,602-record checks pass. [Exact scope and evidence](R07-SCOPE-AND-QUALITY-CHECKPOINT.md#october-27-correctness-follow-up--c11-and-c19).
- **C10 / C18 / C23 and view-name matching — October 26:** transaction-local
  schema/relation/sibling-name admission, partition-spec ownership and exact supplied
  keys, and redacted catalog-owned URL diagnostics. Five-route API regressions,
  concurrency controls, view history/tags and the full 1,030 maintained + 86
  direct-Java / 17,602-record run pass. MySQL view/tag metadata targets are explicitly
  quoted. [Scope, limits and evidence](R07-SCOPE-AND-QUALITY-CHECKPOINT.md#october-26-local-correctness-views-namespaces-partitions-and-diagnostics).
  This repairs existing 0.4/1.0 mutations and 1.1 view reads; it does not enable 1.1
  writes. C06/C17 and C11/C19 were addressed subsequently above. Driver/pool logs
  are outside catalog-owned URL rendering.
- **C09 and direct schema/table identifier reads — October 26:** precise expected-table
  error classification replaces the legacy silent-empty fallback; ASCII-only/binary
  name matching preserves distinct Unicode names on all five read routes. Actual-API
  regressions and the full 1,006 maintained + 86 direct-Java / 17,602-record run pass.
  [Evidence and consumer scope](R07-SCOPE-AND-QUALITY-CHECKPOINT.md#october-26-library-correctness-follow-up).
  C10 DDL namespaces and view-name lookup were subsequently addressed by the batch above.
- C01 `62058cc`, C02 `db75f2b`, C03/C20 `f8f6eb8`, C04 `7a0c429`:
  correctness fixes with regression evidence retained in the archived review.
- C05 and remaining C14 diagnostics `3ae1c18`:
  [ANALYZE publication](archive/FIX-C05-ANALYZE-PUBLICATION-2026-10-15.md).
- C15's expected-missing-table poisoning path was addressed during
  [2.0 preparation](archive/DUCKDB-2.0-PREPARATION.md#step-5-scope-and-qualification-limits).
- Conflict-history retention `f653db9`:
  [expiration evidence](archive/FIX-expired-snapshot-conflict-history-2026-10-09.md).
- UF-01–04 commits/evidence:
  [October 15 execution](archive/UPSTREAM-FOLLOWUPS-RESULTS-2026-10-15.md).

The [old eval tracker](archive/TODO-rectify-from-eval.md) is provenance for W/R/C/Q/S/X
IDs found in source/test comments, not a second active queue. Its reused W-B7 and
old “uncommitted”/row-ID wording are historical; current nullable lineage and safety
contracts take precedence.
