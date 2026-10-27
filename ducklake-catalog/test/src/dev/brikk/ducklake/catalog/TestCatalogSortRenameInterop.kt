package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TestCatalogSortRenameInterop {
    @Test fun localDuckDbCanInsertAndTimeTravelAfterLibrarySortedRename() {
        verify { LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    }
    @Test fun postgresDuckDbCanInsertAndTimeTravelAfterLibrarySortedRename() {
        verify { LegacyWriteFixture.Postgres() }
    }

    private fun verify(fixture: () -> LegacyWriteFixture) {
        fixture().use { f ->
            f.seed()
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(x INTEGER, marker VARCHAR); " +
                "INSERT INTO lake.t VALUES(2,'before'); ALTER TABLE lake.t SET SORTED BY (abs(x) + length('x') DESC NULLS FIRST, marker)")
            val before = f.reader().use { c ->
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!.tableId
                val column = c.getTableColumns(table, snapshot).single { it.columnName == "x" }
                c.renameColumn(table, column.columnId, "renamed")
                snapshot
            }
            f.native("INSERT INTO lake.t VALUES(1,'after')")
            assertThat(f.native("SELECT renamed,marker FROM lake.t ORDER BY renamed"))
                .containsExactly(listOf("1", "after"), listOf("2", "before"))
            assertThat(f.native("SELECT x,marker FROM lake.t AT (VERSION => $before)"))
                .containsExactly(listOf("2", "before"))
        }
    }
}
