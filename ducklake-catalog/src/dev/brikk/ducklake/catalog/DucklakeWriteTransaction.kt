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

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SCHEMA
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_TABLE
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.sql.Connection
import java.sql.SQLException

/**
 * Transactional context for write operations against the DuckLake catalog.
 * Handles snapshot state, ID allocation, and change tracking.
 *
 * Instances are created by [JdbcDucklakeCatalog.executeWriteTransaction]
 * and must not be used outside the callback scope.
 */
internal class DucklakeWriteTransaction(
    private val connection: Connection,
    private val dsl: DSLContext,
    private val currentSnapshotId: Long,
    private var schemaVersion: Long,
    private var nextCatalogId: Long,
    private var nextFileId: Long,
) {
    private val newSnapshotId: Long = currentSnapshotId + 1
    private val initialSchemaVersion: Long = schemaVersion
    private val schemaVersionTableIds: MutableSet<Long> = linkedSetOf()
    private val changes: MutableList<WriteChange> = mutableListOf()

    /**
     * Returns the connection-scoped [DSLContext] for building jOOQ statements that
     * must run inside this write transaction. All mutations go through this context so
     * they inherit the transaction's [Connection] and commit/rollback semantics.
     */
    fun dsl(): DSLContext = dsl

    fun getCurrentSnapshotId(): Long = currentSnapshotId

    fun getNewSnapshotId(): Long = newSnapshotId

    fun getSchemaVersion(): Long = schemaVersion

    /**
     * Allocates a catalog ID for a new object (view, table, schema, etc.).
     * Returns the current value and advances for the next caller.
     */
    fun allocateCatalogId(): Long = nextCatalogId++

    /**
     * Allocates a file ID for a new data file.
     * Returns the current value and advances for the next caller.
     */
    fun allocateFileId(): Long = nextFileId++

    /**
     * Records a typed snapshot-change entry. Both the
     * `ducklake_snapshot_changes.changes_made` text serializer and the
     * logical conflict check read this list.
     */
    fun recordChange(change: WriteChange) {
        changes.add(change)
    }

    /**
     * Resolves a schema name to its schema_id within the current snapshot.
     */
    @Throws(SQLException::class)
    fun resolveSchemaId(schemaName: String): Long {
        val schema = DUCKLAKE_SCHEMA.`as`("write_schema")
        return dsl.select(schema.SCHEMA_ID).from(schema)
            .where(schema.SCHEMA_NAME.lower().eq(DSL.lower(DSL.`val`(schemaName))))
            .and(schema.BEGIN_SNAPSHOT.le(currentSnapshotId))
            .and(schema.END_SNAPSHOT.isNull.or(schema.END_SNAPSHOT.gt(currentSnapshotId)))
            .limit(2).fetchOne(schema.SCHEMA_ID) ?: throw DucklakeEntityNotFoundException("schema", schemaName)
    }

    /**
     * Resolves a table name to its table_id within a schema at the current snapshot.
     */
    @Throws(SQLException::class)
    fun resolveTableId(schemaId: Long, tableName: String): Long {
        val table = DUCKLAKE_TABLE.`as`("write_table")
        return dsl.select(table.TABLE_ID).from(table)
            .where(table.SCHEMA_ID.eq(schemaId)).and(table.TABLE_NAME.lower().eq(DSL.lower(DSL.`val`(tableName))))
            .and(table.BEGIN_SNAPSHOT.le(currentSnapshotId))
            .and(table.END_SNAPSHOT.isNull.or(table.END_SNAPSHOT.gt(currentSnapshotId)))
            .limit(2).fetchOne(table.TABLE_ID) ?: throw DucklakeEntityNotFoundException("table", tableName)
    }

    /**
     * Checks whether a schema has any active tables at the current snapshot.
     */
    @Throws(SQLException::class)
    fun hasTablesInSchema(schemaId: Long): Boolean = hasActiveRowsInSchema("ducklake_table", schemaId)

    /**
     * Checks whether a schema has any active views at the current snapshot.
     */
    @Throws(SQLException::class)
    fun hasViewsInSchema(schemaId: Long): Boolean = hasActiveRowsInSchema("ducklake_view", schemaId)

    /**
     * Checks whether a schema has any active macros at the current snapshot. This library never
     * writes macros, but DuckDB does (`CREATE MACRO` in a DuckLake schema), and upstream refuses to
     * drop a schema that still owns one (`DuckLakeSchemaEntry::TryDropSchema`).
     */
    @Throws(SQLException::class)
    fun hasMacrosInSchema(schemaId: Long): Boolean = hasActiveRowsInSchema("ducklake_macro", schemaId)

    /**
     * Names of the object kinds still active in the schema at the current snapshot, in upstream's
     * order (tables, views, macros). Empty means the schema can be dropped without orphaning
     * anything — upstream's catalog loader throws on a view/table whose schema row is gone.
     */
    @Throws(SQLException::class)
    fun activeObjectKindsInSchema(schemaId: Long): List<String> =
        buildList {
            if (hasTablesInSchema(schemaId)) add("tables")
            if (hasViewsInSchema(schemaId)) add("views")
            if (hasMacrosInSchema(schemaId)) add("macros")
        }

    /**
     * True when [metadataTable] (a fixed `ducklake_*` name — never user input) has a row for
     * [schemaId] that is active at the current snapshot. All three schema-scoped catalog tables
     * share the `schema_id` / `begin_snapshot` / `end_snapshot` column shape.
     */
    @Throws(SQLException::class)
    private fun hasActiveRowsInSchema(metadataTable: String, schemaId: Long): Boolean {
        val schema = DSL.field(DSL.name("schema_id"), Long::class.javaObjectType)
        val begin = DSL.field(DSL.name("begin_snapshot"), Long::class.javaObjectType)
        val end = DSL.field(DSL.name("end_snapshot"), Long::class.javaObjectType)
        return dsl.fetchExists(dsl.selectOne().from(DSL.table(DSL.name(metadataTable)))
            .where(schema.eq(schemaId)).and(begin.le(currentSnapshotId))
            .and(end.isNull.or(end.gt(currentSnapshotId))).limit(1))
    }

    /**
     * Increments the schema version. Called for DDL operations that change
     * the table/column structure (create table, drop table, alter table, etc.).
     *
     * @param tableId the table being modified (used in ducklake_schema_versions)
     */
    /**
     * Bumps the schema version for DDL that changes a table's columns / identity — CREATE TABLE and
     * every ALTER (add/drop/rename/retype column or field, rename, comment). The table is recorded
     * for `ducklake_schema_versions`: upstream writes one row per NEW or ALTERED table
     * (`InsertNewSchema`), which is what `getSnapshotIdForSchemaVersion` resolves per table.
     * Can be called for several tables in one transaction (e.g. renameSchema).
     */
    fun incrementSchemaVersion(tableId: Long) {
        schemaVersionTableIds.add(tableId)
        incrementSchemaVersion()
    }

    private val columnSchemaChangedTableIds: MutableSet<Long> = linkedSetOf()

    /**
     * Records that this transaction changed [tableId]'s COLUMN definitions (add/drop/rename/retype a
     * column or nested field) — upstream's `column_schema_change`. Also bumps the schema version.
     * The commit then registers a new physical inlined-data table for the new schema version if the
     * table already has inlined-data tables (upstream `WriteNewInlinedTables`).
     */
    fun recordColumnSchemaChange(tableId: Long) {
        columnSchemaChangedTableIds.add(tableId)
        incrementSchemaVersion(tableId)
    }

    fun getColumnSchemaChangedTableIds(): Set<Long> = columnSchemaChangedTableIds

    /**
     * Bumps the schema version for DDL that does not alter a table's own definition: create/drop
     * view or schema, DROP TABLE. Upstream `SchemaChangesMade()` flips for these too (a DuckDB
     * reader caches the catalog by `schema_version`), but it writes NO `ducklake_schema_versions`
     * row for them — that table is per-table history of new/altered tables only.
     */
    fun incrementSchemaVersion() {
        if (schemaVersion == initialSchemaVersion) {
            schemaVersion++
        }
    }

    /** Tables whose definition this transaction created or altered (for `ducklake_schema_versions`). */
    fun getSchemaVersionTableIds(): Set<Long> = schemaVersionTableIds

    /**
     * Returns the JDBC connection for preparing mutation statements.
     * The caller must not commit, rollback, or close this connection.
     */
    fun getConnection(): Connection = connection

    // Package-private accessors for the framework to read final state
    // (left public — see class-level visibility note)

    fun getFinalNextCatalogId(): Long = nextCatalogId

    fun getFinalNextFileId(): Long = nextFileId

    fun getChanges(): List<WriteChange> = changes
}
