# 1.1 consumer contract — specification, not implementation

## Compatibility decision

Preserve existing `DucklakeSchema`, `DucklakeDataFile`, `DucklakeFileColumnStats`
and `DucklakeColumnStats` record components, Java constructor/accessor descriptors
and airlift JSON shapes. **Do not append candidate fields to these records.**
Kotlin defaults don't restore Java constructors; secondary constructors alone
don't preserve record components, Kotlin `copy` descriptors or old wire readers.

Expose richer read metadata through **new companion/envelope types and an opt-in
extension interface**, without adding abstract methods to `DucklakeCatalog`.
Names/signatures will be finalized with RC qualification, but this separation is
the intended compatibility seam. Unsupported implementations fail explicitly;
they must not synthesize capability flags or return empty "supported" metadata.

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

- Keep existing fragment JSON unchanged until an explicitly versioned richer
  writer contract is implemented. A reader metadata envelope is not permission
  to accept 1.1 writes through old fragment types.
- New envelopes must distinguish absent/NULL from false and zero, use independent
  versioned JSON fixtures and explicitly negotiate worker/coordinator capability.
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

This does not claim full ABI scanning or finalize new envelope code. Actual 1.1
model/API/schema/write implementation remains deferred.
