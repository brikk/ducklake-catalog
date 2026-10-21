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

## Newer upstream regressions — queued for their capabilities

These are **new after the shipped extension**, not already-qualified shipping
tests. Preserve their source and expectations when adopting them for R02/R03/R05;
report actual native/JVM failures instead of making expected results follow a binary.

| Upstream main path at `ea543c71` | SHA-256 | Queue |
|---|---|---|
| `test/sql/alter/add_struct_field_non_literal_default.test` | `53e969c884d09270376b2d7553978751a7b81882337b6efbdcdd0ac719c8286a` | R02: added nested fields, typed defaults and native expression rejection |
| `test/sql/migration/v10_inlined_struct_field_order.test` | `a015a0482246894223944f601b29b5663087c2cf40631ed2a9e4751095a3c257` | R02: genuine 1.0 physical field reordering and read/flush after migration |
| `test/sql/alter/add_struct_alter_same_transaction.test` | `94bcc8bbc2b810588a081d94c117acd8f449f93dc46caa9f0b0e815fbe85e43d` | R02: same-transaction field identity changes |
| `test/sql/migration/v10_retyped_bounds.test` | `3694ae485e41fe81c4efd63028c2cbdf2bd83351d12ea569c61bb66b36b8409b` | R04: migrated bounds in physical-file/older column types must not cause false pruning or exact aggregates |

The STRUCT-order test depends on the genuine 1.5.6-written
`data/old_ducklake/inlined_struct_field_order.db.gz` at the same source revision:
11,970 bytes, SHA-256 `837dd5fc5f949b13744c3210a0a7d5b43e3ba6d9168514ea55ab7b4218e95d7d`.
Its adoption needs controlled gzip fixture preparation (the general replay harness
currently reports `unzip` as unsupported), not a rewritten hand-produced golden.
The retyped-bounds fixture additionally requires its pinned metadata/data inputs;
those must be inventoried before that entire case can be claimed as adopted.

This inventory is not a claim that queued tests were executed or passed. Existing
upstream `default/struct_field_default.test`, `default/literal_default_rendering.test`
and the native-rejected whole-container defaults in `default/nested_column_default.test`
also guide later type/evolution qualification; they are not blanket mirror coverage.

See the [read/write support TODO](TODO-DUCKLAKE-1.1-SUPPORT.md). New upstream cases
must be added alongside our focused NULL/bytes/identity/failure regressions, not used
to replace them or bypass an unsupported reader operation.
