# Build, test and CI

Run commands from the repository root. The pinned toolchain is in
[`mise.toml`](../mise.toml) / [`.sdkmanrc`](../.sdkmanrc).

## Toolchain

The Gradle launcher must use **JDK 25** because `build-logic` itself targets 25.
The Foojay resolver only supplies compile/test toolchains; launching on JDK 21 or
older fails during configuration. Published library bytecode targets Java 17.

```sh
mise install
mise exec -- ./gradlew build
mise exec -- ./gradlew publishToMavenLocal
```

Tests need Docker/Podman for Testcontainers. The checked-in
[jOOQ schema bindings](../ducklake-catalog/generated/) require Docker only when
[regenerated](SCHEMA-CODEGEN.md), not for normal compilation.

## Verification

Run focused regressions first. Before completing a code fix, run:

```sh
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

The opt-in `:ducklake-catalog:quackPreviewTest` task tests direct Java Quack against
an explicitly supplied staged 2.0 CLI without putting DuckDB JDBC on its classpath.
It is not run by default CI, does not skip when configuration is missing, and does
not change the maintained pins. See [exact target and reproduction](QUACK-2.0-QUALIFICATION.md).

- [CI](../.github/workflows/ci.yml): `check` on PRs/pushes, starter corpus in ordinary
  runs and full corpus nightly. Manual `full_corpus` requests replay everything.
- [Pinned native preview](../.github/workflows/duckdb2-preview.yml): locked native
  layout/migration checks, matched Quack peers and fixture/harness tests. This is
  not full JVM DuckDB 2.0 or DuckLake 1.1 qualification. Independently run the strict
  probes listed in the [probe index](probes/README.md).

Stop session-owned containers, servers and build daemons after verification.
See [the agent guide](AGENT-GUIDE.md) for commit/safety requirements and
[releasing](RELEASING.md) for publication steps.
