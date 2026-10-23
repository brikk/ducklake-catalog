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

/** Backend-specific read opt-ins do not change the global writable-format or migration contract. */
internal object DucklakeReadBackendPolicy {
    fun requireMySqlFormat(version: String?, final11Reads: Boolean = false) {
        if (version in setOf("0.4", "1.0")) return
        if (version == "1.1" && final11Reads) return
        throw DucklakeInvalidOperationException(
            "MySQL catalog format ${version ?: "<missing>"} is not qualified for this reader; " +
                "existing final 1.1 catalogs require enableFinal11Reads and readSession. No creation, writes or migration are enabled.")
    }
}
