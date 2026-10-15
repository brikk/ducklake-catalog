"""UF-01 / #1675: strict CDC gate for versions that never existed at a committed snapshot."""

import argparse
import json
import tempfile
from pathlib import Path

import duckdb

from ducklake_lineage_stats_blob import Case


def head(c):
    return c.execute("SELECT max(snapshot_id) FROM meta.ducklake_snapshot").fetchone()[0]


def changes(case, name, start, end, expected):
    case.query(name, f"SELECT snapshot_id,rowid,change_type,id,v FROM "
               f"ducklake_table_changes('lake','main','t',{start},{end}) ORDER BY ALL", expected)


def update_delete(case, inline_deletes):
    c = case.c
    c.execute("CREATE TABLE lake.t(id INTEGER,v INTEGER)")
    c.execute("INSERT INTO lake.t SELECT i,0 FROM range(3) r(i)")
    if inline_deletes:
        c.execute("CALL lake.set_option('data_inlining_row_limit',2)")
    c.execute("BEGIN; UPDATE lake.t SET v=1; DELETE FROM lake.t WHERE id=0; COMMIT")
    updated = head(c)
    first = [(updated, 0, "delete", 0, 0),
             (updated, 1, "update_postimage", 1, 1), (updated, 1, "update_preimage", 1, 0),
             (updated, 2, "update_postimage", 2, 1), (updated, 2, "update_preimage", 2, 0)]
    changes(case, "update_then_delete", updated, updated, first)
    c.execute("DELETE FROM lake.t WHERE id=1")
    deleted = head(c)
    later = [(deleted, 1, "delete", 1, 1)]
    changes(case, "earlier_window_after_later_delete", updated, updated, first)
    changes(case, "later_window", deleted, deleted, later)
    changes(case, "combined_window", updated, deleted, first + later)
    case.query("insertions", f"SELECT snapshot_id,rowid,id,v FROM "
               f"ducklake_table_insertions('lake','main','t',{updated},{deleted}) ORDER BY ALL",
               [(updated, 1, 1, 1), (updated, 2, 2, 1)])
    case.query("deletions", f"SELECT snapshot_id,rowid,id,v FROM "
               f"ducklake_table_deletions('lake','main','t',{updated},{deleted}) ORDER BY ALL",
               [(updated, 0, 0, 0), (updated, 1, 1, 0), (updated, 2, 2, 0), (deleted, 1, 1, 1)])
    case.query("live_control", "SELECT id,v FROM lake.t ORDER BY id", [(2, 1)])
    if inline_deletes:
        case.command("flush", "CALL ducklake_flush_inlined_data('lake',table_name=>'t')")
        changes(case, "combined_after_flush", updated, deleted, first + later)
        case.query("live_after_flush", "SELECT id,v FROM lake.t ORDER BY id", [(2, 1)])


def insert_delete(case):
    c = case.c
    c.execute("CREATE TABLE lake.t(id INTEGER,v INTEGER)")
    c.execute("BEGIN; INSERT INTO lake.t SELECT i,0 FROM range(3) r(i); DELETE FROM lake.t WHERE id=0; COMMIT")
    inserted = head(c)
    expected = [(inserted, 1, "insert", 1, 0), (inserted, 2, "insert", 2, 0)]
    changes(case, "insert_then_delete", inserted, inserted, expected)
    case.query("no_transient_deletions", f"SELECT rowid,id,v FROM "
               f"ducklake_table_deletions('lake','main','t',{inserted},{inserted}) ORDER BY ALL", [])
    case.query("live_control", "SELECT id,v FROM lake.t ORDER BY id", [(1, 0), (2, 0)])


def run(root, version):
    results = {"python_package": duckdb.__version__, "cases": {}, "checks": {}}
    for name, scenario in {"update_delete_files": lambda x: update_delete(x, False),
                           "update_delete_inline_tags": lambda x: update_delete(x, True),
                           "insert_delete": insert_delete}.items():
        with duckdb.connect() as c:
            case = Case(c)
            try:
                c.execute("INSTALL ducklake; LOAD ducklake")
                uri = str(root / f"{name}.db").replace("'", "''")
                data = str(root / f"{name}-data").replace("'", "''")
                option = f", DUCKLAKE_VERSION '{version}'" if version else ""
                c.execute(f"ATTACH 'ducklake:{uri}' AS lake (DATA_PATH '{data}', METADATA_CATALOG 'meta', "
                          f"DATA_INLINING_ROW_LIMIT 0{option})")
                results["core"] = c.execute("PRAGMA version").fetchall()
                results["ducklake"] = c.execute("SELECT extension_version FROM duckdb_extensions() WHERE extension_name='ducklake'").fetchone()[0]
                case.observations["catalog_version"] = c.execute("SELECT value FROM meta.ducklake_metadata WHERE key='version'").fetchone()[0]
                scenario(case)
            except duckdb.Error as error:
                case.checks["setup_or_scenario"] = False
                case.observations["setup_or_scenario"] = {"error": str(error)}
            results["cases"][name] = {"checks": case.checks, "observations": case.observations}
            results["checks"].update({f"{name}.{key}": value for key, value in case.checks.items()})
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", required=True, type=Path)
    parser.add_argument("--version", choices=["1.0"])
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="catalog-uf01-", dir=args.temp_root) as directory:
        result = run(Path(directory), args.version)
    print(json.dumps(result, indent=2))
    if not result["checks"] or not all(result["checks"].values()):
        raise SystemExit("UF-01 native qualification failed; do not replace independent expected CDC rows")
