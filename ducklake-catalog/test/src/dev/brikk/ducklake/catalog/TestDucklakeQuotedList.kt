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

/**
 * Pins byte-for-byte parity with upstream `DuckLakeUtil::ToQuotedList` /
 * `ParseQuotedList` (`common/ducklake_util.cpp`). Expected strings below are what
 * upstream produces for the same inputs.
 */
class TestDucklakeQuotedList {
    @Test
    fun encodeMatchesUpstream() {
        assertThat(DucklakeQuotedList.encode(emptyList())).isEqualTo("")
        assertThat(DucklakeQuotedList.encode(listOf("a"))).isEqualTo("\"a\"")
        assertThat(DucklakeQuotedList.encode(listOf("a", "b c", ""))).isEqualTo("\"a\",\"b c\",\"\"")
        // embedded quote is doubled (KeywordHelper::WriteQuoted)
        assertThat(DucklakeQuotedList.encode(listOf("say \"hi\""))).isEqualTo("\"say \"\"hi\"\"\"")
        // separator inside a value is not special
        assertThat(DucklakeQuotedList.encode(listOf("x,y"))).isEqualTo("\"x,y\"")
    }

    @Test
    fun parseMatchesUpstream() {
        assertThat(DucklakeQuotedList.parse(null)).isEmpty()
        assertThat(DucklakeQuotedList.parse("")).isEmpty()
        assertThat(DucklakeQuotedList.parse("\"a\"")).containsExactly("a")
        assertThat(DucklakeQuotedList.parse("\"a\",\"b c\",\"\"")).containsExactly("a", "b c", "")
        assertThat(DucklakeQuotedList.parse("\"say \"\"hi\"\"\"")).containsExactly("say \"hi\"")
        assertThat(DucklakeQuotedList.parse("\"x,y\"")).containsExactly("x,y")
    }

    @Test
    fun roundTrip() {
        val values = listOf("plain", "with space", "with,comma", "with\"quote", "", "unicode ✓", "\"\"")
        assertThat(DucklakeQuotedList.parse(DucklakeQuotedList.encode(values))).isEqualTo(values)
    }

    @Test
    fun rejectsWhatUpstreamRejects() {
        // upstream: "Failed to parse quoted value - expected a quote"
        assertThatThrownBy { DucklakeQuotedList.parse("a") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected a quote")
        // JSON payloads (the legacy Trino sidecar) are exactly what upstream chokes on
        assertThatThrownBy { DucklakeQuotedList.parse("{\"originalSql\":\"SELECT 1\"}") }
            .isInstanceOf(IllegalArgumentException::class.java)
        // upstream: "Failed to parse quoted value - unterminated quote"
        assertThatThrownBy { DucklakeQuotedList.parse("\"abc") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unterminated")
        // upstream: "Failed to parse list - expected a ,"
        assertThatThrownBy { DucklakeQuotedList.parse("\"a\";\"b\"") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected a ','")
        assertThatThrownBy { DucklakeQuotedList.parse("\"a\" \"b\"") }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(DucklakeQuotedList.isWellFormed("\"a\",\"b\"")).isTrue()
        assertThat(DucklakeQuotedList.isWellFormed("{}")).isFalse()
    }

    @Test
    fun customSeparator() {
        assertThat(DucklakeQuotedList.encode(listOf("a", "b"), ';')).isEqualTo("\"a\";\"b\"")
        assertThat(DucklakeQuotedList.parse("\"a\";\"b\"", ';')).containsExactly("a", "b")
    }
}
