package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.duckdb.DuckDBDriver
import java.nio.file.Path
import java.sql.DriverManager

/** Driver2.0.0.1 changes TUPLE objects, not their native text or the SLT golden contract. */
internal class TestTupleResultRendering {
    private fun requirePatchedGetters() {
        // A deliberately reverted dependency must fail safely, not crash this test worker.
        val jar = Path.of(DuckDBDriver::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(jar.fileName.toString()).isEqualTo("duckdb_jdbc-2.0.0.1.jar")
    }
    private fun verify(expression: String, expected: String) {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT $expression,CAST($expression AS VARCHAR)").use { r ->
                assertThat(r.next()).isTrue()
                assertThat(r.getString(2)).isEqualTo(expected) // independent native fixture validation
                val value = r.getObject(1)
                assertThat(GoldenComparator.renderCell(value)).isEqualTo(expected)
                assertThat(GoldenComparator.renderNested(value)).isEqualTo(expected)
                assertThat(r.next()).isFalse()
            }
        } }
    }

    @Test fun scalarTupleRetainsPositionalParentheses() = verify("row(1,'x')", "(1, x)")
    @Test fun nestedTuplesAndListsRetainTheirCompoundShape() =
        verify("row(row(1,'x'),[2,NULL],NULL::VARCHAR)", "((1, x), [2, NULL], NULL)")
    @Test fun nullTupleChildrenAreNotStringsOrNamedStructFields() = verify("row(NULL::INTEGER,NULL::VARCHAR)", "(NULL, NULL)")
    @Test fun tupleListPreservesNullContainersAndPositionalMembers() = verify("[row(1,'x'),NULL]", "[(1, x), NULL]")
    @Test fun fixedTupleArrayUsesTheSameNativeText() = verify("array_value(row(1,'x'),NULL)", "[(1, x), NULL]")
    @Test fun tupleMapValuesKeepParentheses() = verify("MAP {'k':row(1,'x'),'n':NULL}", "{k=(1, x), n=NULL}")
    @Test fun tupleMapKeysRetainTheirPositionalShape() = verify("MAP {row(1,'x'):'value'}", "{(1, x)=value}")
    @Test fun aUnionTuplePayloadRetainsItsPositionalShape() = verify("union_value(t:=row(1,'x'))", "(1, x)")
    @Test fun namedStructWithTupleLikeNamesStaysANamedStruct() =
        verify("struct_pack(element1:=1,element2:='x')", "{'element1': 1, 'element2': x}")
    @Test fun tuplesAndNamedStructsRemainDistinctWhenNestedTogether() = verify(
        "struct_pack(t:=row(1,'x'),s:=struct_pack(element1:=1,element2:='x'))",
        "{'t': (1, x), 's': {'element1': 1, 'element2': x}}",
    )
    @Test fun tupleStringLookalikesRetainNativeQuoting() =
        verify("row('(1, x)','NULL','',NULL::VARCHAR)", "('(1, x)', 'NULL', '', NULL)")

    @Test fun tupleGeometryLeavesKeepNativeWktInsteadOfBecomingBlobText() {
        requirePatchedGetters()
        val cases = listOf(
            "row('POINT (1 2)'::GEOMETRY,7)" to "('POINT (1 2)', 7)",
            "struct_pack(t:=row('POINT (1 2)'::GEOMETRY,7))" to "{'t': ('POINT (1 2)', 7)}",
            "[row('POINT (1 2)'::GEOMETRY,7),NULL]" to "[('POINT (1 2)', 7), NULL]",
            "union_value(t:=row('POINT (1 2)'::GEOMETRY,7))" to "('POINT (1 2)', 7)",
        )
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            for ((expression, expected) in cases) {
                s.executeQuery("SELECT $expression,CAST($expression AS VARCHAR)").use { r ->
                    assertThat(GoldenComparator.readRows(r)).containsExactly(listOf(expected, expected))
                }
            }
        } }
    }

    @Test fun tupleDetectionUsesTypesRatherThanFieldNamesOrStringValues() {
        assertThat(GeometryNormalizer.containsTuple("STRUCT(\"TUPLE\" INTEGER, element1 VARCHAR)")).isFalse()
        assertThat(GeometryNormalizer.containsTuple("MAP(VARCHAR, TUPLE(INTEGER, VARCHAR)[])")).isTrue()
        assertThat(GeometryNormalizer.containsTuple("STRUCT(t TUPLE(INTEGER, VARCHAR))")).isTrue()
        assertThat(GeometryNormalizer.containsTuple("UNION(t TUPLE(INTEGER, VARCHAR))")).isTrue()
        assertThat(GeometryNormalizer.containsTuple("UNION(\"TUPLE\" INTEGER)")).isFalse()
        assertThat(GeometryNormalizer.containsTuple("VARCHAR")).isFalse()
    }

    @Test fun returningTupleKeepsItsNativeTextAndExecutesExactlyOnce() {
        val text = "statement ok\nCREATE TABLE t(i INTEGER PRIMARY KEY,v VARCHAR)\n\n" +
            "query T\nINSERT INTO t VALUES(1,'x') RETURNING row(i,v)\n----\n(1, x)\n\n" +
            "query II\nSELECT count(*),sum(i) FROM t\n----\n1\t1\n\n"
        val result = ReplayDriver().replay(SltParser.parse("tuple-returning.test", text))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.skipped).isEmpty()
        assertThat(result.failed).isEmpty()
        assertThat(result.passed).isEqualTo(3)
    }

    @Test fun replayUsesNativeTupleGoldensAndRejectsNamedStructLookalikes() {
        requirePatchedGetters()
        val text = "query T\nSELECT row(1,'x')\n----\n(1, x)\n\n" +
            "query T\nSELECT [row(1,'x'),NULL]\n----\n[(1, x), NULL]\n\n" +
            "query T\nSELECT row(1,'x')\n----\n{'element1': 1, 'element2': x}\n\n"
        val result = ReplayDriver().replay(SltParser.parse("tuple-patch.test", text))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.skipped).isEmpty()
        assertThat(result.passed).isEqualTo(2)
        assertThat(result.failed).hasSize(1)
    }
}
