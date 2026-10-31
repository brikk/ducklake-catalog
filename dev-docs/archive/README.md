# Archived development evidence

These documents preserve dated observations, original review IDs, commit evidence
and completed plans. They are **not current support guidance or another active
queue**. Old pins, line numbers, pending-release text and unchecked historical
items are retained as evidence, not assertions about today's HEAD.

Current work: [developer index](../README.md), [review backlog](../BACKLOG.md),
[upstream TODO](../TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md).
User contracts: [docs](../../docs/README.md).

## Reviews and original IDs

- [Remaining-backlog triage](TRIAGE-2026-10-28.md): all 74 H/B/N/R/Q/UF IDs classified
  against current code, with [executable negative witnesses](triage-2026-10-28/README.md).
- [Catalog review completion](CATALOG-REVIEW-COMPLETION-2026-10-28.md): C01–C24
  complete through `fb53459`; former backlog closure details, not an active queue.
- [Catalog and limited-read verification checkpoint](R07-SCOPE-AND-QUALITY-CHECKPOINT-2026-10-28.md):
  completed fixes, exact artifact/test evidence and connector milestone closeout.
- [1.1 support history](DUCKLAKE-1.1-SUPPORT-HISTORY-2026-10-28.md): original
  requirements/checklists and dated implementation/qualification results.
- [Upstream follow-up history](UPSTREAM-FOLLOWUPS-HISTORY-2026-10-28.md): shipping
  checkpoints and completed investigations removed from the active TODO.
- [September review](REVIEW-2026-09-05.md): canonical C/H/B/N/R/Q findings.
- [Original eval tracker](TODO-rectify-from-eval.md): older W/R/C/Q/S/X IDs cited by tests.
- Old generated navigation notes:
  [main kit](agent-kit/catalog-main-kit-notes.json),
  [test kit](agent-kit/catalog-test-kit-notes.json). Their file/line advice is not authoritative.

## Completed preparation and qualification samples

- [DuckDB 2.0 preparation](DUCKDB-2.0-PREPARATION.md)
- [Maintained 1.5.6 upgrade](UPGRADE-DUCKDB-1.5.6-2026-10-10.md)
- [1.1 preparation plan](PLAN-DUCKLAKE-1.1-PREP.md),
  [results](DUCKLAKE-1.1-PREP-RESULTS.md) and
  [frozen October 12 capabilities](DUCKLAKE-1.1-CAPABILITIES.md)

## Reproduction and execution reports

- [H07: replay attachment transitions](FIX-H07-ATTACHMENT-STATE-2026-10-31.md)
- [N05: expiry criteria and timestamp SQL](FIX-N05-EXPIRY-CRITERIA-2026-10-30.md)
- [N13: required snapshot commit information](FIX-N13-COMMIT-MESSAGE-2026-10-30.md)
- [Java Quack JDBC 0.11.1 adoption](UPGRADE-QUACK-JDBC-0.11.1-2026-10-30.md)
- [Java Quack JDBC 0.11.0 adoption](UPGRADE-QUACK-JDBC-0.11.0-2026-10-29.md)
- [Review R04: SQL-server read-session setup](FIX-REVIEW-R04-READ-SESSION-2026-10-29.md)
- [Harness stages: H05, H09 and H08](FIX-HARNESS-STAGES-2026-10-29.md)
- [Quack JDBC 0.10.0 / embedded DuckDB quick scan](UPGRADE-QUACK-JDBC-0.10.0-2026-10-28.md)
- [Harness integrity repairs: H02, H03 and H04](FIX-HARNESS-INTEGRITY-2026-10-28.md)
- [Priority repairs: N18, review R01 and H01](FIX-PRIORITY-FINDINGS-2026-10-28.md)
- [Expired-snapshot conflict history](FIX-expired-snapshot-conflict-history-2026-10-09.md)
- [C05 ANALYZE publication](FIX-C05-ANALYZE-PUBLICATION-2026-10-15.md)
- [October 14 follow-ups](UPSTREAM-FOLLOWUPS-RESULTS-2026-10-14.md)
- [October 15 UF-01–04](UPSTREAM-FOLLOWUPS-RESULTS-2026-10-15.md)

## Upstream surveys

- [October 8 DuckDB 2.0 survey](UPSTREAM-DUCKDB-2.0-2026-10-08.md)
- [October 13 cross-project survey](UPSTREAM-SURVEY-2026-10-13.md)

Keep useful evidence; do not refresh a historical observation in place to make a
new binary appear qualified. Record new results with their actual date/hashes and
update the active TODO. No upstream checkout, test fixture or probe lock was moved here.
