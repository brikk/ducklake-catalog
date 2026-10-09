"""Reject unexpected preview provenance or a failed canary; no production compatibility claim."""
import json
import sys
from pathlib import Path

lock = json.loads(Path(sys.argv[1]).read_text())
report = json.loads(Path(sys.argv[2]).read_text())
assert report["python_package"] == lock["python_package"]
if len(sys.argv) > 3 and sys.argv[3] == "quack":
    for peer in (report["server"], report["client"]):
        assert peer["core"][0][:2] == [lock["core_version"], lock["core_source_id"]]
        assert dict(peer["extensions"])["quack"] == lock["quack_extension"]
    assert report["rollback_recovery"] and report["commit_reuse"]
    print("Pinned native Quack peers, rollback recovery and commit reuse verified")
else:
    assert report["core"][0][:2] == [lock["core_version"], lock["core_source_id"]]
    extensions = {row[0]: row[1] for row in report["extensions"]}
    assert extensions["ducklake"] == lock["ducklake_extension"]
    assert extensions["quack"] == lock["quack_extension"]
    assert len(report["checks"]) == 12 and all(report["checks"].values())
    assert report["observations"]["pinned_version"]["rows"] == [[lock["catalog_versions"][0]]]
    assert report["observations"]["fresh_version"]["rows"] == [[lock["catalog_versions"][1]]]
    print("Pinned preview provenance and all 12 canaries verified")
