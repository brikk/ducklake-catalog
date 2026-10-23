# PostgreSQL final-1.1 read subset

**0.13.0-SNAPSHOT development tier, not full R06 closure.** Direct PostgreSQL JDBC
supports an explicit root-schema payload subset plus rich metadata/hierarchy with `enableFinal11Reads=true`.
MySQL has a separate [existing-catalog read-only subset](READ-MYSQL-1.1.md).
No 1.1 write or automatic migration is enabled.

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

- Top-level and nested STRUCT add/drop/rename/reorder/replacement by stable IDs,
  including STRUCTs inside LIST/MAP. Source text is decoded by names, never physical
  positions. Replaced same-named grandchildren use their own initial defaults, not
  old values; moved parent/element IDs fail. NULL parents/elements stay NULL and
  empty containers stay empty. Scalar initial defaults never replace stored NULL.
- Native-allowed numeric widening, exact DECIMAL scale/precision extension and
  approximate integer/decimal-to-FLOAT/DOUBLE conversions under the guarded native
  allowlist, at roots and nested leaves. DATE-to-unzoned-TIMESTAMP and increasing
  unzoned timestamp precision are qualified without a session-zone dependency.
  Boolean-to-DECIMAL/FLOAT/DOUBLE remains native-rejected and reader-rejected.
- Scalar MAP keys, including BLOB, floating, temporal and INTERVAL values. Duplicate
  detection uses native scalar equality for binary content, signed zero/NaNs and
  normalized INTERVAL comparisons. BLOB keys retain ByteArray values; callers need
  content-aware byte lookup/comparison rather than Java array identity.
- Historical snapshots and raw/decoded CDC using their intended target columns and
  original row/begin/end identity. Requested columns are re-resolved from catalog
  metadata, not caller-supplied names/types/defaults.
- Ordinary root-schema file/count/partition/snapshot reads with legacy MIN/MAX
  suppression and retain-all range pruning. Row-ID starts remain nullable. Missing
  required descriptor facts fail rather than become zero/false. The historical
  `fileOrder=0` adapter remains only a reserved/unused legacy placeholder—native
  DuckLake writes SQL NULL there; it is not a qualified physical ordering fact.

**Still rejected:** narrowing, arbitrary text casts, implicit timezone-dependent
promotions, container-kind changes, moved LIST/MAP role IDs, composite MAP keys,
whole-container non-NULL initial defaults, VARIANT/other unqualified types, temporal
infinities and TIME 24:00. Floating NaN/infinities and signed zero remain supported;
their exact FLOAT-to-DOUBLE conversion does not certify temporal infinities.
Raw reads cannot bypass value guards. Even a missing
column with a NULL initial default does not certify an unsupported type.

Any parented SQL-schema history remains rejected by ordinary payload/descriptor APIs
(this is separate from supported nested STRUCT column evolution).
The explicit `readMetadata()` data operations and `readHierarchy()` companion now
support PostgreSQL without relaxing those legacy guards. The old
`getReadMetadataCapabilities()` record is **DuckDB-only** and still fails explicitly
on PostgreSQL; use `readBackendProfile()` for nullable provenance/availability.

### Source validation, conversions and raw evolved values

Source values are decoded and domain-checked before widening. DECIMAL conversion
uses exact scale extension, not rounding or approximate binary intermediates; raw
and decoded evolved roots return target-scale `BigDecimal`. FLOAT-to-DOUBLE widens
the stored binary float to `Double`, not its short decimal display: stored FLOAT
`0.1` becomes `0.10000000149011612`. A missing field's initial literal `0.1` is
interpreted under the target schema instead, matching native default semantics.
Stored NULL never becomes a default. Raw reads and CDC use the same validation and
preserve original row/begin/end snapshot identity. Native 1.0→1.1 fixtures and pinned
publication tests qualify nested evolution too. Dropped source fields are still
validated before projection, so discarding them cannot conceal corrupt storage.

Approximate numeric conversions follow the maintained core's cast order, including
wide-integer double intermediates and correctly rounded nonzero-scale decimal
conversion. Bit-level native comparisons exercise precision midpoints and extremes;
finite overflow to floating infinity is explicitly unsupported, not silently
accepted. Legitimate source values outside a target timestamp domain fail as
unsupported conversions, not fabricated NULLs or source corruption.

Unchanged raw roots retain their physical storage shape. Changed STRUCT/LIST/MAP
and temporal roots return bounded, canonical native-compatible target text, not
unchanged source text or decoded Java maps. Independent native casts verify that
text. Quoted strings resembling `CAST(...)` remain literal data; only unquoted
native storage wrappers are decoded, never executed. Expanded values are limited
to 65,536 nodes; raw evolved text additionally has a 1 MiB character bound.
Non-NULL whole-container defaults and composite MAP keys remain explicit canonical
representation boundaries, not guessed support. Native MAP equality can differ
from Java collection equality, so composite keys are not silently collapsed.

The backend profile's descriptive `inlineProjection` label is now
`postgres-stable-id-evolution` (previously the same-shape numeric tier).
Record components/constructors/JSON fields
are unchanged. This is an expanded opt-in read-tier observation, not writer or
mixed-worker capability negotiation; consumers must adopt the documented subset.

## Rich metadata and bounded hierarchy

The same [raw metadata semantics](READ-METADATA.md) apply: parent IDs, independent
nullable exactness and data/delete row groups are exposed as stored, never treated
as typed bounds, qualified pruning or exact aggregates. Table statistics remain
unversioned transaction-visible observations, not historical aggregates. Native
1.0→1.1 migration leaves old unknown flags/counts unknown, including Puffin counts.

The [hierarchy contract](SCHEMA-HIERARCHY.md) qualifies component/ID-based lookup,
historical names and retained data/delete ownership at each file's original begin
snapshot—even after its snapshot row expires. Schema paths remain catalog-root
relative, not logical-parent relative. NULL directories inherit the base; missing
roots remain unknown; absolute paths remain absolute. No URI/case/Unicode
normalization, physical file lease or deletion permission is granted.

Scheduled-reference cursors use `(file ID, UTF-8 path bytes, relative flag)` to retain
case/Unicode distinctions even under PostgreSQL nondeterministic collations. They
include future and unknown ages; age-gated deletion batches are not complete known
sets or acknowledgement cursors. Every page/query is SQL-bounded before transfer,
with the common node/depth/path/traversal budgets. Callers must also bound total
enumeration within one owned session. Required native metadata columns/types,
non-domain/unbounded dimensions and microsecond scheduling precision are validated.

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
and numeric-widened roots return the corresponding PostgreSQL target-storage shape;
mutable BLOB defaults are independently copied per result. This is a newly
qualified opt-in PostgreSQL contract, not a change to legacy 0.4/1.0 raw APIs.

Text/BYTEA payload projections transfer at most 1 MiB plus one guard character/byte
and reject overflow. Value/JSON traversal is at most 65,536 nodes/tokens; JSON depth
is at most 64. Other schema/type/default budgets remain in force. Standard JSON is
validated without changing its exact text; extended JSON syntax or oversized
representations are not blanket-certified.

## Truthful provenance without breaking the old capability record

`JdbcDucklakeCatalog.readBackendProfile()` is an additive portable observation API
available on all five currently qualified routes. Its nullable source ID,
unsigned-extension setting and extension revisions can be **not applicable or
unavailable**, never fabricated DuckDB facts. PostgreSQL reports the actual server
`version()` and driver metadata with `accessRoute=postgres-jdbc`; DuckDB-only source/
extension/unsigned facts are explicit NULL. API availability flags distinguish this
payload subset and available rich metadata/hierarchy data operations; they never
grant writes or make the old DuckDB-only capability record portable.

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
now separates qualified existing MySQL reads from [deferred native MySQL work](READ-MYSQL-1.1-BLOCKERS.md), followed
by R07 acceptance. Existing R02–R04 native failures remain parked and distinct;
this PostgreSQL port neither fixes nor waives those shared native gates.
