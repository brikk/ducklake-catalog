# DuckLake upstream review — October 24, 2026

## Decision

**Keep the checksum-pinned candidate and continue connector implementation, but
not wholly as-is: prioritize a Trino legacy TIMESTAMPTZ partition-pruning regression
and fix before broader qualification.** No dependency upgrade, source build, corpus
repin, deployment, default-on reads, migration or 1.1 writes is authorized by this
review. R07 remains NO-GO in both consumers.

An upstream source merge is not a tested signed artifact. The public Linux amd64
DuckDB-2.0 extension downloads still exactly match the tested binaries. Existing
native failures remain unwaived, and the connector implementation gaps do not
disappear with these upstream changes.

## Compared revisions and availability

- Last recorded upstream-main checkpoint:
  `ea543c719a4e88a8154f9db897aa7c580da3308e`, October 21.
- New inspected main: `947c8496c9becc53ed487b859384ce65e6edd2e3`, October 24.
- Delta: **seven first-parent merges**, 153 reachable commits including merged
  branch history, 90 changed files. The corpus checkout itself stays at
  `417a9117f590e8141742ac0579d02f6a90162e37`; only its remote refs were fetched.
- Latest published DuckDB/JDBC releases remain **2.0.0 / 2.0.0.0** according to
  their GitHub release APIs. DuckLake has no GitHub release entries, so artifact
  availability was checked directly rather than inferred from the release list.

Downloaded these HTTPS core-repository artifacts into an isolated directory under
`/tmp/opencode/catalog-upstream-review-2026-10-24/artifacts`, without INSTALL,
FORCE INSTALL or replacing any cached extension:

| v2.0.0 / linux_amd64 artifact | Actual extension revision | SHA-256 of decompressed extension |
|---|---|---|
| DuckLake | `417a9117f5` | `4b497cf95d768004d34a97e7098d33f687e994dc523945948c321af25061b39b` |
| Quack | `f96420ed46` | `9c893bd543ec2c5253d0c47d5614f90a1bfa1791c735dc6bbcea4e8bd3811a97` |
| PostgreSQL scanner | `c0f5a3c0c4` | `39e661c5cfb678c9472f5acd88235f84f3ef4126e7444c7c8a01214d9b291601` |
| MySQL scanner | `f7fc0d4271` | `6055f1a59dc527df795afccbf59279500d1ddc239608dd310c3eba0e98d1a11c` |

All four are byte-identical to the cached qualified extensions. Each loads into
the official 2.0.0 CLI by its absolute isolated path with
`allow_unsigned_extensions=false`; loaded extension revisions agree with their
embedded metadata. URLs are
`https://extensions.duckdb.org/v2.0.0/linux_amd64/<name>.duckdb_extension.gz`.
Their HTTP Last-Modified values still say October 20; the byte comparison, not
that timestamp, establishes identity.

## New source changes and impact

| Upstream merge | Relevant changes | Our action |
|---|---|---|
| [#1821](https://github.com/duckdb/ducklake/pull/1821) | Cleanup when metadata rollback or pre-send Quack commit fails; partition-transform type checks; retry/conflict diagnostics | Retain uncertain-COMMIT ownership rules; do not port native cleanup behavior into a JVM deletion permission. Future native qualification needs failure-boundary cases. |
| [#1823](https://github.com/duckdb/ducklake/pull/1823) | `ducklake_default_version` applies only to new catalogs; encrypted catalogs use Parquet deletes, not unencrypted Puffin | Keep explicit version/automatic-migration options in fixtures. Do not use a global default as admission or migration protection; no encrypted/Puffin capability expansion. |
| [#1826](https://github.com/duckdb/ducklake/pull/1826) | Retry-count overflow/hints, maintenance finite/year>=1 checks, view bindings, less PostgreSQL cache invalidation | Native mutation/maintenance/performance follow-up, not a read rollout unblocker. No speculative JVM port. |
| [#1824](https://github.com/duckdb/ducklake/pull/1824) | Native PostgreSQL pruning for nanosecond temporal values, TIME and UUID, with widened microsecond bounds | Do not enable JVM pruning/exact aggregates from this native change. Keep the conservative read tier; adopt focused negative/precision cases before any later optimization. |
| [#1836](https://github.com/duckdb/ducklake/pull/1836) | TIMESTAMPTZ calendar partition transforms use UTC for new writes; legacy values retained; Quack read-only attachment change; row-group options; cache/NUL/schema guards | Prioritize the legacy timezone finding below. Add hashed-path/row-group/attach regression gates before a future artifact upgrade. |
| [#1835](https://github.com/duckdb/ducklake/pull/1835) | Native CI/source pins move to DuckDB `d54cbbe3`, PostgreSQL scanner `71cd422e`, Quack `b2d9e770`; SQLite pin also moves | Core now matches our stable core, but the scanner/Quack source revisions do not match signed artifacts. Treat a later extension upgrade as a separate coordinated qualification. |
| [#1838](https://github.com/duckdb/ducklake/pull/1838) | MD5 names for >255-byte escaped partition components; transient/equivalent partition-spec fixes; import range validation/refined-timestamp bounds; TIMETZ ordering; orphan path NULLs; internal error settings | Preserve stored metadata values and opaque paths; never infer native partition values from directory spelling. Keep bounds unknown/conservative and cleanup gated. |

No new migration-layout, Puffin-decoder or deletion-vector-decoder change appears
in this delta. The older R02/R04 source fixes were already present at the previous
main checkpoint; none of this makes them part of signed `417a9117f5`. This review
does not establish a fix for the R03 interval-flush loss or deferred native MySQL.
The strict gates were **not rerun** here; their last reported failures remain
R02=3, R03=1, R04=1 and M01=6+4, not new pass claims.

### Confirmed targeted finding: old timezone partitions versus Trino matcher

Upstream #1836 explicitly changes calendar `year/month/day/hour(TIMESTAMPTZ)` for
**new** native writes to UTC. Its pristine
`test/sql/partitioning/calendar_timestamptz.test` retains a legacy GMT-12 catalog
whose stored values are `2020,12,31,17` for `2021-01-01 05:00:00+00`; migration does
not rewrite them. Existing catalog format alone therefore cannot attest UTC encoding.

This review reproduced the relevant parts without changing connector sources:

1. Official 2.0.0 core plus the exact signed DuckLake `417a9117f5` creates a
   disposable **1.0** catalog with automatic migration false, no inline rows,
   and calendar partitions on TIMESTAMPTZ.
2. An insert in `Etc/GMT+12` of `2021-01-01 05:00:00+00` produces stored partition
   values **2020 / 12 / 31 / 17**. A native equality query in UTC returns the row.
3. The current compiled Trino `DucklakeTemporalPartitionMatcher` at consumer
   commit `12377fe` is invoked on its real released-lane runtime classpath.
   `Domain.singleValue(TIMESTAMP_TZ_MILLIS, 2021-01-01T05:00Z)` with stored calendar
   YEAR `2020` returns **false**, with read leniency both false and true.
4. Source inspection confirms `DucklakeSplitManager` uses this matcher to remove
   candidate files. Calendar/epoch leniency is not timezone leniency; a residual
   filter cannot restore an already-pruned file.

This is **native producer + real matcher-level evidence**, not a completed Trino
SQL/distributed reproduction. It is enough to require the regression/fix work;
do not claim a fully executed SQL failure or a library metadata defect. The library
transports the actual stored partition values without recomputing them.

Evidence/scripts retained under
`/tmp/opencode/catalog-upstream-review-2026-10-24/`:

- `legacy-timezone-partitions.sql`, `legacy-timezone-native.log`
- `LegacyTimezonePartitionProbe.java`, `trino-legacy-timezone-matcher.log`
- `print-trino-classpath.gradle`, `trino-classpath.log`

**Required next action, owned by Trino:** reproduce through split/SQL planning,
then prevent false-negative pruning of legacy/mixed-provenance TIMESTAMPTZ calendar
partitions while retaining the residual predicate. Include old 1.0, migrated/mixed
catalogs, non-UTC sessions and year/month/day/hour boundaries. Without sufficient
writer/partition provenance, retain candidates rather than assume UTC. This matters
to already-readable 1.0 catalogs, not just the proposed 1.1 enablement. No sibling
source fix or new agent dispatch was performed by this review.

### Future-upgrade compatibility gates

- **Quack:** current `QuackBackedDuckDbCatalogUrl.readConnectionInitSql()` sends
  `(READ_ONLY)` on its metadata-only Quack ATTACH. New Quack source rejects that
  option, and native DuckLake #1836 omits it while guarding writes itself. A future
  native Quack upgrade must adapt/qualify our initializer without bootstrap,
  migration, or weakening the actual read-only API/session contract. Do not simply
  replace the tested extension underneath this initializer. Java Quack JDBC 0.8.0
  has separate transport/session qualification; a native ATTACH change does not
  certify or invalidate it automatically.
- **Paths:** #1838 hashes long native partition path components, but the actual
  identity values remain in metadata (and native data columns). Both connectors
  distinguish these from `add_files` body-absent Hive columns via name mappings.
  Test that distinction; do not globally disable Hive parsing or try to decode MD5.
  Include UTF-8 escaping, NULLs, long names, compaction and mixed old/new files.
- **Row groups:** compaction/flush now preserves table row-group options. Tests and
  split planning must use observed footer/metadata counts, not assume one group.
- **Statistics:** widened native PostgreSQL timestamp bounds, refined import bounds
  and TIMETZ ordering are not proof of safe typed pruning in either connector or
  this library. Keep unknown/exactness semantics and independent expected results.

## R07 ownership after the review

- Trino `12377fe`: limited positive SPI scans, but still missing full opt-in SQL/
  worker sessions, bounded production planning and distributed/recovery evidence.
  The legacy timezone pruning finding becomes an additional prioritized task.
- Doris `a700166`: candidate dependency/Java17 and real matched-peer **1.0** scans
  pass; 1.1 remains explicitly refused. Distributed inline payloads, historical/
  tagged deletes, Puffin, CDC, statement-wide ownership/budgets and recovery are
  still connector implementation work.
- Catalog admission with the flag disabled remains a separate review item; native
  default-version/read-only fixes are not evidence that our metadata handle lookup
  enforces that admission contract.

**Proceed on fixed artifacts, with the new Trino safety task first.** Consider an
isolated new-source/signed-artifact qualification only as separately approved work,
not as a substitute for the missing connector implementation or legacy fix.
