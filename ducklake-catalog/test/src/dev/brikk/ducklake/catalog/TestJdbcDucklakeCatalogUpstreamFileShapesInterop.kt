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

/**
 * The upstream v1.5 file shapes this library now writes (TODO-rectify-from-eval.md W-D3, W-D6, R-D2):
 *
 *  - **snapshot-tagged delete files**: a second DELETE on a data file DELETES the superseded
 *    `ducklake_delete_file` row (scheduling its file) and registers ONE 3-column file back-dated to
 *    the first deletion with `partial_max` = this commit, so a data file has a single delete-file
 *    row for its whole history and readers window it by `_ducklake_internal_snapshot_id`;
 *  - **back-dated flush**: `flushInlinedDataWithSnapshots` registers the materialised file at the
 *    MIN/MAX embedded insert snapshot, writes 3-column delete files for rows deleted while inlined,
 *    and physically removes the inlined rows;
 *  - **row-id-preserving rewrite**: compaction allocates no new row ids.
 *
 * Every scenario is read back through stock DuckDB, including time travel to the snapshots in
 * between — the point of these shapes is that DuckDB resolves history from the files alone.
 */
class TestJdbcDucklakeCatalogUpstreamFileShapesInterop {
    companion object {
        private const val ROW_ID_FIELD_ID = 2147483540L // MultiFileReader::ROW_ID_FIELD_ID
        private const val SNAPSHOT_ID_FIELD_ID = 2147483539L // MultiFileReader::LAST_UPDATED_SEQUENCE_NUMBER_ID

        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "upstream-file-shapes")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            }
            catalog = JdbcDucklakeCatalog(config)
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::catalog.isInitialized) {
                catalog.close()
            }
            if (::server.isInitialized) {
                server.close()
            }
        }
    }

    private fun <T> withDuckDb(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake")
                statement.execute("LOAD ducklake")
                statement.execute("INSTALL postgres")
                statement.execute("LOAD postgres")
                statement.execute(
                    "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                        isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
                )
            }
            block(connection)
        }

    private fun duck(sql: String): List<Long> =
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
            }
        }

    private fun scratch(sql: String) {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { st -> st.execute(sql) } }
    }

    private fun parquetLongs(path: Path, column: String): List<Long> =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT $column FROM read_parquet(${q(path.toString())})").use { result ->
                    generateSequence { if (result.next()) result.getLong(1) else null }.toList()
                }
            }
        }

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    private fun tableDir(table: DucklakeTable, snapshot: Long): Path {
        val schema = catalog.listSchemas(snapshot).single { it.schemaId == table.schemaId }
        return isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(table.path)
    }

    private fun pg(sql: String): List<List<Any?>> =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    generateSequence { if (rs.next()) (1..n).map { rs.getObject(it) } else null }.toList()
                }
            }
        }

    /** Writes a 3-column positional delete file with per-position snapshot ids; returns (path, size). */
    private fun writeTaggedDeleteFile(dir: Path, name: String, dataFilePath: String, positions: Map<Long, Long>): Long {
        val rows = positions.entries.joinToString(" UNION ALL ") { (pos, snap) ->
            "SELECT ${q(dataFilePath)} AS file_path, ${pos}::BIGINT AS pos, ${snap}::BIGINT AS _ducklake_internal_snapshot_id"
        }
        // DuckDB requires positions sorted and strictly increasing within a delete file.
        scratch("COPY (SELECT * FROM ($rows) ORDER BY pos) TO ${q(dir.resolve(name).toString())} (FORMAT PARQUET)")
        return Files.size(dir.resolve(name))
    }

    /** `flushed.parquet`: (id, v, _ducklake_internal_row_id, _ducklake_internal_snapshot_id) with DuckLake field ids. */
    private fun writeFlushedDataFile(dir: Path, inlinedRows: List<List<Any?>>, cols: Map<String, DucklakeColumn>) {
        val select = inlinedRows.joinToString(" UNION ALL ") { r ->
            "SELECT ${(r[3] as Number).toInt()}::INTEGER AS id, ${q(r[4].toString())} AS v, " +
                "${(r[0] as Number).toLong()}::BIGINT AS _ducklake_internal_row_id, " +
                "${(r[1] as Number).toLong()}::BIGINT AS _ducklake_internal_snapshot_id"
        }
        val fieldIds = "{id: ${cols.getValue("id").columnId}, v: ${cols.getValue("v").columnId}, " +
            "_ducklake_internal_row_id: $ROW_ID_FIELD_ID, _ducklake_internal_snapshot_id: $SNAPSHOT_ID_FIELD_ID}"
        scratch(
            "COPY ($select ORDER BY _ducklake_internal_row_id) TO ${q(dir.resolve("flushed.parquet").toString())} " +
                "(FORMAT PARQUET, FIELD_IDS $fieldIds)",
        )
    }

    /** One physical inlined table/schema version per file; [oldColumn] is absent after DROP. */
    private fun writeVersionedFlushFile(
        dir: Path,
        name: String,
        rows: List<List<Any?>>,
        idColumn: DucklakeColumn,
        oldColumn: DucklakeColumn?,
    ) {
        val select = rows.joinToString(" UNION ALL ") { row ->
            val old = oldColumn?.let { ", ${(row[3] as Number).toInt()}::INTEGER AS old_value" }.orEmpty()
            "SELECT ${(row[2] as Number).toInt()}::INTEGER AS id$old, " +
                "${(row[0] as Number).toLong()}::BIGINT AS _ducklake_internal_row_id, " +
                "${(row[1] as Number).toLong()}::BIGINT AS _ducklake_internal_snapshot_id"
        }
        val oldFieldId = oldColumn?.let { ", old_value: ${it.columnId}" }.orEmpty()
        val fieldIds = "{id: ${idColumn.columnId}$oldFieldId, _ducklake_internal_row_id: $ROW_ID_FIELD_ID, " +
            "_ducklake_internal_snapshot_id: $SNAPSHOT_ID_FIELD_ID}"
        scratch(
            "COPY ($select ORDER BY _ducklake_internal_row_id) TO ${q(dir.resolve(name).toString())} " +
                "(FORMAT PARQUET, FIELD_IDS $fieldIds)",
        )
    }

    private fun versionedFlushFragment(dir: Path, name: String, rows: Long, idColumn: Long) =
        DucklakeWriteFragment(
            name, Files.size(dir.resolve(name)), 0L, rows,
            listOf(DucklakeFileColumnStats(idColumn, rows * 4, rows, 0L, "1", "4", false)),
        )

    @Test
    fun secondDeleteConsolidatesIntoOneBackDatedRowAndDuckDbTimeTravelsThroughIt() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.cons (id INTEGER)")
                st.execute("INSERT INTO lake.test_schema.cons VALUES (0), (1), (2), (3), (4)")
                st.execute("CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'cons')")
            }
        }
        val r0 = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "cons", r0)!!
        val file = catalog.getDataFiles(table.tableId, r0).single()
        val dir = tableDir(table, r0)
        val dataFilePath = dir.resolve(file.path).toString()

        // DELETE #1 (id = 4 at position 4), tagged with the commit snapshot the writer expects: r0 + 1.
        val s1 = r0 + 1
        val size1 = writeTaggedDeleteFile(dir, "del-1.parquet", dataFilePath, mapOf(4L to s1))
        catalog.commitDelete(
            table.tableId,
            listOf(DucklakeDeleteFragment(file.dataFileId, "del-1.parquet", 1, size1, 0, 1, "parquet", s1, s1)),
            r0,
        )
        assertThat(catalog.currentSnapshotId).isEqualTo(s1)

        // DELETE #2 (id = 0 at position 0): the writer carries position 4 with ITS snapshot (s1) and
        // tags the new position with s1 + 1.
        val s2 = s1 + 1
        val size2 = writeTaggedDeleteFile(dir, "del-2.parquet", dataFilePath, mapOf(4L to s1, 0L to s2))
        catalog.commitDelete(
            table.tableId,
            listOf(DucklakeDeleteFragment(file.dataFileId, "del-2.parquet", 2, size2, 0, 1, "parquet", s1, s2)),
            s1,
        )
        assertThat(catalog.currentSnapshotId).isEqualTo(s2)

        // Catalog shape: exactly ONE delete-file row for the data file, back-dated, superseded one scheduled.
        val rows = pg(
            "SELECT path, begin_snapshot, end_snapshot, partial_max FROM ducklake_delete_file WHERE data_file_id = ${file.dataFileId}",
        )
        assertThat(rows).hasSize(1)
        assertThat(rows.single()[0]).isEqualTo("del-2.parquet")
        assertThat((rows.single()[1] as Number).toLong()).`as`("begin = first deletion snapshot").isEqualTo(s1)
        assertThat(rows.single()[2]).isNull()
        assertThat((rows.single()[3] as Number).toLong()).`as`("partial_max = this commit").isEqualTo(s2)
        assertThat(pg("SELECT path FROM ducklake_files_scheduled_for_deletion").map { it[0].toString() })
            .anyMatch { it.endsWith("del-1.parquet") }

        // The oracle: DuckDB resolves every snapshot from that single file.
        assertThat(duck("SELECT count(*) FROM lake.test_schema.cons AT (VERSION => $r0)")).containsExactly(5L)
        assertThat(duck("SELECT count(*) FROM lake.test_schema.cons AT (VERSION => $s1)")).containsExactly(4L)
        assertThat(duck("SELECT id FROM lake.test_schema.cons AT (VERSION => $s1) ORDER BY id")).containsExactly(0L, 1L, 2L, 3L)
        assertThat(duck("SELECT id FROM lake.test_schema.cons ORDER BY id")).containsExactly(1L, 2L, 3L)
        // And this library's live-count / change-feed views agree (the metadata-only live count is
        // exact at the latest snapshot; through a consolidated file it is a lower bound for time travel).
        assertThat(catalog.getLiveRowCount(table.tableId, s2)).isEqualTo(3L)
        assertThat(catalog.getLiveRowCount(table.tableId, s1)).isLessThanOrEqualTo(4L)
        val feed = catalog.getDeletionsBetween(table.tableId, s2, s2).filter { !it.fullFileDelete }
        assertThat(feed).hasSize(1)
        assertThat(feed.single().currentDeletePartialMax).isEqualTo(s2)
        assertThat(feed.single().previousDeletePath).`as`("began before the window -> previous is itself").isEqualTo("del-2.parquet")

        // A tagged delete whose guessed commit snapshot is stale must be refused, not laundered.
        catalog.createSchema("bump_between_read_and_commit")
        val size3 = writeTaggedDeleteFile(dir, "del-3.parquet", dataFilePath, mapOf(4L to s1, 0L to s2, 1L to (s2 + 1)))
        assertThatThrownBy {
            catalog.commitDelete(
                table.tableId,
                listOf(DucklakeDeleteFragment(file.dataFileId, "del-3.parquet", 3, size3, 0, 1, "parquet", s1, s2 + 1)),
                s2,
            )
        }.isInstanceOf(LogicalConflictException::class.java).hasMessageContaining("lands at snapshot")
        assertThat(duck("SELECT id FROM lake.test_schema.cons ORDER BY id")).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun flushWithSnapshotsBackDatesTheFileAndDuckDbTimeTravelsThroughIt() {
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.fl (id INTEGER, v VARCHAR)")
                st.execute("INSERT INTO lake.test_schema.fl VALUES (1, 'a'), (2, 'b'), (3, 'c')") // sA
                st.execute("INSERT INTO lake.test_schema.fl VALUES (4, 'd'), (5, 'e')") // sB
                st.execute("DELETE FROM lake.test_schema.fl WHERE id = 2") // sC
            }
        }
        val sC = catalog.currentSnapshotId
        val sB = sC - 1
        val sA = sC - 2
        val table = catalog.getTable("test_schema", "fl", sC)!!
        val cols = catalog.getTableColumns(table.tableId, sC).associateBy { it.columnName }
        val sv = catalog.getInlinedDataInfos(table.tableId, sC).single().schemaVersion
        assertThat(catalog.getDataFiles(table.tableId, sC)).isEmpty()

        // Materialise ALL inlined rows (live and deleted) with their original row ids and insert
        // snapshots — what upstream's flush writes — plus a tagged delete file for the deleted one.
        val inlinedRows = pg("SELECT row_id, begin_snapshot, end_snapshot, id, v FROM ducklake_inlined_data_${table.tableId}_$sv ORDER BY row_id")
        assertThat(inlinedRows).hasSize(5)
        val dir = tableDir(table, sC).also { Files.createDirectories(it) }
        writeFlushedDataFile(dir, inlinedRows, cols)
        val deletedPos = inlinedRows.indexOfFirst { it[2] != null }.toLong() // position of the row deleted at sC
        val deleteSize = writeTaggedDeleteFile(dir, "flushed-deletes.parquet", dir.resolve("flushed.parquet").toString(), mapOf(deletedPos to sC))
        val minRowId = inlinedRows.minOf { (it[0] as Number).toLong() }

        val fragment = DucklakeWriteFragment(
            "flushed.parquet", Files.size(dir.resolve("flushed.parquet")), 0L, 5L,
            listOf(
                DucklakeFileColumnStats(cols.getValue("id").columnId, 40L, 5L, 0L, "1", "5", false),
                DucklakeFileColumnStats(cols.getValue("v").columnId, 40L, 5L, 0L, "a", "e", false),
            ),
        )
        catalog.flushInlinedDataWithSnapshots(
            table.tableId,
            listOf(
                FlushedInlinedFile(
                    fragment, sA, sB, minRowId,
                    DucklakeDeleteFragment(0L, "flushed-deletes.parquet", 1, deleteSize, 0, 1, "parquet", sC, sC),
                ),
            ),
            emptyList(),
            sC,
        )
        val sF = catalog.currentSnapshotId
        val registered = catalog.getDataFiles(table.tableId, sF).single()
        assertThat(registered.beginSnapshot).`as`("back-dated to the first insert").isEqualTo(sA)
        assertThat(registered.partialMax).isEqualTo(sB)
        assertThat(registered.rowIdStart).isEqualTo(minRowId)
        assertThat(pg("SELECT count(*) FROM ducklake_inlined_data_${table.tableId}_$sv").single()[0].toString()).isEqualTo("0")

        // Oracle: DuckDB sees exactly the rows that were live at every snapshot, all from the files.
        assertThat(duck("SELECT id FROM lake.test_schema.fl AT (VERSION => $sA) ORDER BY id")).containsExactly(1L, 2L, 3L)
        assertThat(duck("SELECT id FROM lake.test_schema.fl AT (VERSION => $sB) ORDER BY id")).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(duck("SELECT id FROM lake.test_schema.fl AT (VERSION => $sC) ORDER BY id")).containsExactly(1L, 3L, 4L, 5L)
        assertThat(duck("SELECT id FROM lake.test_schema.fl ORDER BY id")).containsExactly(1L, 3L, 4L, 5L)
        assertThat(duck("SELECT rowid FROM lake.test_schema.fl ORDER BY rowid"))
            .`as`("row identity preserved through the flush")
            .containsExactly(*inlinedRows.filter { it[2] == null }.map { (it[0] as Number).toLong() }.toTypedArray())
        assertThat(catalog.getLiveRowCount(table.tableId, sF)).isEqualTo(4L)
        assertThat(catalog.getTableStats(table.tableId)!!.recordCount).`as`("gross unchanged by a flush").isEqualTo(5L)
    }

    @Test
    fun flushUsesDistinctRowIdRangesPerSchemaVersionAndPreservesDroppedFields() {
        withDuckDb { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE lake.test_schema.fl_versions (id INTEGER, old_value INTEGER)")
                statement.execute("INSERT INTO lake.test_schema.fl_versions VALUES (1, 10), (2, 20)")
            }
        }
        val oldSnapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "fl_versions", oldSnapshot)!!
        val oldColumns = catalog.getTableColumns(table.tableId, oldSnapshot).associateBy { it.columnName }
        catalog.dropColumn(table.tableId, oldColumns.getValue("old_value").columnId)
        val droppedSnapshot = catalog.currentSnapshotId
        withDuckDb { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO lake.test_schema.fl_versions VALUES (3), (4)")
            }
        }
        val newSnapshot = catalog.currentSnapshotId
        val newColumns = catalog.getTableColumns(table.tableId, newSnapshot).associateBy { it.columnName }
        val versions = catalog.getInlinedDataInfos(table.tableId, newSnapshot).sortedBy { it.schemaVersion }

        val oldRows = pg(
            "SELECT row_id, begin_snapshot, id, old_value FROM ducklake_inlined_data_${table.tableId}_${versions[0].schemaVersion} " +
                "ORDER BY row_id",
        )
        val newRows = pg(
            "SELECT row_id, begin_snapshot, id FROM ducklake_inlined_data_${table.tableId}_${versions[1].schemaVersion} " +
                "ORDER BY row_id",
        )
        val oldRowStart = (oldRows.first()[0] as Number).toLong()
        val newRowStart = (newRows.first()[0] as Number).toLong()

        val dir = tableDir(table, newSnapshot).also { Files.createDirectories(it) }
        writeVersionedFlushFile(dir, "old-schema.parquet", oldRows, oldColumns.getValue("id"), oldColumns.getValue("old_value"))
        writeVersionedFlushFile(dir, "new-schema.parquet", newRows, newColumns.getValue("id"), null)
        val flushFiles = listOf(
                FlushedInlinedFile(
                    versionedFlushFragment(dir, "old-schema.parquet", oldRows.size.toLong(), oldColumns.getValue("id").columnId),
                    oldSnapshot,
                    oldSnapshot,
                    oldRowStart,
                ),
                FlushedInlinedFile(
                    versionedFlushFragment(dir, "new-schema.parquet", newRows.size.toLong(), newColumns.getValue("id").columnId),
                    newSnapshot,
                    newSnapshot,
                    newRowStart,
                ),
            )
        assertLegacyGlobalStartRejected(table.tableId, flushFiles, oldRowStart)
        catalog.flushInlinedDataWithSnapshots(
            table.tableId,
            flushFiles,
            emptyList(),
            newSnapshot,
        )
        assertVersionedFlush(table, versions, oldSnapshot, droppedSnapshot, oldRowStart, newRowStart)
    }

    @Suppress("DEPRECATION")
    private fun assertLegacyGlobalStartRejected(tableId: Long, files: List<FlushedInlinedFile>, rowIdStart: Long) {
        val before = catalog.currentSnapshotId
        assertThatThrownBy { catalog.flushInlinedDataWithSnapshots(tableId, files, rowIdStart) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("row-id ranges overlap")
        assertThat(catalog.currentSnapshotId).isEqualTo(before)
    }

    private fun assertVersionedFlush(
        table: DucklakeTable,
        versions: List<DucklakeInlinedDataInfo>,
        oldSnapshot: Long,
        droppedSnapshot: Long,
        oldRowStart: Long,
        newRowStart: Long,
    ) {
        assertThat(versions).hasSize(2)
        assertThat(newRowStart).isGreaterThan(oldRowStart)
        val files = catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).sortedBy { it.rowIdStart }
        assertThat(files.map { it.rowIdStart }).containsExactly(oldRowStart, newRowStart)
        assertThat(files[0].rowIdStart + files[0].recordCount).isLessThanOrEqualTo(files[1].rowIdStart)
        versions.forEach { version ->
            assertThat(pg("SELECT count(*) FROM ducklake_inlined_data_${table.tableId}_${version.schemaVersion}").single()[0])
                .isEqualTo(0L)
        }

        assertThat(duck("SELECT old_value FROM lake.test_schema.fl_versions AT (VERSION => $oldSnapshot) ORDER BY id"))
            .containsExactly(10L, 20L)
        assertThat(duck("SELECT id FROM lake.test_schema.fl_versions AT (VERSION => $droppedSnapshot) ORDER BY id"))
            .containsExactly(1L, 2L)
        assertThat(duck("SELECT id FROM lake.test_schema.fl_versions ORDER BY id")).containsExactly(1L, 2L, 3L, 4L)
        assertThat(duck("SELECT rowid FROM lake.test_schema.fl_versions ORDER BY rowid"))
            .containsExactly(oldRowStart, oldRowStart + 1, newRowStart, newRowStart + 1)
    }

    @Test
    fun flushDrainsInlinedFileDeletesIntoTaggedReplacementsAndSchedulesSupersededFiles() {
        withDuckDb { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE lake.test_schema.flush_deletes (id INTEGER)")
                statement.execute("INSERT INTO lake.test_schema.flush_deletes VALUES (1), (2), (3), (4)")
                statement.execute(
                    "CALL ducklake_flush_inlined_data('lake', schema_name => 'test_schema', table_name => 'flush_deletes')",
                )
            }
        }
        val base = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "flush_deletes", base)!!
        val dataFile = catalog.getDataFiles(table.tableId, base).single()
        val dir = tableDir(table, base)
        val dataPath = dir.resolve(dataFile.path)
        val ids = parquetLongs(dataPath, "id")
        val pos1 = ids.indexOf(1L).toLong()
        val pos3 = ids.indexOf(3L).toLong()

        withDuckDb { it.createStatement().use { st -> st.execute("DELETE FROM lake.test_schema.flush_deletes WHERE id = 1") } }
        val delete1 = catalog.currentSnapshotId
        val size1 = writeTaggedDeleteFile(dir, "flushed-file-del-1.parquet", dataPath.toString(), mapOf(pos1 to delete1))
        catalog.flushInlinedDataWithSnapshots(
            table.tableId,
            emptyList(),
            listOf(DucklakeDeleteFragment(dataFile.dataFileId, "flushed-file-del-1.parquet", 1, size1, 0, 1, "parquet", delete1, delete1)),
            delete1,
        )
        assertThat(pg("SELECT count(*) FROM ducklake_inlined_delete_${table.tableId}").single()[0]).isEqualTo(0L)

        withDuckDb { it.createStatement().use { st -> st.execute("DELETE FROM lake.test_schema.flush_deletes WHERE id = 3") } }
        val delete2 = catalog.currentSnapshotId
        val size2 = writeTaggedDeleteFile(
            dir,
            "flushed-file-del-2.parquet",
            dataPath.toString(),
            mapOf(pos1 to delete1, pos3 to delete2),
        )
        catalog.flushInlinedDataWithSnapshots(
            table.tableId,
            emptyList(),
            listOf(DucklakeDeleteFragment(dataFile.dataFileId, "flushed-file-del-2.parquet", 2, size2, 0, 1, "parquet", delete1, delete2)),
            delete2,
        )
        assertFlushedFileDeletes(table, dataFile, base, delete1, delete2)
    }

    private fun assertFlushedFileDeletes(
        table: DucklakeTable,
        dataFile: DucklakeDataFile,
        base: Long,
        delete1: Long,
        delete2: Long,
    ) {
        assertThat(pg("SELECT count(*) FROM ducklake_inlined_delete_${table.tableId}").single()[0]).isEqualTo(0L)
        val active = catalog.getDataFiles(table.tableId, catalog.currentSnapshotId).single()
        assertThat(active.deleteFilePath).isEqualTo("flushed-file-del-2.parquet")
        assertThat(active.deleteFilePartialMax).isEqualTo(delete2)
        assertThat(pg("SELECT path FROM ducklake_files_scheduled_for_deletion").map { it[0].toString() })
            .anyMatch { it.endsWith("flushed-file-del-1.parquet") }
        assertThat(duck("SELECT id FROM lake.test_schema.flush_deletes AT (VERSION => $base) ORDER BY id"))
            .containsExactly(1L, 2L, 3L, 4L)
        assertThat(duck("SELECT id FROM lake.test_schema.flush_deletes AT (VERSION => $delete1) ORDER BY id"))
            .containsExactly(2L, 3L, 4L)
        assertThat(duck("SELECT id FROM lake.test_schema.flush_deletes ORDER BY id")).containsExactly(2L, 4L)
        assertThat(active.dataFileId).isEqualTo(dataFile.dataFileId)
    }

    @Test
    fun flushRejectsAnInlinedChangeCommittedAfterTheCallerReadSnapshot() {
        withDuckDb { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE lake.test_schema.flush_stale (id INTEGER)")
                statement.execute("INSERT INTO lake.test_schema.flush_stale VALUES (1), (2)")
            }
        }
        val readSnapshot = catalog.currentSnapshotId
        val table = catalog.getTable("test_schema", "flush_stale", readSnapshot)!!
        withDuckDb { it.createStatement().use { statement -> statement.execute("INSERT INTO lake.test_schema.flush_stale VALUES (3)") } }
        val beforeAttempt = catalog.currentSnapshotId

        assertThatThrownBy {
            catalog.flushInlinedDataWithSnapshots(table.tableId, emptyList(), emptyList(), readSnapshot)
        }.isInstanceOf(TransactionConflictException::class.java)
            .hasMessageContaining("inlined-inserted")
        assertThat(catalog.currentSnapshotId).isEqualTo(beforeAttempt)
        assertThat(duck("SELECT id FROM lake.test_schema.flush_stale ORDER BY id")).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun rewritePreservesRowIdsAndDoesNotAdvanceTheAllocator() {
        catalog.createSchema("rw")
        catalog.createTable("rw", "t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val snapshot = catalog.currentSnapshotId
        val tableId = catalog.getTable("rw", "t", snapshot)!!.tableId
        val idCol = catalog.getTableColumns(tableId, snapshot).single().columnId
        fun frag(name: String, rows: Long) =
            DucklakeWriteFragment("rw/t/$name.parquet", 100L, 0L, rows, listOf(DucklakeFileColumnStats(idCol, 8L, rows, 0L, "1", "9", false)))
        catalog.commitInsert(tableId, listOf(frag("a", 10)))
        catalog.commitInsert(tableId, listOf(frag("b", 5)))
        val read = catalog.currentSnapshotId
        val files = catalog.getDataFiles(tableId, read).sortedBy { it.rowIdStart }
        assertThat(files.map { it.rowIdStart }).containsExactly(0L, 10L)
        val nextRowIdBefore = pg("SELECT next_row_id FROM ducklake_table_stats WHERE table_id = $tableId").single()[0]

        catalog.rewriteDataFiles(tableId, files.map { it.dataFileId }.toSet(), listOf(frag("merged", 15)), read)

        val merged = catalog.getDataFiles(tableId, catalog.currentSnapshotId).single()
        assertThat(merged.rowIdStart).`as`("registered at the smallest retired source's row_id_start").isEqualTo(0L)
        assertThat(pg("SELECT next_row_id FROM ducklake_table_stats WHERE table_id = $tableId").single()[0])
            .`as`("a compaction allocates no new row ids")
            .isEqualTo(nextRowIdBefore)
        assertThat(catalog.getTableStats(tableId)!!.recordCount).isEqualTo(15L)
    }
}
