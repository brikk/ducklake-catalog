# Final-1.1 read backend qualification

**R06's owner-approved read scope is qualified in 0.13.0-SNAPSHOT.** The guarded
tier covers three DuckDB-backed routes, [PostgreSQL evolution](READ-POSTGRES-1.1.md)
and a narrower [existing-MySQL read-only subset](READ-MYSQL-1.1.md). Native MySQL
creation/mutation/migration/scanner failures remain [deferred](READ-MYSQL-1.1-BLOCKERS.md),
not fixed or waived. R07 acceptance/rollout remains separate.
The maintained 0.4/1.0 lane and all 1.1 write/migration guards are unchanged.
Qualification here uses Linux and the maintained locked artifacts, not a claim
covering every platform or driver build.

| URL | Qualified read mode | Boundaries |
|---|---|---|
| `jdbc:quack://host:port` | Direct Java Quack, metadata SQL on the remote DuckDB | Java driver 0.11.1; no DuckDB JDBC required in the reading JVM |
| `jdbc:duckdb:/existing/metadata.db` | Existing local DuckDB file, **database-level READ_ONLY** | File must exist; no memory/protocol URL or catalog creation; coordinate native writers by closing readers |
| `jdbc:duckdb:quack://host:port?metadata_catalog=name` | Native DuckDB/Quack client, **metadata-only READ_ONLY attachment** | Library never invokes DuckLake bootstrap/version selection/migration on reader open |
| `jdbc:postgresql://…` | **Guarded stable-ID payload evolution; rich metadata/hierarchy** | STRUCT/LIST/MAP evolution, scalar defaults, native-allowed numeric and unzoned temporal promotions; explicit canonical-value/type boundaries; provenance uses `readBackendProfile()` |
| `jdbc:mysql://…` | **Existing final-1.1 read-only subset** | Lossless native scalar forms, files, rich metadata/hierarchy; server-validated UTC/read-only REPEATABLE READ/InnoDB; no schema evolution, creation, upgrade or 1.1 writes |

All qualified routes require `enableFinal11Reads=true` and an owned
`readSession`. Existing records/constructors/fragment JSON remain unchanged. The
same [read limits](READING-1.1.md), [raw metadata contract](READ-METADATA.md) and
[explicit hierarchy/path companion](SCHEMA-HIERARCHY.md) apply. Legacy nested
payload/descriptor APIs stay guarded; parent-aware names/paths are not a physical
Parquet scan, cleanup lease or connector rollout.

## Opening readers must not initialize metadata

Local readers reject missing files and use JDBC property `duckdb.read_only=true`.
Hikari is also configured read-only so it cannot try to change DuckDB's database
access mode at connection level. An existing empty database stays empty after a
failed metadata read. No metadata schema, layout or format is created/migrated.

DuckDB's local database-level access mode cannot coexist with a differently
configured READ_WRITE instance of the same file. The integration contract asserts
that refusal, then closes the reader, publishes natively and reopens to observe
the new state. Concurrent external file writers remain subject to native file
locking. Use a qualified Quack route for the tested live remote publication/pinned
reader pattern; do not infer local multi-writer or online mixed-mode support.

Native Quack readers attach `quack:host:port` directly as a metadata catalog, not
`ducklake:quack:…`. The read initializer loads only Quack and creates the connection
secret; it has no DuckLake ATTACH, data-path override, format selection or migration
step. A data-path configuration is not required for this read initializer. Existing
legacy native-Quack initialization with the flag off remains pinned to writable
1.0 with automatic migration false; its SQL/contract tests are retained.
This attachment/library mode is not a replacement for server authorization or
read-only credentials. It does not grant any 1.1 mutation permission.

Both Quack routes pin metadata through the same owned transaction used for reads.
Native Quack activates the remote transaction owner with `use_transaction := true`;
rollback releases that owner. Concurrent native DDL/inserts remain invisible inside
the pin, and the next session observes the new head, columns and initial defaults.
No independent RPC BEGIN or implicit retry/COMMIT behavior is introduced.

## Provenance is observed, not inferred

The additive `readBackendProfile()` reports nullable portable observations on all
five routes. PostgreSQL/MySQL use actual server/driver facts and explicit NULL for
DuckDB-only source/extension/unsigned fields. Its availability flags identify the
limited projection tier plus available metadata/hierarchy data operations; see the PostgreSQL contract above. The existing
DuckDB-only capability record below keeps its constructors and shape unchanged.

Capabilities report `accessRoute` as `java-quack`, `local-duckdb` or `native-quack`;
`metadataBackend` remains `duckdb`. Engine version/source are queried where the
metadata SQL executes—on the remote server for both Quack routes, locally for a file.
Loaded extension revisions can correctly be NULL when that metadata engine has not
loaded the writer extension. They are not fabricated from client or historical
writer identities.

`jdbcDriverVersion` is the driver's **advertised JDBC metadata value**, not a Maven
artifact attestation: the pinned DuckDB JDBC 2.0.0.0 JAR reports `1.0` here. Tests
independently verify its published Maven Central SHA-256
`5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`, core
`d54cbbe3df` and signed writer DuckLake `417a9117f5`/Quack `f96420ed46`. Direct Java
Quack now advertises driver `0.11.1`; its no-DuckDB-JDBC qualification stays separate.
The [driver update verification](../dev-docs/archive/UPGRADE-QUACK-JDBC-0.11.1-2026-10-30.md)
does not change the embedded JDBC, corpus, extension or preview engine pins, or
relabel earlier full-suite evidence as having used the new driver.

## Tested scope and deferred native work

The shared three-route contract covers real native fresh/migrated catalogs, scalar
initial defaults, nested STRUCT/LIST/MAP evolution, temporal text/nanoseconds/BCE,
BLOB/JSON/literal NULL preservation, unsigned/decimal values and NULL types,
raw/decoded CDC row/snapshot identity, rich metadata/hierarchy and real file paths.
It also requires non-bootstrap behavior, session ownership, native publication/
refresh boundaries, unsupported temporal-edge rejection, malformed physical prefix/
leaf-type rejection and unknown/dev-format rejection.

Four pristine upstream files additionally require eleven native-Quack in-replay JVM
mirrors. Two original upstream files replay completely natively before independent
local read-only final-state assertions, with the native file owner fully closed.
Those local checks are not simultaneous RW/RO mirrors or alleged JVM Parquet scans.
Original SQL/goldens/digests and maintained corpus exclusions are preserved.

**Owner-approved scope change:** MySQL existing-catalog reads are qualified
independently from native creation, upgrades and writes. The latter need native
fixes and an approved build before they can be reopened, not before the proven
JDBC read subset can be used. The PostgreSQL/MySQL tiers retain their explicit
contracts and shared R02–R04 canonical boundaries. A URL
guard edit or a guessed DuckDB/extension/unsigned flag is not qualification. Any
necessary model change follows the documented version/consumer coordination policy,
not a compatibility veto or silent break. Neither SQL backend is newly certified by
the existing maintained 1.0 tests. R07 rollout also remains separate.

The separate R02–R04 native defects remain parked; this transport work neither fixes
nor waives them. No plugin implementation, 1.1 writer, migration or physical cleanup
is enabled. See [execution evidence](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md).

R07's [guarded library acceptance](../dev-docs/FINAL-1.1-R07-READ-ACCEPTANCE.md) adds
42 cross-route native/JDBC cases and the full maintained verification. Historical
file-backed counts/unknown delete counts now refuse exact materialization; Puffin
containers and tagged data/deletes retain snapshot-filtering obligations. Native
strict failures and unverified downstream rollout remain explicit no-go boundaries,
not default-on or broader support authorization.
