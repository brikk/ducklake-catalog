"""Strict disposable native gates for #1611, #1717 and #1695.

Every expected row/ID/byte is independently specified. SQL errors are failed checks,
not skips. Run with an explicitly pinned package; no production/preview pin changes.
"""

import argparse
import json
import tempfile
from pathlib import Path

import duckdb


def encoded(value):
    if isinstance(value, bytes):
        return {"hex": value.hex()}
    if isinstance(value, (tuple, list)):
        return [encoded(v) for v in value]
    if isinstance(value, dict):
        return {key: encoded(v) for key, v in value.items()}
    return value


class Case:
    def __init__(self, c):
        self.c = c
        self.checks = {}
        self.observations = {}

    def query(self, name, sql, expected, parameters=None):
        try:
            actual = self.c.execute(sql, parameters or []).fetchall()
            self.observations[name] = {"actual": encoded(actual), "expected": encoded(expected)}
            self.checks[name] = actual == expected
        except duckdb.Error as error:
            self.observations[name] = {"error": str(error), "expected": encoded(expected)}
            self.checks[name] = False

    def command(self, name, sql):
        try:
            self.c.execute(sql)
            self.checks[name] = True
        except duckdb.Error as error:
            self.observations[name] = {"error": str(error)}
            self.checks[name] = False


def sorted_merge(case):
    c = case.c
    c.execute("CREATE TABLE lake.t(id INTEGER)")
    c.execute("ALTER TABLE lake.t SET SORTED BY (id)")
    c.execute("INSERT INTO lake.t VALUES (1)")
    first = c.execute("SELECT max(snapshot_id) FROM meta.ducklake_snapshot").fetchone()[0]
    c.execute("INSERT INTO lake.t VALUES (0)")
    expected = [(1, 0), (0, 1)]
    case.query("before_merge", "SELECT rowid,id FROM lake.t ORDER BY id", expected)
    case.command("merge", "CALL ducklake_merge_adjacent_files('lake','t')")
    case.query("after_merge", "SELECT rowid,id FROM lake.t ORDER BY id", expected)
    case.query("history_after_merge", f"SELECT rowid,id FROM lake.t AT (VERSION => {first})", [(0, 1)])
    c.execute("INSERT INTO lake.t VALUES (2)")
    case.command("merge_again", "CALL ducklake_merge_adjacent_files('lake','t')")
    case.command("checkpoint", "CHECKPOINT lake")
    case.query("after_checkpoint", "SELECT rowid,id FROM lake.t ORDER BY id", [(1, 0), (0, 1), (2, 2)])


def updated_merge(case):
    c = case.c
    c.execute("CREATE TABLE lake.t(id INTEGER,v INTEGER)")
    c.execute("INSERT INTO lake.t VALUES (1,NULL)")
    c.execute("UPDATE lake.t SET v=10 WHERE id=1")
    updated = c.execute("SELECT max(snapshot_id) FROM meta.ducklake_snapshot").fetchone()[0]
    c.execute("INSERT INTO lake.t VALUES (2,NULL)")
    expected = [(0, 1, 10), (2, 2, None)]
    case.query("before_merge", "SELECT rowid,id,v FROM lake.t ORDER BY id", expected)
    case.command("merge", "CALL ducklake_merge_adjacent_files('lake','t')")
    case.query("after_merge", "SELECT rowid,id,v FROM lake.t ORDER BY id", expected)
    case.query("history_after_merge", f"SELECT rowid,id,v FROM lake.t AT (VERSION => {updated})", [(0, 1, 10)])
    case.query("update_images", f"SELECT rowid,change_type,v FROM ducklake_table_changes('lake','main','t',{updated},{updated}) ORDER BY ALL",
               [(0, "update_postimage", 10), (0, "update_preimage", None)])
    case.command("checkpoint", "CHECKPOINT lake")
    case.query("after_checkpoint", "SELECT rowid,id,v FROM lake.t ORDER BY id", expected)


def statistics(case, deleted_inline):
    c = case.c
    c.execute("CREATE TABLE lake.t(id INTEGER)")
    c.execute("INSERT INTO lake.t SELECT * FROM range(50,70)")
    c.execute(f"INSERT INTO lake.t VALUES ({4 if deleted_inline else 5})")
    historical = c.execute("SELECT max(snapshot_id) FROM meta.ducklake_snapshot").fetchone()[0]
    c.execute("DELETE FROM lake.t WHERE id IN (4,50)" if deleted_inline else "DELETE FROM lake.t WHERE id=50")
    expected_rows = [(i,) for i in range(51, 70)]
    if not deleted_inline:
        expected_rows.insert(0, (5,))
    expected_aggregate = [(51, 69, 19)] if deleted_inline else [(5, 69, 20)]
    case.query("rows_before_rewrite", "SELECT id FROM lake.t ORDER BY id", expected_rows)
    case.command("rewrite", "CALL ducklake_rewrite_data_files('lake','t',delete_threshold => 0.0)")
    case.query("aggregate_after_rewrite", "SELECT min(id),max(id),count(*) FROM lake.t", expected_aggregate)
    case.command("flush", "CALL ducklake_flush_inlined_data('lake',table_name => 't')")
    case.query("rows_after_flush", "SELECT id FROM lake.t ORDER BY id", expected_rows)
    case.query("aggregate_after_flush", "SELECT min(id),max(id),count(*) FROM lake.t", expected_aggregate)
    historical_rows = [(4 if deleted_inline else 5,)] + [(i,) for i in range(50, 70)]
    case.query("history_after_flush", f"SELECT id FROM lake.t AT (VERSION => {historical}) ORDER BY id", historical_rows)


def blobs(case, invalid_utf8):
    c = case.c
    c.execute("CREATE TABLE lake.t(id INTEGER,b BLOB,s STRUCT(b BLOB),xs BLOB[])")
    values = [b"\\x41", b"plain", None]
    if invalid_utf8:
        values.append(b"\xff\xfe")
    for index, value in enumerate(values):
        c.execute("INSERT INTO lake.t VALUES (?, ?, {'b': ?}, [?])", [index, value, value, value])
    expected = [(index, value, value, value) for index, value in enumerate(values)]
    select = "SELECT id,b,s.b,xs[1] FROM lake.t ORDER BY id"
    case.query("baseline_bytes_and_nulls", select, expected)
    c.execute("SET binary_as_string=true")
    case.query("setting_must_not_reinterpret_bytes", select, expected)
    case.command("merge_with_setting", "CALL ducklake_merge_adjacent_files('lake','t')")
    case.command("rewrite_with_setting", "CALL ducklake_rewrite_data_files('lake','t',delete_threshold => 0.0)")
    case.command("checkpoint_with_setting", "CHECKPOINT lake")
    c.execute("SET binary_as_string=false")
    case.query("bytes_after_maintenance", select, expected)
    paths = [row[0] for row in c.execute("SELECT data_file FROM ducklake_list_files('lake','t')").fetchall()]
    case.query("physical_parquet_bytes", "SELECT id,b,s.b,xs[1] FROM read_parquet(?,binary_as_string=false) ORDER BY id", expected, [paths])


def run(root, version):
    results = {"python_package": duckdb.__version__, "cases": {}, "checks": {}}
    scenarios = {
        "sorted_merge": (sorted_merge, 0), "updated_merge": (updated_merge, 0),
        "deleted_inline_stats": (lambda case: statistics(case, True), 10),
        "live_inline_stats_control": (lambda case: statistics(case, False), 10),
        "blob_escape_and_nulls": (lambda case: blobs(case, False), 0),
        "blob_invalid_utf8": (lambda case: blobs(case, True), 0),
    }
    for name, (scenario, limit) in scenarios.items():
        with duckdb.connect() as c:
            c.execute("INSTALL ducklake; LOAD ducklake")
            uri = str(root / f"{name}.db").replace("'", "''")
            data = str(root / f"{name}-data").replace("'", "''")
            option = f", DUCKLAKE_VERSION '{version}'" if version else ""
            c.execute(f"ATTACH 'ducklake:{uri}' AS lake (DATA_PATH '{data}', METADATA_CATALOG 'meta', DATA_INLINING_ROW_LIMIT {limit}{option})")
            results["core"] = c.execute("PRAGMA version").fetchall()
            results["ducklake"] = c.execute("SELECT extension_version FROM duckdb_extensions() WHERE extension_name='ducklake'").fetchone()[0]
            case = Case(c)
            scenario(case)
            results["cases"][name] = {
                "catalog_version": c.execute("SELECT value FROM meta.ducklake_metadata WHERE key='version'").fetchone()[0],
                "checks": case.checks, "observations": case.observations,
            }
            for check, passed in case.checks.items():
                results["checks"][f"{name}.{check}"] = passed
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--version", choices=["1.0"])
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="catalog-lineage-stats-blob-", dir=args.temp_root) as directory:
        results = run(Path(directory), args.version)
    print(json.dumps(results, indent=2))
    if not all(results["checks"].values()):
        raise SystemExit("Native lineage/statistics/BLOB qualification failed; inspect false checks, not replacement goldens")


if __name__ == "__main__":
    main()
