"""Reject unexpected preview provenance or a failed canary; no production compatibility claim."""
import json
import sys
from pathlib import Path

lock = json.loads(Path(sys.argv[1]).read_text())
report = json.loads(Path(sys.argv[2]).read_text())
assert report["python_package"] == lock["python_package"]
assert report["core"][0][:2] == [lock["core_version"], lock["core_source_id"]]
extensions = {row[0]: row[1] for row in report["extensions"]}
assert extensions["ducklake"] == lock["ducklake_extension"]
assert extensions["quack"] == lock["quack_extension"]
assert len(report["checks"]) == 12 and all(report["checks"].values())
assert report["observations"]["pinned_version"]["rows"] == [[lock["catalog_versions"][0]]]
assert report["observations"]["fresh_version"]["rows"] == [[lock["catalog_versions"][1]]]
print("Pinned preview provenance and all 12 canaries verified")
