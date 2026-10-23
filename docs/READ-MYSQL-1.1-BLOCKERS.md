# MySQL final-1.1 native acceptance blockers

**R06 cannot close on the maintained artifacts.** MySQL opt-in reads remain
rejected before connecting. The earlier PostgreSQL implementation gaps are now
ported for the guarded read tier; these MySQL failures are concrete native gates,
not a reason to fabricate capabilities or retag 1.0 metadata.

Locked diagnostic lane: DuckDB JDBC 2.0.0.0, core `d54cbbe3df`, signed DuckLake
`417a9117f5`, signed MySQL scanner `f7fc0d4271`, existing MySQL 8.4 test image.
The scanner revision is additionally recorded, not upgraded. No dependency,
corpus, format-layout pin, writer guard or migration default changes.

## Reproduce

```sh
mise exec -- ./gradlew :ducklake-catalog:final11MySqlNativeTest \
  --rerun-tasks --no-configuration-cache --no-daemon
```

Final result: **2 tests, 2 failures, 0 errors, 0 skips**. This strict task is separate
from `check`, like the existing R02–R04 native acceptance tasks; its failures are
not expected-error assertions, skipped tests or shipping passes. The maintained
suite's green result must never be presented as MySQL final-1.1 qualification.

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
asserts that the library's MySQL guard remains closed.

The diagnostic fixture exposes an additional generic schema limitation: initial
bootstrap creates tables in the selected MySQL database, but subsequent default
queries target `main`. It closes that bootstrap and reopens with explicit
`METADATA_SCHEMA 'ducklake'`; starting directly with that existing database can
instead try to CREATE the already-existing schema. This scoped setup is documented,
not certified as a general initialization or authorization solution. No privilege
escalation, reader bootstrap or library migration is introduced.

These scalar observations do not qualify a MySQL reader, nested payloads, migration,
rich metadata/hierarchy or cleanup. A backend adapter cannot repair the native
writer/migrator while preserving these acceptance requirements and fixed artifacts.
Finishing R06 requires an approved, separately pinned native MySQL fix/build and
then complete native/JDBC qualification. No upgrade is made automatically.

Logs: `/tmp/opencode/catalog-r06-mysql-strict-final.log`; standard verified run:
`/tmp/opencode/catalog-r06-evolution-full-final.log`. Existing R02–R04 failures are
separate and remain parked. See [queue](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md).
