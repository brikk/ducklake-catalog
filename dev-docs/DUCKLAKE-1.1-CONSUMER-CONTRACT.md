# 1.1 consumer contract — specification, not implementation

The richer envelopes below remain a specification. A first
[conservative legacy read tier](../docs/READING-1.1.md) is implemented as an opt-in
direct-Java adapter: it omits bounds and rejects unrepresentable/unqualified cases
rather than adding facts to the old records. It enables no 1.1 writes.

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

## Required semantics of the future envelopes

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

## Legacy reads in a future 1.1 implementation

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

This does not claim full ABI scanning or finalize new envelope code. Richer 1.1
model/envelope and write implementation remains separate from the limited legacy reader.
