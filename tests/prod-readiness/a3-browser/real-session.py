#!/usr/bin/env python3
"""Exercise real cookie sessions with production frontend modules; no API routes are mocked.

Start the backend in the same process/network namespace, then provide SCM_UI_API_BASE,
SCM_UI_USERNAME and SCM_UI_PASSWORD. Only controlled assertions/counts are persisted:
never credentials, response bodies, request headers, cookie values or storage snapshots.
"""
import asyncio
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'target/test-artifacts/a3'
PROTECTED = '/api/companies/options'
AUTH_COOKIES = {'scm_refresh', 'scm_browser'}
TIMEOUT = 60_000


class CheckFailure(Exception):
    """The message is always a constant assertion identifier, never an external value."""


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


def configuration():
    origin = os.environ.get('SCM_UI_API_BASE', '')
    parsed = urlsplit(origin)
    require(parsed.scheme in {'http', 'https'} and parsed.hostname in {'127.0.0.1', 'localhost', '::1'}
            and parsed.port is not None and not parsed.username and not parsed.password
            and parsed.path in {'', '/'} and not parsed.query and not parsed.fragment,
            'SCM_UI_API_BASE_must_be_an_explicit_loopback_origin')
    credentials = {key: os.environ.get('SCM_UI_' + key.upper(), '') for key in ['username', 'password']}
    require(all(credentials.values()), 'SCM_UI_USERNAME_and_SCM_UI_PASSWORD_are_required')
    return origin.rstrip('/'), credentials


async def auth_cookies(context):
    values = [cookie for cookie in await context.cookies() if cookie['name'] in AUTH_COOKIES]
    require(len(values) == 2 and {cookie['name'] for cookie in values} == AUTH_COOKIES, 'both_server_cookies_required')
    require(all(cookie['httpOnly'] and cookie['sameSite'] == 'Strict' and cookie['path'] == '/api/auth'
                and cookie['expires'] > time.time() and cookie['value'] for cookie in values), 'cookie_attributes_invalid')
    return {cookie['name']: cookie for cookie in values}


def rotated(before, after):
    require(before['scm_refresh']['value'] != after['scm_refresh']['value'], 'refresh_cookie_did_not_rotate')
    require(before['scm_browser']['value'] == after['scm_browser']['value'], 'browser_binding_changed_on_refresh')
    # The server retains the family deadline; rotation must not turn it into a sliding lifetime.
    require(abs(before['scm_refresh']['expires'] - after['scm_refresh']['expires']) <= 3,
            'refresh_changed_the_fixed_family_deadline')


async def cookie_response(response):
    require(response.status == 200, 'authentication_response_http_status')
    body = await response.json()
    require(body.get('code') == 0 and isinstance(body.get('data'), str) and body['data'], 'authentication_response_shape')
    headers = await response.header_values('set-cookie')
    require(all(any(header.startswith(name + '=') for header in headers) for name in AUTH_COOKIES), 'response_did_not_set_both_cookies')
    require('no-store' in (await response.header_value('cache-control') or ''), 'authentication_response_must_not_be_cached')


async def cookie_request(request):
    require(request.method == 'POST' and request.post_data_json == {}, 'cookie_action_must_post_empty_json')
    headers = await request.all_headers()
    require(headers.get('x-requested-with') == 'XMLHttpRequest', 'cookie_action_missing_same_site_header')
    require(headers.get('sec-fetch-site') == 'same-origin', 'cookie_action_not_same_origin')
    require(all(re.search(r'(?:^|;\s*)' + name + r'=', headers.get('cookie', '')) for name in AUTH_COOKIES),
            'cookie_action_missing_browser_cookies')


async def exercise(base, url, credentials, metrics):
    requests = []
    responses = []
    console_errors = []
    page_errors = []
    external = []
    expected_read_tags = {'burst-a', 'burst-b', 'successor-check', 'revoked-access'}

    def observe(context, label):
        def request(value):
            parsed = urlsplit(value.url)
            if parsed.scheme in {'http', 'https'} and f'{parsed.scheme}://{parsed.netloc}' != base:
                external.append(True)
            if parsed.path.startswith('/api/'):
                requests.append((label, value))
        context.on('request', request)
        context.on('response', lambda value: responses.append((label, value)) if urlsplit(value.url).path.startswith('/api/') else None)
        def page(tab):
            tab.on('pageerror', lambda _error: page_errors.append(True))
            def console(message):
                if message.type != 'error':
                    return
                parsed = urlsplit(message.location.get('url', ''))
                tag = parse_qs(parsed.query).get('_a3_session', [''])[0]
                rejected = message.text.startswith('Failed to load resource:') and bool(re.search(r'status of 401\b', message.text))
                expected = rejected and ((parsed.path == PROTECTED and tag in expected_read_tags)
                                         or (label == 'revoked-cookie' and parsed.path == '/api/auth/refresh' and tag == 'revoked-cookie'))
                # Do not persist console text: an unexpected library log could contain credentials.
                console_errors.append({'expected': expected, 'resource': 'protected-read' if parsed.path == PROTECTED else 'auth-refresh' if parsed.path == '/api/auth/refresh' else 'other'})
            tab.on('console', console)
        context.on('page', page)

    def matching(items, path, label='primary', tags=None):
        result = []
        for owner, value in items:
            parsed = urlsplit(value.url)
            if owner == label and parsed.path == path and (tags is None or parse_qs(parsed.query).get('_a3_session', [''])[0] in tags):
                result.append(value)
        return result

    async def wait_for(condition, code):
        deadline = time.monotonic() + TIMEOUT / 1000
        while not condition():
            require(time.monotonic() < deadline, code)
            await asyncio.sleep(.05)

    async with async_playwright() as playwright:
        options = {'headless': True}
        if os.environ.get('SCM_UI_CHROMIUM'):
            options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
        browser = await playwright.chromium.launch(**options)
        primary = None
        first = None
        logout_done = False
        try:
            primary = await browser.new_context()
            observe(primary, 'primary')
            first = await primary.new_page()
            await first.goto(url)
            await first.wait_for_function('window.__realSession?.ready', timeout=TIMEOUT)
            require(await first.evaluate('window.__realSession.webLocks'), 'chromium_web_locks_required')
            metrics['stage'] = 'real_login'
            login = await first.evaluate('credentials => window.__realSession.login(credentials)', credentials)
            metrics['login_outcome'] = {'ok': login.get('ok') is True, 'code': login.get('code') if isinstance(login.get('code'), int) else -2}
            require(login == {'ok': True, 'code': 0}, 'real_login_failed')
            login_responses = matching(responses, '/api/auth/login')
            require(len(login_responses) == 1, 'one_real_login_required')
            await cookie_response(login_responses[0])
            initial_cookies = await auth_cookies(primary)
            require(await first.evaluate('window.__realSession.cookiesInvisibleToScript()'), 'http_only_cookies_visible_to_script')
            metrics['checks']['login_issues_real_http_only_cookies'] = True

            second = await primary.new_page()
            await second.goto(url)
            await second.wait_for_function('window.__realSession?.ready', timeout=TIMEOUT)
            tabs = [first, second]
            for tab in tabs:
                require(await tab.evaluate('window.__realSession.webLocks'), 'both_tabs_require_web_locks')
                await tab.evaluate('window.__realSession.markSession()')
            metrics['stage'] = 'sixteen_concurrent_real_reads'
            await first.evaluate('window.__realSession.invalidateAccess()')
            for tab in tabs:
                await tab.wait_for_function('window.__realSession.state().invalid', timeout=TIMEOUT)
            # Hold the actual production lock, not the network: all 16 real 401s must arrive before rotation.
            await first.evaluate('window.__realSession.holdRefresh()')
            try:
                await asyncio.gather(*(tab.evaluate('tag => window.__realSession.startBurst(tag)', tag)
                                       for tab, tag in zip(tabs, ['burst-a', 'burst-b'])))
                await wait_for(lambda: len(matching(responses, PROTECTED, tags={'burst-a', 'burst-b'})) >= 16, 'sixteen_initial_reads_timed_out')
                initial_reads = matching(responses, PROTECTED, tags={'burst-a', 'burst-b'})
                require(len(initial_reads) == 16 and all(response.status == 401 for response in initial_reads), 'all_sixteen_initial_reads_must_reach_real_401')
                require(not matching(requests, '/api/auth/refresh'), 'refresh_ran_before_the_shared_lock_released')
                await first.wait_for_function('window.__realSession.refreshWaiters().then(count => count === 2)', timeout=TIMEOUT)
            finally:
                await first.evaluate('window.__realSession.releaseRefresh()')
            for tab in tabs:
                await tab.wait_for_function('window.__realSession.burstResult() !== null', timeout=TIMEOUT)
                outcomes = await tab.evaluate('window.__realSession.burstResult()')
                require(len(outcomes) == 8 and all(outcome == {'ok': True, 'code': 0} for outcome in outcomes), 'a_protected_read_did_not_replay_successfully')
                require(await tab.evaluate('window.__realSession.state()') == {
                    'stale': False, 'hasAccess': True, 'invalid': False, 'sameEpoch': True, 'sameIdentity': True,
                }, 'refresh_changed_identity_or_left_invalid_access')
            burst_reads = matching(responses, PROTECTED, tags={'burst-a', 'burst-b'})
            require(len(burst_reads) == 32 and sum(response.status == 401 for response in burst_reads) == 16
                    and sum(response.status == 200 for response in burst_reads) == 16, 'protected_reads_must_replay_exactly_once')
            for response in burst_reads:
                require((await response.json()).get('code') == (401 if response.status == 401 else 0), 'protected_response_envelope_mismatch')
            refresh_requests = matching(requests, '/api/auth/refresh')
            refresh_responses = matching(responses, '/api/auth/refresh')
            require(len(refresh_requests) == len(refresh_responses) == 1, 'two_tabs_must_share_one_network_refresh')
            await cookie_request(refresh_requests[0])
            await cookie_response(refresh_responses[0])
            renewed_cookies = await auth_cookies(primary)
            rotated(initial_cookies, renewed_cookies)
            metrics['burst'] = {'tabs': 2, 'logical_reads': 16, 'initial_401': 16, 'successful_replays': 16, 'network_refreshes': 1}
            metrics['checks']['two_tab_sixteen_request_single_flight'] = True
            metrics['checks']['server_cookie_rotation_preserves_family_deadline'] = True

            metrics['stage'] = 'successor_cookie_round_trip'
            await first.evaluate('window.__realSession.invalidateAccess()')
            await second.wait_for_function('window.__realSession.state().invalid', timeout=TIMEOUT)
            require(await second.evaluate("window.__realSession.read('successor-check')") == {'ok': True, 'code': 0}, 'rotated_cookie_was_not_usable')
            follow_up = matching(responses, PROTECTED, tags={'successor-check'})
            require([response.status for response in follow_up] == [401, 200], 'successor_check_must_replay_one_real_401')
            require(len(matching(requests, '/api/auth/refresh')) == len(matching(responses, '/api/auth/refresh')) == 2, 'successor_cookie_check_requires_one_additional_refresh')
            await cookie_request(matching(requests, '/api/auth/refresh')[-1])
            await cookie_response(matching(responses, '/api/auth/refresh')[-1])
            final_cookies = await auth_cookies(primary)
            rotated(renewed_cookies, final_cookies)
            metrics['checks']['rotated_cookie_is_accepted_on_next_refresh'] = True
            metrics['successor_check'] = {'logical_reads': 1, 'network_refreshes': 1, 'successful_replays': 1}

            # Retain only server-issued credentials in memory to prove revocation, never serialize them.
            revoked_context = await browser.new_context()
            observe(revoked_context, 'revoked-cookie')
            await revoked_context.add_cookies(list(final_cookies.values()))
            for tab in tabs:
                await tab.evaluate('window.__realSession.retainAccess()')
            metrics['stage'] = 'expired_access_logout_and_cross_tab_revocation'
            await first.evaluate('window.__realSession.invalidateAccess()')
            await second.wait_for_function('window.__realSession.state().invalid', timeout=TIMEOUT)
            require(await first.evaluate('window.__realSession.logout()') == {'ok': True, 'code': 0}, 'expired_access_cookie_logout_failed')
            logout_done = True
            logout_requests = matching(requests, '/api/auth/logout')
            logout_responses = matching(responses, '/api/auth/logout')
            require(len(logout_requests) == len(logout_responses) == 1, 'one_real_logout_required')
            await cookie_request(logout_requests[0])
            require((await logout_requests[0].all_headers()).get('authorization') == 'Bearer a3-deliberately-invalid-access', 'logout_did_not_exercise_invalid_access')
            require(logout_responses[0].status == 200 and (await logout_responses[0].json()).get('code') == 0, 'server_logout_not_successful')
            require(not [cookie for cookie in await primary.cookies() if cookie['name'] in AUTH_COOKIES], 'logout_did_not_clear_both_cookies')
            await second.wait_for_function('window.__realSession.notifications() > 0', timeout=TIMEOUT)
            require(await second.evaluate('window.__realSession.state().stale'), 'second_tab_did_not_invalidate_its_session')
            before = len(matching(requests, PROTECTED))
            require(await second.evaluate("window.__realSession.read('blocked-after-logout')") == {'ok': False, 'code': 409}, 'stale_tab_read_was_not_blocked')
            require(len(matching(requests, PROTECTED)) == before, 'stale_tab_sent_a_protected_request')
            require(len(matching(requests, '/api/auth/refresh')) == 2, 'logout_triggered_an_unwanted_refresh')
            metrics['checks']['expired_access_logout_clears_cookie_and_cross_tab_epoch'] = True

            require(await second.evaluate('window.__realSession.revokedAccess()') == {'ok': False, 'code': 401, 'status': 401}, 'server_still_accepts_revoked_access')
            revoked_page = await revoked_context.new_page()
            await revoked_page.goto(url)
            await revoked_page.wait_for_function('window.__realSession?.ready', timeout=TIMEOUT)
            require(await revoked_page.evaluate('window.__realSession.revokedRefresh()') == {'ok': False, 'code': 401, 'status': 401}, 'server_still_accepts_revoked_refresh_cookie')
            revoked_requests = matching(requests, '/api/auth/refresh', label='revoked-cookie')
            require(len(revoked_requests) == 1, 'one_revoked_cookie_verification_required')
            await cookie_request(revoked_requests[0])
            metrics['checks']['server_rejects_access_and_refresh_after_logout'] = True
            metrics['stage'] = 'console_and_scope_audit'
            require(not page_errors, 'unexpected_browser_page_error')
            require(not external, 'request_left_the_local_test_environment')
            require(all(record['expected'] for record in console_errors), 'unexpected_browser_console_error')
            metrics['checks']['console_and_page_error_audit'] = True
        finally:
            if first and not first.is_closed() and not logout_done:
                try:
                    await first.evaluate("""async () => {
                      await window.__realSession?.releaseRefresh();
                      await fetch('/api/auth/logout', { method: 'POST', credentials: 'include',
                        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }, body: '{}' });
                    }""")
                except Exception:
                    pass
            await browser.close()
            metrics['network_counts'] = {}
            for category, path, label in [('login', '/api/auth/login', 'primary'), ('me', '/api/auth/me', 'primary'),
                                          ('refresh', '/api/auth/refresh', 'primary'), ('logout', '/api/auth/logout', 'primary'),
                                          ('protected_reads', PROTECTED, 'primary'), ('revoked_refresh', '/api/auth/refresh', 'revoked-cookie')]:
                statuses = [response.status for response in matching(responses, path, label)]
                metrics['network_counts'][category] = {'requests': len(matching(requests, path, label)),
                                                       'http_status_counts': {str(status): statuses.count(status) for status in sorted(set(statuses))}}
            metrics['page_error_count'] = len(page_errors)
            metrics['external_request_count'] = len(external)
            metrics['console_error_counts'] = {'expected': sum(record['expected'] for record in console_errors),
                                               'unexpected': sum(not record['expected'] for record in console_errors)}


async def run():
    metrics = {'verification': 'real backend + production frontend session transport', 'passed': False, 'stage': 'configuration', 'checks': {}}
    process = None
    fixture = None
    started = time.monotonic()
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    try:
        api_base, credentials = configuration()
        fixture = Path(tempfile.mkdtemp(prefix='.a3-real-session-', dir=ROOT / 'web/node_modules'))
        for name in ['real-session.html', 'real-session.ts']:
            shutil.copyfile(Path(__file__).with_name(name), fixture / name)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        child_env = {key: value for key, value in os.environ.items() if key not in {'SCM_UI_USERNAME', 'SCM_UI_PASSWORD', 'DEBUG', 'PWDEBUG'}}
        child_env['VITE_API_TARGET'] = api_base
        process = subprocess.Popen(['pnpm', 'exec', 'vite', '--host', '127.0.0.1', '--port', str(port), '--strictPort'],
                                   cwd=ROOT / 'web', env=child_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   start_new_session=True)
        metrics['stage'] = 'vite_startup'
        for _ in range(200):
            require(process.poll() is None, 'vite_exited_before_ready')
            try:
                _reader, writer = await asyncio.open_connection('127.0.0.1', port)
                writer.close()
                await writer.wait_closed()
                break
            except OSError:
                await asyncio.sleep(.1)
        else:
            raise CheckFailure('vite_startup_timed_out')
        base = f'http://127.0.0.1:{port}'
        metrics['stage'] = 'browser_startup'
        await exercise(base, base + f'/node_modules/{fixture.name}/real-session.html', credentials, metrics)
        require(metrics['page_error_count'] == 0 and metrics['external_request_count'] == 0
                and metrics['console_error_counts']['unexpected'] == 0, 'final_browser_audit_failed')
        metrics['passed'] = True
        metrics['stage'] = 'complete'
    except CheckFailure as error:
        metrics['failure'] = str(error)
    except Exception as error:
        # Playwright call logs and library errors may contain evaluate() arguments. Never print them.
        metrics['failure'] = type(error).__name__
    finally:
        if process:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        if fixture:
            shutil.rmtree(fixture, ignore_errors=True)
        metrics['elapsed_seconds'] = round(time.monotonic() - started, 3)
        (ARTIFACTS / 'real-session-results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
        print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return metrics['passed']


if __name__ == '__main__':
    raise SystemExit(0 if asyncio.run(run()) else 1)
