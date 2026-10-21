"""Fetch exact newer-upstream R02 regressions without moving the maintained corpus/extension pins."""
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
    print(f"Verified {len(FILES)} R02 upstream inputs at {SOURCE}: {ROOT}")


if __name__ == "__main__":
    main()
