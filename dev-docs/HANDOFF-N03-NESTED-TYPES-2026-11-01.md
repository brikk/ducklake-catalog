# N03 Trino / Doris handoff

**Scoped library checks pass; consumer adoption pending. No connector edit or adoption performed
here.** Use the exact catalog commit/artifact supplied with the handoff, not a
moving SNAPSHOT. Public contract: [nested-type signatures](../docs/NESTED-TYPE-SIGNATURES.md).
Contract/test preparation can start: all 18 new library regressions and 71 focused
cases pass, with the public Java API checked on actual Java17.0.2 and25.0.2.
Full verification passes1,177 catalog/91 direct-Java/51 SLT and161of162 replay,
with only the unchanged strict baseline. This is not a green full-project or
connector acceptance claim. Use the source commit supplied with this document.

## Pinned handoff artifacts

Copies are under `/tmp/opencode/catalog-n03-handoff/`; `receipt.json` includes the
resolved source commit after commit, source hashes and full verification counts.

- `ducklake-catalog-0.13.0-SNAPSHOT.jar`:
  `01ffed5abb0b970b307dd4de1b7d7ab42320e33016447896204e5a36506a3041`.
- `ducklake-catalog-0.13.0-SNAPSHOT-test-fixtures.jar`:
  `ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f`.

The main JAR's public parser smoke test also passes on Java17.0.2. These are scoped
build artifacts, not a Maven publication or an authorized production cutover.

## Why both handoffs are needed

`getTableColumns` previously flattened valid field names `a,b` and `x:y` into
ambiguous text such as `struct<a,b:int32,x:y:int32>`. The corrected projection
quotes unsafe names and exposes `DucklakeTypeSignature.parse(String)` returning
the existing `InlinedTypeNode`. Ordinary ASCII field signatures remain unchanged;
separate stable-ID/parent rows and actual values are unchanged. This is our API
contract, not a driver or stored DuckLake-format change.

Read-only inspection saw Trino `25c84f5` (clean) and Doris `8c68181` with unrelated
uncommitted API26/packaging work. Those files were not edited or reset. Refresh
each consumer's state before changing it; these observations are not current
artifact adoption or whole-engine qualification evidence.

## Trino assignment

Update `src/dev/brikk/ducklake/trino/plugin/DucklakeTypeConverter.kt`'s string path
to use the shared parser, or reuse its existing parent-aware conversion where the
complete raw column tree is available. It currently lowercases the whole signature,
splits at top-level commas without quote/decimal awareness, and takes the first
colon as a field boundary. Preserve source field names, types, order and stable-ID
mapping; do not silently coerce them to engine-compatible replacement names.

Keep scalar mapping, type-manager usage and existing refusal/capability boundaries.
Verify actual metadata and supported inline/file read paths using independently
expected names/values, including nested STRUCT in LIST/MAP. Do not claim untested
paths or infer all 1.1 support from parsing a signature.

## Doris assignment

Update `src/dev/brikk/ducklake/doris/plugin/DuckLakeTypeMapping.kt`'s string path
to use the shared parser or complete parent-aware metadata. Its current parser has
the same whole-input lowercasing and delimiter problems. Preserve existing
timestamp-TZ opt-in and type/capability refusals. Account explicitly for any Doris
identifier restriction: no silent rename, field loss or text degradation.

Coordinate with the already-active API26/packaging work rather than overwriting it.
Parser success does not qualify Doris inline/mixed-history/CDC or production hooks.

## Required evidence from both consumers

- Ordinary legacy signatures remain valid, including decimal parameters in nested
  STRUCT/MAP types. New quoted signatures preserve comma, colon, angle brackets,
  embedded double quotes, literal backslashes, surrounding whitespace, Unicode and
  mixed case. Do not lowercase field names while normalizing type keywords.
- Real NULL vs literal `NULL`, nested NULL parents/containers and field values stay
  distinct. Verify source/target identities, not just rendered happy-path strings.
- Malformed syntax/arity and parser budgets fail clearly; unsupported engine types
  are not counted as supported reads. Avoid fake/sentinel values or changed goldens.
- Record exact catalog/plugin commits, artifact hashes, executed tests and remaining
  acceptance gaps. Follow each repository's targeted testing/commit rules; no full
  connector corpus, publication, push, migration or 1.1 enablement is authorized by
  this handoff alone.

H15/N19 are separate replay-correctness changes, not a reason to redo the declared
1.1 reader implementation or broaden these connector assignments.
