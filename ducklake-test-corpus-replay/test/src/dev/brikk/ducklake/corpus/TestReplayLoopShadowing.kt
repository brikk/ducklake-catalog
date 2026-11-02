package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** H13: source SQL, conditional lookup, golden paths, effects and mirror coverage use the right scopes. */
internal class TestReplayLoopShadowing {
    private fun statement(sql: String) = "statement ok\n$sql\n\n"
    private fun query(sql: String, expected: String, types: String = "I") = "query $types\n$sql\n----\n$expected\n\n"
    private fun loop(name: String, values: String, body: String) = "foreach $name $values\n\n${body}endloop\n\n"
    private fun replay(text: String) = ReplayDriver().replay(SltParser.parse("shadow.test", text)).also {
        assertThat(it.fileSkipReason).isNull()
        assertThat(it.failed).isEmpty()
    }

    @Test fun numericShadowingInsertsExactlyTheOuterValuesWithoutLosingIterations() {
        val text = statement("CREATE TABLE audit(i INTEGER)") + "loop i 1 3\n\nloop i 10 12\n\n" +
            statement("INSERT INTO audit VALUES({i})") + "endloop\n\nendloop\n\n" +
            query("SELECT count(*),sum(i) FROM audit", "4\t6", "II")
        assertThat(replay(text).passed).isEqualTo(6)
    }

    @Test fun foreachTextUsesTheOuterValueInBothPlaceholderForms() {
        val body = query("SELECT '\${i}','{i}'", "outside\toutside", "TT")
        assertThat(replay(loop("i", "outside", loop("i", "inside", body))).passed).isEqualTo(1)
    }

    @Test fun innermostConditionsAndOuterTextRemainDifferentViews() {
        val body = "onlyif i=2&&i<>1\n" + query("SELECT {i}", "1") +
            "skipif i=1&&i=2\n" + query("SELECT 999", "999")
        val result = replay(loop("i", "1", loop("i", "2", body)))
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.skipped).hasSize(1)
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(1)
    }

    @Test fun innerNumericConditionDoesNotParseTheOuterTokenAsANumber() {
        val body = "onlyif i>0\n" + query("SELECT '{i}'", "outside", "T")
        assertThat(replay(loop("i", "outside", loop("i", "1", body))).passed).isEqualTo(1)
    }

    @Test fun goldenFilePathsAreSubstitutedInFrameOrder(@TempDir root: Path) {
        Files.writeString(root.resolve("outer.csv"), "value\n7\n")
        Files.writeString(root.resolve("inner.csv"), "value\n99\n")
        val body = query("SELECT 7", "<FILE>:$root/{i}.csv")
        assertThat(replay(loop("i", "outer", loop("i", "inner", body))).passed).isEqualTo(1)
    }

    @Test fun leavingAnInnerFrameDoesNotLeakItIntoLaterStatementsOrLoops() {
        val body = loop("i", "inner", statement("INSERT INTO audit VALUES('{i}')")) + statement("INSERT INTO audit VALUES('after:{i}')")
        val text = statement("CREATE TABLE audit(v VARCHAR)") + loop("i", "outer", body) +
            loop("i", "later", statement("INSERT INTO audit VALUES('{i}')")) +
            query("SELECT v FROM audit ORDER BY rowid", "outer\nafter:outer\nlater", "T")
        assertThat(replay(text).passed).isEqualTo(5)
    }

    @Test fun freshPlaceholdersRequireBothFramesRatherThanAFirstOrLastWinsMap() {
        val text = statement("CREATE TABLE audit(v VARCHAR)") +
            loop("i", "{i}-outer", loop("i", "inner", statement("INSERT INTO audit VALUES('{i}')"))) +
            query("SELECT v FROM audit", "inner-outer", "T")
        assertThat(replay(text).passed).isEqualTo(3)
    }

    @Test fun mirrorReceivesOuterSqlOnlyAfterTheInnerConditionAllowsIt() {
        val calls = mutableListOf<String>()
        val engine = object : ReplayReadEngine {
            override val name = "shadow-control"
            override fun connect(attachment: OracleAttachment) = Unit
            override fun executeQuery(sql: String): List<List<String?>> { calls += sql; return listOf(listOf("outside")) }
            override fun close() = Unit
        }
        val setup = statement("LOAD ducklake") + statement("ATTACH 'ducklake:{TEST_DIR}/meta.db' AS lake " +
            "(DATA_PATH '{TEST_DIR}/data',DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)")
        val body = "onlyif i=inside\n" + query("SELECT '{i}'", "outside", "T")
        val result = ReplayDriver(engine).replay(SltParser.parse("shadow-mirror.test", setup + loop("i", "outside", loop("i", "inside", body))))
        assertThat(result.failed).isEmpty()
        assertThat(calls).containsExactly("SELECT 'outside'")
        assertThat(result.queryCoverage!!.oraclePasses).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorAttempts).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(1)
    }

    @Test fun expectedErrorTextRemainsEnvironmentOnlyNotLoopSubstituted() {
        val body = "statement error\nSELECT * FROM missing_{i}\n----\nmissing_\${i}\n\n"
        val result = ReplayDriver().replay(SltParser.parse("shadow-error.test", loop("i", "1", loop("i", "2", body))))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).singleElement().extracting { it.reason }.asString()
            .contains("error mismatch", "missing_\${i}", "missing_1")
        assertThat(result.passed).isZero()
    }

    @Test fun environmentPrecedenceOverLoopNamesStaysUnchanged() {
        val body = "onlyif i=two\n" + query("SELECT '{i}'", "env", "T")
        val text = "test-env i env\n\n" + loop("i", "one", loop("i", "two", body))
        assertThat(replay(text).passed).isEqualTo(1)
    }

    @Test fun ordinaryGoldenRowsAreStillNotLoopSubstituted() {
        val body = query("SELECT {i}", "\${i}")
        val result = ReplayDriver().replay(SltParser.parse("shadow-golden.test", loop("i", "1", loop("i", "2", body))))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("expected [\${i}], got [1]")
        assertThat(result.passed).isZero()
    }
}
