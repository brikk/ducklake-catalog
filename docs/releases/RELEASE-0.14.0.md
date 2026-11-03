# 0.14.0 — stored-format read admission and independent 1.0/1.1 replay lanes

All three modules (`ducklake-catalog`, `ducklake-test-corpus-replay`, `slt-format`)
and catalog test fixtures are released together at 0.14.0, built from the
consumer-accepted source `f9dde22299caeced077f9fbbf982a5bfef226d62` (snapshot
`0.14.0-20261103.154002-5`). DuckLake 1.0 is the supported read/write format. Final 1.1
remains explicit, read-only and limited; all 1.1 writes stay disabled.

## Behavior change: catalog reads are admitted by stored format

Reads are admitted by the catalog's stored `ducklake_metadata.version`, checked in the
reader's own view before any layout-dependent read: once per `readSession`, or per
pooled read outside one, so later native migrations are noticed. It is one bounded
lookup; nothing is migrated or rewritten.

- With `enableFinal11Reads = false` (default), **final 1.1 and unknown formats are now
  refused up front**. Previously a 1.1 catalog's Parquet-only tables were read and its
  inline tables failed late with `Unknown inlined metadata layout`.
- New optional lock `DucklakeCatalogConfig.requiredCatalogFormat`:
  `DucklakeCatalogFormat.V1_0` (stored 1.0/0.4) or `V1_1` (stored 1.1; requires
  `enableFinal11Reads`). It applies to reads and writes.
- A refusal throws `DucklakeCatalogFormatNotAdmittedException`, a
  `DucklakeUnsupportedCatalogVersionException` carrying `version` and `requiredFormat`.
  MySQL keeps its existing refusal first.
- `getSpecVersion()` is not subject to admission; use it for diagnostics and lane selection.

See [catalog format admission](../COMPATIBILITY.md#catalog-format-admission-0140).

## Replay harness (all optional; defaults keep 0.13 behavior)

- `executionFormatScope`: after a successful ATTACH whose positively read stored format
  is outside the scope, the file halts (`format-out-of-scope: …`). Its remaining SQL
  never runs, earlier outcomes stand and it never counts as ran-to-end. A 1.0 lane does
  not depend on native 1.1 goldens.
- `mirrorFormatScope`: out-of-scope lakes are measured mirror skips. Inspection errors,
  missing or ambiguous versions fail loudly.
- `ReplayOracleSqlBinder`: backend adaptation of oracle SQL (statements and queries, all
  named connections) with live lake context, e.g. metadata schema `main` → `public`
  on PostgreSQL.
- `MetadataRewriteScope.CONNECTION_ENV_ONLY`: rewrite only `DUCKLAKE_CONNECTION`-derived
  lakes, like upstream backend configs.
- All 0.13.x constructors remain binary-compatible.

## Also included

- Drop-time cleanup of a dropped table's unversioned table/column stats.
- `DucklakeCatalog.ensureRecommendedIndexes()` (PostgreSQL/MySQL, explicit, idempotent);
  see [recommended indexes](../RECOMMENDED-INDEXES.md).
- Pool settings: `minIdleCatalogConnections`, `catalogConnectionIdleTimeout`,
  `catalogConnectionMaxLifetime`, `catalogConnectionTimeout`. Defaults are unchanged.

Pins are unchanged: DuckDB 2.0.0 / JDBC 2.0.0.1, signed DuckLake `417a9117f5`, native
Quack `f96420ed46`, optional quack-jdbc 0.13.0, corpus `417a9117f590e8141742ac0579d02f6a90162e37`.

## Verification

- **Catalog:** full suite 1,191 tests (1.0 contract), with the four contract-update tests
  passing after the compatibility fixes; `TestCatalogFormatAdmission` 5/5 on real native
  1.0/1.1 catalogs, including a native migration after construction; Java 17 `checkAbi`.
- **Replay:** 92 tests, including 14 scope/binder tests and negative inspection tests.
- **Consumer acceptance on the published snapshot JARs (Trino):** 483/JDK25 1,071 tests and
  484/JDK26 1,072 tests, zero failures. Starter corpus: 75 files, 930 passing records, 28
  verified mirrors, zero failed records/mirrors, and 14 explicit 1.1 files halted by the
  1.0 execution scope.
- The full corpus was not rerun.
