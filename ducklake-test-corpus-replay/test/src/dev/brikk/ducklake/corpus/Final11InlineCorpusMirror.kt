package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.InlinedTypeNode
import dev.brikk.ducklake.catalog.InlinedValues
import dev.brikk.ducklake.catalog.DucklakeInterval
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import org.assertj.core.api.Assertions.assertThat
import java.sql.DriverManager
import java.time.LocalTime
import java.time.OffsetTime
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.function.Supplier

/** Evaluate pristine SQL over ONLY JVM-decoded inline rows, never over a native DuckLake scan. */
internal class Final11InlineCorpusMirror(
    private val server: TestingDucklakeDuckDbQuackCatalogServer? = null,
    private val allowUnqualified: Boolean = false,
    private val mirrorLimit: Int = Int.MAX_VALUE,
    private val payloadQuery: (String) -> Boolean = { true },
    private val queryTimeZone: (String) -> String = { "UTC" },
    private val nativeQuack: Boolean = false,
    private val backend: Final11MirrorBackend? = null,
) : ReplayReadEngine {
    override val name = "final-11-catalog-inline"
    private val scratch = DriverManager.getConnection("jdbc:duckdb:")
    private var catalog: JdbcDucklakeCatalog? = null
    private var alias: String = ""
    var mirrors = 0

    override fun connect(attachment: OracleAttachment) {
        catalog?.close()
        alias = attachment.catalogAlias
        val config = backend?.configuration?.invoke(attachment) ?: quackConfiguration(attachment)
        catalog = JdbcDucklakeCatalog(config)
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

    private fun source(sql: String): String? {
        val qualified = Regex("\\bFROM\\s+${Regex.escape(alias)}\\.([A-Za-z0-9_]+)\\b", RegexOption.IGNORE_CASE)
            .find(sql)?.groupValues?.get(1)
        if (qualified != null || !allowUnqualified) return qualified
        return Regex("\\bFROM\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:ORDER\\b|WHERE\\b|$)", RegexOption.IGNORE_CASE)
            .find(sql)?.groupValues?.get(1)
    }

    override fun accepts(sql: String): Boolean = mirrors < mirrorLimit && source(sql) != null && payloadQuery(sql) &&
        !sql.contains("information_schema", ignoreCase = true) && !Regex("\\bAT\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(sql)

    override fun executeQuery(sql: String): List<List<String?>> {
        val c = requireNotNull(catalog)
        val tableName = requireNotNull(source(sql))
        val zone = queryTimeZone(sql).replace("'", "''")
        scratch.createStatement().use { it.execute("SET TimeZone='$zone'") }
        c.readSession(Supplier {
            assertThat(c.getSpecVersion()).isEqualTo("1.1")
            if (nativeQuack) assertThat(c.readMetadata().getReadMetadataCapabilities().accessRoute).isEqualTo("native-quack")
            backend?.let { assertThat(c.readBackendProfile().accessRoute).isEqualTo(it.expectedRoute) }
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
            "interval" -> {
                val interval = value as DucklakeInterval
                bindings.addAll(listOf(interval.months, interval.days, interval.micros))
                "(to_months(CAST(? AS INTEGER))+to_days(CAST(? AS INTEGER))+to_microseconds(CAST(? AS BIGINT)))"
            }
            else -> { bindings.add(if (type.type in TEMPORAL) temporalText(value) else value); "?" }
        }
        return "CAST($expression AS $sql)"
    }

    private fun physicalType(type: InlinedTypeNode): String {
        SCALAR_SQL[type.type]?.let { return it }
        if (Regex("decimal\\([0-9]{1,2},[0-9]{1,2}\\)").matches(type.type)) return type.type
        return when (type.type) {
            "struct" -> "STRUCT(" + type.children.joinToString(",") { "${quote(it.name)} ${physicalType(it)}" } + ")"
            "list" -> physicalType(type.children.single()) + "[]"
            "map" -> "MAP(${physicalType(type.children[0])},${physicalType(type.children[1])})"
            else -> error("This focused upstream mirror has not qualified ${type.type}")
        }
    }

    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""

    private fun temporalText(value: Any): String = when (value) {
        is OffsetTime -> value.toLocalTime().format(TIME_TEXT) + value.offset.id.replace("Z", "+00")
        is LocalTime -> value.format(TIME_TEXT)
        is OffsetDateTime -> value.toLocalDate().toString() + " " + value.toLocalTime().format(TIME_TEXT) + value.offset.id.replace("Z", "+00")
        is LocalDateTime -> value.toLocalDate().toString() + " " + value.toLocalTime().format(TIME_TEXT)
        else -> value.toString()
    }

    override fun close() {
        try { catalog?.close() }
        finally { scratch.close() }
    }

    companion object {
        private val SCALAR_SQL = mapOf("int8" to "TINYINT", "int16" to "SMALLINT", "int32" to "INTEGER", "int64" to "BIGINT",
            "uint8" to "UTINYINT", "float64" to "DOUBLE", "boolean" to "BOOLEAN", "varchar" to "VARCHAR", "blob" to "BLOB",
            "json" to "JSON", "null" to "\"NULL\"", "date" to "DATE", "time" to "TIME", "time_ns" to "TIME_NS",
            "timetz" to "TIME WITH TIME ZONE", "timestamp" to "TIMESTAMP", "timestamptz" to "TIMESTAMP WITH TIME ZONE",
            "timestamptz_ns" to "TIMESTAMPTZ_NS", "interval" to "INTERVAL")
        private val TEMPORAL = setOf("date", "time", "time_ns", "timetz", "timestamp", "timestamptz", "timestamptz_ns")
        private val TIME_TEXT: DateTimeFormatter = DateTimeFormatterBuilder().appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter()
    }
}

internal data class Final11MirrorBackend(val expectedRoute: String, val configuration: (OracleAttachment) -> DucklakeCatalogConfig)
