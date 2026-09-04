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

/**
 * Base exception for Ducklake catalog operations.
 */
open class DucklakeException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/**
 * Thrown when the catalog database is reachable but its DuckLake metadata schema
 * (the `ducklake_*` tables, e.g. `ducklake_snapshot`) has not been created yet — i.e.
 * the catalog was never bootstrapped. The connector never issues the schema DDL itself;
 * a fresh catalog must be initialized once (e.g. by attaching it from DuckDB) before use.
 * This turns the otherwise-opaque low-level "table does not exist" SQL error into a clear,
 * actionable message. Engine adapters translate it into their own user-facing error type.
 */
open class DucklakeCatalogNotInitializedException(
    message: String,
    cause: Throwable?,
) : DucklakeException(message, cause)

/**
 * Thrown when a catalog operation fails due to a concurrent commit
 * (optimistic concurrency conflict on the snapshot sequence).
 *
 * By default these are *retryable* — the optimistic-retry policy
 * in [WriteTransactionRetry] will re-run the transaction. Subclasses
 * can override [retryable] to mark the conflict terminal (e.g.
 * the in-flight payload references catalog entities a concurrent commit
 * already removed; re-running with the same payload would fail
 * identically).
 */
open class TransactionConflictException(
    message: String,
    cause: Throwable?,
) : DucklakeException(message, cause) {
    /**
     * Whether the optimistic-retry policy should re-run the operation.
     * Defaults to `true`; non-retryable subclasses override.
     */
    open fun retryable(): Boolean {
        return true
    }
}

/**
 * A [TransactionConflictException] surfaced by [LogicalConflictCheck]:
 * a concurrent commit removed (or otherwise mutated) a catalog entity that
 * the in-flight transaction's payload references. Examples:
 *
 *  * `commitInsert` fragments name a `column_id` that an
 *    intervening `DROP COLUMN` end-snapshotted.
 *  * `commitDelete` fragments target a `data_file_id` that
 *    an intervening `DROP TABLE` or compaction end-snapshotted.
 *  * `addColumn` / `dropColumn` / `commitInsert` on a
 *    `table_id` that an intervening `DROP TABLE`
 *    end-snapshotted.
 *
 * This conflict is *not retryable*: the action's per-call arguments
 * (table IDs, fragment column / file IDs) are captured before the catalog
 * call and would feed the same stale references into a retry. The
 * [WriteTransactionRetry] loop bails out on these and rethrows
 * immediately.
 */
open class LogicalConflictException
        : TransactionConflictException
{
    constructor(message: String) : super(message, null)

    constructor(message: String, cause: Throwable?) : super(message, cause)

    override fun retryable(): Boolean
    {
        return false
    }
}

/**
 * Thrown by [DucklakeCatalog.dropSchema] when the schema still owns active objects (tables,
 * views, or macros) at the current snapshot. DuckLake has no schema-level CASCADE in this
 * library; the caller drops the contents first. Refusing here is what keeps a live view/macro
 * from being left pointing at an end-snapshotted schema — a shape upstream DuckDB refuses to
 * load ("could not find schema that corresponds to the view entry").
 *
 * @property schemaName the schema that was not dropped.
 * @property remainingObjectKinds which kinds of object still exist, in upstream's order
 *   (`tables`, `views`, `macros`) — for the engine's user-facing message.
 */
open class DucklakeSchemaNotEmptyException(
    val schemaName: String,
    val remainingObjectKinds: List<String>,
) : DucklakeException(
    "Cannot drop schema $schemaName: schema is not empty (still has ${remainingObjectKinds.joinToString(", ")})",
)

/**
 * Thrown when a file-writing operation (INSERT / DELETE / MERGE / add_files / flush / rewrite) is
 * attempted against a catalog whose `ducklake_metadata.encrypted = 'true'`. Upstream requires every
 * data and delete file of an encrypted lake to carry an `encryption_key`; a file registered without
 * one is unreadable ("Database is encrypted, but file %s does not have an encryption key"). This
 * library does not implement per-file encryption, so it refuses rather than corrupt the lake.
 * Metadata-only DDL (schemas, tables, columns, views, comments) is unaffected.
 */
open class DucklakeEncryptedCatalogUnsupportedException(
    val catalogDatabaseUrl: String,
) : DucklakeException(
    "DuckLake catalog at \"$catalogDatabaseUrl\" is encrypted (ducklake_metadata.encrypted = 'true'). " +
        "This client cannot write encrypted data or delete files; the operation was refused so no " +
        "unreadable file registration is committed.",
)

/**
 * Thrown when a write is attempted against a catalog whose DuckLake spec `version` this library does
 * not write. The row shapes it inserts (`ducklake_column.default_value_type`,
 * `ducklake_name_mapping.is_partition`, `ducklake_data_file.partial_max`,
 * `ducklake_schema_versions.table_id`, ...) exist from spec 0.4 on; upstream migrates older catalogs
 * in place on ATTACH (`ducklake_initializer.cpp` / `MigrateV0x`), so the remedy is to attach the
 * catalog once from a current DuckDB.
 */
open class DucklakeUnsupportedCatalogVersionException(
    val catalogDatabaseUrl: String,
    val version: String?,
) : DucklakeException(
    "DuckLake catalog at \"$catalogDatabaseUrl\" has spec version " + (version ?: "<missing>") +
        "; this client writes spec versions ${DucklakeSpecVersions.WRITABLE.joinToString(", ")} only. " +
        "Attach the catalog from a current DuckDB (INSTALL ducklake; LOAD ducklake; ATTACH ...) to migrate it, then retry.",
)

/** DuckLake spec versions as recorded in `ducklake_metadata.version`. */
object DucklakeSpecVersions {
    /** Versions whose catalog row shapes this library writes correctly (0.4 -> 1.0 was a version bump only). */
    @JvmField
    val WRITABLE: Set<String> = setOf("0.4", "1.0")
}

/**
 * A catalog object the operation needs does not exist (or is not active at the transaction's
 * snapshot): schema, table, view, column, nested field, partition column. Engines map this to their
 * NOT_FOUND class. Also what a retried write sees when a concurrent commit dropped its target
 * between attempts — the object genuinely no longer exists.
 *
 * @property entityKind `schema` / `table` / `view` / `column` / `field` / `partition column`.
 * @property entityName the name or id the caller used.
 */
open class DucklakeEntityNotFoundException(
    val entityKind: String,
    val entityName: String,
) : DucklakeException("${entityKind.replaceFirstChar { it.uppercase() }} not found: $entityName")

/**
 * A catalog object with this name already exists (and is active) where the operation would create
 * one: schema, table, view, field. Engines map this to their ALREADY_EXISTS class.
 */
open class DucklakeEntityAlreadyExistsException(
    val entityKind: String,
    val entityName: String,
) : DucklakeException("${entityKind.replaceFirstChar { it.uppercase() }} already exists: $entityName")

/**
 * The requested operation is not valid for its target (e.g. a cross-schema table rename, adding a
 * field to a non-struct column, an empty field path). A caller bug or unsupported request, not a
 * catalog fault; engines map this to their INVALID_ARGUMENTS / NOT_SUPPORTED class.
 */
open class DucklakeInvalidOperationException(message: String) : DucklakeException(message)

/**
 * The catalog's metadata is internally inconsistent (e.g. two active `ducklake_view` rows for one
 * name, a `list` column without exactly one child, a NULL where the spec requires a value). Not
 * retryable and not the caller's fault; surfaces the corruption instead of guessing.
 */
open class DucklakeCatalogCorruptionException : DucklakeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}
