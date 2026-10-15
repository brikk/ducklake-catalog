import unittest

import duckdb

from ducklake_lineage_stats_blob import Case, encoded


class FakeQuery:
    def __init__(self, rows=None, error=None):
        self.rows = rows
        self.error = error

    def execute(self, sql, parameters=None):
        if self.error:
            raise self.error
        return self

    def fetchall(self):
        return self.rows


class NativeCanaryContract(unittest.TestCase):
    def test_exact_rows_pass(self):
        case = Case(FakeQuery([(1, b"\\x41", None)]))
        case.query("read", "test", [(1, b"\\x41", None)])
        self.assertTrue(case.checks["read"])

    def test_reinterpreted_escape_bytes_fail(self):
        case = Case(FakeQuery([(1, b"A")]))
        case.query("read", "test", [(1, b"\\x41")])
        self.assertFalse(case.checks["read"])
        self.assertNotEqual(case.observations["read"]["actual"], case.observations["read"]["expected"])

    def test_changed_row_id_fails_even_when_values_match(self):
        case = Case(FakeQuery([(1, 10)]))
        case.query("read", "test", [(0, 10)])
        self.assertFalse(case.checks["read"])

    def test_query_errors_are_failed_checks_not_skips(self):
        case = Case(FakeQuery(error=duckdb.Error("invalid UTF-8")))
        case.query("read", "test", [(1, b"\xff")])
        self.assertFalse(case.checks["read"])
        self.assertIn("error", case.observations["read"])
        self.assertIn("expected", case.observations["read"])

    def test_maintenance_error_is_failed_check(self):
        case = Case(FakeQuery(error=duckdb.Error("maintenance failed")))
        case.command("merge", "test")
        self.assertFalse(case.checks["merge"])

    def test_null_empty_and_zero_bytes_remain_distinct(self):
        self.assertEqual(encoded([None, b"", b"\x00"]), [None, {"hex": ""}, {"hex": "00"}])
        self.assertEqual(encoded({"nested": [None, b"\\x41"]}), {"nested": [None, {"hex": "5c783431"}]})

    def test_transient_cdc_images_and_duplicate_pairs_fail(self):
        from ducklake_cdc_transient import changes

        expected = [(3, 0, "delete", 0, 0)]
        case = Case(FakeQuery([(3, 0, "update_postimage", 0, 1), (3, 0, "update_preimage", 0, 0)]))
        changes(case, "cdc", 3, 3, expected)
        self.assertFalse(case.checks["cdc"])
        case = Case(FakeQuery(expected + expected))
        changes(case, "cdc", 3, 3, expected)
        self.assertFalse(case.checks["cdc"])

    def test_snapshot_ceiling_requires_the_specific_rejection(self):
        from ducklake_quack_snapshot_ceiling import rejected

        for error, passed in [(duckdb.Error("No snapshot found at version 4"), True),
                              (duckdb.Error("connection lost"), False), (None, False)]:
            case = Case(FakeQuery(rows=[(4,)], error=error))
            rejected(case, "future", "test", 4)
            self.assertEqual(case.checks["future"], passed)


if __name__ == "__main__":
    unittest.main()
