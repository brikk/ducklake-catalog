# H15: JVM-independent floating-point text — November 1, 2026

Second step of the approved N03 → H15 → N19 batch. N03 is committed as `3f663b5`,
with separate consumer handoffs and pinned artifact copies. N19 is untouched.
No push or publication is authorized for this batch.

**Status: implemented/verified in scope; pending strict project qualification,
not Complete.** Existing project
qualification boundaries remain unchanged and the parked upstream topic is not
reopened by this formatter repair.

## Before-fix evidence

- The existing/new floating regression matrix executes **11 tests** on each JVM:
  **five fail on Java17**, **three fail on Java25**, zero JUnit errors/skips.
  Native2.0 text and independently expected strings identify the failures; they
  are not accepted as harmless JVM-dependent golden differences.
- A standalone public-method witness compiled with `--release17` runs12 cases:
  **six fail on actual Java17.0.2**, **four on Java25.0.2**. For example, Java17's
  old path emits `9.999999999999999e+22` for `1e23` and `1.17549435e-38` for
  Float.MIN_NORMAL, instead of native `1e+23` and `1.1754944e-38`.
- Both JVMs also disagree with native shortest subnormal text. Newer JVMs are not
  universally closer: the old paths differ on the second-smallest DOUBLE too.
  Native shortest results include `5e-324`, `1e-323`, `1e-45` and `3e-45`.

## Repair

The first Schubfach draft was rejected by the wider native oracle: native FLOAT
bits `ca762bff` render as `-4033279.75`, not shortest-FLOAT `-4033279.8`. The pinned
fmt uses Grisu with FLOAT boundaries, but its explicitly documented fallback still
uses the promoted DOUBLE boundaries. A generic shortest-FLOAT converter is therefore
not an exact replacement. No value-specific patch or relaxed comparison was accepted.
The owner approved porting the exact native formatter rather than parking H15.

- Port the default Grisu digit-generation/rounding path and the FPP fallback from
  DuckDB **d54cbbe3df17e8e5ce68312f483b4155836112aa** (`v2.0.0`), including the
  FLOAT fallback behavior. Use unsigned integer arithmetic and JDK BigInteger;
  **no new dependency remains**. The draft Jackson declaration was removed.
- Preserve all87 cached power significands/exponents exactly; a source comparison
  verifies them against the pinned native files. MIT provenance/notice is retained
  in the source and packaged as `META-INF/licenses/duckdb-fmt.txt`.
- Retain fixed/exponential layout thresholds, exponent sign/padding, integral `.0`,
  negative zero, infinities and native signed NaN text. The raw-bit oracle also
  exposed the old renderer dropping negative NaN's sign; an explicit regression
  now preserves `-nan` rather than normalizing it to `nan`.
- Formatting performs no SQL, native/private reflection, query replay or
  exception-to-value recovery. Primitive values and Boolean hashing are unchanged;
  N19 remains a separate step. No JDK-private formatter or runtime-version branch
  is used. The port deliberately matches the pinned formatter, not a moving fmt release.

## Verification

- Focused native text/shape/conversion controls: **36/36 pass on Java17 and36/36
  on Java25**, including all six new cases, zero failures/errors/skips. Lint passes.
- Standalone public-method witness: all **15 cases pass on actual17.0.2,21.0.2,
  24.0.2 and25.0.2**, compiled with `--release17`, without native/Jackson dependencies.
- Independent native fidelity probe: **100,000 paired DOUBLE/FLOAT raw-bit samples
  pass on both17.0.2 and25.0.2**, checking bound value bits and exact native VARCHAR
  text per value, not just a digest or tolerant numeric comparison. Both native and
  port produce SHA256 `84c2de88654d8a7165c19a15af509040a683b32da4663c832291e28f40f1c3c7`.
- Full forced verification: **1,177/1,177 catalog +91/91 direct-Java +51/51 SLT
  pass; replay167/168**, with only the unchanged parked strict baseline. Corpus
  remains **17,596 passed /six failed /12 original record skips**, all100 original
  file exclusions unchanged. Zero JUnit errors/skips. All groups actually executed;
  lint, frozen API/wire and Java17 checks pass. Duration **44m2s**, exit1 (`--continue`).
  All19 scoped source/proof/recipe/license hashes captured beforehand still match.
  Corpus/read-fixture preparation succeeds. No quota/compiler-fallback errors occur
  in this run; no native acceptance gate was reopened or waived.

New `TestFloatingTextPortability` verifies native core2.0.0/d54cbbe3df, checks bound
raw bits before comparing exact native text, and covers layout boundaries, adjacent
values, low-bit positive/negative subnormals and2,048 seeded raw-bit pairs. Nested
LIST/STRUCT text and genuine NULL are compared independently to native VARCHAR
casts; no observed output is written back as an expected golden. Existing renderer,
comparison-integrity and conversion-failure controls remain required.

Evidence under `/tmp/opencode/`:

- `catalog-h15-before-java17{.log,-xml/}` and `catalog-h15-before-java25{.log,-xml/}`.
- `catalog-h15-jvm-before/`: actual17.0.2/25.0.2 witness failures.
- `catalog-h15-focused-java17{.log,-xml/}` and `catalog-h15-focused-java25{.log,-xml/}`.

Completed post-port evidence is in workspace `build/verification/h15/portable/`:
per-JVM focused logs/XML, the four public-method smoke executions, actual17/25
100K native probe logs and `summary.json`. Earlier failed draft/infrastructure
invocations are not substituted for completed post-port verification.

Pinned source fingerprints (`third_party/fmt/include/fmt/`):

- `format-inl.h`: `de74d8400d2811ecdba35ffea158e546aa00380afdd2b395429b868e02f8b2e7`
- `format.h`: `d46b3545a555e6504e67649d251fc9e0d0b73451bc605e8321be6265cabb562b`

The GitHub v2.0.0 tag resolves to the full core commit above; downloaded source/
hash metadata is retained in `build/verification/h15/upstream/`.

The [public-method witness](h15-float-text-2026-11-01/FloatTextJvmSmoke.java) and
[verification-only Java17 launcher](h15-float-text-2026-11-01/verify_java17.init.gradle)
are preserved. This does not change the repository's build toolchain or require
consumers to upgrade their JVM.

### Verification infrastructure interruption

The first post-change Java17 invocation passed its pure formatting cases, but
native-dependent tests could not initialize: extracting DuckDB's shared library
failed with `IOException: Disk quota exceeded`. This is not counted as completed
native verification. `/tmp` is mounted tmpfs with `usrquota`; the workspace disk
has ample free space. No existing files or other session artifacts were deleted.
The isolated Gradle home was copied to workspace `build/verification/gradle-home`,
and the [verification-only temp recipe](h15-float-text-2026-11-01/verify_local_tmp.init.gradle)
sets each test task's `java.io.tmpdir` to its standard local build temporary directory.
Tests, native bytes, goldens and data semantics are unchanged; reruns must actually
execute the native controls. Original interruption evidence remains under
`/tmp/opencode/catalog-h15-focused-java17{.log,-xml/}`.

Compiler daemon backups also encountered the same tmpfs quota. Verification now
uses in-process, non-incremental Kotlin compilation with a workspace-local build
temp directory; no shared daemon was stopped. The Java temp option is inherited by
forked JVMs, including the isolated legacy producer. Test-only explicit temp roots
accept `ducklake.testing.tmpdir`, retaining `/tmp/opencode` as the unchanged default.
The verification init script sets the override to each task's local temporary
directory and declares it as an input. No test-fixture production API, stored data,
semantic assertion or native gate is changed; no existing temporary files were deleted.

Full output/exit record is under `build/verification/h15/full.log` and
`full-exit.json`. The full command uses the preserved Java17 direct-Quack launcher,
the local-temp recipe, inherited Java temp configuration for child JVMs, and fresh
in-process Kotlin compilation:

```sh
GRADLE_USER_HOME="$PWD/build/verification/gradle-home" \
TMPDIR="$PWD/build/verification/process-tmp" \
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=$PWD/build/verification/process-tmp" \
mise exec -- ./gradlew check :ducklake-catalog:quackPreviewTest --rerun-tasks \
  -Dducklake.corpus.dirs=all \
  -I dev-docs/archive/quack-jdbc-0.12.0-2026-10-31/verify_java17.init.gradle \
  -I dev-docs/archive/h15-float-text-2026-11-01/verify_local_tmp.init.gradle \
  -Djava.io.tmpdir="$PWD/build/verification/process-tmp" \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --continue --no-daemon --no-configuration-cache
```

Full XML/counts/failures are preserved in `build/verification/h15/full-xml/`.
The exact replay JAR is copied to `build/verification/h15/handoff/` with SHA256
`4a3b38e0ab4dc5b757c9e410e3fc5e3d529cbf0a91617d6f0ae5490d98dffee6`.
It includes the MIT notice and Java17-or-lower classes. The receipt records source
hashes, numeric/JVM proof and full results; the resolved source commit is added
after commit. This is not publication or consumer artifact adoption.
