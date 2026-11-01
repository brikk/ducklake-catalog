# Read-correctness batch handoff: N03, H15, N19

Scoped library/framework changes are implemented and committed separately. They
are not a green full-project or consumer-acceptance claim. No connector edits,
publication or push were performed by this batch.

## Source and artifacts

- N03: `3f663b52e1a206e0738c7ecac3b197ba531e4e21`.
- H15: `6585ef1fc9d95d372419618eac59dcd3c7d133d4`.
- N19/final bundle: the commit containing this document; its resolved hash is
  recorded after commit in `build/verification/read-correctness-handoff/receipt.json`.

That directory holds exact copied main catalog, test-fixture, replay and SLT JARs
with SHA256s, source/proof receipts and copied-JAR Java17/25 smoke logs. Do not use
a moving SNAPSHOT as an artifact identity or silently overwrite a shared dependency
installation. These are build copies, not a Maven publication.

The catalog and test-fixture bytes still match the original N03 receipt at
`/tmp/opencode/catalog-n03-handoff/receipt.json`; the updated replay JAR combines
H15/N19, SHA256 `bd97eba965f2234faa75cf27a788c45a70d4f030115e51b7506104267a9899b1`.

## Trino / Doris work

1. Perform the [N03 parser/type-tree adoption](HANDOFF-N03-NESTED-TYPES-2026-11-01.md),
   retaining names, types, ordering and stable IDs through real supported read paths.
   Library tests do not prove either connector has done this.
2. When adopting the replay artifact, verify relevant floating/Boolean conformance
   on each supported test JVM. H15 matches the pinned native Grisu/FPP formatting
   (including FLOAT fallback and signed NaN) without a JVM upgrade or new dependency.
3. N19's framework path already supplies SQL type metadata for native Boolean hashes.
   A source inspection found no direct `resultHash` callers in the owned Kotlin/Java
   consumers. Any new direct caller should use the [typed API](../docs/CORPUS-HASHES.md)
   when metadata is available; raw/live cells and the old untyped descriptor remain unchanged.
4. Keep the existing positive measured mirror-coverage obligation. Setup/oracle/record
   totals alone do not establish engine coverage. Do not expand to a full connector
   corpus, production enablement, publication or push without the owner's assignment.

Do not overwrite the unrelated Doris API26/packaging work observed during the
read-only source check. Preserve each repository's own agent/testing instructions.

## Evidence and remaining boundary

All **42 new batch regressions** pass. Final full check: **1,177 catalog +91 direct-
Java +51 SLT pass; replay185/186**, with only the unchanged parked strict baseline.
Original corpus exclusions/goldens remain unchanged. H15 additionally passes100K
paired native raw-bit samples on17/25; public API witnesses cover17/21/24/25.

Per-step evidence:
[N03](archive/FIX-N03-NESTED-TYPES-2026-11-01.md),
[H15](archive/FIX-H15-FLOAT-TEXT-2026-11-01.md),
[N19](archive/FIX-N19-BOOLEAN-HASHES-2026-11-01.md).
Consumer adoption, strict qualification, default-on reads and all1.1 writes remain
separate. No parked upstream investigation was reopened.
