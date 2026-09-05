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

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_FILES_SCHEDULED_FOR_DELETION
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_METADATA
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.Record
import org.jooq.RecordMapper
import org.jooq.ResultQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.function.Supplier

/** The same bounded-cleanup contract runs against real metadata tables on all four backends. */
class TestJdbcDucklakeCatalogBoundedCleanup {
    @Nested
    inner class PostgreSql : Contract(Backend.POSTGRES)

    @Nested
    inner class LocalDuckDb : Contract(Backend.DUCKDB)

    @Nested
    inner class MySql : Contract(Backend.MYSQL)

    @Nested
    inner class Quack : Contract(Backend.QUACK)

    enum class Backend { POSTGRES, DUCKDB, MYSQL, QUACK }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract class Contract(private val backend: Backend) {
        private var server: AutoCloseable? = null
        private lateinit var catalog: JdbcDucklakeCatalog
        private lateinit var connection: Connection
        private lateinit var metadata: MetadataQuery
        private lateinit var initialRoot: String
        private val cutoff = Instant.parse("2025-01-02T03:04:05Z")
        private val scheduled = cutoff.minusSeconds(1).atOffset(java.time.ZoneOffset.UTC)

        @BeforeAll
        fun openCatalog(@TempDir directory: Path) {
            val config = DucklakeCatalogConfig().apply {
                dataPath = directory.resolve("data").toString()
                maxCatalogConnections = 3
            }
            when (backend) {
                Backend.POSTGRES -> {
                    val pg = TestingDucklakePostgreSqlCatalogServer().also { server = it }
                    config.catalogDatabaseUrl = pg.getJdbcUrl()
                    config.catalogDatabaseUser = pg.getUser()
                    config.catalogDatabasePassword = pg.getPassword()
                    bootstrap(pg.getDuckDbAttachUri(), "postgres", config.dataPath!!)
                }
                Backend.MYSQL -> {
                    val mysql = TestingDucklakeMySqlCatalogServer().also { server = it }
                    config.catalogDatabaseUrl = mysql.getJdbcUrl()
                    config.catalogDatabaseUser = mysql.getUser()
                    config.catalogDatabasePassword = mysql.getPassword()
                    bootstrap(mysql.getDuckDbAttachUri(), "mysql", config.dataPath!!)
                }
                Backend.DUCKDB -> {
                    val file = directory.resolve("lake.db")
                    config.catalogDatabaseUrl = "jdbc:duckdb:$file"
                    bootstrap("ducklake:$file", null, config.dataPath!!)
                }
                Backend.QUACK -> {
                    val quack = TestingDucklakeDuckDbQuackCatalogServer().also { server = it }
                    config.catalogDatabaseUrl = "jdbc:duckdb:quack://${quack.getHost()}:${quack.getMappedPort()}?metadata_catalog=cleanup_meta"
                    config.catalogDatabasePassword = quack.getToken()
                }
            }
            catalog = JdbcDucklakeCatalog(config)
            if (backend == Backend.QUACK) {
                connection = DriverManager.getConnection("jdbc:duckdb:")
                val url = QuackBackedDuckDbCatalogUrl.parse(config.catalogDatabaseUrl!!, config.catalogDatabasePassword, config.dataPath)
                connection.createStatement().use { it.execute(url.connectionInitSql()) }
                metadata = QuackWrappedMetadataQuery(url.metadataCatalog())
            }
            else {
                connection = DriverManager.getConnection(config.catalogDatabaseUrl, config.catalogDatabaseUser, config.catalogDatabasePassword)
                metadata = DirectMetadataQuery()
            }
            initialRoot = requireNotNull(catalog.getDataPath())
            configurePathCollation()
        }

        private fun configurePathCollation() {
            when (backend) {
                Backend.MYSQL -> {
                    // PAD SPACE plus case/accent-insensitivity exposes all three dangerous equalities.
                    exec("ALTER TABLE ducklake_files_scheduled_for_deletion MODIFY path TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                }
                Backend.POSTGRES -> {
                    exec("CREATE COLLATION cleanup_insensitive (provider = icu, locale = 'und-u-ka-shifted-ks-level1', deterministic = false)")
                    exec("ALTER TABLE ducklake_files_scheduled_for_deletion ALTER COLUMN path TYPE TEXT COLLATE cleanup_insensitive")
                }
                Backend.DUCKDB, Backend.QUACK -> {
                    exec("ALTER TABLE ducklake_files_scheduled_for_deletion ALTER COLUMN path TYPE VARCHAR COLLATE NOCASE")
                }
            }
        }

        private fun bootstrap(uri: String, extension: String?, dataPath: String) {
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSTALL ducklake")
                    stmt.execute("LOAD ducklake")
                    if (extension != null) {
                        stmt.execute("INSTALL $extension")
                        stmt.execute("LOAD $extension")
                    }
                    stmt.execute("ATTACH '$uri' AS lake (DATA_PATH '$dataPath')")
                    stmt.execute("DETACH lake")
                }
            }
        }

        @AfterAll
        fun closeCatalog() {
            if (::connection.isInitialized) connection.close()
            if (::catalog.isInitialized) catalog.close()
            server?.close()
        }

        @BeforeEach
        fun resetRows() {
            clearRoots()
            insertRoot(initialRoot)
            listOf("data_file", "delete_file", "files_scheduled_for_deletion", "table", "schema").forEach {
                exec("DELETE FROM ducklake_$it")
            }
            exec("INSERT INTO ducklake_schema (schema_id, begin_snapshot, end_snapshot, path, path_is_relative) VALUES (10, 1, 9, 'schema/', true)")
            exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                "VALUES (20, 10, 1, 9, 'table/', true)")
        }

        private fun exec(sql: String, vararg bindings: Any?) {
            val dsl = catalog.forConnection(connection)
            metadata.execute(dsl, dsl.query(sql, *bindings))
        }

        private fun enqueue(id: Long = 1, path: String = "a.parquet", time: OffsetDateTime = scheduled, relative: Boolean = true) {
            val dsl = catalog.forConnection(connection)
            val sched = DUCKLAKE_FILES_SCHEDULED_FOR_DELETION
            metadata.execute(dsl, dsl.insertInto(sched, sched.DATA_FILE_ID, sched.PATH, sched.PATH_IS_RELATIVE, sched.SCHEDULE_START)
                .values(DSL.`val`(id), DSL.`val`(path), DSL.`val`(relative), DSL.`val`(time, SQLDataType.TIMESTAMPWITHTIMEZONE)))
        }

        private fun clearRoots() {
            val dsl = catalog.forConnection(connection)
            val meta = DUCKLAKE_METADATA
            metadata.execute(dsl, dsl.deleteFrom(meta).where(meta.KEY.eq("data_path")))
        }

        private fun insertRoot(value: String?, scope: String? = null) {
            val dsl = catalog.forConnection(connection)
            val meta = DUCKLAKE_METADATA
            val key = DSL.field(DSL.quotedName("key"), String::class.java)
            metadata.execute(dsl, dsl.insertInto(meta, key, meta.VALUE, meta.SCOPE).values("data_path", value, scope))
        }

        private fun file(kind: DucklakeFileKind, id: Long, tableId: Long = 20, path: String = "file-$id.parquet") {
            val name = if (kind == DucklakeFileKind.DATA) "data" else "delete"
            exec("INSERT INTO ducklake_${name}_file (${name}_file_id, table_id, path, path_is_relative, begin_snapshot, end_snapshot) " +
                "VALUES (?, ?, ?, true, 1, 9)", id, tableId, path)
        }

        @Test
        fun scheduleBatchIsBoundedRepeatableAndStrictlyOlder() {
            // Upstream's MySQL schema uses timestamp(0); the other fixtures retain microseconds.
            val nextTick = if (backend == Backend.MYSQL) cutoff.plusSeconds(1) else cutoff.plusNanos(1000)
            enqueue(5, "later", scheduled.plusNanos(1000))
            enqueue(3, "b")
            enqueue(3, "a")
            enqueue(4, "at-cutoff", cutoff.atOffset(java.time.ZoneOffset.UTC))
            enqueue(8, "next-tick", nextTick.atOffset(java.time.ZoneOffset.UTC))
            enqueue(6, "future", scheduled.plusDays(1))
            exec("INSERT INTO ducklake_files_scheduled_for_deletion (data_file_id, path, path_is_relative) VALUES (7, 'undated', true)")
            val first = catalog.listScheduledFileBatch(cutoff, 1)
            assertThat(first).containsExactly(DucklakeScheduledFileEntry(3, "a", true, scheduled))
            assertThat(catalog.listScheduledFileBatch(cutoff, 1)).isEqualTo(first)
            assertThat(catalog.listScheduledFileBatch(cutoff, 2).map { it.path }).containsExactly("a", "b")
            assertThat(catalog.listScheduledFileBatch(cutoff, 1024).map { it.path }).containsExactly("a", "b", "later")
            assertThat(catalog.listScheduledFileBatch(cutoff.plusNanos(1), 1024).map { it.path })
                .containsExactly("a", "b", "later", "at-cutoff")
            assertThat(catalog.listScheduledFileBatch(cutoff.minusNanos(1), 1024).map { it.path }).containsExactly("a", "b", "later")
            assertThat(catalog.listScheduledFileBatch(cutoff.plusNanos(999), 1024).map { it.path })
                .containsExactly("a", "b", "later", "at-cutoff")
        }

        @Test
        fun keysetPagesIncludeLastHistoricalDataAndDeleteOwnersButNeverQueue() {
            enqueue(999, "queue-only")
            DucklakeFileKind.entries.forEach { kind ->
                listOf(0L, 3L, 10L).forEach { file(kind, it) }
                val first = catalog.listRetainedFileReferencePage(kind, null, 2)
                assertThat(first.map { it.fileId }).containsExactly(0L, 3L)
                val last = catalog.listRetainedFileReferencePage(kind, first.last().fileId, 2)
                assertThat(last.map { it.fileId }).containsExactly(10L)
                assertThat(last.single().pathRef).isEqualTo(
                    DucklakeTableFilePathRef(10, "schema/", true, 20, "table/", true, "file-10.parquet", true))
                assertThat(catalog.listRetainedFileReferencePage(kind, 10, 1)).isEmpty()
                assertThat(catalog.listRetainedFileReferencePage(kind, Long.MAX_VALUE, 1)).isEmpty()
                assertThat(catalog.listRetainedFileReferencePage(kind, 0, 1).single().fileId).isEqualTo(3L)
            }
        }

        @Test
        fun hierarchyUsesOnlyNeededLatestVersionsAndPreservesNullableScopeFields() = withUnconstrainedSchema {
            file(DucklakeFileKind.DATA, 1)
            (2..40).forEach { version ->
                exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                    "VALUES (20, 10, ?, 100, 'latest-table/', false)", version)
                exec("INSERT INTO ducklake_schema (schema_id, begin_snapshot, end_snapshot, path, path_is_relative) " +
                    "VALUES (10, ?, 100, 'latest-schema/', false)", version)
            }
            // Unrelated corrupt hierarchies must not be read by this page.
            exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, path) VALUES (99, 999, 1, ?)", "x".repeat(20000))
            exec("INSERT INTO ducklake_schema (schema_id, begin_snapshot, path) VALUES (99, 1, ?)", "x".repeat(20000))
            val ref = catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1).single().pathRef
            assertThat(ref).isEqualTo(DucklakeTableFilePathRef(10, "latest-schema/", false, 20, "latest-table/", false, "file-1.parquet", true))
            exec("UPDATE ducklake_table SET path = NULL, path_is_relative = NULL WHERE table_id = 20 AND begin_snapshot = 40")
            exec("UPDATE ducklake_schema SET path = NULL, path_is_relative = NULL WHERE schema_id = 10 AND begin_snapshot = 40")
            assertThat(catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1).single().pathRef)
                .isEqualTo(DucklakeTableFilePathRef(10, null, null, 20, null, null, "file-1.parquet", true))
        }

        @Test
        fun missingAndDuplicateLatestParentsFailInsteadOfLosingReferences() = withUnconstrainedSchema {
            file(DucklakeFileKind.DATA, 1)
            listOf("table", "schema").forEach { parent ->
                exec("UPDATE ducklake_$parent SET ${parent}_id = 999")
                assertCorrupt { catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1) }
                exec("UPDATE ducklake_$parent SET ${parent}_id = ?", if (parent == "table") 20L else 10L)
                exec("INSERT INTO ducklake_$parent SELECT * FROM ducklake_$parent")
                assertCorrupt { catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1) }
                resetRows()
                file(DucklakeFileKind.DATA, 1)
            }
        }

        @Test
        fun exactCancellationPreservesOtherRawTuplesAndDeletesIdenticalDuplicates() {
            val newer = if (backend == Backend.MYSQL) scheduled.plusSeconds(1) else scheduled.plusNanos(1000)
            val batchCutoff = cutoff.plusSeconds(2)
            enqueue(path = "cafe")
            enqueue(path = "cafe")
            listOf("other", "CAFE", "caf\u00e9", "cafe ", "cafe  ", "./cafe", "quote'\\path").forEach { enqueue(path = it) }
            enqueue(path = "cafe", relative = false)
            enqueue(path = "cafe", time = newer)
            val all = catalog.listScheduledFileBatch(batchCutoff, 100)
            assertThat(all.filter { it.path == "cafe" && it.pathIsRelative }.map { it.scheduleStart })
                .containsExactlyInAnyOrder(scheduled, scheduled, newer)
            val selected = all.first { it.path == "cafe" && it.pathIsRelative && it.scheduleStart == scheduled }
            catalog.removeSelectedScheduledFiles(listOf(selected))
            assertThat(catalog.listScheduledFileBatch(batchCutoff, 100)).containsExactlyElementsOf(all.filter { it != selected })
            catalog.removeSelectedScheduledFiles(listOf(selected)) // idempotent
            val quoted = all.single { it.path == "quote'\\path" }
            catalog.removeSelectedScheduledFiles(listOf(quoted))
            assertThat(catalog.listScheduledFileBatch(batchCutoff, 100)).containsExactlyElementsOf(all.filter { it != selected && it != quoted })
        }

        @Test
        fun insensitiveColumnCollationCannotChangeRawPathOrderOrCancellation() {
            val paths = listOf("cafe ", "caf\u00e9", "cafe", "CAFE", "cafe  ", "CAFE ")
            paths.forEach { enqueue(path = it) }
            val dsl = catalog.forConnection(connection)
            val sched = DUCKLAKE_FILES_SCHEDULED_FOR_DELETION
            val collatedMatches = metadata.fetch(dsl, dsl.selectCount().from(sched).where(sched.PATH.eq("cafe"))) {
                it.get(0, Int::class.java)
            }.single()
            assertThat(collatedMatches).`as`("fixture really uses an insensitive path collation").isGreaterThan(1)
            val all = catalog.listScheduledFileBatch(cutoff, 100)
            assertThat(all.map { it.path }).containsExactlyElementsOf(paths.sorted())
            assertThat(catalog.listScheduledFileBatch(cutoff, 1).single().path).isEqualTo("CAFE")
            catalog.removeSelectedScheduledFiles(listOf(all.single { it.path == "cafe" }))
            assertThat(catalog.listScheduledFileBatch(cutoff, 100).map { it.path })
                .containsExactlyElementsOf(paths.filter { it != "cafe" }.sorted())
        }

        @Test
        fun cleanupRootPreservesRawValueAndUsesTheExistingGlobalSelectionContract() {
            assertThat(catalog.getCleanupDataPath()).isEqualTo(initialRoot).isEqualTo(catalog.getDataPath())
            clearRoots()
            assertThat(catalog.getCleanupDataPath()).isNull()
            insertRoot("x".repeat(32000), "table")
            insertRoot("schema-root/", "schema")
            assertThat(catalog.getCleanupDataPath()).isEqualTo(catalog.getDataPath()).isNull()
            insertRoot("  raw-root/./ ")
            assertThat(catalog.getCleanupDataPath()).isEqualTo("  raw-root/./ ").isEqualTo(catalog.getDataPath())
            clearRoots()
            insertRoot("")
            assertThat(catalog.getCleanupDataPath()).isEqualTo(catalog.getDataPath()).isEmpty()
        }

        @Test
        fun cleanupRootReturnsNullForNullValueButRejectsDuplicateNullRoots() {
            // Shipped metadata values are NOT NULL. Relax only this test's column to exercise
            // malformed storage without changing production schema or manufacturing a root.
            exec(if (backend == Backend.MYSQL) "ALTER TABLE ducklake_metadata MODIFY value TEXT NULL"
                else "ALTER TABLE ducklake_metadata ALTER COLUMN value DROP NOT NULL")
            try {
                clearRoots()
                insertRoot(null)
                assertThat(catalog.getCleanupDataPath()).isEqualTo(catalog.getDataPath()).isNull()
                insertRoot(null)
                assertCorrupt { catalog.getCleanupDataPath() }
            }
            finally {
                clearRoots()
                insertRoot(initialRoot)
                exec(if (backend == Backend.MYSQL) "ALTER TABLE ducklake_metadata MODIFY value TEXT NOT NULL"
                    else "ALTER TABLE ducklake_metadata ALTER COLUMN value SET NOT NULL")
            }
        }

        @Test
        fun cleanupRootRejectsOversizedValueAfterBoundedSqlTransfer() {
            clearRoots()
            val max = "x".repeat(DucklakeCleanupLimits.MAX_PATH_LENGTH)
            insertRoot(max)
            assertThat(catalog.getCleanupDataPath()).isEqualTo(max)
            clearRoots()
            insertRoot(max + "x")
            assertCorrupt { catalog.getCleanupDataPath() }
            clearRoots()
            insertRoot("x".repeat(32000))
            val recording = RecordingMetadata(metadata)
            val queries = JdbcDucklakeCleanupQueries(catalog.forConnection(connection), recording)
            assertCorrupt { queries.getCleanupDataPath() }
            assertThat(recording.stringLengths).containsExactly(16385)
            assertThat(recording.fieldCounts).containsExactly(1)
            assertThat(recording.sql.single()).contains("substring(", "16385", "data_path", "scope is null")
                .containsPattern("limit 2|fetch next 2 rows only")
        }

        @Test
        fun cleanupRootRejectsDuplicatesAndTransfersAtMostTwoRows() {
            clearRoots()
            repeat(5) { insertRoot("same-root/") }
            assertCorrupt { catalog.getCleanupDataPath() }
            val recording = RecordingMetadata(metadata)
            val queries = JdbcDucklakeCleanupQueries(catalog.forConnection(connection), recording)
            assertCorrupt { queries.getCleanupDataPath() }
            assertThat(recording.rowCounts).containsExactly(2)
            assertThat(recording.fieldCounts).containsExactly(1)
            assertThat(recording.sql).hasSize(1)
        }

        @Test
        fun limitsAndInputValidationFailBeforeMutation() {
            enqueue()
            val entry = catalog.listScheduledFileBatch(cutoff, 1).single()
            listOf(-1, 0, 1025, Int.MAX_VALUE).forEach { limit ->
                assertThatThrownBy { catalog.listScheduledFileBatch(cutoff, limit) }.isInstanceOf(IllegalArgumentException::class.java)
                DucklakeFileKind.entries.forEach { kind ->
                    assertThatThrownBy { catalog.listRetainedFileReferencePage(kind, null, limit) }
                        .isInstanceOf(IllegalArgumentException::class.java)
                }
            }
            assertThatThrownBy { catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, -1, 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { catalog.removeSelectedScheduledFiles(List(1025) { entry }) }
                .isInstanceOf(IllegalArgumentException::class.java)
            listOf(entry.copy(dataFileId = -1), entry.copy(path = ""), entry.copy(path = "x".repeat(16385))).forEach { invalid ->
                assertThatThrownBy { catalog.removeSelectedScheduledFiles(List(65) { entry } + invalid) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
            catalog.removeSelectedScheduledFiles(emptyList())
            assertThat(catalog.listScheduledFileBatch(cutoff, 1)).containsExactly(entry)
            catalog.removeSelectedScheduledFiles(List(1024) { entry })
            assertThat(catalog.listScheduledFileBatch(cutoff, 1)).isEmpty()
        }

        @Test
        fun malformedSelectedScheduleRowsFailLoudly() {
            listOf("data_file_id = NULL", "data_file_id = -1", "path = NULL", "path = ''", "path_is_relative = NULL").forEach { mutation ->
                exec("DELETE FROM ducklake_files_scheduled_for_deletion")
                enqueue()
                exec("UPDATE ducklake_files_scheduled_for_deletion SET $mutation")
                assertCorrupt { catalog.listScheduledFileBatch(cutoff, 1) }
            }
        }

        @Test
        fun malformedSelectedFileRowsFailLoudlyForBothKinds() {
            DucklakeFileKind.entries.forEach { kind ->
                val name = if (kind == DucklakeFileKind.DATA) "data" else "delete"
                listOf("${name}_file_id = -1", "table_id = NULL", "table_id = -1", "path = NULL", "path = ''", "path_is_relative = NULL")
                    .forEach { mutation ->
                        exec("DELETE FROM ducklake_${name}_file")
                        file(kind, 1)
                        exec("UPDATE ducklake_${name}_file SET $mutation")
                        assertCorrupt { catalog.listRetainedFileReferencePage(kind, null, 1) }
                    }
            }
        }

        @Test
        fun pathLimitsApplyToScheduleFileTableAndSchema() {
            val max = "x".repeat(DucklakeCleanupLimits.MAX_PATH_LENGTH)
            enqueue(path = max)
            assertThat(catalog.listScheduledFileBatch(cutoff, 1).single().path).isEqualTo(max)
            exec("UPDATE ducklake_files_scheduled_for_deletion SET path = ?", max + "x")
            assertCorrupt { catalog.listScheduledFileBatch(cutoff, 1) }
            DucklakeFileKind.entries.forEach { kind ->
                file(kind, 1, path = max)
                assertThat(catalog.listRetainedFileReferencePage(kind, null, 1).single().pathRef.path).isEqualTo(max)
                val name = if (kind == DucklakeFileKind.DATA) "data" else "delete"
                exec("UPDATE ducklake_${name}_file SET path = ?", max + "x")
                assertCorrupt { catalog.listRetainedFileReferencePage(kind, null, 1) }
                exec("UPDATE ducklake_${name}_file SET path = 'file'")
            }
            listOf("table", "schema").forEach { parent ->
                exec("UPDATE ducklake_$parent SET path = ?", max)
                assertThat(catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1)).hasSize(1)
                exec("UPDATE ducklake_$parent SET path = ?", max + "x")
                assertCorrupt { catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1) }
                exec("UPDATE ducklake_$parent SET path = 'restored'")
            }
        }

        @Test
        fun readSessionPinsBothReadsAndRefusesEvenEmptyCancellation() {
            enqueue()
            DucklakeFileKind.entries.forEach { file(it, 1) }
            catalog.readSession(Supplier {
                val batch = catalog.listScheduledFileBatch(cutoff, 1)
                val root = catalog.getCleanupDataPath()
                assertThat(root).isEqualTo(initialRoot)
                val data = catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1)
                val deletes = catalog.listRetainedFileReferencePage(DucklakeFileKind.DELETE, null, 1)
                assertThatThrownBy { catalog.removeSelectedScheduledFiles(batch) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
                assertThatThrownBy { catalog.removeSelectedScheduledFiles(emptyList()) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                exec("DELETE FROM ducklake_files_scheduled_for_deletion")
                exec("DELETE FROM ducklake_data_file")
                exec("DELETE FROM ducklake_delete_file")
                exec("DELETE FROM ducklake_table")
                exec("DELETE FROM ducklake_schema")
                clearRoots()
                insertRoot("changed-root/")
                assertThat(catalog.listScheduledFileBatch(cutoff, 1)).isEqualTo(batch)
                assertThat(catalog.getCleanupDataPath()).isEqualTo(root)
                assertThat(catalog.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1)).isEqualTo(data)
                assertThat(catalog.listRetainedFileReferencePage(DucklakeFileKind.DELETE, null, 1)).isEqualTo(deletes)
            })
            assertThat(catalog.listScheduledFileBatch(cutoff, 1)).isEmpty()
            assertThat(catalog.getCleanupDataPath()).isEqualTo("changed-root/")
            DucklakeFileKind.entries.forEach { assertThat(catalog.listRetainedFileReferencePage(it, null, 1)).isEmpty() }
        }

        @Test
        fun actualQueueQueryTransfersOnlyOneBatchAndMutationsUseSmallChunks() {
            enqueue()
            repeat(11) {
                exec("INSERT INTO ducklake_files_scheduled_for_deletion SELECT * FROM ducklake_files_scheduled_for_deletion")
            }
            val recording = RecordingMetadata(metadata)
            val queries = JdbcDucklakeCleanupQueries(catalog.forConnection(connection), recording)
            val batch = queries.listScheduledFileBatch(cutoff, 2)
            assertThat(batch).hasSize(2)
            assertThat(recording.rowCounts).containsExactly(2)
            assertThat(recording.sql).hasSize(1)
            assertThat(recording.sql.single()).contains("16385").containsPattern("limit 2|fetch next 2 rows only").doesNotContain("offset")
            assertThat(queries.listScheduledFileBatch(cutoff, 1024)).hasSize(1024)
            assertThat(recording.rowCounts).containsExactly(2, 1024)
            queries.removeSelectedScheduledFiles(List(129) { batch.first() })
            assertThat(recording.mutationSizes).containsExactly(64, 64, 1)
            assertThat(catalog.listScheduledFileBatch(cutoff, 1)).isEmpty() // all identical requests removed
        }

        @Test
        fun actualHierarchyQueriesBoundProjectionsAndLookupOnlyPageIds() {
            file(DucklakeFileKind.DATA, 1)
            file(DucklakeFileKind.DATA, 2)
            file(DucklakeFileKind.DATA, 3, tableId = 21)
            exec("INSERT INTO ducklake_table (table_id, schema_id, begin_snapshot, path, path_is_relative) " +
                "VALUES (21, 11, 1, 'another-table/', true)")
            exec("INSERT INTO ducklake_schema (schema_id, begin_snapshot, path, path_is_relative) VALUES (11, 1, 'another-schema/', true)")
            val recording = RecordingMetadata(metadata)
            val queries = JdbcDucklakeCleanupQueries(catalog.forConnection(connection), recording)
            assertThat(queries.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 2)).hasSize(2)
            assertThat(recording.rowCounts).containsExactly(2, 1, 1)
            assertThat(recording.fieldCounts).containsExactly(4, 4, 3)
            assertThat(recording.sql[1]).contains("tab.table_id in (20)", "version.table_id = tab.table_id")
                .containsPattern("limit 1|fetch next 1 rows only").containsPattern("limit 2|fetch next 2 rows only")
            assertThat(recording.sql[2]).contains("sch.schema_id in (10)", "version.schema_id = sch.schema_id")
                .containsPattern("limit 1|fetch next 1 rows only").containsPattern("limit 2|fetch next 2 rows only")
            assertThat(recording.sql.joinToString()).doesNotContain("select *", "join", "offset", "comment")
            recording.sql.forEach { assertThat(it).contains("16385") }
            assertThat(queries.listRetainedFileReferencePage(DucklakeFileKind.DATA, 2, 2).single().pathRef.tableId).isEqualTo(21L)
            assertThat(recording.sql[4]).contains("tab.table_id in (21)")
            assertThat(recording.sql[5]).contains("sch.schema_id in (11)")
            assertThat(queries.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 3)).hasSize(3)
            assertThat(recording.rowCounts.takeLast(3)).containsExactly(3, 2, 2)
            assertThat(recording.sql[7]).containsPattern("limit 3|fetch next 3 rows only")
            assertThat(recording.sql[8]).containsPattern("limit 3|fetch next 3 rows only")
        }

        @Test
        fun oversizedTextIsTruncatedBySqlBeforeReachingTheMapper() {
            val huge = "x".repeat(32000)
            enqueue(path = huge)
            DucklakeFileKind.entries.forEach { file(it, 1, path = huge) }
            val recording = RecordingMetadata(metadata)
            val queries = JdbcDucklakeCleanupQueries(catalog.forConnection(connection), recording)
            assertCorrupt { queries.listScheduledFileBatch(cutoff, 1) }
            DucklakeFileKind.entries.forEach { kind ->
                assertCorrupt { queries.listRetainedFileReferencePage(kind, null, 1) }
            }
            exec("UPDATE ducklake_data_file SET path = 'file'")
            exec("UPDATE ducklake_table SET path = ?", huge)
            assertCorrupt { queries.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1) }
            exec("UPDATE ducklake_table SET path = 'table/'")
            exec("UPDATE ducklake_schema SET path = ?", huge)
            assertCorrupt { queries.listRetainedFileReferencePage(DucklakeFileKind.DATA, null, 1) }
            assertThat(recording.stringLengths.filter { it > DucklakeCleanupLimits.MAX_PATH_LENGTH })
                .containsExactly(16385, 16385, 16385, 16385, 16385)
        }

        private fun assertCorrupt(action: () -> Unit) {
            assertThatThrownBy(action).isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        }

        private fun withUnconstrainedSchema(action: () -> Unit) {
            // Shipped schemas have a schema_id PK. Only these history/corruption tests need to
            // admit multiple versions/duplicates, without changing any production schema or file PK.
            exec("ALTER TABLE ducklake_schema RENAME TO cleanup_original_schema")
            try {
                exec("CREATE TABLE ducklake_schema AS SELECT * FROM cleanup_original_schema")
                action()
            }
            finally {
                exec("DROP TABLE ducklake_schema")
                exec("ALTER TABLE cleanup_original_schema RENAME TO ducklake_schema")
            }
        }
    }

    private class RecordingMetadata(private val delegate: MetadataQuery) : MetadataQuery by delegate {
        val sql = mutableListOf<String>()
        val rowCounts = mutableListOf<Int>()
        val fieldCounts = mutableListOf<Int>()
        val stringLengths = mutableListOf<Int>()
        val mutationSizes = mutableListOf<Int>()

        override fun <T> fetch(dsl: DSLContext, query: ResultQuery<*>, mapper: RecordMapper<in Record, T>): List<T> {
            sql.add(dsl.renderInlined(query))
            fieldCounts.add(query.fields().size)
            val rows = delegate.fetch(dsl, query) { row ->
                row.intoArray().filterIsInstance<String>().forEach { stringLengths.add(it.length) }
                mapper.map(row)
            }
            rowCounts.add(rows.size)
            return rows
        }

        override fun execute(dsl: DSLContext, mutation: Query): Int {
            mutationSizes.add(Regex("data_file_id =").findAll(dsl.renderInlined(mutation)).count())
            return delegate.execute(dsl, mutation)
        }
    }
}
