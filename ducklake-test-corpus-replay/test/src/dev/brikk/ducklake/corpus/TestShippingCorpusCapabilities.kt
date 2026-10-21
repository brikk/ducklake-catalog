package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** The approved build-capability exclusion is explicit, narrow and continuously reproduced. */
class TestShippingCorpusCapabilities {
    @Test
    fun `static JSON precondition fails unchanged while all remaining import checks pass`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT loaded,install_mode FROM duckdb_extensions() WHERE extension_name='json'").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBoolean(1)).isTrue()
                    assertThat(rows.getString(2)).isEqualTo("STATICALLY_LINKED")
                }
            }
        }
        val root = Path.of(System.getProperty("ducklake.corpus.root")).parent.parent
        val path = "add_files/add_files_json_without_extension.test"
        val file = SltParser.parse(path, Files.readString(root.resolve("test/sql/$path")))
        val result = ReplayDriver(repoRoot = root).replay(file)
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.skipped).isEmpty()
        val failure = result.failed.single()
        assertThat(failure.record.line).isEqualTo(16)
        assertThat(failure.reason).isEqualTo("row 1: expected [false], got [true]")
        assertThat(result.passed).isGreaterThanOrEqualTo(10)
        println("Explicit official-binary capability gap, unchanged golden: $path:16 ${failure.reason}; " +
            "${result.passed} remaining checks passed")
    }
}
