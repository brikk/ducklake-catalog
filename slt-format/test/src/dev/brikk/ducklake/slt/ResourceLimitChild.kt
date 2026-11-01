package dev.brikk.ducklake.slt

/** No JUnit/native dependencies: deliberately hostile cases stay outside the test worker's heap. */
object ResourceLimitChild {
    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "range" || args[0] == "nesting") {
            val content = if (args[0] == "range") "loop i 0 1000000000\n\nendloop\n"
                else "onlyif duckdb\n".repeat(20_000) + "statement ok\nSELECT 1\n\n"
            val record = SltParser.parse("child.test", content).records.single()
            check(record is SltUnsupported && record.directive == "resource-limit")
            println(record.raw)
            return
        }
        val file = when (args[0]) {
            "cycle" -> {
                val body = mutableListOf<SltRecord>()
                body += SltLoop(1, "i", listOf("0"), body)
                SltFile("cycle.test", body)
            }
            "product" -> SltParser.parse("product.test", "loop i 0 1000\n\nloop j 0 1000\n\n" +
                "statement ok\nSELECT 1\n\nendloop\n\nendloop\n")
            "expansion" -> SltParser.parse("text.test", "statement ok\nSELECT '" + "{X}".repeat(1000) + "'\n\n")
            else -> error("unknown child mode")
        }
        try {
            SltExpander.expand(file, "duckdb", mapOf("X" to "x".repeat(100_000)))
            error("expected explicit resource refusal")
        } catch (e: SltLimitExceeded) { println(e.message) }
    }
}
