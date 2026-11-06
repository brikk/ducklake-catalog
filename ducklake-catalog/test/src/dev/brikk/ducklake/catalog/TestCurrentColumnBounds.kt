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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.function.Supplier

/** R2: O(columns) current-snapshot bounds from ducklake_table_column_stats, written by native DuckDB. */
class TestCurrentColumnBounds {
    @Test
    fun nativeTableBoundsMatchTheFileAggregationAndDeletesOnlyKeepThemWide(@TempDir dir: Path) {
        val file = dir.resolve("lake.db")
        fun native(sql: String) = DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake")
            s.execute("ATTACH 'ducklake:$file' AS lake (DATA_PATH '${dir.resolve("data")}', DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)")
            s.execute(sql)
            s.execute("DETACH lake")
        } }
        native("CREATE TABLE lake.t(i INTEGER, d DOUBLE, s VARCHAR); " +
            "CALL lake.set_option('data_inlining_row_limit', 0, table_name => 't'); " +
            "INSERT INTO lake.t VALUES (5, 1.5, 'b'), (10, NULL, 'a'); INSERT INTO lake.t VALUES (-3, 'nan'::DOUBLE, 'z')")
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply { catalogDatabaseUrl = "jdbc:duckdb:$file" }).use { c ->
            assertThatThrownBy { c.getCurrentColumnBounds(1) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                .hasMessageContaining("readSession")
            val (bounds, stats, ids) = c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val ids = c.getTableColumns(table.tableId, snapshot).associate { it.columnName to it.columnId }
                Triple(c.getCurrentColumnBounds(table.tableId).associateBy { it.columnId },
                    c.getColumnStats(table.tableId, snapshot).associateBy { it.columnId }, ids)
            })
            val i = bounds.getValue(ids.getValue("i"))
            assertThat(i.minValue to i.maxValue).isEqualTo(stats.getValue(ids.getValue("i")).let { it.minValue to it.maxValue })
            assertThat(i.minValue to i.maxValue).isEqualTo("-3" to "10")
            assertThat(i.containsNull).isFalse()
            assertThat(bounds.getValue(ids.getValue("d")).containsNull).isTrue()
            assertThat(bounds.getValue(ids.getValue("d")).containsNan).isTrue()
            assertThat(bounds.getValue(ids.getValue("s")).let { it.minValue to it.maxValue }).isEqualTo("a" to "z")
        }
        // A partial delete of the max row never narrows the gross bounds: a superset, not exact MIN/MAX.
        native("DELETE FROM lake.t WHERE i = 10")
        fun currentI(): DucklakeColumnBounds? = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply { catalogDatabaseUrl = "jdbc:duckdb:$file" }).use { c ->
            c.readSession(Supplier {
                val table = c.getTable("main", "t", c.currentSnapshotId)!!
                val iId = c.getTableColumns(table.tableId, c.currentSnapshotId).single { it.columnName == "i" }.columnId
                c.getCurrentColumnBounds(table.tableId).singleOrNull { it.columnId == iId }
            })
        }
        assertThat(currentI()!!.let { it.minValue to it.maxValue }).isEqualTo("-3" to "10")
        // Native DuckLake drops the table's rows when a delete retires a whole file: omitted = unknown, fall back.
        native("DELETE FROM lake.t WHERE i = -3")
        assertThat(currentI()).isNull()
    }

    @Test
    fun aColumnWithoutATableLevelRowIsOmittedAsUnknown(@TempDir dir: Path) {
        val file = dir.resolve("lake.db")
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake")
            s.execute("ATTACH 'ducklake:$file' AS lake (DATA_PATH '${dir.resolve("data")}', DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE)")
            s.execute("CREATE TABLE lake.t(i INTEGER, j INTEGER); CALL lake.set_option('data_inlining_row_limit', 0, table_name => 't'); " +
                "INSERT INTO lake.t VALUES (1, 2)")
            s.execute("DETACH lake")
        } }
        DriverManager.getConnection("jdbc:duckdb:$file").use { c -> c.createStatement().use {
            it.execute("DELETE FROM ducklake_table_column_stats WHERE column_id = (SELECT max(column_id) FROM ducklake_column)")
        } }
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply { catalogDatabaseUrl = "jdbc:duckdb:$file" }).use { c ->
            c.readSession(Supplier {
                val table = c.getTable("main", "t", c.currentSnapshotId)!!
                val columns = c.getTableColumns(table.tableId, c.currentSnapshotId)
                assertThat(c.getCurrentColumnBounds(table.tableId).map { it.columnId })
                    .containsExactly(columns.single { it.columnName == "i" }.columnId)
            })
        }
    }
}
