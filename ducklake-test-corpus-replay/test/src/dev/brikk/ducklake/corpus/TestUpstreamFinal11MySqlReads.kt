package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.TestingDucklakeMySqlCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Unchanged pristine native SQL/goldens, followed by mandatory existing-catalog MySQL JDBC mirrors. */
class TestUpstreamFinal11MySqlReads {
    @Test fun `MySQL native double values reach direct JDBC`() = qualify(
        "stats/inlined_double_stats_same_transaction.test", "398c2f9c4f5c8cbdf05f6cb8fede8eeeb01ceeee9439272586c8a9826640f4a9", 3)
    @Test fun `MySQL embedded NUL text reaches direct JDBC without replacement`() = qualify(
        "types/null_byte.test", "7f679ed29a25d0f3e6cffe2da826b3e96b3b0cf967421a5de46fd105ce8072ec", 2)
    @Test fun `MySQL null schema and table paths reach ownership APIs`() = qualify(
        "general/null_schema_and_table_paths.test", "7f487746d491d60a536ea904eee008e808604dae0680f454fa12065cecc80466", 1, hierarchy = true)

    private fun qualify(path: String, digest: String, mirrors: Int, hierarchy: Boolean = false) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        // Match the generic native metadata manager's default `main` schema. This is an owned
        // fresh database, not a rewritten ATTACH, retagged format or library-side bootstrap.
        TestingDucklakeMySqlCatalogServer("main").use { server ->
            val backend = Final11MirrorBackend("mysql-jdbc") { attachment -> DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = server.getJdbcUrl()
                catalogDatabaseUser = server.getUser()
                catalogDatabasePassword = server.getPassword()
                dataPath = attachment.dataPath
                maxCatalogConnections = 1
                enableFinal11Reads = true
            } }
            val engine = if (hierarchy) Final11HierarchyCorpusMirror(backend = backend) else Final11InlineCorpusMirror(backend = backend)
            engine.use {
                val setup = SltStatement(0, "INSTALL mysql; LOAD mysql", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") })
                    .replay(file.copy(records = listOf(setup) + file.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                val count = if (engine is Final11InlineCorpusMirror) engine.mirrors else (engine as Final11HierarchyCorpusMirror).mirrors
                assertThat(count).`as`("mandatory direct MySQL JDBC mirrors").isEqualTo(mirrors)
            }
        }
    }
}
