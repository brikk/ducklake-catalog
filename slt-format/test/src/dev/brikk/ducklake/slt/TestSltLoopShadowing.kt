package dev.brikk.ducklake.slt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** H13: ordered replacement frames and innermost condition lookup are different contracts. */
internal class TestSltLoopShadowing {
    private fun statement(sql: String) = "statement ok\n$sql\n\n"
    private fun loop(name: String, values: String, body: String) = "foreach $name $values\n\n${body}endloop\n\n"
    private fun expand(text: String, env: Map<String, String> = emptyMap()) =
        SltExpander.expand(SltParser.parse("shadow.test", text), "duckdb", env).map { it.sql }

    @Test fun numericShadowingKeepsTheOuterValueForBothPlaceholderForms() {
        val text = "loop i 1 2\n\nloop i 2 3\n\n" + statement("SELECT '\${i}','{i}'") + "endloop\n\nendloop\n"
        assertThat(expand(text)).containsExactly("SELECT '1','1'")
    }

    @Test fun foreachShadowingKeepsTheOuterToken() {
        assertThat(expand(loop("i", "outside", loop("i", "inside", statement("SELECT '{i}'")))))
            .containsExactly("SELECT 'outside'")
    }

    @Test fun threeRepeatedNamesRetainAllFramesInTheirOriginalOrder() {
        val text = loop("i", "one", loop("i", "two", loop("i", "three", statement("SELECT '{i}'"))))
        assertThat(expand(text)).containsExactly("SELECT 'one'")
    }

    @Test fun eachOuterIterationKeepsItsOwnValueAcrossInnerIterations() {
        val text = "loop i 1 3\n\nloop i 10 12\n\n" + statement("SELECT {i}") + "endloop\n\nendloop\n"
        assertThat(expand(text)).containsExactly("SELECT 1", "SELECT 1", "SELECT 2", "SELECT 2")
    }

    @Test fun leavingAnInnerScopeRestoresTheOuterAndThenTheFollowingLoop() {
        val outer = loop("i", "outer", loop("i", "inner", statement("SELECT '{i}'")) + statement("SELECT 'after:{i}'"))
        assertThat(expand(outer + loop("i", "later", statement("SELECT '{i}'"))))
            .containsExactly("SELECT 'outer'", "SELECT 'after:outer'", "SELECT 'later'")
    }

    @Test fun conditionsStillUseTheInnermostFrameAndTheH11Policy() {
        val body = "onlyif i=two&&i<>one\n" + statement("SELECT '{i}'") +
            "skipif i=one&&i=two\n" + statement("SELECT 'must-not-run'")
        assertThat(expand(loop("i", "one", loop("i", "two", body)))).containsExactly("SELECT 'one'")
    }

    @Test fun numericConditionsReadTheInnerNumberNotTheOuterText() {
        val body = "onlyif i>0\n" + statement("SELECT '{i}'")
        assertThat(expand(loop("i", "outside", loop("i", "1", body)))).containsExactly("SELECT 'outside'")
    }

    @Test fun environmentPrecedenceOverBothShadowingFramesStaysUnchanged() {
        val body = "onlyif i=two\n" + statement("SELECT '{i}'")
        assertThat(expand(loop("i", "one", loop("i", "two", body)), mapOf("i" to "env")))
            .containsExactly("SELECT 'env'")
    }

    @Test fun everyFrameValueIsEnvironmentSubstitutedBeforeItsOwnReplacement() {
        val text = loop("i", "{ROOT}/outer", loop("i", "{ROOT}/inner", statement("SELECT '{i}'")))
        assertThat(expand(text, mapOf("ROOT" to "/r"))).containsExactly("SELECT '/r/outer'")
    }

    @Test fun aLaterSameNameFrameCanConsumeAPlaceholderIntroducedByTheOuterValue() {
        assertThat(expand(loop("i", "{i}-outer", loop("i", "inner", statement("SELECT '{i}'")))))
            .containsExactly("SELECT 'inner-outer'")
    }

    @Test fun deprecatedDollarThenBracePassesArePreservedWithinEveryFrame() {
        assertThat(expand(loop("i", "{i}-outer", loop("i", "inner", statement("SELECT '\${i}'")))))
            .containsExactly("SELECT 'inner-outer-outer'")
    }

    @Test fun repeatedCommaIteratorFramesKeepOuterTextButInnerRawConditionValues() {
        val body = "onlyif i,j=2,inner\n" + statement("SELECT {i},'{j}'")
        assertThat(expand(loop("i,j", "1,outer", loop("i,j", "2,inner", body))))
            .containsExactly("SELECT 1,'outer'")
    }

    @Test fun partialCommaShadowingDoesNotFlattenConditionIteratorIdentity() {
        val first = loop("i", "1", loop("i,j", "2,y", "onlyif i=1&&i,j=2,y\n" + statement("SELECT {i},'{j}'")))
        val second = loop("i,j", "1,x", loop("i", "2", "onlyif i=2&&i,j=1,x\n" + statement("SELECT {i},'{j}'")))
        assertThat(expand(first + second)).containsExactly("SELECT 1,'y'", "SELECT 1,'x'")
    }

    @Test fun distinctIteratorsStillResolveIndependently() {
        assertThat(expand(loop("i", "1", loop("j", "2", statement("SELECT {i},{j}")))))
            .containsExactly("SELECT 1,2")
    }

    @Test fun expansionIsRepeatableAndDoesNotRewriteTheInputAstOrProvenance() {
        val file = SltParser.parse("shadow.test", loop("i", "one", loop("i", "two", statement("SELECT '{i}'"))))
        val before = file.toString()
        repeat(2) {
            val records = SltExpander.expand(file, "duckdb")
            assertThat(records.map { it.sql }).containsExactly("SELECT 'one'")
            assertThat(records.single().file).isEqualTo("shadow.test")
            assertThat(records.single().line).isEqualTo(5)
            assertThat(file.toString()).isEqualTo(before)
        }
    }
}
