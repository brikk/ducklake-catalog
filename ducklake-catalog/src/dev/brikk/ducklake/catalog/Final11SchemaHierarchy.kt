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

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Pure bounded ID graph. Sibling names are compared with DuckDB's ASCII identifier folding. */
internal class Final11SchemaHierarchy(rows: List<DucklakeSchemaMetadata>, private val root: String?) {
    private val byId = rows.associateBy { it.schema.schemaId }
    private val resolved = mutableMapOf<Long, DucklakeQualifiedSchema>()

    init {
        if (rows.size > MAX_NODES) invalid("schema node budget exceeded")
        if (byId.size != rows.size) corrupt("duplicate schema identity")
        if (rows.map { it.parentSchemaId to key(it.schema.schemaName) }.distinct().size != rows.size) corrupt("ambiguous sibling schema names")
        rows.forEach { row ->
            requireIdentity(row.schema.schemaId)
            row.parentSchemaId?.let(::requireIdentity)
            if (row.schema.schemaName.isEmpty()) corrupt("empty schema name")
            if (row.parentSchemaId != null && row.parentSchemaId !in byId) corrupt("missing parent schema ${row.parentSchemaId}")
        }
        rows.forEach { resolve(it.schema.schemaId) }
    }

    fun all(): List<DucklakeQualifiedSchema> = byId.keys.sorted().map { resolved.getValue(it) }
    fun get(id: Long): DucklakeQualifiedSchema? = resolved[id]
    fun find(components: List<String>): DucklakeQualifiedSchema? {
        validateComponents(components)
        val keys = components.map(::key)
        return resolved.values.singleOrNull { it.names.map(::key) == keys }
    }

    private fun resolve(id: Long): DucklakeQualifiedSchema {
        resolved[id]?.let { return it }
        val chain = mutableListOf<DucklakeSchemaMetadata>()
        val seen = mutableSetOf<Long>()
        var current: Long? = id
        while (current != null) {
            if (!seen.add(current)) corrupt("cyclic schema parentage")
            if (chain.size == MAX_DEPTH) invalid("schema depth budget exceeded")
            val row = byId.getValue(current)
            chain.add(row)
            current = row.parentSchemaId
        }
        val ordered = chain.asReversed()
        if (ordered.sumOf { it.schema.schemaName.length } > MAX_TEXT) invalid("qualified name budget exceeded")
        val row = byId.getValue(id)
        return DucklakeQualifiedSchema(row, ordered.map { it.schema.schemaId }, ordered.map { it.schema.schemaName },
            path(row.schema.path, row.schema.pathIsRelative, root)).also { resolved[id] = it }
    }

    companion object {
        const val MAX_NODES = 4096
        const val MAX_DEPTH = 64
        const val MAX_TEXT = 16384
        fun key(name: String): String = Final11InlineTypes.identifierKey(name)
        fun validateComponents(parts: List<String>) {
            require(parts.isNotEmpty() && parts.size <= MAX_DEPTH && parts.all { it.isNotEmpty() } && parts.sumOf { it.length } <= MAX_TEXT) {
                "Schema components exceed the name/depth budget or contain an empty identifier"
            }
        }

        /** Native stored paths concatenate verbatim. Schema paths are ROOT-relative, not parent-schema-relative. */
        fun path(path: String?, relative: Boolean?, base: String?): String? {
            if (path == null) return checkedText(base)
            checkedText(path)
            if (relative == null) corrupt("missing path_is_relative for non-NULL path")
            return checkedText(if (relative) base?.let { it + path } else path)
        }
        fun checkedText(text: String?): String? = text?.also { if (it.length > MAX_TEXT) invalid("path/text budget exceeded") }
        fun decodedPath(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().also(::checkedText)
        } catch (e: CharacterCodingException) {
            throw DucklakeCatalogCorruptionException("Final-1.1 hierarchy: invalid UTF-8 path bytes", e)
        }
        fun requireIdentity(id: Long) { if (id < 0) corrupt("negative hierarchy identity") }
        fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException("Final-1.1 hierarchy: $message")
        fun corrupt(message: String): Nothing = throw DucklakeCatalogCorruptionException("Final-1.1 hierarchy: $message")
    }
}
