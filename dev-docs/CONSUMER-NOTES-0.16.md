# Consumer notes for the next release (0.16.0) — trino-ducklake and doris-ducklake

Released as 0.16.0 ([release notes](../docs/releases/RELEASE-0.16.0.md)).

## Must review

### PgBouncer / transaction-pooling proxies (R4)

- **Fix, no API change:** `readSession` now sets REPEATABLE READ inside its own transaction.
  Before, behind a transaction-pooling proxy, a read session could silently run at READ COMMITTED
  (its pinned snapshot could move mid-session) and leak the setting to other clients. Direct
  connections and session pooling were not affected.
- **Deployment requirement** when the PostgreSQL metadata URL points at PgBouncer in transaction
  mode: PgBouncer ≥ 1.21 with `max_prepared_statements` > 0 (default 200 since 1.22), **or**
  `prepareThreshold=0` on the JDBC URL. With neither, every operation fails loudly with
  `prepared statement "S_n" already exists`; writes surface `DucklakeCommitOutcomeUnknownException`.

## Configuration to expose

Both plugins pass the JDBC URL through, so `prepareThreshold=0` already works if users can set
URL parameters; document it next to the PgBouncer requirement. Expose the 0.14 pool settings so
deployments behind a proxy can size and recycle connections:

| `DucklakeCatalogConfig` | Today in Trino | Today in Doris | Suggested property |
|---|---|---|---|
| `maxCatalogConnections` | exposed | exposed | (existing) |
| `minIdleCatalogConnections` | — | exposed | `…catalog.min-idle-connections` |
| `catalogConnectionIdleTimeout` | — | — | `…catalog.connection-idle-timeout` |
| `catalogConnectionMaxLifetime` | — | — | `…catalog.connection-max-lifetime` (keep below the proxy's `server_lifetime`) |
| `catalogConnectionTimeout` | — | — | `…catalog.connection-timeout` |

Property names are suggestions; use each plugin's conventions. Defaults are unchanged
(min idle 1, Hikari idle timeout/max lifetime, 30 s connection timeout).

## Should consider

- Add a PgBouncer deployment note (transaction mode, prepared statements) to both plugins' docs.
- A plugin smoke against PgBouncer is optional; the library's `TestPgBouncerTransactionPooling`
  covers reads, writes, expiry/cleanup, recommended indexes and read-session pinning.

## Not covered

- MySQL behind ProxySQL: `readSession` still uses session-level `setTransactionIsolation` there.
