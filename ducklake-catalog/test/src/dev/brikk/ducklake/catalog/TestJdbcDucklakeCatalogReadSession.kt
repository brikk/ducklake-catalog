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
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.function.Supplier

/**
 * [DucklakeCatalog.readSession] (TODO-rectify-from-eval.md R-D1): every read on the calling thread
 * inside the block sees ONE snapshot of the catalog, even while another writer commits or physically
 * deletes rows in between; other threads and reads after the block see the latest state; writes
 * inside a session are refused; nesting reuses the outer session.
 */
class TestJdbcDucklakeCatalogReadSession {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var reader: JdbcDucklakeCatalog
        private lateinit var writer: JdbcDucklakeCatalog

        private fun open(): JdbcDucklakeCatalog = JdbcDucklakeCatalog(
            DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 4
            },
        )

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "read-session")
            reader = open()
            writer = open()
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::reader.isInitialized) reader.close()
            if (::writer.isInitialized) writer.close()
            if (::server.isInitialized) server.close()
        }
    }

    @Test
    fun readsInsideASessionSeeOneSnapshotDespiteConcurrentCommits() {
        val before = reader.currentSnapshotId
        var insideFirst = -1L
        var insideAfterCommit = -1L
        var insideSchemas: List<String> = emptyList()
        var otherThreadSaw = -1L
        reader.readSession(
            Supplier {
                insideFirst = reader.currentSnapshotId // snapshot taken here
                writer.createSchema("committed_during_session") // another writer commits
                insideAfterCommit = reader.currentSnapshotId
                insideSchemas = reader.listSchemas(reader.currentSnapshotId).map { it.schemaName }
                // The pin is per thread: a different thread reads the live catalog.
                val pool = Executors.newSingleThreadExecutor()
                try {
                    otherThreadSaw = pool.submit<Long> { reader.currentSnapshotId }.get()
                }
                finally {
                    pool.shutdownNow()
                }
            },
        )
        assertThat(insideFirst).isEqualTo(before)
        assertThat(insideAfterCommit).`as`("pinned snapshot: the concurrent commit is invisible").isEqualTo(before)
        assertThat(insideSchemas).doesNotContain("committed_during_session")
        assertThat(otherThreadSaw).`as`("other threads are not pinned").isEqualTo(before + 1)
        assertThat(reader.currentSnapshotId).`as`("after the block the latest state is visible").isEqualTo(before + 1)
        assertThat(reader.listSchemas(reader.currentSnapshotId).map { it.schemaName }).contains("committed_during_session")
    }

    @Test
    fun physicallyDeletedRowsStayVisibleToTheSessionThatAlreadyReadThem() {
        val snapshot = reader.currentSnapshotId
        val table = reader.getTable("test_schema", "partitioned_table", snapshot)!!
        val filesBefore = reader.getDataFiles(table.tableId, snapshot)
        assertThat(filesBefore.size).isGreaterThanOrEqualTo(2)
        val victim = filesBefore.first()

        var filesInside: List<DucklakeDataFile> = emptyList()
        var partitionsInside: Map<Long, List<DucklakeFilePartitionValue>> = emptyMap()
        reader.readSession(
            Supplier {
                val s = reader.currentSnapshotId
                val first = reader.getDataFiles(table.tableId, s)
                assertThat(first).hasSize(filesBefore.size)
                // Simulates rewriteDataFilesPartial / consolidation / expire: a row is DELETED, not
                // end-snapshotted, so an unpinned second read would no longer see it.
                DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
                    c.createStatement().use { st ->
                        st.executeUpdate("DELETE FROM ducklake_file_partition_value WHERE data_file_id = ${victim.dataFileId}")
                        st.executeUpdate("DELETE FROM ducklake_file_column_stats WHERE data_file_id = ${victim.dataFileId}")
                        st.executeUpdate("DELETE FROM ducklake_data_file WHERE data_file_id = ${victim.dataFileId}")
                    }
                }
                filesInside = reader.getDataFiles(table.tableId, s)
                partitionsInside = reader.getFilePartitionValues(table.tableId, s)
            },
        )
        assertThat(filesInside.map { it.dataFileId }).`as`("second read inside the session still consistent")
            .containsExactlyInAnyOrderElementsOf(filesBefore.map { it.dataFileId })
        assertThat(partitionsInside.keys).contains(victim.dataFileId)
        assertThat(reader.getDataFiles(table.tableId, snapshot).map { it.dataFileId })
            .`as`("outside the session the deletion is visible")
            .doesNotContain(victim.dataFileId)
    }

    @Test
    fun writesAreRefusedInsideASessionAndNestingReusesIt() {
        reader.readSession(
            Supplier {
                val outer = reader.currentSnapshotId
                assertThatThrownBy { reader.createSchema("nope") }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("readSession")
                assertThatThrownBy { reader.analyzeTable(reader.getTable("test_schema", "simple_table", outer)!!.tableId) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                assertThatThrownBy { reader.removeScheduledFileRows(listOf(1L)) }
                    .isInstanceOf(DucklakeInvalidOperationException::class.java)
                val nested = reader.readSession(Supplier { reader.currentSnapshotId })
                assertThat(nested).isEqualTo(outer)
            },
        )
        // The failed writes left nothing behind and the session is fully released.
        assertThat(reader.getSchema("nope", reader.currentSnapshotId)).isNull()
        reader.createSchema("after_session_ok")
        assertThat(reader.getSchema("after_session_ok", reader.currentSnapshotId)).isNotNull()
    }
}
