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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.sql.DriverManager
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Native 1.5.6 produces these missing/lower inline versions; no hand-edited registry fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TestJdbcDucklakeCatalogLegacyInlineSchemas {
    private lateinit var server: TestingDucklakePostgreSqlCatalogServer
    private lateinit var fixture: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
    private lateinit var catalog: JdbcDucklakeCatalog

    @BeforeAll
    fun open() {
        server = TestingDucklakePostgreSqlCatalogServer()
        fixture = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "legacy-inline")
        catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = fixture.jdbcUrl
            catalogDatabaseUser = fixture.user
            catalogDatabasePassword = fixture.password
            dataPath = fixture.dataDir.toAbsolutePath().toString()
            maxCatalogConnections = 1
        })
    }

    @AfterAll
    fun close() {
        if (::catalog.isInitialized) catalog.close()
        if (::server.isInitialized) server.close()
    }

    private fun native(vararg statements: String) {
        val source = Path.of(javaClass.getResource("/LegacyInlineProducer.java")!!.toURI())
        val output = Files.createTempFile("legacy-inline-producer-", ".log")
        val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--class-path", requireNotNull(System.getProperty("ducklake.legacy.producer.jar")), source.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            child.outputStream.use { stream -> stream.write((
                "INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres; " +
                    "ATTACH '${fixture.duckDbAttachUri}' AS lake (DATA_PATH '${fixture.dataDir.toAbsolutePath()}', DATA_INLINING_ROW_LIMIT 0); " +
                    "CALL lake.set_option('data_inlining_row_limit',0); " + statements.joinToString("; ") + ";"
                ).toByteArray()) }
            check(child.waitFor(90, TimeUnit.SECONDS)) { "Historical inline producer timed out" }
            check(child.exitValue() == 0) { "Historical inline producer failed: ${Files.readString(output)}" }
        }
        finally {
            if (child.isAlive) {
                child.destroyForcibly()
                check(child.waitFor(5, TimeUnit.SECONDS)) { "Failed to reap historical producer" }
            }
            Files.deleteIfExists(output)
        }
    }

    private fun pg(sql: String): List<List<Any?>> =
        DriverManager.getConnection(fixture.jdbcUrl, fixture.user, fixture.password).use { conn ->
            conn.createStatement().use { st -> st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) (1..rs.metaData.columnCount).map { rs.getObject(it) } else null }.toList()
            } }
        }

    private fun table(name: String) = catalog.getTable("test_schema", name, catalog.currentSnapshotId)!!

    private fun lazy(name: String): Pair<DucklakeTable, Long> {
        native("CREATE TABLE lake.test_schema.$name(id INTEGER)", "ALTER TABLE lake.test_schema.$name ADD COLUMN value INTEGER")
        val altered = catalog.currentSnapshotId
        native("CALL lake.set_option('data_inlining_row_limit',10)", "INSERT INTO lake.test_schema.$name VALUES (1,11)")
        return table(name) to altered
    }

    @Test
    @Order(1)
    fun lazyInlineValuesSurviveExpiryRenameAndUnrelatedDdl() {
        val (t, altered) = lazy("lazy")
        val inserted = catalog.currentSnapshotId
        val old = catalog.getInlinedDataInfos(t.tableId, inserted).single { it.hasLiveRows }
        assertThat(pg("SELECT count(*) FROM ducklake_schema_versions WHERE table_id=${t.tableId} AND schema_version=${old.schemaVersion}").single()[0])
            .isEqualTo(0L)
        native("CREATE TABLE lake.test_schema.unrelated(i INTEGER)", "CALL lake.set_option('data_inlining_row_limit',10)",
            "ALTER TABLE lake.test_schema.lazy RENAME COLUMN value TO payload")
        native("CALL lake.set_option('data_inlining_row_limit',10)", "INSERT INTO lake.test_schema.lazy VALUES (2,22)")
        val now = catalog.currentSnapshotId
        catalog.expireSnapshots(setOf(inserted))
        val columns = catalog.getTableColumns(t.tableId, now)
        catalog.readSession(Supplier<Unit> {
            assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, old.schemaVersion, now)).isEqualTo(altered)
            assertThat(catalog.readInlinedDataDecoded(t.tableId, old.schemaVersion, now, columns)).containsExactly(listOf(1, 11))
            assertThat(catalog.readInlinedRowIds(t.tableId, old.schemaVersion, now)).containsExactly(0L)
            assertThat(catalog.getInlinedChangesBetweenDecoded(t.tableId, old.schemaVersion, altered, now, columns.map { it.columnId }).single().values)
                .containsExactly(1, 11)
            Unit
        })
        assertThat(catalog.getInlinedDataInfos(t.tableId, now).filter { it.hasLiveRows }).hasSize(2)
    }

    private fun simultaneous(name: String): Triple<DucklakeTable, Long, Long> {
        native("CREATE TABLE lake.test_schema.$name(id INTEGER)", "CREATE TABLE lake.test_schema.${name}_other(id INTEGER,value INTEGER)")
        val before = catalog.currentSnapshotId
        native("CALL lake.set_option('data_inlining_row_limit',10)", "BEGIN",
            "ALTER TABLE lake.test_schema.$name ADD COLUMN value INTEGER", "INSERT INTO lake.test_schema.$name VALUES (1,11)",
            "INSERT INTO lake.test_schema.${name}_other VALUES (2,22)", "COMMIT")
        return Triple(table(name), before, catalog.currentSnapshotId)
    }

    @Test
    @Order(2)
    fun lowerVersionCreatedDuringMultiTableAlterLoadsTheCommittingSchema() {
        val (t, _, committed) = simultaneous("same_commit")
        val info = catalog.getInlinedDataInfos(t.tableId, committed).single { it.hasLiveRows }
        assertThat(info.schemaVersion).isLessThan(catalog.getSnapshot(committed)!!.schemaVersion)
        val columns = catalog.getTableColumns(t.tableId, committed)
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, info.schemaVersion, committed)).isEqualTo(committed)
        assertThat(catalog.readInlinedDataDecoded(t.tableId, info.schemaVersion, committed, columns)).containsExactly(listOf(1, 11))
        assertThat(catalog.getInlinedChangesBetweenDecoded(t.tableId, info.schemaVersion, committed, committed, columns.map { it.columnId }).single().values)
            .containsExactly(1, 11)
    }

    @Test
    @Order(3)
    fun expiryPreservesTheBoundaryOfLowerLegacyVersionsBeforeRemovingItsEvidence() {
        val (t, before, committed) = simultaneous("expired_boundary")
        val info = catalog.getInlinedDataInfos(t.tableId, committed).single { it.hasLiveRows }
        native("CREATE TABLE lake.test_schema.after_boundary(i INTEGER)")
        catalog.expireSnapshots(setOf(before))
        val now = catalog.currentSnapshotId
        assertThat(catalog.getSnapshot(before)).isNull()
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, info.schemaVersion, now)).isEqualTo(committed)
        assertThat(catalog.readInlinedDataDecoded(t.tableId, info.schemaVersion, now, catalog.getTableColumns(t.tableId, now)))
            .containsExactly(listOf(1, 11))
        assertThat(pg("SELECT begin_snapshot FROM ducklake_schema_versions WHERE table_id=${t.tableId} AND schema_version=${info.schemaVersion}"))
            .containsExactly(listOf(committed))
    }

    @Test
    @Order(4)
    fun jvmFlushOfAnOldLazySchemaPreservesBytesValuesAndRowIdentityAfterExpiry() {
        val (t, _) = lazy("flush_legacy")
        val inserted = catalog.currentSnapshotId
        val info = catalog.getInlinedDataInfos(t.tableId, inserted).single { it.hasLiveRows }
        native("CREATE TABLE lake.test_schema.flush_unrelated(i INTEGER)")
        catalog.expireSnapshots(setOf(inserted))
        val readSnapshot = catalog.currentSnapshotId
        val columns = catalog.getTableColumns(t.tableId, readSnapshot)
        val values = catalog.readInlinedDataDecoded(t.tableId, info.schemaVersion, readSnapshot, columns).single()
        assertThat(values).containsExactly(1, 11)
        val rowId = catalog.readInlinedRowIds(t.tableId, info.schemaVersion, readSnapshot).single()
        val schema = catalog.listSchemas(readSnapshot).single { it.schemaId == t.schemaId }
        val directory = Files.createDirectories(fixture.dataDir.toAbsolutePath().resolve(schema.path).resolve(t.path))
        val file = directory.resolve("legacy-flush.parquet")
        val ids = columns.associate { it.columnName to it.columnId }
        DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
            st.execute("COPY (SELECT ${values[0]}::INTEGER AS id, ${values[1]}::INTEGER AS value, " +
                "$rowId::BIGINT AS _ducklake_internal_row_id, $inserted::BIGINT AS _ducklake_internal_snapshot_id) " +
                "TO '$file' (FORMAT PARQUET, FIELD_IDS {id: ${ids.getValue("id")}, value: ${ids.getValue("value")}, " +
                "_ducklake_internal_row_id: 2147483540, _ducklake_internal_snapshot_id: 2147483539})")
        } }
        val fragment = DucklakeWriteFragment(file.fileName.toString(), Files.size(file), 0, 1, emptyList())
        catalog.flushInlinedDataWithSnapshots(t.tableId, listOf(FlushedInlinedFile(fragment, inserted, inserted, rowId)), emptyList(), readSnapshot)
        assertThat(catalog.countInlinedRows(t.tableId, info.schemaVersion, catalog.currentSnapshotId)).isZero()
        assertThat(catalog.getDataFiles(t.tableId, catalog.currentSnapshotId).single().rowIdStart).isEqualTo(rowId)
        DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { st ->
            st.execute("LOAD ducklake; LOAD postgres")
            st.execute("ATTACH '${fixture.duckDbAttachUri}' AS lake (DATA_PATH '${fixture.dataDir.toAbsolutePath()}')")
            st.executeQuery("SELECT id, value, rowid FROM lake.test_schema.flush_legacy").use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3))).containsExactly(1L, 11L, rowId)
                assertThat(rs.next()).isFalse()
            }
        } }
    }

    @Test
    @Order(5)
    fun externallyLostBoundaryCannotTurnExistingInlineRowsIntoAnEmptyResult() {
        val (t, before, committed) = simultaneous("lost_boundary")
        val info = catalog.getInlinedDataInfos(t.tableId, committed).single { it.hasLiveRows }
        pg("DELETE FROM ducklake_snapshot WHERE snapshot_id=$before RETURNING snapshot_id")
        val columns = catalog.getTableColumns(t.tableId, committed)
        assertThatThrownBy { catalog.readInlinedDataDecoded(t.tableId, info.schemaVersion, committed, columns) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("schema")
        assertThatThrownBy {
            catalog.getInlinedChangesBetweenDecoded(t.tableId, info.schemaVersion, committed, committed, columns.map { it.columnId })
        }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThatThrownBy { catalog.expireSnapshots(setOf(t.beginSnapshot)) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThat(catalog.getSnapshot(t.beginSnapshot)).isNotNull()
    }
}
