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
Unknown history kinds reject regardless of whether an initial value is non-NULL.
An expression kind without a materialized initial value is ambiguous and rejects.
Known INSERT-expression metadata does not invalidate an independently materialized
initial literal: native stores the VARCHAR literal `NULL` that way. Only the initial
value is decoded; the expression text is never executed. Invalid types, malformed
literals, non-NULL whole-container defaults, oversized column text and invisible
IDs/snapshots also fail explicitly.

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

Initial 3fd75dc verification: 13 new tests pass across the four supported routes plus explicit
MySQL refusal. The full maintained run passes **852 JUnit tests** (703 catalog,
51 SLT, 98 replay) and **86 separate direct-Java tests**, with zero failures/errors/
JUnit skips, plus lint, frozen ABI/wire controls and Java 17 checks (7,795 classes,
17 artifacts). Full corpus scope remains 17,602 passing records with unchanged
exclusions. Log: `/tmp/opencode/catalog-r07-file-defaults-full.log` (21m52s).
Strict native gates were not rerun or waived by this additive API verification.

## Consumer-reported history-kind correction

The3fd75dc/0058e8d/78c2a12 candidates checked unknown history kinds only when the
initial value was NULL, so a non-NULL `42` with kind `unknown` still decoded. Trino
supplied a real worker-SQL repro. No expression execution or wrong returned value
was established; this was a fail-closed contract discrepancy.

Unknown kinds now reject independently of literal presence in the shared validator,
covering file defaults and raw/decoded inline/CDC projections. A new real native
four-route regression first fails on all four old implementations, then passes.
It also observes native `initial_default='NULL', default_value_type='expression'`
and verifies that valid literal stays supported. Clearing that initial value still
rejects ambiguous expression history; changing a later INSERT default does not
erase the original materialized literal. NULL/empty legacy kind encodings remain
compatible. The SQL default/expression text is never evaluated.

The corrected full run passes **903 maintained JUnit tests** (754 catalog,51 SLT,
98 replay) plus **86 direct-Java tests**, zero failures/errors/JUnit skips and
17,602 corpus records with unchanged exclusions. Lint/frozen ABI/wire/Java17 pass
(7,810 classes/17 artifacts). Red repro log:
`/tmp/opencode/catalog-r07-unknown-default-history-red.log`; final:
`/tmp/opencode/catalog-r07-unknown-default-history-full.log` (24m20s). Native strict
gates remain unwaived/not rerun. Original candidate bundles remain immutable;
consumers must checksum-qualify the new bytes and restore their negative assertion.
