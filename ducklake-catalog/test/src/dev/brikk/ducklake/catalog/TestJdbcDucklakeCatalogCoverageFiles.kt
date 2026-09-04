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
 * Behaviour tests for the data-file-side [DucklakeCatalog] methods that had no coverage
 * (TODO-rectify-from-eval.md Q-5): [DucklakeCatalog.getDataFilesAddedBetween], [DucklakeCatalog.getDataFilesByIds],
 * [DucklakeCatalog.listReferencedFilePaths], [DucklakeCatalog.getLatestDataFileFormat],
 * [DucklakeCatalog.getTableDataFileFormat], [DucklakeCatalog.getDataPath] and
 * [DucklakeCatalog.getPartitionNameMaps].
 *
 * The file-history tests build one table through every lifecycle shape the catalog knows (plain insert,
 * delete file, end-snapshotting rewrite, partial rewrite that DELETES its sources and schedules them) so the
 * window / liveness rules of each read can be pinned against the raw PostgreSQL rows. The hive-partition
 * name-map test uses stock DuckDB's `ducklake_add_data_files(hive_partitioning => true)` as the writer of
 * the `is_partition` rows, and DuckDB as the reader of the library-written ones.
 */
class TestJdbcDucklakeCatalogCoverageFiles {
    companion object {
        private const val SCHEMA = "test_schema"

        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        /** Built once per class (JUnit instantiates the class per test method). */
        private var refHistory: RefHistory? = null

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "coverage-files")
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

    private fun duckExec(vararg sql: String) = withDuckDb { c -> c.createStatement().use { st -> sql.forEach { st.execute(it) } } }

    private fun pg(sql: String): List<List<String?>> =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { it.rows(sql) }

    private fun pgExec(sql: String) {
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun pgColumn(sql: String): List<String?> = pg(sql).map { it[0] }

    private fun scratch(sql: String) {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { st -> st.execute(sql) } }
    }

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    private fun table(name: String, schema: String = SCHEMA): DucklakeTable =
        catalog.getTable(schema, name, catalog.currentSnapshotId)!!

    private fun columnIds(tableId: Long): Map<String, Long> =
        catalog.getTableColumns(tableId, catalog.currentSnapshotId).associate { it.columnName to it.columnId }

    private fun tableDir(t: DucklakeTable): Path {
        val schema = catalog.listSchemas(catalog.currentSnapshotId).single { it.schemaId == t.schemaId }
        return Files.createDirectories(isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(t.path))
    }

    private fun stats(columnId: Long, rows: Long, min: String, max: String) =
        DucklakeFileColumnStats(columnId, 8L * rows, rows, 0L, min, max, false)

    /** A relative-path parquet fragment of [rows] rows for a single `id` column (metadata only — never scanned). */
    private fun fragment(name: String, idCol: Long, rows: Long, format: String = "parquet") =
        DucklakeWriteFragment(name, format, 100L * rows, 0L, rows, listOf(stats(idCol, rows, "1", "9")), emptyMap(), null)

    private fun fileByPath(files: List<DucklakeDataFile>, path: String): DucklakeDataFile = files.single { it.path == path }

    // ---------------------------------------------------------------- file history fixture

    /** Snapshots of the `ref` table's history; see [buildReferenceTable]. */
    private data class RefHistory(
        val tableId: Long,
        val s1: Long,
        val s2: Long,
        val s3: Long,
        val s4: Long,
        val s5: Long,
        val s6: Long,
        val idsByPath: Map<String, Long>,
    )

    /**
     * `ref`: s1 inserts A; s2 inserts B, C; s3 inserts D; s4 attaches a delete file to A; s5 partial-rewrites
     * {C, D} into P (begin s2, partial_max s3 — sources DELETED from the catalog and scheduled); s6 rewrites
     * {B} into M (B end-snapshotted at s6).
     */
    private fun buildReferenceTable(): RefHistory {
        catalog.createTable(SCHEMA, "ref", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val t = table("ref")
        val idCol = columnIds(t.tableId).getValue("id")
        catalog.commitInsert(t.tableId, listOf(fragment("a.parquet", idCol, 3)))
        val s1 = catalog.currentSnapshotId
        catalog.commitInsert(t.tableId, listOf(fragment("b.parquet", idCol, 2), fragment("c.parquet", idCol, 2)))
        val s2 = catalog.currentSnapshotId
        catalog.commitInsert(t.tableId, listOf(fragment("d.parquet", idCol, 1)))
        val s3 = catalog.currentSnapshotId
        val ids = catalog.getDataFiles(t.tableId, s3).associate { it.path to it.dataFileId }
        catalog.commitDelete(t.tableId, listOf(DucklakeDeleteFragment(ids.getValue("a.parquet"), "a-del.parquet", 1, 50, 0, 1)), s3)
        val s4 = catalog.currentSnapshotId
        catalog.rewriteDataFilesPartial(
            t.tableId,
            setOf(ids.getValue("c.parquet"), ids.getValue("d.parquet")),
            listOf(PartialMergedFile(fragment("p.parquet", idCol, 3), s2, s3)),
            s4,
        )
        val s5 = catalog.currentSnapshotId
        catalog.rewriteDataFiles(t.tableId, setOf(ids.getValue("b.parquet")), listOf(fragment("m.parquet", idCol, 2)), s5)
        val s6 = catalog.currentSnapshotId
        assertThat(listOf(s1, s2, s3, s4, s5, s6)).`as`("one snapshot per commit").isEqualTo((s1..s6).toList())
        val allIds = ids + catalog.getDataFiles(t.tableId, s6).associate { it.path to it.dataFileId }
        return RefHistory(t.tableId, s1, s2, s3, s4, s5, s6, allIds)
    }

    private fun ref(): RefHistory = synchronized(Companion) { refHistory ?: buildReferenceTable().also { refHistory = it } }

    @Test
    fun dataFilesAddedBetweenFollowsOriginSnapshotsRegardlessOfLiveness() {
        val h = ref()
        fun paths(start: Long, end: Long) = catalog.getDataFilesAddedBetween(h.tableId, start, end).map { it.path }

        assertThat(paths(h.s1, h.s1)).containsExactly("a.parquet")
        assertThat(paths(h.s2, h.s2))
            .`as`("B is end-snapshotted (s6) and P is a partial file beginning at s2: both were inserted in-window")
            .containsExactlyInAnyOrder("b.parquet", "p.parquet")
        assertThat(paths(h.s3, h.s3))
            .`as`("P begins BEFORE the window but partial_max (s3) reaches into it; D itself is gone from the catalog")
            .containsExactly("p.parquet")
        assertThat(paths(h.s4, h.s4)).`as`("a delete file is not an insertion").isEmpty()
        assertThat(paths(h.s5, h.s5)).`as`("the partial rewrite itself is back-dated, nothing begins at s5").isEmpty()
        assertThat(paths(h.s6, h.s6)).containsExactly("m.parquet")
        val all = catalog.getDataFilesAddedBetween(h.tableId, h.s1, h.s6)
        assertThat(all.map { it.path }).containsExactlyInAnyOrder("a.parquet", "b.parquet", "p.parquet", "m.parquet")
        assertThat(all.map { it.beginSnapshot }).`as`("ordered by begin_snapshot (B and P tie at s2)").isSorted()
        assertThat(all.first().path).isEqualTo("a.parquet")
        assertThat(all.last().path).isEqualTo("m.parquet")
        assertThat(paths(h.s6 + 1, h.s6 + 10)).isEmpty()

        val a = catalog.getDataFilesAddedBetween(h.tableId, h.s1, h.s1).single()
        assertThat(catalog.getDataFiles(h.tableId, h.s6).single { it.path == "a.parquet" }.deleteFilePath).isEqualTo("a-del.parquet")
        assertThat(a.deleteFilePath).`as`("insert side: delete-file columns are left null even though A has one").isNull()
        assertThat(a.deleteFileFormat).isNull()
        assertThat(a.beginSnapshot).isEqualTo(h.s1)
        assertThat(a.recordCount).isEqualTo(3L)
        val b = fileByPath(catalog.getDataFilesAddedBetween(h.tableId, h.s2, h.s2), "b.parquet")
        assertThat(b.endSnapshot).`as`("liveness is reported, not filtered").isEqualTo(h.s6)
        val p = fileByPath(catalog.getDataFilesAddedBetween(h.tableId, h.s2, h.s2), "p.parquet")
        assertThat(p.beginSnapshot).isEqualTo(h.s2)
        assertThat(p.partialMax).isEqualTo(h.s3)
    }

    @Test
    fun dataFilesByIdsIgnoresLivenessButNotTableOrCatalogPresence() {
        val h = ref()
        val requested = listOf("a.parquet", "b.parquet", "c.parquet", "m.parquet").map { h.idsByPath.getValue(it) }
        val files = catalog.getDataFilesByIds(h.tableId, requested + 999_999L)
        assertThat(files.map { it.path }).`as`("C was deleted from the catalog by the partial rewrite; unknown id skipped")
            .containsExactlyInAnyOrder("a.parquet", "b.parquet", "m.parquet")
        assertThat(fileByPath(files, "b.parquet").endSnapshot).`as`("retired file still returned").isEqualTo(h.s6)
        assertThat(fileByPath(files, "a.parquet").deleteFilePath).`as`("delete-file columns left null").isNull()
        assertThat(fileByPath(files, "a.parquet").rowIdStart).isEqualTo(0L)
        assertThat(fileByPath(files, "m.parquet").rowIdStart).`as`("rewrite keeps the retired source's row_id_start").isEqualTo(3L)
        assertThat(catalog.getDataFilesByIds(h.tableId, emptyList())).isEmpty()
        assertThat(catalog.getDataFilesByIds(h.tableId + 1_000_000, requested)).`as`("scoped to the table").isEmpty()
    }

    @Test
    fun dataFileOrderingUsesIdWhenUpstreamLeavesFileOrderNull() {
        catalog.createSchema("file_order")
        catalog.createTable(
            "file_order", "t",
            listOf(TableColumnSpec.leaf("id", "int32", true)),
            null, null,
        )
        val table = table("t", "file_order")
        val id = columnIds(table.tableId).getValue("id")
        catalog.commitInsert(
            table.tableId,
            listOf(fragment("first.parquet", id, 1), fragment("second.parquet", id, 1)),
        )
        pgExec("UPDATE ducklake_data_file SET file_order = NULL WHERE table_id = ${table.tableId}")
        val files = catalog.getDataFiles(table.tableId, catalog.currentSnapshotId)
        assertThat(files.map { it.dataFileId }).isSorted()
        assertThat(files.map { it.path }).containsExactly("first.parquet", "second.parquet")
    }

    @Test
    fun nullRowIdStartFailsLoudlyInsteadOfAliasingToZero() {
        catalog.createTable(
            SCHEMA, "null_row_id_start",
            listOf(TableColumnSpec.leaf("id", "int32", true)),
            null, null,
        )
        val table = table("null_row_id_start")
        val id = columnIds(table.tableId).getValue("id")
        catalog.commitInsert(table.tableId, listOf(fragment("null-row-id.parquet", id, 1)))
        pgExec("UPDATE ducklake_data_file SET row_id_start = NULL WHERE table_id = ${table.tableId}")

        assertThatThrownBy { catalog.getDataFiles(table.tableId, catalog.currentSnapshotId) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
            .hasMessageContaining("row_id_start")
            .hasMessageContaining("data_file_id")
    }

    @Test
    fun listReferencedFilePathsIsTheUnionOfEveryCatalogRowForTheTable() {
        val h = ref()
        val refs = catalog.listReferencedFilePaths(h.tableId)
        val expectedData = pg("SELECT path, path_is_relative::text FROM ducklake_data_file WHERE table_id = ${h.tableId}")
        val expectedDelete = pg("SELECT path, path_is_relative::text FROM ducklake_delete_file WHERE table_id = ${h.tableId}")
        val expectedScheduled = pg("SELECT path, path_is_relative::text FROM ducklake_files_scheduled_for_deletion")
        assertThat(refs.map { listOf<String?>(it.path, it.pathIsRelative.toString()) })
            .`as`("data + delete rows of the table (any snapshot) + every scheduled file, raw paths")
            .containsExactlyInAnyOrderElementsOf(expectedData + expectedDelete + expectedScheduled)

        val names = refs.map { it.path.substringAfterLast('/') }
        assertThat(names).contains("a.parquet", "a-del.parquet", "p.parquet", "m.parquet")
        assertThat(names).`as`("B is end-snapshotted, still owned").contains("b.parquet")
        assertThat(names).`as`("C and D were deleted from ducklake_data_file but are scheduled for deletion").contains("c.parquet", "d.parquet")
        assertThat(refs.filter { it.path.endsWith("c.parquet") || it.path.endsWith("d.parquet") })
            .`as`("scheduled rows are absolute, under the table's data dir")
            .allSatisfy { assertThat(it.pathIsRelative).isFalse(); assertThat(it.path).startsWith(isolated.dataDir.toAbsolutePath().toString()) }
        assertThat(refs.filter { it.path == "a.parquet" }).allSatisfy { assertThat(it.pathIsRelative).isTrue() }
        assertThat(catalog.listReferencedFilePaths(h.tableId + 1_000_000).map { it.path })
            .`as`("another table sees only the schedule (which has no table_id)")
            .containsExactlyInAnyOrderElementsOf(expectedScheduled.map { it[0] })
    }

    @Test
    fun allReferencedFilesKeepsTableAndCatalogRootNamespacesSeparate() {
        val h = ref()
        val table = table("ref")
        val all = catalog.listAllReferencedFiles()
        val tableRefs = all.tableFiles.filter { it.tableId == h.tableId }
        val expectedData = pg("SELECT path FROM ducklake_data_file WHERE table_id = ${h.tableId}").flatten()
        val expectedDeletes = pg("SELECT path FROM ducklake_delete_file WHERE table_id = ${h.tableId}").flatten()
        val expectedScheduled = pg("SELECT path FROM ducklake_files_scheduled_for_deletion").flatten()

        assertThat(tableRefs.map { it.path })
            .`as`("all data/delete rows remain table-relative, including retired rows")
            .containsExactlyInAnyOrderElementsOf(expectedData + expectedDeletes)
        assertThat(tableRefs.map { it.path }).doesNotContainAnyElementsOf(expectedScheduled)
        assertThat(tableRefs).allSatisfy {
            val schema = catalog.getSchema(SCHEMA, catalog.currentSnapshotId)!!
            assertThat(it.schemaId).isEqualTo(schema.schemaId)
            assertThat(it.schemaPath).isEqualTo(schema.path)
            assertThat(it.schemaPathIsRelative).isEqualTo(schema.pathIsRelative)
            assertThat(it.tablePath).isEqualTo(table.path)
            assertThat(it.tablePathIsRelative).isEqualTo(table.pathIsRelative)
        }
        assertThat(all.scheduledFiles.map { it.path })
            .`as`("scheduled paths are returned separately for resolution against global data_path")
            .containsExactlyInAnyOrderElementsOf(expectedScheduled)
    }

    @Test
    fun allReferencedFilesIncludesDroppedButUnexpiredTables() {
        catalog.createSchema("known_dropped")
        catalog.createTable(
            "known_dropped", "t",
            listOf(TableColumnSpec.leaf("id", "int32", true)),
            null, null,
        )
        val table = table("t", "known_dropped")
        val id = columnIds(table.tableId).getValue("id")
        catalog.commitInsert(table.tableId, listOf(fragment("owned.parquet", id, 1)))
        val beforeDrop = catalog.currentSnapshotId
        catalog.dropTable("known_dropped", "t")
        val schema = catalog.getSchema("known_dropped", catalog.currentSnapshotId)!!
        catalog.dropSchema("known_dropped")

        assertThat(catalog.getTable("known_dropped", "t", catalog.currentSnapshotId)).isNull()
        assertThat(catalog.getSchema("known_dropped", catalog.currentSnapshotId)).isNull()
        assertThat(catalog.getTable("known_dropped", "t", beforeDrop)).isNotNull()
        assertThat(catalog.listAllReferencedFiles().tableFiles)
            .`as`("time-travel files remain known after DROP until snapshot expiry schedules them")
            .anySatisfy {
                assertThat(it.tableId).isEqualTo(table.tableId)
                assertThat(it.path).isEqualTo("owned.parquet")
                assertThat(it.tablePath).isEqualTo(table.path)
                assertThat(it.schemaId).isEqualTo(schema.schemaId)
                assertThat(it.schemaPath).isEqualTo(schema.path)
                assertThat(it.schemaPathIsRelative).isEqualTo(schema.pathIsRelative)
            }
    }

    // ---------------------------------------------------------------- formats / data path

    @Test
    fun latestDataFileFormatIsTheMostRecentlyRegisteredActiveFile() {
        catalog.createSchema("fmt_schema")
        catalog.createTable("fmt_schema", "latest", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val t = table("latest", "fmt_schema")
        val idCol = columnIds(t.tableId).getValue("id")
        val empty = catalog.currentSnapshotId
        assertThat(catalog.getLatestDataFileFormat(t.tableId, empty)).`as`("no files yet").isNull()

        catalog.commitInsert(t.tableId, listOf(fragment("f1.parquet", idCol, 2)))
        val s1 = catalog.currentSnapshotId
        catalog.commitInsert(t.tableId, listOf(fragment("f2.orc", idCol, 2, format = "orc")))
        val s2 = catalog.currentSnapshotId
        assertThat(catalog.getLatestDataFileFormat(t.tableId, s1)).isEqualTo("parquet")
        assertThat(catalog.getLatestDataFileFormat(t.tableId, s2)).`as`("newest file wins").isEqualTo("orc")

        // Retire the orc file: the parquet one is the latest ACTIVE file again at the new snapshot.
        val orc = catalog.getDataFiles(t.tableId, s2).single { it.path == "f2.orc" }
        catalog.rewriteDataFiles(t.tableId, setOf(orc.dataFileId), listOf(fragment("f3.parquet", idCol, 2)), s2)
        val s3 = catalog.currentSnapshotId
        assertThat(catalog.getLatestDataFileFormat(t.tableId, s3)).isEqualTo("parquet")
        assertThat(catalog.getLatestDataFileFormat(t.tableId, s2)).`as`("time travel").isEqualTo("orc")
        assertThat(catalog.getLatestDataFileFormat(t.tableId, empty)).isNull()
    }

    @Test
    fun tableDataFileFormatIsTheDeclaredTableScopedSetting() {
        catalog.createTable(SCHEMA, "fmt_declared", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null, "parquet")
        catalog.createTable(SCHEMA, "fmt_undeclared", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val declared = table("fmt_declared")
        val undeclared = table("fmt_undeclared")
        assertThat(catalog.getTableDataFileFormat(declared.tableId)).isEqualTo("parquet")
        assertThat(catalog.getTableDataFileFormat(undeclared.tableId)).`as`("never declared → inherit from data").isNull()
        assertThat(pg("SELECT value, scope, scope_id FROM ducklake_metadata WHERE key = 'data_file_format'"))
            .containsExactly(listOf("parquet", "table", declared.tableId.toString()))
        assertThat(catalog.getLatestDataFileFormat(declared.tableId, catalog.currentSnapshotId)).`as`("orthogonal to file inheritance").isNull()
        // The extra table-scoped setting must not break DuckDB's catalog load — it surfaces as a table option.
        assertThat(duck("DESCRIBE lake.$SCHEMA.fmt_declared").map { it[0] }).containsExactly("id")
        assertThat(duck("SELECT value, scope, scope_entry FROM ducklake_options('lake') WHERE option_name = 'data_file_format'"))
            .containsExactly(listOf("parquet", "TABLE", "$SCHEMA.fmt_declared"))
        assertThat(duck("SELECT count(*) FROM lake.$SCHEMA.fmt_declared").single().single()).isEqualTo("0")
    }

    @Test
    fun dataPathIsTheCatalogRootDuckDbWasAttachedWith() {
        val dataPath = catalog.getDataPath()
        assertThat(dataPath).isNotNull()
        assertThat(dataPath).isEqualTo(pgColumn("SELECT value FROM ducklake_metadata WHERE key = 'data_path'").single())
        assertThat(Path.of(dataPath!!).toAbsolutePath().normalize()).isEqualTo(isolated.dataDir.toAbsolutePath().normalize())
        assertThat(duck("SELECT value FROM ducklake_options('lake') WHERE option_name = 'data_path'").single().single())
            .`as`("DuckDB reports the same root")
            .isEqualTo(dataPath)
    }

    // ---------------------------------------------------------------- hive-partition name maps

    private fun writeHiveFile(dir: Path, region: String, selectList: String): Path {
        val target = Files.createDirectories(dir.resolve("region=$region")).resolve("part.parquet")
        scratch("COPY (SELECT $selectList) TO ${q(target.toString())} (FORMAT PARQUET)")
        return target
    }

    @Test
    fun partitionNameMapsAreTheHiveEntriesAndOnlyThose() {
        duckExec("CREATE TABLE lake.$SCHEMA.hv (id INTEGER, name VARCHAR, region VARCHAR)")
        val t = table("hv")
        val ids = columnIds(t.tableId)
        val dir = tableDir(t)

        // DuckDB writes the hive name map: `region` has no parquet column, its value is in the path.
        val us = writeHiveFile(dir, "US", "1::INTEGER AS id, 'a' AS name UNION ALL SELECT 2, 'b'")
        duckExec("CALL ducklake_add_data_files('lake', 'hv', ${q(us.toString())}, schema => '$SCHEMA', hive_partitioning => true)")
        val duckFile = catalog.getDataFiles(t.tableId, catalog.currentSnapshotId).single()
        val duckMapping = duckFile.mappingId!!
        assertThat(catalog.getPartitionNameMaps(setOf(duckMapping)))
            .`as`("target_field_id → path key for the is_partition entries only")
            .containsExactly(java.util.Map.entry(duckMapping, mapOf(ids.getValue("region") to "region")))
        assertThat(catalog.getNameMaps(setOf(duckMapping)))
            .`as`("the regular map excludes the hive entry")
            .containsExactly(java.util.Map.entry(duckMapping, mapOf(ids.getValue("id") to "id", ids.getValue("name") to "name")))
        assertThat(pgColumn("SELECT source_name FROM ducklake_name_mapping WHERE mapping_id = $duckMapping AND is_partition"))
            .containsExactly("region")
        assertThat(catalog.getPartitionNameMaps(emptySet())).isEmpty()
        assertThat(catalog.getPartitionNameMaps(setOf(duckMapping + 1_000_000))).isEmpty()

        // The library writes the same shape for an EU file; DuckDB must read `region` from the path.
        val eu = writeHiveFile(dir, "EU", "3::INTEGER AS id, 'c' AS name UNION ALL SELECT 4, 'd'")
        val nameMap = DucklakeNameMap(
            listOf(
                DucklakeNameMapEntry("id", ids.getValue("id")),
                DucklakeNameMapEntry("name", ids.getValue("name")),
                DucklakeNameMapEntry("region", ids.getValue("region"), true, emptyList()),
            ),
        )
        val fragment = DucklakeWriteFragment(
            eu.toString(), false, "parquet", Files.size(eu), 0L, 2L,
            listOf(stats(ids.getValue("id"), 2, "3", "4"), stats(ids.getValue("name"), 2, "c", "d"), stats(ids.getValue("region"), 2, "EU", "EU")),
            emptyMap(), null, nameMap,
        )
        catalog.commitAddFiles(t.tableId, listOf(fragment))
        val ours = catalog.getDataFiles(t.tableId, catalog.currentSnapshotId).single { it.path == eu.toString() }.mappingId!!
        assertThat(ours).isNotEqualTo(duckMapping)
        assertThat(catalog.getPartitionNameMaps(setOf(duckMapping, ours)))
            .containsOnlyKeys(duckMapping, ours)
            .containsEntry(ours, mapOf(ids.getValue("region") to "region"))
        assertThat(catalog.getNameMaps(setOf(ours)).getValue(ours)).doesNotContainKey(ids.getValue("region"))

        assertThat(duck("SELECT region, count(*), min(id), max(id) FROM lake.$SCHEMA.hv GROUP BY region ORDER BY region"))
            .`as`("DuckDB constant-fills region from the path for both the DuckDB- and the library-written file")
            .containsExactly(listOf("EU", "2", "3", "4"), listOf("US", "2", "1", "2"))
    }
}
