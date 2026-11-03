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

    private fun mirrorRun(
        source: SltFile,
        format: ReplaySetupFormat?,
        scope: Set<ReplaySetupFormat>? = null,
        rewrite: MetadataRewriteScope = MetadataRewriteScope.ALL_ATTACHES,
    ): Pair<FileResult, OneZeroCountMirror> =
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            OneZeroCountMirror(server).use { engine ->
                // Harness setup only (extension for the PostgreSQL metadata backend), as the other PG mirrors do.
                val setup = SltStatement(0, "INSTALL postgres; LOAD postgres", false, null, null)
                val result = ReplayDriver(engine, repoRoot = repoRoot,
                    metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") }, setupFormat = format,
                    mirrorFormatScope = scope, metadataRewriteScope = rewrite)
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

    private val migratedMidFile = SltParser.parse("mixed.test", """
        require ducklake

        test-env DUCKLAKE_CONNECTION {TEST_DIR}/{UUID}.db

        statement ok
        ATTACH 'ducklake:{DUCKLAKE_CONNECTION}' AS lake (DATA_PATH '{TEST_DIR}/mixed')

        statement ok
        CREATE TABLE lake.t AS FROM range(4) t(i)

        query I
        SELECT COUNT(*) FROM lake.t
        ----
        4

        statement ok
        DETACH lake

        statement ok
        ATTACH 'ducklake:{DUCKLAKE_CONNECTION}' AS lake (DATA_PATH '{TEST_DIR}/mixed', DUCKLAKE_VERSION '1.1', AUTOMATIC_MIGRATION TRUE)

        query I
        SELECT COUNT(*) FROM lake.t
        ----
        4
    """.trimIndent())

    @Test
    fun `a 1_0 format scope measures an out-of-scope 1_1 lake and keeps earlier mirror outcomes`() {
        val (scoped, _) = mirrorRun(migratedMidFile, ReplaySetupFormat.DUCKLAKE_1_0, setOf(ReplaySetupFormat.DUCKLAKE_1_0))
        assertThat(scoped.failed).isEmpty()
        val coverage = requireNotNull(scoped.queryCoverage)
        assertThat(coverage.oraclePasses).isEqualTo(2)
        assertThat(coverage.mirrorPasses).isEqualTo(1)
        assertThat(coverage.mirrorFailures).isZero()
        assertThat(coverage.mirrorSkipReasons).containsEntry("format-out-of-scope:1.1", 1)
        // Without a scope the 1.0 reader is driven into the migrated 1.1 lake and must fail, not silently pass.
        val (unscoped, _) = mirrorRun(migratedMidFile, ReplaySetupFormat.DUCKLAKE_1_0)
        assertThat(unscoped.queryCoverage!!.mirrorPasses).isEqualTo(1)
        assertThat(unscoped.queryCoverage!!.mirrorFailures).isEqualTo(1)
    }

    @Test
    fun `connection-env rewrite scope keeps hardcoded local metadata fixtures local and measured`() {
        val path = "view/view_null_column_aliases.test"
        val (source, digest) = corpusFile(path)
        val (forced, _) = mirrorRun(source, ReplaySetupFormat.DUCKLAKE_1_0)
        assertThat(forced.failed).hasSize(2)
        val (scoped, engine) = mirrorRun(source, ReplaySetupFormat.DUCKLAKE_1_0, rewrite = MetadataRewriteScope.CONNECTION_ENV_ONLY)
        assertThat(scoped.fileSkipReason).isNull()
        assertThat(scoped.failed).isEmpty()
        assertThat(engine.storedVersions).isEmpty()
        val coverage = requireNotNull(scoped.queryCoverage)
        assertThat(coverage.mirrorPasses).isZero()
        assertThat(coverage.mirrorSkipReasons.keys).containsOnly("backend-local-fixture")
        assertThat(sha256(Files.readAllBytes(corpusRoot.resolve(path)))).isEqualTo(digest)
        // Connection-env lakes are still moved to the backend and mirrored.
        val (env, envEngine) = mirrorRun(corpusFile("general/metadata_cache.test").first, ReplaySetupFormat.DUCKLAKE_1_0,
            rewrite = MetadataRewriteScope.CONNECTION_ENV_ONLY)
        assertThat(envEngine.storedVersions).containsExactly("1.0")
        assertThat(env.queryCoverage!!.mirrorPasses).isEqualTo(2)
    }

    /** Test-only stand-in for a consumer's AST binder: rebinds only rewritten lakes' metadata-catalog schema. */
    private val postgresMetadataSchema = ReplayOracleSqlBinder { sql, context ->
        context.lakes.filter { it.rewritten && it.explicitMetadataSchema == null }.fold(sql) { text, lake ->
            text.replace("${lake.metadataCatalog}.main.", "${lake.metadataCatalog}.public.")
        }
    }

    @Test
    fun `oracle SQL binder runs the original view fixture natively on PostgreSQL metadata`() {
        val path = "view/view_null_column_aliases.test"
        val (source, digest) = corpusFile(path)
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val setup = SltStatement(0, "INSTALL postgres; LOAD postgres", false, null, null)
            val result = ReplayDriver(repoRoot = repoRoot, metadataRewriter = { server.getDuckDbAttachUri().removePrefix("ducklake:") },
                setupFormat = ReplaySetupFormat.DUCKLAKE_1_0, oracleSqlBinder = postgresMetadataSchema)
                .replay(source.copy(records = listOf(setup) + source.records))
            assertThat(result.fileSkipReason).isNull()
            assertThat(result.failed).isEmpty()
            assertThat(result.skipped).isEmpty()
            assertThat(result.passed).isEqualTo(source.records.count { it is SltStatement || it is dev.brikk.ducklake.slt.SltQuery } + 1)
            // The fixture's metadata mutation really happened in PostgreSQL's ducklake_view.
            java.sql.DriverManager.getConnection(server.getJdbcUrl(), server.getUser(), server.getPassword()).use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT view_name, column_aliases IS NULL FROM public.ducklake_view ORDER BY view_name").use { r ->
                        val rows = buildList { while (r.next()) add(r.getString(1) to r.getBoolean(2)) }
                        assertThat(rows).contains("v_plain_null" to true, "v_alias" to false)
                    }
                }
                c.createStatement().use { s ->
                    s.executeQuery("SELECT value FROM public.ducklake_metadata WHERE key = 'version'").use { r ->
                        assertThat(r.next()).isTrue()
                        assertThat(r.getString(1)).isEqualTo("1.0")
                    }
                }
            }
        }
        assertThat(sha256(Files.readAllBytes(corpusRoot.resolve(path)))).isEqualTo(digest)
    }

    @Test
    fun `oracle SQL binder sees statements and queries on every connection with live lake context`() {
        val seen = mutableListOf<Triple<String?, String, List<String>>>()
        val binder = ReplayOracleSqlBinder { sql, context ->
            val lakes = context.lakes.map { "${it.alias}|${it.metadataCatalog}|${it.explicitMetadataSchema}|${it.rewritten}" }
            seen += Triple(context.connection, sql.trim(), lakes)
            sql
        }
        val file = SltParser.parse("binder.test", """
            require ducklake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/b.db' AS lake (DATA_PATH '{TEST_DIR}/b')

            statement ok con1
            CREATE TABLE lake.t AS SELECT 1 i

            query I con1
            SELECT i FROM lake.t
            ----
            1

            statement ok
            DETACH lake

            query I
            SELECT 2
            ----
            2
        """.trimIndent())
        val result = ReplayDriver(repoRoot = repoRoot, oracleSqlBinder = binder).replay(file)
        assertThat(result.failed).isEmpty()
        val lake = listOf("lake|__ducklake_metadata_lake|null|false")
        assertThat(seen.map { it.first to it.third }).containsExactly(
            null to emptyList(), "con1" to lake, "con1" to lake, null to lake, null to emptyList())
        assertThat(seen.map { it.second }).contains("CREATE TABLE lake.t AS SELECT 1 i", "SELECT i FROM lake.t")
    }

    private fun scoped(sql: String, scope: ReplaySetupFormat = ReplaySetupFormat.DUCKLAKE_1_0) =
        ReplayDriver(repoRoot = repoRoot, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0, mirrorFormatScope = setOf(scope))
            .replay(SltParser.parse("inspect.test", sql.trimIndent()))

    @Test
    fun `a failing format inspection fails the attach loudly and keeps earlier outcomes`() {
        val result = scoped("""
            require ducklake

            query I
            SELECT 1
            ----
            1

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/f.db' AS lake (DATA_PATH '{TEST_DIR}/f'); ALTER TABLE __ducklake_metadata_lake.ducklake_metadata RENAME TO renamed_metadata
        """)
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.failed).singleElement().satisfies({
            assertThat(it.reason).contains("mirror format inspection failed").contains("cannot read the stored format of 'lake'")
        })
    }

    @Test
    fun `an ambiguous global version fails loudly while a table-scoped version row cannot mask it`() {
        val prefix = """
            require ducklake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/d.db' AS lake (DATA_PATH '{TEST_DIR}/d')

            statement ok
            CREATE TABLE lake.t(i INTEGER)
        """.trimIndent()
        val reattach = """
            statement ok
            DETACH lake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/d.db' AS lake (DATA_PATH '{TEST_DIR}/d')
        """.trimIndent()
        val duplicate = scoped(prefix + "\n\n" + """
            statement ok
            INSERT INTO __ducklake_metadata_lake.ducklake_metadata (key, value, scope, scope_id) VALUES ('version', '1.0', NULL, NULL)
        """.trimIndent() + "\n\n" + reattach)
        assertThat(duplicate.failed).singleElement().satisfies({
            assertThat(it.reason).contains("mirror format inspection failed").contains("ambiguous")
        })
        // Scope {1.1}: reading the scoped '1.1' row too would be ambiguous; the global 1.0 is positively out of scope.
        val masked = scoped(prefix + "\n\n" + """
            statement ok
            INSERT INTO __ducklake_metadata_lake.ducklake_metadata (key, value, scope, scope_id) VALUES ('version', '1.1', 'table', 1)
        """.trimIndent() + "\n\n" + reattach, ReplaySetupFormat.DUCKLAKE_1_1)
        assertThat(masked.failed).isEmpty()
        assertThat(masked.passed).isEqualTo(5)
    }

    @Test
    fun `a 1_0 execution scope halts a mixed file at its 1_1 attach and keeps earlier outcomes`() {
        val scoped = ReplayDriver(repoRoot = repoRoot, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0,
            executionFormatScope = setOf(ReplaySetupFormat.DUCKLAKE_1_0)).replay(migratedMidFile)
        assertThat(scoped.failed).isEmpty()
        assertThat(scoped.passed).isEqualTo(5) // ATTACH, CREATE, query, DETACH, migrating ATTACH; the 1.1 query never runs
        assertThat(scoped.queryCoverage!!.oraclePasses).isEqualTo(1)
        assertThat(scoped.haltedMidFile).isTrue()
        assertThat(scoped.fileSkipReason).startsWith("format-out-of-scope: stored DuckLake 1.1 outside execution scope 1.0 at line")
        val report = CorpusReport(listOf(scoped))
        assertThat(report.ranFiles).isEmpty()
        assertThat(report.summary()).contains("halted mid-file by format scope 1")
        // Default: no execution scope, the whole file runs.
        val unscoped = ReplayDriver(repoRoot = repoRoot, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0).replay(migratedMidFile)
        assertThat(unscoped.fileSkipReason).isNull()
        assertThat(unscoped.passed).isEqualTo(6)
    }

    @Test
    fun `an execution scope halt never swallows the attach record's own expectation failure`() {
        val result = ReplayDriver(repoRoot = repoRoot, executionFormatScope = setOf(ReplaySetupFormat.DUCKLAKE_1_0))
            .replay(SltParser.parse("expect.test", """
                require ducklake

                statement error
                ATTACH 'ducklake:{TEST_DIR}/e.db' AS lake (DATA_PATH '{TEST_DIR}/e', DUCKLAKE_VERSION '1.1')
                ----

                statement ok
                CREATE TABLE lake.never(i INTEGER)
            """.trimIndent()))
        assertThat(result.failed).singleElement().satisfies({ assertThat(it.reason).contains("expected an error") })
        assertThat(result.passed).isZero()
        assertThat(result.fileSkipReason).startsWith("format-out-of-scope: stored DuckLake 1.1")
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
