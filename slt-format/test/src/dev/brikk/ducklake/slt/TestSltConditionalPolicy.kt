package dev.brikk.ducklake.slt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** H11: upstream CheckLoopCondition applies skip/only policy to every term, not the aggregate. */
internal class TestSltConditionalPolicy {
    private fun statement(sql: String) = "statement ok\n$sql\n\n"
    private fun loop(name: String, values: String, body: String) = "foreach $name $values\n\n${body}endloop\n\n"
    private fun expand(text: String) = SltExpander.expand(SltParser.parse("condition-policy.test", text), "duckdb").map { it.sql }

    @Test fun contradictorySkipTermsStillSkipWhenEitherTermMatches() {
        val text = loop("i", "0 1", "skipif i=0&&i=1\n" + statement("SELECT \${i}"))
        assertThat(expand(text)).isEmpty()
    }

    @Test fun skipConjunctionUsesTheCompleteTwoVariableTruthTable() {
        val text = loop("i", "0 1", loop("j", "0 1", "skipif i=0&&j=0\n" + statement("SELECT \${i},\${j}")))
        assertThat(expand(text)).containsExactly("SELECT 1,1")
    }

    @Test fun threeSkipTermsRunOnlyWhenEveryTermIsFalse() {
        val body = "skipif i=0&&j=0&&k=0\n" + statement("SELECT \${i},\${j},\${k}")
        assertThat(expand(loop("i", "0 1", loop("j", "0 1", loop("k", "0 1", body)))))
            .containsExactly("SELECT 1,1,1")
    }

    @Test fun onlyConjunctionStillRequiresEveryTermToHold() {
        val text = loop("i", "0 1", loop("j", "0 1", "onlyif i=0&&j=0\n" + statement("SELECT \${i},\${j}")))
        assertThat(expand(text)).containsExactly("SELECT 0,0")
    }

    @Test fun strictNumericSkipTermsExcludeEitherSideOfTheInterval() {
        val text = loop("i", "-1 0 1 2", "skipif i<0&&i>1\n" + statement("SELECT \${i}"))
        assertThat(expand(text)).containsExactly("SELECT 0", "SELECT 1")
    }

    @Test fun inclusiveAndNotEqualSkipTermsApplyIndependently() {
        val text = loop("i", "-1 0 1 2", "skipif i<=-1&&i>=2&&i<>0\n" + statement("SELECT \${i}"))
        assertThat(expand(text)).containsExactly("SELECT 0")
    }

    @Test fun equalityStaysTextualRatherThanCoercingLeadingZeros() {
        val text = loop("i", "01 1 2", "skipif i=1&&i=2\n" + statement("SELECT '\${i}'"))
        assertThat(expand(text)).containsExactly("SELECT '01'")
    }

    @Test fun aGuardCanExcludeAWholeNestedLoop() {
        val body = "skipif i=0&&i=9\n" + loop("j", "0 1", statement("SELECT \${i},\${j}"))
        assertThat(expand(loop("i", "0 1", body))).containsExactly("SELECT 1,0", "SELECT 1,1")
    }

    @Test fun stackedOnlyAndSkipPoliciesKeepTheirOwnTermPolicies() {
        val body = "onlyif i>=0&&j>=0\nskipif i=0&&j=0\n" + statement("SELECT \${i},\${j}")
        assertThat(expand(loop("i", "0 1", loop("j", "0 1", body)))).containsExactly("SELECT 1,1")
    }

    @Test fun aBlockingTermDoesNotLookUpAnUnreachableLoopBinding() {
        val skipped = loop("i", "0", "skipif i=0&&missing=1\n" + statement("SELECT 1"))
        val only = loop("i", "0", "onlyif i=1&&missing=1\n" + statement("SELECT 1"))
        assertThat(expand(skipped + only)).isEmpty()
    }

    @Test fun aReachedMissingBindingStillFailsRatherThanBecomingFalse() {
        for (guard in listOf("skipif i=1&&missing=1", "onlyif i=0&&missing=1")) {
            assertThatThrownBy { expand(loop("i", "0", "$guard\n" + statement("SELECT 1"))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("loop iterator")
        }
    }

    @Test fun malformedTermSyntaxCannotHideBehindAnEarlierBlockingTerm() {
        for (guard in listOf("skipif i=0&&not_a_comparison", "onlyif i=1&&not_a_comparison")) {
            assertThatThrownBy { expand(loop("i", "0", "$guard\n" + statement("SELECT 1"))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("must be in the form")
        }
    }

    @Test fun existingEvaluateApiRetainsPositiveConjunctionSemantics() {
        val bindings = mapOf("i" to "0", "j" to "1", "s" to "01")
        assertThat(SltConditions.evaluate("i=0&&j=0", "duckdb", bindings)).isFalse()
        assertThat(SltConditions.evaluate("i=0&&j=1", "duckdb", bindings)).isTrue()
        assertThat(SltConditions.evaluate("s=1", "duckdb", bindings)).isFalse()
        assertThat(SltConditions.evaluate("s>0", "duckdb", bindings)).isTrue()
    }

    @Test fun systemGuardsKeepCaseInsensitiveSingleConditionSemantics() {
        val text = "onlyif DuCkDb\n" + statement("SELECT 1") +
            "skipif POSTGRES\n" + statement("SELECT 2") + "skipif DUCKDB\n" + statement("SELECT 3")
        assertThat(expand(text)).containsExactly("SELECT 1", "SELECT 2")
    }
}
