# H12: reject unterminated SLT loops — November 2, 2026

Second step of the approved **H11 → H12 → H13 → H16** sequence. H11 is committed
and pushed as `2b5c7ca` before this step begins. No H13/H16 production change,
publication, connector edit, native/driver repin, migration or1.1 write enablement
is assigned. Each verified step is committed/pushed before advancing.

**Status: implemented/verified in scope; pending strict project qualification,
not Complete.**
Full project qualification remains distinct from a scoped repair,
and the owner-parked TIME topic is not reopened.

## Contract and authoritative source

Pinned DuckDB `d54cbbe3df17e8e5ce68312f483b4155836112aa`,
`test/sqlite/sqllogic_test_runner.cpp:1167–1201`, opens loop/foreach scopes and
closes them only on an actual endloop directive. At EOF, lines1412–1414 fail with
`Missing endloop!` when a scope remains open. An empty body or zero iteration
count is not an implicit closing directive.

The loop's commands are owned by that scope until it closes. Text in SQL,
expected rows/errors or comments cannot close it. A directive ignored under
lexical `mode skip` is not an executed endloop. The evidence copies/hashes from
the H11 pinned-source check are retained in `build/verification/h12/upstream/`;
the reference submodule is not updated or built.

The framework reports malformed constructs as `SltUnsupported` rather than
throwing or silently repairing the file. Ordinary unsupported records keep the
pure expander's documented omission behavior, so a valid outside prefix may
still expand; the replay driver already preflights the whole file and rejects
any unsupported construct before oracle/backend effects. This repair must expose
the malformed loop to that existing boundary, not invent an endloop or let the
unclosed body escape as top-level records.

## Before-fix evidence and repair

Tests cover numeric/foreach/concurrent loops, empty and zero-iteration scopes,
nested closing ownership, EOF/newline/CRLF/comment boundaries, endloop text in
SQL/results/errors, lexical skip, conditional wrappers, valid closed controls,
ordinary frontier omission and real native file-write/backend/mirror effects.
No expected value, corpus exclusion or resource ceiling is changed.

The complete corrected Java17 baseline executes **32 tests:23 fail, nine controls
pass, zero errors/skips**. SLT has24 tests/16 failures; replay has eight/seven.
The closed-loop control verifies that the exact COPY fixtures are valid native
SQL and create both owned files. Malformed inputs wrongly create the prefix/body
files and invoke backend/mirror callbacks before the repair; no invalid SQL
fixture is counted as a desired failure.

`parseLoopBody` now returns an explicit private closing-state result. EOF and a
real closing directive at EOF can both produce the same next index, but only the
latter has `closed=true`. An unclosed valid loop/foreach becomes a diagnostic
`SltUnsupported` at its opening line, never a `SltLoop`; its owned body is not
promoted to top-level commands. Concurrent loop frontiers retain the same closure
diagnostic. Malformed-header priority and normal closed-loop provenance/tails
are preserved, with no public AST constructor/descriptor change.

Lexical `mode skip` is applied before recognizing an endloop: native skips every
directive except `mode` there, so an ignored endloop cannot pretend to close a
scope. Balanced skip/unskip with a real closing directive remains valid. Replay
production code is unchanged; its existing unsupported preflight now sees the
correct malformed AST and prevents even prefix/backend effects.

The first baseline invocation had an ambiguous AssertJ vararg lambda in the SLT
test source and did not run those tests. Copied SLT XML was stale H11 output and
is not counted. Its eight replay tests did run: seven genuine unsafe-effect/
preflight failures and one passing closed-loop SQL control. That invocation is
preserved as `before-initial*`; the assertion syntax was repaired without any
production or expected-behavior change. The counted full red baseline above is
`before.log`, `before-summary.json`, `before-xml/`.

## Verification

- **232/232 focused checks pass on Java17 and232/232 on Java25:** all118 SLT
  tests plus114 replay controls, including all32 new H12 regressions. Both module
  lint tasks pass, zero failures/errors/skips. Actual task execution/fresh XML and
  totals are recorded in `focused-*` and `focused-summary.json`.
- Sealed SLT/replay JAR copies have their complete packaged class-name sets and
  bytes matched against the tested output (`verified-artifacts.json`). The
  [public Java caller](h12-loop-termination-2026-11-02/LoopTerminationJvmSmoke.java),
  compiled with `--release17`, passes on actual17.0.2 and25.0.2 against the sealed
  SLT JAR without JDBC. A read-only Java17 census of all948 pristine corpus files,
  including already-excluded sources, finds **zero missing-endloop refusals**.
  Evidence: `public-smoke/` and `public-smoke-summary.json`.
- Full forced verification (44m43s, exit1, `--continue`) passes **1,177/1,177
  catalog +91/91 direct-Java Quack +118/118 SLT; replay243/244**. Its only failed
  test contains the original six parked TIME conversion records. Corpus totals
  remain17,596 passed/six failed/12 original record skips,100 original file
  exclusions and5,189 oracle query passes. No pristine file is newly rejected
  for missing closure; no assertion, source, expected value or skip is weakened.
  There are zero JUnit errors/skips. Lint, frozen API/wire and Java17 checks pass;
  all groups actually run with no quota/compiler fallback interruption.
- Verification uses the committed workspace-temp/compiler profiles and Java17
  direct-Quack launcher. All five frozen source/test/witness hashes still match.
  Original full log/exit/command, counts/failure and XML are `full.log`,
  `full-exit.json`, `full-summary.json`, `full-xml/` under the evidence root.
  Earlier Quack transaction failures remain preserved in their original reports;
  this later91/91 execution is not a diagnosed fix for them.
- No H13/H16 implementation is started before this step's verification, commit
  and push. Replay production class bytes remain identical to H11; this step
  changes parser termination, not SQL execution, adapter gates or public AST shape.
