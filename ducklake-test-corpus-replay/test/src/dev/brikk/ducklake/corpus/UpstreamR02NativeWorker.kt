package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltUnsupported
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.zip.GZIPInputStream

/** Child-only native oracle: a native SIGSEGV must fail its parent gate, never skip a test. */
internal object UpstreamR02NativeWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments[0])
        val path = arguments[1]
        val temp = Path.of(arguments[3])
        assertShippingPeer()
        val source = String(verified(root, path, arguments[2]), Charsets.UTF_8)
        val parsed = SltParser.parse(path, source.replace("{TEST_DIR}", temp.toString()))
        val unzip = parsed.records.filterIsInstance<SltUnsupported>().filter { it.directive == "unzip" }
        if (arguments[4].toBooleanStrict()) {
            check(unzip.size == 1)
            unpack(root, temp.resolve("v10_inlined_struct_field_order.db"))
        } else check(unzip.isEmpty())
        val prepared = parsed.copy(records = parsed.records.filterNot { it in unzip })
        val result = ReplayDriver(repoRoot = root).replay(prepared)
        check(result.fileSkipReason == null) { "Strict native file skip: ${result.fileSkipReason}" }
        check(result.skipped.isEmpty()) { "Strict native record skips: ${result.skipped}" }
        check(result.failed.isEmpty()) { "$path STRICT NATIVE FAILURES:\n${result.failed.joinToString("\n")}" }
        check(result.passed > 5)
    }

    private fun verified(root: Path, name: String, expected: String): ByteArray {
        val bytes = Files.readAllBytes(root.resolve(name))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == expected) { "Changed pristine ea543c71 fixture $name" }
        return bytes
    }

    private fun unpack(root: Path, destination: Path) {
        val bytes = verified(root, "data/old_ducklake/inlined_struct_field_order.db.gz",
            "837dd5fc5f949b13744c3210a0a7d5b43e3ba6d9168514ea55ab7b4218e95d7d")
        GZIPInputStream(bytes.inputStream()).use { input ->
            Files.newOutputStream(destination).use { output ->
                val data = input.readNBytes(64 * 1024 * 1024 + 1)
                check(data.size <= 64 * 1024 * 1024) { "Legacy fixture exceeds the decompression budget" }
                output.write(data)
            }
        }
    }

    private fun assertShippingPeer() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            statement.execute("LOAD ducklake")
            statement.executeQuery("PRAGMA version").use { result ->
                check(result.next() && result.getString(1) == "v2.0.0" && result.getString(2) == "d54cbbe3df")
            }
            statement.executeQuery("SELECT extension_version,installed_from FROM duckdb_extensions() WHERE extension_name='ducklake'").use { result ->
                check(result.next() && result.getString(1) == "417a9117f5" && result.getString(2) == "core")
            }
            statement.executeQuery("SELECT current_setting('allow_unsigned_extensions')").use { result ->
                check(result.next() && !result.getBoolean(1))
            }
            println("STRICT NATIVE PEER: JDBC 2.0.0.0, core d54cbbe3df, signed core DuckLake 417a9117f5, unsigned=false")
        } }
    }
}
