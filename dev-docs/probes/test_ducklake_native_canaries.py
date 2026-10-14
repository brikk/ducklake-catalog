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


if __name__ == "__main__":
    unittest.main()
