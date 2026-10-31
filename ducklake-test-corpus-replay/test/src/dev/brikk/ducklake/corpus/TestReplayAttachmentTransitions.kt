package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Real oracle attachment SQL; the control adapter records only dispatch/gating, not engine correctness. */
internal class TestReplayAttachmentTransitions {
    private val query = "query I\nSELECT 1\n----\n1\n\n"

    private class Engine : ReplayReadEngine {
        override val name = "attachment-control"
        val attachments = mutableListOf<OracleAttachment>()
        var queries = 0
        override fun connect(attachment: OracleAttachment) { attachments += attachment }
        override fun executeQuery(sql: String): List<List<String?>> { queries++; return listOf(listOf("1")) }
        override fun close() = Unit
    }

    private fun attach(target: String, alias: String = "lake", extra: String = "", modifier: String = "", data: Boolean = true): String {
        val path = if (data) "DATA_PATH '{TEST_DIR}/$target-data', " else ""
        return "ATTACH $modifier 'ducklake:{TEST_DIR}/$target.db' AS $alias " +
            "(${path}DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE$extra)"
    }

    private fun statement(sql: String, expectation: String = "ok") =
        "statement $expectation\n$sql\n${if (expectation == "error") "----\n" else ""}\n"
    private fun file(text: String) = SltParser.parse("attachments.test", "require ducklake\n\n$text")
    private fun replay(engine: Engine, text: String): ReplayQueryCoverage {
        val result = ReplayDriver(engine).replay(file(text))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).isEmpty()
        assertThat(result.skipped).isEmpty()
        return requireNotNull(result.queryCoverage)
    }

    @Test fun threeConcurrentLakesDisableMirroringForTheRestOfTheFile() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a", "a")) + query +
            statement(attach("b", "b")) + query + statement(attach("c", "c")) + query +
            statement("DETACH b") + statement("DETACH c") + query +
            statement("DETACH a") + statement(attach("a", "a")) + query)
        assertThat(engine.attachments.map { it.catalogAlias }).containsExactly("a")
        assertThat(engine.queries).isEqualTo(1)
        assertThat(coverage.oraclePasses).isEqualTo(5)
        assertThat(coverage.mirrorPasses).isEqualTo(1)
        assertThat(coverage.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("multiple-lakes" to 4))
    }

    @Test fun failedSecondAttachLeavesTheCurrentMirrorUsable() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a")) + query +
            statement(attach("b", "other", ", NOT_AN_OPTION true"), "error") + query)
        assertThat(engine.attachments).hasSize(1)
        assertThat(coverage.mirrorPasses).isEqualTo(2)
        assertThat(coverage.mirrorSkipReasons).isEmpty()
    }

    @Test fun failedSnapshotPinnedAttachDoesNotDisableTheCurrentMirror() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a")) + query +
            statement(attach("b", "other", ", SNAPSHOT_VERSION 999"), "error") + query)
        assertThat(engine.attachments).hasSize(1)
        assertThat(coverage.mirrorPasses).isEqualTo(2)
        assertThat(coverage.mirrorSkipReasons).isEmpty()
    }

    @Test fun failedDetachOfTheDefaultDatabasePreservesMirroring() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a")) + statement("USE lake") + query +
            statement("DETACH lake", "error") + query)
        assertThat(engine.attachments).hasSize(1)
        assertThat(coverage.mirrorPasses).isEqualTo(2)
        assertThat(coverage.mirrorSkipReasons).isEmpty()
    }

    @Test fun quotedDetachRecognizesCaseAndOptionalKeywords() {
        for (sql in listOf("DETACH \"LaKe\"", "DETACH DATABASE IF EXISTS \"LaKe\";")) {
            val engine = Engine()
            val coverage = replay(engine, statement(attach("a")) + query + statement(sql) + query)
            assertThat(engine.queries).isEqualTo(1)
            assertThat(coverage.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("detached" to 1))
        }
    }

    @Test fun quotedAliasesWithSpacesAndEscapedQuotesAreTracked() {
        val engine = Engine()
        val alias = "\"lake \"\"quoted\"\"\""
        val coverage = replay(engine, statement(attach("a", alias)) + query + statement("DETACH $alias") + query)
        assertThat(engine.attachments.map { it.catalogAlias }).containsExactly("lake \"quoted\"")
        assertThat(coverage.mirrorPasses).isEqualTo(1)
        assertThat(coverage.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("detached" to 1))
    }

    @Test fun ordinaryDetachReattachReusesTheRewriteAndSuccessfulDataPath() {
        val engine = Engine()
        var rewrites = 0
        val driver = ReplayDriver(engine, metadataRewriter = { original ->
            rewrites++
            original.replace(".db", "-rewritten.db")
        })
        val result = driver.replay(file(statement(attach("a")) + query + statement("DETACH lake") + query +
            statement(attach("a", "other", data = false)) + query))
        assertThat(result.failed).isEmpty()
        assertThat(rewrites).isEqualTo(1)
        assertThat(engine.attachments.map { it.catalogAlias }).containsExactly("lake", "other")
        assertThat(engine.attachments.map { it.metadataUri }.distinct()).singleElement().asString().endsWith("a-rewritten.db")
        assertThat(engine.attachments.map { it.dataPath }.distinct()).hasSize(1)
        assertThat(engine.attachments.first().dataPath).endsWith("a-data")
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(2)
        assertThat(result.queryCoverage!!.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("detached" to 1))
    }

    @Test fun failedAttachDoesNotReplaceTheLastSuccessfulDataPath() {
        val engine = Engine()
        val invalid = attach("a", extra = ", NOT_AN_OPTION true").replace("a-data", "wrong-data")
        val coverage = replay(engine, statement(attach("a")) + statement("DETACH lake") +
            statement(invalid, "error") + statement(attach("a", data = false)) + query)
        assertThat(engine.attachments).hasSize(2)
        assertThat(engine.attachments.last().dataPath).isEqualTo(engine.attachments.first().dataPath)
        assertThat(coverage.mirrorPasses).isEqualTo(1)
    }

    @Test fun successfulReplacementOfASingleAliasReconnectsInsteadOfUsingTheOldLake() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a")) + query +
            statement(attach("b", modifier = "OR REPLACE")) + query)
        assertThat(engine.attachments.map { it.metadataUri.substringAfterLast('/') }).containsExactly("a.db", "b.db")
        assertThat(coverage.mirrorPasses).isEqualTo(2)
        assertThat(coverage.mirrorSkipReasons).isEmpty()
    }

    @Test fun successfulIfNotExistsNoOpDoesNotAnnounceADifferentLake() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a")) + query +
            statement(attach("b", modifier = "IF NOT EXISTS")) + query)
        assertThat(engine.attachments).hasSize(1)
        assertThat(engine.attachments.single().metadataUri).endsWith("a.db")
        assertThat(coverage.mirrorPasses).isEqualTo(2)
    }

    @Test fun aSuccessfulPinnedAttachDisablesMirroringUntilTheNextFile() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a")) + query + statement("DETACH lake") +
            statement(attach("a", extra = ", SNAPSHOT_VERSION 0")) + query + statement("DETACH lake") +
            statement(attach("a")) + query)
        assertThat(engine.attachments).hasSize(1)
        assertThat(coverage.mirrorPasses).isEqualTo(1)
        assertThat(coverage.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("snapshot-pinned" to 2))
    }

    @Test fun successfulDetachStillChangesStateWhenTheSltExpectedAnError() {
        val engine = Engine()
        val result = ReplayDriver(engine).replay(file(statement(attach("a")) + query +
            statement("DETACH \"lake\"", "error") + query))
        assertThat(result.failed).singleElement().extracting { it.reason }.asString().contains("statement succeeded")
        assertThat(engine.queries).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("detached" to 1))
    }

    @Test fun driverReuseResetsPermanentDisablementAndCoverage() {
        val engine = Engine()
        val driver = ReplayDriver(engine)
        val first = driver.replay(file(statement(attach("a", "a")) + statement(attach("b", "b")) + query))
        assertThat(first.failed).isEmpty()
        assertThat(first.queryCoverage!!.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("multiple-lakes" to 1))
        val second = driver.replay(file(statement(attach("a")) + query))
        assertThat(second.failed).isEmpty()
        assertThat(second.queryCoverage!!.mirrorPasses).isEqualTo(1)
        assertThat(second.queryCoverage!!.mirrorSkipReasons).isEmpty()
    }

    @Test fun anUnmirrorableSecondLakeStillDisablesSingleLakeMirroring() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a")) + query + statement(attach("b", "other", data = false)) + query)
        assertThat(engine.attachments).hasSize(1)
        assertThat(coverage.mirrorPasses).isEqualTo(1)
        assertThat(coverage.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("multiple-lakes" to 1))
    }

    @Test fun aFailedFirstAttachDoesNotMakeAnUnknownDataPathMirrorable() {
        val engine = Engine()
        val coverage = replay(engine, statement(attach("a", extra = ", NOT_AN_OPTION true"), "error") +
            statement(attach("a", data = false)) + query)
        assertThat(engine.attachments).isEmpty()
        assertThat(coverage.mirrorAttempts).isZero()
        assertThat(coverage.mirrorSkipReasons).containsExactlyEntriesOf(mapOf("not-attached" to 1))
    }

    @Test fun ifNotExistsNoOpCannotPoisonTheSuccessfulDataPath() {
        val engine = Engine()
        val noOp = attach("a", modifier = "IF NOT EXISTS").replace("a-data", "wrong-data")
        val coverage = replay(engine, statement(attach("a")) + statement(noOp) + statement("DETACH lake") +
            statement(attach("a", data = false)) + query)
        assertThat(engine.attachments).hasSize(2)
        assertThat(engine.attachments.last().dataPath).isEqualTo(engine.attachments.first().dataPath)
        assertThat(coverage.mirrorPasses).isEqualTo(1)
    }
}
