# N13: required snapshot commit information — October 30, 2026

Owner assigned N13 → N05 → H07, with verification, commit and push between steps.
Base: `76cdba4` (Quack JDBC 0.11.1 adoption, already pushed).

**Status: implemented; pending strict project qualification, not Complete.** The existing six
strict TIME24:00 corpus failures remain a separate project qualification blocker.
No result from a previous run is relabelled as verification of this change.

## Reproduction and boundary

- The final before-fix baseline executes **22 tests: 16 fail, six pass**. All 16
  failures are desired refusal assertions reporting that no exception was thrown,
  not fixture/setup errors. The three policy cases fail on each of local DuckDB,
  native Quack, Java Quack, PostgreSQL and MySQL, plus the retry case.
- Native CREATE already refuses missing information with `require_commit_message`
  enabled. Native explicit author/message succeeds; five non-snapshot maintenance
  controls also pass before the fix. Those six controls remain required.
- Earlier draft runs exposed test setup issues, not N13 evidence: local DuckDB
  cannot attach one file as a native metadata catalog while its direct JDBC
  database is open; a failing native statement also closes itself. The test now
  updates live policy through the metadata route, creates caller-owned Parquet
  independently, and the native fixture always detaches with a fresh statement.
  MySQL's metadata `key` identifier is quoted. Only the final baseline is counted.

## Repair

The owned snapshot-write attempt now reads the catalog-scoped policy before
`executeWriteAction`, after existing format/encryption guards, on **every retry**.
Canonical `true` matches the native policy; absent/false and table/schema settings
do not impose a global requirement. The bounded metadata read rejects duplicate
global rows and uses the existing PG/MySQL locking-read pattern.

Because these APIs cannot supply author/message information, a required policy
throws `DucklakeInvalidOperationException`; no anonymous/sentinel message is
invented. The shared non-snapshot maintenance guard is unchanged. This is not a
distributed option/migration coordination protocol, message-authoring API or 1.1
write enablement.

Regressions cover native-versus-library CREATE, unchanged snapshot/change rows,
schema/table/view/ANALYZE/file publication refusal, live policy enable/disable,
missing/false/scoped settings, caller-owned file survival, retry rechecks, native
explicit-message success and non-snapshot expiry without a new snapshot.

## Verification

- Focused regression/format guard/backend run: **56 tests pass**, including all
  **22 N13 tests**, with zero failures/errors/skips. Lint and Java17 classfile
  checks pass (7,843 classes across 17 artifacts).
- First full forced check: **1,140 catalog + 86 direct-Java + 51 SLT pass**,
  zero JUnit errors/skips. Replay is **144/146**, not the expected 145/146:
  alongside identity control's unchanged six strict TIME records,
  `TestUpstreamFinal11Evolution` reports an aborted transaction on the
  `alter/struct_in_map_evolution.test:54` inline-table probe. This additional
  failure is not waived or relabelled as the known baseline. Full run: 42m22s.
  All **eight targeted evolution cases pass on rerun without code changes**.
  The first failure remains recorded; no root-cause or universal stability claim
  is made. The second full run below does not reproduce it.
- Second full forced check: **1,140/1,140 catalog + 86/86 direct-Java + 51/51 SLT
  pass; replay 145/146**, with only identity control's original six strict TIME
  records (`data_inlining_types.test:36/46/53`, `all_types.test:32/42/49`). Corpus
  remains **17,596 passed / six failed / 12 original record skips**, with all 100
  original file exclusions unchanged. Zero JUnit errors/skips. All groups actually
  executed; lint and Java17 checks pass. Duration **44m**, exit 1 (`--continue`).
  All scoped hashes captured before verification still match the final source.
  No source change was made between the two full runs or the targeted rerun.
- No new skips, golden rewrites, fabricated values, exception-message values,
  reflection, query replay, pin changes, sibling edits or physical cleanup work.

Final before-fix evidence: `/tmp/opencode/catalog-n13-before-verified{.log,-xml/}`.
Focused log: `/tmp/opencode/catalog-n13-focused-complete.log`.
An earlier focused invocation stopped at the shell's 120-second timeout; it is
not counted as completed verification. Fixture cleanup was factored into small
helpers to satisfy lint rather than suppressing its complexity/depth checks.
Focused XML/counts: `/tmp/opencode/catalog-n13-focused-xml/`.
Scoped source hashes captured before full verification:
`/tmp/opencode/catalog-n13-source-hashes.json`.
First full XML, counts and failures: `/tmp/opencode/catalog-n13-full-xml/`.
Targeted evolution rerun log: `/tmp/opencode/catalog-n13-evolution-rerun.log`.
Its XML/counts: `/tmp/opencode/catalog-n13-evolution-rerun-xml/`.
Second full log: `/tmp/opencode/catalog-n13-full-rerun.log`.
Second full XML/counts/failures: `/tmp/opencode/catalog-n13-full-rerun-xml/`.

Full command (output `/tmp/opencode/catalog-n13-full.log`):

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```
