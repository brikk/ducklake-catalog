package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import dev.brikk.ducklake.slt.SltFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Whole pristine files; only the qualified committed payload queries are mirrored through PostgreSQL JDBC. */
class TestUpstreamFinal11PostgresNumeric {
    @Test
    fun `PostgreSQL decimal precision evolution reaches committed JVM values`() = qualify(
        "alter/alter_type_stale_stats_cache.test", "0416ab66c67c85b8e7db1908169de17a5c6bb1c3aa7f2b283f3f57472f7df306", 2,
        "ducklake.t3", inlineRows = 1000)

    @Test
    fun `PostgreSQL transaction local float retype reaches JVM after commit`() = qualify(
        "data_inlining/data_inlining_transaction_local_alter_type_rename.test",
        "773e1bdf4464d726376c1c1e8144a73dabc50444613475708459ed6cdde0b2ff", 1, "ducklake.type_test2")

    @Test
    fun `PostgreSQL widened float defaults retain literal intent after native reopen`() = qualify(
        "default/literal_default_rendering.test", "f804d019c04643e7f9804218906e492e63e1fab5773fdf9174ac2cfcdafc03d2", 1, "dl.fl")

    private fun qualify(path: String, digest: String, mirrors: Int, sourceTable: String, inlineRows: Int? = null) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        val source = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        // The stock 10-row threshold writes Parquet here. Preserve that native control separately;
        // an explicit second fixture profile makes the unchanged queries exercise actual inline values.
        if (inlineRows != null) stockNativeControl(root, source)
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val backend = Final11MirrorBackend("postgres-jdbc") { attachment -> DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = server.getJdbcUrl()
                catalogDatabaseUser = server.getUser()
                catalogDatabasePassword = server.getPassword()
                dataPath = attachment.dataPath
                maxCatalogConnections = 1
                enableFinal11Reads = true
            } }
            val selected = Regex("\\bFROM\\s+${Regex.escape(sourceTable)}\\b", RegexOption.IGNORE_CASE)
            Final11InlineCorpusMirror(backend = backend, payloadQuery = { selected.containsMatchIn(it) }).use { engine ->
                val profile = inlineRows?.let { "INSTALL ducklake; LOAD ducklake; SET ducklake_default_data_inlining_row_limit=$it; " } ?: ""
                val setup = SltStatement(0, profile + "INSTALL postgres; LOAD postgres", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") })
                    .replay(source.copy(records = listOf(setup) + source.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("required direct PostgreSQL numeric payload mirrors: $path").isEqualTo(mirrors)
            }
        }
    }

    private fun stockNativeControl(root: Path, source: SltFile) {
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val setup = SltStatement(0, "INSTALL postgres; LOAD postgres", false, null, null)
            val result = ReplayDriver(repoRoot = root, metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") })
                .replay(source.copy(records = listOf(setup) + source.records))
            assertThat(result.fileSkipReason).isNull()
            assertThat(result.failed).`as`("unchanged stock-threshold native control").isEmpty()
            assertThat(result.skipped).isEmpty()
        }
    }
}
