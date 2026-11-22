#!/usr/bin/env python3
"""P38/P39/P40: actual selectors, forms and AppLayout, explicit synthetic HTTP."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[3]
BASE_ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/p38-39'


def purchase_document(line_count=2):
    lines = []
    for index in range(1, line_count + 1):
        lines.append({'id': str(500 + index), 'seq': index, 'materialSkuId': str(600 + index), 'skuCode': f'ML-SYN-{index}',
            'materialId': str(700 + index), 'materialCode': f'ML-SYN-{index}', 'materialName': '合成面料', 'materialType': 'FABRIC',
            'colorName': '蓝色', 'colorHex': None, 'spec': None, 'stockUnit': 'M', 'image': None, 'supplierItemNo': None,
            'supplierColorNo': None, 'purchaseUnit': 'M', 'conversionFactor': 1, 'qty': 0.018, 'price': 7.5, 'amount': 0.14,
            'priceSourceId': None, 'archivePrice': 7.5, 'priceChangeReason': None, 'lineDueDate': None, 'dueDate': '2026-12-15',
            'calcItemId': None, 'calcId': None, 'calcNo': None, 'orderNos': [], 'styleCodes': [], 'receivedQty': 0,
            'returnedQty': 0, 'pendingQty': 0.018, 'expectDate': None, 'followNote': None})
    return {'id': '9007199254744001', 'no': 'CG-SYN-001', 'version': 1, 'status': 'DRAFT', 'companyId': '1', 'companyName': '合成主体甲',
        'remark': None, 'createBy': '9007199254740993', 'createByName': '合成', 'createTime': '2026-11-23 10:00:00',
        'updateTime': '2026-11-23 10:00:00', 'auditBy': None, 'auditByName': None, 'auditTime': None, 'actions': ['edit'],
        'approval': None, 'pendingNotices': 0, 'supplierId': '3', 'supplierCode': 'GC-3', 'supplierName': '工厂甲',
        'supplierShortName': '工厂甲', 'supplierStatus': 'ACTIVE', 'purchaseType': 'STOCK', 'calcId': None, 'calcNo': None,
        'buyerId': '9007199254740993', 'buyerName': '合成采购员', 'orderDate': '2026-11-23', 'deliveryDate': '2026-12-15',
        'warehouseId': '21', 'warehouseName': '合成面料仓', 'warehouseType': 'MATERIAL', 'warehouseAddress': None,
        'settleMethod': 'MONTHLY', 'taxPct': 0, 'taxIncluded': True, 'freightIncluded': True, 'totalQty': 0.036,
        'totalAmount': 0.28, 'receivedQty': 0, 'returnedQty': 0, 'planStyles': [], 'items': lines, 'stockDocs': []}


class SyntheticApi:
    def __init__(self, purchase_rows=2):
        self.purchase_rows = purchase_rows
        self.calls = []
        self.delays = {}
        self.identity = 1

    def hold(self, selected, company='1'):
        held = {name: asyncio.Event() for name in ('started', 'release', 'finished')}
        self.delays[(selected, company)] = held
        return held

    async def route(self, route):
        url = urlsplit(route.request.url)
        if not url.path.startswith('/api/'):
            await route.continue_(); return
        assert route.request.method == 'GET', 'This UI regression must not write business data'
        params = parse_qs(url.query)
        call = {'path': url.path, 'include_id': params.get('includeId', [None])[0],
                'company': route.request.headers.get('x-company-id')}
        self.calls.append(call)
        held = None
        if url.path == '/api/version':
            data = {'version': 'synthetic-p40', 'buildTime': None}
        elif url.path.startswith('/api/dict-data/by-type/'):
            data = []
        elif url.path == '/api/companies/options':
            data = [{'id': '1', 'code': 'C-1', 'shortName': '合成主体甲'}, {'id': '2', 'code': 'C-2', 'shortName': '合成主体乙'}]
        elif url.path == '/api/warehouses/options':
            data = [{'id': '11', 'code': 'W-11', 'name': '甲工厂仓', 'type': 'FACTORY', 'factoryId': '3'},
                    {'id': '12', 'code': 'W-12', 'name': '乙工厂仓', 'type': 'FACTORY', 'factoryId': '4'},
                    {'id': '21', 'code': 'W-21', 'name': '合成面料仓', 'type': 'MATERIAL', 'factoryId': None}]
        elif url.path == '/api/stocks/qty':
            data = [{'materialSkuId': '601', 'qty': 10}]
        elif url.path == '/api/purchase-orders/9007199254744001':
            data = purchase_document(self.purchase_rows)
        elif url.path == '/api/suppliers/options':
            selected = call['include_id']
            if selected == '998':
                await route.fulfill(status=503, json={'code': 503, 'msg': '合成供应商名称读取故障', 'data': None}); return
            names = {'3': '工厂甲', '4': '工厂乙', '5': '旧慢工厂', '6': '新快工厂'}
            option_id = selected if selected in names else '3'
            name = names[option_id]
            if call['company'] == '2': name = '二主体' + name
            if self.identity == 2: name = '新账号' + name
            data = [{'id': option_id, 'code': 'GC-' + option_id, 'shortName': name, 'status': 'ACTIVE'}]
            key = (selected, call['company'])
            held = self.delays.pop(key, None)
            if held:
                held['started'].set()
                await asyncio.wait_for(held['release'].wait(), timeout=15)
        else:
            raise AssertionError('Unexpected API: ' + url.path)
        try:
            await route.fulfill(json={'code': 0, 'msg': '', 'data': data})
        except Exception:
            if url.path != '/api/suppliers/options': raise
        finally:
            if held: held['finished'].set()


def field(container, label):
    return container.locator('.el-form-item').filter(has=container.page.locator('.el-form-item__label').filter(has_text=re.compile(r'^\s*' + re.escape(label) + r'\s*$')))


async def selected_text(container):
    values = [text.strip() for text in await container.locator('.el-select__selected-item:visible').all_inner_texts() if text.strip()]
    assert len(values) == 1, values
    return values[0]


async def settle_render(page):
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')


async def context_page(browser, base, metrics, view='supplier', *, app_layout=False, purchase_rows=2):
    context = await browser.new_context(viewport={'width': 1440, 'height': 1000})
    api = SyntheticApi(purchase_rows)
    errors = []
    aborted = []
    def observe(page):
        page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
        page.on('console', lambda message: errors.append({'text': message.text, 'path': urlsplit(message.location.get('url', '')).path}) if message.type == 'error' else None)
        page.on('requestfailed', lambda request: aborted.append({'path': urlsplit(request.url).path, 'query': urlsplit(request.url).query, 'failure': request.failure}))
    context.on('page', observe)
    await context.route('**/api/**', api.route)
    page = await context.new_page()
    await page.goto(base + '/node_modules/.s3-supplier-amounts/supplier-amounts.html?view=' + view + ('&layout=app' if app_layout else ''))
    if app_layout: await page.locator('.shell .shell-main').wait_for()
    else: await page.get_by_test_id('route').wait_for()
    return context, page, api, errors, aborted


async def check_forms(browser, base, metrics, artifacts, baseline):
    context, page, api, errors, aborted = await context_page(browser, base, metrics)
    try:
        await page.get_by_role('button', name='新建退料入库', exact=True).click()
        dialog = page.get_by_role('dialog', name='新建退料入库', exact=True)
        await expect(field(dialog, '出仓')).to_contain_text('甲工厂仓')
        supplier = field(dialog, '往来加工厂')
        if baseline: await page.wait_for_timeout(800)
        else: await expect(supplier).to_contain_text('工厂甲')
        text = await selected_text(supplier)
        metrics['checks']['return_preselection_without_dropdown'] = {'passed': text == '工厂甲', 'displayed': text,
            'dropdown_never_opened': True, 'options_calls': [c for c in api.calls if c['path'] == '/api/suppliers/options']}
        await page.screenshot(animations='disabled', path=str(artifacts / 'return-preselected.png'))
        await dialog.get_by_role('button', name='取消', exact=True).click()
        await expect(dialog).not_to_be_visible()
        await page.get_by_role('button', name='编辑退料及金额', exact=True).click()
        dialog = page.get_by_role('dialog', name='编辑 RK-SYN-001（退料入库）', exact=True)
        supplier = field(dialog, '往来加工厂')
        await expect(supplier).to_contain_text('工厂甲')
        await field(dialog, '出仓').locator('.el-select__wrapper').click()
        await page.get_by_role('option', name='乙工厂仓', exact=True).click()
        if baseline: await page.wait_for_timeout(800)
        else: await expect(supplier).to_contain_text('工厂乙')
        text = await selected_text(supplier)
        metrics['checks']['warehouse_change_replaces_label'] = {'passed': text == '工厂乙', 'displayed': text, 'warehouse': '乙工厂仓'}
        row = dialog.locator('.el-table__body-wrapper tbody tr').first
        amount = (await row.locator('td').nth(4).inner_text()).strip()
        metrics['checks']['inventory_amount_half_up_and_null'] = {'passed': amount == '0.14', 'qty': '0.018', 'price': '7.5', 'amount': amount}
        await page.screenshot(animations='disabled', path=str(artifacts / 'return-switched-and-amount.png'))
        if not baseline:
            await row.locator('td').nth(3).locator('input').fill('')
            await row.locator('td').nth(3).locator('input').press('Tab')
            await expect(row.locator('td').nth(4)).to_have_text('—')
            metrics['checks']['inventory_amount_half_up_and_null']['unvalued_price'] = '—'
            await page.screenshot(animations='disabled', path=str(artifacts / 'return-null-price.png'))
        metrics['form_requests'] = api.calls
        metrics['console_errors'].extend(errors)
    except BaseException:
        await page.screenshot(animations='disabled', path=str(artifacts / 'return-failure.png'), full_page=True)
        raise
    finally:
        await context.close()

    context, page, api, errors, aborted = await context_page(browser, base, metrics, 'purchase')
    try:
        rows = page.locator('.el-table__body-wrapper tbody tr')
        await expect(rows).to_have_count(2)
        await expect(page.locator('.el-loading-mask:visible')).to_have_count(0)
        amounts = [(await rows.nth(i).locator('td').nth(5).inner_text()).strip() for i in (0, 1)]
        total = (await page.locator('.sec-bar strong').inner_text()).strip()
        metrics['checks']['purchase_round_each_line_then_sum'] = {'passed': amounts == ['0.14', '0.14'] and total == '0.28', 'line_amounts': amounts, 'total': total}
        await page.screenshot(animations='disabled', path=str(artifacts / 'purchase-line-rounded-total.png'), full_page=True)
        if not baseline:
            await rows.nth(0).locator('td').nth(2).locator('input').fill('')
            await rows.nth(0).locator('td').nth(2).locator('input').press('Tab')
            await expect(rows.nth(0).locator('td').nth(5)).to_have_text('—')
            await expect(page.locator('.sec-bar strong')).to_have_text('0.14')
            await rows.nth(1).locator('td').nth(3).locator('input').fill('')
            await rows.nth(1).locator('td').nth(3).locator('input').press('Tab')
            await expect(rows.nth(1).locator('td').nth(5)).to_have_text('—')
            await expect(page.locator('.sec-bar strong')).to_have_text('0.00')
            metrics['checks']['purchase_round_each_line_then_sum']['empty_quantity_and_price'] = '—'
            metrics['checks']['purchase_round_each_line_then_sum']['only_valued_lines_summed'] = True
            await page.get_by_text('明细', exact=True).click()
            await page.screenshot(animations='disabled', path=str(artifacts / 'purchase-empty-amounts.png'), full_page=True)
        metrics['console_errors'].extend(errors)
    except BaseException:
        await page.screenshot(animations='disabled', path=str(artifacts / 'purchase-failure.png'), full_page=True)
        raise
    finally:
        await context.close()


def is_aborted_supplier(request, selected):
    url = urlsplit(request.url)
    return url.path == '/api/suppliers/options' and parse_qs(url.query).get('includeId') == [selected]


async def release_late(held):
    held['release'].set()
    await asyncio.wait_for(held['finished'].wait(), timeout=5)


async def check_selector_contexts(browser, base, metrics, artifacts):
    context, page, api, errors, aborted = await context_page(browser, base, metrics, 'provided')
    try:
        selected = page.get_by_test_id('supplier-select')
        await expect(selected).to_contain_text('父级工厂甲')
        assert not [c for c in api.calls if c['path'] == '/api/suppliers/options'], api.calls
        held = api.hold('4')
        await page.get_by_role('button', name='只换ID乙', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        await expect(selected).not_to_contain_text('父级工厂甲')
        pending = await selected_text(selected)
        assert pending == '供应商 4', pending
        await release_late(held)
        await expect(selected).to_contain_text('工厂乙')
        metrics['checks']['provided_label_bound_to_its_id'] = {'passed': True, 'initial_parent_label': '父级工厂甲', 'initial_options_calls': 0,
            'while_new_id_pending': pending, 'after': await selected_text(selected)}
        await page.screenshot(animations='disabled', path=str(artifacts / 'provided-label-id-change.png'))
        metrics['console_errors'].extend(errors)
    finally:
        await context.close()

    context, page, api, errors, aborted = await context_page(browser, base, metrics)
    try:
        selected = page.get_by_test_id('supplier-select')
        await expect(selected).to_contain_text('工厂甲')
        held = api.hold('5')
        await page.get_by_role('button', name='慢ID', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        async with page.expect_event('requestfailed', predicate=lambda req: is_aborted_supplier(req, '5')) as failure:
            await page.get_by_role('button', name='快ID', exact=True).click()
        assert (await failure.value).failure == 'net::ERR_ABORTED'
        await expect(selected).to_contain_text('新快工厂')
        await release_late(held)
        await expect(selected).to_contain_text('新快工厂')
        await expect(selected).not_to_contain_text('旧慢工厂')
        metrics['checks']['late_previous_id_cancelled'] = {'passed': True, 'aborted': aborted, 'displayed': await selected_text(selected)}
        await page.screenshot(animations='disabled', path=str(artifacts / 'supplier-late-id-discarded.png'))

        async with page.expect_response(lambda response: is_aborted_supplier(response.request, '999')) as unknown_lookup:
            await page.get_by_role('button', name='未知ID', exact=True).click()
        await (await unknown_lookup.value).finished()
        await settle_render(page)
        await expect(selected).to_contain_text('供应商 999')
        assert await selected_text(selected) == '供应商 999'
        async with page.expect_response(lambda response: is_aborted_supplier(response.request, '998')) as failed_lookup:
            await page.get_by_role('button', name='失败ID', exact=True).click()
        assert (await failed_lookup.value).status == 503
        await (await failed_lookup.value).finished()
        await settle_render(page)
        await expect(selected).to_contain_text('供应商 998')
        assert await selected_text(selected) == '供应商 998'
        metrics['checks']['unknown_and_failed_lookup_never_invent_label'] = {'passed': True, 'unknown_id_ignored_suggestion_for_id': '3',
            'unknown_display': '供应商 999', 'failed_status': 503, 'failed_display': await selected_text(selected)}
        await page.screenshot(animations='disabled', path=str(artifacts / 'supplier-unknown-failed.png'))
        for error in errors:
            target = 'expected_console_errors' if error['path'] == '/api/suppliers/options' and '503' in error['text'] else 'console_errors'
            metrics[target].append(error)
    finally:
        await context.close()

    context, page, api, errors, aborted = await context_page(browser, base, metrics)
    try:
        selected = page.get_by_test_id('supplier-select')
        await expect(selected).to_contain_text('工厂甲')
        held = api.hold('5')
        await page.get_by_role('button', name='慢ID', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        async with page.expect_event('requestfailed', predicate=lambda req: is_aborted_supplier(req, '5')):
            await page.get_by_role('button', name='切换主体', exact=True).click()
        await expect(selected).to_contain_text('二主体旧慢工厂')
        await release_late(held)
        assert await selected_text(selected) == '二主体旧慢工厂'
        # The first ID was cached in company 1. Returning to it must fetch company 2.
        held = api.hold('3', '2')
        await page.get_by_role('button', name='预选甲', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        assert await selected_text(selected) == '供应商 3'
        await release_late(held)
        await expect(selected).to_contain_text('二主体工厂甲')
        metrics['checks']['company_switch_clears_names_and_aborts_old_scope'] = {'passed': True, 'aborted': aborted,
            'options_requests': [c for c in api.calls if c['path'] == '/api/suppliers/options'], 'displayed': await selected_text(selected)}
        await page.screenshot(animations='disabled', path=str(artifacts / 'supplier-company-scope.png'))
        metrics['console_errors'].extend(errors)
    finally:
        await context.close()

    context, page, api, errors, aborted = await context_page(browser, base, metrics)
    try:
        selected = page.get_by_test_id('supplier-select')
        await expect(selected).to_contain_text('工厂甲')
        held = api.hold('5')
        await page.get_by_role('button', name='慢ID', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        api.identity = 2
        async with page.expect_event('requestfailed', predicate=lambda req: is_aborted_supplier(req, '5')):
            await page.get_by_role('button', name='切换身份', exact=True).click()
        await expect(selected).to_contain_text('新账号旧慢工厂')
        await release_late(held)
        assert await selected_text(selected) == '新账号旧慢工厂'
        held = api.hold('3')
        await page.get_by_role('button', name='预选甲', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        assert await selected_text(selected) == '供应商 3'
        await release_late(held)
        await expect(selected).to_contain_text('新账号工厂甲')
        metrics['checks']['identity_switch_clears_names_and_aborts_old_scope'] = {'passed': True, 'aborted': aborted, 'displayed': await selected_text(selected)}
        await page.screenshot(animations='disabled', path=str(artifacts / 'supplier-identity-scope.png'))
        metrics['console_errors'].extend(errors)
    finally:
        await context.close()

    context, page, api, errors, aborted = await context_page(browser, base, metrics)
    try:
        selected = page.get_by_test_id('supplier-select')
        await expect(selected).to_contain_text('工厂甲')
        old_page_requests = []
        page.on('request', lambda request: old_page_requests.append(urlsplit(request.url).path) if urlsplit(request.url).path.startswith('/api/') else None)
        other = await context.new_page()
        await other.goto(base + '/node_modules/.s3-supplier-amounts/supplier-amounts.html?view=provided')
        await expect(other.get_by_test_id('supplier-select')).to_contain_text('父级工厂甲')
        held = api.hold('5')
        await page.get_by_role('button', name='慢ID', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        async with page.expect_event('requestfailed', predicate=lambda req: is_aborted_supplier(req, '5')):
            await other.get_by_role('button', name='切换身份', exact=True).click()
        await expect(selected).to_contain_text('供应商 5')
        await release_late(held)
        assert await selected_text(selected) == '供应商 5'
        # Even a previously resolved ID must not retain the old account's cached name.
        count = len(old_page_requests)
        await page.get_by_role('button', name='预选甲', exact=True).click()
        await expect(selected).to_contain_text('供应商 3')
        await settle_render(page)
        assert len(old_page_requests) == count, old_page_requests
        metrics['checks']['other_tab_identity_change_clears_cached_names'] = {'passed': True, 'aborted': aborted,
            'old_tab_display': await selected_text(selected), 'old_tab_does_not_fetch_as_new_account': True}
        await page.screenshot(animations='disabled', path=str(artifacts / 'supplier-cross-tab-stale.png'))
        metrics['console_errors'].extend(errors)
    finally:
        await context.close()

    context, page, api, errors, aborted = await context_page(browser, base, metrics)
    try:
        selected = page.get_by_test_id('supplier-select')
        await expect(selected).to_contain_text('工厂甲')
        held = api.hold('5')
        await page.get_by_role('button', name='慢ID', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        async with page.expect_event('requestfailed', predicate=lambda req: is_aborted_supplier(req, '5')):
            await page.get_by_role('button', name='切换挂载', exact=True).click()
        await expect(selected.locator('.el-select')).to_have_count(0)
        await release_late(held)
        await expect(selected.locator('.el-select')).to_have_count(0)
        await page.get_by_role('button', name='切换挂载', exact=True).click()
        await expect(selected).to_contain_text('旧慢工厂')
        metrics['checks']['unmount_aborts_lookup_and_remount_resolves'] = {'passed': True, 'aborted': aborted, 'remounted_display': await selected_text(selected)}
        metrics['console_errors'].extend(errors)
    finally:
        await context.close()


async def check_purchase_app_layout(browser, base, metrics, artifacts, baseline):
    context, page, api, errors, _ = await context_page(browser, base, metrics, 'purchase', app_layout=True, purchase_rows=100)
    cases = []
    try:
        rows = page.locator('.el-table__body-wrapper tbody tr')
        await expect(rows).to_have_count(100)
        await expect(page.locator('.el-loading-mask:visible')).to_have_count(0)
        for width in (1440, 1180):
            await page.set_viewport_size({'width': width, 'height': 900})
            await settle_render(page)
            last = rows.nth(99)
            await last.scroll_into_view_if_needed()
            await last.locator('td').first.scroll_into_view_if_needed()
            await settle_render(page)
            seq = await last.locator('td').first.locator('.cell').evaluate("""cell => {
                const style = getComputedStyle(cell), range = document.createRange(); range.selectNodeContents(cell);
                const contentWidth = cell.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
                const probe = document.createElement('span'); probe.textContent = cell.textContent.trim();
                Object.assign(probe.style, {font: style.font, fontVariantNumeric: style.fontVariantNumeric, whiteSpace: 'nowrap', position: 'fixed', visibility: 'hidden'});
                document.body.append(probe); const naturalTextWidth = probe.getBoundingClientRect().width; probe.remove();
                const renderedLines = new Set([...range.getClientRects()].filter(rect => rect.width > 0).map(rect => Math.round(rect.top))).size;
                return {text: cell.textContent.trim(), clientWidth: cell.clientWidth, contentWidth, naturalTextWidth, renderedLines,
                    paddingLeft: style.paddingLeft, paddingRight: style.paddingRight, whiteSpace: style.whiteSpace};
            }""")
            await page.screenshot(animations='disabled', path=str(artifacts / f'purchase-app-{width}-sequence-100.png'))
            delete = last.get_by_role('button', name='删', exact=True)
            await delete.scroll_into_view_if_needed()
            await settle_render(page)
            action = await delete.evaluate("""button => {
                const cell = button.closest('.cell'), style = getComputedStyle(cell), rect = button.getBoundingClientRect();
                const contentWidth = cell.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
                const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
                return {text: button.textContent.trim(), clientWidth: cell.clientWidth, contentWidth, buttonWidth: rect.width,
                    paddingLeft: style.paddingLeft, paddingRight: style.paddingRight, centerClickable: !!hit && button.contains(hit)};
            }""")
            layout = await page.evaluate("""() => {
                const shell = document.querySelector('.shell'), work = document.querySelector('.shell-work'), table = document.querySelector('.el-table');
                return {viewportWidth: innerWidth, shellWidth: shell.getBoundingClientRect().width,
                    sidebarWidth: work.getBoundingClientRect().left, tableWidth: table.getBoundingClientRect().width};
            }""")
            cases.append({'layout': layout, 'sequence': seq, 'delete': action,
                'passed': seq['text'] == '100' and seq['naturalTextWidth'] <= seq['contentWidth'] + .5 and seq['renderedLines'] == 1 and action['buttonWidth'] <= action['contentWidth'] + .5 and action['centerClickable']})
            await page.screenshot(animations='disabled', path=str(artifacts / f'purchase-app-{width}-delete-reachable.png'))
        # Exercise the actual local remove handler. The SyntheticApi rejects every non-GET request.
        removed = False
        if not baseline:
            await rows.nth(99).get_by_role('button', name='删', exact=True).click()
            await expect(rows).to_have_count(99)
            removed = True
            await page.screenshot(animations='disabled', path=str(artifacts / 'purchase-app-delete-local-row.png'))
        assert abs(cases[0]['layout']['tableWidth'] - 1180) <= 2, cases[0]['layout']
        metrics['checks']['purchase_app_layout_edge_columns'] = {'passed': all(case['passed'] for case in cases) and (baseline or removed),
            'actual_components': ['AppLayout', 'AppSidebar', 'TabsBar', 'PurchaseForm'], 'api': 'synthetic GET only',
            'cases': cases, 'local_row_deleted_without_api': removed}
        metrics['console_errors'].extend(errors)
    except BaseException:
        await page.screenshot(animations='disabled', path=str(artifacts / 'purchase-app-failure.png'))
        raise
    finally:
        await context.close()


async def run(baseline, layout_only=False):
    artifacts = (BASE_ARTIFACTS.parent / 'p40' if layout_only else BASE_ARTIFACTS) / ('before' if baseline else 'after')
    artifacts.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-supplier-amounts'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['supplier-amounts.html', 'SupplierAmountsFixture.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    metrics = {'phase': 'before' if baseline else 'after', 'scope': 'Actual SupplierSelect, InvDocFormDialog and purchase form with explicitly synthetic HTTP; no database writes', 'checks': {}, 'page_errors': [], 'console_errors': [], 'expected_console_errors': []}
    with (artifacts / 'vite.log').open('w') as log:
        process = subprocess.Popen(['pnpm', 'exec', 'vite', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None: raise RuntimeError('Vite exited')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close(); await writer.wait_closed(); break
                except OSError: await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'): options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                try:
                    if not layout_only:
                        await check_forms(browser, f'http://127.0.0.1:{port}', metrics, artifacts, baseline)
                        if not baseline: await check_selector_contexts(browser, f'http://127.0.0.1:{port}', metrics, artifacts)
                    await check_purchase_app_layout(browser, f'http://127.0.0.1:{port}', metrics, artifacts, baseline)
                finally:
                    await browser.close()
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            (artifacts / 'results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    assert not metrics['page_errors'], metrics['page_errors']
    assert not metrics['console_errors'], metrics['console_errors']
    assert len(metrics['checks']) == (1 if layout_only else 5 if baseline else 12), metrics['checks'].keys()
    assert all(c['passed'] for c in metrics['checks'].values()), metrics['checks']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--baseline', action='store_true'); parser.add_argument('--layout-only', action='store_true')
    args = parser.parse_args()
    asyncio.run(run(args.baseline, args.layout_only))
