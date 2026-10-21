"""Download only corpus-required DuckDB data at its exact nested gitlink, with SHA-256 checks.

No upstream checkout/goldens are edited. Run from the repository root before full replay.
"""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
LOCK = json.loads((ROOT / "dev-docs/probes/duckdb2-release-lock.json").read_text())
FILES = {
    "arrow/alltypes_plain.parquet": "12a618d20a59ee0967fef45e7ec1ff6d451e724838edc1bbeac780ca15e8fcc4",
    "aws1.snappy.parquet": "e253aeca43ee2c9f62c6fe9d7900ab79b0925d0fb2b4be9c84ef1cd58f980917",
    "bug2557.parquet": "e6f732fdeed9b39373783db997d6bf4fc8367e5ccc8081fd0ecf9aceae435ac2",
    "enum.parquet": "70bd560a5f597a5910db69dee52faa9d3fe9da947331b592719d5c711df72589",
    "fixed.parquet": "d4f9db1689acbca19f0f789503312b28d209e7691d2fe95ad15efe49c2ad92b0",
    "json_convertedtype.parquet": "b6e8714b791c0eb5ceffec2db00357eb0665ebe8af8fa870ebc1cc52870c37f5",
    "parquet_with_json.parquet": "617b53f9b22e09137fc0cd54e6c2034a20d5e3fb5dce30765154afad44c13203",
    "timetz_4byte_stats.parquet": "28e7754eb44bde2d4554b1d96093af701af3c24c5ad9ccfda5f004c7cbcfb370",
}


def main():
    target = ROOT / "ducklake-test-corpus-replay/build/corpus-duckdb/data/parquet-testing"
    for name, digest in FILES.items():
        path = target / name
        if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest:
            continue
        url = f"https://raw.githubusercontent.com/duckdb/duckdb/{LOCK['corpus_duckdb_sha']}/data/parquet-testing/{name}"
        with urlopen(url, timeout=60) as response:
            data = response.read(2_000_000)
        if hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError(f"Wrong upstream fixture digest: {name}")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    print(f"Verified {len(FILES)} upstream DuckDB fixtures at {LOCK['corpus_duckdb_sha']}: {target}")


if __name__ == "__main__":
    main()
