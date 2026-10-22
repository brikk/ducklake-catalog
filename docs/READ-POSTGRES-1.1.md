# PostgreSQL final-1.1 read subset

**0.13.0-SNAPSHOT development tier, not full R06 closure.** Direct PostgreSQL JDBC
now supports an explicit root-schema read subset with `enableFinal11Reads=true`.
MySQL remains rejected. No 1.1 write or automatic migration is enabled.

```kotlin
catalog.readSession(java.util.function.Supplier {
    val profile = catalog.readBackendProfile()
    val snapshot = profile.snapshotId
    val table = catalog.getTable("main", "events", snapshot) ?: error("Not found")
    val columns = catalog.getTableColumns(table.tableId, snapshot)
    val rows = catalog.getInlinedDataInfos(table.tableId, snapshot).flatMap {
        catalog.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
    }
    rows
})
```

Use the normal direct PostgreSQL URL/user/password configuration. Tables must be
in the supported connection default schema/search path; custom metadata-schema or
domain-based layouts are not certified. Every read requires an owned session.
READ_ONLY and REPEATABLE READ are required, not best-effort settings, for this route.
The transaction uses `SET LOCAL intervalstyle='postgres'` to make native INTERVAL
text lossless and predictable; rollback restores the prior session/role settings.
No session-zone-dependent promotion or arbitrary default SQL is evaluated.

## Qualified projections

- Stored finite values whose nested schema shape and **every nested field ID** are
  unchanged. STRUCT text is decoded by names, never physical positions; LIST/MAP,
  NULL parents/elements, empty containers, nested dates/BLOB/JSON and literal NULL
  strings remain distinct. MAP keys are currently Boolean, integer, VARCHAR, UUID
  or DECIMAL; other key families are explicitly unqualified.
- Top-level column add/drop/reorder/rename by stable IDs, qualified scalar initial
  defaults and exact root Boolean/integer widening under the existing native
  conversion allowlist. A stored NULL is not replaced by a default. Same-named
  replacement columns do not inherit old values.
- Historical snapshots and raw/decoded CDC using their intended target columns and
  original row/begin/end identity. Requested columns are re-resolved from catalog
  metadata, not caller-supplied names/types/defaults.
- Ordinary root-schema file/count/partition/snapshot reads with legacy MIN/MAX
  suppression and retain-all range pruning. Row-ID starts remain nullable. Missing
  required descriptor facts fail rather than become zero/false. The historical
  `fileOrder=0` adapter remains only a reserved/unused legacy placeholder—native
  DuckLake writes SQL NULL there; it is not a qualified physical ordering fact.

**Still rejected:** nested field add/drop/rename/reorder/type/ID replacement,
non-integral scalar promotions (including decimal/float/date/timestamp changes),
whole-container non-NULL initial defaults, VARIANT/other unqualified types, temporal
infinities and TIME 24:00. Raw reads cannot bypass value guards. Even a missing
column with a NULL initial default does not certify an unsupported type.

Any nested-schema history remains rejected by ordinary APIs. The DuckDB-specific
`readMetadata()` and `readHierarchy()` companions are **not qualified on PostgreSQL**
yet and fail explicitly; their unported SQL is never sent to PostgreSQL.

## Physical storage and raw values

Native PostgreSQL storage is validated **before** interpreting a payload:
SMALLINT for logical int8/int16, INTEGER for uint8/uint16/int32/NULL, BIGINT for
int64/uint32, BYTEA for VARCHAR/JSON/BLOB, unbounded VARCHAR for nested/wide-integer/
date/nanosecond/timestamp families, and native Boolean/UUID/REAL/DOUBLE/NUMERIC/
TIME/TIMETZ/INTERVAL where supported. Numeric precision/scale and native temporal
precision must match. Domain-typed or narrowed VARCHAR representations are rejected.
The actual final metadata shape and BIGINT inline identity prefix are checked too.

The JVM checks the **source** logical domain before widening: a stored SMALLINT 128
cannot masquerade as an old int8 simply because the new target is INTEGER. Integer,
decimal and temporal ranges/precision, UTF-8, JSON syntax, complete/unique STRUCT
fields, non-NULL/unique MAP keys and logical NULL values are validated explicitly.
Known nested `CAST('literal' AS VARCHAR)` storage wrappers are interpreted as data,
never SQL. No rounding, partial match, replacement character or TRY_CAST-to-NULL
is used to hide invalid storage.
TIMETZ precision/offset domains are checked too; finite numeric text cannot silently
overflow to floating infinity. Seconds/millisecond timestamps beyond the qualified
native microsecond display range are explicitly unsupported, even if Java can hold
the calendar value.

Prefer decoded APIs. Raw unchanged roots keep their PostgreSQL storage shape:
BYTEA text/BLOB, native numeric values and named nested text. Native temporal roots
are read as validated text so JDBC cannot lose microseconds/offsets. Added defaults
and integer-widened roots return the corresponding PostgreSQL target-storage shape;
mutable BLOB defaults are independently copied per result. This is a newly
qualified opt-in PostgreSQL contract, not a change to legacy 0.4/1.0 raw APIs.

Text/BYTEA payload projections transfer at most 1 MiB plus one guard character/byte
and reject overflow. Value/JSON traversal is at most 65,536 nodes/tokens; JSON depth
is at most 64. Other schema/type/default budgets remain in force. Standard JSON is
validated without changing its exact text; extended JSON syntax or oversized
representations are not blanket-certified.

## Truthful provenance without breaking the old capability record

`JdbcDucklakeCatalog.readBackendProfile()` is an additive portable observation API
available on all four currently qualified routes. Its nullable source ID,
unsigned-extension setting and extension revisions can be **not applicable or
unavailable**, never fabricated DuckDB facts. PostgreSQL reports the actual server
`version()` and driver metadata with `accessRoute=postgres-jdbc`; DuckDB-only source/
extension/unsigned facts are explicit NULL. API availability flags distinguish this
subset from unavailable rich metadata/hierarchy and never grant writes.

The existing DuckDB `DucklakeReadMetadataCapabilities` record, Java constructors,
record components and JSON are unchanged. A separate profile is necessary because
its primitive unsigned flag cannot represent PostgreSQL's not-applicable value.
No plugin implementation or coordinated rollout is implied by this additive API.

Qualification keeps core `d54cbbe3df`, signed DuckLake `417a9117f5`, JDBC 2.0.0.0
and the existing corpus pin. It additionally records the already-used signed
PostgreSQL scanner revision `c0f5a3c0c4`; no existing dependency/extension pin was
upgraded. Native probes and JDBC reader assertions are separate; genuine native
1.0→1.1 migration is performed only by the owned test writer.

See [backend status](READ-BACKENDS.md), [support/evidence](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md)
and [pristine upstream adoption](../dev-docs/UPSTREAM-1.1-READ-TESTS.md). Remaining R06
work includes broader PostgreSQL evolution/metadata/hierarchy and MySQL, followed
by R07 acceptance. Existing R02–R04 native failures remain parked and distinct.
