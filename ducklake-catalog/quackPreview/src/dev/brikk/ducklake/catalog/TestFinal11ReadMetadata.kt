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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import java.util.zip.GZIPInputStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestFinal11ReadMetadata {
    private lateinit var server: PreviewQuackServer
    private lateinit var reader: JdbcDucklakeCatalog
    private val sequence = AtomicInteger()
    private val metadata get() = reader.readMetadata()

    @BeforeAll
    fun start() {
        server = PreviewQuackServer("1.1", "1.1-rich-metadata").also { it.start() }
        reader = server.catalog(enableFinal11Reads = true)
    }

    @AfterAll
    fun close() {
        try { if (::reader.isInitialized) reader.close() }
        finally { if (::server.isInitialized) server.close() }
    }

    private fun <T> read(action: () -> T): T = reader.readSession(Supplier { action() })
    private fun sql(sql: String) = server.connection().use { c -> c.createStatement().use { it.execute(sql) } }
    private fun visibleSnapshot(): Long = server.connection().use { c ->
        PreviewQuackServer.jdbcRows(c, "SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
    }
    private fun fileTable(): Long {
        val name = "rich_${sequence.incrementAndGet()}"
        server.native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',false); " +
            "CREATE TABLE lake.$name(i INTEGER); INSERT INTO lake.$name FROM range(1000); DELETE FROM lake.$name WHERE i<100")
        return read { reader.getTable("main", name, reader.currentSnapshotId)!!.tableId }
    }

    @Test
    fun capabilitiesReportActualPeerAndLayoutWithoutClaimingPruningOrWrites() = read {
        val caps = metadata.getReadMetadataCapabilities()
        assertThat(caps.formatVersion).isEqualTo("1.1")
        assertThat(caps.engineVersion).isEqualTo("v2.0.0")
        assertThat(caps.engineSourceId).isEqualTo("d54cbbe3df")
        assertThat(caps.ducklakeExtensionVersion).isEqualTo("417a9117f5")
        assertThat(caps.quackExtensionVersion).isEqualTo("f96420ed46")
        assertThat(caps.jdbcDriverVersion).isEqualTo("0.11.1")
        assertThat(caps.unsignedExtensionsAllowed).isFalse()
        assertThat(caps.observedColumns).contains(DucklakeMetadataColumn("ducklake_schema", "parent_schema_id", "BIGINT"))
        assertThat(listOf(caps.schemaParentIds, caps.boundExactness, caps.dataFileRowGroups, caps.deleteFileRowGroups)).containsOnly(true)
        assertThat(caps.qualifiedNestedSchemaResolution).isTrue()
        assertThat(listOf(caps.qualifiedRangePruning, caps.qualifiedExactAggregates, caps.qualifiedWrites))
            .containsOnly(false)
        Unit
    }

    @Test
    fun everyCompanionOperationRequiresOwnedOptInSessionAndDoesNotQualifyLegacyFormats() {
        val actions = listOf<() -> Any?>({ metadata.getReadMetadataCapabilities() }, { metadata.listSchemaMetadata(0) },
            { metadata.getFileReadMetadata(1, 0) }, { metadata.getFileColumnMetadata(1, 0) }, { metadata.getTableColumnMetadata(1) })
        actions.forEach { action -> assertThatThrownBy { action() }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession") }
        server.catalog().use { disabled -> disabled.readSession(Supplier {
            assertThatThrownBy { disabled.readMetadata().getReadMetadataCapabilities() }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("enableFinal11Reads")
        }) }
        sql("UPDATE ducklake_metadata SET value='1.1-dev1' WHERE key='version'")
        try { read { assertThatThrownBy { metadata.getReadMetadataCapabilities() }.isInstanceOf(DucklakeInvalidOperationException::class.java) } }
        finally { sql("UPDATE ducklake_metadata SET value='1.1' WHERE key='version'") }
    }

    @Test
    fun physicalAdditionsAreRequiredAndWrongTypesNeverBecomeCapabilities() {
        for ((table, column, type) in listOf(Triple("ducklake_schema", "parent_schema_id", "BIGINT"),
            Triple("ducklake_data_file", "row_group_count", "BIGINT"), Triple("ducklake_delete_file", "row_group_count", "BIGINT"),
            Triple("ducklake_file_column_stats", "min_is_exact", "BOOLEAN"), Triple("ducklake_file_column_stats", "max_is_exact", "BOOLEAN"),
            Triple("ducklake_table_column_stats", "min_is_exact", "BOOLEAN"), Triple("ducklake_table_column_stats", "max_is_exact", "BOOLEAN"))) {
            sql("ALTER TABLE $table RENAME $column TO r04_held")
            read { assertThatThrownBy { metadata.getReadMetadataCapabilities() }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("$table.$column ($type)") }
            sql("ALTER TABLE $table ADD COLUMN $column VARCHAR")
            try { read { assertThatThrownBy { metadata.getReadMetadataCapabilities() }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("$table.$column ($type)") } }
            finally { sql("ALTER TABLE $table DROP COLUMN $column; ALTER TABLE $table RENAME r04_held TO $column") }
            read { assertThat(metadata.getReadMetadataCapabilities().formatVersion).isEqualTo("1.1") }
        }
    }

    @Test
    fun independentEndpointFlagsPreserveUnknownFalseAndMissingValuesWithoutChangingLegacyFallbacks() {
        val table = fileTable()
        for ((minFlag, maxFlag) in listOf("NULL" to "TRUE", "FALSE" to "NULL", "TRUE" to "FALSE")) {
            sql("UPDATE ducklake_file_column_stats SET min_value=NULL,max_value='999',min_is_exact=$minFlag,max_is_exact=$maxFlag WHERE table_id=$table; " +
                "UPDATE ducklake_table_column_stats SET min_value='NULL',max_value=NULL,min_is_exact=$minFlag,max_is_exact=$maxFlag WHERE table_id=$table")
            read {
                val snapshot = reader.currentSnapshotId
                val file = metadata.getFileColumnMetadata(table, snapshot).single()
                val global = metadata.getTableColumnMetadata(table).single()
                fun flag(value: String): Boolean? = if (value == "NULL") null else value == "TRUE"
                assertThat(file.minIsExact).isEqualTo(flag(minFlag))
                assertThat(file.maxIsExact).isEqualTo(flag(maxFlag))
                assertThat(file.stats.minValue).isNull()
                assertThat(file.stats.maxValue).isEqualTo("999")
                assertThat(global.minIsExact).isEqualTo(flag(minFlag))
                assertThat(global.maxIsExact).isEqualTo(flag(maxFlag))
                assertThat(global.minValue).isEqualTo("NULL")
                assertThat(global.maxValue).isNull()
                val legacy = reader.getColumnStats(table, snapshot).single()
                assertThat(legacy.minValue).isNull()
                assertThat(legacy.maxValue).isNull()
                assertThat(reader.findDataFileIdsInRange(table, snapshot, ColumnRangePredicate(0, "9999", "10000")))
                    .containsExactlyElementsOf(reader.getDataFiles(table, snapshot).map { it.dataFileId })
            }
        }
    }

    @Test
    fun rowGroupsKeepDataAndDeleteCountsDistinctIncludingUnknownAndZero() {
        val table = fileTable()
        read {
            val result = metadata.getFileReadMetadata(table, reader.currentSnapshotId).single()
            assertThat(result.rowGroupCount).isEqualTo(1)
            assertThat(result.deleteRowGroupCount).isEqualTo(1)
            assertThat(result.deleteFileId).isNotNull()
        }
        for ((data, delete) in listOf("NULL" to "0", "0" to "NULL", "7" to "3")) {
            sql("UPDATE ducklake_data_file SET row_group_count=$data WHERE table_id=$table; " +
                "UPDATE ducklake_delete_file SET row_group_count=$delete WHERE table_id=$table")
            read {
                val result = metadata.getFileReadMetadata(table, reader.currentSnapshotId).single()
                assertThat(result.rowGroupCount).isEqualTo(data.toLongOrNull())
                assertThat(result.deleteRowGroupCount).isEqualTo(delete.toLongOrNull())
            }
        }
        sql("UPDATE ducklake_data_file SET row_group_count=-1 WHERE table_id=$table")
        read { assertThatThrownBy { metadata.getFileReadMetadata(table, reader.currentSnapshotId) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java) }
        sql("UPDATE ducklake_data_file SET row_group_count=1 WHERE table_id=$table; " +
            "UPDATE ducklake_delete_file SET row_group_count=-1 WHERE table_id=$table")
        read { assertThatThrownBy { metadata.getFileReadMetadata(table, reader.currentSnapshotId) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java) }
        sql("UPDATE ducklake_delete_file SET row_group_count=1 WHERE table_id=$table")
    }

    @Test
    fun richSchemaInspectionRetainsParentIdsWithoutUnlockingFlatLegacyReads() {
        val snapshot = visibleSnapshot()
        sql("INSERT INTO ducklake_schema VALUES(900000,uuid(),$snapshot,NULL,'r04_parent','parent/',TRUE,NULL)," +
            "(900001,uuid(),$snapshot,NULL,'r04_child','child/',TRUE,900000)")
        try { read {
            val schemas = metadata.listSchemaMetadata(snapshot)
            assertThat(schemas.single { it.schema.schemaId == 900001L }.parentSchemaId).isEqualTo(900000L)
            assertThat(schemas.single { it.schema.schemaId == 900000L }.parentSchemaId).isNull()
            assertThat(metadata.getReadMetadataCapabilities().qualifiedNestedSchemaResolution).isTrue()
            assertThatThrownBy { reader.listSchemas(snapshot) }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                .hasMessageContaining("nested-schema history")
        } }
        finally { sql("DELETE FROM ducklake_schema WHERE schema_id IN(900000,900001)") }
    }

    @Test
    fun futureSnapshotsAreRejectedAndDroppedRowsAreFilteredAtTheRequestedSnapshot() {
        val table = fileTable()
        val before = visibleSnapshot()
        sql("UPDATE ducklake_data_file SET end_snapshot=$before WHERE table_id=$table")
        read {
            assertThat(metadata.getFileReadMetadata(table, before)).isEmpty()
            assertThat(metadata.getFileColumnMetadata(table, before)).isEmpty()
            assertThat(metadata.getFileReadMetadata(table, before - 1)).hasSize(1)
            for (action in listOf<() -> Any?>({ metadata.listSchemaMetadata(before + 100) },
                { metadata.getFileReadMetadata(table, before + 100) }, { metadata.getFileColumnMetadata(table, before + 100) })) {
                assertThatThrownBy { action() }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("not visible")
            }
        }
    }

    @Test
    fun richReadsStayPinnedAcrossIndependentPublicationAndRefreshNextSession() {
        val table = fileTable()
        try { read {
            val caps = metadata.getReadMetadataCapabilities()
            assertThat(metadata.getFileReadMetadata(table, caps.snapshotId).single().rowGroupCount).isEqualTo(1)
            sql("UPDATE ducklake_data_file SET row_group_count=77 WHERE table_id=$table")
            assertThat(metadata.getFileReadMetadata(table, caps.snapshotId).single().rowGroupCount).isEqualTo(1)
            assertThat(metadata.getReadMetadataCapabilities()).isEqualTo(caps)
        }
            read { assertThat(metadata.getFileReadMetadata(table, metadata.getReadMetadataCapabilities().snapshotId).single().rowGroupCount)
                .isEqualTo(77) }
        }
        finally { sql("UPDATE ducklake_data_file SET row_group_count=1 WHERE table_id=$table") }
    }

    @Test
    fun rawBoundsAreNotCastsAndOversizedTextAndSchemaResultsFailRatherThanTruncate() {
        val longName = "x".repeat(129)
        sql("ALTER TABLE ducklake_schema ADD COLUMN $longName VARCHAR")
        try { read { assertThatThrownBy { metadata.getReadMetadataCapabilities() }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("name/type exceeds") } }
        finally { sql("ALTER TABLE ducklake_schema DROP COLUMN $longName") }
        val table = fileTable()
        sql("UPDATE ducklake_file_column_stats SET min_value='2020-01-01 12:34:56.123456789',max_value='0.1'," +
            "min_is_exact=TRUE,max_is_exact=FALSE WHERE table_id=$table")
        read {
            val stats = metadata.getFileColumnMetadata(table, metadata.getReadMetadataCapabilities().snapshotId).single()
            assertThat(stats.stats.minValue).isEqualTo("2020-01-01 12:34:56.123456789")
            assertThat(stats.stats.maxValue).isEqualTo("0.1")
            assertThat(stats.minIsExact).isTrue()
            assertThat(stats.maxIsExact).isFalse()
        }
        sql("UPDATE ducklake_file_column_stats SET min_value=repeat('x',16385) WHERE table_id=$table")
        read { assertThatThrownBy { metadata.getFileColumnMetadata(table, metadata.getReadMetadataCapabilities().snapshotId) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("text exceeds") }
        sql("UPDATE ducklake_file_column_stats SET min_value='0' WHERE table_id=$table")
        val snapshot = visibleSnapshot()
        sql("INSERT INTO ducklake_schema SELECT 1000000+i,uuid(),$snapshot,NULL,'budget_'||i,NULL,NULL,NULL FROM range(65537) t(i)")
        try { read { assertThatThrownBy { metadata.listSchemaMetadata(snapshot) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("row budget") } }
        finally { sql("DELETE FROM ducklake_schema WHERE schema_id>=1000000") }
    }

    @Test
    fun realNativeMigrationKeepsOldUnknownMetadataWithoutInferringExactness() {
        PreviewQuackServer("1.1", "1.1-rich-migrated", migrateFrom10 = true).use { migrated ->
            migrated.start()
            migrated.catalog(enableFinal11Reads = true).use { catalog -> catalog.readSession(Supplier {
                val rich = catalog.readMetadata()
                val caps = rich.getReadMetadataCapabilities()
                val table = catalog.getTable("main", "migrate_seed", caps.snapshotId)!!
                val files = rich.getFileReadMetadata(table.tableId, caps.snapshotId)
                assertThat(files).hasSize(1)
                assertThat(files.single().rowGroupCount).isNull()
                val stats = rich.getFileColumnMetadata(table.tableId, caps.snapshotId)
                assertThat(stats).isNotEmpty()
                assertThat(stats.map { it.minIsExact }).containsOnlyNulls()
                assertThat(stats.map { it.maxIsExact }).containsOnlyNulls()
                assertThat(stats.map { it.stats.minValue }).contains("1", "file")
                assertThat(catalog.getColumnStats(table.tableId, caps.snapshotId).map { it.minValue }).containsOnlyNulls()
            }) }
        }
    }

    @Test
    fun genuine156RetypedMetadataKeepsRawBoundsUntrustedAndLegacyPlanningConservative() {
        val root = Path.of(System.getProperty("ducklake.read.fixtures"))
        val bytes = Files.readAllBytes(root.resolve("data/old_ducklake/migrate_bounds.db.gz"))
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .isEqualTo("f9ad3a91468c3e3c96b1d3587214885b20fedd86c8a89b84efab2a7487188528")
        val seed = Files.createTempFile(Path.of("/tmp/opencode"), "catalog-r04-bounds-", ".db")
        try {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val data = input.readNBytes(64 * 1024 * 1024 + 1)
                check(data.size <= 64 * 1024 * 1024)
                Files.write(seed, data)
            }
            PreviewQuackServer("1.1", "1.1-rich-retyped", seedCatalog = seed).use { migrated ->
                migrated.start()
                migrated.catalog(enableFinal11Reads = true).use { catalog -> catalog.readSession(Supplier {
                    val rich = catalog.readMetadata()
                    val snapshot = rich.getReadMetadataCapabilities().snapshotId
                    val table = catalog.getTable("main", "ts", snapshot)!!
                    val stored = rich.getFileColumnMetadata(table.tableId, snapshot).single()
                    // Shipped migration did NOT repair this seed. Raw provenance is not a typed/exact bound.
                    assertThat(stored.stats.minValue).isEqualTo("2020-01-01 12:34:56.123456789")
                    assertThat(stored.minIsExact).isNull()
                    assertThat(stored.maxIsExact).isNull()
                    assertThat(catalog.getColumnStats(table.tableId, snapshot).single().minValue).isNull()
                    assertThat(catalog.findDataFileIdsInRange(table.tableId, snapshot,
                        ColumnRangePredicate(1, "2020-01-01 12:34:56.123457", "2020-01-01 12:34:56.123457")))
                        .containsExactlyElementsOf(catalog.getDataFiles(table.tableId, snapshot).map { it.dataFileId })
                    assertThat(rich.getFileReadMetadata(table.tableId, snapshot).single().rowGroupCount).isNull()
                }) }
            }
        }
        finally { Files.deleteIfExists(seed) }
    }

    @Test
    fun unknownCountsStayNullButNegativeCountsAndMissingRequiredValuesAreCorruption() {
        val table = fileTable()
        sql("UPDATE ducklake_file_column_stats SET value_count=NULL,null_count=NULL,contains_nan=NULL WHERE table_id=$table")
        read {
            val stats = metadata.getFileColumnMetadata(table, metadata.getReadMetadataCapabilities().snapshotId).single().stats
            assertThat(stats.valueCount).isNull()
            assertThat(stats.nullCount).isNull()
            assertThat(stats.containsNan).isNull()
        }
        for (column in listOf("value_count", "null_count", "column_size_bytes")) {
            sql("UPDATE ducklake_file_column_stats SET $column=-1 WHERE table_id=$table")
            try { read { assertThatThrownBy { metadata.getFileColumnMetadata(table, metadata.getReadMetadataCapabilities().snapshotId) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("negative") } }
            finally { sql("UPDATE ducklake_file_column_stats SET $column=0 WHERE table_id=$table") }
        }
        sql("UPDATE ducklake_data_file SET record_count=NULL WHERE table_id=$table")
        try { read { assertThatThrownBy { metadata.getFileReadMetadata(table, metadata.getReadMetadataCapabilities().snapshotId) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("NULL required record_count") } }
        finally { sql("UPDATE ducklake_data_file SET record_count=1000 WHERE table_id=$table") }
    }
}
