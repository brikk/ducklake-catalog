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

import java.nio.file.Files
import java.nio.file.Path

/** Qualified final-1.1 read routes; MySQL has a separate existing-catalog scalar contract. */
internal enum class Final11ReadRoute(val label: String) {
    JAVA_QUACK("java-quack"), LOCAL_DUCKDB("local-duckdb"), NATIVE_QUACK("native-quack"), POSTGRES("postgres-jdbc"), MYSQL("mysql-jdbc");

    val duckDb: Boolean get() = this in setOf(JAVA_QUACK, LOCAL_DUCKDB, NATIVE_QUACK)

    companion object {
        private val PROTOCOL = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        fun forUrl(url: String): Final11ReadRoute = when {
            url.startsWith("jdbc:quack:") -> JAVA_QUACK
            url.startsWith("jdbc:postgresql:") -> POSTGRES
            url.startsWith("jdbc:mysql:") -> MYSQL
            QuackBackedDuckDbCatalogUrl.matches(url) -> NATIVE_QUACK
            url.startsWith("jdbc:duckdb:") -> {
                val file = url.removePrefix("jdbc:duckdb:")
                val ordinary = file.isNotEmpty() && file != ":memory:" && !PROTOCOL.containsMatchIn(file)
                if (!ordinary || !Files.isRegularFile(Path.of(file))) {
                    throw DucklakeInvalidOperationException("Opt-in local 1.1 reads require an existing DuckDB metadata file; readers never create catalogs")
                }
                LOCAL_DUCKDB
            }
            else -> throw DucklakeInvalidOperationException(
                "Final-1.1 reads require a qualified DuckDB, PostgreSQL or MySQL route; unsupported catalog route")
        }
    }
}
