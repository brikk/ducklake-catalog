# Review R04 — fail-closed SQL-server read-session setup

Base `bab68a6`; owner assigned the September **review R04** defect, not the
similarly numbered final-1.1 read requirement. Runtime pins remain Quack JDBC0.10.0
and embedded DuckDB JDBC2.0.0.0 / DuckDB2.0.0. No push is authorized for this task.

**Implemented in `9724297`; scoped catalog verification passes, but project
qualification remains blocked.** The full invocation passes all1,118 catalog tests;
its sole failing JUnit test is identity control, containing the known six strict
TIME endpoint failures retained by the owner's prior choice. No new skips or
fabricated values were introduced. Do not claim a green full gate or promote this
repair to Complete while qualification is failing. This new implementation is not pushed.

## Reproduction and repair boundary

Legacy `readSession` selected mandatory READ_ONLY / REPEATABLE READ setup only
when the final-1.1 opt-in route was PostgreSQL/MySQL. Flag-off PostgreSQL therefore
swallowed any setter SQLException under a best-effort helper, even connection or
policy faults. The callback then ran under READ COMMITTED despite its pinned-view
contract. The [original triage](TRIAGE-2026-10-28.md) remains historical evidence.

The standalone archived IsolationProbe was rerun on actual Java17.0.2 against the
unchanged base: one injected isolation fault; one legacy callback observed
**snapshot0 then1** after a competing schema commit. Strict opt-in mode rejected
the same fault. Disposable PostgreSQL/server-owned fixtures were used, not a live lake.

The repair selects required settings by actual recognized SQL backend rather than
the format opt-in flag. PostgreSQL and MySQL must both establish read-only
REPEATABLE READ before an action may run. Failed/unsupported setters propagate
their original exception. The existing connection `use` releases the lease on
failure, before installing session DSL/thread-local state or invoking the callback.
Controlled sessions also clear/finish their single-use control on failure.

DuckDB's own snapshot isolation and best-effort setter compatibility are preserved.
Normal PostgreSQL pins, nesting, write refusal, callback-exception cleanup and pool
reset remain intact. There is no retry, connection-setup deadline guarantee, new
public injection seam or format/write/migration change. Supplier/controlled method
descriptors, DTOs, JSON and the Java17 runtime floor are unchanged.

MySQL coverage here extends the same SQL-session setup rule and existing read
controls. The new fault-only MySQL case uses an empty disposable SQL server without
bootstrapping DuckLake metadata; it is **not** native creation/migration or M01
acceptance. Strict MySQL/native gates are neither rerun nor waived by this task.

## Desired-behavior regressions

Seven maintained groups exercise real pooled connections, with a test-only proxy
injecting faults at setter calls:

- legacy PostgreSQL transport, policy and unsupported-isolation errors never invoke
  the callback or publish its proposed competing schema;
- read-only failure stops before the isolation setter/action;
- controlled setup failure returns its connection, consumes the old control and
  permits a new control/session;
- one-shot setup failure followed by healthy recovery maintains a real pin across
  a competing commit; nested reads perform no new setup/checkout;
- callback failure preserves the caller's exception and clears session/pool state;
- the already-strict opt-in path retains its original fault rejection;
- legacy MySQL rejects read-only/isolation failures and still supports healthy setup.

One-connection pool metrics, close counts and restored auto-commit/read-only/
default-isolation checks catch leaks and state contamination. No expected bad
snapshot, weakened isolation or swallowed exception becomes a new golden.

## Verification

- Before the production change: **7 cases / 5 failures / zero errors/skips**. The
  strict-route and caller-failure controls pass. A separate actualJava17 negative
  witness reproduces the snapshot drift described above.
- Focused: **63 pass**, zero JUnit failures/errors/skips. Includes all7 new cases,
  existing PostgreSQL pinning/cancellation tests, all three DuckDB-backed routes,
  direct Java Quack and existing MySQL read/diagnostic checks. Catalog lint and
  Java17 ABI checks pass.
- The [Java17 repair witness](read-session-setup-2026-10-29/Java17ReadSessionSetup.java)
  compiles with `javac --release17` and passes on actual **Java17.0.2**. It confirms
  no callback on setup failure, the same original exception, real recovered/nested
  snapshot pinning and return of every borrowed lease. Autocommit queries may borrow
  more than one connection in jOOQ; the witness compares borrow/close counts and
  actual pool activity rather than assuming a fixed outside-session checkout count.
- Full forced invocation: **1,118/1,118 catalog**, **86/86 direct-Java** and
  **51/51 SLT** tests pass. Replay: **145/146 pass**; the only failing JUnit test is
  identity control, with the same original TIME failures at
  `data_inlining/data_inlining_types.test:36/46/53` and `types/all_types.test:32/42/49`.
  Corpus: **17,596 passed / six failed / 12 original record skips**, with all100
  original file exclusions unchanged. All groups actually executed in this invocation;
  no stale focused XML was counted as a full run. No JUnit errors/skips.
- Full run: **41m5s**, exit1, with `--continue`. Catalog/replay/SLT lint and Java17
  classfile checks pass (7,843 classes / 17 artifacts); existing ABI/wire controls
  passed within the catalog suite. There is **no successful full-check claim**.
  No R04-specific failure or external cache-lock failure was observed in this run.
  Pinned input preparation succeeded; all three source/test hashes recorded before
  the run match the final committed implementation. No corpus, golden, exclusion,
  dependency, native extension or server pin changed, and no sibling implementation/
  adoption is claimed. Separate strict native gates were not rerun or waived.

Full command:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```

Raw evidence under `/tmp/opencode/`: `catalog-review-r04-red{.log,-xml/}`,
`catalog-review-r04-focused{.log,-xml/}`, `catalog-review-r04-java17-before/`,
`catalog-review-r04-java17-after/`, `catalog-review-r04-source-hashes.json` and
`catalog-review-r04-full{.log,-xml/}` (with summary/failures). The first smoke's overly
specific outside-session
checkout-count assertion is retained separately as a fixture diagnostic; it is not
counted as a production defect or silently erased.
Post-commit checks verify all three committed source/test hashes, all83 local links,
and the active split of12 unfixed defects / four implementations pending qualification.
Machine report: `catalog-review-r04-verification.json`.
