# N19: type-aware Boolean result hashes — November 1, 2026

Final step of the approved N03 → H15 → N19 batch. N03 is committed as `3f663b5`
with separate Trino/Doris handoffs; H15 is committed as `6585ef1`. Neither is
pushed, and no push/publication is authorized for this batch.

**Status: implemented/verified in scope; pending strict project qualification,
not Complete.** Existing project
qualification boundaries remain unchanged. No parked upstream topic is reopened.

## Reproduction and authoritative conversion

The desired-behavior baseline runs **12 tests: eight fail, four controls pass**,
zero JUnit errors/skips. Failures include incorrect scalar Boolean hashes, mixed
columns under all sort modes, wrong label equality between BOOLEAN and VARCHAR,
and acceptance of the old Java-spelling hash for a native Boolean. VARCHAR, nested
Boolean text, empty-result and raw/live-row controls pass before the repair.

Pinned native [`test/sqlite/result_helper.cpp`](https://github.com/duckdb/duckdb/blob/d54cbbe3df17e8e5ce68312f483b4155836112aa/test/sqlite/result_helper.cpp#L468-L512)
(`d54cbbe3df17e8e5ce68312f483b4155836112aa`)
uses `SQLLogicTestConvertValue` to convert **scalar logical BOOLEAN** into `1`/`0`
before sorting and hashing. Other types use their native text; NULL/empty/NUL retain
the established runner encoding. This rule is based on SQL type, not the SLT type
letter or a heuristic over strings such as `true`/`false`.

Expected MD5s are independently calculated from explicit native cell sequences
plus LF. For example `1\n` hashes to `b026324c6904b2a9cb4b88d6d61c81d1`, whereas
literal VARCHAR `true\n` hashes to `74d9a83219cabaab06a69fd318873f33`. Mixed rows
explicitly retain VARCHAR lookalikes while normalizing only the Boolean column.

## Repair and compatibility

- Add `resultHash(query, rows, sqlTypes)`, preserving the existing two-argument
  descriptor and raw-text semantics. Without metadata, Boolean provenance cannot
  safely be inferred from strings or the query's placeholder letters.
- Normalize only scalar BOOLEAN/BOOL columns using the existing Boolean parser,
  before NOSORT/ROWSORT/VALUESORT. Nested values, VARCHAR, NULL and raw input lists
  are not rewritten or mutated. Invalid metadata width or Boolean payload fails
  explicitly rather than hashing an apparent successful value.
- Feed the already-retained JDBC SQL types into both explicit hash-golden comparison
  and labelled-result hashing. Label width checks remain based on actual metadata,
  including labelled placeholder declarations. Invalid hash input becomes a record
  mismatch/failure, not a poisoned label or whole-file crash.
- Raw/live rendering, structured mirror comparison and query execution remain
  unchanged. Wrong goldens do not become oracle/mirror coverage; later valid rows
  still mirror in their original representation. No SQL is re-executed.

## Verification

- Focused Boolean/hash/label/coverage/conversion/floating controls: **77/77 pass on
  Java17 and77/77 on Java25**, including all18 new N19 tests, zero failures/errors/
  skips. Lint passes. Both genuine hash mismatches and false-positive regressions
  are checked; only the correct marked query reaches the control mirror.
- [Public Java descriptor/hash witness](n19-boolean-hashes-2026-11-01/BooleanHashJvmSmoke.java):
  **passes on actual17.0.2,21.0.2,24.0.2 and25.0.2**, compiled with `--release17`.
  Typed/untyped descriptors, fixed independent hashes, all sort modes, invalid
  Boolean rejection and raw-input preservation pass without JDBC query execution.
- Full forced verification: **1,177/1,177 catalog +91/91 direct-Java +51/51 SLT
  pass; replay185/186**, with only the unchanged parked strict baseline. Corpus
  remains **17,596 passed /six failed /12 original record skips**, all100 original
  file exclusions unchanged. Zero JUnit errors/skips. All groups actually executed;
  lint, frozen API/wire and Java17 checks pass. Duration **43m47s**, exit1 (`--continue`).
  All five scoped source/proof hashes captured beforehand still match. Pinned
  corpus/read-fixture preparation succeeds, with no quota/compiler-fallback errors.
  No native acceptance gate was reopened or waived.
- No dependency/corpus/driver/native pin, SQL value, DTO/JSON, write/migration guard,
  skip, sentinel or golden change. Connector code/adoption is not performed here.

Evidence: workspace `build/verification/n19/before{.log,-xml/}` is the counted red
baseline; per-JVM focused logs/XML are beside it. Verification reuses the committed
workspace-temp and compiler profiles from H15, rather than deleting other temporary
files or repeating the exhausted tmpfs configuration.
Completed focused counts are in `build/verification/n19/focused-summary.json`;
public Java API compilation/runtime logs are in `jvm-api/`. Full output and the
exact command/exit record are `full.log` and `full-exit.json`; the command/environment
is the same workspace-temp, fresh-compilation profile preserved in the H15 report.
Source hashes are in `build/verification/n19/source-hashes.json`.

Public caller guidance is in [typed corpus hashes](../../docs/CORPUS-HASHES.md).
A read-only Kotlin/Java source search found no direct `resultHash` callers in the
two owned connector repositories at inspection time. Normal framework use supplies
metadata automatically; exact replay artifact adoption and relevant consumer tests
are still separate from library verification and are not performed here.

Full XML/counts/failures: `build/verification/n19/full-xml/`.
The combined batch receipt/artifact copies are under
`build/verification/read-correctness-handoff/`; the resolved source commit is added
after commit. Final replay JAR SHA256 is
`bd97eba965f2234faa75cf27a788c45a70d4f030115e51b7506104267a9899b1`.
Copied-JAR hash, float and type-signature API witnesses pass on Java17.0.2/25.0.2.
Catalog/main-test-fixture hashes still match the original N03 handoff; numeric class
bytes and MIT notice are identical to the H15 sealed replay JAR, so its native100K
evidence is reused for unchanged code, not described as a new execution.

This is a scoped build receipt, not Maven publication, plugin qualification or
permission to deploy the corrected type grammar without coordinated consumer work.
