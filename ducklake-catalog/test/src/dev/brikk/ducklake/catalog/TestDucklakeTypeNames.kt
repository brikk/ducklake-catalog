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
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** [DucklakeTypeNames] vs upstream `common/ducklake_types.cpp` (TODO-rectify-from-eval.md W-D9). */
class TestDucklakeTypeNames {
    @Test
    fun baseNamesAreCaseInsensitiveAndCanonicalisedLowercase() {
        assertThat(DucklakeTypeNames.canonical("VARCHAR")).isEqualTo("varchar")
        assertThat(DucklakeTypeNames.canonical(" Int32 ")).isEqualTo("int32")
        assertThat(DucklakeTypeNames.canonical("TimestampTZ")).isEqualTo("timestamptz")
        assertThat(DucklakeTypeNames.canonical("json")).isEqualTo("json")
    }

    @Test
    fun decimalMustBeLowercasePrefixWithWidthAndScale() {
        assertThat(DucklakeTypeNames.canonical("decimal(10,2)")).isEqualTo("decimal(10,2)")
        assertThat(DucklakeTypeNames.canonical("decimal( 38 , 0 )")).isEqualTo("decimal(38,0)")
        for (bad in listOf("DECIMAL(10,2)", "decimal(10)", "decimal(39,0)", "decimal(5,6)", "decimal(0,0)")) {
            assertThatThrownBy { DucklakeTypeNames.canonical(bad) }
                .`as`(bad)
                .isInstanceOf(DucklakeInvalidOperationException::class.java)
        }
    }

    @Test
    fun duckDbDialectNamesAreRejectedWithAHint() {
        for (bad in listOf("integer", "BIGINT", "double", "text", "string", "")) {
            assertThatThrownBy { DucklakeTypeNames.canonical(bad) }
                .`as`(bad)
                .isInstanceOf(DucklakeInvalidOperationException::class.java)
                .hasMessageContaining("int32/int64")
        }
    }

    @Test
    fun nestingShapeIsValidated() {
        val ok = TableColumnSpec(
            "s", "struct", true,
            listOf(
                TableColumnSpec("l", "list", true, listOf(TableColumnSpec.leaf("element", "int32", true))),
                TableColumnSpec(
                    "m", "map", true,
                    listOf(TableColumnSpec.leaf("key", "varchar", false), TableColumnSpec.leaf("value", "float64", true)),
                ),
            ),
        )
        assertThatCode { DucklakeTypeNames.validate(ok) }.doesNotThrowAnyException()

        assertThatThrownBy { DucklakeTypeNames.validate(TableColumnSpec("l", "list", true, emptyList())) }
            .hasMessageContaining("exactly one child")
        assertThatThrownBy {
            DucklakeTypeNames.validate(TableColumnSpec("m", "map", true, listOf(TableColumnSpec.leaf("key", "varchar", false))))
        }.hasMessageContaining("exactly two children")
        assertThatThrownBy { DucklakeTypeNames.validate(TableColumnSpec("s", "struct", true, emptyList())) }
            .hasMessageContaining("at least one field")
        assertThatThrownBy {
            DucklakeTypeNames.validate(TableColumnSpec("x", "int32", true, listOf(TableColumnSpec.leaf("y", "int32", true))))
        }.hasMessageContaining("cannot have children")
        // Nested bad names are reported with their path.
        assertThatThrownBy {
            DucklakeTypeNames.validate(TableColumnSpec("s", "struct", true, listOf(TableColumnSpec.leaf("a", "integer", true))))
        }.hasMessageContaining("integer")
    }
}
