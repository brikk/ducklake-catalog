# Upstream follow-up execution — 2026-10-14

Production pins and 1.1 write guards remain unchanged. Each verified step is
committed independently; native qualification failures are not waived by JVM tests.

## 1. Old inline schemas, expiration and flushes

Four regressions reproduced failures in the original JVM implementation using
real native DuckDB/JDBC 1.5.6 PostgreSQL catalogs, not edited registry rows:
lazy inlining after ALTER, lower inline versions inside multi-table commits,
expiry of boundary evidence, and silently empty reads after external expiry.

Resolution now follows the table's preceding schema change. Old inline versions
below their committing schema are distinguished using the immediately preceding
snapshot. An exact global-version snapshot can prove an older boundary when
available. Missing evidence is not an excuse to guess a column tree.

Before our expiry removes that evidence, a bounded, keyset-paged preflight records
resolvable nonempty inline aliases in the existing `ducklake_schema_versions`
ledger, inside the owned expiry transaction. It uses 256-row pages, a 65,536-entry
traversal budget and identifier/version projections only; no file paths or row
payloads are transferred. No alias writes happen until preflight completes.
There is no new format, automatic migration or snapshot. Repeated expiry does not
duplicate aliases. If an external cleaner already erased an ambiguous boundary,
nonempty reads/change feeds and our expiry fail explicitly, preserving the data
instead of returning empty results or NULL substitutions.

Additional coverage verifies rename/field identity, two physical inline versions,
pinned one-connection reads, JVM flush to real Parquet and native read-back with
the original row ID. Alias preservation/repeated expiry runs on all five routes:
PostgreSQL, MySQL, local DuckDB, native Quack and Java Quack.

Verification: `mise exec -- ./gradlew check -Dducklake.corpus.dirs=all
--no-configuration-cache` passed **543 JUnit tests** (452 catalog, 51 SLT, 40 replay),
zero failures/errors/skips, detekt and Java 17 checkAbi. The selected full corpus
retains its verified **9,697 records**, zero failures, with existing harness skips.

**Native defect remains:** the strict disposable
`dev-docs/probes/ducklake_old_inline_schema.py` exits 1 on maintained Python 1.5.6,
core `069cc9f9b5`, DuckLake `ac7595b0`. Physical inline `(1,11)` becomes native
`(1,NULL)` after unrelated DDL, and native flush persists that NULL. This is
#1590's native read/flush failure, not repaired by this JVM change. Do not use
native output as its golden or recommend automatic migration as a workaround.

The public schema-resolution signature is unchanged; its documented boundary is
now table-scoped instead of an unrelated global-version snapshot. Other public
records, wire formats and supported write versions are unchanged.
