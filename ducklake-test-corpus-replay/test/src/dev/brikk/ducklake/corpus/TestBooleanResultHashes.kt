package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/** N19 expected MD5s are independently calculated from native SQLLogicTest cell text plus LF. */
internal class TestBooleanResultHashes {
    private fun query(sql: String, hash: String = "", types: String = "I", sort: String = "nosort", label: String? = null): String =
        "query $types $sort${label?.let { " $it" } ?: ""}\n$sql\n----\n$hash\n\n"
    private fun replay(text: String): FileResult = ReplayDriver().replay(SltParser.parse("boolean-hash.test", text)).also {
        assertThat(it.fileSkipReason).isNull()
        assertThat(it.skipped).isEmpty()
    }
    private fun clean(text: String, queries: Int = 1) {
        val result = replay(text)
        assertThat(result.failed).isEmpty()
        assertThat(result.passed).isEqualTo(queries)
    }

    @Test fun scalarBooleanHashUsesNativeOneForEveryQueryTypeLetter() {
        val text = listOf("I", "R", "T").joinToString("") {
            query("SELECT TRUE", "1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1", types = it)
        }
        clean(text, 3)
    }

    @Test fun falseAndNullKeepNativeZeroAndNullTokens() {
        clean(query("SELECT b FROM (VALUES(0,TRUE),(1,FALSE),(2,NULL::BOOLEAN))r(i,b) ORDER BY i",
            "3 values hashing to 184e6f979a6b9d76049c9b0ff19e1068"))
    }

    private fun mixed(sort: String, digest: String) {
        clean(query("SELECT b,t FROM (VALUES(0,TRUE,'9'),(1,FALSE,'true'),(2,NULL::BOOLEAN,'false'))r(i,b,t) ORDER BY i",
            "6 values hashing to $digest", types = "IT", sort = sort))
    }

    @Test fun mixedColumnsNormalizeOnlyBooleansWithoutSorting() = mixed("nosort", "5efb26c1b2452e75ae993e36f7931249")
    @Test fun mixedColumnsNormalizeBeforeRowSorting() = mixed("rowsort", "3caa583fb6e97db58d6e459e0be3ba32")
    @Test fun mixedColumnsNormalizeBeforeValueSorting() = mixed("valuesort", "6d544ba3f4e91363e4c718e42dd827a8")

    @Test fun repeatedLabelsUseTheSameNativeBooleanAndIntegerCellText() {
        clean(query("SELECT TRUE", label = "native") + query("SELECT 1", label = "native"), 2)
    }

    @Test fun repeatedLabelsDoNotConfuseVarcharTrueWithBooleanTrue() {
        val result = replay(query("SELECT TRUE", label = "native") + query("SELECT 'true'", label = "native"))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("diverged")
        assertThat(result.passed).isEqualTo(1)
    }

    @Test fun theOldJavaBooleanSpellingHashCannotQualifyANativeBooleanResult() {
        val result = replay(query("SELECT TRUE", "1 values hashing to 74d9a83219cabaab06a69fd318873f33"))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("result hash")
    }

    @Test fun varcharTrueAndFalseRemainLiteralTextWithTheirOwnHashes() {
        clean(query("SELECT 'true'", "1 values hashing to 74d9a83219cabaab06a69fd318873f33", "T") +
            query("SELECT 'false'", "1 values hashing to d42f2da1df5ecdf29be4ac27edda0c12", "T"), 2)
    }

    @Test fun nestedBooleansRemainNativeNestedTextNotScalarOneZero() {
        clean(query("SELECT [TRUE,FALSE,NULL::BOOLEAN]", "1 values hashing to 44b073742af764fb12c25a5e85561556", "T"))
    }

    @Test fun anEmptyBooleanResultRetainsTheZeroValueHash() {
        clean(query("SELECT TRUE WHERE FALSE", "0 values hashing to d41d8cd98f00b204e9800998ecf8427e"))
    }

    @Test fun rawRendererAndLiveRowRepresentationsStayUnchanged() {
        assertThat(GoldenComparator.renderCell(true)).isEqualTo("true")
        assertThat(GoldenComparator.renderCell(false)).isEqualTo("false")
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT b FROM (VALUES(0,TRUE),(1,FALSE),(2,NULL::BOOLEAN))r(i,b) ORDER BY i").use { rows ->
                assertThat(GoldenComparator.readRows(rows)).containsExactly(listOf("true"), listOf("false"), listOf(null))
            }
        } }
    }

    @Test fun labelledHashGoldensRetainActualMultiColumnWidthAndTypedSorting() {
        val sql = "SELECT b,t FROM (VALUES(0,TRUE,'9'),(1,FALSE,'true'),(2,NULL::BOOLEAN,'false'))r(i,b,t)"
        val hash = "6 values hashing to 3caa583fb6e97db58d6e459e0be3ba32"
        clean(query("$sql ORDER BY i", hash, "I", "rowsort", "typed") +
            query("$sql ORDER BY i DESC", hash, "I", "rowsort", "typed"), 2)
    }

    @Test fun hashFailuresDoNotBecomeMirrorCoverageAndLaterValidRowsStayRaw() {
        val calls = mutableListOf<String>()
        val engine = object : ReplayReadEngine {
            override val name = "boolean-hash-control"
            override fun connect(attachment: OracleAttachment) = Unit
            override fun executeQuery(sql: String): List<List<String?>> { calls += sql; return listOf(listOf("true")) }
            override fun close() = Unit
        }
        val prefix = "statement ok\nLOAD ducklake\n\nstatement ok\n" +
            "ATTACH 'ducklake:{TEST_DIR}/boolean.db' AS lake (DATA_PATH '{TEST_DIR}/data',DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)\n\n"
        val text = prefix + query("SELECT TRUE /* wrong-java-hash */", "1 values hashing to 74d9a83219cabaab06a69fd318873f33") +
            query("SELECT TRUE /* native-hash */", "1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1")
        val result = ReplayDriver(engine).replay(SltParser.parse("boolean-mirror.test", text))
        assertThat(result.failed).hasSize(1)
        assertThat(calls).containsExactly("SELECT TRUE /* native-hash */")
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorAttempts).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(1)
    }
}
