package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.Random

/** H15: native 2.0 text is the oracle; expected values must not depend on the test JVM's formatter. */
internal class TestFloatingTextPortability {
    @Test fun ordinaryDoubleDigitsAreIndependentOfTheJdkFormatter() {
        assertThat(DuckDbText.double(1e23)).isEqualTo("1e+23")
        assertThat(DuckDbText.double(-1e23)).isEqualTo("-1e+23")
        assertThat(DuckDbText.float(java.lang.Float.MIN_NORMAL)).isEqualTo("1.1754944e-38")
    }

    @Test fun smallestSubnormalsUseNativeShortestDigitsRatherThanJavaMinimumDigitPolicy() {
        assertThat(DuckDbText.double(Double.MIN_VALUE)).isEqualTo("5e-324")
        assertThat(DuckDbText.double(Double.MIN_VALUE * 2)).isEqualTo("1e-323")
        assertThat(DuckDbText.float(Float.MIN_VALUE)).isEqualTo("1e-45")
        assertThat(DuckDbText.float(Float.MIN_VALUE * 2)).isEqualTo("3e-45")
        assertThat(DuckDbText.double(-0.0)).isEqualTo("-0.0")
        assertThat(DuckDbText.float(-0.0f)).isEqualTo("-0.0")
    }

    @Test fun nativeFloatFallbackRetainsPromotedDoubleDigits() {
        val value = Float.fromBits(0xca762bff.toInt())
        assertThat(DuckDbText.float(value)).isEqualTo("-4033279.75")
        assertThat(DuckDbGrisu.digits(-value.toDouble(), true).usedFallback).isTrue()
        assertThat(DuckDbGrisu.digits(java.lang.Float.MIN_NORMAL.toDouble(), true).usedFallback).isFalse()
    }

    @Test fun negativeNanSignIsPreservedLikeTheNativeFormatter() {
        assertThat(DuckDbText.double(Double.fromBits(0xfff8000000000001UL.toLong()))).isEqualTo("-nan")
        assertThat(DuckDbText.float(Float.fromBits(0xffc00001.toInt()))).isEqualTo("-nan")
    }

    @Test fun nativeScalarBoundariesSubnormalsAndDeterministicRawBitsAgreeExactly() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            requirePinnedCore(c)
            c.prepareStatement("SELECT ?::DOUBLE,?::FLOAT,CAST(?::DOUBLE AS VARCHAR),CAST(?::FLOAT AS VARCHAR)").use { p ->
                fun verify(d: Double, f: Float) {
                    p.setDouble(1, d); p.setFloat(2, f); p.setDouble(3, d); p.setFloat(4, f)
                    p.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        if (d.isNaN()) assertThat(rows.getDouble(1)).isNaN()
                        else assertThat(rows.getDouble(1).toRawBits()).isEqualTo(d.toRawBits())
                        if (f.isNaN()) assertThat(rows.getFloat(2)).isNaN()
                        else assertThat(rows.getFloat(2).toRawBits()).isEqualTo(f.toRawBits())
                        assertThat(DuckDbText.double(d)).`as`("DOUBLE bits=${d.toRawBits().toString(16)}").isEqualTo(rows.getString(3))
                        assertThat(DuckDbText.float(f)).`as`("FLOAT bits=${f.toRawBits().toString(16)}").isEqualTo(rows.getString(4))
                        assertThat(rows.next()).isFalse()
                    }
                }
                for (d in listOf(1e23, Math.nextDown(1e23), Math.nextUp(1e23), 0.0, -0.0, 1e-4, 1e16,
                    Math.nextDown(1e-4), Math.nextUp(1e-4), Math.nextDown(1e16), Math.nextUp(1e16),
                    java.lang.Double.MIN_NORMAL, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                    verify(d, java.lang.Float.MIN_NORMAL)
                }
                for (f in listOf(0.0f, -0.0f, java.lang.Float.MIN_NORMAL, Math.nextDown(java.lang.Float.MIN_NORMAL),
                    Math.nextUp(java.lang.Float.MIN_NORMAL), Float.MAX_VALUE, -Float.MAX_VALUE, 1e-4f, 1e16f,
                    Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.fromBits(0xca762bff.toInt()))) verify(1e23, f)
                for (bits in 1L..256L) {
                    verify(Double.fromBits(bits), Float.fromBits(bits.toInt()))
                    verify(-Double.fromBits(bits), -Float.fromBits(bits.toInt()))
                }
                val random = Random(0x15f10a7L)
                repeat(2048) { verify(Double.fromBits(random.nextLong()), Float.fromBits(random.nextInt())) }
            }
        }
    }

    @Test fun nestedListAndStructCellsUseTheSameNativeDigitsAndPreserveNulls() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            requirePinnedCore(c)
            c.prepareStatement("SELECT [?::DOUBLE,?::DOUBLE,NULL],CAST([?::DOUBLE,?::DOUBLE,NULL] AS VARCHAR)," +
                "struct_pack(d:=?::DOUBLE,f:=?::FLOAT),CAST(struct_pack(d:=?::DOUBLE,f:=?::FLOAT) AS VARCHAR)").use { p ->
                p.setDouble(1, 1e23); p.setDouble(2, Double.MIN_VALUE)
                p.setDouble(3, 1e23); p.setDouble(4, Double.MIN_VALUE)
                p.setDouble(5, 1e23); p.setFloat(6, java.lang.Float.MIN_NORMAL)
                p.setDouble(7, 1e23); p.setFloat(8, java.lang.Float.MIN_NORMAL)
                p.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(GoldenComparator.renderCell(rows.getObject(1))).isEqualTo(rows.getString(2))
                    assertThat(GoldenComparator.renderCell(rows.getObject(3))).isEqualTo(rows.getString(4))
                    assertThat(GoldenComparator.renderCell(rows.getObject(1))).isEqualTo("[1e+23, 5e-324, NULL]")
                }
            }
        }
    }

    private fun requirePinnedCore(c: Connection) {
        c.createStatement().use { s -> s.executeQuery("PRAGMA version").use { rows ->
            assertThat(rows.next()).isTrue()
            assertThat(rows.getString(1)).isEqualTo("v2.0.0")
            assertThat(rows.getString(2)).isEqualTo("d54cbbe3df")
        } }
    }
}
