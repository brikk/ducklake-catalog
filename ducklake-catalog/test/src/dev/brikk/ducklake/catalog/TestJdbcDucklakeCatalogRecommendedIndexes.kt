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

import dev.brikk.ducklake.catalog.schema.Public
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * [DucklakeRecommendedIndexes] on PostgreSQL: `ensureRecommendedIndexes` creates exactly the
 * recommended set once, reports them as existing afterwards, and the indexed catalog keeps
 * working for both this library and stock DuckDB (indexes change plans, never results).
 * Also guards the recommended set itself against drift from the generated schema and the
 * user-facing DDL listing.
 */
class TestJdbcDucklakeCatalogRecommendedIndexes {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "recommended-indexes")
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = isolated.jdbcUrl
                catalogDatabaseUser = isolated.user
                catalogDatabasePassword = isolated.password
                dataPath = isolated.dataDir.toAbsolutePath().toString()
                maxCatalogConnections = 5
            }
            catalog = JdbcDucklakeCatalog(config)
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            if (::catalog.isInitialized) {
                catalog.close()
            }
            if (::server.isInitialized) {
                server.close()
            }
        }
    }

    private fun <T> withPostgres(block: (Connection) -> T): T =
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use(block)

    private fun <T> withDuckDb(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake")
                statement.execute("LOAD ducklake")
                statement.execute("INSTALL postgres")
                statement.execute("LOAD postgres")
                statement.execute(
                    "ATTACH '" + isolated.duckDbAttachUri.replace("'", "''") + "' AS lake (DATA_PATH '" +
                        isolated.dataDir.toAbsolutePath().toString().replace("'", "''") + "')",
                )
            }
            block(connection)
        }

    private fun postgresIndexNames(): Set<String> = withPostgres { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname LIKE 'idx_ducklake_%'").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
            }
        }
    }

    @Test
    fun ensureRecommendedIndexesCreatesTheSetOnceAndThenReportsItAsExisting() {
        val expected = DucklakeRecommendedIndexes.ALL.map { it.name }
        assertThat(postgresIndexNames()).`as`("a freshly bootstrapped catalog has none of them").isEmpty()

        val first = catalog.ensureRecommendedIndexes()
        assertThat(first.skippedReason).isNull()
        assertThat(first.created).containsExactlyElementsOf(expected)
        assertThat(first.existing).isEmpty()
        assertThat(postgresIndexNames()).containsExactlyInAnyOrderElementsOf(expected)

        val second = catalog.ensureRecommendedIndexes()
        assertThat(second.created).isEmpty()
        assertThat(second.existing).containsExactlyElementsOf(expected)
        assertThat(postgresIndexNames()).containsExactlyInAnyOrderElementsOf(expected)

        // Every index actually keys the columns it advertises.
        withPostgres { c ->
            for (index in DucklakeRecommendedIndexes.ALL) {
                c.metaData.getIndexInfo(null, "public", index.table, false, false).use { rs ->
                    val columns = generateSequence { if (rs.next()) rs else null }
                        .filter { it.getString("INDEX_NAME") == index.name }
                        .map { it.getShort("ORDINAL_POSITION") to it.getString("COLUMN_NAME") }
                        .sortedBy { it.first }.map { it.second }.toList()
                    assertThat(columns).`as`(index.name).containsExactlyElementsOf(index.columns)
                }
            }
        }

        // The indexed catalog is still a working DuckLake for this library ...
        catalog.createTable("test_schema", "indexed_t", listOf(TableColumnSpec.leaf("id", "int32", true)), null, null)
        val tableId = catalog.getTable("test_schema", "indexed_t", catalog.currentSnapshotId)!!.tableId
        val idColumn = catalog.getTableColumns(tableId, catalog.currentSnapshotId).single().columnId
        catalog.commitInsert(tableId, listOf(DucklakeWriteFragment("indexed.parquet", 100L, 0L, 2L,
            listOf(DucklakeFileColumnStats(idColumn, 8L, 2L, 0L, "1", "2", false)))))
        val snapshot = catalog.currentSnapshotId
        assertThat(catalog.getDataFiles(tableId, snapshot)).hasSize(1)
        assertThat(catalog.getColumnStats(tableId, snapshot).single().maxValue).isEqualTo("2")
        assertThat(catalog.getTableStats(tableId)!!.recordCount).isEqualTo(2L)
        catalog.dropTable("test_schema", "indexed_t")
        assertThat(catalog.getTable("test_schema", "indexed_t", catalog.currentSnapshotId)).isNull()
        // ... and for stock DuckDB, writes included.
        withDuckDb { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE lake.test_schema.native_indexed(id INTEGER)")
                st.execute("INSERT INTO lake.test_schema.native_indexed VALUES (1), (2), (3)")
                st.executeQuery("SELECT count(*) FROM lake.test_schema.native_indexed").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isEqualTo(3L)
                }
                st.execute("DROP TABLE lake.test_schema.native_indexed")
            }
        }
        assertThat(postgresIndexNames()).`as`("nothing above disturbed the indexes").containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun recommendedSetMatchesTheGeneratedSchema() {
        val tables = Public.PUBLIC.tables.associateBy { it.name }
        val names = DucklakeRecommendedIndexes.ALL.map { it.name }
        assertThat(names).doesNotHaveDuplicates()
        for (index in DucklakeRecommendedIndexes.ALL) {
            assertThat(index.name).`as`("PostgreSQL identifier limit").hasSizeLessThanOrEqualTo(63)
            val table = tables[index.table] ?: error("${index.name}: unknown table ${index.table}")
            val columns = table.fields().map { it.name }
            assertThat(columns).`as`("${index.name} columns").containsAll(index.columns)
            assertThat(index.createStatement(ifNotExists = true))
                .isEqualTo("CREATE INDEX IF NOT EXISTS ${index.name} ON ${index.table} (${index.columns.joinToString(", ")})")
        }
        assertThat(DucklakeRecommendedIndexes.supports(SQLDialect.POSTGRES)).isTrue()
        assertThat(DucklakeRecommendedIndexes.supports(SQLDialect.MYSQL)).isTrue()
        assertThat(DucklakeRecommendedIndexes.supports(SQLDialect.DUCKDB)).isFalse()
        assertThat(DucklakeRecommendedIndexes.createStatements(SQLDialect.DUCKDB)).isEmpty()
        assertThat(DucklakeRecommendedIndexes.createStatements(SQLDialect.MYSQL))
            .allSatisfy { assertThat(it).startsWith("CREATE INDEX idx_ducklake_").doesNotContain("IF NOT EXISTS") }
        assertThat(DucklakeRecommendedIndexes.createStatements(SQLDialect.POSTGRES))
            .allSatisfy { assertThat(it).startsWith("CREATE INDEX IF NOT EXISTS idx_ducklake_") }
    }

    @Test
    fun userDocumentationListsExactlyTheRecommendedSet() {
        val doc = locate(Paths.get("docs", "RECOMMENDED-INDEXES.md"))
        val text = Files.readString(doc)
        val documented = Regex("""^CREATE INDEX IF NOT EXISTS .*;$""", RegexOption.MULTILINE).findAll(text).map { it.value.removeSuffix(";") }.toList()
        assertThat(documented).containsExactlyElementsOf(DucklakeRecommendedIndexes.createStatements(SQLDialect.POSTGRES))
    }

    private fun locate(relative: Path): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }
}
