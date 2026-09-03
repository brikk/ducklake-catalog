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
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * `ducklake_column_mapping.mapping_id` lives in the FILE id space (TODO-rectify-from-eval.md W-B3):
 * upstream allocates it from `ducklake_snapshot.next_file_id`, and a DuckDB session's name-map
 * cache only reloads mappings with `mapping_id >=` the `next_file_id` watermark it last saw. A
 * catalog-space id would be invisible to a long-lived session (and could later collide with a
 * DuckDB-allocated one). Pins the allocation invariant on the snapshot row, and reads the
 * `add_files`'d file back through a DuckDB session that had already cached the table's name maps.
 */
class TestJdbcDucklakeCatalogAddFilesNameMapInterop {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "add-files-name-map")
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

    private fun openDuckDb(): Connection {
        val connection = DriverManager.getConnection("jdbc:duckdb:")
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
        return connection
    }

    private fun Connection.longs(sql: String): List<Long> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                (1..rs.metaData.columnCount).map { rs.getLong(it) }
            }
        }

    /** Writes `SELECT <selectList>` to [target] as Parquet with a scratch DuckDB. */
    private fun writeParquet(target: java.nio.file.Path, selectList: String) {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { st ->
                st.execute("COPY (SELECT $selectList) TO '" + target.toString().replace("'", "''") + "' (FORMAT PARQUET)")
            }
        }
    }

    /** DuckDB's own `ducklake_add_data_files` for each of [files] into `test_schema.nm`. */
    private fun duckDbAddFiles(vararg files: java.nio.file.Path) {
        openDuckDb().use { c ->
            c.createStatement().use { st ->
                for (f in files) {
                    st.execute(
                        "CALL ducklake_add_data_files('lake', 'nm', '" + f.toString().replace("'", "''") +
                            "', schema => 'test_schema', ignore_extra_columns => false)",
                    )
                }
            }
        }
    }

    private fun addFilesFragment(path: java.nio.file.Path, idCol: Long, nameCol: Long) =
        DucklakeWriteFragment(
            path.toString(),
            /* pathIsRelative = */ false,
            "parquet",
            Files.size(path),
            /* footerSize = */ 0L,
            /* recordCount = */ 2L,
            listOf(
                DucklakeFileColumnStats(idCol, 16L, 2L, 0L, "3", "4", false),
                DucklakeFileColumnStats(nameCol, 16L, 2L, 0L, "c", "d", false),
            ),
            emptyMap(),
            null,
            DucklakeNameMap(listOf(DucklakeNameMapEntry("ID", idCol), DucklakeNameMapEntry("NAME", nameCol))),
        )

    @Test
    fun mappingIdsComeFromTheFileIdSpaceAndAreVisibleToACachedDuckDbSession() {
        openDuckDb().use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.nm (id INTEGER, name VARCHAR)")
                st.execute("INSERT INTO lake.test_schema.nm VALUES (1, 'a'), (2, 'b')")
                st.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'nm')")
            }
        }
        val snapshot = catalog.currentSnapshotId
        val schema = catalog.getSchema("test_schema", snapshot)!!
        val table = catalog.getTable("test_schema", "nm", snapshot)!!
        val columns = catalog.getTableColumns(table.tableId, snapshot)
        val idCol = columns.single { it.columnName == "id" }.columnId
        val nameCol = columns.single { it.columnName == "name" }.columnId
        val before = catalog.getSnapshot(snapshot)!!

        // A parquet file whose column names differ in case from the table's — needs a name map.
        val dir = isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(table.path)
        val extra = dir.resolve("extra-upper.parquet")
        writeParquet(extra, "3::INTEGER AS \"ID\", 'c' AS \"NAME\" UNION ALL SELECT 4, 'd'")

        // A long-lived DuckDB session that has ALREADY read the table (and so cached its name maps
        // with the current next_file_id as the watermark) before the library adds the file.
        openDuckDb().use { session ->
            assertThat(session.longs("SELECT count(*) FROM lake.test_schema.nm")).containsExactly(2L)

            catalog.commitAddFiles(table.tableId, listOf(addFilesFragment(extra, idCol, nameCol)))

            // Allocation invariant: one data file + one mapping both consumed FILE ids; catalog ids untouched.
            val after = catalog.getSnapshot(catalog.currentSnapshotId)!!
            assertThat(after.nextCatalogId).`as`("no catalog id consumed").isEqualTo(before.nextCatalogId)
            assertThat(after.nextFileId).`as`("data file + mapping each consumed a file id").isEqualTo(before.nextFileId + 2)
            val added = catalog.getDataFiles(table.tableId, after.snapshotId).single { it.path == extra.toString() }
            assertThat(added.mappingId).isNotNull()
            assertThat(added.mappingId!!)
                .`as`("mapping_id is in [old next_file_id, new next_file_id)")
                .isGreaterThanOrEqualTo(before.nextFileId)
                .isLessThan(after.nextFileId)
            assertThat(catalog.getNameMaps(setOf(added.mappingId!!))[added.mappingId!!])
                .containsEntry(idCol, "ID").containsEntry(nameCol, "NAME")

            // The cached session picks up the new mapping (mapping_id >= its watermark) and reads
            // the file through it: 4 rows, max id 4.
            assertThat(session.longs("SELECT count(*), max(id) FROM lake.test_schema.nm")).containsExactly(4L, 4L)
            session.createStatement().use { st ->
                st.executeQuery("SELECT name FROM lake.test_schema.nm WHERE id = 4").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("d")
                }
            }
        }

        // DuckDB itself now add_files two more files. The first has the SAME schema as ours: upstream
        // dedupes name maps, so it must REUSE the library's mapping_id (proof the map is readable
        // and recognised). The second has a different schema, so DuckDB allocates a fresh mapping
        // from next_file_id — which must not collide with the library's.
        val sameSchema = dir.resolve("extra-upper-2.parquet")
        val otherSchema = dir.resolve("extra-mixed.parquet")
        writeParquet(sameSchema, "5::INTEGER AS \"ID\", 'e' AS \"NAME\"")
        writeParquet(otherSchema, "6::INTEGER AS \"Id\", 'f' AS \"Name\"")
        duckDbAddFiles(sameSchema, otherSchema)
        openDuckDb().use { c ->
            assertThat(c.longs("SELECT count(*), max(id) FROM lake.test_schema.nm")).containsExactly(6L, 6L)
        }
        // DuckDB registers add_files paths relative to the table dir where it can; key by file name.
        val files = catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).associateBy { it.path.substringAfterLast('/') }
        val ours = files.getValue(extra.fileName.toString()).mappingId!!
        assertThat(files.getValue(sameSchema.fileName.toString()).mappingId)
            .`as`("DuckDB recognised and reused the library-written name map for an identical schema")
            .isEqualTo(ours)
        assertThat(files.getValue(otherSchema.fileName.toString()).mappingId)
            .`as`("a DuckDB-allocated mapping for a different schema does not collide with ours")
            .isNotNull().isNotEqualTo(ours)
    }
}
