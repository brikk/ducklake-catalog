package dev.brikk.ducklake.catalog

import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_PARTITION_INFO
import dev.brikk.ducklake.catalog.schema.PublicDbTables.DUCKLAKE_PARTITION_COLUMN
import org.jooq.DSLContext

/** Validate caller-supplied partition identities and exact key sets before registering any files. */
internal class PartitionWriteValidation(
    private val ctx: DSLContext, private val metadata: MetadataQuery, private val tableId: Long, private val snapshot: Long,
) {
    private val keysBySpec = mutableMapOf<Long, Set<Int>>()

    fun validate(fragment: DucklakeWriteFragment) {
        val id = fragment.partitionId
        if (id == null) {
            if (fragment.partitionValues.isNotEmpty()) invalid("Partition values require a partition ID")
            return
        }
        if (id < 0) invalid("Negative partition ID")
        val keys = keysBySpec.getOrPut(id) { keys(id, fragment.partitionValues.size) }
        if (fragment.partitionValues.keys != keys) invalid("Partition $id requires exactly keys $keys; missing and SQL NULL are distinct")
    }

    private fun keys(id: Long, provided: Int): Set<Int> {
        val p = DUCKLAKE_PARTITION_INFO
        val owners = metadata.fetch(ctx, ctx.select(p.TABLE_ID, p.BEGIN_SNAPSHOT).from(p)
            .where(p.PARTITION_ID.eq(id)).limit(2))
        if (owners.size != 1) invalid("Unknown or ambiguous partition $id")
        if (owners.single().value1() != tableId) invalid("Foreign partition $id for table $tableId")
        val begin = owners.single().value2() ?: corrupt("Missing partition $id begin snapshot")
        if (begin > snapshot) invalid("Partition $id is not yet visible")
        // Retired specs may still describe retained/rewrite inputs; end_snapshot does not change their keys.
        val f = DUCKLAKE_PARTITION_COLUMN
        val rows = metadata.fetch(ctx, ctx.select(f.TABLE_ID, f.PARTITION_KEY_INDEX).from(f)
            .where(f.PARTITION_ID.eq(id)).limit(Math.addExact(provided, 1)))
        if (rows.size > provided) invalid("Incomplete partition values for spec $id")
        val keys = rows.map {
            val key = it.value2() ?: corrupt("Missing partition key in spec $id")
            if (it.value1() != tableId || key !in 0L..Int.MAX_VALUE.toLong()) {
                corrupt("Invalid partition-field ownership/key for spec $id")
            }
            key.toInt()
        }.toSet()
        if (keys.size != rows.size) corrupt("Duplicate partition key in spec $id")
        return keys
    }

    private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException(message)
    private fun corrupt(message: String): Nothing = throw DucklakeCatalogCorruptionException(message)
}
