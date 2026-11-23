#!/usr/bin/env python3
"""Actual sample review dialog length limits; strict synthetic API09 only."""
import argparse
import asyncio
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import async_playwright, expect
import samples
from sample_http import SampleHttp, ORDER

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/sample-review-limit'


def units(value):
    return len(value.encode('utf-16-le')) // 2


class ReviewApi(SampleHttp):
    async def route(self, route):
        request = route.request
        url = urlsplit(request.url)
        if request.method == 'POST' and url.path == '/api/sample-orders/' + ORDER + '/review':
            body = request.post_data_json
            limit = 500 if body['result'] == 'REVISE' else 1000
            if units(body.get('opinion') or '') > limit:
                params = parse_qs(url.query)
                assert params == {'companyId': ['1']} and request.headers.get('x-company-id') == '1'
                assert body['version'] == 3 and body['sampleVersionLock'] == 7
                assert request.headers.get('idempotency-key') and request.headers.get('x-request-id')
                call = {'method': request.method, 'path': url.path, 'params': params, 'company': '1', 'requestId': request.headers['x-request-id']}
                self.calls.append(call)
                self.attempts.append({**call, 'body': deepcopy(body), 'key': request.headers['idempotency-key']})
                await self.fault(route, 400)
                return
        await super().route(route)

    def write(self, path, params, body):
        if path == '/api/sample-orders/' + ORDER + '/review':
            limit = 500 if body['result'] == 'REVISE' else 1000
            assert units(body.get('opinion') or '') <= limit, 'Synthetic API09 opinion length exceeded'
        return super().write(path, params, body)


async def open_review(browser, base, report):
    context, page, api, console = await samples.start(browser, base, report, api=ReviewApi('WAIT_REVIEW'))
    await samples.loaded(page)
    await page.get_by_role('button', name='评审', exact=True).click()
    return context, page, api, console, page.get_by_role('dialog', name='样衣评审', exact=True)


async def switch_over_limit(browser, base, report):
    context, page, api, console, dialog = await open_review(browser, base, report)
    try:
        opinion = dialog.get_by_label('评审意见', exact=True)
        await opinion.fill('改' * 501)
        await dialog.get_by_text('改版', exact=True).click()
        retained = await opinion.input_value()
        await dialog.get_by_role('button', name='改版，生成下一版', exact=True).click()
        await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
        attempted = len(api.attempts)
        await samples.capture(page, 'revise-501-local-rejection.png')
        passed = retained == '改' * 501 and attempted == 0 and await dialog.get_by_text('改版意见最多 500 字', exact=True).is_visible()
        report['checks']['switch_to_revise_preserves_501_and_rejects_before_http'] = {'passed': passed, 'retained_utf16_units': units(retained), 'attempts': attempted, 'maxlength': await opinion.get_attribute('maxlength')}
        assert passed, report['checks']['switch_to_revise_preserves_501_and_rejects_before_http']
        await expect(opinion).to_have_attribute('maxlength', '500')
        await page.set_viewport_size({'width': 1180, 'height': 1000})
        await samples.capture(page, 'revise-501-local-rejection-1180.png')
        await opinion.fill('改' * 500)
        await dialog.get_by_role('button', name='改版，生成下一版', exact=True).click()
        await expect(dialog).not_to_be_visible()
        assert len(api.writes) == 1 and api.writes[0]['body']['opinion'] == '改' * 500 and api.orders[0]['reason'] == '改' * 500
        samples.check(report, 'revise_500_copies_reason_without_truncation', submitted_units=500, next_reason_units=units(api.orders[0]['reason']))
    finally: await samples.finish(context, page, api, console, report)


async def ordinary_limits(browser, base, report):
    for result, label in [('PASS', '通过'), ('ELIMINATE', '淘汰')]:
        context, page, api, console, dialog = await open_review(browser, base, report)
        try:
            await dialog.get_by_text(label, exact=True).first.click()
            opinion = dialog.get_by_label('评审意见', exact=True)
            await expect(opinion).to_have_attribute('maxlength', '1000')
            text = '评' * 1000
            await opinion.fill(text)
            await samples.capture(page, result.lower() + '-1000.png')
            await dialog.get_by_role('button', name=label, exact=True).click()
            await expect(dialog).not_to_be_visible()
            assert len(api.writes) == 1 and api.writes[0]['body']['opinion'] == text and api.writes[0]['body']['result'] == result
            samples.check(report, result.lower() + '_1000_allowed', submitted_units=1000, preserved=True)
        finally: await samples.finish(context, page, api, console, report)


async def run(only, collect_failures):
    ARTIFACTS.mkdir(parents=True, exist_ok=True); samples.ARTIFACTS = ARTIFACTS
    fixture = ROOT / 'web/node_modules/.s3-samples'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['samples.html', 'SamplesFixture.vue']: shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'scope': 'Actual SampleReviewDialog and request layer with synthetic API09 opinion limits. No backend/database validation.', 'checks': {}, 'http_checks': [], 'page_errors': [], 'console_errors': [], 'expected_console_errors': []}
    with (ARTIFACTS / 'vite.log').open('w') as log:
        process = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None: raise RuntimeError('Vite exited')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port); writer.close(); await writer.wait_closed(); break
                except OSError: await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'): options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                try:
                    for name, test in {'revise': switch_over_limit, 'others': ordinary_limits}.items():
                        if only and name not in only: continue
                        report['running'] = name
                        try: await test(browser, f'http://127.0.0.1:{port}', report)
                        except Exception as error:
                            if not collect_failures: raise
                            report.setdefault('failures', []).append({'group': name, 'message': type(error).__name__ + ': ' + str(error)})
                finally: await browser.close()
        except BaseException as error: report['failure'] = type(error).__name__ + ': ' + str(error); raise
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            files = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT / 'web/src').rglob('*')) if p.is_file()}
            report['source_sha256'] = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
            report['screenshots'] = sorted(p.name for p in ARTIFACTS.glob('*.png'))
            (ARTIFACTS / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert not report.get('failures'), report.get('failures')
    assert not report['page_errors'], report['page_errors']
    assert not report['console_errors'], report['console_errors']
    assert all(row['passed'] for row in report['checks'].values())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--only', nargs='+', choices=['revise', 'others']); parser.add_argument('--collect-failures', action='store_true'); parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.artifacts: ARTIFACTS = args.artifacts.resolve()
    asyncio.run(run(args.only, args.collect_failures))
