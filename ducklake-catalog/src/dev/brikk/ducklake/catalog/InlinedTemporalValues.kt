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

import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.util.Locale

/** Parse native temporal text without JDBC sentinels, packed TIMETZ or partial interval matches. */
internal object InlinedTemporalValues {
    private const val MAX_TEXT_LENGTH = 256
    private val DATE = Regex("([+-]?[0-9]{4,})-([0-9]{2})-([0-9]{2})( \\(BC\\))?")
    private val DATE_TIME = Regex("([+-]?[0-9]{4,}-[0-9]{2}-[0-9]{2}(?: \\(BC\\))?)(?:[ T](.+))?")
    private val OFFSET = Regex("([+-][0-9]{2}(?::?[0-9]{2})?(?::?[0-9]{2})?|Z)$")
    private val NONFINITE = setOf("infinity", "+infinity", "-infinity")
    private val CLOCK = Regex("([+-]?)([0-9]+):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,6}))?")
    private val UNITS = mapOf(
        "year" to Pair(0, 12L), "years" to Pair(0, 12L), "yr" to Pair(0, 12L), "yrs" to Pair(0, 12L), "y" to Pair(0, 12L),
        "month" to Pair(0, 1L), "months" to Pair(0, 1L), "mon" to Pair(0, 1L), "mons" to Pair(0, 1L), "mo" to Pair(0, 1L),
        "day" to Pair(1, 1L), "days" to Pair(1, 1L), "d" to Pair(1, 1L),
        "hour" to Pair(2, 3_600_000_000L), "hours" to Pair(2, 3_600_000_000L), "h" to Pair(2, 3_600_000_000L),
        "minute" to Pair(2, 60_000_000L), "minutes" to Pair(2, 60_000_000L), "min" to Pair(2, 60_000_000L),
        "mins" to Pair(2, 60_000_000L), "m" to Pair(2, 60_000_000L),
        "second" to Pair(2, 1_000_000L), "seconds" to Pair(2, 1_000_000L), "sec" to Pair(2, 1_000_000L),
        "secs" to Pair(2, 1_000_000L), "s" to Pair(2, 1_000_000L),
        "millisecond" to Pair(2, 1_000L), "milliseconds" to Pair(2, 1_000L), "ms" to Pair(2, 1_000L),
        "microsecond" to Pair(2, 1L), "microseconds" to Pair(2, 1L), "us" to Pair(2, 1L),
    )
    private val UNIT = Regex("([+-]?[0-9]+)\\s*(" + UNITS.keys.sortedByDescending { it.length }.joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE)

    fun date(text: String): LocalDate {
        val value = finite(text, "DATE")
        val match = DATE.matchEntire(value) ?: throw IllegalArgumentException("invalid native date")
        val year = BigInteger(match.groupValues[1])
        val signedYear = if (match.groupValues[4].isEmpty()) year else BigInteger.ONE.subtract(year)
        if (signedYear.abs() > BigInteger.valueOf(999999999L)) unsupported("DATE year is outside the Java calendar range")
        return LocalDate.of(signedYear.intValueExact(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }

    fun time(text: String): LocalTime {
        val value = finite(text, "TIME")
        if (value.startsWith("24:")) unsupported("TIME 24:00:00 cannot be represented losslessly as LocalTime")
        return LocalTime.parse(value.let { if (it.length == 5) "$it:00" else it })
    }

    fun timestamp(text: String): LocalDateTime {
        val value = finite(text, "TIMESTAMP")
        val match = DATE_TIME.matchEntire(value) ?: throw IllegalArgumentException("invalid native timestamp")
        val date = date(match.groupValues[1])
        return if (match.groupValues[2].isEmpty()) date.atStartOfDay() else date.atTime(time(match.groupValues[2]))
    }

    fun offsetTime(text: String): OffsetTime {
        val (body, offset) = splitOffset(finite(text, "TIMETZ"))
        return time(body).atOffset(offset ?: ZoneOffset.UTC)
    }

    fun offsetTimestamp(text: String): OffsetDateTime {
        val (body, offset) = splitOffset(finite(text, "TIMESTAMPTZ"))
        return timestamp(body).atOffset(offset ?: ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.UTC)
    }

    fun localTimestampText(text: String): LocalDateTime = timestamp(splitOffset(finite(text, "TIMESTAMP")).first)

    fun hasExplicitOffset(text: String): Boolean {
        val match = OFFSET.find(text.trim()) ?: return false
        return text.trim().substring(0, match.range.first).contains(':')
    }

    private fun splitOffset(value: String): Pair<String, ZoneOffset?> {
        val match = OFFSET.find(value) ?: return value to null
        val prefix = value.substring(0, match.range.first)
        if (!prefix.contains(':')) return value to null // A date's trailing -DD is not a zone.
        val token = match.value
        if (token == "Z") return prefix to ZoneOffset.UTC
        val sign = if (token[0] == '-') -1 else 1
        val digits = token.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        val seconds = if (digits.length == 6) digits.substring(4, 6).toInt() else 0
        require(minutes < 60 && seconds < 60) { "invalid offset minutes/seconds" }
        if (hours > 18 || (hours == 18 && minutes + seconds != 0)) unsupported("temporal offset is outside the Java ZoneOffset range")
        return prefix to ZoneOffset.ofHoursMinutesSeconds(sign * hours, sign * minutes, sign * seconds)
    }

    fun interval(text: String): DucklakeInterval {
        val value = finite(text, "INTERVAL")
        require(value.isNotEmpty()) { "empty interval" }
        val parts = Array(3) { BigInteger.ZERO }
        var position = 0
        while (position < value.length) {
            while (position < value.length && value[position].isWhitespace()) position++
            if (position == value.length) break
            val clock = CLOCK.matchAt(value, position)
            if (clock != null) {
                parts[2] += clockMicros(clock)
                require(value.substring(clock.range.last + 1).isBlank()) { "trailing interval text" }
                position = value.length
            } else {
                val unit = UNIT.matchAt(value, position) ?: throw IllegalArgumentException("invalid interval token at $position")
                val (part, multiplier) = UNITS.getValue(unit.groupValues[2].lowercase(Locale.ROOT))
                parts[part] += BigInteger(unit.groupValues[1]).multiply(BigInteger.valueOf(multiplier))
                position = unit.range.last + 1
                require(position == value.length || value[position].isWhitespace()) { "invalid interval separator" }
            }
        }
        // Native interval_t stores int32 months/days and int64 micros. Never wrap or normalize away components.
        return DucklakeInterval(parts[0].intValueExact().toLong(), parts[1].intValueExact().toLong(), parts[2].longValueExact())
    }

    private fun clockMicros(clock: MatchResult): BigInteger {
        val minutes = clock.groupValues[3].toInt()
        val seconds = clock.groupValues[4].toInt()
        require(minutes < 60 && seconds < 60) { "invalid interval clock" }
        val wholeSeconds = BigInteger(clock.groupValues[2]).multiply(BigInteger.valueOf(3600)) +
            BigInteger.valueOf(minutes * 60L + seconds)
        val fraction = clock.groupValues[5].padEnd(6, '0').toLong()
        val micros = wholeSeconds.multiply(BigInteger.valueOf(1_000_000)) + BigInteger.valueOf(fraction)
        return if (clock.groupValues[1] == "-") micros.negate() else micros
    }

    private fun finite(text: String, type: String): String {
        require(text.length <= MAX_TEXT_LENGTH) { "temporal text exceeds the representation budget" }
        val value = text.trim()
        if (value.lowercase(Locale.ROOT) in NONFINITE) unsupported("non-finite $type is not qualified as a Java temporal value")
        return value
    }

    private fun unsupported(message: String): Nothing = throw DucklakeInvalidOperationException(message)
}
