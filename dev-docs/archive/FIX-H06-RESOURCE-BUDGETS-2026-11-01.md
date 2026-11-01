# H06: bounded SLT preparation and expansion — November 1, 2026

**Status: implemented/verified in scope; strict project qualification pending,
not Complete.** The latest full run retains only the unchanged parked strict baseline.
Second step of the approved H14 → H06 batch. No push, publication, connector edit,
default-lane promotion, dependency repin or unrelated parser semantic fix is assigned.
H11/H12/H13/H16 remain separate findings.
H14 is committed locally as `afaffcc`; no commit has been pushed.

## Reproduction

[ResourceProbe.java](h06-resource-budgets-2026-11-01/ResourceProbe.java) uses only
the existing public parser/model/expander API. Each hostile case runs in its own
Java17 process with **`-Xmx32m -Xss256k`**, bounded externally by a process timeout.
Against the original source:

- A tiny billion-iteration header exhausts the heap in eager `loopValues`.
-20,000 stacked conditionals overflow the parser stack.
- A1,000 ×1,000 nested loop product exhausts expansion memory.
- A cyclic hand-built loop AST overflows the expander stack.
- A3,000-character template referencing a100,000-character env value exhausts the
  heap while building a100-million-character SQL string.

All five staged post-fix witnesses explicitly refuse and exit0 under the same
heap/stack bounds. No OutOfMemoryError/StackOverflowError is caught and disguised
as a successful refusal. Permanent forked regressions likewise keep catastrophic
witnesses out of the Gradle test worker's heap.

## Design

The [resource contract](../../docs/CORPUS-RESOURCE-LIMITS.md) lists fixed defaults
and caller-visible refusal behavior. Parser guards precede line/token materializing,
recursive descent, numeric-range mapping, foreach insertion and comma splitting.
A bounded AST validation pass independently protects public model constructors,
computes conservative nested expansion costs, counts empty iterations, and checks
structure even under false guards or zero-iteration loops.

Substitution shares append-before-allocation checks between the pure expander and
the native replay oracle. It bounds both individual/intermediate text and cumulative
allocation/output/scanning, preserving existing substitution order, unknown variables,
comma semantics and fresh per-occurrence UUIDs. Loop-name shadowing behavior is not
changed here. Existing condition policy is unchanged; condition splitting becomes
lazy/bounded to avoid unneeded eager lists.

Source reading is bounded and moved into the same preparation/replay watchdog.
Controlled cooperative/unresponsive preparation tests verify cancellation, joining,
frozen progress and quarantine; existing timeout integrity controls remain required.
Static refusal executes no prefix. Dynamic exhaustion retains previously completed
failures, passes and skips, and does not become a SQL value or coverage pass.

## Verification status

All948 pristine corpus files pass a read-only parser-budget census with **zero
resource refusals**, including already-curated-out files. Maxima:22,382 characters,
785 lines,156 records, nesting3,512 loop values,1,446 expanded visits. The
[census source](h06-resource-budgets-2026-11-01/CorpusBudgetCensus.java) is archived.
No new exclusion, relaxed assertion, changed golden or parked native workaround
is introduced.

The staged source was transferred hash-identically into the production/test trees
after the H14 commit. Repository checks pass **117/117 on Java17 and117/117 on
Java25**: all80 SLT tests and37 replay tests. These include all38 new H06
regressions, the five32MiB/256KiB forked witnesses on each JVM, all13 H14 cases,
the eight golden-integrity/single-bind controls and the seven existing timeout
integrity controls. Both module lint tasks pass, with zero failures/errors/skips.
Actual per-module XML/logs are in `focused-java{17,25}{.log,-xml/}` and
`focused-summary.json`. The archived [Java17 launcher](h06-resource-budgets-2026-11-01/verify_java17.init.gradle)
covers both affected modules and their forked tests.

The isolated staging build had passed117 tests on Java25 (116 before the final
marker-collision control, also on Java17); that scratch evidence is not substituted
for the repository checks above. Source transfer fingerprints are retained in
`build/verification/h06/{integration-source-hashes,source-hashes}.json`.
Full forced verification (43m50s, exit1, `--continue`): **1,177/1,177 catalog,
91/91 direct-Java Quack and80/80 SLT pass; replay207/208**. The only failed test
contains exactly the six parked TIME records, unchanged at
`data_inlining/data_inlining_types.test:36/46/53` and `types/all_types.test:32/42/49`.
Corpus totals remain17,596 passed/six failed/12 original record skips,100 original
file exclusions and5,189 oracle query passes. There are no resource refusals or
new exclusions in the pristine corpus, no JUnit errors/skips and no quota/compiler
fallback interruption. All groups actually execute; lint and frozen API/wire/JVM
checks pass. All scoped source fingerprints captured before verification match
afterwards. The exact invocation/exit is in `full-exit.json`; results and original
failures are in `full-summary.json` and `full-xml/` beside `full.log`.

H14's full run also exposed an additional Quack aborted-transaction qualification
failure. An unchanged91-test repeat fails in a different direct-Java case with the
same aborted-transaction state. Both original failures remain preserved and open;
H06 neither fixes nor waives that separate problem.
The H06 full run later passes all91 unchanged Quack tests. This is a passing later
execution, not a diagnosed repair or permission to retroactively erase the earlier
failures. No Quack test/catalog production source, assertion, pin or fixture changes.

The exact current **0.13.0-SNAPSHOT** SLT/replay JAR copies have every packaged
class byte compared to the output used by the completed tests. Public Java callers
of the SLT JAR pass all five independently bounded witnesses on actual17.0.2 and
25.0.2 (ten child processes, zero exhaustion errors); the Java17 artifact census
again reports948 files and zero refusals. `artifact-summary.json` records the
proof, census and hashes:

- SLT: `04c1417aa2b0654a850f765342296452e52007014ab81f40db0e0c954e99a7ec`
- Replay: `66d47ae6bc0cd02009796156dfcea8f954fb0ba4bae4280d6917be858d68b034`

An initial manual artifact-probe command incorrectly selected the first historical
JAR from a glob (SLT0.3.0-SNAPSHOT), which naturally reproduces the old OOM. That
invocation is not counted as verification of the new bytes; its log, selection
record and copied old JARs remain in `artifact-selection-error*` and
`verified-artifacts/selection-error/`. The corrected command names the exact current
outputs and checks packaged class identity before running. No historical files were
deleted or production source changed. These are local verification copies, not
publication, promotion or a consumer handoff.

Evidence: `build/verification/h06/red-{range,nesting,expansion,product,cycle}.log`,
`red-results.json`, `additional-probe-results.json`, staging test logs/XML, and
`staged-census.log`. Root qualification remains strict; the owner-parked six TIME
records are not investigated, skipped, rewritten or waived by this work.
