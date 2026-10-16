# Native probes and fixtures

Run from the repository root with an explicitly selected, isolated Python package.
Do not replace maintained or preview pins while inspecting a new candidate.
Temporary output belongs outside the checkout, e.g. `/tmp/opencode`.

## Qualification probes

Each script below accepts `--temp-root /tmp/opencode`. Wrong results are failed
checks, not replacement goldens or blanket skips; strict gates exit nonzero.

| Script | Checks |
|---|---|
| `ducklake_11_regressions.py` | Positional CDC snapshots and same-transaction delete/flush |
| `ducklake_old_inline_schema.py` | Freshly written inline values survive schema changes/read/flush; **not** a complete legacy-catalog migration test |
| `ducklake_lineage_stats_blob.py` | Merge row identity, false-exact statistics and scalar/nested BLOB bytes |
| `ducklake_cdc_transient.py` | UF-01: suppress versions inserted/deleted in one snapshot |
| `ducklake_cdc_names.py` | UF-02: user names do not shadow CDC identity |
| `ducklake_quack_snapshot_ceiling.py` | UF-03: matched native peers, listings/time travel/CDC ceilings |
| `ducklake_bucket_pruning.py` | UF-04: NaNs, nested signed zeros and mixed IN lists versus unpartitioned controls |

For scripts that expose `--version 1.0`, use it to distinguish the candidate's
explicit 1.0 lane from fresh dev-format behavior. Read `--help`; do not assume every
script supports the same options.

## Locked preparation checks

- `duckdb2_readiness.py`, `duckdb2_quack.py` and `verify_duckdb2_preview.py` use
  [`duckdb2-preview-lock.json`](duckdb2-preview-lock.json) to enforce actual provenance.
- `ducklake_11_fixture.py` / `verify_ducklake_11_fixtures.py` capture/verify
  [`ducklake-11-fixtures.json`](ducklake-11-fixtures.json). Source-only captures are
  not executed binaries. The [archived capability report](../archive/DUCKLAKE-1.1-CAPABILITIES.md)
  explains the frozen labels/hashes, not today's upstream state.
- `ducklake_11_migration.py` rehearses old/candidate processes and backup restore.
  Follow the [acceptance runbook](../DUCKLAKE-1.1-ACCEPTANCE.md); never point an
  experiment at production metadata/data.

```sh
PYTHONDONTWRITEBYTECODE=1 <preview>/bin/python -m unittest discover -s dev-docs/probes -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 <candidate>/bin/python dev-docs/probes/ducklake_cdc_transient.py --temp-root /tmp/opencode --version 1.0
```

Harness/fixture tests passing does not mean strict native gates pass. Preserve the
script exit code and actual observations. Reap child servers and remove disposable
catalogs; retain only explicitly labelled evidence. No probe enables JVM 1.1 writes.
