package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Pristine native PostgreSQL-backed SLT goldens plus required independent direct-JDBC library reads. */
class TestUpstreamFinal11Postgres {
    @Test
    fun `PostgreSQL missing initial literal defaults reach direct JDBC`() = qualify(
        "default/add_column_if_not_exists_expression_default.test", "3cec752529d12d57dc42891a519eeef9136a5fb69f954b1d1b96075639fe85b9", 1)

    @Test
    fun `PostgreSQL BYTEA NULL literals and BLOB defaults survive native reopen`() = qualify(
        "issues/issue_1212_string_null_default.test", "a527746e673bde1e6df61b2b8b3bddd8641ae801ba01f7b99306e4ca13119bb5", 1)

    @Test
    fun `PostgreSQL failed expression defaults and JSON data reach direct JDBC`() = qualify(
        "default/cast_defaults_not_literal.test", "ed4ecb85e6ee7f654eab503adc25598735278cfd63943d0fd0048b9a0aa3830b", 2)

    @Test
    fun `PostgreSQL nanosecond TIME text is mirrored before native flush`() = qualify(
        "data_inlining/data_inlining_time_ns.test", "f7a58dae210d60604ee9da76fe7615d6513ec1990778d704e21a288a64eda075", 2)

    private fun qualify(path: String, digest: String, mirrors: Int) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        val source = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val backend = Final11MirrorBackend("postgres-jdbc") { attachment -> DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = server.getJdbcUrl()
                catalogDatabaseUser = server.getUser()
                catalogDatabasePassword = server.getPassword()
                dataPath = attachment.dataPath
                maxCatalogConnections = 1
                enableFinal11Reads = true
            } }
            Final11InlineCorpusMirror(mirrorLimit = mirrors, backend = backend).use { engine ->
                val setup = SltStatement(0, "INSTALL postgres; LOAD postgres", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root,
                    metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") })
                    .replay(source.copy(records = listOf(setup) + source.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("required direct PostgreSQL JDBC payload mirrors: $path").isEqualTo(mirrors)
            }
        }
    }
}
