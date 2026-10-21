package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** A native failure is a failed separate gate, never a replacement golden or expected pass. */
@Tag("upstream-r03-native")
class TestUpstreamR03IntervalNativeGate {
    @Test
    @Timeout(60)
    fun `acknowledged inline interval components and microseconds survive native flush`() {
        val temp = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-r03-native-interval-")
        try {
            DriverManager.getConnection("jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
                statement.execute("LOAD ducklake")
                statement.executeQuery("PRAGMA version").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("v2.0.0")
                    assertThat(rows.getString(2)).isEqualTo("d54cbbe3df")
                }
                statement.executeQuery("SELECT extension_version,current_setting('allow_unsigned_extensions') " +
                    "FROM duckdb_extensions() WHERE extension_name='ducklake'").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("417a9117f5")
                    assertThat(rows.getBoolean(2)).isFalse()
                }
                statement.execute("ATTACH 'ducklake:${temp.resolve("metadata.db")}' AS lake " +
                    "(DATA_PATH '${temp.resolve("data")}',DUCKLAKE_VERSION '1.1'); " +
                    "CREATE TABLE lake.t(id INTEGER,iv INTERVAL); INSERT INTO lake.t VALUES(1,INTERVAL '1 day 100:00:00.123456')")
                val query = "SELECT CAST(iv AS VARCHAR),date_part('microseconds',iv) FROM lake.t"
                statement.executeQuery(query).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("1 day 100:00:00.123456")
                    assertThat(rows.getLong(2)).isEqualTo(123456L)
                }
                statement.execute("CALL ducklake_flush_inlined_data('lake')")
                statement.executeQuery(query).use { rows ->
                    assertThat(rows.next()).isTrue()
                    println("Strict R03 native interval after flush: ${rows.getString(1)}, sub-minute micros=${rows.getLong(2)}")
                    assertThat(rows.getLong(2)).`as`("native flush must not silently discard interval microseconds").isEqualTo(123456L)
                    assertThat(rows.getString(1)).`as`("independent native month/day/micros components must not be silently normalized")
                        .isEqualTo("1 day 100:00:00.123456")
                }
            } }
        }
        finally { Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
