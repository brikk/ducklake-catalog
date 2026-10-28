# Reproducing the October 28 triage

Audited catalog HEAD: `662a84a`; runtime sources match qualified `fb53459`.
These are **negative witnesses against unchanged code**, not fixes or passing
conformance tests. Their outputs intentionally show bad behavior. Future fixes
need desired-behavior regressions that fail before and pass after the repair.

All databases, files, symlink targets and worker latches belong to the probes.
The cleanup witness deliberately deletes only its own disposable parent/target
markers. The parser-allocation case runs in a separate 32 MiB JVM with a timeout.
The PostgreSQL isolation probe starts and stops its own Testcontainers server;
it does not access existing databases or globally stop infrastructure.

Prerequisites: repository JDK 25 launcher, a Java 17 runtime, Docker/Podman, and the
already-installed qualified DuckDB-2.0 DuckLake/PostgreSQL extensions. The probes
use LOAD, never INSTALL/FORCE INSTALL. They do not update pins or publish artifacts.

From the repository root:

```sh
export JAVA17=/absolute/path/to/jdk17/bin/java
export GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated
mkdir -p /tmp/opencode/catalog-triage-reproduce
mise exec -- ./gradlew -q \
  -I dev-docs/archive/triage-2026-10-28/classpaths.init.gradle \
  :ducklake-catalog:classes :ducklake-catalog:testFixturesClasses \
  :ducklake-test-corpus-replay:classes triageClasspaths \
  --no-daemon --no-configuration-cache \
  > /tmp/opencode/catalog-triage-reproduce/classpaths.json
python3 dev-docs/archive/triage-2026-10-28/run_probes.py \
  /tmp/opencode/catalog-triage-reproduce
```

`current.json` covers H01–H16 (H06 is the separate bounded child), N03/N05/N19.
`java17.json` is H15's actual minimum-runtime control. `n18.json`, `r01.json`,
`r04.json` and `n13.json` cover ordinary-rewrite data resurrection, historical-type
statistics, fault-injected legacy isolation and required commit-message enforcement.
The R04 probe changes only a test instance's connection wrapper; the opt-in strict
reader rejects the same injected setting failure. It is not a production fault claim.

Raw run outputs, workflow dry runs and 106 audited source/workflow hashes from the
original triage are under `/tmp/opencode/catalog-triage-2026-10-28/`. The
[triage report](../TRIAGE-2026-10-28.md) preserves the meaningful observations and
their exact classification if those temporary artifacts are later removed.
