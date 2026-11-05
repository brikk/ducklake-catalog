# 0.15.1 — accurate scheduled-file count on a cleanup failure

All three modules and catalog test fixtures are released together at 0.15.1. Only the
catalog changes; everything else is as in [0.15.0](RELEASE-0.15.0.md).

## Fixed

- When cleanup failed after a committed expiry, `DucklakeExpiryCleanupIncompleteException.scheduledFileCount`
  also counted files from the page that rolled back (e.g. reported 1,002 with only 1,000
  committed). Page counters now reach the totals only after the page commits, so the count
  and the strict budget reflect committed work only. Found by the Doris 0.15.0 adoption.
  No file was lost or wrongly scheduled before the fix: recovery already scheduled the
  remainder, and live files stayed protected.

## Verification

New regression: a page commit fails after earlier pages committed, and the exception's count
equals the committed queue rows; a resume completes cleanup. The expiry, table-scoped
cleanup, bounded-cleanup and commit-outcome suites pass on all five backends; detekt is clean.
