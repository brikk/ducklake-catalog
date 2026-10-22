# First final-1.1 read tier — October 20

**R06 PostgreSQL follow-up:** a direct-JDBC subset now reads unchanged nested
values, scalar defaults and exact root integer widening with source-domain/value
validation and portable nullable provenance. Rich metadata/hierarchy data operations
are now qualified too; broader PostgreSQL payload evolution remains rejected and MySQL is still closed. See the
[PostgreSQL contract](../docs/READ-POSTGRES-1.1.md); R06 remains open.

**R06 partial follow-up:** the guarded tier now covers read-only local DuckDB and
native Quack in addition to direct Java Quack. Native read initialization attaches
only metadata, never DuckLake bootstrap/migration. PostgreSQL was opened by the later
increments above; MySQL stays closed. R06 is not complete. See [route contracts](../docs/READ-BACKENDS.md).

**R05 follow-up:** an explicit hierarchy companion now qualifies bounded stable-ID
schema/table lookup, literal components, historical names, root-relative schema
storage paths and retained data/delete ownership. Legacy flat guards remain; no
physical scan, cleanup side effect or write is enabled. See the
[current hierarchy contract](../docs/SCHEMA-HIERARCHY.md).

**R04 follow-up:** the additive rich read companion exposes parent IDs, independent
nullable exactness, nullable data/delete row-group counts and observed layout/peer
provenance. Legacy statistics/pruning remain conservative. The newer native
retyped-bounds migration gate is **not green**; raw metadata exposure does not
qualify optimization or repair migration. See [current contract](../docs/READ-METADATA.md).

**R03 follow-up:** finite temporal/interval/NULL-type reads now use lossless native
text before decoding. Raw temporal roots intentionally return validated text;
VARIANT, temporal infinities and TIME 24:00 remain rejected. A separate strict
native interval flush gate fails on precision/component loss. See the current
[support record](TODO-DUCKLAKE-1.1-SUPPORT.md) and [public scope](../docs/READING-1.1.md);
the original type restrictions below are historical, not blanket current limits.

**R02 follow-up:** guarded stable-ID inline evolution and by-name legacy STRUCT
reads are implemented; the newer strict native gate is **not green** (two native
semantic/read failures plus a native SIGSEGV). See the current support TODO below
for the exact qualified scope and unresolved shipping-native acceptance. Original
type-evolution/physical-order rejections below are historical, not the current scope.

**Read-first expansion:** R01 now projects qualified scalar initial defaults for
missing top-level fields, preserving stored NULLs and CDC identity. See the
[1.1 support TODO and current verification](TODO-DUCKLAKE-1.1-SUPPORT.md) and
[upstream test adoption](UPSTREAM-1.1-READ-TESTS.md). Initial-default rejections
described in the original results below predate this verified increment; other
type/evolution/hierarchy/backend restrictions remain in force.

**Shipping follow-up:** all 33 direct-Java tests were subsequently requalified on
official 2.0.0, core `d54cbbe3df`, signed DuckLake `417a9117f5` and Quack `f96420ed46`.
See [shipping upgrade results](UPGRADE-DUCKDB-2.0.0.md); staged results below remain
historical evidence, not the maintained target.

Implements the conservative legacy adapter allowed by the
[consumer contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md), **not** richer metadata
envelopes or write support. User setup/limits are in [Reading 1.1](../docs/READING-1.1.md).

## Scope

`enableFinal11Reads=true` is explicit and defaults off. It is accepted only for
direct Java Quack; every read requires an owned `readSession`. The first read in
that transaction validates the actual parent-schema column and rejects any
persisted nested-schema history. A native change cannot race a successful
autocommit preflight into returning flattened hierarchy: unowned reads are refused.
The validation marker is removed on session exit, including failure.

The read-only inline descriptor recognizes final `1.1`. The DDL descriptor and
`DucklakeSpecVersions.WRITABLE` remain unchanged. Unknown versions, missing shape,
unsupported projections and failed queries are not guessed into empty data.
Existing physical inline tables must expose the actual first three BIGINT metadata
columns. The namespace-scoped check transfers at most three rows with capped name/
type projections, so later user columns cannot impersonate an unmigrated prefix.

Existing DTOs/Java descriptors/wire shapes are preserved. File-stat queries project
NULL endpoints for this tier before aggregation; range pruning retains all active
files. Flags, row groups and schema parentage are not silently invented in legacy
models. 1.0 behavior/SQL remain unchanged with the default configuration.

## Reproduction and coverage

The first opt-in native test failed with `Unknown inlined metadata layout ... 1.1`
before implementing the reader. The new negative missing-system-column case also
reproduced an empty read outside a session; the opt-in tier now refuses unowned
reads and propagates physical query failures in owned sessions. This contains that
hazard for the new reader; it does not close the whole legacy C09 finding.

The opt-in suite uses **the same exact signed alpha49096 target** as
[direct-v3 qualification](QUACK-2.0-QUALIFICATION.md): core `2ec7e8401c`, DuckLake
`417a9117f5`, Quack `e7a9be3f8c`, published Java driver 0.8.0 with its release digest
and negotiated protocol 3 asserted. No DuckDB JDBC is on the test JVM classpath.

Added real native final-1.1 coverage:

- Scalar and STRUCT/LIST/MAP inline data, NULLs, BLOB escape bytes, misleading legacy
  user-column names and independent expected row/snapshot IDs.
- Exact decimal/unsigned values, UUID, finite DATE/timestamps, pre-epoch nanoseconds,
  UTC timestamp instants and NaN preservation.
- Multiple inline schemas, ADD/rename/unrelated DDL, current/history rows and CDC.
- Native flush plus later inline inserts, partition/file/delete descriptors and
  count estimates, checked against native current/historical rows.
- Explicit native **1.0→1.1 migration** of a disposable same-CLI seed with old Parquet
  and two inline schema versions. The JVM reads five preserved row IDs without
  moving the snapshot head or performing the migration itself. This is not proof
  for every older engine/backend migration.
- TRUE/FALSE/NULL exactness inputs all suppress legacy bounds; pruning retains the
  possible match and does not change file registrations.
- Pinned native-write/flush visibility, validation refresh at the next session, and
  rejection of active/retired nested schemas and malformed final layouts.
- Non-NULL initial defaults, type evolution, empty ordinary projections and
  unqualified payload types fail explicitly; genuine stored NULLs stay NULL.
- Default-off compatibility, route restriction, unchanged DDL descriptor and final
  1.1 write rejection, including retained real output after rejected registration.

## Verification

```sh
mise exec -- ./gradlew check :ducklake-catalog:quackPreviewTest --rerun-tasks \
  -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/absolute/path/to/alpha49096/duckdb \
  --no-configuration-cache --no-daemon
```

Final forced checks passed **598 maintained JUnit tests** (507 catalog, 51 SLT,
40 replay) plus **31 opt-in preview tests**, zero failures/errors/skipped JUnit
tests. Full corpus passed **9,697 records**, zero failed; existing 31 file/12 record
skips remain. Detekt and Java 17 checkAbi passed (7,728 classes, 17 artifacts),
including frozen released-Java-consumer, record-component and JSON controls.
Documentation checks verified 193 local links/anchors across 37 Markdown files.

All fixtures own their processes and temporary catalogs/extensions; they were
removed after verification. Nothing writes production metadata or changes
maintained/preview/corpus pins. Other catalog backends and the newer branch-tip
Quack build remain outside this opt-in qualification.

## Post-push qualification follow-up

[CI 39517353623](https://github.com/brikk/ducklake-catalog/actions/runs/39517353623)
exposed a test assumption: MySQL's fractional native bootstrap time can sort after
later whole-second commits, so latest timestamp does not necessarily mean highest
snapshot ID. The five-route assertion now derives the result from the pinned
visible snapshots at the **winner's actual timestamp**. A leaking query must select
the winner at that cutoff; a future-dated bootstrap cannot mask it. A deterministic
local regression covers non-monotonic times, timestamp ties and pinned visibility.
Timestamp lookup runtime code did not need changing.

The eve-of-release source audit also motivated two narrower reader hardenings:

- A real reordered-STRUCT fixture passed through the old positional reader instead
  of rejecting the physical/logical mismatch. The reader now compares the selected
  physical payload type with a native-normalized, whitelisted source type. It
  transfers only a bounded existence result; it never casts the actual payload.
  Decimal grammar/precision and type-SQL size are bounded before building that query.
- Native metadata confirms `initial_default` holds plain literal text. A string
  `NULL` is a genuine non-NULL VARCHAR default, not the absence of a default. The
  adapter now rejects every non-NULL initial default for an absent source field,
  while preserving stored NULLs. This regression failed before the correction.

Final follow-up forced verification passed **600 maintained JUnit tests** (509
catalog, 51 SLT, 40 replay) and **33 staged-preview tests**, zero failures/errors/
skipped JUnit tests; 9,697 corpus records, existing corpus skips unchanged. Detekt,
Java 17 checkAbi and frozen ABI/wire controls pass. Existing write guards, old DTOs
and production/preview/corpus pins are unchanged. Owned processes and temporary
catalogs/extensions were removed after verification.
