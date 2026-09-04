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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Behaviour tests for the inlined-data [DucklakeCatalog] methods that had no coverage
 * (TODO-rectify-from-eval.md Q-5): [DucklakeCatalog.hasInlinedDeletes],
 * [DucklakeCatalog.getInlinedFileDeletesBetween], [DucklakeCatalog.readInlinedBeginSnapshots] and
 * [DucklakeCatalog.flushInlinedData].
 *
 * Stock DuckDB (default `data_inlining_row_limit`) produces the inlined rows and inlined deletes by
 * writing small INSERTs / DELETEs into the same PostgreSQL catalog; DuckDB's own change feed
 * (`ducklake_table_insertions` / `ducklake_table_deletions`) and `rowid` are the oracle for what the
 * library reads back, and for what it leaves behind after a flush.
 */
class TestJdbcDucklakeCatalogCoverageInlined {
    companion object {
        private const val SCHEMA = "test_schema"
        private const val ROW_ID_FIELD_ID = 2147483540L // MultiFileReader::ROW_ID_FIELD_ID

        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        /** Built once per class (JUnit instantiates the class per test method). */
        private var history: InlinedHistory? = null

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "coverage-inlined")
            catalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = isolated.jdbcUrl
                    catalogDatabaseUser = isolated.user
                    catalogDatabasePassword = isolated.password
                    dataPath = isolated.dataDir.toAbsolutePath().toString()
                    maxCatalogConnections = 5
                },
            )
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

    // ---------------------------------------------------------------- plumbing

    private fun <T> withDuckDb(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { st ->
                st.execute("INSTALL ducklake")
                st.execute("LOAD ducklake")
                st.execute("INSTALL postgres")
                st.execute("LOAD postgres")
                val dataPath = q(isolated.dataDir.toAbsolutePath().toString())
                st.execute("ATTACH ${q(isolated.duckDbAttachUri)} AS lake (DATA_PATH $dataPath)")
            }
            block(connection)
        }

    private fun Connection.rows(sql: String): List<List<String?>> =
        createStatement().use { st ->
            if (!st.execute(sql)) {
                return emptyList()
            }
            st.resultSet.use { rs ->
                val n = rs.metaData.columnCount
                generateSequence { if (rs.next()) (1..n).map { rs.getString(it) } else null }.toList()
            }
        }

    private fun duck(sql: String): List<List<String?>> = withDuckDb { it.rows(sql) }

    private fun duckColumn(sql: String): List<String?> = duck(sql).map { it[0] }

    private fun duckExec(vararg sql: String) = withDuckDb { c -> c.createStatement().use { st -> sql.forEach { st.execute(it) } } }

    private fun pg(sql: String): List<List<String?>> =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { it.rows(sql) }

    /** Catalog-less DuckDB for reading / writing Parquet files. */
    private fun scratch(sql: String): List<List<String?>> =
        DriverManager.getConnection("jdbc:duckdb:").use { it.rows(sql) }

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    private fun table(name: String): DucklakeTable = catalog.getTable(SCHEMA, name, catalog.currentSnapshotId)!!

    private fun tableDir(t: DucklakeTable): Path {
        val schema = catalog.listSchemas(catalog.currentSnapshotId).single { it.schemaId == t.schemaId }
        return Files.createDirectories(isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(t.path))
    }

    private fun changesOf(snapshotId: Long): String =
        catalog.listSnapshotChanges().single { it.snapshotId == snapshotId }.changesMade!!

    // ---------------------------------------------------------------- inlined history fixture

    /**
     * `inl` (id, v): 1..4 flushed into one Parquet file at [flushed]; `DELETE id = 1` (inlined delete) at
     * [del1]; `INSERT 5, 6` (inlined rows) at [ins2]; `DELETE id = 3` (inlined delete) at [del2];
     * `INSERT 7` (inlined row) at [ins3]; `DELETE id = 6` (an inlined ROW, not a file position) at [del3].
     */
    private data class InlinedHistory(
        val tableId: Long,
        val schemaVersion: Long,
        val file: DucklakeDataFile,
        val flushed: Long,
        val del1: Long,
        val ins2: Long,
        val del2: Long,
        val ins3: Long,
        val del3: Long,
    )

    private fun buildHistory(): InlinedHistory {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.inl (id INTEGER, v VARCHAR)",
            "INSERT INTO lake.$SCHEMA.inl VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')",
            "CALL ducklake_flush_inlined_data('lake', schema_name => '$SCHEMA', table_name => 'inl')",
        )
        val flushed = catalog.currentSnapshotId
        duckExec("DELETE FROM lake.$SCHEMA.inl WHERE id = 1")
        duckExec("INSERT INTO lake.$SCHEMA.inl VALUES (5, 'e'), (6, 'f')")
        duckExec("DELETE FROM lake.$SCHEMA.inl WHERE id = 3")
        duckExec("INSERT INTO lake.$SCHEMA.inl VALUES (7, 'g')")
        duckExec("DELETE FROM lake.$SCHEMA.inl WHERE id = 6")
        val del3 = catalog.currentSnapshotId
        assertThat(del3).`as`("one snapshot per statement").isEqualTo(flushed + 5)
        val t = table("inl")
        val file = catalog.getDataFiles(t.tableId, del3).single()
        val sv = catalog.getInlinedDataInfos(t.tableId, del3).single().schemaVersion
        return InlinedHistory(t.tableId, sv, file, flushed, flushed + 1, flushed + 2, flushed + 3, flushed + 4, del3)
    }

    private fun history(): InlinedHistory = synchronized(Companion) { history ?: buildHistory().also { history = it } }

    /** `id` values of the flushed Parquet file in file order — position i holds ids[i]. */
    private fun fileIdsInOrder(h: InlinedHistory): List<Int> {
        val dir = tableDir(catalog.getTableById(h.tableId, catalog.currentSnapshotId)!!)
        return scratch("SELECT id FROM read_parquet(${q(dir.resolve(h.file.path).toString())})").map { it[0]!!.toInt() }
    }

    // ---------------------------------------------------------------- hasInlinedDeletes / getInlinedFileDeletesBetween

    @Test
    fun hasInlinedDeletesIsSnapshotBoundAndFalseWithoutTheDynamicTable() {
        val h = history()
        assertThat(catalog.hasInlinedDeletes(h.tableId, h.flushed)).`as`("before the first inlined DELETE").isFalse()
        assertThat(catalog.hasInlinedDeletes(h.tableId, h.del1)).isTrue()
        assertThat(catalog.hasInlinedDeletes(h.tableId, h.del3)).isTrue()
        assertThat(pg("SELECT count(*) FROM information_schema.tables WHERE table_name = 'ducklake_inlined_delete_${h.tableId}'"))
            .containsExactly(listOf("1"))

        // A table DuckDB never inline-deleted from has no ducklake_inlined_delete_<t> table at all.
        duckExec(
            "CREATE TABLE lake.$SCHEMA.no_del (id INTEGER)",
            "INSERT INTO lake.$SCHEMA.no_del VALUES (1), (2)",
            "DELETE FROM lake.$SCHEMA.no_del WHERE id = 1",
        )
        val noDel = table("no_del")
        assertThat(catalog.hasInlinedDeletes(noDel.tableId, catalog.currentSnapshotId))
            .`as`("deleting an INLINED row end-snapshots it; it is not an inlined file-delete")
            .isFalse()
        assertThat(pg("SELECT count(*) FROM information_schema.tables WHERE table_name = 'ducklake_inlined_delete_${noDel.tableId}'"))
            .containsExactly(listOf("0"))
        assertThat(catalog.getInlinedFileDeletesBetween(noDel.tableId, 0, catalog.currentSnapshotId)).isEmpty()
    }

    @Test
    fun inlinedFileDeletesBetweenAreTheChangeFeedDeleteSideWindowedByBeginSnapshot() {
        val h = history()
        val ids = fileIdsInOrder(h)
        val pos1 = ids.indexOf(1).toLong()
        val pos3 = ids.indexOf(3).toLong()

        assertThat(catalog.getInlinedFileDeletesBetween(h.tableId, h.del1, h.del1))
            .containsExactly(DucklakeInlinedFileDelete(h.file.dataFileId, pos1, h.del1))
        assertThat(catalog.getInlinedFileDeletesBetween(h.tableId, h.del2, h.del2))
            .containsExactly(DucklakeInlinedFileDelete(h.file.dataFileId, pos3, h.del2))
        assertThat(catalog.getInlinedFileDeletesBetween(h.tableId, h.del1, h.del3))
            .`as`("inclusive window; the inlined-ROW delete at del3 is not a file delete")
            .containsExactlyInAnyOrder(
                DucklakeInlinedFileDelete(h.file.dataFileId, pos1, h.del1),
                DucklakeInlinedFileDelete(h.file.dataFileId, pos3, h.del2),
            )
        assertThat(catalog.getInlinedFileDeletesBetween(h.tableId, h.ins2, h.ins2)).isEmpty()
        assertThat(catalog.getInlinedFileDeletesBetween(h.tableId, h.del3, h.del3)).isEmpty()
        assertThat(catalog.getInlinedFileDeletesBetween(h.tableId, h.del3 + 1, h.del3 + 5)).isEmpty()
        assertThat(catalog.getInlinedDeletes(h.tableId, h.del3)).`as`("consistent with the cumulative view")
            .containsExactly(java.util.Map.entry(h.file.dataFileId, setOf(pos1, pos3)))
        // getDataFilesByIds is the companion read for resolving those positions to a file.
        assertThat(catalog.getDataFilesByIds(h.tableId, setOf(h.file.dataFileId)).single().path).isEqualTo(h.file.path)

        // Oracle: DuckDB's own change feed reports exactly those rows as deleted at those snapshots.
        assertThat(duckColumn("SELECT id FROM ducklake_table_deletions('lake', '$SCHEMA', 'inl', ${h.del1}, ${h.del1})")).containsExactly("1")
        assertThat(duckColumn("SELECT id FROM ducklake_table_deletions('lake', '$SCHEMA', 'inl', ${h.del2}, ${h.del2})")).containsExactly("3")
        assertThat(duckColumn("SELECT id FROM lake.$SCHEMA.inl ORDER BY id")).containsExactly("2", "4", "5", "7")
    }

    // ---------------------------------------------------------------- readInlinedBeginSnapshots

    @Test
    fun inlinedBeginSnapshotsAlignWithRowsAndRowIdsAndDuckDbInsertions() {
        val h = history()
        val columns = catalog.getTableColumns(h.tableId, h.del3)
        val idIndex = columns.indexOfFirst { it.columnName == "id" }

        val begins = catalog.readInlinedBeginSnapshots(h.tableId, h.schemaVersion, h.del3)
        val rowIds = catalog.readInlinedRowIds(h.tableId, h.schemaVersion, h.del3)
        val rows = catalog.readInlinedDataDecoded(h.tableId, h.schemaVersion, h.del3, columns)
        assertThat(rows.map { it[idIndex] }).`as`("live inlined rows: 5 (6 was deleted) and 7").containsExactly(5, 7)
        assertThat(begins).`as`("each row's own insert snapshot, in row_id order").containsExactly(h.ins2, h.ins3)
        assertThat(rowIds).hasSameSizeAs(begins).isSorted()
        assertThat(rowIds.first()).`as`("row ids continue after the 4 file rows").isEqualTo(h.file.rowIdStart + h.file.recordCount)

        assertThat(catalog.readInlinedBeginSnapshots(h.tableId, h.schemaVersion, h.ins3))
            .`as`("before 6 was deleted: three live rows")
            .containsExactly(h.ins2, h.ins2, h.ins3)
        assertThat(catalog.readInlinedBeginSnapshots(h.tableId, h.schemaVersion, h.ins2)).containsExactly(h.ins2, h.ins2)
        assertThat(catalog.readInlinedBeginSnapshots(h.tableId, h.schemaVersion, h.flushed)).`as`("no inlined row live yet").isEmpty()
        assertThat(catalog.readInlinedBeginSnapshots(h.tableId, h.schemaVersion + 99, h.del3)).`as`("absent inlined table").isEmpty()
        assertThat(begins).isEqualTo(
            pg("SELECT begin_snapshot FROM ducklake_inlined_data_${h.tableId}_${h.schemaVersion} WHERE end_snapshot IS NULL ORDER BY row_id")
                .map { it[0]!!.toLong() },
        )

        // Oracle: DuckDB attributes the same rows to the same insert snapshots.
        assertThat(duckColumn("SELECT id FROM ducklake_table_insertions('lake', '$SCHEMA', 'inl', ${h.ins2}, ${h.ins2}) ORDER BY id"))
            .containsExactly("5", "6")
        assertThat(duckColumn("SELECT id FROM ducklake_table_insertions('lake', '$SCHEMA', 'inl', ${h.ins3}, ${h.ins3})"))
            .containsExactly("7")
        assertThat(duckColumn("SELECT rowid FROM lake.$SCHEMA.inl WHERE id IN (5, 7) ORDER BY id").map { it!!.toLong() })
            .`as`("DuckDB's rowid for the inlined rows is the catalog row_id")
            .isEqualTo(rowIds)
    }

    // ---------------------------------------------------------------- flushInlinedData

    /** Writes the live inlined rows (with their original row ids embedded) to `<dir>/flushed.parquet`; returns its size. */
    private fun writeFlushFile(dir: Path, rows: List<List<Any?>>, rowIds: List<Long>, cols: Map<String, DucklakeColumn>): Long {
        val select = rows.indices.joinToString(" UNION ALL ") { i ->
            "SELECT ${rows[i][0]}::INTEGER AS id, ${q(rows[i][1].toString())} AS v, ${rowIds[i]}::BIGINT AS _ducklake_internal_row_id"
        }
        val fieldIds = "{id: ${cols.getValue("id").columnId}, v: ${cols.getValue("v").columnId}, _ducklake_internal_row_id: $ROW_ID_FIELD_ID}"
        val target = dir.resolve("flushed.parquet")
        scratch("COPY ($select ORDER BY _ducklake_internal_row_id) TO ${q(target.toString())} (FORMAT PARQUET, FIELD_IDS $fieldIds)")
        return Files.size(target)
    }

    @Test
    fun flushInlinedDataMovesTheRowsToAFileWithoutChangingWhatDuckDbReads() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.fl2 (id INTEGER, v VARCHAR)",
            "INSERT INTO lake.$SCHEMA.fl2 VALUES (10, 'x'), (20, 'y'), (30, 'z')",
        )
        val inlined = catalog.currentSnapshotId
        val t = table("fl2")
        val cols = catalog.getTableColumns(t.tableId, inlined).associateBy { it.columnName }
        val sv = catalog.getInlinedDataInfos(t.tableId, inlined).single().schemaVersion
        assertThat(catalog.getDataFiles(t.tableId, inlined)).isEmpty()
        val rows = catalog.readInlinedDataDecoded(t.tableId, sv, inlined, cols.values.sortedBy { it.columnOrder })
        val rowIds = catalog.readInlinedRowIds(t.tableId, sv, inlined)
        assertThat(rows).hasSize(3)
        val nextRowIdBefore = pg("SELECT next_row_id FROM ducklake_table_stats WHERE table_id = ${t.tableId}").single()[0]
        val duckBefore = duck("SELECT id, v FROM lake.$SCHEMA.fl2 ORDER BY id")

        val dir = tableDir(t)
        val size = writeFlushFile(dir, rows, rowIds, cols)
        val fragment = DucklakeWriteFragment(
            "flushed.parquet", size, 0L, 3L,
            listOf(
                DucklakeFileColumnStats(cols.getValue("id").columnId, 24L, 3L, 0L, "10", "30", false),
                DucklakeFileColumnStats(cols.getValue("v").columnId, 24L, 3L, 0L, "x", "z", false),
            ),
        )
        catalog.flushInlinedData(t.tableId, listOf(fragment), rowIds.min())
        val flushed = catalog.currentSnapshotId
        assertThat(flushed).isEqualTo(inlined + 1)

        val file = catalog.getDataFiles(t.tableId, flushed).single()
        assertThat(file.path).isEqualTo("flushed.parquet")
        assertThat(file.beginSnapshot).`as`("2-arg flush registers at the flush snapshot (not back-dated)").isEqualTo(flushed)
        assertThat(file.rowIdStart).`as`("identity-preserving: registered at the original min row id").isEqualTo(rowIds.min())
        assertThat(file.recordCount).isEqualTo(3L)
        assertThat(file.partialMax).isNull()
        assertThat(catalog.getInlinedDataInfos(t.tableId, flushed).single().hasLiveRows).isFalse()
        assertThat(catalog.countInlinedRows(t.tableId, sv, flushed)).isZero()
        assertThat(catalog.countInlinedRows(t.tableId, sv, inlined)).`as`("rows end-snapshotted, not deleted").isEqualTo(3L)
        assertThat(pg("SELECT DISTINCT end_snapshot FROM ducklake_inlined_data_${t.tableId}_$sv")).containsExactly(listOf(flushed.toString()))
        assertThat(catalog.getTableStats(t.tableId)!!.recordCount).`as`("gross count unchanged — a move, not an insert").isEqualTo(3L)
        assertThat(pg("SELECT next_row_id FROM ducklake_table_stats WHERE table_id = ${t.tableId}").single()[0]).isEqualTo(nextRowIdBefore)
        assertThat(catalog.getLiveRowCount(t.tableId, flushed)).isEqualTo(3L)
        assertThat(catalog.getSnapshot(flushed)!!.schemaVersion).`as`("no schema-version bump").isEqualTo(catalog.getSnapshot(inlined)!!.schemaVersion)
        assertThat(changesOf(flushed)).isEqualTo("inline_flush:${t.tableId}")

        // Oracle: DuckDB reads identical rows (now from the file), the same rowids, and sees the file.
        assertThat(duck("SELECT id, v FROM lake.$SCHEMA.fl2 ORDER BY id")).isEqualTo(duckBefore)
        assertThat(duck("SELECT id, v FROM lake.$SCHEMA.fl2 AT (VERSION => $inlined) ORDER BY id")).isEqualTo(duckBefore)
        assertThat(duckColumn("SELECT rowid FROM lake.$SCHEMA.fl2 ORDER BY rowid").map { it!!.toLong() }).isEqualTo(rowIds)
        assertThat(duck("SELECT file_count, delete_file_count FROM ducklake_table_info('lake') WHERE table_name = 'fl2'"))
            .containsExactly(listOf("1", "0"))
        assertThat(duckColumn("SELECT data_file FROM ducklake_list_files('lake', 'fl2', schema => '$SCHEMA')").single())
            .endsWith("flushed.parquet")
    }
}
