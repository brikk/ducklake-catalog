# Sampled DuckLake layouts and capabilities

> Frozen October 12 observations, not a live compatibility matrix. Keep their
> provenance aligned with the immutable probe fixtures. For current support, see
> [compatibility](../../docs/COMPATIBILITY.md).

Evidence: [immutable observations](../probes/ducklake-11-fixtures.json), captured
2026-10-12. **Do not infer capabilities from the dev version string.**

| Target | Evidence | Inline metadata names | Exactness flags / file row groups | Parent schemas |
|---|---|---|---|---|
| DuckDB 1.5.6 + DuckLake `ac7595b0a1305bea3d4cfaca763b0ce964c763a2` | Executed 1.0 | Legacy | Absent | No nested-schema syntax |
| Locked preview `96063b9e39` + DuckLake `e8924cfc9c`, explicit 1.0 | Executed 1.0 | Legacy | Absent | Syntax accepted, **no persisted parent ID** |
| Same locked preview, fresh default | Executed 1.1-dev1 | `_ducklake_*` | Present | Syntax accepted, **no persisted parent ID** |
| DuckDB 2.0 bundle DuckLake `80e6155e415b922ce27a0f67f02fc62edd508f37` | Source only, not executed | Not requalified | Declared in DDL | `parent_schema_id` declared |
| DuckLake main `d248aff8044c75081ac970ccf7ed512a03f6f87e` | Source only, not executed | Not requalified | Declared in DDL | `parent_schema_id` declared |

The bundle and development-main 1.1 metadata-manager source has the same SHA-256,
but this does **not** imply identical writer/change-feed behavior. Both dev binary
and newer source describe 1.1-dev1 despite a different `ducklake_schema` layout.
The old preview stores `child`, not its qualified name, without a persisted parent
ID. Merely parsing CREATE SCHEMA is not durable nested-schema support.

## Captured shapes and support boundaries

- Data **and delete** files gain nullable `row_group_count BIGINT` in 1.1 DDL.
- File/table column stats gain nullable `min_is_exact`, `max_is_exact` booleans.
  Their presence does not mean every bound is exact or safe to prune on.
- Candidate schema rows gain nullable `parent_schema_id`; root absence is not ID 0.
- Local binary inline samples preserve LIST/MAP contents and NULL elements. They
  do not qualify PostgreSQL/MySQL nested type migration or JVM 1.1 type support.
- All three sampled binary targets write snapshot-tagged Parquet positional
  deletes here: `file_path`, `pos`, `_ducklake_internal_snapshot_id`. This is one
  tested shape, **not** full Puffin/multi-blob deletion-vector qualification.
- JVM 1.0 behavior remains supported. Exact known dev inline-name reads are layout
  preparation only. All 1.1 mutations remain rejected; unknown layout versions
  remain rejected. No schema migrations or model fields are added by this step.

## Reproduce / verify

Use isolated environments with **explicit** package versions (maintained 1.5.6,
preview 2.0.0.dev2610011535). Capture JSON outside the checkout:

```sh
<maintained>/bin/python dev-docs/probes/ducklake_11_fixture.py --temp-root /tmp/opencode --label maintained-1.0 > /tmp/opencode/maintained.json
<preview>/bin/python dev-docs/probes/ducklake_11_fixture.py --temp-root /tmp/opencode --label locked-preview-fresh > /tmp/opencode/preview.json
<preview>/bin/python dev-docs/probes/ducklake_11_fixture.py --temp-root /tmp/opencode --version 1.0 --label locked-preview-1.0 > /tmp/opencode/preview-10.json
python3 dev-docs/probes/ducklake_11_fixture.py --source-checkout <ducklake-git-checkout> --source-ref 80e6155e415b922ce27a0f67f02fc62edd508f37 --label duckdb-2.0-bundle > /tmp/opencode/bundle.json
python3 dev-docs/probes/ducklake_11_fixture.py --source-checkout <ducklake-git-checkout> --source-ref d248aff8044c75081ac970ccf7ed512a03f6f87e --label development-main > /tmp/opencode/main.json
python3 dev-docs/probes/verify_ducklake_11_fixtures.py /tmp/opencode/maintained.json /tmp/opencode/preview.json /tmp/opencode/preview-10.json /tmp/opencode/bundle.json /tmp/opencode/main.json
python3 -m unittest discover -s dev-docs/probes -p 'test_ducklake_11_fixtures.py' -v
```

Binary collection creates disposable catalogs; source collection requires full
immutable SHAs and never changes the checkout. The verifier rejects provenance or
layout drift. CI regenerates the two locked **local** preview fixtures; existing
PostgreSQL preview canaries remain separate. Source SQL is evidence, not a runnable
migration script. Current bundle/main execution awaits RC qualification.
