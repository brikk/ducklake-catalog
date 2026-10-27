package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/** Actual pruning APIs over authored point-file stats, with independent DuckDB logical comparisons. */
internal class TestCatalogTypedPruning {
    @Nested inner class Local : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : Contract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : Contract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun floatingPointPredicatesUseTheirDeclaredBinaryPrecision(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c -> DriverManager.getConnection("jdbc:duckdb:").use { oracle ->
                verify(c, oracle, "float32",
                    listOf("0.1", "-0.1", "0.2", "0.10000001", "-0.0", "1.0E-45", "16777216.0", "3.4028235E38"),
                    listOf(
                        "0.10000000149011612" to "0.10000000149011612",
                        "-0.10000000149011612" to "-0.10000000149011612",
                        "0.10000000894069672" to "0.10000000894069672",
                        "0.0" to "0.0", "1.401298464324817E-45" to "1.401298464324817E-45",
                        "16777217" to "16777217", "3.4028234663852886E38" to "3.4028234663852886E38",
                        "0.10000000149011612" to null, null to "-0.10000000149011612",
                    ))
                verify(c, oracle, "float64", listOf("0.1", "0.10000000000000002", "-0.0", "0.2"),
                    listOf("0.1000000000000000055511151231257827021181583404541015625" to "0.1",
                        "0.10000000000000002" to "0.10000000000000002", "0.0" to "0.0"))
            } }
        }

        @Test fun endOfDayTimeIsAboveLateEveningAndDistinctFromMidnight(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c -> DriverManager.getConnection("jdbc:duckdb:").use { oracle ->
                for ((type, lastTick) in listOf("time" to "23:59:59.999999", "time_ns" to "23:59:59.999999999")) {
                    verify(c, oracle, type, listOf("00:00:00", "12:00:00", lastTick, "24:00:00"),
                        listOf("23:00:00" to null, "24:00:00" to "24:00:00", null to "00:00:00",
                            "00:00:00" to "00:00:00", lastTick to lastTick, "24:00:00.000000000" to null))
                    val table = c.getTable("main", "t_$type", c.currentSnapshotId)!!.tableId
                    val merged = c.getColumnStats(table, c.currentSnapshotId).single()
                    assertThat(merged.minValue).isEqualTo("00:00:00")
                    assertThat(merged.maxValue).isEqualTo("24:00:00")
                }
            } }
        }

        private fun verify(
            c: JdbcDucklakeCatalog, oracle: Connection, type: String,
            values: List<String>, predicates: List<Pair<String?, String?>>,
        ) {
            val sqlType = when (type) { "float32" -> "FLOAT"; "float64" -> "DOUBLE"; "time_ns" -> "TIME_NS"; else -> "TIME" }
            c.createTable("main", "t_$type", listOf(TableColumnSpec.leaf("v", type, true)), null, null)
            val table = c.getTable("main", "t_$type", c.currentSnapshotId)!!.tableId
            val column = c.getTableColumns(table, c.currentSnapshotId).single().columnId
            c.commitInsert(table, values.mapIndexed { index, value ->
                DucklakeWriteFragment("c07/$type/$index.parquet", 100L, 0L, 1L,
                    listOf(DucklakeFileColumnStats(column, 8L, 1L, 0L, value, value, false)))
            })
            val snapshot = c.currentSnapshotId
            val files = c.getDataFiles(table, snapshot).associate { it.path.substringAfterLast('/').removeSuffix(".parquet").toInt() to it.dataFileId }
            for ((lower, upper) in predicates) {
                val expected = values.mapIndexedNotNull { index, value ->
                    if (matches(oracle, sqlType, value, lower, upper)) files.getValue(index) else null
                }.toSet()
                val actual = c.findDataFileIdsInRange(table, snapshot, ColumnRangePredicate(column, lower, upper)).toSet()
                assertThat(actual).describedAs("$type [$lower,$upper]; typed DuckDB comparison")
                    .isEqualTo(expected)
            }
        }

        private fun matches(c: Connection, type: String, value: String, lower: String?, upper: String?): Boolean {
            val conditions = listOfNotNull(lower?.let { "v >= CAST(? AS $type)" }, upper?.let { "v <= CAST(? AS $type)" }).joinToString(" AND ")
            return c.prepareStatement("SELECT $conditions FROM (SELECT CAST(? AS $type) AS v)").use { statement ->
                val bounds = listOfNotNull(lower, upper)
                (bounds + value).forEachIndexed { index, text -> statement.setString(index + 1, text) }
                statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
            }
        }
    }
}
