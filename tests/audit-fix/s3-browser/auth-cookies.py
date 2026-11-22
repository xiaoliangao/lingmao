#!/usr/bin/env python3
"""Production auth/store with real HTTP + HttpOnly Set-Cookie in two browser tabs.

The small HTTP server supplies synthetic identities, controllable response delay,
and the agreed cookie/access-only contract. It is not backend authorization proof.
"""
import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
from time import perf_counter
from urllib.parse import urlsplit

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3'


class State:
    def __init__(self):
        self.reset('')

    def reset(self, blocked):
        self.blocked = blocked
        self.started = threading.Event()
        self.release = threading.Event()
        self.calls = []
        self.set_cookies = 0
        self.fail_refresh = False
        self.refresh_trace = None
        self.password_401 = None


STATE = State()


class Api(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.respond()

    def do_POST(self):
        self.respond()

    def do_PUT(self):
        self.respond()

    def respond(self):
        path = urlsplit(self.path).path
        mode = self.headers.get('X-Session-Mode', 'cookie')
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))) or '{}')
        STATE.calls.append({'path': path, 'method': self.command, 'mode': mode, 'has_request_id': bool(self.headers.get('X-Request-Id'))})
        code, data, cookies, status = 0, None, [], 200
        if path == '/api/auth/login':
            who = body['username']
            data = f'synthetic-{who}-access'
            if mode == 'cookie':
                cookies = [('scm_refresh', f'synthetic-{who}-refresh', 3600), ('scm_browser', f'synthetic-{who}-browser', 3600)]
        elif path == '/api/auth/me':
            next_user = self.headers.get('Authorization') == 'Bearer synthetic-next-access'
            data = {'id': '9007199254740995' if next_user else '9007199254740993', 'username': 'synthetic', 'nickname': '合成账号', 'roles': [], 'perms': [], 'menus': [], 'defaultCompany': {'id': '1', 'name': '合成主体'}}
        elif path == '/api/auth/refresh':
            STATE.refresh_trace = self.headers.get('X-Request-Id')
            if STATE.fail_refresh:
                code = 401
            elif mode == 'access-only':
                code = 400
            else:
                data = 'synthetic-rotated-access'
                cookies = [('scm_refresh', 'synthetic-rotated-refresh', 3600), ('scm_browser', 'synthetic-rotated-browser', 3600)]
        elif path in ['/api/auth/logout', '/api/auth/password']:
            if path == '/api/auth/password' and STATE.password_401:
                code = 401
                status = 401 if STATE.password_401 == 'http' else 200
            elif mode == 'cookie':
                cookies = [('scm_refresh', '', 0), ('scm_browser', '', 0)]
        elif path == '/api/probe/needs-refresh':
            if self.headers.get('Authorization') != 'Bearer synthetic-rotated-access':
                code = 401
            else:
                data = True
        else:
            code = 404
        if path == STATE.blocked and (path != '/api/auth/login' or body.get('username') == 'next'):
            STATE.started.set()
            if not STATE.release.wait(15):
                code, cookies = 503, []
        payload = json.dumps({'code': code, 'msg': '' if not code else '合成认证故障', 'data': data}).encode()
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            if self.headers.get('X-Request-Id'):
                self.send_header('X-Request-Id', self.headers['X-Request-Id'])
            self.send_header('Content-Length', str(len(payload)))
            for name, value, age in cookies:
                self.send_header('Set-Cookie', f'{name}={value}; Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age={age}')
            STATE.set_cookies += len(cookies)
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass


async def until(predicate, timeout=10):
    for _ in range(int(timeout * 100)):
        if predicate():
            return
        await asyncio.sleep(.01)
    raise AssertionError('timed out waiting for controlled HTTP request')


async def page_ready(context, base):
    page = await context.new_page()
    await page.goto(base + '/node_modules/.s3-cookies/auth-cookies.html')
    await page.wait_for_function('Boolean(window.__cookies)')
    return page


async def insecure_origin(browser, port, metrics):
    context = await browser.new_context()
    try:
        page = await context.new_page()
        base = f'http://scm-http.test:{port}'
        await page.goto(base + '/node_modules/.s3-cookies/auth-cookies.html', timeout=7000)
        await page.wait_for_function('Boolean(window.__cookies)', timeout=7000)
        state = await page.evaluate('window.__cookies.state()')
        assert not state['secure'] and not state['locks'], state
        metrics['insecure_http_origin'] = {'verified': True, 'capability_injected': False}
        return base, False
    except Exception as error:
        metrics['insecure_http_origin'] = {'verified': False, 'capability_injected': True, 'reason': str(error).splitlines()[0]}
        return f'http://127.0.0.1:{port}', True
    finally:
        await context.close()


async def no_locks_context(browser, inject):
    context = await browser.new_context()
    if inject:
        # Capability fault injection only; this does not claim an actual insecure origin was reached.
        await context.add_init_script("Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined })")
    return context


async def start(page, method, outcome):
    await page.evaluate(f"window.{outcome} = null; void window.__cookies.{method}.then(() => {{ window.{outcome} = 0 }}, error => {{ window.{outcome} = error.code ?? -1; window.{outcome}Trace = error.traceId ?? null }})")


async def run():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-cookies'
    fixture.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).with_name('auth-cookies.html'), fixture / 'auth-cookies.html')
    api = ThreadingHTTPServer(('127.0.0.1', 0), Api)
    threading.Thread(target=api.serve_forever, daemon=True).start()
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    metrics = {'checks': {}, 'page_errors': []}
    env = {**os.environ, 'VITE_API_TARGET': f'http://127.0.0.1:{api.server_port}', '__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS': 'scm-http.test'}
    with (ARTIFACTS / 'auth-cookies-vite.log').open('w') as log:
        process = subprocess.Popen(['pnpm', 'exec', 'vite', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], cwd=ROOT / 'web', env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None:
                    raise RuntimeError('Vite exited; see auth-cookies-vite.log')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close()
                    await writer.wait_closed()
                    break
                except OSError:
                    await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                # Ordinary test DNS resolution; the HTTP origin remains insecure and Web Locks absent.
                options = {'headless': True, 'args': ['--host-resolver-rules=MAP scm-http.test 127.0.0.1']}
                if os.environ.get('SCM_UI_CHROMIUM'):
                    options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                base = f'http://127.0.0.1:{port}'
                try:
                    for action in ['logout', 'refresh', 'password']:
                        STATE.reset('/api/auth/' + action)
                        context = await browser.new_context()
                        context.on('page', lambda page: page.on('pageerror', lambda error: metrics['page_errors'].append(str(error))))
                        first = await page_ready(context, base)
                        await first.evaluate("window.__cookies.login('first')")
                        second = await page_ready(context, base)
                        await start(first, 'read()' if action == 'refresh' else action + '()', '__oldOutcome')
                        await until(STATE.started.is_set)
                        await start(second, "login('next')", '__nextOutcome')
                        await second.wait_for_function('window.__nextOutcome !== null || window.__cookies.pending()')
                        deferred = sum(call['path'] == '/api/auth/login' for call in STATE.calls) == 1
                        STATE.release.set()
                        await second.wait_for_function('window.__nextOutcome !== null')
                        await first.wait_for_function('window.__oldOutcome !== null')
                        cookies = {cookie['name']: cookie for cookie in await context.cookies(base + '/api/auth/me')}
                        next_cookie = cookies.get('scm_refresh', {}).get('value') == 'synthetic-next-refresh' and cookies.get('scm_browser', {}).get('value') == 'synthetic-next-browser'
                        next_state = await second.evaluate('window.__cookies.state()')
                        stored_next = await second.evaluate('window.__cookies.storedNext()')
                        metrics['checks'][f'{action}_then_login'] = {'passed': deferred and next_cookie and stored_next and next_state['isNext'], 'login_waited_for_old_response': deferred, 'new_http_only_cookie_pair_preserved': next_cookie, 'new_access_preserved': stored_next and next_state['isNext'], 'all_cookies_http_only': all(cookie['httpOnly'] for cookie in cookies.values())}
                        await context.close()

                    STATE.reset('/api/auth/login')
                    context = await browser.new_context()
                    first = await page_ready(context, base)
                    await first.evaluate("window.__cookies.login('first')")
                    second = await page_ready(context, base)
                    await start(second, "login('next')", '__nextOutcome')
                    await until(STATE.started.is_set)
                    await start(first, 'logout()', '__oldOutcome')
                    await first.wait_for_function('window.__oldOutcome !== null || window.__cookies.pending()')
                    STATE.release.set()
                    await second.wait_for_function('window.__nextOutcome !== null')
                    await first.wait_for_function('window.__oldOutcome !== null')
                    sent = sum(call['path'] == '/api/auth/logout' for call in STATE.calls)
                    metrics['checks']['queued_old_logout_not_sent'] = {'passed': sent == 0 and await second.evaluate('window.__cookies.storedNext()'), 'logout_http_requests': sent}
                    await context.close()

                    STATE.reset('')
                    STATE.fail_refresh = True
                    context = await browser.new_context()
                    page = await page_ready(context, base)
                    await page.evaluate("window.__cookies.login('first')")
                    await start(page, 'read()', '__readOutcome')
                    await page.wait_for_function('window.__readOutcome !== null')
                    traced = STATE.refresh_trace is not None and await page.evaluate('window.__readOutcomeTrace') == STATE.refresh_trace
                    metrics['checks']['refresh_failure_request_id'] = {'passed': traced and await page.evaluate('window.__readOutcome') == 401, 'response_trace_preserved': traced, 'refresh_request_has_id': STATE.refresh_trace is not None}
                    await context.close()

                    password_cases = []
                    for transport in ['business', 'http']:
                        STATE.reset('')
                        STATE.password_401 = transport
                        context = await browser.new_context()
                        page = await page_ready(context, base)
                        await page.evaluate("window.__cookies.login('first')")
                        began = perf_counter()
                        await start(page, 'password()', '__passwordOutcome')
                        await page.wait_for_function('window.__passwordOutcome !== null', timeout=5000)
                        elapsed = round((perf_counter() - began) * 1000)
                        code = await page.evaluate('window.__passwordOutcome')
                        held = await page.evaluate("navigator.locks.query().then(state => state.held.filter(lock => lock.name === 'lingmao:auth-cookie').length)")
                        refreshed = sum(call['path'] == '/api/auth/refresh' for call in STATE.calls)
                        password_cases.append({'transport': transport, 'passed': code == 401 and held == 0 and refreshed == 0, 'outcome': code, 'elapsed_ms': elapsed, 'held_auth_locks': held, 'refresh_requests': refreshed})
                        await context.close()
                    metrics['checks']['password_401_completes_without_nested_refresh'] = {'passed': all(case['passed'] for case in password_cases), 'cases': password_cases}

                    insecure_base, inject = await insecure_origin(browser, port, metrics)
                    STATE.reset('')
                    context = await no_locks_context(browser, inject)
                    insecure = await page_ready(context, insecure_base)
                    state = await insecure.evaluate('window.__cookies.state()')
                    assert not state['locks'], state
                    await insecure.evaluate("window.__cookies.login('first')")
                    await start(insecure, 'read()', '__readOutcome')
                    await insecure.wait_for_function('window.__readOutcome !== null')
                    await insecure.evaluate("window.__cookies.login('next')")
                    await insecure.evaluate('window.__cookies.logout()')
                    auth = [call for call in STATE.calls if call['path'] != '/api/auth/me' and call['path'].startswith('/api/auth/')]
                    no_refresh = not any(call['path'] == '/api/auth/refresh' for call in auth)
                    metrics['checks']['access_only_without_web_locks'] = {'passed': no_refresh and STATE.set_cookies == 0 and all(call['mode'] == 'access-only' for call in auth), 'capability_injected': inject, 'secure_context': state['secure'], 'web_locks': state['locks'], 'set_cookie_headers': STATE.set_cookies, 'refresh_requests': sum(call['path'] == '/api/auth/refresh' for call in auth), 'auth_request_modes': [call['mode'] for call in auth]}
                    await context.close()

                    STATE.reset('/api/auth/logout')
                    context = await no_locks_context(browser, inject)
                    first = await page_ready(context, insecure_base)
                    await first.evaluate("window.__cookies.login('first')")
                    second = await page_ready(context, insecure_base)
                    await start(first, 'logout()', '__oldOutcome')
                    await until(STATE.started.is_set)
                    await second.evaluate("window.__cookies.login('next')")
                    STATE.release.set()
                    await first.wait_for_function('window.__oldOutcome !== null')
                    preserved = await second.evaluate('window.__cookies.storedNext() && window.__cookies.state().isNext')
                    metrics['checks']['access_only_old_logout_keeps_new_login'] = {'passed': preserved and STATE.set_cookies == 0, 'new_access_preserved': preserved, 'set_cookie_headers': STATE.set_cookies}
                    await context.close()
                finally:
                    await browser.close()
        finally:
            process.terminate()
            process.wait(timeout=10)
            api.shutdown()
            api.server_close()
            (ARTIFACTS / 'auth-cookies-results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    assert len(metrics['checks']) == 8 and all(check['passed'] for check in metrics['checks'].values()), metrics
    if os.environ.get('SCM_UI_REQUIRE_INSECURE_ORIGIN') == '1' or os.environ.get('GITHUB_ACTIONS') == 'true':
        assert metrics['insecure_http_origin']['verified'], metrics['insecure_http_origin']


if __name__ == '__main__':
    asyncio.run(run())
