# Materialization snapshots — 0.13 migration

Starting with **0.13.0-SNAPSHOT**, the catalog rejects mutation overloads that
cannot identify the caller's planning/materialization snapshot. They throw
`DucklakeInvalidOperationException` before mutation. Their JVM method signatures
remain available for linking, including the two interface default methods' `void`
return descriptors. This is an intentional behavioral change from 0.12.0.

## Required replacements

| Retained, now rejecting form | Supported replacement |
|---|---|
| `flushInlinedData(tableId, fragments, preservedRowIdStart)` | `flushInlinedDataWithSnapshots(tableId, files, existingFileDeletes, readSnapshotId)` |
| `flushInlinedDataWithSnapshots(tableId, files)` | Same four-argument flush |
| `flushInlinedDataWithSnapshots(tableId, files, preservedRowIdStart)` | Same four-argument flush, with each file's own `rowIdStart` |
| `commitDelete(tableId, deleteFragments)` | `commitDelete(tableId, deleteFragments, readSnapshotId)` |
| `commitMerge(tableId, deleteFragments, insertFragments)` | `commitMerge(tableId, deleteFragments, insertFragments, readSnapshotId)` |

Refusal includes empty payloads and insert-only calls to the deprecated MERGE
overload. Plain inserts continue to use `commitInsert`.

## Capture the boundary before preparing output

1. Capture the planning snapshot and required metadata in one owned read session.
2. Read rows and existing deletions at that snapshot, honoring the caller's
   traversal/memory/byte budgets and file-retention requirements.
3. Materialize the output from that captured view.
4. Pass the **original** snapshot to the supported mutation method. Never fetch
   `currentSnapshotId` after preparing output and present it as the read snapshot.
5. On a stale-payload conflict, re-read and rematerialize. The original payload is
   not made safe by attaching a newer snapshot number to it.

The old transaction-start conflict window began after materialization. An INSERT
between those phases could be retired by a flush even though its row was absent
from the file; a newer cumulative DELETE could be replaced by stale positions.
No implementation can infer the missing read boundary from these legacy arguments.

Flush migration also requires the existing snapshot-aware file contract: one
output per physical inline schema, retaining that historical schema and all row
versions, embedded original row and insert-snapshot IDs, per-file origins and
snapshot-tagged deleted positions. Supply complete `existingFileDeletes` coverage.
A live-only legacy flush file cannot simply be relabeled as a historical flush.

The catalog does not delete private materialized output on refusal. Supported
operations retain the [COMMIT outcome contract](COMMIT-OUTCOME-CONTRACT.md).

## Consumer assessment and remaining boundaries

Source review of clean Trino `3385429` found the four-argument flush in
`DucklakeFlushInlinedDataProcedure`, explicit planning snapshots for DELETE/MERGE
in `DucklakeMetadata`, and explicit snapshots for both rewrite variants.
Clean Doris `4efd9bc` has no callers of these mutation APIs. No owned call site
needs conversion from a snapshotless overload. Existing library tests exercising
valid writes now pass their actual planning snapshot; tests that pinned weak
legacy behavior now assert the documented 0.13 refusal.

This assessment does not replace coordinated qualification when consumers adopt
a new catalog artifact; their accepted bundle remains separate. Source/binary/JSON
shapes and the Java 17 runtime floor are preserved. No 1.1 write support is enabled.
Ordinary rewrites also reject native inlined deletions on a selected source that
committed after the supplied planning snapshot, before registering output. The lazy
delete table is rechecked on each attempt with bounded queries; unrelated sources
and deletions already materialized at the planning snapshot do not conflict. Unknown
owner/snapshot identities reject. This closes the separately reported stale-rewrite
boundary without changing the historical/embedded-lineage obligations above.
