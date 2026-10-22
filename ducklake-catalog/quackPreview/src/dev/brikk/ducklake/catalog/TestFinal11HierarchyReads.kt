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
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestFinal11HierarchyReads {
    private lateinit var server: PreviewQuackServer
    private lateinit var reader: JdbcDucklakeCatalog
    private val sequence = AtomicInteger()
    private val hierarchy get() = reader.readHierarchy()

    @BeforeAll
    fun start() {
        server = PreviewQuackServer("1.1", "1.1-hierarchy").also { it.start() }
        reader = server.catalog(enableFinal11Reads = true)
    }
    @AfterAll
    fun close() {
        try { if (::reader.isInitialized) reader.close() }
        finally { if (::server.isInitialized) server.close() }
    }
    private fun <T> read(action: () -> T): T = reader.readSession(Supplier { action() })
    private fun sql(sql: String) = server.connection().use { c -> c.createStatement().use { it.execute(sql) } }
    private fun head() = read { reader.readMetadata().getReadMetadataCapabilities().snapshotId }
    private fun prefix() = "h${sequence.incrementAndGet()}"

    @Test
    fun nativeNestedNamesDotsAndQuotesResolveWithoutUnlockingLegacyFlatApis() {
        val p = prefix()
        server.native("CREATE SCHEMA lake.$p; CREATE SCHEMA lake.$p.child; CREATE SCHEMA lake.\"$p.child\"; " +
            "CREATE TABLE lake.$p.child.\"t.q\" AS SELECT 42 i; CREATE TABLE lake.\"$p.child\".t AS SELECT 7 i")
        val snapshot = head()
        read {
            val nested = hierarchy.findSchema(listOf(p.uppercase(), "CHILD"), snapshot)!!
            val literal = hierarchy.findSchema(listOf("$p.child"), snapshot)!!
            assertThat(nested.schemaIds).hasSize(2)
            assertThat(literal.schemaIds).hasSize(1)
            assertThat(nested.metadata.schema.schemaId).isNotEqualTo(literal.metadata.schema.schemaId)
            val table = hierarchy.findTable(listOf(p, "child"), "T.Q", snapshot)!!
            assertThat(table.schema.schemaIds).isEqualTo(nested.schemaIds)
            assertThat(hierarchy.getTableById(table.table.tableId, snapshot)).isEqualTo(table)
            assertThat(hierarchy.listTables(nested.metadata.schema.schemaId, snapshot)).containsExactly(table)
            assertThatThrownBy { reader.getSchema("child", snapshot) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        }
        server.native("DROP SCHEMA lake.$p CASCADE; DROP SCHEMA lake.\"$p.child\" CASCADE")
    }

    @Test
    fun historicalRenameDropAndNameReuseKeepOriginalIdsAndQualification() {
        val p = prefix()
        server.native("CREATE SCHEMA lake.$p; CREATE SCHEMA lake.$p.child; CREATE TABLE lake.$p.child.t AS SELECT 1 i")
        val old = head()
        val original = read { hierarchy.findTable(listOf(p, "child"), "t", old)!! }
        server.native("ALTER TABLE lake.$p.child.t RENAME TO renamed")
        val renamed = head()
        server.native("DROP SCHEMA lake.$p CASCADE")
        val dropped = head()
        server.native("CREATE SCHEMA lake.$p; CREATE SCHEMA lake.$p.child; CREATE TABLE lake.$p.child.t AS SELECT 2 i")
        val current = head()
        read {
            val historic = hierarchy.getTableById(original.table.tableId, old)!!
            assertThat(historic.table.tableId).isEqualTo(original.table.tableId)
            assertThat(historic.table.tableName).isEqualTo("t")
            assertThat(historic.table.beginSnapshot).isEqualTo(original.table.beginSnapshot)
            assertThat(historic.table.endSnapshot).isEqualTo(renamed)
            assertThat(historic.schema.metadata.schema.endSnapshot).isEqualTo(dropped)
            assertThat(historic.schema.schemaIds).isEqualTo(original.schema.schemaIds)
            assertThat(historic.schema.names).containsExactly(p, "child")
            assertThat(historic.dataPath).isEqualTo(original.dataPath)
            assertThat(hierarchy.getTableById(original.table.tableId, renamed)!!.table.tableName).isEqualTo("renamed")
            assertThat(hierarchy.getTableById(original.table.tableId, current)).isNull()
            val replacement = hierarchy.findTable(listOf(p, "child"), "t", current)!!
            assertThat(replacement.table.tableId).isNotEqualTo(original.table.tableId)
            assertThat(replacement.schema.schemaIds).isNotEqualTo(original.schema.schemaIds)
            assertThat(hierarchy.findSchema(listOf("child"), current)).isNull()
        }
        server.native("DROP SCHEMA lake.$p CASCADE")
    }

    @Test
    fun nativeFilePathsMatchTheOracleAndRetiredOwnersSurviveExpiredOriginSnapshots() {
        val p = prefix()
        server.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',false); " +
            "CREATE SCHEMA lake.$p; CREATE SCHEMA lake.$p.child; CREATE TABLE lake.$p.child.t AS SELECT i FROM range(1000) t(i); " +
            "DELETE FROM lake.$p.child.t WHERE i<100")
        val snapshot = head()
        val table = read { hierarchy.findTable(listOf(p, "child"), "t", snapshot)!! }
        val native = server.native("SELECT data_file,delete_file FROM ducklake_list_files('lake','t',schema=>'$p.child')").single()
        val data = read { hierarchy.listRetainedFiles(DucklakeFileKind.DATA, null, 1024).single { it.owner.table.tableId == table.table.tableId } }
        val delete = read { hierarchy.listRetainedFiles(DucklakeFileKind.DELETE, null, 1024).single { it.owner.table.tableId == table.table.tableId } }
        assertThat(data.resolvedPath).isEqualTo(native[0])
        assertThat(delete.resolvedPath).isEqualTo(native[1])
        server.native("DROP SCHEMA lake.$p CASCADE")
        sql("DELETE FROM ducklake_snapshot WHERE snapshot_id=${data.beginSnapshot}") // Owned fixture: ownership must not depend on a survivor.
        read {
            assertThat(hierarchy.listRetainedFiles(DucklakeFileKind.DATA, null, 1024).single { it.fileId == data.fileId }.resolvedPath)
                .isEqualTo(data.resolvedPath)
            assertThat(hierarchy.listRetainedFiles(DucklakeFileKind.DELETE, null, 1024).single { it.fileId == delete.fileId }.resolvedPath)
                .isEqualTo(delete.resolvedPath)
        }
    }

    @Test
    fun missingParentsCyclesAndAmbiguousSiblingNamesAreExplicitCorruption() {
        val snapshot = head()
        for (rows in listOf(
            "(900000,uuid(),$snapshot,NULL,'orphan',NULL,NULL,999999)",
            "(900000,uuid(),$snapshot,NULL,'cycle',NULL,NULL,900000)",
            "(900000,uuid(),$snapshot,NULL,'sibling',NULL,NULL,NULL),(900001,uuid(),$snapshot,NULL,'SIBLING',NULL,NULL,NULL)")) {
            sql("INSERT INTO ducklake_schema VALUES $rows")
            try { read { assertThatThrownBy { hierarchy.listSchemas(snapshot) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java) } }
            finally { sql("DELETE FROM ducklake_schema WHERE schema_id IN(900000,900001)") }
        }
    }

    @Test
    fun requiredSessionsFutureSnapshotsAndMissingOwnersNeverBecomeEmptySuccessfulOwnership() {
        assertThatThrownBy { hierarchy.listSchemas(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        read { assertThatThrownBy { hierarchy.listSchemas(head() + 1000) }.isInstanceOf(DucklakeInvalidOperationException::class.java) }
        server.catalog().use { disabled -> disabled.readSession(Supplier {
            assertThatThrownBy { disabled.readHierarchy().listSchemas(0) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
        }) }
        val snapshot = head()
        sql("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
            "VALUES(900000,999999,$snapshot,'kept.parquet',TRUE)")
        try { read { assertThatThrownBy { hierarchy.listRetainedFiles(DucklakeFileKind.DATA, 899999, 1) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("missing retained table") } }
        finally { sql("DELETE FROM ducklake_data_file WHERE data_file_id=900000") }
    }

    @Test
    fun nullableDirectoriesAbsoluteResetsAndScheduledPathsUseSeparateNamespaces() {
        val p = prefix()
        server.native("CREATE SCHEMA lake.$p; CREATE TABLE lake.$p.t(i INTEGER)")
        val snapshot = head()
        val table = read { hierarchy.findTable(listOf(p), "t", snapshot)!!.table }
        sql("UPDATE ducklake_schema SET path='s3://outside/Schema/',path_is_relative=FALSE WHERE schema_id=${table.schemaId}; " +
            "UPDATE ducklake_table SET path=NULL,path_is_relative=NULL WHERE table_id=${table.tableId}; " +
            "INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
            "VALUES(900001,${table.tableId},$snapshot,'a//%2F/../FILE.parquet',TRUE); " +
            "INSERT INTO ducklake_files_scheduled_for_deletion VALUES(900001,'queue.parquet',TRUE,TIMESTAMPTZ '2000-01-01 00:00:00+00')")
        try { read {
            val owned = hierarchy.listRetainedFiles(DucklakeFileKind.DATA, 900000, 1).single()
            assertThat(owned.resolvedPath).isEqualTo("s3://outside/Schema/a//%2F/../FILE.parquet")
            val queued = hierarchy.listScheduledFiles(Instant.parse("2020-01-01T00:00:00Z"), 10).single { it.dataFileId == 900001L }
            val root = reader.readMetadata().getReadMetadataCapabilities() // No flattened legacy root lookup.
            assertThat(root.formatVersion).isEqualTo("1.1")
            assertThat(queued.resolvedPath).isEqualTo(server.data.toString() + "/queue.parquet")
        } }
        finally {
            sql("DELETE FROM ducklake_data_file WHERE data_file_id=900001; DELETE FROM ducklake_files_scheduled_for_deletion WHERE data_file_id=900001")
        }
        server.native("DROP SCHEMA lake.$p CASCADE")
    }

    @Test
    fun hierarchyPinAndRefreshObserveOnlyTheOwnedSnapshot() {
        val p = prefix()
        server.native("CREATE SCHEMA lake.$p")
        val snapshot = head()
        val id = read { hierarchy.findSchema(listOf(p), snapshot)!!.metadata.schema.schemaId }
        try { read {
            assertThat(hierarchy.getSchemaById(id, snapshot)!!.names).containsExactly(p)
            sql("UPDATE ducklake_schema SET schema_name='${p}_new' WHERE schema_id=$id")
            assertThat(hierarchy.getSchemaById(id, snapshot)!!.names).containsExactly(p)
        }
            read { assertThat(hierarchy.getSchemaById(id, snapshot)!!.names).containsExactly("${p}_new") }
        }
        finally { sql("UPDATE ducklake_schema SET schema_name='$p' WHERE schema_id=$id") }
        server.native("DROP SCHEMA lake.$p")
    }

    @Test
    fun originVersionNotLatestOwnerControlsRetainedStoragePaths() {
        val p = prefix()
        server.native("CREATE SCHEMA lake.$p; CREATE TABLE lake.$p.t(i INTEGER)")
        val origin = head()
        val old = read { hierarchy.findTable(listOf(p), "t", origin)!! }
        server.native("ALTER TABLE lake.$p.t RENAME TO renamed")
        val current = head()
        sql("UPDATE ducklake_table SET path='s3://later/',path_is_relative=FALSE WHERE table_id=${old.table.tableId} AND end_snapshot IS NULL; " +
            "INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
            "VALUES(900010,${old.table.tableId},$origin,'original.parquet',TRUE)")
        try { read {
            assertThat(hierarchy.getTableById(old.table.tableId, current)!!.dataPath).isEqualTo("s3://later/")
            val file = hierarchy.listRetainedFiles(DucklakeFileKind.DATA, 900009, 1).single()
            assertThat(file.owner.table.tableName).isEqualTo("t")
            assertThat(file.owner.dataPath).isEqualTo(old.dataPath)
            assertThat(file.resolvedPath).isEqualTo(old.dataPath + "original.parquet")
        } }
        finally {
            sql("DELETE FROM ducklake_data_file WHERE data_file_id=900010; UPDATE ducklake_table SET path='t/',path_is_relative=TRUE " +
                "WHERE table_id=${old.table.tableId} AND end_snapshot IS NULL")
        }
        server.native("DROP SCHEMA lake.$p CASCADE")
    }

    @Test
    fun missingRootsNeverFabricateRelativePathsButAbsoluteFilesRemainResolvable() {
        val p = prefix()
        server.native("CREATE TABLE lake.$p(i INTEGER)")
        val snapshot = head()
        val table = read { hierarchy.findTable(listOf("main"), p, snapshot)!!.table }
        val root = server.connection().use { c -> PreviewQuackServer.jdbcRows(c,
            "SELECT value FROM ducklake_metadata WHERE key='data_path' AND scope IS NULL").single().single()!! }
        sql("UPDATE ducklake_metadata SET value='${root.trimEnd('/').replace("'", "''")}' WHERE key='data_path' AND scope IS NULL")
        try { read { assertThatThrownBy { hierarchy.listSchemas(snapshot) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("directory separator") } }
        finally { sql("UPDATE ducklake_metadata SET value='${root.replace("'", "''")}' WHERE key='data_path' AND scope IS NULL") }
        sql("DELETE FROM ducklake_metadata WHERE key='data_path' AND scope IS NULL; " +
            "INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
            "VALUES(900010,${table.tableId},$snapshot,'s3://absolute/kept',FALSE),(900011,${table.tableId},$snapshot,'relative',TRUE)")
        try { read {
            assertThat(hierarchy.findTable(listOf("main"), p, snapshot)!!.dataPath).isNull()
            assertThat(hierarchy.listRetainedFiles(DucklakeFileKind.DATA, 900009, 1).single().resolvedPath).isEqualTo("s3://absolute/kept")
            assertThatThrownBy { hierarchy.listRetainedFiles(DucklakeFileKind.DATA, 900010, 1) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("no catalog data_path")
        } }
        finally {
            sql("DELETE FROM ducklake_data_file WHERE data_file_id IN(900010,900011); " +
                "INSERT INTO ducklake_metadata(key,value,scope,scope_id) VALUES('data_path','${root.replace("'", "''")}',NULL,NULL)")
        }
        server.native("DROP TABLE lake.$p")
    }

    @Test
    fun schemaSqlBudgetWrongPhysicalFlagsAndOversizedNamesFailBeforeResolution() {
        val snapshot = head()
        sql("INSERT INTO ducklake_schema SELECT 1000000+i,uuid(),$snapshot,NULL,'b'||i,NULL,NULL,NULL FROM range(4097) t(i)")
        try { read { assertThatThrownBy { hierarchy.listSchemas(snapshot) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("node budget") } }
        finally { sql("DELETE FROM ducklake_schema WHERE schema_id>=1000000") }
        sql("ALTER TABLE ducklake_table RENAME path_is_relative TO r05_held; ALTER TABLE ducklake_table ADD COLUMN path_is_relative VARCHAR")
        try { read { assertThatThrownBy { hierarchy.listSchemas(snapshot) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("ducklake_table.path_is_relative") } }
        finally { sql("ALTER TABLE ducklake_table DROP COLUMN path_is_relative; ALTER TABLE ducklake_table RENAME r05_held TO path_is_relative") }
        sql("INSERT INTO ducklake_schema VALUES(900000,uuid(),$snapshot,NULL,repeat('x',16385),NULL,NULL,NULL)")
        try { read { assertThatThrownBy { hierarchy.listSchemas(snapshot) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text budget") } }
        finally { sql("DELETE FROM ducklake_schema WHERE schema_id=900000") }
    }

    @Test
    fun keysetPagesKeepAllRetiredReferencesAndRejectDuplicateIdsAtPageEdges() {
        val p = prefix()
        server.native("CREATE TABLE lake.$p(i INTEGER)")
        val snapshot = head()
        val table = read { hierarchy.findTable(listOf("main"), p, snapshot)!!.table }
        sql("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,end_snapshot,path,path_is_relative) " +
            "SELECT 910000+i,${table.tableId},$snapshot,$snapshot+1,'p'||i,TRUE FROM range(5) t(i)")
        try { read {
            val first = hierarchy.listRetainedFiles(DucklakeFileKind.DATA, 909999, 2)
            val next = hierarchy.listRetainedFiles(DucklakeFileKind.DATA, first.last().fileId, 2)
            val last = hierarchy.listRetainedFiles(DucklakeFileKind.DATA, next.last().fileId, 2)
            assertThat((first + next + last).map { it.fileId }).containsExactly(910000, 910001, 910002, 910003, 910004)
            assertThat((first + next + last).map { it.endSnapshot }).containsOnly(snapshot + 1)
        }
            sql("ALTER TABLE ducklake_data_file RENAME TO r05_data_held; CREATE TABLE ducklake_data_file AS SELECT * FROM r05_data_held; " +
                "INSERT INTO ducklake_data_file SELECT * FROM r05_data_held WHERE data_file_id=910000")
            try {
                read { assertThatThrownBy { hierarchy.listRetainedFiles(DucklakeFileKind.DATA, 909999, 1) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("duplicate retained file") }
                sql("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
                    "VALUES(NULL,${table.tableId},$snapshot,'unknown-id',TRUE)")
                read { assertThatThrownBy { hierarchy.listRetainedFiles(DucklakeFileKind.DATA, Long.MAX_VALUE, 1) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("NULL/negative retained file") }
            }
            finally { sql("DROP TABLE ducklake_data_file; ALTER TABLE r05_data_held RENAME TO ducklake_data_file") }
        }
        finally { sql("DELETE FROM ducklake_data_file WHERE data_file_id>=910000") }
        server.native("DROP TABLE lake.$p")
    }

    @Test
    fun allScheduledReferencesProtectFutureAndUnknownAgePathsWithExactCompositeCursors() {
        sql("INSERT INTO ducklake_files_scheduled_for_deletion VALUES " +
            "(950000,'A',TRUE,TIMESTAMPTZ '2099-01-01 00:00:00+00'),(950000,'A',TRUE,NULL)," +
            "(950000,'A',FALSE,TIMESTAMPTZ '2099-01-01 00:00:00+00'),(950000,'a',TRUE,TIMESTAMPTZ '2099-01-01 00:00:00+00')," +
            "(950000,'s3://b/A',FALSE,TIMESTAMPTZ '2099-01-01 00:00:00+00'),(950001,'unknown_time',TRUE,NULL)")
        try { read {
            val first = hierarchy.listScheduledReferences(DucklakeScheduledPathCursor(949999, "seed", false), 2)
            val second = hierarchy.listScheduledReferences(first.last().cursor, 2)
            val third = hierarchy.listScheduledReferences(second.last().cursor, 2)
            val references = first + second + third
            assertThat(references.map { it.cursor }).containsExactly(
                DucklakeScheduledPathCursor(950000, "A", false), DucklakeScheduledPathCursor(950000, "A", true),
                DucklakeScheduledPathCursor(950000, "a", true), DucklakeScheduledPathCursor(950000, "s3://b/A", false),
                DucklakeScheduledPathCursor(950001, "unknown_time", true))
            assertThat(references.map { it.resolvedPath }).containsExactly("A", server.data.toString() + "/A",
                server.data.toString() + "/a", "s3://b/A", server.data.toString() + "/unknown_time")
            assertThat(hierarchy.listScheduledFiles(Instant.parse("2020-01-01T00:00:00Z"), 1024).map { it.dataFileId })
                .doesNotContain(950000, 950001)
            assertThat(hierarchy.listScheduledReferences(third.last().cursor, 2)).isEmpty()
        } }
        finally { sql("DELETE FROM ducklake_files_scheduled_for_deletion WHERE data_file_id IN(950000,950001)") }
    }
}
