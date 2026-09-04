package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Driver semantics that upstream's runner defines and the corpus relies on — run against an
 * embedded DuckDB with no extension (nothing here needs `ducklake`), so these are cheap and
 * always run, unlike [TestIdentityControl].
 */
class TestReplayDriverSubstitution {

    private fun replay(slt: String): FileResult = ReplayDriver().replay(SltParser.parse("t.test", slt.trimIndent() + "\n"))

    private fun FileResult.failReasons(): List<String> = failed.map { it.reason }

    @Test
    fun `comma iterators bind each name to the matching part of the value`() {
        val result =
            replay(
                """
                foreach a,b 1,x 2,y

                statement ok
                CREATE TABLE t${'$'}{a} AS SELECT '${'$'}{b}' AS v

                endloop

                query T
                SELECT v FROM t1
                ----
                x

                query T
                SELECT v FROM t2
                ----
                y
                """,
            )
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failReasons()).isEmpty()
        assertThat(result.passed).isEqualTo(4)
    }

    @Test
    fun `env is substituted before loop bindings and loop values are env-substituted`() {
        val result =
            replay(
                """
                test-env FOO bar

                test-env i ENV

                foreach v ${'$'}{FOO}

                query T
                SELECT '${'$'}{v}'
                ----
                bar

                endloop

                loop i 0 1

                query T
                SELECT '${'$'}{i}'
                ----
                ENV

                endloop
                """,
            )
        assertThat(result.failReasons()).isEmpty()
        assertThat(result.passed).isEqualTo(2)
    }

    @Test
    fun `golden rows are env-substituted but never loop-substituted`() {
        val result =
            replay(
                """
                test-env FOO bar

                query T
                SELECT 'bar'
                ----
                ${'$'}{FOO}

                loop i 0 1

                query I
                SELECT ${'$'}{i}
                ----
                ${'$'}{i}

                endloop
                """,
            )
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.failReasons()).singleElement().asString().contains("expected [\${i}], got [0]")
    }

    @Test
    fun `statement error must fail - success is a failure even without an expectation`() {
        val result =
            replay(
                """
                statement error
                SELECT 1
                ----

                statement error
                SELECT 1
                ----
                anything
                """,
            )
        assertThat(result.failReasons()).hasSize(2).allSatisfy { assertThat(it).contains("but the statement succeeded") }
    }

    @Test
    fun `expected error text is env-substituted, not loop-substituted`() {
        val result =
            replay(
                """
                test-env TBL missing_tbl

                statement error
                SELECT * FROM missing_tbl
                ----
                Table with name ${'$'}{TBL} does not exist

                loop i 0 1

                statement error
                SELECT * FROM missing_tbl${'$'}{i}
                ----
                missing_tbl${'$'}{i}

                endloop
                """,
            )
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.failReasons()).singleElement().asString().contains("error mismatch: expected 'missing_tbl\${i}'")
    }

    @Test
    fun `statement maybe accepts success, and a failure only when the message matches`() {
        val result =
            replay(
                """
                statement maybe
                SELECT 1
                ----
                irrelevant

                statement maybe
                SELECT * FROM missing_tbl
                ----
                does not exist

                statement maybe
                SELECT * FROM missing_tbl
                ----
                <REGEX>:.*does not exist.*

                statement maybe
                SELECT * FROM missing_tbl
                ----
                some other message
                """,
            )
        assertThat(result.passed).isEqualTo(3)
        assertThat(result.failReasons()).singleElement().asString().contains("error mismatch")
    }

    @Test
    fun `internal errors are never an acceptable statement error`() {
        // Upstream `always_fail_error_messages` (sqllogic_test_runner.hpp:78).
        assertThat(ReplayDriver.ALWAYS_FAIL_ERROR_MESSAGES).containsExactlyInAnyOrder("INTERNAL", "differs from original result!")
    }

    @Test
    fun `labelled queries compare result hashes and ignore golden rows`() {
        val result =
            replay(
                """
                query I nosort lbl
                SELECT 1
                ----
                garbage that is ignored

                query I nosort lbl
                SELECT 1
                ----

                query I nosort lbl
                SELECT 2
                ----
                2

                query I nosort other
                SELECT 2
                ----
                1 values hashing to 00000000000000000000000000000000
                """,
            )
        assertThat(result.passed).isEqualTo(2)
        assertThat(result.failReasons()).hasSize(2)
        assertThat(result.failReasons()[0]).contains("labeled result 'lbl' diverged")
        // an explicit hash golden IS still checked on a labelled query
        assertThat(result.failReasons()[1]).contains("result hash: expected")
    }

    @Test
    fun `hash goldens are compared, not skipped`() {
        val result =
            replay(
                """
                query I rowsort
                SELECT * FROM (VALUES (2), (1)) t(x)
                ----
                2 values hashing to 6ddb4095eb719e2a9f0a3f95677d24e0
                """,
            )
        assertThat(result.failReasons()).isEmpty()
        assertThat(result.skipped).isEmpty()
        assertThat(result.passed).isEqualTo(1)
    }

    @Test
    fun `unmet require mid-file skips the remainder but keeps earlier outcomes`() {
        val result =
            replay(
                """
                statement ok
                SELECT 1

                query I
                SELECT 1
                ----
                2

                require some_unknown_extension

                statement ok
                SELECT * FROM never_executed
                """,
            )
        assertThat(result.fileSkipReason).contains("require some_unknown_extension")
        assertThat(result.haltedMidFile).isTrue()
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.failuresBeforeSkip).hasSize(1)
        assertThat(result.outcomes).hasSize(2)

        val report = CorpusReport(listOf(result, FileResult("clean.test", null, emptyList()), FileResult("skipped.test", "skip-list: x", emptyList())))
        assertThat(report.failures).hasSize(1)
        assertThat(report.haltedFiles).containsExactly(result)
        assertThat(report.ranFiles).hasSize(1)
        assertThat(report.skippedFiles).hasSize(2)
        assertThat(report.totalPassed).isEqualTo(1)
        assertThat(report.summary()).contains("1 failed").contains("halted mid-file by an unmet require 1")
    }

    @Test
    fun `require no_alternative_verify is present`() {
        DuckDbOracle().use { oracle ->
            assertThat(oracle.require("no_alternative_verify")).isNull()
            assertThat(oracle.require("notwindows")).isNull()
            assertThat(oracle.require("some_unknown_extension")).isNotNull()
        }
    }

    @Test
    fun `golden file rows are loaded from a pipe-separated csv with header`(@TempDir dir: Path) {
        val csv0 = dir.resolve("g0.csv")
        csv0.writeText("a|b\n1|x y\n|3\n")
        val result =
            replay(
                """
                loop i 0 1

                query IT
                SELECT * FROM (VALUES (1, 'x y'), (NULL, '3')) t(a, b) ORDER BY a NULLS LAST
                ----
                <FILE>:$dir/g${'$'}{i}.csv

                endloop

                query I
                SELECT 1
                ----
                <FILE>:$dir/does-not-exist.csv
                """,
            )
        assertThat(result.failReasons()).isEmpty()
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.skipped).singleElement().extracting { it.reason }.asString().contains("golden file not found")
    }

    @Test
    fun `golden file mismatch is a failure`(@TempDir dir: Path) {
        dir.resolve("g.csv").writeText("a\n2\n")
        val result =
            replay(
                """
                query I
                SELECT 1
                ----
                <FILE>:$dir/g.csv
                """,
            )
        assertThat(result.failReasons()).singleElement().asString().contains("expected [2], got [1]")
    }

    @Test
    fun `discovery includes test_slow files`(@TempDir dir: Path) {
        val root = dir.resolve("test/sql").createDirectories()
        root.resolve("a.test").writeText("")
        root.resolve("b.test_slow").writeText("")
        root.resolve("c.txt").writeText("")
        Files.createDirectories(root.resolve("sub")).resolve("d.test_slow").writeText("")
        val runner = CorpusRunner(root)
        assertThat(runner.discover().map { it.fileName.toString() }).containsExactly("a.test", "b.test_slow", "d.test_slow")
        assertThat(runner.discover("sub").map { it.fileName.toString() }).containsExactly("d.test_slow")
    }
}
