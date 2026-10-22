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

import io.airlift.json.JsonCodecFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.time.OffsetDateTime

class TestFinal11SchemaHierarchy {
    private fun schema(id: Long, name: String, parent: Long? = null, path: String? = null): DucklakeSchemaMetadata =
        DucklakeSchemaMetadata(DucklakeSchema(id, UUID(0, id), 0, null, name, path, if (path == null) null else true), parent)

    @Test
    fun stableIdsSeparateSameNamesLiteralDotsQuotesAndAsciiCase() {
        val graph = Final11SchemaHierarchy(listOf(schema(1, "A"), schema(2, "B", 1), schema(3, "a.b"),
            schema(4, "other"), schema(5, "B", 4), schema(6, "quo\"ted", 2), schema(7, "Ä"), schema(8, "ä")), "/root/")
        assertThat(graph.find(listOf("a", "b"))!!.schemaIds).containsExactly(1, 2)
        assertThat(graph.find(listOf("a.b"))!!.schemaIds).containsExactly(3)
        assertThat(graph.find(listOf("OTHER", "b"))!!.schemaIds).containsExactly(4, 5)
        assertThat(graph.get(6)!!.names).containsExactly("A", "B", "quo\"ted")
        assertThat(graph.find(listOf("Ä"))!!.schemaIds).containsExactly(7)
        assertThat(graph.find(listOf("ä"))!!.schemaIds).containsExactly(8)
        assertThat(graph.find(listOf("missing"))).isNull()
    }

    @Test
    fun missingParentsCyclesDuplicateIdsAndAmbiguousSiblingsFail() {
        for (rows in listOf(listOf(schema(1, "x", 99)), listOf(schema(1, "x", 1)),
            listOf(schema(1, "x", 2), schema(2, "y", 1)), listOf(schema(1, "x"), schema(1, "y")),
            listOf(schema(1, "x"), schema(2, "X")), listOf(schema(1, "p"), schema(2, "x", 1), schema(3, "X", 1)))) {
            assertThatThrownBy { Final11SchemaHierarchy(rows, null) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }
    }

    @Test
    fun schemaStoragePathsUseCatalogRootNotLogicalParentDirectory() {
        val graph = Final11SchemaHierarchy(listOf(schema(1, "parent", path = "parent/"),
            schema(2, "child", 1, "parent/child/")), "s3://bucket/root/")
        assertThat(graph.get(2)!!.dataPath).isEqualTo("s3://bucket/root/parent/child/")
        assertThat(graph.get(2)!!.names).containsExactly("parent", "child")
    }

    @Test
    fun nullPathsInheritButNonNullPathsNeedAFlagAndPreserveNativeBytes() {
        fun path(value: String?, flag: Boolean?, base: String?) = Final11SchemaHierarchy.path(value, flag, base)
        assertThat(path(null, null, "s3://b/base/")).isEqualTo("s3://b/base/")
        assertThat(path("../%2F// A/", true, "s3://b/base/")).isEqualTo("s3://b/base/../%2F// A/")
        assertThat(path("s3://OTHER/Case//", false, null)).isEqualTo("s3://OTHER/Case//")
        assertThat(path("file", true, null)).isNull()
        assertThat(path("x", true, "base")).isEqualTo("basex") // Stored directory separators are not fabricated.
        assertThatThrownBy { path("x", null, "base/") }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThatThrownBy { path("x".repeat(16384), true, "root/") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThat(Final11SchemaHierarchy.decodedPath("Ä//%2f".toByteArray(Charsets.UTF_8))).isEqualTo("Ä//%2f")
        assertThatThrownBy { Final11SchemaHierarchy.decodedPath(byteArrayOf(0xff.toByte())) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("invalid UTF-8")
    }

    @Test
    fun graphAndNameBudgetsFailWithoutRecursingOnTheJvmStack() {
        fun chain(size: Int) = (1..size).map { schema(it.toLong(), "n$it", if (it == 1) null else it - 1L) }
        assertThat(Final11SchemaHierarchy(chain(64), null).get(64)!!.schemaIds).hasSize(64)
        assertThatThrownBy { Final11SchemaHierarchy(chain(65), null) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThatThrownBy { Final11SchemaHierarchy((0..4096).map { schema(it.toLong(), "s$it") }, null) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
        val graph = Final11SchemaHierarchy(listOf(schema(1, "root")), null)
        for (parts in listOf(emptyList(), listOf(""), List(65) { "x" }, listOf("x".repeat(16385)))) {
            assertThatThrownBy { graph.find(parts) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun qualifiedSchemaJsonPreservesIdentityComponentsAndUnknownPath() {
        val value = Final11SchemaHierarchy(listOf(schema(1, "a.b")), null).get(1)!!
        val codec = JsonCodecFactory().jsonCodec(DucklakeQualifiedSchema::class.java)
        assertThat(codec.toJson(value)).isEqualTo(
            """{"metadata":{"schema":{"schemaId":1,"schemaUuid":"00000000-0000-0000-0000-000000000001","beginSnapshot":0,"schemaName":"a.b"},""" +
                """"parentSchemaId":null},"schemaIds":[1],"names":["a.b"],"dataPath":null}""")
        assertThat(codec.fromJson(codec.toJsonBytes(value))).isEqualTo(value)
    }

    @Test
    fun ownedFileJsonKeepsKindRawSpellingAndEndedOwnershipDistinctFromLiveness() {
        val schema = Final11SchemaHierarchy(listOf(schema(1, "a.b")), "s3://b/").get(1)!!
        val table = DucklakeQualifiedTable(DucklakeTable(2, UUID(0, 2), 1, 5, 1, "t", null, null), schema, "s3://b/")
        val file = DucklakeOwnedFile(DucklakeFileKind.DELETE, 3, table, 2, 4, "X//%2f", true, "s3://b/X//%2f")
        val codec = JsonCodecFactory().jsonCodec(DucklakeOwnedFile::class.java)
        assertThat(codec.fromJson(codec.toJsonBytes(file))).isEqualTo(file)
        assertThat(codec.toJson(file)).contains("\"kind\":\"DELETE\"", "\"beginSnapshot\":2", "\"endSnapshot\":4",
            "\"names\":[\"a.b\"]", "\"resolvedPath\":\"s3://b/X//%2f\"")
    }

    @Test
    fun scheduledFileJsonRetainsQueueIdentityAndInstant() {
        val value = DucklakeResolvedScheduledFile(1, "queue.parquet", true, OffsetDateTime.parse("2000-01-01T00:00:00Z"), "s3://b/queue.parquet")
        val codec = JsonCodecFactory().jsonCodec(DucklakeResolvedScheduledFile::class.java)
        assertThat(codec.fromJson(codec.toJsonBytes(value))).isEqualTo(value)
        assertThat(codec.toJson(value)).contains("\"dataFileId\":1", "\"pathIsRelative\":true", "\"path\":\"queue.parquet\"",
            "\"resolvedPath\":\"s3://b/queue.parquet\"")
        val reference = DucklakeScheduledPathReference(DucklakeScheduledPathCursor(1, "A//%2f", true), "s3://b/A//%2f")
        val paths = JsonCodecFactory().jsonCodec(DucklakeScheduledPathReference::class.java)
        assertThat(paths.toJson(reference)).isEqualTo(
            """{"cursor":{"dataFileId":1,"path":"A//%2f","pathIsRelative":true},"resolvedPath":"s3://b/A//%2f"}""")
        assertThat(paths.fromJson(paths.toJsonBytes(reference))).isEqualTo(reference)
    }
}
