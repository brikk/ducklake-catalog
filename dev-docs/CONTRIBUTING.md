# Build, test and CI

Run commands from the repository root. The pinned toolchain is in
[`mise.toml`](../mise.toml) / [`.sdkmanrc`](../.sdkmanrc).

## Toolchain

The Gradle launcher must use **JDK 25** because `build-logic` itself targets 25.
The Foojay resolver only supplies compile/test toolchains; launching on JDK 21 or
older fails during configuration. Published library bytecode targets Java 17.

```sh
mise install
python3 dev-docs/probes/prepare_corpus_data.py
python3 dev-docs/probes/prepare_11_read_fixtures.py
mise exec -- ./gradlew build
mise exec -- ./gradlew publishToMavenLocal
```

Tests need Docker/Podman for Testcontainers. The checked-in
[jOOQ schema bindings](../ducklake-catalog/generated/) require Docker only when
[regenerated](SCHEMA-CODEGEN.md), not for normal compilation.

## Verification

Run focused regressions first. Before completing a code fix, run:

```sh
python3 dev-docs/probes/prepare_corpus_data.py
python3 dev-docs/probes/prepare_11_read_fixtures.py
mise exec -- ./gradlew check --rerun-tasks -Dducklake.corpus.dirs=all --no-configuration-cache
```

`check` compiles the modules and runs tests, detekt and Java 17 classfile checks.
Classfile checks alone do not prove complete public binary/API compatibility or
Java 17 runtime behavior. The [frozen consumer](FROZEN-CONSUMER.md) locks selected
released descriptors and wire tests cover the existing JSON shapes.

The DuckLake corpus is a pinned submodule; preserve its skips and hash unless an
upgrade is explicitly approved. A corpus pass is not proof of connector coverage
or of every strict native correctness gate.

## CI lanes

`:ducklake-catalog:quackPreviewTest` tests direct Java Quack against an explicitly
supplied official 2.0 CLI without putting DuckDB JDBC on its classpath. CI runs it
with a checksum-verified release CLI; it fails rather than skips if configuration
is absent. The default explicit target is `release`; the frozen staged target is
available with `-Pducklake.quack.target=alpha49096`. See [shipping qualification](UPGRADE-DUCKDB-2.0.0.md).

Full replay also uses eight small, checksum-pinned nested DuckDB fixtures. The
preparation script downloads only these files at the corpus's nested gitlink into
`ducklake-test-corpus-replay/build/corpus-duckdb`, without fetching the whole DuckDB
tree or editing upstream data. Override with `-Dducklake.corpus.duckdb.root=/checkout/duckdb`.
The 1.5.6 JDBC configuration is an isolated historical inline-defect producer only;
it never joins maintained, replay, published or direct-Java qualification classpaths.

R02 additionally checksum-pins four newer upstream inputs, including a genuine 1.5.6
catalog. `prepare_11_read_fixtures.py` fetches only those files into the catalog's
`build/read-fixtures`; it does not move the maintained corpus pin. For the separate
**strict native** gate, run `:ducklake-test-corpus-replay:upstreamR02NativeTest`.
That task is not part of `check`: it tests a newer source revision and currently
fails all three cases on shipped DuckLake `417a9117` (including a native crash).
Failures are not accepted as passes or assumption-skipped. Child JVMs bound execution,
preserve logs/hs_err under `build/reports/upstream-r02-native` and keep SIGSEGV from
turning unfinished test cases into skips. The maintained corpus exclusions are unchanged.

- [CI](../.github/workflows/ci.yml): `check` on PRs/pushes, starter corpus in ordinary
  runs and full corpus nightly. Manual `full_corpus` requests replay everything.
- [Pinned native qualification](../.github/workflows/duckdb2-preview.yml): separate
  frozen-preview and shipping-release targets on local/PostgreSQL metadata. The
  release target adds strict native correctness and old-to-final migration gates;
  historical preview/layout locks remain unchanged. This is not connector or full
  DuckLake 1.1 write certification.

Stop session-owned containers, servers and build daemons after verification.
See [the agent guide](AGENT-GUIDE.md) for commit/safety requirements and
[releasing](RELEASING.md) for publication steps.
