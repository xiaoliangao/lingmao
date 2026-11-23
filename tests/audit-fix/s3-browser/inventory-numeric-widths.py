#!/usr/bin/env python3
"""Actual inventory detail + AppLayout; synthetic, read-only HTTP boundary render.

Checks the additional purchase-unit, available-stock and Integer roll-count cells.
This is frontend evidence only. The existing real PG test_23_inventory_large_aggregates
remains unchanged and must still pass for actual quantity/price/amount evidence.
"""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
from urllib.parse import urlencode, urlsplit

from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/inventory-numeric-widths'
ID = '9007199254748001'
BASE = '/api/inventory-docs/' + ID
MAX_QTY = '99999999999.999'


def document(kind):
    purchase = kind == 'purchase'
    line = {'id': '9007199254748002', 'seq': 1, 'materialSkuId': '9007199254748003',
            'materialId': '9007199254748004', 'materialCode': 'ML-SYN-BOUND',
            'skuCode': 'ML-SYN-BOUND-BL', 'materialName': '合成数值边界面料',
            'colorName': '蓝色', 'stockUnit': 'KG', 'posted': False,
            'qty': MAX_QTY, 'purchaseUnit': 'CM' if purchase else None,
            'purchaseQty': MAX_QTY if purchase else None,
            'conversionFactor': '1' if purchase else None, 'price': '0', 'amount': '0.00',
            'dyeLot': None, 'rolls': 2147483647, 'sourceItemId': None,
            'overReason': None, 'remark': None, 'fromStockQty': None if purchase else MAX_QTY}
    return {'id': ID, 'no': 'RK-SYN-BOUND' if purchase else 'CK-SYN-BOUND', 'version': 1,
            'status': 'DRAFT', 'companyId': '1', 'companyName': '合成主体甲',
            'kind': 'IN' if purchase else 'OUT', 'docType': 'PURCHASE_IN' if purchase else 'OTHER_OUT',
            'docDate': '2026-11-23', 'reason': 'OTHER', 'source': None,
            'fromWarehouseId': None if purchase else '31', 'fromWarehouseName': None if purchase else '合成物料仓',
            'toWarehouseId': '31' if purchase else None, 'toWarehouseName': '合成物料仓' if purchase else None,
            'partnerName': '合成供应商' if purchase else None, 'posted': False, 'actions': [],
            'totalQty': MAX_QTY, 'totalAmount': '0.00', 'items': [line],
            'createByName': '合成制单员', 'createTime': '2026-11-23 08:30:00', 'remark': None}


class Api:
    def __init__(self, kind):
        self.kind, self.calls, self.errors = kind, [], []
        self.detail_reads = 0

    async def route(self, route):
        request = route.request
        path = urlsplit(request.url).path
        if not path.startswith('/api/'):
            await route.continue_()
            return
        try:
            assert request.method == 'GET', 'This layout gate may not write business data'
            self.calls.append({'method': request.method, 'path': path, 'companyId': request.headers.get('x-company-id')})
            if path == BASE:
                company = request.headers.get('x-company-id')
                if self.detail_reads == 0:
                    # Existing authorized-detail bootstrap: CompanyContext.target
                    # permits a missing header on this initial GET only. The real
                    # component then binds all object reads to detail.companyId.
                    assert company in (None, '1'), (path, 'Invalid bootstrap company', company)
                else:
                    assert company == '1', (path, 'Expected loaded-document company 1', company)
                self.detail_reads += 1
                data = document(self.kind)
            elif path in (BASE + '/attachments', BASE + '/logs'):
                assert self.detail_reads > 0 and request.headers.get('x-company-id') == '1', (path, 'Missing loaded-document company')
                data = []
            elif path == '/api/tags/objects/INV_DOC/' + ID:
                assert self.detail_reads > 0 and request.headers.get('x-company-id') == '1', (path, 'Missing loaded-document company')
                data = {'version': 0, 'tags': []}
            elif path == '/api/dict-data/by-type/unit':
                data = [{'id': '1', 'value': 'KG', 'label': '公斤', 'status': 1, 'sort': 1},
                        {'id': '2', 'value': 'CM', 'label': '厘米', 'status': 1, 'sort': 2}]
            elif path == '/api/dict-data/by-type/season':
                # DataPick is mounted by the unopened InvDocFormDialog. These
                # exact shared dictionary values follow V11__sys_dict_user.sql.
                data = [{'id': str(21 + index), 'value': value, 'label': label, 'status': 1, 'sort': index + 1}
                        for index, (value, label) in enumerate((('SPRING', '春'), ('SUMMER', '夏'),
                                                              ('AUTUMN', '秋'), ('WINTER', '冬')))]
            elif path in ('/api/dict-data/by-type/stock_in_reason', '/api/dict-data/by-type/stock_out_reason'):
                data = []
            elif path == '/api/version':
                data = {'version': 'synthetic-inventory-width', 'buildTime': None}
            else:
                raise AssertionError('Unexpected HTTP read: ' + path)
            await route.fulfill(json={'code': 0, 'msg': '', 'data': data})
        except Exception as error:
            self.errors.append(type(error).__name__ + ': ' + str(error))
            raise


async def frame(page):
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')


async def inspect(browser, base, report, kind, width):
    context = await browser.new_context(viewport={'width': width, 'height': 1000})
    page = await context.new_page()
    page.set_default_timeout(12000)
    api = Api(kind)
    await context.route(lambda url: urlsplit(url).path.startswith('/api/'), api.route)
    page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
    page.on('console', lambda message: report['console_errors'].append(message.text) if message.type == 'error' else None)
    try:
        path = ('/stock-in/' if kind == 'purchase' else '/stock-out/') + ID + '?companyId=1'
        await page.goto(base + '/node_modules/.s3-inventory-numeric/inventory-numeric-widths.html?' + urlencode({'path': path}))
        table = page.locator('.body > .el-table')
        await expect(table.locator('.el-table__body-wrapper tbody tr')).to_have_count(1)
        labels = [text.strip() for text in await table.locator('.el-table__header-wrapper th').all_text_contents()]
        expected = {'数量': MAX_QTY + ' 公斤', '卷数': '2147483647', '单价': '0', '金额': '0.00'}
        expected['采购单位数量' if kind == 'purchase' else '出仓库存'] = MAX_QTY + (' 厘米' if kind == 'purchase' else '')
        measurements = {}
        await frame(page)
        await page.screenshot(path=str(ARTIFACTS / f'{kind}-{width}-left.png'), animations='disabled')
        for label, text in expected.items():
            cell = table.locator('.el-table__body-wrapper tbody tr td').nth(labels.index(label)).locator('.cell')
            await expect(cell).to_have_text(text)
            await cell.scroll_into_view_if_needed()
            await frame(page)
            measured = await cell.evaluate('''element => {
              const range = document.createRange(); range.selectNodeContents(element);
              const style = getComputedStyle(element), bounds = element.getBoundingClientRect();
              return {text: element.textContent.trim(), textWidth: range.getBoundingClientRect().width,
                availableWidth: element.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight),
                left: bounds.left, right: bounds.right, viewport: innerWidth};
            }''')
            measurements[label] = measured
            assert measured['textWidth'] <= measured['availableWidth'] + 1, (label, measured)
            assert 0 <= measured['left'] < measured['right'] <= width + 1, (label, measured)
        geometry = await page.evaluate('''() => {
          const aside = document.querySelector('.doc-aside').getBoundingClientRect();
          const scroll = document.querySelector('.body .el-table__body-wrapper .el-scrollbar__wrap');
          return {documentWidth: document.documentElement.scrollWidth, asideWidth: aside.width,
            asideRight: aside.right, tableWidth: scroll.clientWidth, tableContentWidth: scroll.scrollWidth};
        }''')
        assert geometry['documentWidth'] <= width + 1 and geometry['asideRight'] <= width + 1, geometry
        assert geometry['tableContentWidth'] > geometry['tableWidth'], geometry
        if width == 1440:
            assert geometry['asideWidth'] == 300, geometry
        # Per-cell visibility checks can return to a left-hand column. Capture
        # the named right screenshot only after reaching the actual scroll end.
        scroll = table.locator('.el-table__body-wrapper .el-scrollbar__wrap')
        await scroll.evaluate('element => { element.scrollLeft = element.scrollWidth; }')
        await frame(page)
        edge = await scroll.evaluate('element => ({left: element.scrollLeft, max: element.scrollWidth - element.clientWidth})')
        assert edge['max'] > 0 and abs(edge['left'] - edge['max']) <= 1, edge
        geometry['rightScreenshotScroll'] = edge
        await page.screenshot(path=str(ARTIFACTS / f'{kind}-{width}-right.png'), animations='disabled')
        report['checks'][f'{kind}_{width}'] = {'passed': True, 'measurements': measurements, 'geometry': geometry}
    finally:
        # Route callbacks can also surface errors while closing. Preserve the
        # original DOM/assertion failure and record cleanup evidence separately.
        original = sys.exc_info()[1]
        report['api_calls'][f'{kind}_{width}'] = list(api.calls)
        report['api_errors'][f'{kind}_{width}'] = list(api.errors)
        try:
            await context.close()
        except Exception as cleanup:
            report['cleanup_errors'].append('context: ' + type(cleanup).__name__ + ': ' + str(cleanup))
            if original is None:
                raise
        if original is None:
            assert not api.errors, list(api.errors)


async def run():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-inventory-numeric'
    fixture.mkdir(parents=True, exist_ok=True)
    for name in ('inventory-numeric-widths.html', 'InventoryNumericFixture.vue'):
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    report = {'scope': 'Actual detail/AppLayout with synthetic read-only HTTP; not PG evidence',
              'scope_limit': 'Only the initial exact inventory detail GET may resolve company without a header, matching existing authorized-detail bootstrap. All subsequent object reads require company 1. Shared dictionaries/version are global. This layout fixture does not verify company membership ACL (M01).',
              'checks': {}, 'page_errors': [], 'console_errors': [], 'api_calls': {}, 'api_errors': {}, 'cleanup_errors': []}
    with (ARTIFACTS / 'vite.log').open('w') as log:
        server = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if server.poll() is not None:
                    raise RuntimeError('Vite exited')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close(); await writer.wait_closed(); break
                except OSError:
                    await asyncio.sleep(.1)
            async with async_playwright() as pw:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'):
                    options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await pw.chromium.launch(**options)
                try:
                    for kind in ('purchase', 'outbound'):
                        for width in (1440, 1180):
                            await inspect(browser, f'http://127.0.0.1:{port}', report, kind, width)
                finally:
                    original = sys.exc_info()[1]
                    try:
                        await browser.close()
                    except Exception as cleanup:
                        report['cleanup_errors'].append('browser: ' + type(cleanup).__name__ + ': ' + str(cleanup))
                        if original is None:
                            raise
            assert not report['page_errors'] and not report['console_errors'], report
        except Exception as error:
            report['failure'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            server.terminate()
            try: server.wait(timeout=10)
            except subprocess.TimeoutExpired: server.kill(); server.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            report['source_sha256'] = hashlib.sha256((ROOT / 'web/src/views/inventory/doc/detail.vue').read_bytes()).hexdigest()
            (ARTIFACTS / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    asyncio.run(run())
