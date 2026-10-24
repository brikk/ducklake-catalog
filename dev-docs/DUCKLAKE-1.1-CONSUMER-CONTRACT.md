# 1.1 consumer contract — implemented read companion and remaining specification

The [conservative legacy read tier](../docs/READING-1.1.md) and additive
[rich read-metadata companion](../docs/READ-METADATA.md) are implemented for opt-in
the three [qualified DuckDB-backed routes](../docs/READ-BACKENDS.md). The legacy adapter still omits bounds and rejects unqualified
cases; the companion exposes raw metadata without qualifying typed pruning,
or exact aggregates. The explicit [R05 hierarchy reader](../docs/SCHEMA-HIERARCHY.md)
adds bounded parent-aware names/paths without unlocking legacy flat APIs. None
enables 1.1 writes.

## Compatibility policy — best effort, coordinated changes allowed

**Owner clarification, October 21:** the known consumers are our own
`trino-ducklake` and `doris-ducklake` plugins; Doris has no release yet. No external
catalog consumers are known, but this is not proof that published artifacts have
none. Compatibility is a preference, **not an absolute design constraint**.

Preserve existing constructors/accessors, record components, Kotlin descriptors
and JSON where practical. Companion/envelope types and an opt-in extension
interface remain useful options, not mandatory architecture. If changing an
existing record, interface or fragment contract is materially clearer, an intentional
break may be made with documented impact, a version/cutover decision and coordinated
changes and verification in both owned plugins. Do not accumulate elaborate
compatibility machinery solely to preserve an unclaimed legacy contract.
Adding 1.1 fields directly to existing models is a valid option when updating the
owned plugins is simpler than maintaining parallel legacy/new APIs.

This is permission to consider coordinated changes, not an instruction to break
APIs now or silently assume that both plugins have been updated. Connector agents
own their implementations. Kotlin defaults do not preserve Java constructors;
secondary constructors alone do not preserve record components, `copy` descriptors
or old wire readers. Existing compatibility tests are useful detectors: an intentional
break must explicitly revise the promised contract and its tests, not make a failing
frozen consumer appear compatible by recompiling it against the changed library.

Correct NULL/byte/identity/exactness semantics, supported JVM runtime floors and
safe mixed worker/coordinator behavior remain requirements. Unsupported implementations
must fail explicitly, not synthesize capabilities or empty "supported" metadata.

## R03 opt-in temporal handoff — 0.13.0-SNAPSHOT

Final-1.1 ordinary raw reads and raw CDC now project any root containing temporal
leaves to validated native VARCHAR text, including whole nested roots. This is an
intentional development-tier value-contract change from driver DATE/timestamp or
container objects, not a DTO/constructor/fragment-JSON change. Non-temporal roots,
legacy-format raw reads, the default-off flag and all write guards are unchanged.

Prefer decoded APIs: finite values retain `LocalTime`, `OffsetTime`, `LocalDate`,
`LocalDateTime`, UTC `OffsetDateTime` and independent `DucklakeInterval` components.
VARIANT, TIME 24:00 and temporal infinities fail explicitly through both raw and
decoded payload reads. Identity-only CDC/counts can still read their metadata.
Timezone-dependent promotions remain rejected without a production zone contract.

Both owned plugin agents must adopt decoded APIs or handle the documented text
before rollout, then qualify nested BLOB/JSON/NULL semantics, nanoseconds, offsets,
CDC identity and their file scans. **Plugin updates/verification have not been
performed here**; do not infer coordinated rollout from catalog tests. Native
interval flush remains a separate failing precision/component gate, not proof of
lossless Parquet persistence. Full public scope: [Reading 1.1](../docs/READING-1.1.md).

## R04 implemented companion and consumer obligations

`JdbcDucklakeCatalog.readMetadata()` returns `DucklakeReadMetadata`, with explicit
methods for observed capabilities, active schema parent IDs, active data/delete
row-group metadata, raw per-file bounds/exactness and unversioned native table
bounds/exactness. Every operation requires the owning catalog's opt-in read session.
Existing interfaces/records/constructors/fragment JSON and frozen consumer tests
remain unchanged. This additive interface does not require a coordinated old-model
break; consumers must explicitly adopt it rather than silently infer capabilities.

The new records preserve independent unknown/false/true and unknown/zero counts,
with JSON fixtures. They do not label old-type bound strings as values of the
current column type. Table stats have no historical snapshot parameter because
the native table is unversioned. Capabilities distinguish physical metadata presence
from qualification: nested resolution, pruning, exact aggregates and writes are
still false. Peer provenance is observed in-session, not historical file provenance.

Both owned plugin agents must qualify their use of these distinctions and scans;
no plugin changes/verification are claimed. R05 supplies explicit hierarchy/path interpretation;
R06 owns other routes/backends. The separate strict R04 migration gate fails on the
shipped peer, so exposure of raw bounds is not a migration/optimization endorsement.

## R05 implemented hierarchy/path handoff

`JdbcDucklakeCatalog.readHierarchy()` returns a separate additive interface, leaving
the R04 interface and old DTO/fragment contracts intact. Qualification uses stable
parent IDs with separate literal name components; ASCII-only folding does not merge
Unicode or confuse literal dots/quotes with separators. Current/historical lookup,
retired owner resolution, cycles/missing parents/ambiguity and traversal budgets
are explicit. Legacy flat methods remain guarded even after successful hierarchy
reads; existing nested inline/file-descriptor methods are not silently enabled.

Schema storage paths remain catalog-root-relative, not relative to the logical
parent schema. Retained file ownership uses the original file begin snapshot's
table/schema identity even after drop or snapshot expiry; missing historic owner
metadata fails rather than disappearing. Scheduled paths keep their root namespace;
the complete path-reference known set includes future/unknown ages and has an exact
composite cursor separate from age-gated deletion batches. Do not substitute an
eligible batch for all referenced paths or acknowledge deletion using a path cursor.
Paths are not canonicalized or treated as filesystem deletion permission. Caller
total page/path budgets and retention/lease coordination remain mandatory.

The existing `qualifiedNestedSchemaResolution` field becomes true for these explicit
APIs after physical layout validation; pruning/exact aggregates/writes remain false.
No existing record shape or constructor changes. New qualified record JSON fixtures
preserve literal components, raw path spelling, nullable directories and ended
ownership. Both plugin agents must explicitly adopt/verify this contract; their
implementations, physical scans and cleanup effects remain outside this increment.

## R06 partial backend handoff

The same guarded reader, raw metadata and hierarchy interfaces now cover direct
Java Quack, database-read-only local DuckDB files and native Quack's metadata-only
attachment. Existing records/constructors/JSON and 1.0 default writer initialization
are unchanged. `accessRoute` is observed from the configured qualified route rather
than always claiming Java Quack; engine/core provenance refers to the executing
metadata SQL engine. Driver metadata is advertised, not an artifact attestation.

Local READ_ONLY cannot coexist with a differently configured local RW file instance;
coordinate close/publish/reopen. Quack's tested publication boundary is an owned
remote transaction, not a physical-file lease. Native reader initialization never
bootstraps DuckLake or selects/migrates its format. Configured data paths do not
override stored reader path namespaces.

The first R06 increment left PostgreSQL/MySQL opt-in rejected. The next PostgreSQL
subset below opens only its qualified scope; R06 is **not complete**. Do not fill unavailable provenance
with fabricated DuckDB source IDs or false unsigned-extension facts. Any required
intentional model change follows the existing version/coordination policy. Plugin
agents own route selection, dependencies, publication/worker handling and scans;
no plugin rollout is certified by this library increment.

### R06 PostgreSQL subset and portable provenance profile

The next increment opens only the documented [PostgreSQL subset](../docs/READ-POSTGRES-1.1.md):
unchanged nested shape, scalar initial defaults, root renames and exact root
Boolean/integer widening, with strict source-domain validation before conversion.
Other nested/schema/type changes fail explicitly; raw results remain storage-shaped
and validated, never DuckDB SQL cast into apparent correctness. Standard JSON text,
UTF-8, domains, physical precision and value budgets are guarded. Existing DuckDB
routes and legacy raw/writer contracts remain unchanged.

`readBackendProfile()` adds a separate nullable observation record instead of changing
the old DuckDB capability record's primitive component/constructor descriptors.
PG server version/driver facts are real; DuckDB-only fields are explicit NULL.
Known library API availability is separate from database facts. PostgreSQL rich
metadata/hierarchy were initially unavailable; the follow-up below qualifies their
data operations without porting the DuckDB-only capability record. MySQL remains closed. The subset requires strict read-only,
repeatable-read ownership and a rollback-local interval display setting, never a
timezone-dependent promotion or arbitrary default expression.

This is not a plugin rollout, physical scan or cleanup authorization. Both plugin
agents must adopt the declared subset/profile and raw/decoded semantics explicitly;
no owned plugin changes are claimed by this library increment.

### R06 PostgreSQL metadata/hierarchy follow-up

The PostgreSQL route now qualifies all rich-metadata **data operations** and the
bounded hierarchy/path companion under the same nullable/raw-bound/ownership
contract as the DuckDB routes. `readBackendProfile()` reports both availability
flags true only after physical-layout validation; its unavailable DuckDB source,
unsigned and extension facts remain NULL. The old `getReadMetadataCapabilities()`
record stays DuckDB-only and rejects PostgreSQL explicitly—no record/constructor/
wire change or fabricated primitive false flag. Consumers must use the portable
profile on PostgreSQL, not call the old provenance accessor indiscriminately.

Retained ownership uses original file origins, not current names or surviving
snapshot rows. Complete scheduled-reference cursors compare UTF-8 bytes, preserving
case/Unicode variants even under nondeterministic PostgreSQL collations. All SQL
pages and path transfers retain their budgets. This does not open ordinary nested
payload APIs, broaden PostgreSQL conversions, enable optimization, grant cleanup
permission or change any writer/migration guard. MySQL and R07 are still open.
Pristine full-file native goldens and direct-JDBC mirrors remain mandatory.

### R06 PostgreSQL exact root numeric follow-up

The PostgreSQL root payload subset now additionally qualifies native-allowed
integer-to-DECIMAL and exact DECIMAL precision/scale widening, plus FLOAT-to-DOUBLE.
Source-domain checks precede conversion. DECIMAL results use target-scale
`BigDecimal` in raw/decoded reads and CDC; FLOAT widens its stored binary value,
not its short decimal text. Missing-field initial defaults retain native literal
semantics, while stored NULL stays NULL. Boolean-to-DECIMAL is native-rejected and
reader-rejected; approximate integer/decimal-to-float, narrowing, temporal and nested
evolution are not opened by this increment.

The descriptive profile label advances from `postgres-same-shape-integer-widening`
to `postgres-same-shape-exact-numeric-widening`. No record components, constructors
or JSON fields change. This opt-in development-tier label is not writer/worker
capability negotiation; both owned connector agents must adopt and qualify the
scope explicitly. No plugin implementation or rollout is claimed.

### R06 PostgreSQL stable-ID evolution and MySQL native blocker

The guarded PostgreSQL payload tier now includes nested stable-ID evolution through
STRUCT/LIST/MAP, scalar initial defaults, native-allowed numeric and unzoned temporal
conversions, with source validation before projection and bounded output expansion.
Same-named replacement IDs do not inherit old data. NULL parents remain NULL.
Raw changed containers and temporals are canonical, bounded target-storage text;
unchanged roots keep their native shape. Raw/decoded history and CDC keep original
row/snapshot identity. Quoted CAST-looking strings are literal, never evaluated.

Scalar MAP keys include binary/floating/temporal/interval values with native
duplicate semantics. Composite keys remain rejected because Java collection
equality is not the native contract. Shared unsupported values/defaults, implicit
zones, narrowing and parented SQL-schema legacy guards stay closed. No source
domain or output budget can be bypassed by requesting raw results.

The descriptive profile label is now `postgres-stable-id-evolution`; existing
records, constructors and JSON fields are unchanged. This is not writer/worker
capability negotiation. Connector agents must explicitly adopt/qualify raw/decoded
semantics and physical scans; no plugin implementation or rollout is claimed.

MySQL remains rejected: the maintained native build fails genuine 1.0→1.1 migration
and fresh inline STRUCT storage. The strict native task has **2 failures, 0 skips**,
separate from maintained green verification. A library-side version retag, SQL repair
or fabricated type/capability cannot replace those native gates. R06 needs an
approved pinned native MySQL fix/build and subsequent complete backend qualification.
See [reproducers and decision boundary](../docs/READ-MYSQL-1.1-BLOCKERS.md).

### R06 owner-approved MySQL existing-catalog read scope

The owner explicitly chose to qualify direct-JDBC reads of already-initialized
MySQL 1.1 catalogs separately from native upgrades/writes. The initial proposed
1.0-only read ceiling was not adopted. Legacy 0.4/1.0 remains supported; 1.1 now
requires explicit opt-in and an owned, server-validated read-only REPEATABLE READ
session. All 1.1 writes, creation and automatic migration remain disabled.

The MySQL tier qualifies unchanged lossless scalar inline layouts, file metadata,
raw rich metadata, bounded hierarchy/ownership and exact scheduled-reference
cursors. TINYINT booleans/UUID TEXT/utf8mb4/InnoDB are validated, UTC is enforced and
observed on the server, and FLOAT is transported through server-side DOUBLE before
text-protocol display can lose bits. Missing/evolved schemas and lossy/unproven
inline types reject, never silently become NULL or approximate values. Insert CDC
uses native identities; a clearly labeled owned row-end fixture tests deletion
materialization without claiming a passing native DELETE writer.

`readBackendProfile()` adds `mysql-jdbc` / `mysql-existing-scalar-reads` observations
and actual server/driver facts, with NULL DuckDB-only provenance. Metadata/hierarchy
availability is not native scanner, producer, optimizer or worker-write negotiation.
The old capability record still rejects SQL-server routes; record/constructor/JSON
field shapes remain unchanged. Existing test-fixture no-arg construction is retained.

Strict native MySQL fixture gates (6 failures) and pristine native gates (4 failures)
remain separate, visible and deferred with zero skips. Their failures do not imply
that correctly typed direct-JDBC reads are impossible, nor does read qualification
waive them. They gate future native creation/migration/mutation/scanner support and
the excluded inline types. See [read contract](../docs/READ-MYSQL-1.1.md) and
[unchanged native failures](../docs/READ-MYSQL-1.1-BLOCKERS.md). Neither connector
implementation nor coordinated rollout is performed here; R07 and writes stay open.

## Required semantics (metadata and hierarchy exposed; optimization still gated)

### R07 acceptance hardening and handoff

**Additive controlled session API:**
`JdbcDucklakeCatalog.readSession(DucklakeReadSessionControl(timeoutSeconds), Supplier)`
publishes the actual executing JDBC statement for cancellation and sets its remaining
query timeout. Old Supplier-only callers and wire/record shapes remain unchanged.
Real blocked PostgreSQL metadata cancel/timeout/recovery is targeted-tested; other
drivers, setup/prepare/network interruption and physical leases are not certified.
[Exact API and qualification limits](../docs/READ-SESSION-CONTROL.md).

**October 24 consumer-requested additive API:**
`JdbcDucklakeCatalog.getMissingFileColumnDefaultDecoded(tableId, columnId, snapshotId)`
resolves fresh stable-ID target metadata and validates/decodes an initial literal
default for a physically absent file field. Four-route qualification excludes MySQL
evolution; stored NULLs and parent NULLs must never be overwritten. Whole-container
non-NULL defaults remain unqualified. Final-1.1 column string transfers are now
SQL-bounded and reject oversize values. This is not an artifact/deployment approval
or connector scan qualification. [Exact contract](../docs/FILE-INITIAL-DEFAULTS-1.1.md).

The [R07 report](FINAL-1.1-R07-READ-ACCEPTANCE.md) completes the in-repository
guarded-library matrix, not downstream rollout. Public record components, Java
constructors and fragment JSON remain unchanged. The opt-in 0.13.0-SNAPSHOT count
contract intentionally narrows: historical file-backed `getLiveRowCount` and unknown
active delete counts reject rather than guess an exact value; negative net counts
are corruption rather than zero. Physical files may include later rows or deletes,
so historical totals are not guaranteed lower bounds. Legacy 0.4/1.0 behavior stays
unchanged. Consumers must scan or omit the estimate, not promote gross counts.

Merge lineage and tagged Parquet deletes are verified through native physical
oracles. A single Puffin vector may be a bare length/magic/CRC blob, while a Puffin
container may hold several cumulative blobs labeled `ducklake-snapshot-id`.
Identical current/previous paths cannot blindly self-cancel such a container's
in-window changes. Recognizing the format is not proof of a working connector
decoder. Geometry metadata is not geometry payload/scan qualification.

Both owned agents still need scan/CDC/cancellation/lease/worker evidence and a
coordinated rollout decision; none is inferred from library tests. Default-on
reads, artifact upgrades, 1.1 writes, migration and physical cleanup are not enabled.
Strict R02/R03/R04 and deferred M01 failures remain visible; full native acceptance
is not marked green. R07 as a whole remains open at this external approval boundary.

| Metadata | Representation | Consumer obligation |
|---|---|---|
| Schema parentage | Schema ID + nullable parent ID, plus explicit layout capability | In a parent-aware layout, NULL denotes root; absent layout capability means **unknown**, not root or ID 0. Resolve qualification by IDs, reject missing parents/cycles and bound depth. Physical paths retain independent relative/absolute semantics. |
| Bound exactness | Independent nullable min/max exactness flags alongside values | NULL = unknown, false = inexact, true = exact. Never infer true from a non-NULL bound. Missing value stays unknown regardless of flag. |
| Row groups | Nullable nonnegative counts for data and delete files separately | NULL = unknown, never substitute 0. Use for planning only; don't fabricate splits or skip Parquet footer verification. |
| Provenance/capabilities | Exact engine/extension/layout provenance with declared capabilities | `1.1-dev1` alone is insufficient; missing required columns is unsupported, not a guessed legacy layout. |

Exactness is **not** a blanket rule that all inexact bounds are unsafe. Some bounds
are conservative with the right polarity; qualify their upstream semantics before
using them for pruning. Until then, reject the operation or omit the unqualified
bound. Never use an approximate endpoint as an exact MIN/MAX aggregate. Aggregate
counts retain current unknown-count propagation and NaN handling is qualified
independently; do not remove current conservative guards based only on new flags.

## Legacy reads alongside the companion

The unchanged old DTOs cannot describe every 1.1 fact. A safe adapter may omit
unsupported advisory row-group counts, but must omit unsafe/unknown bounds and
reject ambiguous nested-schema operations rather than flatten hierarchy or lose
qualification. Missing exactness in **qualified 1.0** retains current semantics;
missing 1.1 metadata does not inherit those semantics automatically.

Legacy change-feed models remain positional for deletes. Embedded absolute row IDs
win over optional contiguous starts; absence stays NULL, not 0. Snapshot-tagged
delete files require per-position filtering even when `partial_max` is NULL.

## Wire and rollout gates

- Prefer existing fragment JSON; intentional changes require a documented writer
  contract and coordinated producer/consumer cutover. A reader metadata envelope
  is not permission to accept 1.1 writes through old fragment types.
- New metadata must distinguish absent/NULL from false and zero and have explicit
  JSON fixtures. Where mixed workers/coordinators are supported, negotiate their
  capability; otherwise use a coordinated cutover with old work drained/rejected.
  Unknown fields being tolerated by a decoder is **not** capability negotiation.
- Test old producer → new consumer and new producer → old consumer where claimed.
  Unsupported mixed-version writes must fail before side effects. Coordinate
  catalog migration externally; never migrate by opening a reader.
- Leave Trino/Doris implementation to their agents. Hand off precise nullability,
  hierarchy, pruning and writer-fragment obligations before enabling support.

## Prepared regression net

`TestDucklake11ConsumerCompatibility` loads a frozen Java 17 consumer compiled
against public catalog **0.11.0**, then links it against the working library. It
exercises schema/data-file/file-stat/table-stat constructors and accessors,
including the old short file constructor and nullable row-ID start. A separate
record-component lock prevents accidental expansion. Old statistics JSON preserves
unknown versus false/zero. Existing `TestJacksonWireFormat` goldens cover the
other DTOs. The fixture's source and Maven JAR SHA-256 are recorded beside it.

This does not claim full ABI scanning or connector rollout. R04 companion models
are additive; richer hierarchy/optimization interpretation and all write contracts
remain gated independently of the limited legacy reader.
