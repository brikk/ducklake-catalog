# 0.16.0 — PgBouncer transaction-pooling support

All three modules and catalog test fixtures are released together at 0.16.0. Only the
catalog changes; replay and SLT are as in [0.15.1](RELEASE-0.15.1.md). Pins unchanged.

## Fixed

- **`readSession` isolation behind transaction-pooling proxies.** PostgreSQL read sessions
  set REPEATABLE READ with pgjdbc's session-level `SET SESSION CHARACTERISTICS`, sent outside
  the transaction. Behind PgBouncer in transaction mode that could run on a different server
  connection, so a session could silently run at READ COMMITTED (its pinned snapshot moving
  mid-session) and leak the setting to other clients. The isolation is now the first statement
  of the session's own transaction. Direct connections and session pooling were not affected.
  No API change.

## Deployment requirement (PgBouncer transaction mode)

PgBouncer ≥ 1.21 with `max_prepared_statements` > 0 (default 200 since 1.22), **or**
`prepareThreshold=0` on the JDBC URL. With neither, every operation fails loudly with
`prepared statement "S_n" already exists`. See
[PgBouncer and transaction-pooling proxies](../COMPATIBILITY.md#pgbouncer-and-other-transaction-pooling-proxies-0160).
MySQL behind ProxySQL is not covered.

## Verification

`TestPgBouncerTransactionPooling` (PgBouncer 1.23.1 by digest, both supported setups) covers
reads, writes, expiry/cleanup, recommended indexes and read-session pinning; the pinning probe
fails without the fix. The read-session fail-closed tests and detekt pass. The full suite
runs in CI.
Consumers: Trino wired the pool settings and PgBouncer docs against a local snapshot of this
code (`1d5702a`, 19 + 20 targeted tests on 483/484); Doris in progress.
