# File-reference namespaces

`listReferencedFilePaths(tableId)` is a deprecated, unbounded compatibility API,
**not an orphan-detection known set**. Its result intentionally retains the old raw
values and JVM descriptor:

- Data/delete paths belong to the requested table, including historical rows.
  Relative paths resolve against that table's storage directory.
- Scheduled paths belong to the whole catalog, not that table. Relative paths
  resolve against the catalog-global `data_path`.
- `DucklakeFilePathRef` does not identify which namespace an entry came from.
  There is no generally correct common base for this mixed list. Do not infer a
  namespace from the path text or resolve every entry against a table directory.

For example, a scheduled `main/t/a.parquet` belongs at
`<data_path>/main/t/a.parquet`, not
`<data_path>/main/t/main/t/a.parquet`. Absolute paths stay absolute.

## Bounded maintenance APIs

Use `listRetainedFileReferencePage(kind, afterFileId, limit)` for retained
data/delete references. Each entry carries its full schema/table/file hierarchy,
including dropped-but-unexpired owners. Use a pinned `readSession` for related
pages. Relative file → table → schema paths ultimately resolve against the
catalog root. Preserve absolute components; do not guess missing hierarchy.

Use `listScheduledFileBatch(olderThan, limit)` for age-eligible scheduled-deletion
candidates. Its relative paths always resolve against the catalog root. This
is a bounded candidate batch, **not a paginated listing of the whole queue**:
repeating it without removing rows returns the same candidates. It cannot by
itself supply all scheduled ownership for an orphan sweep, including younger or
undated entries.

Both APIs bound SQL rows and transferred path lengths. Consumers must also bound
aggregate traversal and validate identities before any physical deletion. Do not
replace a bounded query with an unbounded fetch followed by an in-memory limit.

`listAllReferencedFiles()` separates table and scheduled namespaces correctly and
includes dropped-but-unexpired owners, but remains an **unbounded** compatibility
API. Correct namespaces are not proof of bounded, concurrency-safe orphan
collection. A bounded full-queue ownership contract and connector migration need
separate qualification; do not present scheduled-candidate batches as that contract.

## Known consumer source audit

At Trino `3385429` and Doris `4efd9bc`, neither production orphan implementation
calls `listReferencedFilePaths`. Both use `listAllReferencedFiles`, resolving
scheduled paths against the catalog root separately from table files.

Trino queued cleanup uses `listScheduledFileBatch` and retained-reference pages.
Doris queued cleanup still uses the old unbounded scheduled-file listing. Both
orphan implementations still use the unbounded catalog-wide listing. These are
explicit bounded-maintenance adoption follow-ups, not resolved by deprecating the
per-table API. This catalog change neither edits connectors nor qualifies a new
dependency bundle, performs physical cleanup, or enables DuckLake 1.1 writes.
