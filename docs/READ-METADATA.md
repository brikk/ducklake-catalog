# Rich final-1.1 read metadata

Available in **0.13.0-SNAPSHOT development code**, through
`JdbcDucklakeCatalog.readMetadata(): DucklakeReadMetadata`. This is an additive,
read-only companion; existing `DucklakeCatalog` methods, records, constructors and
fragment JSON are unchanged. It requires **`enableFinal11Reads=true`**, direct Java
Quack and an owned `readSession`. Other formats/routes fail explicitly, rather than
claiming missing metadata is known. See [setup and reader limits](READING-1.1.md).

```kotlin
catalog.readSession(java.util.function.Supplier {
    val metadata = catalog.readMetadata()
    val capabilities = metadata.getReadMetadataCapabilities()
    val snapshot = capabilities.snapshotId
    val schemas = metadata.listSchemaMetadata(snapshot)
    // Choose a table ID from the same pinned session's qualified table discovery.
    val files = metadata.getFileReadMetadata(tableId, snapshot)
    val fileStats = metadata.getFileColumnMetadata(tableId, snapshot)
    // This API deliberately has no historical snapshot parameter:
    val currentNativeTableStats = metadata.getTableColumnMetadata(tableId)
    // Consume raw metadata only under the rules below; this is not a query engine.
})
```

## Capabilities describe this session, not a version guess

`getReadMetadataCapabilities()` returns the visible head snapshot, actual engine
version/source ID, loaded DuckLake/Quack extension revisions (NULL if not loaded),
the unsigned-extension setting, JDBC driver version, access route/backend and
observed physical columns/types. This is **observed peer provenance**, not proof of
which historical writer produced a file, a checksum attestation or universal backend
certification. No advertised metadata feature is inferred from `1.1-dev1`.

The reader verifies the required native columns/types for schema parentage, data
and delete row groups, file exactness and table exactness. Missing/wrong-type columns
fail; they do not become false capabilities or synthesized NULL rows. Layout reads
and data reads share the owned transaction. Validation is repeated, never cached
across sessions/migrations.

The four metadata-presence flags are true only after validation. The separate
`qualifiedNestedSchemaResolution`, `qualifiedRangePruning`,
`qualifiedExactAggregates` and `qualifiedWrites` flags remain **false**.
Accessing metadata does not relax any existing guards.

## Value semantics

- **Parent IDs:** `listSchemaMetadata(snapshot)` returns active schema records with
  `parentSchemaId`. NULL denotes root under this session's validated parent-ID
  capability, never schema ID 0. It preserves names and physical path flags; it does
  not flatten hierarchy, resolve qualifications or construct nested paths. Legacy
  APIs still reject any nested-schema history. Graph/name/path resolution is R05.
- **File metadata:** `getFileReadMetadata(tableId, snapshot)` returns active data/delete
  associations by IDs, with independent `rowGroupCount` and `deleteRowGroupCount`.
  NULL = unknown, including migrated files and Puffin deletes; 0 stays 0. Counts are
  advisory and never replace footer validation or invent splits. `recordCount` is
  gross physical data rows, not live rows; `deleteCount` belongs to the joined delete
  file, not a new CDC delta. NULL `deleteFileId` means no joined active delete file.
- **File bounds:** `getFileColumnMetadata(tableId, snapshot)` returns stored stats for
  active files, preserving independent nullable `minIsExact`/`maxIsExact`: NULL
  unknown, false inexact, true exact **as recorded**. A missing bound remains missing
  even with a true flag. No values/flags/counts/NaN flags are filled in or normalized.
- **Native table bounds:** `getTableColumnMetadata(tableId)` returns the native
  unversioned rows visible to this transaction. These are **not historical aggregate
  statistics**, and are not recomputed from files or substituted for historical rows.

Bound strings are raw metadata, **not cast to the snapshot's logical column type**.
The catalog does not record the original file type. Old/migrated bounds can describe
an earlier type, and even a true exactness flag does not establish type compatibility,
live-row exactness after deletes, missing-column defaults or NaN safety. Do not use
these APIs for exact MIN/MAX or range exclusion without a separately qualified
algorithm. Existing legacy MIN/MAX suppression and retain-all 1.1 range pruning
remain unchanged. Unknown counts/flags are never zero/false/exact.

Every new companion record uses explicit JSON NULLs for its nullable fields. The
nested legacy `DucklakeSchema`/`DucklakeFileColumnStats` records keep their existing
omit-NULL JSON shapes. No new fields are added to existing writer fragments.

Reads reject invisible/invalid requested snapshots, negative stored counts, NULL
required fields, results above 65,536 rows, and transferred text above 16,384
characters. SQL limits and bounded text projections apply before transfer; they
fail explicitly rather than silently truncate the returned metadata. There is no
cross-session capability cache or permission to query without an owned session.

## Native acceptance and consumer handoff

The exact shipped peer still fails the newer pristine `v10_retyped_bounds.test`:
native migration retains suspect timestamp/float bounds, filters miss matches and
an exact MIN is wrong. The separate `upstreamR04NativeTest` preserves all original
SQL/goldens and fails visibly; it is not a JVM metadata-reader pass. The companion
can expose those raw values, but never declares them qualified typed bounds.

Trino/Doris agents must explicitly adopt this optional API and its nullability,
snapshot and raw-bound semantics before relying on it. Their implementations and
physical scans are not certified here. All 1.1 writes and automatic migration stay
disabled. [Support/evidence](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md) and
[consumer contract](../dev-docs/DUCKLAKE-1.1-CONSUMER-CONTRACT.md) define the remaining
R05–R07 and write rollout work.
