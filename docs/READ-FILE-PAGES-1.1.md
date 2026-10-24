# SQL-paged final-1.1 file descriptors

Additive methods on `JdbcDucklakeCatalog`:

- `getDataFilesPage(tableId, snapshotId, after: DucklakeDataFileCursor?, limit)`
  returns `DucklakeDataFilePageEntry(cursor, dataFile)`.
- `getDataFilesAddedBetweenPage(tableId, startSnapshot, endSnapshot,
  after: DucklakeAddedFileCursor?, limit)` returns `DucklakeAddedFilePageEntry`.

Both require an owned opt-in final-1.1 session and a visible read/end snapshot.
`limit` is 1..1024; IDs/cursors/windows are validated before payload queries. The
SQL itself applies the keyset predicate and LIMIT, including inside the native
Quack wrapper. No full metadata list is fetched then trimmed. String cells are
SQL-bounded to 16,385 characters and reject above 16,384, never return truncated paths.

Drain with a caller total page/descriptor/path/byte/work budget **inside the same
owned view**, retaining the final cursor from each page. A terminal empty page is
required before calling enumeration complete. An exhausted budget is failure,
not a successful partial plan. Cursors are seek positions, not session/scope tokens:
the caller must not switch table/window/snapshot/view while draining.

Active associations order by data-file ID and delete-file ID (NULL delete first).
The composite cursor cannot skip another association for the same data-file ID.
It does not certify ambiguous/duplicated active deletes as a valid engine scan.
Added-file candidates retain begin-snapshot/data-file-ID order and the original
`begin <= end AND (begin >= start OR partial_max >= start)` condition. A compacted
file beginning before the window remains a candidate when embedded DATA origins
overlap it. Added descriptors have no joined current delete; ordinary scans still
use active associations. NULL `rowIdStart`, row-origin/deletion tags, formats and
actual paths remain unchanged, not synthesized from ordinal positions.

These pages do not acquire a file lease, authorize cleanup or implement physical
Parquet/Puffin scans. The current descriptor and snapshot filtering rules still
apply. Existing list-returning APIs retain their legacy contract and are **not**
advertised as bounded/streaming merely because these additional entry points exist.

## Qualification and remaining work

Sixteen new tests execute across local DuckDB, native Quack, Java Quack, PostgreSQL
and existing-MySQL JDBC. Real native multi-file/delete-vector fixtures have exact
page/list descriptor parity, independently expected rows/counts/IDs, terminal-page
and caller-budget checks. Invalid requests and owned oversized-path metadata reject
without truncation. A separate owned duplicate-association query fixture checks the
composite seek key; it is not native multi-delete scan certification. Existing
four-route merge tests also verify paged historical NULL starts and later-window
added candidates for a single physical cross-snapshot file.

The first sequential-autocommit multi-file producer failed on native MySQL with
`UPDATE syntax not supported`; its original log is retained at
`/tmp/opencode/catalog-r07-file-pages-native-mysql-initial.log`. The common positive
read fixture instead creates its three files in one genuine native transaction,
then applies the already-qualified scalar delete. No failure is converted into a
native writer pass: general MySQL updates/lifecycle remain deferred M01. The input
values/expected read rows are unchanged, and no corpus golden/exclusion/pin moved.

**Incomplete request coverage:** these methods do not yet page deletion candidates,
inline versions/payloads/CDC, schema/table enumeration, partition specs/values or
name maps. A connector that calls those unbounded APIs still cannot claim a globally
bounded plan. Total query budgets, physical retention/leases and connector adoption
remain separate requirements; this is not broad R07 or production approval.

Full maintained verification passes **872 JUnit tests** (723 catalog, 51 SLT,
98 replay) and **86 separate direct-Java tests**, zero failures/errors/JUnit skips;
17,602 corpus records and existing exclusions are unchanged. Lint, frozen ABI/wire
and Java17 checks pass (7,803 classes across17 artifacts). Log:
`/tmp/opencode/catalog-r07-file-pages-full.log` (22m31s). Strict native gates were
not rerun or waived by the paged API verification.
