# Priority finding repairs — October 28, 2026

Requested sequence: push the cleanup/triage, then repair **N18, review R01 and
H01**. Cleanup `662a84a` and triage `8658792` were pushed before implementation.
The dated [triage](TRIAGE-2026-10-28.md) remains historical evidence, not an assertion
that these repairs had already existed at that base. Review R01 is not the 1.1 read
requirement of the same number.

**Complete and fully verified.** Separate implementation commits:

- N18: `e3aa5a5` — fence ordinary rewrites against newer native inlined deletes.
- Review R01: `bc3240c` — select logical snapshot types for statistics aggregation.
- H01: `ca4b24c` — confine oracle cleanup to an owned, non-followed child.

These new commits are not pushed. The earlier cleanup/triage push does not itself
authorize another push, artifact adoption, publication or deployment.

## N18 — ordinary rewrite / native inline-delete boundary

Ordinary rewrite now checks the selected sources' dynamic native inline-delete
ledger inside every write attempt, after probing lazy table existence without
failing SQL. A deletion newer than the caller's planning snapshot throws a
non-retryable LogicalConflictException before output registration or source retirement.
Missing/negative owner or selected-snapshot identities reject as corruption rather
than qualify stale output.

The guard chunks supplied IDs into at most 256 per query and transfers at most
one pair of numeric facts; it does not fetch an unbounded deletion ledger. Known
deletions already materialized at the planning snapshot and unrelated sources
remain supported. Partial rewrite's stronger delete-free eligibility is unchanged.
Existing physical delete-file/partial-max checks, row allocators, historical source
liveness and unknown-COMMIT classifications are retained. Public descriptors and
DTO/wire shapes do not change.

Desired-behavior regressions cover real native inline DELETE on all five metadata
routes, unchanged snapshots/files/statistics on rejection, rematerialized successful
rewrites, unrelated-file isolation, absent lazy tables, malformed identities and a
forced PostgreSQL retry that sees a new native deletion before attempt two's action.
The original native triage witness now retains `[1]`, rather than restoring `[1,2]`.

MySQL's existing native scanner cannot perform one exploratory repeated-INSERT
fixture (UPDATE syntax error). The final tests use real Parquet COPY plus the
qualified catalog INSERT path for additional files, retaining native DELETE and
native value/history/row-ID assertions. No native failure was rebaselined or skipped;
the separately tracked native MySQL gates and pins remain unchanged.

## Review R01 — snapshot-specific logical statistics types

Statistics no longer fold all historical column types into an unordered map.
Historical aggregate reads select types active at their requested snapshot;
ANALYZE/rewrite recomputation uses its logical publication view, and insert merging
uses the write transaction's new snapshot, including any staged versions. Nested
leaf IDs are retained. Retired/future versions and other tables do not choose the
comparison type. Duplicate or malformed active identities/types fail closed.

Type lookup is SQL-keyset-paged (1,024 plus one duplicate-detection lookahead),
projects at most 257 type characters to detect a 256-character limit, and bounds
total active identities at 65,536. It never fetches an unbounded catalog and applies
a memory limit later. Invalid metadata/over-budget publication rolls back the owned transaction;
missing logical types cannot invent bounds. Existing incomplete/NaN/count-aware
global suppression remains unchanged.

Five-route native-consumption regressions reorder an identical FLOAT→DOUBLE
column-history row set, then exercise ANALYZE, insert merging, ordinary rewrite and
partial rewrite. They retain correct native MIN/MAX, physical rows, row IDs and old
snapshot visibility. Paging, snapshot boundaries, nested leaves, duplicate lookahead,
NULL identities, SQL text caps, exact traversal ceiling and propagated errors have
separate maintained unit controls. The original native triage witness now publishes
MAX `1.00000002`, agreeing with the unchanged scan.

## H01 — oracle temporary-directory ownership

`DuckDbOracle(testDirRoot = parent)` allocates a fresh owned child under the existing
parent instead of treating the caller's directory as disposable. The no-argument
constructor still owns its newly created directory. Cleanup uses Java NIO's
non-following tree walk, including when the child root has been replaced by a
symlink. Only owned entries are deleted; parent files and link targets survive.
Closing twice and independent oracles sharing one parent remain safe. Existing
best-effort connection/cleanup error behavior is otherwise unchanged.

Six maintained tests cover parent files, directory/file symlinks, root replacement,
two-oracle ownership and default cleanup. All markers and targets in negative
before-fix runs were disposable test fixtures. Constructor descriptors remain
unchanged. The intentional supplied-parent behavior correction is documented in
[compatibility](../../docs/COMPATIBILITY.md); callers use `oracle.testDir` for the
actual directory. Known sibling production sources have no direct custom-root
constructor callers; no sibling edit is needed for this correction.

## Verification and scope

- Before production edits, **46 catalog cases / 31 failures**: N18 11/26 fail,
  review R01 20/20 fail; the 15 N18 positive controls pass. All five routes discover
  all intended tests. Exploratory compile/fixture issues are not counted as evidence.
- H01 before-fix: **4 of 6 fail**, with default ownership/file-link controls passing.
- Focused catalog: **104 pass**, including new guards/type lookup, real native
  witnesses, ANALYZE publication/concurrency, missing-bound and mixed-storage controls.
- Focused replay/ownership: **21 pass**. Both module lint checks pass; zero focused
  JUnit failures/errors/skips.
- Actual **Java17.0.2**: owned child/link-target smoke passes; original N18 and review
  R01 native fixtures show the corrected outcomes with the new classes. This is
  local-route minimum-runtime evidence, not all-route or connector certification.
- Final forced full check: **1,266 maintained tests** (1,111 catalog / 51 SLT /
  104 replay), **86 separate direct-Java tests**, and **17,602 corpus records** pass.
  Zero JUnit failures/errors/skips; the existing 100 file / 12 record corpus exclusions
  are unchanged. Lint, frozen compatibility/wire and Java17 checks pass (7,843
  classes / 17 artifacts). Full run: **40m13s**, once on final production/test code.
  The ten source/test hashes captured before that run were checked afterward; the
  separate implementation commits contain that same tested code. Pinned fixture
  preparation succeeded before the run; no stricter native gate was rerun or waived.

Final command:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-daemon --no-configuration-cache
```

Evidence under `/tmp/opencode/`: `catalog-n18-r01-red-final.log`,
`catalog-n18-r01-baseline-xml/`, `catalog-h01-red.log`,
`catalog-h01-red-final-xml/`, `catalog-priority-focused{.log,-xml/}`,
`catalog-h01-focused{.log,-xml/}`, `catalog-priority-java17/`,
`catalog-priority-source-hashes.json`, `catalog-priority-full.log` and
`catalog-priority-full-xml/` (with the machine-readable summary).

No upstream/corpus pin, original golden/exclusion, sibling code, consumer bundle,
release/default, 1.1 write guard or automatic migration changed. This batch does not
close other triaged defects, physical-file lease guarantees, bounded consumer
maintenance adoption, native MySQL evolution or the external 1.1 gates.
The active defect queue now contains 19 remaining findings; the dated triage's
original 74-item classification is preserved rather than rewritten as old evidence.
