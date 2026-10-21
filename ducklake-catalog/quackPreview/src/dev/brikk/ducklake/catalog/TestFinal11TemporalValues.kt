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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** Lossless read qualification on signed 2.0/1.1; decoded normal Java types are retained. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestFinal11TemporalValues {
    private lateinit var server: PreviewQuackServer
    private lateinit var reader: JdbcDucklakeCatalog
    private val sequence = AtomicInteger()

    @BeforeAll
    fun start() {
        server = PreviewQuackServer("1.1", "1.1-temporal-values").also { it.start() }
        reader = server.catalog(enableFinal11Reads = true)
    }

    @AfterAll
    fun close() {
        try { if (::reader.isInitialized) reader.close() }
        finally { if (::server.isInitialized) server.close() }
    }

    private fun name() = "temporal_${sequence.incrementAndGet()}"
    private fun <T> read(action: () -> T): T = reader.readSession(Supplier { action() })

    @Test
    fun finiteTemporalsKeepNanosecondsSecondOffsetsAndIndependentIntervalComponents() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,t TIME,ns TIME_NS,z TIMETZ,iv INTERVAL,tz TIMESTAMPTZ_NS); " +
            "INSERT INTO lake.$name VALUES (1,TIME '12:34:56.123456',TIME_NS '23:59:59.999999999'," +
            "TIMETZ '12:34:56.123456+05:30:15',INTERVAL '1 year 2 months 3 days 100:00:00.123456'," +
            "TIMESTAMPTZ_NS '2026-10-21 12:00:00.123456789+05:30'),(2,NULL,NULL,NULL,NULL,NULL)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
            val rows = reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns)
            assertThat(rows).containsExactly(listOf(1, LocalTime.of(12, 34, 56, 123456000), LocalTime.of(23, 59, 59, 999999999),
                OffsetTime.of(12, 34, 56, 123456000, ZoneOffset.ofHoursMinutesSeconds(5, 30, 15)),
                DucklakeInterval(14, 3, 360000123456L), OffsetDateTime.parse("2026-10-21T06:30:00.123456789Z")),
                listOf(2, null, null, null, null, null))
            assertThat(reader.readInlinedData(table.tableId, info.schemaVersion, snapshot, columns)).containsExactly(
                listOf(1, "12:34:56.123456", "23:59:59.999999999", "12:34:56.123456+05:30:15",
                    "1 year 2 months 3 days 100:00:00.123456", "2026-10-21 06:30:00.123456789+00"), listOf(2, null, null, null, null, null))
            assertThat(reader.currentSnapshotId).isEqualTo(snapshot)
        }
    }

    @Test
    fun nestedTemporalTextPreservesBlobBytesJsonLiteralNullsAndNullContainers() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,s STRUCT(t TIME_NS,z TIMETZ,iv INTERVAL,bl BLOB,v VARCHAR,j JSON)," +
            "xs STRUCT(t TIME_NS,iv INTERVAL)[],mp MAP(VARCHAR,TIMETZ)); " +
            "INSERT INTO lake.$name VALUES (1,{'t':TIME_NS '12:00:00.123456789','z':TIMETZ '01:02:03.123456-00:30:15'," +
            "'iv':INTERVAL '1000 hours','bl':from_hex('005CFF27'),'v':'NULL','j':'{\"k\":[1,null],\"v\":\"NULL\"}'::JSON}," +
            "[{'t':TIME_NS '23:59:59.999999999','iv':INTERVAL '100 hours'},NULL],MAP(['x'],[TIMETZ '01:02:03-00:30:15']))," +
            "(2,NULL,NULL,NULL),(3,{'t':NULL,'z':NULL,'iv':NULL,'bl':NULL,'v':NULL,'j':NULL},[],MAP([],[]))")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
            val rows = reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns)
            val structure = rows[0][1] as Map<*, *>
            assertThat(structure["t"]).isEqualTo(LocalTime.of(12, 0, 0, 123456789))
            assertThat(structure["z"]).isEqualTo(OffsetTime.of(1, 2, 3, 123456000, ZoneOffset.ofHoursMinutesSeconds(0, -30, -15)))
            assertThat(structure["iv"]).isEqualTo(DucklakeInterval(0, 0, 3600000000000L))
            assertThat(structure["bl"] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte(), 0x27)
            assertThat(structure["v"]).isEqualTo("NULL")
            assertThat(structure["j"]).isEqualTo("{\"k\":[1,null],\"v\":\"NULL\"}")
            assertThat(rows[0][2]).isEqualTo(listOf(linkedMapOf("t" to LocalTime.of(23, 59, 59, 999999999),
                "iv" to DucklakeInterval(0, 0, 360000000000L)), null))
            assertThat(rows[0][3]).isEqualTo(mapOf("x" to OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofHoursMinutesSeconds(0, -30, -15))))
            assertThat(rows[1]).containsExactly(2, null, null, null)
            assertThat((rows[2][1] as Map<*, *>).values).containsOnlyNulls()
            assertThat(rows[2][2]).isEqualTo(emptyList<Any?>())
            assertThat(rows[2][3]).isEqualTo(emptyMap<Any?, Any?>())
        }
    }

    @Test
    fun temporalDefaultsRenameAndCdcKeepNanosecondsValuesAndIdentity() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER); INSERT INTO lake.$name VALUES(1)")
        val inserted = read { reader.currentSnapshotId }
        server.native("ALTER TABLE lake.$name ADD COLUMN t TIME_NS DEFAULT TIME_NS '12:34:56.123456789'; " +
            "ALTER TABLE lake.$name ADD COLUMN z TIMETZ DEFAULT TIMETZ '01:02:03+05:30:15'; " +
            "ALTER TABLE lake.$name ADD COLUMN iv INTERVAL DEFAULT INTERVAL '100 hours'; " +
            "ALTER TABLE lake.$name RENAME t TO renamed; " +
            "INSERT INTO lake.$name VALUES(2,NULL,NULL,NULL); DELETE FROM lake.$name WHERE id=1")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot)
            val change = reader.getInlinedChangesBetweenDecoded(table.tableId, infos.first().schemaVersion, snapshot, snapshot,
                columns.map { it.columnId }).single()
            assertThat(change.values).containsExactly(1, LocalTime.of(12, 34, 56, 123456789),
                OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofHoursMinutesSeconds(5, 30, 15)), DucklakeInterval(0, 0, 360000000000L))
            assertThat(change.rowId).isEqualTo(0L)
            assertThat(change.beginSnapshot).isEqualTo(inserted)
            assertThat(change.endSnapshot).isEqualTo(snapshot)
            assertThat(reader.getInlinedChangesBetween(table.tableId, infos.first().schemaVersion, snapshot, snapshot,
                columns.map { it.columnId }).single().values).containsExactly(1, "12:34:56.123456789", "01:02:03+05:30:15", "100:00:00")
            assertThat(infos.flatMap { reader.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) })
                .containsExactly(listOf(2, null, null, null))
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, inserted,
                reader.getTableColumns(table.tableId, inserted))).containsExactly(listOf(1))
        }
    }

    @Test
    fun unsupportedNativeEdgesNeverBypassValidationThroughRawReads() {
        for ((type, literal) in listOf("TIME" to "TIME '24:00:00'", "TIME_NS" to "TIME_NS '24:00:00'",
            "TIMETZ" to "TIMETZ '24:00:00+00'", "DATE" to "DATE 'infinity'", "DATE" to "DATE '-infinity'",
            "TIMESTAMP" to "TIMESTAMP 'infinity'", "TIMESTAMP_NS" to "TIMESTAMP_NS '-infinity'",
            "TIMESTAMPTZ" to "TIMESTAMPTZ 'infinity'", "TIMESTAMPTZ_NS" to "TIMESTAMPTZ_NS '-infinity'")) {
            rejectEdge(type, literal)
        }
    }

    private fun rejectEdge(type: String, literal: String) {
        val name = name()
        server.native("CREATE TABLE lake.$name(v $type); INSERT INTO lake.$name VALUES($literal)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
            assertThatThrownBy { reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns) }
                .`as`("decoded $literal").isInstanceOf(DucklakeInvalidOperationException::class.java)
            assertThatThrownBy { reader.readInlinedData(table.tableId, info.schemaVersion, snapshot, columns) }
                .`as`("raw $literal").isInstanceOf(DucklakeInvalidOperationException::class.java)
            assertThat(reader.countInlinedRows(table.tableId, info.schemaVersion, snapshot)).isEqualTo(1L)
            assertThat(reader.getInlinedChangesBetweenDecoded(table.tableId, info.schemaVersion, snapshot, snapshot, emptyList()).single().rowId)
                .isEqualTo(0L)
        }
    }

    @Test
    fun nativeCalendarTextSupportsBceLargeDatesAndNanosecondZoneInstants() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,d DATE,ts TIMESTAMP,tz TIMESTAMPTZ,ns TIMESTAMPTZ_NS); " +
            "INSERT INTO lake.$name VALUES (1,make_date(0,1,1),make_timestamp(0,1,1,12,34,56)," +
            "TIMESTAMPTZ '0001-01-01 (BC) 12:34:56+00',TIMESTAMPTZ_NS '2026-10-21 12:00:00.123456789+05:30')," +
            "(2,DATE '1000000-01-01',TIMESTAMP '12000-01-01 00:00:00.123456'," +
            "TIMESTAMPTZ '12000-01-01 00:00:00+00',TIMESTAMPTZ_NS '1969-12-31 23:59:59.999999999+00')")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns)).containsExactly(
                listOf(1, LocalDate.of(0, 1, 1), LocalDateTime.of(0, 1, 1, 12, 34, 56),
                    OffsetDateTime.of(0, 1, 1, 12, 34, 56, 0, ZoneOffset.UTC), OffsetDateTime.parse("2026-10-21T06:30:00.123456789Z")),
                listOf(2, LocalDate.of(1000000, 1, 1), LocalDateTime.of(12000, 1, 1, 0, 0, 0, 123456000),
                    OffsetDateTime.of(12000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), OffsetDateTime.parse("1969-12-31T23:59:59.999999999Z")))
        }
    }

    @Test
    fun validNativeIntervalStorageKeepsSignedAndExtremeComponentsWithoutDriverObjects() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,iv INTERVAL); INSERT INTO lake.$name VALUES(1,INTERVAL '1 day')")
        val info = read {
            val table = reader.getTable("main", name, reader.currentSnapshotId)!!
            reader.getInlinedDataInfos(table.tableId, reader.currentSnapshotId).single()
        }
        val cases = listOf(
            "to_microseconds(9223372036854775807)" to DucklakeInterval(0, 0, Long.MAX_VALUE),
            "to_microseconds(-9223372036854775807)-INTERVAL '1 microsecond'" to DucklakeInterval(0, 0, Long.MIN_VALUE),
            "INTERVAL '1 month'-INTERVAL '2 days'+INTERVAL '100 hours'" to DucklakeInterval(1, -2, 360000000000L),
            "to_months(2147483647)" to DucklakeInterval(Int.MAX_VALUE.toLong(), 0, 0),
            "to_days(-2147483648)" to DucklakeInterval(0, Int.MIN_VALUE.toLong(), 0),
        )
        try { cases.forEach { (expression, expected) -> checkStoredInterval(info, expression, expected) } }
        finally {
            server.connection().use { raw -> raw.createStatement().use { it.execute("UPDATE ${info.tableName} SET iv=INTERVAL '1 day'") } }
        }
    }

    private fun checkStoredInterval(info: DucklakeInlinedDataInfo, expression: String, expected: DucklakeInterval) {
        // Owned physical fixture only: current native Parquet writer rejects negative/large intervals.
        // Readers must still interpret valid interval_t storage left by older/other writers losslessly.
        server.connection().use { raw -> raw.createStatement().use { it.execute("UPDATE ${info.tableName} SET iv=$expression") } }
        read {
            val snapshot = reader.currentSnapshotId
            val columns = reader.getTableColumns(info.tableId, snapshot)
            assertThat(reader.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns))
                .containsExactly(listOf(1, expected))
        }
    }

    @Test
    fun knownQuackIntervalRecordDecodesByComponentsRatherThanItsDebugString() {
        server.connection().use { raw -> raw.createStatement().use { statement ->
            statement.executeQuery("SELECT INTERVAL '1 month -2 days 100:00:00.123456'").use { result ->
                assertThat(result.next()).isTrue()
                assertThat(InlinedValues.decode(result.getObject(1), InlinedTypeNode("interval", "v")))
                    .isEqualTo(DucklakeInterval(1, -2, 360000123456L))
            }
        } }
    }

    @Test
    fun nullTypedScalarsAndContainersDoNotInventZeroOrEmptyValuesDuringPromotion() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,n \"NULL\",xs \"NULL\"[],s STRUCT(x \"NULL\"),mp MAP(VARCHAR,\"NULL\")); " +
            "INSERT INTO lake.$name VALUES(1,NULL,[NULL],{'x':NULL},MAP(['k'],[NULL])),(2,NULL,NULL,NULL,NULL); " +
            "ALTER TABLE lake.$name ALTER n TYPE INTEGER; INSERT INTO lake.$name VALUES(3,40,[],{'x':NULL},MAP([],[]))")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val rows = reader.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                reader.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
            }.sortedBy { it[0] as Int }
            assertThat(rows).containsExactly(listOf(1, null, listOf(null), mapOf("x" to null), mapOf("k" to null)),
                listOf(2, null, null, null, null), listOf(3, 40, emptyList<Any?>(), mapOf("x" to null), emptyMap<Any?, Any?>()))
        }
    }

    @Test
    fun midnightAndMaximumFiniteClocksPreserveNativeOffsetSecondBoundaries() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,t TIME,ns TIME_NS,z TIMETZ); " +
            "INSERT INTO lake.$name VALUES(1,TIME '00:00:00',TIME_NS '00:00:00',TIMETZ '00:00:00-15:59:59')," +
            "(2,TIME '23:59:59.999999',TIME_NS '23:59:59.999999999',TIMETZ '23:59:59.999999+15:59:59')")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns)).containsExactly(
                listOf(1, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, OffsetTime.of(0, 0, 0, 0, ZoneOffset.ofHoursMinutesSeconds(-15, -59, -59))),
                listOf(2, LocalTime.of(23, 59, 59, 999999000), LocalTime.MAX,
                    OffsetTime.of(23, 59, 59, 999999000, ZoneOffset.ofHoursMinutesSeconds(15, 59, 59))))
        }
    }

    @Test
    fun variantPhysicalStorageNeverMasqueradesAsDecodedValuesEvenInsideContainers() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,v VARIANT,s STRUCT(v VARIANT)); " +
            "INSERT INTO lake.$name VALUES(1,42::VARIANT,{'v':42::VARIANT}),(2,NULL,{'v':NULL})")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
            for (column in columns.drop(1)) {
                assertThatThrownBy { reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, listOf(column)) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("variant")
                assertThatThrownBy { reader.readInlinedData(table.tableId, info.schemaVersion, snapshot, listOf(column)) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("variant")
            }
            assertThat(reader.countInlinedRows(table.tableId, info.schemaVersion, snapshot)).isEqualTo(2L)
            assertThat(reader.getInlinedChangesBetweenDecoded(table.tableId, info.schemaVersion, snapshot, snapshot, emptyList()).map { it.rowId })
                .containsExactly(0L, 1L)
        }
    }
}
