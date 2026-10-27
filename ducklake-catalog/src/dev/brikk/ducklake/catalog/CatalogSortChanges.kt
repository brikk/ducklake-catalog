package dev.brikk.ducklake.catalog

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SORT_INFO
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_SORT_EXPRESSION
import dev.brikk.ducklake.catalog.schema.tables.records.DucklakeSortExpressionRecord
import org.jooq.impl.DSL

/** Sort expressions are immutable per sort ID: a rename publishes a new version in the same transaction. */
internal class CatalogSortChanges(private val tx: DucklakeWriteTransaction, private val metadata: MetadataQuery, private val tableId: Long) {
    private val ctx = tx.dsl()
    private data class Active(val id: Long, val begin: Long, val rows: List<DucklakeSortExpressionRecord>)

    fun requireUnreferenced(column: String) {
        val active = load() ?: return
        if (active.rows.any { rewrite(it, column, column).referenced }) {
            throw DucklakeInvalidOperationException("Cannot change column '$column': the table is sorted by this column")
        }
    }

    fun rename(oldName: String, newName: String) {
        val active = load() ?: return
        val plans = active.rows.map { rewrite(it, oldName, newName) }
        if (plans.none { it.referenced }) return
        val id = tx.allocateCatalogId()
        val info = DUCKLAKE_SORT_INFO
        val updated = metadata.execute(ctx, ctx.update(info).set(info.END_SNAPSHOT, tx.getNewSnapshotId())
            .where(info.SORT_ID.eq(active.id)).and(info.TABLE_ID.eq(tableId)).and(info.BEGIN_SNAPSHOT.eq(active.begin)))
        if (updated != 1) corrupt("Sort version changed during rename")
        ctx.insertInto(info).set(info.SORT_ID, id).set(info.TABLE_ID, tableId).set(info.BEGIN_SNAPSHOT, tx.getNewSnapshotId()).execute()
        val rows = active.rows.mapIndexed { index, previous ->
            ctx.newRecord(DUCKLAKE_SORT_EXPRESSION).apply {
                from(previous)
                setSortId(id)
                setExpression(plans[index].expression)
            }
        }
        ctx.batchInsert(rows).execute()
    }

    private fun rewrite(row: DucklakeSortExpressionRecord, oldName: String, newName: String): CatalogSortExpressions.Rewrite =
        CatalogSortExpressions.rename(row.expression ?: corrupt("Missing sort expression"), row.dialect ?: corrupt("Missing sort dialect"), oldName, newName)

    private fun load(): Active? {
        val info = DUCKLAKE_SORT_INFO
        val snapshot = tx.getCurrentSnapshotId()
        val versions = metadata.fetch(ctx, ctx.selectFrom(info).where(info.TABLE_ID.eq(tableId))
            .and(info.BEGIN_SNAPSHOT.le(snapshot)).and(info.END_SNAPSHOT.isNull.or(info.END_SNAPSHOT.gt(snapshot))).limit(2))
        if (versions.size > 1) corrupt("Ambiguous active sort versions")
        val version = versions.singleOrNull() ?: return null
        val id = version.sortId ?: corrupt("Missing sort identity")
        val begin = version.beginSnapshot ?: corrupt("Missing sort begin snapshot")
        val e = DUCKLAKE_SORT_EXPRESSION
        val fields = e.fields().map { field ->
            if (field.dataType.isString) DSL.substring(field.coerce(String::class.java), 1, CatalogSortExpressions.MAX_TEXT + 1).`as`(field.name)
            else field
        }
        val rows = metadata.fetch(ctx, ctx.select(fields).from(e).where(e.SORT_ID.eq(id)).orderBy(e.SORT_KEY_INDEX).limit(4097)).map { row ->
            if (row.intoArray().any { it is String && it.length > CatalogSortExpressions.MAX_TEXT }) corrupt("Oversize sort metadata")
            row.into(e)
        }
        if (rows.size > 4096) throw DucklakeInvalidOperationException("Sort key count exceeds the rewrite budget")
        if (rows.any { it.tableId != tableId || it.sortKeyIndex == null || it.sortKeyIndex!! < 0 }) corrupt("Invalid sort key ownership/index")
        if (rows.map { it.sortKeyIndex }.distinct().size != rows.size) corrupt("Duplicate sort key index")
        return Active(id, begin, rows)
    }

    private fun corrupt(message: String): Nothing = throw DucklakeCatalogCorruptionException(message)
}
