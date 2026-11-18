"""Authenticate the isolated CI database and Redis; never use against a deployed SCM."""
import os
from pathlib import Path
import secrets
import socket
import re
import subprocess
import time
import psycopg
from psycopg import sql

if os.environ.get('GITHUB_ACTIONS') != 'true' or not os.environ.get('GITHUB_ENV'):
    raise SystemExit('Run this helper only in the existing isolated CI workflow')
redis_secret, db_secret = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
print('::add-mask::' + redis_secret)
print('::add-mask::' + db_secret)
container = os.environ.get('SCM_CI_POSTGRES_CONTAINER', '')
if not re.fullmatch('[a-f0-9]{12,64}', container):
    raise SystemExit('Existing CI PostgreSQL service container is required')
# Backend integration tests use their existing isolated trust fixtures. Production JAR gates
# then authenticate over TCP using SCRAM; the local socket remains usable by backup/restore tools.
with psycopg.connect(host='127.0.0.1', port=5433, user='scm', dbname='postgres', autocommit=True) as db:
    db.execute("SET password_encryption='scram-sha-256'")
    db.execute(sql.SQL('ALTER ROLE scm PASSWORD {}').format(sql.Literal(db_secret)))
    subprocess.run(['docker', 'exec', container, 'sh', '-c',
                    'sed -i -E \'s/^(host[[:space:]]+[^#]*[[:space:]])trust$/\\1scram-sha-256/\' "$PGDATA/pg_hba.conf"'], check=True)
    db.execute('SELECT pg_reload_conf()')
with psycopg.connect(host='127.0.0.1', port=5433, user='scm', password=db_secret, dbname='postgres', connect_timeout=5):
    pass
deadline = time.monotonic() + 5
while True:
    try:
        with psycopg.connect(host='127.0.0.1', port=5433, user='scm', password='invalid-ephemeral-credential', dbname='postgres', connect_timeout=5):
            pass
    except psycopg.OperationalError:
        break
    if time.monotonic() >= deadline:
        raise SystemExit('Runtime PostgreSQL still accepts unauthenticated TCP connections')
    time.sleep(.05)
with psycopg.connect(host='127.0.0.1', port=5433, user='scm', password=db_secret, dbname='postgres', connect_timeout=5):
    pass
parts = [b'CONFIG', b'SET', b'requirepass', redis_secret.encode()]
wire = b'*4\r\n' + b''.join(b'$' + str(len(part)).encode() + b'\r\n' + part + b'\r\n' for part in parts)
with socket.create_connection(('127.0.0.1', 6380), timeout=5) as connection:
    connection.sendall(wire)
    if connection.recv(1024) != b'+OK\r\n':
        raise SystemExit('Failed to authenticate ephemeral Redis')
with Path(os.environ['GITHUB_ENV']).open('a') as env:
    env.write('SCM_CI_REDIS_PASSWORD=' + redis_secret + '\nSCM_CI_DB_PASSWORD=' + db_secret + '\n')
print('Ephemeral PostgreSQL SCRAM and Redis credentials configured')
