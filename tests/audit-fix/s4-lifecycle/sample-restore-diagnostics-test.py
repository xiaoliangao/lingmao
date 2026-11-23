#!/usr/bin/env python3
"""Pure formatting/privacy regressions; these do not claim a PostgreSQL restore passed."""
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest


spec = importlib.util.spec_from_file_location("sample_restore", Path(__file__).with_name("sample-audit-dump-restore.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


class RestoreDiagnosticsTest(unittest.TestCase):
    def diagnose(self, raw):
        result = restore.safe_diagnostic(subprocess.CompletedProcess([], 1, b"", raw.encode()))
        encoded = json.dumps(result)
        self.assertNotIn("PRIVATE_SENTINEL", encoded)
        self.assertNotIn("postgresql://", encoded)
        self.assertNotIn("SELECT", encoded)
        self.assertNotIn("COPY", encoded)
        self.assertEqual(len(result["stderrSha256"]), 64)
        self.assertEqual(result["stderrBytes"], len(raw.encode()))
        return result

    def test_restore_copy_error_keeps_only_a_fixed_known_function(self):
        result = self.diagnose('pg_restore: error: COPY failed for table "sys_data_log": ERROR:  function scm_security_audit_id_valid(jsonb) does not exist\n'
                               'CONTEXT: COPY sys_data_log, line 1: "PRIVATE_SENTINEL"\nSELECT PRIVATE_SENTINEL;\n')
        self.assertEqual(result["category"], "undefined_function")
        self.assertEqual(result["knownObject"], "scm_security_audit_id_valid")

    def test_restore_execute_error_does_not_copy_unknown_function_name(self):
        result = self.diagnose('pg_restore: error: could not execute query: ERROR:  function PRIVATE_SENTINEL(text) does not exist\n'
                               'Command was: SELECT PRIVATE_SENTINEL;\n')
        self.assertEqual(result["category"], "undefined_function")
        self.assertNotIn("knownObject", result)

    def test_psql_sqlstate_is_preserved_without_detail(self):
        result = self.diagnose('ERROR:  23514\nDETAIL: PRIVATE_SENTINEL\n')
        self.assertEqual(result["sqlState"], "23514")

    def test_constraint_name_and_row_values_are_not_exported(self):
        result = self.diagnose('ERROR:  new row for relation "PRIVATE_SENTINEL" violates check constraint "PRIVATE_SENTINEL"\n')
        self.assertEqual(result["category"], "check_constraint")

    def test_connection_error_does_not_copy_dsn_or_user(self):
        result = self.diagnose('pg_restore: error: connection to postgresql://PRIVATE_SENTINEL failed\n')
        self.assertEqual(result["category"], "unclassified")

    def test_sql_detail_cannot_supply_a_false_known_object(self):
        result = self.diagnose('ERROR:  permission denied for table PRIVATE_SENTINEL\n'
                               'DETAIL: function scm_security_audit_id_valid(jsonb) does not exist\n')
        self.assertEqual(result["category"], "permission_denied")
        self.assertNotIn("knownObject", result)


if __name__ == "__main__":
    unittest.main(verbosity=2)
