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
import java.util.function.Supplier
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.math.BigDecimal
import java.time.LocalDateTime

class TestFinal11PostgresReads {
    @Test
    fun nativeFinal11PhysicalStorageIsObservedIndependently() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i TINYINT,u UBIGINT,s VARCHAR,b BLOB,ns TIME_NS,z TIMETZ,iv INTERVAL," +
                "tz TIMESTAMPTZ_NS,st STRUCT(a INTEGER,d DATE,b BLOB,j JSON),n \"NULL\"); " +
                "INSERT INTO lake.t VALUES(-8,18446744073709551615,'héllo'||chr(0),from_hex('005CFF'),TIME_NS '23:59:59.999999999'," +
                "TIMETZ '01:02:03+05:30:15',INTERVAL '1 month 2 days 100:00:00.123456'," +
                "TIMESTAMPTZ_NS '1969-12-31 23:59:59.999999999+00',{'a':1,'d':DATE '0001-01-01 (BC)','b':from_hex('00FF'),'j':'{\"n\":null}'::JSON},NULL)")
            val table = f.rows("SELECT table_name FROM ducklake_inlined_data_tables ORDER BY schema_version LIMIT 1").single().single()!!
            val types = f.rows("SELECT column_name,data_type FROM information_schema.columns WHERE table_name='$table' ORDER BY ordinal_position")
            println("R06 native PostgreSQL physical columns: $types")
            assertThat(types).contains(listOf("i", "smallint"), listOf("u", "character varying"), listOf("s", "bytea"),
                listOf("b", "bytea"), listOf("ns", "character varying"), listOf("z", "time with time zone"),
                listOf("iv", "interval"), listOf("tz", "character varying"), listOf("st", "character varying"), listOf("n", "integer"))
            val text = f.rows("SELECT ns,z::varchar,iv::varchar,tz,st,encode(s,'hex'),encode(b,'hex'),n FROM $table").single()
            println("R06 native PostgreSQL physical values: $text")
            assertThat(text[0]).isEqualTo("23:59:59.999999999")
            assertThat(text[1]).isEqualTo("01:02:03+05:30:15")
            assertThat(text[5]).isEqualTo("68c3a96c6c6f00")
            assertThat(text[6]).isEqualTo("005cff")
            assertThat(text[7]).isNull()
            f.reader().use(::assertProbeReader)
        }
    }

    private fun assertProbeReader(c: JdbcDucklakeCatalog) {
        c.readSession(Supplier {
            val profile = c.readBackendProfile()
            assertThat(profile.metadataBackend).isEqualTo("postgresql")
            assertThat(profile.accessRoute).isEqualTo("postgres-jdbc")
            assertThat(profile.sqlEngineVersion).startsWith("PostgreSQL 18")
            assertThat(profile.sqlEngineSourceId).isNull()
            assertThat(profile.unsignedExtensionsAllowed).isNull()
            assertThat(profile.ducklakeExtensionVersion).isNull()
            assertThat(profile.quackExtensionVersion).isNull()
            assertThat(profile.richMetadataAvailable).isTrue()
            assertThat(profile.hierarchyAvailable).isTrue()
            assertThat(profile.qualifiedWrites).isFalse()
            assertThatThrownBy { c.readMetadata().getReadMetadataCapabilities() }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("use readBackendProfile")
            val table = c.getTable("main", "t", profile.snapshotId)!!
            val columns = c.getTableColumns(table.tableId, profile.snapshotId)
            val info = c.getInlinedDataInfos(table.tableId, profile.snapshotId).single()
            val row = c.readInlinedDataDecoded(table.tableId, info.schemaVersion, profile.snapshotId, columns).single()
            assertThat(row.take(3)).containsExactly(-8, BigInteger("18446744073709551615"), "héllo\u0000")
            assertThat(row[3] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
            assertThat(row[4]).isEqualTo(LocalTime.MAX)
            assertThat(row[5]).isEqualTo(OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofHoursMinutesSeconds(5, 30, 15)))
            assertThat(row[6]).isEqualTo(DucklakeInterval(1, 2, 360000123456L))
            assertThat(row[7]).isEqualTo(OffsetDateTime.parse("1969-12-31T23:59:59.999999999Z"))
            val structure = row[8] as Map<*, *>
            assertThat(structure["a"]).isEqualTo(1)
            assertThat(structure["d"]).isEqualTo(LocalDate.of(0, 1, 1))
            assertThat(structure["b"] as ByteArray).containsExactly(0, 0xff.toByte())
            assertThat(structure["j"]).isEqualTo("{\"n\":null}")
            assertThat(row[9]).isNull()
            val raw = c.readInlinedData(table.tableId, info.schemaVersion, profile.snapshotId, columns).single()
            assertThat(raw[2] as ByteArray).isEqualTo("héllo\u0000".toByteArray(Charsets.UTF_8))
            assertThat(raw[4]).isEqualTo("23:59:59.999999999")
            assertThat(raw[8]).isInstanceOf(String::class.java)
        })
    }

    @Test
    fun scalarDefaultsRootRenamesAndIntegerWideningPreserveNullsHistoryAndCdcIdentity() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i TINYINT,stored VARCHAR); INSERT INTO lake.t VALUES(1,NULL),(2,'NULL')")
            val inserted = f.snapshot()
            f.native("ALTER TABLE lake.t RENAME i TO id; ALTER TABLE lake.t ALTER id TYPE INTEGER; " +
                "ALTER TABLE lake.t ADD COLUMN added VARCHAR DEFAULT 'NULL'; INSERT INTO lake.t VALUES(3,NULL,NULL); " +
                "DELETE FROM lake.t WHERE id=1")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val columns = c.getTableColumns(table.tableId, snapshot)
                val infos = c.getInlinedDataInfos(table.tableId, snapshot)
                val rows = infos.flatMap { c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) }.sortedBy { it[0] as Int }
                assertThat(rows).containsExactly(listOf(2, "NULL", "NULL"), listOf(3, null, null))
                val deleted = infos.flatMap { c.getInlinedChangesBetweenDecoded(table.tableId, it.schemaVersion, snapshot, snapshot,
                    columns.map { col -> col.columnId }) }.single()
                assertThat(deleted.rowId).isZero()
                assertThat(deleted.beginSnapshot).isEqualTo(inserted)
                assertThat(deleted.endSnapshot).isEqualTo(snapshot)
                assertThat(deleted.values).containsExactly(1, null, "NULL")
                val raw = infos.flatMap { c.getInlinedChangesBetween(table.tableId, it.schemaVersion, snapshot, snapshot,
                    columns.map { col -> col.columnId }) }.single()
                assertThat(raw.values[0]).isEqualTo(1)
                assertThat(raw.values[1]).isNull()
                assertThat(raw.values[2] as ByteArray).isEqualTo("NULL".toByteArray())
                val history = c.getTableColumns(table.tableId, inserted)
                assertThat(history.map { it.columnName }).containsExactly("i", "stored")
                assertThat(c.getInlinedDataInfos(table.tableId, inserted).flatMap {
                    c.readInlinedDataDecoded(table.tableId, it.schemaVersion, inserted, history)
                })
                    .containsExactly(listOf(1, null), listOf(2, "NULL"))
                val forged = columns.map { it.copy(columnName = "untrusted", columnType = "varchar); DROP TABLE ducklake_snapshot --") }
                assertThat(infos.flatMap { c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, forged) }.sortedBy { it[0] as Int })
                    .containsExactlyElementsOf(rows)
            }) }
        }
    }

    @Test
    fun sourceDomainsAreCheckedBeforeWideningAndNonNullLogicalNullIsCorruption() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i TINYINT,n \"NULL\"); INSERT INTO lake.t VALUES(1,NULL); ALTER TABLE lake.t ALTER i TYPE INTEGER")
            val (tableId, schema) = f.inline("t")
            val physical = InlinedDataTables.tableName(tableId, schema)
            f.metadata("UPDATE $physical SET i=128")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val columns = c.getTableColumns(tableId, snapshot)
                assertThatThrownBy { c.readInlinedDataDecoded(tableId, schema, snapshot, columns) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                assertThatThrownBy { c.readInlinedData(tableId, schema, snapshot, columns) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
            }) }
            f.metadata("UPDATE $physical SET i=1,n=0")
            f.reader().use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.readInlinedDataDecoded(tableId, schema, c.currentSnapshotId, c.getTableColumns(tableId, c.currentSnapshotId)) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("logical NULL")
            }) }
        }
    }

    @Test
    fun qualifiedNestedRenameUsesStableIdsRatherThanInventingNulls() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(s STRUCT(a INTEGER)); INSERT INTO lake.t VALUES({'a':1}); ALTER TABLE lake.t RENAME s.a TO renamed")
            val (tableId, schema) = f.inline("t")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                assertThat(c.countInlinedRows(tableId, schema, snapshot)).isEqualTo(1)
                assertThat(c.readInlinedDataDecoded(tableId, schema, snapshot, c.getTableColumns(tableId, snapshot)))
                    .containsExactly(listOf(mapOf("renamed" to 1)))
            }) }
        }
    }

    @Test
    fun storedNestedValuesNeedCompleteUniqueFieldsAndValidUtf8() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(s STRUCT(a INTEGER,b VARCHAR),v VARCHAR); INSERT INTO lake.t VALUES({'a':1,'b':'NULL'},'ok')")
            val (tableId, schema) = f.inline("t")
            val physical = InlinedDataTables.tableName(tableId, schema)
            for (value in listOf("{'a':1}", "{'a':1,'a':2,'b':NULL}", "{'a':1,'b':NULL,'extra':2}")) {
                f.metadata("UPDATE $physical SET s='${value.replace("'", "''")}'")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.readInlinedData(tableId, schema, c.currentSnapshotId, c.getTableColumns(tableId, c.currentSnapshotId)) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                }) }
            }
            f.metadata("UPDATE $physical SET s='{''a'':1,''b'':NULL}',v=decode('ff','hex')")
            f.reader().use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.readInlinedDataDecoded(tableId, schema, c.currentSnapshotId, c.getTableColumns(tableId, c.currentSnapshotId)) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("UTF-8")
            }) }
        }
    }

    @Test
    fun repeatableReadPinsPublicationAndLocalIntervalStyleDoesNotRewriteRoleDefaults() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER,iv INTERVAL); INSERT INTO lake.t VALUES(1,INTERVAL '100:00:00.123456')")
            f.metadata("ALTER ROLE test SET intervalstyle='iso_8601'")
            f.reader().use { c ->
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    f.native("ALTER TABLE lake.t ADD COLUMN added VARCHAR DEFAULT 'NULL'; INSERT INTO lake.t VALUES(2,INTERVAL '0 seconds',NULL)")
                    assertThat(c.currentSnapshotId).isEqualTo(snapshot)
                    assertThat(c.getTableColumns(table.tableId, snapshot).map { it.columnName }).containsExactly("i", "iv")
                })
                c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val columns = c.getTableColumns(table.tableId, snapshot)
                    assertThat(c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                        c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
                    }.sortedBy { it[0] as Int }).containsExactly(listOf(1, DucklakeInterval(0, 0, 360000123456L), "NULL"),
                        listOf(2, DucklakeInterval(0, 0, 0), null))
                })
            }
            assertThat(f.rows("SHOW intervalstyle").single()).containsExactly("iso_8601")
        }
    }

    @Test
    fun nativeMigrationPreservesOldInlineDefaultsFilesAndConservativeStatistics() {
        Final11PostgresFixture(migrate = true).use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7); ALTER TABLE lake.t ADD COLUMN v VARCHAR DEFAULT 'NULL'; " +
                "CREATE TABLE lake.files(i INTEGER); CALL lake.set_option('data_inlining_row_limit',0,table_name=>'files'); " +
                "INSERT INTO lake.files FROM range(20)")
            f.native("INSERT INTO lake.t VALUES(8,NULL)")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val columns = c.getTableColumns(table.tableId, snapshot)
                assertThat(c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                    c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
                }.sortedBy { it[0] as Int }).containsExactly(listOf(7, "NULL"), listOf(8, null))
                val files = c.getTable("main", "files", snapshot)!!
                assertThat(c.getDataFiles(files.tableId, snapshot)).hasSize(1)
                assertThat(c.getColumnStats(files.tableId, snapshot).map { it.minValue }).containsOnlyNulls()
                assertThat(c.getColumnStats(files.tableId, snapshot).map { it.maxValue }).containsOnlyNulls()
                assertThat(c.findDataFileIdsInRange(files.tableId, snapshot, ColumnRangePredicate(1, "999", "1000")))
                    .containsExactlyElementsOf(c.getDataFiles(files.tableId, snapshot).map { it.dataFileId })
                assertThatThrownBy { c.createSchema("forbidden") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            }) }
        }
    }

    @Test
    fun profileAndReadGuardsNeverBootstrapEmptyOrUnknownFormatCatalogs() {
        Final11PostgresFixture().use { f ->
            f.reader().use { c ->
                assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
                c.readSession(Supplier {
                    assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(RuntimeException::class.java).hasMessageContaining("ducklake_metadata")
                })
            }
            assertThat(f.rows("SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'ducklake_%'").single()).containsExactly("0")
            f.native("CREATE TABLE lake.t(i INTEGER)")
            f.metadata("UPDATE ducklake_metadata SET value='1.1-dev1' WHERE key='version'")
            f.reader().use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("final 1.1")
            }) }
        }
    }

    @Test
    fun physicalTypesIdentityAndByteTransferBudgetsAreValidatedBeforeValuesEscape() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i TINYINT,v VARCHAR); INSERT INTO lake.t VALUES(1,'ok')")
            val (id, schema) = f.inline("t")
            val physical = InlinedDataTables.tableName(id, schema)
            f.metadata("ALTER TABLE $physical ALTER i TYPE INTEGER")
            assertPayloadFailure(f, id, schema, DucklakeInvalidOperationException::class.java, "physical payload type")
            f.metadata("ALTER TABLE $physical ALTER i TYPE SMALLINT; UPDATE $physical SET _ducklake_row_id=NULL")
            assertPayloadFailure(f, id, schema, DucklakeCatalogCorruptionException::class.java, "row/snapshot identity")
            f.metadata("UPDATE $physical SET _ducklake_row_id=0,v=convert_to(repeat('x',1048577),'UTF8')")
            assertPayloadFailure(f, id, schema, DucklakeInvalidOperationException::class.java, "budget")
            f.metadata("ALTER TABLE ducklake_schema ALTER parent_schema_id TYPE VARCHAR")
            f.reader().use { c -> c.readSession(Supplier {
                assertThatThrownBy { c.readBackendProfile() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("parent_schema_id")
            }) }
        }
    }

    private fun assertPayloadFailure(f: Final11PostgresFixture, id: Long, schema: Long, expected: Class<out Throwable>, message: String) {
        f.reader().use { c -> c.readSession(Supplier {
            val snapshot = c.currentSnapshotId
            val columns = c.getTableColumns(id, snapshot)
            assertThatThrownBy { c.readInlinedData(id, schema, snapshot, columns) }.isInstanceOf(expected).hasMessageContaining(message)
            assertThatThrownBy { c.readInlinedDataDecoded(id, schema, snapshot, columns) }.isInstanceOf(expected).hasMessageContaining(message)
        }) }
    }

    @Test
    fun badDefaultsNeverExecuteSqlAndRequiredFileMetadataNeverBecomesZeroOrFalse() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1); ALTER TABLE lake.t ADD COLUMN added INTEGER DEFAULT 42; " +
                "CREATE TABLE lake.files(i INTEGER); CALL lake.set_option('data_inlining_row_limit',0,table_name=>'files'); INSERT INTO lake.files VALUES(9)")
            val (id, schema) = f.inline("t")
            f.metadata("UPDATE ducklake_column SET initial_default='1; DROP TABLE ducklake_snapshot --' WHERE column_name='added'")
            assertPayloadFailure(f, id, schema, DucklakeCatalogCorruptionException::class.java, "cannot be decoded")
            assertThat(f.rows("SELECT count(*) FROM ducklake_snapshot").single().single()!!.toLong()).isPositive()
            f.metadata("UPDATE ducklake_data_file SET row_id_start=NULL")
            f.reader().use { c -> c.readSession(Supplier {
                val table = c.getTable("main", "files", c.currentSnapshotId)!!
                assertThat(c.getDataFiles(table.tableId, c.currentSnapshotId).single().rowIdStart).isNull()
            }) }
            f.metadata("UPDATE ducklake_data_file SET path_is_relative=NULL")
            f.reader().use { c -> c.readSession(Supplier {
                val table = c.getTable("main", "files", c.currentSnapshotId)!!
                assertThatThrownBy { c.getDataFiles(table.tableId, c.currentSnapshotId) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("cannot fabricate zero/false")
            }) }
        }
    }

    @Test
    fun unchangedListsMapsDecimalAndTimestampValuesPreserveContainersAndMutableDefaultsAreNotShared() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(id INTEGER,xs STRUCT(i INTEGER,s VARCHAR,b BLOB)[],mp MAP(VARCHAR,STRUCT(j JSON,v INTEGER))," +
                "d DECIMAL(18,3),ts TIMESTAMP_NS); INSERT INTO lake.t VALUES " +
                "(1,[{'i':1,'s':'NULL','b':from_hex('00FF')},NULL],MAP(['NULL'],[{'j':'null'::JSON,'v':NULL}]),12.345," +
                "TIMESTAMP_NS '1969-12-31 23:59:59.999999999'),(2,[],MAP([],[]),NULL,NULL); " +
                "ALTER TABLE lake.t ADD COLUMN added BLOB DEFAULT '\\x00N'::BLOB")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val columns = c.getTableColumns(table.tableId, snapshot)
                val infos = c.getInlinedDataInfos(table.tableId, snapshot)
                val rows = infos.flatMap { c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) }.sortedBy { it[0] as Int }
                val values = rows[0][1] as List<*>
                val structure = values[0] as Map<*, *>
                assertThat(structure["i"]).isEqualTo(1)
                assertThat(structure["s"]).isEqualTo("NULL")
                assertThat(structure["b"] as ByteArray).containsExactly(0, 0xff.toByte())
                assertThat(values[1]).isNull()
                assertThat(rows[0][2]).isEqualTo(mapOf("NULL" to mapOf("j" to "null", "v" to null)))
                assertThat(rows[0][3]).isEqualTo(BigDecimal("12.345"))
                assertThat(rows[0][4]).isEqualTo(LocalDateTime.parse("1969-12-31T23:59:59.999999999"))
                assertThat(rows[1][1]).isEqualTo(emptyList<Any?>())
                assertThat(rows[1][2]).isEqualTo(emptyMap<Any?, Any?>())
                assertThat(rows[1][3]).isNull()
                assertThat(rows[1][4]).isNull()
                (rows[0][5] as ByteArray)[0] = 99
                assertThat(rows[1][5] as ByteArray).containsExactly(0, 0x4e)
            }) }
        }
    }

    @Test
    fun specialNativeValuesCannotBypassJavaRepresentationGuardsThroughRawPostgresReads() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(t TIME,d DATE,z TIMESTAMPTZ_NS); " +
                "INSERT INTO lake.t VALUES(TIME '24:00:00',DATE 'infinity',TIMESTAMPTZ_NS '-infinity')")
            val (id, schema) = f.inline("t")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                assertThat(c.countInlinedRows(id, schema, snapshot)).isEqualTo(1)
                for (column in c.getTableColumns(id, snapshot)) {
                    assertThatThrownBy { c.readInlinedData(id, schema, snapshot, listOf(column)) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java)
                    assertThatThrownBy { c.readInlinedDataDecoded(id, schema, snapshot, listOf(column)) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java)
                }
            }) }
        }
    }
}
