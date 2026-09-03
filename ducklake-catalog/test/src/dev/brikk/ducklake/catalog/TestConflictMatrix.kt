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

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The change-vs-change matrix, entry by entry, against upstream ducklake v1.5
 * `DuckLakeTransactionState::CheckForConflicts` (TODO-rectify-from-eval.md W-D2, C-D2, R-B4).
 * "Other" transactions are described by their `changes_made` text exactly as upstream writes it.
 */
class TestConflictMatrix {
    private fun other(vararg changesMade: String): InterveningChanges = InterveningChanges.parseAll(changesMade.toList())

    private fun conflicts(mine: WriteChange, vararg theirs: String) {
        assertThatThrownBy { ConflictMatrix.check(listOf(mine), other(*theirs)) }
            .`as`("$mine vs ${theirs.toList()}")
            .isInstanceOf(LogicalConflictException::class.java)
    }

    private fun noConflict(mine: WriteChange, vararg theirs: String) {
        assertThatCode { ConflictMatrix.check(listOf(mine), other(*theirs)) }
            .`as`("$mine vs ${theirs.toList()}")
            .doesNotThrowAnyException()
    }

    private val insert = WriteChange.InsertedIntoTable(7, setOf(1L))
    private val delete = WriteChange.DeletedFromTable(7, setOf(10L))
    private val rewrite = WriteChange.RewriteDelete(7, setOf(10L))
    private val merge = WriteChange.MergeAdjacent(7, setOf(10L))
    private val alter = WriteChange.AlteredTable(7)
    private val flush = WriteChange.FlushedInlinedData(7)

    @Test
    fun insertRow() {
        conflicts(insert, "dropped_table:7")
        conflicts(insert, "altered_table:7")
        conflicts(insert, "deleted_from_table:7") // v1.5 addition
        conflicts(insert, "inlined_delete:7") // v1.5 addition
        noConflict(insert, "inserted_into_table:7", "inlined_insert:7", "inline_flush:7", "rewrite_delete:7", "merge_adjacent:7")
        noConflict(insert, "deleted_from_table:8", "dropped_table:8")
    }

    @Test
    fun deleteRow() {
        conflicts(delete, "dropped_table:7")
        conflicts(delete, "altered_table:7")
        conflicts(delete, "merge_adjacent:7")
        conflicts(delete, "rewrite_delete:7")
        conflicts(delete, "inserted_into_table:7") // v1.5 addition
        conflicts(delete, "inlined_insert:7") // v1.5 addition
        // Two deletes on the same TABLE are not a table-level conflict; the FILE overlap check
        // (JdbcDucklakeCatalog.checkDeleteFileOverlap) decides.
        noConflict(delete, "deleted_from_table:7", "inline_flush:7", "inlined_delete:7")
    }

    @Test
    fun compactionRows() {
        for (mine in listOf(rewrite, merge)) {
            conflicts(mine, "dropped_table:7")
            conflicts(mine, "deleted_from_table:7")
            conflicts(mine, "merge_adjacent:7")
            conflicts(mine, "rewrite_delete:7")
            noConflict(mine, "inserted_into_table:7", "altered_table:7", "inlined_insert:7", "inline_flush:7")
        }
    }

    @Test
    fun alterRow() {
        conflicts(alter, "dropped_table:7")
        conflicts(alter, "altered_table:7")
        noConflict(alter, "inserted_into_table:7", "deleted_from_table:7", "rewrite_delete:7")
    }

    @Test
    fun flushRowIsUpstreamPlusStricterEntries() {
        conflicts(flush, "dropped_table:7")
        conflicts(flush, "inlined_delete:7")
        conflicts(flush, "inline_flush:7")
        // Stricter than upstream (documented on ConflictMatrix.checkFlushedInlinedData).
        conflicts(flush, "altered_table:7")
        conflicts(flush, "inlined_insert:7")
        noConflict(flush, "inserted_into_table:7", "deleted_from_table:7")
    }

    @Test
    fun renamedTableCollidesWithConcurrentCreateOfTheNewName() {
        // renameTable records created_table:"s"."new" — so does upstream for a RENAMED entry.
        val rename = WriteChange.CreatedTable(1, "s", "new")
        conflicts(rename, "created_table:\"s\".\"new\"")
        conflicts(rename, "created_view:\"s\".\"new\"")
        conflicts(rename, "dropped_schema:1")
        noConflict(rename, "created_table:\"s\".\"other\"", "created_table:\"t\".\"new\"")
        // And the other direction: our CREATE vs their rename (which they also record as created_table).
        conflicts(WriteChange.CreatedView(1, "s", "new"), "created_table:\"s\".\"new\"")
    }

    @Test
    fun dropSchemaConflictsWithAnythingCreatedInIt() {
        val drop = WriteChange.DroppedSchema(1, "s")
        conflicts(drop, "created_table:\"s\".\"t\"")
        conflicts(drop, "created_view:\"s\".\"v\"")
        conflicts(drop, "dropped_schema:1")
        noConflict(drop, "created_table:\"u\".\"t\"")
    }
}
