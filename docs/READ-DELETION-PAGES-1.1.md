# SQL-paged deletion candidates, positions and retained history

Additive `JdbcDucklakeCatalog` methods, all requiring an owned opt-in final-1.1
view, positive limits (1..1024), nonnegative scope/cursors and a visible read/end
snapshot where applicable:

| Method | Bounded result / seek |
|---|---|
| `getDeletionsBetweenPage(tableId,start,end,after,limit)` | `DucklakeDeletionPageEntry(cursor,deletion)`; incremental candidates first, then full retirements |
| `getInlinedFileDeletesBetweenPage(tableId,start,end,after,limit)` | Actual file-local positions, `(data-file ID,position,snapshot ID)` |
| `getDataFilesByIdsBatch(tableId,ids)` | Exact retained-file lookup; at most1024 input IDs, duplicate inputs coalesced, missing/duplicate stored identities reject |
| `listSnapshotsPage(afterSnapshotId,limit)` | Retained snapshot rows, ascending ID; required time/counters cannot become default0 |
| `listSnapshotChangesPage(afterSnapshotId,limit)` | Change/conflict history, ascending ID, **including expired snapshot IDs** |

Existing materialized list APIs and public record/constructor/wire shapes are not
silently changed or advertised as bounded. Positional/batch/snapshot pages supplement
the separate [file](READ-FILE-PAGES-1.1.md), [planning metadata](READ-PLANNING-METADATA-1.1.md)
and [decoded inline/CDC](READ-INLINE-PAGES-1.1.md) pages.

## Candidate ordering and window semantics

`DucklakeDeletionCursor(fullFileDelete,snapshotId,dataFileId,deleteFileId)` has two
phases: incremental `(begin snapshot,data-file ID,delete-file ID)`, then full-file
`(end snapshot,data-file ID)`. A full cursor has no delete ID; an incremental cursor
requires one. This is **enumeration order, not chronological final CDC event order**.
Require a terminal empty page in the same table/window/view before calling traversal
complete, and enforce shared total pages/paths/bytes/nodes/physical-work budgets.

Incremental SQL keeps every delete begun by the window end, not retired before
the window start, and not known by `partial_max` to predate it. It does not require
begin-in-window or non-NULL partial_max. When begin predates the window, previous
is the **same file**, preserving the existing tagged Parquet/Puffin window rules.
Equal paths must not blindly self-cancel a multi-snapshot container. Readers still
select/filter embedded deletion snapshots and suppress transient rows/pair updates.

For an in-window begin, the predecessor is the bounded largest-begin-before-current
lookup; full retirements use the bounded predecessor-before-end lookup. At most two
rows are fetched to detect ambiguous equal begins. Ambiguity fails rather than
choosing an arbitrary physical state. Old list methods retain their existing behavior.
Full candidates retain NULL contiguous starts and original physical facts; positions
are never treated as absolute IDs. Ended data files remain legal retained CDC sources.

No full data/delete history is materialized. Each main arm uses SQL keyset/LIMIT
with one duplicate-key lookahead; each needed predecessor lookup is LIMIT2. Per-call
SQL work includes up to two main-arm queries plus at most one predecessor lookup per
returned candidate. Caller budgets must reserve this work, not count only outputs.
Orphan/foreign associations, null required facts, encountered negative identities/
counts and invalid lifetimes reject instead of dropping an event or fabricating0.

## SQL shape, physical types and transfer bounds

Data/current-delete fields use distinct positional aliases, including through native
Quack coercion; duplicate `path`/`begin_snapshot` names cannot swap data and delete
facts. Unused encryption keys are projected as NULL, never transferred as incidental
metadata. This does not add encrypted-file payload support.

Every planning view validates actual metadata physical types/dimensions before
coercion (once per owned context, never cached across sessions). Fractional footer
metadata cannot silently downcast into an integer descriptor. Optional inline
registry storage is checked only when present; positional storage requires exactly
the three known BIGINT fields. MySQL retains signed types/UTC/utf8mb4/InnoDB and
Boolean/UUID domain checks. Original rich-metadata capability observations are
unchanged; this is stricter admission for new planning/default APIs, not new capabilities.

String cells are SQL-bounded to at most16,384 characters, reduced by requested page
shape against an approximately16MiB logical text allowance plus bounded sentinels/
lookahead. Oversize fails, never silently truncates; a smaller page can accommodate
a legitimate larger cell within the hard maximum. This is not a heap/network/driver-
buffer guarantee. Snapshots validate required counters/time; change rows preserve
nullable author/message/extra text and do not join away expired conflict history.

## Evidence and boundaries

Five-route native scalar/vector fixtures verify exact descriptor parity and bounded
traversal; four native-mutation-qualified routes additionally cover retirement,
same-snapshot multi-file deletes and actual inlined file positions. Existing real
tagged Parquet/Puffin lineage cases now check paged equal-path descriptors and NULL
embedded-start lookup batches. Authored negative/query fixtures test orphans,
oversize/shape-dependent text bounds, ambiguous predecessors, fractional physical
metadata, negative starts, absent/mismatched positional tables and retained conflict
history; they are not native MySQL mutation/expiry or connector CDC certification.

The first authored test wrongly expected one retained delete row per native DELETE.
Source inspection and independent native metadata confirm the signed writer overwrites
them into one cumulative tagged container with its original minimum begin. The
correct positive check therefore verifies count20, retained earlier begin and a real
container footer, then one incremental plus one full-retirement candidate, with later-
window self-path behavior. Original failing assertions are preserved under
`/tmp/opencode/catalog-r07-deletion-pages-initial-assumption/`; they are not native
regressions counted as green, golden changes or waiver of the strict native gates.

All 1.1 writes/migration/default-on/cleanup, native/corpus pins and known native
failures remain unchanged. These metadata APIs do not decode Parquet/Puffin payloads,
implement an engine inline channel/CDC surface, acquire physical retention leases or
certify cancellation/production fleets. Connector adoption and full-query budgets
still require actual SQL/fault evidence on the exact new candidate.

October25 verification adds51 cases (seven common on each of five routes, four
additional native-mutation cases on each of four routes). Full maintained checks
pass **985 JUnit tests** (836 catalog,51 SLT,98 replay) plus **86 separate
direct-Java tests**, zero failures/errors/JUnit skips;17,602 corpus records and
exclusions are unchanged. Lint/frozen ABI/wire/Java17 pass (7,828 classes across17
artifacts). Log: `/tmp/opencode/catalog-r07-deletion-pages-full.log` (27m20s).
Native strict gates were not rerun or waived by this additive API verification.
