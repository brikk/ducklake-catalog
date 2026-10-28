# Harness integrity repairs — October 28, 2026

Requested order: **H02 → H03 → H04**, based on the reproduced P1 witnesses in
[the dated triage](TRIAGE-2026-10-28.md). Base: `33cdbed`, with the previous repair
batch already complete. These are repairs to verification, not new golden answers.

**Complete and fully verified.** Separate implementation commits, in requested order:

- H02: `4f2499e` — preserve SQL types and result shape in golden comparisons.
- H03: `b1805b9` — compare live mirrors as structured nullable row multisets.
- H04: `85ecb7e` — retain completed outcomes and quarantine timed-out workers.

These commits are not pushed. No publication, sibling edits, pin change or consumer
adoption is implied or authorized. Completed entries are removed from the active
queue; 16 reproduced defects and three delivery-control gaps remain.

## H02 — retain types and shape through golden validation

- Carry JDBC type names/count into golden comparison, including empty SELECT and
  hash results. Update counts have one BIGINT column. Keep genuine JDBC NOTHING
  separate from a typed zero-row result set. Non-result commands retain the existing
  single-letter placeholder convention and accept only empty/zero-hash expectations;
  no native schema is invented for an absent JDBC result set.
- Check ordinary-query actual/expected width against the declaration before
  sorting/hashing. Labels still ignore ordinary expected text and use placeholder
  letters as upstream specifies. Record their actual first-result width alongside
  its hash and reject changed width on later occurrences, including empty results.
  Both tabular and legacy value-per-line goldens remain.
- Use exact BigDecimal numerical equality for integer/DECIMAL SQL types; do not
  round adjacent BIGINT/HUGEINT values via Double or accept them under floating
  tolerance because the declaration used I/R/T. Equivalent scale/spelling is allowed.
  Retain floating tolerance for actual SQL real types only.
- Boolean aliases require actual SQL BOOLEAN metadata. VARCHAR `true`, `false`,
  number-like strings and case variants stay text, regardless of type letter.
- VALUESORT carries actual SQL types with cells; ROWSORT compares structured cell
  boundaries, avoiding ambiguous ordering of separator-containing rows.
- Preserve the old comparator descriptor, but without unsafe inferred Boolean types:
  untyped I exact / R tolerant / T literal. A new typed overload supplies metadata
  and empty-result arity. The existing Boolean unit contract now supplies its actual
  BOOLEAN metadata; no golden/expected Boolean semantics were changed.

## H03 — live nullable row multiset equality

The mirror compares maps from structured nullable rows to occurrence counts. It
does not map null/empty values to golden tokens or serialize cells with a separator.
Ordering is ignored, duplicate multiplicity is preserved, and literal token strings,
SQL NULL, empty text, separators and column boundaries remain distinct. Typed geometry
normalization still runs before equality; BLOB and other values are unchanged.

The engine interface is unchanged. An engine returning no rows still cannot expose
column metadata through that row-only interface; this repair does not invent an
empty-schema guarantee. Golden hash serialization and N19 Boolean hashing remain
separate from live mirror equality.

## H04 — frozen progress and conservative worker quarantine

Each file has a synchronized progress journal. Completed outcomes are snapshotted
on normal/crash completion; the watchdog freezes a stable prefix before cancellation,
so a late worker cannot change the returned report. Earlier failures remain failures.
Cancellation is checked between records and before post-callback SQL/mirror work.

The watchdog interrupts its owned FutureTask worker and joins for up to one second.
Cooperative callbacks exit and the oracle is closed on its owning thread. An
unresponsive backend/driver may ignore interruption; no cross-thread engine or oracle
close is attempted. The runner retains worker ownership, reports whether it stopped,
and BLOCKS all later files and subsequent runs, including after eventual worker exit.
Fresh drivers alone do not isolate the shared engine/rewriter/backend. Caller
interruption follows the same cancellation/quarantine path and restores the interrupt
flag before propagating. Timeout-as-skip policy is unchanged; it no longer erases
earlier outcomes or silently continues against shared state.

This is not a claim of universal hard termination or recoverable per-file isolation.
Callers must not reuse/close active shared state through a different runner. Continuing
safely after arbitrary native hangs would require separately isolated processes and
backends. See [the compatibility contract](../../docs/COMPATIBILITY.md).

## Red/green evidence

- Before production fixes, all **5 H02 regression groups failed** through the real
  JDBC/driver route (integer precision, DECIMAL, VARCHAR Boolean coercion, shape,
  label/hash bypass).
- H03: **2 of 4 groups failed** against independently supplied wrong mirror rows;
  multiset order/duplicate controls already passed. Wrong NULL/token and separator
  cells were accepted before the repair.
- H04: **2 of 3 initial groups failed**; normal/disabled-watchdog control passed.
  Tests use latch-controlled callbacks, release and join every disposable worker,
  and cover both interruptible and interruption-resistant execution.
- Initial focused suite: **55 pass**, zero failures/errors/skips; replay lint passes.
  Includes 10 H02 groups, 4 H03 groups, 7 H04 groups, existing golden/substitution
  controls, and all 6 real geometry normalization/mirror controls. Additional H04
  controls exercise engine ownership, crash prefix retention, caller interruption,
  stable freeze, blocking later files and blocking later runner reuse.
- Actual **Java 17.0.2**: the [standalone witness](harness-repairs-2026-10-28/Java17HarnessIntegrity.java)
  compiles with `javac --release 17` and passes for all three repairs against current
  compiled classes. This is local harness evidence, not connector certification.
- First forced full run: **failed** after 40m30s, with 12 corpus shape errors. The
  other 124 replay tests, 1,111 catalog tests, 51 SLT tests and 86 direct-Java tests
  passed. No result was waived, skipped, or made into a new golden. Nine errors
  exposed upstream's legitimate labelled all-types placeholder declarations; the
  other three exposed genuine no-result commands incorrectly treated as typed
  empty result sets.
  The [pinned upstream helper](https://github.com/duckdb/duckdb/blob/804b3f2070e3e604f1bcc7a1fbbf9f9ebc2fafbd/test/sqlite/result_helper.cpp)
  checks declaration width only outside hash/label mode. The repair now preserves
  labelled placeholders while checking later actual shape; ordinary/hash arity
  enforcement is retained. Two additional regression groups cover label placeholders
  and non-result commands, including reshaped labels with an identical scalar hash,
  incorrect command placeholder width and nonempty command goldens. Typed empty
  SELECTs are not exempted from declaration checks.
- A subsequent full-corpus focused trial retained 57 passing controls but **4 corpus
  failures**: the prepared-metadata path changed snapshot counts and metadata log
  counts for side-effecting table functions. That approach was rejected, not
  rebaselined. Final execution remains direct/single-shot; a non-result marker
  handles commands with no JDBC result set. A new productive native flush regression
  checks exact before/after snapshot counts so metadata capture cannot add bindings.
- Corrected final focused/corpus suite: **59 pass** (58 controls plus identity),
  zero JUnit failures/errors/skips; replay lint passes. All **17,602 corpus records**
  pass, with the original 100 file / 12 record exclusions unchanged. H02 now has
  13 focused groups including the single-binding native state check. Final
  Java17.0.2 witnesses also pass against this direct-execution implementation.
- Final forced qualification: **1,290 maintained tests** (1,111 catalog / 51 SLT /
  128 replay), **86 separate direct-Java tests** and **17,602 corpus records** pass.
  Zero JUnit failures/errors/skips. Original 100 file / 12 record exclusions remain
  unchanged. Lint, frozen compatibility/wire and Java17 checks pass (7,843 classes /
  17 artifacts). Final full run: **40m29s**, on the exact eleven source/test files
  hashed before the run and checked afterward. The three implementation commits
  contain that tested final code. Fixture preparation succeeded before qualification.
  Original goldens, skips/exclusions, pins and native gates are untouched. Other
  defects, notably H05 coverage, H09 conversion sentinels, H14 DML RETURNING and N19
  Boolean hash canonicalization, are not claimed fixed. No stricter native gate or
  connector artifact adoption was rerun or waived.

Final command:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-daemon --no-configuration-cache
```

Evidence under `/tmp/opencode/`: `catalog-h02-red{.log,-xml/}`,
`catalog-h03-red{.log,-xml/}`, `catalog-h02-h03-green-h04-red{.log,-xml/}`,
`catalog-h02-h04-focused-final.log`, `catalog-h02-h04-focused-xml/`,
`catalog-h02-h04-java17/`, `catalog-h02-h04-source-hashes.json` and
`catalog-h02-h04-full.log`. The first full failure is preserved in
`catalog-h02-h04-full-red{.log,-xml/}`; corrected corpus/focused checks are recorded
in `catalog-h02-h04-corpus-focused-final.log` and
`catalog-h02-h04-prepared-trial-xml/`. The corrected direct-execution replay is in
`catalog-h02-h04-corpus-direct-final.log` and `catalog-h02-h04-corpus-direct-xml/`.
Final witnesses and the forced full rerun use `catalog-h02-h04-java17-final/` and
`catalog-h02-h04-full-final{.log,-xml/}` (with machine-readable summary). The final
eleven-file hash manifest replaces the first-run manifest (preserved as
`catalog-h02-h04-first-full-source-hashes.json`). Post-commit checks confirm the
eleven committed source/test hashes match the tested bytes, all 85 local links
resolve, and no workflow, pin, original corpus or catalog/SLT implementation changed;
see `catalog-h02-h04-verification.json`.
