package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Tag("upstream-r04-native")
class TestUpstreamR04NativeGate {
    @Test
    fun `native migration clears retyped legacy bounds before filtering and aggregates`() {
        val temp = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-r04-native-")
        val reports = Files.createDirectories(Path.of(System.getProperty("ducklake.nativeGate.reports")))
        val output = reports.resolve("v10_retyped_bounds.test.log")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val child = ProcessBuilder(java.toString(), "-Xmx1g", "-XX:-CreateCoredumpOnCrash",
            "-XX:ErrorFile=$reports/hs_err_pid%p.log", "-cp", System.getProperty("ducklake.nativeGate.classpath"),
            "dev.brikk.ducklake.corpus.UpstreamR04NativeWorker", System.getProperty("ducklake.read.fixtures"), temp.toString())
            .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            check(child.waitFor(120, TimeUnit.SECONDS)) { "Strict R04 native worker timed out" }
            assertThat(child.exitValue()).withFailMessage {
                "R04 STRICT NATIVE FAILURE (exit ${child.exitValue()}):\n${Files.readString(output).take(24000)}\nFull output: $output"
            }.isZero()
        }
        finally {
            if (child.isAlive) { child.destroyForcibly(); check(child.waitFor(5, TimeUnit.SECONDS)) }
            Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
