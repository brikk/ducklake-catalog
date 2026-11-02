# H13: ordered loop replacement frames — November 2, 2026

Third step of the approved **H11 → H12 → H13 → H16** sequence. H11 is pushed as
`2b5c7ca`; H12 is pushed as `0f83845` before this work starts. H12's unpublished
commit was rebased onto the owner's three funding-only commits after a rejected
non-fast-forward push; all tested H12 files were hash-identical and the funding
edits were preserved. No H16 production change or unassigned connector/native/
publication/migration/write work is performed.

**Status: implemented/verified in scope; pending strict project qualification,
not Complete.**
Strict project qualification remains separate from scoped fixes; the parked
TIME topic is not reopened, and earlier Quack failures are not waived.

## Native contracts and genuine red baseline

Pinned DuckDB `d54cbbe3df17e8e5ce68312f483b4155836112aa` defines two different
views of active loop scopes:

- `test/sqlite/sqllogic_test_runner.cpp:348–390`: replacement iterates the ordered
  frame vector outermost-first, keeping repeated iterator names. Each value is
  env-substituted, then the deprecated `${name}` form and `{name}` form are applied
  sequentially. An ordinary outer value consumes the existing placeholder before
  an inner same-name frame reaches it. A later frame may consume a *new* placeholder
  introduced by an earlier replacement; neither a first-wins nor a last-wins map
  is equivalent to this sequence.
- `test/sqlite/sqllogic_command.cpp:296–338`: condition lookup scans all matching
  raw iterator names without breaking, so the innermost same-name value wins.
  Comma iterators retain their complete raw name/value for condition lookup.

Native `StringUtil::Replace` processes matches in its source text, not recursively
inside the newly inserted replacement (`src/common/string_util.cpp:596–629`).
The [policy-only C++ witness](h13-loop-shadowing-2026-11-02/NativeLoopFrames.cpp)
passes ordinary shadowing, inner lookup, fresh-placeholder, dollar/brace order and
comma-frame cases. This is not an engine build or full upstream-suite execution.
Exact source files/hashes and witness output are in
`build/verification/h13/{upstream/,native-frames.log}`.

Before production changes, the **26 desired-behavior regressions execute on
Java17:22 fail, four controls pass, zero errors/skips** (15 SLT:12 failures;
11 replay: ten). Numeric audit rows have the correct count4 but the wrong sum42
instead of6. Golden lookup selects the valid inner99 CSV instead of the valid
outer7 CSV. Real SQL, error-mismatch text, normal goldens and mirror callbacks
expose the inner value where text must use the earlier outer frame. No malformed
SQL fixture or newly invented golden is counted as a desired failure.

## Repair and compatibility

The private expansion/replay binding representation is now an ordered
`List<Pair<String,String>>`. Appending/passing immutable scope lists retains
duplicates and restores the caller's list naturally after an inner loop. Text and
golden-file paths consume every frame in order; guard evaluation gets a derived
innermost-value map using complete raw iterator names. Public parser/AST/expander/
driver and condition-evaluator signatures are unchanged.

Environment precedence/value substitution, both placeholder forms, comma arity,
normal scope exit, AST/source provenance and H06 traversal/text budgets are
preserved. Static AST depth bounds the frame list. H11's per-term guard policy is
retained; numeric inner conditions do not accidentally parse an outer text token.
Expected-error text and inline golden rows are still env-only, never loop-bound;
the separate H16 raw/expanded-golden repair is not performed here.

No SQL is prepared/re-executed to resolve scopes, and no error becomes a value.
There is no new skip, corpus exclusion, golden rewrite, ABI/JSON shape change,
resource ceiling change, driver pin or write/migration enablement.

## Verification

- **258/258 focused checks pass on Java17 and258/258 on Java25:** all133 SLT
  tests plus125 replay controls, including all26 new H13 cases. Both module lint
  tasks pass, zero failures/errors/skips. Actual task execution and fresh XML are
  verified before counting (`focused-*`, `focused-summary.json`).
- Sealed current SLT/replay JAR copies have every packaged class-name set and byte
  matched to the tested output (`verified-artifacts.json`). The
  [public Java witness](h13-loop-shadowing-2026-11-02/LoopShadowingJvmSmoke.java),
  compiled with `--release17`, passes on actual17.0.2 and25.0.2 against the sealed
  SLT JAR without JDBC/native dependencies. It checks inner-condition/outer-text
  behavior and fresh placeholders under both replacement forms. Evidence:
  `public-smoke/`, `public-smoke-summary.json`.
- Full forced verification (44m15s, exit1, `--continue`) passes **1,177/1,177
  catalog +91/91 direct-Java Quack +133/133 SLT; replay254/255**. Its only failed
  test contains the original six parked TIME conversion records. Corpus totals
  remain17,596 passed/six failed/12 original record skips,100 original file
  exclusions and5,189 oracle query passes. All26 new regressions pass; no new
  exclusion, expected-result change or getter sentinel is introduced. There are
  zero JUnit errors/skips; lint, frozen API/wire and Java17 checks pass. All groups
  actually execute, without quota/compiler fallback interruptions.
- Verification uses the committed workspace-temp/compiler profiles and Java17
  direct-Quack launcher. Six frozen code/test/witness hashes in `source-hashes.json`
  remain unchanged. Exact command/exit, original failure/counts and XML are
  `full.log`, `full-exit.json`, `full-summary.json`, `full-xml/`. Earlier Quack
  transaction failures remain preserved; this later91/91 run is not a diagnosed
  fix for them. No H16 implementation starts before this step's commit and push.

The original counted red log/XML/counts are `before.log`, `before-xml/` and
`before-summary.json`. No corpus source, exclusion or expected result is rewritten
to obtain green tests; the existing strict failures remain visible.
