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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Two DROP paths whose metadata shape upstream's catalog loader validates strictly — a bad row
 * from either takes the WHOLE lake down for DuckDB (`Failed to load DuckLake - ...`), not just
 * the affected object. Each test therefore ends by ATTACHing the catalog in stock DuckDB and
 * querying it (TODO-rectify-from-eval.md W-B4, W-B5).
 *
 *  - [dropColumn] must end-snapshot every transitive descendant of a nested column. A one-level
 *    cascade leaves grandchildren active with a dangling `parent_column`, which upstream rejects
 *    with "Could not find parent column for column ...".
 *  - [dropSchema] must refuse while the schema still owns a view or macro, not only a table. A
 *    live view whose schema row is end-snapshotted makes upstream throw "could not find schema
 *    that corresponds to the view entry".
 */
class TestJdbcDucklakeCatalogDropCascadesInterop {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "drop-cascades")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            }
            catalog = JdbcDucklakeCatalog(config)
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::catalog.isInitialized) {
                catalog.close()
            }
            if (::server.isInitialized) {
                server.close()
            }
        }
    }

    /** Stock DuckDB with this catalog ATTACHed as `lake` — the interop oracle. */
    private fun <T> withDuckDb(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake")
                statement.execute("LOAD ducklake")
                statement.execute("INSTALL postgres")
                statement.execute("LOAD postgres")
                statement.execute(
                    "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                        isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
                )
            }
            block(connection)
        }

    private fun duckDbColumnNames(connection: Connection, table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("DESCRIBE lake.test_schema.$table").use { rs ->
                generateSequence { if (rs.next()) rs.getString("column_name") else null }.toList()
            }
        }

    @Test
    fun dropColumnEndSnapshotsWholeNestedSubtreeAndDuckDbStillLoads() {
        // Three levels deep under `s` (s → mid → b, c) plus a list-of-struct sibling `l` that
        // must survive untouched. Created by DuckDB so the type rows are exactly upstream's shape.
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE lake.test_schema.nested_t (
                        id INTEGER,
                        s STRUCT(a INTEGER, mid STRUCT(b INTEGER, c VARCHAR)),
                        l STRUCT(x INTEGER)[]
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "INSERT INTO lake.test_schema.nested_t VALUES " +
                        "(1, {'a': 1, 'mid': {'b': 2, 'c': 'two'}}, [{'x': 3}])",
                )
            }
        }

        val beforeSnapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "nested_t", beforeSnapshot)!!
        val before = catalog.getAllColumnsWithParentage(table.tableId, beforeSnapshot)
        val sId = before.single { it.columnName == "s" && it.parentColumn == null }.columnId
        val midId = before.single { it.columnName == "mid" }.columnId
        assertThat(before.single { it.columnName == "b" }.parentColumn)
            .`as`("precondition: b is a grandchild of s").isEqualTo(midId)
        assertThat(before.single { it.columnName == "mid" }.parentColumn).isEqualTo(sId)

        catalog.dropColumn(table.tableId, sId)

        val afterSnapshot = catalog.currentSnapshotId
        assertThat(afterSnapshot).isGreaterThan(beforeSnapshot)
        val after = catalog.getAllColumnsWithParentage(table.tableId, afterSnapshot)
        assertThat(after.map { it.columnName })
            .`as`("s and ALL of its descendants are gone; id and the l subtree remain")
            .containsExactlyInAnyOrder("id", "l", "element", "x")
        // No active row may point at an inactive parent — the invariant upstream enforces.
        val activeIds = after.map { it.columnId }.toSet()
        assertThat(after.mapNotNull { it.parentColumn }).allSatisfy { assertThat(activeIds).contains(it) }
        // Time travel still sees the full tree at the pre-drop snapshot.
        assertThat(catalog.getAllColumnsWithParentage(table.tableId, beforeSnapshot).map { it.columnName })
            .containsExactlyInAnyOrder("id", "s", "a", "mid", "b", "c", "l", "element", "x")
        assertThat(catalog.getTableColumns(table.tableId, afterSnapshot).map { it.columnName })
            .containsExactly("id", "l")

        // The oracle: stock DuckDB loads the catalog (a dangling parent_column throws here) and
        // reads the table with the surviving columns.
        withDuckDb { c ->
            assertThat(duckDbColumnNames(c, "nested_t")).containsExactly("id", "l")
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, l[1].x FROM lake.test_schema.nested_t").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getInt(1)).isEqualTo(1)
                    assertThat(rs.getInt(2)).isEqualTo(3)
                }
                // And time travel through DuckDB still resolves the dropped subtree.
                st.executeQuery(
                    "SELECT s.mid.c FROM lake.test_schema.nested_t AT (VERSION => $beforeSnapshot)",
                ).use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("two")
                }
            }
        }
    }

    @Test
    fun dropColumnOfUnknownColumnFailsWithoutMintingASnapshot() {
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "simple_table", snapshot)!!
        assertThatThrownBy { catalog.dropColumn(table.tableId, Long.MAX_VALUE) }
            .rootCause()
            .hasMessageContaining("Column not found")
        assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
    }

    @Test
    fun dropSchemaRefusesWhileViewsOrMacrosRemainAndDuckDbStillLoads() {
        catalog.createSchema("emptyish")
        catalog.createView("emptyish", "v", "SELECT 1 AS one", "duckdb", listOf("one"), emptyMap())

        assertThatThrownBy { catalog.dropSchema("emptyish") }
            .rootCause()
            .isInstanceOf(DucklakeSchemaNotEmptyException::class.java)
            .hasMessageContaining("not empty")
            .hasMessageContaining("views")

        catalog.dropView("emptyish", "v")

        // Macros are written only by DuckDB, but a schema owning one must still be protected.
        withDuckDb { c ->
            c.createStatement().use { st -> st.execute("CREATE MACRO lake.emptyish.plus_one(x) AS x + 1") }
        }
        assertThatThrownBy { catalog.dropSchema("emptyish") }
            .rootCause()
            .isInstanceOf(DucklakeSchemaNotEmptyException::class.java)
            .hasMessageContaining("not empty")
            .hasMessageContaining("macros")

        withDuckDb { c ->
            c.createStatement().use { st -> st.execute("DROP MACRO lake.emptyish.plus_one") }
        }
        val beforeDrop = catalog.currentSnapshotId
        catalog.dropSchema("emptyish")
        assertThat(catalog.getSchema("emptyish", catalog.currentSnapshotId)).isNull()
        assertThat(catalog.getSchema("emptyish", beforeDrop)).isNotNull()

        // The oracle: the catalog loads and the dropped schema is gone (a view orphaned by the
        // schema drop would have thrown at ATTACH/load time instead).
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM lake.test_schema.simple_table").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isGreaterThan(0)
                }
                st.executeQuery(
                    "SELECT count(*) FROM duckdb_schemas() WHERE database_name = 'lake' AND schema_name = 'emptyish'",
                ).use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isZero()
                }
            }
        }
    }
}
