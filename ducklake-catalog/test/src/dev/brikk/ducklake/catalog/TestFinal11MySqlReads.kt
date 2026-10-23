/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import java.math.BigInteger
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetTime
import java.time.OffsetDateTime
import java.util.UUID
import java.util.function.Supplier
import java.time.Instant
import java.util.TimeZone
import java.sql.DriverManager

class TestFinal11MySqlReads {
    private fun <T> read(f: Final11MySqlFixture, action: (JdbcDucklakeCatalog) -> T): T =
        JdbcDucklakeCatalog(f.readerConfig()).use { c -> c.readSession(Supplier { action(c) }) }

    @Test
    fun existingNativeScalarValuesAndNullableBackendProfileReachDirectJdbcWithoutUpgrade() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(b BOOLEAN,i TINYINT,u UBIGINT,h UHUGEINT,s VARCHAR,blob BLOB,d DATE,tm TIME_NS," +
                "z TIMETZ,iv INTERVAL,tz TIMESTAMPTZ_NS,n \"NULL\",dec DECIMAL(18,6),uuid UUID); " +
                "INSERT INTO lake.t VALUES(TRUE,-128,18446744073709551615,340282366920938463463374607431768211455," +
                "'NULL'||chr(0),from_hex('005CFF'),DATE '2000-01-01',TIME_NS '12:34:56.123456789',TIMETZ '01:02:03+05:30:15'," +
                "INTERVAL '1 month 2 days 100:00:00.123456',TIMESTAMPTZ_NS '1969-12-31 23:59:59.999999999+00',NULL,12.345678," +
                "UUID '00000000-0000-0000-0000-000000000001')")
            read(f) { c ->
                val profile = c.readBackendProfile()
                assertThat(profile.accessRoute).isEqualTo("mysql-jdbc")
                assertThat(profile.metadataBackend).isEqualTo("mysql")
                assertThat(profile.sqlEngineVersion).startsWith("8.4.")
                assertThat(profile.sqlEngineSourceId).isNull()
                assertThat(profile.unsignedExtensionsAllowed).isNull()
                assertThat(profile.ducklakeExtensionVersion).isNull()
                assertThat(profile.quackExtensionVersion).isNull()
                assertThat(profile.qualifiedWrites).isFalse()
                assertThatThrownBy { c.readMetadata().getReadMetadataCapabilities() }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readBackendProfile")
                val id = c.getTable("main", "t", profile.snapshotId)!!.tableId
                val info = c.getInlinedDataInfos(id, profile.snapshotId).single()
                val columns = c.getTableColumns(id, profile.snapshotId)
                val row = c.readInlinedDataDecoded(id, info.schemaVersion, profile.snapshotId, columns).single()
                assertThat(row.take(5)).containsExactly(true, -128, BigInteger("18446744073709551615"),
                    BigInteger("340282366920938463463374607431768211455"), "NULL\u0000")
                assertThat(row[5] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                assertThat(row[6]).isEqualTo(LocalDate.of(2000, 1, 1))
                assertThat(row[7]).isEqualTo(LocalTime.of(12, 34, 56, 123456789))
                assertThat(row[8]).isEqualTo(OffsetTime.parse("01:02:03+05:30:15"))
                assertThat(row[9]).isEqualTo(DucklakeInterval(1, 2, 360000123456L))
                assertThat(row[10]).isEqualTo(OffsetDateTime.parse("1969-12-31T23:59:59.999999999Z"))
                assertThat(row[11]).isNull()
                assertThat(row[12]).isEqualTo(BigDecimal("12.345678"))
                assertThat(row[13]).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                val raw = c.readInlinedData(id, info.schemaVersion, profile.snapshotId, columns).single()
                assertThat(raw[0]).isEqualTo(1L)
                assertThat(raw[4]).isEqualTo("NULL\u0000")
                assertThat(raw[7]).isEqualTo("12:34:56.123456789")
                assertThatThrownBy { c.createSchema("forbidden") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            }
            assertThat(f.rows("SELECT value FROM ducklake_metadata WHERE `key`='version'").single()).containsExactly("1.1")
        }
    }

    @Test
    fun existingNativeFilesRichMetadataAndHierarchyRetainPathsAndUnknownFlags() {
        Final11MySqlFixture().use { f ->
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',false); " +
                "CREATE SCHEMA lake.a; CREATE SCHEMA lake.a.child; CREATE TABLE lake.a.child.t AS SELECT i FROM range(100) t(i); " +
                "DELETE FROM lake.a.child.t WHERE i<10")
            val native = f.nativeRows("SELECT data_file,delete_file FROM ducklake_list_files('lake','t',schema=>'a.child')").single()
            read(f) { c ->
                val snapshot = c.readBackendProfile().snapshotId
                val h = c.readHierarchy()
                val table = h.findTable(listOf("A", "CHILD"), "T", snapshot)!!
                assertThat(table.schema.names).containsExactly("a", "child")
                assertThat(h.getTableById(table.table.tableId, snapshot)).isEqualTo(table)
                assertThat(h.listRetainedFiles(DucklakeFileKind.DATA, null, 10).single().resolvedPath).isEqualTo(native[0])
                assertThat(h.listRetainedFiles(DucklakeFileKind.DELETE, null, 10).single().resolvedPath).isEqualTo(native[1])
                val meta = c.readMetadata().getFileReadMetadata(table.table.tableId, snapshot).single()
                assertThat(meta.recordCount).isEqualTo(100)
                assertThat(meta.deleteCount).isEqualTo(10)
                assertThat(meta.rowGroupCount).isEqualTo(1)
                assertThat(meta.deleteRowGroupCount).isEqualTo(1)
                assertThat(c.readMetadata().getFileColumnMetadata(table.table.tableId, snapshot).single().stats.minValue).isEqualTo("0")
                assertThatThrownBy { c.listSchemas(snapshot) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("nested-schema history")
            }
        }
    }

    @Test
    fun exactScheduledCursorsPreserveCaseUnicodeFlagsAndUnknownOrFutureAges() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            f.metadata("INSERT INTO ducklake_files_scheduled_for_deletion VALUES " +
                "(7,'A',TRUE,'2099-01-01'),(7,'A',TRUE,NULL),(7,'A',FALSE,'2099-01-01'),(7,'a',TRUE,NULL)," +
                "(7,'é',TRUE,NULL),(7,'é',TRUE,NULL),(8,'old',TRUE,'2000-01-01'),(9,' ',TRUE,NULL)")
            read(f) { c ->
                val h = c.readHierarchy()
                val all = mutableListOf<DucklakeScheduledPathReference>()
                var cursor: DucklakeScheduledPathCursor? = null
                repeat(5) {
                    val page = h.listScheduledReferences(cursor, 2)
                    all.addAll(page)
                    if (page.isNotEmpty()) cursor = page.last().cursor
                }
                assertThat(all.map { it.cursor }).containsExactly(DucklakeScheduledPathCursor(7, "A", false),
                    DucklakeScheduledPathCursor(7, "A", true), DucklakeScheduledPathCursor(7, "a", true),
                    DucklakeScheduledPathCursor(7, "é", true), DucklakeScheduledPathCursor(7, "é", true),
                    DucklakeScheduledPathCursor(8, "old", true), DucklakeScheduledPathCursor(9, " ", true))
                val old = h.listScheduledFiles(Instant.parse("2020-01-01T00:00:00Z"), 10).single()
                assertThat(old.dataFileId).isEqualTo(8)
                assertThat(old.scheduleStart.toInstant()).isEqualTo(Instant.parse("2000-01-01T00:00:00Z"))
                assertThat(old.resolvedPath).isEqualTo(f.data.toString() + "/old")
                assertThat(h.listScheduledFiles(Instant.parse("2000-01-01T00:00:00Z"), 10)).isEmpty()
                assertThat(h.listScheduledFiles(Instant.parse("2000-01-01T00:00:00.000000001Z"), 10).single().dataFileId).isEqualTo(8)
            }
        }
    }

    @Test
    fun scalarHistoricalReadsAndInsertCdcKeepNativeIdentityAndNulls() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i TINYINT,s VARCHAR)")
            val created = f.rows("SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
            f.native("INSERT INTO lake.t VALUES(1,NULL),(2,'NULL')")
            val inserted = f.rows("SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val columns = c.getTableColumns(id, snapshot)
                val infos = c.getInlinedDataInfos(id, snapshot)
                assertThat(infos.flatMap { c.readInlinedDataDecoded(id, it.schemaVersion, snapshot, columns) }.sortedBy { it[0] as Int })
                    .containsExactly(listOf(1, null), listOf(2, "NULL"))
                val changes = infos.flatMap { c.getInlinedChangesBetweenDecoded(id, it.schemaVersion, snapshot, snapshot, columns.map { col -> col.columnId }) }
                assertThat(changes.map { it.rowId }).containsExactly(0, 1)
                assertThat(changes.map { it.beginSnapshot }).containsOnly(inserted)
                assertThat(changes.map { it.endSnapshot }).containsOnlyNulls()
                assertThat(changes.first().values).containsExactly(1, null)
                assertThat(c.getInlinedDataInfos(id, inserted).flatMap {
                    c.readInlinedDataDecoded(id, it.schemaVersion, inserted, c.getTableColumns(id, inserted))
                }).containsExactly(listOf(1, null), listOf(2, "NULL"))
                assertThat(infos.flatMap { c.readInlinedDataDecoded(id, it.schemaVersion, created, c.getTableColumns(id, created)) }).isEmpty()
            }
        }
    }

    @Test
    fun lossyNativeTypesAndUnqualifiedEvolutionFailBeforePhysicalQueries() {
        fun column(id: Long, name: String, type: String, parent: Long? = null) = DucklakeColumn(id, 0, null, 1, id, name, type, true, parent)
        val ctx = org.jooq.impl.DSL.using(org.jooq.SQLDialect.MYSQL)
        for (type in listOf("int128", "json", "timestamp", "timestamp_us", "timestamp_ms", "timestamp_ns", "timestamptz")) {
            val schema = Final11InlineSchema(listOf(column(1, "v", type)))
            assertThatThrownBy { Final11MySqlProjection(ctx, schema, schema).plan("unused", listOf(1)) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("lossless native MySQL storage")
        }
        val nested = Final11InlineSchema(listOf(column(1, "s", "struct"), column(2, "v", "int32", 1)))
        assertThatThrownBy { Final11MySqlProjection(ctx, nested, nested).plan("unused", listOf(1)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("lossless native MySQL storage")
        val source = Final11InlineSchema(listOf(column(1, "v", "int8")))
        val target = Final11InlineSchema(listOf(column(1, "v", "int32")))
        assertThatThrownBy { Final11MySqlProjection(ctx, source, target).plan("unused", listOf(1)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("scalar schema evolution")
    }

    @Test
    fun storedBooleanLogicalNullAndPhysicalSignednessCorruptionCannotBypassRawReads() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(b BOOLEAN,n \"NULL\",u UTINYINT); INSERT INTO lake.t VALUES(TRUE,NULL,255)")
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            f.metadata("UPDATE $physical SET b=2")
            assertPayloadFailure(f, "0/1 domain", DucklakeCatalogCorruptionException::class.java)
            f.metadata("UPDATE $physical SET b=1,n='NULL'")
            assertPayloadFailure(f, "logical NULL", DucklakeCatalogCorruptionException::class.java)
            f.metadata("UPDATE $physical SET n=NULL,u=1; ALTER TABLE $physical MODIFY COLUMN u TINYINT")
            assertPayloadFailure(f, "physical payload mismatch", DucklakeInvalidOperationException::class.java)
        }
    }

    private fun assertPayloadFailure(f: Final11MySqlFixture, message: String, expected: Class<out Throwable>) {
        val schema = f.rows("SELECT schema_version FROM ducklake_inlined_data_tables ORDER BY schema_version LIMIT 1").single().single()!!.toLong()
        read(f) { c ->
            val snapshot = c.currentSnapshotId
            val id = c.getTable("main", "t", snapshot)!!.tableId
            val columns = c.getTableColumns(id, snapshot)
            assertThatThrownBy { c.readInlinedData(id, schema, snapshot, columns) }.isInstanceOf(expected).hasMessageContaining(message)
            assertThatThrownBy { c.readInlinedDataDecoded(id, schema, snapshot, columns) }.isInstanceOf(expected).hasMessageContaining(message)
        }
    }

    @Test
    fun metadataBooleansUuidCharsetAndStorageEngineMustBeTruthful() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
            f.metadata("UPDATE ducklake_schema SET path_is_relative=2")
            read(f) { c -> assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                .hasMessageContaining("metadata boolean") }
            f.metadata("UPDATE ducklake_schema SET path_is_relative=1; UPDATE ducklake_schema SET schema_uuid='bad'")
            read(f) { c -> assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                .hasMessageContaining("metadata UUID") }
            f.metadata("UPDATE ducklake_schema SET schema_uuid='00000000-0000-0000-0000-000000000001'; " +
                "ALTER TABLE ducklake_schema MODIFY COLUMN path TEXT CHARACTER SET latin1")
            read(f) { c -> assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                .hasMessageContaining("physical metadata column") }
            f.metadata("ALTER TABLE ducklake_schema MODIFY COLUMN path TEXT CHARACTER SET utf8mb4; ALTER TABLE ducklake_schema ENGINE=MyISAM")
            read(f) { c -> assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                .hasMessageContaining("InnoDB") }
        }
    }

    @Test
    fun nativePayloadTypeBudgetAndReservedPrefixChecksFailRatherThanTruncate() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(v VARCHAR); INSERT INTO lake.t VALUES('ok')")
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            f.metadata("ALTER TABLE $physical MODIFY COLUMN _ducklake_row_id BIGINT UNSIGNED")
            assertPayloadFailure(f, "signed BIGINT", DucklakeInvalidOperationException::class.java)
            f.metadata("ALTER TABLE $physical MODIFY COLUMN _ducklake_row_id BIGINT; ALTER TABLE $physical MODIFY COLUMN v LONGTEXT")
            assertPayloadFailure(f, "physical payload mismatch", DucklakeInvalidOperationException::class.java)
            f.metadata("ALTER TABLE $physical MODIFY COLUMN v TEXT; UPDATE ducklake_schema SET schema_name=repeat('x',16385)")
            read(f) { c -> assertThatThrownBy { c.readHierarchy().listSchemas(c.readBackendProfile().snapshotId) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text budget") }
        }
    }

    @Test
    fun mysqlReadsNeverBootstrapAndExplicitlyRequireOwnedOptInSessions() {
        Final11MySqlFixture().use { f ->
            JdbcDucklakeCatalog(f.readerConfig()).use { c ->
                assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("readSession")
                assertThatThrownBy { c.readSession(Supplier { c.currentSnapshotId }) }.isInstanceOf(RuntimeException::class.java)
            }
            assertThat(f.rows("SELECT count(*) FROM information_schema.tables WHERE table_schema=database() AND table_name LIKE 'ducklake_%'").single())
                .containsExactly("0")
        }
    }

    @Test
    fun timezoneAndReadOnlyPropertiesAreVerifiedOnTheServerNotGuessed() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            val timezone = f.readerConfig().apply {
                catalogDatabaseUrl += (if (catalogDatabaseUrl!!.contains('?')) "&" else "?") + "connectionTimeZone=%2B02%3A00"
            }
            JdbcDucklakeCatalog(timezone).use { c -> c.readSession(Supplier {
                assertThat(c.readBackendProfile().accessRoute).isEqualTo("mysql-jdbc") // Pool UTC properties override the conflicting URL.
            }) }
            val unsafe = f.readerConfig().apply {
                catalogDatabaseUrl += (if (catalogDatabaseUrl!!.contains('?')) "&" else "?") + "readOnlyPropagatesToServer=false"
            }
            JdbcDucklakeCatalog(unsafe).use { c ->
                assertThatThrownBy { c.readSession(Supplier { c.currentSnapshotId }) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("server-enforced read-only")
            }
            DriverManager.getConnection(unsafe.catalogDatabaseUrl, unsafe.catalogDatabaseUser, unsafe.catalogDatabasePassword).use { direct ->
                direct.createStatement().use { it.execute("SET SESSION time_zone='+02:00'") }
                assertThatThrownBy { Final11MySqlLayout.requireSession(org.jooq.impl.DSL.using(direct, org.jooq.SQLDialect.MYSQL)) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("UTC/utf8mb4")
            }
        }
    }

    @Test
    fun queueTimestampsStayUtcWithANonUtcJvmDefault() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            f.metadata("INSERT INTO ducklake_files_scheduled_for_deletion VALUES(1,'old',TRUE,'2000-01-01 00:00:00')")
            val before = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
                val config = f.readerConfig().apply {
                    catalogDatabaseUrl += (if (catalogDatabaseUrl!!.contains('?')) "&" else "?") + "cacheDefaultTimeZone=false"
                }
                JdbcDucklakeCatalog(config).use { c -> c.readSession(Supplier {
                    assertThat(c.readHierarchy().listScheduledFiles(Instant.parse("2020-01-01T00:00:00Z"), 1).single().scheduleStart.toInstant())
                        .isEqualTo(Instant.parse("2000-01-01T00:00:00Z"))
                }) }
            } finally { TimeZone.setDefault(before) }
        }
    }

    @Test
    fun nullableStoredRichMetadataAndOwnedSnapshotRefreshDoNotInventOptimizationCapabilities() {
        Final11MySqlFixture().use { f ->
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(20)")
            val id = f.rows("SELECT table_id FROM ducklake_table WHERE table_name='t'").single().single()!!.toLong()
            JdbcDucklakeCatalog(f.readerConfig()).use { c ->
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    assertThat(c.readMetadata().getFileReadMetadata(id, snapshot).single().rowGroupCount).isEqualTo(1)
                    f.metadata("UPDATE ducklake_data_file SET row_group_count=NULL; UPDATE ducklake_file_column_stats SET min_value=NULL," +
                        "max_value='999',min_is_exact=NULL,max_is_exact=FALSE; " +
                        "UPDATE ducklake_table_column_stats SET min_value='NULL',max_value=NULL,min_is_exact=FALSE,max_is_exact=NULL")
                    assertThat(c.readMetadata().getFileReadMetadata(id, snapshot).single().rowGroupCount).isEqualTo(1)
                })
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    assertThat(c.readMetadata().getFileReadMetadata(id, snapshot).single().rowGroupCount).isNull()
                    val file = c.readMetadata().getFileColumnMetadata(id, snapshot).single()
                    assertThat(file.stats.minValue).isNull()
                    assertThat(file.stats.maxValue).isEqualTo("999")
                    assertThat(file.minIsExact).isNull()
                    assertThat(file.maxIsExact).isFalse()
                    val table = c.readMetadata().getTableColumnMetadata(id).single()
                    assertThat(table.minValue).isEqualTo("NULL")
                    assertThat(table.maxValue).isNull()
                    assertThat(table.minIsExact).isFalse()
                    assertThat(table.maxIsExact).isNull()
                    assertThat(c.getColumnStats(id, snapshot).single().minValue).isNull()
                    assertThat(c.getColumnStats(id, snapshot).single().maxValue).isNull()
                    assertThat(c.findDataFileIdsInRange(id, snapshot, ColumnRangePredicate(1, "9999", "10000")))
                        .containsExactlyElementsOf(c.getDataFiles(id, snapshot).map { it.dataFileId })
                })
            }
        }
    }

    @Test
    fun existingEndedRowMetadataHasLosslessRawAndDecodedDeleteCdcWithoutClaimingANativeDeleteWrite() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER,s VARCHAR); INSERT INTO lake.t VALUES(1,NULL),(2,'NULL')")
            val begin = f.rows("SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
            val info = f.rows("SELECT table_id,schema_version,table_name FROM ducklake_inlined_data_tables").single()
            f.native("CREATE TABLE lake.publication(i INTEGER)")
            val end = f.rows("SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
            // Owned metadata-state fixture only; no version retag, invented physical DDL or native DELETE pass.
            f.metadata("UPDATE ${info[2]} SET _ducklake_end_snapshot=$end WHERE _ducklake_row_id=0")
            read(f) { c ->
                val id = info[0]!!.toLong()
                val schema = info[1]!!.toLong()
                val columns = c.getTableColumns(id, end)
                val decoded = c.getInlinedChangesBetweenDecoded(id, schema, end, end, columns.map { it.columnId }).single()
                val raw = c.getInlinedChangesBetween(id, schema, end, end, columns.map { it.columnId }).single()
                assertThat(decoded.rowId).isZero()
                assertThat(decoded.beginSnapshot).isEqualTo(begin)
                assertThat(decoded.endSnapshot).isEqualTo(end)
                assertThat(decoded.values).containsExactly(1, null)
                assertThat(raw).isEqualTo(decoded)
                assertThat(c.readInlinedDataDecoded(id, schema, end, columns)).containsExactly(listOf(2, "NULL"))
                assertThat(c.readInlinedDataDecoded(id, schema, begin, c.getTableColumns(id, begin)))
                    .containsExactly(listOf(1, null), listOf(2, "NULL"))
            }
        }
    }

    @Test
    fun floatTransportWidensOnTheServerBeforeMysqlTextProtocolCanRoundItsDisplay() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(v FLOAT); INSERT INTO lake.t VALUES(0.123456789)")
            val expected = 0.123456789f
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            assertThat(f.rows("SELECT cast(v AS DOUBLE) FROM $physical").single().single()!!.toDouble().toRawBits())
                .isEqualTo(expected.toDouble().toRawBits())
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val info = c.getInlinedDataInfos(id, snapshot).single()
                val columns = c.getTableColumns(id, snapshot)
                assertThat((c.readInlinedDataDecoded(id, info.schemaVersion, snapshot, columns).single().single() as Float).toRawBits())
                    .isEqualTo(expected.toRawBits())
                assertThat((c.readInlinedData(id, info.schemaVersion, snapshot, columns).single().single() as Float).toRawBits())
                    .isEqualTo(expected.toRawBits())
            }
        }
    }

    @Test
    fun signedUnsignedWidthsAndLosslessSecondTimestampAndClockFormsAreQualifiedIndependently() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(s SMALLINT,i INTEGER,l BIGINT,u UTINYINT,us USMALLINT,ui UINTEGER,ts TIMESTAMP_S,tm TIME); " +
                "INSERT INTO lake.t VALUES(-32768,-2147483648,-9223372036854775808,255,65535,4294967295," +
                "TIMESTAMP_S '1969-12-31 23:59:59',TIME '12:34:56.123456')")
            read(f) { c ->
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val info = c.getInlinedDataInfos(id, snapshot).single()
                val columns = c.getTableColumns(id, snapshot)
                val values = c.readInlinedDataDecoded(id, info.schemaVersion, snapshot, columns).single()
                assertThat(values.take(6)).containsExactly(-32768, Int.MIN_VALUE, Long.MIN_VALUE, 255L, 65535L, 4294967295L)
                assertThat(values[6]).isEqualTo(java.time.LocalDateTime.of(1969, 12, 31, 23, 59, 59))
                assertThat(values[7]).isEqualTo(LocalTime.of(12, 34, 56, 123456000))
                val raw = c.readInlinedData(id, info.schemaVersion, snapshot, columns).single()
                assertThat(raw[6]).isEqualTo("1969-12-31 23:59:59")
                assertThat(raw[7]).isEqualTo("12:34:56.123456")
            }
        }
    }
}
