package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class TestUpstreamFinal11Evolution {
    @Test
    fun `upstream struct add drop and widening are actual JVM reads`() = qualify(
        "alter/struct_evolution.test", "6c9e5075d17af16efbef4db21dd26d3a4caf575611ec2b3a00aadb487053f327", 7)

    @Test
    fun `upstream nested struct evolution preserves absent parents`() = qualify(
        "alter/struct_evolution_nested.test", "7db784b1b7486ab79045d064b5165202ea214f88d7e4c72cfc786a944ca91faf", 5)

    @Test
    fun `upstream struct evolution inside lists is mirrored`() = qualify(
        "alter/struct_in_list_evolution.test", "83e2bb90f21799f146c90b6fd69d01db6699607f837351fceec7a43b860f593b", 8)

    @Test
    fun `upstream struct evolution inside maps is mirrored`() = qualify(
        "alter/struct_in_map_evolution.test", "07b6d711696c337921634961738db5afaadb7bb65a5fb7d43cc85a361c8c636b", 5)

    @Test
    fun `upstream nested literal defaults are mirrored after commit`() = qualify(
        "default/struct_field_default.test", "f34029bb8a6d7b6cb795ff9f682028040a7307374d15b4b7b0a8a3e4d98eaecd", 1)

    @Test
    fun `upstream renamed nested defaults keep their field identity`() = qualify(
        "alter/rename_struct_field_default.test", "aae190bed94e2eaba07c61ced8b87242f273c4dcd0ee577c379b536c734e3a90", 3)

    @Test
    fun `upstream reordered native types are mirrored before flush and native goldens continue afterward`() = qualify(
        "alter/struct_evolution_reorder.test", "3a742b719b404443b270e2d3b47f6b7b307d75fa9ac3358af1eed8846e71f7c2", 3, true)

    @Test
    fun `upstream integer widening and narrowing rejection are retained`() = qualify(
        "alter/promote_type.test", "038cdb398891a93b054e48c4f6588bcf72a44efef2d417c749fb06e422a6ab09", 1)

    private fun qualify(path: String, digest: String, mirrors: Int, beforeFlush: Boolean = false) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .`as`("pristine upstream DuckLake 417a9117 $path").isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            Final11InlineCorpusMirror(server, allowUnqualified = beforeFlush,
                mirrorLimit = if (beforeFlush) mirrors else Int.MAX_VALUE).use { engine ->
                val setup = SltStatement(0, "INSTALL quack; LOAD quack; CREATE SECRET(TYPE quack,TOKEN " +
                    "'${server.getToken().replace("'", "''")}')", false, null, null)
                val prepared = file.copy(records = listOf(setup) + file.records)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getQuackAttachUri() }).replay(prepared)
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("$path required JVM read count").isEqualTo(mirrors)
            }
        }
    }
}
