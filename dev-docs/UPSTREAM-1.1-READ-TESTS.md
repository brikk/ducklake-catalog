# Upstream DuckLake 1.1 read-test adoption

The maintained corpus remains **`417a9117f590e8141742ac0579d02f6a90162e37`**,
matching signed DuckLake `417a9117f5` shipped with official DuckDB 2.0.0. Upstream
main was separately fetched at **`ea543c719a4e88a8154f9db897aa7c580da3308e`**.
No checkout, corpus pin or golden SQL was changed to adopt individual regressions.

## R01 defaults — actual JVM read mirrors

`TestUpstreamFinal11Defaults` checks the exact source digests below, replays each
whole pristine SLT file against native DuckDB 2.0/Quack final-1.1 metadata and its
original golden values, and requires every selected query to run a separate JVM
catalog read. The mirror reconstructs plain scratch tables **only from the
catalog's decoded inline rows**, then executes the original query projection;
it must not scan native DuckLake/Parquet as its alleged catalog read.

| Pristine corpus path | SHA-256 | Required JVM mirrors |
|---|---|---:|
| `default/add_column_if_not_exists_expression_default.test` | `3cec752529d12d57dc42891a519eeef9136a5fb69f954b1d1b96075639fe85b9` | 1 |
| `default/cast_defaults_not_literal.test` | `ed4ecb85e6ee7f654eab503adc25598735278cfd63943d0fd0048b9a0aa3830b` | 2 |
| `issues/issue_1212_string_null_default.test` | `a527746e673bde1e6df61b2b8b3bddd8641ae801ba01f7b99306e4ca13119bb5` | 1 |

Uncommitted oracle reads are not mirrored as committed catalog reads. The selected
files have explicit final mirror counts, no file/record skips and no replaced
expected values. Direct-Java qualification without DuckDB JDBC remains the separate
`quackPreviewTest` task; this corpus mirror deliberately uses JDBC for its native
oracle and scratch SQL, not as a substitute for the Java catalog projection.

## R02 shipped corpus — stable-ID evolution JVM mirrors

`TestUpstreamFinal11Evolution` replays eight more entire pristine files at the
maintained shipped corpus pin. Its shared `Final11InlineCorpusMirror` constructs
typed scratch STRUCT/LIST/MAP values exclusively from the catalog's decoded rows,
with scalar values bound as parameters. It asserts field sets and requires empty
Parquet candidate lists; it cannot use a native lake scan as its alleged JVM read.

| Pristine corpus path | SHA-256 | Required JVM mirrors |
|---|---|---:|
| `alter/struct_evolution.test` | `6c9e5075d17af16efbef4db21dd26d3a4caf575611ec2b3a00aadb487053f327` | 7 |
| `alter/struct_evolution_nested.test` | `7db784b1b7486ab79045d064b5165202ea214f88d7e4c72cfc786a944ca91faf` | 5 |
| `alter/struct_in_list_evolution.test` | `83e2bb90f21799f146c90b6fd69d01db6699607f837351fceec7a43b860f593b` | 8 |
| `alter/struct_in_map_evolution.test` | `07b6d711696c337921634961738db5afaadb7bb65a5fb7d43cc85a361c8c636b` | 5 |
| `default/struct_field_default.test` | `f34029bb8a6d7b6cb795ff9f682028040a7307374d15b4b7b0a8a3e4d98eaecd` | 1 |
| `alter/rename_struct_field_default.test` | `aae190bed94e2eaba07c61ced8b87242f273c4dcd0ee577c379b536c734e3a90` | 3 |
| `alter/struct_evolution_reorder.test` | `3a742b719b404443b270e2d3b47f6b7b307d75fa9ac3358af1eed8846e71f7c2` | 3 |
| `alter/promote_type.test` | `038cdb398891a93b054e48c4f6588bcf72a44efef2d417c749fb06e422a6ab09` | 1 |

That is **33 new JVM mirrors**, 37 with R01. The reorder file has an explicit
three-mirror pre-flush limit; its later native post-flush goldens still execute,
but are not described as JVM Parquet reads. Uncommitted reads and DESCRIBE metadata
are not falsely presented as committed payload mirrors. All original expected rows,
errors and source lines remain unchanged.

## R03 shipped corpus — temporal and NULL-type JVM mirrors

`TestUpstreamFinal11TemporalValues` adds three whole pristine files at the same
maintained revision, preserving original SQL, timezone SET statements and goldens.
Typed scratch values come only from decoded JVM rows; native post-flush goldens
still execute but are not misrepresented as JVM Parquet reads.

| Pristine corpus path | SHA-256 | Required JVM mirrors |
|---|---|---:|
| `data_inlining/data_inlining_time_ns.test` | `f7a58dae210d60604ee9da76fe7615d6513ec1990778d704e21a288a64eda075` | 2 |
| `data_inlining/data_inlining_null_type.test` | `e55cfb413f0a3111b74f266598e76e2c2aefd5f19ab111c94d0f8cc9438945f5` | 4 |
| `default/literal_default_rendering.test` | `f804d019c04643e7f9804218906e492e63e1fab5773fdf9174ac2cfcdafc03d2` | 5 |

That is **11 additional required JVM mirrors, 48 across fourteen files**. Mirrors
exclude information_schema/AT-history queries and the literal-default file's
`FROM dl.dt` DATE-to-zoned promotion: no production session-zone contract qualifies
that promotion. Its unchanged native query/golden still runs. The five mirrored
literal-default queries reproduce the source's New York/Tokyo query zones only in
scratch SQL; they do not configure the production metadata reader. Mirror-count
assertions and no-skip assertions remain mandatory.

Ten direct-Java R03 tests separately qualify finite clocks, second offsets,
nanosecond zoned instants, BCE/large dates, temporal defaults/rename/history/CDC,
nested BLOB/JSON/NULL preservation and explicit edge/VARIANT rejection. Signed and
extreme interval reader cases use controlled owned physical INTERVAL storage;
current native negative/large INSERT/Parquet restrictions are not waived.

Final forced R03 maintained-reader qualification passed **632 maintained + 62
direct-Java tests**, zero failed/JUnit-skipped, and **17,602 corpus records** with
the same 100 file/12 record exclusions. Lint, frozen consumer/record/JSON controls
and Java 17 classfile checks passed. This does not include or waive the strict
native failures below.

### R03 strict native interval persistence gate — NOT GREEN

`mise exec -- ./gradlew :ducklake-test-corpus-replay:upstreamR03NativeTest
--no-configuration-cache --no-daemon` is a focused independent native regression,
not a purported upstream SLT adoption. It asserts official core `d54cbbe3df`, signed
DuckLake `417a9117f5` and unsigned=false, and reports **1 failed, 0 skipped**:
acknowledged `1 day 100:00:00.123456` flushes to `5 days 04:00:00.123`, with
sub-minute microseconds **123000 instead of 123456**. It never replaces the expected
value or treats the native defect as a pass. XML/output are under replay module
`build/test-results/upstreamR03NativeTest/` and `build/reports/tests/upstreamR03NativeTest/`.
The task remains separate from maintained `check`, which certifies the qualified
inline reader, not lossless interval file persistence. Shipped
[DuckLake #1727](https://github.com/duckdb/ducklake/pull/1727) rejects new negative
inline intervals; it does not fix this precision/component change.

## R04 shipped corpus — rich metadata JVM mirrors

`TestUpstreamFinal11ReadMetadata` adds three whole pristine files at the maintained
`417a9117f590e8141742ac0579d02f6a90162e37` revision. `Final11MetadataCorpusMirror`
populates plain scratch metadata exclusively through public JVM read APIs, then
executes the original selected metadata query. It never uses native metadata SELECTs
as its alleged JVM result. The native oracle still executes every pristine golden.

| Pristine corpus path | SHA-256 | Required JVM mirrors |
|---|---|---:|
| `metadata/row_group_count.test` | `603a0107ddf93c03a058bed34166bff23ad26c2a6d796d551950baeaee7bf357` | 6 |
| `migration/v10_row_group_count.test` | `87fdbba98e3cca7b3afbecc3be6619977391f6cc5f916651a831801503d3dce6` | 4 |
| `migration/v11_stats_exactness.test` | `9e9523e757416e6b4f6936e54179b46ef53dd76b6a3143001caf1fa8c8827d6b` | 7 |

That is **17 new required JVM mirrors, 65 across seventeen R01–R04 files**. The
mirror selects queries containing `row_group_count` or `min_is_exact` against the
fixture's `dl.ducklake_*` tables, excluding the oracle's footer-derived
`getvariable('added_rg_count')` comparison. This bounded adoption includes native
Puffin delete NULL counts, pre-migration unknown counts/flags, independent file/table
exactness after migration, and native compaction/inline flush metadata. Rejected
queries still run against original native goldens; no physical Parquet reader,
footer scan, live aggregate or native migration repair is claimed as a JVM mirror.

Final R04 maintained-reader qualification passed **639 maintained + 74 direct-Java
JUnit tests**, zero failed/errors/JUnit-skipped, and **17,602 corpus records** with
unchanged 100 file/12 record exclusions. All twelve new R04 peer tests execute;
frozen Java/record/JSON, lint and Java 17 classfile controls pass. This result does
not include a pass or waiver for any separate strict native lane.

### R04 strict newer-upstream migrated-bound gate — NOT GREEN

`upstreamR04NativeTest` adopts the **whole pristine**
`test/sql/migration/v10_retyped_bounds.test` at `ea543c71` (SHA-256
`3694ae485e41fe81c4efd63028c2cbdf2bd83351d12ea569c61bb66b36b8409b`). It prepares only
the known gzip directive, verifies every byte input and replays all original SQL and
goldens against the asserted shipping peer in a bounded isolated child JVM.

The genuine 1.5.6-written `data/old_ducklake/migrate_bounds.db.gz` is **12,454 bytes**,
SHA-256 `f9ad3a91468c3e3c96b1d3587214885b20fedd86c8a89b84efab2a7487188528`.
Its **ten Parquet inputs (2,132 bytes)** are `double.parquet`, `float.parquet`,
`ts_ns.parquet`, `tz_ns.parquet` and the six exact UUID-named files under
`main/dc`, `main/i2f`, `main/r` (two), `main/st` and `main/tf`. Every full path and
SHA-256 is inventoried in [fixture preparation](probes/prepare_11_read_fixtures.py)
and checked independently by `UpstreamR04NativeWorker`. The script now verifies
16 R02/R04 inputs at the same immutable revision; no maintained corpus pin moved.
The gzip is bounded to 64 MiB, all fixture files are copied into owned temporary
storage before native use, and the parent reaps workers on failure/crash/timeout.

The strict gate reports **1 JUnit failure, 0 skipped**: **14 original records pass,
10 fail**. The first query reads timestamp `.123457` but native equality counts 0
instead of 1 and less-than counts 1 instead of 0. Float comparisons lose rows and
native MIN returns `0.1` instead of `0.10000000149011612`. Suspect file/table bounds
survive migration instead of clearing. Both genuine-seed and second config-lake
parts execute; no native error is hidden, skipped or rebaselined. Full output is
retained under replay module `build/reports/upstream-r04-native/`.

[Fork #94](https://github.com/PeterVanHolland/ducklake/pull/94), merged through
[duckdb/ducklake #1806](https://github.com/duckdb/ducklake/pull/1806), fixes this
migration but is absent from shipped `417a9117f5`. The separate direct-Java genuine
seed test verifies raw text/unknown flags and **legacy bound suppression/retain-all
planning only**. It does not claim that raw old-type text is a qualified typed bound,
that JVM physical file scans ran or that the newer native SLT passed. The strict
task remains separate from maintained `check` just like R02/R03's native lanes.

## Newer upstream regressions — strict native failures, not shipping passes

These are **new after the shipped extension**. The first three are adopted as
whole pristine cases in `upstreamR02NativeTest`; the fourth is adopted in
`upstreamR04NativeTest`. None is claimed as a shipping-native pass.

| Upstream main path at `ea543c71` | SHA-256 | Queue |
|---|---|---|
| `test/sql/alter/add_struct_field_non_literal_default.test` | `53e969c884d09270376b2d7553978751a7b81882337b6efbdcdd0ac719c8286a` | **Native FAIL:** expected-error ALTER succeeds and old rows read NULL; JVM rejects the recorded non-literal history |
| `test/sql/migration/v10_inlined_struct_field_order.test` | `a015a0482246894223944f601b29b5663087c2cf40631ed2a9e4751095a3c257` | **Native FAIL:** physical order blocks original DELETE/read/CHECKPOINT; separate JVM reads of the genuine migrated seed pass |
| `test/sql/alter/add_struct_alter_same_transaction.test` | `94bcc8bbc2b810588a081d94c117acd8f449f93dc46caa9f0b0e815fbe85e43d` | **Native SIGSEGV:** same-transaction field identity case fails its isolated parent gate, never skips |
| `test/sql/migration/v10_retyped_bounds.test` | `3694ae485e41fe81c4efd63028c2cbdf2bd83351d12ea569c61bb66b36b8409b` | **Native FAIL:** migrated old-type bounds cause missing matches/wrong MIN; separate JVM legacy fallback checks remain safe |

The STRUCT-order test depends on the genuine 1.5.6-written
`data/old_ducklake/inlined_struct_field_order.db.gz` at the same source revision:
11,970 bytes, SHA-256 `837dd5fc5f949b13744c3210a0a7d5b43e3ba6d9168514ea55ab7b4218e95d7d`.
`prepare_11_read_fixtures.py` fetches the exact source/binary inputs to `build/`,
without changing the maintained corpus. Both the JVM fixture and strict native lane
check their own fixed digests and bound decompression to 64 MiB. The strict lane
actually prepares the one known gzip directive, then executes every original SQL/
golden record; it does not blanket-enable arbitrary `unzip` directives or count a skip
as adoption. Child processes are bounded and reaped after failure/crash.
The retyped-bounds fixture's complete pinned metadata/data inventory is adopted
in R04 above; the observed native failures are not claimed as passes.

The JVM legacy fixture reads the **untouched 1.5.6 seed immediately after explicit
native migration**, with independent expected values across eight tables. It does
not silently bypass the failed native DELETE and pretend the whole newer SLT passed.
The strict task reports **3 failed, 0 skipped** on core `d54cbbe3df`/DuckLake `417a9117`;
the crash log points into the native extension's `std::vector<duckdb::Vector>` path.
Full per-case output and `hs_err` are retained in the replay module's
`build/reports/upstream-r02-native/`. The [R02 support record](TODO-DUCKLAKE-1.1-SUPPORT.md)
links the already-merged fixes and keeps broad native acceptance open.

The R02 qualified maintained/shipping read checks passed **623 maintained + 52 direct-Java
JUnit tests** and **17,602 corpus records** with unchanged exclusions. That result
does not include a pass for the separate strict lane: its final rerun still reports
3 failures, 0 skips, with exact signed-peer/unsigned=false assertions in every
child log. The same-transaction case's native SIGSEGV is preserved as a failure.

The native-rejected whole-container defaults in `default/nested_column_default.test`
still guide subsequent type coverage; this document does not claim blanket mirror
coverage for them. R03 literal-default and R04 metadata coverage are bounded above.

See the [read/write support TODO](TODO-DUCKLAKE-1.1-SUPPORT.md). New upstream cases
must be added alongside our focused NULL/bytes/identity/failure regressions, not used
to replace them or bypass an unsupported reader operation.
