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
import java.sql.Connection
import java.sql.DriverManager

/**
 * Behaviour tests for the DDL-side [DucklakeCatalog] methods that had no coverage (TODO-rectify-from-eval.md
 * Q-5): [DucklakeCatalog.renameTable], [DucklakeCatalog.renameSchema], [DucklakeCatalog.addField],
 * [DucklakeCatalog.dropField], [DucklakeCatalog.getTableComment], [DucklakeCatalog.getColumnComments],
 * [DucklakeCatalog.getSortKeys] and [DucklakeCatalog.resolveSchemaVersionSnapshot].
 *
 * Stock DuckDB (with the same PostgreSQL catalog `ATTACH`ed) is the oracle wherever it can observe the
 * effect: a renamed table / schema is resolvable under its new name and, via `AT (VERSION => …)`, under
 * the old one; a struct field added or dropped by the library shows up in DuckDB's `DESCRIBE` and in the
 * values it reads; comments / sort keys written by DuckDB are read back by the library and vice versa.
 */
class TestJdbcDucklakeCatalogCoverageDdl {
    companion object {
        private const val SCHEMA = "test_schema"

        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "coverage-ddl")
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

    // ---------------------------------------------------------------- oracle plumbing

    /** A fresh stock-DuckDB session with the lake attached as `lake` (fresh per block: an INTERNAL error poisons it). */
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

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    private fun table(name: String, schema: String = SCHEMA): DucklakeTable =
        catalog.getTable(schema, name, catalog.currentSnapshotId)!!

    private fun changesOf(snapshotId: Long): String =
        catalog.listSnapshotChanges().single { it.snapshotId == snapshotId }.changesMade!!

    private fun schemaVersionOf(snapshotId: Long): Long = catalog.getSnapshot(snapshotId)!!.schemaVersion

    /** Active columns keyed by dotted path (`s`, `s.a`, …) at [snapshotId]. */
    private fun columnsByPath(tableId: Long, snapshotId: Long): Map<String, DucklakeColumn> {
        val all = catalog.getAllColumnsWithParentage(tableId, snapshotId)
        val byId = all.associateBy { it.columnId }
        fun path(c: DucklakeColumn): String = c.parentColumn?.let { path(byId.getValue(it)) + "." }.orEmpty() + c.columnName
        return all.associateBy { path(it) }
    }

    // ---------------------------------------------------------------- renameTable

    @Test
    fun renameTableKeepsIdentityAndDataAndIsTimeTravelable() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.rn_old (id INTEGER, v VARCHAR)",
            "INSERT INTO lake.$SCHEMA.rn_old VALUES (1, 'a'), (2, 'b'), (3, 'c')",
            "CALL ducklake_flush_inlined_data('lake', schema_name => '$SCHEMA', table_name => 'rn_old')",
        )
        val before = catalog.currentSnapshotId
        val old = table("rn_old")
        val files = catalog.getDataFiles(old.tableId, before)
        assertThat(files).hasSize(1)

        catalog.renameTable(old.tableId, SCHEMA, "rn_new")
        val after = catalog.currentSnapshotId
        assertThat(after).isEqualTo(before + 1)

        val renamed = catalog.getTable(SCHEMA, "rn_new", after)!!
        assertThat(renamed.tableId).`as`("same table_id").isEqualTo(old.tableId)
        assertThat(renamed.tableUuid).`as`("same uuid").isEqualTo(old.tableUuid)
        assertThat(renamed.path).`as`("data directory keeps its original name").isEqualTo(old.path)
        assertThat(renamed.schemaId).isEqualTo(old.schemaId)
        assertThat(renamed.beginSnapshot).isEqualTo(after)
        assertThat(catalog.getTable(SCHEMA, "rn_old", after)).`as`("old name gone at the new snapshot").isNull()
        assertThat(catalog.getTable(SCHEMA, "rn_old", before)!!.tableId).`as`("time travel keeps the old name").isEqualTo(old.tableId)
        assertThat(catalog.getTable(SCHEMA, "rn_new", before)).isNull()
        assertThat(catalog.getTableById(old.tableId, after)!!.tableName).isEqualTo("rn_new")
        assertThat(catalog.getDataFiles(old.tableId, after)).`as`("data files untouched").isEqualTo(files)
        assertThat(pg("SELECT end_snapshot FROM ducklake_table WHERE table_id = ${old.tableId} AND table_name = 'rn_old'"))
            .`as`("old row end-snapshotted at the rename")
            .containsExactly(listOf(after.toString()))

        assertThat(schemaVersionOf(after)).`as`("a rename is DDL: schema version bumps").isEqualTo(schemaVersionOf(before) + 1)
        assertThat(changesOf(after))
            .`as`("upstream vocabulary: rename == created_table of the new name (+ altered_table)")
            .contains("created_table:\"$SCHEMA\".\"rn_new\"")
            .contains("altered_table:${old.tableId}")
            .doesNotContain("dropped_table")

        // Oracle: DuckDB reads the data under the new name, refuses the old one, and time-travels to it.
        assertThat(duckColumn("SELECT v FROM lake.$SCHEMA.rn_new ORDER BY id")).containsExactly("a", "b", "c")
        assertThat(duckColumn("SELECT v FROM lake.$SCHEMA.rn_old AT (VERSION => $before) ORDER BY id"))
            .containsExactly("a", "b", "c")
        assertThat(duckColumn("SELECT table_name FROM ducklake_table_info('lake') WHERE table_name IN ('rn_old', 'rn_new')"))
            .containsExactly("rn_new")
        assertThatThrownBy { duck("SELECT count(*) FROM lake.$SCHEMA.rn_old") }.hasMessageContaining("rn_old")
    }

    @Test
    fun renameTableRejectsNameClashCrossSchemaAndUnknownTable() {
        catalog.createTable(SCHEMA, "rn_a", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        catalog.createTable(SCHEMA, "rn_b", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        catalog.createSchema("rn_other")
        val snapshot = catalog.currentSnapshotId
        val a = table("rn_a")

        assertThatThrownBy { catalog.renameTable(a.tableId, SCHEMA, "rn_b") }
            .isInstanceOf(DucklakeEntityAlreadyExistsException::class.java)
            .hasMessageContaining("rn_b")
        assertThatThrownBy { catalog.renameTable(a.tableId, "rn_other", "rn_a") }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
            .hasMessageContaining("across schemas")
        assertThatThrownBy { catalog.renameTable(a.tableId + 100_000, SCHEMA, "rn_c") }
            .isInstanceOf(DucklakeEntityNotFoundException::class.java)
        assertThat(catalog.currentSnapshotId).`as`("no snapshot minted by a failed rename").isEqualTo(snapshot)
        assertThat(catalog.getTable(SCHEMA, "rn_a", snapshot)!!.tableId).isEqualTo(a.tableId)
    }

    // ---------------------------------------------------------------- renameSchema

    @Test
    fun renameSchemaRepointsTablesAndViewsAndKeepsThePath() {
        duckExec(
            "CREATE SCHEMA lake.rs_old",
            "CREATE TABLE lake.rs_old.t (id INTEGER, v VARCHAR)",
            "INSERT INTO lake.rs_old.t VALUES (1, 'a'), (2, 'b')",
            "CALL ducklake_flush_inlined_data('lake', schema_name => 'rs_old', table_name => 't')",
            "CREATE VIEW lake.rs_old.v AS SELECT id FROM t WHERE id > 1",
        )
        val before = catalog.currentSnapshotId
        val oldSchema = catalog.getSchema("rs_old", before)!!
        val t = table("t", "rs_old")
        val v = catalog.getView("rs_old", "v", before)!!

        catalog.renameSchema("rs_old", "rs_new")
        val after = catalog.currentSnapshotId
        assertThat(after).isEqualTo(before + 1)

        val newSchema = catalog.getSchema("rs_new", after)!!
        assertThat(newSchema.schemaId).`as`("PK on schema_id forces a NEW id").isNotEqualTo(oldSchema.schemaId)
        assertThat(newSchema.path).`as`("keeps the OLD path so no data moves").isEqualTo(oldSchema.path)
        assertThat(newSchema.pathIsRelative).isEqualTo(oldSchema.pathIsRelative)
        assertThat(newSchema.beginSnapshot).isEqualTo(after)
        assertThat(catalog.getSchema("rs_old", after)).isNull()
        assertThat(catalog.getSchema("rs_old", before)!!.schemaId).`as`("time travel keeps the old name").isEqualTo(oldSchema.schemaId)
        assertThat(catalog.listSchemas(after).map { it.schemaName }).contains("rs_new").doesNotContain("rs_old")

        val movedTable = catalog.getTable("rs_new", "t", after)!!
        assertThat(movedTable.tableId).isEqualTo(t.tableId)
        assertThat(movedTable.tableUuid).isEqualTo(t.tableUuid)
        assertThat(movedTable.schemaId).isEqualTo(newSchema.schemaId)
        assertThat(movedTable.path).isEqualTo(t.path)
        assertThat(catalog.listTables(oldSchema.schemaId, after)).`as`("nothing left under the old schema id").isEmpty()
        assertThat(catalog.getTable("rs_old", "t", before)!!.schemaId).isEqualTo(oldSchema.schemaId)
        val movedView = catalog.getView("rs_new", "v", after)!!
        assertThat(movedView.viewId).isEqualTo(v.viewId)
        assertThat(movedView.viewUuid).isEqualTo(v.viewUuid)
        assertThat(movedView.sql).isEqualTo(v.sql)
        assertThat(movedView.schemaId).isEqualTo(newSchema.schemaId)

        assertThat(changesOf(after))
            .`as`("no schema-rename change type upstream: recorded as dropped + created")
            .contains("dropped_schema:${oldSchema.schemaId}")
            .contains("created_schema:\"rs_new\"")
            .contains("altered_table:${t.tableId}")
        assertThat(schemaVersionOf(after)).isEqualTo(schemaVersionOf(before) + 1)

        // Oracle: DuckDB resolves the data through the renamed schema (the path did not move).
        assertThat(duckColumn("SELECT v FROM lake.rs_new.t ORDER BY id")).containsExactly("a", "b")
        assertThat(duckColumn("SELECT id FROM lake.rs_new.v")).containsExactly("2")
        assertThat(duckColumn("SELECT schema_name FROM information_schema.schemata WHERE catalog_name = 'lake' AND schema_name IN ('rs_old', 'rs_new')"))
            .containsExactly("rs_new")
        assertThat(duckColumn("SELECT v FROM lake.rs_old.t AT (VERSION => $before) ORDER BY id")).containsExactly("a", "b")
    }

    @Test
    fun renameSchemaRejectsTakenNameAndUnknownSchema() {
        catalog.createSchema("rs_x")
        catalog.createSchema("rs_y")
        val snapshot = catalog.currentSnapshotId
        assertThatThrownBy { catalog.renameSchema("rs_x", "rs_y") }
            .isInstanceOf(DucklakeEntityAlreadyExistsException::class.java)
            .hasMessageContaining("rs_y")
        assertThatThrownBy { catalog.renameSchema("rs_nope", "rs_z") }
            .isInstanceOf(DucklakeEntityNotFoundException::class.java)
        assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
    }

    // ---------------------------------------------------------------- addField / dropField

    private fun structColumns() = listOf(
        TableColumnSpec.leaf("id", "int32", false),
        TableColumnSpec(
            "s", "struct", true,
            listOf(TableColumnSpec.leaf("a", "int32", true), TableColumnSpec.leaf("b", "varchar", true)),
        ),
        TableColumnSpec.leaf("n", "int64", true),
    )

    private fun describeType(tableName: String, column: String): String =
        duck("DESCRIBE lake.$SCHEMA.$tableName").single { it[0] == column }[1]!!

    @Test
    fun addFieldAppendsAStructSubfieldVisibleToDuckDb() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.af (id INTEGER, s STRUCT(a INTEGER, b VARCHAR))",
            "INSERT INTO lake.$SCHEMA.af VALUES (1, {'a': 10, 'b': 'x'}), (2, {'a': 20, 'b': 'y'})",
            "CALL ducklake_flush_inlined_data('lake', schema_name => '$SCHEMA', table_name => 'af')",
        )
        val before = catalog.currentSnapshotId
        val t = table("af")
        val beforeCols = columnsByPath(t.tableId, before)
        val maxId = beforeCols.values.maxOf { it.columnId }

        catalog.addField(t.tableId, listOf("s"), TableColumnSpec.leaf("c", "int64", true), false)
        val after = catalog.currentSnapshotId
        assertThat(after).isEqualTo(before + 1)

        val cols = columnsByPath(t.tableId, after)
        val c = cols.getValue("s.c")
        assertThat(c.parentColumn).isEqualTo(beforeCols.getValue("s").columnId)
        assertThat(c.columnId).`as`("fresh column id").isGreaterThan(maxId)
        assertThat(c.columnOrder).`as`("appended after a, b").isEqualTo(beforeCols.getValue("s.b").columnOrder + 1)
        assertThat(c.columnType).isEqualTo("int64")
        assertThat(c.beginSnapshot).isEqualTo(after)
        assertThat(cols.getValue("s")).`as`("struct row itself unchanged").isEqualTo(beforeCols.getValue("s"))
        assertThat(catalog.getTableColumns(t.tableId, after).single { it.columnName == "s" }.columnType)
            .isEqualTo("struct<a:int32,b:varchar,c:int64>")
        assertThat(catalog.getTableColumns(t.tableId, before).single { it.columnName == "s" }.columnType)
            .isEqualTo("struct<a:int32,b:varchar>")
        assertThat(schemaVersionOf(after)).isEqualTo(schemaVersionOf(before) + 1)
        assertThat(changesOf(after)).contains("altered_table:${t.tableId}")

        // Oracle: DuckDB shows the sub-field and reads NULL for it on the pre-existing (Parquet) rows.
        assertThat(describeType("af", "s")).isEqualTo("STRUCT(a INTEGER, b VARCHAR, c BIGINT)")
        assertThat(duckColumn("SELECT typeof(s) FROM lake.$SCHEMA.af AT (VERSION => $before) LIMIT 1"))
            .containsExactly("STRUCT(a INTEGER, b VARCHAR)")
        assertThat(duckColumn("SELECT s.c FROM lake.$SCHEMA.af ORDER BY id")).containsExactly(null, null)
        assertThat(duckColumn("SELECT s::VARCHAR FROM lake.$SCHEMA.af ORDER BY id"))
            .containsExactly("{'a': 10, 'b': x, 'c': NULL}", "{'a': 20, 'b': y, 'c': NULL}")
    }

    /**
     * W-B7: upstream creates `ducklake_inlined_data_<t>_<newSchemaVersion>` (registered in
     * `ducklake_inlined_data_tables`) in the SAME commit as any column-schema change on a table that has
     * inlined-data tables (`ducklake_transaction_state.cpp` `column_schema_change` →
     * `DuckLakeMetadataManager::WriteNewInlinedTables`), because a later inlined INSERT goes to the table with
     * the highest schema version (`LatestInlinedTableQuery`). Without it DuckDB's next small INSERT lands in
     * the OLD-schema inlined table: for a struct field the read then INTERNAL-errors, for a top-level column
     * the INSERT fails to commit.
     */
    @Test
    fun duckDbCanInlineRowsIntoAStructAfterALibraryAddField() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.af_ins (id INTEGER, s STRUCT(a INTEGER, b VARCHAR))",
            "INSERT INTO lake.$SCHEMA.af_ins VALUES (1, {'a': 10, 'b': 'x'})",
            "CALL ducklake_flush_inlined_data('lake', schema_name => '$SCHEMA', table_name => 'af_ins')",
        )
        val t = table("af_ins")
        catalog.addField(t.tableId, listOf("s"), TableColumnSpec.leaf("c", "int64", true), false)
        val schemaVersion = schemaVersionOf(catalog.currentSnapshotId)
        assertThat(pg("SELECT table_name FROM ducklake_inlined_data_tables WHERE table_id = ${t.tableId} AND schema_version = $schemaVersion"))
            .`as`("upstream registers a new inlined-data table for the new schema version in the ALTER commit")
            .containsExactly(listOf("ducklake_inlined_data_${t.tableId}_$schemaVersion"))
        duckExec("INSERT INTO lake.$SCHEMA.af_ins VALUES (3, {'a': 30, 'b': 'z', 'c': 7})")
        assertThat(duckColumn("SELECT s.c FROM lake.$SCHEMA.af_ins ORDER BY id")).containsExactly(null, "7")
        assertThat(duckColumn("SELECT s.a FROM lake.$SCHEMA.af_ins ORDER BY id")).containsExactly("10", "30")
    }

    /**
     * W-B7 for top-level DDL and for the PostgreSQL physical-type mapping: after the library adds columns of
     * many types (nested, wide ints, temporals, blob, decimal), DuckDB must be able to inline rows into the
     * table the library created and read them back — the library's table must therefore have exactly the
     * physical column types DuckDB's own postgres writer expects.
     */
    @Test
    fun duckDbCanInlineRowsAfterLibraryAddDropAndRenameColumn() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.ac_ins (id INTEGER, old VARCHAR, gone INTEGER)",
            "INSERT INTO lake.$SCHEMA.ac_ins VALUES (1, 'a', 0)",
        )
        val t = table("ac_ins")
        val gone = columnsByPath(t.tableId, catalog.currentSnapshotId).getValue("gone").columnId
        catalog.dropColumn(t.tableId, gone)
        catalog.renameColumn(t.tableId, columnsByPath(t.tableId, catalog.currentSnapshotId).getValue("old").columnId, "renamed")
        catalog.addColumn(t.tableId, TableColumnSpec.leaf("d", "decimal(18,3)", true))
        catalog.addColumn(t.tableId, TableColumnSpec.leaf("ts", "timestamptz", true))
        catalog.addColumn(t.tableId, TableColumnSpec.leaf("big", "uint64", true))
        catalog.addColumn(t.tableId, TableColumnSpec.leaf("bl", "blob", true))
        catalog.addColumn(
            t.tableId,
            // No DATE inside the struct: DuckDB's own postgres writer renders a non-native nested leaf as
            // CAST('…' AS VARCHAR) text and then cannot read it back (upstream defect, see R-D4).
            TableColumnSpec("nest", "struct", true, listOf(
                TableColumnSpec.leaf("x", "int32", true),
                TableColumnSpec("l", "list", true, listOf(TableColumnSpec.leaf("element", "int32", true))),
            )),
        )
        catalog.addColumn(t.tableId, TableColumnSpec.leaf("dt", "date", true))
        val schemaVersion = schemaVersionOf(catalog.currentSnapshotId)
        assertThat(pg("SELECT count(*) FROM ducklake_inlined_data_tables WHERE table_id = ${t.tableId}"))
            .`as`("one inlined table per column-changing commit (create + 8 ALTERs)")
            .containsExactly(listOf("9"))
        assertThat(pg("SELECT max(schema_version) FROM ducklake_inlined_data_tables WHERE table_id = ${t.tableId}"))
            .containsExactly(listOf(schemaVersion.toString()))

        duckExec(
            "INSERT INTO lake.$SCHEMA.ac_ins VALUES (2, 'b', 1.5, TIMESTAMPTZ '2024-02-29 10:00:00+00', " +
                "18446744073709551615, '\\x00\\xFF'::BLOB, {'x': 5, 'l': [1, 2]}, DATE '2000-01-02')",
        )
        assertThat(duckColumn("SELECT renamed FROM lake.$SCHEMA.ac_ins ORDER BY id")).containsExactly("a", "b")
        assertThat(duckColumn("SELECT d::VARCHAR FROM lake.$SCHEMA.ac_ins ORDER BY id")).containsExactly(null, "1.500")
        assertThat(duckColumn("SELECT big::VARCHAR FROM lake.$SCHEMA.ac_ins ORDER BY id")).containsExactly(null, "18446744073709551615")
        assertThat(duckColumn("SELECT bl::VARCHAR FROM lake.$SCHEMA.ac_ins ORDER BY id")).containsExactly(null, "\\x00\\xFF")
        assertThat(duckColumn("SELECT nest.l[2]::VARCHAR FROM lake.$SCHEMA.ac_ins ORDER BY id")).containsExactly(null, "2")
        assertThat(duckColumn("SELECT dt::VARCHAR FROM lake.$SCHEMA.ac_ins ORDER BY id")).containsExactly(null, "2000-01-02")
        assertThat(duckColumn("SELECT epoch_us(ts)::VARCHAR FROM lake.$SCHEMA.ac_ins ORDER BY id"))
            .containsExactly(null, "1709200800000000")
        // The library's decoder reads DuckDB's inlined row out of the library-created table too.
        val snapshot = catalog.currentSnapshotId
        val cols = catalog.getTableColumns(t.tableId, snapshot)
        val decoded = catalog.readInlinedDataDecoded(t.tableId, schemaVersion, snapshot, cols).single()
        assertThat(decoded[cols.indexOfFirst { it.columnName == "d" }]).isEqualTo(java.math.BigDecimal("1.500"))
        assertThat(decoded[cols.indexOfFirst { it.columnName == "big" }]).isEqualTo(java.math.BigInteger("18446744073709551615"))
    }

    @Test
    fun addFieldNestedStructAndIgnoreExistingAndErrors() {
        catalog.createTable(SCHEMA, "af2", structColumns(), null, null)
        val t = table("af2")
        val deep = TableColumnSpec("deep", "struct", true, listOf(TableColumnSpec.leaf("x", "int32", true)))
        catalog.addField(t.tableId, listOf("s"), deep, false)
        catalog.addField(t.tableId, listOf("s", "deep"), TableColumnSpec.leaf("y", "varchar", true), false)
        val snapshot = catalog.currentSnapshotId
        val cols = columnsByPath(t.tableId, snapshot)
        assertThat(cols.getValue("s.deep.x").parentColumn).isEqualTo(cols.getValue("s.deep").columnId)
        assertThat(cols.getValue("s.deep.y").parentColumn).isEqualTo(cols.getValue("s.deep").columnId)
        assertThat(cols.getValue("s.deep.y").columnOrder).isEqualTo(cols.getValue("s.deep.x").columnOrder + 1)
        assertThat(catalog.getTableColumns(t.tableId, snapshot).single { it.columnName == "s" }.columnType)
            .isEqualTo("struct<a:int32,b:varchar,deep:struct<x:int32,y:varchar>>")
        assertThat(describeType("af2", "s")).isEqualTo("STRUCT(a INTEGER, b VARCHAR, deep STRUCT(x INTEGER, y VARCHAR))")

        // IF NOT EXISTS: an existing field is a no-op — no snapshot minted.
        catalog.addField(t.tableId, listOf("s"), TableColumnSpec.leaf("a", "int32", true), true)
        assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
        assertThatThrownBy { catalog.addField(t.tableId, listOf("s"), TableColumnSpec.leaf("a", "int32", true), false) }
            .isInstanceOf(DucklakeEntityAlreadyExistsException::class.java)
            .hasMessageContaining("s.a")
        assertThatThrownBy { catalog.addField(t.tableId, listOf("n"), TableColumnSpec.leaf("z", "int32", true), false) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
            .hasMessageContaining("non-struct")
        assertThatThrownBy { catalog.addField(t.tableId, listOf("s", "nope"), TableColumnSpec.leaf("z", "int32", true), false) }
            .isInstanceOf(DucklakeEntityNotFoundException::class.java)
        assertThat(catalog.currentSnapshotId).isEqualTo(snapshot)
    }

    @Test
    fun dropFieldEndSnapshotsTheSubtreeAndDuckDbNoLongerSeesIt() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.df (id INTEGER, s STRUCT(a INTEGER, b VARCHAR, deep STRUCT(x INTEGER, y INTEGER)))",
            "INSERT INTO lake.$SCHEMA.df VALUES (1, {'a': 10, 'b': 'x', 'deep': {'x': 1, 'y': 2}})",
            "CALL ducklake_flush_inlined_data('lake', schema_name => '$SCHEMA', table_name => 'df')",
        )
        val before = catalog.currentSnapshotId
        val t = table("df")
        val beforeCols = columnsByPath(t.tableId, before)

        catalog.dropField(t.tableId, listOf("s", "b"))
        val afterB = catalog.currentSnapshotId
        assertThat(columnsByPath(t.tableId, afterB).keys).containsExactlyInAnyOrder("id", "s", "s.a", "s.deep", "s.deep.x", "s.deep.y")
        assertThat(columnsByPath(t.tableId, before).keys).`as`("time travel still has b").contains("s.b")
        assertThat(pg("SELECT end_snapshot FROM ducklake_column WHERE table_id = ${t.tableId} AND column_id = ${beforeCols.getValue("s.b").columnId}"))
            .containsExactly(listOf(afterB.toString()))
        assertThat(schemaVersionOf(afterB)).isEqualTo(schemaVersionOf(before) + 1)
        assertThat(changesOf(afterB)).contains("altered_table:${t.tableId}")
        assertThat(describeType("df", "s")).isEqualTo("STRUCT(a INTEGER, deep STRUCT(x INTEGER, y INTEGER))")
        assertThat(duckColumn("SELECT s::VARCHAR FROM lake.$SCHEMA.df")).containsExactly("{'a': 10, 'deep': {'x': 1, 'y': 2}}")
        assertThat(duckColumn("SELECT s.b FROM lake.$SCHEMA.df AT (VERSION => $before)")).containsExactly("x")

        // Dropping a nested struct cascades to its descendants (no dangling parent_column left active).
        catalog.dropField(t.tableId, listOf("s", "deep"))
        val afterDeep = catalog.currentSnapshotId
        assertThat(columnsByPath(t.tableId, afterDeep).keys).containsExactlyInAnyOrder("id", "s", "s.a")
        val subtree = listOf("s.deep", "s.deep.x", "s.deep.y").map { beforeCols.getValue(it).columnId }
        assertThat(pg("SELECT DISTINCT end_snapshot FROM ducklake_column WHERE table_id = ${t.tableId} AND column_id IN (${subtree.joinToString()})"))
            .containsExactly(listOf(afterDeep.toString()))
        assertThat(describeType("df", "s")).isEqualTo("STRUCT(a INTEGER)")
        assertThat(duckColumn("SELECT s.a FROM lake.$SCHEMA.df")).containsExactly("10")

        assertThatThrownBy { catalog.dropField(t.tableId, listOf("s", "b")) }
            .`as`("already dropped")
            .isInstanceOf(DucklakeEntityNotFoundException::class.java)
        assertThat(catalog.currentSnapshotId).isEqualTo(afterDeep)
    }

    // ---------------------------------------------------------------- comments

    @Test
    fun tableAndColumnCommentsRoundTripWithDuckDbAndAreVersioned() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.cm (id INTEGER, v VARCHAR)",
            "COMMENT ON TABLE lake.$SCHEMA.cm IS 'table says hi'",
            "COMMENT ON COLUMN lake.$SCHEMA.cm.id IS 'the key'",
        )
        val duckSet = catalog.currentSnapshotId
        val t = table("cm")
        val cols = catalog.getTableColumns(t.tableId, duckSet).associateBy { it.columnName }
        val idCol = cols.getValue("id").columnId
        val vCol = cols.getValue("v").columnId

        assertThat(catalog.getTableComment(t.tableId, duckSet)).isEqualTo("table says hi")
        assertThat(catalog.getColumnComments(t.tableId, duckSet)).containsExactly(java.util.Map.entry(idCol, "the key"))
        assertThat(catalog.getTableComment(t.tableId, t.beginSnapshot)).`as`("before COMMENT ON").isNull()
        assertThat(catalog.getColumnComments(t.tableId, t.beginSnapshot)).isEmpty()

        catalog.setTableComment(t.tableId, "library says hi")
        catalog.setColumnComment(t.tableId, vCol, "the value")
        val librarySet = catalog.currentSnapshotId
        assertThat(catalog.getTableComment(t.tableId, librarySet)).isEqualTo("library says hi")
        assertThat(catalog.getTableComment(t.tableId, duckSet)).`as`("old value still visible at its snapshot").isEqualTo("table says hi")
        assertThat(catalog.getColumnComments(t.tableId, librarySet))
            .containsOnly(java.util.Map.entry(idCol, "the key"), java.util.Map.entry(vCol, "the value"))

        catalog.setTableComment(t.tableId, null)
        catalog.setColumnComment(t.tableId, idCol, null)
        val cleared = catalog.currentSnapshotId
        assertThat(catalog.getTableComment(t.tableId, cleared)).isNull()
        assertThat(catalog.getColumnComments(t.tableId, cleared)).containsExactly(java.util.Map.entry(vCol, "the value"))
        assertThat(catalog.getTableComment(t.tableId, librarySet)).isEqualTo("library says hi")

        // Oracle: DuckDB's catalog views agree with what the library reads.
        assertThat(duck("SELECT comment FROM duckdb_tables() WHERE database_name = 'lake' AND table_name = 'cm'"))
            .containsExactly(listOf(null))
        assertThat(
            duck("SELECT column_name, comment FROM duckdb_columns() WHERE database_name = 'lake' AND table_name = 'cm' ORDER BY column_index"),
        ).containsExactly(listOf("id", null), listOf("v", "the value"))
    }

    // ---------------------------------------------------------------- sort keys

    @Test
    fun sortKeysWrittenByDuckDbAreReadInOrderWithDirectionAndNullOrder() {
        duckExec("CREATE TABLE lake.$SCHEMA.srt (a INTEGER, b VARCHAR, c DOUBLE)")
        val unsorted = catalog.currentSnapshotId
        val t = table("srt")
        assertThat(catalog.getSortKeys(t.tableId, unsorted)).isEmpty()

        duckExec("ALTER TABLE lake.$SCHEMA.srt SET SORTED BY (b DESC NULLS FIRST, a, c DESC)")
        val sorted = catalog.currentSnapshotId
        val keys = catalog.getSortKeys(t.tableId, sorted)
        assertThat(keys.map { it.sortKeyIndex }).`as`("ordered by sort_key_index").containsExactly(0, 1, 2)
        assertThat(keys.map { it.direction })
            .containsExactly(DucklakeSortDirection.DESC, DucklakeSortDirection.ASC, DucklakeSortDirection.DESC)
        assertThat(keys.map { it.nullOrder })
            .containsExactly(DucklakeNullOrder.NULLS_FIRST, DucklakeNullOrder.NULLS_LAST, DucklakeNullOrder.NULLS_LAST)
        assertThat(keys.map { it.dialect }).containsOnly("duckdb")
        assertThat(keys.map { it.expression.replace("\"", "") }).containsExactly("b", "a", "c")
        assertThat(keys.map { it.expression })
            .`as`("expression text is exactly what DuckDB stored")
            .isEqualTo(pg("SELECT expression FROM ducklake_sort_expression WHERE table_id = ${t.tableId} ORDER BY sort_key_index").map { it[0] })
        assertThat(catalog.getSortKeys(t.tableId, unsorted)).`as`("not sorted before the ALTER").isEmpty()

        duckExec("ALTER TABLE lake.$SCHEMA.srt RESET SORTED BY")
        val reset = catalog.currentSnapshotId
        assertThat(catalog.getSortKeys(t.tableId, reset)).isEmpty()
        assertThat(catalog.getSortKeys(t.tableId, sorted)).`as`("history retained").hasSize(3)
    }

    // ---------------------------------------------------------------- resolveSchemaVersionSnapshot

    @Test
    fun resolveSchemaVersionSnapshotIsPerTableAndBoundedBySnapshot() {
        catalog.createTable(SCHEMA, "sv_t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val s1 = catalog.currentSnapshotId
        val t = table("sv_t")
        catalog.createTable(SCHEMA, "sv_u", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val s2 = catalog.currentSnapshotId
        val u = table("sv_u")
        catalog.addColumn(u.tableId, TableColumnSpec.leaf("x", "int32", true))
        val s3 = catalog.currentSnapshotId
        catalog.addColumn(t.tableId, TableColumnSpec.leaf("y", "int32", true))
        val s4 = catalog.currentSnapshotId
        val versions = listOf(s1, s2, s3, s4).map { schemaVersionOf(it) }
        val v1 = versions[0]
        val v2 = versions[1]
        val v3 = versions[2]
        val v4 = versions[3]
        assertThat(versions).`as`("every DDL bumped the version").isEqualTo(listOf(v1, v1 + 1, v1 + 2, v1 + 3))

        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, v1, s4)).`as`("T created at s1").isEqualTo(s1)
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, v4, s4)).`as`("T altered at s4").isEqualTo(s4)
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, v4, s3)).`as`("bounded: v4 not yet introduced at s3").isNull()
        assertThat(catalog.resolveSchemaVersionSnapshot(u.tableId, v3, s4)).`as`("U altered at s3").isEqualTo(s3)
        assertThat(catalog.resolveSchemaVersionSnapshot(u.tableId, v2, s4)).`as`("U created at s2").isEqualTo(s2)
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, v3, s4))
            .`as`("v3 changed only U: no table-scoped row for T → falls back to the snapshot carrying v3")
            .isEqualTo(s3)
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, v4 + 50, s4)).`as`("unknown version").isNull()
        assertThat(pg("SELECT begin_snapshot FROM ducklake_schema_versions WHERE table_id = ${t.tableId} ORDER BY 1").map { it[0] })
            .`as`("one row per new/altered version of T (upstream InsertNewSchema)")
            .containsExactly(s1.toString(), s4.toString())
    }

    @Test
    fun resolveSchemaVersionSnapshotLocatesTheColumnTreeOfADuckDbInlinedTable() {
        duckExec(
            "CREATE TABLE lake.$SCHEMA.sv_inl (id INTEGER)",
            "INSERT INTO lake.$SCHEMA.sv_inl VALUES (1)",
            "ALTER TABLE lake.$SCHEMA.sv_inl ADD COLUMN v VARCHAR",
            "INSERT INTO lake.$SCHEMA.sv_inl VALUES (2, 'b')",
        )
        val now = catalog.currentSnapshotId
        val t = table("sv_inl")
        val altered = now - 1
        val infos = catalog.getInlinedDataInfos(t.tableId, now).sortedBy { it.schemaVersion }
        assertThat(infos).`as`("one inlined table per schema version").hasSize(2)
        val (oldSv, newSv) = infos.map { it.schemaVersion }
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, oldSv, now)).isEqualTo(t.beginSnapshot)
        assertThat(catalog.resolveSchemaVersionSnapshot(t.tableId, newSv, now)).isEqualTo(altered)
        // The resolved snapshot loads the column tree AS OF that version — the whole point of the method.
        assertThat(catalog.getTableColumns(t.tableId, catalog.resolveSchemaVersionSnapshot(t.tableId, oldSv, now)!!).map { it.columnName })
            .containsExactly("id")
        assertThat(catalog.getTableColumns(t.tableId, catalog.resolveSchemaVersionSnapshot(t.tableId, newSv, now)!!).map { it.columnName })
            .containsExactly("id", "v")
    }
}
