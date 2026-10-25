# SQL-paged decoded inline rows and CDC candidates

Additive methods on `JdbcDucklakeCatalog`:

```kotlin
readInlinedDataDecodedPage(tableId, schemaVersion, snapshotId, columnIds, after, limit)
getInlinedChangesBetweenDecodedPage(tableId, schemaVersion, startSnapshot, endSnapshot, columnIds, after, limit)
```

Every call requires one owned opt-in final-1.1 session. The read/end snapshot must
be visible and its schema-version bound must be non-NULL/nonnegative; a future
inline version is not silently treated as an empty table. Projection IDs are
distinct, nonnegative stable **root** IDs, at most4,096, resolved against fresh
target metadata. Caller names/types/defaults are never trusted. Empty projection
is an explicit identity-only page, not payload/type qualification.

Both return existing `DucklakeInlinedChangeRow(rowId, beginSnapshot, endSnapshot,
values)` records. The new `DucklakeInlineCursor(rowId, beginSnapshot)` seeks physical
row **versions**, not CDC event timestamps. Multiple versions of one stable row ID
cannot be skipped. SQL applies scope/window/seek/order and `LIMIT limit+1` before
transfer; one lookahead catches duplicate identities across a page boundary. Limits
are1..1024. Null/negative required identity or end-before-begin is corruption, never
row/snapshot zero. Expected absent physical tables return empty; unexpected query,
layout, identity or projection failures propagate.

## Snapshot and CDC semantics

- Ordinary pages use `begin <= S AND (end IS NULL OR end > S)`. Actual stored end
  metadata is retained, including an end **later than S**; it is not a deletion
  event at S. SQL NULL ends stay boxed NULL, not0.
- CDC pages use inclusive insertion/deletion-window overlap, preserving both
  begin/end coordinates. Rows can be both inserted and deleted in the window,
  including zero-lifetime owned history fixtures. Live-row counts therefore do
  **not** bound CDC history. Pairing/update/transient suppression remains consumer
  work; these pages are row-version candidates, not an assembled change feed.
- Values use the target snapshot's stable-ID schema and validated literal defaults,
  source physical-layout checks and the same qualified evolution as existing reads.
  Known expression-kind/materialized VARCHAR `NULL` remains valid; unknown history
  kinds reject. MySQL's narrower scalar tier and schema-evolution refusals remain.
  Composite MAP keys are explicitly unqualified for payload pages, even NULL cells;
  an empty identity projection does not claim otherwise.

Drain each fixed table/version/window/projection scope in the **same owned view**,
with caller total page/row/path/schema-node/depth/byte/work budgets and a terminal
empty page. An exhausted budget is failure/incomplete work, not truncated success.
Seek positions are not authenticated scope tokens or leases. Do not switch source
version or target/window midway, or infer whole-query bounds from a single page.

## Transfer and decoded allocation bounds

Variable cells are SQL-bounded before JDBC transfer, never fetched unbounded then
trimmed. The representation cap is at most1MiB per cell, reduced by requested page
size/projection width against a16MiB logical page allowance. A sentinel causes
oversize refusal, never successful truncated data. Reduce page size where a wide
page's smaller cell allowance is insufficient. Native DuckDB/Quack use bounded
validated native text; PostgreSQL/MySQL retain the physical bytea/text contract
needed by their existing decoders. These are representation bounds, not a promise
that every backend accepts the same maximum decoded BLOB size.

Decoded output additionally shares65,536-node, depth64 and16MiB logical allocation
budgets across the page. Text charges four bytes per Java character, binary charges
its byte count, fixed scalar/identity slots charge32 logical bytes. This is not a
JVM heap/wire/driver-buffer guarantee. PostgreSQL default BLOB copies are checked
**before** repeated allocations; a post-materialization byte check alone would not
bound nested default expansion. Existing nonpaged behavior is unchanged.

Internal schema/type/default preflights are bounded but are extra work, not free:
caller budgets must account for source/target schemas (at most4,096 nodes), physical
column checks and missing-default history lookups as well as returned rows. SQL
server work, pool/setup/prepare/network interruption and physical retention are not
made globally bounded by this API. [Controlled statement sessions](READ-SESSION-CONTROL.md)
and real connector qualification remain separate requirements.

## Evidence and scope

New tests use real native fixtures on all five routes for scalar ordinary/CDC
pages, original IDs, NULL/literal-NULL/BLOB bytes, identity-only projections,
small-page traversal budgets, Unicode cell limits/recovery and malformed requests.
Owned duplicate/zero-lifetime metadata fixtures expressly test failure/candidate
semantics, not native writer or connector CDC support. Four native mutation-
qualified routes additionally execute repeated same-ID updates, rename/default
evolution, historical future-end metadata, nested parent/element/map NULLs and
nanosecond timestamps. Native MySQL evolution is not claimed or skipped.

Existing list-returning methods/constructors/record components/fragment JSON and
legacy0.4/1.0 branches are unchanged. These pages do not implement a Doris inline
transport/CDC surface, a Parquet reader, retention lease or cleanup permission.
Deletion candidates, positional metadata and retained history have
[separate SQL-paged APIs](READ-DELETION-PAGES-1.1.md). Connector adoption and whole-
query budgets still require their own evidence.

Verification adds31 tests (four common cases on each of five routes, two more on
each of four native-mutation-qualified routes, and three local/unit guards).
Full maintained checks pass **934 JUnit tests** (785 catalog,51 SLT,98 replay) plus
**86 separate direct-Java tests**, zero failures/errors/JUnit skips;17,602 corpus
records and exclusions remain unchanged. Lint/frozen ABI/wire/Java17 pass (7,816
classes/17 artifacts). Log: `/tmp/opencode/catalog-r07-inline-payload-pages-full.log`
(24m57s). Strict native gates were not rerun or waived by this API verification.
