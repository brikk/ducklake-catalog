package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

internal class TestJdbcConversionFailures {
    private fun rows(type: String = "VARCHAR", value: () -> Any?): ResultSet {
        val first = AtomicBoolean(true)
        val metadata = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSetMetaData::class.java)) { _, method, _ ->
            when (method.name) { "getColumnCount" -> 1; "getColumnTypeName" -> type; else -> error("unexpected metadata ${method.name}") }
        } as ResultSetMetaData
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
            when (method.name) {
                "getMetaData" -> metadata
                "next" -> first.getAndSet(false)
                "getObject", "getString" -> value()
                else -> error("unexpected result method ${method.name}")
            }
        } as ResultSet
    }

    @Test fun getterFailuresAreNotValuesIncludingGeometryTextGetters() {
        for (type in listOf("VARCHAR", "GEOMETRY")) {
            val fault = SQLException("controlled getter failure")
            assertThatThrownBy { GoldenComparator.readRows(rows(type) { throw fault }) }
                .isInstanceOf(SQLException::class.java).hasCause(fault)
        }
    }

    @Test fun renderingFailuresAlsoPropagateRatherThanBecomingSentinels() {
        val bad = object { override fun toString(): String = error("controlled renderer failure") }
        assertThatThrownBy { GoldenComparator.readRows(rows { bad }) }
            .isInstanceOf(SQLException::class.java).hasMessageContaining("column 1")
    }

    @Test fun nativeUnrepresentableTimeCannotPassRegexOrRepeatedLabelGoldens() {
        val sql = "SELECT TIME '24:00:00'"
        val text = "query T\n$sql\n----\n<REGEX>:.*\n\n" +
            "query T nosort label\n$sql\n----\n\nquery T nosort label\n$sql\n----\n\n" +
            "query I nosort label\nSELECT 42\n----\n\n"
        val result = ReplayDriver().replay(SltParser.parse("unreadable.test", text))
        assertThat(result.failed).hasSize(3).allSatisfy { assertThat(it.reason).contains("query errored", "column 1") }
        assertThat(result.passed).isEqualTo(1) // failure neither poisons the label nor prevents later records
        assertThat(result.skipped).isEmpty()
    }

    @Test fun genuineNullAndSentinelLookingTextRemainOrdinaryValues() {
        assertThat(GoldenComparator.readRows(rows { null })).isEqualTo(listOf(listOf<String?>(null)))
        assertThat(GoldenComparator.readRows(rows { "<jdbc-unreadable>" })).isEqualTo(listOf(listOf("<jdbc-unreadable>")))
        val result = ReplayDriver().replay(SltParser.parse("literal.test",
            "query T\nSELECT '<jdbc-unreadable>'\n----\n<jdbc-unreadable>\n\n"))
        assertThat(result.failed).isEmpty()
        assertThat(result.passed).isEqualTo(1)
    }

    @Test fun aHashOfTheOldFailureSentinelCannotValidateAConversionFailure() {
        // Independently computed MD5("<jdbc-unreadable>\n"), not comparator-generated evidence.
        val result = ReplayDriver().replay(SltParser.parse("unreadable-hash.test",
            "query T\nSELECT TIME '24:00:00'\n----\n1 values hashing to 7514ae2713b9dfe08c70ad5c1dc5e259\n\n"))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("query errored", "column 1")
        assertThat(result.passed).isZero()
    }

    @Test fun conversionFailureNeverReachesTheMirrorOrItsAcceptanceGate() {
        var accepts = 0
        var queries = 0
        val engine = object : ReplayReadEngine {
            override val name = "conversion-control"
            override fun connect(attachment: OracleAttachment) = Unit
            override fun accepts(sql: String): Boolean { accepts++; return true }
            override fun executeQuery(sql: String): List<List<String?>> { queries++; return listOf(listOf("<jdbc-unreadable>")) }
            override fun close() = Unit
        }
        val text = "statement ok\nLOAD ducklake\n\nstatement ok\n" +
            "ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake (DATA_PATH '{TEST_DIR}/data', " +
            "DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)\n\n" +
            "query T\nSELECT TIME '24:00:00'\n----\n<REGEX>:.*\n\n"
        val result = ReplayDriver(engine).replay(SltParser.parse("conversion-mirror.test", text))
        assertThat(result.failed).hasSize(1)
        assertThat(result.queryCoverage!!.oraclePasses).isZero()
        assertThat(result.queryCoverage!!.mirrorAttempts).isZero()
        assertThat(accepts).isZero()
        assertThat(queries).isZero()
    }
}
