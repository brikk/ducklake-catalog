# Conservative DuckLake 1.1 reads

Available on **0.13.0-SNAPSHOT development code**, not stable 0.12.0. This is an
explicit, limited read tier over **direct Java Quack** (`quack-jdbc 0.8.0`), qualified
against official DuckDB **2.0.0**, DuckLake `417a9117f5` and Quack `f96420ed46`
(protocol v3). It is not full 1.1 support,
other-backend certification or permission to write/migrate a catalog.

## Enable and use an owned read session

```kotlin
val catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
    catalogDatabaseUrl = "jdbc:quack://host:9494"
    catalogDatabasePassword = token
    dataPath = "/shared/lake"
    enableFinal11Reads = true
})

catalog.use { c ->
    val plan = c.readSession(java.util.function.Supplier {
        val snapshot = c.currentSnapshotId
        val table = c.getTable("main", "events", snapshot) ?: error("Table not found")
        val columns = c.getTableColumns(table.tableId, snapshot)
        val files = c.getDataFiles(table.tableId, snapshot)
        val inline = c.getInlinedDataInfos(table.tableId, snapshot)
            .filter { it.hasLiveRows }
            .flatMap { c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) }
        files to inline
    })
    // The engine must scan plan.first with the catalog's lineage/delete/schema rules.
    // plan.second contains only inline rows, not the whole table.
}
```

The flag defaults to **false**. Use **`readSession` for all catalog reads**;
metadata access outside it fails explicitly. This makes layout/hierarchy
validation and the data queries see the same metadata snapshot. Validation is
repeated for each new session, never cached across migrations or native commits.
Only `jdbc:quack:` is accepted for this opt-in mode. Applications supply the Java
driver; no embedded DuckDB JDBC is required.

## Supported scope and deliberate fallbacks

- Real final **`1.1`** inline names, scalar/nested STRUCT/LIST/MAP values, NULLs,
  row IDs, begin/end snapshots, historical versions and CDC candidates.
- File/delete descriptors, partitions, schema/column metadata, snapshot listings,
  timestamp lookup and existing count APIs. Counts retain their existing gross/
  advisory semantics; they are not a new exact `COUNT(*)` optimization contract.
- Existing public records, Java constructors and fragment JSON remain unchanged.
  Advisory 1.1 row-group counts are omitted, not fabricated as zero.
- Missing columns in older inline schemas project their **qualified scalar initial
  defaults**, using the target snapshot's metadata. Ordinary raw/decoded reads and
  CDC share the same projection. This never replaces an existing stored NULL or
  substitutes a later insert default for the original initial default.
- Guarded inline evolution maps fields by stable IDs through add/drop/rename/reorder,
  including STRUCTs inside LISTs/MAPs. Same-named replacement fields do not reuse old
  values. NULL parents/elements and empty containers are preserved. Physical STRUCT
  field order may differ; names and leaf types must still match the source metadata.
- Qualified scalar promotions use native numeric widening and non-timezone-dependent
  DATE/timestamp precision promotions. Raw/decoded reads and CDC use the target
  snapshot's schema; historical reads retain their historical names/types.
- Finite TIME/TIME_NS/TIMETZ, INTERVAL, TIMESTAMPTZ_NS and SQL NULL-typed values,
  recursively within STRUCT/LIST/MAP. Nanoseconds, offset seconds and independent
  interval month/day/microsecond components are preserved. Finite BCE/large native
  dates and timestamps decode without Java driver sentinel values.
- Legacy column statistics expose **NULL MIN/MAX endpoints**, even when a native
  exactness flag is true. Range-pruning requests return all active file IDs. The
  old DTOs cannot carry exactness, so this sacrifices optimization rather than rows.
- Catalogs with **any nested-schema history** are rejected, including retired child
  rows. Missing `parent_schema_id` is an error, not an assumption that every schema
  is a root. A parent-aware companion API remains separate work.

## Explicitly unsupported inline projections

The reader rejects these rather than silently returning wrong values:

- Missing source columns with unqualified initial-default types, including whole
  STRUCT/LIST/MAP defaults (also rejected by native column-default DDL). Qualified
  literal scalar defaults on added nested fields are supported under the rules above.
- Narrowing, text-to-number casts, container-kind changes and timezone-dependent
  promotions without a qualified session-zone contract. These are not arbitrary
  user-supplied ALTER expressions or a generic coercion engine.
- Physical leaf-type drift or missing/extra/renamed physical fields. Reordering alone
  is allowed, but the reader never casts mismatched physical payloads into apparent
  validity. Column/depth/type/projection budgets fail explicitly when exceeded.
- Recorded non-literal or unknown initial-default histories for missing fields.
  In particular, shipping DuckLake may wrongly accept a non-literal added STRUCT
  field; this reader rejects that history rather than returning invented NULLs.
- VARIANT (including nested/NULL-valued VARIANT), temporal infinities and TIME 24:00
  (also TIME_NS/TIMETZ). These have no qualified canonical Java representation here;
  raw payload reads reject them too. This is not a conversion to midnight, a finite
  sentinel or a driver-internal VARIANT storage map.
  Qualified scalar families are Boolean, signed/unsigned integers, floating point,
  decimal, VARCHAR/JSON/BLOB, UUID, finite date/time/timestamp/interval families and
  SQL NULL, recursively within STRUCT/LIST/MAP. Native-disallowed TIME → TIME_NS
  ALTER is not treated as a supported promotion.
- Empty ordinary inline projections: use `countInlinedRows` for counts. Identity-only
  CDC can still use its row/snapshot metadata.

Requested columns must come from the target snapshot's metadata. Stored NULLs
remain NULL; a literal default string `NULL` is not an SQL NULL sentinel. The adapter
never evaluates arbitrary default expressions: initial-default text is one bound
VARCHAR value strictly cast to a whitelisted target type, not SQL source. Malformed
values, invalid casts and overflow fail instead of becoming NULL. Native
query failures and missing physical system columns propagate instead of becoming
empty reads. Raw-value reads do not bypass these safety checks.

## Temporal value contract and consumer handoff

Decoded reads and decoded CDC use `LocalTime`, `OffsetTime`, `LocalDate`,
`LocalDateTime`, UTC `OffsetDateTime` and `DucklakeInterval(months, days, micros)`.
Intervals do not collapse months into days or elapsed hours into calendar days.
Malformed interval tokens, partial matches and component overflow fail as corruption.

**Intentional opt-in raw contract change in 0.13.0-SNAPSHOT:** every final-1.1
projected root containing a temporal leaf returns native VARCHAR text, including
the entire STRUCT/LIST/MAP root. Raw CDC uses the same rule. Even DATE/timestamp
roots formerly returned as driver objects now return text; non-temporal roots and
legacy-format raw paths are unchanged. Physical type validation and native schema
evolution happen before text projection. Raw text is decoded for validation, so
unsupported edges cannot bypass the guards by requesting raw values.

Trino/Doris agents must use decoded APIs or explicitly adopt this text contract
before enabling the opt-in reader; neither plugin is claimed updated or verified
by this library increment. Published records, constructors and fragment JSON do
not change. See the [consumer handoff](../dev-docs/DUCKLAKE-1.1-CONSUMER-CONTRACT.md).

## No writes, migration or connector rollout implied

**Shipping-native gaps remain.** The newer strict upstream R02 gates currently fail
for legacy STRUCT read/flush, non-literal added-field rejection and a same-transaction
STRUCT crash. JVM read safeguards do not fix those native writer/maintenance paths.
See the [R02 execution record](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md) before rollout.
The separate R03 interval persistence gate also fails: native flush changes
`1 day 100:00:00.123456` to `5 days 04:00:00.123`, losing sub-millisecond precision
and changing day/microsecond components. Qualified inline reads do not repair
native Parquet persistence. Negative/extreme component reader fixtures are not a
claim that native INSERT/flush accepts them.

All **1.1 mutations remain blocked**, including file registration, DDL, ANALYZE,
expiry and cleanup acknowledgements. The reader never bootstraps or migrates a
catalog. Coordinate native migration externally and retain a tested backup; older
engines cannot necessarily reopen a `.db` after a newer engine writes it.

The catalog is metadata/inline access, not a Parquet query engine. Trino/Doris agents
must adopt the flag/session and projection contracts and independently qualify
their physical scans, CDC filtering and worker/coordinator behavior.
The session is not a physical-file lease after planning; existing retention/grace
and cleanup coordination obligations still apply.

See [read verification](../dev-docs/FINAL-1.1-READ-RESULTS.md),
[compatibility](COMPATIBILITY.md) and the
[COMMIT outcome contract](COMMIT-OUTCOME-CONTRACT.md).
