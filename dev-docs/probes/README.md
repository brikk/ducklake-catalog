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
  [`duckdb2-preview-lock.json`](duckdb2-preview-lock.json) for the frozen preview or
  [`duckdb2-release-lock.json`](duckdb2-release-lock.json) for official 2.0 provenance.
  Select `--expected-format 1.1` explicitly for shipping readiness/migration rehearsals.
  Do not repin historical fixtures to make new binaries pass old provenance assertions.
- `prepare_corpus_data.py` fetches only eight checksum-pinned upstream Parquet fixtures
  at the corpus's nested DuckDB gitlink. It never edits corpus SQL/goldens.
- `prepare_11_read_fixtures.py` fetches four fixed newer-upstream R02 inputs without
  changing the maintained corpus: three pristine SLT files and the genuine legacy
  STRUCT-order catalog. The dedicated `:ducklake-test-corpus-replay:upstreamR02NativeTest`
  task executes whole cases strictly and currently fails on the shipped native peer;
  successful JVM legacy reads are not a claim those native gates pass.
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
