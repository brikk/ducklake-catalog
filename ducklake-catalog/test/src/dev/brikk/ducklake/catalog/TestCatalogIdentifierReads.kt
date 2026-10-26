package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.TooManyRowsException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Supplier

internal class TestCatalogIdentifierReads {
    @Nested inner class Local : Contract({ ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ ReadAcceptanceFixture.Postgres() })
    @Nested inner class MySql : Contract({ ReadAcceptanceFixture.MySql() })

    abstract class Contract(private val fixture: () -> ReadAcceptanceFixture) {
        @Test fun pointLookupsPreserveAsciiEquivalenceAndDistinctUnicodeNames() {
            fixture().use { f ->
                f.native("CREATE SCHEMA lake.\"Ä\"; CREATE SCHEMA lake.\"ä\"; CREATE SCHEMA lake.\"MiXeD\"; " +
                    "CREATE SCHEMA lake.\"onlyÄ\"; CREATE TABLE lake.main.\"Ä\"(i INTEGER); " +
                    "CREATE TABLE lake.main.\"ä\"(i INTEGER); CREATE TABLE lake.main.\"MiXeD\"(i INTEGER); " +
                    "CREATE TABLE lake.main.\"onlyÄ\"(i INTEGER)")
                // Deliberately insensitive metadata collations must not redefine DuckLake identifiers.
                if (f is ReadAcceptanceFixture.MySql) {
                    f.metadata("ALTER TABLE ducklake_schema MODIFY schema_name TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; " +
                        "ALTER TABLE ducklake_table MODIFY table_name TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                }
                if (f is ReadAcceptanceFixture.Postgres) {
                    f.metadata("CREATE COLLATION identifier_ci (provider=icu,locale='und-u-ks-level1',deterministic=false); " +
                        "ALTER TABLE ducklake_schema ALTER COLUMN schema_name TYPE VARCHAR COLLATE identifier_ci; " +
                        "ALTER TABLE ducklake_table ALTER COLUMN table_name TYPE VARCHAR COLLATE identifier_ci")
                }
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val schemas = c.listSchemas(snapshot).associateBy { it.schemaName }
                    val tables = c.listTables(schemas.getValue("main").schemaId, snapshot).associateBy { it.tableName }
                    for (name in listOf("Ä", "ä", "MiXeD", "onlyÄ")) {
                        assertThat(c.getSchema(name, snapshot)).isEqualTo(schemas.getValue(name))
                        assertThat(c.getTable("main", name, snapshot)).isEqualTo(tables.getValue(name))
                    }
                    assertThat(c.getSchema("MIXED", snapshot)).isEqualTo(schemas.getValue("MiXeD"))
                    assertThat(c.getTable("MAIN", "mixed", snapshot)).isEqualTo(tables.getValue("MiXeD"))
                    assertThat(c.getSchema("onlyä", snapshot)).isNull()
                    assertThat(c.getTable("main", "onlyä", snapshot)).isNull()
                    assertThat(c.getSchema("missing", snapshot)).isNull()
                    assertThat(c.getTable("missing", "Ä", snapshot)).isNull()
                }) }
            }
        }

        @Test fun genuineActiveDuplicatesRejectButExpiredNamesDoNotCollide() {
            fixture().use { f ->
                f.native("CREATE SCHEMA lake.a; CREATE SCHEMA lake.b; CREATE SCHEMA lake.c; " +
                    "CREATE TABLE lake.main.a(i INTEGER); CREATE TABLE lake.main.b(i INTEGER); CREATE TABLE lake.main.c(i INTEGER)")
                f.metadata("UPDATE ducklake_schema SET schema_name='DUP' WHERE schema_name IN ('a','b','c')")
                f.metadata("UPDATE ducklake_table SET table_name='DUP' WHERE table_name IN ('a','b','c')")
                f.reader().use { c ->
                    assertThatThrownBy { c.readSession(Supplier { c.getSchema("dup", c.currentSnapshotId) }) }
                        .isInstanceOf(TooManyRowsException::class.java)
                    assertThatThrownBy { c.readSession(Supplier { c.getTable("main", "dup", c.currentSnapshotId) }) }
                        .isInstanceOf(TooManyRowsException::class.java)
                }
                // Authored metadata fixture: retire two of each set at the current snapshot.
                f.metadata("UPDATE ducklake_schema SET end_snapshot=(SELECT max(snapshot_id) FROM ducklake_snapshot) " +
                    "WHERE schema_name='DUP' AND schema_id>(SELECT min_id FROM (SELECT min(schema_id) AS min_id " +
                    "FROM ducklake_schema WHERE schema_name='DUP') chosen)")
                f.metadata("UPDATE ducklake_table SET end_snapshot=(SELECT max(snapshot_id) FROM ducklake_snapshot) " +
                    "WHERE table_name='DUP' AND table_id>(SELECT min_id FROM (SELECT min(table_id) AS min_id FROM ducklake_table) chosen)")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThat(c.getSchema("dup", c.currentSnapshotId)).isNotNull()
                    assertThat(c.getTable("main", "dup", c.currentSnapshotId)).isNotNull()
                }) }
            }
        }
    }
}
