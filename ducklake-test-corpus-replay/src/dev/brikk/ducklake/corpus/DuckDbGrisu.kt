/*
 * Adapted from fmt as vendored by DuckDB d54cbbe3df17e8e5ce68312f483b4155836112aa:
 * third_party/fmt/include/fmt/format-inl.h (fp, Grisu shortest handler and FPP fallback).
 * Copyright (c) 2012 - present, Victor Zverovich.
 * MIT license: resources/META-INF/licenses/duckdb-fmt.txt.
 */
@file:Suppress("MagicNumber") // Bit widths, boundary adjustments and rounding constants match the pinned formatter.

package dev.brikk.ducklake.corpus

import java.math.BigInteger

/** Default shortest-digit path only; no printf precision, locale, SQL, JNI or host-JDK float rendering. */
internal object DuckDbGrisu {
    data class Decimal(val digits: String, val exponent: Int, val usedFallback: Boolean = false)
    private data class Fp(val significand: ULong, val exponent: Int) {
        fun normalized(): Fp {
            val shift = java.lang.Long.numberOfLeadingZeros(significand.toLong())
            check(significand != 0UL)
            return Fp(significand shl shift, exponent - shift)
        }
        fun multiplied(other: Fp): Fp = Fp(multiply(significand, other.significand), exponent + other.exponent + 64)
    }
    private data class Binary(val value: Fp, val predecessorCloser: Boolean)
    private data class Bounds(val lower: ULong, val upper: ULong)
    private data class Generated(val digits: String, val exponent: Int, val failed: Boolean)
    private enum class Status { MORE, DONE, ERROR }
    private val powers = List(20) { exponent -> var value = 1UL; repeat(exponent) { value *= 10UL }; value }
    private const val IMPLICIT = 0x0010000000000000UL

    fun digits(value: Double, binary32: Boolean): Decimal {
        require(value >= 0 && value.isFinite())
        if (value == 0.0) return Decimal("0", 0)
        val binary = binary(value)
        val normalized = binary.value.normalized()
        val bounds = if (binary32) floatBounds(binary.value) else doubleBounds(binary)
        val cached = DuckDbGrisuPowers.get(-60 - (normalized.exponent + 64))
        val scaled = normalized.multiplied(Fp(cached.significand, cached.exponent))
        val lower = multiply(bounds.lower, cached.significand) - 1UL
        val upper = multiply(bounds.upper, cached.significand) + 1UL
        val generated = generate(Fp(upper, scaled.exponent), upper - lower, upper - scaled.significand)
        if (generated.failed) {
            val estimate = generated.exponent + generated.digits.length - cached.decimalExponent - 1
            // Native fmt's fallback operates on the promoted DOUBLE even for FLOAT (its explicit TODO).
            return fallback(binary, estimate)
        }
        return Decimal(generated.digits, generated.exponent - cached.decimalExponent)
    }

    private fun binary(value: Double): Binary {
        val bits = value.toRawBits().toULong()
        var significand = bits and (IMPLICIT - 1UL)
        var biased = ((bits shr 52) and 0x7ffUL).toInt()
        val closer = significand == 0UL && biased > 1
        if (biased != 0) significand += IMPLICIT else biased = 1
        return Binary(Fp(significand, biased - 1023 - 52), closer)
    }

    private fun doubleBounds(binary: Binary): Bounds {
        val (f, e) = binary.value
        val lower = if (binary.predecessorCloser) Fp((f shl 2) - 1UL, e - 2) else Fp((f shl 1) - 1UL, e - 1)
        val upper = Fp((f shl 1) + 1UL, e - 1).normalized()
        return Bounds(lower.significand shl (lower.exponent - upper.exponent), upper.significand)
    }

    private fun floatBounds(value: Fp): Bounds {
        val (f, e) = value
        val minimumNormalExponent = -178 // float.min_exponent - double.digits
        var halfUlp = 1UL shl 28
        if (minimumNormalExponent > e) halfUlp = halfUlp shl (minimumNormalExponent - e)
        val upper = Fp(f + halfUlp, e).normalized()
        val lower = f - (halfUlp shr if (f == IMPLICIT && e > minimumNormalExponent) 1 else 0)
        return Bounds(lower shl (e - upper.exponent), upper.significand)
    }

    /** Unsigned high product rounded half-up, using the native 32-bit-parts implementation. */
    private fun multiply(lhs: ULong, rhs: ULong): ULong {
        val mask = 0xffffffffUL
        val a = lhs shr 32; val b = lhs and mask
        val c = rhs shr 32; val d = rhs and mask
        val ac = a * c; val bc = b * c; val ad = a * d; val bd = b * d
        val middle = (bd shr 32) + (ad and mask) + (bc and mask) + (1UL shl 31)
        return ac + (ad shr 32) + (bc shr 32) + (middle shr 32)
    }

    private fun generate(value: Fp, initialError: ULong, difference: ULong): Generated {
        val shift = -value.exponent
        check(shift in 32..60)
        val one = 1UL shl shift
        var integral = value.significand shr shift
        var fractional = value.significand and (one - 1UL)
        var exponent = integral.toString().length
        val handler = Shortest(difference)
        do {
            val divisor = powers[exponent - 1]
            val digit = (integral / divisor).toInt()
            integral %= divisor
            exponent--
            val remainder = (integral shl shift) + fractional
            val status = handler.digit(digit, powers[exponent] shl shift, remainder, initialError, exponent, true)
            if (status != Status.MORE) return Generated(handler.text(), exponent, status == Status.ERROR)
        } while (exponent > 0)
        var error = initialError
        while (true) {
            fractional *= 10UL
            error *= 10UL
            val digit = (fractional shr shift).toInt()
            fractional = fractional and (one - 1UL)
            exponent--
            val status = handler.digit(digit, one, fractional, error, exponent, false)
            if (status != Status.MORE) return Generated(handler.text(), exponent, status == Status.ERROR)
        }
    }

    private class Shortest(private val difference: ULong) {
        private val buffer = StringBuilder()
        fun text(): String = buffer.toString()

        fun digit(digit: Int, divisor: ULong, initialRemainder: ULong, error: ULong, exponent: Int, integral: Boolean): Status {
            check(digit in 0..9 && buffer.length < 20)
            buffer.append(('0'.code + digit).toChar())
            if (initialRemainder >= error) return Status.MORE
            val unit = if (integral) 1UL else powers[-exponent]
            val remainder = round((difference - 1UL) * unit, divisor, initialRemainder, error)
            if (canMove((difference + 1UL) * unit, divisor, remainder, error, false)) return Status.ERROR
            return if (2UL * unit <= remainder && remainder <= error - 4UL * unit) Status.DONE else Status.ERROR
        }

        private fun round(distance: ULong, divisor: ULong, initial: ULong, error: ULong): ULong {
            var remainder = initial
            while (canMove(distance, divisor, remainder, error, true)) {
                buffer.setCharAt(buffer.lastIndex, buffer.last() - 1)
                remainder += divisor
            }
            return remainder
        }

        private fun canMove(distance: ULong, divisor: ULong, remainder: ULong, error: ULong, includeTie: Boolean): Boolean {
            if (remainder >= distance || error - remainder < divisor) return false
            val next = remainder + divisor
            if (next < distance) return true
            val before = distance - remainder
            val after = next - distance
            return if (includeTie) before >= after else before > after
        }
    }

    private data class Fraction(val numerator: BigInteger, val denominator: BigInteger, val lower: BigInteger, val upper: BigInteger)

    private fun fraction(binary: Binary, exponent: Int): Fraction {
        val (f, e) = binary.value
        val shift = if (binary.predecessorCloser) 2 else 1
        val significand = BigInteger.valueOf(f.toLong()).shiftLeft(shift)
        if (e >= 0) {
            val lower = BigInteger.ONE.shiftLeft(e)
            val upper = if (binary.predecessorCloser) lower.shiftLeft(1) else lower
            return Fraction(significand.shiftLeft(e), BigInteger.TEN.pow(exponent).shiftLeft(1), lower, upper)
        }
        if (exponent < 0) {
            val lower = BigInteger.TEN.pow(-exponent)
            val upper = if (binary.predecessorCloser) lower.shiftLeft(1) else lower
            return Fraction(lower * significand, BigInteger.ONE.shiftLeft(shift - e), lower, upper)
        }
        val upper = if (binary.predecessorCloser) BigInteger.TWO else BigInteger.ONE
        return Fraction(significand, BigInteger.TEN.pow(exponent).shiftLeft(shift - e), BigInteger.ONE, upper)
    }

    private fun fallback(binary: Binary, exponent: Int): Decimal {
        val fraction = fraction(binary, exponent)
        var numerator = fraction.numerator
        var lower = fraction.lower
        var upper = fraction.upper
        val even = (binary.value.significand and 1UL) == 0UL
        val buffer = StringBuilder()
        while (true) {
            check(buffer.length < 20)
            val divided = numerator.divideAndRemainder(fraction.denominator)
            val digit = divided[0].toInt()
            check(digit in 0..9)
            numerator = divided[1]
            buffer.append(('0'.code + digit).toChar())
            val low = numerator.compareTo(lower) <= if (even) 0 else -1
            val high = (numerator + upper).compareTo(fraction.denominator) >= if (even) 0 else 1
            if (low || high) {
                if (roundUp(low, high, numerator, fraction.denominator, digit)) buffer.setCharAt(buffer.lastIndex, buffer.last() + 1)
                return Decimal(buffer.toString(), exponent - buffer.length + 1, true)
            }
            numerator *= BigInteger.TEN
            lower *= BigInteger.TEN
            upper *= BigInteger.TEN
        }
    }

    private fun roundUp(low: Boolean, high: Boolean, remainder: BigInteger, denominator: BigInteger, digit: Int): Boolean {
        if (!low) return true
        if (!high) return false
        val comparison = remainder.shiftLeft(1).compareTo(denominator)
        return comparison > 0 || (comparison == 0 && digit % 2 != 0)
    }
}
