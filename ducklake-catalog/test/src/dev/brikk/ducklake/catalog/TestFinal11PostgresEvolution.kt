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
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.function.Supplier

class TestFinal11PostgresEvolution {
    private fun <T> read(f: Final11PostgresFixture, action: (JdbcDucklakeCatalog) -> T): T =
        f.reader().use { c -> c.readSession(Supplier { action(c) }) }

    @Test
    fun stableIdNestedAddDropRenameWideningAndNullParentsReachRawDecodedHistoryAndCdc() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER,s STRUCT(a TINYINT,b BLOB,j JSON)); " +
                "INSERT INTO lake.t VALUES(0,{'a':7,'b':from_hex('005CFF'),'j':'{\"x\":null}'::JSON}),(1,NULL)")
            val inserted = f.snapshot()
            f.native("ALTER TABLE lake.t RENAME s.a TO renamed; ALTER TABLE lake.t ALTER s TYPE STRUCT(renamed BIGINT,b BLOB,j JSON)")
            f.native("ALTER TABLE lake.t ADD COLUMN s.added BLOB DEFAULT '\\x00N'::BLOB")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val columns = c.getTableColumns(id, snapshot)
                val schema = f.inline("t").second
                val rows = c.readInlinedDataDecoded(id, schema, snapshot, columns)
                val value = rows[0][1] as Map<*, *>
                assertThat(value["renamed"]).isEqualTo(7L)
                assertThat(value["b"] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                assertThat(value["j"]).isEqualTo("{\"x\":null}")
                assertThat(value["added"] as ByteArray).containsExactly(0, 0x4e)
                assertThat(rows[1][1]).isNull()
                val raw = c.readInlinedData(id, schema, snapshot, columns)[0][1] as String
                assertRawNative(raw)
                val old = c.readInlinedDataDecoded(id, schema, inserted, c.getTableColumns(id, inserted))[0][1] as Map<*, *>
                assertThat(old["a"]).isEqualTo(7)
                assertThat(old.containsKey("added")).isFalse()
            }
            f.native("DELETE FROM lake.t WHERE id=0")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val schema = f.inline("t").second
                val columns = c.getTableColumns(id, snapshot).map { it.columnId }
                val change = c.getInlinedChangesBetweenDecoded(id, schema, snapshot, snapshot, columns).single()
                assertThat(change.rowId).isZero()
                assertThat(change.beginSnapshot).isEqualTo(inserted)
                assertThat(change.endSnapshot).isEqualTo(snapshot)
                assertThat((change.values[1] as Map<*, *>)["renamed"]).isEqualTo(7L)
                assertRawNative(c.getInlinedChangesBetween(id, schema, snapshot, snapshot, columns).single().values[1] as String)
            }
        }
    }

    private fun assertRawNative(raw: String) {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.prepareStatement("SELECT s.renamed,hex(s.b),s.j::VARCHAR,hex(s.added) " +
                "FROM (SELECT CAST(? AS STRUCT(renamed BIGINT,b BLOB,j JSON,added BLOB)) s)").use { s ->
                s.setString(1, raw)
                s.executeQuery().use { r ->
                    check(r.next())
                    assertThat((1..4).map { r.getString(it) }).containsExactly("7", "005CFF", "{\"x\":null}", "004E")
                }
            }
        }
    }

    @Test
    fun listAndMapEvolutionPreservesNullEmptyContainersAndReplacedSameNamedGrandchildren() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(xs STRUCT(a TINYINT)[],m MAP(INTEGER,STRUCT(v INTEGER))); " +
                "INSERT INTO lake.t VALUES([{'a':1},NULL],MAP([1],[{'v':7}])),([],MAP([],[])),(NULL,NULL)")
            f.native("ALTER TABLE lake.t ALTER xs TYPE STRUCT(a BIGINT,added VARCHAR)[]; " +
                "ALTER TABLE lake.t ALTER m TYPE MAP(BIGINT,STRUCT(v BIGINT,added INTEGER))")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val schema = f.inline("t").second
                val columns = c.getTableColumns(id, snapshot)
                val rows = c.readInlinedDataDecoded(id, schema, snapshot, columns)
                assertThat(rows).containsExactly(
                    listOf(listOf(mapOf("a" to 1L, "added" to null), null), mapOf(1L to mapOf("v" to 7L, "added" to null))),
                    listOf(emptyList<Any?>(), emptyMap<Any?, Any?>()), listOf(null, null))
                val raw = c.readInlinedData(id, schema, snapshot, columns)
                DriverManager.getConnection("jdbc:duckdb:").use { native -> native.prepareStatement(
                    "SELECT list_transform(CAST(? AS STRUCT(a BIGINT,added VARCHAR)[]),lambda x:x.a)::VARCHAR, " +
                        "(CAST(? AS MAP(BIGINT,STRUCT(v BIGINT,added INTEGER))))[1].v").use { s ->
                    s.setString(1, raw[0][0] as String); s.setString(2, raw[0][1] as String)
                    s.executeQuery().use { r -> check(r.next()); assertThat(listOf(r.getString(1), r.getString(2))).containsExactly("[1, NULL]", "7") }
                } }
                val all = c.getAllColumnsWithParentage(id, snapshot)
                columns.forEachIndexed { index, column ->
                    val node = all.single { it.columnId == column.columnId }
                    assertThat(Final11PostgresValues.decode(raw[0][index], InlinedValues.typeTree(node, all))).isEqualTo(rows[0][index])
                }
            }
        }
    }

    @Test
    fun dateAndTimestampPrecisionPromotionsAreZoneIndependentAndRawTextIsTargetShaped() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(d DATE,s TIMESTAMP_S,ms TIMESTAMP_MS); " +
                "INSERT INTO lake.t VALUES(DATE '0001-01-01 (BC)',TIMESTAMP_S '1969-12-31 23:59:59',TIMESTAMP_MS '2020-01-01 00:00:00.123'); " +
                "ALTER TABLE lake.t ALTER d TYPE TIMESTAMP; ALTER TABLE lake.t ALTER s TYPE TIMESTAMP_NS; ALTER TABLE lake.t ALTER ms TYPE TIMESTAMP_NS")
            assertThat(f.nativeRows("SELECT d::VARCHAR,s::VARCHAR,ms::VARCHAR FROM lake.t").single()).containsExactly(
                "0001-01-01 (BC) 00:00:00", "1969-12-31 23:59:59", "2020-01-01 00:00:00.123")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val schema = f.inline("t").second
                val columns = c.getTableColumns(id, snapshot)
                assertThat(c.readInlinedDataDecoded(id, schema, snapshot, columns).single()).containsExactly(
                    LocalDateTime.of(0, 1, 1, 0, 0), LocalDateTime.of(1969, 12, 31, 23, 59, 59), LocalDateTime.of(2020, 1, 1, 0, 0, 0, 123000000))
                assertThat(c.readInlinedData(id, schema, snapshot, columns).single()).containsExactly(
                    "0001-01-01 (BC) 00:00:00", "1969-12-31 23:59:59", "2020-01-01 00:00:00.123")
            }
        }
    }

    @Test
    fun approximateNumericPromotionsMatchNativeRatherThanExecutingPostgresCasts() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER,l BIGINT,d DECIMAL(20,1)); INSERT INTO lake.t VALUES(16777217,9007199254740993,9007199254740993.0); " +
                "ALTER TABLE lake.t ALTER i TYPE FLOAT; ALTER TABLE lake.t ALTER l TYPE DOUBLE; ALTER TABLE lake.t ALTER d TYPE DOUBLE")
            assertThat(f.nativeRows("SELECT i::VARCHAR,l::VARCHAR,d::VARCHAR FROM lake.t").single()).containsExactly(
                "16777216.0", "9007199254740992.0", "9007199254740992.0")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val schema = f.inline("t").second
                assertThat(c.readInlinedDataDecoded(id, schema, snapshot, c.getTableColumns(id, snapshot)).single())
                    .containsExactly(16777216.0f, 9007199254740992.0, 9007199254740992.0)
            }
        }
    }

    @Test
    fun replacedGrandchildUsesItsOwnInitialDefaultAndNeverAnOldSameNamedValue() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(s STRUCT(nested STRUCT(v INTEGER,k INTEGER))); INSERT INTO lake.t VALUES({'nested':{'v':7,'k':8}})")
            f.native("ALTER TABLE lake.t DROP COLUMN s.nested.v")
            f.native("ALTER TABLE lake.t ADD COLUMN s.nested.v INTEGER DEFAULT 42")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val schema = f.inline("t").second
                val value = c.readInlinedDataDecoded(id, schema, snapshot, c.getTableColumns(id, snapshot)).single().single()
                assertThat(value).isEqualTo(mapOf("nested" to mapOf("k" to 8, "v" to 42)))
            }
        }
    }

    @Test
    fun missingUnqualifiedDefaultsAndCorruptDroppedLeavesNeverBecomeSuccessfulProjections() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(s STRUCT(keep INTEGER,old TINYINT)); INSERT INTO lake.t VALUES({'keep':1,'old':7})")
            val (id, schema) = f.inline("t")
            f.native("ALTER TABLE lake.t DROP COLUMN s.old")
            f.metadata("UPDATE ${InlinedDataTables.tableName(id, schema)} SET s='{''keep'':1,''old'':128}'")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                assertThatThrownBy { c.readInlinedDataDecoded(id, schema, snapshot, c.getTableColumns(id, snapshot)) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("out of range for int8")
            }
        }
    }

    @Test
    fun nativeScalarMapKeysPreserveTemporalBinaryIntervalAndFloatingDomains() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(d MAP(DATE,INTEGER),b MAP(BLOB,INTEGER),i MAP(INTERVAL,INTEGER),f MAP(DOUBLE,INTEGER)); " +
                "INSERT INTO lake.t VALUES(MAP([DATE '2000-01-01'],[1]),MAP([from_hex('00FF')],[2])," +
                "MAP([INTERVAL '1 month'],[3]),MAP([0.1::DOUBLE,'NaN'::DOUBLE],[4,5]))")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val schema = f.inline("t").second
                val row = c.readInlinedDataDecoded(id, schema, snapshot, c.getTableColumns(id, snapshot)).single()
                assertThat(row[0]).isEqualTo(mapOf(java.time.LocalDate.of(2000, 1, 1) to 1))
                val binary = (row[1] as Map<*, *>).entries.single()
                assertThat(binary.key as ByteArray).containsExactly(0, 0xff.toByte())
                assertThat(binary.value).isEqualTo(2)
                assertThat(row[2]).isEqualTo(mapOf(DucklakeInterval(1, 0, 0) to 3))
                assertThat(row[3]).isEqualTo(mapOf(0.1 to 4, Double.NaN to 5))
            }
        }
    }

    @Test
    fun nativeRejectedBooleanFloatPromotionNeverBecomesAnInventedReaderCast() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.b(v BOOLEAN); INSERT INTO lake.b VALUES(TRUE)")
            assertThatThrownBy { f.native("ALTER TABLE lake.b ALTER v TYPE FLOAT") }
                .isInstanceOf(java.sql.SQLException::class.java).hasMessageContaining("only widening")
        }
    }

    @Test
    fun migratedNestedStorageAndConcurrentSchemaPublicationStayInsideOwnedSnapshot() {
        Final11PostgresFixture(migrate = true).use { f ->
            f.native("CREATE TABLE lake.t(s STRUCT(v TINYINT)); INSERT INTO lake.t VALUES({'v':7}),(NULL)")
            f.native("ALTER TABLE lake.t RENAME s.v TO renamed")
            f.native("ALTER TABLE lake.t ALTER s TYPE STRUCT(renamed BIGINT)")
            val (id, schema) = f.inline("t")
            f.reader().use { c ->
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val columns = c.getTableColumns(id, snapshot)
                    assertThat(c.readInlinedDataDecoded(id, schema, snapshot, columns))
                        .containsExactly(listOf(mapOf("renamed" to 7L)), listOf(null))
                    f.native("ALTER TABLE lake.t ADD COLUMN s.added INTEGER DEFAULT 42")
                    assertThat(c.currentSnapshotId).isEqualTo(snapshot)
                    assertThat(c.readInlinedDataDecoded(id, schema, snapshot, columns))
                        .containsExactly(listOf(mapOf("renamed" to 7L)), listOf(null))
                })
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    assertThat(c.readInlinedDataDecoded(id, schema, snapshot, c.getTableColumns(id, snapshot)))
                        .containsExactly(listOf(mapOf<String, Any?>("renamed" to 7L, "added" to 42)), listOf(null))
                })
            }
        }
    }
}
