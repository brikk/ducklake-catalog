package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TestCatalogNamespaceRaces {
    @Test fun caseEquivalentTableAndViewCannotBothPublish() {
        Final11PostgresFixture(migrate = true).use { f ->
            f.native("SELECT 1")
            f.reader(enableFinal11Reads = false, maxConnections = 3).use { c ->
                val before = c.currentSnapshotId
                val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(c,
                    Runnable { c.createTable("main", "Race", listOf(TableColumnSpec.leaf("i", "int32", true)), null, null) },
                    Runnable { c.createView("main", "rACE", "SELECT 1", "duckdb", emptyList(), emptyMap()) })
                assertThat(result.loserException).isInstanceOf(DucklakeEntityAlreadyExistsException::class.java)
                assertThat(result.loserAttemptCount).isEqualTo(2)
                assertThat(c.currentSnapshotId).isEqualTo(before + 1)
                assertThat(c.getTable("main", "race", c.currentSnapshotId)).isNotNull()
                assertThat(c.getView("main", "race", c.currentSnapshotId)).isNull()
            }
        }
    }

    @Test fun columnNameCollisionOnRetryPreservesLogicalConflictAndOnlyOneColumn() {
        Final11PostgresFixture(migrate = true).use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            f.reader(enableFinal11Reads = false, maxConnections = 3).use { c ->
                val before = c.currentSnapshotId
                val table = c.getTable("main", "t", before)!!.tableId
                val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(c,
                    Runnable { c.addColumn(table, TableColumnSpec.leaf("Field", "int32", true)) },
                    Runnable { c.addColumn(table, TableColumnSpec.leaf("fIELD", "int32", true)) })
                assertThat(result.loserException).isInstanceOf(LogicalConflictException::class.java)
                assertThat(result.loserAttemptCount).isEqualTo(2)
                assertThat(c.currentSnapshotId).isEqualTo(before + 1)
                assertThat(c.getTableColumns(table, c.currentSnapshotId).map { it.columnName }).containsExactly("i", "Field")
            }
        }
    }
}
