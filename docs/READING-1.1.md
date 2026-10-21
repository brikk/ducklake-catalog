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
- Legacy column statistics expose **NULL MIN/MAX endpoints**, even when a native
  exactness flag is true. Range-pruning requests return all active file IDs. The
  old DTOs cannot carry exactness, so this sacrifices optimization rather than rows.
- Catalogs with **any nested-schema history** are rejected, including retired child
  rows. Missing `parent_schema_id` is an error, not an assumption that every schema
  is a root. A parent-aware companion API remains separate work.

## Explicitly unsupported inline projections

The reader rejects these rather than silently returning wrong values:

- Missing source columns with unqualified initial-default types, including whole
  STRUCT/LIST/MAP defaults (also rejected by native column-default DDL). Nested-field
  defaults that require schema evolution remain part of the restriction below.
- Type evolution, including changed nested field structure/names, requiring a
  source-to-target conversion. Top-level renames with stable field IDs/types work.
- Physical payload types/order that differ from the logical source metadata,
  including legacy reordered STRUCT fields. The adapter rejects these rather than
  trusting positional JDBC attributes or silently coercing the data.
- TIME/TIME_NS/TIMETZ, INTERVAL, VARIANT and other unqualified inline payload types.
  Qualified scalar families are Boolean, signed/unsigned integers, floating point,
  decimal, VARCHAR/JSON/BLOB, UUID, DATE and timestamp families, recursively within
  STRUCT/LIST/MAP. This is not a claim covering every temporal edge value.
- Empty ordinary inline projections: use `countInlinedRows` for counts. Identity-only
  CDC can still use its row/snapshot metadata.

Requested columns must come from the target snapshot's metadata. Stored NULLs
remain NULL; a literal default string `NULL` is not an SQL NULL sentinel. The adapter
never evaluates arbitrary default expressions: initial-default text is one bound
VARCHAR value strictly cast to a whitelisted target type, not SQL source. Malformed
values, invalid casts and overflow fail instead of becoming NULL. Native
query failures and missing physical system columns propagate instead of becoming
empty reads. Raw-value reads do not bypass these safety checks.

## No writes, migration or connector rollout implied

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
