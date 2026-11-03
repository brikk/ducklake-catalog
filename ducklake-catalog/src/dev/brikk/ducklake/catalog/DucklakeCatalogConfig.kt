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
 * Engine-agnostic configuration for the Ducklake catalog.
 * Contains only the properties needed by the catalog layer itself (JDBC connection, data path).
 * Engine-specific settings (snapshot defaults, temporal partition encoding, session properties)
 * belong in the engine's own config class.
 *
 * ### Supported catalog backends and their assumptions
 *
 * [catalogDatabaseUrl] is a plain JDBC URL; the jOOQ dialect is inferred from it.
 *  - **PostgreSQL** (`jdbc:postgresql://…`) — the primary, fully tested backend. The `ducklake_*`
 *    tables must be in the connection's default `search_path` (upstream's default `public`
 *    schema); a custom `METADATA_SCHEMA` is not supported.
 *  - **MySQL** (`jdbc:mysql://…`) — 0.4/1.0 legacy lane; opt-in existing final-1.1 reads only.
 *    Never creates/migrates a 1.1 catalog or enables 1.1 writes. Tables must be in the URL's database. REPEATABLE
 *    READ means an in-transaction lineage check cannot observe a concurrent commit; correctness
 *    rests on the `ducklake_snapshot` primary key, which is detected and retried.
 *  - **DuckDB file** (`jdbc:duckdb:/path/lake.db`) — one JVM process holds the file lock, so this
 *    is effectively single-writer-PROCESS: no concurrent DuckDB CLI / second cluster can write the
 *    same catalog file. Concurrency between threads of this process works (DuckDB's optimistic
 *    conflicts are detected and retried).
 *  - **Quack-wrapped DuckDB** (`jdbc:duckdb:quack://…`) — embedded DuckDB's native client,
 *    with server-side wrapping and explicitly owned remote transactions.
 *  - **Direct Java Quack** (`jdbc:quack://…`) — optional `dev.brikk.duckdb:quack-jdbc`
 *    runtime driver; server SQL uses the DuckDB dialect and ordinary JDBC transactions.
 *    The password slot supplies the Quack token. Both routes require initialized remote
 *    metadata and do not turn the Quack catalog into Trino's Parquet scan engine.
 *
 * Every backend must already hold an initialized DuckLake catalog (0.4/1.0 for writes;
 * limited final-1.1 reads require [enableFinal11Reads]). This library never bootstraps
 * or migrates the schema.
 */
class DucklakeCatalogConfig {
    var catalogDatabaseUrl: String? = null
    var catalogDatabaseUser: String? = null
    var catalogDatabasePassword: String? = null
    var dataPath: String? = null

    /** Upper bound of the metadata connection pool (HikariCP `maximumPoolSize`). */
    var maxCatalogConnections: Int = 10

    /**
     * Connections the pool keeps open while idle (HikariCP `minimumIdle`). `0` lets an idle
     * catalog instance release every metadata connection, which matters on shared PostgreSQL
     * servers where each connection has a real cost; the default keeps one warm connection.
     */
    var minIdleCatalogConnections: Int = 1

    /**
     * How long a connection may sit idle above [minIdleCatalogConnections] before the pool
     * closes it (HikariCP `idleTimeout`). `null` keeps HikariCP's default (10 minutes).
     */
    var catalogConnectionIdleTimeout: java.time.Duration? = null

    /**
     * Maximum lifetime of a pooled connection (HikariCP `maxLifetime`). `null` keeps HikariCP's
     * default (30 minutes). Set it below any server- or proxy-side idle limit so the pool
     * retires connections before the other side drops them.
     */
    var catalogConnectionMaxLifetime: java.time.Duration? = null

    /**
     * How long a catalog operation waits for a pooled connection before failing (HikariCP
     * `connectionTimeout`). Default 30 seconds.
     */
    var catalogConnectionTimeout: java.time.Duration = java.time.Duration.ofSeconds(30)

    /**
     * Opt in to qualified final-1.1 reads over DuckDB, PostgreSQL or the narrower existing-MySQL scalar tier:
     * legacy APIs require root-schema catalogs; the explicit hierarchy companion handles parent-aware names/paths. No legacy MIN/MAX
     * bounds or range pruning. All reads must run inside readSession on an owned snapshot. Does not enable any 1.1 writes
     * or migration; existing record/wire shapes omit advisory 1.1 metadata. MySQL's narrower scalar/layout contract is validated separately.
     */
    var enableFinal11Reads: Boolean = false

    /**
     * Optional catalog format lock, checked against the stored `ducklake_metadata.version` before reads (once per
     * owned [DucklakeCatalog.readSession], or per pooled read outside one) and before writes. A mismatch throws
     * [DucklakeCatalogFormatNotAdmittedException]; nothing is migrated and no version string is changed.
     *  - [DucklakeCatalogFormat.V1_0]: admits stored 1.0 (and its layout-identical predecessor 0.4) only.
     *  - [DucklakeCatalogFormat.V1_1]: admits stored final 1.1 only; requires [enableFinal11Reads]. 1.1 writes stay disabled.
     * Null (default): with [enableFinal11Reads] off, final 1.1 and unknown formats are refused up front; with it on,
     * the qualified 1.0/1.1 reader rules apply. Use [DucklakeCatalog.getSpecVersion] to inspect a catalog's format.
     */
    var requiredCatalogFormat: DucklakeCatalogFormat? = null
}

/** DuckLake catalog format families a caller can lock a catalog to; see [DucklakeCatalogConfig.requiredCatalogFormat]. */
enum class DucklakeCatalogFormat(
    /** Stored `ducklake_metadata.version` values admitted under this lock. */
    val admittedSpecVersions: Set<String>,
) {
    V1_0(setOf("0.4", "1.0")),
    V1_1(setOf("1.1")),
}
