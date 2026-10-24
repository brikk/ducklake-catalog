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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.function.Supplier

/** Real native old files; independently expected default values, not Parquet scan certification. */
internal class TestFinal11FileInitialDefaults {
    @Nested inner class Local : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    @Nested inner class NativeQuack : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) }
    @Nested inner class JavaQuack : Contract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) }
    @Nested inner class Postgres : Contract { override fun fixture() = ReadAcceptanceFixture.Postgres() }

    interface Contract {
        fun fixture(): ReadAcceptanceFixture

        @Test
        fun missingFileDefaultsAreDecodedFromFreshTargetMetadataWithoutExecutingText() {
            fixture().use { f ->
                val literal = "42'); DROP TABLE ducklake_snapshot; --"
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1); " +
                    "ALTER TABLE lake.t ADD COLUMN n INTEGER DEFAULT 42; ALTER TABLE lake.t ADD COLUMN s VARCHAR DEFAULT 'NULL'; " +
                    "ALTER TABLE lake.t ADD COLUMN d DATE DEFAULT DATE '2026-10-24'; " +
                    "ALTER TABLE lake.t ADD COLUMN ts TIMESTAMP_NS DEFAULT TIMESTAMP_NS '1969-12-31 23:59:59.123456789'; " +
                    "ALTER TABLE lake.t ADD COLUMN b BLOB DEFAULT '\\x00\\x5C\\xFF'::BLOB; " +
                    "ALTER TABLE lake.t ADD COLUMN z DECIMAL(20,3) DEFAULT 12345678901234567.125; " +
                    "ALTER TABLE lake.t ADD COLUMN q VARCHAR DEFAULT ${q(literal)}; ALTER TABLE lake.t ADD COLUMN p STRUCT(a INTEGER); " +
                    "INSERT INTO lake.t VALUES(2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val columns = c.getAllColumnsWithParentage(table.tableId, snapshot).associateBy { it.columnName }
                    fun default(name: String): Any? = c.getMissingFileColumnDefaultDecoded(table.tableId, columns.getValue(name).columnId, snapshot)
                    assertThat(default("n")).isEqualTo(42)
                    assertThat(default("s")).isEqualTo("NULL")
                    assertThat(default("d")).isEqualTo(LocalDate.parse("2026-10-24"))
                    assertThat(default("ts")).isEqualTo(LocalDateTime.parse("1969-12-31T23:59:59.123456789"))
                    assertThat(default("b") as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                    assertThat(default("z")).isEqualTo(BigDecimal("12345678901234567.125"))
                    assertThat(default("q")).isEqualTo(literal)
                    assertThat(default("p")).isNull()
                    assertThat(default("i")).isNull()
                    assertThat(c.getSnapshot(snapshot)).isNotNull()
                    assertThat(c.getDataFiles(table.tableId, snapshot)).hasSize(2)
                    assertThatThrownBy { c.getMissingFileColumnDefaultDecoded(table.tableId, Long.MAX_VALUE, snapshot) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java)
                    assertThatThrownBy { c.getMissingFileColumnDefaultDecoded(table.tableId, columns.getValue("n").columnId, snapshot + 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java)
                    Unit
                }) }
                assertThat(f.native("SELECT i,n,s,hex(b) FROM lake.t ORDER BY i"))
                    .containsExactly(listOf("1", "42", "NULL", "005CFF"), listOf("2", null, null, null))
            }
        }

        @Test
        fun originalDefaultsSurviveRenameWideningAndLaterInsertDefaults() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1); " +
                    "ALTER TABLE lake.t ADD COLUMN n INTEGER DEFAULT 42")
                val before = f.reader().use { c -> c.readSession(Supplier { c.currentSnapshotId }) }
                f.native("ALTER TABLE lake.t RENAME COLUMN n TO renamed; ALTER TABLE lake.t ALTER COLUMN renamed TYPE BIGINT; " +
                    "ALTER TABLE lake.t ALTER COLUMN renamed SET DEFAULT 77; INSERT INTO lake.t(i) VALUES(2)")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val id = c.getTable("main", "t", snapshot)!!.tableId
                    val column = c.getTableColumns(id, snapshot).single { it.columnName == "renamed" }
                    assertThat(c.getMissingFileColumnDefaultDecoded(id, column.columnId, snapshot)).isEqualTo(42L)
                    assertThat(c.getMissingFileColumnDefaultDecoded(id, column.columnId, before)).isEqualTo(42)
                    Unit
                }) }
                assertThat(f.native("SELECT i,renamed FROM lake.t ORDER BY i")).containsExactly(listOf("1", "42"), listOf("2", "77"))
            }
        }

        @Test
        fun nonNullUnknownHistoryRejectsWhileNativeLiteralNullExpressionStaysQualified() {
            fixture().use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',1); CREATE TABLE lake.t(i INTEGER); " +
                    "INSERT INTO lake.t VALUES(1),(2); INSERT INTO lake.t VALUES(3); " +
                    "ALTER TABLE lake.t ADD COLUMN n INTEGER DEFAULT 42; ALTER TABLE lake.t ADD COLUMN s VARCHAR DEFAULT 'NULL'")
                assertThat(f.metadataRows("SELECT initial_default,default_value_type FROM ducklake_column " +
                    "WHERE column_name='s' ORDER BY begin_snapshot LIMIT 1")).containsExactly(listOf("NULL", "expression"))
                val id = f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val columns = c.getTableColumns(table.tableId, snapshot)
                    assertThat(c.getMissingFileColumnDefaultDecoded(table.tableId, columns.single { it.columnName == "s" }.columnId, snapshot))
                        .isEqualTo("NULL")
                    table.tableId
                }) }
                f.metadata("UPDATE ducklake_column SET default_value_type='unknown' WHERE table_id=$id AND column_name='n'")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val columns = c.getTableColumns(id, snapshot)
                    val old = c.getInlinedDataInfos(id, snapshot).first { it.hasLiveRows }
                    val ids = columns.map { it.columnId }
                    val n = columns.single { it.columnName == "n" }.columnId
                    for (read in listOf<() -> Any?>(
                        { c.getMissingFileColumnDefaultDecoded(id, n, snapshot) },
                        { c.readInlinedData(id, old.schemaVersion, snapshot, columns) },
                        { c.readInlinedDataDecoded(id, old.schemaVersion, snapshot, columns) },
                        { c.getInlinedChangesBetweenDecoded(id, old.schemaVersion, 0, snapshot, ids) },
                    )) assertThatThrownBy { read() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                        .hasMessageContaining("unknown initial-default history kind")
                    assertThat(c.getSnapshot(snapshot)).isNotNull()
                }) }
                f.metadata("UPDATE ducklake_column SET default_value_type='literal' WHERE table_id=$id AND column_name='n'")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val columns = c.getTableColumns(id, snapshot)
                    val old = c.getInlinedDataInfos(id, snapshot).first { it.hasLiveRows }
                    assertThat(c.readInlinedDataDecoded(id, old.schemaVersion, snapshot, columns)).containsExactly(listOf(3, 42, "NULL"))
                    assertThat(c.getMissingFileColumnDefaultDecoded(id, columns.single { it.columnName == "n" }.columnId, snapshot)).isEqualTo(42)
                }) }
            }
        }

        @Test
        fun malformedExpressionContainerAndOversizedDefaultsRejectAndReleaseTheSession() {
            fixture().use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1); ALTER TABLE lake.t ADD COLUMN n INTEGER DEFAULT 42; " +
                    "ALTER TABLE lake.t ADD COLUMN p STRUCT(a INTEGER)")
                val identity = f.reader().use { c -> c.readSession(Supplier {
                    val id = c.getTable("main", "t", c.currentSnapshotId)!!.tableId
                    id to c.getTableColumns(id, c.currentSnapshotId).associate { it.columnName to it.columnId }
                }) }
                fun reject(name: String, value: String?, kind: String) {
                    f.metadata("UPDATE ducklake_column SET initial_default=${value?.let(::q) ?: "NULL"},default_value_type=${q(kind)} " +
                        "WHERE table_id=${identity.first} AND column_name=${q(name)}")
                    f.reader().use { c ->
                        assertThatThrownBy { c.readSession(Supplier {
                            c.getMissingFileColumnDefaultDecoded(identity.first, identity.second.getValue(name), c.currentSnapshotId)
                        }) }.isInstanceOf(DucklakeException::class.java)
                        c.readSession(Supplier { assertThat(c.getSnapshot(c.currentSnapshotId)).isNotNull() })
                    }
                }
                reject("n", "2147483648", "literal")
                reject("n", "0); DROP TABLE ducklake_snapshot; --", "literal")
                reject("n", null, "expression")
                f.metadata("UPDATE ducklake_column SET initial_default='42',default_value_type='literal' " +
                    "WHERE table_id=${identity.first} AND column_name='n'")
                reject("p", "{'a':1}", "literal")
                reject("p", "x".repeat(16385), "literal")
            }
        }

        private fun q(value: String): String = "'${value.replace("'", "''")}'"
    }

    @Test
    fun fileDefaultProjectionDoesNotExpandTheExistingMysqlEvolutionScope() {
        ReadAcceptanceFixture.MySql().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1)")
            f.reader().use { c ->
                assertThatThrownBy { c.getMissingFileColumnDefaultDecoded(0, 0, 0) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    assertThatThrownBy { c.getMissingFileColumnDefaultDecoded(table.tableId, 0, snapshot) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("MySQL")
                })
            }
        }
    }
}
