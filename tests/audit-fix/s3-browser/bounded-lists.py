#!/usr/bin/env python3
"""Real reorder/pending/stock components with explicit synthetic HTTP contracts.

These checks prove paging, preview and write-selection behavior, not backend SQL.
Vite and Chromium run in one process tree for isolated network namespaces.
"""
import asyncio
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/p34-35'
SKU_BASE = 9007199254740993


def material(index):
    return {
        'materialSkuId': str(SKU_BASE + index), 'skuCode': f'ML-{index:04d}',
        'materialId': str(SKU_BASE + 1000 + index), 'materialCode': f'M-{index:04d}',
        'materialName': f'合成面料{index:04d}', 'materialType': 'FABRIC', 'colorName': '卡其',
        'colorHex': '#bda46a', 'spec': '150cm', 'stockUnit': 'M', 'image': None,
    }


async def check_pages(browser, base, metrics):
    context = await browser.new_context(viewport={'width': 1920, 'height': 1080})
    reorder_requests = []
    aborted = []
    generated = []
    generated_skus = set()
    page_two_started = asyncio.Event()
    page_two_release = asyncio.Event()
    reload_started = asyncio.Event()
    reload_release = asyncio.Event()
    delay_page_two = True
    delay_reload = False
    fail_reorder = False
    empty_reorder = False
    fail_pending = False
    errors = []

    def reorder_row(index):
        draft_count = 125 if index == 1 else 1 if str(SKU_BASE + index) in generated_skus else 0
        drafts = [
            {'id': str(SKU_BASE + 5000 + index * 100 + n), 'no': f'CG-{index:04d}-{n + 1:02d}', 'status': 'DRAFT'}
            for n in range(min(draft_count, 20))
        ]
        return {**material(index), 'defaultSupplierId': str(SKU_BASE + 4000), 'defaultSupplierName': '合成面料供应商',
                'defaultSupplierStatus': 'ACTIVE', 'stockQty': 2, 'transitQty': 0, 'heldQty': 1,
                'safetyStock': 11, 'gapQty': 10, 'purchaseUnit': 'M', 'conversionFactor': 1,
                'suggestQty': 10, 'price': 7.5, 'openDrafts': drafts, 'openDraftsTotal': draft_count}

    def pending_row(index):
        populated = index == 1
        return {**material(index), 'purchaseItemId': str(SKU_BASE + 9000 + index), 'purchaseOrderId': str(SKU_BASE + 8000 + index),
                'purchaseNo': f'CG-WAIT-{index:03d}', 'purchaseType': 'STOCK', 'supplierId': str(SKU_BASE + 4000),
                'supplierName': '合成面料供应商', 'buyerId': str(SKU_BASE), 'buyerName': '合成采购员',
                'warehouseId': '1', 'warehouseName': '合成物料仓', 'purchaseUnit': 'M', 'qty': 100,
                'receivedQty': 20, 'returnedQty': 0, 'pendingQty': 80, 'dueDate': '2026-12-15',
                'expectDate': None, 'followNote': None, 'arriveDate': '2026-12-15', 'daysLeft': 23,
                'risk': 'NORMAL', 'needDate': '2026-12-16' if populated else None, 'cutLateDays': 0,
                'orderNos': [f'DD-SYN-{n:03d}' for n in range(1, 21)] if populated else [],
                'orderNosTotal': 31 if populated else 0,
                'styleCodes': [f'KS-SYN-{n:03d}' for n in range(1, 21)] if populated else [],
                'styleCodesTotal': 47 if populated else 0, 'sourcesTotal': 125 if populated else 0,
                'calcNo': 'SL-SYN-001' if populated else None, 'actions': ['receive'] if populated else []}

    async def api(route):
        nonlocal delay_page_two, delay_reload, fail_reorder, empty_reorder, fail_pending
        parsed = urlsplit(route.request.url)
        path = parsed.path
        if not path.startswith('/api/'):
            await route.continue_()
            return
        params = {key: value[0] for key, value in parse_qs(parsed.query).items()}
        if path.startswith('/api/dict-data/by-type/'):
            data = [{'label': '米', 'value': 'M', 'status': 1}, {'label': '面料', 'value': 'FABRIC', 'status': 1}]
        elif path == '/api/reorder-items':
            page = int(params['page']); size = int(params['size'])
            assert 1 <= size <= 100 and page >= 1, params
            reorder_requests.append({'page': page, 'size': size})
            if page == 2 and delay_page_two:
                delay_page_two = False
                page_two_started.set()
                await asyncio.wait_for(page_two_release.wait(), timeout=15)
                try:
                    await route.fulfill(json={'code': 0, 'msg': '', 'data': {'list': [reorder_row(n) for n in range(51, 101)], 'total': 999}})
                except Exception:
                    pass  # The real request must already have been aborted by page 3.
                return
            if delay_reload:
                delay_reload = False
                reload_started.set()
                await asyncio.wait_for(reload_release.wait(), timeout=15)
            if fail_reorder:
                fail_reorder = False
                await route.fulfill(json={'code': 503, 'msg': '合成补货清单暂不可用', 'data': None})
                return
            if empty_reorder:
                empty_reorder = False
                data = {'list': [], 'total': 0}
            else:
                start = (page - 1) * size + 1
                data = {'list': [reorder_row(n) for n in range(start, min(start + size, 236))], 'total': 235}
        elif path == '/api/purchase-orders/from-reorder':
            body = route.request.post_data_json
            assert isinstance(body, dict) and isinstance(body.get('items'), list), body
            items = body['items']
            assert all(isinstance(item['materialSkuId'], str) and isinstance(item['supplierId'], str) for item in items)
            generated.append({'items': items, 'idempotency_key_present': bool(re.fullmatch(r'[0-9a-f-]{36}', route.request.headers.get('idempotency-key', ''))), 'company_id': route.request.headers.get('x-company-id')})
            generated_skus.update(item['materialSkuId'] for item in items)
            await asyncio.sleep(.2)
            data = [{'id': str(SKU_BASE + 7000), 'no': 'CG-SYN-A'}, {'id': str(SKU_BASE + 7001), 'no': 'CG-SYN-B'}]
        elif path == '/api/purchase-orders/pending-items/counts':
            data = {'all': 2, 'late': 0, 'near': 0, 'cut': 0}
        elif path == '/api/purchase-orders/pending-items':
            if fail_pending:
                fail_pending = False
                await route.fulfill(json={'code': 503, 'msg': '合成待料清单暂不可用', 'data': None})
                return
            data = {'list': [pending_row(1), pending_row(2)], 'total': 2}
        elif path == '/api/warehouses/options':
            data = []
        elif path == '/api/stocks':
            data = {'list': [], 'total': 0}
        else:
            raise AssertionError(f'unexpected bounded-list API: {path}')
        await route.fulfill(json={'code': 0, 'msg': '', 'data': data})

    await context.route('**/api/**', api)

    async def open_page(view):
        page = await context.new_page()
        page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
        page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
        page.on('requestfailed', lambda request: aborted.append({'path': urlsplit(request.url).path, 'query': urlsplit(request.url).query, 'failure': request.failure}))
        await page.goto(base + '/node_modules/.s3-bounded/bounded-lists.html?view=' + view)
        return page

    try:
        page = await open_page('reorder')
        rows = page.locator('.el-table__body-wrapper tbody tr')
        generate = page.get_by_role('button', name=re.compile('^生成备货采购单'))
        await page.get_by_role('link', name='ML-0001', exact=True).wait_for()
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        assert reorder_requests == [{'page': 1, 'size': 50}], reorder_requests
        assert await rows.count() == 50
        assert await page.get_by_text('共 235 条', exact=True).count() == 1
        assert '49' in await generate.inner_text()
        assert not await rows.first.get_by_role('checkbox').is_checked()
        assert await rows.first.locator('.draft-chip').count() == 20
        await page.get_by_text('显示最近 20 张，共 125 张', exact=True).wait_for()
        await page.screenshot(path=str(ARTIFACTS / 'reorder-preview.png'))
        metrics['checks']['reorder_bounded_draft_preview'] = {'passed': True, 'shown': 20, 'total': 125, 'default_selected': 49}

        await rows.first.locator('label.el-checkbox').click()
        await rows.first.locator('.el-input-number input').fill('777')
        await rows.first.locator('.el-input-number input').press('Tab')
        await page.locator('.el-pagination .el-pager').get_by_text('2', exact=True).click()
        await asyncio.wait_for(page_two_started.wait(), timeout=10)
        assert await generate.is_disabled()
        assert '（' not in await generate.inner_text()
        await page.locator('.el-pagination .el-pager').get_by_text('3', exact=True).click()
        await page.get_by_role('link', name='ML-0101', exact=True).wait_for()
        page_two_release.set()
        await page.wait_for_timeout(300)
        assert await rows.count() == 50 and await page.get_by_role('link', name='ML-0051', exact=True).count() == 0
        assert await page.get_by_text('共 999 条', exact=True).count() == 0
        assert any(item['path'] == '/api/reorder-items' and 'page=2' in item['query'] and item['failure'] == 'net::ERR_ABORTED' for item in aborted), aborted
        metrics['checks']['reorder_page_abort_clears_old_selection'] = {'passed': True, 'cancelled_page': 2, 'shown_page': 3}

        await generate.evaluate('(button) => { button.click(); button.click() }')
        await page.wait_for_function("document.querySelectorAll('.draft-chip').length === 50")
        assert len(generated) == 1 and len(generated[0]['items']) == 50
        expected = {str(SKU_BASE + index) for index in range(101, 151)}
        assert {item['materialSkuId'] for item in generated[0]['items']} == expected
        assert all(item['qty'] == 10 for item in generated[0]['items'])
        assert generated[0]['idempotency_key_present'] and generated[0]['company_id'] == '1'
        assert await generate.is_disabled()
        metrics['checks']['reorder_generate_only_current_page'] = {'passed': True, 'post_requests': 1, 'item_count': 50, 'all_ids_strings': True, 'old_page_and_edited_qty_excluded': True, 'idempotency_key_present': True}

        await page.locator('.el-pagination .el-pager').get_by_text('1', exact=True).click()
        await page.get_by_role('link', name='ML-0001', exact=True).wait_for()
        assert await rows.first.locator('.el-input-number input').input_value() == '10.000'
        await rows.first.locator('label.el-checkbox').click()
        await rows.first.locator('.el-input-number input').fill('27')
        await rows.first.locator('.el-input-number input').press('Tab')
        delay_reload = True
        await page.get_by_role('button', name='重新取', exact=True).click()
        await asyncio.wait_for(reload_started.wait(), timeout=10)
        assert await generate.is_disabled() and '（' not in await generate.inner_text()
        reload_release.set()
        await page.get_by_role('link', name='ML-0001', exact=True).wait_for()
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        assert not await rows.first.get_by_role('checkbox').is_checked()
        assert await rows.first.locator('.el-input-number input').input_value() == '10.000'
        assert '49' in await generate.inner_text()
        metrics['checks']['reorder_reload_discards_selection_and_edits'] = {'passed': True, 'new_default_selected': 49}

        await page.locator('.el-pagination .el-pager').get_by_text('5', exact=True).click()
        await page.get_by_role('link', name='ML-0201', exact=True).wait_for()
        assert await rows.count() == 35
        await page.evaluate("window.scrollTo({top:0,behavior:'instant'})")
        await page.screenshot(path=str(ARTIFACTS / 'reorder-last-page.png'), full_page=True)
        await page.locator('.el-pagination .el-select__wrapper').click()
        await page.get_by_role('option', name='100条/页', exact=True).click()
        await page.get_by_role('link', name='ML-0001', exact=True).wait_for()
        assert await rows.count() == 100 and reorder_requests[-1] == {'page': 1, 'size': 100}, reorder_requests
        metrics['checks']['reorder_page_bounds_and_size_reset'] = {'passed': True, 'total': 235, 'default_size': 50, 'last_page_rows': 35, 'max_size': 100, 'size_change_returns_page': 1}

        fail_reorder = True
        await page.get_by_role('button', name='重新取', exact=True).click()
        await page.get_by_test_id('reorder-load-error').wait_for()
        assert await rows.count() == 0 and await generate.is_disabled()
        await page.wait_for_function("document.querySelectorAll('.el-message').length === 0")
        await page.screenshot(path=str(ARTIFACTS / 'reorder-error.png'))
        await page.get_by_test_id('reorder-load-error').get_by_role('button', name='重试').click()
        await page.get_by_role('link', name='ML-0001', exact=True).wait_for()
        assert await rows.count() == 100
        empty_reorder = True
        await page.get_by_role('button', name='重新取', exact=True).click()
        await page.get_by_text('没有低于最小库存的物料', exact=True).wait_for()
        assert await rows.count() == 0 and await generate.is_disabled()
        metrics['checks']['reorder_failure_retry_and_empty'] = {'passed': True}
        await page.close()

        page = await open_page('pending')
        await page.get_by_role('link', name='CG-WAIT-001', exact=True).wait_for()
        await page.get_by_text('订单 20 / 共 31', exact=True).wait_for()
        await page.get_by_text('款号 20 / 共 47', exact=True).wait_for()
        await page.get_by_text('来源算料单 125 张', exact=True).wait_for()
        assert await page.get_by_text('备货', exact=True).count() == 1
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        await page.screenshot(path=str(ARTIFACTS / 'pending-source-preview.png'))
        await page.locator('.source-codes').first.hover()
        tooltip = page.get_by_role('tooltip').filter(has_text='DD-SYN-020')
        await tooltip.wait_for()
        await page.wait_for_function("[...document.querySelectorAll('[role=tooltip]')].some(node => node.textContent.includes('DD-SYN-020') && getComputedStyle(node).opacity === '1')")
        tooltip_box = await tooltip.bounding_box()
        assert tooltip_box and tooltip_box['width'] <= 520 and tooltip_box['x'] >= 0 and tooltip_box['x'] + tooltip_box['width'] <= 1920, tooltip_box
        assert 'DD-SYN-021' not in await tooltip.inner_text()
        await page.screenshot(path=str(ARTIFACTS / 'pending-source-tooltip.png'))
        metrics['checks']['pending_independent_preview_totals'] = {'passed': True, 'orders_shown': 20, 'orders_total': 31, 'styles_shown': 20, 'styles_total': 47, 'source_calculations_total': 125}
        await page.locator('.el-table__body-wrapper tbody tr').first.locator('label.el-checkbox').click()
        await page.get_by_role('toolbar', name='批量操作').wait_for()
        fail_pending = True
        search = page.get_by_placeholder('采购单号、物料、供应商、订单号、款号')
        await search.fill('合成查询')
        await search.press('Enter')
        await page.get_by_test_id('pending-load-error').wait_for()
        assert await page.locator('.el-table__body-wrapper tbody tr').count() == 0
        await page.get_by_test_id('pending-load-error').get_by_role('button', name='重试').click()
        await page.get_by_text('来源算料单 125 张', exact=True).wait_for()
        checked = await page.locator('.el-table__body-wrapper tbody tr').first.get_by_role('checkbox').is_checked()
        toolbar = await page.get_by_role('toolbar', name='批量操作').count()
        assert checked == bool(toolbar), {'checked': checked, 'toolbar_count': toolbar}
        metrics['checks']['pending_failure_retry'] = {'passed': True, 'selection_consistent_after_retry': True}
        await page.close()

        page = await open_page('stock')
        await page.get_by_role('button', name=re.compile('补货提醒.*235')).wait_for()
        assert reorder_requests[-1] == {'page': 1, 'size': 1}, reorder_requests[-1]
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        await page.screenshot(path=str(ARTIFACTS / 'stock-reorder-total.png'))
        metrics['checks']['stock_badge_uses_total_with_one_row'] = {'passed': True, 'fetch_size': 1, 'displayed_total': 235}
        await page.close()
        metrics['reorder_requests'] = reorder_requests
        metrics['aborted_requests'] = aborted
        metrics['console_errors'] = errors
        assert not errors, errors
    except Exception:
        await page.screenshot(path=str(ARTIFACTS / 'failure.png'))
        raise
    finally:
        await context.close()


async def run():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-bounded'
    fixture.mkdir(parents=True, exist_ok=True)
    for name in ['bounded-lists.html', 'BoundedListsFixture.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    metrics = {'scope': 'real production components with synthetic HTTP; backend SQL is covered separately by S2', 'checks': {}, 'page_errors': []}
    with (ARTIFACTS / 'vite.log').open('w') as log:
        process = subprocess.Popen(['pnpm', 'exec', 'vite', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None:
                    raise RuntimeError('Vite exited; see p34-35/vite.log')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close(); await writer.wait_closed(); break
                except OSError:
                    await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'):
                    options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                try:
                    await check_pages(browser, f'http://127.0.0.1:{port}', metrics)
                finally:
                    await browser.close()
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            (ARTIFACTS / 'results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    assert not metrics['page_errors'], metrics['page_errors']
    assert len(metrics['checks']) == 9 and all(check['passed'] for check in metrics['checks'].values()), metrics['checks']


if __name__ == '__main__':
    asyncio.run(run())
