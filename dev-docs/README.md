# Developer documentation

Start here for development work. Public setup, compatibility and producer guidance
live in [the user documentation](../docs/README.md).

## Current work

| Document | Purpose |
|---|---|
| [Upstream TODO](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md) | Active UF IDs and shipping-artifact checkpoint |
| [DuckDB 2.0 upgrade](UPGRADE-DUCKDB-2.0.0.md) | Shipping pins, read/write support, verification and rollback |
| [1.1 support TODO](TODO-DUCKLAKE-1.1-SUPPORT.md) | Read-first implementation queue; separate deferred write section |
| [Upstream 1.1 read tests](UPSTREAM-1.1-READ-TESTS.md) | Pristine upstream JVM mirrors and newer regressions queued by capability |
| [Review backlog](BACKLOG.md) | Remaining catalog, harness and delivery review candidates; preserves canonical IDs |
| [1.1 acceptance](DUCKLAKE-1.1-ACCEPTANCE.md) | Artifact qualification, migration/rollback rehearsal and go/no-go gates |
| [Future consumer contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md) | Richer 1.1 metadata envelope/wire design; not yet implemented |
| [Final-1.1 read results](FINAL-1.1-READ-RESULTS.md) | Conservative direct-Java read tier and explicit unsupported cases; no writes |

## Working on the repository

- [Agent guide](AGENT-GUIDE.md): verification, commit and safety requirements.
- [Build and test](CONTRIBUTING.md): toolchain, corpus replay and CI lanes.
- [Release process](RELEASING.md): snapshot/release publishing and verification.
- [Schema codegen](SCHEMA-CODEGEN.md): regenerate the checked-in jOOQ bindings.
- [Frozen consumer fixture](FROZEN-CONSUMER.md): reproduce the release-compiled ABI fixture.
- [Direct Java Quack / staged 2.0](QUACK-2.0-QUALIFICATION.md): v3 qualification without DuckDB JDBC; 1.1 remains gated.
- [Probe index](probes/README.md): native scripts, immutable locks and interpretation.

## History, not another task queue

[The archive](archive/README.md) holds completed plans, dated surveys, execution
evidence and old agent notes. Its original diagnostics and stable IDs are retained,
but old line numbers, test counts, pins and “pending push/release” text are historical.
Do not take an archived checkbox as proof of a current defect or a shipping fix.

Keep new active guidance concise. When a plan finishes, retain evidence in the
archive and update the relevant current guide/TODO rather than adding parallel
status documents. Upstream checkouts, submodules and generated schema files are
not documentation-cleanup targets.
