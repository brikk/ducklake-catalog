# Consumer notes for the next release (0.17.0) — trino-ducklake and doris-ducklake

Running list; main is `0.17.0-SNAPSHOT`, nothing here is released yet.

## Should consider

### O(columns) planning statistics (R2)

New, additive: `getCurrentColumnBounds(tableId): List<DucklakeColumnBounds>` (`columnId`,
`containsNull`, `containsNan`, `minValue`, `maxValue`).

- Reads `ducklake_table_column_stats` once per table instead of aggregating every active file's
  stats (`getColumnStats`, O(files × columns)). Worth it for tables with many files.
- **Only for the latest snapshot and only inside `readSession`** (throws otherwise). For time
  travel, keep `getColumnStats`.
- Bounds are a gross **superset**: deleted rows may keep them wide. Fine for pruning and
  estimates; never answer MIN/MAX from them. Treat `containsNan = true` floating bounds as
  non-pruning.
- A column with no row is **omitted = unknown**: fall back to `getColumnStats` for it. Native
  DuckDB removes all of a table's rows when a delete retires a whole file, after ALTER TYPE, etc.
- No value/NULL counts or sizes; keep `getColumnStats` for those.
- The opt-in final-1.1 reader omits both endpoints, as `getColumnStats` does.

No existing API changed.
