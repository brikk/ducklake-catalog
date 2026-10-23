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
import java.math.BigInteger

/** Maintained native cast order: wide integers use double; nonzero-scale decimals round from digits. */
internal object Final11PostgresNumericCasts {
    private val MASK = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
    private const val TWO_64 = 18446744073709551616.0

    fun floating(value: Number, source: String, single: Boolean): Number {
        if (value is BigDecimal) {
            val precision = Regex("decimal\\(\\s*([0-9]+)\\s*,.*").matchEntire(source)!!.groupValues[1].toInt()
            return if (value.scale() == 0) integral(value.unscaledValue(), precision > 18, single)
                else if (single) value.toFloat() else value.toDouble()
        }
        return integral(value, source in setOf("int128", "uint128"), single)
    }

    private fun integral(value: Number, wide: Boolean, single: Boolean): Number {
        if (wide) {
            val integer = value as BigInteger
            val upper = integer.shiftRight(64)
            val lower = integer.and(MASK)
            val converted = if (upper == BigInteger.valueOf(-1)) -(MASK - lower).toDouble() - 1.0
                else lower.toDouble() + upper.toDouble() * TWO_64
            return if (single) converted.toFloat() else converted
        }
        return if (single) value.toFloat() else value.toDouble()
    }
}
