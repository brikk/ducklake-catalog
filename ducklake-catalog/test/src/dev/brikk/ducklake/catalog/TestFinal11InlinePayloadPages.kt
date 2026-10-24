/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.HexFormat
import java.util.function.Supplier

internal class TestFinal11InlinePayloadPages {
    @Nested inner class Local : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) }
    @Nested inner class NativeQuack : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.NATIVE_QUACK) }
    @Nested inner class JavaQuack : NativeContract { override fun fixture() = ReadAcceptanceFixture.Duck(Final11ReadRoute.JAVA_QUACK) }
    @Nested inner class Postgres : NativeContract { override fun fixture() = ReadAcceptanceFixture.Postgres() }
    @Nested inner class MySql : Contract { override fun fixture() = ReadAcceptanceFixture.MySql() }

    data class Seed(val tableId: Long, val info: DucklakeInlinedDataInfo, val snapshot: Long, val ids: List<Long>)

    interface Contract {
        fun fixture(): ReadAcceptanceFixture

        @Test
        fun ordinaryAndCdcPagesKeepIndependentValuesBytesNullsAndOriginalIdentity() {
            fixture().use { f ->
                val seed = seed(f)
                f.reader().use { c -> c.readSession(Supplier {
                    val rows = drain { after -> c.readInlinedDataDecodedPage(seed.tableId, seed.info.schemaVersion, seed.snapshot, seed.ids, after, 1) }
                    assertThat(rows.map { it.rowId }).containsExactly(0, 1, 2, 3)
                    assertThat(rows.map { it.beginSnapshot }).containsOnly(seed.snapshot)
                    assertThat(rows.map { it.endSnapshot }).containsOnlyNulls()
                    assertThat(rows.first().values[2]).isInstanceOf(ByteArray::class.java)
                    assertThat(normal(rows).map { it.values }).containsExactly(
                        listOf(1, null, "005CFF"), listOf(2, "NULL", "5C783431"), listOf(3, "", null), listOf(4, "four", ""),
                    )
                    val cdc = drain { after -> c.getInlinedChangesBetweenDecodedPage(
                        seed.tableId, seed.info.schemaVersion, seed.snapshot, seed.snapshot, seed.ids, after, 2,
                    ) }
                    assertThat(normal(cdc)).containsExactlyElementsOf(normal(rows))
                    assertThat(normal(cdc)).containsExactlyElementsOf(normal(c.getInlinedChangesBetweenDecoded(
                        seed.tableId, seed.info.schemaVersion, seed.snapshot, seed.snapshot, seed.ids,
                    )))
                    val identity = drain { after -> c.readInlinedDataDecodedPage(
                        seed.tableId, seed.info.schemaVersion, seed.snapshot, emptyList(), after, 2,
                    ) }
                    assertThat(identity.map { it.rowId }).containsExactly(0, 1, 2, 3)
                    assertThat(identity.flatMap { it.values }).isEmpty()
                    assertThatThrownBy { drain(1) { after -> c.readInlinedDataDecodedPage(
                        seed.tableId, seed.info.schemaVersion, seed.snapshot, seed.ids, after, 1,
                    ) } }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("incomplete")
                    assertThat(c.getSnapshot(seed.snapshot)).isNotNull()
                    Unit
                }) }
            }
        }

        @Test
        fun unicodePayloadCellsAreSqlBoundedAndOversizeLargePagesRejectRatherThanTruncate() {
            fixture().use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER,s VARCHAR,b BLOB); INSERT INTO lake.t VALUES(1,repeat('é',8192),from_hex('005CFF'))")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val info = c.getInlinedDataInfos(table.tableId, snapshot).single()
                    val ids = c.getTableColumns(table.tableId, snapshot).map { it.columnId }
                    assertThatThrownBy { c.readInlinedDataDecodedPage(table.tableId, info.schemaVersion, snapshot, ids, null, 1024) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("budget")
                    val row = c.readInlinedDataDecodedPage(table.tableId, info.schemaVersion, snapshot, ids, null, 1).single()
                    assertThat(row.values[1]).isEqualTo("é".repeat(8192))
                    assertThat(row.values[2] as ByteArray).containsExactly(0, 0x5c, 0xff.toByte())
                    assertThat(c.readInlinedDataDecodedPage(table.tableId, info.schemaVersion, snapshot, ids,
                        DucklakeInlineCursor(row.rowId, row.beginSnapshot), 1)).isEmpty()
                }) }
            }
        }

        @Test
        fun invalidRequestsAndDuplicatePhysicalRowVersionsCannotBecomeSuccessfulPartialPages() {
            fixture().use { f ->
                val s = seed(f)
                f.reader().use { c ->
                    assertThatThrownBy { c.readInlinedDataDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot, s.ids, null, 1) }
                        .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("readSession")
                    c.readSession(Supplier { invalidRequests(c, s) })
                }
                f.metadata("INSERT INTO ${s.info.tableName} SELECT * FROM ${s.info.tableName} WHERE i=1") // Owned duplicate-key corruption fixture.
                f.reader().use { c -> c.readSession(Supplier {
                    assertThatThrownBy { c.readInlinedDataDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot, s.ids, null, 1) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("Duplicate")
                    assertThatThrownBy { c.getInlinedChangesBetweenDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot, s.snapshot, s.ids, null, 1) }
                        .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("Duplicate")
                }) }
            }
        }

        @Test
        fun zeroLiveRowsDoNotHideCdcHistoryIncludingTransientRowVersions() {
            fixture().use { f ->
                val s = seed(f)
                // Owned zero-lifetime history fixture, not a claim that native INSERT/DELETE persists transient rows.
                f.metadata("UPDATE ${s.info.tableName} SET _ducklake_end_snapshot=_ducklake_begin_snapshot")
                f.reader().use { c -> c.readSession(Supplier {
                    assertThat(c.countInlinedRows(s.tableId, s.info.schemaVersion, s.snapshot)).isZero()
                    assertThat(c.readInlinedDataDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot, s.ids, null, 1)).isEmpty()
                    val changes = drain { after -> c.getInlinedChangesBetweenDecodedPage(
                        s.tableId, s.info.schemaVersion, s.snapshot, s.snapshot, s.ids, after, 1,
                    ) }
                    assertThat(changes.map { it.rowId }).containsExactly(0, 1, 2, 3)
                    assertThat(changes.map { it.beginSnapshot }).containsOnly(s.snapshot)
                    assertThat(changes.map { it.endSnapshot }).containsOnly(s.snapshot)
                }) }
            }
        }

        fun invalidRequests(c: JdbcDucklakeCatalog, s: Seed) {
            for (limit in listOf(0, 1025)) {
                assertThatThrownBy { c.readInlinedDataDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot, s.ids, null, limit) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThatThrownBy { c.readInlinedDataDecodedPage(s.tableId, Long.MAX_VALUE, s.snapshot, s.ids, null, 1) }
                .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("visible")
            assertThatThrownBy { c.readInlinedDataDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot, listOf(0, 0), null, 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { c.readInlinedDataDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot, s.ids, DucklakeInlineCursor(-1, 0), 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { c.getInlinedChangesBetweenDecodedPage(s.tableId, s.info.schemaVersion, s.snapshot + 1, s.snapshot, s.ids, null, 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        fun seed(f: ReadAcceptanceFixture): Seed {
            f.native("CALL lake.set_option('data_inlining_row_limit',10); CREATE TABLE lake.t(i INTEGER,s VARCHAR,b BLOB); " +
                "INSERT INTO lake.t VALUES(1,NULL,from_hex('005CFF')),(2,'NULL',from_hex('5C783431')),(3,'',NULL),(4,'four',''::BLOB)")
            return f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                val table = c.getTable("main", "t", snapshot)!!
                Seed(table.tableId, c.getInlinedDataInfos(table.tableId, snapshot).single(), snapshot,
                    c.getTableColumns(table.tableId, snapshot).map { it.columnId })
            }) }
        }

        fun drain(maxPages: Int = 8, fetch: (DucklakeInlineCursor?) -> List<DucklakeInlinedChangeRow>): List<DucklakeInlinedChangeRow> {
            var after: DucklakeInlineCursor? = null
            val rows = mutableListOf<DucklakeInlinedChangeRow>()
            var complete = false
            repeat(maxPages) {
                if (!complete) {
                    val page = fetch(after)
                    if (page.isEmpty()) complete = true else {
                        val next = DucklakeInlineCursor(page.last().rowId, page.last().beginSnapshot)
                        val previous = after
                        check(previous == null || next.rowId > previous.rowId ||
                            (next.rowId == previous.rowId && next.beginSnapshot > previous.beginSnapshot)) { "Inline cursor did not advance" }
                        rows.addAll(page)
                        after = next
                    }
                }
            }
            check(complete) { "Inline page traversal incomplete within caller budget" }
            return rows
        }

        fun normal(rows: List<DucklakeInlinedChangeRow>): List<DucklakeInlinedChangeRow> = rows.map { it.copy(values = it.values.map(::value)) }
        fun value(input: Any?): Any? = when (input) {
            is ByteArray -> HexFormat.of().withUpperCase().formatHex(input)
            is Map<*, *> -> input.entries.associate { value(it.key) to value(it.value) }
            is List<*> -> input.map(::value)
            else -> input
        }
    }

    interface NativeContract : Contract {
        @Test
        fun cdcRowVersionSeekKeepsSameIdUpdatesDefaultsRenameAndHistoricalMetadata() {
            fixture().use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER,s VARCHAR,b BLOB); INSERT INTO lake.t VALUES(1,'old',from_hex('005CFF')),(2,'NULL',NULL)")
                val inserted = head(f)
                f.native("ALTER TABLE lake.t RENAME COLUMN s TO txt; ALTER TABLE lake.t ADD COLUMN n INTEGER DEFAULT 42; " +
                    "UPDATE lake.t SET txt='new' WHERE i=1")
                val first = head(f)
                f.native("UPDATE lake.t SET txt='new2' WHERE i=1")
                val second = head(f)
                f.native("DELETE FROM lake.t WHERE i=2")
                val deleted = head(f)
                f.reader().use { c -> c.readSession(Supplier {
                    val table = c.getTable("main", "t", deleted)!!
                    val columns = c.getTableColumns(table.tableId, deleted)
                    val infos = c.getInlinedDataInfos(table.tableId, deleted)
                    val rows = infos.flatMap { info -> drain { after -> c.getInlinedChangesBetweenDecodedPage(
                        table.tableId, info.schemaVersion, inserted, deleted, columns.map { it.columnId }, after, 1,
                    ) } }.sortedWith(compareBy({ it.rowId }, { it.beginSnapshot }))
                    assertThat(normal(rows)).containsExactly(
                        DucklakeInlinedChangeRow(0, inserted, first, listOf(1, "old", "005CFF", 42)),
                        DucklakeInlinedChangeRow(0, first, second, listOf(1, "new", "005CFF", 42)),
                        DucklakeInlinedChangeRow(0, second, null, listOf(1, "new2", "005CFF", 42)),
                        DucklakeInlinedChangeRow(1, inserted, deleted, listOf(2, "NULL", null, 42)),
                    )
                    val old = infos.first()
                    val historical = drain { after -> c.readInlinedDataDecodedPage(table.tableId, old.schemaVersion, inserted,
                        c.getTableColumns(table.tableId, inserted).map { it.columnId }, after, 1) }
                    assertThat(historical.map { it.rowId }).containsExactly(0, 1)
                    assertThat(historical.map { it.endSnapshot }).containsExactly(first, deleted)
                    Unit
                }) }
            }
        }

        @Test
        fun decodedNestedAndNanosecondCellsKeepNullParentsElementsMapsAndBlobBytes() {
            fixture().use { f ->
                f.native("CREATE TABLE lake.t(i INTEGER,ts TIMESTAMP_NS,s STRUCT(a INTEGER,b BLOB),xs STRUCT(v VARCHAR)[],m MAP(INTEGER,STRUCT(v VARCHAR))); " +
                    "INSERT INTO lake.t VALUES(1,TIMESTAMP_NS '1969-12-31 23:59:59.123456789',{'a':11,'b':from_hex('005CFF')}," +
                    "[{'v':'NULL'},NULL],map([1,2],[{'v':'hello'},NULL])),(2,NULL,NULL,NULL,NULL)")
                f.reader().use { c -> c.readSession(Supplier {
                    val snapshot = c.currentSnapshotId
                    val table = c.getTable("main", "t", snapshot)!!
                    val info = c.getInlinedDataInfos(table.tableId, snapshot).single()
                    val ids = c.getTableColumns(table.tableId, snapshot).map { it.columnId }
                    val rows = drain { after -> c.readInlinedDataDecodedPage(table.tableId, info.schemaVersion, snapshot, ids, after, 1) }
                    assertThat(normal(rows).map { it.values }).containsExactly(
                        listOf(1, LocalDateTime.parse("1969-12-31T23:59:59.123456789"), mapOf("a" to 11, "b" to "005CFF"),
                            listOf(mapOf("v" to "NULL"), null), mapOf(1 to mapOf("v" to "hello"), 2 to null)),
                        listOf(2, null, null, null, null),
                    )
                    Unit
                }) }
            }
        }

        fun head(f: ReadAcceptanceFixture): Long = f.metadataRows("SELECT max(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong()
    }

    @Test
    fun aNullSnapshotSchemaVersionCannotLookLikeAnEmptyInlinePage() {
        ReadAcceptanceFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB).use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
            f.metadata("UPDATE ducklake_snapshot SET schema_version=NULL")
            f.reader().use { c -> c.readSession(Supplier {
                val snapshot = c.currentSnapshotId
                assertThatThrownBy { c.readInlinedDataDecodedPage(0, 1, snapshot, emptyList(), null, 1) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("schema version")
                assertThatThrownBy { c.readPlanningMetadata().getInlineVersionsPage(0, snapshot, null, 1) }
                    .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("schema version")
            }) }
        }
    }
}
