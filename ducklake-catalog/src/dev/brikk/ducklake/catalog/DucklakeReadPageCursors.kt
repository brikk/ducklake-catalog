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

/** Lexicographic identity of an active data/delete association; a missing delete sorts first. */
@JvmRecord
data class DucklakeDataFileCursor(val dataFileId: Long, val deleteFileId: Long?)

@JvmRecord
data class DucklakeDataFilePageEntry(val cursor: DucklakeDataFileCursor, val dataFile: DucklakeDataFile)

/** Added-file order retains the existing CDC order: begin snapshot, then data-file ID. */
@JvmRecord
data class DucklakeAddedFileCursor(val beginSnapshot: Long, val dataFileId: Long)

@JvmRecord
data class DucklakeAddedFilePageEntry(val cursor: DucklakeAddedFileCursor, val dataFile: DucklakeDataFile)

/** Seek identity of one physical inline row version, independent of its insertion/deletion event window. */
@JvmRecord
data class DucklakeInlineCursor(val rowId: Long, val beginSnapshot: Long)

/** Incremental phase first, then full-file retirements; snapshot coordinates are candidate facts, not final CDC event order. */
@JvmRecord
data class DucklakeDeletionCursor(val fullFileDelete: Boolean, val snapshotId: Long, val dataFileId: Long, val deleteFileId: Long?)

@JvmRecord
data class DucklakeDeletionPageEntry(val cursor: DucklakeDeletionCursor, val deletion: DucklakeChangeFeedDeletion)

@JvmRecord
data class DucklakeInlinedFileDeleteCursor(val dataFileId: Long, val position: Long, val snapshotId: Long)
