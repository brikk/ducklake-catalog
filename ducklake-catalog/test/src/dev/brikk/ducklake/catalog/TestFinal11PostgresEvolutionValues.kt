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
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.math.BigInteger
import java.sql.DriverManager

class TestFinal11PostgresEvolutionValues {
    private fun column(id: Long, name: String, type: String, parent: Long? = null, default: String? = null) =
        DucklakeColumn(id, 0, null, 1, id, name, type, true, parent, default)
    private fun context(kind: String = "literal") = DSL.using(MockConnection {
        val field = DSL.field("default_value_type", String::class.java)
        val result = DSL.using(SQLDialect.POSTGRES).newResult(field)
        result.add(DSL.using(SQLDialect.POSTGRES).newRecord(field).value1(kind))
        arrayOf(MockResult(1, result))
    }, SQLDialect.POSTGRES)

    @Test
    fun textCodecPreservesQuotedNullBlobJsonUnicodeAndNonSqlFieldNames() {
        val type = InlinedTypeNode("struct", "s", listOf(InlinedTypeNode("varchar", "quote'\\field"), InlinedTypeNode("blob", "b"),
            InlinedTypeNode("json", "j"), InlinedTypeNode("date", "bc"), InlinedTypeNode("varchar", "n")))
        val value = linkedMapOf("quote'\\field" to "NULL 'é'\\\u0000", "b" to byteArrayOf(0, 0x5c, 0xff.toByte()),
            "j" to "{\"x\":null}", "bc" to LocalDate.of(0, 1, 1), "n" to null)
        val text = Final11PostgresText().encode(value, type)
        val decoded = Final11PostgresValues.decode(text, type) as Map<*, *>
        assertThat(decoded["quote'\\field"]).isEqualTo(value["quote'\\field"])
        assertThat(decoded["b"] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
        assertThat(decoded["j"]).isEqualTo(value["j"])
        assertThat(decoded["bc"]).isEqualTo(value["bc"])
        assertThat(decoded["n"]).isNull()
        val sqlText = "CAST('literal; DROP TABLE t' AS VARCHAR)"
        val mapType = InlinedTypeNode("map", "m", listOf(InlinedTypeNode("varchar", "key"), InlinedTypeNode("varchar", "value")))
        val encoded = Final11PostgresText().encode(mapOf(sqlText to sqlText), mapType)
        assertThat(Final11PostgresValues.decode(encoded, mapType)).isEqualTo(mapOf(sqlText to sqlText))
    }

    @Test
    fun outputNodeBudgetBoundsDefaultExpansionBeforeReturningAMaterializedResult() {
        val source = Final11InlineSchema(listOf(column(1, "xs", "list"), column(2, "element", "struct", 1), column(3, "keep", "int32", 2)))
        val target = Final11InlineSchema(source.byId.values.map { it.column } + (4L..68L).map { column(it, "added$it", "int32", 2) })
        val plan = Final11PostgresEvolution(context(), source).plan(source.roots[1], target.roots.getValue(1))
        assertThatThrownBy { plan.apply(List(1024) { mapOf("keep" to 1) }) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            .hasMessageContaining("node budget")
    }

    @Test
    fun expandedRawTextHasItsOwnBoundAndBlobDefaultsAreIndependentlyCopied() {
        val source = Final11InlineSchema(listOf(column(1, "xs", "list"), column(2, "element", "struct", 1), column(3, "keep", "int32", 2)))
        val target = Final11InlineSchema(source.byId.values.map { it.column } + listOf(column(4, "s", "varchar", 2, "x".repeat(4096)),
            column(5, "b", "blob", 2, "\\x00N")))
        val plan = Final11PostgresEvolution(context(), source).plan(source.roots[1], target.roots.getValue(1))
        val value = plan.apply(List(300) { mapOf("keep" to 1) }) as List<*>
        val first = value[0] as Map<*, *>
        (first["b"] as ByteArray)[0] = 99
        assertThat((value[1] as Map<*, *>)["b"] as ByteArray).containsExactly(0, 0x4e)
        assertThatThrownBy { Final11PostgresText().encode(value, target.roots.getValue(1).type) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("representation budget")
    }

    @Test
    fun movedIdsAndExpressionDefaultsAreRejectedEvenForNullParents() {
        val source = Final11InlineSchema(listOf(column(1, "s", "struct"), column(2, "v", "int32", 1)))
        val moved = Final11InlineSchema(listOf(column(2, "v", "int32")))
        assertThatThrownBy { Final11PostgresEvolution(context(), source).plan(null, moved.roots.getValue(2)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("identity moved")
        val added = Final11InlineSchema(listOf(column(1, "s", "struct"), column(3, "v", "int32", 1)))
        assertThatThrownBy { Final11PostgresEvolution(context("expression"), source).plan(source.roots[1], added.roots.getValue(1)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("non-literal")
    }

    @Test
    fun legitimateSourceValuesOutsideTheTargetTemporalDomainAreUnsupportedNotSourceCorruption() {
        val source = Final11InlineSchema(listOf(column(1, "v", "date")))
        val target = Final11InlineSchema(listOf(column(1, "v", "timestamp_ns")))
        val plan = Final11PostgresEvolution(context(), source).plan(source.roots[1], target.roots.getValue(1))
        assertThatThrownBy { plan.apply(LocalDate.of(3000, 1, 1)) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
            .hasMessageContaining("qualified target domain")
    }

    @Test
    fun scalarMapKeysUseNativeBinaryZeroAndIntervalEqualityWithoutDroppingDuplicates() {
        for ((key, text) in listOf("blob" to "{'\\\\x00'=1,'\\\\x00'=2}", "float64" to "{0.0=1,-0.0=2}",
            "interval" to "{'1 month'=1,'30 days'=2}")) {
            val type = InlinedTypeNode("map", "m", listOf(InlinedTypeNode(key, "key"), InlinedTypeNode("int32", "value")))
            assertThatThrownBy { Final11PostgresValues.decode(text, type) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
                .hasRootCauseMessage("duplicate normalized MAP key")
        }
    }

    @Test
    fun compositeMapKeyAndImplicitZoneContractsStayExplicitlyClosed() {
        val source = Final11InlineSchema(listOf(column(1, "v", "timestamp")))
        val zoned = Final11InlineSchema(listOf(column(1, "v", "timestamptz")))
        assertThatThrownBy { Final11PostgresEvolution(context(), source).plan(source.roots[1], zoned.roots.getValue(1)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("scalar conversion")
        val map = Final11InlineSchema(listOf(column(1, "m", "map"), column(2, "key", "list", 1),
            column(3, "element", "int32", 2), column(4, "value", "int32", 1)))
        assertThatThrownBy { Final11PostgresProjection(context(), map, map).plan("t", listOf(1)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("composite MAP keys")
    }

    @Test
    @Suppress("NestedBlockDepth")
    fun floatingCastOrderMatchesPinnedNativeAtWideIntegerAndDecimalRoundingBoundaries() {
        Final11DuckDbBackendFixture.requirePinnedJdbc()
        val midpoint = BigInteger.ONE.shiftLeft(120) + BigInteger.ONE.shiftLeft(96)
        val wide = (-2L..2L).map { (midpoint + BigInteger.valueOf(it)).toString() }
        val cases = listOf(
            Triple("HUGEINT", "int128", wide + wide.map { "-$it" } + listOf("-9007199254740993", "9007199254740993")),
            Triple("UHUGEINT", "uint128", wide),
            Triple("BIGINT", "int64", listOf("16777217", "9007199254740993", "-9007199254740993")),
            Triple("DECIMAL(9,2)", "decimal(9,2)", listOf("0.10", "-0.10", "9999999.99", "-9999999.99")),
            Triple("DECIMAL(18,6)", "decimal(18,6)", listOf("99999999999.999999", "-99999999999.999999", "123456789.123456")),
            Triple("DECIMAL(38,0)", "decimal(38,0)", wide + wide.map { "-$it" }),
            Triple("DECIMAL(38,10)", "decimal(38,10)", listOf("9999999999999999999999999999.9999999999",
                "-9999999999999999999999999999.9999999999", "9007199254740993.1234567890")))
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            for ((sqlType, source, values) in cases) for (single in listOf(true, false)) {
                c.prepareStatement("SELECT CAST(CAST(? AS $sqlType) AS ${if (single) "FLOAT" else "DOUBLE"})").use { statement ->
                    for (text in values) {
                        statement.setString(1, text)
                        val value = Final11PostgresValues.decode(text, InlinedTypeNode(source, "v")) as Number
                        val converted = Final11PostgresNumericCasts.floating(value, source, single)
                        statement.executeQuery().use { result ->
                            check(result.next())
                            if (single) assertThat(converted.toFloat().toRawBits()).`as`("$source $text to FLOAT")
                                .isEqualTo(result.getFloat(1).toRawBits())
                            else assertThat(converted.toDouble().toRawBits()).`as`("$source $text to DOUBLE")
                                .isEqualTo(result.getDouble(1).toRawBits())
                        }
                    }
                }
            }
        }
    }
}
