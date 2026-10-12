"""Strict, disposable native qualification for upstream #1587 and #1662.

This is separate from JVM tests: a failed native writer/feed cannot be its oracle.
Run with a deliberately selected DuckDB package; no package or extension is repinned.
Exit nonzero on any wrong result, including known defects in the maintained engine.
"""

import argparse
import json
import tempfile
from pathlib import Path

import duckdb


def run(root):
    results = {"python_package": duckdb.__version__, "checks": {}}
    with duckdb.connect() as c:
        c.execute("INSTALL ducklake; LOAD ducklake")
        results["core"] = c.execute("PRAGMA version").fetchall()
        results["extensions"] = c.execute(
            "SELECT extension_name, extension_version, installed_from FROM duckdb_extensions() WHERE loaded ORDER BY 1"
        ).fetchall()
        uri = str(root / "lake.db").replace("'", "''")
        data = str(root / "files").replace("'", "''")
        c.execute(f"ATTACH 'ducklake:{uri}' AS lake (DATA_PATH '{data}', METADATA_CATALOG 'lake_meta', DATA_INLINING_ROW_LIMIT 10)")
        results["catalog_version"] = c.execute("SELECT value FROM lake_meta.ducklake_metadata WHERE key='version'").fetchall()
        c.execute("CREATE TABLE lake.t(id INTEGER, v VARCHAR)")
        c.execute("INSERT INTO lake.t VALUES (1, 'a')")
        first = c.execute("SELECT max(snapshot_id) FROM ducklake_snapshots('lake')").fetchone()[0] + 1
        c.execute("UPDATE lake.t SET v='b'")
        c.execute("UPDATE lake.t SET v='c'")
        expected = [(first, 0, "a"), (first + 1, 0, "b")]
        c.execute("CALL ducklake_flush_inlined_data('lake', table_name => 't')")
        actual = c.execute(
            f"SELECT snapshot_id, rowid, v FROM ducklake_table_deletions('lake','main','t',{first},{first + 1}) ORDER BY ALL"
        ).fetchall()
        results["deletions_after_repeated_updates"] = {"actual": actual, "expected": expected}
        results["checks"]["positional_change_feed"] = actual == expected

        c.execute("CREATE TABLE lake.u(id INTEGER)")
        c.execute("INSERT INTO lake.u SELECT * FROM range(20)")
        c.execute("CALL ducklake_flush_inlined_data('lake', table_name => 'u')")
        c.execute("DELETE FROM lake.u WHERE id < 5")
        c.execute("BEGIN")
        c.execute("DELETE FROM lake.u WHERE id >= 5 AND id < 16")
        c.execute("CALL ducklake_flush_inlined_data('lake', table_name => 'u')")
        c.execute("COMMIT")
        actual = c.execute("SELECT id FROM lake.u ORDER BY id").fetchall()
        count = c.execute("SELECT count(*) FROM lake.u").fetchone()[0]
        expected = [(16,), (17,), (18,), (19,)]
        results["transaction_delete_flush"] = {"actual": actual, "expected": expected, "metadata_count": count}
        results["checks"]["transaction_delete_flush"] = actual == expected and count == 4
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="catalog-11-regressions-", dir=args.temp_root) as directory:
        results = run(Path(directory))
    print(json.dumps(results, indent=2))
    if not all(results["checks"].values()):
        raise SystemExit("Native qualification failed; do not use these results as JVM goldens")


if __name__ == "__main__":
    main()
