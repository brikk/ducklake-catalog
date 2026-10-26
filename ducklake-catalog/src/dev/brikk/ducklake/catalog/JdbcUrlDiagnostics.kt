package dev.brikk.ducklake.catalog

/** Diagnostic-only rendering; connection URLs are never rewritten. */
internal object JdbcUrlDiagnostics {
    private val driverPrefix = Regex("^jdbc:[A-Za-z][A-Za-z0-9_-]*:")

    fun redact(url: String): String {
        // Hide all options/user-info, not just a fragile allowlist of password/token parameter names.
        // Keeping only the driver for these URLs also handles nested Quack and semicolon properties.
        if (url.none { it in "?;#@=" || it.isWhitespace() }) return url
        return (driverPrefix.find(url)?.value ?: "") + "<redacted>"
    }
}
