# Java Quack JDBC 0.11.0 adoption — October 29, 2026

Owner requested `dev.brikk.duckdb:quack-jdbc:0.11.0`, verification and commit/push.
Base `a591437` includes the two previously unpushed review R04 commits. Their
scoped success and pending strict project qualification remain separately recorded;
the requested push includes those ancestors, without claiming they become Complete.

**Driver adoption checks pass; full project qualification remains blocked on the
same six strict TIME failures.** Work began October29; full verification finished
October30. The owner authorized commit/push, including the existing R04 ancestors.
This pure-Java driver update does not address the embedded-JDBC TIME limitation or
authorize new skips/values/golden changes. No green full-check claim is made.

## Artifact and scope

- Shared version pin: **0.10.0 → 0.11.0**. Actual-driver version assertions and
  the direct-Java JAR checksum pin are updated, not weakened. Historical catalog
  release notes, JSON samples and prior verification reports retain their old values.
- Maven Central JAR SHA-256: `7c5889640b38e494a39c0bf818f1e0e35fb68152a00e0dcc9f273a5f29935ff3`,
  independently downloaded and matched to the published `.jar.sha256`.
- All **105 classes** are Java17-or-lower (major61). The JAR contains no native
  libraries or `org.duckdb` classes. The POM has no runtime dependencies. Its1.5.6
  property belongs only to an optional upstream oracle-test profile, not our runtime.
- Embedded JDBC remains **2.0.0.0**, core **2.0.0 / d54cbbe3df**. Signed server,
  Quack/DuckLake extensions, corpus and the isolated historical1.5.6 producer are
  unchanged. No catalog runtime implementation, format guard, write support,
  schema authoring, automatic migration or sibling code changes are included.
- The [tagged changelog](https://github.com/brikk/duckdb-quack-jdbc/blob/v0.11.0/CHANGELOG.md)
  adds v3 statement cancellation and TIMESTAMPTZ_NS binary decoding/APPEND, changes
  the PREPARE inline hint, and bounds DISCONNECT teardown. Its default prefix hint
  is not a global streaming/memory guarantee. Upstream's reported698-test oracle
  suites are not claimed as rerun by this repository.

## Verification

- Gradle resolution confirms Quack0.11.0 on the direct-Java and replay test
  classpaths, while embedded replay runtime stays DuckDB JDBC2.0.0.0.
- Focused checks: **44 catalog + all86 direct-Java tests pass**, zero failures/
  errors/skips, with catalog lint and Java17 checks passing. The catalog subset
  includes three backend routes, direct-driver integration, wire samples and all
  seven review R04 setup-failure groups. No version assertion or checksum was removed.
- Independent actual **Java17.0.2** [live-driver witness](quack-jdbc-0.11.0-2026-10-29/Java17Quack011Smoke.java)
  compiles with `--release17` and **passes** against the existing owned2.0 server
  fixture. The client classpath explicitly excludes embedded DuckDB JDBC and old
  Java Quack JARs. It checks actual0.11.0 metadata, negotiated v3, core identity,
  exact negative-epoch zoned nanoseconds and SQL NULL. It then observes its owned
  aggregate query in the server's active-query view, issues real Statement.cancel,
  requires SQL state57014 within3s and confirms connection reuse. The backend10s
  execution limit is only test safety, not the cause of successful cancellation.
  Executor, connection and server are owned and cleaned up.
- That driver-level cancellation check does not promote library read-session
  cancellation across every transport, whole-response deadlines, all close-failure
  paths, manual-transaction cancellation or compressed-range streaming gates.
  Likewise direct TIMESTAMPTZ_NS support does not automatically broaden the catalog's
  qualified scalar/authoring tiers or permit any1.1 writes.
- Full forced run, **40m36s** with `--continue`: **1,118/1,118 catalog**,
  **86/86 direct-Java**, **51/51 SLT** pass; replay **145/146 pass**. The only failing
  JUnit test is identity control with the same six original TIME endpoint failures
  (`data_inlining/data_inlining_types.test:36/46/53`, `types/all_types.test:32/42/49`).
  Corpus: **17,596 passed / six failed / 12 original record skips**, with all100
  original file exclusions unchanged. No JUnit errors/skips or additional regression
  was observed. All test groups actually executed; no stale XML was treated as a run.
- Catalog/replay/SLT lint, Java17 classfile checks (7,843 classes / 17 artifacts)
  and existing catalog ABI/wire controls pass. Pinned input preparation succeeded;
  all six final pin/assertion hashes captured before the run still match.
  **Full verification exits1**, not a green project gate. H05/H09/H08/review R04
  remain pending qualification; this dependency adoption does not promote them.

Full command:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```

Evidence under `/tmp/opencode/`: `catalog-quack011-focused.log`,
`catalog-quack011-dependencies.log`, `catalog-quack011-scan/artifact.json` and
`catalog-quack011-scan/java17/` (compile/stdout/stderr), plus
`catalog-quack011-focused-xml/`, `catalog-quack011-scan/source-hashes.json` and
`catalog-quack011-full{.log,-xml/}` (including summary/failures). Old0.10.0 and review R04
reports are preserved rather than relabelled as having used0.11.0.
All17 current fixture provenance files attest driver0.11.0, the published JAR SHA,
v3, core `d54cbbe3df` and unsigned extensions disabled. Scoped hash and71 local-link
checks are recorded in `catalog-quack011-scan/verification.json`; provenance is
copied under `catalog-quack011-scan/provenance/`.
