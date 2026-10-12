"""Capture disposable binary layouts or exact-ref source-only 1.1 statements.

Output is JSON on stdout. Source capture never claims its code was executed.
No package/extension installation version or production pin is selected here.
"""

import argparse
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path


TABLES = ("ducklake_schema", "ducklake_data_file", "ducklake_delete_file",
          "ducklake_file_column_stats", "ducklake_table_column_stats")


def source_fixture(checkout, ref, label):
    sha = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", f"{ref}^{{commit}}"], text=True).strip()
    if sha != ref:
        raise ValueError("Source ref must be a full immutable commit SHA")
    path = "src/metadata_manager/ducklake_metadata_manager_v1_1.cpp"
    text = subprocess.check_output(["git", "-C", str(checkout), "show", f"{sha}:{path}"], text=True)
    statements = []
    for block in re.findall(r'return\s+((?:"[^"\n]*"\s*)+);', text):
        sql = "".join(re.findall(r'"([^"\n]*)"', block))
        if any(f".{table}(" in sql for table in TABLES):
            statements.append(sql)
    if len(statements) != len(TABLES):
        raise ValueError("Expected exactly five candidate schema statements; inspect upstream changes")
    if not all(sql.endswith(");") for sql in statements):
        raise ValueError("Incomplete CREATE TABLE statement")
    return {"label": label, "kind": "source-only", "executed": False, "ducklake_sha": sha,
            "source_path": path, "source_sha256": hashlib.sha256(text.encode()).hexdigest(), "statements": statements}


def binary_fixture(root, version, label):
    import duckdb

    with duckdb.connect() as c:
        c.execute("INSTALL ducklake; LOAD ducklake")
        result = {"label": label, "kind": "binary", "python_package": duckdb.__version__,
                  "core": c.execute("PRAGMA version").fetchone(),
                  "extensions": c.execute("SELECT extension_name, extension_version, installed_from FROM duckdb_extensions() "
                                          "WHERE extension_name='ducklake'").fetchall()}
        uri = str(root / "lake.db").replace("'", "''")
        data = str(root / "data").replace("'", "''")
        option = f", DUCKLAKE_VERSION '{version}'" if version else ""
        c.execute(f"ATTACH 'ducklake:{uri}' AS lake (DATA_PATH '{data}', METADATA_CATALOG 'meta', DATA_INLINING_ROW_LIMIT 10{option})")
        result["catalog_version"] = c.execute("SELECT value FROM meta.ducklake_metadata WHERE key='version'").fetchone()[0]
        result["schema"] = {table: [f"{row[0]} {row[1]}" for row in c.execute(f"DESCRIBE meta.{table}").fetchall()] for table in TABLES}
        c.execute("CREATE TABLE lake.inl(i INTEGER, xs INTEGER[], kv MAP(VARCHAR, INTEGER))")
        c.execute("INSERT INTO lake.inl VALUES (1, [1,NULL], map(['x'],[7]))")
        table = c.execute("SELECT table_name FROM meta.ducklake_inlined_data_tables").fetchone()[0]
        result["inline_columns"] = [f"{row[0]} {row[1]}" for row in c.execute(f'DESCRIBE meta."{table}"').fetchall()]
        result["nested_values"] = c.execute("SELECT i, xs, kv FROM lake.inl").fetchall()
        c.execute("CREATE SCHEMA lake.parent")
        try:
            c.execute("CREATE SCHEMA lake.parent.child")
            result["nested_schema"] = "accepted"
        except duckdb.Error as error:
            result["nested_schema"] = str(error).splitlines()[0]
        result["schema_names"] = c.execute("SELECT schema_name FROM meta.ducklake_schema ORDER BY schema_name").fetchall()
        if "parent_schema_id BIGINT" in result["schema"]["ducklake_schema"]:
            result["schema_parentage"] = c.execute("SELECT schema_name, parent_schema_id IS NOT NULL FROM meta.ducklake_schema ORDER BY schema_id").fetchall()

        c.execute("CREATE TABLE lake.files(i INTEGER)")
        c.execute("INSERT INTO lake.files SELECT * FROM range(20)")
        c.execute("DELETE FROM lake.files WHERE i=1")
        c.execute("DELETE FROM lake.files WHERE i=2")
        c.execute("CALL ducklake_flush_inlined_data('lake', table_name => 'files')")
        path = c.execute("SELECT path FROM meta.ducklake_delete_file").fetchone()[0]
        # Resolve from native list_files, rather than guessing catalog path conventions.
        full_path = c.execute("SELECT delete_file FROM ducklake_list_files('lake', 'files')").fetchone()[0]
        result["delete_format"] = c.execute("SELECT format FROM meta.ducklake_delete_file").fetchone()[0]
        result["delete_columns"] = [f"{row[0]} {row[1]}" for row in c.execute("DESCRIBE SELECT * FROM read_parquet(?)", [full_path]).fetchall()]
        result["delete_positions"] = c.execute("SELECT pos FROM read_parquet(?) ORDER BY pos", [full_path]).fetchall()
        assert path and result["delete_positions"] == [(1,), (2,)]
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--temp-root", type=Path)
    parser.add_argument("--version", choices=["1.0"])
    parser.add_argument("--source-checkout", type=Path)
    parser.add_argument("--source-ref")
    args = parser.parse_args()
    if args.source_checkout:
        result = source_fixture(args.source_checkout, args.source_ref, args.label)
    else:
        if not args.temp_root:
            parser.error("Binary capture requires --temp-root")
        with tempfile.TemporaryDirectory(prefix="catalog-11-fixture-", dir=args.temp_root) as directory:
            result = binary_fixture(Path(directory), args.version, args.label)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
