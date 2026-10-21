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
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** Actual final-1.1 schemas/physical values; no replacement of native rows with observed goldens. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestFinal11Evolution {
    private lateinit var server: PreviewQuackServer
    private lateinit var reader: JdbcDucklakeCatalog
    private val sequence = AtomicInteger()

    @BeforeAll
    fun start() {
        server = PreviewQuackServer("1.1", "1.1-evolution").also { it.start() }
        reader = server.catalog(enableFinal11Reads = true)
    }

    @AfterAll
    fun close() {
        try { if (::reader.isInitialized) reader.close() }
        finally { if (::server.isInitialized) server.close() }
    }

    private fun name() = "evolved_${sequence.incrementAndGet()}"
    private fun <T> read(action: () -> T): T = reader.readSession(Supplier { action() })
    private fun table(name: String) = reader.getTable("main", name, reader.currentSnapshotId)!!
    private fun rows(tableId: Long, snapshot: Long): List<List<Any?>> {
        val columns = reader.getTableColumns(tableId, snapshot)
        return reader.getInlinedDataInfos(tableId, snapshot).flatMap {
            reader.readInlinedDataDecoded(tableId, it.schemaVersion, snapshot, columns)
        }.sortedBy { (it[0] as Number).toLong() }
    }

    @Test
    fun scalarCastsUseTheTargetSnapshotWithoutChangingOriginalCdcIdentity() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,n SMALLINT,f FLOAT,d DECIMAL(5,2),u UINTEGER,ts TIMESTAMP); " +
            "INSERT INTO lake.$name VALUES (1,42,0.1,1.25,4294967295,TIMESTAMP '2020-01-01 12:34:56.123456')")
        val inserted = read { reader.currentSnapshotId }
        server.native("ALTER TABLE lake.$name ALTER n TYPE INTEGER; ALTER TABLE lake.$name ALTER f TYPE DOUBLE; " +
            "ALTER TABLE lake.$name ALTER d TYPE DECIMAL(10,4); ALTER TABLE lake.$name ALTER u TYPE UBIGINT; " +
            "ALTER TABLE lake.$name ALTER ts TYPE TIMESTAMP_NS")
        val evolved = read { reader.currentSnapshotId }
        val expected = listOf(1, 42, 0.10000000149011612, BigDecimal("1.2500"), BigInteger("4294967295"),
            LocalDateTime.parse("2020-01-01T12:34:56.123456"))
        read {
            val table = table(name)
            assertThat(rows(table.tableId, evolved)).containsExactly(expected)
            assertThat(rows(table.tableId, inserted)).containsExactly(listOf(1, 42, 0.1f, BigDecimal("1.25"), 4294967295L,
                LocalDateTime.parse("2020-01-01T12:34:56.123456")))
        }
        server.native("DELETE FROM lake.$name WHERE id=1")
        read {
            val table = table(name)
            val snapshot = reader.currentSnapshotId
            val columns = reader.getTableColumns(table.tableId, snapshot)
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).first()
            val change = reader.getInlinedChangesBetweenDecoded(table.tableId, info.schemaVersion, snapshot, snapshot,
                columns.map { it.columnId }).single()
            assertThat(change.values).isEqualTo(expected)
            assertThat(change.rowId).isEqualTo(0L)
            assertThat(change.beginSnapshot).isEqualTo(inserted)
            assertThat(change.endSnapshot).isEqualTo(snapshot)
            assertThat(reader.getInlinedChangesBetween(table.tableId, info.schemaVersion, snapshot, snapshot,
                columns.map { it.columnId }).single().values).isEqualTo(expected.dropLast(1) + "2020-01-01 12:34:56.123456")
            assertThat(rows(table.tableId, snapshot)).isEmpty()
        }
    }

    @Test
    fun structFieldsFollowIdsAcrossAddRenameDropReorderAndReusedNames() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,s STRUCT(a INTEGER,b VARCHAR)); " +
            "INSERT INTO lake.$name VALUES (1,{'a':10,'b':'old'}),(2,NULL),(3,{'a':NULL,'b':NULL})")
        val original = read { reader.currentSnapshotId }
        server.native("ALTER TABLE lake.$name ADD COLUMN s.c INTEGER DEFAULT 42; " +
            "ALTER TABLE lake.$name RENAME s.a TO renamed; " +
            "ALTER TABLE lake.$name ALTER s TYPE STRUCT(b VARCHAR,renamed BIGINT,c INTEGER); " +
            "ALTER TABLE lake.$name DROP COLUMN s.b; ALTER TABLE lake.$name ADD COLUMN s.b VARCHAR DEFAULT 'new'; " +
            "INSERT INTO lake.$name VALUES (4,{'renamed':20,'c':99,'b':NULL})")
        read {
            val table = table(name)
            val snapshot = reader.currentSnapshotId
            val before = reader.getAllColumnsWithParentage(table.tableId, original).single { it.columnName == "b" }
            val after = reader.getAllColumnsWithParentage(table.tableId, snapshot).single { it.columnName == "b" }
            assertThat(after.columnId).isNotEqualTo(before.columnId)
            assertThat(rows(table.tableId, snapshot)).containsExactly(
                listOf(1, linkedMapOf("renamed" to 10L, "c" to 42, "b" to "new")), listOf(2, null),
                listOf(3, linkedMapOf("renamed" to null, "c" to 42, "b" to "new")),
                listOf(4, linkedMapOf<String, Any?>("renamed" to 20L, "c" to 99, "b" to null)))
            assertThat(rows(table.tableId, original)).containsExactly(
                listOf(1, linkedMapOf("a" to 10, "b" to "old")), listOf(2, null), listOf(3, linkedMapOf("a" to null, "b" to null)))
        }
        assertThat(server.native("SELECT id,s.renamed,s.c,s.b FROM lake.$name ORDER BY id")).containsExactly(
            listOf("1", "10", "42", "new"), listOf("2", null, null, null),
            listOf("3", null, "42", "new"), listOf("4", "20", "99", null))
    }

    @Test
    fun nestedListAndMapConversionsPreserveEmptyAndNullContainersAndElements() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,xs STRUCT(a INTEGER,b VARCHAR)[],mp MAP(STRUCT(k INTEGER),STRUCT(a INTEGER,b VARCHAR))); " +
            "INSERT INTO lake.$name VALUES (1,[{'a':1,'b':'x'},NULL,{'a':NULL,'b':NULL}]," +
            "MAP([{'k':7},{'k':8}],[{'a':2,'b':'m'},NULL])),(2,NULL,NULL),(3,[],MAP([],[])); " +
            "ALTER TABLE lake.$name ALTER xs TYPE STRUCT(b VARCHAR,a BIGINT,c INTEGER)[]; " +
            "ALTER TABLE lake.$name ALTER mp TYPE MAP(STRUCT(k BIGINT),STRUCT(b VARCHAR,a BIGINT,c INTEGER))")
        read {
            val table = table(name)
            assertThat(rows(table.tableId, reader.currentSnapshotId)).containsExactly(
                listOf(1, listOf(linkedMapOf("a" to 1L, "b" to "x", "c" to null), null,
                    linkedMapOf("a" to null, "b" to null, "c" to null)),
                    linkedMapOf(mapOf("k" to 7L) to linkedMapOf("a" to 2L, "b" to "m", "c" to null), mapOf("k" to 8L) to null)),
                listOf(2, null, null), listOf(3, emptyList<Any?>(), emptyMap<Any?, Any?>()))
        }
        assertThat(server.native("SELECT id,CAST(xs AS VARCHAR),CAST(mp AS VARCHAR) FROM lake.$name ORDER BY id")).containsExactly(
            listOf("1", "[{'a': 1, 'b': x, 'c': NULL}, NULL, {'a': NULL, 'b': NULL, 'c': NULL}]",
                "{{'k': 7}={'a': 2, 'b': m, 'c': NULL}, {'k': 8}=NULL}"),
            listOf("2", null, null), listOf("3", "[]", "{}"))
    }

    @Test
    fun aPhysicalLeafTypeMismatchIsNotHiddenByAValueCast() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,s STRUCT(a INTEGER,b INTEGER)); INSERT INTO lake.$name VALUES (1,{'a':10,'b':20})")
        val info = read {
            val table = table(name)
            reader.getInlinedDataInfos(table.tableId, reader.currentSnapshotId).single()
        }
        server.connection().use { raw -> raw.createStatement().use { it.execute("ALTER TABLE ${info.tableName} ALTER s TYPE STRUCT(a BIGINT,b INTEGER)") } }
        try {
            assertThatThrownBy { read {
                val snapshot = reader.currentSnapshotId
                reader.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, reader.getTableColumns(info.tableId, snapshot))
            } }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("physical payload type")
        }
        finally {
            server.connection().use { raw -> raw.createStatement().use {
                it.execute("ALTER TABLE ${info.tableName} ALTER s TYPE STRUCT(a INTEGER,b INTEGER)")
            } }
        }
    }

    @Test
    fun invalidCastValuesFailForRawDecodedOrdinaryAndCdcReadsInsteadOfBecomingNull() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,v DATE); INSERT INTO lake.$name VALUES (1,DATE '1000000-01-01'); " +
            "ALTER TABLE lake.$name ALTER v TYPE TIMESTAMP")
        val info = read {
            val table = table(name)
            reader.getInlinedDataInfos(table.tableId, reader.currentSnapshotId).first()
        }
        for (decoded in listOf(false, true)) {
            assertThatThrownBy { read {
                val snapshot = reader.currentSnapshotId
                val columns = reader.getTableColumns(info.tableId, snapshot)
                if (decoded) reader.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns)
                else reader.readInlinedData(info.tableId, info.schemaVersion, snapshot, columns)
            } }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("cast")
            assertThatThrownBy { read {
                val snapshot = reader.currentSnapshotId
                val ids = reader.getTableColumns(info.tableId, snapshot).map { it.columnId }
                if (decoded) reader.getInlinedChangesBetweenDecoded(info.tableId, info.schemaVersion, 0, snapshot, ids)
                else reader.getInlinedChangesBetween(info.tableId, info.schemaVersion, 0, snapshot, ids)
            } }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("cast")
        }
        assertThatThrownBy { server.native("SELECT v FROM lake.$name") }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cast")
        read { assertThat(reader.countInlinedRows(info.tableId, info.schemaVersion, reader.currentSnapshotId)).isEqualTo(1L) }
    }

    @Test
    fun shippingNonliteralAddedFieldHistoryIsRejectedRatherThanInventingNull() {
        val name = name()
        // This is the shipping-native #93 defect, not a claim that the ALTER should succeed.
        // The pristine newer upstream gate separately requires the ALTER to be rejected natively.
        server.native("CREATE TABLE lake.$name(id INTEGER,s STRUCT(a INTEGER)); INSERT INTO lake.$name VALUES(1,{'a':1}); " +
            "ALTER TABLE lake.$name ADD COLUMN s.f INTEGER DEFAULT 1+1")
        assertThatThrownBy { read {
            val table = table(name)
            rows(table.tableId, reader.currentSnapshotId)
        } }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("non-literal initial-default history")
    }

    @Test
    fun aLaterExpressionInsertDefaultDoesNotInvalidateAnInitiallyNullAddedColumn() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER); INSERT INTO lake.$name VALUES(1); " +
            "ALTER TABLE lake.$name ADD COLUMN v INTEGER; ALTER TABLE lake.$name ALTER v SET DEFAULT 1+1; " +
            "INSERT INTO lake.$name(id) VALUES(2)")
        read {
            val table = table(name)
            assertThat(rows(table.tableId, reader.currentSnapshotId)).containsExactly(listOf(1, null), listOf(2, 2))
        }
    }

    @Test
    fun quotedFieldNamesAndLambdaLikeColumnNamesDoNotChangeBindings() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,\"_catalog_read_element_0\" " +
            "STRUCT(\"a,b\" INTEGER,\"it's \"\"field\"\"\" VARCHAR,\"É\" INTEGER,\"é\" INTEGER)[]); " +
            "INSERT INTO lake.$name VALUES (1,[{'a,b':10,'it''s \"field\"':'value','É':1,'é':2},NULL]); " +
            "ALTER TABLE lake.$name ALTER \"_catalog_read_element_0\" TYPE " +
            "STRUCT(\"it's \"\"field\"\"\" VARCHAR,\"a,b\" BIGINT,\"É\" BIGINT,\"é\" INTEGER,\"new.dot\" VARCHAR)[]")
        read {
            val table = table(name)
            assertThat(rows(table.tableId, reader.currentSnapshotId)).containsExactly(listOf(1, listOf(
                linkedMapOf("a,b" to 10L, "it's \"field\"" to "value", "É" to 1L, "é" to 2, "new.dot" to null), null)))
        }
    }

    @Test
    fun qualifiedWideningBoundariesUseNativeNumericAndTemporalSemantics() {
        val cases = listOf(
            listOf("BOOLEAN", "true", "BIGINT", 1L),
            listOf("TINYINT", "-128", "SMALLINT", -128),
            listOf("UTINYINT", "255", "SMALLINT", 255),
            listOf("UBIGINT", "18446744073709551615", "HUGEINT", BigInteger("18446744073709551615")),
            listOf("UINTEGER", "4294967295", "UBIGINT", BigInteger("4294967295")),
            listOf("INTEGER", "2147483647", "DECIMAL(12,2)", BigDecimal("2147483647.00")),
            listOf("DECIMAL(5,2)", "1.25", "FLOAT", 1.25f),
            listOf("DATE", "DATE '2020-01-01'", "TIMESTAMP_NS", LocalDateTime.parse("2020-01-01T00:00:00")),
            listOf("TIMESTAMP_S", "TIMESTAMP_S '2020-01-01 12:34:56'", "TIMESTAMP_MS", LocalDateTime.parse("2020-01-01T12:34:56")),
        )
        for (case in cases) {
            val name = name()
            server.native("CREATE TABLE lake.$name(id INTEGER,v ${case[0]}); INSERT INTO lake.$name VALUES(1,${case[1]}); " +
                "ALTER TABLE lake.$name ALTER v TYPE ${case[2]}")
            read {
                val table = table(name)
                assertThat(rows(table.tableId, reader.currentSnapshotId)).`as`("${case[0]} to ${case[2]}")
                    .containsExactly(listOf(1, case[3]))
            }
        }
    }

    @Test
    fun nestedBlobBytesLiteralNullStringsAndCdcSurviveRenameAndEvolution() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,s STRUCT(b BLOB,v VARCHAR)); " +
            "INSERT INTO lake.$name VALUES (1,{'b':from_hex('005CFF27'),'v':'NULL'}),(2,NULL),(3,{'b':NULL,'v':NULL})")
        val inserted = read { reader.currentSnapshotId }
        server.native("ALTER TABLE lake.$name ADD COLUMN s.n INTEGER DEFAULT 42; ALTER TABLE lake.$name RENAME s.b TO payload; " +
            "ALTER TABLE lake.$name ALTER s TYPE STRUCT(v VARCHAR,payload BLOB,n BIGINT)")
        read {
            val table = table(name)
            val values = rows(table.tableId, reader.currentSnapshotId)
            val first = values[0][1] as Map<*, *>
            assertThat(first["payload"] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte(), 0x27)
            assertThat(first["v"]).isEqualTo("NULL")
            assertThat(first["n"]).isEqualTo(42L)
            assertThat(values[1]).containsExactly(2, null)
            assertThat(values[2]).containsExactly(3, linkedMapOf("payload" to null, "v" to null, "n" to 42L))
        }
        server.native("DELETE FROM lake.$name WHERE id=1")
        read {
            val table = table(name)
            val snapshot = reader.currentSnapshotId
            val ids = reader.getTableColumns(table.tableId, snapshot).map { it.columnId }
            val info = reader.getInlinedDataInfos(table.tableId, snapshot).first()
            val change = reader.getInlinedChangesBetweenDecoded(table.tableId, info.schemaVersion, snapshot, snapshot, ids).single()
            val value = change.values[1] as Map<*, *>
            assertThat(value["payload"] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte(), 0x27)
            assertThat(value["v"]).isEqualTo("NULL")
            assertThat(value["n"]).isEqualTo(42L)
            assertThat(change.rowId).isEqualTo(0L)
            assertThat(change.beginSnapshot).isEqualTo(inserted)
            assertThat(change.endSnapshot).isEqualTo(snapshot)
        }
    }

    @Test
    fun malformedColumnParentageFailsBeforePublicTypeReconstructionAndDoesNotRemainCached() {
        val name = name()
        server.native("CREATE TABLE lake.$name(id INTEGER,s STRUCT(a INTEGER)); INSERT INTO lake.$name VALUES(1,{'a':2})")
        val table = read { table(name) }
        val snapshot = read { reader.currentSnapshotId }
        val original = read { reader.getAllColumnsWithParentage(table.tableId, snapshot).single { it.columnName == "a" } }
        fun update(sql: String) = server.connection().use { raw -> raw.createStatement().use { it.execute(sql) } }
        for (mutation in listOf("parent_column=column_id", "column_id=NULL", "column_order=NULL", "nulls_allowed=NULL")) {
            try {
                update("UPDATE ducklake_column SET $mutation WHERE table_id=${table.tableId} AND column_name='a'")
                assertSchemaCorruption(table.tableId, snapshot)
            }
            finally {
                update("UPDATE ducklake_column SET parent_column=${original.parentColumn},column_id=${original.columnId}," +
                    "column_order=${original.columnOrder},nulls_allowed=TRUE WHERE table_id=${table.tableId} AND column_name='a'")
            }
        }
        read {
            assertThat(rows(table.tableId, snapshot)).containsExactly(listOf(1, mapOf("a" to 2)))
            assertThat(reader.currentSnapshotId).isEqualTo(snapshot)
        }
    }

    private fun assertSchemaCorruption(tableId: Long, snapshot: Long) {
        assertThatThrownBy { read { reader.getTableColumns(tableId, snapshot) } }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThatThrownBy { read { reader.getAllColumnsWithParentage(tableId, snapshot) } }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
    }
}
