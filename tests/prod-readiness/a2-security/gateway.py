#!/usr/bin/env python3
"""Run the shipped Nginx locations over real HTTP/TLS with a temporary local upstream."""
import base64, hashlib, http.client, json, os, shutil, socket, ssl, struct, subprocess, tempfile, threading, time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = Path(__file__).resolve().parents[3]
ARTIFACTS = REPO / 'target/test-artifacts/a2-gateway'
ARTIFACTS.mkdir(parents=True, exist_ok=True)
NGINX = os.environ.get('SCM_TEST_NGINX') or shutil.which('nginx')
if not NGINX:
    raise SystemExit('Install nginx or set SCM_TEST_NGINX')

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

disconnected = threading.Event()
class Upstream(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        if self.path == '/api/stream':
            try:
                for _ in range(2000):
                    self.wfile.write(b'x' * 8192)
                    self.wfile.flush()
                    if disconnected.wait(.005): break
            except (BrokenPipeError, ConnectionResetError):
                disconnected.set()
        else:
            self.wfile.write(json.dumps({'forwarded': self.headers.get('X-Forwarded-For'), 'proto': self.headers.get('X-Forwarded-Proto'), 'host': self.headers.get('Host')}).encode())
    def log_message(self, *_): pass

def request(port, path, tls=None, headers=None):
    connection = (http.client.HTTPSConnection('127.0.0.1', port, timeout=4, context=tls) if tls else http.client.HTTPConnection('127.0.0.1', port, timeout=4))
    try:
        connection.request('GET', path, headers=headers or {})
        response = connection.getresponse()
        return response.status, {k.lower(): v for k, v in response.getheaders()}, response.read()
    finally:
        connection.close()

def check_headers(headers):
    assert headers['x-content-type-options'] == 'nosniff', headers
    assert headers['x-frame-options'] == 'DENY', headers
    assert 'content-security-policy' in headers, headers

checks = {}
with tempfile.TemporaryDirectory(prefix='gateway-', dir=ARTIFACTS) as temp:
    root = Path(temp)
    config = root / 'conf'
    shutil.copytree(REPO / 'deploy/package/nginx', config)
    web = root / 'web'
    (web / 'assets').mkdir(parents=True)
    (web / 'index.html').write_text('<!doctype html><title>SCM synthetic gateway check</title>')
    (web / 'assets/app-a12b3c.js').write_text('window.syntheticGateway=true')
    uploads = root / 'uploads/2026/11/17'
    uploads.mkdir(parents=True)
    (uploads / 'example.pdf').write_bytes(b'%PDF-1.7 synthetic')
    (uploads / 'example.png').write_bytes(b'\x89PNG\r\n\x1a\n')
    external = root / 'outside.txt'
    external.write_text('outside synthetic sentinel')
    (uploads / 'linked.pdf').symlink_to(external)
    key = base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip('=')
    http_port, tls_port, redirect_port, declared_tls_port = [free_port() for _ in range(4)]
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    site = (config / 'site.conf').read_text().replace('/etc/nginx/scm', str(config))
    site = site.replace('/usr/share/nginx/html', str(web)).replace('/data/uploads', str(root / 'uploads'))
    site = site.replace('http://app:8080', f'http://127.0.0.1:{upstream.server_port}')
    (config / 'site.conf').write_text(site)
    cert, private_key = root / 'cert.pem', root / 'key.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost', '-keyout', str(private_key), '-out', str(cert)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    private_key.chmod(0o600)
    def render(name):
        return (config / name).read_text().replace('/etc/nginx/scm', str(config)).replace('${SCM_FILE_SIGN_KEY}', key)
    http_config = render('http.conf').replace('${SCM_PUBLIC_SCHEME}', '').replace('listen 80 ', f'listen 127.0.0.1:{http_port} ')
    declared = render('http.conf').replace('${SCM_PUBLIC_SCHEME}', 'https').replace('listen 80 ', f'listen 127.0.0.1:{declared_tls_port} ')
    tls = render('https.conf').replace('${SCM_HTTPS_PORT}', str(tls_port)).replace('listen 80 ', f'listen 127.0.0.1:{redirect_port} ')
    tls = tls.replace('listen 443 ', f'listen 127.0.0.1:{tls_port} ').replace('/etc/nginx/certs/fullchain.pem', str(cert)).replace('/etc/nginx/certs/privkey.pem', str(private_key))
    include_maps = f'include {config}/gateway-maps.conf;'
    declared = declared.replace(include_maps, '')
    tls = tls.replace(include_maps, '')
    for name in ('body', 'proxy', 'fastcgi', 'uwsgi', 'scgi'): (root / name).mkdir()
    master = root / 'nginx.conf'
    master.write_text(f'''daemon off;
master_process off;
pid {root}/nginx.pid;
error_log {root}/error.log warn;
events {{ worker_connections 256; }}
http {{
 access_log off;
 client_body_temp_path {root}/body;
 proxy_temp_path {root}/proxy;
 fastcgi_temp_path {root}/fastcgi;
 uwsgi_temp_path {root}/uwsgi;
 scgi_temp_path {root}/scgi;
 {http_config}
 {declared}
 {tls}
}}
''')
    validation = subprocess.run([NGINX, '-t', '-p', str(root), '-c', str(master)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert validation.returncode == 0, validation.stderr
    process = subprocess.Popen([NGINX, '-p', str(root), '-c', str(master)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 5
        while True:
            try:
                status, headers, body = request(http_port, '/')
                break
            except OSError:
                if time.monotonic() > deadline: raise AssertionError((root / 'error.log').read_text())
                time.sleep(.05)
        assert status == 200 and headers['cache-control'] == 'no-cache'
        check_headers(headers)
        assert 'strict-transport-security' not in headers
        checks['index_revalidates_plain_http_has_no_hsts'] = True
        status, headers, body = request(http_port, '/assets/app-a12b3c.js')
        assert status == 200 and 'immutable' in headers['cache-control']; check_headers(headers)
        status, headers, body = request(http_port, '/assets/removed-a12b3c.js')
        assert status == 404 and b'SCM synthetic' not in body; check_headers(headers)
        checks['hashed_assets_immutable_missing_chunk_404'] = True
        _, _, body = request(http_port, '/api/headers', headers={'X-Forwarded-For': '203.0.113.66', 'X-Forwarded-Proto': 'https'})
        assert json.loads(body) == {'forwarded': '127.0.0.1', 'proto': 'http', 'host': f'127.0.0.1:{http_port}'}
        checks['client_cannot_forge_forwarded_headers'] = True
        context = ssl.create_default_context(cafile=str(cert))
        _, headers, body = request(tls_port, '/api/headers', tls=context)
        assert json.loads(body)['proto'] == 'https' and headers['strict-transport-security'] == 'max-age=31536000'
        assert json.loads(body)['host'] == f'127.0.0.1:{tls_port}'
        checks['browser_host_and_non_default_port_preserved'] = True
        _, headers, body = request(declared_tls_port, '/api/headers')
        assert json.loads(body)['proto'] == 'https' and 'strict-transport-security' in headers
        checks['real_tls_and_declared_tls_termination'] = True
        def signed(name):
            path = '/files/2026/11/17/' + name
            expires = str(int(time.time()) + 60)
            signature = base64.urlsafe_b64encode(hashlib.md5((expires + path + ' ' + key).encode()).digest()).decode().rstrip('=')
            return f'{path}?e={expires}&s={signature}'
        status, headers, _ = request(http_port, signed('example.pdf'))
        assert status == 200 and "filename*=UTF-8''example.pdf" in headers['content-disposition']
        assert 'sandbox' in headers['content-security-policy'] and headers['cache-control'].startswith('private'); check_headers(headers)
        assert request(http_port, '/files/2026/11/17/example.pdf')[0] == 403
        assert request(http_port, signed('linked.pdf'))[0] == 403
        assert request(http_port, signed('example.png'))[1]['content-disposition'] == 'inline'
        checks['signed_files_sandboxed_symlinks_blocked'] = True
        conn = http.client.HTTPConnection('127.0.0.1', http_port, timeout=4)
        conn.putrequest('POST', '/api/files'); conn.putheader('Content-Length', str(103 * 1024 * 1024)); conn.endheaders()
        result = conn.getresponse(); assert result.status == 413 and json.loads(result.read())['code'] == 413
        assert result.getheader('X-Request-Id'); conn.close()
        checks['oversized_ingress_structured_413'] = True
        with socket.create_connection(('127.0.0.1', http_port), timeout=4) as connection:
            connection.sendall(b'GET /api/stream HTTP/1.1\r\nHost: localhost\r\n\r\n')
            assert connection.recv(16384)
            connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
        assert disconnected.wait(4), 'Client disconnect did not reach upstream'
        checks['client_reset_reaches_upstream'] = True
        upstream.shutdown(); upstream.server_close()
        status, headers, body = request(http_port, '/api/headers')
        assert status == 503 and json.loads(body)['code'] == 503 and headers['retry-after'] == '3'
        assert headers['x-request-id']; check_headers(headers)
        checks['upstream_failure_structured_503'] = True
    finally:
        process.terminate(); process.wait(timeout=5)
        upstream.shutdown(); upstream.server_close()
    (ARTIFACTS / 'results.json').write_text(json.dumps({'checks': checks, 'count': len(checks)}, indent=2))
print(json.dumps({'gatewayChecks': checks, 'count': len(checks)}))
