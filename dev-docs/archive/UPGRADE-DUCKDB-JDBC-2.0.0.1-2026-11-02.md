# DuckDB JDBC2.0.0.1 adoption — November 2, 2026

**Status: adopted/verified in scope; strict project qualification remains blocked,
not Complete.** User explicitly approved this patch adoption. No push, publication, connector edit, native extension
repin, schema migration or1.1 write enablement is authorized.

## This is the crash we reported

The original upstream item [#728](https://github.com/duckdb/duckdb-java/pull/728)
is an older PR, not an issue we opened. The owner's **apatrida** comments report
and then clarify our reproducer:

- [October31 clarification](https://github.com/duckdb/duckdb-java/pull/728#issuecomment-6539143425):
  JDBC `ResultSet.getString()` on `[row(1,'x'),NULL]` crashes JDBC2.0.0.0 with
  native SIGSEGV. Scalar TUPLE, getObject(), explicit VARCHAR CAST and the other
  listed controls did not establish that crash. This was not Java `toString()`.
- Scalar VARIANT text mismatch was a related observation, not a second confirmed
  native crash.
- [Fix#951](https://github.com/duckdb/duckdb-java/pull/951) explicitly says to see
  the problem description in #728's comments, and is included in released2.0.0.1.

Raw issue/PR/comments are preserved in `build/verification/jdbc-2.0.0.1/`.

## Provenance and scope

The release is published on GitHub and Maven Central. Both downloaded hashes match
Central's independently requested `.sha256` files:

| Artifact | SHA256 |
|---|---|
| `duckdb_jdbc-2.0.0.1.jar` | `6cb8b0e0fc51b2e45aead2d2c1e1fd09dd10267db2966e69aaab0de67319f826` |
| `duckdb_jdbc-2.0.0.1-sources.jar` | `7c984be48b3f9f6f9cec82e0359f86719f0234342989bd4c1321cf6bdbba219d` |

The manifest identifies2.0.0.1. All94 runtime classes are major52 or lower, with
actual Java17/25 qualification still required. Source changes are TUPLE metadata/
typed-object handling, preserving original vectors during fallback casts, macOS
CI version handling and exported native symbol lists. The native core pin is not
changed by the release. The exact tag diff is recorded in the preceding
`build/verification/upstream-2026-11-02/jdbc-delta.json` check.

Core remains2.0.0/`d54cbbe3df`; DuckLake `417a9117f5`, native Quack `f96420ed46`,
Quack JDBC0.12.0, PostgreSQL/MySQL extensions, CLI, pristine corpus and isolated
legacy JDBC1.5.6.0 producer stay unchanged. The new pluggable SQLite profile on
DuckLake main is not adopted. Existing published consumer candidates remain their
original bytes/dependencies; there is no implicit Trino/Doris/default promotion.

## Reproduction before adoption

The canonical public-JDBC [child probe](../../ducklake-test-corpus-replay/test/src/dev/brikk/ducklake/corpus/JdbcResultProbe.java)
is run only in isolated JVMs, with bounded time/heap, crash logs and coredumps
disabled. On actual Java17.0.2 and unchanged JDBC2.0.0.0:

- The precise tuple-list getter crashes natively, as reported.
- TUPLE is not typed as JDBC STRUCT/DuckDBStruct.
- Nested BIGNUM getter text disagrees with the independent native VARCHAR column.
- Scalar TUPLE, a named STRUCT with `element1`/`element2` fields, NULL and MAP
  controls pass. No malformed SQL fixture is counted as desired failure evidence.

The same four source-level cases already pass against the downloaded2.0.0.1 JAR,
including35 combinations of recent scalar types and nested containers. This is
standalone artifact evidence, not a substitute for repository qualification.

Before the version pin changes, the15 permanent desired-behavior tests execute on
Java17: **13 fail, two controls pass, zero errors/skips**. This includes the native
crash in its child (the Gradle test worker survives), metadata/runtime identity,
fallback getters and tuple golden rendering. Source/object output is not accepted
as a new golden; tuple parentheses, named-struct braces and NULL distinctions have
independent native/literal expectations.

## Pin-only compatibility boundary

With the exact2.0.0.1 pin/SHA but unchanged production renderer, all five native
driver/provenance regressions pass. The16-test pin-only run still has **ten renderer
failures and six passes**, zero errors/skips. TUPLEs now arrive as typed
`DuckDBStruct`, exposing our old assumption that every such object is a named
STRUCT. It emits `{'element1': ...}` instead of `(…)`; geometry inside a tuple can
also become BLOB text. The tuple/native-text expectations are not changed.

The compatibility change renders public tuple objects positionally using their
actual SQL type, not synthetic field names. Typed cell reading uses the qualified
native string getter for tuple-containing result types, preserving native tuple
text and geometry WKT without any SQL re-execution. Named structs, VARCHAR
lookalikes, scalar temporal rendering and logical SQL metadata for hashing/shape
remain distinct. This path relies on the new driver; consumers adopting a newly
published replay artifact must not force it back to the known-unsafe2.0.0.0 driver.

## Focused verification

- **191/191 checks pass on Java17 and191/191 on Java25:** all80 SLT tests plus111
  selected replay tests, zero failures/errors/skips. This includes all20 new
  adoption cases, H14 single-execution/RETURNING, H06 resources/timeouts, golden
  integrity, geometry, floating text, typed Boolean hashes and conversion-failure
  controls. Both module lint tasks pass. The tests actually execute; final logs,
  fresh XML and counts are in `focused-final-*` and `focused-final-summary.json`.
- The new tuple tests cover scalar/nested tuples, LIST/fixed ARRAY/MAP keys and
  values/STRUCT/UNION containers, NULLs, text lookalikes, genuine named STRUCTs
  with tuple-like field names, native geometry WKT, a rejected wrong golden and
  tuple RETURNING with exact single-mutation effects. Dangerous parent-process
  tuple getter tests fail safely if the required patch dependency is reverted;
  the original native crash remains exclusively in its bounded child witness.
- The canonical standard-JDBC source probe passes all four modes on actual
  **17.0.2 and25.0.2**: eight isolated processes,41 getter expressions checked per
  JVM (including35 recent-type/container combinations), three getter repetitions
  each, plus independent typed-object metadata/attributes/NULL checks. The
  snapshots compare binary content as well as structure. Native core identity is
  checked in every child. No crash occurs; `standalone-final-summary.json` and
  per-process logs retain the evidence.
- The first focused invocation stopped at a one-line digest-loop lint finding
  before either test task executed. Its automatically copied XML was stale from
  earlier stages and is explicitly **not verification of that run**. The original
  log/XML and `focused-initial-interruption.json` remain. The loop was formatted
  normally; no assertion or lint policy changed. Final collection checks actual
  task execution and XML modification time before counting results.

## First full qualification and unchanged rerun

The first forced full check (48m59s, exit1, `--continue`) passes **1,177/1,177
catalog +91/91 direct-Java Quack +80/80 SLT**, but replay is **225/228**, not the
expected parked-baseline-only result. In addition to identity control's unchanged
six TIME records, two `TestUpstreamFinal11Evolution` Quack-backed mirrors fail:

- `alter/struct_in_list_evolution.test:103`, an inline-table snapshot-visibility probe;
- `alter/struct_evolution.test:49`, an `information_schema.columns` probe.

Both report `Current transaction is aborted (please ROLLBACK)`. These extra
failures are **not waived or relabeled as the TIME baseline**. The same symptom
in this evolution class was recorded before this adoption in the
[N13 report](FIX-N13-COMMIT-MESSAGE-2026-10-30.md), but that history does not establish
the cause of these new observations or prove this patch harmless. No root-cause
or universal-stability claim is made. Original full XML/failures/log/command are
retained in `full-xml/`, `full-summary.json`, `full.log` and `full-exit.json`.

The source/test/pin hashes captured before that invocation all match afterwards.
The original corpus remains17,596 passed/six failed/12 record skips and100 file
exclusions; no new exclusion or changed expected value is introduced. Verification
uses the committed workspace-temp/compiler profiles and Java17 direct-Quack
launcher, without quota/compiler fallback errors.

All **eight unchanged targeted evolution cases pass**, zero failures/errors/skips
(`evolution-repeat.log`, `evolution-repeat-summary.json`, `evolution-repeat-xml/`).
A second forced full run is complete. Source is frozen throughout, and no
assertion, fixture, timeout, skip, native pin or production behavior is altered
to obtain a passing rerun.

The second full invocation (46m31s, exit1, `--continue`) passes **1,177/1,177
catalog +91/91 direct-Java Quack +80/80 SLT; replay227/228**. Its only failed
test is identity control's original six TIME24 conversion failures, at
`data_inlining/data_inlining_types.test:36/46/53` and `types/all_types.test:32/42/49`.
Corpus totals remain17,596 passed/six failed/12 original record skips,100 original
file exclusions and5,189 oracle query passes. All20 new regressions pass, no new
golden mismatch/exclusion appears, and there are zero JUnit errors/skips.
Lint, frozen API/wire and Java17 checks pass (7,845 classes across17 artifacts).
All groups actually execute, without quota/compiler fallback interruptions.
The eight source/test/pin hashes still match their pre-verification values.
Exact second command/exit, counts, preserved failures and XML are in
`full-repeat-exit.json`, `full-repeat-summary.json`, `full-repeat.log` and
`full-repeat-xml/`. The first run's two evolution failures remain original evidence;
the passing later run is not a diagnosed fix or a general stability claim.

The exact current0.13.0-SNAPSHOT replay/SLT JARs are sealed in
`verified-artifacts/`: every packaged class and the complete class-name set match
the compiled output used by the tests. The [public Java caller](jdbc-2.0.0.1-2026-11-02/TupleRenderingJvmSmoke.java),
compiled with `--release17`, passes on actual17.0.2 and25.0.2 against those copies,
the exact2.0.0.1 driver and stdlib. It checks seven typed object/text cases
(including FLOAT/DOUBLE portability and named-STRUCT lookalikes) plus native WKT
inside a UNION/TUPLE through `readRows`. Evidence is `public-smoke/`,
`public-smoke-summary.json` and `verified-artifacts.json`; replay SHA256 is
`6ef55ef865eac40477b1facd0d5dba2cdf1f3ae64eb5145056652416a6181161`.
These are local verification copies, not publication or consumer adoption.
The sealed class-name sets/bytes also match the completed second full run.
Read-only [classpath verification](jdbc-2.0.0.1-2026-11-02/verify_classpaths.init.gradle)
confirms exactly2.0.0.1 in catalog/replay test runtimes and the replay runtime;
the separate legacy producer remains1.5.6.0, and the direct-Java Quack runtime
contains no embedded DuckDB JDBC. The resolved-file proof is `classpaths.log`.
No parked topic is investigated as part of this adoption. The six TIME24 failures
stay strict; no skip, sentinel, private native reflection, SQL re-execution or
exception-to-value recovery is introduced.
