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

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** Bounded target-storage text for evolved roots; it is data, never a SQL expression. */
internal class Final11PostgresText {
    private val out = StringBuilder()
    fun encode(value: Any?, type: InlinedTypeNode): String {
        appendValue(value, type, false)
        return out.toString()
    }
    private fun appendValue(value: Any?, type: InlinedTypeNode, nested: Boolean) {
        when {
            value == null -> append("NULL")
            type.type == "struct" -> {
                val fields = value as Map<*, *>
                append("{")
                type.children.forEachIndexed { index, child ->
                    if (index > 0) append(", ")
                    quote(child.name); append(": "); appendValue(fields[child.name], child, true)
                }
                append("}")
            }
            type.type == "list" -> {
                append("[")
                (value as List<*>).forEachIndexed { index, item ->
                    if (index > 0) append(", ")
                    appendValue(item, type.children.single(), true)
                }
                append("]")
            }
            type.type == "map" -> {
                append("{")
                (value as Map<*, *>).entries.forEachIndexed { index, entry ->
                    if (index > 0) append(", ")
                    appendValue(entry.key, type.children[0], true); append("="); appendValue(entry.value, type.children[1], true)
                }
                append("}")
            }
            nested -> quote(scalar(value))
            else -> append(scalar(value))
        }
    }
    private fun scalar(value: Any): String = when (value) {
        is BigDecimal -> value.toPlainString()
        is LocalDate -> date(value)
        is LocalTime -> value.format(TIME)
        is LocalDateTime -> date(value.toLocalDate()) + " " + value.toLocalTime().format(TIME)
        is OffsetTime -> value.toLocalTime().format(TIME) + value.offset.id.replace("Z", "+00")
        is OffsetDateTime -> date(value.toLocalDate()) + " " + value.toLocalTime().format(TIME) + value.offset.id.replace("Z", "+00")
        is DucklakeInterval -> "${value.months} months ${value.days} days ${value.micros} microseconds"
        is ByteArray -> buildString { value.forEach { byte -> append("\\x"); append(HEX[(byte.toInt() and 255) / 16]); append(HEX[byte.toInt() and 15]) } }
        else -> value.toString()
    }
    private fun quote(value: String) {
        append("'")
        value.forEach {
            if (it == '\\' || it == '\'') { append("\\") }
            append(it.toString())
        }
        append("'")
    }
    private fun append(value: String) {
        if (out.length + value.length > Final11PostgresValues.MAX_BYTES) {
            throw DucklakeInvalidOperationException("PostgreSQL evolved inline text exceeds the representation budget")
        }
        out.append(value)
    }
    companion object {
        private const val HEX = "0123456789ABCDEF"
        private val TIME = DateTimeFormatterBuilder().appendPattern("HH:mm:ss").appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter(Locale.ROOT)
        private fun date(value: LocalDate): String {
            val year = if (value.year <= 0) 1L - value.year else value.year.toLong()
            return year.toString().padStart(4, '0') + "-%02d-%02d".format(Locale.ROOT, value.monthValue, value.dayOfMonth) +
                if (value.year <= 0) " (BC)" else ""
        }
    }
}
