"""UF-03 / #1756: strict native Quack snapshot ceiling, with isolated matched peers."""

import argparse
import json
import multiprocessing
import socket
import tempfile
import uuid
from pathlib import Path

import duckdb

from duckdb2_quack import provenance, serve
from ducklake_lineage_stats_blob import Case


def rejected(case, name, sql, snapshot):
    try:
        rows = case.c.execute(sql).fetchall()
        case.checks[name] = False
        case.observations[name] = {"unexpected_rows": rows, "expected": f"No snapshot found at version {snapshot}"}
    except duckdb.Error as error:
        case.checks[name] = f"No snapshot found at version {snapshot}" in str(error)
        case.observations[name] = {"error": str(error)}


def run(root, version):
    context = multiprocessing.get_context("spawn")
    receive, send = context.Pipe(duplex=False)
    stop = context.Event()
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    token = uuid.uuid4().hex
    server = context.Process(target=serve, args=(send, stop, port, token))
    server.start()
    results = {"python_package": duckdb.__version__}
    try:
        if not receive.poll(30):
            raise RuntimeError("Quack startup timed out")
        results["server"] = receive.recv()
        if "error" in results["server"]:
            raise RuntimeError(results["server"]["error"])
        with duckdb.connect() as reader, duckdb.connect() as writer:
            for c in (reader, writer):
                c.execute("INSTALL quack; LOAD quack; INSTALL ducklake; LOAD ducklake")
                c.execute(f"CREATE SECRET (TYPE quack,TOKEN '{token}')")
                data = str(root / "data").replace("'", "''")
                option = f", DUCKLAKE_VERSION '{version}'" if version else ""
                c.execute(f"ATTACH 'ducklake:quack:127.0.0.1:{port}' AS lake "
                          f"(DATA_PATH '{data}',METADATA_CATALOG 'meta',DATA_INLINING_ROW_LIMIT 0{option})")
            results["client"] = provenance(reader)
            case = Case(reader)
            case.checks["matched_peers"] = results["client"] == results["server"]
            results["ducklake"] = reader.execute("SELECT extension_version FROM duckdb_extensions() WHERE extension_name='ducklake'").fetchone()[0]
            results["catalog_version"] = reader.execute("SELECT value FROM meta.ducklake_metadata WHERE key='version'").fetchone()[0]
            writer.execute("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES (1),(2)")
            reader.execute("BEGIN")
            case.query("pin", "SELECT count(*) FROM lake.t", [(2,)])
            before = reader.execute("SELECT max(snapshot_id) FROM lake.snapshots()").fetchone()[0]
            writer.execute("INSERT INTO lake.t VALUES (3)")
            after = writer.execute("SELECT max(snapshot_id) FROM lake.snapshots()").fetchone()[0]
            timestamp = writer.execute("SELECT current_timestamp::VARCHAR").fetchone()[0].replace("'", "''")
            case.query("live_pinned", "SELECT count(*) FROM lake.t", [(2,)])
            case.query("list_ceiling", "SELECT max(snapshot_id) FROM lake.snapshots()", [(before,)])
            case.query("timestamp_ceiling", f"SELECT count(*) FROM lake.t AT (TIMESTAMP=>TIMESTAMPTZ '{timestamp}')", [(2,)])
            case.query("timestamp_cdc_ceiling", "SELECT count(*) FROM ducklake_table_changes('lake','main','t',"
                       f"TIMESTAMPTZ '2000-01-01',TIMESTAMPTZ '{timestamp}')", [(2,)])
            rejected(case, "future_version_rejected", f"SELECT count(*) FROM lake.t AT (VERSION=>{after})", after)
            reader.execute("ROLLBACK; BEGIN")
            case.query("repin", "SELECT count(*) FROM lake.t", [(3,)])
            writer.execute("INSERT INTO lake.t VALUES (4)")
            newest = writer.execute("SELECT max(snapshot_id) FROM lake.snapshots()").fetchone()[0]
            rejected(case, "future_cdc_rejected", f"SELECT count(*) FROM ducklake_table_changes('lake','main','t',0,{newest})", newest)
            reader.execute("ROLLBACK")
            case.query("unpinned_control", "SELECT i FROM lake.t ORDER BY i", [(1,), (2,), (3,), (4,)])
            case.query("unpinned_cdc_control", f"SELECT count(*) FROM ducklake_table_changes('lake','main','t',0,{newest})", [(4,)])
            results.update(checks=case.checks, observations=case.observations)
    finally:
        stop.set()
        server.join(5)
        if server.is_alive():
            server.terminate()
            server.join(5)
        if server.is_alive():
            server.kill()
            server.join(5)
        receive.close()
        send.close()
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", required=True, type=Path)
    parser.add_argument("--version", choices=["1.0"])
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="catalog-uf03-", dir=args.temp_root) as directory:
        result = run(Path(directory), args.version)
    print(json.dumps(result, indent=2))
    if not result["checks"] or not all(result["checks"].values()):
        raise SystemExit("UF-03 native Quack qualification failed; newer commits leaked through a pinned transaction")
