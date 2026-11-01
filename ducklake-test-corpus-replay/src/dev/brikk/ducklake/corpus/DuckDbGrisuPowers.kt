/*
 * Adapted from fmt as vendored by DuckDB d54cbbe3df17e8e5ce68312f483b4155836112aa.
 * Copyright (c) 2012 - present, Victor Zverovich.
 * MIT license: resources/META-INF/licenses/duckdb-fmt.txt.
 */
@file:Suppress("MagicNumber") // Exact cached constants and index formula from the pinned numerical algorithm.

package dev.brikk.ducklake.corpus

internal object DuckDbGrisuPowers {
    data class Power(val significand: ULong, val exponent: Int, val decimalExponent: Int)

    fun get(minimumExponent: Int): Power {
        val estimate = (((minimumExponent + 63).toLong() * 0x4d104d42L + 0xffffffffL) shr 32).toInt()
        val index = (estimate + 348 - 1) / 8 + 1
        return Power(significands[index], exponents[index], -348 + index * 8)
    }

    private val significands = listOf(
        0xfa8fd5a0081c0288UL, 0xbaaee17fa23ebf76UL, 0x8b16fb203055ac76UL,
        0xcf42894a5dce35eaUL, 0x9a6bb0aa55653b2dUL, 0xe61acf033d1a45dfUL,
        0xab70fe17c79ac6caUL, 0xff77b1fcbebcdc4fUL, 0xbe5691ef416bd60cUL,
        0x8dd01fad907ffc3cUL, 0xd3515c2831559a83UL, 0x9d71ac8fada6c9b5UL,
        0xea9c227723ee8bcbUL, 0xaecc49914078536dUL, 0x823c12795db6ce57UL,
        0xc21094364dfb5637UL, 0x9096ea6f3848984fUL, 0xd77485cb25823ac7UL,
        0xa086cfcd97bf97f4UL, 0xef340a98172aace5UL, 0xb23867fb2a35b28eUL,
        0x84c8d4dfd2c63f3bUL, 0xc5dd44271ad3cdbaUL, 0x936b9fcebb25c996UL,
        0xdbac6c247d62a584UL, 0xa3ab66580d5fdaf6UL, 0xf3e2f893dec3f126UL,
        0xb5b5ada8aaff80b8UL, 0x87625f056c7c4a8bUL, 0xc9bcff6034c13053UL,
        0x964e858c91ba2655UL, 0xdff9772470297ebdUL, 0xa6dfbd9fb8e5b88fUL,
        0xf8a95fcf88747d94UL, 0xb94470938fa89bcfUL, 0x8a08f0f8bf0f156bUL,
        0xcdb02555653131b6UL, 0x993fe2c6d07b7facUL, 0xe45c10c42a2b3b06UL,
        0xaa242499697392d3UL, 0xfd87b5f28300ca0eUL, 0xbce5086492111aebUL,
        0x8cbccc096f5088ccUL, 0xd1b71758e219652cUL, 0x9c40000000000000UL,
        0xe8d4a51000000000UL, 0xad78ebc5ac620000UL, 0x813f3978f8940984UL,
        0xc097ce7bc90715b3UL, 0x8f7e32ce7bea5c70UL, 0xd5d238a4abe98068UL,
        0x9f4f2726179a2245UL, 0xed63a231d4c4fb27UL, 0xb0de65388cc8ada8UL,
        0x83c7088e1aab65dbUL, 0xc45d1df942711d9aUL, 0x924d692ca61be758UL,
        0xda01ee641a708deaUL, 0xa26da3999aef774aUL, 0xf209787bb47d6b85UL,
        0xb454e4a179dd1877UL, 0x865b86925b9bc5c2UL, 0xc83553c5c8965d3dUL,
        0x952ab45cfa97a0b3UL, 0xde469fbd99a05fe3UL, 0xa59bc234db398c25UL,
        0xf6c69a72a3989f5cUL, 0xb7dcbf5354e9beceUL, 0x88fcf317f22241e2UL,
        0xcc20ce9bd35c78a5UL, 0x98165af37b2153dfUL, 0xe2a0b5dc971f303aUL,
        0xa8d9d1535ce3b396UL, 0xfb9b7cd9a4a7443cUL, 0xbb764c4ca7a44410UL,
        0x8bab8eefb6409c1aUL, 0xd01fef10a657842cUL, 0x9b10a4e5e9913129UL,
        0xe7109bfba19c0c9dUL, 0xac2820d9623bf429UL, 0x80444b5e7aa7cf85UL,
        0xbf21e44003acdd2dUL, 0x8e679c2f5e44ff8fUL, 0xd433179d9c8cb841UL,
        0x9e19db92b4e31ba9UL, 0xeb96bf6ebadf77d9UL, 0xaf87023b9bf0ee6bUL,
    )
    private val exponents = listOf(
        -1220, -1193, -1166, -1140, -1113, -1087, -1060, -1034, -1007, -980, -954,
        -927, -901, -874, -847, -821, -794, -768, -741, -715, -688, -661,
        -635, -608, -582, -555, -529, -502, -475, -449, -422, -396, -369,
        -343, -316, -289, -263, -236, -210, -183, -157, -130, -103, -77,
        -50, -24, 3, 30, 56, 83, 109, 136, 162, 189, 216,
        242, 269, 295, 322, 348, 375, 402, 428, 455, 481, 508,
        534, 561, 588, 614, 641, 667, 694, 720, 747, 774, 800,
        827, 853, 880, 907, 933, 960, 986, 1013, 1039, 1066,
    )
}
