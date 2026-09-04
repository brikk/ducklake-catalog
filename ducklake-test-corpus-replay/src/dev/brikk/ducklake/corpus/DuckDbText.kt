package dev.brikk.ducklake.corpus

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * DuckDB's own text renderings (what `x::VARCHAR` / `Value::ToString()` produce) for the value
 * kinds where the JDBC driver hands back a Java object whose `toString` differs.
 *
 * Floating point: DuckDB casts DOUBLE/FLOAT with `duckdb_fmt::format("{}", v)`
 * (`src/common/operator/string_cast.cpp`), i.e. the shortest round-trip digit string laid out by
 * fmt's `float_writer` (`third_party/fmt/include/fmt/format.h`, `float_writer::float_writer` +
 * `prettify`) with the default spec (`general`, no precision → `trailing_zeros` on, precision
 * threshold 16):
 *  - exponent notation when the decimal exponent `e` (value = d.ddd × 10^e) is outside `[-4, 16)`:
 *    `1e+16`, `1.5e-07` (sign always written, at least two exponent digits);
 *  - integral values keep a `.0`: `12345678.0`, `100.0`, `-0.0`;
 *  - otherwise plain positional: `0.0001`, `123.456`, `0.30000000000000004`;
 *  - non-finite: `inf`, `-inf`, `nan`.
 * The digits come from `Double.toString`/`Float.toString`, which produce the shortest
 * round-trip representation on JDK 19+ (JDK-4511638); the only known divergence is for
 * subnormals whose shortest form is one digit (`5e-324` vs Java's `4.9E-324`).
 *
 * Temporal: `'infinity'::TIMESTAMP` is `int64::max` microseconds (`timestamp_t::infinity()`),
 * `-infinity` is its negation; DATE uses `int32::max` days. The driver returns those as ordinary
 * `Timestamp`/`LocalDate` values (year 294247 / 5881580); DuckDB prints `infinity` / `-infinity`
 * (`src/common/types/timestamp.cpp` `Timestamp::ToString`, `date.cpp` `Date::ToString`).
 */
object DuckDbText {

    /** fmt's default general-format precision threshold: exponents `>= 16` switch to `e` notation. */
    private const val EXP_UPPER = 16

    /** Exponents `< -4` switch to `e` notation (mirrors `%g`). */
    private const val EXP_LOWER = -4

    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L

    /** `timestamp_t::infinity()` (`int64::max` µs since epoch) as the value the JDBC driver yields. */
    val TIMESTAMP_INFINITY: LocalDateTime = microsToLocalDateTime(Long.MAX_VALUE)

    /** `timestamp_t::ninfinity()` — `-int64::max`, NOT `int64::min`. */
    val TIMESTAMP_NEGATIVE_INFINITY: LocalDateTime = microsToLocalDateTime(-Long.MAX_VALUE)

    /** `date_t::infinity()` — `int32::max` days since epoch. */
    val DATE_INFINITY: LocalDate = LocalDate.ofEpochDay(Int.MAX_VALUE.toLong())

    /** `date_t::ninfinity()` — `-int32::max` days. */
    val DATE_NEGATIVE_INFINITY: LocalDate = LocalDate.ofEpochDay(-Int.MAX_VALUE.toLong())

    fun double(d: Double): String {
        if (d.isNaN()) return "nan"
        if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
        // compareTo is the IEEE total order: -0.0 sorts below 0.0, so the sign bit is honoured (fmt
        // uses std::signbit and prints "-0.0").
        return signed(d.compareTo(0.0) < 0, Math.abs(d).toString())
    }

    fun float(f: Float): String {
        if (f.isNaN()) return "nan"
        if (f.isInfinite()) return if (f > 0) "inf" else "-inf"
        return signed(f.compareTo(0.0f) < 0, Math.abs(f).toString())
    }

    /** `infinity` / `-infinity` for the two sentinel timestamps, null for a regular value. */
    fun timestampSpecial(dt: LocalDateTime): String? =
        when (dt) {
            TIMESTAMP_INFINITY -> "infinity"
            TIMESTAMP_NEGATIVE_INFINITY -> "-infinity"
            else -> null
        }

    /** `infinity` / `-infinity` for the two sentinel dates, null for a regular value. */
    fun dateSpecial(date: LocalDate): String? =
        when (date) {
            DATE_INFINITY -> "infinity"
            DATE_NEGATIVE_INFINITY -> "-infinity"
            else -> null
        }

    private fun signed(negative: Boolean, javaAbs: String): String {
        val bd = BigDecimal(javaAbs).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val body = prettify(digits, -bd.scale())
        return if (negative) "-$body" else body
    }

    /**
     * fmt `float_writer::prettify` for the default spec, given value = [digits] × 10^[exp] with
     * [digits] free of leading/trailing zeros (except the single `0` for zero).
     */
    internal fun prettify(digits: String, exp: Int): String {
        val n = digits.length
        val integerDigits = n + exp // fmt's `full_exp` inside prettify
        val sciExp = integerDigits - 1
        return when {
            sciExp < EXP_LOWER || sciExp >= EXP_UPPER -> exponential(digits, sciExp)
            // 1234e7 -> 12340000000.0 (trailing_zeros → ".0" since precision is unset)
            exp >= 0 -> digits + "0".repeat(exp) + ".0"
            // 1234e-2 -> 12.34
            integerDigits > 0 -> digits.substring(0, integerDigits) + "." + digits.substring(integerDigits)
            // 1234e-6 -> 0.001234
            else -> "0." + "0".repeat(-integerDigits) + digits
        }
    }

    private fun exponential(digits: String, sciExp: Int): String {
        val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
        val sign = if (sciExp < 0) "-" else "+"
        val magnitude = Math.abs(sciExp).toString().padStart(2, '0')
        return "${mantissa}e$sign$magnitude"
    }

    private fun microsToLocalDateTime(micros: Long): LocalDateTime =
        LocalDateTime.ofEpochSecond(
            Math.floorDiv(micros, MICROS_PER_SECOND),
            (Math.floorMod(micros, MICROS_PER_SECOND) * NANOS_PER_MICRO).toInt(),
            ZoneOffset.UTC,
        )
}
