# Java Quack JDBC 0.12.0 adoption — October 31, 2026

Owner requested `dev.brikk.duckdb:quack-jdbc:0.12.0`. Base `4924299` already has
the pushed N13/N05/H07 batch. The owner subsequently authorized pushing this
adoption after verification/commit. Publication, sibling artifact adoption and
native/server upgrades remain outside this request.

**Driver adoption checks pass; full project qualification remains blocked by the
same six strict embedded-JDBC TIME24:00 records.** No new regression was observed.
No skip, fabricated value or green full-check claim is introduced; the seven
pending source repairs stay pending until strict qualification passes.

## Artifact and scope

- Shared Java driver pin **0.11.1 → 0.12.0**, actual-version assertions, fixture
  provenance and exact JAR checksum updated. Historical reports and wire samples
  retain their original versions/results.
- Central JAR SHA256:
  `0fa0cc634d35cb2a77ff88232fe1f6a3fd1570ba54729c9cc50005c7b575f1cf`.
  Independently downloaded bytes match Central's `.jar.sha256`. All **107 classes**
  target Java17-or-lower (major61); no native libraries or bundled `org.duckdb`
  classes. The POM has no runtime dependencies; its 1.5.6 DuckDB property is only
  an upstream test-profile pin, not a maintained runtime change.
- Embedded JDBC stays **2.0.0.0**, core **2.0.0 / d54cbbe3df**, signed DuckLake
  **417a9117f5**, native Quack **f96420ed46**. The corpus, other extensions,
  isolated legacy producer, library runtime, format/write guards, migration and
  connector code are unchanged.
- The [tagged changelog](https://github.com/brikk/duckdb-quack-jdbc/blob/v0.12.0/CHANGELOG.md)
  describes SQL-aware classification of triggered non-RETURNING DML's
  `count_star()` result as an affected-row count. SELECT and RETURNING results
  remain ResultSets even with count-like column names. The fix also covers prepared
  and batched writes, context scanning and cancellation rechecks before success.
  Unknown/multi-statement contexts conservatively retain the wire result.

## Desired-behavior adoption controls

New `TestQuackTriggeredCounts` uses real signed v3 SQL with no embedded JDBC on
its classpath. Its five tests exercise statement/prepared/batch INSERT, prepared
UPDATE/DELETE and zero-match counts, SELECT/RETURNING count-like aliases (including
empty results), CTE/nested-comment/quoted-keyword/bound-string contexts, native
unsupported row-trigger RETURNING errors, explicit rollback and exact source/audit
effects. These are real effects rather than issuing the write again for its count.

On **0.11.1 all five fail** at the intended count/result assertions, with no fixture
failure, JUnit error or skip. This is a genuine old-driver baseline, not a golden
rewritten to accept old behavior. The native row-trigger RETURNING refusal remains
an error; the test does not turn unsupported SQL into success.

## Verification

- Focused **44 catalog + all 91 direct-Java cases pass**, zero failures/errors/
  skips, including all five new desired-behavior tests. Lint and Java17 classfile
  checks pass (7,843 classes across 17 artifacts). The direct-Java task actually
  runs on **Temurin17.0.19**, selected by Gradle's Java17 toolchain launcher through
  a verification-only external Gradle init
  script ([preserved recipe](quack-jdbc-0.12.0-2026-10-31/verify_java17.init.gradle));
  the build/consumer toolchain is not repinned.
- The [Java17 cancellation witness recipe](quack-jdbc-0.12.0-2026-10-31/verify_java17_cancel.py)
  **passes on actual Java17.0.2** with the 0.12.0 Central JAR and no embedded JDBC.
  It preserves the archived 0.11.1 source unchanged and derives a fresh source
  with only version/class/marker substitutions. This is a new execution, not old
  results relabelled as 0.12.0. Healthy cancellation/reuse, negative-epoch zoned
  nanoseconds/NULLs and actual v3/core identity pass. Lost response headers and
  stalled bodies fail closed in **4,043ms / 4,003ms**, under the unchanged 6s safety
  bound: one owned-UUID CANCEL and DISCONNECT, no SQL replay, closed/non-reusable
  connection and idempotent close. Owned workers/server resources are released.
- Full forced verification: **1,159/1,159 catalog + 91/91 direct-Java + 51/51 SLT
  pass; replay 161/162**, with only identity control's original six TIME records
  (`data_inlining/data_inlining_types.test:36/46/53`, `types/all_types.test:32/42/49`).
  Corpus remains **17,596 passed / six failed / 12 original record skips**, with
  all 100 original file exclusions unchanged. Zero JUnit errors/skips. All test
  groups actually executed; direct-Java tests run on Temurin17.0.19. Duration
  **43m16s**, exit 1 (`--continue`). Lint and Java17 classfile checks pass; all
  scoped hashes captured before the full run still match.
- Source/classfile checks are not a blanket public API, transport,
  cancellation-race or streaming certification. Upstream's 724-test oracle-profile
  results are release information, not tests run here. The native MySQL/R02–R04
  acceptance gates are not rerun or promoted by this driver adoption.
- No new skip, golden/sentinel, exception-as-value, private native reflection,
  query replay, automatic repin, 1.1 write enablement or physical cleanup change.

Evidence under `/tmp/opencode/`: `catalog-quack012-before{.log,-xml/}`,
`catalog-quack012-scan/` (artifact and Java17 execution),
`catalog-quack012-focused.log`, and `quack012-java17.init.gradle`.
Completed focused XML/counts/runtime identity: `catalog-quack012-focused-xml/`.
Resolved dependency reports: `catalog-quack012-dependencies.log` (Quack0.12.0;
embedded JDBC remains2.0.0.0). Scoped source/recipe hashes captured before the full
run: `catalog-quack012-scan/source-hashes.json`.
Full XML/counts/failures and direct-Java runtime identity:
`catalog-quack012-full-xml/`.
All **18** current fixture provenance files attest 0.12.0, the Central checksum,
protocol3, core `d54cbbe3df` and unsigned extensions disabled. Nine scoped
source/recipe hashes and 77 local-link checks are recorded in
`catalog-quack012-scan/verification.json`; provenance copies are beside it.

Full command (output `catalog-quack012-full.log`):

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -I dev-docs/archive/quack-jdbc-0.12.0-2026-10-31/verify_java17.init.gradle \
  -Dorg.gradle.java.installations.paths=/home/jayson/.local/share/mise/installs/java/17.0.2 \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```

## Separate tuple representation note

The release history documents native JDBC 2.0 TUPLE falling back to String/OTHER,
while Quack retains positional JDBC Struct values. This corrects the earlier
over-broad conversational "don't know of any" answer, but does not establish a
new tuple `toString()` failure in this catalog or claim that 0.12.0 fixes one.
No native TUPLE-mapping repair or extra acceptance gate is assigned by this adoption.
