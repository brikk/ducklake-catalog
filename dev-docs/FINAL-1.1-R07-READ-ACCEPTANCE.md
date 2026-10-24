# R07 read acceptance and rollout handoff — October 23, 2026

**October24 continuation:** the original library/consumer-handoff checkpoint below
is historical. Both plugins now have positive guarded read-only SQL evidence;
Trino's timezone fix is verified, and consumer-requested catalog default/control/
file-page/planning-metadata APIs have been implemented and fully checked. Fresh
GPT6.1Sol sessions are adopting them. Broad R07/production remains NO-GO; payload/
deletion paging, engine/lease/route/deployment requirements and native failures are
not waived. [Latest exact commits, evidence and open work](R07-CONSUMER-CONTINUATION-2026-10-24.md).

## Decision

**Guarded library acceptance: PASS. Production/default-on/broad-native rollout:
NOT APPROVED.** R07's library checks and consumer handoff are complete; the whole
R07 checkbox stays open until the separately owned rollout requirements pass.
This is not another unverified checkpoint or a claim that failing native gates
passed. No connector implementation, publication or deployment was performed.

The approved read scope is the [five-route matrix](../docs/READ-BACKENDS.md),
including MySQL's [existing-catalog scalar read-only subset](../docs/READ-MYSQL-1.1.md),
not native creation/upgrade/write/scanner parity. `enableFinal11Reads` remains
default-off, all 1.1 library writes remain refused, and no automatic migration or
artifact/corpus/layout pin changes.

## Exact tested lane and commands

- DuckDB JDBC **2.0.0.0**, core/CLI **2.0.0 / `d54cbbe3df`**.
- Signed DuckLake **`417a9117f5`**, Quack **`f96420ed46`**, Java Quack JDBC **0.8.0**.
- PostgreSQL scanner **`c0f5a3c0c4`**, MySQL scanner **`f7fc0d4271`**.
- Corpus **`417a9117f590e8141742ac0579d02f6a90162e37`**, with existing frozen
  newer regression inputs at **`ea543c719a4e88a8154f9db897aa7c580da3308e`**.
- Linux amd64 execution. Dependency checksums and Java 17 floor remain the existing
  [shipping qualification](UPGRADE-DUCKDB-2.0.0.md), not advertised driver strings.

```sh
python3 dev-docs/probes/prepare_corpus_data.py
python3 dev-docs/probes/prepare_11_read_fixtures.py
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks \
  -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-configuration-cache --no-daemon
```

An earlier full run was interrupted by a Gradle daemon stop command, with no
completed verdict. The unchanged-source rerun used an isolated daemon registry
(same cached artifacts/toolchain) and completed successfully. That interrupted run
is not counted as a pass. Final log:
`/tmp/opencode/catalog-r07-full-acceptance-final.log`.

## Maintained results

**839 maintained JUnit tests** (690 catalog, 51 SLT, 98 replay) plus **86 separate
driver-only direct-Java tests** pass, zero failures/errors/JUnit skips. The complete
matching corpus has **17,602 records passed**, with unchanged **100 file / 12
record exclusions**. Lint, frozen Java 0.11.0 consumer/record/JSON controls and Java
17 checks pass: **7,795 classes across 17 artifacts**. Final forced run: **21m25s**.

Existing **165 mandatory transport mirrors across 25 pristine source files**, plus
two separate drained-local final-state checks, remain intact. Original SQL,
expected values/errors/digests and exclusions are unchanged. The new acceptance
fixtures are explicitly authored native/JDBC tests, not fabricated upstream goldens
or alleged JVM Parquet scans.

### New cross-route acceptance cases

| Route | Shared read acceptance | Native tagged-delete/merge lineage | Total new tests |
|---|---:|---:|---:|
| Local DuckDB READ_ONLY | 6 | 3 | 9 |
| Native Quack metadata-only | 6 | 3 | 9 |
| Direct Java Quack | 6 | 3 | 9 |
| PostgreSQL JDBC | 6 | 3 | 9 |
| Existing MySQL 1.1 JDBC | 6 | Out of native mutation scope, not a skipped test | 6 |

All **42** new cases execute and pass:

- A genuinely native mixed file/inline table is planned without double counting.
  File/inline absolute row-ID sets are disjoint and cover the original rows; their
  allocation is not assumed to follow SQL insertion order. NULL/BLOB/literal-NULL
  values and conservative pruning survive the combined plan.
- Real Puffin delete vectors preserve file paths, nullable advisory row groups and
  counts. A single vector is a bare deletion-vector-v1 blob, not necessarily a
  `PFA1` container: bounded byte checks verify length, magic and CRC32 independently.
- Geometry is preserved in column/file metadata and produces real native Parquet
  files without claiming a canonical inline geometry object or connector decoder.
- Caller aborts retain the original exception, roll back/release the owned session,
  and allow the next session to plan again. Missing required SQL columns propagate
  failures rather than become empty successful plans. These checks do not certify
  every network-outage/cancellation/lease-recovery scenario.
- Bounded hierarchy/retained-reference pages and caller page budgets terminate
  without duplicates. Invalid limits and invisible companion snapshots reject;
  absent future `getSnapshot` lookups stay absent. Existing legacy list APIs are
  still materialized lists, not newly advertised as streaming or globally paged.
- On the four native mutation-qualified routes, real inline flush emits Parquet
  delete files with multiple embedded deletion snapshots and NULL `partial_max`.
  Metadata candidates still cover the later window, including current=previous
  paths. Native historical values confirm the per-position filtering obligation.
- Native merge output preserves embedded absolute row IDs and source snapshots
  across file ranges separated by a real inline-row gap; nullable `rowIdStart`
  stays NULL even in historical descriptors. The same physical merge output can
  serve older snapshots and must be filtered by embedded row snapshot tags.
- Multi-snapshot Puffin containers preserve cumulative blob cardinalities and
  `ducklake-snapshot-id` properties. An identical current/previous container path
  does not mean there are no in-window changes; consumers must select/difference
  the snapshot-tagged vectors.

Native Parquet/Puffin inspection in these tests is an **independent fixture oracle**,
not a library or connector scan. Local native producers close before the database-
read-only instance opens. Online local RW/RO coexistence is not inferred.

## Acceptance hardening: historical/unknown counts

The audit identified an unsafe metadata-only assumption: a historical data file
can include later rows after cross-snapshot merge, while a historical delete file
can include later deletions. Totals can therefore **overcount OR undercount**, not
form a guaranteed lower bound.

The default-off final-1.1 tier now explicitly refuses historical file-backed
`getLiveRowCount` requests and unknown active delete counts. A snapshot-aware scan
is required; NULL delete counts cannot become zero. Negative net counts fail as
corruption rather than clamp to zero. Latest known-count and inline-only historical
paths remain available. Existing 0.4/1.0 behavior and public record/constructor/wire
shapes are unchanged. Consumers must handle this intentional **0.13.0-SNAPSHOT
opt-in narrowing** rather than substitute a gross table count or exact COUNT pushdown.

Snapshot-preservation claims are operation-specific: merge preserves its inputs'
embedded snapshot tags; native full delete rewrite can rebase a new file's visibility
begin and omit old row snapshot tags. This report does not promise an immutable
physical birth snapshot through every native maintenance operation or fabricate an
old tag where the native file lacks it. Metadata descriptors expose actual facts;
CDC assembly still needs the full source/window semantics.

## Strict native outcomes — visible failures, never green acceptance counts

All five tasks were rerun with the same artifacts and `--rerun-tasks --continue`:

| Task | Tests | Failures | Errors | Skips | Interpretation |
|---|---:|---:|---:|---:|---|
| `:ducklake-test-corpus-replay:upstreamR02NativeTest` | 3 | 3 | 0 | 0 | Legacy STRUCT order, accepted nonliteral defaults, isolated same-transaction crash |
| `:ducklake-test-corpus-replay:upstreamR03NativeTest` | 1 | 1 | 0 | 0 | Native interval flush loses microseconds/components |
| `:ducklake-test-corpus-replay:upstreamR04NativeTest` | 1 | 1 | 0 | 0 | Retyped legacy bounds cause native filter/aggregate failures |
| `:ducklake-catalog:final11MySqlNativeTest` | 6 | 6 | 0 | 0 | Deferred native creation/migration/mutation/type fidelity |
| `:ducklake-test-corpus-replay:upstreamMySqlNativeTest` | 4 | 4 | 0 | 0 | Deferred native scanner/write/lifecycle, unchanged whole-file goldens |

Final strict log: `/tmp/opencode/catalog-r07-strict-native-final.log`. Crash-prone
native R02 cases remain bounded child processes, not crashes of the maintained test
worker. Their original inputs, expectations and failures are preserved. Merged
source fixes are not treated as present in the shipping binary.

R02–R04 block blanket native parity, affected native persistence/optimization and
unrestricted 1.1 claims. Reader protections/independent passing JVM assertions do
not fix or waive those native failures. Deferred M01 MySQL results are not a blanket
ban on the separately owner-approved existing-catalog JDBC read subset.

## Consumer rollout checklist — not performed in this repository

Both owned agents (`trino-ducklake`, unreleased `doris-ducklake`) must provide their
own evidence before claiming a coordinated rollout:

- [ ] Adopt decoded inline/CDC APIs or the route-specific raw value contract,
  including temporal text, NULL/BLOB semantics, stable field IDs and unsupported cases.
- [ ] Prefer embedded absolute row IDs; preserve nullable contiguous starts and
  filter cross-snapshot DATA rows by their embedded origin snapshot.
- [ ] Filter Parquet delete positions by embedded deletion snapshots even when
  `partial_max` is NULL; do not treat current=previous as self-canceling tagged data.
- [ ] Support/reject both bare Puffin vectors and snapshot-tagged containers; choose
  applicable cumulative blobs and compute window deltas. Format recognition is not
  evidence of a working decoder.
- [ ] Verify their own Parquet/geometry/footer/schema/lineage/deletion scans and CDC
  update pairing/transient-row suppression. This library report does not certify them.
- [ ] Handle historical/unknown count refusal with a scan or no estimate; never
  promote raw bounds/exactness into pruning or exact aggregates without qualification.
- [ ] Keep complete metadata planning in one owned read session, apply total
  page/path/traversal budgets, and abort on incomplete/failed plans. A session or
  resolved path is not a physical-file lease or cleanup/deletion permission.
- [ ] Use nullable SQL-server backend profiles rather than fabricated DuckDB-only
  capabilities; qualify MySQL's exact scalar/UTC/InnoDB/boolean/type boundaries.
- [ ] Qualify cancellation/network/lease recovery and worker/coordinator rollout on
  matched peers. No mixed-node capability negotiation is implied by unchanged JSON.
- [ ] Keep all 1.1 writes and automatic migration disabled; no publication, default-on
  rollout, physical cleanup or production catalog mutation follows from this report.

## Completion boundary

R07's in-repository library acceptance, count safety hardening and handoff are
finished. **R07 as a whole remains unchecked:** downstream scan/CDC/worker evidence
and any broader release approval have not been supplied. Broad native gates remain
red. No other session's work, artifacts or deployment is inferred from this result.
See [canonical queue](TODO-DUCKLAKE-1.1-SUPPORT.md) and
[consumer contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md).
