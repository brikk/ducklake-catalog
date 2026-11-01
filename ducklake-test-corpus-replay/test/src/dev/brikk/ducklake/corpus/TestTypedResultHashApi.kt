package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SortMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class TestTypedResultHashApi {
    private fun query() = SltQuery(1, "SELECT TRUE", "I", SortMode.NOSORT, null, null, emptyList())

    @Test fun onlyScalarBooleanMetadataSelectsNativeOneZeroNormalization() {
        for (type in listOf("BOOLEAN", "BOOL", " boolean ", "bool(1)")) {
            assertThat(GoldenComparator.resultHash(query(), listOf(listOf("TRUE")), listOf(type)))
                .isEqualTo("1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1")
            assertThat(GoldenComparator.resultHash(query(), listOf(listOf("false")), listOf(type)))
                .isEqualTo("1 values hashing to 897316929176464ebc9ad085f31e7284")
        }
        for (type in listOf("VARCHAR", "VARCHAR(4)", "BOOLEAN[]", "STRUCT(b BOOLEAN)", "MAP(VARCHAR,BOOLEAN)", "ENUM('true','false')", "BIT")) {
            assertThat(GoldenComparator.resultHash(query(), listOf(listOf("true")), listOf(type)))
                .isEqualTo("1 values hashing to 74d9a83219cabaab06a69fd318873f33")
        }
    }

    @Test fun theLegacyUntypedDescriptorAndRawTextSemanticsRemainAvailable() {
        assertThat(GoldenComparator::class.java.getMethod("resultHash", SltQuery::class.java, List::class.java)).isNotNull()
        assertThat(GoldenComparator.resultHash(query(), listOf(listOf("true"))))
            .isEqualTo("1 values hashing to 74d9a83219cabaab06a69fd318873f33")
        assertThat(GoldenComparator.resultHash(query(), listOf(listOf("true")), listOf("BOOLEAN")))
            .isEqualTo("1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1")
    }

    @Test fun hashingDoesNotMutateRawRowsOrRewriteVarcharLookalikes() {
        val original = mutableListOf(mutableListOf<String?>("TRUE", "true"), mutableListOf("0", "false"), mutableListOf(null, ""))
        val before = original.map { it.toList() }
        val expectedCells = listOf(listOf("1", "true"), listOf("0", "false"), listOf(null, ""))
        assertThat(GoldenComparator.resultHash(query(), original, listOf("BOOLEAN", "VARCHAR")))
            .isEqualTo(GoldenComparator.resultHash(query(), expectedCells))
        assertThat(original).isEqualTo(before)
    }

    @Test fun invalidWidthOrBooleanPayloadCannotBeHashedIntoAnApparentSuccess() {
        assertThatThrownBy { GoldenComparator.resultHash(query(), listOf(listOf("true", "false")), listOf("BOOLEAN")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("width")
        for (value in listOf("NULL", "", "1.0", " true ", "garbage")) {
            assertThatThrownBy { GoldenComparator.resultHash(query(), listOf(listOf(value)), listOf("BOOLEAN")) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("BOOLEAN")
            val expected = query().copy(expected = listOf("1 values hashing to 74d9a83219cabaab06a69fd318873f33"))
            assertThat(GoldenComparator.compare(expected, listOf(listOf(value)), listOf("BOOLEAN")))
                .isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
        }
    }
}
