package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltFile
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SltTestEnv
import dev.brikk.ducklake.slt.SortMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** H16: CompareValues keeps raw cells; expanded keywords are exact-text alternatives, not new control syntax. */
internal class TestGoldenKeywordAlternatives {
    private val raw = "\${FOO}"
    private val oneHash = "1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1"
    private fun literal(name: String = "FOO") = "chr(36)||chr(123)||'$name'||chr(125)"
    private fun query(sql: String, expected: List<String>, types: String, sort: String) =
        "query $types $sort\n$sql\n----\n${expected.joinToString("\n")}\n\n"
    private fun file(text: String, env: Map<String, String>): SltFile {
        val parsed = SltParser.parse("keyword-golden.test", text)
        // Values supplied through the public AST may contain tabs/spaces, just as native OS env values can.
        val definitions = env.entries.mapIndexed { index, (name, value) -> SltTestEnv(index + 1, name, value) }
        return parsed.copy(records = definitions + parsed.records)
    }
    private fun run(sql: String, expected: List<String>, types: String = "T", env: Map<String, String> = mapOf("FOO" to "bar"),
        sort: String = "nosort") = ReplayDriver().replay(file(query(sql, expected, types, sort), env))
    private fun passes(sql: String, expected: List<String>, types: String = "T", env: Map<String, String> = mapOf("FOO" to "bar"),
        sort: String = "nosort") = run(sql, expected, types, env, sort).also {
        assertThat(it.fileSkipReason).isNull()
        assertThat(it.failed).isEmpty()
        assertThat(it.skipped).isEmpty()
        assertThat(it.passed).isEqualTo(1)
    }
    private fun fails(sql: String, expected: List<String>, types: String = "T", env: Map<String, String> = mapOf("FOO" to "bar")) {
        val result = run(sql, expected, types, env)
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.skipped).isEmpty()
        assertThat(result.failed).hasSize(1)
        assertThat(result.passed).isZero()
        assertThat(result.queryCoverage!!.oraclePasses).isZero()
    }

    @Test fun reportedLiteralDollarMacroKeepsItsValidRawGolden() { passes("SELECT ${literal()}", listOf(raw)) }
    @Test fun literalBraceMacroAlsoKeepsItsRawGolden() {
        passes("SELECT chr(123)||'FOO'||chr(125)", listOf("{FOO}"))
    }
    @Test fun exactExpandedTextStillMatches() { passes("SELECT 'bar'", listOf(raw)) }

    @Test fun rawAndExpandedFormsCanMatchDifferentCellsInOneRow() {
        passes("SELECT ${literal()},'bar'", listOf("$raw\t$raw"), "TT")
    }
    @Test fun rawAndExpandedFormsCanMatchDifferentRows() {
        passes("SELECT v FROM (VALUES(1,${literal()}),(2,'bar'))t(i,v) ORDER BY i", listOf(raw, raw))
    }
    @Test fun legacyValuePerLineLayoutKeepsPerCellAlternatives() {
        passes("SELECT ${literal()},'bar'", listOf(raw, raw), "TT")
    }
    @Test fun allSortModesAcceptMixedRawAndExpandedCells() {
        for (sort in listOf("nosort", "rowsort", "valuesort")) {
            passes("SELECT v FROM (VALUES(1,${literal()}),(2,'bar'))t(i,v) ORDER BY i", listOf(raw, raw), sort = sort)
        }
    }
    @Test fun rowsortCanKeepALiteralRawTokenAheadOfItsCompanion() {
        passes("SELECT ${literal("X")} UNION ALL SELECT 'a'", listOf("\${X}", "a"), env = mapOf("X" to "z"), sort = "rowsort")
    }
    @Test fun rowsortUsesValidGivenPositionsBeforeRawTokenSorting() {
        passes("SELECT 'z' UNION ALL SELECT 'a'", listOf("a", "\${X}"), env = mapOf("X" to "z"), sort = "rowsort")
    }

    @Test fun rawPositiveRegexPatternIsNotRewrittenByEnvironmentExpansion() {
        passes("SELECT 'F'", listOf("<REGEX>:[$raw]"))
    }
    @Test fun rawNegativeRegexPatternIsNotRewrittenByEnvironmentExpansion() {
        passes("SELECT 'b'", listOf("<!REGEX>:[$raw]"))
    }
    @Test fun keywordExpansionCannotActivateANewRegexControl() {
        fails("SELECT 'wrong'", listOf("\${PAT}"), env = mapOf("PAT" to "<REGEX>:.*"))
    }
    @Test fun keywordExpansionCannotActivateANewNegativeRegexControl() {
        fails("SELECT 'wrong'", listOf("\${PAT}"), env = mapOf("PAT" to "<!REGEX>:never"))
    }
    @Test fun keywordExpansionCannotActivateAHashGolden() {
        fails("SELECT 1", listOf("\${HASH}"), "I", mapOf("HASH" to oneHash))
    }
    @Test fun anExpandedHashLookingCellIsStillLiteralData() {
        passes("SELECT '$oneHash'", listOf("\${HASH}"), env = mapOf("HASH" to oneHash))
    }

    @Test fun expandedIntegerTextDoesNotGetAdditionalNumericCoercion() {
        fails("SELECT 1", listOf("\${N}"), "I", mapOf("N" to "1.0"))
    }
    @Test fun expandedRealTextDoesNotGetAdditionalFloatingTolerance() {
        fails("SELECT 1.0000000001::DOUBLE", listOf("\${N}"), "R", mapOf("N" to "1.0"))
    }
    @Test fun expandedBooleanLiteralUsesNativeOneZeroProvenance() {
        passes("SELECT TRUE", listOf("\${B}"), "I", mapOf("B" to "1"))
        passes("SELECT FALSE", listOf("\${B}"), "I", mapOf("B" to "0"))
    }
    @Test fun expandedBooleanWordsAreNotReinterpretedAsRawBooleanExpectations() {
        fails("SELECT TRUE", listOf("\${B}"), "T", mapOf("B" to "true"))
        fails("SELECT FALSE", listOf("\${B}"), "T", mapOf("B" to "false"))
    }
    @Test fun rawNumericAndBooleanCoercionsStayAvailable() {
        passes("SELECT 1", listOf("1.0"), "I")
        passes("SELECT 1.0000000001::DOUBLE", listOf("1.0"), "R")
        passes("SELECT TRUE", listOf("true"), "T")
    }
    @Test fun expansionCannotInjectExpectedColumnBoundaries() {
        fails("SELECT 1,2", listOf("\${SEP}"), "II", mapOf("SEP" to "1\t2"))
    }
    @Test fun aTabIntroducedInsideOneExpectedCellStaysInsideThatCell() {
        passes("SELECT chr(97)||chr(9)||chr(98)", listOf("\${SEP}"), env = mapOf("SEP" to "a\tb"))
    }
    @Test fun exactIntegerAndVarcharLookalikeMismatchesAreStillFailures() {
        fails("SELECT 9007199254740993::BIGINT", listOf("9007199254740992"), "I")
        fails("SELECT 'true'", listOf("\${B}"), "I", mapOf("B" to "1"))
    }

    @Test fun aRawExactMatchDoesNotAttemptAnUnneededHugeExpansion() {
        passes("SELECT repeat(${literal("X")},1000)", listOf("\${X}".repeat(1000)), env = mapOf("X" to "x".repeat(100_000)))
    }
    @Test fun aNeededHugeExpansionStillHitsTheExistingResourceBudget() {
        val result = run("SELECT 'not-the-raw-token'", listOf("\${X}".repeat(1000)), env = mapOf("X" to "x".repeat(100_000)))
        assertThat(result.fileSkipReason).startsWith("RESOURCE LIMIT:")
        assertThat(result.passed).isZero()
        assertThat(result.queryCoverage!!.oraclePasses).isZero()
    }

    @Test fun returningRawTokensIsOneMutationNotAQueryReplay() {
        val text = "statement ok\nCREATE TABLE audit(i INTEGER PRIMARY KEY)\n\n" +
            query("INSERT INTO audit VALUES(1) RETURNING i,${literal()}", listOf("1\t$raw"), "IT", "nosort") +
            query("SELECT count(*),sum(i) FROM audit", listOf("1\t1"), "II", "nosort")
        val result = ReplayDriver().replay(file(text, mapOf("FOO" to "bar")))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).isEmpty()
        assertThat(result.skipped).isEmpty()
        assertThat(result.passed).isEqualTo(3)
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(2)
    }

    private fun mirrorCase(sql: String, expected: String, returned: String): Pair<FileResult, List<String>> {
        val calls = mutableListOf<String>()
        val engine = object : ReplayReadEngine {
            override val name = "keyword-golden-control"
            override fun connect(attachment: OracleAttachment) = Unit
            override fun executeQuery(sql: String): List<List<String?>> { calls += sql; return listOf(listOf(returned)) }
            override fun close() = Unit
        }
        val setup = "statement ok\nLOAD ducklake\n\nstatement ok\nATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake " +
            "(DATA_PATH '{TEST_DIR}/data',DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)\n\n"
        val result = ReplayDriver(engine).replay(file(setup + query(sql, listOf(expected), "T", "nosort"), mapOf("FOO" to "bar")))
        return result to calls
    }
    @Test fun aRawGoldenPassMirrorsTheOriginalRowsNotExpandedExpectations() {
        val sql = "SELECT ${literal()}"
        val (result, calls) = mirrorCase(sql, raw, raw)
        assertThat(result.failed).isEmpty()
        assertThat(calls).containsExactly(sql)
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(1)
    }
    @Test fun aGenuineMismatchNeverReceivesOracleOrMirrorCoverage() {
        val (result, calls) = mirrorCase("SELECT 'wrong'", raw, "wrong")
        assertThat(result.failed).hasSize(1)
        assertThat(calls).isEmpty()
        assertThat(result.queryCoverage!!.oraclePasses).isZero()
        assertThat(result.queryCoverage!!.mirrorAttempts).isZero()
    }
    @Test fun csvCellsAlsoKeepRawAndExpandedAlternatives(@TempDir root: Path) {
        val csv = Files.writeString(root.resolve("expected.csv"), "value\n$raw\n$raw\n")
        passes("SELECT v FROM (VALUES(1,${literal()}),(2,'bar'))t(i,v) ORDER BY i", listOf("<FILE>:$csv"))
    }
    @Test fun labelsAndRawHashGoldensKeepTheirExistingPolicies() {
        passes("SELECT 1", listOf(oneHash), "I")
        val text = "query I nosort label\nSELECT 1\n----\n$raw\n\n" +
            "query I nosort label\nSELECT 1\n----\nignored\n\n"
        val result = ReplayDriver().replay(file(text, mapOf("FOO" to "bar")))
        assertThat(result.failed).isEmpty()
        assertThat(result.passed).isEqualTo(2)
    }
    @Test fun comparisonWithoutAnExpansionCallbackKeepsTheOldPublicContract() {
        val q = SltQuery(1, "SELECT 1", "T", SortMode.NOSORT, null, null, listOf(raw))
        assertThat(GoldenComparator.compare(q, listOf(listOf(raw)), listOf("VARCHAR"))).isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(GoldenComparator.compare(q, listOf(listOf("bar")), listOf("VARCHAR")))
            .isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
    }
    @Test fun unknownKeywordsRemainLiteral() { passes("SELECT ${literal("UNKNOWN")}", listOf("\${UNKNOWN}"), env = emptyMap()) }
    @Test fun rawWhitespaceRemainsSignificantAroundAMacro() {
        passes("SELECT ' '||${literal()}||' '", listOf(" $raw "))
        fails("SELECT ${literal()}", listOf(" $raw "))
    }
    @Test fun loopBindingsAreNotAppliedToExpectedKeywordCells() {
        val text = "foreach i one\n\n" + query("SELECT ${literal("i")}", listOf("\${i}"), "T", "nosort") + "endloop\n"
        val result = ReplayDriver().replay(file(text, emptyMap()))
        assertThat(result.failed).isEmpty()
        assertThat(result.passed).isEqualTo(1)
    }

    @Test fun rawExactNativeBooleanAndHashMatchesDoNotInvokeTheCallback() {
        val q = SltQuery(1, "SELECT 1", "T", SortMode.NOSORT, null, null, listOf(raw))
        val never: (String) -> String = { error("unexpected expansion") }
        assertThat(GoldenComparator.compare(q, listOf(listOf(raw)), listOf("VARCHAR"), never))
            .isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(GoldenComparator.compare(q.copy(expected = listOf("1")), listOf(listOf("true")), listOf("BOOLEAN"), never))
            .isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(GoldenComparator.compare(q.copy(expected = listOf(oneHash)), listOf(listOf("1")), listOf("INTEGER"), never))
            .isEqualTo(GoldenComparator.Comparison.Match)
    }

    @Test fun structuralMismatchesAreRejectedBeforeAnyKeywordExpansion() {
        val q = SltQuery(1, "SELECT 1,2", "II", SortMode.NOSORT, null, null, listOf(raw))
        val never: (String) -> String = { error("expansion must not repair a shape mismatch") }
        assertThat(GoldenComparator.compare(q, listOf(listOf("1", "2")), listOf("INTEGER", "INTEGER"), never))
            .isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
        assertThat(GoldenComparator.compare(q, listOf(listOf("1")), listOf("INTEGER"), never))
            .isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
    }

    @Test fun givenExpectedPositionsAndInputsRemainIntactWithTheNewOverload() {
        val expected = mutableListOf("a", "\${X}")
        val actual = mutableListOf(mutableListOf<String?>("z"), mutableListOf<String?>("a"))
        val before = actual.map { it.toList() }
        val q = SltQuery(1, "SELECT 1", "T", SortMode.ROWSORT, null, null, expected)
        assertThat(GoldenComparator.compare(q, actual, listOf("VARCHAR")) { it.replace("\${X}", "z") })
            .isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(expected).containsExactly("a", "\${X}")
        assertThat(actual).isEqualTo(before)
    }
}
