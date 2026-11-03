package dev.brikk.ducklake.corpus

/**
 * Optional backend adaptation of the SQL the ORACLE executes, for fixtures whose native metadata SQL names the
 * metadata database's own namespace (e.g. `__ducklake_metadata_dl.main.ducklake_view` on DuckDB, which is
 * `public` on PostgreSQL) after a `metadataRewriter` moved that lake to another backend.
 *
 * Called for every oracle statement and query, on every named connection, after template/loop substitution and
 * the ATTACH target rewrite; the returned text is executed instead. Corpus files, goldens and expected errors are
 * unchanged, and mirrors still receive the unbound SQL. Implementations should rewrite structurally (only the
 * metadata catalog's schema qualifier of the lakes it adapts) and return [sql] unchanged otherwise.
 */
fun interface ReplayOracleSqlBinder {
    fun bind(sql: String, context: ReplayOracleSqlContext): String
}

/** The oracle state a [ReplayOracleSqlBinder] sees: the executing connection label and the active lakes. */
data class ReplayOracleSqlContext(
    /** Named SLT connection (`con1`, …), or null for the root connection. */
    val connection: String?,
    /** DuckLake lakes currently attached in the oracle database (attachments are database-wide). */
    val lakes: List<ReplayAttachedLake>,
)

/** One active oracle DuckLake attachment. */
data class ReplayAttachedLake(
    /** Catalog alias the corpus SQL uses. */
    val alias: String,
    /** The DuckLake connection remainder as written in the corpus (after substitution). */
    val originalMetadataUri: String,
    /** What the oracle actually attached: the possibly rewritten metadata URI, data path and alias. */
    val attachment: OracleAttachment,
    /** Native metadata catalog name (`__ducklake_metadata_<alias>` unless `METADATA_CATALOG` was given). */
    val metadataCatalog: String,
    /** An explicit `METADATA_SCHEMA` option, which binders must respect; null when not given. */
    val explicitMetadataSchema: String?,
) {
    /** True when a `metadataRewriter` moved this lake's metadata to another backend. */
    val rewritten: Boolean get() = attachment.metadataUri != originalMetadataUri
}
