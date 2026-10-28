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

import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A stale rewrite cannot replace a source whose native inlined deletes were not materialized. */
internal object RewriteInlinedDeleteGuard {
    private const val IDS_PER_QUERY = 256

    /** The caller probes lazy table existence without failing SQL, inside this same write attempt. */
    fun check(ctx: DSLContext, metadata: MetadataQuery, tableName: String, sources: Set<Long>, readSnapshot: Long) {
        val file = DSL.field(DSL.name("file_id"), Long::class.javaObjectType)
        val begin = DSL.field(DSL.name("begin_snapshot"), Long::class.javaObjectType)
        val unknownOwner = file.isNull.or(file.lt(0L))
        val unknownTime = begin.isNull.or(begin.lt(0L))
        for (ids in sources.asSequence().chunked(IDS_PER_QUERY)) {
            // Transfer at most one pair of numeric facts, never the whole delete ledger. Chunk the
            // supplied identities too, so large rewrites do not generate a giant candidate-ID list.
            val row = metadata.fetchOne(ctx, ctx.select(file, begin).from(DSL.table(DSL.name(tableName)))
                .where(unknownOwner.or(file.`in`(ids).and(unknownTime.or(begin.gt(readSnapshot)))))
                .limit(1)) ?: continue
            val id = row.get(0, Long::class.javaObjectType)
            val snapshot = row.get(1, Long::class.javaObjectType)
            if (id == null || id < 0) {
                corrupt("Invalid inlined file-delete owner in $tableName; cannot qualify rewrite output")
            }
            if (snapshot == null || snapshot < 0) {
                corrupt("Invalid inlined file-delete identity/snapshot in $tableName; cannot qualify rewrite output")
            }
            throw LogicalConflictException(
                "Failed to rewrite data files: a newer inlined deletion affects source data_file_id $id " +
                    "after read snapshot $readSnapshot. The materialized output would resurrect deleted rows; " +
                    "re-plan from the current snapshot. This conflict is not retried.",
            )
        }
    }

    private fun corrupt(message: String): Nothing = throw DucklakeCatalogCorruptionException(message)
}
