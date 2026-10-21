package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Pristine shipped 1.1 SLT goldens first, then the JVM catalog's own decoded inline values. */
class TestUpstreamFinal11Defaults {
    @Test
    fun `upstream added literal default reads back through the final 11 JVM reader`() = qualify(
        "default/add_column_if_not_exists_expression_default.test",
        "3cec752529d12d57dc42891a519eeef9136a5fb69f954b1d1b96075639fe85b9", 1)

    @Test
    fun `upstream cast and JSON default fixtures reach the JVM reader`() = qualify(
        "default/cast_defaults_not_literal.test",
        "ed4ecb85e6ee7f654eab503adc25598735278cfd63943d0fd0048b9a0aa3830b", 2)

    @Test
    fun `upstream literal NULL string and blob defaults retain their values across reopen`() = qualify(
        "issues/issue_1212_string_null_default.test",
        "a527746e673bde1e6df61b2b8b3bddd8641ae801ba01f7b99306e4ca13119bb5", 1)

    private fun qualify(path: String, digest: String, mirrors: Int) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertThat(actual).`as`("pristine DuckLake 417a9117f590e8141742ac0579d02f6a90162e37 $path").isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            Final11InlineCorpusMirror(server).use { engine ->
                // Backend setup only: every upstream statement, line and golden remains unchanged.
                val setup = SltStatement(0, "INSTALL quack; LOAD quack; CREATE SECRET (TYPE quack, TOKEN " +
                    "'${server.getToken().replace("'", "''")}')", expectError = false, expectedError = null, connection = null)
                val prepared = file.copy(records = listOf(setup) + file.records)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getQuackAttachUri() }).replay(prepared)
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("$path must execute each intended JVM mirror, not just the native oracle").isEqualTo(mirrors)
            }
        }
    }

}
