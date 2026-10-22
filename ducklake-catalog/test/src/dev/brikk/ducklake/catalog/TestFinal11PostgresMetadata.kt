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
import java.util.function.Supplier

class TestFinal11PostgresMetadata {
    private fun <T> read(f: Final11PostgresFixture, action: (JdbcDucklakeCatalog) -> T): T =
        f.reader().use { c -> c.readSession(Supplier { action(c) }) }

    private fun files(f: Final11PostgresFixture) {
        f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',false); " +
            "CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(1000); DELETE FROM lake.t WHERE i<100")
    }

    @Test
    fun nativePhysicalMetadataAndPathsAreObservedBeforeIndependentJdbcReads() {
        Final11PostgresFixture().use { f ->
            files(f)
            assertThat(f.rows("SELECT data_type FROM information_schema.columns WHERE table_name='ducklake_data_file' " +
                "AND column_name='row_group_count'").single()).containsExactly("bigint")
            assertThat(f.rows("SELECT data_type FROM information_schema.columns WHERE table_name='ducklake_file_column_stats' " +
                "AND column_name IN('min_is_exact','max_is_exact')")).containsOnly(listOf("boolean"))
            assertThat(f.rows("SELECT record_count,row_group_count FROM ducklake_data_file").single()).containsExactly("1000", "1")
            assertThat(f.rows("SELECT delete_count,row_group_count,format FROM ducklake_delete_file").single()).containsExactly("100", "1", "parquet")
            val native = f.nativeRows("SELECT data_file,delete_file FROM ducklake_list_files('lake','t')").single()
            read(f) { c ->
                val profile = c.readBackendProfile()
                assertThat(profile.richMetadataAvailable).isTrue()
                assertThat(profile.hierarchyAvailable).isTrue()
                assertThat(profile.sqlEngineSourceId).isNull()
                assertThat(profile.unsignedExtensionsAllowed).isNull()
                val table = c.readHierarchy().findTable(listOf("main"), "t", profile.snapshotId)!!
                val metadata = c.readMetadata().getFileReadMetadata(table.table.tableId, profile.snapshotId).single()
                assertThat(metadata.recordCount).isEqualTo(1000)
                assertThat(metadata.deleteCount).isEqualTo(100)
                assertThat(metadata.rowGroupCount).isEqualTo(1)
                assertThat(metadata.deleteRowGroupCount).isEqualTo(1)
                assertThat(metadata.deleteFileFormat).isEqualTo("parquet")
                assertThat(c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 10).single().resolvedPath).isEqualTo(native[0])
                assertThat(c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 10).single().resolvedPath).isEqualTo(native[1])
                assertThatThrownBy { c.readMetadata().getReadMetadataCapabilities() }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("DuckDB-only").hasMessageContaining("readBackendProfile")
            }
        }
    }

    @Test
    fun independentFlagsRawBoundsAndUnknownCountsNeverCertifyLegacyPruning() {
        Final11PostgresFixture().use { f ->
            files(f)
            for ((min, max) in listOf("NULL" to "TRUE", "FALSE" to "NULL", "TRUE" to "FALSE")) {
                f.metadata("UPDATE ducklake_file_column_stats SET min_value=NULL,max_value='2020-01-01 12:34:56.123456789'," +
                    "min_is_exact=$min,max_is_exact=$max,value_count=NULL,null_count=NULL,contains_nan=NULL; " +
                    "UPDATE ducklake_table_column_stats SET min_value='NULL',max_value=NULL,min_is_exact=$min,max_is_exact=$max")
                read(f) { c ->
                    val snapshot = c.readBackendProfile().snapshotId
                    val id = c.getTable("main", "t", snapshot)!!.tableId
                    val file = c.readMetadata().getFileColumnMetadata(id, snapshot).single()
                    val table = c.readMetadata().getTableColumnMetadata(id).single()
                    fun flag(value: String): Boolean? = if (value == "NULL") null else value == "TRUE"
                    assertThat(file.minIsExact).isEqualTo(flag(min))
                    assertThat(file.maxIsExact).isEqualTo(flag(max))
                    assertThat(file.stats.minValue).isNull()
                    assertThat(file.stats.maxValue).isEqualTo("2020-01-01 12:34:56.123456789")
                    assertThat(file.stats.valueCount).isNull()
                    assertThat(file.stats.nullCount).isNull()
                    assertThat(file.stats.containsNan).isNull()
                    assertThat(table.minIsExact).isEqualTo(flag(min))
                    assertThat(table.maxIsExact).isEqualTo(flag(max))
                    assertThat(table.minValue).isEqualTo("NULL")
                    assertThat(table.maxValue).isNull()
                    assertThat(c.getColumnStats(id, snapshot).single().minValue).isNull()
                    assertThat(c.getColumnStats(id, snapshot).single().maxValue).isNull()
                    assertThat(c.findDataFileIdsInRange(id, snapshot, ColumnRangePredicate(0, "9999", "10000")))
                        .containsExactlyElementsOf(c.getDataFiles(id, snapshot).map { it.dataFileId })
                }
            }
            for ((data, delete) in listOf("NULL" to "0", "0" to "NULL", "7" to "3")) {
                f.metadata("UPDATE ducklake_data_file SET row_group_count=$data; UPDATE ducklake_delete_file SET row_group_count=$delete")
                read(f) { c ->
                    val snapshot = c.readBackendProfile().snapshotId
                    val id = c.getTable("main", "t", snapshot)!!.tableId
                    val result = c.readMetadata().getFileReadMetadata(id, snapshot).single()
                    assertThat(result.rowGroupCount).isEqualTo(data.toLongOrNull())
                    assertThat(result.deleteRowGroupCount).isEqualTo(delete.toLongOrNull())
                }
            }
        }
    }

    @Test
    fun genuineNativeMigrationKeepsUnknownExactnessAndRowGroupsUnknown() {
        Final11PostgresFixture(migrate = true).use { f ->
            files(f)
            assertThat(f.rows("SELECT count(*) FROM information_schema.columns WHERE table_name='ducklake_data_file' " +
                "AND column_name='row_group_count'").single()).containsExactly("0")
            f.native("SELECT 1")
            read(f) { c ->
                val snapshot = c.readBackendProfile().snapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val file = c.readMetadata().getFileReadMetadata(id, snapshot).single()
                assertThat(file.rowGroupCount).isNull()
                assertThat(file.deleteRowGroupCount).isNull()
                val stats = c.readMetadata().getFileColumnMetadata(id, snapshot).single()
                assertThat(stats.minIsExact).isNull()
                assertThat(stats.maxIsExact).isNull()
                assertThat(stats.stats.minValue).isEqualTo("0")
                assertThat(c.readHierarchy().findTable(listOf("main"), "t", snapshot)).isNotNull()
            }
        }
    }

    @Test
    fun metadataAndHierarchyUseOwnedRepeatableReadAndRefreshOnlyNextSession() {
        Final11PostgresFixture().use { f ->
            files(f)
            f.reader().use { c ->
                val metadata = c.readMetadata()
                c.readSession(Supplier {
                    val profile = c.readBackendProfile()
                    val table = c.readHierarchy().findTable(listOf("main"), "t", profile.snapshotId)!!
                    f.native("ALTER TABLE lake.t RENAME TO renamed")
                    f.metadata("UPDATE ducklake_data_file SET row_group_count=77")
                    assertThat(metadata.getFileReadMetadata(table.table.tableId, profile.snapshotId).single().rowGroupCount).isEqualTo(1)
                    assertThat(c.readHierarchy().getTableById(table.table.tableId, profile.snapshotId)).isEqualTo(table)
                    assertThat(c.readBackendProfile()).isEqualTo(profile)
                })
                c.readSession(Supplier {
                    val snapshot = c.readBackendProfile().snapshotId
                    val table = c.readHierarchy().findTable(listOf("main"), "renamed", snapshot)!!
                    assertThat(metadata.getFileReadMetadata(table.table.tableId, snapshot).single().rowGroupCount).isEqualTo(77)
                    assertThat(c.readHierarchy().findTable(listOf("main"), "t", snapshot)).isNull()
                })
                assertThatThrownBy { metadata.listSchemaMetadata(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThatThrownBy { c.readHierarchy().listSchemas(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            }
        }
    }

    @Test
    fun malformedPhysicalMetadataAndPrecisionAreRejectedNotReportedAsCapabilities() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            val changes = listOf(
                "ALTER TABLE ducklake_schema RENAME parent_schema_id TO held" to "ALTER TABLE ducklake_schema RENAME held TO parent_schema_id",
                "ALTER TABLE ducklake_table ALTER path_is_relative TYPE VARCHAR" to
                    "ALTER TABLE ducklake_table ALTER path_is_relative TYPE BOOLEAN USING path_is_relative::boolean",
                "ALTER TABLE ducklake_schema ALTER schema_name TYPE VARCHAR(100)" to "ALTER TABLE ducklake_schema ALTER schema_name TYPE VARCHAR",
                "CREATE DOMAIN flag AS BOOLEAN; ALTER TABLE ducklake_file_column_stats ALTER min_is_exact TYPE flag" to
                    "ALTER TABLE ducklake_file_column_stats ALTER min_is_exact TYPE BOOLEAN; DROP DOMAIN flag",
                "ALTER TABLE ducklake_files_scheduled_for_deletion ALTER schedule_start TYPE TIMESTAMPTZ(3)" to
                    "ALTER TABLE ducklake_files_scheduled_for_deletion ALTER schedule_start TYPE TIMESTAMPTZ(6)")
            for ((change, restore) in changes) {
                f.metadata(change)
                read(f) { c ->
                    assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    assertThatThrownBy { c.readMetadata().listSchemaMetadata(f.snapshot()) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    assertThatThrownBy { c.readHierarchy().listSchemas(f.snapshot()) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                }
                f.metadata(restore)
            }
        }
    }

    @Test
    fun countsTextAndResultBudgetsFailWithoutTruncationOrInventedValues() {
        Final11PostgresFixture().use { f ->
            files(f)
            for (table in listOf("ducklake_data_file", "ducklake_delete_file")) {
                f.metadata("UPDATE $table SET row_group_count=-1")
                read(f) { c ->
                    val snapshot = c.readBackendProfile().snapshotId
                    val id = c.getTable("main", "t", snapshot)!!.tableId
                    assertThatThrownBy { c.readMetadata().getFileReadMetadata(id, snapshot) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                }
                f.metadata("UPDATE $table SET row_group_count=NULL")
            }
            f.metadata("UPDATE ducklake_file_column_stats SET min_value=repeat('x',16385)")
            read(f) { c ->
                val snapshot = c.readBackendProfile().snapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                assertThatThrownBy { c.readMetadata().getFileColumnMetadata(id, snapshot) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text exceeds")
                assertThatThrownBy { c.readMetadata().getFileReadMetadata(id, snapshot + 100) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("not visible")
            }
            f.metadata("INSERT INTO ducklake_schema SELECT 1000000+i,gen_random_uuid(),0,NULL,'budget_'||i,NULL,NULL,NULL " +
                "FROM generate_series(1,65537) i")
            read(f) { c ->
                assertThatThrownBy { c.readMetadata().listSchemaMetadata(f.snapshot()) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("row budget")
                assertThatThrownBy { c.readHierarchy().listSchemas(f.snapshot()) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("node budget")
            }
        }
    }

    @Test
    fun nullRequiredCountsNegativeStatsAndExpiredFilesNeverProduceMisleadingMetadata() {
        Final11PostgresFixture().use { f ->
            files(f)
            for (column in listOf("value_count", "null_count", "column_size_bytes")) {
                f.metadata("UPDATE ducklake_file_column_stats SET $column=-1")
                read(f) { c ->
                    val snapshot = c.readBackendProfile().snapshotId
                    val id = c.getTable("main", "t", snapshot)!!.tableId
                    assertThatThrownBy { c.readMetadata().getFileColumnMetadata(id, snapshot) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("negative")
                }
                f.metadata("UPDATE ducklake_file_column_stats SET $column=0")
            }
            f.metadata("UPDATE ducklake_data_file SET record_count=NULL")
            read(f) { c ->
                val snapshot = c.readBackendProfile().snapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                assertThatThrownBy { c.readMetadata().getFileReadMetadata(id, snapshot) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("NULL required record_count")
            }
            val snapshot = f.snapshot()
            f.metadata("UPDATE ducklake_data_file SET record_count=1000,end_snapshot=$snapshot")
            read(f) { c ->
                val id = c.getTable("main", "t", snapshot)!!.tableId
                assertThat(c.readMetadata().getFileReadMetadata(id, snapshot)).isEmpty()
                assertThat(c.readMetadata().getFileColumnMetadata(id, snapshot)).isEmpty()
                assertThat(c.readMetadata().getFileReadMetadata(id, snapshot - 1)).hasSize(1)
            }
        }
    }

    @Test
    fun companionsRequireOptInOwnedSessionsAndFinalFormatWithoutBootstrapping() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            f.reader().use { c ->
                val m = c.readMetadata()
                val h = c.readHierarchy()
                val actions = listOf<() -> Any?>({ m.listSchemaMetadata(0) }, { m.getFileReadMetadata(1, 0) },
                    { m.getFileColumnMetadata(1, 0) }, { m.getTableColumnMetadata(1) }, { h.listSchemas(0) },
                    { h.listRetainedFiles(DucklakeFileKind.DATA, null, 1) }, { h.listScheduledReferences(null, 1) })
                actions.forEach { assertThatThrownBy { it() }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession") }
            }
            f.reader(enableFinal11Reads = false).use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.readMetadata().listSchemaMetadata(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("enableFinal11Reads")
                assertThatThrownBy { c.readHierarchy().listSchemas(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            }) }
            f.metadata("UPDATE ducklake_metadata SET value='1.1-dev1' WHERE key='version'")
            read(f) { c ->
                assertThatThrownBy { c.readMetadata().getTableColumnMetadata(1) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThatThrownBy { c.readHierarchy().listSchemas(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            }
            assertThat(f.rows("SELECT value FROM ducklake_metadata WHERE key='version'").single()).containsExactly("1.1-dev1")
        }
    }
}
