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

/**
 * Views carry their output column names in `ducklake_view.column_aliases` (spec quoted
 * list) and engine metadata in `ducklake_tag` rows keyed by `view_id` — the exact shape
 * upstream loads. These tests pin:
 *
 *  1. round-trip of aliases + tags through create / replace / rename / drop, with tags
 *     end-snapshotted in the same snapshot as the view row (time travel sees the old set);
 *  2. the interop property that motivated the design: a catalog holding a view written by
 *     this library still ATTACHes in stock DuckDB, `duckdb_views()` shows the aliases, and
 *     the `comment` tag surfaces as the DuckDB view comment;
 *  3. a `column_aliases` value that is NOT a quoted list (a foreign writer's payload) is
 *     flagged per view via [DucklakeView.malformedColumnAliases] — never silently "no
 *     aliases", and never fatal to listing the rest of the schema.
 */
class TestJdbcDucklakeCatalogViewTags {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: JdbcDucklakeCatalogTestDataGenerator.IsolatedCatalog
        private lateinit var catalog: JdbcDucklakeCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUpClass() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = JdbcDucklakeCatalogTestDataGenerator.generateIsolatedCatalog(server, "view-tags")
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

    private fun view(name: String, snapshotId: Long = catalog.currentSnapshotId): DucklakeView? =
        catalog.getView("test_schema", name, snapshotId)

    @Test
    fun createReplaceRenameDropRoundTripWithTagsInOneSnapshot() {
        val tags = mapOf(
            DucklakeView.COMMENT_TAG_KEY to "first comment",
            "engine.column_types" to "\"integer\",\"varchar\"",
            "engine.owner" to "alice",
            "engine.skipped" to null, // null values are not written
        )
        catalog.createView("test_schema", "tagged_view", "SELECT 1 AS a, 'x' AS b", "engine", listOf("a", "b"), tags)
        val s1 = catalog.currentSnapshotId

        val created = view("tagged_view")!!
        assertThat(created.dialect).isEqualTo("engine")
        assertThat(created.columnAliases).containsExactly("a", "b")
        assertThat(created.tags).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                DucklakeView.COMMENT_TAG_KEY to "first comment",
                "engine.column_types" to "\"integer\",\"varchar\"",
                "engine.owner" to "alice",
            ),
        )
        assertThat(created.comment).isEqualTo("first comment")
        assertThat(created.beginSnapshot).isEqualTo(s1)

        // Replace: new aliases, a dropped tag, a changed tag, a new tag.
        catalog.replaceViewMetadata(
            "test_schema", "tagged_view", "SELECT 2 AS c", "engine", listOf("c"),
            mapOf(DucklakeView.COMMENT_TAG_KEY to "second comment", "engine.new" to "yes"),
        )
        val s2 = catalog.currentSnapshotId
        assertThat(s2).isGreaterThan(s1)

        val replaced = view("tagged_view")!!
        assertThat(replaced.viewId).isEqualTo(created.viewId)
        assertThat(replaced.viewUuid).isEqualTo(created.viewUuid)
        assertThat(replaced.sql).isEqualTo("SELECT 2 AS c")
        assertThat(replaced.columnAliases).containsExactly("c")
        assertThat(replaced.tags).containsExactlyInAnyOrderEntriesOf(
            mapOf(DucklakeView.COMMENT_TAG_KEY to "second comment", "engine.new" to "yes"),
        )
        // Time travel to s1 sees the ORIGINAL row and the ORIGINAL tag set — tags were
        // end-snapshotted at s2 together with the view row, not left dangling.
        val atS1 = view("tagged_view", s1)!!
        assertThat(atS1.columnAliases).containsExactly("a", "b")
        assertThat(atS1.tags).containsOnlyKeys(DucklakeView.COMMENT_TAG_KEY, "engine.column_types", "engine.owner")
        assertThat(atS1.comment).isEqualTo("first comment")

        // Rename keeps view_id, so tags ride along untouched.
        catalog.renameView("test_schema", "tagged_view", "test_schema", "tagged_view_renamed")
        assertThat(view("tagged_view")).isNull()
        val renamed = view("tagged_view_renamed")!!
        assertThat(renamed.viewId).isEqualTo(created.viewId)
        assertThat(renamed.columnAliases).containsExactly("c")
        assertThat(renamed.tags).isEqualTo(replaced.tags)

        // Drop end-snapshots tags with the row; time travel still sees them.
        catalog.dropView("test_schema", "tagged_view_renamed")
        val s4 = catalog.currentSnapshotId
        assertThat(view("tagged_view_renamed")).isNull()
        assertThat(view("tagged_view_renamed", s4 - 1)!!.tags).isEqualTo(replaced.tags)
        assertThat(catalog.listViews(schemaId(), s4).map { it.viewName }).doesNotContain("tagged_view_renamed")
    }

    @Test
    fun listViewsReturnsTagsForEveryViewInOneCall() {
        catalog.createView("test_schema", "lv_a", "SELECT 1", "engine", listOf("one"), mapOf("k" to "va"))
        catalog.createView("test_schema", "lv_b", "SELECT 2", "engine", emptyList(), emptyMap())
        catalog.createView("test_schema", "lv_c", "SELECT 3", "engine", listOf("x", "y"), mapOf("k" to "vc", "k2" to "w"))

        val byName = catalog.listViews(schemaId(), catalog.currentSnapshotId).associateBy { it.viewName }
        assertThat(byName["lv_a"]!!.tags).containsExactlyInAnyOrderEntriesOf(mapOf("k" to "va"))
        assertThat(byName["lv_a"]!!.columnAliases).containsExactly("one")
        // A view without tags is still listed (LEFT JOIN), with empty aliases and tags.
        assertThat(byName["lv_b"]!!.tags).isEmpty()
        assertThat(byName["lv_b"]!!.columnAliases).isEmpty()
        assertThat(byName["lv_c"]!!.tags).containsExactlyInAnyOrderEntriesOf(mapOf("k" to "vc", "k2" to "w"))
        assertThat(byName["lv_c"]!!.columnAliases).containsExactly("x", "y")
    }

    @Test
    fun duckDbAttachesCatalogAndSeesAliasesAndComment() {
        // A view in a dialect DuckDB does not speak, with a quote-bearing alias and a comment.
        catalog.createView(
            "test_schema", "foreign_view",
            "SELECT id, name FROM test_schema.simple_table WHERE active", "not-duckdb",
            listOf("id", "the \"name\""),
            mapOf(DucklakeView.COMMENT_TAG_KEY to "written by another engine", "other.meta" to "{\"json\":true}"),
        )

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
                // The catalog loads (this is exactly what a non-quoted-list column_aliases breaks).
                statement.executeQuery("SELECT count(*) FROM lake.test_schema.simple_table").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isGreaterThan(0)
                }
                statement.executeQuery(
                    "SELECT comment, sql FROM duckdb_views() WHERE database_name = 'lake' AND view_name = 'foreign_view'",
                ).use { rs ->
                    assertThat(rs.next()).`as`("DuckDB lists the foreign-dialect view").isTrue()
                    assertThat(rs.getString(1)).isEqualTo("written by another engine")
                    // duckdb_views().sql is DuckDB's CREATE VIEW rendering; the aliases we stored
                    // appear as the view's column list, quote escaped per DuckDB rules.
                    assertThat(rs.getString(2)).contains("foreign_view").contains("\"the \"\"name\"\"\"")
                }
            }
        }
    }

    @Test
    fun nonQuotedListColumnAliasesIsFlaggedNotSwallowed() {
        catalog.createView("test_schema", "poisoned_view", "SELECT 1 AS a", "engine", listOf("a"), emptyMap())
        val viewId = view("poisoned_view")!!.viewId
        // Simulate a foreign writer stuffing a payload into column_aliases.
        DriverManager.getConnection(isolated.jdbcUrl, isolated.user, isolated.password).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "UPDATE ducklake_view SET column_aliases = '{\"originalSql\":\"SELECT 1\"}' " +
                        "WHERE view_id = $viewId AND end_snapshot IS NULL",
                )
            }
        }
        val poisoned = view("poisoned_view")!!
        assertThat(poisoned.malformedColumnAliases).isEqualTo("{\"originalSql\":\"SELECT 1\"}")
        assertThat(poisoned.columnAliases).isEmpty()
        // The rest of the schema keeps listing; the poisoned view is flagged, not thrown.
        val listed = catalog.listViews(schemaId(), catalog.currentSnapshotId).associateBy { it.viewName }
        assertThat(listed["poisoned_view"]!!.malformedColumnAliases).isNotNull()
        // Renaming would re-insert the row and silently launder the payload — refused.
        assertThatThrownBy { catalog.renameView("test_schema", "poisoned_view", "test_schema", "laundered") }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java)
            .hasMessageContaining("non-conformant writer")
        // Dropping is allowed (it is how an operator gets rid of it).
        catalog.dropView("test_schema", "poisoned_view")
        assertThat(view("poisoned_view")).isNull()
    }

    private fun schemaId(): Long = catalog.getSchema("test_schema", catalog.currentSnapshotId)!!.schemaId
}
