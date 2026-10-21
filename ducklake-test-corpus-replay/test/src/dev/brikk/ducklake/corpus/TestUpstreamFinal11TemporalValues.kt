package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import dev.brikk.ducklake.slt.SltQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Pristine R03 SQL/goldens, then required Java-catalog reads before any native flush. */
class TestUpstreamFinal11TemporalValues {
    @Test
    fun `upstream TIME_NS boundaries are mirrored before flush`() = qualify(
        "data_inlining/data_inlining_time_ns.test", "f7a58dae210d60604ee9da76fe7615d6513ec1990778d704e21a288a64eda075", 2)

    @Test
    fun `upstream NULL scalar and container promotion is mirrored before flush`() = qualify(
        "data_inlining/data_inlining_null_type.test", "e55cfb413f0a3111b74f266598e76e2c2aefd5f19ab111c94d0f8cc9438945f5", 4, true)

    @Test
    fun `upstream temporal literal default data queries reach the Java reader`() = qualify(
        "default/literal_default_rendering.test", "f804d019c04643e7f9804218906e492e63e1fab5773fdf9174ac2cfcdafc03d2", 5)

    private fun qualify(path: String, digest: String, mirrors: Int, unqualified: Boolean = false) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .`as`("pristine shipped 417a9117 $path").isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        val zones = mutableMapOf<String, String>()
        var currentZone = "UTC"
        for (record in file.records) {
            if (record is SltStatement) Regex("^SET TimeZone\\s*=\\s*'([^']+)'$", RegexOption.IGNORE_CASE)
                .matchEntire(record.sql)?.let { currentZone = it.groupValues[1] }
            if (record is SltQuery) zones[record.sql] = currentZone
        }
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            Final11InlineCorpusMirror(server, allowUnqualified = unqualified, mirrorLimit = mirrors,
                payloadQuery = { !it.contains("FROM dl.dt", ignoreCase = true) },
                queryTimeZone = { zones.getValue(it) }).use { engine ->
                val setup = SltStatement(0, "INSTALL quack; LOAD quack; CREATE SECRET(TYPE quack,TOKEN " +
                    "'${server.getToken().replace("'", "''")}')", false, null, null)
                val prepared = file.copy(records = listOf(setup) + file.records)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getQuackAttachUri() }).replay(prepared)
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("$path required JVM payload reads; post-flush native goldens still run").isEqualTo(mirrors)
            }
        }
    }
}
