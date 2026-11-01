package dev.brikk.ducklake.slt

/** Explicit resource refusal, never a partial expansion or a fabricated SQL result. */
class SltLimitExceeded(val line: Int, detail: String) : IllegalArgumentException(
    if (detail.startsWith("SLT resource limit:")) detail else "SLT resource limit: $detail at line $line",
)

/**
 * Fixed per-file safety ceilings, not dialect limits or a sandbox for SQL execution. No limit
 * silently truncates input. The parser reports a single resource-limit [SltUnsupported]; direct
 * AST/expansion callers get [SltLimitExceeded]. Existing parser/expander JVM descriptors remain.
 */
object SltLimits {
    const val MAX_SOURCE_CHARS: Int = 1_048_576
    const val MAX_LINES: Int = 50_000
    const val MAX_RECORDS: Int = 20_000
    const val MAX_NESTING: Int = 32
    const val MAX_LOOP_VALUES: Int = 10_000
    const val MAX_TOTAL_LOOP_VALUES: Int = 20_000
    const val MAX_EXPANDED_VISITS: Long = 100_000
    const val MAX_EXPANDED_CHARS: Long = 16_777_216
    const val MAX_SUBSTITUTION_SCAN_CHARS: Long = 67_108_864

    /** Validate even hand-built ASTs before recursive traversal, allocation or oracle effects. */
    fun validate(file: SltFile) { Validation().records(file.records, 1) }

    /** Bound comma-split allocations for public ASTs and env-introduced iterator replacements. */
    fun commaParts(text: String): List<String> {
        check(text.length.toLong(), MAX_SOURCE_CHARS.toLong(), "comma text characters", 1)
        val parts = text.splitToSequence(',').take(MAX_LOOP_VALUES + 1).toList()
        check(parts.size.toLong(), MAX_LOOP_VALUES.toLong(), "comma parts", 1)
        return parts
    }

    internal fun check(value: Long, limit: Long, name: String, line: Int) {
        if (value < 0 || value > limit) throw SltLimitExceeded(line, "$name exceed $limit")
        if (Thread.currentThread().isInterrupted) throw InterruptedException("SLT processing interrupted")
    }

    private class Validation {
        private var recordCount = 0L
        private var valueCount = 0L
        private var sourceChars = 0L
        private var sourceItems = 0L

        fun records(records: List<SltRecord>, depth: Int): Long {
            check(records.size.toLong(), MAX_RECORDS.toLong(), "records", records.firstOrNull()?.line ?: 1)
            var work = 0L
            for (record in records) {
                check(depth.toLong(), MAX_NESTING.toLong(), "nesting", record.line)
                check(++recordCount, MAX_RECORDS.toLong(), "records", record.line)
                work += record(record, depth)
                check(work, MAX_EXPANDED_VISITS, "expanded visits", record.line)
            }
            return work
        }

        private fun text(text: String?, line: Int) {
            sourceChars += text?.length ?: 0
            check(sourceChars, MAX_SOURCE_CHARS.toLong(), "source characters", line)
            check(++sourceItems, MAX_LINES.toLong(), "source items", line)
        }

        private fun record(record: SltRecord, depth: Int): Long {
            val line = record.line
            return when (record) {
                is SltLoop -> loop(record, depth)
                is SltConditional -> {
                    text(record.engine, line)
                    1 + records(listOf(record.record), depth + 1)
                }
                is SltStatement -> { text(record.sql, line); text(record.expectedError, line); text(record.connection, line); 1 }
                is SltQuery -> {
                    text(record.sql, line); text(record.types, line); text(record.connection, line); text(record.label, line)
                    check(record.expected.size.toLong(), MAX_LINES.toLong(), "expected lines", line)
                    record.expected.forEach { text(it, line) }
                    text(record.goldenFile?.path, line)
                    1
                }
                is SltTestEnv -> { text(record.name, line); text(record.value, line); 1 }
                is SltRequire -> { text(record.requirement, line); 1 }
                is SltUnsupported -> {
                    if (record.directive == "resource-limit" && record.raw.startsWith("SLT resource limit:")) {
                        throw SltLimitExceeded(line, record.raw)
                    }
                    text(record.directive, line); text(record.raw, line); 1
                }
            }
        }

        private fun loop(record: SltLoop, depth: Int): Long {
            val size = record.values.size.toLong()
            check(size, MAX_LOOP_VALUES.toLong(), "loop values", record.line)
            valueCount += size
            check(valueCount, MAX_TOTAL_LOOP_VALUES.toLong(), "total loop values", record.line)
            text(record.variable, record.line)
            record.values.forEach { text(it, record.line) }
            // Count empty iterations too; validate zero-iteration/guarded bodies structurally.
            val bodyWork = records(record.body, depth + 1)
            val work = 1 + size * (1 + bodyWork)
            check(work, MAX_EXPANDED_VISITS, "expanded visits", record.line)
            return work
        }
    }
}
