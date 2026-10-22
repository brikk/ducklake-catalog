package dev.brikk.ducklake.corpus

import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltUnsupported
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Whole pristine regression on an owned copy, including every original native SQL/golden. */
internal object UpstreamR04NativeWorker {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments[0])
        val temp = Path.of(arguments[1])
        UpstreamR02NativeWorker.assertShippingPeer()
        val source = verified(root, "test/sql/migration/v10_retyped_bounds.test",
            "3694ae485e41fe81c4efd63028c2cbdf2bd83351d12ea569c61bb66b36b8409b")
        val gzip = verified(root, "data/old_ducklake/migrate_bounds.db.gz",
            "f9ad3a91468c3e3c96b1d3587214885b20fedd86c8a89b84efab2a7487188528")
        GZIPInputStream(gzip.inputStream()).use { input ->
            val bytes = input.readNBytes(64 * 1024 * 1024 + 1)
            check(bytes.size <= 64 * 1024 * 1024)
            Files.write(temp.resolve("v10_retyped_bounds.db"), bytes)
        }
        DATA.forEach { (name, hash) ->
            val relative = "data/old_ducklake/migrate_bounds/$name"
            val destination = temp.resolve(relative)
            Files.createDirectories(destination.parent)
            Files.write(destination, verified(root, relative, hash))
        }
        val file = SltParser.parse("test/sql/migration/v10_retyped_bounds.test", String(source, Charsets.UTF_8).replace("{TEST_DIR}", temp.toString()))
        val unzip = file.records.filterIsInstance<SltUnsupported>()
        check(unzip.size == 1 && unzip.single().directive == "unzip" && unzip.single().raw.contains("migrate_bounds.db.gz"))
        val result = ReplayDriver(repoRoot = temp).replay(file.copy(records = file.records.filterNot { it in unzip }))
        check(result.fileSkipReason == null && result.skipped.isEmpty()) { "Strict R04 skip: $result" }
        println("R04 native passed records=${result.passed}; failed=${result.failed.size}; skipped=${result.skipped.size}")
        check(result.failed.isEmpty()) { "Strict R04 native failures:\n${result.failed.joinToString("\n")}" }
    }

    private fun verified(root: Path, name: String, expected: String): ByteArray {
        val bytes = Files.readAllBytes(root.resolve(name))
        check(bytes.size <= 1_048_576)
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == expected) { "Wrong pinned input $name" }
        return bytes
    }

    private val DATA = mapOf(
        "double.parquet" to "aafe0f9ca47791572eb18383eb64c0ebf4d49b6ee6a30d27a905ef098f524492",
        "float.parquet" to "63b92f770a523fa1d98cd38b62b7e759f7239bda322bf5dca49d638f6f65f20d",
        "ts_ns.parquet" to "d7bb6eb26ae2cad0deddefc721863ab27b9cc31892c1ef0695ec565c738069df",
        "tz_ns.parquet" to "50dfd3c981de43edfe86da6dfe2c898507ac40bb257015ff3e3f6e213bf1f913",
        "main/dc/ducklake-01a14a6c-ef3e-7a35-93c9-1240f2b75a4c.parquet" to "0d4aef2b1b8bb6468566112d0a74e0da71781780c8a21e8286844310cce1d868",
        "main/i2f/ducklake-01a14a6c-ee9e-71bd-a440-ce5b0d111470.parquet" to "eaaa6d7c3ef8e79dafdcd32aa6d55201d37db549516b7031737ca087577b8a32",
        "main/r/ducklake-01a14a6c-edf7-7e3f-a2e1-edc403aefe32.parquet" to "4b0fbb0165b9f1884098913813b9cc86312563bbbdd03651a97bf60de8a0e4be",
        "main/r/ducklake-01a14a6c-ee27-7f05-b734-54809d0c1975.parquet" to "27a8f1d7ef2c6240d3b6267e5ddf905b6bc5e61a1fa162e1205a5ad208fcb11b",
        "main/st/ducklake-01a14a6c-ee4f-77fc-9fdd-6dd816642b2c.parquet" to "4b0fbb0165b9f1884098913813b9cc86312563bbbdd03651a97bf60de8a0e4be",
        "main/tf/ducklake-01a14a6c-ef01-72ab-abc1-9c9aa81ec7de.parquet" to "4b0fbb0165b9f1884098913813b9cc86312563bbbdd03651a97bf60de8a0e4be",
    )
}
