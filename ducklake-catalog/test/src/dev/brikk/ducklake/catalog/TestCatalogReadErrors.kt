package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Supplier

internal class TestCatalogReadErrors {
    @Nested inner class Local : Contract(Final11ReadRoute.LOCAL_DUCKDB)
    @Nested inner class NativeQuack : Contract(Final11ReadRoute.NATIVE_QUACK)
    @Nested inner class JavaQuack : Contract(Final11ReadRoute.JAVA_QUACK)

    abstract class Contract(private val route: Final11ReadRoute) {
        @Test fun genuinelyMissingCatalogMetadataStillHasInitializationDiagnostic() {
            Final11DuckDbBackendFixture(route).use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER)")
                f.metadata("DROP TABLE ducklake_metadata")
                f.reader().use { c ->
                    assertThatThrownBy { c.readSession(Supplier { c.currentSnapshotId }) }
                        .isInstanceOf(DucklakeCatalogNotInitializedException::class.java)
                }
            }
        }

        @Test fun legacyInlineMissingColumnsPropagateWhileExpectedTableAbsenceStaysEmpty() {
            // First producer call really creates 1.0; no format retag or migration is performed.
            Final11DuckDbBackendFixture(route, migrate = true).use { f ->
                f.native("CREATE TABLE lake.t(ducklake_value INTEGER); INSERT INTO lake.t VALUES(7)")
                val info = f.inlineInfo("t")
                val snapshot = f.snapshot()
                f.reader(enableFinal11Reads = false).use { c ->
                    assertThat(c.getSpecVersion()).isEqualTo("1.0")
                    val columns = c.getTableColumns(info.tableId, snapshot)
                    assertThat(c.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns)).containsExactly(listOf(7))
                    assertThat(c.readInlinedDataDecoded(info.tableId, info.schemaVersion + 99, snapshot, columns)).isEmpty()
                    assertThat(c.countInlinedRows(info.tableId, info.schemaVersion + 99, snapshot)).isZero()
                    f.metadata("ALTER TABLE ${info.tableName} RENAME COLUMN ducklake_value TO hidden_value")
                    assertThatThrownBy { c.readInlinedData(info.tableId, info.schemaVersion, snapshot, columns) }
                        .isInstanceOf(DataAccessException::class.java).hasMessageContaining("ducklake_value")
                    assertThatThrownBy { c.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns) }
                        .isInstanceOf(DataAccessException::class.java)
                    assertThatThrownBy { c.getInlinedChangesBetween(info.tableId, info.schemaVersion, 0, snapshot, columns.map { it.columnId }) }
                        .isInstanceOf(DataAccessException::class.java)
                    assertThatThrownBy { c.getInlinedChangesBetweenDecoded(info.tableId, info.schemaVersion, 0, snapshot, columns.map { it.columnId }) }
                        .isInstanceOf(DataAccessException::class.java)
                    assertThatThrownBy { c.readSession(Supplier {
                        c.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns)
                    }) }.isInstanceOf(DataAccessException::class.java)
                    f.metadata("ALTER TABLE ${info.tableName} RENAME COLUMN hidden_value TO ducklake_value")
                    assertThat(c.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns)).containsExactly(listOf(7))
                }
            }
        }

        @Test fun missingSnapshotColumnIsNotMisreportedAsUninitializedCatalog() {
            Final11DuckDbBackendFixture(route, migrate = true).use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER)")
                f.metadata("ALTER TABLE ducklake_snapshot RENAME COLUMN snapshot_id TO ducklake_hidden_id")
                f.reader().use { c ->
                    assertThatThrownBy { c.readSession(Supplier { c.currentSnapshotId }) }.isInstanceOf(DataAccessException::class.java)
                        .isNotInstanceOf(DucklakeCatalogNotInitializedException::class.java)
                }
            }
        }
    }

    @Test fun missingRelationReferencedByAnExistingInlineViewIsNotEmptyData() {
        Final11DuckDbBackendFixture(Final11ReadRoute.LOCAL_DUCKDB, migrate = true).use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
            val info = f.inlineInfo("t")
            val snapshot = f.snapshot()
            f.reader(enableFinal11Reads = false).use { c ->
                val columns = c.getTableColumns(info.tableId, snapshot)
                f.metadata("CREATE TABLE ducklake_unrelated AS SELECT * FROM ${info.tableName}")
                f.metadata("DROP TABLE ${info.tableName}")
                f.metadata("CREATE VIEW ${info.tableName} AS SELECT * FROM ducklake_unrelated")
                f.metadata("DROP TABLE ducklake_unrelated")
                assertThatThrownBy { c.readInlinedDataDecoded(info.tableId, info.schemaVersion, snapshot, columns) }
                    .isInstanceOf(DataAccessException::class.java).hasMessageContaining("ducklake_unrelated")
            }
        }
    }
}
