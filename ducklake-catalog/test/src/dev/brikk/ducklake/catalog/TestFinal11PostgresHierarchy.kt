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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.function.Supplier

class TestFinal11PostgresHierarchy {
    private fun <T> read(f: Final11PostgresFixture, action: (JdbcDucklakeCatalog) -> T): T =
        f.reader().use { c -> c.readSession(Supplier { action(c) }) }

    @Test
    fun nativeNestedNamesQuotedDotsAndHistoricReusePreserveStableIdentity() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE SCHEMA lake.a; CREATE SCHEMA lake.a.child; CREATE SCHEMA lake.\"a.child\"; " +
                "CREATE TABLE lake.a.child.\"t.q\" AS SELECT 42 i; CREATE TABLE lake.\"a.child\".t AS SELECT 7 i")
            val old = f.snapshot()
            val original = read(f) { c ->
                val h = c.readHierarchy()
                val nested = h.findSchema(listOf("A", "CHILD"), old)!!
                val literal = h.findSchema(listOf("a.child"), old)!!
                assertThat(nested.schemaIds).hasSize(2)
                assertThat(literal.schemaIds).hasSize(1)
                assertThat(nested.metadata.schema.schemaId).isNotEqualTo(literal.metadata.schema.schemaId)
                assertThat(c.readMetadata().listSchemaMetadata(old).single { it.schema.schemaId == nested.metadata.schema.schemaId }.parentSchemaId)
                    .isEqualTo(nested.schemaIds.first())
                val table = h.findTable(listOf("a", "child"), "T.Q", old)!!
                assertThat(h.listTables(nested.metadata.schema.schemaId, old)).containsExactly(table)
                assertThatThrownBy { c.listSchemas(old) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("nested-schema history")
                table
            }
            f.native("ALTER TABLE lake.a.child.\"t.q\" RENAME TO renamed")
            val renamed = f.snapshot()
            f.native("DROP SCHEMA lake.a CASCADE; CREATE SCHEMA lake.a; CREATE SCHEMA lake.a.child; CREATE TABLE lake.a.child.\"t.q\" AS SELECT 9 i")
            read(f) { c ->
                val h = c.readHierarchy()
                val historic = h.getTableById(original.table.tableId, old)!!
                assertThat(historic.table.tableName).isEqualTo("t.q")
                assertThat(historic.table.endSnapshot).isEqualTo(renamed)
                assertThat(historic.schema.names).containsExactly("a", "child")
                assertThat(h.getTableById(original.table.tableId, renamed)!!.table.tableName).isEqualTo("renamed")
                assertThat(h.getTableById(original.table.tableId, f.snapshot())).isNull()
                assertThat(h.findTable(listOf("a", "child"), "t.q", f.snapshot())!!.table.tableId).isNotEqualTo(original.table.tableId)
            }
        }
    }

    @Test
    fun retainedNativeFilesResolveAtOriginalOwnerEvenAfterDropAndOriginSnapshotExpiry() {
        Final11PostgresFixture().use { f ->
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',false); " +
                "CREATE SCHEMA lake.a; CREATE SCHEMA lake.a.child; CREATE TABLE lake.a.child.t AS SELECT i FROM range(1000) t(i); " +
                "DELETE FROM lake.a.child.t WHERE i<100")
            val native = f.nativeRows("SELECT data_file,delete_file FROM ducklake_list_files('lake','t',schema=>'a.child')").single()
            val original = read(f) { c ->
                listOf(c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 10).single(),
                    c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 10).single())
            }
            assertThat(original.map { it.resolvedPath }).containsExactly(native[0], native[1])
            f.native("ALTER TABLE lake.a.child.t RENAME TO renamed")
            f.metadata("UPDATE ducklake_table SET path='s3://later/',path_is_relative=FALSE WHERE end_snapshot IS NULL")
            f.native("DROP SCHEMA lake.a CASCADE")
            f.metadata("DELETE FROM ducklake_snapshot WHERE snapshot_id IN (${original.joinToString(",") { it.beginSnapshot.toString() }})")
            read(f) { c ->
                for (file in original) {
                    val retained = c.readHierarchy().listRetainedFiles(file.kind, null, 10).single()
                    assertThat(retained.resolvedPath).isEqualTo(file.resolvedPath)
                    assertThat(retained.owner.table.tableName).isEqualTo("t")
                    assertThat(retained.owner.schema.schemaIds).isEqualTo(file.owner.schema.schemaIds)
                }
            }
        }
    }

    @Test
    fun scheduledReferencesUseExactByteCursorsUnderNondeterministicPostgresCollation() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            f.metadata("CREATE COLLATION loose(provider=icu,locale='und-u-ks-level1',deterministic=false); " +
                "ALTER TABLE ducklake_files_scheduled_for_deletion ALTER path TYPE VARCHAR COLLATE loose; " +
                "INSERT INTO ducklake_files_scheduled_for_deletion VALUES " +
                "(950000,'A',TRUE,'2099-01-01'),(950000,'A',TRUE,NULL),(950000,'A',FALSE,'2099-01-01')," +
                "(950000,'a',TRUE,'2099-01-01'),(950000,'é',TRUE,'2099-01-01'),(950000,'é',TRUE,'2099-01-01')," +
                "(950001,'unknown_time',TRUE,NULL),(950002,'old',TRUE,'2000-01-01')")
            read(f) { c ->
                val h = c.readHierarchy()
                val all = mutableListOf<DucklakeScheduledPathReference>()
                var after: DucklakeScheduledPathCursor? = null
                repeat(5) {
                    val page = h.listScheduledReferences(after, 2)
                    all.addAll(page)
                    if (page.isNotEmpty()) after = page.last().cursor
                }
                assertThat(all.map { it.cursor }).containsExactly(
                    DucklakeScheduledPathCursor(950000, "A", false), DucklakeScheduledPathCursor(950000, "A", true),
                    DucklakeScheduledPathCursor(950000, "a", true), DucklakeScheduledPathCursor(950000, "é", true),
                    DucklakeScheduledPathCursor(950000, "é", true), DucklakeScheduledPathCursor(950001, "unknown_time", true),
                    DucklakeScheduledPathCursor(950002, "old", true))
                assertThat(all[0].resolvedPath).isEqualTo("A")
                assertThat(all[1].resolvedPath).isEqualTo(f.data.toString() + "/A")
                assertThat(h.listScheduledFiles(Instant.parse("2020-01-01T00:00:00Z"), 10).map { it.dataFileId }).containsExactly(950002)
            }
        }
    }

    @Test
    fun missingParentsCyclesAmbiguityAndRetainedOwnersAreCorruption() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            val snapshot = f.snapshot()
            val variants = listOf(
                "(900000,gen_random_uuid(),$snapshot,NULL,'orphan',NULL,NULL,999999)",
                "(900000,gen_random_uuid(),$snapshot,NULL,'cycle',NULL,NULL,900000)",
                "(900000,gen_random_uuid(),$snapshot,NULL,'sibling',NULL,NULL,NULL),(900001,gen_random_uuid(),$snapshot,NULL,'SIBLING',NULL,NULL,NULL)")
            for (rows in variants) {
                f.metadata("INSERT INTO ducklake_schema VALUES $rows")
                read(f) { c -> assertThatThrownBy { c.readHierarchy().listSchemas(snapshot) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java) }
                f.metadata("DELETE FROM ducklake_schema WHERE schema_id IN (900000,900001)")
            }
            f.metadata("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
                "VALUES(900000,999999,$snapshot,'kept.parquet',TRUE)")
            read(f) { c -> assertThatThrownBy { c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 1) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("missing retained table") }
        }
    }

    @Test
    fun nullDirectoriesAbsolutePathsAndMissingRootsNeverInventAStorageRoot() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE SCHEMA lake.a; CREATE TABLE lake.a.t(i INTEGER)")
            val snapshot = f.snapshot()
            val table = read(f) { c -> c.readHierarchy().findTable(listOf("a"), "t", snapshot)!!.table }
            f.metadata("UPDATE ducklake_schema SET path='s3://outside/Schema/',path_is_relative=FALSE WHERE schema_id=${table.schemaId}; " +
                "UPDATE ducklake_table SET path=NULL,path_is_relative=NULL WHERE table_id=${table.tableId}; " +
                "INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
                "VALUES(900000,${table.tableId},$snapshot,'a//%2F/../FILE.parquet',TRUE)")
            read(f) { c -> assertThat(c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 1).single().resolvedPath)
                .isEqualTo("s3://outside/Schema/a//%2F/../FILE.parquet") }
            f.metadata("DELETE FROM ducklake_metadata WHERE key='data_path' AND scope IS NULL; " +
                "UPDATE ducklake_schema SET path=NULL,path_is_relative=NULL; " +
                "UPDATE ducklake_data_file SET path='s3://absolute/kept',path_is_relative=FALSE")
            read(f) { c ->
                assertThat(c.readHierarchy().getTableById(table.tableId, snapshot)!!.dataPath).isNull()
                assertThat(c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 1).single().resolvedPath).isEqualTo("s3://absolute/kept")
            }
            f.metadata("UPDATE ducklake_data_file SET path='relative',path_is_relative=TRUE")
            read(f) { c -> assertThatThrownBy { c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 1) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("no catalog data_path") }
        }
    }

    @Test
    fun retainedPagesAreCompleteAndDuplicateBoundaryIdsAndOversizedPathsAreRejected() {
        Final11PostgresFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER)")
            val snapshot = f.snapshot()
            val id = read(f) { c -> c.readHierarchy().findTable(listOf("main"), "t", snapshot)!!.table.tableId }
            f.metadata("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,end_snapshot,path,path_is_relative) " +
                "SELECT 910000+i,$id,$snapshot,$snapshot+1,'p'||i,TRUE FROM generate_series(0,4) i")
            read(f) { c ->
                val h = c.readHierarchy()
                val first = h.listRetainedFiles(DucklakeFileKind.DATA, null, 2)
                val next = h.listRetainedFiles(DucklakeFileKind.DATA, first.last().fileId, 2)
                val last = h.listRetainedFiles(DucklakeFileKind.DATA, next.last().fileId, 2)
                assertThat((first + next + last).map { it.fileId }).containsExactly(910000, 910001, 910002, 910003, 910004)
                assertThat((first + next + last).map { it.endSnapshot }).containsOnly(snapshot + 1)
                assertThat(h.listRetainedFiles(DucklakeFileKind.DATA, last.last().fileId, 2)).isEmpty()
            }
            f.metadata("ALTER TABLE ducklake_data_file RENAME TO held; CREATE TABLE ducklake_data_file AS TABLE held; " +
                "INSERT INTO ducklake_data_file SELECT * FROM held WHERE data_file_id=910000")
            read(f) { c -> assertThatThrownBy { c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 1) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("duplicate retained file") }
            f.metadata("DROP TABLE ducklake_data_file; ALTER TABLE held RENAME TO ducklake_data_file; " +
                "UPDATE ducklake_data_file SET path=repeat('x',16385) WHERE data_file_id=910000")
            read(f) { c -> assertThatThrownBy { c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 1) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text budget") }
            f.metadata("INSERT INTO ducklake_files_scheduled_for_deletion VALUES(1,repeat('x',16385),TRUE,NULL)")
            read(f) { c -> assertThatThrownBy { c.readHierarchy().listScheduledReferences(null, 1) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("invalid scheduled") }
        }
    }

    @Test
    fun retainedNameLookupUsesDuckdbAsciiFoldingNotPostgresAccentCollation() {
        Final11PostgresFixture().use { f ->
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE SCHEMA lake.a; " +
                "CREATE TABLE lake.a.t AS SELECT 1 i; CREATE TABLE lake.a.\"té\" AS SELECT 2 i")
            f.metadata("CREATE COLLATION loose(provider=icu,locale='und-u-ks-level1',deterministic=false); " +
                "ALTER TABLE ducklake_table ALTER table_name TYPE VARCHAR COLLATE loose; " +
                "ALTER TABLE ducklake_schema ALTER schema_name TYPE VARCHAR COLLATE loose")
            read(f) { c ->
                val h = c.readHierarchy()
                val snapshot = c.readBackendProfile().snapshotId
                val schema = h.findSchema(listOf("A"), snapshot)!!
                assertThat(h.listTables(schema.metadata.schema.schemaId, snapshot).map { it.table.tableName }).containsExactly("t", "té")
                val t = h.findTable(listOf("a"), "T", snapshot)!!
                assertThat(h.getTableById(t.table.tableId, snapshot)).isEqualTo(t)
                assertThat(h.listRetainedFiles(DucklakeFileKind.DATA, null, 10).map { it.owner.table.tableName }).containsExactly("t", "té")
            }
        }
    }
}
