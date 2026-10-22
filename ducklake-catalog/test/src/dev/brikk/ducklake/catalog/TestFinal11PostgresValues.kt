/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.ducklake.catalog

import io.airlift.json.JsonCodecFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class TestFinal11PostgresValues {
    private fun leaf(type: String) = InlinedTypeNode(type, "v")
    private fun decode(raw: Any?, type: String) = Final11PostgresValues.decode(raw, leaf(type))

    @Test
    fun strictStructTextIsByNameCompleteAndDuplicateFree() {
        val type = InlinedTypeNode("struct", "s", listOf(InlinedTypeNode("int32", "a"), InlinedTypeNode("varchar", "b")))
        assertThat(Final11PostgresValues.decode("{'B':'NULL','A':1}", type)).isEqualTo(linkedMapOf("a" to 1, "b" to "NULL"))
        for (text in listOf("{}", "{'a':1}", "{'a':1,'b':NULL,'a':2}", "{'a':1,'A':2,'b':NULL}", "{'a':1,'b':NULL,'extra':9}")) {
            assertThatThrownBy { Final11PostgresValues.decode(text, type) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
    }

    @Test
    fun duplicateAndNullMapKeysCannotDisappearDuringDecodeOrDecimalNormalization() {
        val map = InlinedTypeNode("map", "m", listOf(leaf("varchar"), leaf("int32")))
        assertThat(Final11PostgresValues.decode("{'NULL'=1}", map)).isEqualTo(mapOf("NULL" to 1))
        for (text in listOf("{NULL=1}", "{x=1,x=2}")) {
            assertThatThrownBy { Final11PostgresValues.decode(text, map) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
        val decimal = InlinedTypeNode("map", "m", listOf(leaf("decimal(4,2)"), leaf("int32")))
        assertThatThrownBy { Final11PostgresValues.decode("{1.0=1,1.00=2}", decimal) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
    }

    @Test
    fun byteaTextIsStrictUtf8ButBlobBytesRemainUninterpretedAndNullIsNeverAStringSentinel() {
        assertThat(decode("A\u0000NULL".toByteArray(Charsets.UTF_8), "varchar")).isEqualTo("A\u0000NULL")
        assertThat(decode(byteArrayOf(0xff.toByte()), "blob") as ByteArray).containsExactly(0xff.toByte())
        assertThatThrownBy { decode(byteArrayOf(0xff.toByte()), "varchar") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThat(decode(null, "varchar")).isNull()
        assertThat(decode("NULL".toByteArray(), "varchar")).isEqualTo("NULL")
        assertThatThrownBy { decode(0, "null") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
    }

    @Test
    fun widerSqlStorageCannotHideIntegerDecimalOrTemporalDomainCorruption() {
        assertThatThrownBy { decode(128, "int8") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThatThrownBy { decode("1e100", "float32") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThatThrownBy { decode("1e1000", "float64") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThat(decode("inf", "float32")).isEqualTo(Float.POSITIVE_INFINITY)
        assertThat(decode("1.2", "decimal(4,2)")).isEqualTo(BigDecimal("1.20"))
        for (value in listOf("1.234", "100.00")) {
            assertThatThrownBy { decode(value, "decimal(4,2)") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
        for ((value, type) in listOf("10000000-01-01" to "date", "2262-04-12 00:00:00" to "timestamp_ns",
            "2020-01-01 00:00:00.1234567" to "timestamp", "2020-01-01 00:00:00.1" to "timestamp_s")) {
            assertThatThrownBy { decode(value, type) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
        assertThat(decode("0001-01-01 (BC)", "date")).isEqualTo(LocalDate.of(0, 1, 1))
        assertThatThrownBy { decode("1000000-01-01 00:00:00", "timestamp_s") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
    }

    @Test
    fun zonedValuesNeedExplicitOffsetsAndNativeSpecialValuesStayUnsupported() {
        for ((value, type) in listOf("12:34:56" to "timetz", "2020-01-01 00:00:00" to "timestamptz_ns")) {
            assertThatThrownBy { decode(value, type) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
        assertThatThrownBy { decode("24:00:00", "time") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThatThrownBy { decode("infinity", "date") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThatThrownBy { decode("-infinity", "timestamptz_ns") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        for (value in listOf("01:02:03.123456789+00", "01:02:03+17")) {
            assertThatThrownBy { decode(value, "timetz") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
    }

    @Test
    fun jsonRemainsExactTextButMustBeOneValidBoundedValue() {
        val json = " { \"n\" : null, \"literal\" : \"NULL\" } "
        assertThat(decode(json.toByteArray(), "json")).isEqualTo(json)
        assertThat(decode("null", "json")).isEqualTo("null")
        for (value in listOf("", "NULL", "{} []", "{broken}")) {
            assertThatThrownBy { decode(value, "json") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
        assertThatThrownBy { decode("[".repeat(65) + "0" + "]".repeat(65), "json") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
    }

    @Test
    fun valueAndNodeBudgetsRejectRatherThanTruncate() {
        assertThatThrownBy { decode("x".repeat(Final11PostgresValues.MAX_BYTES + 1), "varchar") }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
        val list = InlinedTypeNode("list", "l", listOf(leaf("int32")))
        assertThatThrownBy { Final11PostgresValues.decode(List(65537) { "0" }.joinToString(",", "[", "]"), list) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
    }

    @Test
    fun backendProfileJsonUsesExplicitNullForUnavailableProvenanceWithoutChangingOldCaps() {
        val value = DucklakeReadBackendProfile("1.1", 7, "postgresql", "postgres-jdbc", "PostgreSQL 18", null,
            "PostgreSQL JDBC Driver", "42", null, null, null, "postgres-same-shape-integer-widening", false, false, false)
        val codec = JsonCodecFactory().jsonCodec(DucklakeReadBackendProfile::class.java)
        assertThat(codec.toJson(value)).contains("\"sqlEngineSourceId\":null", "\"unsignedExtensionsAllowed\":null",
            "\"ducklakeExtensionVersion\":null", "\"quackExtensionVersion\":null", "\"qualifiedWrites\":false")
        assertThat(codec.fromJson(codec.toJsonBytes(value))).isEqualTo(value)
    }
}
