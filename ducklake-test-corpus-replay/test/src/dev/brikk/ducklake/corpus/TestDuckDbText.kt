package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Expected strings were taken from DuckDB 1.5 itself (`SELECT (x::DOUBLE)::VARCHAR`), i.e. from
 * `duckdb_fmt::format("{}", v)` — see [DuckDbText] for the layout rules being reproduced.
 */
class TestDuckDbText {

    private val doubles = mapOf(
        // integral values keep ".0" up to the 16-digit threshold
        "12345678.0" to "12345678.0",
        "1e7" to "10000000.0",
        "100.0" to "100.0",
        "0.0" to "0.0",
        "1e15" to "1000000000000000.0",
        "9999999999999998.0" to "9999999999999998.0",
        // >= 1e16 switches to exponent notation (sign always written, two-digit minimum)
        "1e16" to "1e+16",
        "1e20" to "1e+20",
        "1.5e20" to "1.5e+20",
        "123456789012345678.0" to "1.2345678901234568e+17",
        "1.7976931348623157e308" to "1.7976931348623157e+308",
        "1e100" to "1e+100",
        // fixed notation down to 1e-4, exponent below that
        "0.1" to "0.1",
        "0.0001" to "0.0001",
        "0.00012345" to "0.00012345",
        "1e-5" to "1e-05",
        "2.5e-5" to "2.5e-05",
        "1.5e-7" to "1.5e-07",
        "1.2345678e-5" to "1.2345678e-05",
        "1e-100" to "1e-100",
        // shortest round-trip digits, no padding
        "123.456" to "123.456",
        "12345678.5" to "12345678.5",
        "999999999999999.9" to "999999999999999.9",
        "1.0000000000000002" to "1.0000000000000002",
        "0.30000000000000004" to "0.30000000000000004",
        // sign
        "-1.5e-7" to "-1.5e-07",
        "-123.5" to "-123.5",
    )

    @Test
    fun `double renders like duckdb_fmt`() {
        assertThat(doubles.mapValues { (input, _) -> DuckDbText.double(input.toDouble()) }).isEqualTo(doubles)
    }

    @Test
    fun `double special values`() {
        assertThat(DuckDbText.double(-0.0)).isEqualTo("-0.0")
        assertThat(DuckDbText.double(Double.NaN)).isEqualTo("nan")
        assertThat(DuckDbText.double(Double.POSITIVE_INFINITY)).isEqualTo("inf")
        assertThat(DuckDbText.double(Double.NEGATIVE_INFINITY)).isEqualTo("-inf")
        assertThat(DuckDbText.double(Double.MAX_VALUE)).isEqualTo("1.7976931348623157e+308")
    }

    private val floats = mapOf(
        "0.1" to "0.1",
        "1.5" to "1.5",
        "100.0" to "100.0",
        "123456.7" to "123456.7",
        "16777216.0" to "16777216.0",
        "1e10" to "10000000000.0",
        "1e16" to "1e+16",
        "3.4028235e38" to "3.4028235e+38",
        "1e-5" to "1e-05",
        "1.17549435e-38" to "1.1754944e-38",
    )

    @Test
    fun `float renders with float precision`() {
        assertThat(floats.mapValues { (input, _) -> DuckDbText.float(input.toFloat()) }).isEqualTo(floats)
    }

    @Test
    fun `float special values`() {
        assertThat(DuckDbText.float(-0.0f)).isEqualTo("-0.0")
        assertThat(DuckDbText.float(Float.NaN)).isEqualTo("nan")
        assertThat(DuckDbText.float(Float.POSITIVE_INFINITY)).isEqualTo("inf")
        assertThat(DuckDbText.float(Float.NEGATIVE_INFINITY)).isEqualTo("-inf")
    }

    @Test
    fun `prettify boundaries follow fmt thresholds exactly`() {
        // decimal exponent 15 is still fixed, 16 is exponential
        assertThat(DuckDbText.prettify("1", 15)).isEqualTo("1000000000000000.0")
        assertThat(DuckDbText.prettify("1", 16)).isEqualTo("1e+16")
        // decimal exponent -4 is still fixed, -5 is exponential
        assertThat(DuckDbText.prettify("1", -4)).isEqualTo("0.0001")
        assertThat(DuckDbText.prettify("1", -5)).isEqualTo("1e-05")
        // multi-digit mantissas
        assertThat(DuckDbText.prettify("15", 19)).isEqualTo("1.5e+20")
        assertThat(DuckDbText.prettify("1234", -2)).isEqualTo("12.34")
        assertThat(DuckDbText.prettify("1234", -6)).isEqualTo("0.001234")
        assertThat(DuckDbText.prettify("1234", 3)).isEqualTo("1234000.0")
        // three-digit exponents are not zero-padded further
        assertThat(DuckDbText.prettify("1", 100)).isEqualTo("1e+100")
        assertThat(DuckDbText.prettify("1", -100)).isEqualTo("1e-100")
    }

    @Test
    fun `timestamp infinities are the int64 max sentinels the driver hands back`() {
        // 'infinity'::TIMESTAMP arrives as 294247-01-10 04:00:54.775807 (int64::max microseconds)
        val inf = LocalDateTime.of(294247, 1, 10, 4, 0, 54, 775_807_000)
        assertThat(DuckDbText.TIMESTAMP_INFINITY).isEqualTo(inf)
        assertThat(DuckDbText.timestampSpecial(inf)).isEqualTo("infinity")
        assertThat(DuckDbText.timestampSpecial(DuckDbText.TIMESTAMP_NEGATIVE_INFINITY)).isEqualTo("-infinity")
        // -infinity is -int64::max, not int64::min: one microsecond above the minimum
        assertThat(DuckDbText.TIMESTAMP_NEGATIVE_INFINITY.nano).isEqualTo(224_193_000)
        assertThat(DuckDbText.timestampSpecial(LocalDateTime.of(2020, 1, 1, 0, 0))).isNull()
    }

    @Test
    fun `date infinities are the int32 max sentinels`() {
        assertThat(DuckDbText.dateSpecial(LocalDate.of(5881580, 7, 11))).isEqualTo("infinity")
        assertThat(DuckDbText.dateSpecial(LocalDate.of(-5877641, 6, 24))).isEqualTo("-infinity")
        assertThat(DuckDbText.dateSpecial(LocalDate.of(2020, 1, 1))).isNull()
    }
}
