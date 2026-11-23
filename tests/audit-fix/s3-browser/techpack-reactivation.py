#!/usr/bin/env python3
"""Actual cached Style detail + TechPackTab, strict synthetic HTTP only."""
import argparse
import asyncio
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import TimeoutError as PlaywrightTimeoutError, async_playwright, expect
import samples
from sample_http import SampleHttp, PACK, SAMPLE, STYLE, pack
from wide_pack_checks import run_wide_pack

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/techpack-reactivation'
PERMISSIONS = ['style:style:list', 'techpack:pack:list', 'techpack:pack:edit', 'techpack:cost:query']
COST_PATH = '/api/tech-packs/' + PACK + '/cost'


class PackHttp(SampleHttp):
    def __init__(self):
        super().__init__()
        self.style_pack = {**pack(), 'styleId': STYLE, 'sampleId': None, 'companyId': None, 'stage': 'PROD',
                           'styleCode': 'KS-SYN-001', 'laborTotal': 0, 'actions': ['edit']}
        self.cost_calls = 0
        self.resumed = asyncio.Event()
        self.default_calls = 0

    async def route(self, route):
        req = route.request
        url = urlsplit(req.url)
        if not url.path.startswith('/api/tech-packs'):
            return await super().route(route)
        task = asyncio.current_task(); self.tasks.add(task)
        p = parse_qs(url.query)
        self.calls.append({'method': req.method, 'path': url.path, 'params': p, 'company': req.headers.get('x-company-id'), 'requestId': req.headers.get('x-request-id')})
        held = self.holds.get((url.path, None))
        if held and held['started'].is_set(): held = None
        try:
            assert req.headers.get('x-request-id')
            assert req.headers.get('x-company-id') is None, 'Shared Style owner must not inherit an unrelated company'
            if url.path.endswith('/bom-defaults'):
                assert req.method == 'POST' and req.post_data_json == {'items': [{'materialId': '51', 'supplierId': None, 'skuIds': ['61']}]}
                self.default_calls += 1
                data = [{'materialId': '51', 'unit': 'M', 'purchaseUnit': 'M', 'conversionFactor': 1, 'supplierId': None,
                         'supplierName': None, 'lossPct': 0, 'source': 'PURCHASE', 'refPrice': 7.5, 'priceSourceId': None,
                         'priceTaxIncluded': True, 'priceFreightIncluded': True, 'priceFrom': 'MATERIAL', 'skuPrices': []}]
            else:
                assert req.method == 'GET', 'Reactivation must never replay a write'
                if url.path == COST_PATH: self.cost_calls += 1
                if held:
                    held['started'].set(); await asyncio.wait_for(held['release'].wait(), 20)
                data = self.read(url.path, p)
            await route.fulfill(json={'code': 0, 'msg': '', 'data': data}, headers={'X-Request-Id': 'synthetic-pack-reactivation'})
            if url.path == COST_PATH and self.cost_calls >= 2: self.resumed.set()
        except asyncio.CancelledError: raise
        except Exception as error:
            self.errors.append(type(error).__name__ + ': ' + str(error))
            try: await route.fulfill(status=500, json={'code': 500, 'msg': 'synthetic contract mismatch', 'data': None})
            except Exception: pass
        finally:
            if held: held['finished'].set()
            self.tasks.discard(task)

    def read(self, path, p):
        if path == '/api/tech-packs':
            assert p == {'styleId': [STYLE]}
            return {'styleId': STYLE, 'packs': [deepcopy(self.style_pack)], 'defaultPackId': PACK,
                    'creatableStages': [], 'unmatchedColors': [], 'unitCost': None, 'unitCostPackId': PACK, 'unitCostStage': 'PROD'}
        if path == '/api/tech-packs/' + PACK:
            assert not p
            return deepcopy(self.style_pack)
        if path == COST_PATH:
            assert p == {'colorId': ['21']}
            return {**super().read(path, p), 'otherItems': [{'name': '合成核价项', 'amount': 42.75, 'remark': None}],
                    'summary': {'fabric': 0, 'trim': 0, 'labor': 0, 'other': 42.75, 'total': 42.75}}
        if path == '/api/material-categories/tree': return []
        if path == '/api/materials/pick':
            assert p.get('page') == ['1'] and p.get('size') == ['20']
            return {'list': [{'skuId': '61', 'skuCode': 'ML-SYN-BL', 'materialId': '51', 'materialCode': 'ML-SYN',
                 'materialName': '切页后新增合成面料', 'materialType': 'FABRIC', 'categoryId': '71', 'categoryName': '面料',
                 'colorId': '21', 'colorCode': 'BK', 'colorName': '黑色', 'colorHex': '#202020', 'spec': '150cm',
                 'stockUnit': 'M', 'purchaseUnit': 'M', 'conversionFactor': 1, 'price': 7.5, 'image': None}], 'total': 1}
        return super().read(path, p)


async def navigate_away_and_back(page):
    await page.get_by_test_id('sample-home').click()
    await expect(page.get_by_test_id('sample-path')).to_have_text('/home')
    await page.get_by_role('navigation', name='打开的页面').get_by_role('link', name='款式 KS-SYN-001', exact=True).click()
    await expect(page.get_by_test_id('sample-path')).to_have_text('/styles/' + STYLE)


async def check_cost(browser, base, report):
    api = PackHttp(); held = api.hold(COST_PATH)
    context, page, api, console = await samples.start(browser, base, report,
        '/styles/' + STYLE + '?tab=techpack&sub=cost', api=api, permissions=PERMISSIONS)
    try:
        await asyncio.wait_for(held['started'].wait(), 5)
        await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
        initial_aborts = [row for row in api.aborted if row['path'] == COST_PATH]
        initial_passed = api.cost_calls == 1 and not initial_aborts
        report['checks']['style_first_cost_read_is_single'] = {
            'passed': initial_passed, 'reads': api.cost_calls, 'aborted': initial_aborts}
        assert initial_passed, report['checks']['style_first_cost_read_is_single']
        await expect(page.get_by_text('核价加载中，其他页签可继续编辑…', exact=True)).to_be_visible()
        await page.get_by_placeholder('本版说明（选填）', exact=True).fill('切页前未保存的说明')
        await samples.capture(page, 'style-cost-pending.png')
        await page.get_by_test_id('sample-home').click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/home')
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await page.get_by_role('navigation', name='打开的页面').get_by_role('link', name='款式 KS-SYN-001', exact=True).click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/styles/' + STYLE)
        try: await asyncio.wait_for(api.resumed.wait(), 3)
        except asyncio.TimeoutError: pass
        await samples.capture(page, 'style-return-cost.png')
        loading = await page.get_by_text('核价加载中，其他页签可继续编辑…', exact=True).is_visible()
        cost_visible = await page.locator('.cost-total').is_visible()
        source_aborts = [x for x in api.aborted if x['path'] == COST_PATH]
        passed = api.cost_calls == 2 and not loading and cost_visible and bool(source_aborts)
        report['checks']['style_cost_resumes_after_cached_page_activation'] = {'passed': passed,
            'cost_calls': api.cost_calls, 'loading': loading, 'cost_visible': cost_visible, 'source_aborts': source_aborts, 'writes': len(api.writes)}
        assert passed, report['checks']['style_cost_resumes_after_cached_page_activation']
        await expect(page.locator('.cost-total')).to_contain_text('¥42.75')
        await expect(page.get_by_placeholder('本版说明（选填）', exact=True)).to_have_value('切页前未保存的说明')
        await page.wait_for_function("() => Object.keys(localStorage).filter(key => key.startsWith('lingmao-draft:')).some(key => JSON.parse(localStorage.getItem(key)).data?.versionNote === '切页前未保存的说明')")
        samples.check(report, 'draft_persistence_resumes_after_cost', note_preserved=True, writes=len(api.writes))
        await samples.capture(page, 'style-return-draft-saved.png')
        assert not api.writes
    finally:
        held['release'].set()
        await samples.finish(context, page, api, console, report)


async def check_material(browser, base, report):
    context, page, api, console = await samples.start(browser, base, report,
        '/styles/' + STYLE + '?tab=techpack', api=PackHttp(), permissions=PERMISSIONS)
    try:
        await page.get_by_role('tab', name='核价', exact=True).click()
        await expect(page.locator('.cost-total')).to_contain_text('¥42.75')
        await navigate_away_and_back(page)
        await page.get_by_role('tab', name='物料清单（0）', exact=True).click()
        await page.get_by_role('button', name='从物料库选', exact=True).click()
        picker = page.get_by_role('dialog', name='从物料库选', exact=True)
        await picker.locator('.el-table__body-wrapper tbody tr').first.click()
        await picker.get_by_role('button', name='加入 1 条', exact=True).click()
        await expect(picker).not_to_be_visible()
        try: await page.get_by_role('tab', name='物料清单（1）', exact=True).wait_for(timeout=3000)
        except PlaywrightTimeoutError: pass
        await samples.capture(page, 'style-return-add-material.png')
        visible = await page.get_by_role('tab', name='物料清单（1）', exact=True).is_visible()
        passed = visible and api.default_calls == 1 and api.cost_calls == 1
        report['checks']['style_material_picker_works_after_cached_page_activation'] = {'passed': passed, 'material_rows': 1 if visible else 0, 'default_reads': api.default_calls, 'cost_calls': api.cost_calls, 'writes': len(api.writes)}
        assert passed, report['checks']['style_material_picker_works_after_cached_page_activation']
        assert not api.writes
    finally: await samples.finish(context, page, api, console, report)


async def check_wide(browser, base, report):
    await run_wide_pack(browser, base, ARTIFACTS, report)


async def check_pending_sample(browser, base, report, kind):
    api = SampleHttp(packs=1)
    target = '/api/tech-packs' if kind == 'overview' else '/api/tech-packs/' + PACK
    held = api.hold(target)
    context, page, api, console = await samples.start(browser, base, report,
        '/samples/' + SAMPLE + '?companyId=1&tab=techpack', api=api,
        permissions=PERMISSIONS + ['dev:sample:list'])
    try:
        await asyncio.wait_for(held['started'].wait(), 5)
        await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
        initial_reads = len([call for call in api.calls if call['path'] == target])
        initial_aborts = [row for row in api.aborted if row['path'] == target]
        initial_passed = initial_reads == 1 and not initial_aborts
        report['checks']['sample_first_' + kind + '_read_is_single'] = {
            'passed': initial_passed, 'reads': initial_reads, 'aborted': initial_aborts}
        await samples.capture(page, 'sample-initial-' + kind + '.png')
        assert initial_passed, report['checks']['sample_first_' + kind + '_read_is_single']
        await page.get_by_test_id('sample-home').click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/home')
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await page.get_by_role('navigation', name='打开的页面').get_by_role('link', name='样衣 YY-SYN-001', exact=True).click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/samples/' + SAMPLE)
        try:
            await expect(page.locator('.tp-wrap .pack-meta')).to_be_visible(timeout=3000)
            await expect(page.locator('.tp-wrap .el-loading-mask')).to_have_count(0, timeout=3000)
        except AssertionError: pass
        await samples.capture(page, 'sample-return-' + kind + '.png')
        requests = len([call for call in api.calls if call['path'] == target])
        masks = await page.locator('.tp-wrap .el-loading-mask').count()
        visible = await page.locator('.tp-wrap .pack-meta').is_visible()
        aborted = [row for row in api.aborted if row['path'] == target]
        passed = requests == 2 and masks == 0 and visible and bool(aborted)
        report['checks']['sample_' + kind + '_resumes_after_cached_page_activation'] = {
            'passed': passed, 'reads': requests, 'loading_masks': masks, 'detail_visible': visible,
            'aborted': aborted, 'writes': len(api.writes)}
        assert passed, report['checks']['sample_' + kind + '_resumes_after_cached_page_activation']
        assert not api.writes
    finally:
        held['release'].set()
        await samples.finish(context, page, api, console, report)


async def run(only, collect_failures):
    ARTIFACTS.mkdir(parents=True, exist_ok=True); samples.ARTIFACTS = ARTIFACTS
    fixture = ROOT / 'web/node_modules/.s3-samples'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['samples.html', 'SamplesFixture.vue']: shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    wide_fixture = ROOT / 'web/node_modules/.s3-audit'
    if only and 'wide' in only:
        wide_fixture.mkdir(parents=True, exist_ok=True)
        for name in ['home-review.html', 'HomeReviewFixture.vue', 'TechPackFixture.vue', 'UpstreamFixture.vue']:
            shutil.copyfile(Path(__file__).with_name(name), wide_fixture / name)
    with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'scope': 'Actual cached Style detail and TechPackTab with strict synthetic HTTP; no backend/database claim.',
              'checks': {}, 'http_checks': [], 'page_errors': [], 'console_errors': [], 'expected_console_errors': []}
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
                    tests = {'cost': check_cost, 'material': check_material,
                             'overview': lambda b, u, r: check_pending_sample(b, u, r, 'overview'),
                             'pack': lambda b, u, r: check_pending_sample(b, u, r, 'pack')}
                    if only and 'wide' in only: tests['wide'] = check_wide
                    for name, test in tests.items():
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
            if only and 'wide' in only: shutil.rmtree(wide_fixture, ignore_errors=True)
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
    parser = argparse.ArgumentParser(); parser.add_argument('--only', nargs='+', choices=['cost', 'material', 'overview', 'pack', 'wide']); parser.add_argument('--collect-failures', action='store_true'); parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.artifacts: ARTIFACTS = args.artifacts.resolve()
    asyncio.run(run(args.only, args.collect_failures))
