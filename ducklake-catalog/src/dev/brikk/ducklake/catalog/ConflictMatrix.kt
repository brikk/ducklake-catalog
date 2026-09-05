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

/**
 * Change-vs-change conflict check between this transaction's
 * [WriteChange] list and the [InterveningChanges] committed by
 * other transactions in the snapshot range we're racing against.
 *
 * Port of upstream `DuckLakeTransactionState::CheckForConflicts` (ducklake v1.5,
 * `src/storage/ducklake_transaction_state.cpp`, the loops following the created-table name
 * check). Keep the upstream entries in sync; the deliberately stricter flush checks are
 * documented on [checkFlushedInlinedData]. Flush payloads are materialized before the metadata
 * transaction, and this library records TRUNCATE's inlined deletions as `deleted_from_table`.
 *
 * Complements [LogicalConflictCheck] (which is state-based, per-call args):
 * this check catches dueling-name commits (two concurrent
 * `createSchema(name)`, etc.) that the state-based check misses because
 * catalog rows are snapshot-versioned and there's no DDL UNIQUE constraint
 * on `(schema_id, name)`.
 *
 * Conflicts thrown here are non-retryable — the in-flight payload's
 * stale references would feed every retry, so re-running burns the retry
 * budget on a guaranteed-fail. Upstream achieves the same via its
 * `can_retry = false` flag; we use [LogicalConflictException.retryable] returning `false`.
 */
internal object ConflictMatrix {
    /**
     * Run the matrix. Throws [LogicalConflictException] on the first
     * mismatch found.
     *
     * @param myChanges the in-flight transaction's recorded changes
     * @param other     changes committed in the snapshot range
     *                  `(transactionStartSnapshotId, currentSnapshotId]`
     */
    fun check(myChanges: List<WriteChange>, other: InterveningChanges) {
        for (change in myChanges) {
            checkOne(change, other)
        }
        checkUnknownChanges(myChanges, other)
    }

    /**
     * An intervening commit recorded a change kind this library does not know (newer DuckLake, or a
     * non-conformant writer). We cannot tell which table it touched or how, so any DATA change of
     * ours might conflict with it — abort those conservatively. Pure catalog DDL (schemas, tables,
     * views, columns, comments) is unaffected: every data-vs-DDL row of the matrix is keyed on the
     * altered/dropped side, which known kinds already cover.
     */
    private fun checkUnknownChanges(myChanges: List<WriteChange>, other: InterveningChanges) {
        if (other.unknownChanges.isEmpty()) {
            return
        }
        val dataChange = myChanges.firstOrNull {
            it is WriteChange.InsertedIntoTable || it is WriteChange.DeletedFromTable ||
                it is WriteChange.FlushedInlinedData || it is WriteChange.RewriteDelete || it is WriteChange.MergeAdjacent
        } ?: return
        throw conflict(
            "commit data change $dataChange",
            "another transaction recorded change(s) this client does not understand: " +
                other.unknownChanges.distinct().joinToString(", ") +
                " (possibly a newer DuckLake writer). The change may affect the same table",
        )
    }

    private fun checkOne(change: WriteChange, other: InterveningChanges) {
        when (change) {
            is WriteChange.DroppedTable -> checkDroppedTable(change, other)
            is WriteChange.DroppedView -> checkDroppedView(change, other)
            is WriteChange.DroppedSchema -> checkDroppedSchema(change, other)
            is WriteChange.CreatedSchema -> checkCreatedSchema(change, other)
            is WriteChange.CreatedTable -> checkCreatedTable(change, other)
            is WriteChange.CreatedView -> checkCreatedView(change, other)
            is WriteChange.AlteredTable -> checkAlteredTable(change, other)
            is WriteChange.AlteredView -> checkAlteredView(change, other)
            else -> checkDataChange(change, other)
        }
    }

    private fun checkDataChange(change: WriteChange, other: InterveningChanges) {
        when (change) {
            is WriteChange.InsertedIntoTable -> checkInsertedIntoTable(change, other)
            is WriteChange.DeletedFromTable -> checkDeletedFromTable(change, other)
            is WriteChange.FlushedInlinedData -> checkFlushedInlinedData(change, other)
            is WriteChange.RewriteDelete -> checkCompaction(change.tableId, other)
            is WriteChange.MergeAdjacent -> checkCompaction(change.tableId, other)
            else -> throw IllegalStateException("Unhandled change kind in ConflictMatrix: $change")
        }
    }

    // upstream: `for (table_id : changes.dropped_tables)`
    private fun checkDroppedTable(c: WriteChange.DroppedTable, other: InterveningChanges) {
        conflictIfMember(
            c.tableId, other.droppedTables,
            "drop table", "dropped it already",
        )
    }

    // upstream: `for (view_id : changes.dropped_views)`
    private fun checkDroppedView(c: WriteChange.DroppedView, other: InterveningChanges) {
        conflictIfMember(
            c.viewId, other.droppedViews,
            "drop view", "dropped it already",
        )
    }

    // upstream: `for (schema_id : changes.dropped_schemas)` + created-entry-in-schema check
    private fun checkDroppedSchema(c: WriteChange.DroppedSchema, other: InterveningChanges) {
        conflictIfMember(
            c.schemaId, other.droppedSchemas,
            "drop schema", "dropped it already",
        )
        // Upstream: another transaction created an entry (table/view) in this schema.
        val nameMap = other.createdTablesByName[c.schemaName]
        if (!nameMap.isNullOrEmpty()) {
            val firstName = nameMap.keys.first()
            val kind = nameMap[firstName]
            throw conflict(
                "drop schema \"${c.schemaName}\"",
                "another transaction created $kind \"$firstName\" in this schema",
            )
        }
    }

    // upstream: `for (schema : changes.created_schemas)`
    private fun checkCreatedSchema(c: WriteChange.CreatedSchema, other: InterveningChanges) {
        if (c.schemaName in other.createdSchemas) {
            throw conflict(
                "create schema \"${c.schemaName}\"",
                "another transaction created a schema with this name already",
            )
        }
    }

    // upstream: the created_tables name-collision loop
    private fun checkCreatedTable(c: WriteChange.CreatedTable, other: InterveningChanges) {
        // Schema this table is being created in must not have been dropped.
        if (c.schemaId in other.droppedSchemas) {
            throw conflict(
                "create table \"${c.tableName}\" in schema \"${c.schemaName}\"",
                "another transaction dropped this schema",
            )
        }
        // No other transaction created an entry with this (schema, name) pair.
        val existingKind = other.createdTablesByName[c.schemaName]?.get(c.tableName)
        if (existingKind != null) {
            throw conflict(
                "create table \"${c.tableName}\" in schema \"${c.schemaName}\"",
                "this $existingKind has been created by another transaction already",
            )
        }
    }

    // Same shape as checkCreatedTable, for views.
    private fun checkCreatedView(c: WriteChange.CreatedView, other: InterveningChanges) {
        if (c.schemaId in other.droppedSchemas) {
            throw conflict(
                "create view \"${c.viewName}\" in schema \"${c.schemaName}\"",
                "another transaction dropped this schema",
            )
        }
        val existingKind = other.createdTablesByName[c.schemaName]?.get(c.viewName)
        if (existingKind != null) {
            throw conflict(
                "create view \"${c.viewName}\" in schema \"${c.schemaName}\"",
                "this $existingKind has been created by another transaction already",
            )
        }
    }

    // upstream: `for (table_id : changes.tables_inserted_into)` — v1.5 also conflicts an insert
    // with a concurrent DELETE (file or inlined) on the same table.
    private fun checkInsertedIntoTable(c: WriteChange.InsertedIntoTable, other: InterveningChanges) {
        conflictIfMember(c.tableId, other.droppedTables, "insert into table", "dropped it")
        conflictIfMember(c.tableId, other.alteredTables, "insert into table", "altered it")
        conflictIfMember(c.tableId, other.tablesDeletedFrom, "insert into table", "deleted from it")
        conflictIfMember(c.tableId, other.tablesDeletedInlined, "insert into table", "deleted inlined data from it")
    }

    // upstream: `for (table_id : changes.tables_deleted_from)` — v1.5 also conflicts a delete with
    // a concurrent INSERT (file or inlined) on the same table. The delete-vs-delete FILE overlap
    // check (upstream's GetFilesDeletedOrDroppedAfterSnapshot block) lives in
    // JdbcDucklakeCatalog.checkDeleteFileOverlap, which needs catalog access.
    private fun checkDeletedFromTable(c: WriteChange.DeletedFromTable, other: InterveningChanges) {
        conflictIfMember(c.tableId, other.droppedTables, "delete from table", "dropped it")
        conflictIfMember(c.tableId, other.alteredTables, "delete from table", "altered it")
        conflictIfMember(c.tableId, other.tablesMergeAdjacent, "delete from table", "compacted it")
        conflictIfMember(c.tableId, other.tablesRewriteDelete, "delete from table", "compacted it")
        conflictIfMember(c.tableId, other.insertedTables, "delete from table", "inserted into it")
        conflictIfMember(c.tableId, other.tablesInsertedInlined, "delete from table", "inserted into it")
    }

    // upstream: `for (table_id : changes.tables_merge_adjacent)` and `tables_rewrite_delete` — the
    // two compaction kinds share one row: conflicts with drop, with any DELETE on the table (a
    // delete file added to a source after the compaction read it would be lost), and with another
    // compaction. NOT with inserts or alters — files carry field ids and a new file is unaffected.
    private fun checkCompaction(tableId: Long, other: InterveningChanges) {
        conflictIfMember(tableId, other.droppedTables, "compact table", "dropped it")
        conflictIfMember(tableId, other.tablesDeletedFrom, "compact table", "deleted from it")
        conflictIfMember(tableId, other.tablesMergeAdjacent, "compact table", "compacted it")
        conflictIfMember(tableId, other.tablesRewriteDelete, "compact table", "compacted it")
    }

    // upstream: `for (table_id : changes.altered_tables)`
    private fun checkAlteredTable(c: WriteChange.AlteredTable, other: InterveningChanges) {
        conflictIfMember(
            c.tableId, other.droppedTables,
            "alter table", "dropped it",
        )
        conflictIfMember(
            c.tableId, other.alteredTables,
            "alter table", "altered it",
        )
    }

    // Upstream checks dropped/deleted_inlined/flushed_inlined. Also guard pre-materialized
    // payloads against ALTER and inlined INSERT, and against deleted_from_table: our TRUNCATE
    // end-snapshots inlined rows but records that kind. Accepting an older flush would resurrect
    // those rows and erase their deletion history. This also protects already-committed TRUNCATEs.
    private fun checkFlushedInlinedData(c: WriteChange.FlushedInlinedData, other: InterveningChanges) {
        conflictIfMember(c.tableId, other.droppedTables, "flush inlined data", "dropped it")
        conflictIfMember(c.tableId, other.alteredTables, "flush inlined data", "altered it")
        conflictIfMember(c.tableId, other.tablesDeletedFrom, "flush inlined data", "deleted from it")
        conflictIfMember(c.tableId, other.tablesFlushedInlined, "flush inlined data", "flushed it")
        conflictIfMember(c.tableId, other.tablesInsertedInlined, "flush inlined data", "inlined-inserted into it")
        conflictIfMember(c.tableId, other.tablesDeletedInlined, "flush inlined data", "inlined-deleted from it")
    }

    // upstream: `for (view_id : changes.altered_views)`
    private fun checkAlteredView(c: WriteChange.AlteredView, other: InterveningChanges) {
        conflictIfMember(
            c.viewId, other.alteredViews,
            "alter view", "altered it",
        )
    }

    private fun conflictIfMember(id: Long, otherSet: Set<Long>, myAction: String, theirAction: String) {
        if (id in otherSet) {
            throw conflict("$myAction (id=$id)", "another transaction $theirAction")
        }
    }

    private fun conflict(myAction: String, theirAction: String): LogicalConflictException =
        LogicalConflictException(
            "Transaction conflict - attempting to $myAction - but $theirAction. " +
                "This conflict is not retried (re-running with the same payload " +
                "would fail identically).",
        )
}
