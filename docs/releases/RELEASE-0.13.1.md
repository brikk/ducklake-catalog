# 0.13.1 — explicit replay setup format

All three modules (`ducklake-catalog`, `ducklake-test-corpus-replay`, `slt-format`)
and catalog test fixtures are released together at 0.13.1. Only the replay harness
changes; catalog and SLT code are as in [0.13.0](RELEASE-0.13.0.md). Runtime pins are
unchanged: DuckDB 2.0.0 / JDBC 2.0.0.1, signed DuckLake `417a9117f5`, native Quack
`f96420ed46`, optional quack-jdbc 0.12.1. The corpus pin is
`417a9117f590e8141742ac0579d02f6a90162e37`.

## Added

`ReplaySetupFormat` (`DUCKLAKE_1_0`, `DUCKLAKE_1_1`) and a `setupFormat` parameter on
`CorpusRunner`, `ReplayDriver` and `DuckDbOracle`. DuckDB 2.0 creates DuckLake 1.1
lakes by default, which engines without final-1.1 planning correctly refuse. With
`DUCKLAKE_1_0`, the oracle sets DuckLake's native `ducklake_default_version`:

```kotlin
CorpusRunner(corpusRoot, skipList, engine, metadataRewriter, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0)
ReplayDriver(engine, repoRoot, metadataRewriter, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0)
```

- An explicit `DUCKLAKE_VERSION` in a corpus ATTACH still wins; a file's own
  `SET`/`RESET ducklake_default_version` keeps its native meaning.
- Attaching an existing newer catalog fails loudly ("Cannot downgrade").
- Corpus SQL, goldens and mirror gating (snapshot-pinned, multiple lakes) are unchanged.
- `null` (default) keeps the native default, exactly as 0.13.0.
- All 0.13.0 constructors remain binary-compatible.

## Verification

Six new tests use a real 1.0-only mirror: the catalog library with final-1.1 reads
off, over a PostgreSQL metadata backend. On `general/metadata_cache.test` the native
default stores 1.1 and both mirrors fail. `DUCKLAKE_1_0` stores spec 1.0 and passes
2/2 real mirrors and 2/2 oracle goldens, with the corpus file digest unchanged. The
tests also cover an explicit 1.1 ATTACH winning (and honestly refused), the loud
downgrade error, `CorpusRunner` propagation and snapshot-pinned gating. The pinned
`general` directory replays identically with and without `DUCKLAKE_1_0` (349 records,
0 failures). Existing attachment, mirror and timeout tests pass.
