package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class TestUpstreamFinal11ReadMetadata {
    @Test
    fun `shipped row group metadata including Puffin is mirrored through public APIs`() = qualify(
        "metadata/row_group_count.test", "603a0107ddf93c03a058bed34166bff23ad26c2a6d796d551950baeaee7bf357", 6)

    @Test
    fun `shipped migrated row group unknowns remain unknown through public APIs`() = qualify(
        "migration/v10_row_group_count.test", "87fdbba98e3cca7b3afbecc3be6619977391f6cc5f916651a831801503d3dce6", 4)

    @Test
    fun `shipped migrated independent exactness flags are mirrored through public APIs`() = qualify(
        "migration/v11_stats_exactness.test", "9e9523e757416e6b4f6936e54179b46ef53dd76b6a3143001caf1fa8c8827d6b", 7)

    private fun qualify(path: String, digest: String, mirrors: Int) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            Final11MetadataCorpusMirror(server).use { engine ->
                val setup = SltStatement(0, "INSTALL quack; LOAD quack; CREATE SECRET(TYPE quack,TOKEN " +
                    "'${server.getToken().replace("'", "''")}')", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getQuackAttachUri() })
                    .replay(file.copy(records = listOf(setup) + file.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("required rich metadata JVM mirrors: $path").isEqualTo(mirrors)
            }
        }
    }
}
