package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Actual native 1.0 Parquet production, not just authored metadata or comparator self-consistency. */
internal class TestTypedPruningNativeFiles {
    @Test fun localNativeFilesRetainMatchingFloatAndEndOfDayRows() {
        verify { LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    }

    @Test fun postgresNativeFilesRetainMatchingFloatAndEndOfDayRows() {
        verify { LegacyWriteFixture.Postgres() }
    }

    private fun verify(fixture: () -> LegacyWriteFixture) = fixture().use { f ->
        f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(id INTEGER,f FLOAT,t TIME); " +
            "INSERT INTO lake.t VALUES(1,0.1,TIME '24:00:00')")
        val first = f.reader().use { c -> c.getDataFiles(c.getTable("main", "t", c.currentSnapshotId)!!.tableId, c.currentSnapshotId).single().dataFileId }
        f.native("INSERT INTO lake.t VALUES(2,0.2,TIME '12:00:00')")
        assertThat(f.native("SELECT id FROM lake.t WHERE f=CAST('0.10000000149011612' AS FLOAT) AND t>=TIME '23:00:00'"))
            .containsExactly(listOf("1"))
        f.reader().use { c ->
            val snapshot = c.currentSnapshotId
            val table = c.getTable("main", "t", snapshot)!!.tableId
            val columns = c.getTableColumns(table, snapshot).associateBy { it.columnName }
            assertThat(c.getDataFiles(table, snapshot)).hasSize(2)
            assertThat(c.findDataFileIdsInRange(table, snapshot, ColumnRangePredicate(columns.getValue("f").columnId,
                "0.10000000149011612", "0.10000000149011612"))).containsExactly(first)
            assertThat(c.findDataFileIdsInRange(table, snapshot, ColumnRangePredicate(columns.getValue("t").columnId,
                "23:00:00", null))).containsExactly(first)
        }
    }
}
