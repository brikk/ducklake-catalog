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

/** Unchanged upstream SQL and goldens; scratch rows come only from direct PostgreSQL library APIs. */
class TestUpstreamFinal11PostgresMetadata {
    @Test
    fun `PostgreSQL row groups including Puffin reach direct JDBC`() = qualify(
        "metadata/row_group_count.test", "603a0107ddf93c03a058bed34166bff23ad26c2a6d796d551950baeaee7bf357", 6)

    @Test
    fun `PostgreSQL migrated row groups remain unknown`() = qualify(
        "migration/v10_row_group_count.test", "87fdbba98e3cca7b3afbecc3be6619977391f6cc5f916651a831801503d3dce6", 4)

    @Test
    fun `PostgreSQL migrated exactness keeps independent unknown endpoints`() = qualify(
        "migration/v11_stats_exactness.test", "9e9523e757416e6b4f6936e54179b46ef53dd76b6a3143001caf1fa8c8827d6b", 7)

    @Test
    fun `PostgreSQL schema tree lookups preserve quoted components`() = qualify(
        "catalog/nested_schema/load_schema_tree.test", "928f9604a973b368c94d945e92869e8c837f30554009d81424a4dd672e00be0e", 2, hierarchy = true)

    @Test
    fun `PostgreSQL shared schema helpers retain committed children`() = qualify(
        "catalog/nested_schema/shared_schema_helpers.test", "30d6280b4908ff776a7acd4a8d16ca13a73bd807a9e67185e2370da83a4e6659", 1, hierarchy = true)

    @Test
    fun `PostgreSQL explicit schema parent migrations reach hierarchy`() = qualify(
        "migration/schema_parent_column.test", "bdde7edb231fa36ad4ae1793d008dd7b200b5099049cd4747f41bcfa7b6ab250", 3, hierarchy = true)

    @Test
    fun `PostgreSQL nullable schema table paths resolve native files`() = qualify(
        "general/null_schema_and_table_paths.test", "7f487746d491d60a536ea904eee008e808604dae0680f454fa12065cecc80466", 1, hierarchy = true)

    private fun qualify(path: String, digest: String, mirrors: Int, hierarchy: Boolean = false) {
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
            val engine = if (hierarchy) Final11HierarchyCorpusMirror(backend = backend) else Final11MetadataCorpusMirror(backend = backend)
            engine.use {
                val setup = SltStatement(0, "INSTALL postgres; LOAD postgres", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") })
                    .replay(source.copy(records = listOf(setup) + source.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                val count = if (engine is Final11HierarchyCorpusMirror) engine.mirrors else (engine as Final11MetadataCorpusMirror).mirrors
                assertThat(count).`as`("required direct PostgreSQL metadata/hierarchy mirrors: $path").isEqualTo(mirrors)
            }
        }
    }
}
