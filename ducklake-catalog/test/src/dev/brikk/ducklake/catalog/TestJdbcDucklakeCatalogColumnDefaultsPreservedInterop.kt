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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `renameColumn` / `setColumnType` / `setFieldType` replace a `ducklake_column` row with a new
 * version. Every other attribute must carry over — above all `initial_default` and `default_value`
 * (TODO-rectify-from-eval.md W-B6): `initial_default` is what readers substitute for the column in
 * files written BEFORE an `ADD COLUMN ... DEFAULT x`, so discarding it changes the values DuckDB
 * returns for old rows.
 *
 * Oracle: DuckDB adds the defaulted column itself, the library renames / retypes it, DuckDB reads.
 */
class TestJdbcDucklakeCatalogColumnDefaultsPreservedInterop {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "column-defaults")
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

    private fun duckDbValues(sql: String): List<Long?> =
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getObject(1)?.let { (it as Number).toLong() } else null }.toList()
                }
            }
        }

    private data class RawColumn(
        val name: String,
        val type: String,
        val initialDefault: String?,
        val defaultValue: String?,
        val defaultType: String?,
        val parent: Long?,
    )

    private fun rawActiveColumn(tableId: Long, columnId: Long): RawColumn =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name, column_type, initial_default, default_value, default_value_type, parent_column " +
                        "FROM ducklake_column WHERE table_id = $tableId AND column_id = $columnId AND end_snapshot IS NULL",
                ).use { rs ->
                    check(rs.next()) { "no active row for column $columnId" }
                    RawColumn(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6)?.let { (it as Number).toLong() },
                    )
                }
            }
        }

    @Test
    fun renameAndRetypeKeepInitialDefaultSoOldRowsStillReadTheDefault() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.defaults (id INTEGER)")
                st.execute("INSERT INTO lake.test_schema.defaults VALUES (1), (2), (3)")
                st.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'defaults')")
                // Existing rows have no physical value for c; readers substitute initial_default.
                st.execute("ALTER TABLE lake.test_schema.defaults ADD COLUMN c INTEGER DEFAULT 42")
            }
        }
        var snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "defaults", snapshot)!!
        val cId = catalog.getTableColumns(table.tableId, snapshot).single { it.columnName == "c" }.columnId
        val before = rawActiveColumn(table.tableId, cId)
        assertThat(before.initialDefault).`as`("precondition: DuckDB recorded the initial default").isEqualTo("42")
        assertThat(before.defaultValue).isEqualTo("42")
        assertThat(duckDbValues("SELECT c FROM lake.test_schema.defaults ORDER BY id")).containsExactly(42L, 42L, 42L)

        catalog.renameColumn(table.tableId, cId, "c_renamed")
        val afterRename = rawActiveColumn(table.tableId, cId)
        assertThat(afterRename.name).isEqualTo("c_renamed")
        assertThat(afterRename.initialDefault).`as`("initial_default survives rename").isEqualTo("42")
        assertThat(afterRename.defaultValue).isEqualTo("42")
        assertThat(afterRename.defaultType).isEqualTo(before.defaultType)
        assertThat(duckDbValues("SELECT c_renamed FROM lake.test_schema.defaults ORDER BY id"))
            .`as`("DuckDB still substitutes the default for pre-ADD rows")
            .containsExactly(42L, 42L, 42L)

        catalog.setColumnType(table.tableId, cId, "int64")
        val afterRetype = rawActiveColumn(table.tableId, cId)
        assertThat(afterRetype.type).isEqualTo("int64")
        assertThat(afterRetype.initialDefault).`as`("initial_default survives retype").isEqualTo("42")
        assertThat(afterRetype.defaultValue).isEqualTo("42")
        assertThat(duckDbValues("SELECT c_renamed FROM lake.test_schema.defaults ORDER BY id")).containsExactly(42L, 42L, 42L)

        // And a DuckDB INSERT that omits the column still gets default_value.
        withDuckDb { c -> c.createStatement().use { st -> st.execute("INSERT INTO lake.test_schema.defaults (id) VALUES (4)") } }
        assertThat(duckDbValues("SELECT c_renamed FROM lake.test_schema.defaults WHERE id = 4")).containsExactly(42L)

        // Time travel to before the rename still sees the old name.
        snapshot = catalog.currentSnapshotId
        assertThat(catalog.getTableColumns(table.tableId, snapshot - 3).map { it.columnName }).containsExactly("id", "c")
    }

    @Test
    fun setFieldTypeKeepsParentAndSiblings() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.nested_defaults (id INTEGER, s STRUCT(a INTEGER, b VARCHAR))")
                st.execute("INSERT INTO lake.test_schema.nested_defaults VALUES (1, {'a': 7, 'b': 'x'})")
                st.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'nested_defaults')")
            }
        }
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "nested_defaults", snapshot)!!
        val all = catalog.getAllColumnsWithParentage(table.tableId, snapshot)
        val sId = all.single { it.columnName == "s" }.columnId
        val aId = all.single { it.columnName == "a" }.columnId
        val beforeA = rawActiveColumn(table.tableId, aId)

        catalog.setFieldType(table.tableId, listOf("s", "a"), "int64")
        val afterA = rawActiveColumn(table.tableId, aId)
        assertThat(afterA.type).isEqualTo("int64")
        assertThat(afterA.parent).isEqualTo(sId)
        assertThat(afterA.name).isEqualTo("a")
        assertThat(afterA.defaultValue).isEqualTo(beforeA.defaultValue)
        assertThat(afterA.defaultType).isEqualTo(beforeA.defaultType)
        assertThat(duckDbValues("SELECT s.a FROM lake.test_schema.nested_defaults")).containsExactly(7L)
        assertThat(catalog.getTableColumns(table.tableId, catalog.currentSnapshotId).single { it.columnName == "s" }.columnType)
            .isEqualTo("struct<a:int64,b:varchar>")
    }
}
