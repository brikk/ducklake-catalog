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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.function.Supplier

/**
 * Read admission follows the STORED catalog format, checked in the reader's own view before layout-dependent
 * reads. Real native-created catalogs; nothing is migrated by the library.
 */
internal class TestCatalogFormatAdmission {
    private val parquetAndInline =
        "CREATE TABLE lake.p(i INTEGER); CALL lake.set_option('data_inlining_row_limit',0,table_name=>'p'); " +
            "INSERT INTO lake.p VALUES (42); CREATE TABLE lake.inl(i INTEGER); INSERT INTO lake.inl VALUES (7)"

    private fun reader(f: Final11DuckDbBackendFixture, lock: DucklakeCatalogFormat? = null, final11: Boolean = false) =
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = f.readerUrl
            dataPath = f.data.toString()
            maxCatalogConnections = 1
            enableFinal11Reads = final11
            requiredCatalogFormat = lock
        })

    private fun <T> session(c: JdbcDucklakeCatalog, action: () -> T): T = c.readSession(Supplier { action() })

    /** Both the Parquet-only and the inline table read correctly through the default 1.0 reader. */
    private fun readsBoth(c: JdbcDucklakeCatalog) = session(c) {
        val snapshot = c.currentSnapshotId
        val p = c.getTable("main", "p", snapshot)!!
        assertThat(c.getDataFiles(p.tableId, snapshot)).hasSize(1)
        assertThat(c.getLiveRowCount(p.tableId, snapshot)).isEqualTo(1L)
        val inl = c.getTable("main", "inl", snapshot)!!
        val columns = c.getTableColumns(inl.tableId, snapshot)
        assertThat(c.getInlinedDataInfos(inl.tableId, snapshot).flatMap {
            c.readInlinedDataDecoded(inl.tableId, it.schemaVersion, snapshot, columns)
        }).containsExactly(listOf(7))
    }

    private fun refused(action: () -> Any?) =
        assertThatThrownBy { action() }.isInstanceOf(DucklakeCatalogFormatNotAdmittedException::class.java)

    @Test
    fun defaultReaderReadsStored10ParquetAndInlineTables() {
        Final11DuckDbBackendFixture(Final11ReadRoute.LOCAL_DUCKDB, formatVersion = "1.0").use { f ->
            f.native(parquetAndInline)
            reader(f).use { c ->
                assertThat(c.getSpecVersion()).isEqualTo("1.0")
                readsBoth(c)
                // Outside a session (pooled autocommit reads) too.
                assertThat(c.getTable("main", "p", c.currentSnapshotId)).isNotNull
            }
            reader(f, DucklakeCatalogFormat.V1_0).use { c -> readsBoth(c) }
        }
    }

    @Test
    fun defaultReaderRefusesStored11BeforeAnyParquetOrInlineRead() {
        Final11DuckDbBackendFixture(Final11ReadRoute.LOCAL_DUCKDB, formatVersion = "1.1").use { f ->
            f.native(parquetAndInline)
            reader(f).use { c ->
                assertThat(c.getSpecVersion()).isEqualTo("1.1")
                // The Parquet-only table used to be admitted silently; the inline one failed late.
                refused { session(c) { c.getTable("main", "p", c.currentSnapshotId) } }
                refused { session(c) { c.getTable("main", "inl", c.currentSnapshotId) } }
                refused { c.currentSnapshotId }
                assertThatThrownBy { session(c) { c.currentSnapshotId } }
                    .hasMessageContaining("stored format 1.1").hasMessageContaining("enableFinal11Reads")
                assertThat(c.getSpecVersion()).isEqualTo("1.1")
            }
        }
    }

    @Test
    fun locksAdmitOnlyTheirOwnStoredFormat() {
        Final11DuckDbBackendFixture(Final11ReadRoute.LOCAL_DUCKDB, formatVersion = "1.1").use { f ->
            f.native(parquetAndInline)
            reader(f, DucklakeCatalogFormat.V1_0, final11 = true).use { c ->
                val e = assertThatThrownBy { session(c) { c.currentSnapshotId } }
                e.isInstanceOf(DucklakeCatalogFormatNotAdmittedException::class.java).hasMessageContaining("V1_0")
                // A lock mismatch refuses writes before the writable-version guard, without touching metadata.
                refused { c.createSchema("forbidden") }
            }
            reader(f, DucklakeCatalogFormat.V1_1, final11 = true).use { c ->
                assertThat(session(c) { c.getTable("main", "p", c.currentSnapshotId) }).isNotNull
            }
            assertThat(f.nativeRows("SELECT value FROM meta.ducklake_metadata WHERE key='version'")).containsExactly(listOf("1.1"))
        }
        Final11DuckDbBackendFixture(Final11ReadRoute.LOCAL_DUCKDB, formatVersion = "1.0").use { f ->
            f.native(parquetAndInline)
            reader(f, DucklakeCatalogFormat.V1_1, final11 = true).use { c ->
                assertThatThrownBy { session(c) { c.currentSnapshotId } }
                    .isInstanceOf(DucklakeCatalogFormatNotAdmittedException::class.java)
                    .satisfies({ assertThat((it as DucklakeCatalogFormatNotAdmittedException).version).isEqualTo("1.0") })
            }
            assertThat(f.nativeRows("SELECT value FROM meta.ducklake_metadata WHERE key='version'")).containsExactly(listOf("1.0"))
        }
    }

    @Test
    fun aV11LockCannotImplicitlyEnableFinal11Reads() {
        assertThatThrownBy {
            JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = "jdbc:duckdb:"
                requiredCatalogFormat = DucklakeCatalogFormat.V1_1
            })
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("enableFinal11Reads")
    }

    @Test
    fun aNativeMigrationAfterConstructionIsRefusedInTheNextOwnedReadView() {
        Final11DuckDbBackendFixture(Final11ReadRoute.JAVA_QUACK, migrate = true).use { f ->
            f.native(parquetAndInline) // created at stored 1.0
            JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = f.readerUrl
                catalogDatabasePassword = f.token
                dataPath = f.data.toString()
                maxCatalogConnections = 1
            }).use { c ->
                assertThat(session(c) { c.getTable("main", "p", c.currentSnapshotId) }).isNotNull
                f.native("SELECT 1") // the fixture's second attach migrates natively to 1.1
                assertThat(c.getSpecVersion()).isEqualTo("1.1")
                refused { session(c) { c.getTable("main", "p", c.currentSnapshotId) } }
            }
        }
    }
}
