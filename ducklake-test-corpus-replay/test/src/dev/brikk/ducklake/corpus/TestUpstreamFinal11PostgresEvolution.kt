package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import dev.brikk.ducklake.slt.SltQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class TestUpstreamFinal11PostgresEvolution {
    @Test fun `PostgreSQL STRUCT add drop widening`() = qualify(
        "alter/struct_evolution.test", "6c9e5075d17af16efbef4db21dd26d3a4caf575611ec2b3a00aadb487053f327", 7)
    @Test fun `PostgreSQL nested STRUCT absent parents`() = qualify(
        "alter/struct_evolution_nested.test", "7db784b1b7486ab79045d064b5165202ea214f88d7e4c72cfc786a944ca91faf", 5)
    @Test fun `PostgreSQL STRUCT inside LIST`() = qualify(
        "alter/struct_in_list_evolution.test", "83e2bb90f21799f146c90b6fd69d01db6699607f837351fceec7a43b860f593b", 8)
    @Test fun `PostgreSQL STRUCT inside MAP`() = qualify(
        "alter/struct_in_map_evolution.test", "07b6d711696c337921634961738db5afaadb7bb65a5fb7d43cc85a361c8c636b", 5)
    @Test fun `PostgreSQL committed nested defaults`() = qualify(
        "default/struct_field_default.test", "f34029bb8a6d7b6cb795ff9f682028040a7307374d15b4b7b0a8a3e4d98eaecd", 1)
    @Test fun `PostgreSQL renamed defaults`() = qualify(
        "alter/rename_struct_field_default.test", "aae190bed94e2eaba07c61ced8b87242f273c4dcd0ee577c379b536c734e3a90", 3)
    @Test fun `PostgreSQL reorder before flush with unchanged native goldens afterward`() = qualify(
        "alter/struct_evolution_reorder.test", "3a742b719b404443b270e2d3b47f6b7b307d75fa9ac3358af1eed8846e71f7c2", 3, true)
    @Test fun `PostgreSQL integer promotion preserves native narrowing errors`() = qualify(
        "alter/promote_type.test", "038cdb398891a93b054e48c4f6588bcf72a44efef2d417c749fb06e422a6ab09", 1)
    @Test fun `PostgreSQL NULL types promote inside qualified containers`() = qualify(
        "data_inlining/data_inlining_null_type.test", "e55cfb413f0a3111b74f266598e76e2c2aefd5f19ab111c94d0f8cc9438945f5", 4, true)
    @Test fun `PostgreSQL temporal initial literals preserve original scratch query zones`() = qualify(
        "default/literal_default_rendering.test", "f804d019c04643e7f9804218906e492e63e1fab5773fdf9174ac2cfcdafc03d2", 5)

    private fun qualify(path: String, digest: String, mirrors: Int, beforeFlush: Boolean = false) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        val zones = mutableMapOf<String, String>()
        var zone = "UTC"
        file.records.forEach { record ->
            if (record is SltStatement) Regex("^SET TimeZone\\s*=\\s*'([^']+)'$", RegexOption.IGNORE_CASE)
                .matchEntire(record.sql)?.let { zone = it.groupValues[1] }
            if (record is SltQuery) zones[record.sql] = zone
        }
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val backend = Final11MirrorBackend("postgres-jdbc") { attachment -> DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = server.getJdbcUrl()
                catalogDatabaseUser = server.getUser()
                catalogDatabasePassword = server.getPassword()
                dataPath = attachment.dataPath
                maxCatalogConnections = 1
                enableFinal11Reads = true
            } }
            Final11InlineCorpusMirror(backend = backend, allowUnqualified = beforeFlush,
                mirrorLimit = if (beforeFlush || path.startsWith("default/")) mirrors else Int.MAX_VALUE,
                payloadQuery = { !it.contains("FROM dl.dt", ignoreCase = true) }, queryTimeZone = { zones.getValue(it) }).use { engine ->
                val setup = SltStatement(0, "INSTALL postgres; LOAD postgres", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") })
                    .replay(file.copy(records = listOf(setup) + file.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("mandatory PostgreSQL evolution payload mirrors").isEqualTo(mirrors)
            }
        }
    }
}
