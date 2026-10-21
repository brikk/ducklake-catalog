package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Runs WHOLE unmodified newer upstream regressions; expected failures are never accepted as passes. */
@Tag("upstream-r02-native")
class TestUpstreamR02NativeGate {
    private val root = Path.of(System.getProperty("ducklake.read.fixtures"))

    @Test
    fun `native rejects nonliteral added STRUCT field defaults`() = qualify(
        "test/sql/alter/add_struct_field_non_literal_default.test", "53e969c884d09270376b2d7553978751a7b81882337b6efbdcdd0ac719c8286a")

    @Test
    fun `native retains same transaction added struct field identities`() = qualify(
        "test/sql/alter/add_struct_alter_same_transaction.test", "94bcc8bbc2b810588a081d94c117acd8f449f93dc46caa9f0b0e815fbe85e43d")

    @Test
    fun `native reads and flushes genuine legacy STRUCT order without changing values`() = qualify(
        "test/sql/migration/v10_inlined_struct_field_order.test", "a015a0482246894223944f601b29b5663087c2cf40631ed2a9e4751095a3c257", true)

    private fun qualify(path: String, digest: String, legacy: Boolean = false) {
        val temp = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-r02-native-gate-")
        val reports = Files.createDirectories(Path.of(System.getProperty("ducklake.nativeGate.reports")))
        val output = reports.resolve(path.substringAfterLast('/') + ".log")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val child = ProcessBuilder(java.toString(), "-Xmx1g", "-XX:-CreateCoredumpOnCrash",
            "-Djava.io.tmpdir=${Files.createDirectory(temp.resolve("jvm-temp"))}",
            "-XX:ErrorFile=$reports/hs_err_pid%p.log", "-cp", System.getProperty("ducklake.nativeGate.classpath"),
            "dev.brikk.ducklake.corpus.UpstreamR02NativeWorker", root.toString(), path, digest, temp.toString(), legacy.toString())
            .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            check(child.waitFor(120, TimeUnit.SECONDS)) { "Strict upstream native worker timed out: $path" }
            assertThat(child.exitValue()).withFailMessage {
                "$path STRICT NATIVE FAILURE (exit ${child.exitValue()}):\n${Files.readString(output).take(24000)}\nFull output: $output"
            }.isZero()
        }
        finally {
            if (child.isAlive) {
                child.destroyForcibly()
                check(child.waitFor(5, TimeUnit.SECONDS)) { "Failed to reap owned strict native worker" }
            }
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
