# Decoded initial defaults for missing file fields

Development-only additive API on `JdbcDucklakeCatalog`:

```kotlin
catalog.readSession(Supplier {
    catalog.getMissingFileColumnDefaultDecoded(tableId, stableColumnId, targetSnapshot)
})
```

Requires `enableFinal11Reads=true`, final format 1.1, a visible target snapshot/table
and the stable field ID in its freshly loaded target schema. It is qualified on
local DuckDB, native Quack, Java Quack and PostgreSQL. MySQL missing-field evolution
remains explicitly unsupported; this API does not expand that route's scalar tier.

The value uses the same validated literal-default history, closed type grammar and
decoded leaf-domain checks as inline evolution. Default text is data, never SQL.
The caller supplies no trusted type, name or default text. Later insert defaults do
not replace initial defaults; rename/widening uses the requested target metadata.
Malformed/unknown/expression history, invalid types, non-NULL whole-container
defaults, oversized column text and invisible IDs/snapshots fail explicitly.

SQL NULL is `null`, literal VARCHAR `NULL` is `"NULL"`, BLOB is `ByteArray`, DECIMAL
is `BigDecimal`, and temporal values retain their qualified Java representations.
NULL whole containers stay NULL; this is not whole-container literal support.

**Call only for a physically absent field.** The API does not inspect a Parquet
footer, establish field absence or perform a file scan. A present field containing
NULL must retain its NULL, and an absent nested child inside a NULL parent must not
make the parent non-NULL. File-schema evolution, snapshot filtering and actual
connector conversion still require their own qualification. This is not permission
to evaluate a catalog expression, overwrite stored values, write or migrate.

The ordinary column metadata query now limits final-1.1 string transfer to 16,385
characters per cell and rejects anything over 16,384, alongside its existing
4,096-node schema budget. Legacy 0.4/1.0 behavior and public record shapes remain.
This guard is not a substitute for SQL-paged metadata/inline/CDC APIs or total query
budgets; those connector requests remain separate work.

Native fixtures independently verify real old files and expected decoded defaults,
NULL/literal-NULL/BLOB bytes, temporal precision, decimal precision, rename/widening,
changed insert defaults, negative metadata cases, query recovery and MySQL refusal.
Native reads are an oracle, not a claim of a JVM/Trino/Doris Parquet scan.

Verification: 13 new tests pass across the four supported routes plus explicit
MySQL refusal. The full maintained run passes **852 JUnit tests** (703 catalog,
51 SLT, 98 replay) and **86 separate direct-Java tests**, with zero failures/errors/
JUnit skips, plus lint, frozen ABI/wire controls and Java 17 checks (7,795 classes,
17 artifacts). Full corpus scope remains 17,602 passing records with unchanged
exclusions. Log: `/tmp/opencode/catalog-r07-file-defaults-full.log` (21m52s).
Strict native gates were not rerun or waived by this additive API verification.
