# Quack transaction diagnosis — November 3, 2026

Owner requested a bounded investigation before deciding how to release the catalog
for Trino and Doris. This is not a release authorization, dependency adoption,
connector assignment or TIME investigation.

**Status: diagnosed to a driver/native result-lifecycle interaction; not caused by
catalog SQL or session handling.** The driver fix shipped as `quack-jdbc` 0.12.1
(`cca8445`) and is adopted by the catalog in
[UPGRADE-QUACK-JDBC-0.12.1-2026-11-03](UPGRADE-QUACK-JDBC-0.12.1-2026-11-03.md).
The native interrupt behavior remains upstream and unreported.

## Conclusion

The aborted transactions come from **closing a Quack result while the server still
considers its statement unfinished, then sending the next query in the same
transaction**. The pinned native server interrupts the superseded statement, and
DuckDB invalidates the open transaction. The next statement then reports `Current
transaction is aborted`, with no earlier SQL error ever reaching Java.

- **Catalog:** not the cause. It reads single-row probes with legal JDBC (first row,
  then close) and never sees an earlier error. It *triggers* the window because it
  issues many such probes inside one read transaction.
- **Our `quack-jdbc` driver:** `Cursor.close` abandons a result whose PREPARE said
  `more=true` without the terminal FETCH, leaving the server producer unfinished.
  This is the practical place to fix it.
- **Native Quack `f96420ed46`:** superseding an unfinished statement interrupts it,
  which poisons the caller's transaction. Small results are commonly still
  "unfinished" for a moment after their only batch is delivered, so this is a race.
  An upstream report is warranted, but not a prerequisite for a driver fix.

## Original observations

Intermittent direct-Java `jdbc:quack:` read-session failures occurred on the pinned
native peer. They are not the embedded-JDBC tuple getter crash. In particular,
the H11 pinned-reader failure occurs at the inline-registry SELECT **before** the
test's native flush, not during/after it. Preserve the original H11/H14/JDBC
upgrade XML and passing repeats; none is invalidated by this investigation.

## Artifacts and instrumentation

- Catalog source base: `7ebc2f3`.
- Published `dev.brikk.duckdb:quack-jdbc:0.12.0`, SHA256
  `0fa0cc634d35cb2a77ff88232fe1f6a3fd1570ba54729c9cc50005c7b575f1cf`.
- Signed DuckDB `v2.0.0`/`d54cbbe3df`, DuckLake `417a9117f5`, native Quack
  `f96420ed46`, protocol3, unsigned extensions disabled. Existing owned fixture
  verifies runtime identities and driver bytes.
- Driver checkout inspected at `eeb46fc`; its Java source is unchanged from
  release `9ef00f3`. No driver checkout edits are made.
- Archive-only diagnostic sources are added to the opt-in preview source set by
  [an explicit init script](quack-transaction-diagnosis-2026-11-03/trace.init.gradle).
  Normal builds/published artifacts do not include them. Transport injection uses
  the driver's public factory interface and delegates each request exactly once.
  Log request sequence, SQL and response completion flags, never connection-request
  payloads, authentication properties, headers or tokens. No private reflection,
  retries, recovery query replay, invented values or weakened assertions.
- Raw evidence is under `build/verification/quack-transaction-diagnosis/`.
  Pinned server/collector/buffer source downloads and SHA256s are under `upstream/`.

## Findings

1. Catalog `dynamicTableExists` uses a valid non-failing information-schema probe
   with `LIMIT1` and jOOQ `fetchOne`. Tracing shows these one-row results can be
   closed without a terminal FETCH even when PREPARE says `more=true`. Other
   schema-version/column-existence one-row probes exhibit the same sequence.
2. Driver `QuackSession.Cursor.close` only clears local buffered rows and marks
   the Java query done; it neither drains the stream nor waits for native completion.
3. Pinned native Quack `quack_server.cpp:662–663` aborts the previous producer when
   the next PREPARE arrives. `AbortDetachedStatement:63–73` interrupts its DuckDB
   connection if the stream is not finished. `DriveQuery:196–197` marks the stream
   finished only after the native query completes. Thus reading its first batch is
   not necessarily observing statement completion.
4. Driver prepared-statement `getMetaData` does catch and discard runtime errors
   from its wrapper query, but the initial traced catalog run sends **no such
   wrapper queries**. That source concern is not established as this failure's cause.
5. Initial disk-flushed tracing passes150 catalog sessions and300 direct fully
   drained sessions. This is a passing observation, not a fix. Expected startup
   connect failures and the existing handshake heartbeat-timeout negotiation error
   are recorded separately from SQL; no SQL error appears in that initial run.

6. Buffered tracing also passes600 catalog sessions,300 fully drained direct
   sessions and600 direct single-row/early-close sessions. Their SQL requests all
   succeed. Early close is therefore a timing hazard, not a guaranteed failure on
   every one-row result. No statement-metadata wrapper query is sent here either.

7. **Deterministic reproduction without the catalog** (`TestQuackEarlyCloseControl`):
   one direct `quack-jdbc` connection in manual-commit mode runs the valid
   `SELECT 1; SELECT sum(sqrt(i::DOUBLE)) FROM range(10000000) t(i)`, reads the first
   row, closes it, then runs `SELECT 42`. The follow-up fails with `Current
   transaction is aborted (please ROLLBACK)` in **6/6** runs. Fully draining the
   identical query first passes in **6/6**. The multi-statement shape only widens the
   same "last batch delivered, statement not finished" window that small probes hit
   briefly. Evidence: `early-close-first/`, `early-close-controls-final.xml`, `repro-{1,2,3}.{log,xml}`.
8. **Historical failures match the window.** In the traced catalog workload
   (`predecessor-analysis.json`), every historical failing statement is immediately
   preceded by a probe closed while unfinished:
   - H11 inline-registry SELECT follows the `ducklake_inlined_data_tables` existence
     probe: unfinished **1,200/1,200**.
   - H14 VARIANT `ducklake_snapshot.schema_version … LIMIT 1` follows the inlined-data
     existence probe: unfinished **600/600**.
   - H14 legacy-STRUCT `ducklake_metadata` version read follows the
     `ducklake_schema_versions … LIMIT 1` lookup: unfinished **599/600**.
   Existence/`LIMIT 1` probes are closed unfinished essentially every time; fully read
   multi-row queries are not. The race is narrow, so loops of 600 sessions pass, which
   matches the observed rare, unchanged-rerun-passes pattern. The evolution-test
   failures were not traced individually; they run the same probe-heavy read path.

## Not diagnosed / separate

- A direct `SELECT i FROM range(40000000)` is refused by the driver's default
  `maxDecodedBytes` on its first PREPARE batch, even after `SET
  quack_target_batch_bytes=1048576`. This is unrelated to the transaction abort and
  is not diagnosed; it is recorded for the driver team (`single-statement-budget-refusal/`).
  That control was removed rather than counted either way.
- The prepared-statement `getMetaData` error suppression is real but not involved.
- Startup connect retries and the existing heartbeat-timeout handshake fallback are
  pre-existing, non-SQL, and unrelated.

## Fix options (none applied)

1. **Driver (recommended):** when a cursor closes with `more=true`, finish it rather
   than abandon it. At minimum, issue the terminal FETCH when the local buffer is
   already empty (the common small-result case; one extra round trip). For genuinely
   large unread results in manual-commit mode, either drain within a bound or document
   that abandoning them invalidates the transaction. Add the control above as a
   regression in the driver repository.
2. **Catalog workaround (only if a driver release is delayed):** fully consume
   single-row probes on Quack routes (read until `next()` is false) so the terminal
   FETCH happens. Small, local, one round trip per probe; no semantic change.
3. **Upstream native report:** superseding a statement whose rows were all delivered
   should not interrupt it into a transaction abort.

## Reproduction commands

Run from the repository root with the already qualified CLI path; paths below do
not install a replacement driver or repin native extensions:

```sh
GRADLE_USER_HOME="$PWD/build/verification/gradle-home" \
TMPDIR="$PWD/build/verification/process-tmp" \
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/verification/process-tmp" \
mise exec -- ./gradlew :ducklake-catalog:quackPreviewTest \
  --tests '*TestQuackTransactionDiagnosis' \
  --tests '*TestQuackEarlyCloseControl' \
  -I dev-docs/archive/quack-transaction-diagnosis-2026-11-03/trace.init.gradle \
  -I dev-docs/archive/quack-jdbc-0.12.0-2026-10-31/verify_java17.init.gradle \
  -I dev-docs/archive/h15-float-text-2026-11-01/verify_local_tmp.init.gradle \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-configuration-cache --console=plain
```

The broad tracer and the forced unfinished-work control are separate experiments;
do not treat passing repeats of the former as a fix for a failing latter.
