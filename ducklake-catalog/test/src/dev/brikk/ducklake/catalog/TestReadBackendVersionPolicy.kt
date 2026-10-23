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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.function.Supplier

class TestReadBackendVersionPolicy {
    @Test
    fun mysql11ReadsRequireOptInWithoutDropping04OrEnabling11Writes() {
        listOf("0.4", "1.0").forEach { DucklakeReadBackendPolicy.requireMySqlFormat(it) }
        for (version in listOf(null, "", "0.3", "1.0 ", "1.1-dev1", "1.1", "2.0")) {
            assertThatThrownBy { DucklakeReadBackendPolicy.requireMySqlFormat(version) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("not qualified for this reader")
        }
        DucklakeReadBackendPolicy.requireMySqlFormat("1.1", true)
        assertThat(DucklakeSpecVersions.WRITABLE).containsExactlyInAnyOrder("0.4", "1.0")
        assertThat(DucklakeCatalogConfig().enableFinal11Reads).isFalse()
    }

    @Test
    fun qualified11RoutesStayAvailableWithMysqlExistingCatalogRoute(@TempDir root: Path) {
        assertThat(Final11ReadRoute.forUrl("jdbc:postgresql://unused/never-connect")).isEqualTo(Final11ReadRoute.POSTGRES)
        assertThat(Final11ReadRoute.forUrl("jdbc:quack://unused:9999")).isEqualTo(Final11ReadRoute.JAVA_QUACK)
        assertThat(Final11ReadRoute.forUrl("jdbc:duckdb:quack://unused:9999")).isEqualTo(Final11ReadRoute.NATIVE_QUACK)
        val file = Files.createFile(root.resolve("route-only.db"))
        assertThat(Final11ReadRoute.forUrl("jdbc:duckdb:$file")).isEqualTo(Final11ReadRoute.LOCAL_DUCKDB)
        assertThat(Final11ReadRoute.forUrl("jdbc:mysql://unused/never-connect")).isEqualTo(Final11ReadRoute.MYSQL)
    }

    @Test
    fun actualNative10MysqlStillReadsInlineNullLiteralsAndBlobBytes() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER,s VARCHAR,b BLOB); " +
                "INSERT INTO lake.t VALUES(1,'NULL',from_hex('00FF')),(2,NULL,NULL)", version = "1.0")
            for (optIn in listOf(false, true)) {
                JdbcDucklakeCatalog(f.readerConfig().apply { enableFinal11Reads = optIn }).use { c -> c.readSession(Supplier {
                assertThat(c.getSpecVersion()).isEqualTo("1.0")
                val snapshot = c.currentSnapshotId
                val id = c.getTable("main", "t", snapshot)!!.tableId
                val info = c.getInlinedDataInfos(id, snapshot).single()
                val rows = c.readInlinedDataDecoded(id, info.schemaVersion, snapshot, c.getTableColumns(id, snapshot))
                assertThat(rows[0].take(2)).containsExactly(1, "NULL")
                assertThat(rows[0][2] as ByteArray).containsExactly(0, 0xff.toByte())
                assertThat(rows[1]).containsExactly(2, null, null)
                }) }
            }
        }
    }

    @Test
    fun actualNative11MysqlIsRejectedEvenWhenTheOptInIsDisabledAndNoWritesOccur() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
            val before = f.rows("SELECT count(*) FROM ducklake_schema").single()
            JdbcDucklakeCatalog(f.readerConfig().apply { enableFinal11Reads = false }).use { c ->
                assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("catalog format 1.1")
                c.readSession(Supplier {
                    for (action in listOf<() -> Any?>({ c.getSpecVersion() }, { c.listSchemas(0) }, { c.getCleanupDataPath() })) {
                        assertThatThrownBy { action() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                            .hasMessageContaining("enableFinal11Reads")
                    }
                })
                assertThatThrownBy { c.createSchema("forbidden") }.isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
            }
            assertThat(f.rows("SELECT count(*) FROM ducklake_schema").single()).isEqualTo(before)
            assertThat(f.rows("SELECT value FROM ducklake_metadata WHERE `key`='version'").single()).containsExactly("1.1")
        }
    }

    @Test
    fun mysqlVersionValidationIsPinnedPerOwnedSessionAndNeverCachedAcrossSessions() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)", version = "1.0")
            val config = f.readerConfig().apply { enableFinal11Reads = false }
            JdbcDucklakeCatalog(config).use { c ->
                c.readSession(Supplier {
                    assertThat(c.getSpecVersion()).isEqualTo("1.0")
                    // Negative metadata corruption only; this is not a manufactured future-format qualification fixture.
                    DriverManager.getConnection(config.catalogDatabaseUrl, config.catalogDatabaseUser, config.catalogDatabasePassword).use { writer ->
                        writer.createStatement().use { it.execute("UPDATE ducklake_metadata SET value='2.0' WHERE `key`='version'") }
                    }
                    assertThat(c.getSpecVersion()).isEqualTo("1.0")
                })
                c.readSession(Supplier {
                    assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                        .hasMessageContaining("catalog format 2.0")
                })
                assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("catalog format 2.0")
            }
        }
    }
}
