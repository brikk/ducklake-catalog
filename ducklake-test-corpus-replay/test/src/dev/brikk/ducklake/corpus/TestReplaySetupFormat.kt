package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.slt.SltFile
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.function.Supplier

/**
 * Explicit replay setup format: DuckDB 2.0 creates DuckLake 1.1 by default, which a 1.0-only reader
 * (a connector without final-1.1 planning) must refuse. [ReplaySetupFormat] configures only the oracle
 * session; corpus SQL, goldens and mirror gating are unchanged.
 */
class TestReplaySetupFormat {
    private val corpusRoot = Path.of(System.getProperty("ducklake.corpus.root"))
    private val repoRoot = corpusRoot.parent.parent

    /** A real 1.0-only reader: the catalog library with final-1.1 reads disabled, as normal Trino uses it. */
    private class OneZeroCountMirror(private val server: TestingDucklakePostgreSqlCatalogServer) : ReplayReadEngine {
        override val name = "one-zero-count"
        val storedVersions = mutableListOf<String?>()
        private var catalog: JdbcDucklakeCatalog? = null
        private val count = Regex("^\\s*SELECT\\s+COUNT\\(\\*\\)\\s+FROM\\s+\\w+\\.(\\w+)\\s*;?\\s*$", RegexOption.IGNORE_CASE)

        override fun connect(attachment: OracleAttachment) {
            catalog?.close()
            catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = server.getJdbcUrl()
                catalogDatabaseUser = server.getUser()
                catalogDatabasePassword = server.getPassword()
                dataPath = attachment.dataPath
                maxCatalogConnections = 1
            }).also { storedVersions += it.getSpecVersion() }
        }

        override fun accepts(sql: String) = count.matches(sql)

        override fun executeQuery(sql: String): List<List<String?>> {
            val table = requireNotNull(count.matchEntire(sql)).groupValues[1]
            val c = requireNotNull(catalog)
            val live = c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                c.getLiveRowCount(requireNotNull(c.getTable("main", table, snapshot)).tableId, snapshot)
            })
            return listOf(listOf(live.toString()))
        }

        override fun close() { catalog?.close() }
    }

    private fun corpusFile(path: String): Pair<SltFile, String> {
        val bytes = Files.readAllBytes(corpusRoot.resolve(path))
        return SltParser.parse(path, String(bytes, Charsets.UTF_8)) to sha256(bytes)
    }

    private fun mirrorRun(source: SltFile, format: ReplaySetupFormat?): Pair<FileResult, OneZeroCountMirror> =
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            OneZeroCountMirror(server).use { engine ->
                // Harness setup only (extension for the PostgreSQL metadata backend), as the other PG mirrors do.
                val setup = SltStatement(0, "INSTALL postgres; LOAD postgres", false, null, null)
                val result = ReplayDriver(engine, repoRoot = repoRoot,
                    metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") }, setupFormat = format)
                    .replay(source.copy(records = listOf(setup) + source.records))
                result to engine
            }
        }

    @Test
    fun `native default creates 1_1 which the 1_0 reader refuses instead of passing`() {
        val (source, _) = corpusFile("general/metadata_cache.test")
        val (result, engine) = mirrorRun(source, null)
        assertThat(engine.storedVersions).containsExactly("1.1")
        val coverage = requireNotNull(result.queryCoverage)
        assertThat(coverage.mirrorPasses).isZero()
        assertThat(coverage.mirrorFailures).isEqualTo(2)
    }

    @Test
    fun `1_0 setup stores spec 1_0 and real 1_0 reader mirrors pass without touching the corpus`() {
        val (source, digest) = corpusFile("general/metadata_cache.test")
        val (result, engine) = mirrorRun(source, ReplaySetupFormat.DUCKLAKE_1_0)
        assertThat(engine.storedVersions).containsExactly("1.0")
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).isEmpty()
        val coverage = requireNotNull(result.queryCoverage)
        assertThat(coverage.mirrorPasses).isEqualTo(2)
        assertThat(coverage.mirrorFailures).isZero()
        assertThat(coverage.oraclePasses).isEqualTo(2)
        assertThat(sha256(Files.readAllBytes(corpusRoot.resolve("general/metadata_cache.test")))).isEqualTo(digest)
    }

    @Test
    fun `an explicit corpus DUCKLAKE_VERSION wins and the 1_0 reader still refuses honestly`() {
        val source = SltParser.parse("explicit.test", """
            require ducklake

            test-env DATA_PATH {TEST_DIR}

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/explicit.db' AS lake (DATA_PATH '{DATA_PATH}/explicit', DUCKLAKE_VERSION '1.1')

            statement ok
            CREATE TABLE lake.t AS FROM range(3) t(i)

            query I
            SELECT COUNT(*) FROM lake.t
            ----
            3
        """.trimIndent())
        val (result, engine) = mirrorRun(source, ReplaySetupFormat.DUCKLAKE_1_0)
        assertThat(engine.storedVersions).containsExactly("1.1")
        assertThat(result.queryCoverage!!.mirrorFailures).isEqualTo(1)
        assertThat(result.queryCoverage!!.mirrorPasses).isZero()
    }

    @Test
    fun `an existing newer catalog fails loudly instead of being downgraded`() {
        val file = SltParser.parse("downgrade.test", """
            require ducklake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/newer.db' AS lake (DATA_PATH '{TEST_DIR}/newer', DUCKLAKE_VERSION '1.1')

            statement ok
            DETACH lake

            statement error
            ATTACH 'ducklake:{TEST_DIR}/newer.db' AS lake
            ----
            Cannot downgrade a DuckLake catalog

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/newer.db' AS again (DUCKLAKE_VERSION '1.1')

            query I
            SELECT value FROM __ducklake_metadata_again.ducklake_metadata WHERE key = 'version'
            ----
            1.1
        """.trimIndent())
        val result = ReplayDriver(repoRoot = repoRoot, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0).replay(file)
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).isEmpty()
        assertThat(result.passed).isEqualTo(5)
    }

    @Test
    fun `CorpusRunner propagates the setup format and the default keeps native 1_1`() {
        val root = Files.createTempDirectory(Path.of(System.getProperty("ducklake.testing.tmpdir", "/tmp/opencode")), "setup-format-")
        try {
            val sql = Files.createDirectories(root.resolve("repo/test/sql"))
            fun write(version: String) = Files.writeString(sql.resolve("version.test"), """
                require ducklake

                statement ok
                ATTACH 'ducklake:{TEST_DIR}/v.db' AS lake (DATA_PATH '{TEST_DIR}/v')

                query I
                SELECT value FROM __ducklake_metadata_lake.ducklake_metadata WHERE key = 'version'
                ----
                $version
            """.trimIndent())
            write("1.0")
            val oneZero = CorpusRunner(sql, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0).run(listOf(sql.resolve("version.test")))
            assertThat(oneZero.failures).isEmpty()
            assertThat(oneZero.totalPassed).isEqualTo(2)
            write("1.1")
            val native = CorpusRunner(sql).run(listOf(sql.resolve("version.test")))
            assertThat(native.failures).isEmpty()
            assertThat(native.totalPassed).isEqualTo(2)
        }
        finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot-pinned attachments still disable mirrors under a setup format`() {
        val source = SltParser.parse("pinned.test", """
            require ducklake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/p.db' AS lake (DATA_PATH '{TEST_DIR}/p')

            statement ok
            CREATE TABLE lake.t AS FROM range(2) t(i)

            statement ok
            DETACH lake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/p.db' AS lake (SNAPSHOT_VERSION 1)

            query I
            SELECT COUNT(*) FROM lake.t
            ----
            2
        """.trimIndent())
        val (result, engine) = mirrorRun(source, ReplaySetupFormat.DUCKLAKE_1_0)
        assertThat(engine.storedVersions).containsExactly("1.0")
        assertThat(result.failed).isEmpty()
        val coverage = requireNotNull(result.queryCoverage)
        assertThat(coverage.mirrorPasses).isZero()
        assertThat(coverage.mirrorSkipReasons.keys.joinToString()).contains("snapshot-pinned")
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
