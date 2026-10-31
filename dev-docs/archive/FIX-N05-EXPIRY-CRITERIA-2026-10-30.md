# N05: expiry criteria and timestamp SQL — October 30, 2026

Owner assigned N13 → N05 → H07 with verification, commit and push between steps.
N13 was committed/pushed as `7fac90c` before this step started. H07 is untouched.

**Status: implemented; pending strict project qualification, not Complete.** The separate strict
TIME24:00 project qualification blocker is neither waived nor repaired here.

## Two distinct reproduced defects

1. Supplying both `olderThan` and `versions` silently preferred versions, ignoring
   the cutoff. The API already declares them mutually exclusive. This observation
   does not establish a deletion-loss incident; this method is a read-only selector.
2. Timestamp-only selection on local DuckDB and direct Java Quack rendered
   `TIMESTAMP(6) WITH TIME ZONE`, which DuckDB rejects. Native Quack's inlined SQL
   and PostgreSQL/MySQL did not show that rendering failure.

The final before-fix regression baseline runs **19 tests: nine fail, ten pass**.
Exactly five desired refusal assertions fail across the five routes; four
timestamp tests (two each on local DuckDB/Java Quack) fail on the precision syntax.
There are no setup failures or JUnit errors/skips in that baseline.

An earlier draft expected a fractional snapshot timestamp in the native MySQL
column; an independent seeded-value assertion showed `.123456` is stored as whole
seconds on this native layout. That producer/schema precision limitation is not
counted as an N05 rendering failure or hidden by changing the schema. All five
routes retain whole-second/equality/UTC controls; a separate microsecond contract
runs on local DuckDB, native Quack, Java Quack and PostgreSQL. No test is skipped
and no native acceptance gate is weakened by this scoped selector repair.

## Repair and contract

- `require` rejects both non-null criteria before any catalog read, including an
  empty versions set. The documented exception is `IllegalArgumentException`.
- Extract/reuse the already qualified `getSnapshotAtOrBefore` timestamp expression:
  DuckDB dialect binds ISO-8601 `Instant` text and explicitly casts to supported
  `TIMESTAMPTZ`, without dropping UTC or interpolating input into SQL. PostgreSQL
  and MySQL keep the existing typed OffsetDateTime parameter path.
- Preserve strict `<` cutoff semantics, ascending IDs, both-null/default behavior,
  empty/unknown version handling and protection of the latest **ID**, even when
  writers' clocks are non-monotonic. Equality, negative epoch and stored
  microsecond boundaries have independent expected values. Selection leaves
  snapshots unchanged. No deletion/retention algorithm or physical cleanup changed.

## Verification

- Focused selector/timestamp/expiry/native-Quack/N13 regressions: **54 tests pass**,
  including all **19 N05 tests**, zero failures/errors/skips. Lint and Java17
  classfile checks pass (7,843 classes across 17 artifacts).
- Full forced check (finished October 31): **1,159/1,159 catalog + 86/86 direct-Java
  + 51/51 SLT pass; replay 145/146**, with only identity control's original six
  TIME records (`data_inlining_types.test:36/46/53`, `all_types.test:32/42/49`).
  Corpus remains **17,596 passed / six failed / 12 original record skips**, with
  all 100 original file exclusions unchanged. Zero JUnit errors/skips. All test
  groups actually executed; lint and Java17 classfile checks pass. Duration
  **42m9s**, exit 1 (`--continue`). All captured scoped source hashes still match.
  The first N13 full run's intermittent evolution failure does not recur here;
  its original evidence remains in the N13 report, not erased or waived.
- No pin, corpus, golden, skip, sibling, publication, migration or 1.1 write change.

Evidence under `/tmp/opencode/`:

- `catalog-n05-before-verified{.log,-xml/}`: final red baseline and classified counts.
- `catalog-n05-mysql-precision{.log,-xml/}`: stored timestamp diagnostic, not N05 evidence.
- `catalog-n05-focused.log`: focused verification.
- `catalog-n05-focused-xml/`: completed focused XML/counts.
- `catalog-n05-source-hashes.json`: scoped hashes captured before full verification.
- `catalog-n05-full.log`: full forced verification output.
- `catalog-n05-full-xml/`: completed full XML/counts/failures.

Full command:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```
