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

/**
 * The DuckLake spec's quoted-value / quoted-list text encoding, byte-for-byte
 * compatible with upstream `DuckLakeUtil::ToQuotedList` / `ParseQuotedList` /
 * `ParseQuotedValue` (`common/ducklake_util.cpp`).
 *
 * Each value is wrapped in double quotes with embedded `"` escaped by doubling
 * (`KeywordHelper::WriteQuoted`); values are joined by a bare separator
 * (`,` by default). The empty list encodes as the empty string.
 *
 * Used by: `ducklake_view.column_aliases`, `ducklake_snapshot_changes.changes_made`
 * (`created_schema:"s"`, `created_table:"s"."t"`), and anything else the spec
 * stores as a quoted list. Upstream throws at catalog-load time if
 * `column_aliases` is not in this form, so every writer MUST go through
 * [encode]/[encodeValue].
 */
object DucklakeQuotedList {
    const val DEFAULT_SEPARATOR: Char = ','

    /** A parsed quoted value and the position just past its closing quote. */
    data class Parsed(val value: String, val endPos: Int)

    /** `"..."` with embedded `"` doubled — upstream `KeywordHelper::WriteQuoted(str, '"')`. */
    @JvmStatic
    fun encodeValue(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    /** Upstream `DuckLakeUtil::ToQuotedList`. Empty list → empty string. */
    @JvmStatic
    @JvmOverloads
    fun encode(values: List<String>, separator: Char = DEFAULT_SEPARATOR): String =
        values.joinToString(separator.toString()) { encodeValue(it) }

    /**
     * Upstream `DuckLakeUtil::ParseQuotedValue`: expects `"` at [startPos], scans to the
     * matching `"`, treating `""` as an escaped literal quote.
     *
     * @throws IllegalArgumentException on a missing opening quote or an unterminated value.
     */
    @JvmStatic
    fun parseValue(input: String, startPos: Int): Parsed {
        if (startPos >= input.length || input[startPos] != '"') {
            throw IllegalArgumentException(
                "Failed to parse quoted value - expected a quote at position $startPos in: $input",
            )
        }
        val sb = StringBuilder()
        var p = startPos + 1
        while (p < input.length) {
            val c = input[p]
            if (c == '"') {
                p++
                if (p < input.length && input[p] == '"') {
                    sb.append('"')
                    p++
                    continue
                }
                return Parsed(sb.toString(), p)
            }
            sb.append(c)
            p++
        }
        throw IllegalArgumentException("Failed to parse quoted value - unterminated quote in: $input")
    }

    /**
     * Upstream `DuckLakeUtil::ParseQuotedList`. `null` and the empty string both decode
     * to the empty list (upstream only sees non-null VARCHAR; we tolerate SQL NULL from
     * other writers so a missing alias list doesn't take the whole view down).
     *
     * @throws IllegalArgumentException if the text is not a well-formed quoted list —
     *   callers surface this as "written by an incompatible writer", never as an empty list.
     */
    @JvmStatic
    @JvmOverloads
    fun parse(input: String?, separator: Char = DEFAULT_SEPARATOR): List<String> {
        if (input.isNullOrEmpty()) {
            return emptyList()
        }
        val result = ArrayList<String>()
        var pos = 0
        while (true) {
            val parsed = parseValue(input, pos)
            result.add(parsed.value)
            pos = parsed.endPos
            if (pos >= input.length) {
                break
            }
            if (input[pos] != separator) {
                throw IllegalArgumentException("Failed to parse list - expected a '$separator' at position $pos in: $input")
            }
            pos++
        }
        return result
    }

    /** True iff [input] is a well-formed quoted list (or null/empty). */
    @JvmStatic
    fun isWellFormed(input: String?): Boolean =
        try {
            parse(input)
            true
        }
        catch (_: IllegalArgumentException) {
            false
        }
}
