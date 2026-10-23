# MySQL final-1.1 native acceptance blockers

**Deferred native writer/scanner/migration acceptance—not a blanket read ban.**
The owner explicitly approved qualifying direct-JDBC reads of already-initialized
MySQL 1.1 catalogs separately from upgrades and writes. That
[guarded read-only subset](READ-MYSQL-1.1.md) is now qualified. The failures below
remain real, visible gates for their own native operations and unsupported types;
they have not been fixed, waived or replaced with retagged 1.0 fixtures.

Locked diagnostic lane: DuckDB JDBC 2.0.0.0, core `d54cbbe3df`, signed DuckLake
`417a9117f5`, signed MySQL scanner `f7fc0d4271`, existing MySQL 8.4 test image.
The scanner revision is additionally recorded, not upgraded. No dependency,
corpus, format-layout pin, writer guard or migration default changes.

## Reproduce

```sh
mise exec -- ./gradlew :ducklake-catalog:final11MySqlNativeTest \
  --rerun-tasks --no-configuration-cache --no-daemon
mise exec -- ./gradlew :ducklake-test-corpus-replay:upstreamMySqlNativeTest \
  --rerun-tasks --no-configuration-cache --no-daemon
```

The expanded native fixture task reports **6 tests, 6 failures, 0 errors, 0 skips**;
the pristine corpus task reports **4 tests, 4 failures, 0 errors, 0 skips**. Both are
separate from `check`, like the existing R02–R04 native tasks. They are not
expected-error assertions, skipped tests or shipping passes. Maintained green
verification qualifies only the declared direct-JDBC read subset, not these native
operations or unrestricted MySQL 1.1 support. The original two migration/nested
failures are retained within the expanded fixture task.

### Genuine 1.0→1.1 migration fails

The fixture natively creates a 1.0 catalog/table, closes the writer, then explicitly
attaches with `DUCKLAKE_VERSION '1.1', AUTOMATIC_MIGRATION TRUE`. It does not modify
the version or repair SQL through the reader. Native migration fails preparing the
default-restoration self-join UPDATE:

```text
Failed to restore the defaults of renamed struct fields while migrating to v1.1
Column 'initial_default' in field list is ambiguous
```

The unqualified assignment targets in the translated multi-table UPDATE are
ambiguous between `renamed` and `original` aliases. This happens even for the
simple INTEGER seed, before any library read. Source:
`ducklake/src/storage/ducklake_metadata_manager.cpp`, default-restoration migration
at the maintained corpus revision `417a9117f590e8141742ac0579d02f6a90162e37`.

### Fresh native inline STRUCT commit fails

A fresh native final-1.1 lake executes:

```sql
CREATE TABLE lake.t(s STRUCT(a INTEGER));
INSERT INTO lake.t VALUES ({'a':42});
SELECT * FROM lake.t;
```

The native commit fails:

```text
Failed to flush changes into DuckLake:
MySQL does not support composite types - unsupported type "STRUCT(a INTEGER)"
```

The generic native backend attempts composite inline DDL without a MySQL physical
storage/serialization contract. Replacing the table with hand-authored text DDL,
forcing all rows to Parquet or asserting the error as success would not qualify
the required native↔JVM inline route.

## What the positive probe does—and does not—prove

`TestFinal11MySqlNativeProbe` independently observes actual native fresh scalar
storage: BIGINT identity prefix, INTEGER, TEXT, BLOB and DATE. Literal `NULL`
strings and BLOB `00FF` are not guessed from PostgreSQL mappings. It also
asserts that 1.1 reads require explicit opt-in rather than leaking through legacy mode.

The diagnostic fixture exposes an additional generic schema limitation: initial
bootstrap creates tables in the selected MySQL database, but subsequent default
queries target `main`. It closes that bootstrap and reopens with explicit
`METADATA_SCHEMA 'ducklake'`; starting directly with that existing database can
instead try to CREATE the already-existing schema. This scoped setup is documented,
not certified as a general initialization or authorization solution. No privilege
escalation, reader bootstrap or library migration is introduced.

Read qualification is provided separately by native/JDBC fixtures, independent
expected values and pristine **passing** MySQL mirrors. No passing native scanner
or writer is inferred from a passing JDBC read. A backend reader cannot recover
precision already discarded by a native producer, so those declared inline types
are rejected instead of presented as lossless.

## Additional native limitations retained as strict gates

- **Schema mutation:** even scalar retype commits fail with `UPDATE syntax not
  supported`; read-only mode rejects unqualified source/target schema evolution.
- **HUGEINT:** native physical storage is DOUBLE; input `9007199254740993` does not
  survive exactly. Signed int128 inline reads are rejected, even if a particular
  small value happened to fit. UHUGEINT uses TEXT and is independently qualified.
- **Fractional timestamps:** TIMESTAMP/MS/NS and microsecond zoned timestamps use
  DATETIME(0), losing fractional data. The strict microsecond assertion remains
  red. TIMESTAMP_S and TEXT-backed TIMESTAMPTZ_NS have separate lossless read tests.
- **JSON:** native materialization fails with an invalid `"JSON"` type expression;
  the direct-JDBC inline type guard stays closed.

`upstreamMySqlNativeTest` preserves the whole original shipped files/digests:
`data_inlining/inlining_double.test`, `default/default_values.test`,
`metadata/row_group_count.test` and `general/ducklake_read_only.test`. Native integer
inline scans can fail `expected INTEGER ... read BIGINT` (even for an empty table),
and native metadata boolean expressions can return `1` where original goldens
require `true`. Repeated writes/flush lifecycle cases also fail. None of these
original expected values is rewritten; no arbitrary partial file is called a pass.

Future native creation/migration/write/scanner support still requires approved
native fixes/builds and independent acceptance. That deferred work does **not**
block the explicitly qualified existing-catalog JDBC read subset. New MySQL
catalogs should remain explicitly on 1.0 unless creation is separately approved;
no library-side creation or upgrade is added anywhere.

Logs: `/tmp/opencode/catalog-r06-mysql-deferred-native-read-scope-final.log`; maintained read run:
`/tmp/opencode/catalog-r06-mysql-existing-read-full-final.log`. Existing R02–R04 failures are
separate and remain parked. See [queue](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md).
