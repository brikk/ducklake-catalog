package dev.brikk.ducklake.slt

/** Per-expansion character accounting, shared by pure expansion and the replay oracle. */
class SltExpansionBudget {
    private var allocatedChars = 0L
    private var scannedChars = 0L

    /** Charge output even when substitution reuses the original String. */
    fun retain(text: String): String {
        charge(text.length)
        return text
    }

    /** Charge inspection work that does not allocate replacement strings (e.g. conditions). */
    fun inspect(text: String) {
        SltLimits.check(text.length.toLong(), SltLimits.MAX_SOURCE_CHARS.toLong(), "text characters", 1)
        scan(text.length)
    }

    fun environment(env: Map<String, String>) {
        SltLimits.check(env.size.toLong(), SltLimits.MAX_RECORDS.toLong(), "environment entries", 1)
        var chars = 0L
        for ((key, value) in env) {
            chars += key.length.toLong() + value.length
            SltLimits.check(chars, SltLimits.MAX_SOURCE_CHARS.toLong(), "environment characters", 1)
        }
    }

    /** Size-check each append BEFORE allocating the expanded text, including intermediate passes. */
    fun replace(text: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        SltLimits.check(text.length.toLong(), SltLimits.MAX_SOURCE_CHARS.toLong(), "text characters", 1)
        scan(text.length)
        val matches = pattern.findAll(text).iterator()
        if (!matches.hasNext()) return text
        val out = StringBuilder()
        var offset = 0
        while (matches.hasNext()) {
            val match = matches.next()
            append(out, text, offset, match.range.first)
            val value = replacement(match)
            append(out, value, 0, value.length)
            offset = match.range.last + 1
        }
        append(out, text, offset, text.length)
        return out.toString()
    }

    fun replace(text: String, key: String, value: String): String {
        SltLimits.check(text.length.toLong(), SltLimits.MAX_SOURCE_CHARS.toLong(), "text characters", 1)
        scan(text.length)
        if (key == value || !text.contains(key)) return text
        require(key.isNotEmpty()) { "SLT substitution keys must not be empty" }
        return replace(text, Regex(Regex.escape(key))) { value }
    }

    private fun append(out: StringBuilder, text: String, from: Int, to: Int) {
        val length = to - from
        SltLimits.check(out.length.toLong() + length, SltLimits.MAX_SOURCE_CHARS.toLong(), "text characters", 1)
        charge(length)
        out.append(text, from, to)
    }

    private fun charge(length: Int) {
        SltLimits.check(length.toLong(), SltLimits.MAX_SOURCE_CHARS.toLong(), "text characters", 1)
        allocatedChars += length
        SltLimits.check(allocatedChars, SltLimits.MAX_EXPANDED_CHARS, "expanded characters", 1)
    }

    private fun scan(length: Int) {
        scannedChars += maxOf(1, length)
        SltLimits.check(scannedChars, SltLimits.MAX_SUBSTITUTION_SCAN_CHARS, "substitution scan characters", 1)
    }
}
