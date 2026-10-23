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
import java.math.BigDecimal
import java.util.function.Supplier

class TestFinal11PostgresNumericEvolution {
    private fun <T> read(f: Final11PostgresFixture, action: (JdbcDucklakeCatalog) -> T): T =
        f.reader().use { c -> c.readSession(Supplier { action(c) }) }

    private fun rows(c: JdbcDucklakeCatalog, name: String, snapshot: Long, decoded: Boolean = true): List<List<Any?>> {
        val table = c.getTable("main", name, snapshot)!!
        val columns = c.getTableColumns(table.tableId, snapshot)
        return c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
            if (decoded) c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
            else c.readInlinedData(table.tableId, it.schemaVersion, snapshot, columns)
        }
    }

    @Test
    fun decimalPrecisionScaleWideningPreservesStoredNullHistoryAndCdcIdentity() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER,d DECIMAL(9,2)); INSERT INTO lake.t VALUES(0,-9999999.99),(1,0.01),(2,NULL)")
            val inserted = f.snapshot()
            f.native("ALTER TABLE lake.t ALTER d TYPE DECIMAL(18,4); ALTER TABLE lake.t RENAME d TO widened; " +
                "INSERT INTO lake.t VALUES(3,123456789.1234)")
            assertThat(f.nativeRows("SELECT id,widened::VARCHAR FROM lake.t ORDER BY id")).containsExactly(
                listOf("0", "-9999999.9900"), listOf("1", "0.0100"), listOf("2", null), listOf("3", "123456789.1234"))
            f.native("DELETE FROM lake.t WHERE id=0")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                assertThat(c.readBackendProfile().inlineProjection).isEqualTo("postgres-stable-id-evolution")
                val expected = listOf(listOf(1, BigDecimal("0.0100")), listOf(2, null), listOf(3, BigDecimal("123456789.1234")))
                assertThat(rows(c, "t", snapshot).sortedBy { it[0] as Int }).containsExactlyElementsOf(expected)
                assertThat(rows(c, "t", snapshot, decoded = false).sortedBy { it[0] as Int }).containsExactlyElementsOf(expected)
                assertThat(rows(c, "t", inserted)).containsExactly(
                    listOf(0, BigDecimal("-9999999.99")), listOf(1, BigDecimal("0.01")), listOf(2, null))
                val table = c.getTable("main", "t", snapshot)!!
                val ids = c.getTableColumns(table.tableId, snapshot).map { it.columnId }
                val changes = c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                    c.getInlinedChangesBetweenDecoded(table.tableId, it.schemaVersion, snapshot, snapshot, ids)
                }
                assertThat(changes).hasSize(1)
                assertThat(changes.single().rowId).isZero()
                assertThat(changes.single().beginSnapshot).isEqualTo(inserted)
                assertThat(changes.single().endSnapshot).isEqualTo(snapshot)
                assertThat(changes.single().values).containsExactly(0, BigDecimal("-9999999.9900"))
                val raw = c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                    c.getInlinedChangesBetween(table.tableId, it.schemaVersion, snapshot, snapshot, ids)
                }.single()
                assertThat(raw).isEqualTo(changes.single())
            }
        }
    }

    @Test
    fun exactSignedAndUnsignedIntegerToDecimalConversionsMatchNativeExtremes() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(b BOOLEAN,i BIGINT,u UBIGINT); " +
                "INSERT INTO lake.t VALUES(TRUE,-9223372036854775808,18446744073709551615),(FALSE,9223372036854775807,0),(NULL,NULL,NULL); " +
                "ALTER TABLE lake.t ALTER i TYPE DECIMAL(23,4); " +
                "ALTER TABLE lake.t ALTER u TYPE DECIMAL(24,4)")
            val expected = listOf(
                listOf(true, BigDecimal("-9223372036854775808.0000"), BigDecimal("18446744073709551615.0000")),
                listOf(false, BigDecimal("9223372036854775807.0000"), BigDecimal("0.0000")), listOf(null, null, null))
            assertThat(f.nativeRows("SELECT b::VARCHAR,i::VARCHAR,u::VARCHAR FROM lake.t ORDER BY i NULLS LAST"))
                .containsExactly(listOf("true", "-9223372036854775808.0000", "18446744073709551615.0000"),
                    listOf("false", "9223372036854775807.0000", "0.0000"), listOf(null, null, null))
            read(f) { c ->
                assertThat(rows(c, "t", c.currentSnapshotId)).containsExactlyElementsOf(expected)
                assertThat(rows(c, "t", c.currentSnapshotId, decoded = false)).containsExactlyElementsOf(expected)
            }
        }
    }

    @Test
    fun floatToDoubleWidensStoredBinaryFloatNotItsShortDecimalDisplay() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER,v FLOAT); INSERT INTO lake.t VALUES(0,0.1),(1,-0.0::FLOAT)," +
                "(2,'NaN'::FLOAT),(3,'Infinity'::FLOAT),(4,'-Infinity'::FLOAT),(5,NULL); ALTER TABLE lake.t ALTER v TYPE DOUBLE")
            assertThat(f.nativeRows("SELECT v::VARCHAR FROM lake.t ORDER BY id")).containsExactly(
                listOf("0.10000000149011612"), listOf("-0.0"), listOf("nan"), listOf("inf"), listOf("-inf"), listOf(null))
            read(f) { c ->
                for (decoded in listOf(true, false)) {
                    val values = rows(c, "t", c.currentSnapshotId, decoded).sortedBy { it[0] as Int }.map { it[1] }
                    assertThat(values[0]).isEqualTo(0.10000000149011612)
                    assertThat((values[1] as Double).toRawBits()).isEqualTo((-0.0).toRawBits())
                    assertThat(values[2] as Double).isNaN()
                    assertThat(values[3]).isEqualTo(Double.POSITIVE_INFINITY)
                    assertThat(values[4]).isEqualTo(Double.NEGATIVE_INFINITY)
                    assertThat(values[5]).isNull()
                }
            }
        }
    }

    @Test
    fun missingFloatDefaultUsesNativeInitialLiteralButStoredFloatUsesBinaryValue() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER); INSERT INTO lake.t VALUES(0); " +
                "ALTER TABLE lake.t ADD COLUMN added FLOAT DEFAULT 0.1; INSERT INTO lake.t VALUES(1,0.1),(2,NULL); " +
                "ALTER TABLE lake.t ALTER added TYPE DOUBLE")
            assertThat(f.nativeRows("SELECT id,added::VARCHAR FROM lake.t ORDER BY id")).containsExactly(
                listOf("0", "0.1"), listOf("1", "0.10000000149011612"), listOf("2", null))
            read(f) { c ->
                for (decoded in listOf(true, false)) assertThat(rows(c, "t", c.currentSnapshotId, decoded).sortedBy { it[0] as Int })
                    .containsExactly(listOf(0, 0.1), listOf(1, 0.10000000149011612), listOf(2, null))
            }
        }
    }

    @Test
    fun decimalSourceDimensionsAreCheckedBeforeWiderTargetCanHideCorruption() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(v DECIMAL(9,2)); INSERT INTO lake.t VALUES(1.23); ALTER TABLE lake.t ALTER v TYPE DECIMAL(18,4)")
            val (id, schema) = f.inline("t")
            val physical = InlinedDataTables.tableName(id, schema)
            f.metadata("ALTER TABLE $physical ALTER v TYPE NUMERIC(18,4); UPDATE $physical SET v=1.2345")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val columns = c.getTableColumns(id, snapshot)
                assertThatThrownBy { c.readInlinedData(id, schema, snapshot, columns) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("precision/scale mismatch")
                assertThatThrownBy { c.readInlinedDataDecoded(id, schema, snapshot, columns) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("precision/scale mismatch")
            }
        }
    }

    @Test
    fun sourceIntegerRangeIsCheckedBeforeDecimalWideningAndRawReadsCannotBypassIt() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(v TINYINT); INSERT INTO lake.t VALUES(1); ALTER TABLE lake.t ALTER v TYPE DECIMAL(5,2)")
            val (id, schema) = f.inline("t")
            f.metadata("UPDATE ${InlinedDataTables.tableName(id, schema)} SET v=128")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val columns = c.getTableColumns(id, snapshot)
                assertThatThrownBy { c.readInlinedData(id, schema, snapshot, columns) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("out of range for int8")
                assertThatThrownBy { c.readInlinedDataDecoded(id, schema, snapshot, columns) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("out of range for int8")
            }
        }
    }

    @Test
    fun nativeBooleanDecimalAndDecimalNarrowingErrorsArePreservedWithNumericPromotions() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.b(v BOOLEAN); INSERT INTO lake.b VALUES(TRUE); " +
                "CREATE TABLE lake.d(v DECIMAL(9,2)); INSERT INTO lake.d VALUES(1.23); " +
                "CREATE TABLE lake.t(v INTEGER); INSERT INTO lake.t VALUES(16777217); ALTER TABLE lake.t ALTER v TYPE FLOAT")
            assertThatThrownBy { f.native("ALTER TABLE lake.b ALTER v TYPE DECIMAL(5,4)") }
                .isInstanceOf(java.sql.SQLException::class.java).hasMessageContaining("only widening")
            assertThatThrownBy { f.native("ALTER TABLE lake.d ALTER v TYPE DECIMAL(9,1)") }
                .isInstanceOf(java.sql.SQLException::class.java).hasMessageContaining("only widening")
            read(f) { c ->
                assertThat(rows(c, "t", c.currentSnapshotId)).containsExactly(listOf(16777216.0f))
            }
        }
    }

    @Test
    fun floatSubnormalAndFiniteExtremesWidenWithoutOverflowOrDisplayRoundTrips() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER,v FLOAT); INSERT INTO lake.t VALUES(0,'1.401298464324817e-45'::FLOAT)," +
                "(1,'3.4028234663852886e38'::FLOAT),(2,'-3.4028234663852886e38'::FLOAT); ALTER TABLE lake.t ALTER v TYPE DOUBLE")
            assertThat(f.nativeRows("SELECT v::VARCHAR FROM lake.t ORDER BY id")).containsExactly(
                listOf("1.401298464324817e-45"), listOf("3.4028234663852886e+38"), listOf("-3.4028234663852886e+38"))
            read(f) { c ->
                assertThat(rows(c, "t", c.currentSnapshotId)).containsExactly(listOf(0, 1.401298464324817e-45),
                    listOf(1, 3.4028234663852886e38), listOf(2, -3.4028234663852886e38))
            }
        }
    }

    @Test
    fun nativeMigrationAndConcurrentPublicationRetainNumericProjectionContracts() {
        Final11PostgresFixture(migrate = true).use { f ->
            f.native("CREATE TABLE lake.t(d DECIMAL(9,2),f FLOAT); INSERT INTO lake.t VALUES(1.25,0.1)")
            f.native("ALTER TABLE lake.t ALTER d TYPE DECIMAL(18,4); ALTER TABLE lake.t ALTER f TYPE DOUBLE")
            f.reader().use { c ->
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val expected = listOf(BigDecimal("1.2500"), 0.10000000149011612)
                    assertThat(rows(c, "t", snapshot)).containsExactly(expected)
                    f.native("INSERT INTO lake.t VALUES(2.5001,0.1)")
                    assertThat(c.currentSnapshotId).isEqualTo(snapshot)
                    assertThat(rows(c, "t", snapshot)).containsExactly(expected)
                })
                c.readSession(Supplier {
                    assertThat(rows(c, "t", c.currentSnapshotId)).containsExactly(
                        listOf(BigDecimal("1.2500"), 0.10000000149011612), listOf(BigDecimal("2.5001"), 0.1))
                })
            }
        }
    }
}
