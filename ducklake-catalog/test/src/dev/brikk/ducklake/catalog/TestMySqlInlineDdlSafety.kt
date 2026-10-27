package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.jooq.SQLDialect
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/** Authored 1.0 inline fixtures isolate library writes from the separate native MySQL producer gates. */
internal class TestMySqlInlineDdlSafety {
    @Nested inner class DefaultMode : Contract(false)
    @Nested inner class AnsiQuotes : Contract(true)

    abstract class Contract(private val ansiQuotes: Boolean) {
        @Test fun failedDropCannotBecomeVisibleOnAnUnrelatedCommit() = withCatalog(ansiQuotes) { f, c ->
            val table = seedTable(f, c)
            val snapshot = c.currentSnapshotId
            val columns = c.getTableColumns(table, snapshot)
            val before = state(f)
            val publications = AtomicInteger()
            c.beforeSnapshotInsertAction = Runnable {
                publications.incrementAndGet()
                throw IllegalStateException("injected failure before snapshot publication")
            }
            val failure = try { catchThrowable { c.dropColumn(table, columns.single { it.columnName == "remove_me" }.columnId) } }
                finally { c.beforeSnapshotInsertAction = Runnable {} }
            assertThat(failure).isInstanceOf(DucklakeException::class.java)
            assertThat(state(f)).describedAs("rollback must leave no future column rows or orphan physical table").isEqualTo(before)
            c.createSchema("unrelated")
            assertThat(c.getTableColumns(table, c.currentSnapshotId)).isEqualTo(columns)
            assertThat(f.rows("SELECT id,remove_me FROM `${inlineName(c, table, snapshot)}`")).containsExactly(listOf("7", "8"))
            assertThat(failure).isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("MySQL")
            assertThat(publications.get()).isZero()
        }

        @Test fun everyColumnAndFieldMutationRefusesBeforeIssuingMetadataWrites() = withCatalog(ansiQuotes) { f, c ->
            val table = seedTable(f, c, nested = true)
            val snapshot = c.currentSnapshotId
            val columns = c.getAllColumnsWithParentage(table, snapshot).associateBy { it.columnName }
            // A sorted rename would otherwise mutate sort history even before the column row.
            val sortId = c.getSnapshot(snapshot)!!.nextCatalogId
            f.metadata("INSERT INTO ducklake_sort_info(sort_id,table_id,begin_snapshot) VALUES($sortId,$table,$snapshot)")
            f.metadata("INSERT INTO ducklake_sort_expression VALUES($sortId,$table,0,'id','duckdb','ASC','NULLS_LAST')")
            f.metadata("UPDATE ducklake_snapshot SET next_catalog_id=${sortId + 1} WHERE snapshot_id=$snapshot")
            // Enable trigger creation only inside this test's disposable MySQL server.
            val config = f.readerConfig()
            DriverManager.getConnection(config.catalogDatabaseUrl, "root", config.catalogDatabasePassword).use { connection ->
                connection.createStatement().use { it.execute("SET GLOBAL log_bin_trust_function_creators=1") }
            }
            for (target in listOf("ducklake_column", "ducklake_sort_info", "ducklake_sort_expression")) {
                for (event in listOf("INSERT", "UPDATE", "DELETE")) {
                    f.metadata("CREATE TRIGGER guard_${target}_$event BEFORE $event ON $target FOR EACH ROW " +
                        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='metadata mutation reached before refusal'")
                }
            }
            val before = state(f)
            c.addField(table, listOf("s"), leaf("a"), true)
            assertThat(state(f)).describedAs("existing-field no-op").isEqualTo(before)
            val actions = linkedMapOf<String, () -> Unit>(
                "drop column" to { c.dropColumn(table, columns.getValue("remove_me").columnId) },
                "rename sorted column" to { c.renameColumn(table, columns.getValue("id").columnId, "new `\"id") },
                "retype column" to { c.setColumnType(table, columns.getValue("remove_me").columnId, "varchar") },
                "add field" to { c.addField(table, listOf("s"), leaf("extra"), false) },
                "drop field" to { c.dropField(table, listOf("s", "a")) },
                "retype field" to { c.setFieldType(table, listOf("s", "a"), "int64") },
                "rename field" to { c.renameColumn(table, columns.getValue("a").columnId, "renamed") },
            )
            for (type in listOf("int32", "varchar", "uint64", "int128", "decimal(18,3)")) {
                actions["add $type"] = { c.addColumn(table, TableColumnSpec.leaf("order `\"", type, true)) }
            }
            for ((label, action) in actions) {
                assertThatThrownBy { action() }.describedAs(label)
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("MySQL")
                assertThat(state(f)).describedAs(label).isEqualTo(before)
            }
        }

        @Test fun emptyRegisteredInlineTableStillRequiresSafeDdl() = withCatalog(ansiQuotes) { f, c ->
            val table = seedTable(f, c)
            f.metadata("DELETE FROM `${inlineName(c, table, c.currentSnapshotId)}`")
            val before = state(f)
            assertThatThrownBy { c.addColumn(table, leaf("new_column")) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("MySQL")
            assertThat(state(f)).isEqualTo(before)
        }
    }

    @Test fun competingSnapshotCannotPublishTheRejectedInlineDrop() = withCatalog(true) { f, c ->
        val table = seedTable(f, c)
        val snapshot = c.currentSnapshotId
        val columns = c.getTableColumns(table, snapshot)
        val inlineTables = f.rows("SELECT * FROM ducklake_inlined_data_tables ORDER BY schema_version")
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(c,
            Runnable { c.createSchema("winner") },
            Runnable { c.dropColumn(table, columns.single { it.columnName == "remove_me" }.columnId) })
        assertThat(c.getTableColumns(table, c.currentSnapshotId)).isEqualTo(columns)
        assertThat(c.currentSnapshotId).isEqualTo(snapshot + 1)
        assertThat(result.loserException).isInstanceOf(DucklakeInvalidOperationException::class.java)
        assertThat(result.loserAttemptCount).isEqualTo(1)
        assertThat(f.rows("SELECT * FROM ducklake_inlined_data_tables ORDER BY schema_version")).isEqualTo(inlineTables)
        assertThat(f.rows("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
            "AND table_name LIKE 'ducklake_inlined_data_%' AND table_name <> 'ducklake_inlined_data_tables'"))
            .containsExactly(listOf("1"))
    }

    @Test fun metadataOnlyChangesStillRollbackAndRetryASnapshotCollision() = withCatalog(true) { f, c ->
        val table = seedTable(f, c, inline = false)
        val snapshot = c.currentSnapshotId
        val columns = c.getTableColumns(table, snapshot)
        val removed = columns.single { it.columnName == "remove_me" }.columnId
        val before = state(f)
        c.beforeSnapshotInsertAction = Runnable { throw IllegalStateException("injected publication failure") }
        try { assertThatThrownBy { c.dropColumn(table, removed) }.isInstanceOf(DucklakeException::class.java) }
        finally { c.beforeSnapshotInsertAction = Runnable {} }
        assertThat(state(f)).isEqualTo(before)
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(c,
            Runnable { c.createSchema("winner") }, Runnable { c.dropColumn(table, removed) },
            parkPoint = ConcurrentWriterHarness.ParkPoint.BEFORE_SNAPSHOT_INSERT)
        assertThat(result.loserException).isNull()
        assertThat(result.loserAttemptCount).isEqualTo(2)
        assertThat(c.currentSnapshotId).isEqualTo(snapshot + 2)
        assertThat(c.getTableColumns(table, snapshot + 1).map { it.columnName }).containsExactly("id", "remove_me")
        assertThat(c.getTableColumns(table, snapshot + 2).map { it.columnName }).containsExactly("id")
        assertThat(f.rows("SELECT * FROM ducklake_inlined_data_tables")).isEmpty()
    }

    @Test fun retryRechecksInlineStorageRegisteredByACompetingWriter() = withCatalog(true) { f, c ->
        val table = seedTable(f, c, inline = false)
        val snapshot = c.currentSnapshotId
        val columns = c.getTableColumns(table, snapshot)
        val version = c.getSnapshot(snapshot)!!.schemaVersion
        val name = inlineName(c, table, snapshot)
        f.metadata("CREATE TABLE `$name` (row_id BIGINT,begin_snapshot BIGINT,end_snapshot BIGINT,id INT,remove_me INT)")
        val result = ConcurrentWriterHarness.runWinnerWhileLoserParked(c,
            Runnable {
                // Authored producer publication: first inline storage appears after the reader's snapshot.
                f.metadata("START TRANSACTION; " +
                    "INSERT INTO `$name` VALUES(0,${snapshot + 1},NULL,7,8); " +
                    "INSERT INTO ducklake_inlined_data_tables VALUES($table,'$name',$version); " +
                    "INSERT INTO ducklake_snapshot SELECT snapshot_id+1,CURRENT_TIMESTAMP,schema_version,next_catalog_id,next_file_id " +
                    "FROM ducklake_snapshot WHERE snapshot_id=$snapshot; " +
                    "INSERT INTO ducklake_snapshot_changes(snapshot_id,changes_made) VALUES(${snapshot + 1},'inserted_into_table:$table'); COMMIT")
            },
            Runnable { c.dropColumn(table, columns.single { it.columnName == "remove_me" }.columnId) })
        assertThat(result.loserAttemptCount).isEqualTo(2)
        assertThat(result.loserException).isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("MySQL")
        assertThat(c.currentSnapshotId).isEqualTo(snapshot + 1)
        assertThat(c.getTableColumns(table, c.currentSnapshotId)).isEqualTo(columns)
        assertThat(f.rows("SELECT remove_me FROM `$name`")).containsExactly(listOf("8"))
    }

    @Test fun mysqlDdlCannotFallThroughToDuckDbRendering() {
        for (type in listOf("int32", "uint64", "int128", "varchar", "decimal(18,3)")) {
            val column = InlinedTypeNode(type, "order `\"")
            assertThatThrownBy { InlinedDataTables.createTableSql("new_inline", listOf(column), SQLDialect.MYSQL) }
                .describedAs(type).isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("MySQL")
        }
    }

    companion object {
        private fun withCatalog(ansiQuotes: Boolean, action: (Final11MySqlFixture, JdbcDucklakeCatalog) -> Unit) {
            Final11MySqlFixture().use { f ->
                f.native("SELECT 1", version = "1.0")
                val config = f.readerConfig().apply {
                    enableFinal11Reads = false
                    maxCatalogConnections = 3
                    if (ansiQuotes) {
                        val variables = URLEncoder.encode("sql_mode='ANSI_QUOTES,STRICT_TRANS_TABLES'", StandardCharsets.UTF_8)
                        catalogDatabaseUrl += (if ('?' in catalogDatabaseUrl!!) "&" else "?") + "sessionVariables=$variables"
                    }
                }
                DriverManager.getConnection(config.catalogDatabaseUrl, config.catalogDatabaseUser, config.catalogDatabasePassword).use { connection ->
                    connection.createStatement().use { statement -> statement.executeQuery("SELECT @@sql_mode").use { rows ->
                        check(rows.next())
                        assertThat("ANSI_QUOTES" in rows.getString(1).split(',')).isEqualTo(ansiQuotes)
                    } }
                }
                JdbcDucklakeCatalog(config).use { c -> action(f, c) }
            }
        }

        private fun leaf(name: String) = TableColumnSpec.leaf(name, "int32", true)

        private fun seedTable(f: Final11MySqlFixture, c: JdbcDucklakeCatalog, nested: Boolean = false, inline: Boolean = true): Long {
            val columns = mutableListOf(leaf("id"), leaf("remove_me"))
            if (nested) columns.add(TableColumnSpec("s", "struct", true, listOf(leaf("a"), leaf("b"))))
            c.createTable("main", "t", columns, null, null)
            val snapshot = c.currentSnapshotId
            val table = c.getTable("main", "t", snapshot)!!.tableId
            if (inline) {
                val name = inlineName(c, table, snapshot)
                f.metadata("CREATE TABLE `$name` (row_id BIGINT, begin_snapshot BIGINT, end_snapshot BIGINT, " +
                    "id INT,remove_me INT${if (nested) ",s LONGTEXT" else ""})")
                f.metadata("INSERT INTO `$name` (row_id,begin_snapshot,id,remove_me) VALUES(0,$snapshot,7,8)")
                f.metadata("INSERT INTO ducklake_inlined_data_tables(table_id,table_name,schema_version) " +
                    "VALUES($table,'$name',${c.getSnapshot(snapshot)!!.schemaVersion})")
            }
            return table
        }

        private fun inlineName(c: JdbcDucklakeCatalog, table: Long, snapshot: Long): String =
            InlinedDataTables.tableName(table, c.getSnapshot(snapshot)!!.schemaVersion)

        private fun state(f: Final11MySqlFixture): List<List<List<String?>>> = listOf(
            "SELECT * FROM ducklake_column ORDER BY column_id,begin_snapshot",
            "SELECT * FROM ducklake_snapshot ORDER BY snapshot_id",
            "SELECT * FROM ducklake_snapshot_changes ORDER BY snapshot_id",
            "SELECT * FROM ducklake_schema_versions ORDER BY table_id,begin_snapshot",
            "SELECT * FROM ducklake_inlined_data_tables ORDER BY table_id,schema_version",
            "SELECT * FROM ducklake_sort_info ORDER BY sort_id",
            "SELECT * FROM ducklake_sort_expression ORDER BY sort_id,sort_key_index",
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name",
        ).map(f::rows)
    }
}
