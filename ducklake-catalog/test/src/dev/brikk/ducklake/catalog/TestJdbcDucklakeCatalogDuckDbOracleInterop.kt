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
 * End-to-end oracle test (TODO-rectify-from-eval.md Q-5): THIS LIBRARY performs every catalog write
 * into a PostgreSQL-backed DuckLake, and STOCK DuckDB (`ATTACH 'ducklake:postgres:…'`) is the oracle
 * that reads everything back — schema and table DDL with nested columns, a view, column evolution and
 * comments, real Parquet data (written with DuckDB `COPY … (FIELD_IDS …)`, registered via
 * `commitInsert`), time travel, a positional delete file, `add_files` through a name map, snapshot
 * change metadata and global column stats. Every DuckDB read uses a fresh connection so a poisoned
 * session or a stale per-schema-version catalog cache cannot mask a catalog-row bug.
 */
class TestJdbcDucklakeCatalogDuckDbOracleInterop {
    companion object {
        private const val SCHEMA = "oracle"
        private const val UUID1 = "1aaaaaaa-0000-0000-0000-000000000001"
        private const val UUID3 = "3aaaaaaa-0000-0000-0000-000000000003"
        private const val UUID4 = "4aaaaaaa-0000-0000-0000-000000000004"
        private const val UUID5 = "5aaaaaaa-0000-0000-0000-000000000005"
        private const val TS1 = "2024-01-02 03:04:05+00"
        private const val TS3 = "2025-01-01 00:00:00+00"
        private const val TS4 = "2025-03-04 05:06:07+00"
        private const val TS5 = "2025-12-31 23:59:59+00"

        /** Rows 1..3 of the nested table, as DuckDB SQL (row 3 has a NULL struct). */
        private const val NESTED_ROWS_1 = "SELECT * FROM (VALUES " +
            "(1::INTEGER, {a: 10::INTEGER, l: [1::BIGINT, 2], m: MAP {'k1': 'v1'}}, 1.500::DECIMAL(18,3), " +
            "TIMESTAMPTZ '$TS1', '$UUID1'::UUID), " +
            "(2, {a: 20, l: [3::BIGINT], m: MAP {'k2': 'v2', 'k3': 'v3'}}, 22.250, " +
            "TIMESTAMPTZ '2024-06-07 08:09:10+00', '2aaaaaaa-0000-0000-0000-000000000002'::UUID), " +
            "(3, NULL, 333.125, TIMESTAMPTZ '$TS3', '$UUID3'::UUID)) t(id, s, d, ts, u)"

        /** Rows 4..5 (empty list / empty map, and a longer list). */
        private const val NESTED_ROWS_2 = "SELECT * FROM (VALUES " +
            "(4::INTEGER, {a: 40::INTEGER, l: []::BIGINT[], m: MAP {}::MAP(VARCHAR, VARCHAR)}, " +
            "4.000::DECIMAL(18,3), TIMESTAMPTZ '$TS4', '$UUID4'::UUID), " +
            "(5, {a: 50, l: [5::BIGINT, 6, 7], m: MAP {'k5': 'v5'}}, 5555.555, TIMESTAMPTZ '$TS5', '$UUID5'::UUID)" +
            ") t(id, s, d, ts, u)"

        private const val NESTED_PROJECTION = "id, s::VARCHAR, d::VARCHAR, ts::VARCHAR, u::VARCHAR"

        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog
        private var schemaSnapshot: Long = -1

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "duckdb-oracle-interop")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            }
            catalog = JdbcDucklakeCatalog(config)
            catalog.createSchema(SCHEMA)
            schemaSnapshot = catalog.currentSnapshotId
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

    // ---------------------------------------------------------------- oracle plumbing

    /** A fresh stock-DuckDB session with the lake attached as `lake` (UTC so TIMESTAMPTZ text is stable). */
    private fun <T> withDuckDb(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { st ->
                st.execute("INSTALL ducklake")
                st.execute("LOAD ducklake")
                st.execute("INSTALL postgres")
                st.execute("LOAD postgres")
                st.execute("SET TimeZone = 'UTC'")
                val dataPath = q(isolated.dataDir.toAbsolutePath().toString())
                st.execute("ATTACH ${q(isolated.duckDbAttachUri)} AS lake (DATA_PATH $dataPath)")
            }
            block(connection)
        }

    /** Runs [sql]; every row of its result set (none for a statement such as `COPY`), each cell as text. */
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

    /** All rows of [sql] in DuckDB, every cell as its DuckDB text rendering. */
    private fun duck(sql: String): List<List<String?>> = withDuckDb { it.rows(sql) }

    /** First column of every row of [sql] in DuckDB. */
    private fun duckColumn(sql: String): List<String?> = duck(sql).map { it[0] }

    private fun duckLong(sql: String): Long = duck(sql).single().single()!!.toLong()

    /** All rows of [sql] against the PostgreSQL catalog database, as text. */
    private fun pg(sql: String): List<List<String?>> =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { it.rows(sql) }

    /** Runs [sql] in a scratch, catalog-less DuckDB (used to write Parquet files and to render expectations). */
    private fun scratch(sql: String): List<List<String?>> =
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { st -> st.execute("SET TimeZone = 'UTC'") }
            c.rows(sql)
        }

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    private fun tableDir(tableName: String): Path {
        val snapshot = catalog.currentSnapshotId
        val schema = catalog.getSchema(SCHEMA, snapshot)!!
        val table = catalog.getTable(SCHEMA, tableName, snapshot)!!
        return Files.createDirectories(isolated.dataDir.toAbsolutePath().resolve(schema.path).resolve(table.path))
    }

    private fun tableId(tableName: String): Long =
        catalog.getTable(SCHEMA, tableName, catalog.currentSnapshotId)!!.tableId

    private fun schemaId(): Long = catalog.getSchema(SCHEMA, catalog.currentSnapshotId)!!.schemaId

    /** Every active column of the table keyed by dotted path (`s`, `s.l`, `s.l.element`, `s.m.key`, …). */
    private fun columnIds(tableId: Long): Map<String, Long> {
        val all = catalog.getAllColumnsWithParentage(tableId, catalog.currentSnapshotId)
        val byId = all.associateBy { it.columnId }
        fun path(c: DucklakeColumn): String =
            c.parentColumn?.let { path(byId.getValue(it)) + "." }.orEmpty() + c.columnName
        return all.associate { path(it) to it.columnId }
    }

    /** `ducklake_snapshots('lake').changes` for one snapshot, as DuckDB's MAP text. */
    private fun changesOf(snapshotId: Long): String =
        duck("SELECT changes::VARCHAR FROM ducklake_snapshots('lake') WHERE snapshot_id = $snapshotId")
            .single().single()!!

    /** `(file_count, delete_file_count)` of `ducklake_table_info('lake')` for one table of the test schema. */
    private fun tableInfoCounts(tableName: String): List<String?> =
        duck(
            "SELECT file_count, delete_file_count FROM ducklake_table_info('lake') " +
                "WHERE schema_id = ${schemaId()} AND table_name = '$tableName'",
        ).single()

    /** `DESCRIBE lake.oracle.<table>` as column name → DuckDB type text, in column order. */
    private fun describe(tableName: String): Map<String, String> =
        duck("DESCRIBE lake.$SCHEMA.$tableName").associate { it[0]!! to it[1]!! }

    private fun stats(columnId: Long, size: Long, values: Long?, nulls: Long?, min: String?, max: String?) =
        DucklakeFileColumnStats(columnId, size, values, nulls, min, max, null)

    /** Global `ducklake_table_column_stats` `(min_value, max_value)` text for one column, read from PostgreSQL. */
    private fun globalBounds(tableId: Long, columnId: Long): List<String?> =
        pg(
            "SELECT min_value, max_value FROM ducklake_table_column_stats " +
                "WHERE table_id = $tableId AND column_id = $columnId",
        ).single()

    // ------------------------------------------------ nested table: DDL, data, time travel, delete, stats

    private fun nestedColumns(): List<TableColumnSpec> = listOf(
        TableColumnSpec.leaf("id", "int32", false),
        TableColumnSpec(
            "s", "struct", true,
            listOf(
                TableColumnSpec.leaf("a", "int32", true),
                TableColumnSpec("l", "list", true, listOf(TableColumnSpec.leaf("element", "int64", true))),
                TableColumnSpec(
                    "m", "map", true,
                    listOf(
                        TableColumnSpec.leaf("key", "varchar", false),
                        TableColumnSpec.leaf("value", "varchar", true),
                    ),
                ),
            ),
        ),
        TableColumnSpec.leaf("d", "decimal(18,3)", true),
        TableColumnSpec.leaf("ts", "timestamptz", true),
        TableColumnSpec.leaf("u", "uuid", true),
    )

    /** DuckDB `FIELD_IDS` for the nested table: every (nested) parquet field tagged with its DuckLake column id. */
    private fun nestedFieldIds(ids: Map<String, Long>): String {
        fun id(path: String) = ids.getValue(path)
        return "{id: ${id("id")}, " +
            "s: {__duckdb_field_id: ${id("s")}, a: ${id("s.a")}, " +
            "l: {__duckdb_field_id: ${id("s.l")}, element: ${id("s.l.element")}}, " +
            "m: {__duckdb_field_id: ${id("s.m")}, key: ${id("s.m.key")}, value: ${id("s.m.value")}}}, " +
            "d: ${id("d")}, ts: ${id("ts")}, u: ${id("u")}}"
    }

    /** Writes [rowsSql] to `<table dir>/<name>` with DuckLake field ids for every (nested) column; returns its size. */
    private fun writeNestedFile(dir: Path, name: String, rowsSql: String, ids: Map<String, Long>): Long {
        val target = dir.resolve(name)
        scratch("COPY ($rowsSql) TO ${q(target.toString())} (FORMAT PARQUET, FIELD_IDS ${nestedFieldIds(ids)})")
        return Files.size(target)
    }

    /** The fragment for `nested-1.parquet` (rows 1..3) or `nested-2.parquet` (rows 4..5), with per-column stats. */
    private fun nestedFragment(
        name: String,
        size: Long,
        ids: Map<String, Long>,
        first: Boolean,
    ): DucklakeWriteFragment {
        val stats = if (first) {
            listOf(
                stats(ids.getValue("id"), 32, 3, 0, "1", "3"),
                stats(ids.getValue("s.a"), 32, 2, 1, "10", "20"),
                stats(ids.getValue("d"), 32, 3, 0, "1.500", "333.125"),
                stats(ids.getValue("ts"), 32, 3, 0, TS1, TS3),
                stats(ids.getValue("u"), 64, 3, 0, UUID1, UUID3),
            )
        }
        else {
            listOf(
                stats(ids.getValue("id"), 32, 2, 0, "4", "5"),
                stats(ids.getValue("s.a"), 32, 2, 0, "40", "50"),
                stats(ids.getValue("d"), 32, 2, 0, "4.000", "5555.555"),
                stats(ids.getValue("ts"), 32, 2, 0, TS4, TS5),
                stats(ids.getValue("u"), 64, 2, 0, UUID4, UUID5),
            )
        }
        return DucklakeWriteFragment(name, size, 0L, if (first) 3L else 2L, stats)
    }

    private fun assertNestedDdlVisibleToDuckDb() {
        assertThat(changesOf(schemaSnapshot)).contains("schemas_created").contains(SCHEMA)
        assertThat(changesOf(catalog.currentSnapshotId)).contains("tables_created").contains("nested")
        val structType = "STRUCT(a INTEGER, l BIGINT[], m MAP(VARCHAR, VARCHAR))"
        assertThat(describe("nested")).containsExactly(
            java.util.Map.entry("id", "INTEGER"),
            java.util.Map.entry("s", structType),
            java.util.Map.entry("d", "DECIMAL(18,3)"),
            java.util.Map.entry("ts", "TIMESTAMP WITH TIME ZONE"),
            java.util.Map.entry("u", "UUID"),
        )
        assertThat(
            duck(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns " +
                    "WHERE table_catalog = 'lake' AND table_schema = '$SCHEMA' AND table_name = 'nested' " +
                    "ORDER BY ordinal_position",
            ),
        ).containsExactly(
            listOf("id", "INTEGER", "NO"),
            listOf("s", structType, "YES"),
            listOf("d", "DECIMAL(18,3)", "YES"),
            listOf("ts", "TIMESTAMP WITH TIME ZONE", "YES"),
            listOf("u", "UUID", "YES"),
        )
        assertThat(duckLong("SELECT count(*) FROM lake.$SCHEMA.nested")).isZero()
    }

    /** Library-merged global bounds (PG `ducklake_table_column_stats`) must equal what DuckDB computes. */
    private fun assertGlobalStatsAgreeWithDuckDb(tableId: Long, ids: Map<String, Long>) {
        for (column in listOf("id", "s.a", "d", "ts", "u")) {
            val duckRow = duck("SELECT min($column)::VARCHAR, max($column)::VARCHAR FROM lake.$SCHEMA.nested").single()
            assertThat(globalBounds(tableId, ids.getValue(column)))
                .`as`("global min/max of $column (PG catalog) == DuckDB's computed min/max")
                .isEqualTo(duckRow)
        }
    }

    /** DuckDB's nested-value access on the rows this library's catalog rows describe. */
    private fun assertNestedValuesInDuckDb() {
        val table = "lake.$SCHEMA.nested"
        assertThat(duck("SELECT s.l[2], s.m['k1'] FROM $table WHERE id = 1")).containsExactly(listOf("2", "v1"))
        assertThat(duck("SELECT s.m['k3'], len(s.l) FROM $table WHERE id = 2")).containsExactly(listOf("v3", "1"))
        assertThat(duck("SELECT s.a, len(s.l), cardinality(s.m) FROM $table WHERE id = 4"))
            .containsExactly(listOf("40", "0", "0"))
        assertThat(duckColumn("SELECT id FROM $table WHERE s IS NULL")).containsExactly("3")
        assertThat(duckColumn("SELECT id FROM $table WHERE u = '$UUID5' AND d > 5000")).containsExactly("5")
        assertThat(duckColumn("SELECT id FROM $table WHERE ts < TIMESTAMPTZ '$TS3' ORDER BY id"))
            .containsExactly("1", "2")
    }

    @Test
    fun nestedTableRoundTripsWithTimeTravelDeleteAndStats() {
        catalog.createTable(SCHEMA, "nested", nestedColumns(), null, null)
        val created = catalog.currentSnapshotId
        assertNestedDdlVisibleToDuckDb()
        val tableId = tableId("nested")
        val ids = columnIds(tableId)
        val dir = tableDir("nested")

        // Two library INSERTs, each a real Parquet file with nested values.
        val size1 = writeNestedFile(dir, "nested-1.parquet", NESTED_ROWS_1, ids)
        catalog.commitInsert(tableId, listOf(nestedFragment("nested-1.parquet", size1, ids, first = true)))
        val afterFirst = catalog.currentSnapshotId
        assertThat(changesOf(afterFirst)).contains("tables_inserted_into").contains(tableId.toString())
        val size2 = writeNestedFile(dir, "nested-2.parquet", NESTED_ROWS_2, ids)
        catalog.commitInsert(tableId, listOf(nestedFragment("nested-2.parquet", size2, ids, first = false)))
        val afterSecond = catalog.currentSnapshotId

        val expected1 = scratch("SELECT $NESTED_PROJECTION FROM ($NESTED_ROWS_1) ORDER BY id")
        val expected2 = scratch("SELECT $NESTED_PROJECTION FROM ($NESTED_ROWS_2) ORDER BY id")
        val read = "SELECT $NESTED_PROJECTION FROM lake.$SCHEMA.nested"
        assertThat(duck("$read ORDER BY id")).`as`("DuckDB reads exactly the rows the library registered")
            .isEqualTo(expected1 + expected2)
        assertThat(duck("$read AT (VERSION => $afterFirst) ORDER BY id")).`as`("time travel: first insert only")
            .isEqualTo(expected1)
        assertThat(duckLong("SELECT count(*) FROM lake.$SCHEMA.nested AT (VERSION => $created)")).isZero()
        assertThat(tableInfoCounts("nested")).containsExactly("2", "0")
        assertNestedValuesInDuckDb()
        assertGlobalStatsAgreeWithDuckDb(tableId, ids)

        deleteRowTwoAndAssert(tableId, dir, afterSecond)
    }

    /** A positional delete of `id = 2` (position 1 of the first file) registered through `commitDelete`. */
    private fun deleteRowTwoAndAssert(tableId: Long, dir: Path, readSnapshot: Long) {
        val dataFile = catalog.getDataFiles(tableId, readSnapshot).single { it.path == "nested-1.parquet" }
        val deleteName = "nested-1-delete.parquet"
        scratch(
            "COPY (SELECT ${q(dir.resolve(dataFile.path).toString())} AS file_path, 1::BIGINT AS pos) " +
                "TO ${q(dir.resolve(deleteName).toString())} (FORMAT PARQUET)",
        )
        val deleteSize = Files.size(dir.resolve(deleteName))
        catalog.commitDelete(
            tableId,
            listOf(DucklakeDeleteFragment(dataFile.dataFileId, deleteName, 1L, deleteSize, 0L, 1L)),
            readSnapshot,
        )
        val afterDelete = catalog.currentSnapshotId
        assertThat(changesOf(afterDelete)).contains("tables_deleted_from").contains(tableId.toString())
        assertThat(duckColumn("SELECT id FROM lake.$SCHEMA.nested ORDER BY id")).containsExactly("1", "3", "4", "5")
        assertThat(duckColumn("SELECT id FROM lake.$SCHEMA.nested AT (VERSION => $readSnapshot) ORDER BY id"))
            .`as`("time travel to before the delete still sees id 2").containsExactly("1", "2", "3", "4", "5")
        assertThat(tableInfoCounts("nested")).containsExactly("2", "1")
        assertThat(catalog.getLiveRowCount(tableId, afterDelete)).isEqualTo(4L)
    }

    // ------------------------------------------------ flat-table helpers (view, evolution, partitioning)

    /**
     * Writes [rowsSql] to `<dir>/<name>` as Parquet, tagging each top-level column with the DuckLake column id
     * from [fieldIds] (parquet name → column id); returns the file's size in bytes.
     */
    private fun writeFlatFile(dir: Path, name: String, rowsSql: String, fieldIds: Map<String, Long>): Long {
        val target = dir.resolve(name)
        Files.createDirectories(target.parent)
        val tags = fieldIds.entries.joinToString(", ", "{", "}") { (column, id) -> "$column: $id" }
        scratch("COPY ($rowsSql) TO ${q(target.toString())} (FORMAT PARQUET, FIELD_IDS $tags)")
        return Files.size(target)
    }

    private fun flatColumns(vararg columns: Pair<String, String>): List<TableColumnSpec> =
        columns.map { (name, type) -> TableColumnSpec.leaf(name, type, true) }

    // ------------------------------------------------ view

    @Test
    fun viewOverALibraryTableIsQueryableInDuckDb() {
        catalog.createTable(SCHEMA, "vt", flatColumns("id" to "int32", "name" to "varchar"), null, null)
        val tableId = tableId("vt")
        val ids = columnIds(tableId)
        val size = writeFlatFile(
            tableDir("vt"), "vt-1.parquet",
            "SELECT * FROM (VALUES (1::INTEGER, 'ann'), (2, 'bob'), (3, 'cid')) t(id, name)",
            ids,
        )
        val stats = listOf(
            stats(ids.getValue("id"), 16, 3, 0, "1", "3"),
            stats(ids.getValue("name"), 16, 3, 0, "ann", "cid"),
        )
        catalog.commitInsert(tableId, listOf(DucklakeWriteFragment("vt-1.parquet", size, 0L, 3L, stats)))

        catalog.createView(
            SCHEMA, "vt_upper",
            "SELECT id, upper(name) AS name_upper FROM $SCHEMA.vt WHERE id > 1", "duckdb",
            listOf("id", "name_upper"),
            mapOf(DucklakeView.COMMENT_TAG_KEY to "library view"),
        )
        assertThat(changesOf(catalog.currentSnapshotId)).contains("views_created").contains("vt_upper")

        assertThat(duck("SELECT id, name_upper FROM lake.$SCHEMA.vt_upper ORDER BY id"))
            .`as`("DuckDB SELECTs through the library-created view")
            .containsExactly(listOf("2", "BOB"), listOf("3", "CID"))
        assertThat(
            duck(
                "SELECT schema_name, comment, internal FROM duckdb_views() " +
                    "WHERE database_name = 'lake' AND view_name = 'vt_upper'",
            ),
        ).containsExactly(listOf(SCHEMA, "library view", "false"))
        assertThat(
            duck(
                "SELECT table_type FROM information_schema.tables " +
                    "WHERE table_catalog = 'lake' AND table_schema = '$SCHEMA' AND table_name = 'vt_upper'",
            ),
        ).containsExactly(listOf("VIEW"))
    }

    // ------------------------------------------------ column evolution + comments

    private fun assertEvolvedShapeInDuckDb(altered: List<Long>) {
        for (snapshot in altered) {
            assertThat(changesOf(snapshot)).`as`("snapshot $snapshot").contains("tables_altered")
        }
        assertThat(describe("evolve")).containsExactly(
            java.util.Map.entry("id", "INTEGER"),
            java.util.Map.entry("label", "VARCHAR"),
            java.util.Map.entry("added", "BIGINT"),
        )
        assertThat(duck("SELECT * FROM lake.$SCHEMA.evolve ORDER BY id"))
            .`as`("old file: renamed column keeps its data, dropped column is gone, added column is NULL")
            .containsExactly(listOf("1", "a", null), listOf("2", "b", null))
    }

    private fun assertCommentsInDuckDb() {
        assertThat(
            duck(
                "SELECT comment FROM duckdb_tables() " +
                    "WHERE database_name = 'lake' AND schema_name = '$SCHEMA' AND table_name = 'evolve'",
            ),
        ).containsExactly(listOf("evolved by the library"))
        assertThat(
            duck(
                "SELECT column_name, comment FROM duckdb_columns() " +
                    "WHERE database_name = 'lake' AND schema_name = '$SCHEMA' AND table_name = 'evolve' " +
                    "ORDER BY column_index",
            ),
        ).containsExactly(listOf("id", null), listOf("label", "was v"), listOf("added", null))
    }

    @Test
    fun columnEvolutionAndCommentsAreVisibleToDuckDb() {
        val columns = flatColumns("id" to "int32", "v" to "varchar", "gone" to "int32")
        catalog.createTable(SCHEMA, "evolve", columns, null, null)
        val tableId = tableId("evolve")
        val ids = columnIds(tableId)
        val dir = tableDir("evolve")
        val size1 = writeFlatFile(
            dir, "evolve-1.parquet",
            "SELECT * FROM (VALUES (1::INTEGER, 'a', 100::INTEGER), (2, 'b', 200)) t(id, v, gone)",
            ids,
        )
        catalog.commitInsert(tableId, listOf(DucklakeWriteFragment("evolve-1.parquet", size1, 0L, 2L, emptyList())))
        assertThat(duck("SELECT * FROM lake.$SCHEMA.evolve ORDER BY id"))
            .containsExactly(listOf("1", "a", "100"), listOf("2", "b", "200"))

        catalog.addColumn(tableId, TableColumnSpec.leaf("added", "int64", true))
        val s1 = catalog.currentSnapshotId
        catalog.renameColumn(tableId, ids.getValue("v"), "label")
        val s2 = catalog.currentSnapshotId
        catalog.dropColumn(tableId, ids.getValue("gone"))
        val s3 = catalog.currentSnapshotId
        assertEvolvedShapeInDuckDb(listOf(s1, s2, s3))

        // A second file written against the EVOLVED shape; DuckDB stitches both files by field id.
        val evolved = columnIds(tableId)
        assertThat(evolved.getValue("label")).`as`("rename keeps the column id").isEqualTo(ids.getValue("v"))
        assertThat(evolved).doesNotContainKey("gone").doesNotContainKey("v")
        val size2 = writeFlatFile(
            dir, "evolve-2.parquet",
            "SELECT * FROM (VALUES (3::INTEGER, 'c', 300::BIGINT), (4, 'd', NULL)) t(id, label, added)",
            evolved,
        )
        catalog.commitInsert(tableId, listOf(DucklakeWriteFragment("evolve-2.parquet", size2, 0L, 2L, emptyList())))
        assertThat(duck("SELECT id, label, added FROM lake.$SCHEMA.evolve ORDER BY id")).containsExactly(
            listOf("1", "a", null), listOf("2", "b", null), listOf("3", "c", "300"), listOf("4", "d", null),
        )
        assertThat(duckColumn("SELECT id FROM lake.$SCHEMA.evolve WHERE added IS NOT NULL")).containsExactly("3")

        // Comments land in the tag tables DuckDB's COMMENT ON uses.
        catalog.setTableComment(tableId, "evolved by the library")
        catalog.setColumnComment(tableId, evolved.getValue("label"), "was v")
        assertCommentsInDuckDb()
    }

    // ------------------------------------------------ partitioned table

    private fun partitionedFragment(
        dir: Path,
        name: String,
        rowsSql: String,
        ids: Map<String, Long>,
        partitionId: Long,
    ): DucklakeWriteFragment {
        val size = writeFlatFile(dir, name, rowsSql, ids)
        val region = scratch("SELECT DISTINCT region FROM ($rowsSql)").single().single()!!
        val rowCount = scratch("SELECT count(*) FROM ($rowsSql)").single().single()!!.toLong()
        return DucklakeWriteFragment(
            name, size, 0L, rowCount,
            listOf(stats(ids.getValue("region"), 16, rowCount, 0, region, region)),
            mapOf(0 to region), partitionId,
        )
    }

    @Test
    fun partitionedTableFilesAreReadThroughPartitionValues() {
        catalog.createTable(
            SCHEMA, "part",
            flatColumns("id" to "int32", "region" to "varchar", "amount" to "decimal(10,2)"),
            listOf(PartitionFieldSpec("region", DucklakePartitionTransform.IDENTITY)),
            null,
        )
        val tableId = tableId("part")
        val ids = columnIds(tableId)
        val spec = catalog.getPartitionSpecs(tableId, catalog.currentSnapshotId).single()
        assertThat(spec.fields.single().columnId).isEqualTo(ids.getValue("region"))
        val dir = tableDir("part")
        val eu = partitionedFragment(
            dir, "region=eu/part-eu.parquet",
            "SELECT * FROM (VALUES (1::INTEGER, 'eu', 10.50::DECIMAL(10,2)), (2, 'eu', 20.25)) t(id, region, amount)",
            ids, spec.partitionId,
        )
        val us = partitionedFragment(
            dir, "region=us/part-us.parquet",
            "SELECT * FROM (VALUES (3::INTEGER, 'us', 30.00::DECIMAL(10,2))) t(id, region, amount)",
            ids, spec.partitionId,
        )
        catalog.commitInsert(tableId, listOf(eu, us))
        val snapshot = catalog.currentSnapshotId
        assertThat(catalog.getFilePartitionValues(tableId, snapshot).values.flatten().map { it.partitionValue })
            .containsExactlyInAnyOrder("eu", "us")

        assertThat(duck("SELECT id, region, amount::VARCHAR FROM lake.$SCHEMA.part ORDER BY id"))
            .containsExactly(listOf("1", "eu", "10.50"), listOf("2", "eu", "20.25"), listOf("3", "us", "30.00"))
        assertThat(duckColumn("SELECT id FROM lake.$SCHEMA.part WHERE region = 'us'")).containsExactly("3")
        assertThat(duck("SELECT region, sum(amount)::VARCHAR FROM lake.$SCHEMA.part GROUP BY region ORDER BY region"))
            .containsExactly(listOf("eu", "30.75"), listOf("us", "30.00"))
        assertThat(tableInfoCounts("part")).containsExactly("2", "0")
        val listFiles = "SELECT data_file FROM ducklake_list_files('lake', 'part', schema => '$SCHEMA') ORDER BY 1"
        assertThat(duckColumn(listFiles))
            .hasSize(2)
            .allMatch { it!!.endsWith("part-eu.parquet") || it.endsWith("part-us.parquet") }
    }

    // ------------------------------------------------ add_files with a name map

    @Test
    fun addFilesWithANameMapIsReadThroughTheMapping() {
        catalog.createTable(SCHEMA, "nm", flatColumns("id" to "int32", "name" to "varchar"), null, null)
        val tableId = tableId("nm")
        val ids = columnIds(tableId)
        // An external file: no field ids, and column names that differ from the table's.
        val external = tableDir("nm").resolve("external-ident-label.parquet")
        scratch(
            "COPY (SELECT * FROM (VALUES (7::INTEGER, 'seven'), (8, 'eight')) t(ident, label)) " +
                "TO ${q(external.toString())} (FORMAT PARQUET)",
        )
        val nameMap = DucklakeNameMap(
            listOf(
                DucklakeNameMapEntry("ident", ids.getValue("id")),
                DucklakeNameMapEntry("label", ids.getValue("name")),
            ),
        )
        val stats = listOf(
            stats(ids.getValue("id"), 16, 2, 0, "7", "8"),
            stats(ids.getValue("name"), 16, 2, 0, "eight", "seven"),
        )
        catalog.commitAddFiles(
            tableId,
            listOf(
                DucklakeWriteFragment(
                    external.toString(), false, "parquet", Files.size(external), 0L, 2L, stats,
                    emptyMap(), null, nameMap,
                ),
            ),
        )
        val snapshot = catalog.currentSnapshotId
        assertThat(changesOf(snapshot)).contains("tables_inserted_into").contains(tableId.toString())
        val mappingId = catalog.getDataFiles(tableId, snapshot).single().mappingId!!
        assertThat(pg("SELECT type FROM ducklake_column_mapping WHERE mapping_id = $mappingId").single().single())
            .isEqualTo("map_by_name")

        assertThat(duck("SELECT id, name FROM lake.$SCHEMA.nm ORDER BY id"))
            .`as`("DuckDB reads the differently-named parquet columns through the library's name map")
            .containsExactly(listOf("7", "seven"), listOf("8", "eight"))
        assertThat(duckColumn("SELECT name FROM lake.$SCHEMA.nm WHERE id = 8")).containsExactly("eight")
        assertThat(globalBounds(tableId, ids.getValue("id")))
            .isEqualTo(duck("SELECT min(id)::VARCHAR, max(id)::VARCHAR FROM lake.$SCHEMA.nm").single())
        assertThat(tableInfoCounts("nm")).containsExactly("1", "0")
    }
}
