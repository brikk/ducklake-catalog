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
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Pure decoding rules of [InlinedValues] (the backend round-trips live in the *Interop tests). */
class TestInlinedValues {
    private fun leaf(type: String, name: String = "c") = InlinedTypeNode(type, name)

    @Test
    fun scalarsFromTextAndFromNativeTypes() {
        assertThat(InlinedValues.decode("2024-02-29T12:34:56.5Z", leaf("timestamptz")))
            .isEqualTo(OffsetDateTime.of(2024, 2, 29, 12, 34, 56, 500_000_000, ZoneOffset.UTC))
        assertThat(InlinedValues.decode("2024-02-29 14:34:56+02", leaf("timestamptz")))
            .isEqualTo(OffsetDateTime.of(2024, 2, 29, 12, 34, 56, 0, ZoneOffset.UTC))
        assertThat(InlinedValues.decode("2024-02-29", leaf("timestamp"))).isEqualTo(LocalDateTime.of(2024, 2, 29, 0, 0))
        assertThat(InlinedValues.decode("2024-02-29".toByteArray(), leaf("varchar"))).isEqualTo("2024-02-29")
        assertThat(InlinedValues.decode(java.sql.Date.valueOf("2024-02-29"), leaf("date"))).isEqualTo(LocalDate.of(2024, 2, 29))
        assertThat(InlinedValues.decode(7, leaf("int64"))).isEqualTo(7L)
        assertThat(InlinedValues.decode("7", leaf("int8"))).isEqualTo(7)
        assertThat(InlinedValues.decode("18446744073709551615", leaf("uint64"))).isEqualTo(BigInteger("18446744073709551615"))
        assertThat(InlinedValues.decode("t", leaf("boolean"))).isEqualTo(true)
        assertThat(InlinedValues.decode("inf", leaf("float64"))).isEqualTo(Double.POSITIVE_INFINITY)
        assertThat(InlinedValues.decode("\\x00A\\\\", leaf("blob")) as ByteArray).containsExactly(0, 'A'.code.toByte(), '\\'.code.toByte())
        assertThat(InlinedValues.decode(null, leaf("int32"))).isNull()
        assertThat(InlinedValues.decode("whatever", leaf("geometry"))).`as`("unknown types pass through").isEqualTo("whatever")
    }

    @Test
    fun intervalsInDuckDbAndPostgresSpellings() {
        val expected = DucklakeInterval(14, 3, 4 * 3_600_000_000L + 5 * 60_000_000L + 6_007_000L)
        assertThat(InlinedValues.decode("1 year 2 months 3 days 04:05:06.007", leaf("interval"))).isEqualTo(expected)
        assertThat(InlinedValues.decode("1 year 2 mons 3 days 04:05:06.007", leaf("interval"))).isEqualTo(expected)
        assertThat(InlinedValues.decode("-00:00:01.5", leaf("interval"))).isEqualTo(DucklakeInterval(0, 0, -1_500_000L))
        assertThat(InlinedValues.decode("00:00:00", leaf("interval"))).isEqualTo(DucklakeInterval(0, 0, 0))
    }

    @Test
    fun nestedTextFollowsDuckDbQuotingRules() {
        val list = InlinedTypeNode("list", "l", listOf(leaf("varchar", "element")))
        assertThat(InlinedValues.decode("[plain, 'it\\'s', '', NULL, 'a,b', sp ace, 'NULL', '[x]']", list))
            .isEqualTo(listOf("plain", "it's", "", null, "a,b", "sp ace", "NULL", "[x]"))
        assertThat(InlinedValues.decode("[]", list)).isEqualTo(emptyList<Any?>())

        val struct = InlinedTypeNode(
            "struct", "s",
            listOf(leaf("int32", "a"), leaf("varchar", "b"), InlinedTypeNode("struct", "mid", listOf(leaf("date", "x")))),
        )
        assertThat(InlinedValues.decode("{'a': 1, 'b': 'x, y', 'mid': {'x': 2000-01-02}}", struct))
            .isEqualTo(linkedMapOf("a" to 1, "b" to "x, y", "mid" to linkedMapOf("x" to LocalDate.of(2000, 1, 2))))
        // DuckDB's postgres writer wraps non-native nested leaves in CAST('…' AS VARCHAR) — recoverable.
        assertThat(InlinedValues.decode("{'a': 1, 'b': NULL, 'mid': {'x': CAST('2000-01-02' AS VARCHAR)}}", struct))
            .isEqualTo(linkedMapOf("a" to 1, "b" to null, "mid" to linkedMapOf("x" to LocalDate.of(2000, 1, 2))))

        val map = InlinedTypeNode("map", "m", listOf(leaf("varchar", "key"), leaf("int32", "value")))
        assertThat(InlinedValues.decode("{k1=1, k2=NULL, 'has space'=3}", map))
            .isEqualTo(linkedMapOf("k1" to 1, "k2" to null, "has space" to 3))
        val nestedList = InlinedTypeNode("list", "ll", listOf(InlinedTypeNode("list", "element", listOf(leaf("int32", "element")))))
        assertThat(InlinedValues.decode("[[1, 2], [], NULL]", nestedList)).isEqualTo(listOf(listOf(1, 2), emptyList<Any?>(), null))
    }

    @Test
    fun undecodableValuesSurfaceAsCorruptionNotAsWrongValues() {
        assertThatThrownBy { InlinedValues.decode("not-a-date", leaf("date")) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
            .hasMessageContaining("date")
        assertThatThrownBy { InlinedValues.decode("[1, 2", InlinedTypeNode("list", "l", listOf(leaf("int32", "element")))) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThatThrownBy { InlinedValues.decode("300", leaf("int8")) }
            .`as`("out of range for the declared width")
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
    }

    @Test
    fun typeTreeFollowsParentColumnLinks() {
        fun col(id: Long, name: String, type: String, parent: Long?, order: Long) =
            DucklakeColumn(id, 1, null, 1, order, name, type, true, parent)
        val cols = listOf(col(1, "s", "struct", null, 1), col(3, "b", "varchar", 1, 1), col(2, "a", "int32", 1, 0), col(4, "l", "list", 3, 0))
        val tree = InlinedValues.typeTree(cols[0], cols)
        assertThat(tree.children.map { it.name }).`as`("ordered by column_order").containsExactly("a", "b")
        assertThat(tree.children[1].children.single().name).isEqualTo("l")
    }
}
