package dev.brikk.ducklake.slt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class TestSltResourceLimits {
    private val sql = SltStatement(1, "SELECT 1", false, null, null)
    private fun parse(text: String) = SltParser.parse("limits.test", text)
    private fun refusal(text: String, detail: String) {
        val file = parse("statement ok\nSELECT 'must not run'\n\n$text")
        assertThat(file.records).singleElement().isInstanceOf(SltUnsupported::class.java)
        val unsupported = file.records.single() as SltUnsupported
        assertThat(unsupported.directive).isEqualTo("resource-limit")
        assertThat(unsupported.raw).contains(detail)
        assertThatThrownBy { SltExpander.expand(file, "duckdb") }.isInstanceOf(SltLimitExceeded::class.java)
    }
    private fun ast(records: List<SltRecord>) = SltFile("hand-built.test", records)

    @Test fun numericRangeRefusesBeforeEagerAllocationAndDropsRunnablePrefix() {
        refusal("loop i 0 ${SltLimits.MAX_LOOP_VALUES + 1}\n\nstatement ok\nSELECT 1\n\nendloop\n", "loop values")
    }

    @Test fun fullLongRangeCannotOverflowTheCardinalityGuard() {
        refusal("loop i ${Long.MIN_VALUE} ${Long.MAX_VALUE}\n\nendloop\n", "loop values")
    }

    @Test fun exactRangeBoundaryAndDescendingEmptyRangesRemainValid() {
        val file = parse("loop i 0 ${SltLimits.MAX_LOOP_VALUES}\n\nendloop\n\n" +
            "loop j 5 -5\n\nstatement ok\nSELECT 1\n\nendloop\n")
        assertThat(file.records).hasSize(2).allMatch { it is SltLoop }
        assertThat((file.records[0] as SltLoop).values).hasSize(SltLimits.MAX_LOOP_VALUES).startsWith("0").endsWith("9999")
        assertThat((file.records[1] as SltLoop).values).isEmpty()
        assertThat(SltExpander.expand(file, "duckdb")).isEmpty()
    }

    @Test fun totalRangeAllocationIsBoundedAcrossSeparateLoops() {
        refusal("loop i 0 ${SltLimits.MAX_LOOP_VALUES}\n\nendloop\n\n".repeat(3), "total loop values")
    }

    @Test fun foreachCollectionsAreCheckedBeforeCollectionInsertion() {
        refusal("foreach t " + "<all_types_columns> ".repeat(200) + "\n\nendloop\n", "foreach values")
    }

    @Test fun directiveTokenizationDoesNotEagerlySplitAnEntireHugeHeader() {
        refusal("foreach i " + "x ".repeat(100_000) + "\n\nendloop\n", "directive tokens")
    }

    @Test fun sourceCharactersAreCheckedBeforeLineMaterialization() {
        refusal("#".repeat(SltLimits.MAX_SOURCE_CHARS + 1), "source characters")
    }

    @Test fun shortEmptyLinesHaveTheirOwnAllocationBudget() {
        refusal("\n".repeat(SltLimits.MAX_LINES + 1), "source lines")
    }

    @Test fun parsedRecordCountIsBoundedWithoutKeepingAPrefix() {
        refusal("require core_functions\n".repeat(SltLimits.MAX_RECORDS + 1), "records")
    }

    @Test fun conditionalNestingRefusesBeforeRecursionOverflows() {
        refusal("onlyif duckdb\n".repeat(SltLimits.MAX_NESTING + 1) + "statement ok\nSELECT 1\n\n", "nesting")
    }

    @Test fun loopNestingAndUnsupportedConcurrentBodiesShareTheDepthBudget() {
        for (header in listOf("loop i 0 1", "concurrentloop i 0 1")) {
            refusal(("$header\n\n").repeat(1000) + "statement ok\nSELECT 1\n\n" + "endloop\n\n".repeat(1000), "nesting")
        }
    }

    @Test fun nestedProductsAreRejectedRatherThanPartiallyUnrolled() {
        refusal("loop i 0 1000\n\nloop j 0 1000\n\nstatement ok\nSELECT 1\n\nendloop\n\nendloop\n", "expanded visits")
    }

    @Test fun exactNestingBoundaryStillExpandsEveryStatement() {
        val file = parse("onlyif duckdb\n".repeat(SltLimits.MAX_NESTING - 1) + "statement ok\nSELECT 1\n\n")
        assertThat(SltExpander.expand(file, "duckdb")).singleElement().extracting { it.sql }.isEqualTo("SELECT 1")
    }

    @Test fun publicAstCannotBypassDepthLimitsEvenWithCycles() {
        val body = mutableListOf<SltRecord>()
        body += SltLoop(1, "i", listOf("0"), body)
        assertThatThrownBy { SltExpander.expand(ast(body), "duckdb") }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("nesting")
    }

    @Test fun publicAstCannotBypassValueOrProductLimits() {
        for (loop in listOf(
            SltLoop(1, "i", List(SltLimits.MAX_LOOP_VALUES + 1) { "0" }, emptyList()),
            SltLoop(1, "i", List(1000) { "0" }, listOf(SltLoop(2, "j", List(1000) { "0" }, emptyList()))),
        )) {
            assertThatThrownBy { SltExpander.expand(ast(listOf(loop)), "duckdb") }.isInstanceOf(SltLimitExceeded::class.java)
        }
    }

    @Test fun zeroIterationAndGuardedBodiesAreStillStructurallyBounded() {
        var record: SltRecord = sql
        repeat(SltLimits.MAX_NESTING + 1) { record = SltConditional(1, true, "duckdb", record) }
        assertThatThrownBy { SltExpander.expand(ast(listOf(SltLoop(1, "i", emptyList(), listOf(record)))), "duckdb") }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("nesting")
    }

    @Test fun cumulativeOutputCharactersAreBoundedEvenWithoutSubstitution() {
        val text = "x".repeat(20_000)
        val file = ast(listOf(SltLoop(1, "i", List(1000) { "0" }, listOf(sql.copy(sql = text)))))
        assertThatThrownBy { SltExpander.expand(file, "duckdb") }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("expanded characters")
    }

    @Test fun replacementGrowthIsRejectedBeforeAllocatingTheGiantResult() {
        val file = ast(listOf(sql.copy(sql = "{X}".repeat(1000))))
        assertThatThrownBy { SltExpander.expand(file, "duckdb", mapOf("X" to "x".repeat(100_000))) }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("text characters")
    }

    @Test fun recursivelyDefinedEnvironmentCannotExhaustHeap() {
        val records = List(22) { SltTestEnv(it + 1, "X", "{X}{X}") } + sql.copy(sql = "SELECT '{X}'")
        assertThatThrownBy { SltExpander.expand(ast(records), "duckdb", mapOf("X" to "x")) }
            .isInstanceOf(SltLimitExceeded::class.java)
    }

    @Test fun literalReplacementAndUnchangedLargePassesHaveBudgetsToo() {
        val budget = SltExpansionBudget()
        assertThatThrownBy { budget.replace("__X__".repeat(1000), "__X__", "x".repeat(100_000)) }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("text characters")
        val unused = (0 until 2000).associate { "__UNUSED_${it}__" to "x" }
        assertThatThrownBy { SltExpander.expand(ast(listOf(sql.copy(sql = "x".repeat(50_000)))), "duckdb", unused) }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("scan characters")
    }

    @Test fun oversizedExternalEnvironmentIsRejectedBeforeCopyingIt() {
        assertThatThrownBy { SltExpander.expand(ast(listOf(sql)), "duckdb", mapOf("X" to "x".repeat(SltLimits.MAX_SOURCE_CHARS + 1))) }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("environment characters")
    }

    @Test fun commaSplitsAreBoundedBeforeBuildingHundredsOfThousandsOfParts() {
        refusal("foreach " + "i,".repeat(100_000) + "i 1\n\nendloop\n", "iterator arity")
        assertThatThrownBy { SltLimits.commaParts(",".repeat(100_000)) }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("comma parts")
    }

    @Test fun exactExpandedVisitBoundaryPassesAndOneMoreRecordRefuses() {
        val loop = SltLoop(1, "i", List(9999) { "0" }, List(9) { sql })
        SltLimits.validate(ast(listOf(loop) + List(9) { sql })) // 1 + 9999*(1+9) + 9 = 100000
        assertThatThrownBy { SltLimits.validate(ast(listOf(loop) + List(10) { sql })) }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("expanded visits")
    }

    @Test fun publicAstSourceStringsAndRecordListsCannotBypassParserBudgets() {
        assertThatThrownBy { SltExpander.expand(ast(List(SltLimits.MAX_RECORDS + 1) { sql }), "duckdb") }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("records")
        assertThatThrownBy { SltExpander.expand(ast(listOf(sql.copy(sql = "x".repeat(SltLimits.MAX_SOURCE_CHARS + 1)))), "duckdb") }
            .isInstanceOf(SltLimitExceeded::class.java).hasMessageContaining("source characters")
    }

    @Test fun eachExpansionGetsAnIndependentBudgetAndOrdinaryUnknownRecordsStayOmitted() {
        val file = ast(listOf(SltUnsupported(1, "unknown", "unknown"), sql))
        repeat(2) { assertThat(SltExpander.expand(file, "duckdb")).hasSize(1) }
    }

    @Test fun anUnknownDirectiveNamedResourceLimitIsNotConfusedWithAParserRefusalMarker() {
        val file = parse("resource-limit not-a-real-directive\n\nstatement ok\nSELECT 1\n\n")
        assertThat(file.records).hasSize(2)
        assertThat(SltExpander.expand(file, "duckdb")).singleElement().extracting { it.sql }.isEqualTo("SELECT 1")
    }

    @Test fun interruptedParsingDoesNotKeepWorkingOrTurnCancellationIntoUnsupported() {
        Thread.currentThread().interrupt()
        try { assertThatThrownBy { parse("statement ok\nSELECT 1\n\n") }.isInstanceOf(InterruptedException::class.java) }
        finally { Thread.interrupted() }
    }

    @Test fun exhaustionWitnessesRunOnlyInSeparatelyBoundedChildren(@TempDir root: Path) {
        val classpath = listOf(ResourceLimitChild::class.java, SltParser::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            .joinToString(java.io.File.pathSeparator)
        for (mode in listOf("range", "nesting", "product", "cycle", "expansion")) {
            val log = root.resolve("$mode.log")
            val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-Xss256k", "-cp", classpath, ResourceLimitChild::class.java.name, mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                assertThat(child.waitFor(20, TimeUnit.SECONDS)).describedAs("bounded child %s", mode).isTrue()
                assertThat(child.exitValue()).withFailMessage(Files.readString(log)).isZero()
                assertThat(Files.readString(log)).contains("SLT resource limit:").doesNotContain("OutOfMemoryError", "StackOverflowError")
            } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) } }
        }
    }

    @Test fun normalTemplatesCommaLoopsAndProvenanceRemainUnchanged() {
        val file = parse("foreach i,v 1,a 2,b\n\nstatement error\nSELECT '{i}:{v}:{X}'\n----\nerror {X} {i}\n\nendloop\n")
        val records = SltExpander.expand(file, "duckdb", mapOf("X" to "ok"))
        assertThat(records.map { it.sql }).containsExactly("SELECT '1:a:ok'", "SELECT '2:b:ok'")
        assertThat(records.map { it.expectedError }).containsExactly("error ok {i}", "error ok {i}")
        assertThat(records).allSatisfy { assertThat(it.file).isEqualTo("limits.test"); assertThat(it.line).isEqualTo(3) }
    }
}
