package dev.brikk.ducklake.corpus

import java.util.Locale

/** Per-file mirror state. Preparing SQL never announces an attachment or changes mirror eligibility. */
internal class ReplayAttachmentState(private val metadataRewriter: ((String) -> String)?) {
    data class PreparedSql(val sql: String, val attach: Attach? = null, val detachAlias: String? = null)
    data class Attach(
        val original: String,
        val attachment: OracleAttachment,
        val providedDataPath: String?,
        val mirrorable: Boolean,
        val pinned: Boolean,
        val ifNotExists: Boolean,
    )

    // Rewriters may provision a backend, so reserve each mapping once even when the SQL later fails.
    private val rewrittenTargets = mutableMapOf<String, String>()
    private val successfulDataPaths = mutableMapOf<String, String>()
    private val activeTargets = mutableMapOf<String, String>()
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
        val dataPath = DATA_PATH.find(options)?.groupValues?.get(1)
        val mirrorable = original in successfulDataPaths || dataPath != null
        val target = rewrittenTargets[original] ?: if (dataPath == null) original else
            rewrittenTargets.getOrPut(original) { metadataRewriter?.invoke(original) ?: original }
        val alias = match.groupValues[3].ifEmpty { original.substringAfterLast('/').substringBefore('.') }
        val attachment = OracleAttachment(target, dataPath ?: successfulDataPaths[original] ?: "", identifier(alias))
        val rewrittenSql = if (target == original) sql else sql.replace("'ducklake:$original'", "'ducklake:$target'")
        return PreparedSql(rewrittenSql, Attach(original, attachment, dataPath, mirrorable,
            SNAPSHOT_PIN.containsMatchIn(options), match.groupValues[1].isNotEmpty()))
    }

    /** Apply the actual SQL effect even if the SLT incorrectly expected an error; adapter setup stays separate. */
    fun succeeded(statement: PreparedSql): OracleAttachment? {
        statement.detachAlias?.let(::detached)
        val attach = statement.attach ?: return null
        val alias = key(attach.attachment.catalogAlias)
        if (attach.ifNotExists && alias in activeTargets) return null
        val replaced = activeTargets.put(alias, attach.attachment.metadataUri)
        attach.providedDataPath?.let { successfulDataPaths[attach.original] = it }
        if (replaced != null && alias == mirroredAlias?.let(::key)) disconnect("not-attached")
        when {
            attach.pinned -> disable("snapshot-pinned")
            activeTargets.values.distinct().size > 1 -> disable("multiple-lakes")
        }
        if (permanentReason != null || connected || !attach.mirrorable) return null
        return attach.attachment
    }

    private fun detached(alias: String) {
        activeTargets.remove(key(alias))
        if (key(alias) == mirroredAlias?.let(::key)) disconnect(permanentReason ?: "detached")
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
        private val SNAPSHOT_PIN = Regex("SNAPSHOT_(VERSION|TIME)", RegexOption.IGNORE_CASE)
        private val DETACH = Regex(
            "^\\s*DETACH\\s+(?:DATABASE\\s+)?(?:IF\\s+EXISTS\\s+)?($IDENTIFIER)\\s*;?\\s*$", RegexOption.IGNORE_CASE)
    }
}
