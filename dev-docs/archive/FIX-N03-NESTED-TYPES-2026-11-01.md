# N03: unambiguous nested-type signatures — November 1, 2026

Owner approved N03 → H15 → N19 with separate verification/commits, and requested
notification when Trino/Doris handoffs are needed. No push is authorized for this
new batch. Base: `acd7d03` (pushed Quack JDBC0.12 adoption).

**Status: library repair implemented/verified in scope; pending consumer adoption
and strict project qualification, not Complete.** Connector cutover
is separate, and the previously parked strict project qualification gate remains
unchanged. No connector code, dependency adoption or publication is performed here.

## Reproduction

The corrected before-fix baseline runs **nine tests: four fail, five controls pass**,
zero JUnit errors/skips. The four native-capable routes (local DuckDB, native Quack,
Java Quack and PostgreSQL) all flatten valid field-name punctuation into ambiguous
signatures. Ordinary signatures/case and actual native/JVM-decoded values remain
correct controls, isolating the defect to the metadata string projection.

An initial draft additionally attempted native MySQL composite-table creation.
Both cases failed at the known unsupported native composite type, before this
read path. Those are not N03 regression evidence or assumption-skipped passes.
The new native fixture matrix explicitly covers the four supported producer routes;
the shared grammar has independent backend-free tests. Native MySQL's existing
acceptance boundaries are unchanged.

## Contract and repair

- `getTableColumns` quotes non-ASCII-identifier STRUCT field names using existing
  `DucklakeQuotedList.encodeValue`: double quotes with embedded quotes doubled.
  Plain identifier signatures remain unchanged, including original case.
- New `DucklakeTypeSignature.parse(String)` reuses `InlinedTypeNode` and the existing
  quoted-value decoder. It preserves field names/ordering, canonicalizes only type
  keywords, handles decimal commas and nested LIST/MAP/STRUCT, and rejects malformed
  syntax/arity and exceeded length/depth/node budgets. This is not SQL or new type
  admission. Existing DTO constructors/JSON shape and raw parent-aware rows are unchanged.
- [Public contract](../../docs/NESTED-TYPE-SIGNATURES.md) and
  [consumer handoff](../HANDOFF-N03-NESTED-TYPES-2026-11-01.md) document the intentional
  corrected grammar/cutover boundary. Both inspected consumer parsers currently
  lowercase the whole string and use non-quote-aware delimiter splitting.
  They must use the shared parser or complete parent-aware tree before adoption.
- Trino `25c84f5` and Doris `8c68181` were read-only observations, not qualification
  on the new library. Unrelated uncommitted Doris API26/packaging work was left intact.

## Verification

- Focused parser/integration/default/nested/backend controls: **71 tests pass**,
  including all **18 new N03 tests**, zero failures/errors/skips. Lint and Java17
  classfile checks pass (7,845 classes across17 artifacts).
- Public Java API witness: **passes on actual Java17.0.2 and25.0.2**, compiled with
  `--release17`, using only compiled main catalog classes and Kotlin stdlib2.3.10.
  It verifies quoted field names/case, nested decimal interpretation, malformed
  rejection and the depth budget. No JDBC/native or connector implementation is
  substituted for the public parser call.
- Full forced verification: **1,177/1,177 catalog + 91/91 direct-Java + 51/51 SLT
  pass; replay 161/162**, with only the unchanged strict identity-control baseline.
  Corpus remains **17,596 passed / six failed / 12 original record skips**, with
  all 100 original file exclusions unchanged. Zero JUnit errors/skips. All groups
  actually executed; lint/Java17 checks pass. Duration **47m26s**, exit1 (`--continue`).
  All seven scoped source/proof hashes captured beforehand still match; pinned
  corpus/read-fixture preparation succeeds. No unrelated gate was reopened or waived.
- Consumer changes/verification: **not performed; handoff required**. Do not claim
  the library regressions prove Trino/Doris read correctness on the new contract.
- No driver/server/corpus pin, stored schema/value, write guard, automatic migration,
  sentinel, skip or golden change. No reopening of the parked upstream topic.

Evidence under `/tmp/opencode/`: `catalog-n03-before-verified{.log,-xml/}` and
`catalog-n03-focused-final.log`. The first draft's MySQL setup failures are preserved
separately in `catalog-n03-before{.log,-xml/}`, not counted as desired red evidence.
The [Java17 witness](n03-nested-types-2026-11-01/Java17TypeSignatureSmoke.java)
uses the public static parser and existing type-tree getters without JDBC/runtime
native dependencies.
Completed focused XML/counts: `/tmp/opencode/catalog-n03-focused-xml/`.
Actual JVM witness classpath/logs/counts: `/tmp/opencode/catalog-n03-java/`.
Source hashes: `/tmp/opencode/catalog-n03-source-hashes.json`.
Full log: `/tmp/opencode/catalog-n03-full.log`.
Full XML/counts/failures: `/tmp/opencode/catalog-n03-full-xml/`.

Exact handoff copies under `/tmp/opencode/catalog-n03-handoff/`:

- Main `ducklake-catalog-0.13.0-SNAPSHOT.jar` SHA256
  `01ffed5abb0b970b307dd4de1b7d7ab42320e33016447896204e5a36506a3041`.
- Test fixtures `ducklake-catalog-0.13.0-SNAPSHOT-test-fixtures.jar` SHA256
  `ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f`.

Main JAR class bytes match the full-tested compiled parser/catalog classes. The
public Java witness also passes on Java17.0.2 using the copied JAR rather than
the loose classes. `receipt.json` records artifact/source hashes and full results;
the resolved source commit is added there after commit. No repository publication
or plugin adoption is implied by these scoped artifact copies.

Full command:

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  check :ducklake-catalog:quackPreviewTest --rerun-tasks -Dducklake.corpus.dirs=all \
  -I dev-docs/archive/quack-jdbc-0.12.0-2026-10-31/verify_java17.init.gradle \
  -Dorg.gradle.java.installations.paths=/home/jayson/.local/share/mise/installs/java/17.0.2 \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```
