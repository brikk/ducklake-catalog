# DuckLake 1.1 preparation — 2026-10-12

## Goal and baseline

Prepare tests, fixtures and compatibility contracts now; implement/enable actual
1.1 support around T−3/T−4 days, after confirming release-candidate schema/pins.

- Catalog release: **0.11.0**; `main` **0.12.0-SNAPSHOT**, code baseline `77ad8a7`.
- Maintained JDBC/server: **DuckDB 1.5.6.0 / 1.5.6**; Java Quack driver **0.8.0**.
- Maintained corpus: `ac7595b0a1305bea3d4cfaca763b0ce964c763a2`.
- Last full verification: **525 tests**, **9,697 corpus records**, detekt/Java 17 passed.
- Existing prep: explicit lambdas, fresh mutation guards, versioned inline names,
  pinned native-preview CI, matched Quack peers and transaction recovery.
- 1.1 writes remain disabled. Latest checked upstream main:
  `d248aff8044c75081ac970ccf7ed512a03f6f87e`; newest format still `1.1-dev1`.
- Checked DuckDB 2.0 bundled DuckLake pin: `80e6155e415b922ce27a0f67f02fc62edd508f37`.
  It predates several latest fixes; our locked native preview is a separate target.

## Ordered work — verify and commit each step

### 1. Current-lane correctness and scale regressions

- [x] Start with repeated-update change feeds across inline flushes:
  [#1587](https://github.com/duckdb/ducklake/pull/1587), physical positions versus reused row IDs.
- [x] Add snapshot/file expiration scale fixtures:
  [#1659](https://github.com/duckdb/ducklake/pull/1659), range-join fan-out on DuckDB/Quack.
  Preserve liveness/GC semantics; do not blindly emit ASOF SQL on PostgreSQL/MySQL.
- [x] Cover same-transaction delete/flush interactions:
  [#1662](https://github.com/duckdb/ducklake/pull/1662).
- [x] Audit orphan reference/age boundaries against
  [#1669](https://github.com/duckdb/ducklake/pull/1669). Trino's agent owns physical
  deletion and cross-process in-flight-writer protection; no Trino edits here.

**Exit:** deterministic regressions/scale evidence, with native-oracle defects
distinguished from JVM failures. Fix only reproduced local defects; do not relax
goldens or blanket-skip scenarios to hide an older oracle's bug.

**Results:** [execution evidence](DUCKLAKE-1.1-PREP-RESULTS.md). JVM checks passed;
both native targets reproduce #1587/#1662 and remain failing qualification gates.

### 2. Versioned fixture and capability matrix

- [x] Capture 1.0 versus candidate 1.1 schema, inline layouts, exactness flags,
  parent schemas, row-group counts and deletion-file shapes against exact SHAs.
- [x] Separate bundled-extension and development-main fixtures. Do not infer
  capabilities from `1.1-dev1` alone or silently update production/preview pins.

**Exit:** reproducible disposable fixtures and explicit supported/rejected cases.

**Results:** [capability matrix](DUCKLAKE-1.1-CAPABILITIES.md). Five regenerated
fixtures matched exactly; four fixture-contract tests passed. Latest bundle/main
fixtures are source-only, not binary qualification.

### 3. Consumer API and wire contracts

- [x] Specify how schema parentage, bound exactness and row-group counts reach readers.
- [x] Add constructor/serialization compatibility fixtures before extending models.
  Kotlin default parameters alone do not preserve Java record binary compatibility.

**Exit:** documented changes and consumer obligations; no premature 1.1 write enablement.

**Contract:** [consumer specification](DUCKLAKE-1.1-CONSUMER-CONTRACT.md). Preserve
old records; plan an opt-in companion/envelope seam. A frozen published-0.11.0
Java consumer, record-component locks and JSON controls guard current contracts.

### 4. Migration and release-candidate acceptance

- [ ] Prepare old-file, mixed-inline-schema, reserved-name collision, live-old-client,
  unsupported-write and native-storage rollback tests/runbook.
- [ ] At T−3/T−4, refresh release calendar, final format and engine/extension/JDBC
  availability; then explicitly scope real 1.1 implementation and qualification.

**Exit:** clear upgrade/rollback boundaries and final-artifact release gates.

## Execution rules and handoff

- Keep supported 1.0 behavior and strict version guards; no automatic migration.
- Bound maintenance SQL pages/path transfers and traversal before destructive effects.
- Run targeted tests, then full checks for fixes; commit verified intended changes
  before reporting completion. Record SHAs/results. **Do not push without permission.**
- Leave sibling repositories to their agents; tear down session-owned test infrastructure.
- Reference: [existing preparation log](DUCKDB-2.0-PREPARATION.md),
  [assessment](UPSTREAM-DUCKDB-2.0-2026-10-08.md), [release notes](RELEASE-0.11.0.md).
- **Resume after compaction at step 1:** inspect the upstream change-feed regression
  and our current change-feed implementation before editing code.
