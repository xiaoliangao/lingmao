#!/usr/bin/env python3
"""Exercise the real request wrapper under overlapping recovery and identity changes.

Only HTTP responses are fault-injected; no production implementation is replaced.
Vite and Chromium live under one process tree so isolated network namespaces work.
"""
import asyncio
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess

from playwright.async_api import async_playwright
from home_review_checks import run_home_review
from wide_pack_checks import run_wide_pack
from auth_identity_checks import run_auth_identity
from upstream_checks import run_upstream

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3'


async def run():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-audit'
    fixture.mkdir(parents=True, exist_ok=True)
    for name in ['write-recovery.html', 'home-review.html', 'auth-identity.html', 'HomeReviewFixture.vue', 'TechPackFixture.vue', 'UpstreamFixture.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    metrics = {'checks': {}, 'page_errors': []}
    with (ARTIFACTS / 'write-recovery-vite.log').open('w') as log:
        process = subprocess.Popen(
            ['pnpm', 'exec', 'vite', '--host', '127.0.0.1', '--port', str(port), '--strictPort'],
            cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            for _ in range(200):
                if process.poll() is not None:
                    raise RuntimeError('Vite exited; see write-recovery-vite.log')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close()
                    await writer.wait_closed()
                    break
                except OSError:
                    await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'):
                    options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                try:
                    url = f'http://127.0.0.1:{port}/node_modules/.s3-audit/write-recovery.html'
                    for scenario in ['overlapping_recovery', 'write_identity_change', 'resolver_identity_change']:
                        context = await browser.new_context()
                        page = await context.new_page()
                        page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
                        requests = {'states': 0, 'writes': 0, 'keys': []}
                        started = asyncio.Event()
                        release = asyncio.Event()

                        async def state_route(route):
                            requests['states'] += 1
                            if scenario == 'overlapping_recovery':
                                if requests['states'] >= 2:
                                    release.set()
                            else:
                                started.set()
                            await asyncio.wait_for(release.wait(), timeout=10)
                            await route.fulfill(json={'code': 0, 'msg': '', 'data': {'done': False}})

                        async def write_route(route):
                            requests['writes'] += 1
                            requests['keys'].append(route.request.headers.get('idempotency-key'))
                            await asyncio.sleep(.15)
                            await route.fulfill(json={'code': 0, 'msg': '', 'data': {'id': '9007199254740993'}})

                        await context.route('**/api/http-actions/*', state_route)
                        await context.route('**/api/orders', write_route)
                        await page.goto(url)
                        await page.wait_for_function('Boolean(window.__s3)')
                        receipt = await page.evaluate('window.__s3.seed()', None) if scenario != 'write_identity_change' else await page.evaluate('window.__s3.seed(89000)')
                        if scenario == 'overlapping_recovery':
                            results = await page.evaluate('Promise.all([window.__s3.write(), window.__s3.write()])')
                            passed = requests['states'] == 2 and requests['writes'] == 1 and len(set(requests['keys'])) == 1 and results == [{'id': '9007199254740993'}] * 2
                            details = {'state_requests': requests['states'], 'write_requests': requests['writes'], 'distinct_write_keys': len(set(requests['keys']))}
                        else:
                            method = 'write' if scenario == 'write_identity_change' else 'resolve'
                            await page.evaluate(f'window.__outcome = null; void window.__s3.{method}().then(() => {{ window.__outcome = 0 }}, error => {{ window.__outcome = error.code ?? -1 }})')
                            await asyncio.wait_for(started.wait(), timeout=10)
                            await page.evaluate('window.__s3.login(true)')
                            release.set()
                            await page.wait_for_function('window.__outcome !== null', timeout=10000)
                            receipts = await page.evaluate('window.__s3.receipts()')
                            outcome = await page.evaluate('window.__outcome')
                            passed = requests['states'] == 1 and requests['writes'] == 0 and receipt in receipts
                            if scenario == 'write_identity_change':
                                passed = passed and outcome == 409
                            details = {'state_requests': requests['states'], 'write_requests': requests['writes'], 'original_receipt_retained': receipt in receipts, 'outcome': outcome}
                        metrics['checks'][scenario] = {'passed': passed, **details}
                        await context.close()
                    # A POST can commit before its delayed response reaches a page that has changed accounts.
                    context = await browser.new_context()
                    page = await context.new_page()
                    page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
                    started = asyncio.Event()
                    release = asyncio.Event()
                    posted_keys = []

                    async def late_write(route):
                        posted_keys.append(route.request.headers.get('idempotency-key'))
                        started.set()
                        await asyncio.wait_for(release.wait(), timeout=10)
                        await route.fulfill(json={'code': 0, 'msg': '', 'data': {'id': '9007199254740993'}})

                    await context.route('**/api/orders', late_write)
                    await page.goto(url)
                    await page.wait_for_function('Boolean(window.__s3)')
                    await page.evaluate('window.__outcome = null; void window.__s3.write().then(() => { window.__outcome = 0 }, error => { window.__outcome = error.code ?? -1 })')
                    await asyncio.wait_for(started.wait(), timeout=10)
                    await page.evaluate('window.__s3.login(true)')
                    release.set()
                    await page.wait_for_function('window.__outcome !== null')
                    outcome = await page.evaluate('window.__outcome')
                    retained = posted_keys[0] in await page.evaluate('window.__s3.receipts()')
                    metrics['checks']['late_response_identity_change'] = {
                        'passed': outcome == -1 and retained and len(posted_keys) == 1,
                        'outcome': outcome, 'original_receipt_retained': retained, 'write_requests': len(posted_keys),
                    }
                    await context.close()
                    await run_auth_identity(browser, f'http://127.0.0.1:{port}', ARTIFACTS, metrics)
                    await run_home_review(browser, f'http://127.0.0.1:{port}', ARTIFACTS, metrics)
                    await run_wide_pack(browser, f'http://127.0.0.1:{port}', ARTIFACTS, metrics)
                    await run_upstream(browser, f'http://127.0.0.1:{port}', ARTIFACTS, metrics)
                finally:
                    await browser.close()
        finally:
            process.terminate()
            process.wait(timeout=10)
            (ARTIFACTS / 'write-recovery-results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    assert not metrics['page_errors'], metrics['page_errors']
    assert len(metrics['checks']) == 22 and all(check['passed'] for check in metrics['checks'].values()), metrics['checks']


if __name__ == '__main__':
    asyncio.run(run())
