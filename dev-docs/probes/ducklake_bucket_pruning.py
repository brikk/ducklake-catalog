"""UF-04 / #1696: strict safe-pruning gate; never rewrite buckets to hide mismatched hashes."""

import argparse
import json
import tempfile
from pathlib import Path

from ducklake_cdc_transient import run


def scalar(case, kind):
    c = case.c
    c.execute(f"CREATE TABLE lake.t(d {kind},k INTEGER)")
    c.execute("ALTER TABLE lake.t SET PARTITIONED BY (bucket(8,d))")
    for values in [f"('nan'::{kind},1)", f"(-('nan'::{kind}),2)", "(1.5,3)", "(NULL,4)"]:
        c.execute(f"INSERT INTO lake.t VALUES {values}")
    c.execute("CREATE TEMP TABLE unpartitioned AS SELECT * FROM lake.t")
    before = c.execute("SELECT data_file_id,partition_value FROM meta.ducklake_file_partition_value ORDER BY ALL").fetchall()
    case.query("live_control", "SELECT k FROM lake.t ORDER BY k", [(1,), (2,), (3,), (4,)])
    for name, predicate, expected in [
        ("positive_nan", f"d='nan'::{kind}", [(1,), (2,)]),
        ("negative_nan", f"d=-('nan'::{kind})", [(1,), (2,)]),
        ("mixed_in", f"d IN ('nan'::{kind},1.5,2.5)", [(1,), (2,), (3,)]),
        ("mixed_in_and", f"d IN (1.5,'nan'::{kind}) AND d<>7.5", [(1,), (2,), (3,)]),
        ("normal_equality_control", "d=1.5", [(3,)]),
        ("null_control", "d IS NULL", [(4,)]),
        ("null_in_control", "d IN (1.5,NULL)", [(3,)]),
    ]:
        case.query(name, f"SELECT k FROM lake.t WHERE {predicate} ORDER BY k", expected)
        case.query(f"{name}_unpartitioned", f"SELECT k FROM unpartitioned WHERE {predicate} ORDER BY k", expected)
    case.query("buckets_unchanged", "SELECT data_file_id,partition_value FROM meta.ducklake_file_partition_value ORDER BY ALL", before)


def nested(case, kind, negative_zero, positive_zero, negative_nan, positive_nan):
    c = case.c
    c.execute(f"CREATE TABLE lake.t(d {kind},k INTEGER)")
    c.execute("ALTER TABLE lake.t SET PARTITIONED BY (bucket(8,d))")
    c.execute(f"INSERT INTO lake.t SELECT {negative_zero},1")
    c.execute(f"INSERT INTO lake.t SELECT {negative_nan},2")
    c.execute("INSERT INTO lake.t VALUES (NULL,3)")
    c.execute("CREATE TEMP TABLE unpartitioned AS SELECT * FROM lake.t")
    before = c.execute("SELECT data_file_id,partition_value FROM meta.ducklake_file_partition_value ORDER BY ALL").fetchall()
    case.query("live_control", "SELECT k FROM lake.t ORDER BY k", [(1,), (2,), (3,)])
    case.query("nested_zero", f"SELECT k FROM lake.t WHERE d={positive_zero} ORDER BY k", [(1,)])
    case.query("nested_nan", f"SELECT k FROM lake.t WHERE d={positive_nan} ORDER BY k", [(2,)])
    case.query("nested_mixed_in", f"SELECT k FROM lake.t WHERE d IN ({positive_zero},{positive_nan}) ORDER BY k", [(1,), (2,)])
    case.query("zero_unpartitioned", f"SELECT k FROM unpartitioned WHERE d={positive_zero}", [(1,)])
    case.query("nan_unpartitioned", f"SELECT k FROM unpartitioned WHERE d={positive_nan}", [(2,)])
    case.query("mixed_in_unpartitioned", f"SELECT k FROM unpartitioned WHERE d IN ({positive_zero},{positive_nan}) ORDER BY k", [(1,), (2,)])
    case.query("null_control", "SELECT k FROM lake.t WHERE d IS NULL", [(3,)])
    case.query("buckets_unchanged", "SELECT data_file_id,partition_value FROM meta.ducklake_file_partition_value ORDER BY ALL", before)


def normal(case):
    c = case.c
    c.execute("CREATE TABLE lake.t(d DOUBLE,k INTEGER)")
    c.execute("ALTER TABLE lake.t SET PARTITIONED BY (bucket(16,d))")
    c.execute("INSERT INTO lake.t SELECT i-32,i FROM range(64) r(i)")
    for name, expression in [("zero", "0"), ("negative_zero", "-(0.0::DOUBLE)"), ("in_null", "0,NULL")]:
        predicate = f"d IN ({expression})" if name == "in_null" else f"d={expression}"
        query = f"SELECT k FROM lake.t WHERE {predicate}"
        case.query(name, query, [(32,)])
        plan = c.execute(f"EXPLAIN ANALYZE {query}").fetchone()[1]
        # Pruning is still useful for normal constants. The correctness cases above must
        # fall back safely rather than changing the stored murmur3 buckets.
        import re
        case.checks[f"{name}_pruned_control"] = bool(re.search(r"Total Files Read:\s*1(?:\s|[│|])", plan))
        case.observations[f"{name}_pruned_control"] = {"plan": plan}


SCENARIOS = {
    "double_nan": lambda x: scalar(x, "DOUBLE"), "float_nan": lambda x: scalar(x, "FLOAT"),
    "list": lambda x: nested(x, "DOUBLE[]", "[-(0.0::DOUBLE)]", "[0.0::DOUBLE]", "[-('nan'::DOUBLE)]", "['nan'::DOUBLE]"),
    "struct": lambda x: nested(x, "STRUCT(a FLOAT)", "{'a':-(0.0::FLOAT)}", "{'a':0.0::FLOAT}",
                                "{'a':-('nan'::FLOAT)}", "{'a':'nan'::FLOAT}"),
    "map": lambda x: nested(x, "MAP(INTEGER,DOUBLE)", "MAP {1:-(0.0::DOUBLE)}", "MAP {1:0.0::DOUBLE}",
                             "MAP {1:-('nan'::DOUBLE)}", "MAP {1:'nan'::DOUBLE}"),
    "normal_zero": normal,
}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", required=True, type=Path)
    parser.add_argument("--version", choices=["1.0"])
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="catalog-uf04-", dir=args.temp_root) as directory:
        result = run(Path(directory), args.version, SCENARIOS)
    print(json.dumps(result, indent=2))
    if not result["checks"] or not all(result["checks"].values()):
        raise SystemExit("UF-04 native qualification failed; disable unsafe pruning, do not rehash partitions")
