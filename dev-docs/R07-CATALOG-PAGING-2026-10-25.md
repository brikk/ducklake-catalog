# R07 catalog payload/deletion paging — October 25, 2026

**Doris consumer update:** `3448107ad3a0ea7375863e69cf4e442633354e25` qualifies
10f81f9 paged capture:16 live/14 targeted checks, candidate262/236 and released
253/227 Java25/Java17 lanes, lint/ABI/assembly, restored byte-identical released ZIP.
The128-total-page boundary is retained and practical history limits remain explicit.
Source report: `/home/jayson/DEV/brikk/doris-ducklake/dev-docs/VALIDATION-R07-PAGING-2026-10-25.md`.
The prior Doris assignment below has finished; fresh GPT6.1Sol session
`ses_e8be6ad39ffedAk2DgeiQf6A3j` is now assigned6730bb8 integration/requalification.
Use only APIs needed by the ordinary tier; do not invent an engine inline/CDC
surface because payload/deletion metadata APIs now exist.

**Trino consumer update:** `cd4afc1d8036bbe0169ba6ceae6d70bb865addff` qualifies
10f81f9 typed planning pages/controlled views (128 candidate/265 released tests,
lint,81 corpus records). Actual PostgreSQL engine cancellation and deadline stop
the waiting metadata statement before fixture unlock, followed by one-connection
recovery; worker/other-driver/network/lease guarantees are not inferred. Source
report: `/home/jayson/DEV/brikk/trino-ducklake/dev-docs/R07-TRINO-CANDIDATE-QUALIFICATION.md`.
Fresh GPT6.1Sol session `ses_e8be2ebaeffeDrkOEHWirU0xs3` now adopts6730bb8 payload/
deletion/history APIs. Both earlier10f81f9 task entries below are historical; both
latest6730bb8 results are pending. No newer-byte consumer success is inferred.

## Outcome and boundary

The reported catalog payload/deletion/history transfer entry points are now
implemented and fully library-verified. **R07 is not declared complete:** both
connectors must adopt/qualify the exact new bytes with whole-query budgets, and
the documented engine/retention/fleet/native gates remain open. No legacy list
method becomes bounded merely because a corresponding page method exists.

No push, formal/remote publication, production deployment, default-on1.1 reads,
automatic migration, connector1.1 writes or physical cleanup was authorized or run.
Native/JDBC/extension/corpus pins, expected goldens and exclusions remain unchanged.
The latest upstream review remains the [October24 checkpoint](UPSTREAM-REVIEW-2026-10-24.md),
not a claim that newer source fixes were installed during these API tests.

## Verified catalog commits

- `10f81f91df69ab6bc024cc02b6a0530628c54166`: resolves Trino's non-NULL unknown
  default-history repro. Unknown kinds reject; genuine native VARCHAR literal
  `NULL` retains its materialized initial value despite known INSERT-expression
  encoding. No expression execution/wrong return was established by the original
  discrepancy, and no expression text is evaluated by the fix.
- `df00fcd`: ordinary and inclusive-CDC decoded inline pages. Composite physical
  row-version seeks preserve repeated same-ID updates and real begin/end facts;
  SQL cell/row limits and decoded/default-copy budgets precede unsafe expansion.
  Live counts cannot hide deleted/transient history. Identity-only pages are
  explicit, not unqualified payload support.
- `6730bb871350fe27d6c926db2f1d8f50f41cb5e1`: incremental/full deletion candidate
  phases, inline file-local positions, exact retained-file ID batches, snapshot
  and retained change/conflict-history pages. SQL-bounded predecessor lookups,
  scoped identities, untruncated text, actual physical-type validation and missing/
  ambiguous metadata refusals replace unbounded-history transfer on these new APIs.

Earlier default/control/file/metadata page commits and first consumer implementation
are retained in [the October24 continuation](R07-CONSUMER-CONTINUATION-2026-10-24.md).
Public record components/constructors/fragment JSON and legacy0.4/1.0 branches stay
unchanged. New entry points are additive; internal projection helpers are not new
public wire capabilities.

## Precise contracts

- [Decoded missing-file defaults](../docs/FILE-INITIAL-DEFAULTS-1.1.md): physical
  absence must be proven independently; stored/parent NULLs remain NULL. MySQL
  evolution and non-NULL whole-container defaults stay unqualified.
- [Controlled owned sessions](../docs/READ-SESSION-CONTROL.md): actual executing
  JDBC Statement.cancel/remaining timeout, positively tested on blocked PostgreSQL
  before fixture unlock. No invented engine cancellation registration, helper
  thread, connection kill or query retry; other interruption boundaries remain.
- [Active/added file pages](../docs/READ-FILE-PAGES-1.1.md) and
  [planning metadata](../docs/READ-PLANNING-METADATA-1.1.md): complete same-view
  enumeration and caller node/depth/path/byte/work budgets remain mandatory.
- [Inline ordinary/CDC payload pages](../docs/READ-INLINE-PAGES-1.1.md): fresh
  stable-ID target projection, encoded/decoded allocation guards, nullable actual
  ends and unchanged candidate/consumer event-assembly responsibility.
- [Deletion/position/batch/history pages](../docs/READ-DELETION-PAGES-1.1.md): two
  enumeration phases are not chronological CDC events; same-path containers retain
  embedded snapshot meaning; expired snapshot conflict rows are not joined away.

Planning/default paths now validate actual metadata types before coercion and clear
their validation marker on session exit. Fractional footer storage cannot be
converted into an apparently valid integer offset; queried negative optional row
starts/IDs cannot become a fabricated contiguous origin. Rich-metadata observation
arrays themselves are unchanged. Optional registry/positional tables are validated
only when present, preserving absence semantics without false terminal pages.

## Evidence

Latest full command, isolated Gradle registry and exact native lane:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-daemon --no-configuration-cache
```

- **985 maintained JUnit tests** (836 catalog,51 SLT,98 replay), plus **86 separate
  direct-Java tests**: zero failures/errors/JUnit skips.
- **17,602 corpus records**, unchanged100 file/12 record exclusions and all original
  goldens/pins. Lint/frozen ABI/wire checks pass.
- Java17 maximum61: **7,828 classes across17 artifacts**.
- Log `/tmp/opencode/catalog-r07-deletion-pages-full.log`, **27m20s**.
- Preceding payload full run:934 maintained+86 direct-Java, zero failures/errors/
  JUnit skips; log `/tmp/opencode/catalog-r07-inline-payload-pages-full.log`,24m57s.

Payload paging adds31 cases; deletion/history/type checks add51. Five-route scalar
queries are distinct from four-route native mutation/evolution cases; authored
corruption/shape fixtures are not represented as native MySQL writer, connector
CDC, expiry or physical-object success. The original incorrect per-DELETE-row test
assumption is retained and explained in the deletion contract; native consolidation
into one tagged cumulative container is positively checked, not treated as a waiver.

Strict native R02=3/R03=1/R04=1/M01=6+4 and Doris's long-partition producer failure
remain unwaived. They were not rerun or relabeled as passes by these library tests.

## Immutable candidate and pending consumer work

New local bundle: **`/tmp/opencode/catalog-r07-6730bb8`**. Its README/SHA256SUMS
identify the exact source and catalog+fixtures/replay/SLT0.13.0-SNAPSHOT Maven tree.
Catalog JAR SHA256:
`9ae564720d48b78f8aa6d4349cc082a8ff11815dfe8a9e639a5c140cc95cbab6`.
The other three runtime/fixture JARs retain their earlier hashes; all four are
independently scanned at max61. Publication log:
`/tmp/opencode/catalog-r07-6730bb8-publication.log`.

All earlier bundles are unchanged. Resolve exclusively from the selected bundle and
verify actual resolved bytes. Preserve Doris's scoped FE Maven-local dependencies;
do not globally redirect Maven-local resolution. A repository/version string alone
does not establish candidate identity.

Fresh **GPT6.1Sol** sessions are currently assigned10f81f9 adoption:

- Trino `ses_e8fe300ceffe6MX1AxMfhy8hee`: paged planning, actual JDBC control and
  restored unknown-history negative/recovery checks, following verifiede4743d5.
- Doris `ses_e8fe300c2ffe0YG0UPbw5B6f21`: paged controlled statement capture and
  fresh negative/recovery checks, following verified7535fcf/a6aa725.

Do not interrupt/duplicate their work or infer adoption of6730bb8. When a result
returns, hand off the latest immutable payload/deletion/history APIs in a **fresh
GPT6.1Sol session** per the owner preference, with exact tested commits/bytes.
Permission approvals can delay startup; no polling/restart is needed.

Still open: complete consumer adoption/whole-query budgets and positive SQL/fault
evidence; genuine0.4 and broader type/route/packaging qualification; Doris's real
snapshot-filter/inline/CDC/cancellation engine interfaces; immutable object retention/
leases, aggregate pools, fleets and production approval. New API availability does
not eliminate those boundaries or automatically qualify every other legacy list call.
