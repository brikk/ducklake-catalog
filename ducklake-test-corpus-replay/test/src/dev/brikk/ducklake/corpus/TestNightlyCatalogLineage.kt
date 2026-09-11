package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Replays the ten CI-02 nightly records through the real catalog API before a native mirror read. */
class TestNightlyCatalogLineage {
    @Test
    fun `native rewrite fixtures remain readable through nullable catalog models`() {
        val cases = linkedMapOf(
            "compaction/compaction_per_thread_output.test" to setOf(61),
            "rewrite_data_files/test_rewrite_transaction_conflict.test" to setOf(135),
            "stats/min_max_nested_leaf_rewrite_corruption.test" to setOf(47, 53, 59),
            "stats/min_max_optimization_compaction.test" to setOf(48, 90, 151, 162),
            "stats/min_max_optimization_deletes.test" to setOf(80),
        )
        val root = Path.of("ducklake")
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            cases.entries.forEachIndexed { index, (path, lines) ->
                val file = SltParser.parse(path, Files.readString(root.resolve("test/sql/$path")))
                val queries = file.records.filterIsInstance<SltQuery>().filter { it.line in lines }.map { it.sql }.toSet()
                val database = "lineage_$index"
                server.createDatabase(database)
                val uri = server.getDuckDbAttachUri(database).removePrefix("ducklake:")
                CatalogMirror(server, database, queries).use { engine ->
                    val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { uri }).replay(file)
                    assertThat(result.fileSkipReason).isNull()
                    assertThat(result.failed).`as`(path).isEmpty()
                    assertThat(result.skipped).isEmpty()
                    assertThat(engine.embeddedMirrors).`as`("$path must exercise the NULL-start API at every reported read")
                        .isGreaterThanOrEqualTo(lines.size)
                    for (line in lines) {
                        assertThat(result.outcomes.single { it.record?.line == line }).isInstanceOf(RecordOutcome.Pass::class.java)
                    }
                }
            }
        }
    }

    private class CatalogMirror(
        private val server: TestingDucklakePostgreSqlCatalogServer,
        private val database: String,
        private val queries: Set<String>,
    ) : ReplayReadEngine {
        override val name = "catalog-lineage-control"
        private val connection = DriverManager.getConnection("jdbc:duckdb:")
        private lateinit var catalog: JdbcDucklakeCatalog
        var embeddedMirrors = 0

        override fun connect(attachment: OracleAttachment) {
            catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = server.getJdbcUrl(database)
                catalogDatabaseUser = server.getUser()
                catalogDatabasePassword = server.getPassword()
                dataPath = attachment.dataPath
            })
            connection.createStatement().use { st ->
                st.execute("LOAD ducklake")
                st.execute("ATTACH 'ducklake:${attachment.metadataUri}' AS ${attachment.catalogAlias} (DATA_PATH '${attachment.dataPath}')")
            }
        }

        override fun accepts(sql: String): Boolean = sql in queries

        override fun executeQuery(sql: String): List<List<String?>> {
            val snapshot = catalog.currentSnapshotId
            val name = requireNotNull(Regex("ducklake\\.(\\w+)").find(sql)).groupValues[1]
            val table = requireNotNull(catalog.getTable("main", name, snapshot))
            val files = catalog.getDataFiles(table.tableId, snapshot)
            if (files.any { it.rowIdStart == null }) embeddedMirrors++
            val byId = catalog.getDataFilesByIds(table.tableId, files.map { it.dataFileId }).associateBy { it.dataFileId }
            files.forEach { assertThat(byId.getValue(it.dataFileId).rowIdStart).isEqualTo(it.rowIdStart) }
            catalog.getDataFilesAddedBetween(table.tableId, 0, snapshot)
            catalog.getDeletionsBetween(table.tableId, 0, snapshot)
            return connection.createStatement().use { st -> st.executeQuery(sql).use(GoldenComparator::readRows) }
        }

        override fun close() {
            if (::catalog.isInitialized) catalog.close()
            connection.close()
        }
    }
}
