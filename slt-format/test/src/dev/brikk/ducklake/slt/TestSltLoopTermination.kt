package dev.brikk.ducklake.slt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** H12: EOF is never an implicit endloop, including when the loop would execute zero times. */
internal class TestSltLoopTermination {
    private fun statement(sql: String) = "statement ok\n$sql\n\n"
    private fun parse(text: String) = SltParser.parse("termination.test", text)
    private fun flattened(records: List<SltRecord>): List<SltRecord> = records.flatMap { record ->
        listOf(record) + when (record) {
            is SltLoop -> flattened(record.body)
            is SltConditional -> flattened(listOf(record.record))
            else -> emptyList()
        }
    }
    private fun refused(text: String, directive: String = "loop", line: Int = 1) {
        val file = parse(text)
        val records = flattened(file.records)
        assertThat(records).noneMatch { it is SltLoop }
        val unsupported = records.filterIsInstance<SltUnsupported>()
        assertThat(unsupported).hasSize(1)
        assertThat(unsupported.single().directive).isEqualTo(directive)
        assertThat(unsupported.single().line).isEqualTo(line)
        assertThat(unsupported.single().raw).contains("Missing endloop")
        assertThat(SltExpander.expand(file, "duckdb")).isEmpty()
    }

    @Test fun aNumericLoopCannotBeTerminatedByEof() = refused("loop i 0 1\n\n" + statement("SELECT 1"))
    @Test fun aForeachCannotBeTerminatedByEof() = refused("foreach i one\n\n" + statement("SELECT 1"), "foreach")
    @Test fun anEmptyLoopBodyStillNeedsItsClosingDirective() = refused("loop i 0 1")
    @Test fun anEmptyForeachBodyStillNeedsItsClosingDirective() = refused("foreach i one", "foreach")
    @Test fun zeroIterationsDoNotMakeAnUnclosedLoopValid() = refused("loop i 1 1\n\n" + statement("SELECT 1"))

    @Test fun aClosedInnerLoopDoesNotAlsoCloseItsOuterLoop() {
        refused("loop i 0 1\n\nloop j 0 1\n\n" + statement("SELECT 1") + "endloop\n")
    }

    @Test fun twoUnclosedLoopBodiesCannotLeakRunnableCommands() {
        refused("loop i 0 1\n\nforeach j one\n\n" + statement("SELECT 1"))
    }

    @Test fun trailingBlanksCommentsAndCrLfDoNotSupplyAClosingDirective() {
        for (suffix in listOf("", "\n\n", "# trailing comment\n\n")) {
            refused("loop i 0 1\n\n" + statement("SELECT 1") + suffix)
        }
        refused(("foreach i one\n\n" + statement("SELECT 1")).replace("\n", "\r\n"), "foreach")
    }

    @Test fun aCommentedOutEndloopIsNotATerminator() {
        refused("loop i 0 1\n\n" + statement("SELECT 1") + "# endloop\n")
    }

    @Test fun anExpectedResultRowNamedEndloopIsNotATerminator() {
        refused("loop i 0 1\n\nquery T\nSELECT 'endloop'\n----\nendloop")
    }

    @Test fun anExpectedErrorLineNamedEndloopIsNotATerminator() {
        refused("loop i 0 1\n\nstatement error\nSELECT * FROM missing_endloop_table\n----\nendloop")
    }

    @Test fun sqlTextOrAStringNamedEndloopDoesNotCloseTheLoop() {
        // SELECT 1 followed by endloop is valid SQL using endloop as a column alias.
        refused("loop i 0 1\n\nstatement ok\nSELECT 1\nendloop")
        refused("loop i 0 1\n\n" + statement("SELECT 'endloop'"))
    }

    @Test fun aTerminatorIgnoredByLexicalModeSkipDoesNotCloseTheLoop() {
        refused("loop i 0 1\n\n" + statement("SELECT 1") + "mode skip\n\nendloop\n\nmode unskip\n")
    }

    @Test fun aRealEndloopAtEofWithoutNewlineIsAccepted() {
        val file = parse("loop i 0 2\n\n" + statement("SELECT \${i}") + "endloop")
        assertThat(file.records).singleElement().isInstanceOf(SltLoop::class.java)
        assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 0", "SELECT 1")
    }

    @Test fun correctlyNestedLoopsAndTheirTailRemainRunnable() {
        val text = "loop i 0 1\n\nforeach j one two\n\n" + statement("SELECT '\${j}'") +
            "endloop\n\nendloop\n\n" + statement("SELECT 'tail'")
        val file = parse(text)
        assertThat(flattened(file.records).filterIsInstance<SltUnsupported>()).isEmpty()
        assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 'one'", "SELECT 'two'", "SELECT 'tail'")
    }

    @Test fun closedLoopsKeepTheirSourceLineAndFollowingRecords() {
        val file = parse("# header\n\nforeach i one\n\n" + statement("SELECT 1") + "endloop\n\n" + statement("SELECT 2"))
        val loop = file.records.first() as SltLoop
        assertThat(loop.line).isEqualTo(3)
        assertThat(loop.body.single().line).isEqualTo(5)
        assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
    }

    @Test fun concurrentLoopsWithoutTerminatorsRetainAMissingEndloopDiagnostic() {
        for (header in listOf("concurrentloop i 0 1", "concurrentforeach i one")) {
            refused("$header\n\n" + statement("SELECT 1"), header.substringBefore(' '))
        }
    }

    @Test fun closedConcurrentBodiesStillDoNotEscapeIntoTheFollowingRecords() {
        for (header in listOf("concurrentloop i 0 1", "concurrentforeach i one")) {
            val file = parse("$header\n\n" + statement("SELECT 'body'") + "endloop\n\n" + statement("SELECT 'tail'"))
            assertThat(file.records).hasSize(2)
            assertThat(file.records.first()).isInstanceOf(SltUnsupported::class.java)
            assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 'tail'")
        }
    }

    @Test fun ignoredLoopHeadersInsideModeSkipDoNotCreateOpenScopes() {
        val file = parse("mode skip\n\nloop i 0 1\n\n" + statement("SELECT 'ignored'") + "mode unskip\n\n" + statement("SELECT 'tail'"))
        assertThat(file.records).singleElement().isInstanceOf(SltStatement::class.java)
        assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 'tail'")
    }

    @Test fun balancedModeSkipInsideALoopKeepsTheRealTerminator() {
        val file = parse("loop i 0 2\n\nmode skip\n\n" + statement("SELECT 'ignored'") +
            "mode unskip\n\n" + statement("SELECT \${i}") + "endloop\n")
        assertThat(flattened(file.records).filterIsInstance<SltUnsupported>()).isEmpty()
        assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 0", "SELECT 1")
    }

    @Test fun ordinaryUnsupportedOmissionDoesNotDiscardAValidExpanderPrefix() {
        val file = parse(statement("SELECT 'prefix'") + "loop i 0 1\n\n" + statement("SELECT 'unclosed-body'"))
        assertThat(file.records).hasSize(2)
        assertThat(file.records[1]).isInstanceOf(SltUnsupported::class.java)
        assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 'prefix'")
    }

    @Test fun guardsCannotConvertAModelledUnclosedLoopIntoAValidLoop() {
        for (guard in listOf("onlyif duckdb", "skipif duckdb")) {
            refused("$guard\nloop i 0 1\n\n" + statement("SELECT 1"), line = 2)
        }
    }

    @Test fun malformedHeaderDiagnosticsAreNotReplacedWhenTheBodyDoesClose() {
        val file = parse("loop i bad 1\n\n" + statement("SELECT 1") + "endloop\n")
        val unsupported = file.records.single() as SltUnsupported
        assertThat(unsupported.raw).isEqualTo("loop i bad 1")
        assertThat(SltExpander.expand(file, "duckdb")).isEmpty()
    }

    @Test fun aStrayEndloopStillDoesNotSilentlyTruncateTheFile() {
        val file = parse("endloop\n\n" + statement("SELECT 'tail'"))
        assertThat(file.records).hasSize(2)
        assertThat(file.records.first()).isInstanceOf(SltUnsupported::class.java)
        assertThat(SltExpander.expand(file, "duckdb").map { it.sql }).containsExactly("SELECT 'tail'")
    }
}
