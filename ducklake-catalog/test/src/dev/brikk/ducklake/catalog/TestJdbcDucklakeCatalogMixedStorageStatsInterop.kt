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
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** C04: complete Parquet stats still cannot describe values held only in native inlined storage. */
class TestJdbcDucklakeCatalogMixedStorageStatsInterop {
    companion object {
        private const val ROW_ID_FIELD_ID = 2147483540L
        private const val SNAPSHOT_ID_FIELD_ID = 2147483539L
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "mixed-storage-stats")
            catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            })
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::catalog.isInitialized) catalog.close()
            if (::server.isInitialized) server.close()
        }
    }

    @Test
    fun rewriteApplyingDeletesSuppressesGlobalsAndLaterInsertsCannotResurrectThem() {
        val (table, source) = createSource("mixed_rewrite", listOf(-1000, 1, 1000))
        val sourceRows = scanWithRowIds(table, source.beginSnapshot)
        assertThat(sourceRows.map { it[1] }).containsExactly("-1000", "1", "1000")
        insertInlineRows(table)
        val preDeleteSnapshot = catalog.currentSnapshotId
        val preDeleteRows = scanWithRowIds(table, preDeleteSnapshot)
        assertThat(preDeleteRows).hasSize(6)
        val nextRowId = nextRowId(table)

        // Tagged deletes must predict this isolated catalog's next commit, not a fixed snapshot id.
        val deleteSnapshot = preDeleteSnapshot + 1
        val deletePath = tableDir(table).resolve("source-deletes.parquet")
        writeParquet(deletePath,
            "SELECT ${q(tableDir(table).resolve(source.path).toString())} AS file_path, " +
                "pos::BIGINT AS pos, $deleteSnapshot::BIGINT AS _ducklake_internal_snapshot_id " +
                "FROM (VALUES (0), (2)) AS positions(pos) ORDER BY pos")
        catalog.commitDelete(table.tableId, listOf(DucklakeDeleteFragment(
            source.dataFileId, deletePath.fileName.toString(), 2L, Files.size(deletePath), 0L, 2L,
            "parquet", deleteSnapshot, deleteSnapshot,
        )), preDeleteSnapshot)
        val readSnapshot = catalog.currentSnapshotId
        assertThat(readSnapshot).isEqualTo(deleteSnapshot)
        assertAccounting(table, 6L, 4L, nextRowId, source.fileSizeBytes)
        assertRowsAndAggregates(table, listOf(-100, 1, 100, null))
        val output = materializeRewrite(table, source, readSnapshot)

        catalog.rewriteDataFiles(table.tableId, setOf(source.dataFileId), listOf(output), readSnapshot)

        val rewriteSnapshot = catalog.currentSnapshotId
        val rewritten = catalog.getDataFiles(table.tableId, rewriteSnapshot).single()
        assertThat(pg("SELECT begin_snapshot, end_snapshot FROM ducklake_data_file WHERE data_file_id = ${source.dataFileId}"))
            .containsExactly(listOf(source.beginSnapshot.toString(), rewriteSnapshot.toString()))
        assertFileStats(table, rewritten, output)
        assertAccounting(table, 4L, 4L, nextRowId, output.fileSizeBytes)
        assertRowsAndAggregates(table, listOf(-100, 1, 100, null))
        assertSuppressed(table)
        assertThat(oracle("SELECT rowid FROM ${relation(table)} WHERE id = 1"))
            .containsExactly(listOf((source.rowIdStart + 1).toString()))

        catalog.analyzeTable(table.tableId)
        assertSuppressed(table)
        assertAccounting(table, 4L, 4L, nextRowId, output.fileSizeBytes)
        native("INSERT INTO ${relation(table)} VALUES (0)")
        assertSuppressed(table)
        assertRowsAndAggregates(table, listOf(-100, 0, 1, 100, null))
        assertThat(catalog.getDataFiles(table.tableId, catalog.currentSnapshotId)).hasSize(1)
        assertThat(catalog.getInlinedDataInfos(table.tableId, catalog.currentSnapshotId).sumOf {
            catalog.countInlinedRows(table.tableId, it.schemaVersion, catalog.currentSnapshotId)
        }).isEqualTo(4L)
        assertThat(oracle("SELECT rowid FROM ${relation(table)} WHERE id = 0"))
            .containsExactly(listOf(nextRowId.toString()))

        val later = writeFile(table, "later.parquet", listOf(2))
        catalog.commitInsert(table.tableId, listOf(later))
        assertSuppressed(table)
        assertRowsAndAggregates(table, listOf(-100, 0, 1, 2, 100, null))
        assertAccounting(table, 6L, 6L, nextRowId + 2, output.fileSizeBytes + later.fileSizeBytes)
        assertThat(catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).single { it.path == later.path }.rowIdStart)
            .isEqualTo(nextRowId + 1)
        assertThat(scanWithRowIds(table, source.beginSnapshot)).isEqualTo(sourceRows)
        assertThat(scanWithRowIds(table, preDeleteSnapshot)).isEqualTo(preDeleteRows)
        assertThat(scanWithRowIds(table, deleteSnapshot)).isEqualTo(
            preDeleteRows.filter { it[1] != "-1000" && it[1] != "1000" },
        )
    }

    @Test
    fun partialRewriteFindsLiveInlineRowsInOlderSchemaEvenWhenLatestInlineTableIsEmpty() {
        val (table, source) = createSource("mixed_partial", listOf(1, 2))
        val sourceRows = scanWithRowIds(table, source.beginSnapshot)
        val readSnapshot = catalog.currentSnapshotId
        val output = materializeRewrite(table, source, readSnapshot, partial = true)
        insertInlineRows(table)
        val inlineSnapshot = catalog.currentSnapshotId
        val beforeRows = scanWithRowIds(table, inlineSnapshot)
        val oldVersion = catalog.getInlinedDataInfos(table.tableId, inlineSnapshot).single()
        catalog.addColumn(table.tableId, TableColumnSpec.leaf("added", "int32", true))
        val schemaSnapshot = catalog.currentSnapshotId
        val versions = catalog.getInlinedDataInfos(table.tableId, schemaSnapshot).sortedBy { it.schemaVersion }
        assertThat(versions).hasSize(2)
        assertThat(versions.first().schemaVersion).isEqualTo(oldVersion.schemaVersion)
        assertThat(versions.last().schemaVersion).isGreaterThan(oldVersion.schemaVersion)
        assertThat(versions.map { it.hasLiveRows }).containsExactly(true, false)
        assertThat(pg("SELECT count(*) FROM ${versions.first().tableName} WHERE end_snapshot IS NULL"))
            .containsExactly(listOf("3"))
        assertThat(pg("SELECT count(*) FROM ${versions.last().tableName}"))
            .containsExactly(listOf("0"))
        // C02: partial rewrites must preserve all source rows, with no file or inlined deletes.
        assertThat(source.partialMax).isNull()
        assertThat(pg("SELECT * FROM ducklake_delete_file WHERE data_file_id = ${source.dataFileId}")).isEmpty()
        assertThat(catalog.getInlinedDeletes(table.tableId, schemaSnapshot)).isEmpty()
        val nextRowId = nextRowId(table)
        assertThat(oracle("SELECT id, _ducklake_internal_row_id, _ducklake_internal_snapshot_id " +
            "FROM read_parquet(${q(tableDir(table).resolve(output.path).toString())}) ORDER BY id"))
            .containsExactly(
                listOf("1", source.rowIdStart.toString(), source.beginSnapshot.toString()),
                listOf("2", (source.rowIdStart + 1).toString(), source.beginSnapshot.toString()),
            )

        catalog.rewriteDataFilesPartial(table.tableId, setOf(source.dataFileId),
            listOf(PartialMergedFile(output, source.beginSnapshot, source.beginSnapshot)), readSnapshot)

        val rewritten = catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).single()
        assertThat(rewritten.path).isEqualTo(output.path)
        assertThat(rewritten.beginSnapshot).isEqualTo(source.beginSnapshot)
        assertThat(rewritten.partialMax).isEqualTo(source.beginSnapshot)
        assertThat(rewritten.rowIdStart).isEqualTo(source.rowIdStart)
        assertThat(rewritten.recordCount).isEqualTo(2L)
        assertThat(rewritten.deleteFilePath).isNull()
        assertThat(pg("SELECT * FROM ducklake_data_file WHERE data_file_id = ${source.dataFileId}")).isEmpty()
        assertFileStats(table, rewritten, output)
        assertAccounting(table, 5L, 5L, nextRowId, output.fileSizeBytes)
        assertRowsAndAggregates(table, listOf(-100, 1, 2, 100, null))
        assertSuppressed(table)
        assertThat(oracle("SELECT added FROM ${relation(table)} ORDER BY rowid"))
            .containsExactlyElementsOf(List(5) { listOf<String?>(null) })
        assertThat(scanWithRowIds(table, catalog.currentSnapshotId)).isEqualTo(beforeRows)
        assertThat(scanWithRowIds(table, source.beginSnapshot)).isEqualTo(sourceRows)
        assertThat(scanWithRowIds(table, inlineSnapshot)).isEqualTo(beforeRows)

        catalog.analyzeTable(table.tableId)
        assertSuppressed(table)
        assertAccounting(table, 5L, 5L, nextRowId, output.fileSizeBytes)
        assertRowsAndAggregates(table, listOf(-100, 1, 2, 100, null))
    }

    @Test
    fun analyzeRemovesStaleGlobalsAndRecoversWhenInlineRowsEndAtCurrentSnapshot() {
        val (table, source) = createSource("mixed_analyze", listOf(1, 2))
        insertInlineRows(table)
        val liveSnapshot = catalog.currentSnapshotId
        val inline = catalog.getInlinedDataInfos(table.tableId, liveSnapshot).single()
        val nextRowId = nextRowId(table)
        val idColumn = catalog.getTableColumns(table.tableId, liveSnapshot).single().columnId
        // Simulate globals left by an earlier file-only rebuild, not unknown per-file statistics.
        withMetadata { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM ducklake_table_column_stats WHERE table_id = ${table.tableId}")
                statement.executeUpdate("INSERT INTO ducklake_table_column_stats " +
                    "(table_id, column_id, contains_null, contains_nan, min_value, max_value) " +
                    "VALUES (${table.tableId}, $idColumn, FALSE, NULL, '1', '2')")
            }
        }
        assertThat(globalStats(table)).containsExactly(listOf(idColumn.toString(), "false", null, "1", "2"))

        catalog.analyzeTable(table.tableId)

        assertThat(catalog.currentSnapshotId).isEqualTo(liveSnapshot)
        assertAccounting(table, 5L, 5L, nextRowId, source.fileSizeBytes)
        assertRowsAndAggregates(table, listOf(-100, 1, 2, 100, null))
        assertSuppressed(table)
        native("DELETE FROM ${relation(table)} WHERE id < 0 OR id > 2 OR id IS NULL")
        val deletedSnapshot = catalog.currentSnapshotId
        assertThat(deletedSnapshot).isGreaterThan(liveSnapshot)
        assertThat(pg("SELECT count(*), count(*) FILTER (WHERE end_snapshot = $deletedSnapshot) FROM ${inline.tableName}"))
            .containsExactly(listOf("3", "3"))
        assertThat(pg("SELECT table_name FROM ducklake_inlined_data_tables WHERE table_id = ${table.tableId}"))
            .containsExactly(listOf(inline.tableName))
        assertThat(catalog.getInlinedDataInfos(table.tableId, deletedSnapshot).single().hasLiveRows).isFalse()
        assertThat(catalog.countInlinedRows(table.tableId, inline.schemaVersion, deletedSnapshot)).isZero()
        assertThat(catalog.getDataFiles(table.tableId, deletedSnapshot).single().dataFileId).isEqualTo(source.dataFileId)
        assertThat(catalog.getInlinedDeletes(table.tableId, deletedSnapshot)).isEmpty()

        catalog.analyzeTable(table.tableId)

        assertThat(catalog.currentSnapshotId).isEqualTo(deletedSnapshot)
        assertThat(globalStats(table)).`as`("registrations and deleted inline rows must not prevent safe recovery")
            .containsExactly(listOf(idColumn.toString(), "false", null, "1", "2"))
        // Deleted, unflushed inline rows still contribute to gross accounting, but not global bounds.
        assertAccounting(table, 5L, 2L, nextRowId, source.fileSizeBytes)
        assertRowsAndAggregates(table, listOf(1, 2))
    }

    @Test
    fun maintenanceUsesOneConnectionAndMissingInlineTablesDoNotAbortIt() {
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = isolated.jdbcUrl
            catalogDatabaseUser = isolated.user
            catalogDatabasePassword = isolated.password
            dataPath = isolated.dataDir.toAbsolutePath().toString()
            maxCatalogConnections = 1
        }).use { single ->
            for (partial in listOf(false, true)) {
                val (table, source) = createSource("single_connection_$partial", listOf(1, 2))
                insertInlineRows(table)
                val snapshot = catalog.currentSnapshotId
                val output = materializeRewrite(table, source, snapshot, partial)
                metadataSql("INSERT INTO ducklake_inlined_data_tables (table_id, table_name, schema_version) VALUES " +
                    "(${table.tableId}, 'ducklake_inlined_data_${table.tableId}_0', 0)")
                try {
                    if (partial) single.rewriteDataFilesPartial(table.tableId, setOf(source.dataFileId),
                        listOf(PartialMergedFile(output, source.beginSnapshot, source.beginSnapshot)), snapshot)
                    else single.rewriteDataFiles(table.tableId, setOf(source.dataFileId), listOf(output), snapshot)
                    single.analyzeTable(table.tableId)
                    assertSuppressed(table)
                }
                finally {
                    metadataSql("DELETE FROM ducklake_inlined_data_tables WHERE table_id = ${table.tableId} AND schema_version = 0")
                }
                assertRowsAndAggregates(table, listOf(-100, 1, 2, 100, null))
            }
            val (table, _) = createSource("missing_inline_registry", listOf(3, 4))
            metadataSql("ALTER TABLE ducklake_inlined_data_tables RENAME TO hidden_inline_registry")
            try {
                single.analyzeTable(table.tableId)
                assertThat(single.getTableStats(table.tableId)!!.recordCount).isEqualTo(2L)
                assertThat(globalStats(table)).hasSize(1)
            }
            finally {
                metadataSql("ALTER TABLE hidden_inline_registry RENAME TO ducklake_inlined_data_tables")
            }
            assertRowsAndAggregates(table, listOf(3, 4))
        }
    }

    @Test
    fun analyzeExcludesFutureInlineVersionsAndRows() {
        val (table, source) = createSource("future_inline", listOf(1, 2))
        val snapshot = catalog.currentSnapshotId
        val schemaVersion = catalog.getSnapshot(snapshot)!!.schemaVersion
        val versions = listOf(schemaVersion, schemaVersion + 1)
        try {
            for (version in versions) {
                val name = "ducklake_inlined_data_${table.tableId}_$version"
                metadataSql("CREATE TABLE $name (row_id BIGINT, begin_snapshot BIGINT, end_snapshot BIGINT, id INTEGER)")
                val begin = if (version == schemaVersion) snapshot + 1 else snapshot
                metadataSql("INSERT INTO $name VALUES (99, $begin, NULL, 1000)")
                metadataSql("INSERT INTO ducklake_inlined_data_tables (table_id, table_name, schema_version) " +
                    "VALUES (${table.tableId}, '$name', $version)")
            }
            catalog.analyzeTable(table.tableId)
            assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
            assertAccounting(table, 2L, 2L, source.rowIdStart + 2, source.fileSizeBytes)
            assertThat(globalStats(table).single().takeLast(2)).containsExactly("1", "2")
        }
        finally {
            metadataSql("DELETE FROM ducklake_inlined_data_tables WHERE table_id = ${table.tableId}")
            versions.forEach { metadataSql("DROP TABLE IF EXISTS ducklake_inlined_data_${table.tableId}_$it") }
        }
        assertRowsAndAggregates(table, listOf(1, 2))
    }

    @Test
    fun inlineQueryFailuresRollBackBothRewriteVariants() {
        for (partial in listOf(false, true)) {
            val (table, source) = createSource("inline_error_$partial", listOf(1, 2))
            insertInlineRows(table)
            val snapshot = catalog.currentSnapshotId
            val inline = catalog.getInlinedDataInfos(table.tableId, snapshot).single()
            val output = materializeRewrite(table, source, snapshot, partial)
            val filesBefore = catalog.getDataFiles(table.tableId, snapshot)
            val statsBefore = catalog.getTableStats(table.tableId)
            val globalsBefore = globalStats(table)
            val scheduledBefore = pg("SELECT * FROM ducklake_files_scheduled_for_deletion ORDER BY data_file_id, path")
            metadataSql("ALTER TABLE ${inline.tableName} RENAME COLUMN end_snapshot TO broken_end")
            try {
                assertThatThrownBy {
                    if (partial) catalog.rewriteDataFilesPartial(table.tableId, setOf(source.dataFileId),
                        listOf(PartialMergedFile(output, source.beginSnapshot, source.beginSnapshot)), snapshot)
                    else catalog.rewriteDataFiles(table.tableId, setOf(source.dataFileId), listOf(output), snapshot)
                }.isInstanceOf(DucklakeException::class.java)
                    .hasStackTraceContaining("end_snapshot")
                assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
                assertThat(catalog.getDataFiles(table.tableId, snapshot)).isEqualTo(filesBefore)
                assertThat(catalog.getTableStats(table.tableId)).isEqualTo(statsBefore)
                assertThat(globalStats(table)).isEqualTo(globalsBefore)
                assertThat(pg("SELECT * FROM ducklake_files_scheduled_for_deletion ORDER BY data_file_id, path")).isEqualTo(scheduledBefore)
            }
            finally {
                metadataSql("ALTER TABLE ${inline.tableName} RENAME COLUMN broken_end TO end_snapshot")
            }
            assertRowsAndAggregates(table, listOf(-100, 1, 2, 100, null))
        }
    }

    private fun createSource(name: String, values: List<Int>): Pair<DucklakeTable, DucklakeDataFile> {
        catalog.createTable("test_schema", name, listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val table = catalog.getTable("test_schema", name, catalog.currentSnapshotId)!!
        val fragment = writeFile(table, "source.parquet", values)
        catalog.commitInsert(table.tableId, listOf(fragment))
        val source = catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).single()
        assertFileStats(table, source, fragment)
        return table to source
    }

    private fun insertInlineRows(table: DucklakeTable) {
        native("INSERT INTO ${relation(table)} VALUES (-100), (100), (NULL)")
        val snapshot = catalog.currentSnapshotId
        val info = catalog.getInlinedDataInfos(table.tableId, snapshot).single()
        assertThat(info.hasLiveRows).isTrue()
        assertThat(catalog.countInlinedRows(table.tableId, info.schemaVersion, snapshot)).isEqualTo(3L)
        assertThat(pg("SELECT id FROM ${info.tableName} WHERE end_snapshot IS NULL ORDER BY id NULLS LAST"))
            .containsExactly(listOf("-100"), listOf("100"), listOf<String?>(null))
        assertThat(catalog.getDataFiles(table.tableId, snapshot)).hasSize(1)
    }

    private fun materializeRewrite(
        table: DucklakeTable,
        source: DucklakeDataFile,
        snapshot: Long,
        partial: Boolean = false,
    ): DucklakeWriteFragment {
        val rows = oracle("SELECT rowid, id FROM ${relation(table)} AT (VERSION => $snapshot) " +
            "WHERE rowid >= ${source.rowIdStart} AND rowid < ${source.rowIdStart + source.recordCount} ORDER BY rowid")
        return writeFile(table, "rewritten.parquet", rows.map { it[1]!!.toInt() }, rows.map { it[0]!!.toLong() },
            if (partial) source.beginSnapshot else null)
    }

    /** Every user column has complete int32 stats; added nullable columns are explicitly all-NULL. */
    private fun writeFile(
        table: DucklakeTable,
        name: String,
        values: List<Int>,
        rowIds: List<Long>? = null,
        insertionSnapshot: Long? = null,
    ): DucklakeWriteFragment {
        val columns = catalog.getTableColumns(table.tableId, catalog.currentSnapshotId)
        val rows = values.mapIndexed { index, value ->
            val fields = columns.map { "${if (it.columnName == "id") value.toString() else "NULL"}::INTEGER AS ${it.columnName}" } +
                listOfNotNull(rowIds?.let { "${it[index]}::BIGINT AS _ducklake_internal_row_id" },
                    insertionSnapshot?.let { "$it::BIGINT AS _ducklake_internal_snapshot_id" })
            "SELECT ${fields.joinToString(", ")}"
        }
        val fieldIds = columns.map { "${it.columnName}: ${it.columnId}" } +
            listOfNotNull(rowIds?.let { "_ducklake_internal_row_id: $ROW_ID_FIELD_ID" },
                insertionSnapshot?.let { "_ducklake_internal_snapshot_id: $SNAPSHOT_ID_FIELD_ID" })
        val path = tableDir(table).resolve(name)
        writeParquet(path, rows.joinToString(" UNION ALL ") + " ORDER BY id", "{${fieldIds.joinToString(", ")}}")
        val count = values.size.toLong()
        return DucklakeWriteFragment(name, Files.size(path), 0L, count, columns.map {
            if (it.columnName == "id") {
                DucklakeFileColumnStats(it.columnId, count * 4, count, 0L, values.min().toString(), values.max().toString(), false)
            }
            else {
                DucklakeFileColumnStats(it.columnId, count * 4, 0L, count, null, null, false)
            }
        })
    }

    private fun writeParquet(path: Path, select: String, fieldIds: String? = null) {
        Files.createDirectories(path.parent)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use {
                it.execute("COPY ($select) TO ${q(path.toString())} (FORMAT PARQUET${fieldIds?.let { ids -> ", FIELD_IDS $ids" }.orEmpty()})")
            }
        }
    }

    private fun assertFileStats(table: DucklakeTable, file: DucklakeDataFile, fragment: DucklakeWriteFragment) {
        assertThat(pg("SELECT column_id, value_count, null_count, min_value, max_value, contains_nan::text " +
            "FROM ducklake_file_column_stats WHERE table_id = ${table.tableId} AND data_file_id = ${file.dataFileId} ORDER BY column_id"))
            .containsExactlyElementsOf(fragment.columnStats.sortedBy { it.columnId }.map {
                // int32 has no NaN flag in the upstream metadata representation.
                listOf(it.columnId.toString(), it.valueCount.toString(), it.nullCount.toString(), it.minValue, it.maxValue, null)
            })
    }

    private fun assertAccounting(table: DucklakeTable, gross: Long, live: Long, nextRowId: Long, fileBytes: Long) {
        assertThat(pg("SELECT record_count, next_row_id, file_size_bytes FROM ducklake_table_stats WHERE table_id = ${table.tableId}"))
            .containsExactly(listOf(gross.toString(), nextRowId.toString(), fileBytes.toString()))
        assertThat(catalog.getLiveRowCount(table.tableId, catalog.currentSnapshotId)).isEqualTo(live)
    }

    private fun assertSuppressed(table: DucklakeTable) {
        assertThat(globalStats(table)).`as`("all global column-stat rows must be absent while live inline values are unaccounted for").isEmpty()
    }

    private fun globalStats(table: DucklakeTable) = pg(
        "SELECT column_id, contains_null::text, contains_nan::text, min_value, max_value FROM ducklake_table_column_stats " +
            "WHERE table_id = ${table.tableId} ORDER BY column_id",
    )

    private fun nextRowId(table: DucklakeTable) =
        pg("SELECT next_row_id FROM ducklake_table_stats WHERE table_id = ${table.tableId}").single().single()!!.toLong()

    private fun assertRowsAndAggregates(table: DucklakeTable, values: List<Int?>) {
        val nonNull = values.filterNotNull()
        withOracle { connection ->
            assertThat(query(connection, "SELECT min(id), max(id) FROM ${relation(table)}"))
                .containsExactly(listOf(nonNull.min().toString(), nonNull.max().toString()))
            assertThat(query(connection, "SELECT count(*) FILTER (WHERE id IS NULL), count(*) FROM ${relation(table)}"))
                .containsExactly(listOf((values.size - nonNull.size).toString(), values.size.toString()))
            assertThat(query(connection, "SELECT id FROM ${relation(table)} ORDER BY id NULLS LAST").map { it.single() })
                .containsExactlyElementsOf(nonNull.sorted().map { it.toString() } + List(values.size - nonNull.size) { null })
        }
    }

    private fun scanWithRowIds(table: DucklakeTable, snapshot: Long) =
        oracle("SELECT rowid, id FROM ${relation(table)} AT (VERSION => $snapshot) ORDER BY rowid")

    private fun tableDir(table: DucklakeTable): Path {
        val schema = catalog.listSchemas(catalog.currentSnapshotId).single { it.schemaId == table.schemaId }
        return isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(table.path)
    }

    private fun relation(table: DucklakeTable) = "lake.test_schema.${table.tableName}"
    private fun q(value: String) = "'${value.replace("'", "''")}'"

    // getString preserves SQL NULL rather than silently turning it into a zero in scan assertions.
    private fun query(connection: Connection, sql: String): List<List<String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val count = result.metaData.columnCount
                generateSequence { if (result.next()) (1..count).map { result.getString(it) } else null }.toList()
            }
        }

    private fun <T> withMetadata(block: (Connection) -> T): T =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use(block)

    private fun pg(sql: String) = withMetadata { query(it, sql) }
    private fun metadataSql(sql: String) = withMetadata { connection -> connection.createStatement().use { it.execute(sql) } }
    private fun oracle(sql: String) = withOracle { query(it, sql) }
    private fun native(sql: String) = withOracle { connection -> connection.createStatement().use { it.execute(sql) } }

    private fun <T> withOracle(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
                statement.execute("ATTACH ${q(isolated.duckDbAttachUri)} AS lake (DATA_PATH ${q(isolated.dataDir.toAbsolutePath().toString())})")
            }
            block(connection)
        }
}
