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
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Statement

/** UF-04: the catalog transports stored bucket assignments; it does not hash predicates or prune. */
class TestJdbcDucklakeCatalogBucketCandidates {
    @Test
    fun nanAndNestedZeroBucketsRemainUnmodifiedAndAllFilesAreOffered() {
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val fixture = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "bucket-candidates")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = fixture.jdbcUrl
                catalogDatabaseUser = fixture.user
                catalogDatabasePassword = fixture.password
                dataPath = fixture.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 1
            }
            DriverManager.getConnection("jdbc:duckdb:").use { native -> native.createStatement().use { st ->
                st.execute("LOAD ducklake; LOAD postgres")
                st.execute("ATTACH '${fixture.duckDbAttachUri}' AS lake (DATA_PATH '${fixture.dataDir.toAbsolutePath()}',METADATA_CATALOG 'meta')")
                JdbcDucklakeCatalog(config).use { catalog ->
                    verifyCases(catalog, st)
                }
            } }
        }
    }

    private fun verifyCases(catalog: JdbcDucklakeCatalog, st: Statement) {
        val cases = listOf(
            Triple("nan_buckets", "DOUBLE", listOf("'nan'::DOUBLE", "-('nan'::DOUBLE)")),
            Triple("list_buckets", "DOUBLE[]", listOf("[0.0::DOUBLE]", "[-(0.0::DOUBLE)]")),
            Triple("struct_buckets", "STRUCT(a FLOAT)", listOf("{'a':0.0::FLOAT}", "{'a':-(0.0::FLOAT)}")),
            Triple("map_buckets", "MAP(INTEGER,DOUBLE)", listOf("MAP {1:0.0::DOUBLE}", "MAP {1:-(0.0::DOUBLE)}")),
        )
        for ((name, type, values) in cases) verify(catalog, st, name, type, values)
    }

    private fun verify(catalog: JdbcDucklakeCatalog, st: Statement, name: String, type: String, values: List<String>) {
        st.execute("CREATE TABLE lake.test_schema.$name(d $type,k INTEGER)")
        st.execute("CALL lake.set_option('data_inlining_row_limit',0,table_name=>'$name',schema=>'test_schema')")
        st.execute("ALTER TABLE lake.test_schema.$name SET PARTITIONED BY (bucket(8,d))")
        values.forEachIndexed { index, value -> st.execute("INSERT INTO lake.test_schema.$name SELECT $value,$index") }
        val snapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", name, snapshot)!!
        val column = catalog.getTableColumns(table.tableId, snapshot).single { it.columnName == "d" }
        val spec = catalog.getPartitionSpecs(table.tableId, snapshot).single()
        assertThat(spec.fields.single()).isEqualTo(DucklakePartitionField(0, column.columnId, DucklakePartitionTransform.BUCKET, 8))
        val files = catalog.getDataFiles(table.tableId, snapshot)
        assertThat(files).hasSize(2)
        assertThat(files.map { it.partitionId }).containsOnly(spec.partitionId)
        val stored = catalog.getFilePartitionValues(table.tableId, snapshot)
        assertThat(stored.keys).containsExactlyInAnyOrderElementsOf(files.map { it.dataFileId })
        assertThat(stored.values.map { it.single().partitionValue }).doesNotContainNull()
        st.executeQuery("SELECT p.data_file_id,p.partition_value FROM meta.ducklake_file_partition_value p " +
            "JOIN meta.ducklake_data_file f USING (data_file_id) WHERE f.table_id=${table.tableId} ORDER BY 1 LIMIT 3").use { rs ->
            val raw = generateSequence { if (rs.next()) rs.getLong(1) to rs.getString(2) else null }.toList().toMap()
            assertThat(stored.mapValues { it.value.single().partitionValue }).isEqualTo(raw)
        }
        // No predicate/hashing enters the catalog API. In particular it must not
        // discard one of the differently hashed but equal NaN / nested-zero files.
        st.executeQuery("SELECT k FROM lake.test_schema.$name ORDER BY k").use { rs ->
            assertThat(generateSequence { if (rs.next()) rs.getInt(1) else null }.toList()).containsExactly(0, 1)
        }
        assertThat(catalog.getFilePartitionValues(table.tableId, snapshot)).isEqualTo(stored)
        assertThat(catalog.getDataFiles(table.tableId, snapshot)).isEqualTo(files)
        assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
    }
}
