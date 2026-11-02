package dev.brikk.ducklake.corpus

import org.assertj.core.api.Assertions.assertThat
import org.duckdb.DuckDBDriver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Upstream951/728: unsafe native getter cases must never run in the Gradle test worker. */
internal class TestJdbcPatchNativeResults {
    private fun verify(mode: String, root: Path) {
        val classpath = listOf(JdbcResultProbe::class.java, DuckDBDriver::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        val log = root.resolve("$mode.log")
        val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=$root/hs_err_pid%p.log", "-Djava.io.tmpdir=$root",
            "-cp", classpath, JdbcResultProbe::class.java.name, mode)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).describedAs("bounded native getter child: %s", mode).isTrue()
            assertThat(child.exitValue()).withFailMessage(Files.readString(log)).isZero()
            assertThat(Files.readString(log)).contains("PASS $mode").doesNotContain("SIGSEGV", "fatal error")
        } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) } }
    }

    @Test fun reportedTupleListGetStringCrashIsFixed(@TempDir root: Path) = verify("tuple-list", root)
    @Test fun tupleMetadataAndObjectsAreTypedAndPreserveNestedNulls(@TempDir root: Path) = verify("tuple-objects", root)
    @Test fun fallbackNestedGettersPreserveTextAndOriginalTypedValues(@TempDir root: Path) = verify("nested-fallback", root)
    @Test fun namedStructScalarTupleAndNullControlsStayCorrect(@TempDir root: Path) = verify("controls", root)

    @Test fun resolvedRuntimeIsTheExactPublishedPatch() {
        val jar = Path.of(DuckDBDriver::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(jar.fileName.toString()).isEqualTo("duckdb_jdbc-2.0.0.1.jar")
        val sha = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(jar).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        assertThat(sha.digest().joinToString("") { "%02x".format(it) })
            .isEqualTo("6cb8b0e0fc51b2e45aead2d2c1e1fd09dd10267db2966e69aaab0de67319f826")
    }
}
