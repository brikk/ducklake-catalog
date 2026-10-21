# Maintained DuckDB 2.0.0 promotion — October 21, 2026

The user authorized upgrading the maintained engine lane to official 2.x. This
changes development dependencies/server/corpus, **not DuckLake 1.1 write support**
or the already-published 0.12.0 release. No push/publication is implied.

## Shipping provenance

| Component | Qualified artifact |
|---|---|
| DuckDB JDBC | `org.duckdb:duckdb_jdbc:2.0.0.0` |
| CLI/server/Python | Official `2.0.0`, core `d54cbbe3df` |
| Signed DuckLake | `417a9117f5` |
| Signed Quack | `f96420ed46`, protocol 3 |
| Optional Java Quack JDBC | `dev.brikk.duckdb:quack-jdbc:0.8.0`, unchanged |
| Corpus | `417a9117f590e8141742ac0579d02f6a90162e37`, matching shipped DuckLake |
| Nested DuckDB fixture source | Corpus gitlink `804b3f2070e3e604f1bcc7a1fbbf9f9ebc2fafbd` |
| Fresh DuckLake format | `1.1`; explicitly pinned writable lane remains `1.0` |

SHA-256:

- JDBC JAR: `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
- Official Linux amd64 CLI archive: `c6507206baf7793c6013dbd82368469a45f20dc0e17a6f92f204602f652ed2bc`.
- Extracted Linux amd64 CLI: `c41f957db5645d545a9caef430994cf262d24fad9297fef70c691395aadb43fb`.
- Official Linux arm64 CLI archive: `859e1875d2a9515fbb4bf8871061437a14af82320da68c753f094c4b776348fe`.
- Java Quack JAR: `320ccd96e978e1b30ff7ba77791dd0062bc6e72bd6d160e389b9e2c52269ac9d`.

The Docker fixture verifies archive digests for both architectures. Execution here
was Linux amd64, not arm64 certification. The release lock is separate from frozen
preview and layout locks; those historical pins were not rewritten.

## Read/write support

| Catalog format / route | Result / boundary |
|---|---|
| 1.0 PostgreSQL, MySQL, local DuckDB | Maintained catalog read/write regressions on JDBC 2.0.0.0 |
| 1.0 native Quack | Matched official native client/server; DDL, appends/deletes, rollback, contention, expiry/ANALYZE and outcome tests |
| 1.0 direct Java Quack | Driver 0.8.0 on final protocol-v3 peer; independent JVM without DuckDB JDBC also tested |
| Final 1.1 direct Java Quack | Default-off `enableFinal11Reads`, owned `readSession`, fresh and explicitly native-migrated fixtures |
| 1.1 writes, automatic migration | **Disabled**; no mutation path was enabled |
| Richer 1.1 schemas/types/stats, other 1.1 routes | Not qualified; retain [read-tier limits](../docs/READING-1.1.md) |

Native Quack bootstrap explicitly uses `DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION
FALSE`. Other new read/write lakes need the same explicit initialization options:
DuckDB 2.0's default is final 1.1, not a reason to accept writes our model cannot represent.
Existing DTOs/constructors/accessors and JSON shapes are unchanged.

### Actual 2.0 integration changes

- Quack's non-loopback server bind now defaults to HTTPS. The private test container
  explicitly disables SSL but publishes and verifies **only `127.0.0.1`**. Remote
  Docker daemons are rejected. Production TLS defaults and extension signatures are
  not disabled.
- Native appends now activate Quack's transaction manager. The old independent RPC
  `BEGIN` collided with that owner. `use_transaction := true` activates the native
  owner before reads/appends; JDBC commits/rollbacks complete that transaction once.
  Unknown COMMIT outcomes remain non-retryable; acknowledged post-commit failures
  retain their separate classification. Real two-session rollback/retry tests remain.
- Write-time schema/table lookups now use the transaction's static jOOQ context and
  wrapper instead of directly preparing parameters the native scan failed to forward.
- Direct multi-scans now work in Quack 2.0. The wrapper test checks agreement with
  direct results instead of requiring an obsolete native optimizer error.
- An isolated 1.5.6 JDBC subprocess still produces genuine legacy inline-schema
  defects. It never shares a JVM/native library or maintained/published classpath
  with 2.0. Historical missing-boundary and corruption assertions remain unchanged.
- Native NOT NULL validation can now inspect unknown-statistics files. Tests require
  valid non-NULL data to succeed and an actual NULL in an unknown-statistics file to
  fail, preserving values and nullability metadata.

## Corpus qualification and explicit exclusions

The new corpus is substantially larger than the old 9,697-record lane. Its SQL and
goldens are pristine. The replay renderer now implements 2.0's nested-backslash
quoting, BLOB quote/backslash hex escaping, nanosecond temporal text and session-zone
TIMESTAMPTZ rendering. An all-256-byte native comparison checks bytes independently;
the old nanosecond timestamp file skip is removed. Named JDBC sessions duplicate a
pristine template so root-local settings do not override later GLOBAL changes.

Eight small nested DuckDB Parquet files are checksum-fetched at the corpus's actual
gitlink, not the release core or a moving branch. They stay under `build/`, not in
the upstream checkout. Missing fixtures cause failures, not new file exclusions.

**One user-approved build-capability exclusion:**
`add_files/add_files_json_without_extension.test:16` expects JSON `loaded=false`,
but official CLI, Python and JDBC report `loaded=true`, `STATICALLY_LINKED`.
`TestShippingCorpusCapabilities` replays that exact unmodified file, requires that
exact original failure and requires every other import/geometry/error/golden check
to pass. The full-corpus skip reason names this test; the discrepancy is not
described as a native pass. Other existing unsupported harness constructs and
record exclusions remain visible in the report.

The nullable-lineage mirror uses the new corpus's actual query positions and
asserts all target queries were found. It selects the writable 1.0/backend axis
without replacing any expected values. It still requires NULL row-ID starts at
every selected rewrite read.

## Native correctness and migration

Executed on official Python/core/signed extensions, without unsigned extension flags:

- Local and PostgreSQL layout/SQL/default-format/migration canaries: all 12 each.
- Same-transaction delete/flush and positional CDC: 2/2; fresh inline read/flush: 3/3.
- Lineage/statistics/BLOB gates: all 42 on explicit 1.0 and fresh 1.1.
- Transient CDC: all 20 on 1.0 and 1.1; bucket-pruning controls: all 65 on both.
- User-name collision CDC: all 12 on 1.0, with explicit virtual identity projection
  and the original independent expected rows unchanged.
- Matched native Quack snapshot/CDC/time-travel ceilings: all 11 on 1.0 and 1.1;
  separate rollback/error recovery/commit reuse passed.
- 1.5.6 → official 2.0 native migration rehearsal: all 12 checks, preserving file and
  inline rows, row IDs, schema histories and historical snapshots. Reserved inline
  names reject atomically. The pristine old backup restores correctly.

**Storage downgrade is unsafe:** ordinary 2.0 checkpoint/write upgrades local `.db`
storage from version 7 to 69 even while DuckLake remains 1.0. Old 1.5.6 cannot reopen
the written/checkpointed or migrated copy. The untouched backup reopens; restore is
the rollback mechanism. This is not general PG/MySQL migration/restore certification.

## Reproduction and verification

```sh
python3 dev-docs/probes/prepare_corpus_data.py
mise exec -- ./gradlew check :ducklake-catalog:quackPreviewTest --rerun-tasks \
  -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/absolute/path/to/official-2.0.0/duckdb \
  --no-configuration-cache --no-daemon
```

Final verification passed:

- **605 maintained JUnit tests**: 510 catalog, 51 SLT and 44 replay; no JUnit skips/failures.
- **33 shipping direct-Java tests**: 15 transport/1.0 cases and 18 final-1.1 reader cases;
  fresh provenance reports assert official core/extensions, protocol 3 and unsigned=false.
- **17,602 corpus records passed**, zero failed; 100 file exclusions and 12 record
  exclusions remain visible across 948 discovered files. The one approved static-JSON
  exclusion replaces the removed nanosecond skip; its independent test preserves the
  original failure and passes all **11** remaining checks.
- All module checks, detekt, frozen Java/record/wire controls and Java 17 `checkAbi`
  passed (7,728 runtime classes across 17 artifacts).
- The separate native gates above and all 12 Python fixture/canary harness tests passed.

An actual **Java 17.0.2** process also passed JDBC 2.0.0.0/core identity checks,
catalog 1.0 schema/table creation and a native insert/read round trip. This is a
local smoke check, not a claim that the entire test suite or Doris ran on Java 17.

Execution notes: the first forced combined run completed all 605 maintained tests
but hit the tool's ten-minute cap before completing the direct-Java task. A later
intermediate rerun lost its PostgreSQL fixture connection in four inline tests;
the cause was not established. The affected class and official direct-Java suite
then passed, followed by a successful complete `check :ducklake-catalog:quackPreviewTest`
invocation (5m51s, full catalog execution and already-passed full corpus inputs
up-to-date). No assertions were weakened for those connection failures. Allow at
least twenty minutes for a fully forced run. Session-owned test infrastructure was
stopped; other agents' Docker/Gradle processes were left alone.

CI now executes shipping direct-Java qualification with the digest-verified CLI.
Native CI has separate frozen-preview/release targets and release strict/migration
gates. No CI push/run or snapshot publication is claimed before user authorization.

## Connector handoff

Trino/Doris agents must align runtime/native peers and the matching pristine corpus,
retain NULL/BLOB and nullable/embedded row-ID semantics, adopt 2.0 replay rendering,
and independently qualify scans/CDC/worker behavior. They must preserve non-retryable
unknown/acknowledged COMMIT outcomes through error mapping and cleanup. The catalog
upgrade alone neither certifies their connectors nor leases physical files after
planning. No sibling connector implementation was changed here.
