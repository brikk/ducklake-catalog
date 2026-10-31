# Triaged work queue

Remaining work after current-code triage and the verified
[priority](archive/FIX-PRIORITY-FINDINGS-2026-10-28.md) and
[harness integrity](archive/FIX-HARNESS-INTEGRITY-2026-10-28.md) repairs. The
[74-item triage report](archive/TRIAGE-2026-10-28.md) preserves the original evidence
against `662a84a`; completed repairs are not repeated in this queue.
There are **16 open findings**: **10 unfixed reproduced defects** and **6 implemented
repairs pending qualification**, plus **3 source-confirmed delivery-control gaps**.
Missing features or missing evidence are not counted as reproduced bugs.

**Namespace:** `review R04` below is a September residual ID, not
the similarly numbered 1.1 read requirement. No sibling implementation is assigned
or edited by this queue.

## Unfixed confirmed defects

Each entry has a fresh current-code witness in the report, not just an old checkbox.
Use desired-behavior regressions before repairing it; do not preserve a bad result
as a new golden. Priorities distinguish data/filesystem safety from other defects,
not production incident frequency.

| ID | Priority | Reproduced failure / next fix boundary |
|---|---|---|
| N03 | P2 | Valid nested field punctuation produces ambiguous legacy type strings; coordinate an escaping/structured-type contract with consumers. |
| N19 | P2 | Boolean result hashes use true/false rather than upstream 1/0. |
| H06 | P2 | A tiny huge-loop header exhausts a bounded child JVM before rejection; budget parsing, nesting and expansion. |
| H07 | P2 | A/B/C attachments, quoted DETACH and failed DETACH corrupt mirror state transitions. |
| H11 | P2 | Mixed skipif conjunctions use the wrong per-term skip policy. |
| H12 | P2 | Truncated loops become runnable loops rather than malformed records. |
| H13 | P2 | Nested reuse of an iterator name substitutes the inner value instead of upstream outermost-first behavior. |
| H14 | P2 | DML RETURNING mutates then fails through executeUpdate; support results/counts without a second mutation. |
| H15 | P2 | Java17 floating rendering differs from native/Java25 for ordinary values; use runtime-independent conversion. |
| H16 | P2 | Golden substitution discards valid raw expectations. |

## Implemented repairs pending qualification

The [H05 → H09 → H08 implementations](archive/FIX-HARNESS-STAGES-2026-10-29.md)
and [review R04 setup repair](archive/FIX-REVIEW-R04-READ-SESSION-2026-10-29.md)
pass their scoped regressions, but are **not Complete**. H09 exposes six original
all-types corpus queries whose native TIME24:00 values the pinned JDBC driver cannot
represent. The owner chose to keep those failures strict, without new skips or
fabricated values. Full qualification remains blocked; do not redo these implemented
changes or remove their active entries until the gate is resolved and rerun.
The [N13 required-message guard](archive/FIX-N13-COMMIT-MESSAGE-2026-10-30.md)
also passes its scoped regressions and returns to the same strict baseline on a
second full run; its first full run's intermittent evolution failure is preserved
in the evidence, not silently discarded.
The [N05 expiry selector repair](archive/FIX-N05-EXPIRY-CRITERIA-2026-10-30.md)
also passes all scoped checks with the same strict full-project blocker.

| ID | Priority | Pending boundary |
|---|---|---|
| H05 | P1 | Measured oracle/mirror counts, skip reasons and positive coverage guard implemented; shared batch qualification remains blocked. |
| H09 | P2 | Conversion sentinels removed; six TIME24:00 corpus failures now remain visible. Needs separately scoped lossless support and a passing qualification rerun. |
| H08 | P2 | Oracle SQL expectations separated from adapter setup errors; shared batch qualification remains blocked. |
| review R04 | P2 | Required PostgreSQL/MySQL read-session setup now fails closed regardless of format opt-in; all catalog regressions pass, but the project TIME24:00 qualification gate remains blocked. |
| N13 | P2 | Required-message snapshot writes now refuse before mutation on every attempt; all 22 regressions and 1,140 catalog tests pass. Strict project qualification remains blocked. |
| N05 | P2 | Conflicting expiry criteria now reject; DuckDB timestamp selection uses the existing UTC-safe rendering. All 19 regressions and 1,159 catalog tests pass; strict project qualification remains blocked. |

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
  When adopting the new replay artifact, both general connector conformance suites
  must enforce a meaningful positive `requireMirrorPasses` threshold; legacy
  record/oracle totals are not verified engine coverage. No sibling edit or rerun
  is assigned by this library repair batch.
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
