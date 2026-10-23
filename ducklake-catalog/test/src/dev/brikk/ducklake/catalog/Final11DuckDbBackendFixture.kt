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

import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.security.MessageDigest
import java.util.Properties

/** Native setup is deliberately outside library readers. Every backend owns its files/server. */
internal class Final11DuckDbBackendFixture(val route: Final11ReadRoute, private val migrate: Boolean = false) : AutoCloseable {
    val root: Path = Files.createTempDirectory(Files.createDirectories(Path.of("/tmp/opencode")), "catalog-r06-")
    val data: Path = Files.createDirectories(root.resolve("data"))
    val file: Path = root.resolve("metadata.db")
    private val server: TestingDucklakeDuckDbQuackCatalogServer? =
        if (route == Final11ReadRoute.LOCAL_DUCKDB) null else TestingDucklakeDuckDbQuackCatalogServer()
    private var initialized = false
    val readerUrl: String get() = when (route) {
        Final11ReadRoute.LOCAL_DUCKDB -> "jdbc:duckdb:$file"
        Final11ReadRoute.NATIVE_QUACK -> "jdbc:duckdb:quack://${server!!.getHost()}:${server.getMappedPort()}?metadata_catalog=r06_meta"
        Final11ReadRoute.JAVA_QUACK -> "jdbc:quack://${server!!.getHost()}:${server.getMappedPort()}"
        Final11ReadRoute.POSTGRES -> error("Use Final11PostgresFixture for PostgreSQL")
        Final11ReadRoute.MYSQL -> error("Use Final11MySqlFixture for MySQL")
    }
    private fun q(value: String) = "'${value.replace("'", "''")}'"

    fun native(sql: String) {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            // DuckDB's JDBC metadata reports "1.0"; qualify the published artifact independently.
            requirePinnedJdbc()
            s.execute("INSTALL ducklake; LOAD ducklake")
            s.executeQuery("PRAGMA version").use { r ->
                assertThat(r.next()).isTrue()
                assertThat(r.getString(1)).isEqualTo("v2.0.0")
                assertThat(r.getString(2)).isEqualTo("d54cbbe3df")
            }
            s.executeQuery("SELECT extension_version,current_setting('allow_unsigned_extensions') FROM duckdb_extensions() " +
                "WHERE extension_name='ducklake'").use { r ->
                assertThat(r.next()).isTrue()
                assertThat(r.getString(1)).isEqualTo("417a9117f5")
                assertThat(r.getBoolean(2)).isFalse()
            }
            val target = if (server == null) file.toString() else {
                s.execute("INSTALL quack; LOAD quack; CREATE SECRET(TYPE quack,TOKEN ${q(server.getToken())})")
                s.executeQuery("SELECT extension_version FROM duckdb_extensions() WHERE extension_name='quack'").use { r ->
                    assertThat(r.next()).isTrue()
                    assertThat(r.getString(1)).isEqualTo("f96420ed46")
                }
                server.getQuackAttachUri()
            }
            val version = if (!initialized && migrate) "1.0" else "1.1"
            s.execute("ATTACH ${q("ducklake:$target")} AS lake(DATA_PATH ${q(data.toString())},METADATA_CATALOG 'meta'," +
                "DUCKLAKE_VERSION '$version',AUTOMATIC_MIGRATION ${if (initialized && migrate) "TRUE" else "FALSE"})")
            s.execute(sql)
            s.execute("DETACH lake")
            initialized = true
        } }
    }

    fun metadata(sql: String) {
        metadataConnection().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun metadataTableCount(): Long = metadataConnection().use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'ducklake_%'").use { r ->
            check(r.next())
            r.getLong(1)
        }
    } }

    fun snapshot(): Long = metadataConnection().use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT max(snapshot_id) FROM ducklake_snapshot").use { r -> check(r.next()); r.getLong(1) }
    } }

    fun inlineInfo(table: String): InlineInfo = metadataConnection().use { c ->
        c.prepareStatement("SELECT i.table_id,i.schema_version,i.table_name FROM ducklake_inlined_data_tables i " +
            "JOIN ducklake_table t ON t.table_id=i.table_id WHERE t.table_name=? ORDER BY i.schema_version LIMIT 1").use { s ->
            s.setString(1, table)
            s.executeQuery().use { r ->
                check(r.next())
                InlineInfo(r.getLong(1), r.getLong(2), r.getString(3))
            }
        }
    }

    data class InlineInfo(val tableId: Long, val schemaVersion: Long, val tableName: String)

    fun metadataConnection(): Connection = if (server == null) DriverManager.getConnection("jdbc:duckdb:$file") else
        DriverManager.getConnection("jdbc:quack://${server.getHost()}:${server.getMappedPort()}", Properties().apply {
            setProperty("token", server.getToken())
        })

    fun reader(): JdbcDucklakeCatalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = readerUrl
        catalogDatabasePassword = server?.getToken()
        dataPath = data.toString()
        maxCatalogConnections = 1
        enableFinal11Reads = true
    })

    override fun close() {
        try { server?.close() }
        finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    companion object {
        fun requirePinnedJdbc() {
            assertThat(jdbcSha256).isEqualTo("5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186")
        }
        // Maven Central org.duckdb:duckdb_jdbc:2.0.0.0 .jar.sha256, checked independently of driverVersion.
        private val jdbcSha256: String by lazy {
            val jar = Path.of(Class.forName("org.duckdb.DuckDBDriver").protectionDomain.codeSource.location.toURI())
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(jar).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
