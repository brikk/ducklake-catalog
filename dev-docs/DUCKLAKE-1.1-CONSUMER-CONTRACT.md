# 1.1 consumer contract — implemented read companion and remaining specification

The [conservative legacy read tier](../docs/READING-1.1.md) and additive
[rich read-metadata companion](../docs/READ-METADATA.md) are implemented for opt-in
direct Java Quack. The legacy adapter still omits bounds and rejects unqualified
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

## Required semantics (metadata and hierarchy exposed; optimization still gated)

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
