# Harness coverage and failure stages — October 29, 2026

Owner-requested order: **H05 → H09 → H08**. Base `9c5d1a2` already pins Quack JDBC
0.10.0; embedded DuckDB JDBC remains2.0.0.0 (core2.0.0). Earlier harness repairs stay
complete. This batch does not upgrade pins, rewrite goldens or edit sibling sources.

**Implemented; qualification is blocked.** The corrected 76-test control suite and
lint pass, but strict corpus validation exposes six previously hidden TIME endpoint
conversion failures. The owner explicitly chose **keep strict failures**, not new
driver skips or fabricated values. The full run also encountered an external
extension-cache lock; the unchanged-source route retry passes, while the complete
replay task remains red on exactly the six TIME records. Keep the three repairs
active as pending qualification, not Complete.

Separate implementation commits, in requested order:

- H05: `34e4192` — measure oracle and mirror query coverage separately.
- H09: `f7affa7` — reject unreadable JDBC cells instead of manufacturing values.
- H08: `89113f6` — separate oracle statement expectations from adapter setup errors.

These commits preserve the intended implementations with an explicitly failing
qualification gate. They are not pushed or promoted as fully qualified repairs.
No push, publication, connector artifact adoption or 1.1 writes are authorized.

## H05 — measured coverage, not inferred conformance

Each file's synchronized progress journal records immutable snapshots of query-stage
measurements: oracle/golden passes, actual engine executions, verified mirror passes,
divergences/errors, execution skips, gate skips and their reasons, acceptance errors,
and pending attempts frozen by timeout/crash. Identity-control passes are explicit.
Report summaries and additive aggregate getters separate those stages from old
record totals. An always-rejecting engine now reports zero attempts/passes and
dialect-rejected skips; a file without an engine attachment reports not-attached.

Legacy Pass/Fail/Skip, FileResult constructor/data components/copy/equality and engine
method descriptors remain intact. Supplementary `queryCoverage` is attached only to
measured results; manual/data-copied results are explicitly unmeasured rather than
manufacturing evidence. Gate skips are measured separately, not additional record
outcomes. Actual execution skips retain their legacy Skip outcome as well.

`CorpusReport.requireMirrorPasses(minimum)` enforces a positive verified-mirror
threshold; neither setup/oracle passes, missing measurements nor attempt counts can
satisfy it. The caller still checks record failures/crashes and chooses a meaningful
aggregate threshold. Zero mirrors is legitimate for identity control or individual
files, not proof of engine conformance. Consumers must adopt this check separately.

Read-only sibling source inspection found Trino's general corpus suite still checks
legacy totalPassed>50 and Doris's general suite checks failures/crashes without a
mirror threshold. Those are adoption boundaries, not fresh runtime rerun evidence.
No sibling edit or whole connector qualification is claimed. The existing native
geometry conformance test's execution check is not replaced by these new counters.

This reporting fix does not close H07's separate attachment-transition defects.
It reports the actual current decision without claiming a repaired attachment state
machine. H04's freeze/quarantine rules are retained, including pending mirror counts.

## H09 — conversion failures cannot become values

JDBC getters (including geometry text getters) and cell renderers now propagate a
SQLException with row/column/type context and the original cause/SQL state. No
`<jdbc-unreadable>` value is synthesized. The driver's existing per-query isolation
turns the failure into a failed record before golden regexes, labelled hashes or
mirror gates. A repeated conversion fault cannot seed/confirm a label, and the hash
of the former sentinel cannot qualify it. Real NULL and literal sentinel-looking
strings remain data. Later valid records, labels and owned-resource cleanup survive.

### Maintained qualification blocker: TIME '24:00:00'

The all-types producer emits a valid native TIME24:00 endpoint. Pinned JDBC2.0.0.0
converts it through `LocalTime.ofNanoOfDay`, which cannot represent end-of-day.
Both `getObject` and `getString` fail; the latter delegates to the former. Source
inspection of public time/long/lazy-string getters found no supported lossless
alternative for the physical TIME vector. No private-field/native reflection,
exception-message-as-data recovery, query re-execution or new dependency was added.
A separate read-only Java17 probe confirms `test_all_types()` natively renders
`00:00:00`, `24:00:00` and SQL NULL when explicitly cast to VARCHAR in that probe;
object/string/time/long getters all throw DateTimeException for the24:00 endpoint.
Those diagnostic casts are not used as a query-reexecution fallback in the harness.

Six original labelled corpus queries had compared/hash-cached the same synthesized
sentinel instead of real TIME values. They now fail correctly:

- `data_inlining/data_inlining_types.test`: lines36,46,53 (row2, column10).
- `types/all_types.test`: lines32,42,49 (row2, column11).

The owner selected keeping those strict failures visible. Original goldens and the
100 file / 12 record exclusions remain untouched; no new skip is authorized.
This is an exposed JDBC representation/qualification gap, not proof native DuckDB
produced a wrong value. Separate lossless support and a passing full rerun are needed
before promoting these implementations to fully qualified repairs.

## H08 — oracle SQL and adapter setup have separate expectations

Only oracle SQL is inside expected-SQL-error classification. After an oracle
success, transaction tracking and its record expectation are resolved first. An
unexpected success (`statement error`) fails before adapter setup. A real expected
oracle failure also never reaches the adapter. Only an accepted success can connect
the adapter; checked, unchecked and query-only ReplayEngineSkip setup exceptions
all become explicit failed records and leave mirroring unavailable. No connect
exception can satisfy `statement error` or `statement maybe`'s SQL error matcher.

No SQL is retried or executed twice, and no adapter is closed under an active worker.
Normal successful setup and expected native SQL errors remain supported. This is
not a new adapter reconnection, partial-resource reclamation or H07 state contract.

## Verification

- Initial baseline: H05 **4/4 red**, H09 **3/4 red**; NULL/literal controls pass.
  An initial H08 expected-error fixture lacked the required `----` block and was
  parser-skipped. That fixture was corrected, a no-file-skip assertion was added,
  and the old H08 catch boundary was temporarily restored for a fresh negative run:
  **3/4 corrected H08 groups fail**, with genuine oracle-error/success controls passing.
  The corrected witnesses use real oracle execution and injected failures, not saved
  bad goldens. The original invalid-fixture assertion is not counted as defect evidence.
- H05 focused: **34 pass**, zero failures/errors/skips; replay lint passes.
- H09 focused: **45 pass**, zero failures/errors/skips; replay lint passes.
- Corrected final controls: **76 pass**, zero failures/errors/skips, replay lint
  passes. Eight H05, six H09 and four H08 groups plus existing comparator, typed shape,
  geometry, substitution and timeout controls. Native TIME faults cannot qualify via
  regex/labels/hash or reach the engine, and a later valid labelled query still runs.
- First combined corpus run: **17,596 passed / six failed / 12 skipped records**,
  with original100 file exclusions. The failures are precisely the TIME endpoints
  above, not new golden answers. Pinned fixture preparation succeeded; no separate
  strict native gate was rerun or waived.
- Forced full invocation: **failed after34m51s** in catalog:test:1,110 of1,111
  catalog cases passed; a Java-Quack route fixture hit an external lock on a global
  `lance.duckdb_extension...info` cache file, held by Java PID1732043. That process
  was no longer present at inspection; no process was killed or cache file removed.
  All86 direct-Java tests, all51 SLT tests, lint and Java17 ABI checks passed.
  **Replay:test did not execute in this invocation**; its stale76-control XML is
  explicitly separated from full-run results, not counted as a full replay pass.
- Supplemental affected-route retry: **27/27 pass** (all three DuckDB-backed routes,
  including the previously cache-locked case). Complete replay: **145/146 pass**;
  the sole failing JUnit test is identity control, containing exactly the six TIME
  failures above. Corpus: **17,596 passed / six failed / 12 skipped**, with all100
  original file exclusions unchanged. No JUnit errors/skips. Supplemental run:
  **8m6s**, using `--continue` on the same eleven source/test files, whose committed
  hashes match the recorded final test inputs. This is **not** a successful full
  `check` claim. Other than the cleared external cache lock and the retained TIME
  gate, no additional failure was observed in these recorded runs.
- A [standalone Java17 witness](harness-coverage-errors-2026-10-29/Java17HarnessStages.java)
  is included for minimum-runtime coverage and old constructor/copy compatibility.
  It passes on actual **Java17.0.2** against the final classes. The invalid H08 fixture
  is guarded against parser skips here too. This is local harness evidence, not
  connector artifact adoption or broad native-driver certification.

Full command (failed at catalog:test before replay):

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-daemon --no-configuration-cache
```

Supplemental command (route retry passes; replay keeps the TIME failures):

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  :ducklake-catalog:test --tests '*TestFinal11DuckDbBackends*' \
  :ducklake-test-corpus-replay:test :ducklake-test-corpus-replay:detekt \
  -Dducklake.corpus.dirs=all --continue --no-daemon --no-configuration-cache
```

Evidence under `/tmp/opencode/`: `catalog-h05-h09-h08-red{.log,-xml/}`,
`catalog-h05-focused-final.log`, `catalog-h05-focused-xml/`,
`catalog-h09-focused{.log,-xml/}` and `catalog-h05-h09-h08-focused.log`.
Corrected H08 red evidence: `catalog-h08-corrected-red{.log,-xml/}`. Final control
evidence: `catalog-h05-h09-h08-controls-final{.log,-xml/}`; Java17:
`catalog-h05-h09-h08-java17-final/`; scoped hashes:
`catalog-h05-h09-h08-source-hashes.json`; full run: `catalog-h05-h09-h08-full.log`.
Full-run XML and its execution-aware summary: `catalog-h05-h09-h08-full-xml/`;
supplemental checks: `catalog-h05-h09-h08-supplement.log`.
Supplemental XML/counts/failures: `catalog-h05-h09-h08-supplement-xml/`.
Pinned JDBC source inspection is retained under `catalog-h09-time-investigation/`.
Post-commit verification confirms all eleven committed source/test hashes match,
all91 local links resolve, and the open queue retains13 unfixed defects plus the
three pending repairs. Machine report: `catalog-h05-h09-h08-verification.json`.
See the [compatibility contract](../../docs/COMPATIBILITY.md) for consumer use.
