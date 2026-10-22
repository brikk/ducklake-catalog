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
import java.sql.DriverManager

/** Native setup and direct SQL observation, always outside the library reader's owned transaction. */
internal class Final11PostgresFixture(private val migrate: Boolean = false) : AutoCloseable {
    val root: Path = Files.createTempDirectory(Files.createDirectories(Path.of("/tmp/opencode")), "catalog-r06-pg-")
    val data: Path = Files.createDirectories(root.resolve("data"))
    private val server = TestingDucklakePostgreSqlCatalogServer()
    private var initialized = false

    fun native(sql: String) {
        Final11DuckDbBackendFixture.requirePinnedJdbc()
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            s.executeQuery("PRAGMA version").use { r ->
                check(r.next())
                assertThat(r.getString(1)).isEqualTo("v2.0.0")
                assertThat(r.getString(2)).isEqualTo("d54cbbe3df")
            }
            s.executeQuery("SELECT extension_name,extension_version,installed_from FROM duckdb_extensions() " +
                "WHERE loaded AND extension_name IN ('ducklake','postgres_scanner') ORDER BY 1").use { r ->
                check(r.next())
                assertThat(r.getString(1)).isEqualTo("ducklake")
                assertThat(r.getString(2)).isEqualTo("417a9117f5")
                assertThat(r.getString(3)).isEqualTo("core")
                check(r.next())
                println("R06 PostgreSQL scanner: ${r.getString(2)} from ${r.getString(3)}")
                assertThat(r.getString(1)).isEqualTo("postgres_scanner")
                assertThat(r.getString(2)).isEqualTo("c0f5a3c0c4")
                assertThat(r.getString(3)).isEqualTo("core")
            }
            s.executeQuery("SELECT current_setting('allow_unsigned_extensions')").use { r -> check(r.next()); assertThat(r.getBoolean(1)).isFalse() }
            val version = if (migrate && !initialized) "1.0" else "1.1"
            s.execute("ATTACH '${server.getDuckDbAttachUri().replace("'", "''")}' AS lake " +
                "(DATA_PATH '${data.toString().replace("'", "''")}',DUCKLAKE_VERSION '$version'," +
                "AUTOMATIC_MIGRATION ${if (migrate && initialized) "TRUE" else "FALSE"})")
            s.execute(sql)
            s.execute("DETACH lake")
            initialized = true
        } }
    }

    fun metadata(sql: String) {
        DriverManager.getConnection(server.getJdbcUrl(), server.getUser(), server.getPassword()).use { c ->
            c.createStatement().use { it.execute(sql) }
        }
    }

    fun rows(sql: String): List<List<String?>> = DriverManager.getConnection(server.getJdbcUrl(), server.getUser(), server.getPassword()).use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList {
            while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
        } } }
    }

    fun reader(): JdbcDucklakeCatalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = server.getJdbcUrl()
        catalogDatabaseUser = server.getUser()
        catalogDatabasePassword = server.getPassword()
        dataPath = data.toString()
        maxCatalogConnections = 2
        enableFinal11Reads = true
    })

    fun snapshot(): Long = rows("SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
    fun inline(table: String): Pair<Long, Long> = rows("SELECT i.table_id,i.schema_version FROM ducklake_inlined_data_tables i " +
        "JOIN ducklake_table t ON i.table_id=t.table_id WHERE t.table_name='$table' ORDER BY i.schema_version LIMIT 1")
        .single().let { it[0]!!.toLong() to it[1]!!.toLong() }

    override fun close() {
        try { server.close() }
        finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
