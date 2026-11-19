#!/usr/bin/env python3
"""T12 and SIGTERM gate against the production JAR and disposable PostgreSQL/Redis.

Requires explicit SCM_PROD_READINESS=1, loopback test services, and Redis authentication.
Only assertion names, bounded timings and sanitized application logs are retained.
"""
import base64
import concurrent.futures
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

import psycopg
from psycopg import sql

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'target/test-artifacts/a4'


def require(value, message):
    if not value:
        raise AssertionError(message)


def request(port, path, body=None, token=None, method='GET', trace=None, timeout=5):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if trace:
        headers['X-Request-Id'] = trace
    if body is not None and method == 'POST':
        headers['Idempotency-Key'] = str(uuid.uuid4())
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', headers=headers,
                                 data=None if body is None else json.dumps(body).encode(), method=method)
    try:
        response = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        return response.status, json.load(response), dict(response.headers)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def eventually(predicate, timeout, message, interval=.05):
    deadline = time.monotonic() + timeout
    while not predicate():
        require(time.monotonic() < deadline, message)
        time.sleep(interval)


def active_queries(db, database):
    return db.execute("""SELECT pid,wait_event_type,wait_event,query FROM pg_stat_activity
        WHERE datname=%s AND pid<>pg_backend_pid() AND state='active'""", (database,)).fetchall()


def closed_in_order(logfile, completed=True):
    log = logfile.read_text()
    stages = ['shutdown_refusing_traffic', 'shutdown_drain_complete completed=' + str(completed).lower(),
              'outbox_stopped remaining=0', 'Shutdown initiated...', 'Shutdown completed.']
    positions = [log.find(stage) for stage in stages]
    require(all(position >= 0 for position in positions) and positions == sorted(positions),
            'actual drain, executor termination and connection-pool closure are out of order')
    stopped = next((line for line in log.splitlines() if 'outbox_stopped remaining=0' in line), '')
    require(all(value in stopped for value in ['controls=0', 'workersTerminated=true', 'controlsTerminated=true']),
            'Outbox reported stopped without confirming both executors actually terminated')


def export_fixture(db, rows):
    """Synthetic rows feed the actual exporter; the test never constructs or replaces its XLSX."""
    code = 'A4' + secrets.token_hex(8)
    parent = db.execute("INSERT INTO bas_material_category(name,level) VALUES(%s,1) RETURNING id", (code,)).fetchone()[0]
    category = db.execute("""INSERT INTO bas_material_category(code,name,level,parent_id)
        SELECT chr(65+a)||chr(65+b),%s,2,%s FROM generate_series(0,25) a,generate_series(0,25) b
        WHERE NOT EXISTS (SELECT 1 FROM bas_material_category WHERE code=chr(65+a)||chr(65+b))
        ORDER BY a,b LIMIT 1 RETURNING id""", (code, parent)).fetchone()[0]
    material = db.execute("""INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit)
        VALUES(%s,'A4 stream fixture',%s,'FABRIC','PIECE','PIECE') RETURNING id""", (code, category)).fetchone()[0]
    sku = db.execute("INSERT INTO bas_material_sku(material_id,code) VALUES(%s,%s) RETURNING id", (material, code)).fetchone()[0]
    warehouse = db.execute("INSERT INTO bas_warehouse(code,name,type) VALUES(%s,'A4 stream warehouse','MATERIAL') RETURNING id", (code,)).fetchone()[0]
    db.execute("""INSERT INTO inv_stock_log(change_type,doc_no,biz_date,warehouse_id,material_sku_id,
        change_qty,balance_qty,dye_lot,unit_price,amount,stock_unit,currency,valuation_status)
        SELECT 'OTHER_IN',md5(n::text),current_date,%s,%s,1,n,md5((n+999)::text),12.34,12.34,'PIECE','CNY','RECORDED'
        FROM generate_series(1,%s) AS n""", (warehouse, sku, rows))
    return warehouse


def hash_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(128 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def blocked_download(process, logfile):
    """Inspect the real JVM rather than assuming an unread response is still in the controller."""
    # SIGQUIT is HotSpot's diagnostic dump signal. Unlike attach/jcmd it also works
    # when the runner PID namespace differs from its mounted /proc namespace.
    offset = logfile.stat().st_size
    process.send_signal(signal.SIGQUIT)
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        with logfile.open('rb') as source:
            source.seek(offset); dump = source.read(1024 * 1024).decode(errors='replace')
        if any('ExportJobController.download' in thread
               and ('NioSocketWrapper' in thread or 'SocketDispatcher.write' in thread)
               for thread in dump.split('\n\n')):
            return True
        require(process.poll() is None, 'production JVM exited during its diagnostic thread snapshot')
        if 'JNI global refs:' in dump:
            return False
        time.sleep(.02)
    return False


def main():
    require(os.environ.get('SCM_PROD_READINESS') == '1', 'explicit disposable-test authorization required')
    host = os.environ.get('SCM_TEST_PG_HOST', '127.0.0.1')
    redis_host = os.environ.get('SCM_TEST_REDIS_HOST', '127.0.0.1')
    require(host in {'localhost', '127.0.0.1', '::1'} and redis_host in {'localhost', '127.0.0.1', '::1'}, 'test services must be loopback')
    redis_password = os.environ.get('SCM_TEST_REDIS_PASSWORD', '')
    require(bool(redis_password), 'SCM_TEST_REDIS_PASSWORD must authenticate the test Redis')
    pg = dict(host=host, port=int(os.environ.get('SCM_TEST_PG_PORT', '5433')),
              user=os.environ.get('SCM_TEST_PG_USER', 'scm'), password=os.environ.get('SCM_TEST_PG_PASSWORD', ''))
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    jar = Path(os.environ.get('SCM_TEST_JAR', ROOT / 'target/scm-0.0.1-SNAPSHOT.jar')).resolve()
    require(java and jar.is_file(), 'production JAR and Java 21 are required')
    with zipfile.ZipFile(jar) as archive:
        app_entries = [name for name in archive.namelist() if name.startswith('BOOT-INF/classes/')]
        require(not any('/test/' in name or '/mock/' in name.lower() or 'application-it.' in name or 'Fixture' in name for name in app_entries), 'test classes/resources entered production JAR')
    database = 'a4_' + secrets.token_hex(8) + '_test'
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    metrics = {'passed': False, 'jar_test_resources_absent': True, 'checks': []}
    (ARTIFACTS / 'process-results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))

    def record(result):
        metrics['checks'].append(result)
        (ARTIFACTS / 'process-results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
        print('PASS ' + result['name'], flush=True)
    logs = []
    processes = []
    runtime_secrets = []
    work = Path(tempfile.mkdtemp(prefix='a4-process-'))
    admin = psycopg.connect(**pg, dbname='postgres', autocommit=True)
    admin.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(database)))
    try:
        password = secrets.token_urlsafe(24)
        key = base64.b64encode(secrets.token_bytes(48)).decode()
        file_key = secrets.token_hex(24)
        db_password = pg['password'] or secrets.token_urlsafe(18)  # CI trust authentication still receives a nonempty ephemeral value.
        runtime_secrets.extend([password, key, file_key, db_password, redis_password])
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(('SPRING_DATASOURCE_', 'SPRING_DATA_REDIS_', 'LINGMAO_TENANT_'))}
        env.update(SPRING_PROFILES_ACTIVE='prod', DB_URL=f'jdbc:postgresql://{host}:{pg["port"]}/{database}',
                   DB_USER=pg['user'], DB_PASSWORD=db_password, REDIS_HOST=redis_host,
                   REDIS_PORT=os.environ.get('SCM_TEST_REDIS_PORT', '6380'), REDIS_PASSWORD=redis_password, REDIS_DB='11',
                   JWT_SECRET=key, FILE_SIGN_KEY=file_key, INIT_ADMIN_PASSWORD=password,
                   UPLOAD_DIR=str(work / 'uploads'), EXPORT_DIR=str(work / 'exports'),
                   LINGMAO_TENANT_ID='a4-' + secrets.token_hex(8), LINGMAO_DB_LOCK_TIMEOUT_MS='10000')

        def start(name, overrides=None, ready=False, arguments=()):
            port = free_port()
            logfile = work / (name + '.log')
            logfile.touch(mode=0o600)
            output = logfile.open('w')
            logs.append(output)
            case_env = {**env, 'LOGGING_FILE_NAME': str(work / (name + '-file.log')), **(overrides or {})}
            process = subprocess.Popen([java, '-Xmx512m', '-XX:ActiveProcessorCount=4', '-jar', str(jar), f'--server.port={port}',
                                        f'--logging.logback.rollingpolicy.file-name-pattern={work}/rolling-%d{{yyyy-MM-dd}}.%i.log', *arguments],
                                       env=case_env, stdout=output, stderr=subprocess.STDOUT)
            processes.append(process)
            if ready:
                deadline = time.monotonic() + 90
                while True:
                    require(process.poll() is None, name + ' exited before readiness')
                    try:
                        if request(port, '/health/ready')[0] == 200:
                            break
                    except (OSError, ValueError):
                        pass
                    require(time.monotonic() < deadline, name + ' readiness timeout')
                    time.sleep(.15)
            return process, port, logfile

        bad_configs = {
            'weak-jwt': {'JWT_SECRET': base64.b64encode(b'dev-secret' * 8).decode()},
            'missing-jwt': {'JWT_SECRET': ''},
            'weak-file': {'FILE_SIGN_KEY': 'abcdefgh' * 4},
            'missing-file': {'FILE_SIGN_KEY': ''},
            'weak-admin': {'INIT_ADMIN_PASSWORD': 'dev-secret-123456'},
            'debug': {'DEBUG': 'true'},
            'nested-debug': {'LOGGING_LEVEL_COM_LINGMAO_SCM': 'DEBUG'},
            'framework-trace': {'LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB': 'TRACE'},
            'mixed-profile': {'SPRING_PROFILES_ACTIVE': 'prod,test'},
            'missing-db-password': {'DB_PASSWORD': ''},
            'missing-redis-password': {'REDIS_PASSWORD': ''},
        }
        for name, override in bad_configs.items():
            started = time.monotonic()
            process, _, logfile = start(name, override)
            process.wait(timeout=20)
            require(process.returncode != 0 and 'Production configuration rejected' in logfile.read_text(), name + ' did not fail fast')
            record({'name': name, 'passed': True, 'seconds': round(time.monotonic() - started, 3)})

        process, _, logfile = start('fresh-database-missing-admin', {'INIT_ADMIN_PASSWORD': ''})
        process.wait(timeout=90)
        require(process.returncode != 0 and 'ProductionAdminGuard' in logfile.read_text(), 'fresh database accepted missing bootstrap password')
        record({'name': 'fresh-database-missing-admin', 'passed': True})
        process, port, logfile = start('lifecycle', ready=True)
        status, body, headers = request(port, '/api/auth/login', {'username': 'admin', 'password': password}, method='POST')
        require(status == 200 and body['code'] == 0, 'real admin login failed')
        token = body['data']; runtime_secrets.append(token)

        # Actual database unavailability: no new connections and all existing application sessions terminated.
        admin.execute(sql.SQL('ALTER DATABASE {} ALLOW_CONNECTIONS false').format(sql.Identifier(database)))
        admin.execute('SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=%s', (database,))
        began = time.monotonic()
        require(request(port, '/health/ready', timeout=3)[0] == 503, 'ready must fail when PostgreSQL is unavailable')
        require(time.monotonic() - began < 2.5, 'readiness exceeded its bounded deadline')
        require(request(port, '/health/live')[0] == 200, 'liveness depends on database availability')
        admin.execute(sql.SQL('ALTER DATABASE {} ALLOW_CONNECTIONS true').format(sql.Identifier(database)))
        deadline = time.monotonic() + 30
        while request(port, '/health/ready')[0] != 200:
            require(time.monotonic() < deadline, 'readiness did not recover')
            time.sleep(.1)
        record({'name': 'separate-live-and-database-ready', 'passed': True})

        # The normal business API and real worker produce a large, valid private workbook.
        # It is reused by the slow-client shutdown check below, without replacing any bytes.
        export_rows = 180_000
        with psycopg.connect(**pg, dbname=database, autocommit=True) as db:
            warehouse = export_fixture(db, export_rows)
            status, body, _ = request(port, '/api/export-jobs/stock-logs', {'warehouseId': str(warehouse)}, token, 'POST')
            require(status == 202 and body['code'] == 0, 'real large export creation failed')
            export_job = body['data']['id']

            def exported():
                state, rows = db.execute('SELECT o.status,j.row_count FROM sys_export_job j JOIN sys_outbox o ON o.id=j.outbox_id WHERE j.id=%s', (export_job,)).fetchone()
                require(state not in {'DEAD_LETTER', 'CANCELLED', 'EXPIRED'}, 'real large export failed')
                return state == 'SUCCEEDED' and rows == export_rows

            eventually(exported, 240, 'real large export did not finish')
            file_token, byte_size = db.execute('SELECT file_token,byte_size FROM sys_export_job WHERE id=%s', (export_job,)).fetchone()
            export_file = work / 'exports' / f'{env["LINGMAO_TENANT_ID"]}-{export_job}-{file_token}.xlsx'
            require(export_file.is_file() and export_file.stat().st_size == byte_size and byte_size >= 8 * 1024 * 1024,
                    'export fixture is too small to establish real socket backpressure')
            require(zipfile.is_zipfile(export_file), 'actual export is not an XLSX archive')
            expected_hash = hash_file(export_file)
            print(f'Prepared actual export: rows={export_rows} bytes={byte_size}', flush=True)

        code = secrets.token_hex(3)
        _, created, _ = request(port, '/api/companies', {'code': code, 'name': 'A4-before', 'shortName': 'A4-before'}, token, 'POST')
        require(created['code'] == 0, 'real company creation failed')
        company = created['data']
        trace = 'a4-drain-' + str(uuid.uuid4())
        with psycopg.connect(**pg, dbname=database, autocommit=True) as db:
            # A test-owned DB trigger stalls the real worker acknowledgement; no production test route is installed.
            db.execute("""CREATE FUNCTION a4_slow_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                IF NEW.dedupe_key='a4-drain-live' AND NEW.status='SUCCEEDED' THEN PERFORM pg_sleep(2.5); END IF;
                RETURN NEW; END $$""")
            db.execute('CREATE TRIGGER a4_slow_ack BEFORE UPDATE ON sys_outbox FOR EACH ROW EXECUTE FUNCTION a4_slow_ack()')
            cache_key = 'scm:' + env['LINGMAO_TENANT_ID'] + ':company:shared:dict-v4:YTRnYXRl'
            job = db.execute("INSERT INTO sys_outbox(tenant_key,kind,dedupe_key,payload,request_id) VALUES(%s,'CACHE_INVALIDATE','a4-drain-live',%s::jsonb,%s) RETURNING id",
                             (env['LINGMAO_TENANT_ID'], json.dumps(cache_key), trace)).fetchone()[0]
            lock = psycopg.connect(**pg, dbname=database)
            try:
                lock.execute('SELECT id FROM bas_company WHERE id=%s FOR UPDATE', (company,))
                with concurrent.futures.ThreadPoolExecutor(1) as pool:
                    in_flight = pool.submit(request, port, '/api/companies/' + company,
                                            {'code': code, 'name': 'A4-after', 'shortName': 'A4-after'}, token, 'PUT', trace, 20)
                    deadline = time.monotonic() + 10
                    while True:
                        activity = db.execute("SELECT wait_event_type,wait_event,query FROM pg_stat_activity WHERE datname=%s AND pid<>pg_backend_pid()", (database,)).fetchall()
                        http_waiting = any(kind == 'Lock' and 'bas_company' in query and 'UPDATE' in query for kind, event, query in activity)
                        worker_waiting = any(event == 'PgSleep' and 'sys_outbox' in query for kind, event, query in activity)
                        if http_waiting and worker_waiting:
                            break
                        require(time.monotonic() < deadline, 'real HTTP and Outbox work were not both in flight')
                        time.sleep(.03)
                    process.send_signal(signal.SIGTERM)
                    deadline = time.monotonic() + 1.5
                    while request(port, '/health/ready')[0] != 503:
                        require(time.monotonic() < deadline, 'SIGTERM did not promptly refuse readiness')
                        time.sleep(.02)
                    require(request(port, '/health/live')[0] == 200, 'live must remain available while draining')
                    refused = request(port, '/api/companies', {'code': 'STOP', 'name': 'Must not exist', 'shortName': 'STOP'}, token, 'POST')
                    require(refused[0] == 503 and refused[1]['code'] == 503, 'new business request admitted during drain')
                    queued = db.execute("INSERT INTO sys_outbox(tenant_key,kind,dedupe_key,payload,request_id) VALUES(%s,'CACHE_INVALIDATE','a4-after-term',%s::jsonb,%s) RETURNING id",
                                        (env['LINGMAO_TENANT_ID'], json.dumps(cache_key), trace)).fetchone()[0]
                    time.sleep(1.05)
                    require(db.execute('SELECT status FROM sys_outbox WHERE id=%s', (queued,)).fetchone()[0] == 'PENDING', 'Outbox admitted new work after SIGTERM')
                    lock.commit()
                    done = in_flight.result(timeout=15)
                    require(done[0] == 200 and done[1]['code'] == 0 and done[2].get('X-Request-Id') == trace, 'in-flight HTTP transaction did not finish')
                process.wait(timeout=40)
                require(db.execute('SELECT name FROM bas_company WHERE id=%s', (company,)).fetchone()[0] == 'A4-after', 'in-flight mutation was lost')
                require(db.execute('SELECT status FROM sys_outbox WHERE id=%s', (job,)).fetchone()[0] == 'SUCCEEDED', 'in-flight Outbox acknowledgement was interrupted')
                require(db.execute('SELECT count(*) FROM bas_company WHERE code=%s', ('STOP',)).fetchone()[0] == 0, 'refused mutation reached database')
                audit = db.execute("SELECT diff,actor_user_id,client_ip,request_id FROM sys_data_log WHERE table_name='bas_company' AND row_id=%s AND action='UPDATE'", (company,)).fetchone()
                require(audit and audit[0]['name'] == {'old': 'A4-before', 'new': 'A4-after'} and audit[1] is not None and audit[2] == '127.0.0.1' and audit[3] == trace, 'drained transaction audit is incomplete')
            finally:
                lock.close()
        closed_in_order(logfile)
        log = logfile.read_text()
        require('outbox_completed' in log and trace in log, 'worker request correlation missing')
        record({'name': 'real-SIGTERM-inflight-HTTP-Outbox-drain', 'passed': True, 'requestId': trace})

        # Hold the actual session-cleanup DELETE in PostgreSQL, then hold an actual
        # download in Tomcat socket output. Both must finish before pool destruction.
        housekeeping_lock = secrets.randbits(62)
        stream_trace = 'a4-stream-' + str(uuid.uuid4())
        with psycopg.connect(**pg, dbname=database, autocommit=True) as db, \
                psycopg.connect(**pg, dbname=database, autocommit=True) as blocker:
            db.execute("""INSERT INTO sys_login_session(tenant_id,jti,user_id,session_version,expires_at)
                SELECT %s,'a4-housekeeping-drain',id,session_version,clock_timestamp()-interval '1 hour'
                FROM sys_user WHERE username='admin'""", (env['LINGMAO_TENANT_ID'],))
            db.execute('DELETE FROM sys_job_lease WHERE name=%s', (env['LINGMAO_TENANT_ID'] + ':session-cleanup',))
            db.execute(f"""CREATE FUNCTION a4_hold_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                IF OLD.jti='a4-housekeeping-drain' THEN PERFORM pg_advisory_xact_lock({housekeeping_lock}); END IF;
                RETURN OLD; END $$""")
            db.execute('CREATE TRIGGER a4_hold_cleanup BEFORE DELETE ON sys_login_session FOR EACH ROW EXECUTE FUNCTION a4_hold_cleanup()')
            blocker.execute('SELECT pg_advisory_lock(%s)', (housekeeping_lock,))
            process, port, logfile = start('stream-and-housekeeping', ready=True)
            try:
                eventually(lambda: any(kind == 'Lock' and 'DELETE FROM sys_login_session' in query
                                      for _, kind, _, query in active_queries(db, database)),
                           35, 'actual housekeeping DELETE did not enter its PostgreSQL wait')
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as client:
                    client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 64 * 1024)
                    client.settimeout(20)
                    client.connect(('127.0.0.1', port))
                    client.sendall((f'GET /api/export-jobs/{export_job}/file HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n'
                                    f'Authorization: Bearer {token}\r\nX-Request-Id: {stream_trace}\r\nConnection: close\r\n\r\n').encode())
                    response = http.client.HTTPResponse(client)
                    response.begin()
                    require(response.status == 200 and response.getheader('X-Request-Id') == stream_trace,
                            'real large-file download was not authorized or correlated')
                    eventually(lambda: blocked_download(process, logfile), 4,
                               'unread XLSX did not leave an actual servlet thread blocked in socket output', interval=.1)
                    process.send_signal(signal.SIGTERM)
                    eventually(lambda: request(port, '/health/ready')[0] == 503, 1.5, 'streaming SIGTERM did not refuse readiness')
                    require(request(port, '/health/live')[0] == 200, 'live failed while stream and housekeeping were draining')
                    time.sleep(.5)
                    require(process.poll() is None and 'Shutdown initiated...' not in logfile.read_text(),
                            'connection pool closed while an actual housekeeping query and stream were still active')
                    require(any(kind == 'Lock' and 'DELETE FROM sys_login_session' in query
                                for _, kind, _, query in active_queries(db, database)), 'housekeeping was interrupted before its drain window')
                    pool_pids = [row[0] for row in db.execute('SELECT pid FROM pg_stat_activity WHERE datname=%s AND pid NOT IN (%s,%s)',
                                (database, db.info.backend_pid, blocker.info.backend_pid)).fetchall()]
                    require(bool(pool_pids), 'no real application JDBC sessions were observed during drain')
                    blocker.execute('SELECT pg_advisory_unlock(%s)', (housekeeping_lock,))
                    eventually(lambda: db.execute("SELECT count(*) FROM sys_login_session WHERE jti='a4-housekeeping-drain'").fetchone()[0] == 0,
                               4, 'in-flight housekeeping transaction did not finish')
                    require(not any('DELETE FROM sys_login_session' in query for _, _, _, query in active_queries(db, database)),
                            'housekeeping JDBC was still executing after its completed mutation')
                    require(process.poll() is None and 'Shutdown initiated...' not in logfile.read_text() and blocked_download(process, logfile),
                            'database or process closed while the download still needed to drain')
                    client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)
                    actual_hash = hashlib.sha256()
                    actual_size = 0
                    while chunk := response.read(128 * 1024):
                        actual_hash.update(chunk); actual_size += len(chunk)
                    response.close()
                    require(actual_size == byte_size and actual_hash.hexdigest() == expected_hash,
                            'SIGTERM truncated or changed the actual XLSX response')
                process.wait(timeout=35)
                closed_in_order(logfile)
                require(db.execute('SELECT count(*) FROM pg_stat_activity WHERE pid=ANY(%s)', (pool_pids,)).fetchone()[0] == 0,
                        'application JDBC sessions remained after process termination')
                record({'name': 'real-SIGTERM-XLSX-and-housekeeping-drain', 'passed': True,
                                          'rows': export_rows, 'bytes': actual_size, 'sha256': expected_hash,
                                          'socketBlockedInJvm': True, 'housekeepingSqlBlocked': True})
            finally:
                blocker.execute('SELECT pg_advisory_unlock(%s)', (housekeeping_lock,))
                db.execute('DROP TRIGGER a4_hold_cleanup ON sys_login_session')
                db.execute('DROP FUNCTION a4_hold_cleanup()')

        # Time this real cache task from the first database polling statement, not
        # process startup estimates. Its acknowledgement overlaps the 10s heartbeat.
        heartbeat_lock = secrets.randbits(62)
        heartbeat_trace = 'a4-heartbeat-' + str(uuid.uuid4())
        with psycopg.connect(**pg, dbname=database, autocommit=True) as db, \
                psycopg.connect(**pg, dbname=database, autocommit=True) as blocker:
            heartbeat_job = db.execute("""INSERT INTO sys_outbox(tenant_key,kind,dedupe_key,payload,request_id,available_at)
                VALUES(%s,'CACHE_INVALIDATE','a4-heartbeat-live',%s::jsonb,%s,clock_timestamp()+interval '1 hour') RETURNING id""",
                (env['LINGMAO_TENANT_ID'], json.dumps(cache_key), heartbeat_trace)).fetchone()[0]
            db.execute('CREATE TABLE a4_first_poll(id integer PRIMARY KEY,observed_at timestamptz NOT NULL)')
            db.execute("""CREATE FUNCTION a4_observe_poll() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                IF position('lease_until<=clock_timestamp()' in current_query())>0 THEN
                    INSERT INTO a4_first_poll VALUES(1,clock_timestamp()) ON CONFLICT DO NOTHING;
                END IF; RETURN NULL; END $$""")
            db.execute('CREATE TRIGGER a4_observe_poll BEFORE UPDATE ON sys_outbox FOR EACH STATEMENT EXECUTE FUNCTION a4_observe_poll()')
            db.execute(f"""CREATE FUNCTION a4_hold_heartbeat_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                IF NEW.dedupe_key='a4-heartbeat-live' AND NEW.status='SUCCEEDED' THEN
                    PERFORM pg_advisory_xact_lock({heartbeat_lock});
                END IF; RETURN NEW; END $$""")
            db.execute('CREATE TRIGGER a4_hold_heartbeat_ack BEFORE UPDATE ON sys_outbox FOR EACH ROW EXECUTE FUNCTION a4_hold_heartbeat_ack()')
            blocker.execute('SELECT pg_advisory_lock(%s)', (heartbeat_lock,))
            process, port, logfile = start('heartbeat-drain', ready=True)
            try:
                eventually(lambda: db.execute('SELECT count(*) FROM a4_first_poll').fetchone()[0] == 1, 5,
                           'actual Outbox polling was not observed')
                db.execute("UPDATE sys_outbox SET available_at=(SELECT observed_at+interval '7 seconds' FROM a4_first_poll) WHERE id=%s", (heartbeat_job,))

                def heartbeat_and_ack_waiting():
                    queries = active_queries(db, database)
                    return (any(kind == 'Lock' and 'UPDATE sys_outbox SET lease_until=' in query for _, kind, _, query in queries)
                            and any(kind == 'Lock' and "UPDATE sys_outbox SET status='SUCCEEDED'" in query for _, kind, _, query in queries))

                eventually(heartbeat_and_ack_waiting, 15, 'real heartbeat renewal and task acknowledgement were not both in JDBC')
                process.send_signal(signal.SIGTERM)
                eventually(lambda: request(port, '/health/ready')[0] == 503, 1.5, 'heartbeat SIGTERM did not refuse readiness')
                time.sleep(.3)
                require(process.poll() is None and 'Shutdown initiated...' not in logfile.read_text() and heartbeat_and_ack_waiting(),
                        'Outbox or its pool stopped before the actual heartbeat JDBC completed')
                blocker.execute('SELECT pg_advisory_unlock(%s)', (heartbeat_lock,))
                process.wait(timeout=35)
                closed_in_order(logfile)
                require(db.execute('SELECT status FROM sys_outbox WHERE id=%s', (heartbeat_job,)).fetchone()[0] == 'SUCCEEDED',
                        'normal heartbeat drain interrupted the task acknowledgement')
                record({'name': 'real-SIGTERM-heartbeat-JDBC-drain', 'passed': True,
                                          'heartbeatSqlBlocked': True, 'bothExecutorsTerminated': True})
            finally:
                blocker.execute('SELECT pg_advisory_unlock(%s)', (heartbeat_lock,))
                db.execute('DROP TRIGGER a4_hold_heartbeat_ack ON sys_outbox')
                db.execute('DROP TRIGGER a4_observe_poll ON sys_outbox')
                db.execute('DROP FUNCTION a4_hold_heartbeat_ack()')
                db.execute('DROP FUNCTION a4_observe_poll()')
                db.execute('DROP TABLE a4_first_poll')

        # Exceed a short drain deadline in the real export worker's completion SQL.
        # A forced stop must leave its durable lease, remove the half-published file,
        # and terminate before pool destruction. The next JVM runs the real reaper.
        force_lock = secrets.randbits(62)
        force_trace = 'a4-force-' + str(uuid.uuid4())
        with psycopg.connect(**pg, dbname=database, autocommit=True) as db, \
                psycopg.connect(**pg, dbname=database, autocommit=True) as blocker:
            small_warehouse = export_fixture(db, 3)
            db.execute(f"""CREATE FUNCTION a4_hold_forced_export() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                IF NEW.request_id='{force_trace}' AND NEW.status='SUCCEEDED' THEN
                    PERFORM pg_advisory_xact_lock({force_lock});
                END IF; RETURN NEW; END $$""")
            db.execute('CREATE TRIGGER a4_hold_forced_export BEFORE UPDATE ON sys_outbox FOR EACH ROW EXECUTE FUNCTION a4_hold_forced_export()')
            blocker.execute('SELECT pg_advisory_lock(%s)', (force_lock,))
            process, port, logfile = start('forced-export-stop', ready=True, arguments=('--lingmao.shutdown.drain-timeout=1s',))
            try:
                status, body, _ = request(port, '/api/export-jobs/stock-logs', {'warehouseId': str(small_warehouse)}, token, 'POST', force_trace)
                require(status == 202 and body['code'] == 0, 'real forced-drain export creation failed')
                forced_export = body['data']['id']
                event = db.execute('SELECT outbox_id FROM sys_export_job WHERE id=%s', (forced_export,)).fetchone()[0]
                eventually(lambda: any(kind == 'Lock' and "UPDATE sys_outbox SET status='SUCCEEDED'" in query
                                      for _, kind, _, query in active_queries(db, database)), 12,
                           'real export worker did not block in its completion transaction')
                began = time.monotonic()
                process.send_signal(signal.SIGTERM)
                process.wait(timeout=25)
                elapsed = time.monotonic() - began
                require(process.returncode != -signal.SIGKILL and elapsed < 25, 'forced shutdown exceeded its bounded process deadline')
                closed_in_order(logfile, completed=False)
                state, lease, attempts = db.execute('SELECT status,lease_token,attempts FROM sys_outbox WHERE id=%s', (event,)).fetchone()
                require(state == 'PROCESSING' and lease is not None and attempts == 1,
                        'forced shutdown did not leave the recoverable durable Outbox lease')
                require(db.execute('SELECT file_token FROM sys_export_job WHERE id=%s', (forced_export,)).fetchone()[0] is None,
                        'interrupted export transaction published file metadata')
                require(not list((work / 'exports').glob(f'{env["LINGMAO_TENANT_ID"]}-{forced_export}-*')),
                        'forced export shutdown left a partial or uncommitted file')
            finally:
                blocker.execute('SELECT pg_advisory_unlock(%s)', (force_lock,))
                db.execute('DROP TRIGGER a4_hold_forced_export ON sys_outbox')
                db.execute('DROP FUNCTION a4_hold_forced_export()')
            # Advance only disposable test lease/backoff timestamps; recovery and
            # execution are still performed by the unchanged production scheduler.
            db.execute("UPDATE sys_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=%s", (event,))
            recovered, recovery_port, recovery_log = start('forced-export-recovery', ready=True)
            eventually(lambda: db.execute('SELECT status,error_code FROM sys_outbox WHERE id=%s', (event,)).fetchone() == ('RETRY_WAIT', 'LEASE_EXPIRED'),
                       8, 'next production JVM did not reap the expired lease')
            db.execute('UPDATE sys_outbox SET available_at=clock_timestamp() WHERE id=%s', (event,))
            eventually(lambda: db.execute('SELECT status,attempts FROM sys_outbox WHERE id=%s', (event,)).fetchone() == ('SUCCEEDED', 2),
                       15, 'next production JVM failed to complete the recovered export')
            result = request(recovery_port, '/api/export-jobs/' + str(forced_export), token=token)
            require(result[0] == 200 and result[1]['data']['status'] == 'SUCCEEDED' and result[1]['data']['rows'] == 3,
                    'recovered export is not available through its normal authorized endpoint')
            recovered.send_signal(signal.SIGTERM); recovered.wait(timeout=35)
            closed_in_order(recovery_log)
            record({'name': 'real-SIGTERM-deadline-and-restart-reaper', 'passed': True,
                                      'stopSeconds': round(elapsed, 3), 'leasePreserved': True,
                                      'partialFilesRemoved': True, 'recoveredAttempts': 2})

        for name, override in [('namespace-mismatch', {'LINGMAO_TENANT_ID': 'a4-other'}),
                               ('private-storage-mismatch', {'EXPORT_DIR': str(work / 'other-exports')})]:
            refused, _, _ = start(name, override)
            refused.wait(timeout=60)
            require(refused.returncode != 0, name + ' incorrectly started')
            record({'name': name, 'passed': True})
        metrics['passed'] = True
        print('PASS production JAR: bootstrap guards, database readiness, HTTP/XLSX/heartbeat/housekeeping drain and bounded shutdown with lease recovery')
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
        for handle in logs:
            handle.close()
        admin.execute(sql.SQL('ALTER DATABASE {} ALLOW_CONNECTIONS true').format(sql.Identifier(database)))
        admin.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(database)))
        admin.close()
        for logfile in work.glob('*.log'):
            # Only structured application events become artifacts. Diagnostic
            # thread snapshots and pre-logging bootstrap output stay private.
            safe_lines = []
            for line in logfile.read_text(errors='replace').splitlines():
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if isinstance(event, dict) and 'logger' in event and 'message' in event:
                    safe_lines.append(line)
            text = '\n'.join(safe_lines) + '\n'
            for secret in runtime_secrets:
                if secret:
                    text = text.replace(secret, '[REDACTED]')
            (ARTIFACTS / logfile.name).write_text(text)
        (ARTIFACTS / 'process-results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
