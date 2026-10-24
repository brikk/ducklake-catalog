# R07 consumer continuation and additive catalog APIs — October 24

## Current decision

**Latest follow-up:** Trino `e4743d5a7dd60bc05d3b4f9e84594662fb3a384e` adopts decoded
missing-file defaults with actual four-route two-worker SQL (118 candidate/264
released tests +lint/81 records). Doris `7535fcf6db5f1407f5706d85108380a06c697906`
and `a6aa725d0de6f39836ee33625a1a2e309d992390` adopt controlled capture/defaults
(13 live passes, candidate262 Java25/236 Java17, default253/227, existing one skip).
Both fresh-session assignments below have finished. Their next paging/control
assignments should use fresh GPT6.1Sol sessions and newly checksum-qualified bytes.

Trino's non-NULL unknown-history repro exposed a real contract mismatch, not
established SQL execution or a wrong value. The shared library validator now rejects
unknown kinds independently of initial-literal presence while preserving native
VARCHAR `NULL`'s known expression-kind encoding. Full latest library checks pass
**903 maintained+86 direct-Java tests/17,602 records**, zero failures/errors/JUnit
skips, lint/ABI/wire/Java17. [Fix, red repro, exact semantics and log](../docs/FILE-INITIAL-DEFAULTS-1.1.md).
The earlier898-test/candidate78c2a12 checkpoint below is retained as history; the
old bundles were not replaced. No latest-byte connector adoption is inferred.

**Useful guarded read-only SQL tiers now exist in both plugins; broad R07 and
production rollout remain NO-GO.** The October23 refusal/SPI-only reports are
historical checkpoints, not the latest implementation state. The owner authorized
continuing implementation and temporary local candidates, not publication, push,
deployment, default-on reads, migration, 1.1 connector writes or cleanup.

### Trino

- `9913cd6f990caa88548e773df2a3b72bf6ec95f0`: legacy TIMESTAMPTZ partition-pruning
  fix. Real SQL first lost the GMT-12-written matching row, then passes with
  conservative candidate retention and exact residuals, including migrated/mixed
  catalogs and session/calendar boundaries.
- `46e79429db5d3da440458b47d1bda347bfa1e22f`: explicit default-off read-only flag,
  metadata/lazy-worker sessions, logical head pinning, work limits, mutation barriers
  and recovery fixes. Actual two-worker SQL (coordinator scheduling disabled) passes
  on all five routes, including historical/tagged-delete/Puffin CDC, lineage,
  evolution, nanosecond and failure/recovery cases in the declared scope.
- Reported verification: **108 candidate / 242 released-lane tests**, lint and
  **81 maintained corpus records**, no new failures/skips or exclusion changes.
- Scoped GO for known-small matched-peer trials on58ef4d7, **not** globally bounded
  or multi-process/multi-host production certification. JDBC cancellation previously
  returned promptly but left the locked metadata statement alive until fixture
  unlock. Missing file defaults rejected rather than invented values.
- Source report:
  `/home/jayson/DEV/brikk/trino-ducklake/dev-docs/R07-TRINO-CANDIDATE-QUALIFICATION.md`.

### Doris

- `3228a80092bba5757bca391817e73e65f7090c7d`: guarded default-off PostgreSQL/S3
  native-file 1.1 tier, complete bounded statement-scope capture/recent history,
  schema/default/widening/NULL/BLOB/temporal SQL, actual current/tagged Parquet and
  historical/current Puffin slices (including same-path vectors and self-joins),
  embedded ID gaps/NULL starts, multiple row groups and recovery. Admission
  diagnostics and serialized Thrift optional fields were fixed.
- Reported verification: candidate **259 Java25 / 233 Java17** passes; default
  **253 / 227**; **14 targeted + 9 live** checks, ABI/lint/assembly/Python checks.
  Maintained Java lanes overlap and must not be summed as unique tests. One
  pre-existing disabled test remains visible; no new skip.
- Separate long-partition native producer gate remains **1 failure, zero errors/
  skips**. It is not a reader pass or proof that every S3 implementation has the
  same limit. Fixed signed artifacts do not have upstream's newer hash-name writer.
- Missing BE mandatory DATA/delete snapshot filtering, approved distributed inline
  transport, engine CDC surface and leases remain genuine engine/architecture
  boundaries. No temporary/shared-filesystem/staging-write workaround was introduced.
- Source report:
  `/home/jayson/DEV/brikk/doris-ducklake/dev-docs/VALIDATION-R07-IMPLEMENTATION-2026-10-24.md`.

## Catalog-owner work executed after consumer feedback

All are additive development APIs with existing constructors/records/wire and
0.4/1.0 behavior retained. Each unit was committed after its targeted and complete
maintained/direct-Java/corpus/lint/ABI verification; none changes native pins.

| Commit | Executed API work | Contract |
|---|---|---|
| `3fd75dcd7a31a0b54e40d544970ac106d8522280` | Fresh stable-ID decoded missing-file initial defaults; bounded final-1.1 column text | [File defaults](../docs/FILE-INITIAL-DEFAULTS-1.1.md) |
| `0058e8d574924128738596c72239fc8e5ef67878` | Single-use controlled owned sessions, actual JDBC statement cancel and remaining query timeout | [Session control](../docs/READ-SESSION-CONTROL.md) |
| `6146a3e` | SQL-limited active data/delete and CDC-added file pages, composite identities and original bounded paths | [File pages](../docs/READ-FILE-PAGES-1.1.md) |
| `78c2a12720f2951022668bf554228264c2264c67` | SQL-paged schemas/tables/columns/partition fields/values/name rows/inline registry versions | [Planning metadata](../docs/READ-PLANNING-METADATA-1.1.md) |

The cancellation tests hold a genuine PostgreSQL lock and observe both explicit
Statement.cancel and JDBC timeout stop the actual metadata statement **before
unlocking**, followed by successful same-pool reads. That is stronger than the
earlier connector responsiveness result, but still not arbitrary pool/setup/
prepare/network interruption, another driver's qualification or a physical lease.

Metadata and file pages apply SQL LIMIT and strict keyset seeks before transfer;
there is no unbounded materialize-and-trim workaround. Text is bounded in SQL and
oversize requests reject rather than truncate. Complete trees and total scope/
work/node/depth/path/byte budgets remain caller obligations. Absent physical inline
tables retain explicit registry cursor positions, not false terminal empty pages.

### Latest complete library verification

`check :ducklake-catalog:quackPreviewTest -Dducklake.corpus.dirs=all
-Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb
--no-daemon --no-configuration-cache`, using the isolated
`GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated`:

- **898 maintained JUnit tests**:749 catalog,51 SLT,98 replay.
- **86 separate direct-Java tests**; zero failures/errors/JUnit skips.
- **17,602 corpus records**, unchanged100 file/12 record exclusions and original
  goldens/pins; lint/frozen ABI/wire controls pass.
- Java17 maximum61: **7,810 classes across17 artifacts**.
- Final log `/tmp/opencode/catalog-r07-metadata-pages-full.log`,23m42s.

Strict native R02=3/R03=1/R04=1/M01=6+4 results remain unwaived; they were not rerun
or converted into passes by the additive API tests. A separate initial native MySQL
sequential-file producer also failed with UPDATE syntax unsupported; the original
log and the scoped native one-transaction read fixture boundary are documented in
the file-page contract. Existing-catalog MySQL reads are not given a 1.0-only ceiling.

## Candidate identities and fresh-session handoff

All bundles are isolated local Maven repositories at version0.13.0-SNAPSHOT,
containing catalog+test-fixtures/replay/SLT, Java17 JARs and SHA256 manifests.
No earlier bundle was overwritten. Resolve exclusively by the explicit repository
override and verify actual JAR bytes, not just requested coordinates.

- Original connector candidate: `/tmp/opencode/catalog-r07-58ef4d7`.
- Defaults candidate: `/tmp/opencode/catalog-r07-3fd75dc`.
- Cancellation candidate: `/tmp/opencode/catalog-r07-0058e8d`.
- Latest file/metadata pages: `/tmp/opencode/catalog-r07-78c2a12`.

Latest catalog JAR SHA256:
`5e15000f2c7ad536f096782f64be2d5a48e4db58eaa8af1381ae929efaab6909`.
The fixture/replay/SLT runtime JARs retain their previous byte hashes. Preserve
Doris's existing org.apache.doris Maven-local FE resolution; do not set global
`maven.repo.local` in a connector build.

Per the owner's context preference, the next assignments were sent to **fresh
GPT6.1Sol sessions**, not appended to the long first-round sessions:

- Trino `ses_e90746f29ffedNaKfgJYxVOQP7`: adopts3fd75dc defaults and verifies
  actual missing-field worker SQL. Result pending at this checkpoint.
- Doris `ses_e90574960ffenue3Zfjovee4cz`: adopts0058e8d controlled capture/defaults
  and verifies actual SQL cancellation/recovery. Result pending at this checkpoint.

Do not interrupt their active verification or infer they have adopted78c2a12 yet.
Use a concise next handoff when a session is ready, with exact tested commit/bytes.
No new connector success on later APIs is claimed before its own evidence returns.

## Work still open

- Catalog **decoded ordinary/CDC inline payload pages** and **deletion-candidate
  pages**, with original identity/default/schema/window semantics. Registry/file
  pages do not close these transfer gaps.
- Connector adoption of all new public page/control/default contracts and total
  query budgets, with actual positive scans and negative/fault recovery.
- Genuine legacy0.4 fixture/end-to-end evidence, broader route/type coverage and
  independently qualified optional-driver/multi-process packaging where claimed.
- Engine-owned retention/physical-file leases, deployment approval and Doris's
  missing transport/snapshot/CDC interfaces; no hidden architectural substitutes.
- The flag-off metadata-reachability observation is explicitly retained in
  `TestDirectQuackPreview`; do not silently replace that compatibility test. A
  successful handle lookup is not opt-in admission or payload qualification.

R07 stays open at these concrete boundaries. No source upgrades, new exclusions,
native failure waivers, global cleanup or production/default-on approval follows.
