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
import org.jooq.impl.DSL
import org.jooq.SQLDialect
import org.junit.jupiter.api.Test

class TestFinal11InlineProjection {
    private fun column(id: Long, name: String, type: String, parent: Long? = null, default: String? = null) =
        DucklakeColumn(id, 0, null, 1, id, name, type, true, parent, default)

    @Test
    fun parserAcceptsQualifiedNestedTypesButDoesNotTreatStructOrderAsIdentity() {
        val first = Final11InlineTypes.parsePhysical("STRUCT(a INTEGER, \"comma, quote\"\"\" MAP(VARCHAR, STRUCT(x DECIMAL(10,2))[]))")
        val reordered = Final11InlineTypes.parsePhysical("STRUCT(\"comma, quote\"\"\" MAP(VARCHAR, STRUCT(x DECIMAL(10,2))[]), a INTEGER)")
        assertThat(Final11InlineTypes.samePhysical(first, reordered)).isTrue()
        assertThat(Final11InlineTypes.physicalSql(first)).contains("\"comma, quote\"\"\"")
        val changed = Final11InlineTypes.parsePhysical("STRUCT(a BIGINT, \"comma, quote\"\"\" MAP(VARCHAR, STRUCT(x DECIMAL(10,2))[]))")
        assertThat(Final11InlineTypes.samePhysical(first, changed)).isFalse()
        assertThat(Final11InlineTypes.samePhysical(InlinedTypeNode("timestamp_us", ""), Final11InlineTypes.parsePhysical("TIMESTAMP"))).isTrue()
        assertThat(Final11InlineTypes.parsePhysical("TIMESTAMP WITH TIME ZONE").type).isEqualTo("timestamptz")
    }

    @Test
    fun physicalTypesCannotInjectSqlOrGuessUnqualifiedLayouts() {
        for (sql in listOf("INTEGER); SELECT 1 --", "STRUCT(a INTEGER, a INTEGER)", "STRUCT(a INTEGER, A INTEGER)",
            "STRUCT(a INTEGER", "STRUCT(\"bad INTEGER)", "INTEGER[2]", "VARIANT", "TIME_NS", "DECIMAL(39,0)",
            "DECIMAL(2,3)", "MAP(INTEGER)", "STRUCT()", "INTEGER garbage")) {
            assertThatThrownBy { Final11InlineTypes.parsePhysical(sql) }
                .`as`(sql).isInstanceOf(DucklakeInvalidOperationException::class.java)
        }
    }

    @Test
    fun typeAndSchemaBudgetsFailBeforeUnboundedRecursionOrSqlRendering() {
        val deep = "STRUCT(a ".repeat(70) + "INTEGER" + ")".repeat(70)
        assertThatThrownBy { Final11InlineTypes.parsePhysical(deep) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThatThrownBy { Final11InlineTypes.parsePhysical("VARCHAR" + " ".repeat(Final11InlineTypes.MAX_TYPE_LENGTH)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
        val columns = (0L..70L).map { column(it, "c$it", if (it == 70L) "int32" else "struct", if (it == 0L) null else it - 1) }
        assertThatThrownBy { Final11InlineSchema(columns) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
            .hasMessageContaining("budget")
    }

    @Test
    fun malformedParentageAndDuplicateIdentitiesAreNotResolvedByName() {
        for (columns in listOf(
            listOf(column(1, "a", "int32"), column(1, "b", "int32")),
            listOf(column(1, "a", "int32", 99)),
            listOf(column(1, "a", "struct", 2), column(2, "b", "struct", 1)),
            listOf(column(1, "same", "int32"), column(2, "SAME", "int32")),
            listOf(column(1, "s", "struct"), column(2, "a", "int32", 1), column(3, "A", "int32", 1)),
        )) {
            assertThatThrownBy { Final11InlineSchema(columns) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
    }

    @Test
    fun movedFieldIdentityCannotBecomeAnInventedInitialDefault() {
        val source = listOf(column(1, "s", "struct"), column(2, "a", "int32", 1))
        val target = listOf(column(2, "a", "int32"))
        assertThatThrownBy { Final11ReadCompatibility.projections(DSL.using(SQLDialect.DUCKDB), "t", source, target, listOf(2)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("identity moved")
    }

    @Test
    fun unqualifiedDefaultTypesAreRejectedBeforeTheyCanBecomeSql() {
        val ctx = DSL.using(SQLDialect.DUCKDB)
        for (type in listOf("time", "time_ns", "interval", "variant", "varchar); SELECT 1 --")) {
            assertThatThrownBy { Final11ReadCompatibility.projections(ctx, "t", emptyList(),
                listOf(column(1, "v", type, default = "42")), listOf(1)) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("inline type")
        }
        assertThatThrownBy { Final11ReadCompatibility.projections(ctx, "t", emptyList(),
            listOf(column(1, "_DUCKLAKE_ROW_ID", "int64")), listOf(1)) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("reserved metadata")
    }

    @Test
    fun scalarPromotionsAreAnExplicitSubsetOfNativeWideningNotArbitraryCasts() {
        for ((from, to) in listOf("boolean" to "int32", "int8" to "int64", "uint8" to "int16", "uint32" to "uint64",
            "uint64" to "int128", "float32" to "float64", "int32" to "float32", "decimal(5,2)" to "decimal(10,4)",
            "int16" to "decimal(7,2)", "date" to "timestamp", "timestamp_ms" to "timestamp_ns", "timestamp_us" to "timestamp")) {
            Final11InlineTypes.requireConversion(from, to)
        }
        for ((from, to) in listOf("varchar" to "int32", "int64" to "int32", "int32" to "uint64", "float64" to "float32",
            "decimal(5,3)" to "decimal(6,2)", "timestamp_ns" to "timestamp", "timestamp" to "timestamptz", "json" to "varchar")) {
            assertThatThrownBy { Final11InlineTypes.requireConversion(from, to) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("scalar conversion")
        }
    }
}
