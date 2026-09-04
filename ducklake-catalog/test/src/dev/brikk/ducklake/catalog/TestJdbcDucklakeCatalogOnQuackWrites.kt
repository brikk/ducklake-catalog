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

/**
 * C-D5: the WRITE paths against a Quack-backed remote catalog. Upstream's `QuackMetadataManager`
 * routes every statement through `quack_query_by_name` inside the client's local transaction and
 * relies on the server-side transaction following the local `COMMIT` / `ROLLBACK`. The library
 * must (a) be able to run every DDL/DML path on this backend at all and (b) roll wrapped
 * statements back together with the direct ones when a commit aborts.
 */
class TestJdbcDucklakeCatalogOnQuackWrites {
    companion object {
        private var server: TestingDucklakeDuckDbQuackCatalogServer? = null
        private var catalog: JdbcDucklakeCatalog? = null
        private var dataDir: Path? = null

        @BeforeAll
        @JvmStatic
        fun setUpClass() {
            server = TestingDucklakeDuckDbQuackCatalogServer()
            dataDir = Files.createTempDirectory("ducklake-quack-writes-")
            catalog = JdbcDucklakeCatalog(
                DucklakeCatalogConfig().apply {
                    catalogDatabaseUrl = "jdbc:duckdb:quack://${server!!.getHost()}:${server!!.getMappedPort()}?metadata_catalog=writes_meta"
                    catalogDatabasePassword = server!!.getToken()
                    dataPath = dataDir!!.toAbsolutePath().toString()
                    maxCatalogConnections = 3
                },
            )
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            catalog?.close()
            server?.close()
            dataDir?.let { dir -> Files.walk(dir).use { w -> w.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        }
    }

    private val c: JdbcDucklakeCatalog get() = catalog!!

    private fun stats(colId: Long, min: String, max: String) = DucklakeFileColumnStats(colId, 16L, 2L, 0L, min, max, null)

    private fun createTable(schema: String, name: String): Pair<Long, Map<String, Long>> {
        c.createTable(
            schema, name,
            listOf(TableColumnSpec.leaf("id", "int32", true), TableColumnSpec.leaf("v", "varchar", true)),
            null, null,
        )
        val t = c.getTable(schema, name, c.currentSnapshotId)!!
        return t.tableId to c.getTableColumns(t.tableId, c.currentSnapshotId).associate { it.columnName to it.columnId }
    }

    @Test
    fun insertDeleteAlterAndDropAllCommitOnQuack() {
        c.createSchema("w")
        val (tableId, cols) = createTable("w", "t")

        c.commitInsert(
            tableId,
            listOf(
                DucklakeWriteFragment("w/a.parquet", 100L, 0L, 2L, listOf(stats(cols.getValue("id"), "1", "2"))),
                DucklakeWriteFragment("w/b.parquet", 100L, 0L, 2L, listOf(stats(cols.getValue("id"), "3", "4"))),
            ),
        )
        var snap = c.currentSnapshotId
        assertThat(c.getDataFiles(tableId, snap)).extracting<String> { it.path }.containsExactly("w/a.parquet", "w/b.parquet")
        assertThat(c.getLiveRowCount(tableId, snap)).isEqualTo(4L)
        assertThat(c.getColumnStats(tableId, snap).single { it.columnId == cols.getValue("id") }.maxValue).isEqualTo("4")

        // Delete against file a: UPDATE ducklake_table_stats + INSERT delete file.
        val fileA = c.getDataFiles(tableId, snap).single { it.path == "w/a.parquet" }
        c.commitDelete(tableId, listOf(DucklakeDeleteFragment(fileA.dataFileId, "w/a-del.parquet", 1L, 50L, 0L, 1L)))
        snap = c.currentSnapshotId
        assertThat(c.getLiveRowCount(tableId, snap)).isEqualTo(3L)
        assertThat(c.getDataFiles(tableId, snap).single { it.path == "w/a.parquet" }.deleteFilePath).isEqualTo("w/a-del.parquet")

        // Column DDL: UPDATE end_snapshot paths (drop / rename / retype) + INSERT (add).
        c.addColumn(tableId, TableColumnSpec.leaf("x", "int64", true))
        c.renameColumn(tableId, cols.getValue("v"), "w")
        c.setColumnType(tableId, cols.getValue("id"), "int64")
        c.setTableComment(tableId, "hello")
        snap = c.currentSnapshotId
        val after = c.getTableColumns(tableId, snap)
        assertThat(after.map { it.columnName }).containsExactly("id", "w", "x")
        assertThat(after.single { it.columnName == "id" }.columnType).isEqualTo("int64")
        assertThat(c.getTableComment(tableId, snap)).isEqualTo("hello")
        c.dropColumn(tableId, after.single { it.columnName == "x" }.columnId)
        assertThat(c.getTableColumns(tableId, c.currentSnapshotId).map { it.columnName }).containsExactly("id", "w")

        // Rewrite (compaction): sources end-snapshotted, stats netted.
        val live = c.getDataFiles(tableId, c.currentSnapshotId).map { it.dataFileId }
        c.rewriteDataFiles(
            tableId, live.toSet(),
            listOf(DucklakeWriteFragment("w/c.parquet", 100L, 0L, 3L, listOf(stats(cols.getValue("id"), "2", "4")))),
            c.currentSnapshotId,
        )
        snap = c.currentSnapshotId
        assertThat(c.getDataFiles(tableId, snap)).extracting<String> { it.path }.containsExactly("w/c.parquet")
        assertThat(c.getLiveRowCount(tableId, snap)).isEqualTo(3L)

        // Drop table, drop schema.
        c.dropTable("w", "t")
        assertThat(c.getTable("w", "t", c.currentSnapshotId)).isNull()
        assertThat(c.getTable("w", "t", snap)).`as`("time travel").isNotNull()
        c.dropSchema("w")
        assertThat(c.getSchema("w", c.currentSnapshotId)).isNull()
    }

    @Test
    fun viewsAnalyzeExpireAndReadSessionWorkOnQuack() {
        c.createSchema("m")
        val (tableId, cols) = createTable("m", "t")
        c.commitInsert(tableId, listOf(DucklakeWriteFragment("m/a.parquet", 100L, 0L, 2L, listOf(stats(cols.getValue("id"), "1", "2")))))
        val s1 = c.currentSnapshotId
        c.createView("m", "v", "SELECT id FROM t", "duckdb", emptyList(), emptyMap())
        val schemaId = c.getSchema("m", c.currentSnapshotId)!!.schemaId
        assertThat(c.listViews(schemaId, c.currentSnapshotId).map { it.viewName }).containsExactly("v")
        c.analyzeTable(tableId)
        assertThat(c.getColumnStats(tableId, c.currentSnapshotId).single { it.columnId == cols.getValue("id") }.minValue).isEqualTo("1")

        c.commitInsert(tableId, listOf(DucklakeWriteFragment("m/b.parquet", 100L, 0L, 2L, listOf(stats(cols.getValue("id"), "3", "4")))))
        val (n, snapshotSeen) = c.readSession(
            java.util.function.Supplier {
                val snap = c.currentSnapshotId
                c.getDataFiles(tableId, snap).size to snap
            },
        )
        assertThat(n).isEqualTo(2)
        assertThat(snapshotSeen).isEqualTo(c.currentSnapshotId)

        assertThat(c.listExpirableSnapshots(java.time.Instant.now().plusSeconds(60), null)).contains(s1)
        val result = c.expireSnapshots(setOf(s1))
        assertThat(result.expiredSnapshotCount).isEqualTo(1)
        assertThat(c.getSnapshot(s1)).isNull()
        assertThat(c.listSnapshotChanges().map { it.snapshotId }).doesNotContain(s1)
    }

    @Test
    fun anAbortedCommitRollsBackWrappedStatementsTogetherWithDirectOnes() {
        c.createSchema("rb")
        val (tableId, cols) = createTable("rb", "t")
        c.commitInsert(tableId, listOf(DucklakeWriteFragment("rb/a.parquet", 100L, 0L, 2L, listOf(stats(cols.getValue("id"), "1", "2")))))
        val before = c.currentSnapshotId

        // dropColumn = UPDATE ducklake_column SET end_snapshot (wrapped on Quack); abort right before the snapshot INSERT.
        c.beforeSnapshotInsertAction = Runnable { throw IllegalStateException("simulated crash before snapshot insert") }
        try {
            assertThatThrownBy { c.dropColumn(tableId, cols.getValue("v")) }.hasRootCauseMessage("simulated crash before snapshot insert")
        }
        finally {
            c.beforeSnapshotInsertAction = Runnable {}
        }
        assertThat(c.currentSnapshotId).isEqualTo(before)
        assertThat(c.getTableColumns(tableId, before).map { it.columnName })
            .`as`("the UPDATE end_snapshot must have rolled back with the local transaction")
            .containsExactly("id", "v")
        assertThat(c.getTableColumns(tableId, before + 1).map { it.columnName })
            .`as`("no column row may point at a snapshot that never landed")
            .containsExactly("id", "v")

        // Same for a delete (UPDATE ducklake_table_stats + INSERT ducklake_delete_file).
        val fileA = c.getDataFiles(tableId, before).single()
        c.beforeSnapshotInsertAction = Runnable { throw IllegalStateException("simulated crash before snapshot insert") }
        try {
            assertThatThrownBy {
                c.commitDelete(tableId, listOf(DucklakeDeleteFragment(fileA.dataFileId, "rb/a-del.parquet", 1L, 50L, 0L, 1L)))
            }.hasRootCauseMessage("simulated crash before snapshot insert")
        }
        finally {
            c.beforeSnapshotInsertAction = Runnable {}
        }
        assertThat(c.currentSnapshotId).isEqualTo(before)
        assertThat(c.getLiveRowCount(tableId, before)).isEqualTo(2L)
        assertThat(c.getDataFiles(tableId, before + 1).single().deleteFilePath).isNull()
    }

    @Test
    fun concurrentWritersRetryOnQuackToo() {
        c.createSchema("cc")
        val (tableId, cols) = createTable("cc", "t")
        val base = c.currentSnapshotId
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(
            c,
            Runnable { c.createSchema("cc_winner") },
            Runnable {
                c.commitInsert(tableId, listOf(DucklakeWriteFragment("cc/l.parquet", 100L, 0L, 2L, listOf(stats(cols.getValue("id"), "1", "2")))))
            },
            parkPoint = ConcurrentWriterHarness.ParkPoint.BEFORE_SNAPSHOT_INSERT,
        )
        assertThat(result.loserException).isNull()
        assertThat(result.loserAttemptCount).isEqualTo(2)
        assertThat(c.currentSnapshotId).isEqualTo(base + 2)
        assertThat(c.getDataFiles(tableId, base + 2)).hasSize(1)
    }
}
