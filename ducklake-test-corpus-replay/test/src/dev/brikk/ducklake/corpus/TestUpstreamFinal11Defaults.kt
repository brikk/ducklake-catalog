package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakeDuckDbQuackCatalogServer
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.function.Supplier

/** Pristine shipped 1.1 SLT goldens first, then the JVM catalog's own decoded inline values. */
class TestUpstreamFinal11Defaults {
    @Test
    fun `upstream added literal default reads back through the final 11 JVM reader`() = qualify(
        "default/add_column_if_not_exists_expression_default.test",
        "3cec752529d12d57dc42891a519eeef9136a5fb69f954b1d1b96075639fe85b9", 1)

    @Test
    fun `upstream cast and JSON default fixtures reach the JVM reader`() = qualify(
        "default/cast_defaults_not_literal.test",
        "ed4ecb85e6ee7f654eab503adc25598735278cfd63943d0fd0048b9a0aa3830b", 2)

    @Test
    fun `upstream literal NULL string and blob defaults retain their values across reopen`() = qualify(
        "issues/issue_1212_string_null_default.test",
        "a527746e673bde1e6df61b2b8b3bddd8641ae801ba01f7b99306e4ca13119bb5", 1)

    private fun qualify(path: String, digest: String, mirrors: Int) {
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val bytes = Files.readAllBytes(root.resolve("test/sql/$path"))
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertThat(actual).`as`("pristine DuckLake 417a9117f590e8141742ac0579d02f6a90162e37 $path").isEqualTo(digest)
        val file = SltParser.parse(path, String(bytes, Charsets.UTF_8))
        TestingDucklakeDuckDbQuackCatalogServer().use { server ->
            InlineCatalogMirror(server).use { engine ->
                // Backend setup only: every upstream statement, line and golden remains unchanged.
                val setup = SltStatement(0, "INSTALL quack; LOAD quack; CREATE SECRET (TYPE quack, TOKEN " +
                    "'${server.getToken().replace("'", "''")}')", expectError = false, expectedError = null, connection = null)
                val prepared = file.copy(records = listOf(setup) + file.records)
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = { server.getQuackAttachUri() }).replay(prepared)
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.mirrors).`as`("$path must execute each intended JVM mirror, not just the native oracle").isEqualTo(mirrors)
            }
        }
    }

    /** Scratch SQL evaluates the original projection over catalog-decoded rows, never a native lake scan. */
    private class InlineCatalogMirror(private val server: TestingDucklakeDuckDbQuackCatalogServer) : ReplayReadEngine {
        override val name = "final-11-catalog-defaults"
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

        private fun source(sql: String): String? = Regex("\\bFROM\\s+${Regex.escape(alias)}\\.([A-Za-z0-9_]+)\\b", RegexOption.IGNORE_CASE)
            .find(sql)?.groupValues?.get(1)

        override fun accepts(sql: String): Boolean = source(sql) != null

        override fun executeQuery(sql: String): List<List<String?>> {
            val c = requireNotNull(catalog)
            val tableName = requireNotNull(source(sql))
            c.readSession(Supplier {
                assertThat(c.getSpecVersion()).isEqualTo("1.1")
                val snapshot = c.currentSnapshotId
                val table = requireNotNull(c.getTable("main", tableName, snapshot))
                assertThat(c.getDataFiles(table.tableId, snapshot)).`as`("this mirror must read ONLY JVM-decoded inline rows").isEmpty()
                val columns = c.getTableColumns(table.tableId, snapshot)
                val values = c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                    c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
                }
                val target = "${quote(alias)}.${quote(tableName)}"
                val types = columns.map { physicalType(it.columnType) }
                val ddl = columns.mapIndexed { index, col -> "${quote(col.columnName)} ${types[index]}" }.joinToString(",")
                scratch.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA IF NOT EXISTS ${quote(alias)}; DROP TABLE IF EXISTS $target; CREATE TABLE $target($ddl)")
                }
                populate(target, types, values)
                Unit
            })
            mirrors++
            return scratch.createStatement().use { statement -> statement.executeQuery(sql).use(GoldenComparator::readRows) }
        }

        private fun populate(target: String, types: List<String>, values: List<List<Any?>>) {
            val parameters = types.joinToString(",") { "CAST(? AS $it)" }
            scratch.prepareStatement("INSERT INTO $target VALUES ($parameters)").use { statement ->
                for (row in values) {
                    row.forEachIndexed { index, value ->
                        if (value is ByteArray) statement.setBytes(index + 1, value) else statement.setObject(index + 1, value)
                    }
                    statement.executeUpdate()
                }
            }
        }

        private fun physicalType(type: String): String = when (type) {
            "int32" -> "INTEGER"
            "varchar" -> "VARCHAR"
            "blob" -> "BLOB"
            "json" -> "JSON"
            else -> error("This focused upstream mirror has not qualified $type")
        }

        private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""

        override fun close() {
            try { catalog?.close() }
            finally { scratch.close() }
        }
    }
}
