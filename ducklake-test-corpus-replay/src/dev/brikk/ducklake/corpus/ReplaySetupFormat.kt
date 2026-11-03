package dev.brikk.ducklake.corpus

/**
 * DuckLake catalog format the replay oracle requests for lakes that a corpus file creates or attaches
 * WITHOUT naming a version. Applied through DuckLake's native `ducklake_default_version` setting, so:
 *  - an explicit `DUCKLAKE_VERSION` in a corpus ATTACH always wins, and a file's own
 *    `SET`/`RESET ducklake_default_version` keeps its native meaning;
 *  - an existing newer catalog fails loudly ("Cannot downgrade"), never silently rewritten;
 *  - corpus SQL and golden expectations are not modified; only the oracle session is configured.
 *
 * Null (the default) keeps the native extension default (DuckDB 2.0 creates DuckLake 1.1).
 * Engines that read only DuckLake 1.0, e.g. a connector without final-1.1 planning, pass [DUCKLAKE_1_0].
 */
enum class ReplaySetupFormat(
    val version: String,
    /** Stored `ducklake_metadata.version` values belonging to this format (mirror format scope). */
    val storedVersions: Set<String>,
) {
    DUCKLAKE_1_0("1.0", setOf("0.4", "1.0")),
    DUCKLAKE_1_1("1.1", setOf("1.1")),
}
