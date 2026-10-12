import copy
import json
import unittest
from pathlib import Path

from verify_ducklake_11_fixtures import verify


class FixtureContract(unittest.TestCase):
    def setUp(self):
        self.fixtures = json.loads(Path(__file__).with_name("ducklake-11-fixtures.json").read_text())
        self.by_label = {row["label"]: row for row in self.fixtures["fixtures"]}

    def test_distinct_targets_match(self):
        self.assertEqual(len(self.by_label), 5)
        for row in self.by_label.values():
            verify(copy.deepcopy(row), self.fixtures)

    def test_provenance_drift_is_rejected(self):
        row = copy.deepcopy(self.by_label["locked-preview-fresh"])
        row["extensions"][0][1] = "unknown"
        with self.assertRaisesRegex(ValueError, "extensions"):
            verify(row, self.fixtures)

    def test_missing_metadata_field_is_rejected(self):
        row = copy.deepcopy(self.by_label["maintained-1.0"])
        row["schema"]["ducklake_data_file"].remove("row_id_start BIGINT")
        with self.assertRaisesRegex(ValueError, "schema"):
            verify(row, self.fixtures)

    def test_dev_version_is_not_a_parent_schema_capability(self):
        preview = self.by_label["locked-preview-fresh"]
        self.assertEqual(preview["catalog_version"], "1.1-dev1")
        self.assertNotIn("parent_schema_id BIGINT", preview["schema"]["ducklake_schema"])
        for label in ("duckdb-2.0-bundle", "development-main"):
            source = self.by_label[label]
            self.assertFalse(source["executed"])
            self.assertIn("parent_schema_id BIGINT", source["statements"][0])
            self.assertTrue(all(sql.endswith(");") for sql in source["statements"]))


if __name__ == "__main__":
    unittest.main()
