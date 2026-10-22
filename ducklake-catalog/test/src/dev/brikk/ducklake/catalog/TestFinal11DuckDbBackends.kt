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
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.OffsetDateTime
import java.math.BigInteger
import java.math.BigDecimal
import java.sql.SQLException
import java.sql.DriverManager
import java.util.function.Supplier

internal class TestFinal11DuckDbBackends {
    @Nested inner class Local : Contract(Final11ReadRoute.LOCAL_DUCKDB)
    @Nested inner class NativeQuack : Contract(Final11ReadRoute.NATIVE_QUACK)
    @Nested inner class JavaQuack : Contract(Final11ReadRoute.JAVA_QUACK)

    abstract class Contract(private val route: Final11ReadRoute) {
        private fun <T> read(c: JdbcDucklakeCatalog, action: () -> T): T = c.readSession(Supplier { action() })

        @Test
        fun finalInlineScalarDefaultsNestedEvolutionTemporalTextAndCdcAgree() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CREATE TABLE lake.t(id INTEGER,s STRUCT(a INTEGER,b BLOB),ns TIME_NS,z TIMETZ,iv INTERVAL); " +
                    "INSERT INTO lake.t VALUES(1,{'a':10,'b':from_hex('005CFF')},TIME_NS '12:34:56.123456789'," +
                    "TIMETZ '01:02:03+05:30:15',INTERVAL '100:00:00.123456')")
                val inserted = f.snapshot()
                f.native("ALTER TABLE lake.t ADD COLUMN added VARCHAR DEFAULT 'NULL'; ALTER TABLE lake.t RENAME s.a TO renamed; " +
                    "ALTER TABLE lake.t ALTER s TYPE STRUCT(renamed BIGINT,b BLOB); " +
                    "INSERT INTO lake.t VALUES(2,NULL,NULL,NULL,NULL,NULL); DELETE FROM lake.t WHERE id=1")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val columns = c.getTableColumns(table.tableId, snapshot)
                    val versions = c.getInlinedDataInfos(table.tableId, snapshot)
                    val changes = versions.flatMap { c.getInlinedChangesBetweenDecoded(table.tableId, it.schemaVersion, snapshot, snapshot,
                        columns.map { col -> col.columnId }) }
                    val deleted = changes.single { it.endSnapshot == snapshot }
                    assertThat(deleted.rowId).isEqualTo(0)
                    assertThat(deleted.beginSnapshot).isEqualTo(inserted)
                    assertThat(deleted.endSnapshot).isEqualTo(snapshot)
                    val struct = deleted.values[1] as Map<*, *>
                    assertThat(struct["renamed"]).isEqualTo(10L)
                    assertThat(struct["b"] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                    assertThat(deleted.values.drop(2)).containsExactly(LocalTime.of(12, 34, 56, 123456789),
                        OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofHoursMinutesSeconds(5, 30, 15)), DucklakeInterval(0, 0, 360000123456L), "NULL")
                    assertThat(versions.flatMap { c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) })
                        .containsExactly(listOf(2, null, null, null, null, null))
                    val raw = versions.flatMap { c.getInlinedChangesBetween(table.tableId, it.schemaVersion, snapshot, snapshot,
                        columns.map { col -> col.columnId }) }.single { it.endSnapshot == snapshot }
                    assertThat(raw.beginSnapshot).isEqualTo(inserted)
                    assertThat(raw.endSnapshot).isEqualTo(snapshot)
                    assertThat(raw.values.drop(2)).containsExactly("12:34:56.123456789", "01:02:03+05:30:15", "100:00:00.123456", "NULL")
                    assertThat(c.currentSnapshotId).isEqualTo(snapshot)
                    assertThatThrownBy { c.createSchema("forbidden") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                } }
            }
        }

        @Test
        fun richMetadataAndHierarchyHaveActualRouteProvenanceAndPreserveNativePaths() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE SCHEMA lake.a; CREATE SCHEMA lake.a.child; " +
                    "CREATE TABLE lake.a.child.t AS SELECT i FROM range(100) t(i)")
                f.reader().use { c -> read(c) {
                    val caps = c.readMetadata().getReadMetadataCapabilities()
                    assertThat(caps.accessRoute).isEqualTo(route.label)
                    assertThat(caps.engineVersion).isEqualTo("v2.0.0")
                    assertThat(caps.engineSourceId).isEqualTo("d54cbbe3df")
                    assertThat(caps.jdbcDriverVersion).isEqualTo(if (route == Final11ReadRoute.JAVA_QUACK) "0.8.0" else "1.0")
                    assertThat(caps.ducklakeExtensionVersion).isNull() // Metadata server/RO file engine did not load the writer extension.
                    assertThat(caps.quackExtensionVersion).isEqualTo(if (route == Final11ReadRoute.LOCAL_DUCKDB) null else "f96420ed46")
                    assertThat(caps.unsignedExtensionsAllowed).isFalse()
                    assertThat(caps.qualifiedWrites).isFalse()
                    val h = c.readHierarchy()
                    val table = h.findTable(listOf("a", "child"), "t", caps.snapshotId)!!
                    assertThat(table.schema.names).containsExactly("a", "child")
                    val file = h.listRetainedFiles(DucklakeFileKind.DATA, null, 10).single()
                    assertThat(file.owner.table.tableId).isEqualTo(table.table.tableId)
                    assertThat(file.resolvedPath).startsWith(f.data.toString() + "/")
                    assertThat(Files.isRegularFile(Path.of(file.resolvedPath))).isTrue()
                    assertThat(c.readMetadata().getFileReadMetadata(table.table.tableId, caps.snapshotId).single().rowGroupCount).isEqualTo(1)
                    assertThatThrownBy { c.listSchemas(caps.snapshotId) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                } }
            }
        }

        @Test
        fun explicitNativeMigrationRetainsOldValuesAndUnknownMetadata() {
            Final11DuckDbBackendFixture(route, migrate = true).use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7); ALTER TABLE lake.t ADD COLUMN v VARCHAR DEFAULT 'NULL'; " +
                    "CREATE TABLE lake.files(i INTEGER); CALL lake.set_option('data_inlining_row_limit',0,table_name=>'files'); " +
                    "INSERT INTO lake.files SELECT i FROM range(20) t(i)")
                f.native("INSERT INTO lake.t VALUES(8,NULL)") // Native explicit migration; never the reader.
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val columns = c.getTableColumns(table.tableId, snapshot)
                    assertThat(c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                        c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
                    }.sortedBy { it[0] as Int }).containsExactly(listOf(7, "NULL"), listOf(8, null))
                    val files = c.getTable("main", "files", snapshot)!!
                    val metadata = c.readMetadata()
                    assertThat(metadata.getFileReadMetadata(files.tableId, snapshot).single().rowGroupCount).isNull()
                    assertThat(metadata.getFileColumnMetadata(files.tableId, snapshot).map { it.minIsExact }).containsOnlyNulls()
                    assertThat(c.getColumnStats(files.tableId, snapshot).map { it.minValue }).containsOnlyNulls()
                } }
            }
        }

        @Test
        fun malformedPhysicalLayoutsAndUnsupportedTemporalEdgesFailExplicitly() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CREATE TABLE lake.edge(t TIME); INSERT INTO lake.edge VALUES(TIME '24:00:00')")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "edge", snapshot)!!
                    val info = c.getInlinedDataInfos(table.tableId, snapshot).single()
                    val columns = c.getTableColumns(table.tableId, snapshot)
                    assertThatThrownBy { c.readInlinedData(table.tableId, info.schemaVersion, snapshot, columns) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("24:00")
                    assertThatThrownBy { c.readInlinedDataDecoded(table.tableId, info.schemaVersion, snapshot, columns) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java)
                } }
                f.metadata("ALTER TABLE ducklake_schema RENAME parent_schema_id TO hidden_parent")
                f.reader().use { c -> read(c) {
                    assertThatThrownBy { c.readMetadata().getReadMetadataCapabilities() }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("parent_schema_id")
                } }
            }
        }

        @Test
        fun ownedReadSessionsAreMandatoryAndReadsNeverBootstrapEmptyMetadata() {
            Final11DuckDbBackendFixture(route).use { f ->
                if (route == Final11ReadRoute.LOCAL_DUCKDB) {
                    assertThatThrownBy { f.reader() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    assertThat(Files.exists(f.file)).isFalse()
                    DriverManager.getConnection("jdbc:duckdb:${f.file}").use { /* Explicit fixture-created empty database. */ }
                }
                f.reader().use { c -> read(c) {
                    assertThatThrownBy { c.readMetadata().getReadMetadataCapabilities() }.isInstanceOf(RuntimeException::class.java)
                        .hasMessageContaining("ducklake_metadata")
                } }
                assertThat(f.metadataTableCount()).isZero()
                f.native("CREATE TABLE lake.t(i INTEGER)")
                f.reader().use { c ->
                    assertThatThrownBy { c.listSchemas(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
                    assertThatThrownBy { c.readMetadata().getReadMetadataCapabilities() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                }
            }
        }

        @Test
        fun snapshotPinsAndNextSessionRefreshHonorEachRoutesConcurrencyBoundary() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1)")
                f.reader().use { c ->
                    val before = read(c) { c.currentSnapshotId }
                    if (route == Final11ReadRoute.LOCAL_DUCKDB) {
                        // Database-level READ_ONLY cannot coexist with a differently configured RW instance.
                        assertThatThrownBy { publish(f) }.isInstanceOf(SQLException::class.java)
                    } else {
                        read(c) {
                            assertThat(c.currentSnapshotId).isEqualTo(before)
                            publish(f)
                            assertThat(c.currentSnapshotId).isEqualTo(before)
                            val table = c.getTable("main", "t", before)!!
                            assertThat(c.getTableColumns(table.tableId, before).map { it.columnName }).containsExactly("i")
                        }
                        assertPublishedRows(c)
                    }
                }
                if (route == Final11ReadRoute.LOCAL_DUCKDB) publish(f) // Reopen only after the reader releases its file instance.
                f.reader().use(::assertPublishedRows)
            }
        }

        private fun publish(f: Final11DuckDbBackendFixture) {
            f.native("ALTER TABLE lake.t ADD COLUMN added VARCHAR DEFAULT 'NULL'; INSERT INTO lake.t VALUES(2,NULL)")
        }

        private fun assertPublishedRows(c: JdbcDucklakeCatalog) {
            read(c) {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                val columns = c.getTableColumns(table.tableId, snapshot)
                assertThat(columns.map { it.columnName }).containsExactly("i", "added")
                assertThat(c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                    c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
                }.sortedBy { it[0] as Int }).containsExactly(listOf(1, "NULL"), listOf(2, null))
            }
        }

        @Test
        fun nestedTemporalContainersJsonBlobWideNumbersAndNullTypesKeepCanonicalValues() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CREATE TABLE lake.t(xs STRUCT(a INTEGER,ns TIME_NS,bl BLOB)[],mp MAP(VARCHAR,STRUCT(a INTEGER,j JSON))," +
                    "d DATE,tz TIMESTAMPTZ_NS,v DECIMAL(38,5),u UBIGINT,n \"NULL\"); " +
                    "INSERT INTO lake.t VALUES([{'a':1,'ns':TIME_NS '23:59:59.999999999','bl':from_hex('005CFF')},NULL]," +
                    "MAP(['k'],[{'a':2,'j':'{\"v\":\"NULL\",\"n\":null}'::JSON}]),DATE '0001-01-01 (BC)'," +
                    "TIMESTAMPTZ_NS '1969-12-31 23:59:59.999999999+00',12345678901234567890.12345,18446744073709551615,NULL); " +
                    "ALTER TABLE lake.t ALTER xs TYPE STRUCT(a BIGINT,ns TIME_NS,bl BLOB)[]; " +
                    "ALTER TABLE lake.t ALTER mp TYPE MAP(VARCHAR,STRUCT(a BIGINT,j JSON))")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val columns = c.getTableColumns(table.tableId, snapshot)
                    val row = c.getInlinedDataInfos(table.tableId, snapshot).flatMap {
                        c.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns)
                    }.single()
                    val list = row[0] as List<*>
                    val structure = list[0] as Map<*, *>
                    assertThat(structure["a"]).isEqualTo(1L)
                    assertThat(structure["ns"]).isEqualTo(LocalTime.MAX)
                    assertThat(structure["bl"] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                    assertThat(list[1]).isNull()
                    assertThat(row[1]).isEqualTo(mapOf("k" to mapOf("a" to 2L, "j" to "{\"v\":\"NULL\",\"n\":null}")))
                    assertThat(row[2]).isEqualTo(LocalDate.of(0, 1, 1))
                    assertThat(row[3]).isEqualTo(OffsetDateTime.parse("1969-12-31T23:59:59.999999999Z"))
                    assertThat(row[4]).isEqualTo(BigDecimal("12345678901234567890.12345"))
                    assertThat(row[5]).isEqualTo(BigInteger("18446744073709551615"))
                    assertThat(row[6]).isNull()
                } }
            }
        }

        @Test
        fun physicalPrefixImpersonationAndLeafTypeDriftCannotBeCastIntoApparentValidity() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(1),(2)")
                val info = f.inlineInfo("t")
                f.metadata("ALTER TABLE ${info.tableName} RENAME _ducklake_row_id TO row_id; " +
                    "ALTER TABLE ${info.tableName} ADD COLUMN _ducklake_row_id BIGINT DEFAULT 999")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val columns = c.getTableColumns(info.tableId, snapshot)
                    assertThatThrownBy { c.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("metadata layout")
                } }
                f.metadata("ALTER TABLE ${info.tableName} DROP COLUMN _ducklake_row_id; " +
                    "ALTER TABLE ${info.tableName} RENAME row_id TO _ducklake_row_id; ALTER TABLE ${info.tableName} ALTER i TYPE BIGINT")
                f.reader().use { c -> read(c) {
                    val snapshot = c.currentSnapshotId
                    val columns = c.getTableColumns(info.tableId, snapshot)
                    assertThat(c.countInlinedRows(info.tableId, info.schemaVersion, snapshot)).isEqualTo(2)
                    assertThatThrownBy { c.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("physical payload type")
                } }
            }
        }

        @Test
        fun unknownAndDevFormatsRemainRejectedWithoutMigrationOrVersionGuessing() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER)")
                for (version in listOf("1.1-dev1", "1.1-dev2", "2.0")) {
                    f.metadata("UPDATE ducklake_metadata SET value='$version' WHERE key='version'")
                    f.reader().use { c -> read(c) {
                        assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                            .hasMessageContaining("cannot qualify catalog version")
                        assertThatThrownBy { c.readMetadata().getReadMetadataCapabilities() }
                            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("only for final 1.1")
                    } }
                }
            }
        }
    }
}
