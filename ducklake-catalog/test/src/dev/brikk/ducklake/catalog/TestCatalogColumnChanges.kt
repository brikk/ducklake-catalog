package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class TestCatalogColumnChanges {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun invalidNestedRemovalsNeverPublishInvalidTypeTrees() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                c.createTable("main", "t", listOf(leaf("id"), nested("s", "struct", leaf("a")),
                    nested("l", "list", leaf("element")), nested("m", "map", leaf("key"), leaf("value"))), null, null)
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!.tableId
                val before = c.getAllColumnsWithParentage(table, snapshot)
                val child = before.single { it.columnName == "a" }.columnId
                val actions: List<() -> Unit> = listOf(
                    { c.dropField(table, listOf("s", "a")) },
                    { c.dropField(table, listOf("l", "element")) },
                    { c.dropField(table, listOf("m", "key")) },
                    { c.dropField(table, listOf("m", "value")) },
                    { c.dropField(table, listOf("id")) },
                    { c.dropColumn(table, child) },
                )
                for (action in actions) {
                    assertThatThrownBy { action() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    assertThat(c.currentSnapshotId).isEqualTo(snapshot)
                    assertThat(c.getAllColumnsWithParentage(table, snapshot)).isEqualTo(before)
                }
                c.dropColumn(table, before.single { it.columnName == "s" }.columnId)
                assertThat(c.getAllColumnsWithParentage(table, c.currentSnapshotId).map { it.columnId }).doesNotContain(child)
                assertThat(c.getAllColumnsWithParentage(table, snapshot)).isEqualTo(before.map {
                    if (it.columnName in setOf("s", "a")) it.copy(endSnapshot = snapshot + 1) else it
                })
            } }
        }

        @Test fun removingANonLastStructFieldPreservesTheOnlyRootAndHistoricalDescendants() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                c.createTable("main", "t", listOf(nested("s", "struct", leaf("keep"),
                    nested("deep", "struct", leaf("x"), leaf("y")))), null, null)
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!.tableId
                val before = c.getAllColumnsWithParentage(table, snapshot)
                c.dropField(table, listOf("S", "deep"))
                assertThat(c.getAllColumnsWithParentage(table, c.currentSnapshotId).map { it.columnName }).containsExactly("s", "keep")
                assertThat(c.getAllColumnsWithParentage(table, snapshot)).isEqualTo(before.map {
                    if (it.columnName in setOf("deep", "x", "y")) it.copy(endSnapshot = snapshot + 1) else it
                })
                assertThatThrownBy { c.dropField(table, listOf("s", "keep")) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThatThrownBy { c.dropColumn(table, before.single { it.columnName == "s" }.columnId) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThat(c.currentSnapshotId).isEqualTo(snapshot + 1)
            } }
        }

        @Test fun renameVersionsSortReferencesAndPreservesFunctionsLiteralsAndHistory() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                c.createTable("main", "t", listOf(leaf("x"), leaf("abs"), leaf("unused")), null, null)
                val before = c.currentSnapshotId
                val table = c.getTable("main", "t", before)!!.tableId
                val columns = c.getTableColumns(table, before).associateBy { it.columnName }
                installSort(f, c, table, listOf("abs(x) + length('x')", "abs + 1"))
                val original = c.getSortKeys(table, before)
                c.renameColumn(table, columns.getValue("x").columnId, "new \"x\"")
                val intermediate = c.currentSnapshotId
                val first = c.getSortKeys(table, intermediate)
                assertThat(first[0].expression).contains("abs(", "'x'", "\"new \"\"x\"\"\"")
                assertThat(first.map { it.direction to it.nullOrder }).isEqualTo(original.map { it.direction to it.nullOrder })
                c.renameColumn(table, columns.getValue("abs").columnId, "magnitude")
                val second = c.getSortKeys(table, c.currentSnapshotId)
                assertThat(second[0].expression).isEqualTo(first[0].expression) // Function abs is not the column abs.
                assertThat(second[1].expression).contains("magnitude")
                assertThat(c.getSortKeys(table, before)).isEqualTo(original)
                assertThat(c.getSortKeys(table, intermediate)).isEqualTo(first)
                val nextId = c.getSnapshot(c.currentSnapshotId)!!.nextCatalogId
                c.renameColumn(table, columns.getValue("unused").columnId, "other")
                assertThat(c.getSortKeys(table, c.currentSnapshotId)).isEqualTo(second)
                assertThat(c.getSnapshot(c.currentSnapshotId)!!.nextCatalogId).isEqualTo(nextId)
            } }
        }

        @Test fun nestedPathsCannotBypassPartitionOrSortDependencies() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                val columns = listOf(nested("s", "struct", leaf("a"), leaf("b")), leaf("i"))
                c.createTable("main", "partitioned", columns, listOf(PartitionFieldSpec("s", DucklakePartitionTransform.BUCKET, 2)), null)
                c.createTable("main", "sorted", columns, null, null)
                val snapshot = c.currentSnapshotId
                val partitioned = c.getTable("main", "partitioned", snapshot)!!.tableId
                val sorted = c.getTable("main", "sorted", snapshot)!!.tableId
                installSort(f, c, sorted, listOf("s"))
                assertThatThrownBy { c.dropField(partitioned, listOf("s", "a")) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThatThrownBy { c.dropField(sorted, listOf("s", "a")) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                val field = c.getAllColumnsWithParentage(sorted, snapshot).single { it.columnName == "a" }
                assertThatThrownBy { c.renameColumn(sorted, field.columnId, "changed") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThat(c.currentSnapshotId).isEqualTo(snapshot)
            } }
        }

        @Test fun unqualifiedSortDialectCannotBeSilentlyCarriedAcrossRename() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                c.createTable("main", "t", listOf(leaf("x")), null, null)
                val before = c.currentSnapshotId
                val table = c.getTable("main", "t", before)!!.tableId
                val column = c.getTableColumns(table, before).single()
                installSort(f, c, table, listOf("x"), "unknown_writer")
                val sort = c.getSortKeys(table, before)
                assertThatThrownBy { c.renameColumn(table, column.columnId, "renamed") }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThat(c.currentSnapshotId).isEqualTo(before)
                assertThat(c.getTableColumns(table, before)).containsExactly(column)
                assertThat(c.getSortKeys(table, before)).isEqualTo(sort)
            } }
        }

        private fun installSort(f: LegacyWriteFixture, c: JdbcDucklakeCatalog, table: Long, expressions: List<String>, dialect: String = "duckdb") {
            val snapshot = c.currentSnapshotId
            val id = c.getSnapshot(snapshot)!!.nextCatalogId
            f.metadata("INSERT INTO ducklake_sort_info(sort_id,table_id,begin_snapshot) VALUES($id,$table,$snapshot)")
            expressions.forEachIndexed { index, expression ->
                f.metadata("INSERT INTO ducklake_sort_expression(sort_id,table_id,sort_key_index,expression,dialect,sort_direction,null_order) " +
                    "VALUES($id,$table,$index,'${expression.replace("'", "''")}','$dialect','DESC','NULLS_FIRST')")
            }
            f.metadata("UPDATE ducklake_snapshot SET next_catalog_id=${id + 1} WHERE snapshot_id=$snapshot")
        }
        private fun leaf(name: String) = TableColumnSpec.leaf(name, "int32", true)
        private fun nested(name: String, type: String, vararg children: TableColumnSpec) = TableColumnSpec(name, type, true, children.toList())
    }
}
