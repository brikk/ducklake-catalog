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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Supplier

internal class TestFinal11PlanningMetadataPages {
    @Nested inner class Local : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    @Nested inner class NativeQuack : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) }
    @Nested inner class JavaQuack : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) }
    @Nested inner class Postgres : NativeContract { override fun fixture() = ReadAcceptanceFixture.Postgres() }
    @Nested inner class MySql : Contract { override fun fixture() = ReadAcceptanceFixture.MySql() }

    interface Contract {
        fun fixture(): ReadAcceptanceFixture

        @Test
        fun schemaTableColumnAndInlinePagesHaveIndependentExpectedValuesAndBoundedTraversal() {
            fixture().use { f ->
                seed(f)
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val pages = c.readPlanningMetadata()
                    val schemas = drain<Long, DucklakeSchema>({ pages.listSchemasPage(snapshot, it, 1) }, { it.schemaId })
                    assertThat(schemas.map { it.schemaName }).containsExactlyInAnyOrder("main", "s")
                    assertThat(schemas).containsExactlyElementsOf(c.listSchemas(snapshot).sortedBy { it.schemaId })
                    val main = schemas.single { it.schemaName == "main" }
                    val tables = drain<Long, DucklakeTable>({ pages.listTablesPage(main.schemaId, snapshot, it, 1) }, { it.tableId })
                    assertThat(tables.map { it.tableName }).containsExactly("u")
                    val table = c.getTable("s", "t", snapshot)!!
                    val columns = drain<Long, DucklakeColumn>({ pages.getColumnsPage(table.tableId, snapshot, it, 1) }, { it.columnId })
                    assertThat(columns.map { it.columnName }).containsExactly("i", "v")
                    assertThat(columns).containsExactlyElementsOf(c.getAllColumnsWithParentage(table.tableId, snapshot).sortedBy { it.columnId })
                    val versions = drain<Long, DucklakeInlinedDataInfo>(
                        { pages.getInlineVersionsPage(tables.single().tableId, snapshot, it, 1) }, { it.schemaVersion },
                    )
                    assertThat(versions).hasSize(1)
                    assertThat(versions.single().hasLiveRows).isTrue()
                    assertThat(versions).containsExactlyElementsOf(c.getInlinedDataInfos(tables.single().tableId, snapshot))
                    assertThat(pages.getPartitionFieldsPage(table.tableId, snapshot, null, 1)).isEmpty()
                    assertThat(pages.getFilePartitionValuesPage(table.tableId, snapshot, null, 1)).isEmpty()
                    Unit
                }) }
            }
        }

        @Test
        fun mappingRowsKeepRegistryIdentityParentAndPartitionFlagsWithoutInventingPayloadSupport() {
            fixture().use { f ->
                seed(f)
                // Authored immutable mapping registry fixture, not native add_files or a connector payload scan.
                f.metadata("INSERT INTO ducklake_name_mapping(mapping_id,column_id,source_name,target_field_id,parent_column,is_partition) " +
                    "VALUES(303,11,'old_i',0,NULL,false),(303,22,'old_v',1,11,true)")
                f.reader().use { c -> c.readSession(Supplier {
                    val pages = c.readPlanningMetadata()
                    val rows = drain<Long, DucklakeNameMappingRow>({ pages.getNameMappingPage(303, it, 1) }, { it.mappingColumnId })
                    assertThat(rows).containsExactly(DucklakeNameMappingRow(11, "old_i", 0, null, false),
                        DucklakeNameMappingRow(22, "old_v", 1, 11, true))
                    assertThat(pages.getNameMappingPage(999, null, 1)).isEmpty()
                }) }
            }
        }

        @Test
        fun missingPhysicalInlineTablesDoNotTurnRegistryPagesIntoFalseTerminalPages() {
            fixture().use { f ->
                seed(f)
                val info = f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "u", snapshot)!!
                    c.readPlanningMetadata().getInlineVersionsPage(table.tableId, snapshot, null, 1).single()
                }) }
                f.metadata("DROP TABLE ${info.tableName}") // Owned absent-table negative fixture, not native flush qualification.
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val pages = c.readPlanningMetadata()
                    val row = pages.getInlineVersionsPage(info.tableId, snapshot, null, 1).single()
                    assertThat(row.schemaVersion).isEqualTo(info.schemaVersion)
                    assertThat(row.hasLiveRows).isFalse()
                    assertThat(pages.getInlineVersionsPage(info.tableId, snapshot, row.schemaVersion, 1)).isEmpty()
                }) }
            }
        }

        @Test
        fun guardFailuresRejectWithoutTruncatingOversizedNamesOrExposingAnUnownedView() {
            fixture().use { f ->
                seed(f)
                f.reader().use { c ->
                    val pages = c.readPlanningMetadata()
                    assertThatThrownBy { pages.listSchemasPage(0, null, 1) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                        .hasMessageContaining("readSession")
                    c.readSession(Supplier {
                        val snapshot = c.currentSnapshotId
                        assertThatThrownBy { pages.listSchemasPage(snapshot, null, 0) }.isInstanceOf(IllegalArgumentException::class.java)
                        assertThatThrownBy { pages.getNameMappingPage(0, null, 1025) }.isInstanceOf(IllegalArgumentException::class.java)
                        assertThatThrownBy { pages.getColumnsPage(0, snapshot, -1, 1) }.isInstanceOf(IllegalArgumentException::class.java)
                        assertThatThrownBy { pages.listSchemasPage(snapshot + 1, null, 1) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    })
                }
                f.metadata("UPDATE ducklake_schema SET schema_name='${"x".repeat(16385)}' WHERE schema_name='s'")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.readPlanningMetadata().listSchemasPage(c.currentSnapshotId, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text")
                    assertThat(c.getSnapshot(c.currentSnapshotId)).isNotNull()
                }) }
            }
        }

        private fun seed(f: ReadAcceptanceFixture) {
            f.native("CALL lake.set_option('data_inlining_row_limit',2); BEGIN; CREATE SCHEMA lake.s; " +
                "CREATE TABLE lake.u(i INTEGER); INSERT INTO lake.u VALUES(9); CREATE TABLE lake.s.t(i INTEGER,v VARCHAR); " +
                "INSERT INTO lake.s.t VALUES(1,'a/b'),(2,NULL),(3,'NULL'); COMMIT")
        }

        fun <C, T> drain(fetch: (C?) -> List<T>, cursor: (T) -> C): List<T> {
            var after: C? = null
            val rows = mutableListOf<T>()
            var complete = false
            repeat(8) {
                if (!complete) {
                    val page = fetch(after)
                    assertThat(page.size).isLessThanOrEqualTo(1)
                    if (page.isEmpty()) complete = true else {
                        assertThat(cursor(page.single())).isNotEqualTo(after)
                        rows.addAll(page)
                        after = cursor(page.single())
                    }
                }
            }
            assertThat(complete).isTrue()
            return rows
        }
    }

    interface NativeContract : Contract {
        @Test
        fun nativePartitionFieldsValuesAndInlineSchemaHistorySurviveOneItemPages() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER,v VARCHAR); " +
                    "ALTER TABLE lake.t SET PARTITIONED BY(i,v); INSERT INTO lake.t VALUES(1,'a/b'),(1,'NULL'),(2,NULL)")
                f.native("CALL lake.set_option('data_inlining_row_limit',10); CREATE TABLE lake.u(i INTEGER); INSERT INTO lake.u VALUES(9); " +
                    "ALTER TABLE lake.u ADD COLUMN c INTEGER DEFAULT 42; INSERT INTO lake.u VALUES(10,7)")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val pages = c.readPlanningMetadata()
                    val fields = drain<DucklakePartitionFieldCursor, DucklakePartitionFieldPageEntry>(
                        { pages.getPartitionFieldsPage(table.tableId, snapshot, it, 1) }, { it.cursor },
                    )
                    assertThat(fields.map { it.field.partitionKeyIndex }).containsExactly(0, 1)
                    assertThat(fields.map { it.field.transform }).containsOnly(DucklakePartitionTransform.IDENTITY)
                    assertThat(fields.map { it.field }).containsExactlyElementsOf(c.getPartitionSpecs(table.tableId, snapshot).single().fields)
                    val values = drain<DucklakeFilePartitionCursor, DucklakeFilePartitionValue>(
                        { pages.getFilePartitionValuesPage(table.tableId, snapshot, it, 1) },
                        { DucklakeFilePartitionCursor(it.dataFileId, it.partitionKeyIndex) },
                    )
                    assertThat(values.map { it.partitionValue }).containsExactlyInAnyOrder("1", "a/b", "1", "NULL", "2", null)
                    assertThat(values).containsExactlyElementsOf(c.getFilePartitionValues(table.tableId, snapshot).values.flatten()
                        .sortedWith(compareBy({ it.dataFileId }, { it.partitionKeyIndex })))
                    val inline = c.getTable("main", "u", snapshot)!!
                    val versions = drain<Long, DucklakeInlinedDataInfo>(
                        { pages.getInlineVersionsPage(inline.tableId, snapshot, it, 1) }, { it.schemaVersion },
                    )
                    assertThat(versions).hasSize(2)
                    assertThat(versions.map { it.hasLiveRows }).containsOnly(true)
                    assertThat(versions).containsExactlyElementsOf(c.getInlinedDataInfos(inline.tableId, snapshot))
                    Unit
                }) }
            }
        }
    }

    @Test
    fun duplicateMappingSeekKeysRejectEvenAtTheSqlLookaheadBoundary() {
        ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB).use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            f.metadata("INSERT INTO ducklake_name_mapping(mapping_id,column_id,source_name,target_field_id,parent_column,is_partition) " +
                "VALUES(303,11,'a',0,NULL,false),(303,11,'b',0,NULL,false)")
            f.reader().use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.readPlanningMetadata().getNameMappingPage(303, null, 1) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("Duplicate")
                assertThat(c.getSnapshot(c.currentSnapshotId)).isNotNull()
            }) }
        }
    }

    @Test
    fun wideColumnMetadataRequiresSmallerPagesRatherThanBypassingTheAggregateTextBound() {
        ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB).use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER,v VARCHAR)")
            val literal = "x".repeat(8000)
            f.metadata("UPDATE ducklake_column SET initial_default='$literal' WHERE column_name='v'")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val pages = c.readPlanningMetadata()
                assertThatThrownBy { pages.getColumnsPage(table.tableId, snapshot, null, 1024) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text")
                val first = pages.getColumnsPage(table.tableId, snapshot, null, 1).single()
                val second = pages.getColumnsPage(table.tableId, snapshot, first.columnId, 1).single()
                assertThat(second.columnName).isEqualTo("v")
                assertThat(second.initialDefault).isEqualTo(literal)
                assertThat(pages.getColumnsPage(table.tableId, snapshot, second.columnId, 1)).isEmpty()
            }) }
        }
    }
}
