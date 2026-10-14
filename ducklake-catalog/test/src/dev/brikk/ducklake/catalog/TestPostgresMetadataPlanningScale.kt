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
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.RecordMapper
import org.jooq.ResultQuery
import org.jooq.conf.ParamType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Actual catalog SQL, prepared six times in both PG plan modes; no timing pass/fail thresholds. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestPostgresMetadataPlanningScale {
    private lateinit var server: TestingDucklakePostgreSqlCatalogServer
    private lateinit var root: Path

    @BeforeAll
    fun seed(@TempDir directory: Path) {
        root = directory
        server = TestingDucklakePostgreSqlCatalogServer()
        DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
            st.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            st.execute("ATTACH '${server.getDuckDbAttachUri()}' AS lake (DATA_PATH '$directory/data')")
        } }
        DriverManager.getConnection(server.getJdbcUrl(), server.getUser(), server.getPassword()).use { conn ->
            conn.createStatement().use { st ->
                // Synthetic metadata only: no physical files, writers, GC or destructive filesystem operations.
                st.execute("INSERT INTO ducklake_snapshot (snapshot_id,schema_version,next_catalog_id,next_file_id) " +
                    "SELECT i,i,50000,200001 FROM generate_series(1,10) AS g(i)")
                st.execute("INSERT INTO ducklake_table (table_id,schema_id,table_name,begin_snapshot,end_snapshot,path,path_is_relative) " +
                    "SELECT t,0,'t_'||t,v,CASE WHEN v<9 THEN v+1 ELSE NULL END,'t_'||t||'/',true " +
                    "FROM generate_series(1,5000) AS a(t) CROSS JOIN generate_series(0,9) AS b(v)")
                st.execute("INSERT INTO ducklake_column (column_id,table_id,begin_snapshot,column_order,column_name,column_type,nulls_allowed) " +
                    "SELECT t*8+c,t,0,c,'c_'||c,'int64',true FROM generate_series(1,5000) AS a(t) CROSS JOIN generate_series(1,8) AS b(c)")
                st.execute("INSERT INTO ducklake_data_file (data_file_id,table_id,begin_snapshot,path,path_is_relative,file_format,record_count) " +
                    "SELECT i,(i-1)/40+1,0,'file_'||i||'.parquet',true,'parquet',1 FROM generate_series(1,200000) AS g(i)")
                st.execute("INSERT INTO ducklake_file_column_stats " +
                    "(data_file_id,table_id,column_id,column_size_bytes,value_count,null_count,min_value,max_value,contains_nan) " +
                    "SELECT i,(i-1)/40+1,((i-1)/40+1)*8+c,8,1,0,'10','10',false " +
                    "FROM generate_series(1,200000) AS a(i) CROSS JOIN generate_series(1,8) AS b(c)")
                st.execute("ANALYZE") // PostgreSQL planner statistics, NOT catalog ANALYZE publication (C05).
            }
        }
    }

    @AfterAll
    fun close() { if (::server.isInitialized) server.close() }

    @Test fun customPlans() = exercise("force_custom_plan")
    @Test fun genericPlans() = exercise("force_generic_plan")

    private fun exercise(mode: String) {
        val base = server.getJdbcUrl()
        val url = base + (if ('?' in base) "&" else "?") + "options=-c%20plan_cache_mode%3D$mode&prepareThreshold=1"
        val config = DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = url
            catalogDatabaseUser = server.getUser()
            catalogDatabasePassword = server.getPassword()
            dataPath = root.resolve("data").toString()
            maxCatalogConnections = 1
        }
        JdbcDucklakeCatalog(config).use { catalog ->
            DriverManager.getConnection(url, server.getUser(), server.getPassword()).use { conn ->
                val ctx = catalog.forConnection(conn)
                val recorder = Recording(DirectMetadataQuery())
                val cleanup = JdbcDucklakeCleanupQueries(ctx, recorder)
                val page = cleanup.listRetainedFileReferencePage(DucklakeFileKind.DATA, 0, 940)
                assertThat(page.map { it.fileId }).containsExactlyElementsOf((1L..940L).toList())
                assertThat(recorder.rows).containsExactly(940, 24, 1)
                recorder.queries.forEach { query ->
                    assertThat(query.bindValues).contains(DucklakeCleanupLimits.MAX_PATH_LENGTH + 1)
                }
                val filePlan = explain(conn, recorder.queries[0], "files", mode)
                val tablePlan = explain(conn, recorder.queries[1], "tables", mode)
                val schemaPlan = explain(conn, recorder.queries[2], "schemas", mode)
                val stats = catalog.getColumnStats(20, 10)
                assertThat(stats).hasSize(8)
                stats.forEach { column ->
                    assertThat(column.totalValueCount).isEqualTo(40L)
                    assertThat(column.minValue).isEqualTo("10")
                    assertThat(column.maxValue).isEqualTo("10")
                }
                val statisticsPlan = explain(conn, catalog.fileColumnStatisticsQuery(ctx, 20, 10), "statistics", mode)
                assertThat(maxLoops(filePlan)).isLessThanOrEqualTo(940.0)
                assertThat(maxLoops(tablePlan)).`as`("latest-parent lookup must not repeat for every historical row").isLessThanOrEqualTo(24.0)
                assertThat(maxLoops(schemaPlan)).isLessThanOrEqualTo(1.0)
                assertThat(statisticsPlan["Plan"]["Actual Rows"].asInt()).isEqualTo(320)
                assertThat(maxLoops(statisticsPlan)).isLessThanOrEqualTo(320.0)
            }
        }
    }

    private fun explain(connection: Connection, query: ResultQuery<*>, label: String, mode: String): JsonNode {
        val name = "scale_$label"
        var number = 0
        val sql = query.getSQL(ParamType.INDEXED).replace(Regex("\\?")) { "\$${++number}" }
        assertThat(number).isEqualTo(query.bindValues.size)
        // Match JDBC's typed binds: untyped substring parameters resolve to PG's regex overload.
        val parameterTypes = query.bindValues.joinToString(",") { bindType(it) }
        val parameters = query.bindValues.joinToString(",") { bindLiteral(it) }
        connection.createStatement().use { st ->
            st.execute("PREPARE $name($parameterTypes) AS $sql")
            var result: JsonNode? = null
            repeat(6) {
                st.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE $name($parameters)").use { rs ->
                    assertThat(rs.next()).isTrue()
                    result = ObjectMapper().readTree(rs.getString(1))[0]
                }
            }
            st.executeQuery("SELECT custom_plans,generic_plans FROM pg_prepared_statements WHERE name='$name'").use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getLong(if (mode == "force_custom_plan") 1 else 2)).isEqualTo(6L)
            }
            val plan = requireNotNull(result)
            println("PG_SCALE mode=$mode query=$label execution_ms=${plan["Execution Time"].asDouble()} " +
                "rows=${plan["Plan"]["Actual Rows"].asInt()} max_loops=${maxLoops(plan)}")
            st.execute("DEALLOCATE $name")
            return plan
        }
    }

    private fun bindType(value: Any?): String = when (value) {
        is Long -> "bigint"
        is Number -> "integer"
        is Boolean -> "boolean"
        is String, null -> "text"
        else -> error("Unsupported probe bind type: ${value.javaClass}")
    }

    private fun bindLiteral(value: Any?): String = when (value) {
        is Number, is Boolean -> value.toString()
        is String -> "'${value.replace("'", "''")}'"
        null -> "NULL"
        else -> error("Unsupported probe bind type: ${value.javaClass}")
    }

    private fun maxLoops(node: JsonNode): Double {
        val own = node.path("Actual Loops").asDouble()
        return maxOf(own, node.elements().asSequence().map { maxLoops(it) }.maxOrNull() ?: 0.0)
    }

    private class Recording(private val delegate: MetadataQuery) : MetadataQuery by delegate {
        val queries = mutableListOf<ResultQuery<*>>()
        val rows = mutableListOf<Int>()
        override fun <T> fetch(dsl: DSLContext, query: ResultQuery<*>, mapper: RecordMapper<in Record, T>): List<T> {
            queries.add(query)
            return delegate.fetch(dsl, query, mapper).also { rows.add(it.size) }
        }
    }
}
