# DuckLake 1.1 preparation results — 2026-10-12

> Historical verification evidence for the October 12 samples. Later follow-ups
> are indexed in [the archive](README.md); current gates live in the
> [acceptance runbook](../DUCKLAKE-1.1-ACCEPTANCE.md).

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

## Step 2 — versioned fixtures

Five labelled captures matched [the committed fixture](../probes/ducklake-11-fixtures.json)
exactly: maintained 1.0, locked preview explicit 1.0, locked preview fresh dev,
bundled candidate source and development-main source. Four verifier tests passed,
including negative provenance/layout controls. The native-preview CI adds the
same local fixture regeneration; its GitHub execution awaits permission to push.

Important discovery: the locked dev binary has no `parent_schema_id`, whereas
newer candidate/main DDL does, despite both being called 1.1-dev1. The new
[capability matrix](DUCKLAKE-1.1-CAPABILITIES.md) separates executed binary evidence
from source declarations. All samples are disposable, and no production pins or
JVM models changed.

## Step 3 — API/wire specification and compatibility fixtures

[The consumer contract](../DUCKLAKE-1.1-CONSUMER-CONTRACT.md) chooses new opt-in
metadata envelopes rather than changing existing records, with explicit unknown
parentage, exactness and row-group semantics. No public models or interfaces were
modified. A Java 17 fixture compiled against the published 0.11.0 Maven JAR links
against current classes; decoded fixture bytes match the release-compiled class
exactly. Release JAR SHA-256 is recorded beside the source.

Targeted verification passed **31 tests** (3 new ABI/record/wire controls plus
28 existing airlift goldens), zero failures/errors/skips, detekt and Java 17
checkAbi. This locks selected descriptors, not the entire public ABI. New-envelope
cross-version wire negotiation remains an actual-implementation release gate.

## Step 4 — migration/rollback rehearsal and acceptance runbook

`ducklake_11_migration.py` ran the maintained and locked preview engines in separate
bounded processes on disposable copies. **All 12 rehearsal checks passed**: default
1.0 format, real old Parquet rows and snapshots, two inline schema versions,
preserved row IDs, renamed dev metadata after explicit migration, a visible new
write/checkpoint, pristine-backup restore and atomic reserved-name collision
rejection. The rejected collision copy also still reopens with 1.5.6.

**Downgrade is not safe after a successful preview write/checkpoint:** even while
DuckLake format remains 1.0, the metadata `.db` storage rises to version 69 and
1.5.6's storage version 7 cannot reopen it. Explicit migration has the same reopen
boundary. This is recorded as an unsafe downgrade, not a promised capability or a
reason to weaken the probe. The untouched backup restored all original state.

[The acceptance runbook](../DUCKLAKE-1.1-ACCEPTANCE.md) covers writer draining,
consistent backups, real migration versus synthetic guard tests, backend/transport
qualification and RC go/no-go gates. Final/unknown version-string regressions
confirm that 1.1 cannot be enabled merely by relabelling a catalog.

Final forced verification:

```sh
mise exec -- ./gradlew check --rerun-tasks -Dducklake.corpus.dirs=all --no-configuration-cache
```

- **533 JUnit tests**: 442 catalog + 51 SLT + 40 replay; zero failures/errors/skips.
- Full corpus: 484 files discovered, 453 run, **9,697 records passed**, zero failed;
  existing 31 file skips and 12 record skips unchanged.
- Detekt and Java 17 checkAbi passed. Four Python fixture-contract tests and all
  five regenerated fixture comparisons passed; workflow YAML parsed. Added GitHub
  workflow execution is not claimed before pushing.
- All 12 native migration rehearsal checks passed. The **two strict native
  correctness gates still fail on both sampled engines** as recorded in step 1.

## Remaining work / handoff

Preparation commits: step 1 `94c13de`, step 2 `e42888f`, step 3 `c86c7e9`; step 4 is
the commit containing this final log/runbook. No production dependencies, corpus
pin, native-preview lock, public DTOs or write support changed. No sibling edits.

At T−3/T−4, refresh the release calendar, engine/JDBC/extension provenance and final
schema, then scope actual 1.1 implementation. Current bundle/main fixtures are
source-only. Shipping-engine #1587/#1662 fixes, real multi-backend migration,
new-envelope wire negotiation, larger memory-profiled expiration and matched
Quack/JVM qualification remain gates. **Do not enable 1.1 writes yet.**
