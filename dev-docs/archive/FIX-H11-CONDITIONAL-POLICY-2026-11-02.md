# H11: per-term SLT conditional policy — November 2, 2026

First step of the approved **H11 → H12 → H13 → H16** sequence. The owner requested
a verified commit/push between steps. The preceding H14/H06/JDBC commits were
pushed through `0b66e6b` before this work began. No publication, connector edit,
driver/native repin, new format admission or1.1 write enablement is assigned.

**Status: implemented/verified in scope; pending strict project qualification,
not Complete.** H12/H13/H16
production behavior is unchanged. Strict project qualification remains separate
from a passing scoped repair; the owner-parked TIME topic is not reopened.

## Authoritative policy and before-fix evidence

Pinned DuckDB core source is
[`d54cbbe3df17e8e5ce68312f483b4155836112aa`](https://github.com/duckdb/duckdb/tree/d54cbbe3df17e8e5ce68312f483b4155836112aa).
`test/sqlite/sqllogic_test_runner.cpp:723–775` parses every `&&` term and stores
the directive's `skip_if` flag on each condition. Then
[`sqllogic_command.cpp:286–358`](https://github.com/duckdb/duckdb/blob/d54cbbe3df17e8e5ce68312f483b4155836112aa/test/sqlite/sqllogic_command.cpp#L286-L358)
checks each term independently:

| Predicate terms | `skipif` runs | `onlyif` runs |
|---|---|---|
| false, false | yes | no |
| false, true | no | no |
| true, false | no | no |
| true, true | no | yes |

Thus `skipif i=0&&i=1` excludes both0 and1 iterations; it is **not** negation of
the aggregate conjunction. The old implementation ran both. The
[policy-only C++ witness](h11-conditional-policy-2026-11-02/NativeConditionPolicy.cpp)
passes all four explicit truth rows for both policies. It is a standalone extract
of the policy branch, not a new DuckDB build or full upstream-suite execution.
Source hashes, exact downloaded files and CSV output are retained under
`build/verification/h11/{upstream/,native-truth-table.csv}`.

Before production changes, all22 new desired-behavior tests execute on Java17:
**17 failures, five passing controls, zero errors/skips** (14 SLT:11 failures;
eight replay: six failures). All failures are genuine policy/side-effect/coverage
or validation differences, not malformed SQL fixtures. Real audit queries observe
two inserts where none are allowed, three where only one is allowed, and extra
out-of-range values. A missing binding that should be unreachable crashes a file;
queries that should not execute inflate oracle/mirror coverage.

## Repair and compatibility

- Preserve public `SltConditions.evaluate(expr, engine, bindings)` and its positive
  conjunction behavior. Add `shouldRun(..., skipIf)` for actual directive policy;
  both the pure expander and replay driver call the same implementation.
- Apply skip/only policy per term. Retain case-insensitive system guards, textual
  equality/inequality, numeric relational comparisons, nested guarded loops and
  stacked directives' distinct policies.
- Validate every term's syntax before dynamic evaluation, as native parsing does,
  but look up only bindings reached by the runtime policy. A malformed later term
  is still an error; an unreachable missing loop binding is not consulted. Reached
  missing bindings still fail rather than becoming false or synthetic data.
- Syntax validation uses a separate streaming pass, never an unbounded list of
  parsed terms. H06 source/AST/scanning budgets, watchdog cancellation/quarantine
  and immutable progress remain in force.
- No golden, corpus source/exclusion, conversion sentinel, native/private
  reflection, SQL retry, adapter contract, mutation or migration guard is changed.
  Legitimate guarded-record skips follow native policy; they are not new corpus
  exclusions or permission to hide existing failures.

## Verification

- **200/200 focused checks pass on Java17 and200/200 on Java25:** all94 SLT tests
  plus106 replay controls, including all22 new H11 cases. Both module lint tasks
  pass, with zero failures/errors/skips. Actual task execution and fresh XML are
  checked before counting; logs/XML and totals are `focused-*` and
  `focused-summary.json` under `build/verification/h11/`.
- The complete packaged class sets/bytes of the sealed SLT/replay JAR copies match
  the output used by those tests (`verified-artifacts.json`). The
  [public Java witness](h11-conditional-policy-2026-11-02/ConditionalPolicyJvmSmoke.java),
  compiled with `--release17`, passes on actual17.0.2 and25.0.2 against the sealed
  SLT JAR. The old positive `evaluate` descriptor/semantics and new `shouldRun`
  truth tables, system policy, unreachable bindings and syntax failures are checked
  without JDBC/native dependencies. Evidence: `public-smoke/` and
  `public-smoke-summary.json`.
- Full forced verification (47m31s, exit1, `--continue`) passes1,177/1,177 catalog
  and94/94 SLT; replay235/236 retains only identity control's six parked TIME
  records. Corpus17,596 passed/six failed/12 original record skips,100 original
  file exclusions and5,189 oracle query passes are unchanged. Seven code/test/
  witness hashes still match their pre-verification values. Lint, frozen API/wire
  and Java17 checks pass, with no quota/compiler fallback interruption.
- That invocation additionally finishes direct-Java Quack **90/91**:
  `TestFinal11ReadPreview.aPinnedReaderSurvivesNativeInlineFlushWithoutMissingOrDuplicatingRows`
  fails at its inline-registry query with `Current transaction is aborted (please
  ROLLBACK)`. Its catalog-only dependency graph does not include the H11 SLT/replay
  code, but the cause is not diagnosed. The original failure is preserved, not
  relabeled as TIME baseline or an accepted flake. Full log/exit/counts/XML are
  `full.log`, `full-exit.json`, `full-summary.json` and `full-xml/`.
- The unchanged complete Quack repeat passes **91/91**, zero failures/errors/skips
  (`quack-repeat.log`, `quack-repeat-summary.json`, `quack-repeat-xml/`). This is
  a passing later execution, not a diagnosed fix for the first failure. No H12 source change,
  assertion/fixture/timeout relaxation, new skip or native pin change is made to
  obtain a passing repeat. This first full run must not be described as having
  only the parked strict baseline.
  The sealed SLT/replay class-name sets and bytes also match the completed full run;
  all seven frozen code/test/witness hashes remain unchanged.

Raw red evidence is `before.log`, `before-summary.json` and `before-xml/` under
`build/verification/h11/`. The pinned sqllogictest source files are evidence only;
no reference submodule was checked out or updated. Existing strict failure
boundaries remain unchanged until verified otherwise; no failure is waived.
