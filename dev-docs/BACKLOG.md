# Triaged work queue

Remaining work after current-code triage and the
[verified priority repairs](archive/FIX-PRIORITY-FINDINGS-2026-10-28.md). The
[74-item triage report](archive/TRIAGE-2026-10-28.md) preserves the original evidence
against `662a84a`; completed repairs are not repeated in this queue.
There are **19 reproduced defects** below, plus **3 source-confirmed delivery-control
gaps**. Missing features or missing evidence are not counted as reproduced bugs.

**Namespace:** `review R04` below is a September residual ID, not
the similarly numbered 1.1 read requirement. No sibling implementation is assigned
or edited by this queue.

## Confirmed defects

Each entry has a fresh current-code witness in the report, not just an old checkbox.
Use desired-behavior regressions before repairing it; do not preserve a bad result
as a new golden. Priorities distinguish data/filesystem safety from other defects,
not production incident frequency.

| ID | Priority | Reproduced failure / next fix boundary |
|---|---|---|
| H02 | P1 | Wrong BIGINT, VARCHAR-as-Boolean and incorrect query arity pass goldens; preserve SQL types/shape and compare exactly where required. |
| H03 | P1 | NULL/empty text tokens and row separators collide in live-vs-live equality; compare structured nullable rows. |
| H04 | P1 | Timeout drops an earlier failure and abandons a worker; preserve outcomes and isolate/terminate worker ownership. |
| H05 | P1 | An always-rejecting engine produces passes with zero engine queries and no engine skips; report actual coverage. |
| review R04 | P2 | Legacy PostgreSQL readSession swallows an isolation-setting fault and sees a new snapshot mid-session; strict opt-in mode already rejects the fault. |
| N13 | P2 | Catalog CREATE bypasses require_commit_message while native CREATE rejects; honor the policy or refuse unsupported message-required writes. |
| N05 | P2 | Expiry accepts mutually exclusive criteria; timestamp-only DuckDB SQL also renders unsupported TIMESTAMPTZ precision. Keep validation/rendering subcases distinct. |
| N03 | P2 | Valid nested field punctuation produces ambiguous legacy type strings; coordinate an escaping/structured-type contract with consumers. |
| N19 | P2 | Boolean result hashes use true/false rather than upstream 1/0. |
| H06 | P2 | A tiny huge-loop header exhausts a bounded child JVM before rejection; budget parsing, nesting and expansion. |
| H07 | P2 | A/B/C attachments, quoted DETACH and failed DETACH corrupt mirror state transitions. |
| H08 | P2 | An adapter-connect SQLException is accepted as the expected oracle statement error. |
| H09 | P2 | JDBC conversion exceptions become comparable/hashable failure sentinels. |
| H11 | P2 | Mixed skipif conjunctions use the wrong per-term skip policy. |
| H12 | P2 | Truncated loops become runnable loops rather than malformed records. |
| H13 | P2 | Nested reuse of an iterator name substitutes the inner value instead of upstream outermost-first behavior. |
| H14 | P2 | DML RETURNING mutates then fails through executeUpdate; support results/counts without a second mutation. |
| H15 | P2 | Java17 floating rendering differs from native/Java25 for ordinary values; use runtime-independent conversion. |
| H16 | P2 | Golden substitution discards valid raw expectations. |

## Confirmed delivery-control gaps

These are current configuration/task-graph defects, not evidence that an existing
published artifact is broken. Address before relying on the corresponding guarantee.

| ID | Missing control |
|---|---|
| B02 | Require successful verification of the exact published SHA; current publication graph has no check/test dependency. |
| B03 | Enforce module/root version and release-tag agreement; current guard checks only root SNAPSHOT status. |
| B04 | Isolate nightly/manual exhaustive CI from replaceable push cancellation groups. |

## Separate capability and qualification work

- **Known external failures:** keep the exact native gates and original failures in
  the [upstream TODO](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md) and
  [checkpoint](R07-SCOPE-AND-QUALITY-CHECKPOINT.md). They were not rerun or waived here.
- **Consumer adoption / evidence:** qualify newer exact catalog artifacts and
  affected engine behavior; missing fleet/cancellation/CDC evidence is not a proved
  connector bug. Current Trino 483/484 and Doris API24 released-lane checks do not
  promote the separate R07 candidate lane.
- **Bounded maintenance:** source audit confirms both orphan listings and Doris
  queued cleanup remain unbounded. This is a real scalability/safety hardening gap,
  not a measured OOM or deletion-loss incident. Preserve root/table namespaces,
  SQL/path/aggregate traversal budgets, and all young/undated queued ownership;
  age-filtered candidate batches are not a complete orphan known set. See
  [contract limits](../docs/FILE-REFERENCE-NAMESPACES.md).
- **Deferred features / contracts:** current-default metadata, full encryption,
  broader authoring/evolution, unknown-writer policy, failed-DROP retry inventory,
  physical leases and full MySQL inline preparation/publication need their own
  scope decisions. Keep the existing refusals and retention boundaries until qualified.
  W01–W06 and native M01 remain in the [1.1 TODO](TODO-DUCKLAKE-1.1-SUPPORT.md).
- **Optional hardening:** broader API/release-aware JVM checks, structural/lint
  hygiene, reproducible inputs, dependency automation, permission declarations,
  schema freshness and future toolchain/native-access work are inventoried in the
  report, not silently counted as current runtime failures or automatic assignments.
