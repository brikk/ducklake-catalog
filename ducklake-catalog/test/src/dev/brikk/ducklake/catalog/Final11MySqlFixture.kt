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

/** Native-only diagnostic fixture. No retagged format, reader migration or manufactured inline schema. */
internal class Final11MySqlFixture : AutoCloseable {
    private val server = TestingDucklakeMySqlCatalogServer()
    val data: Path = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-r06-mysql-")
    private var initialized = false

    fun native(sql: String, version: String = "1.1", migrate: Boolean = false) {
        nativeRows(sql, version, migrate)
    }

    fun nativeRows(sql: String, version: String = "1.1", migrate: Boolean = false): List<List<String?>> {
        Final11DuckDbBackendFixture.requirePinnedJdbc()
        return DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake; INSTALL mysql; LOAD mysql")
            s.executeQuery("PRAGMA version").use { r ->
                check(r.next()); assertThat(r.getString(1)).isEqualTo("v2.0.0"); assertThat(r.getString(2)).isEqualTo("d54cbbe3df")
            }
            s.executeQuery("SELECT extension_name,extension_version,installed_from FROM duckdb_extensions() " +
                "WHERE loaded AND extension_name IN('ducklake','mysql_scanner') ORDER BY 1").use { r ->
                check(r.next()); assertThat(r.getString(2)).isEqualTo("417a9117f5"); assertThat(r.getString(3)).isEqualTo("core")
                check(r.next()); assertThat(r.getString(2)).isEqualTo("f7fc0d4271"); assertThat(r.getString(3)).isEqualTo("core")
            }
            s.executeQuery("SELECT current_setting('allow_unsigned_extensions')").use { r -> check(r.next()); assertThat(r.getBoolean(1)).isFalse() }
            if (!initialized) {
                // The generic initializer creates tables in the selected database but subsequent reads
                // default to `main`. Close it and reopen with the actual MySQL database/schema explicitly.
                s.execute("ATTACH '${server.getDuckDbAttachUri()}' AS seed " +
                    "(DATA_PATH '$data',DUCKLAKE_VERSION '$version',AUTOMATIC_MIGRATION FALSE)")
                s.execute("DETACH seed")
                initialized = true
            }
            s.execute("ATTACH '${server.getDuckDbAttachUri()}' AS lake " +
                "(DATA_PATH '$data',METADATA_SCHEMA 'ducklake',DUCKLAKE_VERSION '$version',AUTOMATIC_MIGRATION $migrate)")
            val rows = if (s.execute(sql)) s.resultSet.use { r -> buildList {
                while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
            } } else emptyList()
            s.execute("DETACH lake")
            rows
        } }
    }

    fun metadata(sql: String) {
        val url = server.getJdbcUrl()
        DriverManager.getConnection(url + (if ('?' in url) "&" else "?") + "allowMultiQueries=true", server.getUser(), server.getPassword()).use { c ->
            c.createStatement().use { it.execute(sql) }
        }
    }

    fun rows(sql: String): List<List<String?>> = DriverManager.getConnection(server.getJdbcUrl(), server.getUser(), server.getPassword()).use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList {
            while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
        } } }
    }

    fun readerConfig() = DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = server.getJdbcUrl()
        catalogDatabaseUser = server.getUser()
        catalogDatabasePassword = server.getPassword()
        dataPath = data.toString()
        enableFinal11Reads = true
    }

    override fun close() {
        try { server.close() }
        finally { Files.walk(data).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
