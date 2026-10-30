# Compatibility and transports

## Supported lane

- Latest stable catalog release: **0.12.0**; development: **0.13.0-SNAPSHOT**.
- Catalog format: **DuckLake 1.0**; a [limited final-1.1 reader](READING-1.1.md) is
  explicit opt-in on development code. DuckLake 1.1 writes remain disabled.
- Maintained development DuckDB: **2.0.0**; JDBC: **2.0.0.0**. Stable 0.12.0 retains its historical pins.
- Optional direct Java Quack JDBC: **0.11.1**; see the [driver update verification](../dev-docs/archive/UPGRADE-QUACK-JDBC-0.11.1-2026-10-30.md).
- Published libraries target **Java 17**. Building the repository requires JDK 25.
- Applications supply their JDBC drivers and access to the data-file location.

| Metadata route | `DucklakeCatalogConfig.catalogDatabaseUrl` | Notes |
|---|---|---|
| PostgreSQL | `jdbc:postgresql://host/catalog` | Supply the PostgreSQL JDBC driver and separate credentials |
| MySQL | `jdbc:mysql://host/catalog` | Supply the MySQL JDBC driver; transactional-DDL limits still apply |
| Local DuckDB | `jdbc:duckdb:/path/catalog.db` | DuckDB JDBC 2.0.0.0 |
| Native Quack | `jdbc:duckdb:quack://host:9494?metadata_catalog=lake_meta` | DuckDB JDBC and native Quack/DuckLake extensions |
| Direct Java Quack | `jdbc:quack://host:9494` | `dev.brikk.duckdb:quack-jdbc:0.11.1`; optional `/existing-server-catalog` |

Pass Quack tokens in `catalogDatabasePassword`, not shared URLs. The direct Java
route uses ordinary remote JDBC transactions and does not require embedded DuckDB
in the client process. Quack serves metadata, not Parquet data access.

Catalog-owned logs and diagnostic exceptions redact URLs containing user-info or
options, including their diagnostic URL getters. Options are omitted conservatively,
not matched against a password-name allowlist. The configured connection URL is
unchanged; a redacted diagnostic value must not be reused to connect. Driver/pool
logging remains governed by those components' own settings.

Local/direct JDBC routes expect initialized metadata. The native Quack route uses
DuckLake ATTACH and can bootstrap missing metadata. This library does not enable
automatic 1.1 migration. Native Quack bootstrap explicitly selects 1.0; when creating
other read/write lakes with native DuckDB 2.0, pass
`DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE` to ATTACH, because the engine's
default for new lakes is 1.1. Full per-file encryption support is not provided; file
registration into encrypted catalogs is rejected.

When an initialization query finds missing DuckLake metadata tables or an empty
`ducklake_snapshot`, read planning, CREATE and file registration report
`DucklakeCatalogNotInitializedException` with bootstrap guidance. File preflight
also checks for a snapshot before accepting empty fragment lists. No metadata is
initialized by these guards. Missing columns, permission/connection failures and
unsupported catalog versions are not reclassified as initialization failures;
existing read-session write rejection and uncertain-COMMIT classifications remain
unchanged. Native Quack's legacy ATTACH/bootstrap remains a separate boundary.

## Limits and safe operation

- A backend smoke test does not establish every operation/type combination.
  MySQL column/field add, drop, rename and type changes reject with
  `DucklakeInvalidOperationException` before metadata mutation when the table has
  registered inline storage, including empty or previously flushed inline tables.
  MySQL physical inline DDL implicitly commits; supporting these changes requires
  a safe table-preparation/publication protocol and qualified MySQL type rendering.
  Tables without inline registrations retain their metadata-only mutation path.
  An existing-field `addField(..., ignoreExisting=true)` remains a no-op.
- Use snapshot-aware materialization APIs. In 0.13, snapshotless flush/DELETE/MERGE
  forms explicitly reject before mutation while retaining their linking signatures.
  Follow the [materialization-snapshot migration](MATERIALIZATION-SNAPSHOTS.md);
  consumers must capture the read boundary before preparing complete, lineage-preserving output.
- Prefer bounded cleanup pages and exact acknowledgements. Metadata read sessions
  are not ownership leases or protection against physical deletion after planning.
  Enforce traversal budgets, age/reference checks and writer coordination before
  deleting files.
- Do not use the deprecated per-table `listReferencedFilePaths` for orphan detection:
  it mixes table-relative and catalog-root-relative paths without a discriminator.
  See [file-reference namespaces and bounded-maintenance limits](FILE-REFERENCE-NAMESPACES.md).
- Read the [COMMIT outcome contract](COMMIT-OUTCOME-CONTRACT.md) before adopting
  0.13.0-SNAPSHOT. An uncertain commit is not permission to retry or delete output.

## Statistics and pruning

ANALYZE, insert and rewrite statistics use the column types visible at their logical
snapshot, not the physical ordering of historical column rows. Current FLOAT/DOUBLE
precision must not be replaced by a retired type during bound aggregation.
Type lookup uses bounded SQL pages/text and refuses duplicate/malformed active
metadata, type strings above 256 characters or more than 65,536 active identities.

Legacy min/max pruning interprets FLOAT/DOUBLE statistics and predicate bounds at
the declared binary precision. Equivalent decimal spellings compare equally;
adjacent values remain distinct. Non-finite, malformed or binary-overflowing float
bounds remain unknown, and the existing `contains_nan` checks still apply.
Integer/decimal comparisons retain arbitrary-precision decimal keys.

TIME/TIME_NS `24:00:00` has an end-of-day statistics key above the last tick before
midnight. It is not normalized to `00:00:00`; malformed clocks fail open. The shared
bound accumulator and numeric swapped-bound detection use the same typed comparison.
This is a statistics correction, not a new Java value representation for TIME 24:00
or permission to enable min/max pruning in the conservative final-1.1 tier.

## Legacy inline reads

Ordinary legacy `readInlinedData` / `readInlinedDataDecoded` reads apply qualified
scalar **initial** defaults to root column IDs absent from an older physical inline
schema on DuckDB, native/direct-Java Quack and PostgreSQL. Defaults and types come
from catalog metadata at the requested snapshot. Later INSERT defaults and caller
DTO values cannot replace them; stored SQL NULLs stay NULL. This reuses the checked
1.1 literal projection/decoder, without evaluating SQL expressions or changing
present-column/nested evolution behavior. Synthesized raw defaults use its typed
text/byte representations; existing raw payloads keep their JDBC representation.

Non-NULL whole-container defaults, malformed/unknown histories and unsupported
types fail explicitly. MySQL missing-column default projection also rejects; its
qualified schema-evolution/producer work remains separate. Existing-column MySQL
reads remain available.

With final-1.1 opt-in disabled, empty ordinary projections preserve cardinality:
one empty value list per live row, with the same snapshot filtering. Use
`countInlinedRows` when only the count is needed. Opt-in ordinary list reads retain
their existing empty-projection rejection; the decoded page API supports
identity-only pages.

**Legacy CDC keeps its documented NULL-fill contract** for fields absent from a
source schema, and empty payload projections preserve CDC identity. The final-1.1
CDC tier retains its separate qualified initial-default behavior.

## Replay comparison and watchdog integrity

The 0.13 replay driver carries JDBC SQL types and column count into golden
validation, including empty SELECT and hash results. Integers and DECIMAL use
exact numeric equality (equivalent scales/spellings remain accepted); only SQL
FLOAT/DOUBLE use floating tolerance. Boolean aliases require SQL BOOLEAN metadata,
not a VARCHAR value that happens to spell `true` or `false`. Expected and actual
ordinary-query row widths must match the declaration before sorting or hashing.
Upstream label letters are placeholders: the first occurrence records actual width
alongside its hash, and later occurrences must preserve it (including empty results).
Labels still ignore ordinary golden text, as upstream specifies. VALUESORT keeps
each actual cell's original SQL type; ROWSORT compares cell boundaries, not joined
delimiter strings. Original corpus goldens and exclusions are not changed.

JDBC's genuine NOTHING/no-result command is represented separately, not as an empty
SELECT with lost metadata. It accepts an empty expectation (or zero-value hash)
with the existing single-letter command placeholder; nonempty goldens and wider
ordinary declarations fail. No native column type/schema is invented for this
non-tabular JDBC result. Execution remains direct and single-shot: preparing again
to obtain metadata can rebind side-effecting table functions and alter lake state.

`GoldenComparator.compare(query, rows)` keeps its descriptor, with conservative
declared-letter behavior: exact I, tolerant R, literal T, no guessed Boolean type.
Use the new `compare(query, rows, sqlTypes)` overload for typed coercions and empty
result arity. The old row-only entry point cannot infer an empty result's metadata.
SQL NULL and empty-value golden tokens (and upstream hash serialization) remain
golden-format conventions, not an injective encoding of live data. Boolean hash
canonicalization is a separately tracked defect; this change does not claim it fixed.

Engine mirrors compare **multisets of structured nullable rows**, not golden tokens
or delimiter-joined rows. SQL NULL, literal `NULL`, empty text, literal `(empty)`,
embedded separators, column boundaries and duplicate counts remain distinct. Typed
geometry normalization remains the only live-value normalization; mirror equality
has no numeric tolerance. The row-only engine API cannot report an empty result's
column metadata; no stronger engine-side empty-schema guarantee is claimed.

A file watchdog timeout freezes completed outcomes, interrupts its worker and
waits up to one second for termination. Timeout remains a file-skip, but earlier
failures, passes and record-skips remain counted. Later files and later `run` calls
on that runner are BLOCKED, even after a cooperative worker exits: its engine or
metadata backend may already have changed. Caller interruption also cancels and
quarantines the runner, restores the caller's interrupt flag and propagates the
interruption. A crash retains its completed prefix as well.

This is conservative quarantine, not forcibly killable native execution. A worker
ignoring interruption can remain active with its owned oracle/resources until it
returns or the JVM exits. The runner does not close an engine under such a worker;
the caller still owns engine lifecycle and must not reuse/close shared engine or
rewriter state via another runner while it is active. Recoverable continuation
requires genuinely isolated processes/backends, not merely another ReplayDriver.
Public constructor, engine and FileResult descriptors are unchanged; no sibling
source changes are required, and no connector artifact adoption is implied.

### Measured replay coverage and failure stages

These stage corrections are implemented but **not fully qualified**: strict corpus
checks now expose six TIME24:00 conversion failures in the pinned JDBC driver. The
owner chose to retain those failures, without new skips or fabricated values. See
[the pending qualification report](../dev-docs/archive/FIX-HARNESS-STAGES-2026-10-29.md).

`FileResult.queryCoverage` is additive, supplementary runtime evidence. It separates
oracle/golden query passes from actual mirror attempts, passes, failures, explicit
engine query skips and gate skips (not attached, transaction open, cap or rejected
dialect). Pending attempts after a timeout/crash and acceptance-gate failures are
reported separately. `CorpusReport` aggregates those measurements and skip reasons.
Legacy `passed` / `totalPassed` remain record totals, including setup and oracle-only
validation; they do **not** establish engine conformance. Gate skips are measured
mirror skips, not additional legacy record outcomes. A real `ReplayEngineSkip` during
execution remains a legacy record skip and a measured attempted mirror skip.

Conformance suites must check failures/crashes and call
`report.requireMirrorPasses(positiveMinimum)` with a meaningful aggregate threshold.
Identity control remains explicitly labelled and intentionally has no engine passes.
Existing constructors, data components, copy/equality and engine method descriptors
are retained. Manually created or data-copied FileResults have null/unmeasured
coverage rather than fabricated success; aggregate reports expose that count. Copies
therefore must not be used to carry measured conformance evidence. Catalog wire DTOs
are unchanged; this is an additive replay diagnostic, not a new catalog wire format.
Owned connector suites need to adopt the new artifact/threshold separately; their
existing oracle/record totals are not retrospectively upgraded into mirror evidence.

JDBC getter/renderer failures propagate as contextual conversion errors before a
query can reach regex comparison, label/hash registration or the engine. The old
`<jdbc-unreadable>` sentinel is not synthesized; a genuine SQL string with that
spelling remains ordinary data. No broad regex or repeated error hash can validate
an unreadable value. Later records still run after a failed query.

Oracle statement expectations are determined before adapter setup. A successful
`statement error` fails without invoking the adapter; a real expected oracle SQL
error never invokes it either. After an accepted oracle success, any adapter-connect
exception is an explicit setup failure, never an expected SQL error or query-only
engine skip. The driver marks the engine unavailable after setup failure; normal
successful setup and ordinary oracle error matching are retained.

## Replay temporary-directory ownership

In 0.13, `DuckDbOracle(testDirRoot = parent)` creates a fresh owned child under the
existing parent. Use `oracle.testDir` for the actual per-oracle directory; it is
not the supplied parent. Closing removes only that child without following file
or directory symlinks, including a child root replaced with a symlink. Parent
contents and symlink targets remain unowned. The no-argument constructor still
creates and owns a temporary directory. Constructor descriptors are unchanged;
the old behavior of deleting a supplied parent is intentionally not retained.

## DuckDB 2.0 / DuckLake 1.1

The [conservative final-1.1 reader](READING-1.1.md) requires an owned read session
on a [qualified DuckDB-backed route](READ-BACKENDS.md): direct Java Quack, read-only
local DuckDB or native Quack. Direct PostgreSQL has a separate
[guarded evolution tier](READ-POSTGRES-1.1.md). MySQL separately qualifies
[read-only existing-1.1 catalogs](READ-MYSQL-1.1.md) under its narrower scalar/layout
contract; native creation, upgrades, writes and scanner SQL remain unqualified.
Legacy APIs still reject nested-schema history and suppress bounds/pruning; the
explicit [hierarchy companion](SCHEMA-HIERARCHY.md) handles bounded names/paths.
This is qualified on official 2.0 artifacts, **not blanket 1.1 or connector support**.
Passing pinned native-preview CI is not passing every strict correctness gate.

The original [shipping qualification](../dev-docs/UPGRADE-DUCKDB-2.0.0.md) covers maintained
2.0 JDBC/local/native-Quack and Java Quack 0.8.0 routes separately, including protocol
v3 without embedded DuckDB in the direct Java reader. Default-off final inline reads
and all 1.1 writes remain guarded. Existing constructors and wire shapes are unchanged.
The subsequent driver scans/verifications through 0.11.1 are recorded separately; they do not
relabel that earlier full engine/corpus run as having used the newer driver.

Native probes have reproduced CDC, inline-flush, statistics, BLOB, lineage and
bucket-pruning failures on specific sampled engines. Results depend on actual
core/extension hashes, not just a version string or a main-branch merge. See the
[developer acceptance runbook](../dev-docs/DUCKLAKE-1.1-ACCEPTANCE.md) for qualification.

**Rollback requires a tested, consistent backup of metadata and data.** A 2.0
write/checkpoint can upgrade local `.db` storage even while the DuckLake format
remains 1.0. Swapping JDBC versions or editing a catalog-version string is not a
supported downgrade. Upgrade native Quack peers together; use normal remote TLS
configuration, not the loopback-only plaintext Docker test fixture settings.
