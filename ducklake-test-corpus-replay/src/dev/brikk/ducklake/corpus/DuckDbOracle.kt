package dev.brikk.ducklake.corpus

import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * The oracle: an embedded DuckDB session that executes corpus files verbatim.
 *
 * One oracle per corpus file. Owns:
 *  - the root JDBC connection plus named connections (`con1`, `con2`, …) via
 *    [DuckDBConnection.duplicate] — same database instance, separate sessions
 *    (what the upstream harness does for its concurrent-connection tests);
 *  - template-variable state: `{TEST_DIR}` / `${TEST_DIR}` / `__TEST_DIR__`
 *    map to a per-file temp dir, `{UUID}` is fresh per occurrence, and
 *    `test-env` definitions accumulate;
 *  - extension requirements (INSTALL/LOAD, first run needs network, cached in
 *    `~/.duckdb` afterwards).
 */
class DuckDbOracle(
    testDirRoot: Path? = null,
    /** The pinned ducklake repo root; corpus SQL references test data as repo-relative `data/...` paths. */
    private val repoRoot: Path? = null,
) : AutoCloseable {

    val testDir: Path =
        (testDirRoot ?: Files.createTempDirectory("corpus-replay")).toAbsolutePath()

    private val root: DuckDBConnection =
        DriverManager.getConnection("jdbc:duckdb:").unwrap(DuckDBConnection::class.java)

    private val connections = mutableMapOf<String, Connection>()
    private val env = mutableMapOf<String, String>()
    private val loaded = mutableSetOf<String>()

    init {
        root.createStatement().use { it.execute("SET timezone='UTC'") }
    }

    fun connection(name: String?): Connection =
        if (name == null) {
            root
        } else {
            connections.getOrPut(name) {
                root.duplicate().also { c -> c.createStatement().use { it.execute("SET timezone='UTC'") } }
            }
        }

    /**
     * Satisfies a `require` directive (upstream `sqllogic_test_runner.cpp` `CheckRequire`).
     * Returns null when PRESENT or a skip reason when MISSING / not met in this harness:
     *  - `no_alternative_verify` is PRESENT — MISSING only in `DUCKDB_ALTERNATIVE_VERIFY` builds
     *    (`:562-568`), which the release JDBC binary is not;
     *  - `no_extension_autoloading` is MISSING exactly when `autoload_known_extensions` is on
     *    (`:600-604`); the release binary enables it, so this is checked live.
     */
    fun require(requirement: String): String? {
        val req = requirement.substringBefore(' ').trim().lowercase()
        return when (req) {
            "notwindows", "core_functions", "no_alternative_verify" -> null // always true here
            "ducklake", "parquet", "icu", "json", "httpfs" -> installAndLoad(req)
            "no_extension_autoloading" ->
                if (autoloadKnownExtensions()) "setting autoload_known_extensions is enabled" else null
            else -> "unsupported extension requirement '$req'"
        }
    }

    private fun autoloadKnownExtensions(): Boolean =
        root.createStatement().use { st ->
            st.executeQuery("SELECT current_setting('autoload_known_extensions')").use { rs ->
                rs.next() && rs.getBoolean(1)
            }
        }

    /**
     * Upstream `TestResultHelper::LoadResultFromFile` (`result_helper.cpp:362-399`): a `<FILE>:`
     * golden is a `|`-separated CSV WITH header, read with every column typed VARCHAR
     * (`read_csv(..., header=1, sep='|', columns=STRUCT_PACK(<result names> := VARCHAR),
     * auto_detect=false)`), each value then rendered with `Value::ToString()` — so an empty field
     * is NULL and renders `NULL`. Column names only shape the struct; [columnCount] is what matters.
     * Relative [path]s resolve against the repo root (upstream runs from the ducklake checkout).
     */
    fun loadGoldenCsv(path: String, columnCount: Int): List<List<String>> {
        val resolved = Path.of(path).let { if (it.isAbsolute || repoRoot == null) it else repoRoot.resolve(it) }
        if (!Files.isRegularFile(resolved)) {
            throw GoldenFileMissing("golden file not found: $resolved")
        }
        val columns = (0 until columnCount).joinToString(", ") { "'c$it': 'VARCHAR'" }
        val sql =
            "SELECT * FROM read_csv('${resolved.toString().replace("'", "''")}', header=1, sep='|', " +
                "columns={$columns}, auto_detect=false)"
        return root.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val rows = mutableListOf<List<String>>()
                while (rs.next()) {
                    rows += (1..columnCount).map { rs.getString(it) ?: "NULL" }
                }
                rows
            }
        }
    }

    /** A `<FILE>:` golden that is not on disk — an environment gap (e.g. the `duckdb` submodule), not a result mismatch. */
    class GoldenFileMissing(message: String) : RuntimeException(message)

    private fun installAndLoad(extension: String): String? {
        if (extension in loaded) return null
        return try {
            root.createStatement().use {
                it.execute("INSTALL $extension")
                it.execute("LOAD $extension")
            }
            loaded += extension
            null
        } catch (e: SQLException) {
            "cannot INSTALL/LOAD '$extension': ${e.message?.lineSequence()?.firstOrNull()}"
        }
    }

    fun defineEnv(name: String, value: String) {
        env[name] = substitute(value)
    }

    /** Applies template substitution: env vars, TEST_DIR forms, per-occurrence UUID. */
    fun substitute(text: String): String {
        var s = text.replace("__TEST_DIR__", testDir.toString())
        // Repo-relative test-data references (read_parquet('data/...'),
        // ducklake_add_data_files(..., 'data/...')): upstream's harness runs
        // from the repo root; we resolve against the pinned submodule.
        if (repoRoot != null) {
            s = s.replace("'data/", "'${repoRoot.resolve("data")}/")
        }
        // ${NAME} and {NAME} forms; UUID is fresh per occurrence.
        val pattern = Regex("\\$?\\{([A-Za-z_][A-Za-z0-9_]*)}")
        s = pattern.replace(s) { m ->
            when (val name = m.groupValues[1]) {
                "TEST_DIR" -> testDir.toString()
                "UUID" -> UUID.randomUUID().toString()
                else -> env[name] ?: m.value // unknown vars stay literal
            }
        }
        return s
    }

    override fun close() {
        connections.values.forEach { runCatching { it.close() } }
        runCatching { root.close() }
        runCatching {
            testDir.toFile().deleteRecursively()
        }
    }
}
