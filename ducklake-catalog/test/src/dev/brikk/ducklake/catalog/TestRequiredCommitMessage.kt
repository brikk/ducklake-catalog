package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/** N13: no author/message capability means refusal, not bypass, when native metadata requires it. */
internal class TestRequiredCommitMessage {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        private fun setPolicy(f: LegacyWriteFixture, required: Boolean) {
            // Local DuckDB cannot simultaneously attach one file as native metadata and a JDBC database.
            val key = if (f is LegacyWriteFixture.MySql) "`key`" else "\"key\""
            f.metadata("DELETE FROM ducklake_metadata WHERE $key='require_commit_message' AND scope IS NULL")
            f.metadata("INSERT INTO ducklake_metadata VALUES('require_commit_message','$required',NULL,NULL)")
        }

        @Test fun requiredPolicyRejectsNativeAndCatalogCreateWithoutPublication(): Unit = fixture().use { f ->
            f.seed()
            f.native("CALL lake.set_option('require_commit_message',true)")
            assertThatThrownBy { f.native("CREATE SCHEMA lake.native_without_message") }
                .isInstanceOf(SQLException::class.java).hasMessageContaining("Commit Information")
            f.reader().use { c ->
                val before = c.currentSnapshotId
                val changes = c.listSnapshotChanges()
                val publication = AtomicInteger()
                c.beforeSnapshotInsertAction = Runnable { publication.incrementAndGet() }
                assertThatThrownBy { c.createSchema("jvm_without_message") }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("require_commit_message")
                assertThat(publication.get()).isZero()
                assertThat(c.currentSnapshotId).isEqualTo(before)
                assertThat(c.listSnapshotChanges()).isEqualTo(changes)
                assertThat(c.getSchema("native_without_message", before)).isNull()
                assertThat(c.getSchema("jvm_without_message", before)).isNull()
            }
        }

        @Test fun livePolicyChangeAlsoBlocksTableViewAnalyzeAndFileSnapshots(@TempDir root: Path): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                c.createTable("main", "existing", listOf(TableColumnSpec.leaf("i", "int32", true)), null, null)
                val table = c.getTable("main", "existing", c.currentSnapshotId)!!
                val column = c.getTableColumns(table.tableId, c.currentSnapshotId).single().columnId
                val path = root.resolve("caller-owned.parquet")
                DriverManager.getConnection("jdbc:duckdb:").use { conn -> conn.createStatement().use { s ->
                    s.execute("COPY (SELECT 1::INTEGER AS i) TO '${path.toString().replace("'", "''")}' (FORMAT PARQUET,FIELD_IDS {i:$column})")
                } }
                val fragment = DucklakeWriteFragment(path.toString(), Files.size(path), 0, 1, emptyList())
                setPolicy(f, true)
                val before = c.currentSnapshotId
                val publication = AtomicInteger()
                c.beforeSnapshotInsertAction = Runnable { publication.incrementAndGet() }
                val operations: List<() -> Unit> = listOf(
                    { c.createSchema("blocked_schema") },
                    { c.createTable("main", "blocked_table", listOf(TableColumnSpec.leaf("i", "int32", true)), null, null) },
                    { c.createView("main", "blocked_view", "SELECT 1", "duckdb", emptyList(), emptyMap()) },
                    { c.analyzeTable(table.tableId) },
                    { c.commitInsert(table.tableId, listOf(fragment)) },
                )
                for (operation in operations) {
                    assertThatThrownBy { operation() }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                        .hasMessageContaining("require_commit_message")
                }
                assertThat(publication.get()).isZero()
                assertThat(c.currentSnapshotId).isEqualTo(before)
                assertThat(c.getDataFiles(table.tableId, before)).isEmpty()
                assertThat(c.getTable("main", "blocked_table", before)).isNull()
                assertThat(c.getView("main", "blocked_view", before)).isNull()
                assertThat(Files.isRegularFile(path)).isTrue() // rejected registration never deletes caller output
            }
        }

        @Test fun missingFalseAndScopedPoliciesAllowWritesAndRequiredPolicyIsNotCached(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                c.createSchema("default_allows")
                setPolicy(f, false)
                c.createSchema("false_allows")
                f.metadata("INSERT INTO ducklake_metadata VALUES('require_commit_message','true','table',987654)")
                c.createSchema("scoped_does_not_require_global_message")
                f.metadata("DELETE FROM ducklake_metadata WHERE scope='table' AND scope_id=987654")
                setPolicy(f, true)
                assertThatThrownBy { c.createSchema("required_rejects") }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                setPolicy(f, false)
                c.createSchema("disabled_allows")
                assertThat(c.getSchema("disabled_allows", c.currentSnapshotId)).isNotNull()
                assertThat(c.getSchema("required_rejects", c.currentSnapshotId)).isNull()
            }
        }

        @Test fun nonSnapshotMaintenanceDoesNotRequireCommitInformation(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                val old = c.currentSnapshotId
                c.createSchema("keep_schema")
                val before = c.currentSnapshotId
                setPolicy(f, true)
                c.expireSnapshots(setOf(old))
                assertThat(c.getSnapshot(old)).isNull()
                assertThat(c.currentSnapshotId).isEqualTo(before)
                assertThat(c.getSchema("keep_schema", before)).isNotNull()
            }
        }
    }

    @Test fun retryRechecksARequiredPolicyBeforeRunningItsActionAgain(): Unit = LegacyWriteFixture.Postgres().use { f ->
        f.seed()
        f.native("CALL lake.set_option('require_commit_message',false)")
        f.reader().use { c ->
            val before = c.currentSnapshotId
            val attempts = AtomicInteger()
            val publication = AtomicInteger()
            c.beforeWriteTransactionAction = Runnable {
                if (attempts.incrementAndGet() == 2) f.native("CALL lake.set_option('require_commit_message',true)")
            }
            c.beforeSnapshotInsertAction = Runnable {
                if (publication.incrementAndGet() == 1) throw TransactionConflictException("forced retry before publication", null)
            }
            assertThatThrownBy { c.createSchema("retry_without_message") }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("require_commit_message")
            assertThat(attempts.get()).isEqualTo(2)
            assertThat(publication.get()).isEqualTo(1)
            assertThat(c.currentSnapshotId).isEqualTo(before)
            assertThat(c.getSchema("retry_without_message", before)).isNull()
        }
    }

    @Test fun nativeProducerCanStillProvideRequiredInformation(): Unit = LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB).use { f ->
        f.seed()
        f.native("CALL lake.set_option('require_commit_message',true)")
        f.native("BEGIN; CREATE SCHEMA lake.with_information; CALL lake.set_commit_message('native-author','explicit-message'); COMMIT")
        f.reader().use { c ->
            assertThat(c.getSchema("with_information", c.currentSnapshotId)).isNotNull()
            assertThat(c.listSnapshotChanges().map { it.commitMessage }).contains("explicit-message")
        }
    }
}
