# Read-session statement cancellation and deadlines

Additive development API on `JdbcDucklakeCatalog`; the existing Supplier-only
`readSession` remains unchanged:

```kotlin
val control = DucklakeReadSessionControl(timeoutSeconds = 30)
catalog.readSession(control, Supplier { catalog.currentSnapshotId })
// From another thread while the operation is running:
control.cancel()
```

Use one control per outer session. Timeout must be 1..3600 seconds and starts when
the session claims the control, not when it is constructed. Controls cannot be
reused or shared across simultaneous sessions. Nested reads reuse the existing
control; replacing an existing controlled or uncontrolled session's control rejects
instead of silently ignoring a deadline. Cancellation is sticky and can be requested
before entry. Nested calls and successful callback return both check it, so cancelled
work cannot become successful partial output. Caller failures remain the original
failure; there is no automatic retry.

Each executing jOOQ query publishes its actual JDBC statement and sets its query
timeout to the remaining deadline (rounded up to whole seconds). Another thread
can issue `Statement.cancel`. Statements are unpublished when each query ends,
including when jOOQ has already cleared its statement reference, and on session exit.
Cancellation of a finished control therefore cannot hit a later pooled borrower.
Rollback/release still happens on setup, query, timeout, cancellation and callback
failure. The opt-in format/layout/session rules and write refusal remain intact.

**Qualification:** genuine PostgreSQL metadata reads with the fixed native producer
and JDBC driver. Separate tests hold an ACCESS EXCLUSIVE lock on `ducklake_snapshot`,
observe the real reader waiting in `pg_stat_activity`, then verify both explicit
cancel and JDBC timeout finish the query **before releasing the fixture lock**.
The one-connection pool subsequently reads the original snapshot/table. This is
actual statement interruption, not merely responsive Trino cancellation or an
oracle query standing in for the library query.

## Limits — do not overstate the guarantee

- A true return from `cancel()` means a request was sent, not that the server has
  stopped. Driver cancellation failures propagate to that caller; the control
  remains cancelled. The JDBC driver may block the cancellation call itself.
- The deadline is also checked cooperatively before/after the callback, but does
  not interrupt arbitrary callback computation. Whole-second query timeouts can
  run up to a rounding interval past the remaining deadline; late output rejects.
- Pool acquisition, connection setup, JDBC prepare round trips and arbitrary network
  partitions are not bounded by this API. Driver connect/socket/network timeouts
  need separate qualification. No helper thread, connection kill or hidden retry
  pretends to supply those guarantees.
- Explicitly unsupported JDBC timeout configuration fails, not falls back to an
  unbounded statement. A driver that accepts but ignores timeout/cancel still needs
  its own tests. Local DuckDB, native Quack, Java Quack and MySQL interruption are
  **not certified by the PostgreSQL result**. In particular native wrapper prepare
  may itself perform remote work before the executing statement is available.
- Read sessions and cancellation controls are not snapshot/file retention leases,
  cleanup permission, mixed-worker negotiation or a production-rollout approval.

Targeted tests also cover invalid bounds, cancel-before-entry, single-use controls,
nested identity, end-of-callback cancellation, caller exception preservation and
an explicitly unsupported driver timeout. Connector adoption and blocked SQL
cancellation must be retested against the new exact candidate, not inferred from
these library results.

Full maintained verification passes **856 JUnit tests** (707 catalog, 51 SLT,
98 replay) plus **86 separate direct-Java tests**, zero failures/errors/JUnit skips;
17,602 corpus records and exclusions are unchanged. Lint, frozen ABI/wire and Java
17 checks pass (7,797 classes across17 artifacts). The four new cancellation tests
all execute. Log: `/tmp/opencode/catalog-r07-session-cancellation-full.log`
(22m51s). Strict native gates were not rerun or waived by this verification.
