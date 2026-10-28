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

Catalog-owned logs and diagnostic exceptions redact URLs containing user-info or
options, including their diagnostic URL getters. Options are omitted conservatively,
not matched against a password-name allowlist. The configured connection URL is
unchanged; a redacted diagnostic value must not be reused to connect. Driver/pool
logging remains governed by those components' own settings.

Local/direct JDBC routes expect initialized metadata. The native Quack route uses
DuckLake ATTACH and can bootstrap missing metadata. This library does not enable
automatic 1.1 migration. Native Quack bootstrap explicitly selects 1.0; when creating
other read/write lakes with native DuckDB 2.0, pass
`DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE` to ATTACH, because the engine's
default for new lakes is 1.1. Full per-file encryption support is not provided; file
registration into encrypted catalogs is rejected.

When an initialization query finds missing DuckLake metadata tables or an empty
`ducklake_snapshot`, read planning, CREATE and file registration report
`DucklakeCatalogNotInitializedException` with bootstrap guidance. File preflight
also checks for a snapshot before accepting empty fragment lists. No metadata is
initialized by these guards. Missing columns, permission/connection failures and
unsupported catalog versions are not reclassified as initialization failures;
existing read-session write rejection and uncertain-COMMIT classifications remain
unchanged. Native Quack's legacy ATTACH/bootstrap remains a separate boundary.

## Limits and safe operation

- A backend smoke test does not establish every operation/type combination.
  MySQL column/field add, drop, rename and type changes reject with
  `DucklakeInvalidOperationException` before metadata mutation when the table has
  registered inline storage, including empty or previously flushed inline tables.
  MySQL physical inline DDL implicitly commits; supporting these changes requires
  a safe table-preparation/publication protocol and qualified MySQL type rendering.
  Tables without inline registrations retain their metadata-only mutation path.
  An existing-field `addField(..., ignoreExisting=true)` remains a no-op.
- Use snapshot-aware materialization APIs. In 0.13, snapshotless flush/DELETE/MERGE
  forms explicitly reject before mutation while retaining their linking signatures.
  Follow the [materialization-snapshot migration](MATERIALIZATION-SNAPSHOTS.md);
  consumers must capture the read boundary before preparing complete, lineage-preserving output.
- Prefer bounded cleanup pages and exact acknowledgements. Metadata read sessions
  are not ownership leases or protection against physical deletion after planning.
  Enforce traversal budgets, age/reference checks and writer coordination before
  deleting files.
- Do not use the deprecated per-table `listReferencedFilePaths` for orphan detection:
  it mixes table-relative and catalog-root-relative paths without a discriminator.
  See [file-reference namespaces and bounded-maintenance limits](FILE-REFERENCE-NAMESPACES.md).
- Read the [COMMIT outcome contract](COMMIT-OUTCOME-CONTRACT.md) before adopting
  0.13.0-SNAPSHOT. An uncertain commit is not permission to retry or delete output.

## Statistics and pruning

Legacy min/max pruning interprets FLOAT/DOUBLE statistics and predicate bounds at
the declared binary precision. Equivalent decimal spellings compare equally;
adjacent values remain distinct. Non-finite, malformed or binary-overflowing float
bounds remain unknown, and the existing `contains_nan` checks still apply.
Integer/decimal comparisons retain arbitrary-precision decimal keys.

TIME/TIME_NS `24:00:00` has an end-of-day statistics key above the last tick before
midnight. It is not normalized to `00:00:00`; malformed clocks fail open. The shared
bound accumulator and numeric swapped-bound detection use the same typed comparison.
This is a statistics correction, not a new Java value representation for TIME 24:00
or permission to enable min/max pruning in the conservative final-1.1 tier.

## Legacy inline reads

Ordinary legacy `readInlinedData` / `readInlinedDataDecoded` reads apply qualified
scalar **initial** defaults to root column IDs absent from an older physical inline
schema on DuckDB, native/direct-Java Quack and PostgreSQL. Defaults and types come
from catalog metadata at the requested snapshot. Later INSERT defaults and caller
DTO values cannot replace them; stored SQL NULLs stay NULL. This reuses the checked
1.1 literal projection/decoder, without evaluating SQL expressions or changing
present-column/nested evolution behavior. Synthesized raw defaults use its typed
text/byte representations; existing raw payloads keep their JDBC representation.

Non-NULL whole-container defaults, malformed/unknown histories and unsupported
types fail explicitly. MySQL missing-column default projection also rejects; its
qualified schema-evolution/producer work remains separate. Existing-column MySQL
reads remain available.

With final-1.1 opt-in disabled, empty ordinary projections preserve cardinality:
one empty value list per live row, with the same snapshot filtering. Use
`countInlinedRows` when only the count is needed. Opt-in ordinary list reads retain
their existing empty-projection rejection; the decoded page API supports
identity-only pages.

**Legacy CDC keeps its documented NULL-fill contract** for fields absent from a
source schema, and empty payload projections preserve CDC identity. The final-1.1
CDC tier retains its separate qualified initial-default behavior.

## DuckDB 2.0 / DuckLake 1.1

The [conservative final-1.1 reader](READING-1.1.md) requires an owned read session
on a [qualified DuckDB-backed route](READ-BACKENDS.md): direct Java Quack, read-only
local DuckDB or native Quack. Direct PostgreSQL has a separate
[guarded evolution tier](READ-POSTGRES-1.1.md). MySQL separately qualifies
[read-only existing-1.1 catalogs](READ-MYSQL-1.1.md) under its narrower scalar/layout
contract; native creation, upgrades, writes and scanner SQL remain unqualified.
Legacy APIs still reject nested-schema history and suppress bounds/pruning; the
explicit [hierarchy companion](SCHEMA-HIERARCHY.md) handles bounded names/paths.
This is qualified on official 2.0 artifacts, **not blanket 1.1 or connector support**.
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
