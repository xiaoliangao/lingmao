"""Private PostgreSQL statement capture for the disposable GitHub service only."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import time

import psycopg
from psycopg import sql

DIRECTORY = 'scm-ci-private-log'
IDLE_FILE = 'idle.log'
FILE_PATTERN = re.compile(r'(?:idle|gate-[a-f0-9]{16})\.log')
DATABASE_PATTERN = re.compile(r'(?:a2|p36)_[a-f0-9]{16}_test')
SETTINGS = {
    'logging_collector': 'on', 'log_destination': 'stderr', 'log_directory': DIRECTORY,
    'log_filename': IDLE_FILE, 'log_file_mode': '0600',
    'log_rotation_age': '0', 'log_rotation_size': '0', 'log_truncate_on_rotation': 'off',
    'log_statement': 'none', 'log_duration': 'off', 'log_min_duration_statement': '-1',
    'log_parameter_max_length': '0', 'log_parameter_max_length_on_error': '0',
}


class PrivateLogError(RuntimeError):
    """Only our constant, non-query diagnostics may be shown in CI."""


def require(condition, message):
    if not condition:
        raise PrivateLogError(message)


def remaining(deadline, maximum):
    if deadline is None:
        return maximum
    value = deadline - time.monotonic()
    require(value > 0, 'Private PostgreSQL cleanup exceeded its deadline')
    return min(value, maximum)


def docker(*arguments, timeout=10, deadline=None):
    # Never let Docker's stderr (potentially a raw server log) reach the runner.
    result = subprocess.run(['docker', *arguments], capture_output=True, timeout=remaining(deadline, timeout))
    require(result.returncode == 0, 'Private PostgreSQL Docker operation failed')
    return result


def service_container():
    container = os.environ.get('SCM_CI_POSTGRES_CONTAINER', '')
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and re.fullmatch('[a-f0-9]{12,64}', container),
            'Use only the existing GitHub Actions PostgreSQL service')
    inspected = json.loads(docker('inspect', container).stdout)
    require(len(inspected) == 1, 'Expected one CI service container')
    config = inspected[0]['Config']
    environment = dict(value.split('=', 1) for value in config['Env'] if '=' in value)
    require(config['Image'] == 'postgres:16' and environment.get('POSTGRES_DB') == 'scm_test'
            and environment.get('POSTGRES_USER') == 'scm'
            and environment.get('POSTGRES_HOST_AUTH_METHOD') == 'trust',
            'Container is not the configured disposable PostgreSQL 16 test service')
    ports = inspected[0]['HostConfig']['PortBindings'].get('5432/tcp', [])
    require(any(port['HostPort'] == '5433' for port in ports), 'Unexpected CI PostgreSQL port')
    return container


def connect(database='postgres', timeout=5, deadline=None):
    require(database in ('postgres', 'scm_test') or DATABASE_PATTERN.fullmatch(database),
            'Refusing a non-test database')
    # libpq treats connect_timeout < 2 as 2 seconds. Do not round up beyond the
    # remaining cleanup budget or start a connection that cannot fit in it.
    allowance = remaining(deadline, max(2, timeout))
    require(allowance >= 2, 'Insufficient cleanup time for a PostgreSQL connection')
    timeout = int(allowance)
    statement_ms = 5000 if deadline is None else 1000
    return psycopg.connect(host='127.0.0.1', port=5433, user='scm',
                           password=os.environ.get('SCM_CI_DB_PASSWORD', ''), dbname=database,
                           autocommit=True, connect_timeout=timeout,
                           options=f'-c statement_timeout={statement_ms} -c lock_timeout={min(statement_ms, 2000)}')


def execute(db, query, deadline=None):
    # Cleanup connections have a one-second statement/lock timeout. Reserve that
    # whole bound before each SQL call; a short tail cannot start another query.
    if deadline is not None:
        require(remaining(deadline, 1) >= 1, 'Insufficient cleanup time for a PostgreSQL statement')
    return db.execute(query)


def reload_settings(db, expected, deadline=None):
    remaining(deadline, 5)
    execute(db, 'SELECT pg_reload_conf()', deadline=deadline)
    limit = time.monotonic() + remaining(deadline, 5)
    while True:
        remaining(deadline, 5)
        if all(execute(db, sql.SQL('SHOW {}').format(sql.Identifier(key)), deadline=deadline).fetchone()[0] == value
               for key, value in expected.items()):
            return
        require(time.monotonic() < limit, 'PostgreSQL log settings did not reach the requested state')
        time.sleep(.05)


def check_private_settings(db, container, deadline=None):
    for key, value in SETTINGS.items():
        remaining(deadline, 5)
        require(execute(db, sql.SQL('SHOW {}').format(sql.Identifier(key)), deadline=deadline).fetchone()[0] == value,
                'CI PostgreSQL private log configuration is not active: ' + key)
    mode = docker('exec', '--user', 'postgres', container, 'sh', '-c',
                  'test ! -L "$PGDATA/scm-ci-private-log" && stat -c %a "$PGDATA/scm-ci-private-log"',
                  deadline=deadline).stdout.strip()
    require(mode == b'700', 'Private PostgreSQL log directory must have mode 0700')


def rotate(db, container, filename, deadline=None):
    require(FILE_PATTERN.fullmatch(filename), 'Invalid private log filename')
    remaining(deadline, 5)
    execute(db, sql.SQL('ALTER SYSTEM SET log_filename={}').format(sql.Literal(filename)), deadline=deadline)
    reload_settings(db, {'log_filename': filename}, deadline=deadline)
    execute(db, 'SELECT pg_rotate_logfile()', deadline=deadline)
    limit = time.monotonic() + remaining(deadline, 5)
    while execute(db, "SELECT pg_current_logfile('stderr')", deadline=deadline).fetchone()[0] != DIRECTORY + '/' + filename:
        remaining(deadline, 5)
        require(time.monotonic() < limit, 'Private PostgreSQL log rotation did not complete')
        time.sleep(.05)
    mode = docker('exec', '--user', 'postgres', container, 'sh', '-c',
                  'test ! -L "$PGDATA/scm-ci-private-log/$1" && stat -c %a "$PGDATA/scm-ci-private-log/$1"',
                  'sh', filename, deadline=deadline).stdout.strip()
    require(mode == b'600', 'Private PostgreSQL query log must have mode 0600')


def remove_file(container, filename, deadline=None):
    require(FILE_PATTERN.fullmatch(filename) and filename != IDLE_FILE, 'Refusing to remove a non-capture log')
    docker('exec', '--user', 'postgres', container, 'sh', '-c',
           'test ! -L "$PGDATA/scm-ci-private-log/$1" && rm -f -- "$PGDATA/scm-ci-private-log/$1" '
           '&& test ! -e "$PGDATA/scm-ci-private-log/$1"', 'sh', filename, deadline=deadline)


def read_chunk(container, filename, offset):
    require(FILE_PATTERN.fullmatch(filename) and type(offset) is int and offset >= 0, 'Invalid private log position')
    return docker('exec', '--user', 'postgres', container, 'sh', '-c',
                  'exec dd if="$PGDATA/scm-ci-private-log/$1" bs=1048576 count=1 skip="$2" iflag=skip_bytes status=none',
                  'sh', filename, str(offset), timeout=3).stdout


def create_database(db, database):
    require(DATABASE_PATTERN.fullmatch(database), 'Refusing to configure a non-disposable database')
    db.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(database)))
    for key, value in {'log_statement': 'all', 'log_parameter_max_length': '0',
                       'log_parameter_max_length_on_error': '0'}.items():
        db.execute(sql.SQL('ALTER DATABASE {} SET {}={}').format(
            sql.Identifier(database), sql.Identifier(key), sql.Literal(value)))


def drop_database(db, database, deadline=None):
    require(DATABASE_PATTERN.fullmatch(database), 'Refusing to drop a non-disposable database')
    execute(db, sql.SQL('DROP DATABASE IF EXISTS {} WITH (FORCE)').format(sql.Identifier(database)), deadline=deadline)


def cleanup_capture(container, database, filename, outcome, deadline=None):
    """Fresh bounded connections let a failed gate connection still be cleaned up."""
    # A cancellation can arrive after the wrapper has already entered normal
    # cleanup and passed None. Normal cleanup must therefore obey the same bound.
    normal_deadline = time.monotonic() + 7
    deadline = normal_deadline if deadline is None else min(deadline, normal_deadline)
    failures = []
    try:
        with connect(timeout=1, deadline=deadline) as db:
            drop_database(db, database, deadline=deadline)
            outcome['databaseRemoved'] = True
    except Exception as error:
        failures.append(type(error).__name__)
    # Attempt restoration independently even when DROP fails. Do not unlink an
    # active file after a failed rotation: the final CI step/container teardown
    # owns that private leftover, and this gate must fail rather than report success.
    try:
        with connect(timeout=1, deadline=deadline) as db:
            rotate(db, container, IDLE_FILE, deadline=deadline)
            check_private_settings(db, container, deadline=deadline)
            outcome['configurationRestored'] = True
        remove_file(container, filename, deadline=deadline)
        outcome['privateSourceRemoved'] = True
    except Exception as error:
        failures.append(type(error).__name__)
    require(not failures, 'Private PostgreSQL capture cleanup failed: ' + ', '.join(failures))


def assert_private_route(db, container, filename):
    """An actual SQL literal must reach the collector, but never Docker stdout/stderr."""
    marker = 'p36_private_sql_' + secrets.token_hex(16)
    db.execute(sql.SQL('SELECT {}').format(sql.Literal(marker)))
    needle = marker.encode()
    offset, overlap = 0, b''
    deadline = time.monotonic() + 10
    while True:
        chunk = read_chunk(container, filename, offset)
        if needle in overlap + chunk:
            break
        offset += len(chunk)
        overlap = (overlap + chunk)[-len(needle):]
        require(time.monotonic() < deadline, 'Anonymous SQL marker did not reach the private collector')
        if not chunk:
            time.sleep(.05)
    public = docker('logs', container)
    require(needle not in public.stdout and needle not in public.stderr,
            'Anonymous SQL marker leaked into the service container log')


def initialize():
    container = service_container()
    evidence = Path(__file__).resolve().parents[3] / 'target/test-artifacts/a4-private-pg'
    (evidence / 'initialization.json').unlink(missing_ok=True)
    with connect() as db:
        names = {row[0] for row in db.execute('SELECT datname FROM pg_database')}
        require(names <= {'template0', 'template1', 'postgres', 'scm_test'} and 'scm_test' in names,
                'Initialize only the fresh CI database service before tests')
        with connect('scm_test') as test_db:
            require(test_db.execute("SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace").fetchone()[0] == 0,
                    'The CI database is not empty; refusing a service restart')
        require(db.execute('SHOW log_statement').fetchone()[0] == 'none', 'Unexpected global SQL logging before initialization')
        docker('exec', '--user', 'postgres', container, 'sh', '-c',
               'umask 077; test ! -L "$PGDATA/scm-ci-private-log" && mkdir -p "$PGDATA/scm-ci-private-log" '
               '&& chmod 700 "$PGDATA/scm-ci-private-log"')
        for key, value in SETTINGS.items():
            db.execute(sql.SQL('ALTER SYSTEM SET {}={}').format(sql.Identifier(key), sql.Literal(value)))
    docker('restart', '--time', '10', container, timeout=30)
    deadline = time.monotonic() + 30
    while True:
        try:
            db = connect(timeout=1)
            break
        except psycopg.OperationalError:
            require(time.monotonic() < deadline, 'CI PostgreSQL did not restart')
            time.sleep(.1)
    with db:
        check_private_settings(db, container)
        database = 'p36_' + secrets.token_hex(8) + '_test'
        filename = 'gate-' + secrets.token_hex(8) + '.log'
        try:
            create_database(db, database)
            rotate(db, container, filename)
            with connect(database) as probe:
                assert_private_route(probe, container, filename)
        finally:
            cleanup_capture(container, database, filename, {})
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / 'initialization.json').write_text(json.dumps({
        'collector': True, 'directoryMode': '0700', 'fileMode': '0600',
        'globalStatementLogging': 'none', 'databaseScopedCapture': True,
        'sqlLiteralPrivate': True, 'sqlLiteralAbsentFromContainerLogs': True,
        'probeDatabaseAndFileRemoved': True,
    }, indent=2) + '\n')
    print('CI PostgreSQL private collector and anonymous SQL boundary verified')


def follow(filename):
    # This mode emits raw bytes only into the wrapper's private regular file, never a CI pipe/terminal.
    output = os.fstat(sys.stdout.fileno())
    require(stat.S_ISREG(output.st_mode) and stat.S_IMODE(output.st_mode) == 0o600,
            'Raw log capture requires a private mode-0600 regular output file')
    container = service_container()
    offset = 0
    while True:
        chunk = read_chunk(container, filename, offset)
        if chunk:
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
            offset += len(chunk)
        else:
            time.sleep(.05)


def cleanup():
    container = service_container()
    # This is the last DB-related CI step. Keep the collector enabled until container destruction;
    # unlinking its private files cannot redirect its open descriptors into Docker stdout.
    docker('exec', '--user', 'postgres', container, 'sh', '-c',
           'test ! -L "$PGDATA/scm-ci-private-log" && '
           'if [ -d "$PGDATA/scm-ci-private-log" ]; then '
           'find "$PGDATA/scm-ci-private-log" -mindepth 1 -maxdepth 1 -type f -name "*.log" -delete; '
           'test -z "$(find "$PGDATA/scm-ci-private-log" -mindepth 1 -maxdepth 1 -type f -name "*.log" -print -quit)"; fi')
    print('CI PostgreSQL private log files removed; collector remains private until teardown')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('initialize', 'follow', 'cleanup'))
    parser.add_argument('--file')
    args = parser.parse_args()
    try:
        if args.command == 'initialize':
            initialize()
        elif args.command == 'follow':
            require(args.file is not None, 'Capture filename required')
            follow(args.file)
        else:
            cleanup()
    except Exception as error:
        # Neither psycopg nor Docker error text may echo the query being inspected.
        detail = str(error) if isinstance(error, PrivateLogError) else type(error).__name__
        print('CI private PostgreSQL log operation failed: ' + detail, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
