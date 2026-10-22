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

/** Qualified physical DuckDB routes; other backends need their own projection/provenance contract. */
internal enum class Final11ReadRoute(val label: String) {
    JAVA_QUACK("java-quack"), LOCAL_DUCKDB("local-duckdb"), NATIVE_QUACK("native-quack");

    companion object {
        private val PROTOCOL = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        fun forUrl(url: String): Final11ReadRoute = when {
            url.startsWith("jdbc:quack:") -> JAVA_QUACK
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
                "Final-1.1 reads are qualified only for direct Java Quack, local DuckDB and native Quack; PostgreSQL/MySQL remain unqualified")
        }
    }
}
