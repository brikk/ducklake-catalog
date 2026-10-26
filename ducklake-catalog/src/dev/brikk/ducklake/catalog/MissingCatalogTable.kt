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

import java.sql.SQLException

/** Classify the actual relation diagnostic, never echoed SQL; unrecognized/localized failures propagate. */
internal object MissingCatalogTable {
    private val diagnostics = listOf(
        Regex("""^(?:Catalog Error: )?Table with name ([^\r\n]+) does not exist[!.]?$"""),
        Regex("""^(?:ERROR: )?relation "([^"]+)" does not exist[.]?$"""),
        Regex("""^Table '([^']+)' doesn't exist$"""),
        Regex("""^no such table: (\S+)$"""),
    )

    fun matches(exception: SQLException, expectedTable: String? = null): Boolean {
        val state = exception.sqlState
        if (!state.isNullOrEmpty() && state !in setOf("HY000", "42P01", "42S02")) return false
        val firstLine = exception.message?.lineSequence()?.firstOrNull()?.trim() ?: return false
        val relation = diagnostics.firstNotNullOfOrNull { it.matchEntire(firstLine)?.groupValues?.get(1) } ?: return false
        val name = relation.substringAfterLast('.').trim('"', '`')
        return if (expectedTable == null) name.startsWith("ducklake_") else name == expectedTable
    }
}
