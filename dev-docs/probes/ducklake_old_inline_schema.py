"""Strict old-schema native qualification on a disposable maintained/preview lake.

Do not bless NULL output as a golden: #1590 concerns real legacy 1.0 values.
This exits nonzero when native reads/flushes lose the independently known values.
"""

import argparse
import json
import tempfile
from pathlib import Path

import duckdb


def run(root):
    with duckdb.connect() as c:
        c.execute("INSTALL ducklake; LOAD ducklake")
        uri = str(root / "lake.db").replace("'", "''")
        data = str(root / "data").replace("'", "''")
        c.execute(f"ATTACH 'ducklake:{uri}' AS lake (DATA_PATH '{data}', METADATA_CATALOG 'meta', DATA_INLINING_ROW_LIMIT 0)")
        c.execute("CREATE TABLE lake.t(id INTEGER)")
        c.execute("ALTER TABLE lake.t ADD COLUMN value INTEGER")
        c.execute("CALL lake.set_option('data_inlining_row_limit',10)")
        c.execute("INSERT INTO lake.t VALUES (1,11)")
        c.execute("CREATE TABLE lake.unrelated(i INTEGER)")
        table = c.execute("SELECT table_name FROM meta.ducklake_inlined_data_tables WHERE table_id=(SELECT table_id FROM meta.ducklake_table WHERE table_name='t')").fetchone()[0]
        stored = c.execute(f'SELECT id,value FROM meta."{table}"').fetchall()
        before = c.execute("SELECT id,value FROM lake.t").fetchall()
        c.execute("CALL ducklake_flush_inlined_data('lake', table_name => 't')")
        after = c.execute("SELECT id,value FROM lake.t").fetchall()
        expected = [(1, 11)]
        return {
            "python_package": duckdb.__version__, "core": c.execute("PRAGMA version").fetchall(),
            "ducklake": c.execute("SELECT extension_version FROM duckdb_extensions() WHERE extension_name='ducklake'").fetchone()[0],
            "physical_before_flush": stored, "native_before_flush": before, "native_after_flush": after,
            "expected": expected,
            "checks": {"physical_values": stored == expected, "native_read": before == expected, "native_flush": after == expected},
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="catalog-old-inline-", dir=args.temp_root) as directory:
        result = run(Path(directory))
    print(json.dumps(result, indent=2))
    if not all(result["checks"].values()):
        raise SystemExit("Native old-schema qualification failed; the original values must not become NULL")


if __name__ == "__main__":
    main()
