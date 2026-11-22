"""Small actual file/process regressions for CI failure cleanup and report privacy."""
import importlib.util
import html
import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

import psycopg
from psycopg import sql

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('ci_two_instances', Path(__file__).with_name('ci_two_instances.py'))
wrapper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wrapper)


class CiFailureSafetyTest(unittest.TestCase):
    forms = {
        'assertion.txt': '["scm_refresh=REFRESH_SENTINEL; Path=/api/auth", "scm_browser=BROWSER_SENTINEL; HttpOnly"]',
        'reversed.log': 'scm_browser=BROWSER_SENTINEL; scm_refresh=REFRESH_SENTINEL',
        'failsafe.xml': '<testsuite><failure message="&quot;password&quot;: &quot;PASSWORD_SENTINEL two words&quot; '
                        '&quot;scm_refresh&quot;: &quot;REFRESH_SENTINEL&quot; '
                        '&#39;scm_browser&#39;=&#39;BROWSER_SENTINEL&#39;"/></testsuite>',
        'escaped.json': r'{"message":"{\"password\":\"PASSWORD_SENTINEL two words\",'
                        r'\"authorization\":\"Bearer AUTH_SENTINEL\",'
                        r'\"scm_refresh\":\"REFRESH_SENTINEL\",\"scm_browser\":\"BROWSER_SENTINEL\"}","public":"visible"}',
        'runtime.txt': 'runtime DB_SENTINEL\nAuthorization: Bearer eyJheader.claims.signature',
        'multiple.log': 'Cookie: OTHER_COOKIE_SENTINEL\tAuthorization: Bearer AUTH_SENTINEL\n'
                        'Authorization: Bearer AUTH_SENTINEL Cookie: OTHER_COOKIE_SENTINEL\n'
                        '{"authorization":"Bearer AUTH_SENTINEL", "password":"PASSWORD_SENTINEL two words", "public":"visible"}',
        'properties.xml': '<testsuite><property name="DB_PASSWORD" value="PASSWORD_SENTINEL &amp; more"/>'
                          '<password>PASSWORD_SENTINEL two words</password></testsuite>',
        'nested.txt': r'password="PASSWORD_SENTINEL \" NESTED_SENTINEL" public=visible',
        'nested-xml.xml': '<testsuite><failure message="' + html.escape(json.dumps({
            'password': 'PASSWORD_SENTINEL " NESTED_SENTINEL\\'}), quote=True) + '"/></testsuite>',
        'nested-json.json': json.dumps({'message': json.dumps({
            'password': 'PASSWORD_SENTINEL " NESTED_SENTINEL\\'}), 'public': 'visible'}),
        'opaque-credentials.json': json.dumps({
            **{key: 'OPAQUE_SENTINEL two words' for key in ('appSecret', 'app_secret', 'secret',
                                                         'access_token', 'refresh_token', 'accessToken', 'token')},
            'public': 'visible'}),
    }
    markers = ('REFRESH_SENTINEL', 'BROWSER_SENTINEL', 'DB_SENTINEL', 'PASSWORD_SENTINEL',
               'AUTH_SENTINEL', 'OTHER_COOKIE_SENTINEL', 'NESTED_SENTINEL', 'OPAQUE_SENTINEL', 'eyJheader.claims.signature')

    def assert_redacted(self, value):
        self.assertFalse(any(marker in value for marker in self.markers),
                         'A transient credential survived report sanitization')
        self.assertIn('[REDACTED', value)

    def test_report_files_hide_bare_cookies_and_xml_assertion_values(self):
        with tempfile.TemporaryDirectory(prefix='scm-redaction-test-') as directory:
            reports = Path(directory) / 'target/failsafe-reports'
            reports.mkdir(parents=True)
            for name, value in self.forms.items():
                (reports / name).write_text(value)
            subprocess.run([sys.executable, str(ROOT / 'dev/ci/sanitize-artifacts.py')], cwd=directory,
                           env={**os.environ, 'SCM_CI_DB_PASSWORD': 'DB_SENTINEL'}, check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            for path in reports.iterdir():
                with self.subTest(format=path.suffix):
                    value = path.read_text()
                    self.assert_redacted(value)
                    if path.suffix == '.xml':
                        ET.fromstring(value)
                    if path.suffix == '.json':
                        self.assertEqual(json.loads(value)['public'], 'visible')
            first_pass = {path.name: path.read_text() for path in reports.iterdir()}
            subprocess.run([sys.executable, str(ROOT / 'dev/ci/sanitize-artifacts.py')], cwd=directory,
                           env={**os.environ, 'SCM_CI_DB_PASSWORD': 'DB_SENTINEL'}, check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            self.assertTrue(all(path.read_text() == first_pass[path.name] for path in reports.iterdir()),
                            'Repeated report sanitization changed already redacted values')

    def test_log_copy_uses_same_redaction_and_excludes_private_postgres_log(self):
        with tempfile.TemporaryDirectory(prefix='scm-log-copy-test-') as directory:
            source, destination = Path(directory) / 'source', Path(directory) / 'destination'
            source.mkdir()
            for name in ('app.log', 'console.log', 'web.log', 'seed.log', 'postgres.log'):
                (source / name).write_text('\n'.join(self.forms.values()))
            subprocess.run([sys.executable, str(ROOT / 'dev/ci/sanitize-logs.py'), str(source), str(destination)],
                           env={**os.environ, 'SCM_CI_DB_PASSWORD': 'DB_SENTINEL'}, check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            self.assertEqual({path.name for path in destination.iterdir()}, {'app.log', 'console.log', 'web.log', 'seed.log'})
            for path in destination.iterdir():
                self.assert_redacted(path.read_text())

    def assert_group_stops(self, leader_exits):
        child = 'import signal;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("CHILD_READY",flush=True);signal.pause()'
        parent = ('import subprocess,sys,signal;'
                  'signal.signal(signal.SIGTERM,signal.SIG_IGN);'
                  f'subprocess.Popen([sys.executable,"-c",{child!r}]);'
                  + ('sys.exit(0)' if leader_exits else 'signal.pause()'))
        process = subprocess.Popen([sys.executable, '-c', parent], start_new_session=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertTrue(select.select([process.stdout], [], [], 5)[0], 'Child startup did not complete')
            self.assertEqual(process.stdout.readline().strip(), 'CHILD_READY')
            if leader_exits:
                self.assertEqual(process.wait(timeout=5), 0)
            wrapper.stop_process_group(process, grace_seconds=.1)
            # The descendant inherited stdout. EOF proves it exited too, even after
            # the group leader had already exited and wait() alone would succeed.
            process.communicate(timeout=3)
            self.assertIsNotNone(process.returncode)
        finally:
            wrapper.stop_process_group(process, grace_seconds=.1)
            process.stdout.close()
            process.stderr.close()

    def test_timeout_cleanup_stops_interrupt_ignoring_descendant(self):
        self.assert_group_stops(False)

    def test_cleanup_stops_orphan_after_python_leader_exits(self):
        self.assert_group_stops(True)

    def test_sigterm_wrapper_stops_children_restores_postgres_and_removes_private_files(self):
        # This executes the unmodified wrapper entry point against the real CI
        # PostgreSQL service and its actual private collector. Only the expensive
        # JVM gate is a fixture; Docker, SQL, signals and file cleanup are real.
        container = wrapper.private_log.service_container()
        password = os.environ.get('SCM_CI_DB_PASSWORD', '')
        keys = ('log_statement', 'log_parameter_max_length', 'log_parameter_max_length_on_error', 'log_filename')
        with psycopg.connect(host='127.0.0.1', port=5433, user='scm', password=password,
                             dbname='postgres', autocommit=True, connect_timeout=5) as db:
            original = {key: db.execute(sql.SQL('SHOW {}').format(sql.Identifier(key))).fetchone()[0] for key in keys}
            with tempfile.TemporaryDirectory(prefix='scm-cancel-test-') as directory:
                root = Path(directory)
                script = root / 'tests/prod-readiness/a4-lifecycle/ci_two_instances.py'
                script.parent.mkdir(parents=True)
                shutil.copyfile(Path(__file__).with_name('ci_two_instances.py'), script)
                shutil.copyfile(Path(__file__).with_name('ci_private_pg_log.py'), script.with_name('ci_private_pg_log.py'))
                gate = root / 'tests/prod-readiness/a2-security/two_instances.py'
                gate.parent.mkdir(parents=True)
                child = 'import signal;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("READY",flush=True);signal.pause()'
                gate.write_text(
                    'import json,os,signal,subprocess,sys,time\nfrom pathlib import Path\n'
                    'from psycopg.conninfo import conninfo_to_dict\n'
                    'signal.signal(signal.SIGTERM,signal.SIG_IGN)\n'
                    f'child=subprocess.Popen([sys.executable,"-c",{child!r}],stdout=subprocess.PIPE,text=True)\n'
                    'assert child.stdout.readline().strip()=="READY"\n'
                    'pg_log=Path(sys.argv[sys.argv.index("--pg-log")+1])\n'
                    'deadline=time.monotonic()+5\n'
                    'while b"p36_private_sql_" not in pg_log.read_bytes():\n'
                    ' if time.monotonic()>=deadline: raise RuntimeError("Private collector did not arrive")\n'
                    ' time.sleep(.05)\n'
                    'Path(os.environ["SCM_CANCEL_READY"]).write_text(json.dumps({"gate":os.getpid(),"child":child.pid,'
                    '"database":conninfo_to_dict(os.environ["PG_DSN"])["dbname"],"work":str(pg_log.parent)}))\n'
                    'signal.pause()\n')
                ready = root / 'ready.json'
                process = subprocess.Popen([sys.executable, str(script)], env={
                    **os.environ, 'SCM_CI_POSTGRES_CONTAINER': container,
                    'SCM_CI_DB_PASSWORD': password, 'SCM_CI_REDIS_PASSWORD': '',
                    'SCM_CANCEL_READY': str(ready), 'TMPDIR': str(root)},
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
                state = None
                try:
                    deadline = time.monotonic() + 15
                    while not ready.is_file():
                        self.assertIsNone(process.poll(), 'Wrapper exited before the cancellation fixture started')
                        if time.monotonic() >= deadline:
                            self.fail('Wrapper cancellation fixture did not become ready')
                        time.sleep(.05)
                    state = json.loads(ready.read_text())
                    self.assertEqual(db.execute('SELECT count(*) FROM pg_database WHERE datname=%s', (state['database'],)).fetchone()[0], 1)
                    self.assertEqual(db.execute('SHOW log_statement').fetchone()[0], 'none')
                    filename = db.execute('SHOW log_filename').fetchone()[0]
                    self.assertRegex(filename, r'^gate-[a-f0-9]{16}\.log$')
                    with wrapper.private_log.connect(state['database']) as scoped:
                        self.assertEqual(scoped.execute('SHOW log_statement').fetchone()[0], 'all')
                        self.assertEqual(scoped.execute('SHOW log_parameter_max_length').fetchone()[0], '0')
                        self.assertEqual(scoped.execute('SHOW log_parameter_max_length_on_error').fetchone()[0], '0')
                    self.assertTrue((Path(state['work']) / 'postgres.log').is_file())
                    self.assertEqual((Path(state['work']) / 'postgres.log').stat().st_mode & 0o777, 0o600)
                    cancelling_at = time.monotonic()
                    process.send_signal(signal.SIGTERM)
                    stdout, stderr = process.communicate(timeout=20)
                    self.assertLess(time.monotonic() - cancelling_at, 8, 'Cancellation left too little time for runner cleanup')
                    self.assertEqual(process.returncode, 128 + signal.SIGTERM)
                    self.assertTrue(b'p36_private_sql_' not in stdout + stderr,
                                    'Private SQL escaped into wrapper stdout/stderr')
                    self.assertFalse(wrapper.group_has_live_members(state['gate']), 'Cancelled gate still owns live children')
                    self.assertFalse(Path(state['work']).exists(), 'Private query log survived cancellation')
                    self.assertEqual(db.execute('SELECT count(*) FROM pg_database WHERE datname=%s', (state['database'],)).fetchone()[0], 0)
                    restored = {key: db.execute(sql.SQL('SHOW {}').format(sql.Identifier(key))).fetchone()[0] for key in keys}
                    self.assertEqual(restored, original)
                    wrapper.private_log.docker('exec', '--user', 'postgres', container, 'sh', '-c',
                                               'test ! -e "$PGDATA/scm-ci-private-log/$1"', 'sh', filename)
                    privacy = json.loads((root / 'target/test-artifacts/a2-instances/log-privacy.json').read_text())
                    self.assertEqual(privacy, {
                        'databaseScoped': True, 'sqlLiteralPrivate': True,
                        'sqlLiteralAbsentFromContainerLogs': True, 'endSentinelVerified': False,
                        'databaseRemoved': True, 'privateSourceRemoved': True,
                        'configurationRestored': True, 'hostCopyRemoved': True,
                    })
                finally:
                    wrapper.stop_process_group(process, grace_seconds=.1)
                    if state and wrapper.group_has_live_members(state['gate']):
                        os.killpg(state['gate'], signal.SIGKILL)
                    process.stdout.close()
                    process.stderr.close()


if __name__ == '__main__':
    unittest.main()
