package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import java.sql.DriverManager
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat

/** Scratch metadata is populated ONLY from public JVM read APIs, never native metadata SELECTs. */
internal class Final11MetadataCorpusMirror(
    private val server: TestingDucklakeDuckDbQuackCatalogServer? = null, private val nativeQuack: Boolean = false,
    private val backend: Final11MirrorBackend? = null,
) : ReplayReadEngine {
    override val name = "final-11-rich-metadata"
    private val scratch = DriverManager.getConnection("jdbc:duckdb:")
    private var catalog: JdbcDucklakeCatalog? = null
    var mirrors = 0

    override fun connect(attachment: OracleAttachment) {
        catalog?.close()
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

    override fun accepts(sql: String): Boolean = sql.contains("dl.ducklake_", ignoreCase = true) &&
        (sql.contains("row_group_count", ignoreCase = true) || sql.contains("min_is_exact", ignoreCase = true)) &&
        !sql.contains("getvariable", ignoreCase = true)

    override fun executeQuery(sql: String): List<List<String?>> {
        scratch.createStatement().use { s ->
            s.execute("DROP SCHEMA IF EXISTS dl CASCADE; CREATE SCHEMA dl; " +
                "CREATE TABLE dl.ducklake_table(table_id BIGINT,table_name VARCHAR); " +
                "CREATE TABLE dl.ducklake_data_file(data_file_id BIGINT,table_id BIGINT,record_count BIGINT,row_group_count BIGINT,end_snapshot BIGINT); " +
                "CREATE TABLE dl.ducklake_delete_file(delete_file_id BIGINT,table_id BIGINT,delete_count BIGINT,row_group_count BIGINT,format VARCHAR); " +
                "CREATE TABLE dl.ducklake_file_column_stats(data_file_id BIGINT,table_id BIGINT,column_id BIGINT," +
                "min_value VARCHAR,max_value VARCHAR,min_is_exact BOOLEAN,max_is_exact BOOLEAN); " +
                "CREATE TABLE dl.ducklake_table_column_stats(table_id BIGINT,column_id BIGINT," +
                "min_value VARCHAR,max_value VARCHAR,min_is_exact BOOLEAN,max_is_exact BOOLEAN)")
        }
        val c = requireNotNull(catalog)
        c.readSession(Supplier {
            val metadata = c.readMetadata()
            val profile = c.readBackendProfile()
            if (nativeQuack) assertThat(profile.accessRoute).isEqualTo("native-quack")
            backend?.let { assertThat(profile.accessRoute).isEqualTo(it.expectedRoute) }
            assertThat(profile.richMetadataAvailable).isTrue()
            val snapshot = profile.snapshotId
            for (schema in c.listSchemas(snapshot)) for (table in c.listTables(schema.schemaId, snapshot)) {
                insert("ducklake_table", listOf(table.tableId, table.tableName))
                metadata.getFileReadMetadata(table.tableId, snapshot).forEach { f ->
                    insert("ducklake_data_file", listOf(f.dataFileId, table.tableId, f.recordCount, f.rowGroupCount, null))
                    if (f.deleteFileId != null) insert("ducklake_delete_file",
                        listOf(f.deleteFileId, table.tableId, f.deleteCount, f.deleteRowGroupCount, f.deleteFileFormat))
                }
                metadata.getFileColumnMetadata(table.tableId, snapshot).forEach { f -> insert("ducklake_file_column_stats",
                    listOf(f.dataFileId, table.tableId, f.stats.columnId, f.stats.minValue, f.stats.maxValue, f.minIsExact, f.maxIsExact)) }
                metadata.getTableColumnMetadata(table.tableId).forEach { s -> insert("ducklake_table_column_stats",
                    listOf(table.tableId, s.columnId, s.minValue, s.maxValue, s.minIsExact, s.maxIsExact)) }
            }
        })
        mirrors++
        return scratch.createStatement().use { it.executeQuery(sql).use(GoldenComparator::readRows) }
    }

    private fun insert(table: String, values: List<Any?>) {
        scratch.prepareStatement("INSERT INTO dl.$table VALUES (${values.joinToString(",") { "?" }})").use { s ->
            values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
            s.executeUpdate()
        }
    }

    override fun close() {
        try { catalog?.close() }
        finally { scratch.close() }
    }
}
