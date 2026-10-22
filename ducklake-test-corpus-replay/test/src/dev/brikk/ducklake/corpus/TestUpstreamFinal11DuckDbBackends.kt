package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import dev.brikk.ducklake.slt.SltFile
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.function.Supplier

/** Same pristine sources/goldens, distinct native-Quack mirrors and drained local-file read qualification. */
class TestUpstreamFinal11DuckDbBackends {
    @Test
    fun `native Quack mirrors pristine missing column literal defaults`() = native(
        "default/add_column_if_not_exists_expression_default.test", "3cec752529d12d57dc42891a519eeef9136a5fb69f954b1d1b96075639fe85b9", "inline", 1)

    @Test
    fun `native Quack mirrors pristine TIME_NS boundaries before flush`() = native(
        "data_inlining/data_inlining_time_ns.test", "f7a58dae210d60604ee9da76fe7615d6513ec1990778d704e21a288a64eda075", "inline", 2)

    @Test
    fun `native Quack mirrors pristine file and Puffin row group metadata`() = native(
        "metadata/row_group_count.test", "603a0107ddf93c03a058bed34166bff23ad26c2a6d796d551950baeaee7bf357", "metadata", 6)

    @Test
    fun `native Quack mirrors pristine nested schema loading and retirement`() = native(
        "catalog/nested_schema/load_schema_tree.test", "928f9604a973b368c94d945e92869e8c837f30554009d81424a4dd672e00be0e", "hierarchy", 2)

    @Test
    fun `drained local catalog retains pristine string NULL and BLOB defaults`() = local(
        "issues/issue_1212_string_null_default.test", "a527746e673bde1e6df61b2b8b3bddd8641ae801ba01f7b99306e4ca13119bb5", true)

    @Test
    fun `drained local catalog projects pristine added initial literal default`() = local(
        "default/add_column_if_not_exists_expression_default.test", "3cec752529d12d57dc42891a519eeef9136a5fb69f954b1d1b96075639fe85b9", false)

    private val root: Path get() = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
    private fun pristine(path: String, digest: String): SltFile {
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).isEqualTo(digest)
        return SltParser.parse(path, String(bytes, Charsets.UTF_8))
    }

    private fun native(path: String, digest: String, kind: String, required: Int) {
        val source = pristine(path, digest)
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            val engine = when (kind) {
                "inline" -> Final11InlineCorpusMirror(server, mirrorLimit = required, nativeQuack = true)
                "metadata" -> Final11MetadataCorpusMirror(server, nativeQuack = true)
                else -> Final11HierarchyCorpusMirror(server, nativeQuack = true)
            }
            engine.use {
                val setup = SltStatement(0, "INSTALL quack; LOAD quack; CREATE SECRET(TYPE quack,TOKEN " +
                    "'${server.getToken().replace("'", "''")}')", false, null, null)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getQuackAttachUri() })
                    .replay(source.copy(records = listOf(setup) + source.records))
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                val actual = when (engine) {
                    is Final11InlineCorpusMirror -> engine.mirrors
                    is Final11MetadataCorpusMirror -> engine.mirrors
                    is Final11HierarchyCorpusMirror -> engine.mirrors
                    else -> error("Unknown native mirror")
                }
                assertThat(actual).`as`("required native-Quack JVM mirrors: $path").isEqualTo(required)
            }
        }
    }

    private fun local(path: String, digest: String, blob: Boolean) {
        val temp = Files.createTempDirectory(Files.createDirectories(Path.of("/tmp/opencode")), "catalog-r06-pristine-local-")
        try {
            val metadata = temp.resolve("metadata.db")
            // Native oracle closes completely before the local read-only instance opens. No live RW/RO mixing.
            val result = ReplayDriver(repoRoot = root, metadataRewriter = { metadata.toString() }).replay(pristine(path, digest))
            assertThat(result.fileSkipReason).isNull()
            assertThat(result.failed).isEmpty()
            assertThat(result.skipped).isEmpty()
            JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = "jdbc:duckdb:$metadata"
                maxCatalogConnections = 1
                enableFinal11Reads = true
            }).use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val columns = c.getTableColumns(table.tableId, snapshot)
                assertThat(c.getDataFiles(table.tableId, snapshot)).isEmpty()
                val rows = c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                    c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
                }.sortedBy { it[0] as Int }
                if (blob) {
                    assertThat(rows.map { it[0] }).containsExactly(1, 2)
                    rows.forEach { row ->
                        assertThat(row[1]).isEqualTo("NULL")
                        assertThat(row[2] as ByteArray).containsExactly(0x4e, 0x55, 0x4c, 0x4c)
                        assertThat(row[3]).isEqualTo("NULL")
                    }
                } else assertThat(rows).containsExactly(listOf(1, 42))
                assertThat(c.readMetadata().getReadMetadataCapabilities().accessRoute).isEqualTo("local-duckdb")
            }) }
        }
        finally { Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
