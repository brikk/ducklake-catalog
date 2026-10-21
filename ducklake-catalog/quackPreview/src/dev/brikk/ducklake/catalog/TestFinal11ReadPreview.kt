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
import java.nio.file.Files
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** Real final-1.1 fixtures on the pinned signed native peer, not a version-string relabel. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestFinal11ReadPreview {
    private lateinit var server: PreviewQuackServer
    private lateinit var reader: JdbcDucklakeCatalog
    private val sequence = AtomicInteger()

    @BeforeAll
    fun start() {
        server = PreviewQuackServer("1.1", "1.1-reader").also { it.start() }
        reader = server.catalog(enableFinal11Reads = true)
    }

    @AfterAll
    fun stop() {
        try { if (::reader.isInitialized) reader.close() }
        finally { if (::server.isInitialized) server.close() }
    }

    private fun name(prefix: String) = "${prefix}_${sequence.incrementAndGet()}"
    private fun <T> read(action: () -> T): T = reader.readSession(Supplier { action() })

    private fun disguiseMetadataPrefix(isolated: PreviewQuackServer, tableName: String) {
        isolated.connection().use { raw -> raw.createStatement().use { st ->
            for (name in listOf("row_id", "begin_snapshot", "end_snapshot")) {
                st.execute("ALTER TABLE $tableName RENAME COLUMN _ducklake_$name TO $name")
            }
            st.execute("ALTER TABLE $tableName ADD COLUMN _ducklake_row_id BIGINT DEFAULT 999")
            st.execute("ALTER TABLE $tableName ADD COLUMN _ducklake_begin_snapshot BIGINT DEFAULT 0")
            st.execute("ALTER TABLE $tableName ADD COLUMN _ducklake_end_snapshot BIGINT")
        } }
    }

    @Test
    fun fileCountsRemainAvailableButLegacyBoundsAndRangePruningStayConservative() {
        val name = name("stats")
        server.native("CREATE TABLE lake.$name(i INTEGER); " +
            "CALL lake.set_option('data_inlining_row_limit',0,table_name=>'$name'); " +
            "INSERT INTO lake.$name VALUES (1); INSERT INTO lake.$name VALUES (1000)")
        val snapshot = read { reader.currentSnapshotId }
        val table = read { reader.getTable("main", name, snapshot)!! }
        val columnId = read { reader.getTableColumns(table.tableId, snapshot).single().columnId }
        val files = read { reader.getDataFiles(table.tableId, snapshot) }
        assertThat(files).hasSize(2)
        for (exactness in listOf("TRUE", "FALSE", "NULL")) {
            server.connection().use { c -> c.createStatement().use { st ->
                st.execute("UPDATE ducklake_file_column_stats SET min_is_exact=$exactness,max_is_exact=$exactness " +
                    "WHERE table_id=${table.tableId}")
            } }
            reader.readSession(Supplier {
                val stats = reader.getColumnStats(table.tableId, snapshot).single()
                assertThat(stats.totalValueCount).isEqualTo(2L)
                assertThat(stats.totalNullCount).isEqualTo(0L)
                assertThat(stats.minValue).isNull()
                assertThat(stats.maxValue).isNull()
                assertThat(reader.findDataFileIdsInRange(table.tableId, snapshot, ColumnRangePredicate(columnId, "1000", "1000")))
                    .containsExactlyInAnyOrderElementsOf(files.map { it.dataFileId })
            })
        }
        assertThat(server.native("SELECT i FROM lake.$name WHERE i=1000")).containsExactly(listOf("1000"))
        read {
            assertThat(reader.currentSnapshotId).isEqualTo(snapshot)
            assertThat(reader.getDataFiles(table.tableId, snapshot)).isEqualTo(files)
        }
    }

    @Test
    fun multipleInlineSchemasRenameNullsHistoryAndCdcKeepTheirOriginalValuesAndIds() {
        val name = name("versions")
        server.native("CREATE TABLE lake.$name(i INTEGER,v VARCHAR); INSERT INTO lake.$name VALUES (1,'old'),(2,NULL)")
        val inserted = read { reader.currentSnapshotId }
        server.native("ALTER TABLE lake.$name ADD COLUMN x INTEGER; INSERT INTO lake.$name VALUES (3,'new',7); " +
            "ALTER TABLE lake.$name RENAME v TO text; CREATE TABLE lake.${name}_unrelated(j INTEGER)")
        val renamed = read { reader.currentSnapshotId }
        server.native("DELETE FROM lake.$name WHERE i=2")
        val deleted = read { reader.currentSnapshotId }
        reader.readSession(Supplier {
            val table = reader.getTable("main", name, deleted)!!
            val columns = reader.getTableColumns(table.tableId, deleted)
            assertThat(columns.map { it.columnName }).containsExactly("i", "text", "x")
            val infos = reader.getInlinedDataInfos(table.tableId, deleted)
            assertThat(infos.size).isGreaterThanOrEqualTo(2)
            val live = infos.flatMap { reader.readInlinedDataDecoded(table.tableId, it.schemaVersion, deleted, columns) }.sortedBy { it[0] as Int }
            assertThat(live).containsExactly(listOf(1, "old", null), listOf(3, "new", 7))
            val beforeDelete = infos.flatMap { reader.readInlinedDataDecoded(table.tableId, it.schemaVersion, renamed, columns) }
                .sortedBy { it[0] as Int }
            assertThat(beforeDelete).containsExactly(listOf(1, "old", null), listOf(2, null, null), listOf(3, "new", 7))
            val changes = infos.flatMap {
                reader.getInlinedChangesBetweenDecoded(table.tableId, it.schemaVersion, deleted, deleted, columns.map { col -> col.columnId })
            }
            assertThat(changes).hasSize(1)
            assertThat(changes.single().values).isEqualTo(listOf(2, null, null))
            assertThat(changes.single().rowId).isEqualTo(1L)
            assertThat(changes.single().beginSnapshot).isEqualTo(inserted)
            assertThat(changes.single().endSnapshot).isEqualTo(deleted)
        })
        read { assertThat(reader.currentSnapshotId).isEqualTo(deleted) }
        assertThat(server.native("SELECT i,text,x,rowid FROM lake.$name ORDER BY i"))
            .containsExactly(listOf("1", "old", null, "0"), listOf("3", "new", "7", "2"))
    }

    @Test
    fun nativeFlushAndLaterInlineRowsExposeFilesDeletesPartitionsAndLiveCountsWithoutWrites() {
        val name = name("flush")
        server.native("CREATE TABLE lake.$name(i INTEGER,p INTEGER); " +
            "ALTER TABLE lake.$name SET PARTITIONED BY (p); INSERT INTO lake.$name VALUES (1,0),(2,0),(3,1)")
        val inserted = read { reader.currentSnapshotId }
        server.native("DELETE FROM lake.$name WHERE i=2; CALL ducklake_flush_inlined_data('lake',table_name=>'$name'); " +
            "INSERT INTO lake.$name VALUES (4,1)")
        val snapshot = read { reader.currentSnapshotId }
        reader.readSession(Supplier {
            val table = reader.getTable("main", name, snapshot)!!
            val files = reader.getDataFiles(table.tableId, snapshot)
            assertThat(files).hasSize(2)
            assertThat(files.any { it.deleteFilePath != null }).isTrue()
            assertThat(reader.getDataFilesAddedBetween(table.tableId, inserted, inserted)).hasSize(2)
            assertThat(reader.getFilePartitionValues(table.tableId, snapshot).keys).containsExactlyInAnyOrderElementsOf(files.map { it.dataFileId })
            assertThat(reader.getPartitionSpecs(table.tableId, snapshot)).hasSize(1)
            assertThat(reader.getLiveRowCount(table.tableId, snapshot)).isEqualTo(3L)
            assertThat(reader.getDeletionsBetween(table.tableId, inserted + 1, snapshot).any { it.currentDeletePath != null }).isTrue()
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val inline = reader.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                reader.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
            }
            assertThat(inline).containsExactly(listOf(4, 1))
        })
        assertThat(server.native("SELECT i,rowid FROM lake.$name ORDER BY i"))
            .containsExactly(listOf("1", "0"), listOf("3", "2"), listOf("4", "3"))
        assertThat(server.native("SELECT i,rowid FROM lake.$name AT (VERSION=>$inserted) ORDER BY i"))
            .containsExactly(listOf("1", "0"), listOf("2", "1"), listOf("3", "2"))
        read { assertThat(reader.currentSnapshotId).isEqualTo(snapshot) }
    }

    @Test
    fun final11ReadSessionPinsNativeChangesAndRefreshesAfterRelease() {
        val name = name("pin")
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1)")
        val before = read { reader.currentSnapshotId }
        val table = read { reader.getTable("main", name, before)!! }
        val columns = read { reader.getTableColumns(table.tableId, before) }
        val info = read { reader.getInlinedDataInfos(table.tableId, before).single() }
        reader.readSession(Supplier {
            assertThat(reader.currentSnapshotId).isEqualTo(before)
            server.native("INSERT INTO lake.$name VALUES (2)")
            assertThat(reader.currentSnapshotId).isEqualTo(before)
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, before, columns)).containsExactly(listOf(1))
        })
        read {
            assertThat(reader.currentSnapshotId).isEqualTo(before + 1)
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, reader.currentSnapshotId, columns))
                .containsExactly(listOf(1), listOf(2))
        }
    }

    @Test
    fun aPinnedReaderSurvivesNativeInlineFlushWithoutMissingOrDuplicatingRows() {
        val name = name("flush_pin")
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1)")
        val before = read { reader.currentSnapshotId }
        val table = read { reader.getTable("main", name, before)!! }
        val columns = read { reader.getTableColumns(table.tableId, before) }
        reader.readSession(Supplier {
            val info = reader.getInlinedDataInfos(table.tableId, before).single()
            assertThat(reader.getDataFiles(table.tableId, before)).isEmpty()
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, before, columns)).containsExactly(listOf(1))
            server.native("CALL ducklake_flush_inlined_data('lake',table_name=>'$name'); INSERT INTO lake.$name VALUES (2)")
            assertThat(reader.getDataFiles(table.tableId, before)).isEmpty()
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, before, columns)).containsExactly(listOf(1))
            assertThat(reader.getLiveRowCount(table.tableId, before)).isEqualTo(1L)
        })
        read {
            val now = reader.currentSnapshotId
            assertThat(reader.getDataFiles(table.tableId, now)).hasSize(1)
            val inline = reader.getInlinedDataInfos(table.tableId, now).flatMap {
                reader.readInlinedDataDecoded(table.tableId, it.schemaVersion, now, columns)
            }
            assertThat(inline).containsExactly(listOf(2))
            assertThat(reader.getLiveRowCount(table.tableId, now)).isEqualTo(2L)
        }
        assertThat(server.native("SELECT i,rowid FROM lake.$name ORDER BY i")).containsExactly(listOf("1", "0"), listOf("2", "1"))
    }

    @Test
    fun nestedSchemaHistoryIsRejectedAndValidationIsNotCachedAcrossReadSessions() {
        PreviewQuackServer("1.1", "1.1-nested").use { isolated ->
            isolated.start()
            isolated.catalog(enableFinal11Reads = true).use { c ->
                val table = c.readSession(Supplier { c.getTable("main", "scalar_seed", c.currentSnapshotId)!! })
                c.readSession(Supplier {
                    val before = c.currentSnapshotId
                    assertThat(c.listSchemas(before).map { it.schemaName }).containsExactly("main")
                    isolated.native("CREATE SCHEMA lake.parent; CREATE SCHEMA lake.parent.child")
                    assertThat(c.listSchemas(before).map { it.schemaName }).containsExactly("main")
                })
                assertThatThrownBy { c.readSession(Supplier { c.listSchemas(Long.MAX_VALUE) }) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("parent-aware")
                assertThatThrownBy { c.readSession(Supplier { c.getDataFiles(table.tableId, Long.MAX_VALUE) }) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThatThrownBy { c.readSession(Supplier { c.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1) }) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                isolated.native("DROP SCHEMA lake.parent.child")
                assertThatThrownBy { c.readSession(Supplier { c.listSchemas(Long.MAX_VALUE) }) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
            }
        }
    }

    @Test
    fun aFinalVersionStringWithoutTheRequiredSchemaColumnIsNotEnough() {
        PreviewQuackServer("1.1", "1.1-malformed").use { isolated ->
            isolated.start()
            isolated.connection().use { c -> c.createStatement().use { it.execute("ALTER TABLE ducklake_schema DROP COLUMN parent_schema_id") } }
            isolated.catalog(enableFinal11Reads = true).use { c ->
                assertThatThrownBy { c.readSession(Supplier { c.listSchemas(1000) }) }
                    .isInstanceOf(DataAccessException::class.java).hasMessageContaining("parent_schema_id")
            }
        }
    }

    @Test
    fun legacyLookingUserNamesAndLiteralBlobBytesDoNotBecomeSystemMetadata() {
        val name = name("names")
        server.native("CREATE TABLE lake.$name(row_id BIGINT,begin_snapshot VARCHAR,end_snapshot INTEGER,b BLOB); " +
            "INSERT INTO lake.$name VALUES (999,'literal',777,from_hex('5C783431')),(1000,'NULL',NULL,NULL)")
        val snapshot = read { reader.currentSnapshotId }
        val table = read { reader.getTable("main", name, snapshot)!! }
        val columns = read { reader.getTableColumns(table.tableId, snapshot) }
        val info = read { reader.getInlinedDataInfos(table.tableId, snapshot).single() }
        reader.readSession(Supplier {
            val rows = reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns)
            assertThat(rows).hasSize(2)
            assertThat(rows[0].take(3)).containsExactly(999L, "literal", 777)
            assertThat(rows[0][3] as ByteArray).containsExactly(0x5c, 0x78, 0x34, 0x31)
            assertThat(rows[1]).containsExactly(1000L, "NULL", null, null)
            assertThat(reader.readInlinedRowIds(table.tableId, info.schemaVersion, snapshot)).containsExactly(0L, 1L)
            assertThat(reader.readInlinedBeginSnapshots(table.tableId, info.schemaVersion, snapshot)).containsExactly(snapshot, snapshot)
        })
        read { assertThat(reader.currentSnapshotId).isEqualTo(snapshot) }
    }

    @Test
    fun aMissingPhysicalSystemColumnMustNotBecomeAnEmptyReadOutsideASession() {
        PreviewQuackServer("1.1", "1.1-broken-inline").use { isolated ->
            isolated.start()
            isolated.catalog(enableFinal11Reads = true).use { c ->
                val snapshot = c.readSession(Supplier { c.currentSnapshotId })
                val table = c.readSession(Supplier { c.getTable("main", "scalar_seed", snapshot)!! })
                val info = c.readSession(Supplier { c.getInlinedDataInfos(table.tableId, snapshot).single() })
                isolated.connection().use { raw -> raw.createStatement().use { st ->
                    st.execute("ALTER TABLE ${info.tableName} RENAME COLUMN _ducklake_row_id TO _ducklake_row_id_hidden")
                } }
                assertThatThrownBy { c.readInlinedRowIds(table.tableId, info.schemaVersion, snapshot) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
                assertThatThrownBy { c.readSession(Supplier { c.readInlinedRowIds(table.tableId, info.schemaVersion, snapshot) }) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("physical inline metadata layout")
            }
        }
    }

    @Test
    fun matchingUserColumnNamesCannotDisguiseAnUnmigratedPhysicalMetadataPrefix() {
        PreviewQuackServer("1.1", "1.1-prefix-collision").use { isolated ->
            isolated.start()
            isolated.catalog(enableFinal11Reads = true).use { c ->
                val info = c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "scalar_seed", snapshot)!!
                    c.getInlinedDataInfos(table.tableId, snapshot).single()
                })
                disguiseMetadataPrefix(isolated, info.tableName)
                assertThatThrownBy { c.readSession(Supplier { c.readInlinedRowIds(info.tableId, info.schemaVersion, c.currentSnapshotId) }) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("physical inline metadata layout")
            }
        }
    }

    @Test
    fun reorderedPhysicalStructFieldsCannotSilentlySwapLogicalValues() {
        val name = name("physical_struct")
        server.native("CREATE TABLE lake.$name(s STRUCT(a INTEGER,b INTEGER)); INSERT INTO lake.$name VALUES ({'a':10,'b':20})")
        val snapshot = read { reader.currentSnapshotId }
        val table = read { reader.getTable("main", name, snapshot)!! }
        val columns = read { reader.getTableColumns(table.tableId, snapshot) }
        val info = read { reader.getInlinedDataInfos(table.tableId, snapshot).single() }
        // Reproduces the legacy physical ordering identified upstream without changing the
        // logical catalog tree. Positional JDBC STRUCT decoding must not turn a=10 into a=20.
        server.connection().use { c -> c.createStatement().use { st ->
            st.execute("ALTER TABLE ${info.tableName} ALTER COLUMN s TYPE STRUCT(b INTEGER,a INTEGER)")
        } }
        server.connection().use { c ->
            assertThat(PreviewQuackServer.jdbcRows(c, "SELECT s.a,s.b FROM ${info.tableName}")).containsExactly(listOf("10", "20"))
        }
        read {
            assertThat(reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns))
                .containsExactly(listOf(linkedMapOf("a" to 10, "b" to 20)))
            val raw = reader.readInlinedData(table.tableId, info.schemaVersion, snapshot, columns).single().single() as java.sql.Struct
            assertThat(raw.attributes.toList()).containsExactly(10, 20)
            assertThat(reader.readInlinedRowIds(table.tableId, info.schemaVersion, snapshot)).containsExactly(0L)
        }
    }

    @Test
    fun commonScalarValuesPreserveUnsignedDecimalUuidAndNanosecondPrecision() {
        val name = name("scalars")
        server.native("CREATE TABLE lake.$name(i INTEGER,a DECIMAL(38,9),u UBIGINT,d DATE,ts TIMESTAMP_NS,tz TIMESTAMPTZ,k UUID,b BOOLEAN,f DOUBLE); " +
            "INSERT INTO lake.$name VALUES (1,CAST('12345678901234567890.123456789' AS DECIMAL(38,9)),18446744073709551615," +
            "DATE '2026-10-20',TIMESTAMP_NS '1969-12-31 23:59:59.123456789',TIMESTAMPTZ '2026-10-20 12:00:00+02'," +
            "UUID '12345678-1234-5678-9abc-123456789abc',true,'nan'::DOUBLE),(2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
            val rows = reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns)
            assertThat(rows).hasSize(2)
            assertThat(rows[0][0]).isEqualTo(1)
            assertThat(rows[0][1]).isEqualTo(BigDecimal("12345678901234567890.123456789"))
            assertThat(rows[0][2]).isEqualTo(BigInteger("18446744073709551615"))
            assertThat(rows[0][3]).isEqualTo(LocalDate.parse("2026-10-20"))
            assertThat(rows[0][4]).isEqualTo(LocalDateTime.parse("1969-12-31T23:59:59.123456789"))
            assertThat((rows[0][5] as OffsetDateTime).toInstant()).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"))
            assertThat(rows[0][6]).isEqualTo(UUID.fromString("12345678-1234-5678-9abc-123456789abc"))
            assertThat(rows[0][7]).isEqualTo(true)
            assertThat(rows[0][8] as Double).isNaN()
            assertThat(rows[1].drop(1)).containsOnlyNulls()
        }
    }

    @Test
    fun absentInitialDefaultsAreProjectedWithoutReplacingStoredNulls() {
        val name = name("defaults")
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1); " +
            "ALTER TABLE lake.$name ADD COLUMN c INTEGER DEFAULT 42; INSERT INTO lake.$name VALUES (2,NULL)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot).sortedBy { it.schemaVersion }
            assertThat(infos).hasSize(2)
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, snapshot, columns))
                .containsExactly(listOf(1, 42))
            assertThat(reader.readInlinedData(table.tableId, infos.first().schemaVersion, snapshot, columns))
                .containsExactly(listOf(1, 42))
            assertThat(reader.getInlinedChangesBetweenDecoded(table.tableId, infos.first().schemaVersion, 0, snapshot,
                columns.map { it.columnId }).single().values).containsExactly(1, 42)
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.last().schemaVersion, snapshot, columns))
                .containsExactly(listOf(2, null)) // A real stored NULL must not become the default.
            assertThatThrownBy { reader.readInlinedDataDecoded(table.tableId, infos.last().schemaVersion, snapshot, emptyList()) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("nonempty projection")
        }
        assertThat(server.native("SELECT i,c FROM lake.$name ORDER BY i")).containsExactly(listOf("1", "42"), listOf("2", null))
    }

    @Test
    fun literalNullInitialDefaultIsNotConfusedWithSqlNull() {
        val name = name("literal_default")
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1); " +
            "ALTER TABLE lake.$name ADD COLUMN c VARCHAR DEFAULT 'NULL'; INSERT INTO lake.$name VALUES (2,NULL)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot).sortedBy { it.schemaVersion }
            assertThat(columns.single { it.columnName == "c" }.initialDefault).isEqualTo("NULL") // Plain literal value, not an SQL sentinel.
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, snapshot, columns))
                .containsExactly(listOf(1, "NULL"))
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.last().schemaVersion, snapshot, columns))
                .containsExactly(listOf(2, null))
        }
        assertThat(server.native("SELECT i,c FROM lake.$name ORDER BY i")).containsExactly(listOf("1", "NULL"), listOf("2", null))
    }

    @Test
    fun inlineTypeEvolutionUsesTheTargetSnapshotAndPreservesHistoricalTypes() {
        val name = name("evolution")
        server.native("CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name VALUES (1)")
        val inserted = read { reader.currentSnapshotId }
        server.native("ALTER TABLE lake.$name ALTER COLUMN i TYPE BIGINT; INSERT INTO lake.$name VALUES (2)")
        read {
            val snapshot = reader.currentSnapshotId
            val table = reader.getTable("main", name, snapshot)!!
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val infos = reader.getInlinedDataInfos(table.tableId, snapshot).sortedBy { it.schemaVersion }
            assertThat(infos).hasSize(2)
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, snapshot, columns))
                .containsExactly(listOf(1L))
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.last().schemaVersion, snapshot, columns)).containsExactly(listOf(2L))
            assertThat(reader.readInlinedDataDecoded(table.tableId, infos.first().schemaVersion, inserted,
                reader.getTableColumns(table.tableId, inserted))).containsExactly(listOf(1))
            assertThat(reader.getInlinedChangesBetweenDecoded(table.tableId, infos.first().schemaVersion, inserted, snapshot,
                columns.map { it.columnId }).single().values).containsExactly(1L)
        }
    }

    @Test
    fun unqualifiedInlinePayloadTypesFailExplicitlyWhileCountsRemainReadable() {
        for ((type, literal) in listOf("TIME" to "TIME '24:00:00'", "INTERVAL" to "INTERVAL '100 hours'", "VARIANT" to "42::VARIANT")) {
            val name = name("unsupported_type")
            server.native("CREATE TABLE lake.$name(v $type); INSERT INTO lake.$name VALUES ($literal)")
            read {
                val snapshot = reader.currentSnapshotId
                val table = reader.getTable("main", name, snapshot)!!
                val columns = reader.getTableColumns(table.tableId, snapshot)
                val info = reader.getInlinedDataInfos(table.tableId, snapshot).single()
                assertThatThrownBy { reader.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("inline type")
                assertThatThrownBy { reader.readInlinedData(table.tableId, info.schemaVersion, snapshot, columns) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("inline type")
                assertThat(reader.countInlinedRows(table.tableId, info.schemaVersion, snapshot)).isEqualTo(1L)
                val identity = reader.getInlinedChangesBetweenDecoded(table.tableId, info.schemaVersion, snapshot, snapshot, emptyList()).single()
                assertThat(identity.rowId).isEqualTo(0L)
                assertThat(identity.values).isEmpty()
            }
        }
    }

    @Test
    fun readsANativelyMigrated10CatalogWithOldParquetAndTwoInlineSchemas() {
        PreviewQuackServer("1.1", "1.1-migrated", migrateFrom10 = true).use { migrated ->
            migrated.start()
            migrated.catalog(enableFinal11Reads = true).use { c ->
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    assertThat(snapshot).isEqualTo(migrated.seededSnapshotId)
                    assertThat(c.getSpecVersion()).isEqualTo("1.1")
                    val table = c.getTable("main", "migrate_seed", snapshot)!!
                    val columns = c.getTableColumns(table.tableId, snapshot)
                    val file = c.getDataFiles(table.tableId, snapshot).single()
                    assertThat(file.recordCount).isEqualTo(3L)
                    val infos = c.getInlinedDataInfos(table.tableId, snapshot)
                    assertThat(infos).hasSize(2)
                    assertThat(infos.flatMap { c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) }
                        .sortedBy { it[0] as Int }).containsExactly(listOf(100, "old", null), listOf(101, "new", 7))
                    assertThat(infos.flatMap { c.readInlinedRowIds(table.tableId, it.schemaVersion, snapshot) }.sorted())
                        .containsExactly(3L, 4L)
                    assertThat(c.getLiveRowCount(table.tableId, snapshot)).isEqualTo(5L)
                    assertThat(c.getTableColumns(table.tableId, file.beginSnapshot).map { it.columnName }).containsExactly("i", "v")
                    assertThat(migrated.native("SELECT i,x,rowid FROM lake.migrate_seed ORDER BY i"))
                        .containsExactly(listOf("1", null, "0"), listOf("2", null, "1"), listOf("3", null, "2"),
                            listOf("100", null, "3"), listOf("101", "7", "4"))
                })
                c.readSession(Supplier { assertThat(c.currentSnapshotId).isEqualTo(migrated.seededSnapshotId) })
            }
        }
    }

    @Test
    fun optingIntoReadsDoesNotPermitFinal11DdlFileRegistrationStatsOrMaintenanceMutations() {
        val before = read { reader.currentSnapshotId }
        val table = read { reader.getTable("main", "scalar_seed", before)!! }
        val schema = read { reader.getSchema("main", before)!! }
        val column = read { reader.getTableColumns(table.tableId, before).single() }
        val directory = Files.createDirectories(server.data.resolve(schema.path).resolve(table.path))
        val output = directory.resolve("must-not-register.parquet")
        server.execute("COPY (SELECT 1000::INTEGER AS i) TO ${PreviewQuackServer.literal(output.toString())} " +
            "(FORMAT PARQUET,FIELD_IDS {i:${column.columnId}})")
        val fragment = DucklakeWriteFragment(output.fileName.toString(), Files.size(output), 0, 1, emptyList())
        assertThatThrownBy { reader.createSchema("must_not_create") }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
        assertThatThrownBy { reader.commitInsert(table.tableId, listOf(fragment)) }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
        assertThatThrownBy { reader.analyzeTable(table.tableId) }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
        assertThatThrownBy { reader.expireSnapshots(setOf(1L)) }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
        read {
            assertThat(reader.currentSnapshotId).isEqualTo(before)
            assertThat(reader.getSchema("must_not_create", before)).isNull()
            assertThat(reader.getDataFiles(table.tableId, before)).isEmpty()
            assertThat(reader.getSpecVersion()).isEqualTo("1.1")
            assertThat(reader.listScheduledFileBatch(Instant.parse("1900-01-01T00:00:00Z"), 1)).isEmpty()
        }
        assertThat(Files.exists(output)).isTrue()
        assertThat(server.native("SELECT i FROM lake.scalar_seed ORDER BY i")).containsExactly(listOf("11"), listOf("22"))
    }
}
