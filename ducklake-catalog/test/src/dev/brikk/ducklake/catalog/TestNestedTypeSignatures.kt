package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** N03: the catalog's string projection must not turn field-name punctuation into type syntax. */
internal class TestNestedTypeSignatures {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun nativeNestedFieldNamesHaveUnambiguousSignaturesAndUnchangedParentage(): Unit = fixture().use { f ->
            f.seed()
            f.native("CREATE TABLE lake.punctuation(s STRUCT(\"a,b\" INTEGER,\"x:y\" VARCHAR,\"angle<>\" BIGINT," +
                "\"q\"\"uote\" INTEGER,\" spaced \" VARCHAR,\"雪\" INTEGER)," +
                "l STRUCT(\"a,b\" INTEGER)[],m MAP(VARCHAR,STRUCT(\"x:y\" INTEGER)))")
            f.reader().use { c ->
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "punctuation", snapshot)!!
                val roots = c.getTableColumns(table.tableId, snapshot).associateBy { it.columnName }
                assertThat(roots.getValue("s").columnType).isEqualTo(
                    "struct<\"a,b\":int32,\"x:y\":varchar,\"angle<>\":int64,\"q\"\"uote\":int32,\" spaced \":varchar,\"雪\":int32>")
                assertThat(roots.getValue("l").columnType).isEqualTo("list<struct<\"a,b\":int32>>")
                assertThat(roots.getValue("m").columnType).isEqualTo("map<varchar,struct<\"x:y\":int32>>")
                val raw = c.getAllColumnsWithParentage(table.tableId, snapshot)
                assertThat(raw.filter { it.parentColumn == roots.getValue("s").columnId }.map { it.columnName })
                    .containsExactly("a,b", "x:y", "angle<>", "q\"uote", " spaced ", "雪")
                assertThat(DucklakeTypeSignature.parse(roots.getValue("s").columnType).children.map { it.name })
                    .containsExactly("a,b", "x:y", "angle<>", "q\"uote", " spaced ", "雪")
                assertThat(c.currentSnapshotId).isEqualTo(snapshot)
            }
        }

        @Test fun ordinarySignaturesAndFieldCaseRemainUnchanged(): Unit = fixture().use { f ->
            f.seed()
            f.native("CREATE TABLE lake.ordinary(s STRUCT(a INTEGER,b DECIMAL(18,2),MiXeD VARCHAR),l INTEGER[],m MAP(VARCHAR,BIGINT))")
            f.reader().use { c ->
                val table = c.getTable("main", "ordinary", c.currentSnapshotId)!!
                assertThat(c.getTableColumns(table.tableId, c.currentSnapshotId).map { it.columnType })
                    .containsExactly("struct<a:int32,b:decimal(18,2),MiXeD:varchar>", "list<int32>", "map<varchar,int64>")
            }
        }
    }

    @Test fun punctuationDoesNotChangeTheActualNativeOrDecodedValues(): Unit = LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB).use { f ->
        f.seed()
        f.native("CREATE TABLE lake.values_test(s STRUCT(\"a,b\" INTEGER,\"x:y\" VARCHAR,MiXeD BIGINT)); " +
            "INSERT INTO lake.values_test VALUES(struct_pack(\"a,b\":=11,\"x:y\":='literal',MiXeD:=22)),(NULL)")
        assertThat(f.native("SELECT s.\"a,b\",s.\"x:y\",s.MiXeD FROM lake.values_test ORDER BY s.\"a,b\" NULLS LAST"))
            .containsExactly(listOf("11", "literal", "22"), listOf(null, null, null))
        f.reader().use { c ->
            val snapshot = c.currentSnapshotId
            val table = c.getTable("main", "values_test", snapshot)!!
            val version = c.getInlinedDataInfos(table.tableId, snapshot).single().schemaVersion
            assertThat(c.readInlinedDataDecoded(table.tableId, version, snapshot, c.getTableColumns(table.tableId, snapshot)))
                .containsExactly(listOf(linkedMapOf("a,b" to 11, "x:y" to "literal", "MiXeD" to 22L)), listOf(null))
        }
    }
}
