package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class TestUpstreamFinal11Hierarchy {
    @Test
    fun `shipped schema tree lookup and retained subtree visibility reach the JVM hierarchy`() = qualify(
        "catalog/nested_schema/load_schema_tree.test", "928f9604a973b368c94d945e92869e8c837f30554009d81424a4dd672e00be0e", 2)

    @Test
    fun `shipped shared schema helpers preserve committed quoted child visibility`() = qualify(
        "catalog/nested_schema/shared_schema_helpers.test", "30d6280b4908ff776a7acd4a8d16ca13a73bd807a9e67185e2370da83a4e6659", 1)

    @Test
    fun `shipped explicit schema parent migrations reach the JVM hierarchy`() = qualify(
        "migration/schema_parent_column.test", "bdde7edb231fa36ad4ae1793d008dd7b200b5099049cd4747f41bcfa7b6ab250", 3)

    @Test
    fun `shipped nullable schema and table paths resolve files through JVM ownership`() = qualify(
        "general/null_schema_and_table_paths.test", "7f487746d491d60a536ea904eee008e808604dae0680f454fa12065cecc80466", 1)

    private fun qualify(path: String, digest: String, mirrors: Int) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            Final11HierarchyCorpusMirror(server).use { engine ->
                val setup = SltStatement(0, "INSTALL quack; LOAD quack; CREATE SECRET(TYPE quack,TOKEN " +
                    "'${server.getToken().replace("'", "''")}')", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getQuackAttachUri() })
                    .replay(file.copy(records = listOf(setup) + file.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("required public JVM hierarchy/path mirrors: $path").isEqualTo(mirrors)
            }
        }
    }
}
