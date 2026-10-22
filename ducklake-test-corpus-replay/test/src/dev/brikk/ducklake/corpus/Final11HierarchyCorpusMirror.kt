package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeFileKind
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import org.assertj.core.api.Assertions.assertThat
import java.sql.DriverManager
import java.util.function.Supplier

/** Mirrors hierarchy/paths, not native payload scans, exports, uncommitted state or SQL helper implementation. */
internal class Final11HierarchyCorpusMirror(
    private val server: TestingDucklakeDuckDbQuackCatalogServer? = null, private val nativeQuack: Boolean = false,
    private val backend: Final11MirrorBackend? = null,
) : ReplayReadEngine {
    override val name = "final-11-hierarchy"
    private val scratch = DriverManager.getConnection("jdbc:duckdb:")
    private var catalog: JdbcDucklakeCatalog? = null
    private var alias = ""
    var mirrors = 0

    override fun connect(attachment: OracleAttachment) {
        catalog?.close()
        alias = attachment.catalogAlias
        catalog = JdbcDucklakeCatalog(backend?.configuration?.invoke(attachment) ?: quackConfiguration(attachment))
    }

    private fun quackConfiguration(attachment: OracleAttachment): DucklakeCatalogConfig {
        val peer = requireNotNull(server)
        return DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = if (nativeQuack) "jdbc:duckdb:quack://${peer.getHost()}:${peer.getMappedPort()}" else
                "jdbc:quack://${peer.getHost()}:${peer.getMappedPort()}"
            catalogDatabasePassword = peer.getToken()
            dataPath = attachment.dataPath
            maxCatalogConnections = 1
            enableFinal11Reads = true
        }
    }

    override fun accepts(sql: String): Boolean = sql.contains("duckdb_schemas()", true) ||
        (sql.contains("FROM meta.ducklake_schema", true) && sql.contains("parent_schema_id", true)) ||
        (sql.contains("ducklake_list_files('dl', 't')", true) && sql.contains("data_file LIKE", true))

    override fun executeQuery(sql: String): List<List<String?>> {
        scratch.createStatement().use { s -> s.execute("DROP TABLE IF EXISTS hierarchy_schemas; DROP TABLE IF EXISTS hierarchy_files; " +
            "CREATE TABLE hierarchy_schemas(database_name VARCHAR,schema_name VARCHAR,parent_schema VARCHAR); " +
            "CREATE TABLE hierarchy_files(data_file VARCHAR); CREATE SCHEMA IF NOT EXISTS meta; DROP TABLE IF EXISTS meta.ducklake_schema; " +
            "CREATE TABLE meta.ducklake_schema(schema_id BIGINT,schema_name VARCHAR,parent_schema_id BIGINT)") }
        val c = requireNotNull(catalog)
        c.readSession(Supplier {
            val profile = c.readBackendProfile()
            if (nativeQuack) assertThat(profile.accessRoute).isEqualTo("native-quack")
            backend?.let { assertThat(profile.accessRoute).isEqualTo(it.expectedRoute) }
            assertThat(profile.hierarchyAvailable).isTrue()
            if (backend == null) assertThat(c.readMetadata().getReadMetadataCapabilities().qualifiedNestedSchemaResolution).isTrue()
            val h = c.readHierarchy()
            h.listSchemas(profile.snapshotId).forEach { schema ->
                insert("hierarchy_schemas", listOf(alias, schema.metadata.schema.schemaName, schema.names.dropLast(1).lastOrNull()))
                insert("meta.ducklake_schema", listOf(schema.metadata.schema.schemaId, schema.metadata.schema.schemaName, schema.metadata.parentSchemaId))
                // Exercise component lookup separately; identity is never recovered by splitting a dotted name.
                assertThat(h.findSchema(schema.names, profile.snapshotId)!!.schemaIds).isEqualTo(schema.schemaIds)
            }
            if (sql.contains("ducklake_list_files", true)) {
                val table = h.findTable(listOf("main"), "t", profile.snapshotId)!!
                h.listRetainedFiles(DucklakeFileKind.DATA, null, 1024).filter { it.owner.table.tableId == table.table.tableId }
                    .forEach { insert("hierarchy_files", listOf(it.resolvedPath)) }
            }
        })
        mirrors++
        val query = sql.replace("duckdb_schemas()", "hierarchy_schemas", true)
            .replace("ducklake_list_files('dl', 't')", "hierarchy_files", true)
        return scratch.createStatement().use { it.executeQuery(query).use(GoldenComparator::readRows) }
    }

    private fun insert(table: String, values: List<Any?>) {
        scratch.prepareStatement("INSERT INTO $table VALUES (${values.joinToString(",") { "?" }})").use { s ->
            values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
            s.executeUpdate()
        }
    }

    override fun close() {
        try { catalog?.close() }
        finally { scratch.close() }
    }
}
