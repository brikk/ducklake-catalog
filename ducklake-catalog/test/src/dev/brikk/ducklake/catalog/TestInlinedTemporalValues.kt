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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class TestInlinedTemporalValues {
    private fun decode(value: String, type: String): Any? = InlinedValues.decode(value, InlinedTypeNode(type, "v"))

    @Test
    fun temporalTextPreservesNanosecondsAndSecondResolutionOffsets() {
        assertThat(decode("23:59:59.999999999", "time_ns")).isEqualTo(LocalTime.of(23, 59, 59, 999999999))
        assertThat(decode("12:34:56.123456+05:30:15", "timetz"))
            .isEqualTo(OffsetTime.of(12, 34, 56, 123456000, ZoneOffset.ofHoursMinutesSeconds(5, 30, 15)))
        assertThat(decode("01:02-00:30:15", "timetz"))
            .isEqualTo(OffsetTime.of(1, 2, 0, 0, ZoneOffset.ofHoursMinutesSeconds(0, -30, -15)))
        assertThat(decode("2026-10-21 12:00:00.123456789+05:30", "timestamptz_ns"))
            .isEqualTo(OffsetDateTime.parse("2026-10-21T06:30:00.123456789Z"))
    }

    @Test
    fun nativeCalendarTextSupportsBceAndLargeFiniteYearsWithoutJavaParseSentinels() {
        assertThat(decode("0001-01-01 (BC)", "date")).isEqualTo(LocalDate.of(0, 1, 1))
        assertThat(decode("0002-01-01 (BC) 12:34:56.123456", "timestamp"))
            .isEqualTo(LocalDateTime.of(-1, 1, 1, 12, 34, 56, 123456000))
        assertThat(decode("0001-01-01 (BC) 12:34:56+00", "timestamptz"))
            .isEqualTo(OffsetDateTime.of(0, 1, 1, 12, 34, 56, 0, ZoneOffset.UTC))
        assertThat(decode("1000000-01-01", "date")).isEqualTo(LocalDate.of(1000000, 1, 1))
        assertThat(decode("12000-01-01 00:00:00.123456", "timestamp"))
            .isEqualTo(LocalDateTime.of(12000, 1, 1, 0, 0, 0, 123456000))
    }

    @Test
    fun intervalClocksAbove99HoursAndMixedSignsNeverBecomePartialMatchesOrNormalizedDays() {
        assertThat(decode("1 month -2 days 100:00:00.123456", "interval")).isEqualTo(DucklakeInterval(1, -2, 360000123456L))
        assertThat(decode("-100:00:00.123456", "interval")).isEqualTo(DucklakeInterval(0, 0, -360000123456L))
        assertThat(decode("2562047788:00:54.775807", "interval")).isEqualTo(DucklakeInterval(0, 0, Long.MAX_VALUE))
        assertThat(decode("-2562047788:00:54.775808", "interval")).isEqualTo(DucklakeInterval(0, 0, Long.MIN_VALUE))
        assertThat(decode("2147483647 months -2147483648 days", "interval")).isEqualTo(DucklakeInterval(Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(), 0))
        assertThat(decode("1 ms 2 us", "interval")).isEqualTo(DucklakeInterval(0, 0, 1002))
    }

    @Test
    fun malformedIntervalsAndOverflowAreCorruptionNotZeroValues() {
        for (value in listOf("", "garbage", "1 day garbage", "100:99:00", "1.5 days", "00:00:00.1234567",
            "00:00:00 trailing", "2562047788:00:54.775808", "2147483648 months", "2147483648 days")) {
            assertThatThrownBy { decode(value, "interval") }.`as`(value).isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
    }

    @Test
    fun unrepresentedNativeEdgesAreExplicitlyUnsupportedRatherThanWrongFiniteJavaValues() {
        for (type in listOf("date", "timestamp", "timestamp_ns", "timestamptz", "timestamptz_ns")) {
            for (value in listOf("infinity", "-infinity")) {
                assertThatThrownBy { decode(value, type) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("non-finite")
            }
        }
        for (type in listOf("time", "time_ns", "timetz")) {
            assertThatThrownBy { decode(if (type == "timetz") "24:00:00+02" else "24:00:00", type) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("24:00")
        }
        assertThatThrownBy { decode("12:00:00+05:99:00", "timetz") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
    }
}
