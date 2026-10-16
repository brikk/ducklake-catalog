# Developer documentation

Start here for development work. Public setup, compatibility and producer guidance
live in [the user documentation](../docs/README.md).

## Current work

| Document | Purpose |
|---|---|
| [Upstream TODO](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md) | Active UF IDs and the pending DuckDB 2.0 / DuckLake RC checkpoint |
| [Review backlog](BACKLOG.md) | Remaining catalog, harness and delivery review candidates; preserves canonical IDs |
| [1.1 acceptance](DUCKLAKE-1.1-ACCEPTANCE.md) | Artifact qualification, migration/rollback rehearsal and go/no-go gates |
| [Future consumer contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md) | Opt-in 1.1 API/wire design; not implemented support |

## Working on the repository

- [Agent guide](AGENT-GUIDE.md): verification, commit and safety requirements.
- [Build and test](CONTRIBUTING.md): toolchain, corpus replay and CI lanes.
- [Release process](RELEASING.md): snapshot/release publishing and verification.
- [Schema codegen](SCHEMA-CODEGEN.md): regenerate the checked-in jOOQ bindings.
- [Frozen consumer fixture](FROZEN-CONSUMER.md): reproduce the release-compiled ABI fixture.
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
