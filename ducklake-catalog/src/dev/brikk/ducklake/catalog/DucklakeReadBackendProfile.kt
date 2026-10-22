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

import com.fasterxml.jackson.annotation.JsonInclude
import org.jooq.DSLContext

/**
 * Nullable observations distinguish unavailable/not-applicable facts from false or fabricated source IDs.
 * Availability flags describe library data APIs, not missing database metadata. DuckDB-only capabilities
 * still require a DuckDB route; this profile supplies PostgreSQL provenance. No mutation/scan permission.
 */
@JvmRecord
@JacksonSerializedInternalJavaCompatibleClass
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DucklakeReadBackendProfile(
    val formatVersion: String,
    val snapshotId: Long,
    val metadataBackend: String,
    val accessRoute: String,
    val sqlEngineVersion: String,
    val sqlEngineSourceId: String?,
    val jdbcDriverName: String,
    val jdbcDriverVersion: String,
    val unsignedExtensionsAllowed: Boolean?,
    val ducklakeExtensionVersion: String?,
    val quackExtensionVersion: String?,
    val inlineProjection: String,
    val richMetadataAvailable: Boolean,
    val hierarchyAvailable: Boolean,
    val qualifiedWrites: Boolean,
)

internal class Final11BackendProfileQueries(private val ctx: DSLContext, private val route: Final11ReadRoute) {
    fun profile(): DucklakeReadBackendProfile {
        val driver = ctx.connectionResult { it.metaData.driverName to it.metaData.driverVersion }
        if (route.duckDb) {
            val cap = Final11ReadMetadataQueries(ctx, route).capabilities()
            return DucklakeReadBackendProfile(cap.formatVersion, cap.snapshotId, cap.metadataBackend, cap.accessRoute,
                cap.engineVersion, cap.engineSourceId, driver.first, driver.second, cap.unsignedExtensionsAllowed,
                cap.ducklakeExtensionVersion, cap.quackExtensionVersion, "native-duckdb", true, true, false)
        }
        Final11ReadMetadataQueries.observedLayout(ctx, route)
        val version = ctx.fetch("SELECT version()").single().get(0, String::class.java)
            ?: throw DucklakeCatalogCorruptionException("NULL PostgreSQL version observation")
        val snapshot = ctx.fetch("SELECT max(snapshot_id) FROM ducklake_snapshot").single().get(0, Long::class.javaObjectType)
            ?: throw DucklakeCatalogCorruptionException("Missing backend-profile snapshot")
        return DucklakeReadBackendProfile("1.1", snapshot, "postgresql", route.label, version, null, driver.first, driver.second,
            null, null, null, "postgres-same-shape-exact-numeric-widening", true, true, false)
    }
}
