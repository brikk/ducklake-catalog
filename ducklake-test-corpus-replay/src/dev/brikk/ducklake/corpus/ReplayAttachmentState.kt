package dev.brikk.ducklake.corpus

import java.util.Locale

/** Per-file mirror state. Preparing SQL never announces an attachment or changes mirror eligibility. */
internal class ReplayAttachmentState(
    private val metadataRewriter: ((String) -> String)?,
    private val rewriteScope: MetadataRewriteScope = MetadataRewriteScope.ALL_ATTACHES,
    /** Current substituted `DUCKLAKE_CONNECTION` test-env value, for [MetadataRewriteScope.CONNECTION_ENV_ONLY]. */
    private val connectionEnv: () -> String? = { null },
) {
    data class PreparedSql(val sql: String, val attach: Attach? = null, val detachAlias: String? = null)
    data class Attach(
        val original: String,
        val attachment: OracleAttachment,
        val providedDataPath: String?,
        val mirrorable: Boolean,
        val pinned: Boolean,
        val ifNotExists: Boolean,
        /** Native metadata catalog/schema holding this lake's `ducklake_metadata` (format inspection). */
        val metadataCatalog: String = "",
        val metadataSchema: String? = null,
        /** A hardcoded fixture lake left on its own backend by [MetadataRewriteScope.CONNECTION_ENV_ONLY]. */
        val backendLocal: Boolean = false,
    )

    // Rewriters may provision a backend, so reserve each mapping once even when the SQL later fails.
    private val rewrittenTargets = mutableMapOf<String, String>()
    private val successfulDataPaths = mutableMapOf<String, String>()
    private val activeTargets = mutableMapOf<String, String>()
    private val activeLakes = linkedMapOf<String, Attach>()
    private var mirroredAlias: String? = null
    private var permanentReason: String? = null
    var connected = false
        private set
    var unavailableReason = "not-attached"
        private set

    fun prepare(sql: String): PreparedSql {
        DETACH.find(sql)?.let { return PreparedSql(sql, detachAlias = identifier(it.groupValues[1])) }
        val match = ATTACH.find(sql) ?: return PreparedSql(sql)
        val original = match.groupValues[2]
        val options = match.groupValues[4]
        val dataPath = option(DATA_PATH, options)
        val backendLocal = isBackendLocal(original)
        val mirrorable = !backendLocal && (original in successfulDataPaths || dataPath != null)
        val target = rewrittenTargets[original] ?: if (dataPath == null || backendLocal) original else
            rewrittenTargets.getOrPut(original) { metadataRewriter?.invoke(original) ?: original }
        val alias = match.groupValues[3].ifEmpty { original.substringAfterLast('/').substringBefore('.') }
        val attachment = OracleAttachment(target, dataPath ?: successfulDataPaths[original] ?: "", identifier(alias))
        val rewrittenSql = if (target == original) sql else sql.replace("'ducklake:$original'", "'ducklake:$target'")
        val metadataCatalog = option(METADATA_CATALOG, options) ?: "__ducklake_metadata_${identifier(alias)}"
        return PreparedSql(rewrittenSql, Attach(original, attachment, dataPath, mirrorable,
            SNAPSHOT_PIN.containsMatchIn(options), match.groupValues[1].isNotEmpty(),
            metadataCatalog, option(METADATA_SCHEMA, options), backendLocal))
    }

    /** Upstream backend configs only substitute DUCKLAKE_CONNECTION; hardcoded fixture lakes stay where written. */
    private fun isBackendLocal(original: String): Boolean =
        metadataRewriter != null && rewriteScope == MetadataRewriteScope.CONNECTION_ENV_ONLY &&
            connectionEnv()?.let { original.startsWith(it) } != true

    private fun option(regex: Regex, options: String): String? = regex.find(options)?.groupValues?.get(1)

    /**
     * Apply the actual SQL effect even if the SLT incorrectly expected an error; adapter setup stays separate.
     * [formatGate] returns a measured skip reason when the attached lake's stored format is out of the mirror's
     * scope (null = in scope); such a lake is never connected and replacing the mirrored alias disconnects it.
     * An inspection failure propagates after marking the lake unavailable. Earlier outcomes stand, and a later
     * in-scope lake can still connect.
     */
    fun succeeded(statement: PreparedSql, formatGate: ((Attach) -> String?)? = null): OracleAttachment? {
        statement.detachAlias?.let(::detached)
        val attach = statement.attach ?: return null
        val alias = key(attach.attachment.catalogAlias)
        if (attach.ifNotExists && alias in activeTargets) return null
        val replaced = activeTargets.put(alias, attach.attachment.metadataUri)
        activeLakes[alias] = attach
        attach.providedDataPath?.let { successfulDataPaths[attach.original] = it }
        applyAttachRules(alias, attach, replaced)
        if (formatBlocked(attach, replaced, formatGate)) return null
        if (attach.backendLocal && permanentReason == null && !connected) unavailableReason = "backend-local-fixture"
        return attach.attachment.takeIf { permanentReason == null && !connected && attach.mirrorable }
    }

    private fun applyAttachRules(alias: String, attach: Attach, replaced: String?) {
        if (replaced != null && alias == mirroredAlias?.let(::key)) disconnect("not-attached")
        when {
            attach.pinned -> disable("snapshot-pinned")
            activeTargets.values.distinct().size > 1 -> disable("multiple-lakes")
        }
    }

    private fun formatBlocked(attach: Attach, replaced: String?, formatGate: ((Attach) -> String?)?): Boolean {
        if (formatGate == null || permanentReason != null) return false
        if (!attach.mirrorable && replaced == null) return false
        val reason = try {
            formatGate(attach)
        } catch (e: ReplayFormatInspectionFailed) {
            // The ATTACH itself succeeded; never connect a lake whose format is not positively known.
            if (!connected) unavailableReason = "format-inspection-failed"
            throw e
        }
        if (reason != null && !connected) unavailableReason = reason
        return reason != null
    }

    private fun detached(alias: String) {
        activeTargets.remove(key(alias))
        activeLakes.remove(key(alias))
        if (key(alias) == mirroredAlias?.let(::key)) disconnect(permanentReason ?: "detached")
    }

    /** Active lakes for [ReplayOracleSqlBinder]; attachments are database-wide, not per named connection. */
    fun lakes(): List<ReplayAttachedLake> = activeLakes.values.map {
        ReplayAttachedLake(it.attachment.catalogAlias, it.original, it.attachment, it.metadataCatalog, it.metadataSchema)
    }

    fun connectionSucceeded(attachment: OracleAttachment) {
        connected = true
        mirroredAlias = attachment.catalogAlias
    }

    fun connectionFailed() = disconnect("adapter-setup-failed")

    private fun disconnect(reason: String) {
        connected = false
        mirroredAlias = null
        unavailableReason = reason
    }

    private fun disable(reason: String) {
        permanentReason = permanentReason ?: reason
        disconnect(requireNotNull(permanentReason))
    }

    private fun key(alias: String): String = alias.lowercase(Locale.ROOT)
    private fun identifier(sql: String): String = sql.removeSurrounding("\"").replace("\"\"", "\"")

    companion object {
        private const val IDENTIFIER = "\"(?:[^\"]|\"\")*\"|[A-Za-z_][A-Za-z0-9_]*"
        private val ATTACH = Regex(
            "ATTACH\\s+(?:OR\\s+REPLACE\\s+)?(?:(IF\\s+NOT\\s+EXISTS)\\s+)?'ducklake:([^']+)'" +
                "(?:\\s+AS\\s+($IDENTIFIER))?(?:\\s*\\(([^)]*)\\))?", RegexOption.IGNORE_CASE)
        private val DATA_PATH = Regex("DATA_PATH\\s+'([^']*)'", RegexOption.IGNORE_CASE)
        private val METADATA_CATALOG = Regex("METADATA_CATALOG\\s+'([^']*)'", RegexOption.IGNORE_CASE)
        private val METADATA_SCHEMA = Regex("METADATA_SCHEMA\\s+'([^']*)'", RegexOption.IGNORE_CASE)
        private val SNAPSHOT_PIN = Regex("SNAPSHOT_(VERSION|TIME)", RegexOption.IGNORE_CASE)
        private val DETACH = Regex(
            "^\\s*DETACH\\s+(?:DATABASE\\s+)?(?:IF\\s+EXISTS\\s+)?($IDENTIFIER)\\s*;?\\s*$", RegexOption.IGNORE_CASE)
    }
}
