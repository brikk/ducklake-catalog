package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.TestingDucklakeMySqlCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Preserve original native MySQL scanner/write failures independently of the direct-JDBC read tier. */
@Tag("mysql-native")
class TestUpstreamMySqlNativeGate {
    @Test fun `native integer inline scanner must match pristine expected type`() = qualify(
        "data_inlining/inlining_double.test", "319fb25dc84c7f88a819c3b1f34ffe76da938e5cfacc131c06dc021f087a32af")
    @Test fun `native repeated default inserts and scans must preserve pristine goldens`() = qualify(
        "default/default_values.test", "ad3060d0742f68d8da74934bac9f2c4cee3f1a860f40eeac1e68af9d491910d2")
    @Test fun `native row group lifecycle must preserve original metadata goldens`() = qualify(
        "metadata/row_group_count.test", "603a0107ddf93c03a058bed34166bff23ad26c2a6d796d551950baeaee7bf357")
    @Test fun `native read-only empty integer scan must preserve original golden`() = qualify(
        "general/ducklake_read_only.test", "25db00ba1287a3b53a1d86013b092e3795c150fd520b488fa266f4bc1e8e0683")

    private fun qualify(path: String, digest: String) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        val source = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        TestingDucklakeMySqlCatalogServer("main").use { server ->
            val setup = SltStatement(0, "INSTALL mysql; LOAD mysql", false, null, null)
            val result = ReplayDriver(repoRoot = root, metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") })
                .replay(source.copy(records = listOf(setup) + source.records))
            assertThat(result.fileSkipReason).isNull()
            assertThat(result.failed).`as`("strict unchanged native MySQL acceptance: $path").isEmpty()
            assertThat(result.skipped).isEmpty()
        }
    }
}
