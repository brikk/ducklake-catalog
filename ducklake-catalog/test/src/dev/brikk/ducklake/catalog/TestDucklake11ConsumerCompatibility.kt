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
import org.junit.jupiter.api.Test
import java.util.Base64

/** Lock the old consumer contract BEFORE introducing richer, opt-in 1.1 metadata. */
class TestDucklake11ConsumerCompatibility {
    @Test
    fun java17ConsumerCompiledAgainstPublished011LinksAgainstCurrentClasses() {
        val encoded = javaClass.getResourceAsStream("/compat/Catalog011Consumer.class.base64")!!.use { it.readAllBytes() }
        val bytes = Base64.getMimeDecoder().decode(encoded)
        assertThat(bytes[6].toInt() * 256 + bytes[7].toInt()).`as`("Java 17 fixture bytecode").isEqualTo(61)
        val type = FixtureLoader(javaClass.classLoader).define(bytes)
        val values = type.getMethod("read").invoke(null) as Array<*>
        assertThat(values).hasSize(5)
        assertThat((values[0] as DucklakeSchema).schemaName).isEqualTo("main")
        assertThat((values[1] as DucklakeDataFile).rowIdStart).isNull()
        assertThat((values[2] as DucklakeDataFile).mappingId).isNull()
        assertThat((values[3] as DucklakeFileColumnStats).valueCount).isNull()
        assertThat((values[4] as DucklakeColumnStats).totalNullCount).isNull()
    }

    @Test
    fun existingRecordComponentsDoNotAcquireCandidateMetadata() {
        assertThat(DucklakeSchema::class.java.recordComponents.map { it.name }).containsExactly(
            "schemaId", "schemaUuid", "beginSnapshot", "endSnapshot", "schemaName", "path", "pathIsRelative",
        )
        assertThat(DucklakeFileColumnStats::class.java.recordComponents.map { it.name }).containsExactly(
            "columnId", "columnSizeBytes", "valueCount", "nullCount", "minValue", "maxValue", "containsNan",
        )
        assertThat(DucklakeColumnStats::class.java.recordComponents.map { it.name }).containsExactly(
            "columnId", "totalValueCount", "totalNullCount", "totalSizeBytes", "minValue", "maxValue",
        )
        assertThat(DucklakeDataFile::class.java.recordComponents.map { it.name }).containsExactly(
            "dataFileId", "tableId", "beginSnapshot", "endSnapshot", "fileOrder", "path", "pathIsRelative", "fileFormat",
            "recordCount", "fileSizeBytes", "footerSize", "rowIdStart", "partitionId", "deleteFilePath",
            "deleteFilePathIsRelative", "deleteFileFooterSize", "deleteFileFormat", "mappingId", "partialMax", "deleteFilePartialMax",
        )
    }

    @Test
    fun oldStatsWireDoesNotInventPrecisionOrUnknownCounts() {
        val codec = JsonCodecFactory().jsonCodec(DucklakeFileColumnStats::class.java)
        val json = """{"columnId":1,"columnSizeBytes":16,"minValue":"a","maxValue":"z"}"""
        val stats = codec.fromJson(json)
        assertThat(stats).isEqualTo(DucklakeFileColumnStats(1, 16, null, null, "a", "z", null))
        assertThat(codec.toJson(stats)).isEqualTo(json)
        val zero = codec.fromJson("""{"columnId":1,"columnSizeBytes":16,"valueCount":0,"nullCount":0,"containsNan":false}""")
        assertThat(zero.valueCount).isZero()
        assertThat(zero.nullCount).isZero()
        assertThat(zero.containsNan).isFalse()
    }

    private class FixtureLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(bytes: ByteArray): Class<*> = defineClass(null, bytes, 0, bytes.size)
    }
}
