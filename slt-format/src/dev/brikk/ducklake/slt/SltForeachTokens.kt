package dev.brikk.ducklake.slt

/**
 * `foreach` value-token expansion, mirroring upstream `SQLLogicTestRunner::ForEachTokenReplace`
 * (`test/sqlite/sqllogic_test_runner.cpp`) token for token, in upstream order:
 *
 *  - `<signed>`   → tinyint smallint integer bigint hugeint
 *  - `<unsigned>` → utinyint usmallint uinteger ubigint uhugeint
 *  - `<integral>` → `<signed>` + `<unsigned>`
 *  - `<numeric>`  → `<integral>` + float double
 *  - `<alltypes>` → `<numeric>` + bool interval varchar
 *  - `<compression>` → none uncompressed rle bitpacking dictionary fsst dict_fsst alp alprd
 *  - `<all_types_columns>` → the column names of DuckDB's `test_all_types()` table function
 *  - `!tok` removes the (case-sensitive) literal `tok` from the values collected SO FAR; when it is
 *    not present the `!tok` token is kept verbatim as a value, exactly as upstream does.
 *
 * Special tokens are matched case-insensitively (upstream lower-cases before comparing).
 */
object SltForeachTokens {

    val SIGNED: List<String> = listOf("tinyint", "smallint", "integer", "bigint", "hugeint")
    val UNSIGNED: List<String> = listOf("utinyint", "usmallint", "uinteger", "ubigint", "uhugeint")
    val FLOATING: List<String> = listOf("float", "double")
    val ALL_EXTRA: List<String> = listOf("bool", "interval", "varchar")
    val COMPRESSION: List<String> =
        listOf("none", "uncompressed", "rle", "bitpacking", "dictionary", "fsst", "dict_fsst", "alp", "alprd")
    val ALL_TYPES_COLUMNS: List<String> = listOf(
        "bool", "tinyint", "smallint", "int", "bigint", "hugeint", "uhugeint", "utinyint", "usmallint", "uint",
        "ubigint", "date", "time", "timestamp", "timestamp_s", "timestamp_ms", "timestamp_ns", "time_tz",
        "timestamp_tz", "float", "double", "dec_4_1", "dec_9_4", "dec_18_6", "dec38_10", "uuid", "interval",
        "varchar", "blob", "bit", "small_enum", "medium_enum", "large_enum", "int_array", "double_array",
        "date_array", "timestamp_array", "timestamptz_array", "varchar_array", "nested_int_array", "struct",
        "struct_of_arrays", "array_of_structs", "map", "union", "fixed_int_array", "fixed_varchar_array",
        "fixed_nested_int_array", "fixed_nested_varchar_array", "fixed_struct_array", "struct_of_fixed_array",
        "fixed_array_of_int_list", "list_of_fixed_int_array",
    )

    /** Expands the raw value tokens of a `foreach` directive (everything after the iterator name). */
    fun expand(tokens: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (token in tokens) {
            if (!replace(token, result)) {
                result += token
            }
        }
        return result
    }

    /** Upstream `ForEachTokenReplace`: true when [token] was consumed as a collection / exclusion. */
    private fun replace(token: String, result: MutableList<String>): Boolean {
        if (token.isEmpty()) return true
        val name = token.lowercase().trim()
        if (name[0] == '!') {
            // !token removes the token from the values collected so far; not found → keep as-is.
            return result.remove(token.substring(1))
        }
        val collection = COLLECTIONS[name] ?: return false
        result += collection
        return true
    }

    private val COLLECTIONS: Map<String, List<String>> = mapOf(
        "<signed>" to SIGNED,
        "<unsigned>" to UNSIGNED,
        "<integral>" to SIGNED + UNSIGNED,
        "<numeric>" to SIGNED + UNSIGNED + FLOATING,
        "<alltypes>" to SIGNED + UNSIGNED + FLOATING + ALL_EXTRA,
        "<compression>" to COMPRESSION,
        "<all_types_columns>" to ALL_TYPES_COLUMNS,
    )
}
