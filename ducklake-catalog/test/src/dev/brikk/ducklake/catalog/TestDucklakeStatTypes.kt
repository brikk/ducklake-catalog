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
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Type-coverage matrix for the canonical stat comparator. The bug this guards
 * against is lexical comparison of numeric stats: lexically `"12" < "8"`
 * and `"-3" > "-10"`, which corrupts cross-file min/max merges and silently
 * mis-prunes data files. Every numeric canonical type must compare numerically;
 * every non-numeric type keeps (correct) lexical order.
 */
internal class TestDucklakeStatTypes {
    @Test
    fun numericTypesAreRecognized() {
        for (type in NUMERIC_TYPES) {
            assertThat(DucklakeStatTypes.isNumericType(type)).`as`(type).isTrue()
        }
    }

    @Test
    fun nonNumericTypesAreNotNumeric() {
        for (type in NON_NUMERIC_TYPES) {
            assertThat(DucklakeStatTypes.isNumericType(type)).`as`(type).isFalse()
        }
    }

    @Test
    fun int128AndUint128AreNumeric() {
        // Regression: the old isNumericType omitted these, so 128-bit columns
        // were parsed as raw strings and hard-pruned by lexical comparison.
        assertThat(DucklakeStatTypes.isNumericType("int128")).isTrue()
        assertThat(DucklakeStatTypes.isNumericType("uint128")).isTrue()
    }

    @Test
    fun numericCrossFileMergeIsNumeric() {
        // The canonical bug case: 12 > 8 numerically, but "12" < "8" lexically.
        for (type in NUMERIC_TYPES) {
            assertThat(DucklakeStatTypes.max("8", "12", type)).`as`(type).isEqualTo("12")
            assertThat(DucklakeStatTypes.min("8", "12", type)).`as`(type).isEqualTo("8")
        }
    }

    @Test
    fun negativeNumericMergeIsNumeric() {
        // -10 < -3 numerically, but "-10" > "-3" lexically.
        for (type in INTEGER_TYPES) {
            assertThat(DucklakeStatTypes.min("-3", "-10", type)).`as`(type).isEqualTo("-10")
            assertThat(DucklakeStatTypes.max("-3", "-10", type)).`as`(type).isEqualTo("-3")
        }
    }

    @Test
    fun floatMergeIsNumeric() {
        for (type in FLOAT_TYPES) {
            assertThat(DucklakeStatTypes.max("9.5", "12.25", type)).`as`(type).isEqualTo("12.25")
            assertThat(DucklakeStatTypes.min("9.5", "12.25", type)).`as`(type).isEqualTo("9.5")
        }
    }

    @Test
    fun int128FullRangeDoesNotOverflow() {
        val big = "170141183460469231731687303715884105727" // 2^127 - 1
        val bigger = "170141183460469231731687303715884105728" // would overflow long
        assertThat(DucklakeStatTypes.max(big, bigger, "int128")).isEqualTo(bigger)
    }

    @Test
    fun booleanComparesLexicallyButCorrectly() {
        // "false" < "true" lexically == false < true semantically.
        assertThat(DucklakeStatTypes.min("true", "false", "boolean")).isEqualTo("false")
        assertThat(DucklakeStatTypes.max("true", "false", "boolean")).isEqualTo("true")
    }

    @Test
    fun isoDatesAndTimestampsCompareAsValues() {
        assertThat(DucklakeStatTypes.max("2024-01-15", "2024-12-01", "date")).isEqualTo("2024-12-01")
        assertThat(DucklakeStatTypes.max("2024-01-15T08:00", "2024-01-15T17:30", "timestamp"))
                .isEqualTo("2024-01-15T17:30")
    }

    @Test
    fun temporalSpellingsFromDuckDbAndIsoCompareCorrectly() {
        // DuckDB writes `2024-01-01 00:00:00`, ISO writers `2024-01-01T00:00:00`: lexically ' ' < 'T'
        // would make the ISO value the larger one regardless of the instant.
        assertThat(DucklakeStatTypes.compare("2024-01-01 12:00:00", "2024-01-01T09:00:00", "timestamp")).isPositive()
        assertThat(DucklakeStatTypes.max("2024-01-01 12:00:00", "2024-01-01T09:00:00", "timestamp"))
                .isEqualTo("2024-01-01 12:00:00")
        // Fractions and zone offsets are honoured for timestamptz.
        assertThat(DucklakeStatTypes.compare("2024-01-01 12:00:00.5+00", "2024-01-01T13:00:00+02:00", "timestamptz"))
                .`as`("12:00:00.5Z is later than 11:00Z").isPositive()
        assertThat(DucklakeStatTypes.compare("2024-01-01 12:00:00+00", "2024-01-01T12:00:00Z", "timestamptz")).isZero()
        assertThat(DucklakeStatTypes.compare("09:30:00", "9:30:00.000001".padStart(15, '0'), "time")).isNegative()
        assertThat(DucklakeStatTypes.compare("infinity", "2999-12-31 23:59:59", "timestamp")).isPositive()
        assertThat(DucklakeStatTypes.compare("-infinity", "0001-01-01", "date")).isNegative()
        // Unparseable temporal text is unknown, never a guess.
        assertThat(DucklakeStatTypes.parseStat("date", "yesterday")).isNull()
        assertThat(DucklakeStatTypes.compareOrNull("yesterday", "2024-01-01", "date")).isNull()
    }

    @Test
    fun blobAndIntervalAreNeverPrunedOrMerged() {
        assertThat(DucklakeStatTypes.comparisonClass("blob")).isEqualTo(DucklakeStatTypes.ComparisonClass.UNCOMPARABLE)
        assertThat(DucklakeStatTypes.parseStat("blob", "\\x00\\xFF")).isNull()
        assertThat(DucklakeStatTypes.parseStat("interval", "1 year")).isNull()
        val acc = DucklakeStatTypes.BoundsAccumulator("blob")
        acc.merge("a", "b")
        assertThat(acc.min).isNull()
        assertThat(acc.max).isNull()
    }

    @Test
    fun varcharComparesByCodePointNotUtf16Unit() {
        // U+FF5E (BMP, one UTF-16 unit) vs U+1F600 (supplementary, surrogate pair 0xD83D 0xDE00):
        // UTF-16 unit order says U+1F600 < U+FF5E; code point / UTF-8 order says the opposite.
        val bmp = "\uFF5E"
        val supplementary = "\uD83D\uDE00"
        assertThat(bmp.compareTo(supplementary)).`as`("String.compareTo (UTF-16) disagrees").isPositive()
        assertThat(DucklakeStatTypes.compare(bmp, supplementary, "varchar")).isNegative()
        assertThat(DucklakeStatTypes.max(bmp, supplementary, "varchar")).isEqualTo(supplementary)
    }

    @Test
    fun boundsAccumulatorFollowsUpstreamMergeStats() {
        // All-NULL file contributes nothing; first bounded file seeds.
        val acc = DucklakeStatTypes.BoundsAccumulator("int64")
        acc.merge(null, null, 0L, 3L)
        assertThat(acc.min).isNull()
        acc.merge("10", "20")
        assertThat(acc.min).isEqualTo("10")
        assertThat(acc.max).isEqualTo("20")
        // Numeric, not lexical.
        acc.merge("9", "100")
        assertThat(acc.min).isEqualTo("9")
        assertThat(acc.max).isEqualTo("100")
        // A later all-NULL file still contributes nothing.
        acc.merge(null, null, 0L, 2L)
        assertThat(acc.min).isEqualTo("9")
        // A file with a max but no min poisons min — and it never comes back.
        acc.merge(null, "5")
        assertThat(acc.min).isNull()
        assertThat(acc.max).isEqualTo("100")
        acc.merge("1", "2")
        assertThat(acc.min).`as`("unknown does not recover").isNull()
        assertThat(acc.max).isEqualTo("100")
        // Unparseable under the type -> unknown too.
        val acc2 = DucklakeStatTypes.BoundsAccumulator("int64")
        acc2.merge("1", "2")
        acc2.merge("not-a-number", "3")
        assertThat(acc2.min).isNull()
        assertThat(acc2.max).isEqualTo("3")
        // A stored row has no counts, so NULL bounds cannot prove the existing column is all NULL.
        val seeded = DucklakeStatTypes.BoundsAccumulator("int64").seed(null, null)
        seeded.merge("4", "6")
        assertThat(seeded.min).isNull()
        val seeded2 = DucklakeStatTypes.BoundsAccumulator("int64").seed("0", "50")
        seeded2.merge(null, "60")
        assertThat(seeded2.min).isNull()
        assertThat(seeded2.max).isEqualTo("60")
    }

    @Test
    fun nonNullCountEqualToNullCountDoesNotMeanAllNull() {
        val acc = DucklakeStatTypes.BoundsAccumulator("int64")
        acc.merge(null, null, 1L, 1L)
        acc.merge("4", "6", 2L, 0L)
        assertThat(acc.min).isNull()
        assertThat(acc.max).isNull()
    }

    @Test
    fun incompleteCountsCannotProveAllNull() {
        val acc = DucklakeStatTypes.BoundsAccumulator("int64")
        acc.merge(null, null, 0L, null)
        acc.merge("4", "6", 2L, 0L)
        assertThat(acc.min).isNull()
        assertThat(acc.max).isNull()
    }

    @Test
    fun boundsAccumulatorUnknownFirstAbsorbsKnownBounds() {
        val acc = DucklakeStatTypes.BoundsAccumulator("int64")
        acc.merge(null, null)
        acc.merge("10", "20")
        assertThat(acc.min).isNull()
        assertThat(acc.max).isNull()
    }

    @Test
    fun boundsAccumulatorUnknownAfterKnownAbsorbsBounds() {
        val acc = DucklakeStatTypes.BoundsAccumulator("int64")
        acc.merge("10", "20")
        acc.merge(null, null)
        assertThat(acc.min).isNull()
        assertThat(acc.max).isNull()
        acc.merge("1", "100")
        assertThat(acc.min).isNull()
        assertThat(acc.max).isNull()
    }

    @Test
    fun boundsAccumulatorUnknownSeedDoesNotRecover() {
        val acc = DucklakeStatTypes.BoundsAccumulator("int64").seed(null, null)
        acc.merge("4", "6")
        assertThat(acc.min).isNull()
        assertThat(acc.max).isNull()
    }

    @Test
    fun boundsAccumulatorValidatesFirstMalformedMinimum() {
        val acc = DucklakeStatTypes.BoundsAccumulator("int64")
        acc.merge("not-a-number", "20")
        assertThat(acc.min).isNull()
        assertThat(acc.max).isEqualTo("20")
        acc.merge("1", "100")
        assertThat(acc.min).isNull()
        assertThat(acc.max).isEqualTo("100")
    }

    @Test
    fun boundsAccumulatorValidatesFirstMalformedMaximum() {
        val acc = DucklakeStatTypes.BoundsAccumulator("int64")
        acc.merge("10", "not-a-number")
        assertThat(acc.max).isNull()
        assertThat(acc.min).isEqualTo("10")
        acc.merge("1", "100")
        assertThat(acc.max).isNull()
        assertThat(acc.min).isEqualTo("1")
    }

    @Test
    fun varcharSortsLexically() {
        assertThat(DucklakeStatTypes.min("alpha", "delta", "varchar")).isEqualTo("alpha")
        assertThat(DucklakeStatTypes.max("alpha", "delta", "varchar")).isEqualTo("delta")
    }

    @Test
    fun parseStatRoundTrip() {
        assertThat(DucklakeStatTypes.parseStat("int64", "42")).isEqualTo(BigDecimal("42"))
        assertThat(DucklakeStatTypes.parseStat("int128", "170141183460469231731687303715884105727"))
                .isEqualTo(BigDecimal("170141183460469231731687303715884105727"))
        assertThat(DucklakeStatTypes.parseStat("decimal(10,2)", "3.14")).isEqualTo(BigDecimal("3.14"))
        assertThat(DucklakeStatTypes.parseStat("boolean", "true")).isEqualTo(java.lang.Boolean.TRUE)
        assertThat(DucklakeStatTypes.parseStat("varchar", "hello")).isEqualTo(DucklakeStatTypes.CodePointString("hello"))
        assertThat(DucklakeStatTypes.parseStat("int64", null)).isNull()
        assertThat(DucklakeStatTypes.parseStat("int64", "not-a-number")).isNull()
    }

    @Test
    fun typeNamesNormalizeCaseAndWhitespace() {
        assertThat(DucklakeStatTypes.isNumericType("  INT64 ")).isTrue()
        assertThat(DucklakeStatTypes.isNumericType("DECIMAL(10,2)")).isTrue()
    }

    @Test
    fun numericStatsSwappedDetectsInvertedNumericBounds() {
        // The read-side guard case. Lexically "100" < "20", so a lexical merge can persist
        // min=100, max=20 for a wide numeric — provably corrupt (min > max).
        assertThat(DucklakeStatTypes.numericStatsSwapped("decimal(38,0)", "100", "20")).isTrue()
        assertThat(DucklakeStatTypes.numericStatsSwapped("int32", "100", "20")).isTrue()
        // 128-bit range, beyond Long: still detected via BigDecimal.
        val hugeMax = "170141183460469231731687303715884105727" // 2^127 - 1
        assertThat(DucklakeStatTypes.numericStatsSwapped("int128", hugeMax, "20")).isTrue()
        // Negatives: -3 > -10 numerically, so (min=-3, max=-10) is swapped.
        assertThat(DucklakeStatTypes.numericStatsSwapped("int64", "-3", "-10")).isTrue()
    }

    @Test
    fun numericStatsNotSwappedWhenOrdered() {
        assertThat(DucklakeStatTypes.numericStatsSwapped("decimal(38,0)", "20", "100")).isFalse()
        assertThat(DucklakeStatTypes.numericStatsSwapped("int64", "-10", "-3")).isFalse()
        // Equal bounds (single-value file) are not swapped.
        assertThat(DucklakeStatTypes.numericStatsSwapped("int128", "42", "42")).isFalse()
    }

    @Test
    fun numericStatsSwappedIgnoresNonNumericTypes() {
        // Critical false-positive guard: for text, min="b" > max="az" in code-point order is a
        // LEGITIMATE surface form (collations differ), so it must NOT be flagged as corrupt.
        assertThat(DucklakeStatTypes.numericStatsSwapped("varchar", "b", "az")).isFalse()
        assertThat(DucklakeStatTypes.numericStatsSwapped("blob", "b", "az")).isFalse()
        // Temporal/boolean are stored as lexically-ordered strings; leave them unchanged.
        assertThat(DucklakeStatTypes.numericStatsSwapped("date", "2024-12-01", "2024-01-15")).isFalse()
        assertThat(DucklakeStatTypes.numericStatsSwapped("boolean", "true", "false")).isFalse()
    }

    @Test
    fun numericStatsSwappedFailSafeOnNullOrUnparseable() {
        // Never discard stats on uncertainty.
        assertThat(DucklakeStatTypes.numericStatsSwapped("int64", null, "10")).isFalse()
        assertThat(DucklakeStatTypes.numericStatsSwapped("int64", "10", null)).isFalse()
        assertThat(DucklakeStatTypes.numericStatsSwapped(null, "10", "1")).isFalse()
        // Non-finite / malformed numeric text parses to neither -> not flagged.
        assertThat(DucklakeStatTypes.numericStatsSwapped("float64", "Infinity", "1")).isFalse()
        assertThat(DucklakeStatTypes.numericStatsSwapped("int64", "not-a-number", "1")).isFalse()
    }

    companion object {
        private val INTEGER_TYPES = arrayOf(
                "int8", "int16", "int32", "int64", "int128",
                "uint8", "uint16", "uint32", "uint64", "uint128",
        )
        private val FLOAT_TYPES = arrayOf("float32", "float64")
        private val NUMERIC_TYPES = arrayOf(
                "int8", "int16", "int32", "int64", "int128",
                "uint8", "uint16", "uint32", "uint64", "uint128",
                "float32", "float64", "decimal(10,2)", "decimal(38,0)", "decimal(18,4)",
        )
        private val NON_NUMERIC_TYPES = arrayOf(
                "boolean", "date", "time", "timestamp", "timestamptz", "varchar", "blob", "uuid",
        )
    }
}
