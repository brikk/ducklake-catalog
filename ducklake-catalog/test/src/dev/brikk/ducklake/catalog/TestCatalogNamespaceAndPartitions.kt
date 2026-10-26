package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class TestCatalogNamespaceAndPartitions {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun sequentialNamespacesRejectCollisionsWithoutPublishingSnapshots() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                c.createSchema("Names")
                rejectsName(c) { c.createSchema("nAMES") }
                c.createTable("Names", "t", listOf(leaf("i")), null, null)
                val table = c.getTable("names", "t", c.currentSnapshotId)!!
                rejectsName(c) { c.createTable("NAMES", "T", listOf(leaf("i")), null, null) }
                rejectsName(c) { c.createView("names", "T", "SELECT 1", "duckdb", emptyList(), emptyMap()) }
                c.createView("names", "v", "SELECT 1", "duckdb", emptyList(), emptyMap())
                rejectsName(c) { c.createView("names", "V", "SELECT 2", "duckdb", emptyList(), emptyMap()) }
                rejectsName(c) { c.createTable("names", "V", listOf(leaf("i")), null, null) }
                rejectsName(c) { c.renameTable(table.tableId, "names", "V") }
                rejectsName(c) { c.renameView("names", "v", "names", "T") }
                rejectsName(c) { c.renameSchema("names", "MAIN") }
                c.dropView("NAMES", "V")
                c.renameTable(table.tableId, "names", "v")
                assertThat(c.getTable("names", "V", c.currentSnapshotId)!!.tableId).isEqualTo(table.tableId)
                // Unicode names that DuckDB distinguishes must remain independently writable/resolvable.
                c.createSchema("Ä"); c.createSchema("ä")
                c.createView("Ä", "Ä", "SELECT 1", "duckdb", emptyList(), emptyMap())
                c.createView("ä", "ä", "SELECT 2", "duckdb", emptyList(), emptyMap())
                assertThat(c.getView("Ä", "Ä", c.currentSnapshotId)!!.sql).isEqualTo("SELECT 1")
                assertThat(c.getView("ä", "ä", c.currentSnapshotId)!!.sql).isEqualTo("SELECT 2")
                c.createView("names", "Ä", "SELECT 1", "duckdb", emptyList(), mapOf("comment" to "upper"))
                c.createView("names", "ä", "SELECT 2", "duckdb", emptyList(), mapOf("comment" to "lower"))
                val history = c.currentSnapshotId
                c.replaceViewMetadata("names", "Ä", "SELECT 10", "duckdb", emptyList(), mapOf("comment" to "changed"))
                assertThat(c.getView("names", "ä", c.currentSnapshotId)!!.sql).isEqualTo("SELECT 2")
                rejectsName(c) { c.renameView("names", "ä", "names", "Ä") }
                c.renameView("names", "ä", "names", "moved")
                c.dropView("names", "Ä")
                assertThat(c.getView("names", "Ä", c.currentSnapshotId)).isNull()
                assertThat(c.getView("names", "moved", c.currentSnapshotId)!!.comment).isEqualTo("lower")
                assertThat(c.getView("names", "Ä", history)!!.comment).isEqualTo("upper")
                assertThat(c.getView("names", "ä", history)!!.comment).isEqualTo("lower")
            } }
        }

        @Test fun columnAndFieldSiblingsUseTheSameAsciiNamespace() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                rejectsName(c) { c.createTable("main", "bad", listOf(leaf("i"), leaf("I")), null, null) }
                rejectsName(c) { c.createTable("main", "bad_nested", listOf(struct("s", leaf("x"), leaf("X"))), null, null) }
                c.createTable("main", "t", listOf(leaf("i"), leaf("j"), struct("s", leaf("x"))), null, null)
                val id = c.getTable("main", "t", c.currentSnapshotId)!!.tableId
                rejectsName(c) { c.addColumn(id, leaf("I")) }
                val columns = c.getAllColumnsWithParentage(id, c.currentSnapshotId)
                rejectsName(c) { c.renameColumn(id, columns.single { it.columnName == "j" }.columnId, "I") }
                rejectsName(c) { c.addField(id, listOf("s"), leaf("X"), false) }
                val before = c.currentSnapshotId
                c.addField(id, listOf("S"), leaf("X"), true)
                assertThat(c.currentSnapshotId).isEqualTo(before)
                c.addField(id, listOf("S"), leaf("Ä"), false)
                c.addField(id, listOf("s"), leaf("ä"), false)
                val nested = c.getAllColumnsWithParentage(id, c.currentSnapshotId).filter { it.parentColumn != null }
                assertThat(nested.map { it.columnName }).containsExactly("x", "Ä", "ä")
                rejectsName(c) { c.renameColumn(id, nested.single { it.columnName == "Ä" }.columnId, "X") }
            } }
        }

        @Test fun partitionOwnershipAndExactKeysAreValidatedBeforeFilePublication() {
            fixture().use { f -> f.seed(); f.reader().use { c ->
                val fields = listOf(PartitionFieldSpec("i", DucklakePartitionTransform.IDENTITY), PartitionFieldSpec("j", DucklakePartitionTransform.IDENTITY))
                for (name in listOf("a", "b")) c.createTable("main", name, listOf(leaf("i"), leaf("j")), fields, null)
                val snapshot = c.currentSnapshotId
                val a = c.getTable("main", "a", snapshot)!!.tableId
                val b = c.getTable("main", "b", snapshot)!!.tableId
                val spec = c.getPartitionSpecs(a, snapshot).single().partitionId
                val foreign = c.getPartitionSpecs(b, snapshot).single().partitionId
                val invalid = listOf(fragment(spec, emptyMap()), fragment(spec, mapOf(0 to "1")),
                    fragment(spec, mapOf(0 to "1", 1 to "2", 2 to "extra")), fragment(foreign, mapOf(0 to "1", 1 to "2")),
                    fragment(Long.MAX_VALUE, mapOf(0 to "1", 1 to "2")), fragment(null, mapOf(0 to "1")))
                for (bad in invalid) {
                    val before = c.currentSnapshotId
                    assertThatThrownBy { c.commitInsert(a, listOf(bad)) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    assertThat(c.currentSnapshotId).isEqualTo(before)
                    assertThat(c.getDataFiles(a, before)).isEmpty()
                }
                // A valid first fragment must not publish when a later fragment is invalid.
                assertThatThrownBy { c.commitAddFiles(a, listOf(fragment(spec, mapOf(0 to "1", 1 to "2")), invalid.first())) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThat(c.currentSnapshotId).isEqualTo(snapshot)
                c.commitInsert(a, listOf(fragment(spec, mapOf(0 to null, 1 to "2"))))
                val file = c.getDataFiles(a, c.currentSnapshotId).single()
                assertThat(file.partitionId).isEqualTo(spec)
                assertThat(c.getFilePartitionValues(a, c.currentSnapshotId).getValue(file.dataFileId)
                    .associate { it.partitionKeyIndex to it.partitionValue }).containsExactlyInAnyOrderEntriesOf(mapOf(0 to null, 1 to "2"))
            } }
        }

        private fun rejectsName(c: JdbcDucklakeCatalog, action: () -> Unit) {
            val before = c.currentSnapshotId
            assertThatThrownBy { action() }.isInstanceOf(DucklakeEntityAlreadyExistsException::class.java)
            assertThat(c.currentSnapshotId).isEqualTo(before)
        }
        private fun leaf(name: String) = TableColumnSpec.leaf(name, "int32", true)
        private fun struct(name: String, vararg children: TableColumnSpec) = TableColumnSpec(name, "struct", true, children.toList())
        private fun fragment(id: Long?, values: Map<Int, String?>) = DucklakeWriteFragment("owned.parquet", 100, 10, 1, emptyList(), values, id)
    }
}
