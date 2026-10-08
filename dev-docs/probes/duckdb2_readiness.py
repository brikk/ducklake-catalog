"""Read-only-to-the-project compatibility canaries; all lakes are disposable.

Run with an isolated Python environment containing a pinned DuckDB preview:
  <venv>/bin/python dev-docs/probes/duckdb2_readiness.py --temp-root /tmp/opencode
The output identifies core and extension builds; it is not JVM/JDBC qualification.
"""

import argparse
import json
import tempfile
from pathlib import Path

import duckdb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", required=True, type=Path)
    args = parser.parse_args()
    results = {"python_package": duckdb.__version__, "observations": {}}
    with tempfile.TemporaryDirectory(prefix="catalog-duckdb2-", dir=args.temp_root) as directory:
        root = Path(directory)
        connection = duckdb.connect()
        try:
            results["core"] = connection.execute("PRAGMA version").fetchall()
            for extension in ("ducklake", "quack"):
                connection.execute(f"INSTALL {extension}; LOAD {extension}")
            results["extensions"] = connection.execute(
                "SELECT extension_name, extension_version, installed_from "
                "FROM duckdb_extensions() WHERE loaded ORDER BY extension_name"
            ).fetchall()

            def observe(name, sql):
                try:
                    rows = connection.execute(sql).fetchall()
                    results["observations"][name] = {"rows": rows}
                except duckdb.Error as error:
                    results["observations"][name] = {"error": str(error).splitlines()[0]}

            observe("old_lambda", "SELECT list_transform([1, 2], x -> x + 1)")
            observe("new_lambda", "SELECT list_transform([1, 2], lambda x: x + 1)")
            observe("geometry_mirror_sql", "SELECT CAST(list_transform(v, geo_0 -> ST_GeomFromWKB(geo_0)) "
                    "AS VARCHAR) FROM (SELECT [ST_AsWKB('POINT(1 2)'::GEOMETRY)] AS v)")
            observe("geometry_mirror_sql_new_lambda", "SELECT CAST(list_transform(v, lambda geo_0: ST_GeomFromWKB(geo_0)) "
                    "AS VARCHAR) FROM (SELECT [ST_AsWKB('POINT(1 2)'::GEOMETRY)] AS v)")
            observe("null_type", "DESCRIBE SELECT NULL n")
            observe("timestamp_ns_type", "DESCRIBE SELECT CAST(NULL AS TIMESTAMPTZ_NS) n")
            observe("timestamp_ns_text", "SELECT CAST(TIMESTAMPTZ_NS '2020-01-01 00:00:00.123456789+00' AS VARCHAR)")
            observe("settings", "SELECT name,value FROM duckdb_settings() WHERE name IN "
                    "('default_transaction_invalidation_policy','legacy_disable_null_type',"
                    "'preserve_identifier_case','null_on_division_by_zero','integer_division_throws') ORDER BY name")
            connection.execute("BEGIN")
            observe("transaction_bind_error", "SELECT * FROM does_not_exist")
            observe("transaction_after_bind_error", "SELECT 1")
            connection.execute("ROLLBACK")
            observe("transaction_after_rollback", "SELECT 1")

            def attach(alias, version_option=""):
                # Paths come from TemporaryDirectory, not from user-authored SQL fragments.
                metadata = str(root / f"{alias}.db").replace("'", "''")
                data = str(root / f"{alias}-files").replace("'", "''")
                connection.execute(f"ATTACH 'ducklake:{metadata}' AS {alias} "
                                   f"(DATA_PATH '{data}', METADATA_CATALOG '{alias}_meta'{version_option})")

            def catalog_shape(alias, label=None):
                label = label or alias
                observe(f"{label}_version", f"SELECT value FROM {alias}_meta.ducklake_metadata WHERE key='version'")
                tables = connection.execute(f"SELECT table_name FROM {alias}_meta.ducklake_inlined_data_tables").fetchall()
                for index, (table,) in enumerate(tables):
                    quoted = table.replace('"', '""')
                    observe(f"{label}_inline_columns_{index}", f'DESCRIBE {alias}_meta."{quoted}"')

            attach("fresh")
            connection.execute("CREATE TABLE fresh.t(i INTEGER)")
            connection.execute("INSERT INTO fresh.t VALUES (42)")
            catalog_shape("fresh")
            # Inspect the actual registered table instead of depending on a generated ID.
            inline_table = connection.execute("SELECT table_name FROM fresh_meta.ducklake_inlined_data_tables").fetchone()[0]
            quoted_inline = inline_table.replace('"', '""')
            observe("old_inline_names_registered_table", f'SELECT row_id,begin_snapshot,end_snapshot '
                    f'FROM fresh_meta."{quoted_inline}"')
            observe("fresh_file_columns", "DESCRIBE fresh_meta.ducklake_data_file")
            observe("fresh_stats_columns", "DESCRIBE fresh_meta.ducklake_file_column_stats")
            observe("fresh_schema_columns", "DESCRIBE fresh_meta.ducklake_schema")
            observe("lake_null_type", "CREATE TABLE fresh.nulls AS SELECT NULL n")
            observe("lake_null_catalog_type", "SELECT column_type FROM fresh_meta.ducklake_column WHERE column_name='n'")
            observe("nested_schema_parent", "CREATE SCHEMA fresh.s1")
            observe("nested_schema_child", "CREATE SCHEMA fresh.s1.child")
            observe("nested_schema_rows", "SELECT schema_id,schema_name,path FROM fresh_meta.ducklake_schema ORDER BY schema_id")

            attach("pinned", ", DUCKLAKE_VERSION '1.0'")
            connection.execute("CREATE TABLE pinned.t(i INTEGER)")
            connection.execute("INSERT INTO pinned.t VALUES (7)")
            catalog_shape("pinned")
            connection.execute("DETACH pinned")
            attach("pinned")
            observe("existing_default_version", "SELECT value FROM pinned_meta.ducklake_metadata WHERE key='version'")
            connection.execute("DETACH pinned")
            attach("pinned", ", AUTOMATIC_MIGRATION TRUE")
            catalog_shape("pinned", "migrated")
            observe("migrated_native_rows", "SELECT * FROM pinned.t")
        finally:
            connection.close()

    # These pin the sampled alpha, not every future preview or the unreleased JVM driver.
    observed = results["observations"]
    checks = {
        "old_lambda_rejected": "lambda arrow" in observed["old_lambda"].get("error", ""),
        "new_lambda_works": observed["new_lambda"].get("rows") == [([2, 3],)],
        "our_geometry_sql_rejected": "lambda arrow" in observed["geometry_mirror_sql"].get("error", ""),
        "replacement_geometry_sql_works": observed["geometry_mirror_sql_new_lambda"].get("rows") == [("['POINT (1 2)']",)],
        "bind_error_aborts_transaction": "transaction is aborted" in observed["transaction_after_bind_error"].get("error", ""),
        "rollback_recovers": observed["transaction_after_rollback"].get("rows") == [(1,)],
        "new_catalog_is_dev": observed["fresh_version"].get("rows") == [("1.1-dev1",)],
        "old_inline_projection_fails": "row_id" in observed["old_inline_names_registered_table"].get("error", ""),
        "existing_catalog_stays_v1": observed["existing_default_version"].get("rows") == [("1.0",)],
        "explicit_migration_changes_version": observed["migrated_version"].get("rows") == [("1.1-dev1",)],
        "explicit_migration_preserves_rows": observed["migrated_native_rows"].get("rows") == [(7,)],
        "null_type_is_persisted": observed["lake_null_catalog_type"].get("rows") == [("null",)],
    }
    results["checks"] = checks
    print(json.dumps(results, indent=2, default=str))
    if not all(checks.values()):
        raise SystemExit("Sampled behavior changed; inspect false checks before updating the report")


if __name__ == "__main__":
    main()
