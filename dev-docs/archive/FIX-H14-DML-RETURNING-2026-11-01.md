# H14: single-execution DML results — November 1, 2026

First step of the approved H14 → H06 batch. No push, publication, dependency
promotion or connector edit is authorized.

**Status: implemented/verified in scope; strict project qualification remains
blocked, not Complete.** The first full run also records an unexpected Quack
migration-test failure; it is not waived or counted as the parked TIME baseline.

## Before-fix evidence

The new native regression suite runs13 tests against unchanged production code:
**11 failures, two passing controls, zero errors/skips**. Every failure originates
from the replay harness choosing `executeUpdate()` for a result-producing DML
command. Pinned **`org.duckdb:duckdb_jdbc:2.0.0.0`** executes the mutation and then
rejects that API's result shape. Source and statement-trigger audit queries after
the failure confirm the mutations happened once; no malformed fixture is counted
as a desired failure. MERGE and statement-trigger syntax work on the pinned peer.

The two controls cover prefixed/CTE RETURNING (already using direct execution) and
ordinary DML counts, including zero, CTEs, comments, MERGE and TRUNCATE.

## Repair

- Use one standard JDBC `Statement.execute(sql)` for every query record. Consume
  either its ResultSet once or its `getLargeUpdateCount()` once, without preparing,
  rebinding, inspecting private native state, detecting a RETURNING keyword or
  retrying/re-executing SQL.
- Preserve typed RETURNING rows, SQL NULL, BOOLEAN versus VARCHAR provenance,
  count-like aliases, and empty typed results. Preserve a genuine no-result command
  separately from an empty ResultSet. Ordinary DML counts retain the BIGINT result
  representation, without narrowing to JDBC's32-bit update-count accessor.
- Remove the stale comment that `execute()` cannot supply changed-row counts.
  A [standard JDBC probe](h14-single-execution-2026-11-01/NativeExecutionProbe.java)
  on Java17 verifies CREATE/DROP(-1), INSERT(2), CTE INSERT(1), DELETE(1),
  TRUNCATE(3), RETURNING rows and empty RETURNING metadata.
- Keep the read-adapter `accepts` gate and all existing mirror gates unchanged.
  The regression adapter explicitly rejects DML; this does not claim the framework
  universally recognizes mutating SQL or side-effecting SELECT functions.

Tests additionally check exact source/audit effects, explicit rollback, intentional
shape/golden mismatches after one mutation, and the existing single-bind side-effect
and genuine no-result controls. No golden, skip, native pin, SQL value, sentinel,
write guard, migration or exception-to-value recovery is changed.

## Verification

Focused execution: **21/21 pass on Java17 and21/21 on Java25**, comprising all13
new regressions plus the eight existing golden-integrity/single-bind controls,
zero failures/errors/skips. Replay lint passes.

Full forced verification (45m22s, exit1, `--continue`): **1,177/1,177 catalog and
51/51 SLT pass; replay198/199**. Its only replay failure contains exactly the six
parked TIME records: corpus17,596 passed/six failed/12 original record skips,
100 original file exclusions,5,189 oracle query passes. Source/proof hashes remain
unchanged. No quota/compiler interruption or new corpus frontier appears.

The same full run's direct-Java Quack group is **90/91**, with
`TestFinal11LegacyStructOrder.genuineUpstream156StructOrderFixtureReadsCorrectlyByNameAfterExplicitMigration`
failing during a catalog inline read with `Current transaction is aborted (please
ROLLBACK)`. This group does not execute the replay driver. Its full XML is preserved;
the unchanged whole-group forced rerun also finishes **90/91** (2m17s), but its
failure moves to
`TestFinal11ReadPreview.unqualifiedVariantPayloadsFailExplicitlyWhileCountsRemainReadable`:
an aborted-transaction `DataAccessException` appears instead of the expected
`DucklakeInvalidOperationException`. The original legacy-struct test passes in
that rerun. Both failures are retained in `full-xml/` and `quack-repeat-xml/`, with
logs and `quack-repeat-failures.json`. No source in that group's catalog-only
dependency graph is changed by H14. The cause is not diagnosed or fixed by this
batch; this is an additional open qualification problem, not proof of a replay
regression or an accepted flake. This full run must not be described as having only
the parked TIME blocker. No assertion, fixture, timeout or driver pin is changed.

Evidence: `build/verification/h14/` contains the red log/XML, per-JVM focused
logs/XML, `full.log`, `full-exit.json`, `full-summary.json`, `full-xml/` and native
JDBC probe output. Full verification uses the committed H15 workspace-temp/compiler
profiles and the committed Java17 direct-Quack launcher, with the same forced-check
command preserved in `full-exit.json`. The downloaded published JDBC sources have
SHA256 `dd622d97825978b8967776053201bb01c793e795cbef71daa7e67bfd663c80c5`;
the unchanged binary is
`5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
The source witness uses public result metadata/return-category handling, not a
runtime dependency on implementation internals.
