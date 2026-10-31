# H07: replay attachment transitions — October 31, 2026

Owner assigned N13 → N05 → H07, with verification, commit and push between steps.
N13 (`7fac90c`) and N05 (`07defb1`) were pushed before this step began.

**Status: implemented; pending strict project qualification, not Complete.** Keep the original
six strict TIME24:00 failures separate; this is not a JDBC/native representation fix.

## Before-fix evidence

The verified baseline runs **14 tests: 12 desired transition assertions fail, two
controls pass**. Real oracle SQL succeeds/fails as intended; a control adapter
records connection announcements and query dispatch. It does not certify a real
connector's result correctness. A first draft omitted SLT error delimiters; that
draft's parser/setup failures are not counted as defect evidence.

Reproduced cases include A/B/C reconnecting after supposed permanent disablement;
failed second/pinned ATTACH or failed DETACH changing valid mirroring; missed
quoted DETACH and quoted aliases; failed ATTACH poisoning the last successful
DATA_PATH; single-alias replacement leaving stale state; and IF NOT EXISTS no-ops
being treated as different lakes. Coverage assertions distinguish real oracle
passes, mirror dispatch/pass counts and specific gate reasons.
Two additional review regressions distinguish rewrite reservation from successful
DATA_PATH knowledge and protect that knowledge through an IF NOT EXISTS no-op.
The first exposed a draft implementation incorrectly announcing an empty-path
attachment after a failed first ATTACH (one red/one green control before correction).

## Repair boundary

`ReplayAttachmentState` prepares rewritten SQL without changing mirror eligibility.
Only successful oracle execution commits attachment/DETACH state and successful
DATA_PATH knowledge. Metadata rewrite reservations remain memoized once per
original because a rewriter can provision a backend before executing ATTACH; that
reservation is not a successful attachment or adapter connection.

- Track active alias/target membership. A successful second distinct lake or
  snapshot-pinned attach latches the first disabling reason for the rest of the
  file. Later ATTACH/DETACH cannot reconnect around it. Each replay gets fresh state.
- Successful ordinary DETACH clears the current mirror; a later ordinary ATTACH
  reconnects, retaining the successful rewrite/data-path memo. Failed statements
  leave eligibility and successful data-path knowledge unchanged.
- Decode quoted aliases, including doubled quotes; DETACH accepts optional
  DATABASE/IF EXISTS, case differences and a trailing semicolon. Replacement and
  IF NOT EXISTS preserve actual alias-membership semantics.
- Apply a successful SQL state effect even when SLT incorrectly expected an error,
  but never let that failed expectation call adapter setup. Adapter connection
  failures remain distinct record failures, not accepted native SQL errors/skips.
- Preserve H05's measured query stages/positive guard and H08's setup boundary.
  No fabricated mirror passes, query replay, new corpus skip, golden change,
  connector edit, pin change, migration or 1.1 library write enablement.

This is a scoped attachment recognizer/state repair, not a general SQL parser,
multi-lake adapter capability or full transaction/connection lifecycle redesign.

## Verification

- Focused transitions, measured coverage, setup, comparison and timeout controls:
  **39 tests pass**, including all **16 transition regressions**, zero failures/
  errors/skips. Lint and Java17 classfile checks pass.
- Full forced check: **1,159/1,159 catalog + 86/86 direct-Java + 51/51 SLT pass;
  replay 161/162**, with only identity control's original six TIME records
  (`data_inlining_types.test:36/46/53`, `all_types.test:32/42/49`). Corpus remains
  **17,596 passed / six failed / 12 original record skips**, with all 100 original
  file exclusions unchanged. Zero JUnit errors/skips. Every test group actually
  executed; lint and Java17 checks pass. Duration **42m**, exit 1 (`--continue`).
  All scoped source hashes captured before full verification still match.
  The first N13 full run's intermittent evolution failure does not recur; that
  earlier observation remains documented in the N13 evidence.

Evidence under `/tmp/opencode/`: `catalog-h07-before-verified{.log,-xml/}`
(the final counted original-code baseline),
`catalog-h07-rewrite-reservation-before{.log,-xml/}` (the review regression), and
`catalog-h07-focused-final.log` (focused verification, including all 16 transitions).
Completed focused XML/counts: `catalog-h07-focused-xml/`. Scoped hashes captured
before full verification: `catalog-h07-source-hashes.json`.
Completed full XML/counts/failures: `catalog-h07-full-xml/`.

Full command (output `catalog-h07-full.log`):

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```
