package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SortMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TestGoldenComparisonBoundaries {
    private fun query(types: String, expected: List<String>, sort: SortMode = SortMode.NOSORT) =
        SltQuery(1, "SELECT 1", types, sort, null, null, expected)

    @Test fun untypedCompatibilityEntryPointDoesNotGuessBooleanOrRoundIntegers() {
        val wrong = GoldenComparator.Comparison.Mismatch::class.java
        assertThat(GoldenComparator.compare(query("T", listOf("1")), listOf(listOf("true")))).isInstanceOf(wrong)
        assertThat(GoldenComparator.compare(query("I", listOf("9007199254740992")), listOf(listOf("9007199254740993"))))
            .isInstanceOf(wrong)
        assertThat(GoldenComparator.compare(query("I", listOf("1.0")), listOf(listOf("1"))))
            .isEqualTo(GoldenComparator.Comparison.Match)
    }

    @Test fun valuesortRetainsEachCellsOriginalSqlType() {
        // Lexical sorting moves the DOUBLE ahead of the BIGINT. Its tolerance must not move too.
        val rows = listOf(listOf<String?>("9007199254740993", "1.0000000001"))
        val q = query("IR", listOf("1.0\t9007199254740993"), SortMode.VALUESORT)
        assertThat(GoldenComparator.compare(q, rows, listOf("BIGINT", "DOUBLE"))).isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(GoldenComparator.compare(q.copy(expected = listOf("1.0\t9007199254740992")), rows, listOf("BIGINT", "DOUBLE")))
            .isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
    }

    @Test fun everyActualRowAndExpectedRowMustHaveTheDeclaredWidth() {
        val wrong = GoldenComparator.Comparison.Mismatch::class.java
        val q = query("II", listOf("1\t2", "3\t4"), SortMode.VALUESORT)
        assertThat(GoldenComparator.compare(q, listOf(listOf("1"), listOf("2", "3", "4")), listOf("INTEGER", "INTEGER")))
            .isInstanceOf(wrong)
        assertThat(GoldenComparator.compare(q.copy(expected = listOf("1", "2\t3\t4")),
            listOf(listOf("1", "2"), listOf("3", "4")), listOf("INTEGER", "INTEGER"))).isInstanceOf(wrong)
    }

    @Test fun rowsortUsesCellBoundariesEvenWhenDelimiterSerializationsCollide() {
        val rows = listOf(listOf<String?>("a\u0001b", "c"), listOf("a", "b\u0001c"))
        val q = query("TT", listOf("a\tb\u0001c", "a\u0001b\tc"), SortMode.ROWSORT)
        assertThat(GoldenComparator.compare(q, rows, listOf("VARCHAR", "VARCHAR"))).isEqualTo(GoldenComparator.Comparison.Match)
    }

    @Test fun typedEmptyResultsAndEmptyHashesStillCheckMetadataWidth() {
        val q = query("I", emptyList())
        assertThat(GoldenComparator.compare(q, emptyList(), listOf("BIGINT"))).isEqualTo(GoldenComparator.Comparison.Match)
        for (expected in listOf(emptyList(), listOf("0 values hashing to d41d8cd98f00b204e9800998ecf8427e"))) {
            assertThat(GoldenComparator.compare(q.copy(expected = expected), emptyList(), listOf("BIGINT", "BIGINT")))
                .isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
        }
    }
}
