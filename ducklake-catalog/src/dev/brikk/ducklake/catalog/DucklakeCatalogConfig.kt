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
 *  - **MySQL** (`jdbc:mysql://…`) — the tables must be in the URL's default database. REPEATABLE
 *    READ means an in-transaction lineage check cannot observe a concurrent commit; correctness
 *    rests on the `ducklake_snapshot` primary key, which is detected and retried.
 *  - **DuckDB file** (`jdbc:duckdb:/path/lake.db`) — one JVM process holds the file lock, so this
 *    is effectively single-writer-PROCESS: no concurrent DuckDB CLI / second cluster can write the
 *    same catalog file. Concurrency between threads of this process works (DuckDB's optimistic
 *    conflicts are detected and retried).
 *  - **Quack-wrapped DuckDB** (`jdbc:duckdb:` + Quack RPC) — read paths are supported; the write
 *    paths that UPDATE/DELETE metadata rows are not verified end-to-end (see
 *    TODO-rectify-from-eval.md C-D5).
 *
 * Every backend must already hold an initialized DuckLake catalog (spec version 0.4 or 1.0, created
 * by attaching it once from DuckDB); this library never bootstraps or migrates the schema.
 */
class DucklakeCatalogConfig {
    var catalogDatabaseUrl: String? = null
    var catalogDatabaseUser: String? = null
    var catalogDatabasePassword: String? = null
    var dataPath: String? = null
    var maxCatalogConnections: Int = 10
}
