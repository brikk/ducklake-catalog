# Existing MySQL final-1.1 catalogs: guarded read-only mode

**0.13.0-SNAPSHOT development tier.** MySQL can remain on the maintained 0.4/1.0
lane, or use explicit read-only access to an **already-initialized final-1.1**
catalog. This does not create, upgrade, repair or downgrade a catalog. It enables
no 1.1 writes. The [native writer/scanner/migration failures](READ-MYSQL-1.1-BLOCKERS.md)
remain visible, not fixed or waived by direct JDBC read qualification.
Metadata and data must already be consistent; this reader does not repair partial
state left by failed native mutations.

```kotlin
val config = DucklakeCatalogConfig().apply {
    catalogDatabaseUrl = "jdbc:mysql://host:3306/existing_catalog"
    catalogDatabaseUser = "reader"
    catalogDatabasePassword = "..."
    enableFinal11Reads = true
}
JdbcDucklakeCatalog(config).use { catalog ->
    catalog.readSession(java.util.function.Supplier {
        val profile = catalog.readBackendProfile()
        val snapshot = profile.snapshotId
        val table = catalog.getTable("main", "events", snapshot) ?: error("Not found")
        val columns = catalog.getTableColumns(table.tableId, snapshot)
        catalog.getInlinedDataInfos(table.tableId, snapshot).flatMap {
            catalog.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
        }
    })
}
```

Use read-only database credentials too. Metadata must be in the JDBC URL's default
database; custom metadata namespaces and other MySQL-compatible servers are not
blanket-certified. Qualification uses the existing MySQL 8.4 test lane and pinned
Connector/J 9.7.0, without upgrading either artifact. No DuckDB JDBC/native client
is needed by the direct-JDBC reading process.

## Format and session policy

- `enableFinal11Reads` remains **false by default**. The ordinary MySQL lane retains
  0.4/1.0 compatibility and now explicitly rejects 1.1 or unknown formats instead of
  accidentally reading part of a newer layout. It never retags them as 1.0.
- Opt-in 1.1 reads require an owned `readSession`, server-enforced READ ONLY and
  REPEATABLE READ, UTC session time and utf8mb4. Connector/J pool properties enforce
  UTC/preserve-instants, and actual server variables are checked. Client-only
  read-only flags, incompatible settings or missing metadata fail explicitly.
- Native `ducklake_*` metadata tables must be InnoDB (bounded metadata-table
  enumeration); every used dynamic inline table must be InnoDB too. Required
  column types, signed identity fields, utf8mb4 text and canonical UUIDs are checked.
  TINYINT(1) metadata/payload booleans must be 0, 1 or a permitted SQL NULL; arbitrary
  nonzero values never silently become true. Validation belongs to the owned view
  and is not cached across sessions or external format changes.
- Legacy 0.4/1.0 reads/writes remain available with the flag off. All 1.1 library
  write guards and automatic-migration defaults remain unchanged on **every**
  backend. Explicit native migration is neither performed nor qualified here.

## Qualified inline value subset

Only unchanged selected scalar fields are qualified. Requested IDs/names/types
are resolved from catalog metadata, not trusted caller descriptors.

| Logical values | Required native MySQL storage / read behavior |
|---|---|
| Boolean | TINYINT(1), validated through signed integer transport; decoded Boolean, raw 0/1 Long |
| Signed int8/int16/int32/int64 | Exact signed TINYINT/SMALLINT/INT/BIGINT dimensions and source-domain checks |
| Unsigned uint8/uint16/uint32/uint64 | Corresponding UNSIGNED integer storage; no signed-overflow coercion |
| uint128, UUID, VARCHAR, logical NULL | Native utf8mb4 TEXT; bounded binary transport and strict UTF-8 before interpretation; non-NULL logical NULL is corruption |
| FLOAT / DOUBLE | Exact native finite scalar types; FLOAT widens on the server before text-protocol display can lose bits, then returns Float |
| DECIMAL | Exact native precision/scale; no rounding or approximate intermediate |
| BLOB | Native BLOB, opaque bytes; no UTF-8 interpretation |
| DATE | Native DATE, read as validated text before Java decoding |
| TIMESTAMP_S | Native DATETIME(0), whole-second unzoned text; not inferred micro/nanosecond precision |
| TIME, TIME_NS, TIMETZ, INTERVAL, TIMESTAMPTZ_NS | Native utf8mb4 TEXT retaining clocks/nanoseconds/offsets/interval components; canonical-domain validation applies |

**Rejected inline types:** signed int128/HUGEINT (native storage is DOUBLE and can
round integers), JSON (native materialization fails), STRUCT/LIST/MAP, TIMESTAMP/
TIMESTAMP_US/TIMESTAMP_MS/TIMESTAMP_NS and microsecond TIMESTAMPTZ (native DATETIME(0)
loses their precision), plus other unqualified types. Rejection applies even to a
missing/NULL-default projection; unsupported values do not turn into fabricated NULL.
Schema-changing value projections (renames, retypes and missing-column defaults) remain
unqualified on MySQL: the native mutation path currently fails. Selective reads of
other unchanged fields remain possible. PostgreSQL's broader
evolution contract does not automatically transfer to this route.

Raw values retain validated MySQL storage-shaped values: text stays String, BLOB
stays ByteArray, numeric fields keep their physical shape, FLOAT is a lossless Float,
and DATE/TIMESTAMP_S return text. Prefer decoded APIs. Literal `NULL`, embedded NUL
text, SQL NULL and BLOB bytes remain distinct. Raw reads cannot bypass guards.
Per-cell binary projections transfer at most 1 MiB plus a guard byte (native TEXT/
BLOB are smaller); wider custom physical declarations are rejected rather than
assumed native. Shared temporal infinity, 24:00 and other canonical limits apply.

Historical reads and insert CDC retain original row/begin/end IDs. A separately
labeled owned row-end metadata fixture verifies raw/decoded deletion materialization;
it is **not** a claimed passing native DELETE or migration. No positive 1.1 fixture
is made by changing a 1.0 version string or manufacturing substitute inline DDL.

## Metadata, paths and provenance

The [rich metadata data operations](READ-METADATA.md) and
[bounded hierarchy/path companion](SCHEMA-HIERARCHY.md) are qualified on existing
1.1 MySQL metadata. They preserve independent nullable exactness and row groups,
raw unversioned table bounds, original retained ownership, SQL-schema component
identity and relative/absolute path namespaces. Legacy parented SQL-schema
payload/descriptor APIs remain guarded. File metadata is not a JVM Parquet scan.

Names fold only ASCII letters; byte comparisons preserve case/Unicode variants in
paths even under case/accent-insensitive MySQL collations. Scheduled references
include future/unknown ages and use exact composite cursors. Native scheduling
metadata is DATETIME(0): the API exposes its actual stored whole-second value in
UTC, not lost writer microseconds. Age-gated batches remain separate and add no
acknowledgement, deletion permission or physical-file lease. Query/page/text/depth/
traversal bounds remain in force; consumers must bound complete enumeration too.

Use `readBackendProfile()`: `metadataBackend=mysql`, `accessRoute=mysql-jdbc`,
`inlineProjection=mysql-existing-scalar-reads`, actual server/driver observations,
and explicit NULL for DuckDB-only source/extension/unsigned facts. The old
`getReadMetadataCapabilities()` record stays DuckDB-only and rejects this route.
Existing public records, constructors and JSON fields are unchanged.

This tier does not qualify native scanner SQL, catalog creation/migration, schema
mutation, optimization, connector rollout or any 1.1 writes. Keep new MySQL catalogs
on explicit native format 1.0 with automatic migration false unless their creation
is separately approved. PostgreSQL/DuckDB/Quack retain their qualified 1.1 read
contracts independently. See [backend matrix](READ-BACKENDS.md) and
[execution evidence](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md).

Final forced verification: **797 maintained JUnit tests + 86 separate direct-Java
tests**, **17,602 corpus records**, lint, frozen compatibility/wire and Java 17
checks pass. Existing corpus exclusions stay 100 files/12 records. The deferred
native MySQL tasks remain **6 + 4 failures, zero skips**; they are not counted as
successful reads or waived by this scope. R06's approved read-only scope is complete;
R07 rollout and all write/native-upgrade work remain separate.
