# H16: retain raw and expanded golden cells — November 2, 2026

Final step of the approved **H11 → H12 → H13 → H16** sequence. H11 is pushed as
`2b5c7ca`, H12 as `0f83845` and H13 as `abcee21` before this work begins. No
connector edit, driver/native repin, publication, format migration or1.1 write
enablement is assigned. The owner explicitly requested commit/push between steps.

**Status: implemented/verified in scope; pending strict project qualification,
not Complete.**
Strict project qualification remains separate from scoped fixes; parked TIME and
earlier Quack failures are neither investigated nor waived by this change.

## Authoritative boundary

Pinned DuckDB `d54cbbe3df17e8e5ce68312f483b4155836112aa`,
[`result_helper.cpp:543–616`](https://github.com/duckdb/duckdb/blob/d54cbbe3df17e8e5ce68312f483b4155836112aa/test/sqlite/result_helper.cpp#L543-L616),
first accepts either exact raw expected text or an exact keyword-expanded version
of that cell. The alternatives are **per cell**, not two versions of the entire
expected block. Only after those literal checks does it apply regex/numeric/
Boolean rules to the **raw expectation**. Hash recognition and layout happen
before cell expansion. Expected rows are extracted without keyword rewriting
(`sqllogic_test_runner.cpp:1024–1027`). File paths are expanded and then loop-bound;
loaded CSV cells also reach the same raw/expanded literal gate.

The [literal-gate-only C++ witness](h16-golden-keywords-2026-11-02/NativeKeywordLiteralGate.cpp)
passes raw/expanded matching, per-cell mixing, raw short-circuit and control/
coercion boundaries. It is a small policy extract, not an engine build or full
upstream-suite execution. Exact pinned source/hashes and output are under
`build/verification/h16/{upstream/,native-keyword-literal-gate.log}`.

## Genuine red baseline

Before production changes, all34 desired-behavior tests execute on Java17:
**23 failures,11 passing controls, zero errors/skips**. Failures show both:

- false negatives: a real literal `${FOO}` or `{FOO}` is lost when its golden is
  eagerly replaced with `bar`; mixed raw/expanded cells/rows, regex patterns,
  CSV cells, RETURNING and mirror coverage fail;
- false positives: expanded text activates new regex/hash control syntax, numeric
  tolerance/coercion, Boolean-word coercion or extra expected-column boundaries.

SQL constructs literal markers from `chr()` fragments, so SQL keyword substitution
does not silently turn the reproducer into a different value. Owned CSV fixtures
and native queries are valid; no invalid SQL is counted as a desired failure.
One raw exact match unnecessarily reaches the H06 expansion budget before the
repair. Its needed-expansion counterpart remains an explicit resource refusal,
not fabricated successful data. Original evidence: `before.log`,
`before-summary.json`, `before-xml/`.

## Repair and compatibility

Inline expected blocks stay raw. Add an SQL-type-aware comparison overload with
a lazy keyword-expansion callback, preserving the old two-/three-argument public
descriptors and behavior. Validate raw layout/shape first, and recognize raw hash
goldens before any expansion. For each cell, raw equality short-circuits; expanded
text is only an exact alternative. Original regex and numeric/Boolean rules keep
their raw expected input. Scalar BOOLEAN uses its retained SQL provenance and
native1/0 for the expanded literal check; VARCHAR lookalikes are not guessed.
Raw/live row strings and typed hash conversion remain unchanged.

With the callback, comparison tries the given raw expected positions against
the sorted actual cells first, as native does. The existing raw-expected sort
fallback remains; existing overloads retain their sorting semantics. No expanded
expected block is sorted or reparsed into rows, controls or alternative schemas.
Expected-source/input lists and actual SQL-type attachment are preserved.

The driver passes only env/keyword substitution for golden cells, never loop
bindings. Golden-file path resolution still uses env then H13's ordered loop
frames; loaded data uses the per-cell literal alternatives. Labels and explicit
raw hash behavior remain distinct. No query is prepared/re-executed, no genuine
error becomes data, and wrong goldens gain neither oracle nor mirror coverage.
H06 allocation/scan limits still apply when expansion is actually needed.

## Verification

The first post-change invocation stopped at a cell-comparator complexity lint
finding before its test task executed; no test XML is counted for that invocation.
Literal matching was extracted into a small helper, without suppressions or
changed thresholds/assertions. The subsequent Java25 golden/substitution/shadowing
check and lint pass. Three further API-boundary tests cover lazy raw/native
Boolean/hash matches, rejection before callbacks on malformed shape and input/
given-position preservation. No golden, corpus exclusion, skip, comparison
tolerance, dependency pin or write guard is changed to obtain green results.

- **331/331 focused checks pass on Java17 and331/331 on Java25:** all133 SLT
  tests plus198 replay controls, including all37 new H16 cases. Both module lint
  tasks pass, zero failures/errors/skips. Actual task execution and fresh XML are
  verified before counting (`focused-*`, `focused-summary.json`).
- Sealed SLT/replay JAR copies have complete packaged class-name sets and bytes
  matched to the tested output (`verified-artifacts.json`). The
  [public Java witness](h16-golden-keywords-2026-11-02/GoldenKeywordsJvmSmoke.java),
  compiled with `--release17`, passes on actual17.0.2 and25.0.2. It links both old
  comparison descriptors and the new callback overload, checks per-cell mixing/
  callback laziness and rejects expanded controls/coercions without executing SQL.
  Evidence: `public-smoke/`, `public-smoke-summary.json`.
- Full forced verification (44m59s, exit1, `--continue`) passes **1,177/1,177
  catalog +91/91 direct-Java Quack +133/133 SLT; replay291/292**. Its only failed
  test contains the original six parked TIME conversion records. Corpus totals
  remain17,596 passed/six failed/12 original record skips,100 original file
  exclusions and5,189 oracle query passes. All37 new regressions pass; no new
  exclusion, weaker expected result, getter sentinel or comparison tolerance is
  introduced. There are zero JUnit errors/skips; lint, frozen API/wire and Java17
  checks pass. All groups actually execute, without quota/compiler fallback errors.
- Verification uses the committed workspace-temp/compiler profiles and Java17
  direct-Quack launcher. All five frozen code/test/witness hashes in
  `source-hashes.json` remain unchanged, and sealed packaged class-name sets/bytes
  match the completed full run. Exact command/exit, counts, original failure and
  XML are `full.log`, `full-exit.json`, `full-summary.json`, `full-xml/` under the
  evidence root. Earlier Quack failures remain archived and unwaived; this later
  passing run is not a diagnosed fix for their cause.
