# Review backlog

This is an index of remaining review work, **not a new correctness audit**. Original
evidence, severity and closure criteria are preserved in the
[September review](archive/REVIEW-2026-09-05.md); its line numbers are historical.
Reproduce against current code before choosing a fix. A documentation move does
not close a finding, and green corpus totals alone do not establish conformance.

For authorized upstream work and the next RC checkpoint, use the separate
[UF TODO](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md). Do not edit sibling connectors.

## Confirmed follow-up priorities

- **C06 — MySQL atomicity:** physical inline CREATE can implicitly commit metadata.
  Reproduce injected failure/snapshot collision before designing safe refusal or
  coordinated table preparation. Quoting fixes alone do not solve this.
- **Native correctness / RC:** UF-01–04 investigations are complete but native
  failures remain gates; UF-05–09 are not implemented. Keep 1.1 writes disabled.
- **Consumer adoption:** preserve COMMIT classifications and retain uncertain output;
  connector agents must verify cleanup/error mapping and their CDC/pruning paths.

## Catalog candidates still requiring current-code verification

| ID | Work / closure criterion |
|---|---|
| C07 | Logical-type pruning: float32 equality and end-of-day TIME must not lose matching rows. UF-04's bucket work is separate. |
| C08 | Apply initial defaults to absent old-inline columns without replacing stored NULLs; make CDC contract decisions explicit. |
| C10 | Case-insensitive relation/sibling-field namespace checks for sequential DDL and races. |
| C11 | Validate surviving STRUCT/LIST/MAP arity and nested removal paths. |
| C12 | Per-output partial-rewrite origins or required embedded IDs; no overlapping fallback row-ID ranges. |
| C13 | Migrate callers off weak legacy materialization boundaries without silently removing shipped APIs. |
| C16 | Large/signed interval parsing and literal CAST-string provenance. |
| C17 | Real MySQL inline DDL type/identifier rendering or safe refusal; coordinate with C06. |
| C18 | Partition-spec ownership and key coverage, distinguishing missing keys from SQL NULL. |
| C19 | Rewrite/version sort expressions on rename, including subsequent native inserts and old snapshots. |
| C21 | Empty inline projections preserve cardinality or reject explicitly. |
| C22 | Legacy reference API path namespaces and downstream adoption of bounded replacements. |
| C23 | Redact credential-bearing JDBC URLs in diagnostics/logs. |
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

- **C09 and direct schema/table identifier reads — October 26:** precise expected-table
  error classification replaces the legacy silent-empty fallback; ASCII-only/binary
  name matching preserves distinct Unicode names on all five read routes. Actual-API
  regressions and the full 1,006 maintained + 86 direct-Java / 17,602-record run pass.
  [Evidence and consumer scope](R07-SCOPE-AND-QUALITY-CHECKPOINT.md#october-26-library-correctness-follow-up).
  This does not close C10 DDL namespaces or change view-name lookup.
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
