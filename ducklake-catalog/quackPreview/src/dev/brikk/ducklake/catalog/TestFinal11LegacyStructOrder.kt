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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier

class TestFinal11LegacyStructOrder {
    private fun struct(vararg fields: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*fields)

    @Test
    fun genuineUpstream156StructOrderFixtureReadsCorrectlyByNameAfterExplicitMigration() {
        val input = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-r02-legacy-input-")
        try {
            val database = input.resolve("legacy.db")
            Final11ReadFixtures.unpackLegacy(database)
            Final11ReadFixtures.read(Final11ReadFixtures.STRUCT_SQL) // Verify the matching pristine regression source too.
            PreviewQuackServer("1.1", "1.1-genuine-legacy-order", seedCatalog = database).use { server ->
                server.start()
                // Read the genuine seed immediately after migration. The separate strict native
                // gate runs the WHOLE pristine SLT: the shipping peer cannot execute its DELETE.
                verifyTables(server)
            }
        }
        finally {
            Files.walk(input).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun verifyTables(server: PreviewQuackServer) {
        val cases = linkedMapOf(
            "t" to listOf(listOf(struct("a" to 1, "b" to 2)), listOf(struct("a" to 3, "b" to 4))),
            "front" to listOf(listOf(1, struct("a" to 1, "c" to null)), listOf(2, struct("a" to 3, "c" to 5))),
            "inner_s" to listOf(listOf(1, struct("s" to struct("x" to 1, "y" to 2)))),
            "map_v" to listOf(listOf(1, mapOf("k" to struct("a" to 1, "b" to 2)))),
            "list_s" to listOf(listOf(1, listOf(struct("a" to 1, "b" to 2)))),
            "widen" to listOf(listOf(1, struct("a" to 1, "b" to 2L)), listOf(2, struct("a" to 3, "b" to 40000000000L))),
            "appended" to listOf(listOf(1, struct("a" to 1, "z" to null)), listOf(2, struct("a" to 2, "z" to 9))),
            "other" to listOf(listOf(1)),
        )
        server.catalog(enableFinal11Reads = true).use { catalog -> catalog.readSession(Supplier {
            val snapshot = catalog.currentSnapshotId
            for ((name, expected) in cases) {
                val table = catalog.getTable("main", name, snapshot)!!
                val columns = catalog.getTableColumns(table.tableId, snapshot)
                assertThat(catalog.getDataFiles(table.tableId, snapshot)).`as`("$name must be an actual inline read before native flush").isEmpty()
                val infos = catalog.getInlinedDataInfos(table.tableId, snapshot)
                val actual = infos.flatMap { catalog.readInlinedDataDecoded(table.tableId, it.schemaVersion, snapshot, columns) }
                assertThat(actual).`as`("independent rows from upstream ea543c71 $name").containsExactlyInAnyOrderElementsOf(expected)
            }
            assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
            Unit
        }) }
    }
}
