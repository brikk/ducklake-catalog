package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.InlinedTypeNode
import dev.brikk.ducklake.catalog.InlinedValues
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import org.assertj.core.api.Assertions.assertThat
import java.sql.DriverManager
import java.util.function.Supplier

/** Evaluate pristine SQL over ONLY JVM-decoded inline rows, never over a native DuckLake scan. */
internal class Final11InlineCorpusMirror(
    private val server: TestingDucklakeDuckDbQuackCatalogServer,
    private val allowUnqualified: Boolean = false,
    private val mirrorLimit: Int = Int.MAX_VALUE,
) : ReplayReadEngine {
    override val name = "final-11-catalog-inline"
    private val scratch = DriverManager.getConnection("jdbc:duckdb:")
    private var catalog: JdbcDucklakeCatalog? = null
    private var alias: String = ""
    var mirrors = 0

    override fun connect(attachment: OracleAttachment) {
        catalog?.close()
        alias = attachment.catalogAlias
        catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = "jdbc:quack://${server.getHost()}:${server.getMappedPort()}"
            catalogDatabasePassword = server.getToken()
            dataPath = attachment.dataPath
            maxCatalogConnections = 1
            enableFinal11Reads = true
        })
    }

    private fun source(sql: String): String? {
        val qualified = Regex("\\bFROM\\s+${Regex.escape(alias)}\\.([A-Za-z0-9_]+)\\b", RegexOption.IGNORE_CASE)
            .find(sql)?.groupValues?.get(1)
        if (qualified != null || !allowUnqualified) return qualified
        return Regex("\\bFROM\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:ORDER\\b|WHERE\\b|$)", RegexOption.IGNORE_CASE)
            .find(sql)?.groupValues?.get(1)
    }

    override fun accepts(sql: String): Boolean = mirrors < mirrorLimit && source(sql) != null

    override fun executeQuery(sql: String): List<List<String?>> {
        val c = requireNotNull(catalog)
        val tableName = requireNotNull(source(sql))
        c.readSession(Supplier {
            assertThat(c.getSpecVersion()).isEqualTo("1.1")
            val snapshot = c.currentSnapshotId
            val table = requireNotNull(c.getTable("main", tableName, snapshot))
            assertThat(c.getDataFiles(table.tableId, snapshot)).`as`("the mirror must read ONLY JVM-decoded inline rows").isEmpty()
            val columns = c.getTableColumns(table.tableId, snapshot)
            val all = c.getAllColumnsWithParentage(table.tableId, snapshot)
            val types = all.filter { it.parentColumn == null }.associate { it.columnId to InlinedValues.typeTree(it, all) }
            val values = c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
            }
            val target = "${quote(alias)}.${quote(tableName)}"
            val nodes = columns.map { types.getValue(it.columnId) }
            val ddl = columns.mapIndexed { index, col -> "${quote(col.columnName)} ${physicalType(nodes[index])}" }.joinToString(",")
            scratch.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA IF NOT EXISTS ${quote(alias)}; DROP TABLE IF EXISTS $target; " +
                    "CREATE TABLE $target($ddl); USE ${quote(alias)}")
            }
            values.forEach { populate(target, nodes, it) }
            Unit
        })
        mirrors++
        return scratch.createStatement().use { statement -> statement.executeQuery(sql).use(GoldenComparator::readRows) }
    }

    private fun populate(target: String, types: List<InlinedTypeNode>, row: List<Any?>) {
        val bindings = mutableListOf<Any?>()
        val fields = row.mapIndexed { index, value -> expression(value, types[index], bindings) }
        scratch.prepareStatement("INSERT INTO $target VALUES (${fields.joinToString(",")})").use { statement ->
            bindings.forEachIndexed { index, value ->
                if (value is ByteArray) statement.setBytes(index + 1, value) else statement.setObject(index + 1, value)
            }
            statement.executeUpdate()
        }
    }

    private fun expression(value: Any?, type: InlinedTypeNode, bindings: MutableList<Any?>): String {
        val sql = physicalType(type)
        if (value == null) return "CAST(NULL AS $sql)"
        val expression = when (type.type) {
            "struct" -> {
                val fields = value as Map<*, *>
                check(fields.keys == type.children.map { it.name }.toSet()) { "JVM STRUCT omitted or invented a field" }
                "struct_pack(" + type.children.joinToString(",") {
                    "${quote(it.name)} := ${expression(fields[it.name], it, bindings)}"
                } + ")"
            }
            "list" -> (value as List<*>).joinToString(",", "[", "]") { expression(it, type.children.single(), bindings) }
            "map" -> {
                val entries = (value as Map<*, *>).entries
                val keys = entries.joinToString(",", "[", "]") { expression(it.key, type.children[0], bindings) }
                val values = entries.joinToString(",", "[", "]") { expression(it.value, type.children[1], bindings) }
                "map($keys,$values)"
            }
            else -> { bindings.add(value); "?" }
        }
        return "CAST($expression AS $sql)"
    }

    private fun physicalType(type: InlinedTypeNode): String = when (type.type) {
        "int8" -> "TINYINT"
        "int16" -> "SMALLINT"
        "int32" -> "INTEGER"
        "int64" -> "BIGINT"
        "varchar" -> "VARCHAR"
        "blob" -> "BLOB"
        "json" -> "JSON"
        "struct" -> "STRUCT(" + type.children.joinToString(",") { "${quote(it.name)} ${physicalType(it)}" } + ")"
        "list" -> physicalType(type.children.single()) + "[]"
        "map" -> "MAP(${physicalType(type.children[0])},${physicalType(type.children[1])})"
        else -> error("This focused upstream mirror has not qualified ${type.type}")
    }

    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""

    override fun close() {
        try { catalog?.close() }
        finally { scratch.close() }
    }
}
