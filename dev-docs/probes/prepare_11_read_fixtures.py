"""Fetch exact newer-upstream R02/R04 regressions without moving corpus/extension pins."""
import hashlib
from pathlib import Path
from urllib.request import urlopen

SOURCE = "ea543c719a4e88a8154f9db897aa7c580da3308e"
ROOT = Path(__file__).resolve().parents[2] / "ducklake-catalog/build/read-fixtures"
FILES = {
    "test/sql/alter/add_struct_field_non_literal_default.test": "53e969c884d09270376b2d7553978751a7b81882337b6efbdcdd0ac719c8286a",
    "test/sql/alter/add_struct_alter_same_transaction.test": "94bcc8bbc2b810588a081d94c117acd8f449f93dc46caa9f0b0e815fbe85e43d",
    "test/sql/migration/v10_inlined_struct_field_order.test": "a015a0482246894223944f601b29b5663087c2cf40631ed2a9e4751095a3c257",
    "data/old_ducklake/inlined_struct_field_order.db.gz": "837dd5fc5f949b13744c3210a0a7d5b43e3ba6d9168514ea55ab7b4218e95d7d",
    "test/sql/migration/v10_retyped_bounds.test": "3694ae485e41fe81c4efd63028c2cbdf2bd83351d12ea569c61bb66b36b8409b",
    "data/old_ducklake/migrate_bounds.db.gz": "f9ad3a91468c3e3c96b1d3587214885b20fedd86c8a89b84efab2a7487188528",
    "data/old_ducklake/migrate_bounds/double.parquet": "aafe0f9ca47791572eb18383eb64c0ebf4d49b6ee6a30d27a905ef098f524492",
    "data/old_ducklake/migrate_bounds/float.parquet": "63b92f770a523fa1d98cd38b62b7e759f7239bda322bf5dca49d638f6f65f20d",
    "data/old_ducklake/migrate_bounds/ts_ns.parquet": "d7bb6eb26ae2cad0deddefc721863ab27b9cc31892c1ef0695ec565c738069df",
    "data/old_ducklake/migrate_bounds/tz_ns.parquet": "50dfd3c981de43edfe86da6dfe2c898507ac40bb257015ff3e3f6e213bf1f913",
    "data/old_ducklake/migrate_bounds/main/dc/ducklake-01a14a6c-ef3e-7a35-93c9-1240f2b75a4c.parquet": "0d4aef2b1b8bb6468566112d0a74e0da71781780c8a21e8286844310cce1d868",
    "data/old_ducklake/migrate_bounds/main/i2f/ducklake-01a14a6c-ee9e-71bd-a440-ce5b0d111470.parquet": "eaaa6d7c3ef8e79dafdcd32aa6d55201d37db549516b7031737ca087577b8a32",
    "data/old_ducklake/migrate_bounds/main/r/ducklake-01a14a6c-edf7-7e3f-a2e1-edc403aefe32.parquet": "4b0fbb0165b9f1884098913813b9cc86312563bbbdd03651a97bf60de8a0e4be",
    "data/old_ducklake/migrate_bounds/main/r/ducklake-01a14a6c-ee27-7f05-b734-54809d0c1975.parquet": "27a8f1d7ef2c6240d3b6267e5ddf905b6bc5e61a1fa162e1205a5ad208fcb11b",
    "data/old_ducklake/migrate_bounds/main/st/ducklake-01a14a6c-ee4f-77fc-9fdd-6dd816642b2c.parquet": "4b0fbb0165b9f1884098913813b9cc86312563bbbdd03651a97bf60de8a0e4be",
    "data/old_ducklake/migrate_bounds/main/tf/ducklake-01a14a6c-ef01-72ab-abc1-9c9aa81ec7de.parquet": "4b0fbb0165b9f1884098913813b9cc86312563bbbdd03651a97bf60de8a0e4be",
}


def main():
    for name, expected in FILES.items():
        path = ROOT / name
        if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
            continue
        with urlopen(f"https://raw.githubusercontent.com/duckdb/ducklake/{SOURCE}/{name}", timeout=60) as response:
            data = response.read(1_048_577)
        if len(data) > 1_048_576 or hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError(f"Wrong upstream 1.1 fixture digest/size: {name}")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    print(f"Verified {len(FILES)} R02/R04 upstream inputs at {SOURCE}: {ROOT}")


if __name__ == "__main__":
    main()
