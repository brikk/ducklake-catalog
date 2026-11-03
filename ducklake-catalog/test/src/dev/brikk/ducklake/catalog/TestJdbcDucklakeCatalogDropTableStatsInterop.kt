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
 * `dropTable` deletes the dropped table's unversioned `ducklake_table_stats` and
 * `ducklake_table_column_stats` rows in the drop transaction instead of leaving them for
 * `expireSnapshots`. Under create/drop-heavy workloads those rows otherwise come to dominate the
 * stats tables and are scanned by every insert's per-table probe (the "99% of statistics rows
 * belonged to tables that no longer existed" failure mode reported at petabyte scale).
 *
 * The rows are safe to remove early because they describe only the latest state of a live table
 * and the spec allows them to be absent, but a stock DuckDB must agree: each test ends by having
 * DuckDB load the catalog, time-travel into the dropped table, and expire the snapshots that kept
 * it alive.
 */
class TestJdbcDucklakeCatalogDropTableStatsInterop {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "drop-table-stats")
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

    private fun count(sql: String): Long =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun statsRowCounts(tableId: Long): Pair<Long, Long> =
        count("SELECT count(*) FROM ducklake_table_stats WHERE table_id = $tableId") to
            count("SELECT count(*) FROM ducklake_table_column_stats WHERE table_id = $tableId")

    private fun duckDbScalar(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }

    /**
     * Shared tail: the stats rows are gone, our reader reports no table stats, DuckDB still loads
     * the catalog and time-travels into the dropped table, and expiring every snapshot that kept
     * the table alive — natively and through our own expiry — needs no stats rows.
     */
    private fun assertDroppedTableStatsAreGone(name: String, tableId: Long, createdAt: Long, droppedAt: Long) {
        assertThat(statsRowCounts(tableId)).`as`("stats rows after drop").isEqualTo(0L to 0L)
        assertThat(catalog.getTableStats(tableId)).isNull()
        assertThat(catalog.getTable("test_schema", name, droppedAt)).isNull()
        assertThat(catalog.getTable("test_schema", name, droppedAt - 1)).`as`("time travel").isNotNull()

        withDuckDb { c ->
            assertThat(duckDbScalar(c, "SELECT count(*) FROM lake.test_schema.$name AT (VERSION => ${droppedAt - 1})"))
                .isEqualTo(2L)
            assertThat(duckDbScalar(c, "SELECT max(id) FROM lake.test_schema.$name AT (VERSION => ${droppedAt - 1})"))
                .isEqualTo(2L)
        }

        // Native expiry of the snapshots in which the table was alive: DuckDB GCs the dropped
        // table's remaining metadata and must not trip over the already-missing stats rows.
        val alive = (createdAt until droppedAt).toList()
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CALL ducklake_expire_snapshots('lake', versions => [${alive.joinToString()}])")
            }
            assertThat(duckDbScalar(c, "SELECT count(*) FROM lake.snapshots() WHERE snapshot_id IN (${alive.joinToString()})"))
                .isZero()
            // The catalog still loads and serves the surviving tables afterwards.
            assertThat(duckDbScalar(c, "SELECT count(*) FROM lake.test_schema.simple_table")).isEqualTo(5L)
        }
        assertThat(count("SELECT count(*) FROM ducklake_snapshot WHERE snapshot_id IN (${alive.joinToString()})")).isZero()
        assertThat(catalog.getTable("test_schema", name, catalog.currentSnapshotId)).isNull()
    }

    @Test
    fun dropOfADuckDbWrittenTableDeletesItsStatsRows() {
        val name = "native_dropped"
        val createdAt = catalog.currentSnapshotId + 1
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.$name(id INTEGER, label VARCHAR)")
                st.execute("INSERT INTO lake.test_schema.$name VALUES (1, 'one'), (2, 'two')")
            }
        }
        val tableId = catalog.getTable("test_schema", name, catalog.currentSnapshotId)!!.tableId
        assertThat(statsRowCounts(tableId)).`as`("DuckDB wrote table + column stats").isEqualTo(1L to 2L)

        catalog.dropTable("test_schema", name)

        assertDroppedTableStatsAreGone(name, tableId, createdAt, catalog.currentSnapshotId)
    }

    @Test
    fun dropOfAJvmWrittenTableDeletesItsStatsRows() {
        val name = "jvm_dropped"
        val createdAt = catalog.currentSnapshotId + 1
        catalog.createTable(
            "test_schema", name,
            listOf(TableColumnSpec.leaf("id", "int32", true), TableColumnSpec.leaf("label", "varchar", true)),
            null, null,
        )
        val tableId = catalog.getTable("test_schema", name, catalog.currentSnapshotId)!!.tableId
        val columns = catalog.getTableColumns(tableId, catalog.currentSnapshotId).associate { it.columnName to it.columnId }
        // A real file, with DuckLake field ids, so DuckDB can time-travel into the table after the drop.
        val path = withDuckDb { c ->
            val file = isolated.dataDir.resolve("jvm_dropped.parquet").toAbsolutePath()
            c.createStatement().use { st ->
                st.execute(
                    "COPY (SELECT * FROM (VALUES (1, 'one'), (2, 'two')) t(id, label)) TO '$file' " +
                        "(FORMAT PARQUET, FIELD_IDS {id: ${columns.getValue("id")}, label: ${columns.getValue("label")}})",
                )
            }
            file.toString()
        }
        catalog.commitInsert(
            tableId,
            listOf(DucklakeWriteFragment(
                path = path, pathIsRelative = false, fileFormat = "parquet",
                fileSizeBytes = java.io.File(path).length(), footerSize = 0L, recordCount = 2L,
                columnStats = listOf(
                    DucklakeFileColumnStats(columns.getValue("id"), 8L, 2L, 0L, "1", "2", false),
                    DucklakeFileColumnStats(columns.getValue("label"), 8L, 2L, 0L, "one", "two", false),
                ),
                partitionValues = emptyMap(), partitionId = null, nameMap = null,
            )),
        )
        assertThat(statsRowCounts(tableId)).`as`("commitInsert wrote table + column stats").isEqualTo(1L to 2L)

        catalog.dropTable("test_schema", name)

        assertDroppedTableStatsAreGone(name, tableId, createdAt, catalog.currentSnapshotId)
    }
}
