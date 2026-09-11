package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

class TestGeometryComparison {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private var database = 0

        @BeforeAll
        @JvmStatic
        fun start() { server = TestingDucklakePostgreSqlCatalogServer() }

        @AfterAll
        @JvmStatic
        fun stop() { server.close() }
    }

    private fun newMetadata(@Suppress("UNUSED_PARAMETER") original: String): String {
        val name = "geometry_${database++}"
        server.createDatabase(name)
        return server.getDuckDbAttachUri(name).removePrefix("ducklake:")
    }

    @Test
    fun `typed geometry rendering matches native text without changing blobs or nulls`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val expressions = listOf(
                "'POINT(1 2)'::GEOMETRY",
                "['POINT(1 2)'::GEOMETRY, NULL, 'LINESTRING(0 0, 1 1)'::GEOMETRY]",
                "MAP([1, 2], ['POINT(1 2)'::GEOMETRY, NULL])",
                "{'a': 'POINT(1 2)'::GEOMETRY, 'b': '\\x01'::BLOB}",
                "[{'shape': NULL::GEOMETRY, 'GEOMETRY': 'NULL', 'b': '\\x01'::BLOB}, NULL]",
                "NULL::STRUCT(g GEOMETRY, b BLOB)",
                "[NULL, 'POINT EMPTY'::GEOMETRY]::GEOMETRY[2]",
                "{'GEOMETRY': '\\x01'::BLOB, 'ordinary': 'POINT(1 2)'}",
                "'\\x01'::BLOB", "NULL::BLOB", "[NULL::BLOB, 'NULL'::BLOB]",
            )
            for (expression in expressions) {
                val sql = "SELECT $expression AS g"
                val expected = connection.createStatement().use { st ->
                    st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) }
                }
                val actual = connection.createStatement().use { st ->
                    st.executeQuery(sql).use(GoldenComparator::readRows)
                }
                assertThat(actual).`as`(expression).isEqualTo(listOf(listOf(expected)))
            }
        }
    }

    @Test
    fun `nightly nested geometry fixtures pass golden and binary mirror stages`() {
        val root = Path.of("ducklake")
        for (shape in listOf("list", "map", "struct")) {
            val path = "geo/ducklake_geometry_nested_$shape.test"
            val file = SltParser.parse(path, Files.readString(root.resolve("test/sql/$path")))
            for (nativeText in listOf(false, true)) BinaryMirror(nativeText).use { engine ->
                val result = ReplayDriver(engine, repoRoot = root, metadataRewriter = ::newMetadata).replay(file)
                assertThat(result.fileSkipReason).isNull()
                assertThat(result.failed).`as`(path).isEmpty()
                assertThat(result.skipped).isEmpty()
                assertThat(engine.queries).`as`("must reach the mirror after golden validation").isEqualTo(1)
            }
        }
    }

    @Test
    fun `mirror handles nested null containers quoted fields arrays and geometry map keys`() {
        val expressions = listOf(
            "'POINT Z (1 2 3)'::GEOMETRY",
            "[NULL, 'POINT EMPTY'::GEOMETRY]::GEOMETRY[2]",
            "{'a,b\"x': [NULL::STRUCT(g GEOMETRY), {'g': 'POINT(1 2)'::GEOMETRY}], " +
                "'GEOMETRY': 'payload'::BLOB, 'other': MAP([1], ['POINT EMPTY'::GEOMETRY])}",
            "MAP(['POINT(1 2)'::GEOMETRY], ['\\x01'::BLOB])",
            "[NULL::GEOMETRY[], []::GEOMETRY[]]",
            "NULL::STRUCT(g GEOMETRY)",
        )
        DriverManager.getConnection("jdbc:duckdb:").use { reference ->
            for (expression in expressions) {
                val expected = reference.createStatement().use { st ->
                    st.executeQuery("SELECT $expression").use { rs -> rs.next(); GoldenComparator.toGoldenCell(rs.getString(1)) }
                }
                BinaryMirror().use { engine ->
                    val result = replayExpression(engine, expression, expected)
                    assertThat(result.failed).`as`(expression).isEmpty()
                    assertThat(result.skipped).isEmpty()
                    assertThat(engine.queries).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `WKB byte order is normalized but malformed geometry never becomes null`() {
        val bigEndian = "00000000013ff00000000000004000000000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val cells = listOf(listOf(GoldenComparator.renderCell(bigEndian)))
            assertThat(GeometryNormalizer.normalizeRows(connection, listOf("GEOMETRY"), cells))
                .isEqualTo(listOf(listOf("POINT (1 2)")))
            assertThat(GeometryNormalizer.normalizeRows(connection, listOf("BLOB"), cells)).isEqualTo(cells)
            assertThatThrownBy {
                GeometryNormalizer.normalizeRows(connection, listOf("GEOMETRY"), listOf(listOf("\\x01")))
            }.isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `mirror normalizes geometry but still detects incorrect geometry blobs and nulls`() {
        val expression = "{'g': ['POINT(1 2)'::GEOMETRY, NULL], 'b': 'payload'::BLOB, 's': 'NULL'}"
        val expected = "{'g': ['POINT (1 2)', NULL], 'b': payload, 's': 'NULL'}"
        for (replacement in listOf(null, "geometry", "blob", "null")) {
            BinaryMirror { sql ->
                when (replacement) {
                    "geometry" -> sql.replace("POINT(1 2)", "POINT(9 9)")
                    "blob" -> sql.replace("'payload'::BLOB", "'changed'::BLOB")
                    "null" -> sql.replace("GEOMETRY, NULL", "GEOMETRY, 'POINT(1 2)'::GEOMETRY")
                    else -> sql
                }
            }.use { engine ->
                val result = replayExpression(engine, expression, expected)
                assertThat(result.fileSkipReason).isNull()
                assertThat(engine.queries).isEqualTo(1)
                assertThat(result.skipped).isEmpty()
                if (replacement == null) assertThat(result.failed).isEmpty()
                else assertThat(result.failed.single().reason).contains("diverged from oracle")
            }
        }
    }

    private fun replayExpression(engine: ReplayReadEngine, expression: String, expected: String): FileResult {
        val slt = """
            require ducklake

            statement ok
            ATTACH 'ducklake:__TEST_DIR__/meta.db' AS lake (DATA_PATH '__TEST_DIR__/data')

            query T
            SELECT * FROM (SELECT $expression AS g)
            ----
            $expected
        """.trimIndent() + "\n"
        return ReplayDriver(engine, metadataRewriter = ::newMetadata).replay(SltParser.parse("geometry-mirror.test", slt))
    }

    /** Reads the same lake but renders geometry as ordinary WKB/BLOB, like the Trino adapter. */
    private class BinaryMirror(
        private val nativeText: Boolean = false,
        private val rewrite: (String) -> String = { it },
    ) : ReplayReadEngine {
        override val name = "binary-geometry-control"
        private val connection = DriverManager.getConnection("jdbc:duckdb:")
        var queries = 0

        override fun connect(attachment: OracleAttachment) {
            connection.createStatement().use { st ->
                st.execute("LOAD ducklake")
                st.execute("ATTACH 'ducklake:${attachment.metadataUri.replace("'", "''")}' AS ${attachment.catalogAlias} " +
                    "(DATA_PATH '${attachment.dataPath.replace("'", "''")}')")
                st.execute("USE ${attachment.catalogAlias}")
            }
        }

        override fun accepts(sql: String): Boolean = sql.trim().startsWith("select * from", ignoreCase = true)

        override fun executeQuery(sql: String): List<List<String?>> {
            queries++
            return connection.createStatement().use { st ->
                st.executeQuery(rewrite(sql)).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map {
                            if (nativeText) rs.getString(it) else GoldenComparator.renderCell(rs.getObject(it))
                        })
                    }
                }
            }
        }

        override fun close() = connection.close()
    }
}
