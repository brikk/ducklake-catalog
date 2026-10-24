# SQL-paged planning metadata

`JdbcDucklakeCatalog.readPlanningMetadata()` returns the additive
`DucklakePlanningMetadata` companion. Creating it does not acquire/cache a pooled
view. **Every operation** requires an owned opt-in final-1.1 read session, including
when the same companion is reused in a later session. This is the existing root-
schema legacy-DTO tier, not new nested-schema/payload/optimization admission.

| Page | Scope / seek order |
|---|---|
| `listSchemasPage` | Active at snapshot, schema ID |
| `listTablesPage` | Active in schema at snapshot, table ID |
| `getColumnsPage` | Active in table at snapshot, stable column ID; stored column order and parent ID are preserved |
| `getPartitionFieldsPage` | Active table specs, `(partition ID, partition key index)` |
| `getFilePartitionValuesPage` | Active table files, `(data-file ID, partition key index)` |
| `getNameMappingPage` | One immutable mapping ID, **registry column ID**, not target field ID |
| `getInlineVersionsPage` | Table registry schema versions visible at the requested snapshot |

Limits are 1..1024. Scope IDs, seek keys and snapshot visibility are validated.
SQL uses the scope predicate, strict keyset seek, stable order and `LIMIT limit+1`.
One bounded lookahead detects duplicate seek keys across a page boundary; no full
list is fetched and trimmed. Null required/negative IDs and overflowing partition
indices fail instead of becoming zero or wrapping. Metadata SQL/type errors escape,
never become an empty successful page.

String transfer is SQL-bounded before fetch. Cells never exceed a 16,384-character
qualified maximum; the row/field-shaped queries additionally derive a smaller
per-cell bound from the requested page size and an approximately 16 MiB logical
text allowance (plus bounded sentinels/lookahead/fixed fields). If a legitimate
large cell needs more allowance, select a smaller page size **in the same view**;
the large-page request fails, never returns truncated metadata. This is not a heap
or driver/network memory guarantee. Other reads/physical scans have separate budgets.

Drain each scope in one owned session with caller totals for calls/pages, metadata
nodes, paths/bytes, hierarchy depth and physical work. Require a terminal empty
page before publishing a complete plan; budget exhaustion must fail, not truncate.
Seek positions are not authenticated scope/session tokens: callers must not change
the table/mapping/snapshot/view/window midway. Partial column/name/spec pages do not
attest a complete tree: assemble under a total budget, then validate stable-ID
parentage/cycles/depth/duplicates before payload/default projection. Ordering by ID
does not replace the stored column-order facts.

## Inline registry and name-map details

An inline registry entry whose physical table is absent is retained with
`hasLiveRows=false`. It is not dropped from the page, so it cannot hide the seek
position and create a false empty/terminal page. Expected physical absence is
probed without poisoning the transaction; unexpected failures propagate. Registry
table names must agree with their stable table/version identity. At most one
bounded liveness/layout probe sequence per returned registry row is performed;
this work must be included in caller budgets. Versions are not payload/CDC pages.

Name rows preserve source name, target field ID, parent **mapping** column ID and
nullable `isPartition`: unknown is not fabricated as false. Callers must distinguish
body-absent Hive name mappings from native body-carried partition values/opaque
hashed directories. Mapping enumeration does not attest a Parquet file/schema or
authorize unqualified types, default evaluation or a scan.

## Evidence and remaining boundary

Twenty-six new targeted cases pass: four common cases on all five routes, one
additional real native partition/inline-evolution case on each of the four native
mutation-qualified routes, and two local duplicate-boundary/text-budget cases.
Common native fixtures verify schema/table/column values and live inline registry
entries; four-route native cases verify exact identity partition values (including
NULL/escaped text) and multiple inline schema versions. Native MySQL evolution is
not claimed or assumption-skipped. Authored raw name-map and absent-table fixtures
are expressly query/negative fixtures, not native import/flush or connector scans.
Existing 0.4/1.0 list APIs, constructors and JSON record shapes remain unchanged.

File descriptors have separate [active/added pages](READ-FILE-PAGES-1.1.md).
Deletion candidates and decoded inline ordinary/CDC payload transfers still need
their own SQL paging; these metadata pages alone do **not** make a whole query
globally bounded. Snapshot/file leases, arbitrary network interruption and engine
capabilities are also separate. Read-only/opt-in defaults, migration/write guards,
dependency/corpus pins and native failing gates are unchanged. Connector adoption
must be verified on exact candidate bytes before extending the declared scope.

Full verification passes **898 maintained JUnit tests** (749 catalog, 51 SLT,
98 replay), **86 separate direct-Java tests** and17,602 corpus records, with
unchanged exclusions and zero failures/errors/JUnit skips. Lint, frozen ABI/wire
and Java17 checks pass (7,810 classes across17 artifacts). Log:
`/tmp/opencode/catalog-r07-metadata-pages-full.log` (23m42s). Strict native gates
were not rerun or waived by this additive metadata API verification.
