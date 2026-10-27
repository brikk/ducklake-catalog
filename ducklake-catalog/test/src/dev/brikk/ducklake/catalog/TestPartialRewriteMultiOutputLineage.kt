package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** C12 closure: exercise the existing embedded-ID contract with multiple actual partitioned outputs. */
internal class TestPartialRewriteMultiOutputLineage {
    @Test fun localPartialOutputsKeepOriginalRowIdsAcrossPartitionsAndHistory() {
        verify { LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    }

    @Test fun postgresPartialOutputsKeepOriginalRowIdsAcrossPartitionsAndHistory() {
        verify { LegacyWriteFixture.Postgres() }
    }

    private data class Row(val rowId: Long, val id: Int, val partition: Int, val snapshot: Long)

    private fun verify(fixture: () -> LegacyWriteFixture) {
        val root = Files.createTempDirectory(Path.of("/tmp/opencode"), "catalog-c12-")
        try { fixture().use { f ->
            f.native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(id INTEGER,p INTEGER); " +
                "ALTER TABLE lake.t SET PARTITIONED BY(p); INSERT INTO lake.t VALUES(10,0),(20,1),(30,0),(40,1)")
            val first = snapshot(f)
            val earlier = f.native("SELECT rowid,id,p FROM lake.t ORDER BY id")
            f.native("INSERT INTO lake.t VALUES(50,0),(60,1),(70,0),(80,1)")
            val before = snapshot(f)
            val original = f.native("SELECT rowid,id,p FROM lake.t ORDER BY id")
            val rows = original.map { Row(it[0]!!.toLong(), it[1]!!.toInt(), it[2]!!.toInt(), if (it[1]!!.toInt() <= 40) first else before) }
            assertThat(rows.map { it.rowId }.sorted()).containsExactlyElementsOf((0L..7L).toList())
            val (table, columns, sources) = f.reader().use { c ->
                val table = c.getTable("main", "t", before)!!.tableId
                Triple(table, c.getTableColumns(table, before).associateBy { it.columnName }, c.getDataFiles(table, before))
            }
            assertThat(sources).hasSize(4).allSatisfy { assertThat(it.partialMax).isNull() }
            val partitionId = sources.map { it.partitionId }.distinct().single()!!
            val outputs = rows.groupBy { it.partition }.flatMap { (partition, partitionRows) ->
                partitionRows.withIndex().groupBy { it.index % 2 }.map { (chunk, indexed) ->
                    writeOutput(f, root.resolve("p$partition-$chunk.parquet"), indexed.map { it.value }, columns, partitionId)
                }
            }
            f.reader().use { c ->
                c.rewriteDataFilesPartial(table, sources.map { it.dataFileId }.toSet(), outputs, before)
                val rewritten = c.getDataFiles(table, c.currentSnapshotId)
                assertThat(rewritten).hasSize(4).allSatisfy {
                    assertThat(it.rowIdStart).isNull()
                    assertThat(it.beginSnapshot).isEqualTo(first)
                    assertThat(it.partialMax).isEqualTo(before)
                }
                assertThat(c.getDataFilesByIds(table, sources.map { it.dataFileId })).isEmpty()
                assertThat(c.getTableStats(table)!!.recordCount).isEqualTo(8L)
                assertThat(c.getFilePartitionValues(table, c.currentSnapshotId).values.flatten().map { it.partitionValue })
                    .containsExactlyInAnyOrder("0", "0", "1", "1")
            }
            assertRowsAndSubsequentMutations(f, first, before, earlier, original)
        } } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun writeOutput(
        f: LegacyWriteFixture, path: Path, rows: List<Row>, columns: Map<String, DucklakeColumn>, partitionId: Long,
    ): PartialMergedFile {
        val values = rows.joinToString(" UNION ALL ") {
            "SELECT ${it.id}::INTEGER AS id,${it.partition}::INTEGER AS p,${it.rowId}::BIGINT AS _ducklake_internal_row_id," +
                "${it.snapshot}::BIGINT AS _ducklake_internal_snapshot_id"
        }
        f.native("COPY ($values ORDER BY id DESC) TO '${path}' (FORMAT PARQUET, FIELD_IDS {id: ${columns.getValue("id").columnId}, " +
            "p: ${columns.getValue("p").columnId},_ducklake_internal_row_id: 2147483540,_ducklake_internal_snapshot_id: 2147483539})")
        assertThat(f.native("SELECT _ducklake_internal_row_id FROM read_parquet('$path') ORDER BY id DESC").map { it.single()!!.toLong() })
            .isEqualTo(rows.sortedByDescending { it.id }.map { it.rowId })
        val stats = listOf(
            DucklakeFileColumnStats(columns.getValue("id").columnId, 8L, 2L, 0L, rows.minOf { it.id }.toString(), rows.maxOf { it.id }.toString(), false),
            DucklakeFileColumnStats(columns.getValue("p").columnId, 8L, 2L, 0L, rows[0].partition.toString(), rows[0].partition.toString(), false),
        )
        val fragment = DucklakeWriteFragment(path.toString(), false, "parquet", Files.size(path), 0L, 2L, stats,
            mapOf(0 to rows[0].partition.toString()), partitionId, null)
        return PartialMergedFile(fragment, rows.minOf { it.snapshot }, rows.maxOf { it.snapshot })
    }

    private fun assertRowsAndSubsequentMutations(
        f: LegacyWriteFixture, first: Long, before: Long, earlier: List<List<String?>>, original: List<List<String?>>,
    ) {
        assertThat(f.native("SELECT rowid,id,p FROM lake.t ORDER BY id")).isEqualTo(original)
        assertThat(f.native("SELECT rowid,id,p FROM lake.t AT(VERSION=>$first) ORDER BY id")).isEqualTo(earlier)
        assertThat(f.native("SELECT rowid,id,p FROM lake.t AT(VERSION=>$before) ORDER BY id")).isEqualTo(original)
        assertThat(f.native("SELECT rowid,id,p FROM lake.t WHERE p=1 ORDER BY id")).isEqualTo(original.filter { it[2] == "1" })
        f.native("DELETE FROM lake.t WHERE id=50")
        assertThat(f.native("SELECT rowid,id,p FROM lake.t ORDER BY id")).isEqualTo(original.filter { it[1] != "50" })
        assertThat(f.native("SELECT rowid,id,p FROM lake.t AT(VERSION=>$before) ORDER BY id")).isEqualTo(original)
        f.native("INSERT INTO lake.t VALUES(90,0)")
        assertThat(f.native("SELECT rowid FROM lake.t WHERE id=90")).containsExactly(listOf("8"))
    }

    private fun snapshot(f: LegacyWriteFixture) = f.reader().use { it.currentSnapshotId }
}
