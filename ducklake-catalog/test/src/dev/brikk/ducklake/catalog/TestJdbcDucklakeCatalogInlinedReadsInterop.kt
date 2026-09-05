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
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Inlined-data reads (TODO-rectify-from-eval.md R-B5, C-B4). DuckDB creates the per-table
 * `ducklake_inlined_data_<t>_<sv>` tables with QUOTED identifiers, so a column named `My Col` or
 * `select` is stored exactly so; the library must quote user-derived identifiers when it projects
 * them. And the only error a reader may treat as "no rows" is the table not existing — any other
 * failure (a mis-rendered identifier, connection loss, a wrong column) must propagate rather than
 * silently emptying a query's result.
 */
class TestJdbcDucklakeCatalogInlinedReadsInterop {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "inlined-reads")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            }
            catalog = JdbcDucklakeCatalog(config)
            // Rows are inlined by default (data_inlining_row_limit = 10); hostile column names.
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.createStatement().use { st ->
                    st.execute("INSTALL ducklake")
                    st.execute("LOAD ducklake")
                    st.execute("INSTALL postgres")
                    st.execute("LOAD postgres")
                    st.execute(
                        "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                            isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
                    )
                    st.execute(
                        "CREATE TABLE lake.test_schema.hostile (\"My Col\" INTEGER, \"select\" VARCHAR, \"Order\" INTEGER)",
                    )
                    st.execute("INSERT INTO lake.test_schema.hostile VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)")
                    st.execute("DELETE FROM lake.test_schema.hostile WHERE \"My Col\" = 2")
                }
            }
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

    @Test
    fun inlinedRowsWithQuotedIdentifiersAreReadBack() {
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "hostile", snapshot)!!
        val columns = catalog.getTableColumns(table.tableId, snapshot)
        assertThat(columns.map { it.columnName }).containsExactly("My Col", "select", "Order")
        val infos = catalog.getInlinedDataInfos(table.tableId, snapshot)
        assertThat(infos).hasSize(1)
        val sv = infos.single().schemaVersion
        assertThat(infos.single().hasLiveRows).isTrue()
        assertThat(catalog.countInlinedRows(table.tableId, sv, snapshot)).isEqualTo(2L)

        val rows = catalog.readInlinedData(table.tableId, sv, snapshot, columns)
        assertThat(rows).`as`("both live rows come back, projected through the quoted column names").hasSize(2)
        assertThat(rows.map { (it[0] as Number).toInt() }).containsExactly(1, 3)
        assertThat(rows.map { (it[2] as Number).toInt() }).containsExactly(10, 30)
        // Time travel to before the delete sees all three.
        assertThat(catalog.readInlinedData(table.tableId, sv, snapshot - 1, columns)).hasSize(3)

        val changes = catalog.getInlinedChangesBetween(table.tableId, sv, snapshot - 1, snapshot, columns.map { it.columnId })
        assertThat(changes).`as`("3 inserts + 1 delete touch the window").hasSize(3)
        assertThat(changes.count { it.endSnapshot == snapshot }).isEqualTo(1)
        val rawById = changes.associateBy { (it.values[0] as Number).toInt() }
        assertThat(rawById.getValue(1).endSnapshot).`as`("live raw row").isNull()
        assertThat(rawById.getValue(2).endSnapshot).`as`("deleted raw row").isEqualTo(snapshot)
        assertThat(rawById.getValue(3).endSnapshot).`as`("live raw row").isNull()

        val decoded = catalog.getInlinedChangesBetweenDecoded(
            table.tableId,
            sv,
            snapshot - 1,
            snapshot,
            columns.map { it.columnId },
        )
        val decodedById = decoded.associateBy { it.values[0] as Int }
        assertThat(decodedById.getValue(1).endSnapshot).`as`("live decoded row").isNull()
        assertThat(decodedById.getValue(2).endSnapshot).`as`("deleted decoded row").isEqualTo(snapshot)
        assertThat(decodedById.getValue(3).endSnapshot).`as`("live decoded row").isNull()
    }

    @Test
    fun onlyAMissingTableIsSilentEverythingElsePropagates() {
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "hostile", snapshot)!!
        val columns = catalog.getTableColumns(table.tableId, snapshot)
        val sv = catalog.getInlinedDataInfos(table.tableId, snapshot).single().schemaVersion

        // Missing table: "no rows", not an error.
        assertThat(catalog.readInlinedData(table.tableId, sv + 99, snapshot, columns)).isEmpty()
        assertThat(catalog.hasInlinedRows(table.tableId, sv + 99, snapshot)).isFalse()
        assertThat(catalog.countInlinedRows(table.tableId, sv + 99, snapshot)).isZero()
        assertThat(catalog.readInlinedRowIds(table.tableId, sv + 99, snapshot)).isEmpty()
        assertThat(catalog.getInlinedDeletes(table.tableId + 9999, snapshot)).isEmpty()

        // Any OTHER failure must surface. Provoke one the reader cannot mistake for absence: the
        // physical inlined table exists but lacks a column the catalog says it has (PostgreSQL 42703
        // undefined_column — exactly what an unquoted mixed-case identifier used to produce).
        val physical = "ducklake_inlined_data_${table.tableId}_$sv"
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st -> st.executeUpdate("ALTER TABLE $physical RENAME COLUMN \"Order\" TO \"Order_hidden\"") }
        }
        try {
            assertThatThrownBy { catalog.readInlinedData(table.tableId, sv, snapshot, columns) }
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageContaining("Order")
            assertThatThrownBy { catalog.getInlinedChangesBetween(table.tableId, sv, snapshot - 1, snapshot, columns.map { it.columnId }) }
                .isInstanceOf(DataAccessException::class.java)
        }
        finally {
            DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
                c.createStatement().use { st -> st.executeUpdate("ALTER TABLE $physical RENAME COLUMN \"Order_hidden\" TO \"Order\"") }
            }
        }
        assertThat(catalog.readInlinedData(table.tableId, sv, snapshot, columns)).hasSize(2)
    }
}
