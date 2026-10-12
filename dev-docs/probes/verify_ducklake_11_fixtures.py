"""Verify regenerated observations against immutable, explicitly labelled fixtures."""

import argparse
import json
from pathlib import Path


def verify(actual, fixtures):
    expected = next(row for row in fixtures["fixtures"] if row["label"] == actual["label"])
    if actual != expected:
        keys = sorted(key for key in actual.keys() | expected.keys() if actual.get(key) != expected.get(key))
        raise ValueError(f"{actual['label']}: changed fields {keys}; inspect provenance/layout before updating fixtures")
    print(f"{actual['label']}: exact provenance and observations match")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("observations", type=Path, nargs="+")
    args = parser.parse_args()
    fixtures = json.loads(Path(__file__).with_name("ducklake-11-fixtures.json").read_text())
    for path in args.observations:
        verify(json.loads(path.read_text()), fixtures)


if __name__ == "__main__":
    main()
