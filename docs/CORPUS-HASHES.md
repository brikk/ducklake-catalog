# Typed corpus result hashes

The replay framework hashes native SQLLogicTest cell text, using UTF-8 and one LF
after each cell. Sorting happens before flattening/hashing as requested by the
query's NOSORT, ROWSORT or VALUESORT mode.

## Preserve SQL type provenance

Use the additive SQL-type-aware overload for native results:

```kotlin
val types = (1..rs.metaData.columnCount).map { rs.metaData.getColumnTypeName(it) }
val rows = GoldenComparator.readRows(rs)
val hash = GoldenComparator.resultHash(query, rows, types)
```

The rows are the renderer's existing nullable strings, not raw JDBC objects.
Scalar BOOLEAN/BOOL cells become native `1`/`0` **before** sorting. Metadata stays
attached to original column positions during this normalization; VALUESORT cannot
turn a neighbouring VARCHAR `true` into a Boolean. Nested Boolean text remains
native nested text. NULL, empty-string and NUL encodings retain their existing rules.

These are ordinary text hashes, not type-tagged hashes: Boolean TRUE and integer1
have the same native cell text/hash. VARCHAR `true` has a different hash; VARCHAR
`1` legitimately has the same hash as Boolean TRUE. Query type letters do not
establish Boolean provenance. Invalid metadata width or Boolean payload fails
explicitly; no fabricated value or successful empty result is substituted.

## Compatibility and execution

The original `resultHash(query, rows)` descriptor remains available with its raw-
text semantics. It cannot safely infer whether a string such as `true` came from
BOOLEAN or VARCHAR. Direct callers with metadata should use the typed overload.
`ReplayDriver` and SQL-type-aware `compare` already use it for explicit hash goldens
and repeated labels, retaining actual result width for labelled placeholders.

Raw/live row rendering and structured mirror comparisons do not change. The hash
normalization copies rows; it does not mutate the oracle/adapter values. It issues
no SQL and re-executes no query. Wrong hash goldens remain failed records before
oracle-pass/mirror coverage, and later valid records continue normally.

This is replay verification behavior, not a DuckLake data/type admission change.
Adopt exact replay artifacts and rerun relevant consumer conformance tests; no
connector implementation, publication or enablement is implied. See
[execution evidence](../dev-docs/archive/FIX-N19-BOOLEAN-HASHES-2026-11-01.md).
