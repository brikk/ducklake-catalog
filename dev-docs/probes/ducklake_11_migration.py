"""Cross-process migration/rollback rehearsals on copies, never user catalogs.

Use --old-python and --candidate-python from isolated explicitly pinned environments.
Workers run one native engine each, with bounded subprocess lifetimes. This is
not JVM qualification and no package, production pin or preview lock is changed.
"""

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


def observe(c):
    return {
        "version": c.execute("SELECT value FROM meta.ducklake_metadata WHERE key='version'").fetchone()[0],
        "file_rows": c.execute("SELECT count(*), sum(id) FROM lake.t WHERE id < 20").fetchall(),
        "inline_rows": c.execute("SELECT id, v, extra FROM lake.t WHERE id >= 100 ORDER BY id").fetchall(),
        "row_ids": c.execute("SELECT rowid FROM lake.t ORDER BY id").fetchall(),
        "old_snapshot": c.execute("SELECT count(*), sum(id) FROM lake.t AT (VERSION => 2)").fetchall(),
        "old_inline_snapshot": c.execute("SELECT id, v FROM lake.t AT (VERSION => 3) WHERE id=100").fetchall(),
        "collision_rows": c.execute("SELECT _ducklake_row_id FROM lake.collision").fetchall()
        if c.execute("SELECT table_id FROM meta.ducklake_table WHERE table_name='collision'").fetchall() else None,
        "inline_columns": [
            [row[0] for row in c.execute(f'DESCRIBE meta."{table}"').fetchall()]
            for (table,) in c.execute("SELECT table_name FROM meta.ducklake_inlined_data_tables ORDER BY table_id, schema_version").fetchall()
        ],
    }


def worker(mode, file):
    import duckdb

    result = {"python_package": duckdb.__version__, "mode": mode}
    try:
        with duckdb.connect() as c:
            c.execute("INSTALL ducklake; LOAD ducklake")
            result["core"] = c.execute("PRAGMA version").fetchall()
            result["ducklake"] = c.execute("SELECT extension_version FROM duckdb_extensions() WHERE extension_name='ducklake'").fetchone()[0]
            uri = str(file).replace("'", "''")
            data = str(file.parent / "data").replace("'", "''")
            option = ", AUTOMATIC_MIGRATION TRUE" if mode == "migrate" else ""
            c.execute(f"ATTACH 'ducklake:{uri}' AS lake (DATA_PATH '{data}', METADATA_CATALOG 'meta', DATA_INLINING_ROW_LIMIT 10{option})")
            if mode.startswith("seed"):
                c.execute("CREATE TABLE lake.t(id INTEGER, v VARCHAR)")
                c.execute("INSERT INTO lake.t SELECT range, 'file' FROM range(20)")
                c.execute("INSERT INTO lake.t VALUES (100,'old')")
                c.execute("ALTER TABLE lake.t ADD COLUMN extra INTEGER")
                c.execute("INSERT INTO lake.t VALUES (101,'new',7)")
                if mode == "seed-collision":
                    c.execute("CREATE TABLE lake.collision(_ducklake_row_id INTEGER)")
                    c.execute("INSERT INTO lake.collision VALUES (777)")
            result["before_write"] = observe(c)
            if mode == "checkpoint":
                c.execute("INSERT INTO lake.t VALUES (102,'after',8)")
                c.execute("CHECKPOINT meta")
                result["after_write"] = observe(c)
            result["ok"] = True
    except Exception as error:
        result["ok"] = False
        result["error"] = str(error)
    print(json.dumps(result))
    return 0 if result["ok"] else 1


def rehearse(args):
    script = str(Path(__file__).resolve())
    def invoke(python, mode, file):
        process = subprocess.run([str(python), script, "--worker", mode, "--file", str(file)],
                                 capture_output=True, text=True, timeout=90, check=False)
        result = json.loads(process.stdout)
        result["exit_code"] = process.returncode
        return result

    with tempfile.TemporaryDirectory(prefix="catalog-11-migration-", dir=args.temp_root) as directory:
        root = Path(directory)
        seed = root / "backup.db"
        baseline = invoke(args.old_python, "seed", seed)
        if not baseline["ok"]:
            raise RuntimeError(baseline)
        results = {"baseline": baseline, "checks": {}}
        original = baseline["before_write"]
        assert original["version"] == "1.0" and len(original["inline_columns"]) == 2
        for mode in ("inspect", "checkpoint", "migrate"):
            target = root / f"{mode}.db"
            shutil.copy2(seed, target)
            observation = invoke(args.candidate_python, mode, target)
            results[f"candidate_{mode}"] = observation
            if observation["ok"]:
                actual = observation["before_write"]
                results["checks"][f"{mode}_preserves_history"] = all(actual[key] == original[key] for key in
                                                                        ("file_rows", "inline_rows", "row_ids", "old_snapshot", "old_inline_snapshot"))
                results["checks"][f"{mode}_version"] = actual["version"] == ("1.1-dev1" if mode == "migrate" else "1.0")
                names = ["_ducklake_row_id", "_ducklake_begin_snapshot", "_ducklake_end_snapshot"] if mode == "migrate" else ["row_id", "begin_snapshot", "end_snapshot"]
                results["checks"][f"{mode}_inline_layout"] = all(columns[:3] == names for columns in actual["inline_columns"])
                if mode == "checkpoint":
                    written = observation["after_write"]
                    results["checks"]["checkpoint_write_visible"] = written["inline_rows"] == original["inline_rows"] + [[102, "after", 8]]
            else:
                results["checks"][f"{mode}_preserves_history"] = False
            if mode != "inspect":
                # Record attempted downgrade, not an assertion that it is safe.
                results[f"old_reopen_{mode}"] = invoke(args.old_python, "inspect", target)
        restored = invoke(args.old_python, "inspect", seed)
        results["old_reopen_backup"] = restored
        results["checks"]["untouched_backup_restores"] = restored["ok"] and restored["before_write"] == original

        collision = root / "collision.db"
        collision_seed = invoke(args.old_python, "seed-collision", collision)
        results["collision_seed"] = collision_seed
        collision_before = invoke(args.candidate_python, "inspect", collision)
        results["collision_before"] = collision_before
        attempt = invoke(args.candidate_python, "migrate", collision)
        results["collision_migrate"] = attempt
        after = invoke(args.candidate_python, "inspect", collision)
        results["collision_after"] = after
        results["old_reopen_collision"] = invoke(args.old_python, "inspect", collision)
        # The sampled migration must reject the collision without metadata/data drift.
        # A future collision-resolution policy requires deliberate requalification.
        rejected = not attempt["ok"] and after["ok"] and collision_before["ok"] and after["before_write"] == collision_before["before_write"]
        results["checks"]["collision_rejected_atomically"] = rejected
        return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-python", type=Path)
    parser.add_argument("--candidate-python", type=Path)
    parser.add_argument("--temp-root", type=Path)
    parser.add_argument("--worker", choices=["seed", "seed-collision", "inspect", "checkpoint", "migrate"])
    parser.add_argument("--file", type=Path)
    args = parser.parse_args()
    if args.worker:
        if not args.file:
            parser.error("--worker requires --file")
        raise SystemExit(worker(args.worker, args.file))
    if not (args.old_python and args.candidate_python and args.temp_root):
        parser.error("Require --old-python, --candidate-python and --temp-root")
    results = rehearse(args)
    print(json.dumps(results, indent=2))
    if not all(results["checks"].values()):
        raise SystemExit("Native migration acceptance failed; inspect checks without changing expectations")


if __name__ == "__main__":
    main()
