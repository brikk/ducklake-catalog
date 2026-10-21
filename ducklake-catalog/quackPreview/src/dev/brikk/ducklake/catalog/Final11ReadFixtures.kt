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
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal object Final11ReadFixtures {
    const val STRUCT_SQL = "test/sql/migration/v10_inlined_struct_field_order.test"
    private val hashes = mapOf(
        STRUCT_SQL to "a015a0482246894223944f601b29b5663087c2cf40631ed2a9e4751095a3c257",
        "data/old_ducklake/inlined_struct_field_order.db.gz" to "837dd5fc5f949b13744c3210a0a7d5b43e3ba6d9168514ea55ab7b4218e95d7d",
    )

    fun read(name: String): ByteArray {
        val file = Path.of(System.getProperty("ducklake.read.fixtures"), name)
        check(Files.isRegularFile(file)) { "Run python3 dev-docs/probes/prepare_11_read_fixtures.py before R02 qualification" }
        val bytes = Files.readAllBytes(file)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == hashes.getValue(name)) { "Changed upstream ea543c71 fixture $name" }
        return bytes
    }

    fun unpackLegacy(destination: Path) {
        GZIPInputStream(read("data/old_ducklake/inlined_struct_field_order.db.gz").inputStream()).use { input ->
            Files.newOutputStream(destination).use { output ->
                val buffer = ByteArray(65536)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 64 * 1024 * 1024) { "Legacy R02 fixture exceeds the decompression budget" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
