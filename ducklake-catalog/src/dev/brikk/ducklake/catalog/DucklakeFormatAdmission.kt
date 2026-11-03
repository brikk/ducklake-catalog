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
 * Which stored catalog formats this client instance may read, decided from the stored version string only
 * (layout qualification for admitted final 1.1 stays in [Final11ReadCompatibility]).
 */
internal class DucklakeFormatAdmission(
    private val catalogDatabaseUrl: String,
    private val required: DucklakeCatalogFormat?,
    private val final11Reads: Boolean,
) {
    init {
        require(required != DucklakeCatalogFormat.V1_1 || final11Reads) {
            "requiredCatalogFormat=V1_1 requires enableFinal11Reads; 1.1 reads are not enabled implicitly"
        }
    }

    /** Admit or refuse a stored version for reads. A missing version stays with the existing uninitialized handling. */
    fun requireReadable(version: String?) {
        if (required != null) {
            if (version !in required.admittedSpecVersions) {
                throw DucklakeCatalogFormatNotAdmittedException(catalogDatabaseUrl, version, required,
                    "this client is locked to DuckLake ${required.name} (stored ${required.admittedSpecVersions.joinToString("/")}).")
            }
            return
        }
        if (version == null || final11Reads) return
        if (version !in LEGACY_READABLE) {
            throw DucklakeCatalogFormatNotAdmittedException(catalogDatabaseUrl, version, null,
                if (version == "1.1") "final DuckLake 1.1 requires enableFinal11Reads (and a readSession); the default reader admits 1.0."
                else "this client does not know how to read it.")
        }
    }

    /** Writes honor the lock as well; the separate writable-version guard still applies. */
    fun requireWritable(version: String?) {
        if (required != null && version !in required.admittedSpecVersions) {
            throw DucklakeCatalogFormatNotAdmittedException(catalogDatabaseUrl, version, required,
                "this client is locked to DuckLake ${required.name}; refusing to write.")
        }
    }

    companion object {
        /** Formats the default (non-final-1.1) reader has explicit layouts for; see [InlinedDataTables.metadataColumns]. */
        val LEGACY_READABLE: Set<String> = setOf("0.1", "0.2", "0.3-dev1", "0.3", "0.4-dev1", "0.4", "1.0", "1.1-dev1")
    }
}
