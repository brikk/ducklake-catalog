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
import org.jooq.SQLDialect
import java.sql.DriverManager

class TestInlinedMetadataLayouts {
    @Test
    fun knownLayoutsAndDdlShareTheSameDescriptor() {
        val legacy = InlinedDataTables.metadataColumns("1.0")
        val dev = InlinedDataTables.metadataColumns("1.1-dev1")
        assertThat(legacy.rowId).isEqualTo("row_id")
        assertThat(dev.rowId).isEqualTo("_ducklake_row_id")
        for (version in listOf("1.0", "1.1-dev1")) {
            val names = InlinedDataTables.metadataColumns(version)
            val sql = InlinedDataTables.createTableSql("t", emptyList(), SQLDialect.POSTGRES, version)
            assertThat(sql).contains("\"${names.rowId}\"", "\"${names.beginSnapshot}\"", "\"${names.endSnapshot}\"")
        }
        for (unknown in listOf(null, "1.1", "1.1-dev2", "1.2")) {
            assertThatThrownBy { InlinedDataTables.metadataColumns(unknown) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        }
    }

    @Test
    fun final11ReadNamesRequireOptInAndDoNotEnableDdlOrOtherTransports() {
        assertThat(InlinedDataTables.readMetadataColumns("1.1", true)).isEqualTo(InlinedDataTables.metadataColumns("1.1-dev1"))
        assertThatThrownBy { InlinedDataTables.readMetadataColumns("1.1", false) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThatThrownBy { InlinedDataTables.createTableSql("t", emptyList(), SQLDialect.DUCKDB, "1.1") }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThat(DucklakeCatalogConfig().enableFinal11Reads).isFalse()
        assertThatThrownBy { JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = "jdbc:duckdb:"
            enableFinal11Reads = true
        }) }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("direct Java Quack")
    }

    @Test
    fun unqualifiedDecimalMetadataIsRejectedBeforeItCanBecomeSqlTypeText() {
        val ctx = org.jooq.impl.DSL.using(SQLDialect.DUCKDB) // No connection: validation must precede any query.
        for (type in listOf("decimal(0,0)", "decimal(39,0)", "decimal(2,3)", "decimal(10,2)); SELECT 1 --", "decimal(10,2) trailing")) {
            val column = DucklakeColumn(0, 0, null, 0, 0, "amount", type, true, null)
            val targets = mapOf(0L to InlinedTypeNode(type, "amount"))
            assertThatThrownBy { Final11ReadCompatibility.requireProjection(ctx, "t", listOf(column), listOf(column), mapOf(0L to type), targets) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("inline type")
            // The absent-field/default arm must whitelist metadata before constructing SQL too.
            assertThatThrownBy { Final11ReadCompatibility.requireProjection(ctx, "t", emptyList(),
                listOf(column.copy(initialDefault = "42")), emptyMap(), targets) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("inline type")
        }
    }

    @Test
    fun readsFollowExternallyRenamedMetadataWithoutEnablingDevWrites() {
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val fixture = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "inlined-layouts")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = fixture.jdbcUrl
                catalogDatabaseUser = fixture.user
                catalogDatabasePassword = fixture.password
                dataPath = fixture.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 1
            }
            DriverManager.getConnection("jdbc:duckdb:").use { native ->
                native.createStatement().use { st ->
                    st.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
                    st.execute("ATTACH '${fixture.duckDbAttachUri}' AS lake (DATA_PATH '${fixture.dataDir.toAbsolutePath()}')")
                    st.execute("CREATE TABLE lake.test_schema.layout(i INTEGER)")
                    st.execute("INSERT INTO lake.test_schema.layout VALUES (11), (22)")
                }
            }
            JdbcDucklakeCatalog(config).use { catalog ->
                val inserted = catalog.currentSnapshotId
                val table = catalog.getTable("test_schema", "layout", inserted)!!
                val info = catalog.getInlinedDataInfos(table.tableId, inserted).single()
                val columns = catalog.getTableColumns(table.tableId, inserted)
                assertReads(catalog, info, inserted, columns)
                migrateInlineColumns(fixture, info.tableName)
                assertReads(catalog, info, inserted, columns)
                assertThatThrownBy { catalog.truncateTable("test_schema", "layout") }
                    .isInstanceOf(DucklakeUnsupportedCatalogVersionException::class.java)
                assertThat(catalog.currentSnapshotId).isEqualTo(inserted)
            }
        }
    }

    private fun assertReads(catalog: JdbcDucklakeCatalog, info: DucklakeInlinedDataInfo, inserted: Long, columns: List<DucklakeColumn>) {
        catalog.readSession(java.util.function.Supplier {
            val tableId = info.tableId
            assertThat(catalog.getInlinedDataInfos(tableId, inserted).single().hasLiveRows).isTrue()
            assertThat(catalog.hasInlinedRows(tableId, info.schemaVersion, inserted)).isTrue()
            assertThat(catalog.countInlinedRows(tableId, info.schemaVersion, inserted)).isEqualTo(2)
            assertThat(catalog.readInlinedRowIds(tableId, info.schemaVersion, inserted)).containsExactly(0L, 1L)
            assertThat(catalog.readInlinedBeginSnapshots(tableId, info.schemaVersion, inserted)).containsExactly(inserted, inserted)
            assertThat(catalog.readInlinedDataDecoded(tableId, info.schemaVersion, inserted, columns))
                .containsExactly(listOf(11), listOf(22))
            assertThat(catalog.getInlinedChangesBetweenDecoded(tableId, info.schemaVersion, inserted, inserted,
                columns.map { it.columnId })).hasSize(2)
            assertThatThrownBy { catalog.commitInsert(tableId, emptyList()) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
            Unit
        })
    }

    private fun migrateInlineColumns(fixture: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog, tableName: String) {
        DriverManager.getConnection(fixture.jdbcUrl, fixture.user, fixture.password).use { pg ->
            pg.createStatement().use { st ->
                for (name in listOf("row_id", "begin_snapshot", "end_snapshot")) {
                    st.execute("ALTER TABLE $tableName RENAME COLUMN $name TO _ducklake_$name")
                }
                st.execute("ALTER TABLE $tableName ADD COLUMN row_id BIGINT DEFAULT 999")
                st.execute("UPDATE ducklake_metadata SET value='1.1-dev1' WHERE key='version' AND scope IS NULL")
            }
        }
    }
}
