package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.jooq.exception.DataAccessException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.function.Supplier

internal class TestLegacyInlineProjections {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun ordinaryDefaultsUseTargetHistoryWhileLegacyCdcKeepsNullFill(): Unit = fixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER); INSERT INTO lake.t VALUES(7); ALTER TABLE lake.t ADD COLUMN n INTEGER DEFAULT 42")
            val historical = f.reader().use { it.currentSnapshotId }
            f.native("ALTER TABLE lake.t RENAME COLUMN n TO renamed; ALTER TABLE lake.t ALTER COLUMN renamed TYPE BIGINT; " +
                "ALTER TABLE lake.t ALTER COLUMN renamed SET DEFAULT 99; INSERT INTO lake.t VALUES(8,NULL); INSERT INTO lake.t(id) VALUES(9)")
            f.reader().use { c ->
                val now = c.currentSnapshotId
                val table = c.getTable("main", "t", now)!!.tableId
                val columns = c.getTableColumns(table, now)
                val infos = c.getInlinedDataInfos(table, now)
                assertThat(infos.flatMap { c.readInlinedDataDecoded(table, it.schemaVersion, now, columns) }.sortedBy { it[0] as Int })
                    .containsExactly(listOf<Any?>(7, 42L), listOf(8, null), listOf<Any?>(9, 99L))
                val old = infos.minBy { it.schemaVersion }
                val pastColumns = c.getTableColumns(table, historical)
                assertThat(c.readInlinedDataDecoded(table, old.schemaVersion, historical, pastColumns)).containsExactly(listOf(7, 42))
                // Public DTOs carry projection IDs, not authority to supply a different default/type.
                val forged = columns.map { if (it.columnName == "renamed") it.copy(initialDefault = "666", columnType = "varchar") else it }
                assertThat(c.readInlinedDataDecoded(table, old.schemaVersion, now, forged)).containsExactly(listOf<Any?>(7, 42L))
                assertThat(c.readSession(Supplier { c.readInlinedDataDecoded(table, old.schemaVersion, now, columns) }))
                    .containsExactly(listOf<Any?>(7, 42L))
                val raw = c.readInlinedData(table, old.schemaVersion, now, columns).single()
                assertThat((raw[1] as Number).toLong()).isEqualTo(42L)
                for (decoded in listOf(false, true)) {
                    val changes = if (decoded) c.getInlinedChangesBetweenDecoded(table, old.schemaVersion, 0, now, columns.map { it.columnId })
                        else c.getInlinedChangesBetween(table, old.schemaVersion, 0, now, columns.map { it.columnId })
                    assertThat(changes.single().values).containsExactly(7, null)
                    assertThat(changes.single().endSnapshot).isNull()
                }
                assertThat(c.currentSnapshotId).isEqualTo(now)
            }
            assertThat(f.native("SELECT id,renamed FROM lake.t ORDER BY id"))
                .containsExactly(listOf("7", "42"), listOf("8", null), listOf("9", "99"))
        }

        @Test fun missingScalarDefaultsAreLiteralTypedValuesAndNeverReplaceStoredNulls(): Unit = fixture().use { f ->
            val literal = "42'); DROP TABLE ducklake_snapshot; --"
            f.native("CREATE TABLE lake.t(id INTEGER); INSERT INTO lake.t VALUES(1); " +
                "ALTER TABLE lake.t ADD COLUMN s VARCHAR DEFAULT 'NULL'; " +
                "ALTER TABLE lake.t ADD COLUMN q VARCHAR DEFAULT ${q(literal)}; " +
                "ALTER TABLE lake.t ADD COLUMN b BLOB DEFAULT '\\x00\\x5C\\xFF'::BLOB; " +
                "ALTER TABLE lake.t ADD COLUMN ts TIMESTAMP_NS DEFAULT TIMESTAMP_NS '1969-12-31 23:59:59.123456789'; " +
                "ALTER TABLE lake.t ADD COLUMN d DECIMAL(20,3) DEFAULT 12345678901234567.125; " +
                "ALTER TABLE lake.t ADD COLUMN p STRUCT(a INTEGER); INSERT INTO lake.t VALUES(2,NULL,NULL,NULL,NULL,NULL,NULL)")
            f.reader().use { c ->
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!.tableId
                val columns = c.getTableColumns(table, snapshot)
                val rows = c.getInlinedDataInfos(table, snapshot).flatMap { c.readInlinedDataDecoded(table, it.schemaVersion, snapshot, columns) }
                    .sortedBy { it[0] as Int }
                assertThat(rows).hasSize(2)
                assertThat(rows[0].take(3)).containsExactly(1, "NULL", literal)
                assertThat(rows[0][3] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                assertThat(rows[0].drop(4)).containsExactly(LocalDateTime.parse("1969-12-31T23:59:59.123456789"), BigDecimal("12345678901234567.125"), null)
                assertThat(rows[1]).containsExactly(2, null, null, null, null, null, null)
                val old = c.getInlinedDataInfos(table, snapshot).minBy { it.schemaVersion }
                val raw = c.readInlinedData(table, old.schemaVersion, snapshot, columns).single()
                val all = c.getAllColumnsWithParentage(table, snapshot)
                for (i in columns.indices) {
                    val value = InlinedValues.decode(raw[i], InlinedValues.typeTree(all.single { it.columnId == columns[i].columnId }, all))
                    assertThat(value).describedAs(columns[i].columnName).isEqualTo(rows[0][i])
                }
            }
            assertThat(f.native("SELECT id,s,q,hex(b) FROM lake.t ORDER BY id"))
                .containsExactly(listOf("1", "NULL", literal, "005CFF"), listOf("2", null, null, null))
        }

        @Test fun emptyProjectionPreservesLiveHistoricalAndCdcCardinality(): Unit = fixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER); INSERT INTO lake.t VALUES(1),(2)")
            val before = f.reader().use { it.currentSnapshotId }
            f.native("DELETE FROM lake.t WHERE id=2")
            f.reader().use { c ->
                val now = c.currentSnapshotId
                val table = c.getTable("main", "t", now)!!.tableId
                val version = c.getInlinedDataInfos(table, now).single().schemaVersion
                for ((snapshot, count) in listOf(before to 2, now to 1)) {
                    assertThat(c.countInlinedRows(table, version, snapshot)).isEqualTo(count.toLong())
                    assertThat(c.readInlinedData(table, version, snapshot, emptyList())).isEqualTo(List(count) { emptyList<Any?>() })
                    assertThat(c.readInlinedDataDecoded(table, version, snapshot, emptyList())).isEqualTo(List(count) { emptyList<Any?>() })
                }
                val changes = c.getInlinedChangesBetweenDecoded(table, version, 0, now, emptyList())
                assertThat(changes.map { it.rowId }).containsExactly(0L, 1L)
                assertThat(changes.map { it.values }).containsExactly(emptyList(), emptyList())
                assertThat(c.readInlinedDataDecoded(table, version + 999, now, emptyList())).isEmpty()
            }
        }

        @Test fun missingDefaultsRejectUnknownHistoryInvalidValuesAndUnknownProjectionIds(): Unit = fixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER); INSERT INTO lake.t VALUES(1); ALTER TABLE lake.t ADD COLUMN n INTEGER DEFAULT 42; " +
                "ALTER TABLE lake.t ADD COLUMN p STRUCT(a INTEGER)")
            f.reader().use { c ->
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!.tableId
                val columns = c.getTableColumns(table, snapshot).associateBy { it.columnName }
                val old = c.getInlinedDataInfos(table, snapshot).minBy { it.schemaVersion }
                fun reject(name: String, value: String?, kind: String) {
                    f.metadata("UPDATE ducklake_column SET initial_default=${value?.let(::q) ?: "NULL"},default_value_type=${q(kind)} " +
                        "WHERE table_id=$table AND column_name=${q(name)}")
                    for (decoded in listOf(false, true)) assertThatThrownBy {
                        if (decoded) c.readInlinedDataDecoded(table, old.schemaVersion, snapshot, listOf(columns.getValue(name)))
                        else c.readInlinedData(table, old.schemaVersion, snapshot, listOf(columns.getValue(name)))
                    }.isInstanceOfAny(DucklakeException::class.java, DataAccessException::class.java)
                    assertThat(c.getSnapshot(snapshot)).isNotNull()
                }
                reject("n", "42", "unknown")
                reject("n", null, "expression")
                reject("n", "0); DROP TABLE ducklake_snapshot; --", "literal")
                reject("n", "2147483648", "literal")
                reject("p", "{'a':1}", "literal")
                assertThatThrownBy { c.readInlinedDataDecoded(table, old.schemaVersion, snapshot,
                    listOf(columns.getValue("id").copy(columnId = Long.MAX_VALUE))) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            }
        }

        private fun q(value: String) = "'${value.replace("'", "''")}'"
    }

    @Test fun mysqlLegacyMissingDefaultsRejectWhileExistingPayloadEmptyProjectionAndCdcRemainUsable() {
        LegacyWriteFixture.MySql().use { f -> f.seed(); f.reader().use { c ->
            c.createTable("main", "t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
            val before = c.currentSnapshotId
            val table = c.getTable("main", "t", before)!!.tableId
            val version = c.getSnapshot(before)!!.schemaVersion
            c.addColumn(table, TableColumnSpec.leaf("added", "int32", true))
            f.metadata("UPDATE ducklake_column SET initial_default='42' WHERE table_id=$table AND column_name='added'")
            val name = InlinedDataTables.tableName(table, version)
            f.metadata("CREATE TABLE `$name`(row_id BIGINT,begin_snapshot BIGINT,end_snapshot BIGINT,id INT)")
            f.metadata("INSERT INTO `$name` VALUES(0,$before,NULL,7)")
            f.metadata("INSERT INTO ducklake_inlined_data_tables VALUES($table,'$name',$version)")
            val snapshot = c.currentSnapshotId
            val columns = c.getTableColumns(table, snapshot)
            for (decoded in listOf(false, true)) assertThatThrownBy {
                if (decoded) c.readInlinedDataDecoded(table, version, snapshot, columns)
                else c.readInlinedData(table, version, snapshot, columns)
            }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("MySQL")
            assertThat(c.readInlinedDataDecoded(table, version, snapshot, columns.take(1))).containsExactly(listOf(7))
            assertThat(c.readInlinedDataDecoded(table, version, snapshot, emptyList())).containsExactly(emptyList())
            assertThat(c.readInlinedData(table, version, snapshot, emptyList())).containsExactly(emptyList())
            assertThat(c.getInlinedChangesBetweenDecoded(table, version, 0, snapshot, columns.map { it.columnId }).single().values)
                .containsExactly(7, null)
        } }
    }
}
