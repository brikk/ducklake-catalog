"""UF-02 / #1723: user rowid/snapshot_id names must not change CDC identity or cardinality."""

import argparse
import json
import tempfile
from pathlib import Path

from ducklake_cdc_transient import head, run


def collisions(case, inline):
    c = case.c
    if inline:
        c.execute("CALL lake.set_option('data_inlining_row_limit',10)")
    c.execute("CREATE TABLE lake.t(rowid_1 INTEGER,rowid BIGINT,snapshot_id_1 INTEGER,snapshot_id BIGINT,v INTEGER)")
    c.execute("INSERT INTO lake.t VALUES (7,0,70,0,1),(8,0,80,0,2)")
    inserted = head(c)
    # Identical user identity values make name-based pairing multiply the result.
    c.execute("UPDATE lake.t SET v=v+10")
    updated = head(c)
    expected = [(updated, 0, "update_postimage", 7, 0, 70, 0, 11),
                (updated, 0, "update_preimage", 7, 0, 70, 0, 1),
                (updated, 1, "update_postimage", 8, 0, 80, 0, 12),
                (updated, 1, "update_preimage", 8, 0, 80, 0, 2)]
    sql = f"SELECT * FROM ducklake_table_changes('lake','main','t',{updated},{updated}) ORDER BY ALL"
    case.query("update_images_by_position", sql, expected)
    case.query("internal_rowid_predicate", sql.replace(" ORDER BY ALL", " WHERE rowid=1 ORDER BY ALL"), expected[2:])
    for function, values in [("insertions", [(updated, 0, 7, 0, 70, 0, 11), (updated, 1, 8, 0, 80, 0, 12)]),
                             ("deletions", [(updated, 0, 7, 0, 70, 0, 1), (updated, 1, 8, 0, 80, 0, 2)])]:
        # In 2.0 snapshot_id/rowid are virtual on these functions: * is payload-only.
        # Name the system identity explicitly; retain the independent expected rows.
        query = f"SELECT snapshot_id,rowid,* FROM ducklake_table_{function}('lake','main','t',{updated},{updated}) ORDER BY ALL"
        case.query(function, query, values)
        case.observations[f"{function}_columns"] = [d[0] for d in c.execute(query).description]
    case.query("history_control", f"SELECT rowid_1,rowid,snapshot_id_1,snapshot_id,v FROM lake.t AT (VERSION=>{inserted}) ORDER BY v",
               [(7, 0, 70, 0, 1), (8, 0, 80, 0, 2)])
    case.query("live_control", "SELECT rowid_1,rowid,snapshot_id_1,snapshot_id,v FROM lake.t ORDER BY v",
               [(7, 0, 70, 0, 11), (8, 0, 80, 0, 12)])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", required=True, type=Path)
    parser.add_argument("--version", choices=["1.0"])
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="catalog-uf02-", dir=args.temp_root) as directory:
        result = run(Path(directory), args.version, {"file_names": lambda x: collisions(x, False),
                                                   "inline_names": lambda x: collisions(x, True)})
    print(json.dumps(result, indent=2))
    if not result["checks"] or not all(result["checks"].values()):
        raise SystemExit("UF-02 native qualification failed; user names must not replace CDC identity")
