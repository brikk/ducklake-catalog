package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** H03: use real oracle results and independently supplied, deliberately wrong mirror rows. */
internal class TestMirrorComparisonIntegrity {
    private fun compare(sql: String, types: String, golden: String, rows: List<List<String?>>): FileResult {
        val engine = object : ReplayReadEngine {
            override val name = "structured-control"
            var calls = 0
            override fun connect(attachment: OracleAttachment) = Unit
            override fun executeQuery(sql: String): List<List<String?>> { calls++; return rows }
            override fun close() = Unit
        }
        val text = "statement ok\nLOAD ducklake\n\nstatement ok\n" +
            "ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake (DATA_PATH '{TEST_DIR}/data', " +
            "DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)\n\nquery $types\n$sql\n----\n$golden\n\n"
        val result = ReplayDriver(engine).replay(SltParser.parse("mirror.test", text))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.skipped).isEmpty()
        assertThat(engine.calls).isEqualTo(1)
        return result
    }

    @Test fun nullAndEmptyAreNotGoldenTokensInLiveComparisons() {
        for ((sql, golden, wrong) in listOf(
            Triple("SELECT NULL::VARCHAR", "NULL", "NULL"),
            Triple("SELECT ''", "(empty)", "(empty)"),
            Triple("SELECT 'NULL'", "NULL", null),
            Triple("SELECT '(empty)'", "(empty)", ""),
        )) {
            assertThat(compare(sql, "T", golden, listOf(listOf(wrong))).failed).hasSize(1)
        }
        assertThat(compare("SELECT NULL::VARCHAR,''", "TT", "NULL\t(empty)", listOf(listOf(null, ""))).failed).isEmpty()
    }

    @Test fun separatorBytesNeverReplaceColumnBoundaries() {
        assertThat(compare("SELECT 'a','b'||chr(1)||'c'", "TT", "a\tb\u0001c",
            listOf(listOf("a\u0001b", "c"))).failed).hasSize(1)
        assertThat(compare("SELECT 'a','b'", "TT", "a\tb", listOf(listOf("a\u0001b"))).failed).hasSize(1)
    }

    @Test fun liveComparisonIsAnUnorderedMultisetNotASetOrAnOrderedList() {
        val sql = "SELECT * FROM (VALUES ('a'),('a'),('b')) t(x)"
        assertThat(compare(sql, "T", "a\na\nb", listOf(listOf("b"), listOf("a"), listOf("a"))).failed).isEmpty()
        assertThat(compare(sql, "T", "a\na\nb", listOf(listOf("b"), listOf("b"), listOf("a"))).failed).hasSize(1)
        assertThat(compare(sql, "T", "a\na\nb", listOf(listOf("a"), listOf("b"))).failed).hasSize(1)
    }

    @Test fun rawNullAndSeparatorRowsCanBeReorderedWithoutLoss() {
        val sql = "SELECT * FROM (VALUES (NULL,''),('NULL','(empty)'),('a',chr(1))) t(a,b)"
        val rows = listOf(listOf<String?>("a", "\u0001"), listOf("NULL", "(empty)"), listOf(null, ""))
        assertThat(compare(sql, "TT", "NULL\t(empty)\nNULL\t(empty)\na\t\u0001", rows).failed).isEmpty()
    }
}
