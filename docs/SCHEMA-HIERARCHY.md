# Parent-aware final-1.1 schema and path reads

Available in **0.13.0-SNAPSHOT development code** through
`JdbcDucklakeCatalog.readHierarchy(): DucklakeHierarchyReader`. It is an additive,
read-only companion, qualified on the three DuckDB-backed, PostgreSQL and existing-MySQL [routes](READ-BACKENDS.md)
as [rich metadata](READ-METADATA.md). Every operation requires
`enableFinal11Reads=true` and the owning catalog's `readSession`. Other formats and
routes fail explicitly. MySQL uses its narrower [existing-catalog read contract](READ-MYSQL-1.1.md).
No write, migration or physical-file scan is enabled.

```kotlin
catalog.readSession(java.util.function.Supplier {
    val snapshot = catalog.readBackendProfile().snapshotId
    val hierarchy = catalog.readHierarchy()
    // Two literal components, NOT a dotted SQL string:
    val schema = hierarchy.findSchema(listOf("sales", "north.america"), snapshot)
    val table = hierarchy.findTable(listOf("sales", "north.america"), "orders", snapshot)
    // IDs remain unambiguous when other parents have children with the same name.
    val sameTable = table?.let { hierarchy.getTableById(it.table.tableId, snapshot) }
    val firstRetainedPage = hierarchy.listRetainedFiles(DucklakeFileKind.DATA, null, 256)
})
```

## Names and snapshots

`listSchemas`, `getSchemaById`, `findSchema`, `listTables`, `getTableById` and
`findTable` select active metadata at the requested visible snapshot. Root-to-leaf
`schemaIds` and `names` remain separate ordered arrays, never a flattened dotted
identifier. Dots/quotes inside one component are literal. Matching folds ASCII
letters exactly as DuckDB identifiers do; distinct non-ASCII names remain distinct.

Stable IDs select parent relationships. Duplicate identities, ambiguous sibling
names, missing/invisible parents and cycles fail rather than choosing one match or
assuming root ID 0. Names reused after a drop resolve to the new ID only at the new
snapshot; historical requests retain the old IDs, names and table versions. Stored
end snapshots remain the metadata's actual end values, even when later than the
requested historical snapshot. Historical selection is not a rewritten metadata row.

The DuckDB `qualifiedNestedSchemaResolution` capability is true for these R05 APIs;
the portable backend profile reports `hierarchyAvailable=true` on PostgreSQL/MySQL too.
It does **not** unlock legacy flat APIs: their rejection of any nested-schema history
(including retired children) remains unchanged. It also does not certify legacy
inline/column/file-descriptor calls on nested catalogs or connector physical scans.
Pruning, exact aggregates and writes remain explicitly unqualified.

## Physical paths are not logical schema ancestry

Native DuckLake stores **every schema path relative to the catalog data root**,
including nested schemas. A child's stored path is not appended to its logical
parent's directory. Table paths are relative to their owning schema's resolved
directory; data/delete file paths are relative to the owning table directory.
Deletion-queue relative paths use the **catalog root**, never a table directory.

A NULL schema/table path inherits that scope's base. A non-NULL path requires a
known `path_is_relative` flag. False replaces the base; true concatenates the stored
path verbatim, as native metadata loading does. No URI decoding, slash/dot-segment
normalization, case folding, whitespace trimming or invented directory separator
is applied. The qualified stored catalog root must end in `/`; missing roots remain
unknown, and other root separator forms fail explicitly. Missing roots may leave
qualified directory `dataPath` NULL; relative file resolution then fails, while an
absolute stored file path can still resolve independently.

The base is the persisted global `data_path` in this pinned metadata view. Native
per-attachment `OVERRIDE_DATA_PATH` settings or an unrelated configured alternate
root are not inferred; relocated namespaces need their own explicit qualification.

`resolvedPath` follows stored flags and spelling. It is not a sandbox/canonical-path
attestation or proof that a file exists. Caller filesystem/namespace safety checks
and real footer/schema/lineage/delete filtering remain necessary.

## Retained ownership and scheduled files

`listRetainedFiles(kind, afterFileId, limit)` returns a keyset page ordered by file
ID, with a **separate cursor per DATA/DELETE kind**. It includes ended files and
files of dropped-but-retained tables/schemas, not just current query candidates.
Each `DucklakeOwnedFile` retains raw path/relative flag and begin/end snapshots,
plus its qualified historical owner and resolved path.

Owner resolution uses the file's original `beginSnapshot`, not the latest table
version or a reused schema/table name. It works even if that origin snapshot row
has expired, provided the required historical owner metadata is retained. Missing
or overlapping owner versions, missing ancestors, ambiguous names and malformed
paths fail explicitly. They never disappear through an inner join or become an
empty successful known set. Duplicate file IDs, including a tie at a page boundary,
are rejected using one extra bounded SQL row. NULL/negative file identities are
checked before keyset filtering so they cannot hide beyond a cursor.

`listScheduledReferences(after, limit)` supplies the **entire scheduled path known
set**, including future dates and NULL/unknown ages. Its exact composite cursor is
`(dataFileId, path bytes, pathIsRelative)`; data/delete ID cursors cannot substitute
for it. Repeated identical references at different schedule times collapse only
for this ownership view. Encoded path comparison keeps case/Unicode variants
distinct even under a text collation, including PostgreSQL nondeterministic ICU
collations. Cursor identity is not a queue-row identity
and must never be used for deletion acknowledgements.

`listScheduledFiles(olderThan, limit)` returns an age-gated batch with the exact
queue identity and a catalog-root-resolved path. It is not a keyset cursor or a
claim that scheduled files belong to a current table. No acknowledgement/deletion
method is added or enabled.

Do not use the age-gated batch as a complete orphan-protection known set: it omits
not-yet-eligible and unknown-age entries. Use `listScheduledReferences` for that.

All retained DATA, retained DELETE and scheduled-reference pages must be read in
one owned session, with caller-enforced total
page/path/traversal budgets. Abort any destructive workflow on an incomplete page
sequence or resolution failure; a planning session is not a physical-file lease
after it ends. This increment performs **no destructive effects** and certifies no
consumer cleanup implementation or concurrent physical-file lease.

## Bounds and consumer handoff

Each hierarchy/table listing query transfers at most 4,097 rows and rejects more than 4,096
nodes. Ancestry depth is at most 64, identifier/path and composed-path text at most
16,384 characters, and each ownership operation has an 8,192-unit lookup/traversal
budget. Retained pages are at most 1,024 rows plus one tie sentinel; scheduled path
references use distinct encoded keys and at most 1,024 rows; point/history/
ambiguity lookups return at most two rows. Path/name projections use one guard
character and fail on overflow rather than return truncated values. Caches are
operation-local and never cross read-session boundaries.

Existing `DucklakeCatalog` interfaces/records, Java constructors and fragment JSON
are unchanged. New qualified records preserve ordered IDs/components, NULL unknown
paths and ended ownership in JSON fixtures. Both owned plugin agents must adopt the
new APIs explicitly and qualify their own planning, physical reads, retention and
mixed coordinator/worker behavior. No plugin changes or rollout are claimed here.

The separate R02–R04 native gates remain parked/failing on the maintained pin;
hierarchy qualification does not repair those native defects. See the
[support record](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md) and
[pristine upstream adoption](../dev-docs/UPSTREAM-1.1-READ-TESTS.md).
