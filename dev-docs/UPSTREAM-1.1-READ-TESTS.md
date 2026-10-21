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

## Newer upstream regressions — strict native failures, not shipping passes

These are **new after the shipped extension**. The first three are now adopted as
whole pristine cases in the separate strict `upstreamR02NativeTest` task; none is
claimed as a shipping-native pass. The fourth remains queued for R04.

| Upstream main path at `ea543c71` | SHA-256 | Queue |
|---|---|---|
| `test/sql/alter/add_struct_field_non_literal_default.test` | `53e969c884d09270376b2d7553978751a7b81882337b6efbdcdd0ac719c8286a` | **Native FAIL:** expected-error ALTER succeeds and old rows read NULL; JVM rejects the recorded non-literal history |
| `test/sql/migration/v10_inlined_struct_field_order.test` | `a015a0482246894223944f601b29b5663087c2cf40631ed2a9e4751095a3c257` | **Native FAIL:** physical order blocks original DELETE/read/CHECKPOINT; separate JVM reads of the genuine migrated seed pass |
| `test/sql/alter/add_struct_alter_same_transaction.test` | `94bcc8bbc2b810588a081d94c117acd8f449f93dc46caa9f0b0e815fbe85e43d` | **Native SIGSEGV:** same-transaction field identity case fails its isolated parent gate, never skips |
| `test/sql/migration/v10_retyped_bounds.test` | `3694ae485e41fe81c4efd63028c2cbdf2bd83351d12ea569c61bb66b36b8409b` | R04: migrated bounds in physical-file/older column types must not cause false pruning or exact aggregates |

The STRUCT-order test depends on the genuine 1.5.6-written
`data/old_ducklake/inlined_struct_field_order.db.gz` at the same source revision:
11,970 bytes, SHA-256 `837dd5fc5f949b13744c3210a0a7d5b43e3ba6d9168514ea55ab7b4218e95d7d`.
`prepare_11_read_fixtures.py` fetches the exact source/binary inputs to `build/`,
without changing the maintained corpus. Both the JVM fixture and strict native lane
check their own fixed digests and bound decompression to 64 MiB. The strict lane
actually prepares the one known gzip directive, then executes every original SQL/
golden record; it does not blanket-enable arbitrary `unzip` directives or count a skip
as adoption. Child processes are bounded and reaped after failure/crash.
The retyped-bounds fixture additionally requires its pinned metadata/data inputs;
those must be inventoried before that entire case can be claimed as adopted.

The JVM legacy fixture reads the **untouched 1.5.6 seed immediately after explicit
native migration**, with independent expected values across eight tables. It does
not silently bypass the failed native DELETE and pretend the whole newer SLT passed.
The strict task reports **3 failed, 0 skipped** on core `d54cbbe3df`/DuckLake `417a9117`;
the crash log points into the native extension's `std::vector<duckdb::Vector>` path.
Full per-case output and `hs_err` are retained in the replay module's
`build/reports/upstream-r02-native/`. The [R02 support record](TODO-DUCKLAKE-1.1-SUPPORT.md)
links the already-merged fixes and keeps broad native acceptance open.

The qualified maintained/shipping read checks pass **623 maintained + 52 direct-Java
JUnit tests** and **17,602 corpus records** with unchanged exclusions. That result
does not include a pass for the separate strict lane: its final rerun still reports
3 failures, 0 skips, with exact signed-peer/unsigned=false assertions in every
child log. The same-transaction case's native SIGSEGV is preserved as a failure.

`default/literal_default_rendering.test` and the native-rejected whole-container
defaults in `default/nested_column_default.test` still guide subsequent type coverage;
this document does not claim blanket mirror coverage for them or queued R04 cases.

See the [read/write support TODO](TODO-DUCKLAKE-1.1-SUPPORT.md). New upstream cases
must be added alongside our focused NULL/bytes/identity/failure regressions, not used
to replace them or bypass an unsupported reader operation.
