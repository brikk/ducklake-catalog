package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SortMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Pure comparator tests. Every expected nested rendering below was produced by DuckDB 1.5
 * (`SELECT x::VARCHAR`) so the rules of `nested_to_varchar_cast.cpp` / `vector_cast_helpers.hpp`
 * are checked against the real thing, not against a reading of the source.
 */
class TestGoldenComparator {

    private fun query(
        types: String,
        expected: List<String>,
        sort: SortMode = SortMode.NOSORT,
        label: String? = null,
    ) = SltQuery(1, "SELECT 1", types, sort, null, label, expected)

    @Test
    fun `nested strings are quoted only when duckdb quotes them`() {
        val values =
            listOf(
                "a b", " a", "a ", "", "null", "NULL", "a,b", "it's", "back\\slash", "x:y", "x=y", "x(y)",
                "tab\there", "with\"dq", " ",
            )
        assertThat(GoldenComparator.renderCell(values))
            .isEqualTo(
                "[a b, ' a', 'a ', '', 'null', 'NULL', 'a,b', 'it\\'s', back\\slash, 'x:y', 'x=y', 'x(y)', " +
                    "tab\there, 'with\"dq', ' ']",
            )
    }

    @Test
    fun `nested quoting edge cases`() {
        // trailing whitespace counts only from two characters on; a lone space is a leading space
        assertThat(GoldenComparator.renderCell(listOf(" a", "a ", " ", "  "))).isEqualTo("[' a', 'a ', ' ', '  ']")
        // backslash and quote are escaped with a backslash inside quotes, bare otherwise
        assertThat(GoldenComparator.renderCell(listOf("\\"))).isEqualTo("[\\]")
        assertThat(GoldenComparator.renderCell(listOf("\\'"))).isEqualTo("['\\\\\\'']")
        // all of CharacterIsSpace: leading VT / trailing CR force quotes; an interior LF does not
        assertThat(GoldenComparator.renderCell(listOf("\u000Bx"))).isEqualTo("['\u000Bx']")
        assertThat(GoldenComparator.renderCell(listOf("x\r"))).isEqualTo("['x\r']")
        assertThat(GoldenComparator.renderCell(listOf("a\nb"))).isEqualTo("[a\nb]")
        // NUL is not special upstream; it is escaped as \0 after quoting is decided
        assertThat(GoldenComparator.renderCell(listOf("a\u0000b"))).isEqualTo("[a\\0b]")
        assertThat(GoldenComparator.renderCell(listOf("a,\u0000"))).isEqualTo("['a,\\0']")
        // NULL-lookalikes of any case are quoted; a real NULL is bare
        assertThat(GoldenComparator.renderCell(listOf(null, "NULL", "Null"))).isEqualTo("[NULL, 'NULL', 'Null']")
    }

    @Test
    fun `non-string nested children are rendered to text first and then quoted by the same rule`() {
        val ts = LocalDateTime.of(2020, 1, 1, 0, 0)
        assertThat(GoldenComparator.renderCell(listOf(ts, LocalDateTime.of(2020, 1, 1, 12, 0))))
            .isEqualTo("['2020-01-01 00:00:00', '2020-01-01 12:00:00']")
        assertThat(GoldenComparator.renderCell(listOf(LocalTime.of(12, 0)))).isEqualTo("['12:00:00']")
        assertThat(GoldenComparator.renderCell(listOf(LocalDate.of(2020, 1, 1)))).isEqualTo("[2020-01-01]")
        assertThat(GoldenComparator.renderCell(listOf(1.5e20, 0.1, 12345678.0, Double.NaN)))
            .isEqualTo("[1.5e+20, 0.1, 12345678.0, nan]")
        assertThat(GoldenComparator.renderCell(listOf(true, null))).isEqualTo("[true, NULL]")
        assertThat(GoldenComparator.renderCell(listOf(java.math.BigDecimal("1.50")))).isEqualTo("[1.50]")
        // nested children are written as-is (their own cast already applied the rules)
        assertThat(GoldenComparator.renderCell(listOf(listOf(1, 2), listOf("a b")))).isEqualTo("[[1, 2], [a b]]")
        // MAP: key=value, both escaped
        assertThat(GoldenComparator.renderCell(linkedMapOf(1 to "x y", 2 to "z,"))).isEqualTo("{1=x y, 2='z,'}")
        assertThat(GoldenComparator.renderCell(linkedMapOf("a b" to "c d"))).isEqualTo("{a b=c d}")
    }

    @Test
    fun `scalar doubles and floats use duckdb text`() {
        assertThat(GoldenComparator.renderCell(12345678.0)).isEqualTo("12345678.0")
        assertThat(GoldenComparator.renderCell(1e-4)).isEqualTo("0.0001")
        assertThat(GoldenComparator.renderCell(1e20)).isEqualTo("1e+20")
        assertThat(GoldenComparator.renderCell(-0.0)).isEqualTo("-0.0")
        assertThat(GoldenComparator.renderCell(0.1f)).isEqualTo("0.1")
        assertThat(GoldenComparator.renderCell(1e10f)).isEqualTo("10000000000.0")
    }

    @Test
    fun `timestamp and date infinities render as duckdb text`() {
        val inf = DuckDbText.TIMESTAMP_INFINITY
        assertThat(GoldenComparator.renderCell(Timestamp.valueOf(inf))).isEqualTo("infinity")
        assertThat(GoldenComparator.renderCell(inf)).isEqualTo("infinity")
        assertThat(GoldenComparator.renderCell(DuckDbText.TIMESTAMP_NEGATIVE_INFINITY)).isEqualTo("-infinity")
        // TIMESTAMPTZ infinity carries no "+00" suffix
        assertThat(GoldenComparator.renderCell(OffsetDateTime.of(inf, ZoneOffset.UTC))).isEqualTo("infinity")
        assertThat(GoldenComparator.renderCell(OffsetDateTime.of(LocalDateTime.of(2020, 1, 1, 0, 0, 0, 500_000_000), ZoneOffset.UTC)))
            .isEqualTo("2020-01-01 00:00:00.5+00")
        assertThat(GoldenComparator.renderCell(DuckDbText.DATE_INFINITY)).isEqualTo("infinity")
        assertThat(GoldenComparator.renderCell(DuckDbText.DATE_NEGATIVE_INFINITY)).isEqualTo("-infinity")
        // and nested: a bare word, so no quotes
        assertThat(GoldenComparator.renderCell(listOf(inf))).isEqualTo("[infinity]")
    }

    @Test
    fun `booleans accept true false and 1 0 for any type letter`() {
        val actual = listOf(listOf<String?>("true", "false"))
        for (types in listOf("II", "TT", "RR", "IT")) {
            assertThat(GoldenComparator.compare(query(types, listOf("1\t0")), actual)).isEqualTo(GoldenComparator.Comparison.Match)
            assertThat(GoldenComparator.compare(query(types, listOf("true\tfalse")), actual)).isEqualTo(GoldenComparator.Comparison.Match)
            assertThat(GoldenComparator.compare(query(types, listOf("True\tFALSE")), actual)).isEqualTo(GoldenComparator.Comparison.Match)
            assertThat(GoldenComparator.compare(query(types, listOf("0\t1")), actual)).isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
            assertThat(GoldenComparator.compare(query(types, listOf("1.0\t0")), actual)).isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
        }
        // plain integers are still numeric: "1" vs "1.0" under I is a match, as before
        assertThat(GoldenComparator.compare(query("I", listOf("1.0")), listOf(listOf("1")))).isEqualTo(GoldenComparator.Comparison.Match)
    }

    @Test
    fun `expected cells are verbatim - leading whitespace is significant`() {
        assertThat(GoldenComparator.compare(query("T", listOf(" x")), listOf(listOf(" x")))).isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(GoldenComparator.compare(query("T", listOf(" x")), listOf(listOf("x")))).isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
        assertThat(GoldenComparator.compare(query("T", listOf("x")), listOf(listOf(" x")))).isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
    }

    @Test
    fun `null and empty golden forms`() {
        val actual = listOf(listOf<String?>(null, ""))
        assertThat(GoldenComparator.compare(query("TT", listOf("NULL\t(empty)")), actual)).isEqualTo(GoldenComparator.Comparison.Match)
    }

    @Test
    fun `hash goldens are compared by md5 of the sorted cells`() {
        // md5("1\n2\n")
        val hash = "2 values hashing to 6ddb4095eb719e2a9f0a3f95677d24e0"
        val q = query("I", listOf(hash), SortMode.ROWSORT)
        assertThat(GoldenComparator.isHashGolden(q)).isTrue()
        assertThat(GoldenComparator.compare(q, listOf(listOf("2"), listOf("1")))).isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(GoldenComparator.compare(query("I", listOf(hash)), listOf(listOf("2"), listOf("1"))))
            .isInstanceOf(GoldenComparator.Comparison.Mismatch::class.java)
        // NULL / (empty) canonical forms feed the hash: md5("x\nNULL\n(empty)\n")
        assertThat(GoldenComparator.resultHash(query("T", emptyList()), listOf(listOf("x"), listOf(null), listOf(""))))
            .isEqualTo("3 values hashing to 7d44db9431eaf5372b7e3099401f72ef")
    }

    @Test
    fun `result hash honours the query sort style`() {
        val rows = listOf(listOf<String?>("b", "2"), listOf("a", "1"))
        val nosort = GoldenComparator.resultHash(query("TI", emptyList()), rows)
        val rowsort = GoldenComparator.resultHash(query("TI", emptyList(), SortMode.ROWSORT), rows)
        val valuesort = GoldenComparator.resultHash(query("TI", emptyList(), SortMode.VALUESORT), rows)
        assertThat(nosort).isNotEqualTo(rowsort)
        assertThat(rowsort).isNotEqualTo(valuesort)
        assertThat(GoldenComparator.resultHash(query("TI", emptyList(), SortMode.ROWSORT), rows.reversed())).isEqualTo(rowsort)
    }

    @Test
    fun `regex cells`() {
        assertThat(GoldenComparator.compare(query("T", listOf("<REGEX>:.*Files Read: 1.*")), listOf(listOf("x\nTotal Files Read: 1\ny"))))
            .isEqualTo(GoldenComparator.Comparison.Match)
        assertThat(GoldenComparator.compare(query("T", listOf("<!REGEX>:.*Files Read: 1.*")), listOf(listOf("Total Files Read: 2"))))
            .isEqualTo(GoldenComparator.Comparison.Match)
    }
}
