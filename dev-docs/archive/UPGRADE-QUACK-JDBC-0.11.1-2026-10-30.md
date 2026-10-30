# Java Quack JDBC 0.11.1 adoption — October 30, 2026

Owner requested adoption of `dev.brikk.duckdb:quack-jdbc:0.11.1`. Base `d019fed`
already contains the pushed0.11.0 adoption and review R04 implementation/evidence.
This request does not authorize a push, publication or consumer artifact adoption.

**Driver adoption checks pass; full project qualification remains blocked on the
same six strict embedded-JDBC TIME24:00 failures.** No new regression was observed.
The known failures remain visible, not skipped or converted to values. This is
not a green full-check claim, and no push is authorized by this request.

## Changes and artifact identity

- Shared driver pin **0.11.0 → 0.11.1**, plus actual-driver version assertions and
  the strict JAR digest pin. Existing wire samples and historical verification
  records retain their original versions and results.
- Published Central JAR SHA256:
  `8cff98a94eb793569f3c6de0bc840edfc7ba9bb1118e5fb0f30ac0429d9fe3b3`.
  Independent download matches Central's `.jar.sha256`. All **105 classes** target
  Java17-or-lower (major61), with no native libraries or bundled `org.duckdb` classes.
  The POM has no runtime dependencies; its1.5.6 DuckDB property is upstream test-only.
- The [tagged changelog](https://github.com/brikk/duckdb-quack-jdbc/blob/v0.11.1/CHANGELOG.md)
  gives CANCEL its own two-second whole-operation deadline, covering headers/body/
  custom waits. If an acknowledgement cannot be confirmed, the driver closes using
  the separate two-second DISCONNECT budget and throws instead of advertising reuse.
  Ordinary query deadlines, single-attempt controls and statement-owned UUIDs remain.
  Uncooperative custom I/O can retain interrupted daemon workers; no hard-kill or
  universal resource-reclamation guarantee is inferred.
- Embedded JDBC stays **2.0.0.0**, core **2.0.0 / d54cbbe3df**. Native server,
  signed extensions, corpus, isolated legacy producer and all1.1 write/migration
  guards remain unchanged. No catalog runtime or sibling implementation was edited.

## Verification

- Focused **44 catalog + all86 direct-Java cases pass**, zero failures/errors/skips,
  with catalog lint and Java17 checks passing. Existing actual-version and binary
  assertions are updated to the published artifact, not removed or wildcarded.
- The [standalone Java17 witness](quack-jdbc-0.11.1-2026-10-30/Java17Quack0111Smoke.java)
  compiles with `--release17` and **passes on actualJava17.0.2**. The direct client
  excludes embedded JDBC and old Quack JARs. It verifies0.11.1/v3/core2.0 identity,
  negative-epoch zoned nanoseconds/NULL and healthy confirmed cancellation/reuse.
- Two real HTTP downstream-partition cases forward the owned query's CANCEL and
  DISCONNECT to the signed2.0 backend, then withhold response headers or stall the
  response body. General requestTimeout is30s, while cancellation fails closed in
  **4,044ms / 4,001ms** (the two separate control budgets), under a6s safety bound.
  The connection is closed/non-reusable, exactly one CANCEL/DISCONNECT is observed,
  the cancelled UUID equals the query's own PREPARE UUID, and no SQL is replayed.
  Releasing the partition exposes query SQL state57014; repeated close sends no
  second DISCONNECT. All owned proxy/query/server resources are released and joined.
- This does not rerun upstream's entire704-test oracle profiles or certify every
  custom transport, v1 peer, library deadline, FETCH race, memory/streaming or
  manual-transaction behavior. No skipped upstream integration result is imported
  as our evidence. The v3 cancellation path is exercised here with real HTTP/backend
  work, not merely a responsive future or native statement standing in for Quack.
- Full forced verification: **1,118/1,118 catalog**, **86/86 direct-Java** and
  **51/51 SLT** pass; replay **145/146 pass**, with the sole failing JUnit case being
  identity control and the same six TIME endpoint records in
  `data_inlining/data_inlining_types.test:36/46/53` and `types/all_types.test:32/42/49`.
  Corpus: **17,596 passed / six failed / 12 original record skips**, with all100
  original file exclusions unchanged. Zero JUnit errors/skips. Every test group
  actually executed; no stale focused XML was counted as full verification.
- Full run: **40m39s**, exit1, with `--continue`. Lint, Java17 classfile checks
  (7,843 classes / 17 artifacts) and existing catalog ABI/wire controls pass.
  Pinned input preparation succeeded; all six pin/assertion hashes captured before
  the run still match. No additional failure, golden rewrite or new skip was
  introduced. H05/H09/H08/review R04 remain pending strict project qualification;
  this driver adoption does not promote those source repairs to Complete.

Full command:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```

Evidence under `/tmp/opencode/`: `catalog-quack0111-focused{.log,-xml/}`,
`catalog-quack0111-dependencies.log`, `catalog-quack0111-scan/` (artifact, Java17
source execution and scoped hashes) and `catalog-quack0111-full{.log,-xml/}`
(with summary/failures). No other pin,
format admission, native gate or candidate rollout is promoted by a passing driver
check, and old0.11.0 results are not relabelled as0.11.1 verification.
All17 current fixture provenance files attest0.11.1, the published checksum,
protocol3, core `d54cbbe3df` and unsigned extensions disabled. Six scoped hashes
and72 local-link checks are recorded in `catalog-quack0111-scan/verification.json`;
provenance copies are under `catalog-quack0111-scan/provenance/`.
