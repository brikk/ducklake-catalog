# DuckLake 1.1 preparation results — 2026-10-12

This is preparation, not 1.1 write support or final DuckDB 2.0 qualification.
Production engine, extension/corpus and preview-lock pins remain unchanged.

## Step 1 — correctness, scale and cleanup boundaries

New JVM tests verify repeated UPDATE/DELETE histories after a native inline flush:
five physical row versions share two absolute row IDs. The catalog returns the
back-dated delete file in each relevant snapshot window; its positional deletion
snapshots map to the right versions. Engines must use **file positions** for this
lookup, then pair change-feed images by absolute row ID and snapshot. A native
`table_deletions` result is not a trustworthy golden for this regression.

The JVM flush test merges committed file deletes with inlined deletes, atomically
replaces the old delete-file metadata and drains inline positions. It checks one
live delete file, current/time-travel rows and scheduling the superseded file.
The API has no native-style multi-statement DELETE+flush transaction; this verifies
its supported committed-materialization path, not that missing API.

The expiration fixture seeds **3,000 snapshots, 1,000 data files and 1,000 delete
files** on local DuckDB and native Quack. A broad survivor set retains everything;
then sparse survivors reclaim exactly 1,500 files, including files of a dropped
table. An end snapshot equal to a survivor is correctly excluded. Repeated expiry
does not reschedule files. Local/Quack test durations were 1.34s/5.07s (observations,
not timing assertions). No JVM defect reproduced at this size; this is **not a
warehouse-scale memory guarantee**. The old expiration query still deserves a
larger memory-profiled benchmark before adopting backend-specific ASOF SQL.

### Native gates that still FAIL

`dev-docs/probes/ducklake_11_regressions.py` is a strict standalone qualification
probe. It does not skip defects or change expected results; it exits **1** when
the engine fails. Both sampled native targets failed both checks:

| Target | Core | DuckLake | Catalog | #1587 | #1662 |
|---|---|---|---|---|---|
| Python 1.5.6 | `069cc9f9b5` | `ac7595b0` | 1.0 | FAIL | FAIL |
| Locked Python 2.0.0.dev2610011535 | `96063b9e39` | `e8924cfc9c` | 1.1-dev1 | FAIL | FAIL |

Repeated versions `a`,`b` should be deleted at snapshots 3,4; both engines report
3,3. DELETE+inline-flush should yield `[16,17,18,19]`, but scans return 24 rows
while metadata count is 4. These are native upstream defects, not catalog fixes.
Re-run these gates against the release candidate; don't silently bless them.

```sh
# Use a separately prepared environment with an explicitly selected package.
<venv>/bin/python dev-docs/probes/ducklake_11_regressions.py --temp-root /tmp/opencode
```

### Orphan/age audit and downstream handoff

- `listRetainedFileReferencePage` includes historical and dropped owners, resolves
  only each page's hierarchy, bounds SQL rows and transferred path strings, and
  honors pinned read sessions. Do not use the legacy unbounded reference-list APIs
  for bulk physical cleanup.
- `listScheduledFileBatch` uses **strictly older** schedule timestamps; exact raw
  id/path/relative/timestamp acknowledgements are bounded and atomic. Queue grace
  periods are not file-creation age checks for orphan traversal.
- No catalog reference API knows another process's uncommitted files. Even a
  pinned metadata snapshot excludes future commits. Upstream #1669 protects some
  same-process writers only; it does not protect external Trino writers or active
  file creation. Physical deletion needs conservative file-age thresholds,
  coordination/rechecks, normalized containment and traversal/page budgets.
- This repository performs no physical deletion in these scenarios. Trino owns
  its deletion implementation; no sibling repository was edited.
- Existing five-backend bounded-cleanup tests cover strict cutoff equality,
  historical/dropped references, oversized paths, pinned reads and exact matching.

Verification: `mise exec -- ./gradlew check -Dducklake.corpus.dirs=all
--no-configuration-cache` passed **529 JUnit tests** (438 catalog, 51 SLT, 40 replay),
zero failures/errors/skips, detekt and Java 17 checkAbi. Replay was up-to-date from
the verified 9,697-record baseline; a forced full replay will run at final handoff.
