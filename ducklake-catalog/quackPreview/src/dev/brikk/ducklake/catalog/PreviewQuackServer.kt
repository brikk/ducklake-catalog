/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.ducklake.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Owns only disposable files/processes. Both native peers use the supplied CLI, never embedded JDBC. */
internal class PreviewQuackServer(
    private val format: String,
    private val reportLabel: String = format,
    private val migrateFrom10: Boolean = false,
) : AutoCloseable {
    private val cli = Path.of(System.getProperty("ducklake.preview.cli")).toAbsolutePath()
    private val root = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-quack-v3-$format-")
    private val reports = Files.createDirectories(Path.of(System.getProperty("ducklake.preview.reports")))
    private val extensions = root.resolve("extensions")
    private val database = root.resolve("metadata.db")
    val data: Path = root.resolve("data")
    private val token = UUID.randomUUID().toString()
    private val port = ServerSocket(0).use { it.localPort }
    private var process: Process? = null
    private var sequence = 0
    var seededSnapshotId: Long = -1
        private set

    val url: String get() = "jdbc:quack://127.0.0.1:$port"

    fun start() {
        var ready = false
        try {
            check(Files.isExecutable(cli)) { "Preview CLI is not executable: $cli" }
            val identity = rows("PRAGMA version")
            assertThat(identity.single().take(2)).containsExactly(VERSION, CORE)
            execute("INSTALL quack; INSTALL ducklake; LOAD quack; LOAD ducklake")
            val provenance = rows("LOAD quack; LOAD ducklake; SELECT extension_name,extension_version,installed_from " +
                "FROM duckdb_extensions() WHERE extension_name IN ('quack','ducklake') AND loaded ORDER BY 1")
            assertThat(provenance).containsExactly(listOf("ducklake", DUCKLAKE, "core"), listOf("quack", QUACK, "core"))
            val seedFormat = if (migrateFrom10) "1.0" else format
            execute("LOAD ducklake; ATTACH ${literal("ducklake:$database")} AS lake " +
                "(DATA_PATH ${literal(data.toString())},METADATA_CATALOG 'meta',DUCKLAKE_VERSION '$seedFormat'); " +
                "CREATE TABLE lake.scalar_seed(i INTEGER); INSERT INTO lake.scalar_seed VALUES (11),(22); " +
                "CREATE TABLE lake.nested_seed AS SELECT {'a':42::INTEGER,'b':[1::INTEGER,NULL]} AS s, " +
                "MAP([1::INTEGER],[{'v':'x'}]) AS m; DETACH lake")
            if (migrateFrom10) seedAndMigrate()

            val builder = command(database).redirectErrorStream(true).redirectOutput(reports.resolve("server-$reportLabel.log").toFile())
            val server = builder.start().also { process = it }
            server.outputStream.write(("SET extension_directories=[${literal(extensions.toString())}]; " +
                "LOAD ducklake; LOAD quack; SELECT * FROM quack_serve('quack://127.0.0.1:$port/',token:='$token');\n").toByteArray())
            server.outputStream.flush() // Keep stdin open until close; EOF would terminate the CLI/listener.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var failure: Exception? = null
            while (System.nanoTime() < deadline) {
                check(server.isAlive) { "Preview server exited; inspect server-$reportLabel.log" }
                try {
                    connection().use { c ->
                        assertThat(c.metaData.driverVersion).isEqualTo("0.8.0")
                        val session = c.javaClass.getMethod("session").invoke(c)
                        assertThat(session.javaClass.getMethod("protocolVersion").invoke(session)).isEqualTo(3L)
                        assertThat(sha256(Path.of(c.javaClass.protectionDomain.codeSource.location.toURI()))).isEqualTo(DRIVER_SHA256)
                        assertThat(jdbcRows(c, "PRAGMA version").single().take(2)).containsExactly(VERSION, CORE)
                        assertThat(jdbcRows(c, "SELECT value FROM ducklake_metadata WHERE key='version'")).containsExactly(listOf(format))
                        assertThat(jdbcRows(c, "SELECT current_setting('allow_unsigned_extensions')")).containsExactly(listOf("false"))
                    }
                    Files.writeString(reports.resolve("provenance-$reportLabel.txt"),
                        "cli=$cli\ncli_sha256=${sha256(cli)}\ncore=$CORE\nversion=$VERSION\n" +
                            "ducklake=$DUCKLAKE\nquack=$QUACK\nformat=$format\ndriver=0.8.0\n" +
                            "driver_sha256=$DRIVER_SHA256\nprotocol=3\nunsigned=false\n")
                    ready = true
                    return
                }
                catch (e: java.sql.SQLException) {
                    failure = e
                    Thread.sleep(100)
                }
            }
            error("Preview server readiness timed out: ${failure?.message}")
        }
        finally { if (!ready) close() }
    }

    fun connection(): Connection = DriverManager.getConnection(url, Properties().apply { setProperty("token", token) })

    private fun seedAndMigrate() {
        require(format == "1.1")
        val attach = "LOAD ducklake; ATTACH ${literal("ducklake:$database")} AS lake " +
            "(DATA_PATH ${literal(data.toString())},METADATA_CATALOG 'meta',DUCKLAKE_VERSION '1.0'); "
        execute(attach + "CREATE TABLE lake.migrate_seed(i INTEGER,v VARCHAR); " +
            "CALL lake.set_option('data_inlining_row_limit',0,table_name=>'migrate_seed'); " +
            "INSERT INTO lake.migrate_seed VALUES (1,'file'),(2,'file'),(3,'file'); " +
            "CALL lake.set_option('data_inlining_row_limit',10,table_name=>'migrate_seed'); " +
            "INSERT INTO lake.migrate_seed VALUES (100,'old'); " +
            "ALTER TABLE lake.migrate_seed ADD COLUMN x INTEGER; INSERT INTO lake.migrate_seed VALUES (101,'new',7)")
        seededSnapshotId = rows(attach + "SELECT max(snapshot_id) FROM meta.ducklake_snapshot").single().single()!!.toLong()
        // This is explicit native migration of a disposable copy, never an action by the JVM reader.
        execute("LOAD ducklake; ATTACH ${literal("ducklake:$database")} AS lake " +
            "(DATA_PATH ${literal(data.toString())},METADATA_CATALOG 'meta',AUTOMATIC_MIGRATION TRUE); DETACH lake")
    }

    fun catalog(enableFinal11Reads: Boolean = false): JdbcDucklakeCatalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = url
        catalogDatabasePassword = token
        dataPath = data.toString()
        maxCatalogConnections = 1
        this.enableFinal11Reads = enableFinal11Reads
    })

    fun native(sql: String): List<List<String?>> = rows("LOAD quack; LOAD ducklake; " +
        "CREATE SECRET (TYPE quack,TOKEN '$token'); " +
        "ATTACH 'ducklake:quack:127.0.0.1:$port' AS lake " +
        "(DATA_PATH ${literal(data.toString())},METADATA_CATALOG 'meta',DUCKLAKE_VERSION '$format'); $sql")

    fun execute(sql: String) { run(sql) }

    private fun rows(sql: String): List<List<String?>> {
        return parseRows(run(sql))
    }

    private fun command(db: Path? = null): ProcessBuilder {
        val args = mutableListOf(cli.toString(), "-no-agent", "-batch", "-bail", "-init", "/dev/null")
        if (db != null) args.add(db.toString())
        return ProcessBuilder(args)
    }

    private fun run(sql: String): String {
        val output = root.resolve("command-${sequence++}.json")
        val errors = root.resolve("command-$sequence.err")
        val command = command().command().toMutableList().apply {
            addAll(listOf("-json", "-cmd", "SET extension_directories=[${literal(extensions.toString())}]"))
        }
        val child = ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
        try {
            child.outputStream.use { it.write((sql + ";\n").toByteArray()) }
            check(child.waitFor(60, TimeUnit.SECONDS)) { "Native preview command timed out" }
            check(child.exitValue() == 0) { "Native preview command failed: ${Files.readString(errors)}" }
            return Files.readString(output)
        }
        finally { stop(child) }
    }

    override fun close() {
        process?.let { server ->
            runCatching { server.outputStream.close() }
            stop(server)
        }
        process = null
        if (Files.exists(root)) Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        private val target = System.getProperty("ducklake.quack.target", "release").also {
            require(it in setOf("release", "alpha49096")) { "Unknown explicit Quack qualification target $it" }
        }
        val VERSION = if (target == "release") "v2.0.0" else "v2.0.0-alpha49096"
        val CORE = if (target == "release") "d54cbbe3df" else "2ec7e8401c"
        val QUACK = if (target == "release") "f96420ed46" else "e7a9be3f8c"
        const val DUCKLAKE = "417a9117f5"
        private const val DRIVER_SHA256 = "320ccd96e978e1b30ff7ba77791dd0062bc6e72bd6d160e389b9e2c52269ac9d"

        fun literal(value: String): String = "'" + value.replace("'", "''") + "'"

        /** CLI setup (e.g. CREATE SECRET) can emit a success result before the requested SELECT. */
        fun parseRows(text: String): List<List<String?>> {
            val mapper = ObjectMapper()
            var last: JsonNode? = null
            mapper.factory.createParser(text).use { parser ->
                while (parser.nextToken() != null) last = mapper.readTree(parser)
            }
            val result = last ?: return emptyList()
            check(result.isArray) { "Expected a JSON row array from the native oracle" }
            return result.map { row -> row.map { if (it.isNull) null else it.asText() } }
        }

        fun jdbcRows(connection: Connection, sql: String): List<List<String?>> = connection.createStatement().use { st ->
            st.executeQuery(sql).use { rows ->
                buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }
            }
        }

        private fun stop(process: Process) {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Failed to reap owned native preview process" }
                }
            }
        }

        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(65536)
                var count = input.read(buffer)
                while (count >= 0) {
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
