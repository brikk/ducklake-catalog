package dev.brikk.ducklake.catalog

/** Structural checks for column mutations, independent of whether an inline table exists. */
internal class CatalogColumnTree(private val columns: List<DucklakeColumn>) {
    private val byId = columns.associateBy { it.columnId }
    init {
        if (byId.size != columns.size) throw DucklakeCatalogCorruptionException("Duplicate active column identities")
    }

    fun column(id: Long): DucklakeColumn = byId[id] ?: throw DucklakeEntityNotFoundException("column", id.toString())

    fun requireRoot(id: Long) {
        if (column(id).parentColumn != null) {
            throw DucklakeInvalidOperationException("dropColumn requires a top-level column; use dropField for a nested STRUCT field")
        }
    }

    fun requireDroppableField(id: Long): DucklakeColumn {
        val field = column(id)
        val parent = field.parentColumn?.let(::parent)
            ?: invalid("dropField requires a nested STRUCT field")
        if (!parent.columnType.equals("struct", ignoreCase = true)) {
            invalid("Cannot remove a required LIST/MAP child or a field of a non-STRUCT column")
        }
        if (columns.count { it.parentColumn == parent.columnId } <= 1) {
            invalid("Cannot remove the last field of STRUCT ${parent.columnName}")
        }
        return root(field)
    }

    fun root(field: DucklakeColumn): DucklakeColumn {
        val seen = hashSetOf<Long>()
        var current = field
        while (current.parentColumn != null) {
            if (!seen.add(current.columnId)) throw DucklakeCatalogCorruptionException("Cycle in column parentage")
            current = parent(current.parentColumn)
        }
        return current
    }

    private fun parent(id: Long): DucklakeColumn = byId[id]
        ?: throw DucklakeCatalogCorruptionException("Missing column parent $id")

    private fun invalid(message: String): Nothing = throw DucklakeInvalidOperationException(message)
}
