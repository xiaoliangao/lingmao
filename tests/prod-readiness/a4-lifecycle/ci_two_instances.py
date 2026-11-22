"""Provide A2's two-JVM gate with a private empty CI database and actual PostgreSQL log stream."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time

from psycopg.conninfo import make_conninfo

import ci_private_pg_log as private_log

_CANCEL_SIGNAL = None
_CANCEL_DEADLINE = None
_IN_CLEANUP = False


@contextmanager
def cancellation_signals():
    """GitHub cancellation must unwind finally blocks before the wrapper exits."""
    originals = {number: signal.getsignal(number) for number in (signal.SIGTERM, signal.SIGINT)}

    def cancel(number, _frame):
        global _CANCEL_SIGNAL, _CANCEL_DEADLINE
        _CANCEL_SIGNAL = number
        _CANCEL_DEADLINE = time.monotonic() + 7
        # A second cancellation must not interrupt database/file cleanup.
        for current in originals:
            signal.signal(current, signal.SIG_IGN)
        if not _IN_CLEANUP:
            raise SystemExit(128 + number)

    try:
        for number in originals:
            signal.signal(number, cancel)
        yield
        if _CANCEL_SIGNAL is not None:
            raise SystemExit(128 + _CANCEL_SIGNAL)
    finally:
        for number, handler in originals.items():
            signal.signal(number, handler)


@contextmanager
def cleanup_mode():
    global _IN_CLEANUP
    previous = _IN_CLEANUP
    _IN_CLEANUP = True
    try:
        yield
    finally:
        _IN_CLEANUP = previous


def group_has_live_members(group):
    try:
        os.killpg(group, 0)
    except ProcessLookupError:
        return False
    # CI is Linux. An orphan zombie is already dead but can remain visible until
    # PID 1 reaps it; it must not be confused with a process still using the DB.
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            fields = (entry / 'stat').read_text().rsplit(') ', 1)[1].split()
        except (FileNotFoundError, ProcessLookupError):
            continue
        if int(fields[2]) == group and fields[0] not in {'Z', 'X'}:
            return True
    return False


def stop_process_group(process, grace_seconds=10):
    """Stop the gate and every child JVM, including when the Python leader exited."""
    if process is None:
        return
    deadline = time.monotonic() + grace_seconds
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait(timeout=private_log.remaining(_CANCEL_DEADLINE, 1))
        return
    while time.monotonic() < deadline:
        if _CANCEL_SIGNAL is not None:
            # Leave time for SQL restoration before the runner escalates its
            # cancellation. A stuck child can be killed in this disposable gate.
            deadline = min(deadline, time.monotonic() + .5, _CANCEL_DEADLINE)
        process.poll()  # Reap the leader without confusing it with group termination.
        if not group_has_live_members(process.pid):
            return
        time.sleep(.05)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=private_log.remaining(_CANCEL_DEADLINE, 5))
    deadline = time.monotonic() + private_log.remaining(_CANCEL_DEADLINE, 5)
    while group_has_live_members(process.pid):
        if time.monotonic() >= deadline:
            raise RuntimeError('A CI gate descendant did not terminate after SIGKILL')
        time.sleep(.05)


def run_gate():
    root = Path(__file__).resolve().parents[3]
    container = private_log.service_container()
    evidence = root / 'target/test-artifacts/a2-instances'
    evidence.mkdir(parents=True, exist_ok=True)
    for name in ('summary.json', 'log-privacy.json'):
        (evidence / name).unlink(missing_ok=True)
    password = os.environ['SCM_CI_DB_PASSWORD']
    redis_password = os.environ['SCM_CI_REDIS_PASSWORD']
    database = 'a2_' + secrets.token_hex(8) + '_test'
    filename = 'gate-' + secrets.token_hex(8) + '.log'
    work = None
    follower = gate = None
    privacy = {'databaseScoped': False, 'sqlLiteralPrivate': False, 'sqlLiteralAbsentFromContainerLogs': False,
               'endSentinelVerified': False, 'databaseRemoved': False,
               'privateSourceRemoved': False, 'configurationRestored': False}
    try:
        work = Path(tempfile.mkdtemp(prefix='a2-instances-ci-'))
        pg_log = work / 'postgres.log'
        pg_log.touch(mode=0o600)
        with private_log.connect() as db:
            private_log.check_private_settings(db, container)
            try:
                private_log.create_database(db, database)
                privacy['databaseScoped'] = True
                private_log.rotate(db, container, filename)
                with private_log.connect(database) as probe:
                    private_log.assert_private_route(probe, container, filename)
                privacy['sqlLiteralPrivate'] = privacy['sqlLiteralAbsentFromContainerLogs'] = True
                env = {**os.environ, 'PG_DSN': make_conninfo(host='127.0.0.1',port=5433,user='scm',password=password,dbname=database),
                       'DB_URL': f'jdbc:postgresql://127.0.0.1:5433/{database}', 'DB_USER':'scm', 'DB_PASSWORD':password,
                       'REDIS_HOST':'127.0.0.1','REDIS_PORT':'6380','REDIS_PASSWORD':redis_password,'REDIS_DB':'10',
                       'PDF_FONT_DIR':str(root / 'dev/fonts')}
                with pg_log.open('w') as output:
                    follower = subprocess.Popen([sys.executable, str(Path(__file__).with_name('ci_private_pg_log.py')),
                                                 'follow', '--file', filename], stdout=output,
                                                stderr=subprocess.DEVNULL, start_new_session=True)
                    command = [sys.executable,str(root / 'tests/prod-readiness/a2-security/two_instances.py'),
                               '--jar',str(root / 'target/scm-0.0.1-SNAPSHOT.jar'),'--pg-log',str(pg_log),
                               '--work-dir',str(work / 'instances')]
                    gate = subprocess.Popen(command,env=env,start_new_session=True)
                    code = gate.wait(timeout=300)
                    if code:
                        raise subprocess.CalledProcessError(code, command)
                    private_log.require(follower.poll() is None, 'Private PostgreSQL log follower exited during T08')
                    with private_log.connect(database) as probe:
                        private_log.assert_private_route(probe, container, filename)
                    privacy['endSentinelVerified'] = True
            finally:
                # Stop descendants before dropping their DB or private files, even
                # when the wrapper itself receives a runner cancellation signal.
                with cleanup_mode():
                    try:
                        try:
                            stop_process_group(gate)
                        finally:
                            stop_process_group(follower, grace_seconds=5)
                        summary = work / 'instances/summary.json'
                        if summary.is_file():
                            (evidence / 'summary.json').write_text(json.dumps(json.loads(summary.read_text()),indent=2))
                    finally:
                        private_log.cleanup_capture(container, database, filename, privacy,
                                                    deadline=_CANCEL_DEADLINE)
    finally:
        with cleanup_mode():
            if work is not None:
                shutil.rmtree(work,ignore_errors=True)
                privacy['hostCopyRemoved'] = not work.exists()
                (evidence / 'log-privacy.json').write_text(json.dumps(privacy, indent=2) + '\n')
                private_log.require(privacy['hostCopyRemoved'], 'Private PostgreSQL host capture could not be removed')


def main():
    with cancellation_signals():
        try:
            run_gate()
        except Exception as error:
            detail = str(error) if isinstance(error, private_log.PrivateLogError) else type(error).__name__
            raise SystemExit('T08 private PostgreSQL gate failed: ' + detail) from None


if __name__ == '__main__':
    main()
