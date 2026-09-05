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
 * Row-shape parity with upstream for the catalog rows this library writes
 * (TODO-rectify-from-eval.md W-D1, W-D5, W-D7):
 *  - `ducklake_schema_versions` gets one row per CREATED or ALTERED table, none for view/schema DDL
 *    or DROP TABLE; comments bump `schema_version` (a DuckDB session caches the catalog per version);
 *  - `ducklake_table_column_stats.contains_nan` is an explicit `false` for float columns;
 *  - `default_value_dialect = 'duckdb'` on new columns;
 *  - a name that is not path-safe gets a `<uuid>/` directory;
 *  - DROP TABLE retires the table's tags / column tags / sort info.
 */
class TestJdbcDucklakeCatalogRowShapeParity {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "row-shape-parity")
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

    private fun <T> sql(query: String, read: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st -> st.executeQuery(query).use(read) }
        }

    private fun longs(query: String): List<Long?> =
        sql(query) { rs -> generateSequence { if (rs.next()) rs.getObject(1)?.let { (it as Number).toLong() } else null }.toList() }

    private fun schemaVersionRows(snapshot: Long): List<Long?> =
        sql("SELECT table_id FROM ducklake_schema_versions WHERE begin_snapshot = $snapshot ORDER BY table_id") { rs ->
            val out = mutableListOf<Long?>()
            while (rs.next()) out.add(rs.getObject(1)?.let { (it as Number).toLong() })
            out
        }

    private fun openDuckDb(): Connection {
        val connection = DriverManager.getConnection("jdbc:duckdb:")
        connection.createStatement().use { st ->
            st.execute("INSTALL ducklake")
            st.execute("LOAD ducklake")
            st.execute("INSTALL postgres")
            st.execute("LOAD postgres")
            st.execute(
                "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                    isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
            )
        }
        return connection
    }

    @Test
    fun schemaVersionsRowsFollowUpstreamAndCommentsBumpTheVersion() {
        catalog.createSchema("shape")
        val afterSchema = catalog.currentSnapshotId
        assertThat(schemaVersionRows(afterSchema)).`as`("schema DDL: no per-table row").isEmpty()

        catalog.createTable("shape", "t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val afterCreate = catalog.currentSnapshotId
        val tableId = catalog.getTable("shape", "t", afterCreate)!!.tableId
        assertThat(schemaVersionRows(afterCreate)).`as`("created table gets a row").containsExactly(tableId)

        catalog.createView("shape", "v", "SELECT 1", "duckdb", listOf("x"), emptyMap())
        assertThat(schemaVersionRows(catalog.currentSnapshotId)).`as`("view DDL: no per-table row").isEmpty()

        // A DuckDB session has the catalog cached; a comment set by the library must show up in it.
        openDuckDb().use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM lake.shape.t").use { it.next() } }
            val before = catalog.getSnapshot(catalog.currentSnapshotId)!!.schemaVersion
            catalog.setTableComment(tableId, "hello from the library")
            val after = catalog.getSnapshot(catalog.currentSnapshotId)!!
            assertThat(after.schemaVersion).`as`("comment bumps schema_version").isEqualTo(before + 1)
            assertThat(schemaVersionRows(after.snapshotId)).containsExactly(tableId)
            c.createStatement().use { st ->
                st.executeQuery("SELECT comment FROM duckdb_tables() WHERE database_name = 'lake' AND table_name = 't'").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).`as`("cached DuckDB session sees the new comment").isEqualTo("hello from the library")
                }
            }
        }

        catalog.dropTable("shape", "t")
        assertThat(schemaVersionRows(catalog.currentSnapshotId)).`as`("DROP TABLE: no per-table row").isEmpty()
        assertThat(longs("SELECT count(*) FROM ducklake_schema_versions WHERE table_id IS NULL")).containsExactly(0L)
    }

    @Test
    fun floatColumnsGetExplicitContainsNanFalseAndNewColumnsCarryDialect() {
        catalog.createSchema("nanshape")
        catalog.createTable(
            "nanshape", "f",
            listOf(TableColumnSpec.leaf("x", "float64", true), TableColumnSpec.leaf("s", "varchar", true)),
            null, null,
        )
        val snapshot = catalog.currentSnapshotId
        val tableId = catalog.getTable("nanshape", "f", snapshot)!!.tableId
        val cols = catalog.getTableColumns(tableId, snapshot).associateBy { it.columnName }
        assertThat(sql("SELECT DISTINCT default_value_dialect FROM ducklake_column WHERE table_id = $tableId") { rs -> rs.next(); rs.getString(1) })
            .isEqualTo("duckdb")

        catalog.commitInsert(
            tableId,
            listOf(
                DucklakeWriteFragment(
                    "f/a.parquet", 100L, 0L, 2L,
                    listOf(
                        DucklakeFileColumnStats(cols.getValue("x").columnId, 16L, 2L, 0L, "1.5", "2.5", false),
                        DucklakeFileColumnStats(cols.getValue("s").columnId, 16L, 2L, 0L, "a", "b", false),
                    ),
                ),
            ),
        )
        fun containsNan(col: String): String? =
            sql(
                "SELECT contains_nan::text FROM ducklake_table_column_stats " +
                    "WHERE table_id = $tableId AND column_id = ${cols.getValue(col).columnId}",
            ) { rs -> rs.next(); rs.getString(1) }
        assertThat(containsNan("x")).`as`("float: explicit false, so DuckDB builds global stats").isEqualTo("false")
        assertThat(containsNan("s")).`as`("non-float: SQL NULL").isNull()
        // Second insert keeps it false (UPDATE path), and analyze rebuild keeps it too.
        catalog.commitInsert(
            tableId,
            listOf(
                DucklakeWriteFragment(
                    "f/b.parquet", 100L, 0L, 1L,
                    listOf(
                        DucklakeFileColumnStats(cols.getValue("x").columnId, 8L, 1L, 0L, "3", "3", false),
                        DucklakeFileColumnStats(cols.getValue("s").columnId, 8L, 1L, 0L, "c", "c", false),
                    ),
                ),
            ),
        )
        assertThat(containsNan("x")).isEqualTo("false")
        catalog.analyzeTable(tableId)
        assertThat(containsNan("x")).isEqualTo("false")
        assertThat(containsNan("s")).isNull()
    }

    @Test
    fun unsafeNamesGetUuidDirectoriesAndDropTableRetiresTagsAndSortInfo() {
        catalog.createSchema("my schema")
        val snapshot0 = catalog.currentSnapshotId
        val schema = catalog.getSchema("my schema", snapshot0)!!
        assertThat(schema.path).`as`("space in the name → uuid directory").isEqualTo("${schema.schemaUuid}/")
        catalog.createTable("my schema", "weird.table", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        catalog.createTable("my schema", "plain_table-1", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val snapshot = catalog.currentSnapshotId
        val weird = catalog.getTable("my schema", "weird.table", snapshot)!!
        val plain = catalog.getTable("my schema", "plain_table-1", snapshot)!!
        assertThat(weird.path).isEqualTo("${weird.tableUuid}/")
        assertThat(plain.path).isEqualTo("plain_table-1/")

        // Tag + column tag on the plain table, then drop it: both must be end-snapshotted.
        catalog.setTableComment(plain.tableId, "c")
        val idCol = catalog.getTableColumns(plain.tableId, catalog.currentSnapshotId).single().columnId
        catalog.setColumnComment(plain.tableId, idCol, "cc")
        assertThat(longs("SELECT count(*) FROM ducklake_tag WHERE object_id = ${plain.tableId} AND end_snapshot IS NULL")).containsExactly(1L)
        assertThat(longs("SELECT count(*) FROM ducklake_column_tag WHERE table_id = ${plain.tableId} AND end_snapshot IS NULL")).containsExactly(1L)
        catalog.dropTable("my schema", "plain_table-1")
        assertThat(longs("SELECT count(*) FROM ducklake_tag WHERE object_id = ${plain.tableId} AND end_snapshot IS NULL")).containsExactly(0L)
        assertThat(longs("SELECT count(*) FROM ducklake_column_tag WHERE table_id = ${plain.tableId} AND end_snapshot IS NULL")).containsExactly(0L)
        // And DuckDB still loads the lake with the uuid-pathed objects present.
        openDuckDb().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM lake.\"my schema\".\"weird.table\"").use { rs -> rs.next(); assertThat(rs.getLong(1)).isZero() }
            }
        }
    }
}
