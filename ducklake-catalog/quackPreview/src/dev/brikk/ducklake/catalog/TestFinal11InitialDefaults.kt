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
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** R01: independent default values, not replacement goldens derived from native reads. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestFinal11InitialDefaults {
    private lateinit var server: PreviewQuackServer
    private lateinit var reader: JdbcDucklakeCatalog
    private val sequence = AtomicInteger()

    @BeforeAll
    fun start() {
        server = PreviewQuackServer("1.1", "1.1-defaults").also { it.start() }
        reader = server.catalog(enableFinal11Reads = true)
    }

    @AfterAll
    fun stop() {
        try { if (::reader.isInitialized) reader.close() }
        finally { if (::server.isInitialized) server.close() }
    }

    private fun name() = "initial_default_${sequence.incrementAndGet()}"
    private fun <T> read(action: () -> T): T = reader.readSession(Supplier { action() })

    private fun withInitialDefault(tableId: Long, column: String, value: String, restore: String?, action: () -> Unit) {
        fun set(default: String?) = server.connection().use { raw -> raw.createStatement().use { statement ->
            statement.execute("UPDATE ducklake_column SET initial_default=${default?.let(PreviewQuackServer::literal) ?: "NULL"} " +
                "WHERE table_id=$tableId AND column_name=${PreviewQuackServer.literal(column)}")
        } }
        try { set(value); action() }
        finally { set(restore) }
    }

    @Test
    fun qualifiedScalarDefaultsRetainTheirTypesBytesAndNulls() {
        val name = name()
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1); " +
            "ALTER TABLE lake.$name ADD COLUMN n DECIMAL(38,9) DEFAULT 12345678901234567890.123456789; " +
            "ALTER TABLE lake.$name ADD COLUMN u UBIGINT DEFAULT 18446744073709551615; " +
            "ALTER TABLE lake.$name ADD COLUMN d DATE DEFAULT DATE '2026-10-21'; " +
            "ALTER TABLE lake.$name ADD COLUMN ts TIMESTAMP_NS DEFAULT TIMESTAMP_NS '1969-12-31 23:59:59.123456789'; " +
            "ALTER TABLE lake.$name ADD COLUMN k UUID DEFAULT UUID '12345678-1234-5678-9abc-123456789abc'; " +
            "ALTER TABLE lake.$name ADD COLUMN tz TIMESTAMPTZ DEFAULT TIMESTAMPTZ '2026-10-21 12:00:00+02'; " +
            "ALTER TABLE lake.$name ADD COLUMN b BLOB DEFAULT '\\x00\\x5C\\xFF\\x27'::BLOB; " +
            "ALTER TABLE lake.$name ADD COLUMN v VARCHAR DEFAULT 'it''s \\\\ a literal'; " +
            "ALTER TABLE lake.$name ADD COLUMN flag BOOLEAN DEFAULT true; " +
            "INSERT INTO lake.$name VALUES (2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot).filter { it.hasLiveRows }.sortedBy { it.schemaVersion }
            val old = reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, snapshot, columns).single()
            assertThat(old.take(6)).containsExactly(1, BigDecimal("12345678901234567890.123456789"),
                BigInteger("18446744073709551615"), LocalDate.parse("2026-10-21"),
                LocalDateTime.parse("1969-12-31T23:59:59.123456789"), UUID.fromString("12345678-1234-5678-9abc-123456789abc"))
            assertThat((old[6] as OffsetDateTime).toInstant()).isEqualTo(Instant.parse("2026-10-21T10:00:00Z"))
            assertThat(old[7] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte(), 0x27)
            assertThat(old.drop(8)).containsExactly("it's \\\\ a literal", true)
            assertThat(reader.readInlinedRowIds(table.tableId, infos.first().schemaVersion, snapshot)).containsExactly(0L)
            val stored = reader.readInlinedDataDecoded(table.tableId, infos.last().schemaVersion, snapshot, columns).single()
            assertThat(stored).containsExactly(2, null, null, null, null, null, null, null, null, null)
            assertThat(reader.currentSnapshotId).isEqualTo(snapshot)
        }
        assertThat(server.native("SELECT i,hex(b),u,flag FROM lake.$name ORDER BY i"))
            .containsExactly(listOf("1", "005CFF27", "18446744073709551615", "true"), listOf("2", null, null, null))
    }

    @Test
    fun emptyStringsFalseZeroNanAndJsonAreRealDefaultsNotMissingValues() {
        val name = name()
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1); " +
            "ALTER TABLE lake.$name ADD COLUMN s VARCHAR DEFAULT ''; " +
            "ALTER TABLE lake.$name ADD COLUMN z INTEGER DEFAULT 0; " +
            "ALTER TABLE lake.$name ADD COLUMN flag BOOLEAN DEFAULT false; " +
            "ALTER TABLE lake.$name ADD COLUMN j JSON DEFAULT '{\"k\":null}'; " +
            "ALTER TABLE lake.$name ADD COLUMN f DOUBLE DEFAULT 'nan'::DOUBLE; " +
            "ALTER TABLE lake.$name ADD COLUMN b BLOB DEFAULT ''::BLOB; " +
            "INSERT INTO lake.$name VALUES (2,NULL,NULL,NULL,NULL,NULL,NULL)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot).filter { it.hasLiveRows }.sortedBy { it.schemaVersion }
            val first = reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, snapshot, columns).single()
            assertThat(first.take(5)).containsExactly(1, "", 0, false, "{\"k\":null}")
            assertThat(first[5] as Double).isNaN()
            assertThat(first[6] as ByteArray).isEmpty()
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.last().schemaVersion, snapshot, columns))
                .containsExactly(listOf(2, null, null, null, null, null, null))
        }
        assertThat(server.native("SELECT i,length(s),z,flag,CAST(j AS VARCHAR),octet_length(b) FROM lake.$name ORDER BY i"))
            .containsExactly(listOf("1", "0", "0", "false", "{\"k\":null}", "0"), listOf("2", null, null, null, null, null))
    }

    @Test
    fun nativelyMigrated10DefaultsKeepLiteralNullStringsStoredNullsAndAbsoluteIds() {
        PreviewQuackServer("1.1", "1.1-default-migration", migrateFrom10 = true).use { migrated ->
            migrated.start()
            migrated.catalog(enableFinal11Reads = true).use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "migrate_initial_default", snapshot)!!
                val columns = c.getTableColumns(table.tableId, snapshot)
                val infos = c.getInlinedDataInfos(table.tableId, snapshot)
                val rows = infos.flatMap { c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) }
                    .sortedBy { it[0] as Int }
                assertThat(rows).containsExactly(listOf(1, "NULL"), listOf(2, null))
                assertThat(infos.flatMap { c.readInlinedRowIds(table.tableId, it.schemaVersion, snapshot) }.sorted())
                    .containsExactly(0L, 1L)
                assertThat(c.currentSnapshotId).isEqualTo(migrated.seededSnapshotId)
                Unit
            }) }
            assertThat(migrated.native("SELECT i,v,rowid FROM lake.migrate_initial_default ORDER BY i"))
                .containsExactly(listOf("1", "NULL", "0"), listOf("2", null, "1"))
        }
    }

    @Test
    fun absentNestedColumnsWithoutDefaultsStayNullAndUnqualifiedContainerDefaultsAreRejected() {
        val name = name()
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1); " +
            "ALTER TABLE lake.$name ADD COLUMN s STRUCT(a INTEGER,v VARCHAR); " +
            "ALTER TABLE lake.$name ADD COLUMN xs INTEGER[]; " +
            "ALTER TABLE lake.$name ADD COLUMN m MAP(VARCHAR,INTEGER[]); " +
            "INSERT INTO lake.$name VALUES (2,NULL,NULL,NULL)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot).filter { it.hasLiveRows }.sortedBy { it.schemaVersion }
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, snapshot, columns))
                .containsExactly(listOf(1, null, null, null))
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.last().schemaVersion, snapshot, columns))
                .containsExactly(listOf(2, null, null, null))
        }
        val table = read { reader.getTable("main", name, reader.currentSnapshotId)!! }
        withInitialDefault(table.tableId, "xs", "[1,NULL,3]", null) {
            assertThatThrownBy { read {
                val snapshot = reader.currentSnapshotId
                val columns = reader.getTableColumns(table.tableId, snapshot)
                val info = reader.getInlinedDataInfos(table.tableId, snapshot).first()
                reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns)
            } }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("initial default for nested")
        }
    }

    @Test
    fun changingTheInsertDefaultDoesNotChangeOldRowsOrTheirCdcIdentity() {
        val name = name()
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1)")
        val inserted = read { reader.currentSnapshotId }
        server.native("ALTER TABLE lake.$name ADD COLUMN c INTEGER DEFAULT 42; " +
            "ALTER TABLE lake.$name ALTER COLUMN c SET DEFAULT 77; " +
            "INSERT INTO lake.$name(i) VALUES (2); INSERT INTO lake.$name VALUES (3,NULL)")
        val beforeDelete = read { reader.currentSnapshotId }
        server.native("DELETE FROM lake.$name WHERE i=1")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot)
            val rows = infos.flatMap { reader.readInlinedDataDecoded(table.tableId, it.schemaVersion, beforeDelete, columns) }
                .sortedBy { it[0] as Int }
            assertThat(rows).containsExactly(listOf(1, 42), listOf(2, 77), listOf(3, null))
            val old = infos.first()
            assertThat(reader.readInlinedDataDecoded(table.tableId, old.schemaVersion, snapshot, columns)).isEmpty()
            val deletion = reader.getInlinedChangesBetweenDecoded(table.tableId, old.schemaVersion, snapshot, snapshot,
                columns.map { it.columnId }).single()
            assertThat(deletion.values).containsExactly(1, 42)
            assertThat(deletion.rowId).isEqualTo(0L)
            assertThat(deletion.beginSnapshot).isEqualTo(inserted)
            assertThat(deletion.endSnapshot).isEqualTo(snapshot)
            assertThat(reader.getInlinedChangesBetween(table.tableId, old.schemaVersion, snapshot, snapshot,
                columns.map { it.columnId }).single().values).containsExactly(1, 42)
        }
        assertThat(server.native("SELECT i,c,rowid FROM lake.$name ORDER BY i"))
            .containsExactly(listOf("2", "77", "1"), listOf("3", null, "2"))
    }

    @Test
    fun defaultTextIsOneLiteralAndCannotExecuteSqlOrOverrideFreshTargetMetadata() {
        val name = name()
        val literal = "42'); DROP TABLE ducklake_snapshot; --"
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1); " +
            "ALTER TABLE lake.$name ADD COLUMN c VARCHAR DEFAULT ${PreviewQuackServer.literal(literal)}")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).first()
            val forged = columns.map { if (it.columnName == "c") it.copy(initialDefault = "wrong") else it }
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, forged))
                .containsExactly(listOf(1, literal))
            assertThat(reader.getSnapshot(snapshot)).isNotNull()
            assertThat(reader.currentSnapshotId).isEqualTo(snapshot)
        }
        assertThat(server.native("SELECT c FROM lake.$name")).containsExactly(listOf(literal))
    }

    @Test
    fun malformedNumericDefaultsFailRatherThanExecutingSqlOrBecomingNull() {
        val name = name()
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1); " +
            "ALTER TABLE lake.$name ADD COLUMN c INTEGER DEFAULT 42")
        val actual = read { reader.getTable("main", name, reader.currentSnapshotId)!! }
        for (literal in listOf("NULL", "2147483648", "0); DROP TABLE ducklake_snapshot; --")) {
            withInitialDefault(actual.tableId, "c", literal, "42") {
                assertThatThrownBy { read {
                    val snapshot = reader.currentSnapshotId
                    val columns = reader.getTableColumns(actual.tableId, snapshot)
                    val info = reader.getInlinedDataInfos(actual.tableId, snapshot).first()
                    reader.readInlinedDataDecoded(actual.tableId, info.schemaVersion, snapshot, columns)
                } }.isInstanceOf(DataAccessException::class.java)
                read { assertThat(reader.getSnapshot(reader.currentSnapshotId)).isNotNull() }
            }
        }
    }
}
