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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [DucklakeCatalog.readInlinedDataDecoded] / [InlinedValues] (TODO-rectify-from-eval.md R-D4): one
 * row of every inlinable DuckLake type written by DuckDB into a PostgreSQL-backed catalog — where
 * upstream stores text as BYTEA, temporals / wide integers / nested values as DuckDB text and
 * narrows small integers — decodes to the same canonical Java values the DuckDB-file backend
 * yields from native driver objects.
 */
class TestJdbcDucklakeCatalogInlinedValueDecoding {
    companion object {
        private const val CREATE = """CREATE TABLE %s.test_schema.alltypes (
            b BOOLEAN, i8 TINYINT, i16 SMALLINT, i32 INTEGER, i64 BIGINT, i128 HUGEINT,
            u8 UTINYINT, u16 USMALLINT, u32 UINTEGER, u64 UBIGINT, u128 UHUGEINT,
            f32 FLOAT, f64 DOUBLE, dec DECIMAL(18,3), dec38 DECIMAL(38,10),
            s VARCHAR, bl BLOB, u UUID, d DATE, t TIME, ttz TIMETZ,
            ts TIMESTAMP, tss TIMESTAMP_S, tsms TIMESTAMP_MS, tsns TIMESTAMP_NS, tstz TIMESTAMPTZ, iv INTERVAL,
            st STRUCT(a INTEGER, b VARCHAR, mid STRUCT(x DATE)), li INTEGER[], mp MAP(VARCHAR, INTEGER),
            ls VARCHAR[], js JSON, nulls INTEGER)"""
        private const val INSERT = """INSERT INTO %s.test_schema.alltypes VALUES (
            true, -8, -16, -32, -64, 170141183460469231731687303715884105727,
            255, 65535, 4294967295, 18446744073709551615, 340282366920938463463374607431768211455,
            1.5, 2.25, 12345.678, 1234567890123456789012345678.0123456789,
            'héllo ''q'' [x, y] {z}', '\x00\x01\xFF'::BLOB, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', DATE '2024-02-29',
            TIME '12:34:56.123456', TIMETZ '12:34:56.123456+02',
            TIMESTAMP '2024-02-29 12:34:56.123456', TIMESTAMP_S '2024-02-29 12:34:56', TIMESTAMP_MS '2024-02-29 12:34:56.123',
            TIMESTAMP_NS '2024-02-29 12:34:56.123456789', TIMESTAMPTZ '2024-02-29 12:34:56.123456+02',
            INTERVAL '1 year 2 months 3 days 04:05:06.007',
            {'a': 1, 'b': 'x, y', 'mid': {'x': DATE '2000-01-02'}}, [1, 2, NULL], MAP {'k1': 1, 'k2': NULL},
            ['plain', 'it''s', '', NULL, 'a,b', 'sp ace', 'NULL'], '{"j": [1, 2]}', NULL)"""

        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var pgCatalog: JdbcDucklakeCatalog
        private lateinit var duckCatalog: JdbcDucklakeCatalog
        private lateinit var duckFixture: TestingDucklakeLocalDuckDbCatalogFixture

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "inlined-decoding")
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.createStatement().use { st ->
                    st.execute("INSTALL ducklake")
                    st.execute("LOAD ducklake")
                    st.execute("INSTALL postgres")
                    st.execute("LOAD postgres")
                    st.execute(
                        "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                            isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
                    )
                    st.execute(CREATE.format("lake"))
                    st.execute(INSERT.format("lake"))
                }
            }
            pgCatalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = isolated.jdbcUrl
                    catalogDatabaseUser = isolated.user
                    catalogDatabasePassword = isolated.password
                    dataPath = isolated.dataDir.toAbsolutePath().toString()
                    maxCatalogConnections = 3
                },
            )

            // Same table on a DuckDB-file catalog: native driver objects instead of text/BYTEA.
            duckFixture = TestingDucklakeLocalDuckDbCatalogFixture()
            val dir = duckFixture.catalogDirectory("inlined-decoding")
            Files.createDirectories(dir)
            val file = dir.resolve("lake.db")
            val data = dir.resolve("data").also { Files.createDirectories(it) }
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.createStatement().use { st ->
                    st.execute("INSTALL ducklake")
                    st.execute("LOAD ducklake")
                    st.execute("ATTACH 'ducklake:${file.toAbsolutePath()}' AS dl (DATA_PATH '${data.toAbsolutePath()}')")
                    st.execute("CREATE SCHEMA dl.test_schema")
                    st.execute(CREATE.format("dl"))
                    st.execute(INSERT.format("dl"))
                    st.execute("DETACH dl")
                }
            }
            duckCatalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = "jdbc:duckdb:${file.toAbsolutePath()}"
                    dataPath = data.toAbsolutePath().toString()
                    maxCatalogConnections = 2
                },
            )
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::pgCatalog.isInitialized) pgCatalog.close()
            if (::duckCatalog.isInitialized) duckCatalog.close()
            if (::duckFixture.isInitialized) duckFixture.close()
            if (::server.isInitialized) server.close()
        }

        private fun decodedRow(catalog: JdbcDucklakeCatalog): Map<String, Any?> {
            val snapshot = catalog.currentSnapshotId
            val table = catalog.getTable("test_schema", "alltypes", snapshot)!!
            val cols = catalog.getTableColumns(table.tableId, snapshot)
            val sv = catalog.getInlinedDataInfos(table.tableId, snapshot).single().schemaVersion
            val row = catalog.readInlinedDataDecoded(table.tableId, sv, snapshot, cols).single()
            return cols.indices.associate { cols[it].columnName to row[it] }
        }
    }

    private fun assertCanonical(row: Map<String, Any?>) {
        assertThat(row["b"]).isEqualTo(true)
        assertThat(row["i8"]).isEqualTo(-8)
        assertThat(row["i16"]).isEqualTo(-16)
        assertThat(row["i32"]).isEqualTo(-32)
        assertThat(row["i64"]).isEqualTo(-64L)
        assertThat(row["i128"]).isEqualTo(BigInteger("170141183460469231731687303715884105727"))
        assertThat(row["u8"]).isEqualTo(255L)
        assertThat(row["u16"]).isEqualTo(65535L)
        assertThat(row["u32"]).isEqualTo(4294967295L)
        assertThat(row["u64"]).isEqualTo(BigInteger("18446744073709551615"))
        assertThat(row["u128"]).isEqualTo(BigInteger("340282366920938463463374607431768211455"))
        assertThat(row["f32"]).isEqualTo(1.5f)
        assertThat(row["f64"]).isEqualTo(2.25)
        assertThat((row["dec"] as BigDecimal).compareTo(BigDecimal("12345.678"))).isZero()
        assertThat((row["dec38"] as BigDecimal).compareTo(BigDecimal("1234567890123456789012345678.0123456789"))).isZero()
        assertThat(row["s"]).isEqualTo("héllo 'q' [x, y] {z}")
        assertThat(row["bl"] as ByteArray).containsExactly(0, 1, 0xFF.toByte())
        assertThat(row["u"]).isEqualTo(UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"))
        assertThat(row["d"]).isEqualTo(LocalDate.of(2024, 2, 29))
        assertThat(row["t"]).`as`("time keeps microseconds").isEqualTo(LocalTime.of(12, 34, 56, 123_456_000))
        assertThat((row["ttz"] as OffsetTime).withOffsetSameInstant(ZoneOffset.UTC))
            .`as`("timetz keeps micros and the instant").isEqualTo(OffsetTime.of(10, 34, 56, 123_456_000, ZoneOffset.UTC))
        assertThat(row["ts"]).isEqualTo(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000))
        assertThat(row["tss"]).isEqualTo(LocalDateTime.of(2024, 2, 29, 12, 34, 56))
        assertThat(row["tsms"]).isEqualTo(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_000_000))
        assertThat(row["tsns"]).isEqualTo(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_789))
        assertThat(row["tstz"]).isEqualTo(OffsetDateTime.of(2024, 2, 29, 10, 34, 56, 123_456_000, ZoneOffset.UTC))
        assertThat(row["iv"]).isEqualTo(DucklakeInterval(14, 3, 4 * 3_600_000_000L + 5 * 60_000_000L + 6_007_000L))
        assertThat(row["st"]).isEqualTo(
            linkedMapOf("a" to 1, "b" to "x, y", "mid" to linkedMapOf("x" to LocalDate.of(2000, 1, 2))),
        )
        assertThat(row["li"]).isEqualTo(listOf(1, 2, null))
        assertThat(row["mp"]).isEqualTo(linkedMapOf("k1" to 1, "k2" to null))
        assertThat(row["ls"]).isEqualTo(listOf("plain", "it's", "", null, "a,b", "sp ace", "NULL"))
        assertThat(row["js"]).isEqualTo("""{"j": [1, 2]}""")
        assertThat(row["nulls"]).isNull()
    }

    @Test
    fun postgresBackedRowsDecodeToCanonicalValues() {
        // Note: DuckDB's own postgres writer renders the DATE inside `st.mid` as `CAST('2000-01-02' AS
        // VARCHAR)` in the struct text and then cannot read the row back itself (INTERNAL error,
        // upstream defect). The decoder unwraps the literal, so this library reads what DuckDB wrote.
        assertCanonical(decodedRow(pgCatalog))
    }

    @Test
    fun duckDbBackedRowsDecodeToTheSameCanonicalValues() {
        assertCanonical(decodedRow(duckCatalog))
    }

    @Test
    fun rawReadIsUnchangedAndChangeFeedDecodesToo() {
        val snapshot = pgCatalog.currentSnapshotId
        val table = pgCatalog.getTable("test_schema", "alltypes", snapshot)!!
        val cols = pgCatalog.getTableColumns(table.tableId, snapshot)
        val sv = pgCatalog.getInlinedDataInfos(table.tableId, snapshot).single().schemaVersion
        val raw = pgCatalog.readInlinedData(table.tableId, sv, snapshot, cols).single()
        assertThat(raw[cols.indexOfFirst { it.columnName == "s" }]).`as`("raw form is the backend's (BYTEA)").isInstanceOf(ByteArray::class.java)
        assertThat(raw[cols.indexOfFirst { it.columnName == "d" }]).isInstanceOf(String::class.java)

        val changes = pgCatalog.getInlinedChangesBetweenDecoded(table.tableId, sv, snapshot, snapshot, cols.map { it.columnId })
        assertThat(changes).hasSize(1)
        assertCanonical(cols.indices.associate { cols[it].columnName to changes.single().values[it] })
    }
}
