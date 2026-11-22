"""Local guard/failure tests; real collector and SQL assertions run in the CI service."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'tests/prod-readiness/a4-lifecycle/ci_private_pg_log.py'
spec = importlib.util.spec_from_file_location('ci_private_pg_log', SCRIPT)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)

CONTAINER = 'a' * 64
DATABASE = 'a2_' + 'b' * 16 + '_test'
FILENAME = 'gate-' + 'c' * 16 + '.log'


class PrivatePgHelperTest(unittest.TestCase):
    def test_non_ci_cannot_inspect_or_restart_any_container(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false', 'SCM_CI_POSTGRES_CONTAINER': CONTAINER}), \
                patch.object(helper, 'docker') as docker:
            with self.assertRaises(helper.PrivateLogError):
                helper.service_container()
            docker.assert_not_called()

    def test_container_identity_requires_the_disposable_service(self):
        identity = {'Config': {'Image': 'postgres:16', 'Env': [
            'POSTGRES_DB=scm_test', 'POSTGRES_USER=scm', 'POSTGRES_HOST_AUTH_METHOD=trust']},
            'HostConfig': {'PortBindings': {'5432/tcp': [{'HostPort': '5433'}]}}}
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true', 'SCM_CI_POSTGRES_CONTAINER': CONTAINER}), \
                patch.object(helper, 'docker') as docker:
            docker.return_value.stdout = json.dumps([identity]).encode()
            self.assertEqual(helper.service_container(), CONTAINER)
            identity['Config']['Env'][0] = 'POSTGRES_DB=production'
            docker.return_value.stdout = json.dumps([identity]).encode()
            with self.assertRaises(helper.PrivateLogError):
                helper.service_container()

    def test_database_and_file_arguments_cannot_escape_test_names(self):
        db = MagicMock()
        with patch.object(helper, 'docker') as docker, patch.object(helper.psycopg, 'connect') as connect:
            for database in ('production', 'scm_test', DATABASE + ';DROP DATABASE scm_test', '../' + DATABASE):
                with self.subTest(database=database), self.assertRaises(helper.PrivateLogError):
                    helper.create_database(db, database)
                with self.subTest(database=database), self.assertRaises(helper.PrivateLogError):
                    helper.drop_database(db, database)
            for filename in ('../postgres.log', '/tmp/sql.log', FILENAME + ';id', None):
                with self.subTest(filename=filename), self.assertRaises((helper.PrivateLogError, TypeError)):
                    helper.read_chunk(CONTAINER, filename, 0)
            with self.assertRaises(helper.PrivateLogError):
                helper.remove_file(CONTAINER, helper.IDLE_FILE)
            with self.assertRaises(helper.PrivateLogError):
                helper.connect('production')
            db.execute.assert_not_called()
            docker.assert_not_called()
            connect.assert_not_called()

    def test_raw_follow_refuses_stdout_pipe(self):
        process = subprocess.run([sys.executable, str(SCRIPT), 'follow', '--file', FILENAME],
                                 capture_output=True, timeout=5)
        self.assertEqual(process.returncode, 1)
        self.assertEqual(process.stdout, b'')
        self.assertIn(b'private mode-0600 regular output file', process.stderr)

    def test_raw_docker_failure_text_never_reaches_cli_output(self):
        failure = subprocess.CalledProcessError(1, ['docker'], output=b'PRIVATE_SQL_VALUE', stderr=b'PRIVATE_SQL_VALUE')
        output = io.StringIO()
        with patch.object(sys, 'argv', [str(SCRIPT), 'initialize']), \
                patch.object(helper, 'initialize', side_effect=failure), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(helper.main(), 1)
        self.assertNotIn('PRIVATE_SQL_VALUE', output.getvalue())
        self.assertIn('CalledProcessError', output.getvalue())

    def test_anonymous_marker_spans_private_chunks_and_must_not_reach_docker_logs(self):
        marker = b'p36_private_sql_' + b'd' * 32
        first = b'LOG: statement: SELECT ' + marker[:20]
        second = marker[20:] + b'\n'
        with patch.object(helper.secrets, 'token_hex', return_value='d' * 32), \
                patch.object(helper, 'read_chunk', side_effect=[first, second]) as read, \
                patch.object(helper, 'docker', return_value=subprocess.CompletedProcess([], 0, b'service startup', b'')):
            helper.assert_private_route(MagicMock(), CONTAINER, FILENAME)
            self.assertEqual([call.args[2] for call in read.call_args_list], [0, len(first)])
        for stdout, stderr in ((marker, b''), (b'', marker)):
            with self.subTest(stream='stdout' if stdout else 'stderr'), \
                    patch.object(helper.secrets, 'token_hex', return_value='d' * 32), \
                    patch.object(helper, 'read_chunk', return_value=marker), \
                    patch.object(helper, 'docker', return_value=subprocess.CompletedProcess([], 0, stdout, stderr)), \
                    self.assertRaises(helper.PrivateLogError):
                helper.assert_private_route(MagicMock(), CONTAINER, FILENAME)

    def test_drop_failure_still_restores_collector_and_removes_closed_capture(self):
        outcome = {}
        with patch.object(helper, 'connect', return_value=MagicMock()) as connect, \
                patch.object(helper, 'drop_database', side_effect=OSError('private diagnostic')), \
                patch.object(helper, 'rotate') as rotate, patch.object(helper, 'check_private_settings'), \
                patch.object(helper, 'remove_file') as remove:
            with self.assertRaises(helper.PrivateLogError):
                helper.cleanup_capture(CONTAINER, DATABASE, FILENAME, outcome)
            self.assertEqual(connect.call_count, 2, 'Restoration must use a fresh connection after DROP failure')
            rotate.assert_called_once()
            remove.assert_called_once()
            self.assertEqual(outcome, {'configurationRestored': True, 'privateSourceRemoved': True})

    def test_failed_rotation_never_unlinks_active_capture_or_reports_it_removed(self):
        outcome = {}
        with patch.object(helper, 'connect', return_value=MagicMock()), patch.object(helper, 'drop_database'), \
                patch.object(helper, 'rotate', side_effect=TimeoutError('private diagnostic')), \
                patch.object(helper, 'remove_file') as remove:
            with self.assertRaises(helper.PrivateLogError):
                helper.cleanup_capture(CONTAINER, DATABASE, FILENAME, outcome)
            remove.assert_not_called()
            self.assertEqual(outcome, {'databaseRemoved': True})

    def test_expired_cleanup_deadline_cannot_start_more_external_operations(self):
        deadline = time.monotonic() - 1
        with patch.object(helper.subprocess, 'run') as run, patch.object(helper.psycopg, 'connect') as connect:
            with self.assertRaises(helper.PrivateLogError):
                helper.docker('inspect', CONTAINER, deadline=deadline)
            with self.assertRaises(helper.PrivateLogError):
                helper.connect(deadline=deadline)
            run.assert_not_called()
            connect.assert_not_called()

    def test_short_tail_cannot_start_a_connection_or_sql_that_exceeds_the_budget(self):
        db = MagicMock()
        with patch.object(helper.time, 'monotonic', return_value=100), \
                patch.object(helper.psycopg, 'connect') as connect:
            with self.assertRaises(helper.PrivateLogError):
                helper.connect(deadline=101.9)
            connect.assert_not_called()
            helper.connect(deadline=102.9)
            self.assertEqual(connect.call_args.kwargs['connect_timeout'], 2)
            self.assertIn('statement_timeout=1000', connect.call_args.kwargs['options'])
            with self.assertRaises(helper.PrivateLogError):
                helper.execute(db, 'SELECT 1', deadline=100.9)
            db.execute.assert_not_called()

    def test_cancellation_during_normal_cleanup_does_not_restart_the_shared_budget(self):
        # Controlled-clock helper test: invoke the installed handler directly.
        # This does not claim real Docker, PostgreSQL or OS signal integration.
        wrapper_spec = importlib.util.spec_from_file_location('p36_wrapper', SCRIPT.with_name('ci_two_instances.py'))
        wrapper = importlib.util.module_from_spec(wrapper_spec)
        with patch.dict(sys.modules, {'ci_private_pg_log': helper}):
            wrapper_spec.loader.exec_module(wrapper)
        clock = [100.0]
        db = MagicMock()
        db.__enter__.return_value = db
        outcome, timeouts = {}, []

        def execute(query):
            statement = query if isinstance(query, str) else query.as_string()
            value = None
            if statement.startswith('DROP DATABASE'):
                clock[0] = 102.0
                signal.getsignal(signal.SIGINT)(signal.SIGINT, None)
                self.assertTrue(wrapper._IN_CLEANUP)
                self.assertEqual(wrapper._CANCEL_DEADLINE, 109.0)
            elif statement.startswith('SHOW '):
                value = helper.SETTINGS[statement[5:].strip('"')]
            elif statement == "SELECT pg_current_logfile('stderr')":
                value = helper.DIRECTORY + '/' + helper.IDLE_FILE
            cursor = MagicMock()
            cursor.fetchone.return_value = (value,)
            return cursor

        def run(command, *, capture_output, timeout):
            self.assertTrue(capture_output)
            timeouts.append(timeout)
            # The first two Docker operations inspect file and directory modes;
            # the final one removes the now-closed private capture.
            duration, stdout = ((4.0, b'600\n'), (.8, b'700\n'), (.1, b''))[len(timeouts) - 1]
            self.assertLessEqual(duration, timeout)
            clock[0] += duration
            return subprocess.CompletedProcess(command, 0, stdout, b'')

        db.execute.side_effect = execute
        with patch.object(helper.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(helper, 'connect', return_value=db) as connect, \
                patch.object(helper.subprocess, 'run', side_effect=run):
            with self.assertRaises(SystemExit) as cancelled:
                with wrapper.cancellation_signals(), wrapper.cleanup_mode():
                    helper.cleanup_capture(CONTAINER, DATABASE, FILENAME, outcome)
            self.assertEqual(cancelled.exception.code, 128 + signal.SIGINT)
            self.assertEqual([call.kwargs['deadline'] for call in connect.call_args_list], [107.0, 107.0])
        self.assertEqual(outcome, {'databaseRemoved': True, 'configurationRestored': True, 'privateSourceRemoved': True})
        self.assertEqual(len(timeouts), 3)
        for actual, expected in zip(timeouts, (5.0, 1.0, .2)):
            self.assertAlmostEqual(actual, expected)
        self.assertLess(clock[0], 107.0)


if __name__ == '__main__':
    unittest.main()
