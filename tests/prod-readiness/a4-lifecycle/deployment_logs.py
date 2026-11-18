#!/usr/bin/env python3
"""Exercise the shipped PostgreSQL and Nginx log policy with real local services.

Native: --postgres-bin /path/to/pg16/bin --nginx /path/to/nginx
CI: --component postgres --docker-postgres, and --component nginx inside the Nginx test image.
Only enumerated assertions/settings are retained. Raw process logs and synthetic credentials are removed.
"""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'target/test-artifacts/a4-deployment-logs'
SETTINGS = {
    'log_statement': 'none',
    'log_min_duration_statement': '-1',
    'log_parameter_max_length': '0',
    'log_parameter_max_length_on_error': '0',
    'log_error_verbosity': 'terse',
    'log_min_error_statement': 'panic',
    'log_min_messages': 'panic',
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def postgres_command():
    """Read this repository's deliberately simple YAML scalar-list command, never execute a shell."""
    lines = (ROOT / 'deploy/package/compose.yml').read_text().splitlines()
    start = lines.index('  postgres:')
    service = []
    for line in lines[start + 1:]:
        if re.match(r'^  [A-Za-z_][A-Za-z_0-9-]*:', line):
            break
        service.append(line)
    command_at = service.index('    command:')
    command = []
    for line in service[command_at + 1:]:
        if line.strip().startswith('#') or not line.strip():
            continue
        if not line.startswith('      - '):
            break
        words = shlex.split(line[len('      - '):], comments=True)
        require(len(words) == 1, 'PostgreSQL compose command must remain an explicit scalar list')
        command.append(words[0])
    require(command and command[0] == 'postgres' and len(command) % 2 == 1,
            'Unexpected PostgreSQL compose command')
    options = {}
    for flag, value in zip(command[1::2], command[2::2]):
        require(flag == '-c' and '=' in value, 'Unexpected PostgreSQL command flag')
        key, configured = value.split('=', 1)
        require(key not in options, 'Duplicate PostgreSQL setting')
        options[key] = configured
    require(all(options.get(key) == value for key, value in SETTINGS.items()),
            'Shipped PostgreSQL command does not enforce the safe log policy')
    return command


@contextmanager
def postgres(args, work, command):
    """Both modes create a new disposable PG16 instance; existing databases are never touched."""
    container = None
    process = None
    output = None
    connection = None
    stopped = False
    log = work / 'postgres.log'
    try:
        if args.docker_postgres:
            created = subprocess.run(['docker', 'run', '--detach', '--publish', '127.0.0.1::5432',
                                      '--env', 'POSTGRES_USER=scm_log_gate', '--env', 'POSTGRES_DB=scm_logs_test',
                                      '--env', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:16', *command],
                                     check=True, capture_output=True, text=True)
            container = created.stdout.strip()
            require(re.fullmatch('[a-f0-9]{64}', container), 'Unexpected disposable container ID')
            published = subprocess.run(['docker', 'port', container, '5432/tcp'], check=True, capture_output=True, text=True).stdout.strip()
            require(re.fullmatch(r'127\.0\.0\.1:[0-9]+', published), 'PostgreSQL test port must be loopback')
            port = int(published.rsplit(':', 1)[1])
        else:
            directory = Path(args.postgres_bin or os.environ.get('SCM_TEST_PG_BIN', ''))
            binary = directory / 'postgres'
            initdb = directory / 'initdb'
            require(binary.is_file() and initdb.is_file(), 'Provide --postgres-bin pointing to PostgreSQL 16 binaries')
            subprocess.run([str(initdb), '-D', str(work / 'data'), '-U', 'scm_log_gate', '--auth=trust',
                            '--encoding=UTF8', '--locale=C.UTF-8'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            port = free_port()
            output = log.open('w')
            process = subprocess.Popen([str(binary), '-D', str(work / 'data'), '-p', str(port), '-h', '127.0.0.1',
                                        '-c', 'unix_socket_directories=', *command[1:]], stdout=output, stderr=subprocess.STDOUT)
        import psycopg
        deadline = time.monotonic() + 30
        while True:
            try:
                connection = psycopg.connect(host='127.0.0.1', port=port, user='scm_log_gate', dbname='postgres',
                                             autocommit=True, connect_timeout=1)
                break
            except psycopg.OperationalError:
                require(process is None or process.poll() is None, 'Disposable PostgreSQL exited during startup')
                require(time.monotonic() < deadline, 'Disposable PostgreSQL startup timed out')
                time.sleep(.1)
        if connection.execute("SELECT 1 FROM pg_database WHERE datname='scm_logs_test'").fetchone() is None:
            connection.execute('CREATE DATABASE scm_logs_test')
        connection.close()
        connection = psycopg.connect(host='127.0.0.1', port=port, user='scm_log_gate', dbname='scm_logs_test',
                                     autocommit=True, connect_timeout=1)

        def stop_and_read_logs():
            nonlocal stopped
            # A missing canary is meaningful only after every backend and log producer has exited.
            connection.close()
            if container:
                subprocess.run(['docker', 'stop', '--time', '10', container], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                stopped = True
                state = subprocess.run(['docker', 'inspect', '--format', '{{.State.Running}} {{.State.ExitCode}}', container],
                                       check=True, capture_output=True, text=True).stdout.strip()
                require(state == 'false 0', 'Disposable PostgreSQL did not exit gracefully before log collection')
                captured = subprocess.run(['docker', 'logs', container], check=True, capture_output=True, text=True)
                return captured.stdout + captured.stderr
            require(process.poll() is None, 'Disposable PostgreSQL exited before the final shutdown barrier')
            process.terminate()
            process.wait(timeout=10)
            require(process.returncode == 0, 'Disposable PostgreSQL did not exit gracefully before log collection')
            output.flush()
            output.close()
            stopped = True
            return log.read_text(errors='replace')
        try:
            yield connection, stop_and_read_logs
        finally:
            connection.close()
    finally:
        if connection:
            connection.close()
        if process and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        if output:
            output.close()
        if container:
            if not stopped:
                subprocess.run(['docker', 'stop', '--time', '10', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            subprocess.run(['docker', 'rm', '--force', '--volumes', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


def check_postgres(args, work):
    import psycopg
    from psycopg import sql
    command = postgres_command()
    marker = 'A4-Sensitive-Bank-Token-' + secrets.token_hex(16)
    sql_marker = 'a4_private_statement_' + secrets.token_hex(10)
    with postgres(args, work, command) as (db, stop_and_read_logs):
        version = db.execute('SHOW server_version_num').fetchone()[0]
        server_version = db.execute('SHOW server_version').fetchone()[0]
        require(int(version) // 10_000 == 16, 'The deployment gate must use actual PostgreSQL 16')
        settings = {name: db.execute(sql.SQL('SHOW {}').format(sql.Identifier(name))).fetchone()[0] for name in SETTINGS}
        require(settings == SETTINGS, 'The running PostgreSQL instance did not apply the shipped command')
        db.execute('CREATE TABLE a4_log_canary(id integer PRIMARY KEY, bank_account text UNIQUE)')
        db.execute('INSERT INTO a4_log_canary VALUES(1,%s)', (marker,), prepare=True)
        faults = {}
        try:
            db.execute('INSERT INTO a4_log_canary VALUES(2,%s)', (marker,), prepare=True)
        except psycopg.errors.UniqueViolation as failure:
            faults['unique_constraint'] = failure.sqlstate == '23505' and marker in (failure.diag.message_detail or '')
        try:
            db.execute('SELECT %s::integer', (marker,), prepare=True)
        except psycopg.errors.InvalidTextRepresentation as failure:
            faults['invalid_type_primary_message'] = failure.sqlstate == '22P02' and marker in (failure.diag.message_primary or '')
        require(len(faults) == 2 and all(faults.values()), 'The real SQL fault canaries did not reach PostgreSQL diagnostics')
        began = time.monotonic()
        db.execute(sql.SQL('SELECT pg_sleep(1.1), {}, {}').format(sql.Literal(marker), sql.Literal(sql_marker))).fetchone()
        require(time.monotonic() - began >= 1, 'Slow SQL canary did not execute for at least one second')
        raw = stop_and_read_logs()
        require(marker not in raw and sql_marker not in raw and 'a4_log_canary' not in raw,
                'PostgreSQL raw statement or sensitive diagnostic value entered its logs')
        return {'passed': True, 'serverMajor': 16, 'serverVersion': server_version, 'settings': settings, 'faults': faults,
                'slowLiteralSqlAbsent': True, 'boundParameterAndErrorValuesAbsent': True,
                'logReadAfterGracefulExit': True}


class Upstream(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith('/api/drop'):
            self.close_connection = True
            self.connection.shutdown(socket.SHUT_RDWR)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('X-Request-Id', self.headers.get('X-Request-Id', ''))
        self.end_headers()
        self.wfile.write(b'{"code":0,"data":null}')

    def log_message(self, *_):
        pass


def request(port, path, trace, tls=None):
    connection = (http.client.HTTPSConnection('127.0.0.1', port, context=tls, timeout=5) if tls
                  else http.client.HTTPConnection('127.0.0.1', port, timeout=5))
    try:
        connection.request('GET', path, headers={'X-Request-Id': trace})
        response = connection.getresponse()
        body = response.read()
        return response.status, dict((key.lower(), value) for key, value in response.getheaders()), body
    finally:
        connection.close()


def check_nginx(args, work):
    binary = args.nginx or os.environ.get('SCM_TEST_NGINX') or shutil.which('nginx')
    require(binary, 'Provide --nginx or SCM_TEST_NGINX')
    version = subprocess.run([binary, '-v'], check=True, capture_output=True, text=True)
    nginx_version = re.search(r'nginx/([0-9.]+)', version.stdout + version.stderr)
    require(nginx_version, 'Could not identify the actual Nginx binary')
    config = work / 'conf'
    shutil.copytree(ROOT / 'deploy/package/nginx', config)
    web = work / 'web'; web.mkdir(); (web / 'index.html').write_text('<title>SCM log gate</title>')
    uploads = work / 'uploads/2026/11/18'; uploads.mkdir(parents=True)
    (uploads / 'example.pdf').write_bytes(b'%PDF-1.7 synthetic log canary')
    outside = work / 'outside.txt'; outside.write_text('synthetic')
    (uploads / 'linked.pdf').symlink_to(outside)
    secret = secrets.token_urlsafe(32)
    sentinel = 'A4-Query-Credential-' + secrets.token_hex(16)
    http_port, tls_port, redirect_port = [free_port() for _ in range(3)]
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    site = (config / 'site.conf').read_text().replace('/etc/nginx/scm', str(config))
    site = site.replace('/usr/share/nginx/html', str(web)).replace('/data/uploads', str(work / 'uploads'))
    site = site.replace('http://app:8080', f'http://127.0.0.1:{upstream.server_port}')
    (config / 'site.conf').write_text(site)
    cert, private_key = work / 'cert.pem', work / 'key.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost',
                    '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost', '-keyout', str(private_key), '-out', str(cert)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    private_key.chmod(0o600)

    def render(name):
        return (config / name).read_text().replace('/etc/nginx/scm', str(config)).replace('${SCM_FILE_SIGN_KEY}', secret)
    http = render('http.conf').replace('${SCM_PUBLIC_SCHEME}', '').replace('listen 80 ', f'listen 127.0.0.1:{http_port} ')
    tls = render('https.conf').replace('${SCM_HTTPS_PORT}', str(tls_port)).replace('listen 80 ', f'listen 127.0.0.1:{redirect_port} ')
    tls = tls.replace('listen 443 ', f'listen 127.0.0.1:{tls_port} ').replace('/etc/nginx/certs/fullchain.pem', str(cert)).replace('/etc/nginx/certs/privkey.pem', str(private_key))
    tls = tls.replace(f'include {config}/gateway-maps.conf;', '')
    for name in ('body', 'proxy', 'fastcgi', 'uwsgi', 'scgi'): (work / name).mkdir()
    master = work / 'nginx.conf'
    master.write_text(f'''daemon off;
master_process off;
pid {work}/nginx.pid;
error_log {work}/master-error.log warn;
events {{ worker_connections 128; }}
http {{
 log_format deliberately_unsafe '$request';
 access_log {work}/inherited-unsafe.log deliberately_unsafe;
 client_body_temp_path {work}/body;
 proxy_temp_path {work}/proxy;
 fastcgi_temp_path {work}/fastcgi;
 uwsgi_temp_path {work}/uwsgi;
 scgi_temp_path {work}/scgi;
 {http}
 {tls}
}}
''')
    validation = subprocess.run([binary, '-t', '-p', str(work), '-c', str(master)], capture_output=True)
    require(validation.returncode == 0, 'Shipped Nginx configuration did not validate')
    checks = {}
    traces = {}
    signatures = []
    process = None
    with (work / 'access.log').open('w') as access, (work / 'stderr.log').open('w') as errors:
        try:
            process = subprocess.Popen([binary, '-p', str(work), '-c', str(master)], stdout=access, stderr=errors)
            deadline = time.monotonic() + 5
            while True:
                try:
                    require(request(http_port, '/', 'a4-startup-probe')[0] == 200, 'Nginx did not serve its real root')
                    break
                except OSError:
                    require(process.poll() is None and time.monotonic() < deadline, 'Nginx startup failed')
                    time.sleep(.05)

            def signed(name):
                path = '/files/2026/11/18/' + name
                expires = str(int(time.time()) + 60)
                signature = base64.urlsafe_b64encode(hashlib.md5((expires + path + ' ' + secret).encode()).digest()).decode().rstrip('=')
                signatures.append(signature)
                return f'{path}?e={expires}&s={signature}&token={sentinel}'

            def check(name, port, path, expected_status, tls_context=None):
                trace = 'a4-log-' + secrets.token_hex(12)
                status, headers, _ = request(port, path, trace, tls_context)
                require(status == expected_status, 'Nginx response did not match controlled case: ' + name)
                traces[trace] = expected_status
                checks[name] = True
                return headers

            check('signed_file_200', http_port, signed('example.pdf'), 200)
            check('signed_missing_file_404', http_port, signed('missing.pdf'), 404)
            check('invalid_signature_403', http_port, '/files/2026/11/18/example.pdf?s=' + sentinel, 403)
            check('signed_symlink_403', http_port, signed('linked.pdf'), 403)
            check('upstream_disconnect_503', http_port, '/api/drop?appSecret=' + sentinel, 503)
            context = ssl.create_default_context(cafile=str(cert))
            check('tls_signed_file_200', tls_port, signed('example.pdf'), 200, context)
            check('https_redirect_301', redirect_port, '/files/2026/11/18/example.pdf?s=' + sentinel, 301)
            status, headers, _ = request(http_port, '/api/ok?token=' + sentinel, 'invalid request id ' + sentinel)
            require(status == 200 and re.fullmatch(r'[a-f0-9]{32}', headers.get('x-request-id', '')),
                    'Untrusted request ID was not normalized before proxying')
            traces[headers['x-request-id']] = 200
            checks['request_id_normalized'] = True
        finally:
            if process:
                process.terminate(); process.wait(timeout=5)
            upstream.shutdown(); upstream.server_close()
    log_files = [work / 'access.log', work / 'stderr.log', work / 'master-error.log', work / 'inherited-unsafe.log']
    raw = '\n'.join(path.read_text(errors='replace') for path in log_files if path.exists())
    require(all(value not in raw for value in [secret, sentinel, *signatures]), 'Nginx leaked a credential or signed query string')
    require(not (work / 'inherited-unsafe.log').read_text(), 'The image combined access log was not overridden at server scope')
    records = [json.loads(line) for line in (work / 'access.log').read_text().splitlines() if line.strip()]
    keyed = {record['requestId']: record for record in records}
    for trace, status in traces.items():
        record = keyed.get(trace)
        require(record and record.get('status') == status and record.get('method') == 'GET', 'Safe access log lost method/status/requestId')
        require(record.get('path', '').startswith('/') and '?' not in record['path'] and '&s=' not in record['path'], 'Safe access log path contains arguments')
        require(isinstance(record.get('requestTime'), (float, int)), 'Safe access log lacks request duration')
    require(all(set(record) == {'time', 'method', 'path', 'status', 'requestTime', 'requestId'} for record in records),
            'Unexpected fields entered the safe access log')
    return {'passed': True, 'nginxVersion': nginx_version.group(1), 'checks': checks, 'recordCount': len(records),
            'rawQueriesAndCredentialsAbsent': True, 'masterCombinedLogOverridden': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=['all', 'postgres', 'nginx'], default='all')
    parser.add_argument('--postgres-bin')
    parser.add_argument('--docker-postgres', action='store_true')
    parser.add_argument('--nginx')
    args = parser.parse_args()
    require(not (args.postgres_bin and args.docker_postgres), 'Choose native or disposable Docker PostgreSQL')
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    for component in (['postgres', 'nginx'] if args.component == 'all' else [args.component]):
        artifact = ARTIFACTS / (component + '-results.json')
        artifact.unlink(missing_ok=True)
        try:
            with tempfile.TemporaryDirectory(prefix='a4-deployment-logs-') as directory:
                result = (check_postgres if component == 'postgres' else check_nginx)(args, Path(directory))
        except Exception as failure:
            artifact.write_text(json.dumps({'passed': False, 'errorType': type(failure).__name__}) + '\n')
            raise
        artifact.write_text(json.dumps(result, indent=2) + '\n')
        print('PASS deployment log gate: ' + component, flush=True)


if __name__ == '__main__':
    try:
        main()
    except AssertionError as failure:
        # Assertion messages are controlled labels above, never response/SQL values.
        print('FAIL deployment log gate: ' + str(failure), flush=True)
        raise SystemExit(1) from None
    except Exception as failure:
        # SQL and subprocess exception messages may include diagnostic values; retain only the type.
        print('FAIL deployment log gate: ' + type(failure).__name__, flush=True)
        raise SystemExit(1) from None
