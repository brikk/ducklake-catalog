package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Initialization is an adapter-visible boundary, shared by CREATE and file registration. */
internal class TestCatalogWriteInitialization {
    @Test fun createOnBareDatabase(@TempDir directory: Path) = bare(directory) { it.createSchema("new_schema") }
    @Test fun insertOnBareDatabase(@TempDir directory: Path) = bare(directory) {
        it.commitInsert(123, listOf(DucklakeWriteFragment("new.parquet", 100L, 0L, 1L, emptyList())))
    }

    private fun bare(directory: Path, action: (JdbcDucklakeCatalog) -> Unit) {
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = "jdbc:duckdb:${directory.resolve("bare.db")}"
            dataPath = directory.toString()
            maxCatalogConnections = 1
        }).use { c ->
            assertThatThrownBy { action(c) }.isInstanceOf(DucklakeCatalogNotInitializedException::class.java)
                .hasMessageContaining("has no metadata schema").hasMessageContaining("Initialize the catalog once")
        }
    }

    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun createSchemaWithoutSnapshotTable() = missing("snapshot") { it.createSchema("new_schema") }
        @Test fun createTableWithoutSnapshotTable() = missing("snapshot") { createTable(it) }
        @Test fun insertWithoutMetadataTable() = missing("metadata") { it.commitInsert(123, fragments()) }
        @Test fun addFilesWithoutMetadataTable() = missing("metadata") { it.commitAddFiles(123, fragments()) }
        @Test fun createSchemaWithoutMetadataTable() = missing("metadata") { it.createSchema("new_schema") }
        @Test fun createSchemaWithoutSnapshots() = empty { it.createSchema("new_schema") }
        @Test fun createTableWithoutSnapshots() = empty { createTable(it) }
        @Test fun insertWithoutSnapshots() = empty { it.commitInsert(123, fragments()) }
        @Test fun addFilesWithoutSnapshots() = empty { it.commitAddFiles(123, fragments()) }
        @Test fun emptyInsertWithoutSnapshots() = empty { it.commitInsert(123, emptyList()) }
        @Test fun emptyAddFilesWithoutSnapshots() = empty { it.commitAddFiles(123, emptyList()) }
        @Test fun missingSnapshotColumnIsNotInitializationFailureForCreate() = missingColumn { it.createSchema("new_schema") }
        @Test fun missingSnapshotColumnIsNotInitializationFailureForInsert() = missingColumn { it.commitInsert(123, fragments()) }

        private fun missing(table: String, action: (JdbcDucklakeCatalog) -> Unit): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                f.metadata("DROP TABLE ducklake_$table")
                assertThatThrownBy { action(c) }
                    .isInstanceOf(DucklakeCatalogNotInitializedException::class.java)
                    .hasMessageContaining("Initialize the catalog once")
            }
        }

        private fun empty(action: (JdbcDucklakeCatalog) -> Unit): Unit = fixture().use { f ->
            f.seed()
            // Keep a copy so the reader can recover on the SAME instance after a rejected call.
            f.reader().use { c ->
                f.metadata("CREATE TABLE saved_snapshot AS SELECT * FROM ducklake_snapshot")
                f.metadata("DELETE FROM ducklake_snapshot")
                assertThatThrownBy { action(c) }
                    .isInstanceOf(DucklakeCatalogNotInitializedException::class.java)
                    .hasMessageContaining("ducklake_snapshot has no rows")
                    .hasMessageContaining("Initialize the catalog once")
                assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeCatalogNotInitializedException::class.java)
                f.metadata("INSERT INTO ducklake_snapshot SELECT * FROM saved_snapshot")
                assertThat(c.currentSnapshotId).isZero()
                assertThat(c.getSchema("new_schema", 0)).isNull()
                assertThat(c.getTable("main", "new_table", 0)).isNull()
                c.createSchema("recovered")
                assertThat(c.getSchema("recovered", c.currentSnapshotId)).isNotNull()
            }
        }

        private fun missingColumn(action: (JdbcDucklakeCatalog) -> Unit) = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                val before = c.currentSnapshotId
                f.metadata("ALTER TABLE ducklake_snapshot RENAME COLUMN snapshot_id TO hidden_id")
                val failure = catchThrowable { action(c) }
                assertThat(failure).isNotNull()
                assertThat(generateSequence(failure) { it.cause }.toList())
                    .noneMatch { it is DucklakeCatalogNotInitializedException }
                f.metadata("ALTER TABLE ducklake_snapshot RENAME COLUMN hidden_id TO snapshot_id")
                assertThat(c.currentSnapshotId).isEqualTo(before)
                c.createSchema("recovered")
            }
        }

        private fun createTable(c: JdbcDucklakeCatalog) =
            c.createTable("main", "new_table", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)

        private fun fragments() = listOf(DucklakeWriteFragment("new.parquet", 100L, 0L, 1L, emptyList()))
    }
}
