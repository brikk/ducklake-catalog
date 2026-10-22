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
import java.util.UUID

class TestReadMetadataWireFormat {
    private fun <T> verify(type: Class<T>, value: T, expected: String) {
        val codec = JsonCodecFactory().jsonCodec(type)
        assertThat(codec.toJson(value)).isEqualTo(expected)
        assertThat(codec.fromJson(codec.toJsonBytes(value))).isEqualTo(value)
    }

    @Test
    fun unknownCountsAreExplicitNullNotAbsentOrZero() {
        verify(DucklakeFileReadMetadata::class.java, DucklakeFileReadMetadata(1, 2, 100, null, 3, 5, "parquet", 0),
            """{"tableId":1,"dataFileId":2,"recordCount":100,"rowGroupCount":null,"deleteFileId":3,"deleteCount":5,""" +
                """"deleteFileFormat":"parquet","deleteRowGroupCount":0}""")
    }

    @Test
    fun endpointFlagsDistinguishUnknownFalseAndTrueIndependently() {
        verify(DucklakeTableColumnMetadata::class.java, DucklakeTableColumnMetadata(1, 2, null, false, "NULL", null, false, true),
            """{"tableId":1,"columnId":2,"containsNull":null,"containsNan":false,"minValue":"NULL","maxValue":null,"minIsExact":false,"maxIsExact":true}""")
        verify(DucklakeFileColumnMetadata::class.java, DucklakeFileColumnMetadata(1, 2,
            DucklakeFileColumnStats(3, 4, null, null, null, "9", null), null, false),
            """{"tableId":1,"dataFileId":2,"stats":{"columnId":3,"columnSizeBytes":4,"maxValue":"9"},"minIsExact":null,"maxIsExact":false}""")
    }

    @Test
    fun rootParentIsExplicitNullAndLegacySchemaShapeStaysNestedUnchanged() {
        verify(DucklakeSchemaMetadata::class.java, DucklakeSchemaMetadata(DucklakeSchema(1,
            UUID.fromString("00000000-0000-0000-0000-000000000001"), 2, null, "root", null, null), null),
            """{"schema":{"schemaId":1,"schemaUuid":"00000000-0000-0000-0000-000000000001","beginSnapshot":2,"schemaName":"root"},"parentSchemaId":null}""")
    }

    @Test
    fun capabilitiesKeepUnknownProvenanceDistinctFromQualifiedFeatureFlags() {
        val caps = DucklakeReadMetadataCapabilities("1.1", 7, "duckdb", "java-quack", "v2.0.0", "d54cbbe3df", null,
            "f96420ed46", false, "0.8.0", listOf(DucklakeMetadataColumn("ducklake_schema", "parent_schema_id", "BIGINT")),
            true, true, true, true, false, false, false, false)
        verify(DucklakeReadMetadataCapabilities::class.java, caps,
            """{"formatVersion":"1.1","snapshotId":7,"metadataBackend":"duckdb","accessRoute":"java-quack","engineVersion":"v2.0.0",""" +
                """"engineSourceId":"d54cbbe3df","ducklakeExtensionVersion":null,"quackExtensionVersion":"f96420ed46",""" +
                """"unsignedExtensionsAllowed":false,"jdbcDriverVersion":"0.8.0","observedColumns":[{"tableName":"ducklake_schema",""" +
                """"columnName":"parent_schema_id","dataType":"BIGINT"}],"schemaParentIds":true,"boundExactness":true,""" +
                """"dataFileRowGroups":true,"deleteFileRowGroups":true,"qualifiedNestedSchemaResolution":false,"qualifiedRangePruning":false,""" +
                """"qualifiedExactAggregates":false,"qualifiedWrites":false}""")
    }
}
