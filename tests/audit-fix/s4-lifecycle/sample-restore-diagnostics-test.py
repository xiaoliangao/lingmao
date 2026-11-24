#!/usr/bin/env python3
"""Pure formatting/privacy regressions; these do not claim a PostgreSQL restore passed."""
import importlib.util
import errno
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("sample_restore", Path(__file__).with_name("sample-audit-dump-restore.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


class RestoreDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.output_failed = restore._OUTPUT_FAILED
        restore._OUTPUT_FAILED = False

    def tearDown(self):
        restore._OUTPUT_FAILED = self.output_failed

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

    def test_disk_full_is_classified_without_copy_row_or_file_path(self):
        result = self.diagnose('pg_restore: error: COPY failed for table "sys_data_log": ERROR:  could not extend file "PRIVATE_SENTINEL": No space left on device\n'
                               'CONTEXT: COPY sys_data_log, line 4147643: "PRIVATE_SENTINEL"\n')
        self.assertEqual(result["category"], "disk_full")

    def test_command_events_keep_failed_stage_and_original_exit_without_raw_stderr(self):
        output = io.StringIO()
        completed = subprocess.CompletedProcess([], 7, b"", b"ERROR: PRIVATE_SENTINEL\nCOPY PRIVATE_SENTINEL\n")
        with patch.object(restore.subprocess, "run", return_value=completed), patch.object(sys, "stdout", output):
            with self.assertRaises(restore.GateFailure) as caught:
                restore.run(["unused"], {}, "real pg_restore")
        self.assertEqual(caught.exception.diagnostic["exitCode"], 7)
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([(row["stage"], row["phase"]) for row in events], [("pg_restore", "start"), ("pg_restore", "failure")])
        self.assertEqual(events[-1]["exitCode"], 7)
        self.assertNotIn("PRIVATE_SENTINEL", output.getvalue())

    def test_command_timeout_remains_60_seconds_and_has_safe_stage(self):
        output = io.StringIO()
        with patch.object(restore.subprocess, "run", side_effect=subprocess.TimeoutExpired("PRIVATE_SENTINEL", 60)) as command, patch.object(sys, "stdout", output):
            with self.assertRaisesRegex(restore.GateFailure, "real pg_restore: TimeoutExpired"):
                restore.run(["unused"], {}, "real pg_restore")
        self.assertEqual(command.call_args.kwargs["timeout"], 60)
        last = json.loads(output.getvalue().splitlines()[-1])
        self.assertEqual((last["stage"], last["phase"], last["errorKind"]), ("pg_restore", "failure", "timeout"))
        self.assertNotIn("PRIVATE_SENTINEL", output.getvalue())

    def test_evidence_disk_failure_cannot_replace_primary_or_cleanup_failure(self):
        evidence = {"passed": False, "failure": "real pg_restore: exit 7",
                    "failureDiagnostic": {"exitCode": 7}, "cleanupFailure": "remove isolated restore database: exit 1",
                    "privateArchiveRemoved": True}
        output = io.StringIO()
        with patch.object(Path, "mkdir"), patch.object(Path, "write_text", side_effect=OSError(errno.ENOSPC, "PRIVATE_SENTINEL")), patch.object(sys, "stdout", output):
            code = restore.publish_result(evidence, Path("unused.json"))
        self.assertEqual(code, 1)
        self.assertEqual(evidence["failure"], "real pg_restore: exit 7")
        self.assertEqual(evidence["failureDiagnostic"]["exitCode"], 7)
        self.assertEqual(evidence["cleanupFailure"], "remove isolated restore database: exit 1")
        self.assertEqual(evidence["evidenceWriteFailure"], {"category": "disk_full", "errno": errno.ENOSPC})
        result = json.loads(output.getvalue().splitlines()[-1])
        self.assertTrue(result["primaryFailure"] and result["cleanupFailed"])
        self.assertFalse(result["evidenceWritten"])
        self.assertNotIn("PRIVATE_SENTINEL", output.getvalue())

    def test_broken_pipe_is_replaced_before_interpreter_final_flush(self):
        class Broken:
            def write(self, _value):
                raise BrokenPipeError("PRIVATE_SENTINEL")

            def flush(self):
                raise BrokenPipeError("PRIVATE_SENTINEL")

        with patch.object(sys, "stdout", Broken()):
            restore.event("command", "pg_restore", "failure", exitCode=7)
            self.assertTrue(restore._OUTPUT_FAILED)
            self.assertIsInstance(sys.stdout, restore._DiscardOutput)
            sys.stdout.flush()  # Interpreter cleanup can no longer turn exit1 into120.

    def test_real_full_device_does_not_replace_exit_one_with_interpreter_exit120(self):
        self.assertTrue(Path("/dev/full").exists(), "The Linux CI full-device negative control must be available")
        unchanged = subprocess.run([sys.executable, "-c", "import sys; sys.stdout=open('/dev/full','w'); print('synthetic'); raise SystemExit(1)"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=False)
        self.assertEqual(unchanged.returncode, 120)
        code = """
import importlib.util,sys
spec=importlib.util.spec_from_file_location('restore',sys.argv[1])
restore=importlib.util.module_from_spec(spec);spec.loader.exec_module(restore)
sys.stdout=open('/dev/full','w')
restore.event('command','pg_restore','failure',exitCode=7)
raise SystemExit(1)
"""
        guarded = subprocess.run([sys.executable, "-c", code, str(Path(__file__).with_name("sample-audit-dump-restore.py"))],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=False)
        self.assertEqual(guarded.returncode, 1)

    def test_successful_report_retains_the_original_verification_fields(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(sys, "stdout", io.StringIO()) as output:
            path = Path(directory) / "result.json"
            evidence = {"passed": True, "exactSnapshotEqual": True, "targetDatabaseRemoved": True, "privateArchiveRemoved": True}
            self.assertEqual(restore.publish_result(evidence, path), 0)
            self.assertTrue(json.loads(path.read_text())["exactSnapshotEqual"])
            result = json.loads(output.getvalue().splitlines()[-1])
            self.assertTrue(result["passed"] and result["verificationPassed"] and result["evidenceWritten"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
