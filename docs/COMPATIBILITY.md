# Compatibility and transports

## Supported lane

- Latest stable catalog release: **0.12.0**; development: **0.13.0-SNAPSHOT**.
- Catalog format: **DuckLake 1.0**; a [limited final-1.1 reader](READING-1.1.md) is
  explicit opt-in on development code. DuckLake 1.1 writes remain disabled.
- Maintained development DuckDB: **2.0.0**; JDBC: **2.0.0.0**. Stable 0.12.0 retains its historical pins.
- Optional direct Java Quack JDBC: **0.8.0**, qualified against the maintained server.
- Published libraries target **Java 17**. Building the repository requires JDK 25.
- Applications supply their JDBC drivers and access to the data-file location.

| Metadata route | `DucklakeCatalogConfig.catalogDatabaseUrl` | Notes |
|---|---|---|
| PostgreSQL | `jdbc:postgresql://host/catalog` | Supply the PostgreSQL JDBC driver and separate credentials |
| MySQL | `jdbc:mysql://host/catalog` | Supply the MySQL JDBC driver; transactional-DDL limits still apply |
| Local DuckDB | `jdbc:duckdb:/path/catalog.db` | DuckDB JDBC 2.0.0.0 |
| Native Quack | `jdbc:duckdb:quack://host:9494?metadata_catalog=lake_meta` | DuckDB JDBC and native Quack/DuckLake extensions |
| Direct Java Quack | `jdbc:quack://host:9494` | `dev.brikk.duckdb:quack-jdbc:0.8.0`; optional `/existing-server-catalog` |

Pass Quack tokens in `catalogDatabasePassword`, not shared URLs. The direct Java
route uses ordinary remote JDBC transactions and does not require embedded DuckDB
in the client process. Quack serves metadata, not Parquet data access.

Local/direct JDBC routes expect initialized metadata. The native Quack route uses
DuckLake ATTACH and can bootstrap missing metadata. This library does not enable
automatic 1.1 migration. Native Quack bootstrap explicitly selects 1.0; when creating
other read/write lakes with native DuckDB 2.0, pass
`DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE` to ATTACH, because the engine's
default for new lakes is 1.1. Full per-file encryption support is not provided; file
registration into encrypted catalogs is rejected.

## Limits and safe operation

- A backend smoke test does not establish every operation/type combination.
  In particular, MySQL implicit DDL commits require care around physical inline
  table creation; generic rollback errors are not proof that DDL effects vanished.
- Use snapshot-aware materialization APIs. Deprecated flush/delete/rewrite
  overloads have weaker planning-boundary protection; deprecation is not a safety
  guarantee. Consumers must provide complete payloads and preserve row lineage.
- Prefer bounded cleanup pages and exact acknowledgements. Metadata read sessions
  are not ownership leases or protection against physical deletion after planning.
  Enforce traversal budgets, age/reference checks and writer coordination before
  deleting files.
- Read the [COMMIT outcome contract](COMMIT-OUTCOME-CONTRACT.md) before adopting
  0.13.0-SNAPSHOT. An uncertain commit is not permission to retry or delete output.

## DuckDB 2.0 / DuckLake 1.1

The [conservative final-1.1 reader](READING-1.1.md) requires direct Java Quack and
an owned read session, rejects nested-schema history, and suppresses legacy bounds/
pruning. This limited tier is qualified on official 2.0 artifacts, **not general
1.1 schema/type/statistics/nested-schema support**.
Passing pinned native-preview CI is not passing every strict correctness gate.

The [shipping qualification](../dev-docs/UPGRADE-DUCKDB-2.0.0.md) covers maintained
2.0 JDBC/local/native-Quack and Java Quack 0.8.0 routes separately, including protocol
v3 without embedded DuckDB in the direct Java reader. Default-off final inline reads
and all 1.1 writes remain guarded. Existing constructors and wire shapes are unchanged.

Native probes have reproduced CDC, inline-flush, statistics, BLOB, lineage and
bucket-pruning failures on specific sampled engines. Results depend on actual
core/extension hashes, not just a version string or a main-branch merge. See the
[developer acceptance runbook](../dev-docs/DUCKLAKE-1.1-ACCEPTANCE.md) for qualification.

**Rollback requires a tested, consistent backup of metadata and data.** A 2.0
write/checkpoint can upgrade local `.db` storage even while the DuckLake format
remains 1.0. Swapping JDBC versions or editing a catalog-version string is not a
supported downgrade. Upgrade native Quack peers together; use normal remote TLS
configuration, not the loopback-only plaintext Docker test fixture settings.
