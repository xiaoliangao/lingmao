#!/usr/bin/env python3
"""Capture frozen-dev pages only after real API and visible content readiness."""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import urlsplit

from harness import ApiClient, read_fixture, line_values, local_url, sanitized_error, write_evidence
from playwright.sync_api import sync_playwright, expect


def main():
    base = local_url('SCM_UI_BASE_URL')
    api = ApiClient(local_url('SCM_UI_API_BASE', required_port=18080))
    api.login()
    fixture = read_fixture(os.environ['SCM_UI_FIXTURE'])
    made = api.request('POST', '/api/orders', fixture['orderTemplate'])
    order_id = made['id']
    order = api.request('GET', f'/api/orders/{order_id}')
    count = api.request('GET', f"/api/inventory-counts/{fixture['countId']}")
    pack = api.request('GET', f"/api/tech-packs/{fixture['packId']}")
    cost = api.request('GET', f"/api/tech-packs/{fixture['packId']}/cost")
    shots = Path(os.environ['SCM_UI_SCREENSHOTS'])
    shots.mkdir(parents=True, exist_ok=True)
    evidence = {'schemaVersion': 1, 'synthetic': True, 'phase': 'before', 'captureVersion': 'stable-v1',
                'database': fixture['database'], 'passed': False, 'orderBefore': line_values(order),
                'pages': [], 'consoleErrors': [], 'pageErrors': [], 'externalRequests': [],
                'requestFailures': [], 'apiResponses': [], 'draftDecisions': []}
    pending = set()
    active_name = 'startup'
    last_change = time.monotonic()
    page = None
    succeeded = False
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(executable_path=os.environ.get('SCM_UI_CHROMIUM'), headless=True)
            try:
                context = browser.new_context(viewport={'width': 1440, 'height': 900}, timezone_id='Asia/Shanghai')
                context.add_init_script('if (location.origin === ' + json.dumps(base) + ') { localStorage.setItem("lingmao-token", ' + json.dumps(api.token) + '); }')
                def local_only(route):
                    if urlsplit(route.request.url).hostname in {'127.0.0.1', 'localhost'}:
                        route.continue_()
                    else:
                        evidence['externalRequests'].append({'page': active_name, 'host': urlsplit(route.request.url).hostname})
                        route.abort('blockedbyclient')
                context.route(re.compile(r'^https?://'), local_only)
                def started(request):
                    nonlocal last_change
                    if urlsplit(request.url).path.startswith('/api/'):
                        pending.add(request)
                        last_change = time.monotonic()
                def finished(request):
                    nonlocal last_change
                    if request in pending:
                        pending.discard(request)
                        last_change = time.monotonic()
                        response = request.response()
                        if response is not None:
                            evidence['apiResponses'].append({'page': active_name, 'method': request.method,
                                                            'path': urlsplit(request.url).path, 'status': response.status})
                def failed(request):
                    nonlocal last_change
                    pending.discard(request)
                    last_change = time.monotonic()
                    evidence['requestFailures'].append({'page': active_name, 'path': urlsplit(request.url).path,
                                                         'error': sanitized_error(request.failure or '', api.token)})
                context.on('request', started)
                context.on('requestfinished', finished)
                context.on('requestfailed', failed)
                page = context.new_page()
                page.set_default_timeout(30_000)
                page.set_default_navigation_timeout(60_000)
                page.on('pageerror', lambda error: evidence['pageErrors'].append({'page': active_name, 'error': sanitized_error(str(error), api.token)}))
                page.on('console', lambda message: evidence['consoleErrors'].append({'page': active_name, 'error': sanitized_error(message.text, api.token)}) if message.type == 'error' else None)
                draft = page.get_by_role('dialog', name='恢复草稿', exact=True)
                def discard(dialog):
                    dialog.get_by_role('button', name='放弃草稿', exact=True).click()
                    evidence['draftDecisions'].append({'route': urlsplit(page.url).path, 'decision': 'discard'})
                page.add_locator_handler(draft, discard)
                expect.set_options(timeout=30_000)

                def stable():
                    expect(draft).not_to_be_visible()
                    expect(page.locator('.el-loading-mask:visible')).to_have_count(0)
                    expect(page.locator('.el-skeleton:visible, .el-skeleton__item:visible')).to_have_count(0)
                    deadline = time.monotonic() + 20
                    while pending or time.monotonic() - last_change < .35:
                        if time.monotonic() >= deadline:
                            raise AssertionError('Real API requests did not settle before capture')
                        page.wait_for_timeout(50)
                    page.evaluate('async () => { await document.fonts.ready; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); }')
                    expect(page.locator('.el-loading-mask:visible')).to_have_count(0)
                    expect(page.locator('.el-skeleton:visible, .el-skeleton__item:visible')).to_have_count(0)
                    if pending:
                        raise AssertionError('An API request restarted before capture')

                def navigate(route, endpoint):
                    with page.expect_response(lambda response: response.request.method == 'GET' and urlsplit(response.url).path == endpoint, timeout=60_000) as received:
                        page.goto(base + route, wait_until='domcontentloaded')
                    response = received.value
                    envelope = response.json()
                    if response.status != 200 or envelope.get('code') != 0:
                        raise AssertionError('Required real page API did not return a successful envelope')
                    expect(draft).not_to_be_visible()
                    return envelope.get('data')

                targets = [
                    ('orders', '/orders', '/api/orders'),
                    ('order-matrix', f'/orders/{order_id}/edit', f'/api/orders/{order_id}'),
                    ('techpack-bom', f"/styles/{fixture['styleId']}?tab=techpack&techPackId={fixture['packId']}&sub=bom", f"/api/tech-packs/{fixture['packId']}"),
                    ('techpack-cost', f"/styles/{fixture['styleId']}?tab=techpack&techPackId={fixture['packId']}&sub=cost", f"/api/tech-packs/{fixture['packId']}/cost"),
                    ('count-detail', f"/stock-takes/{fixture['countId']}", f"/api/inventory-counts/{fixture['countId']}"),
                    ('purchases-empty', '/purchases', '/api/purchase-orders'),
                    ('stock-takes', '/stock-takes', '/api/inventory-counts'),
                ]
                for name, route, endpoint in targets:
                    active_name = name
                    data = navigate(route, endpoint)
                    readiness = {'primaryApiPath': endpoint, 'primaryApiStatus': 200, 'primaryApiCode': 0}
                    if name == 'orders':
                        if data['total'] != 1 or not any(str(row['id']) == str(order_id) for row in data['list']):
                            raise AssertionError('The synthetic order is absent from the real list response')
                        row = page.locator('.el-table__row').filter(has_text=order['no'])
                        expect(row.first).to_be_visible()
                        readiness.update({'apiTotal': data['total'], 'expectedDocumentNo': order['no'], 'matchingVisibleRows': row.count()})
                    elif name == 'order-matrix':
                        expect(page.locator('.style-block')).to_have_count(1)
                        expect(page.locator('.sku-matrix').first).to_be_visible()
                        expect(page.locator('.style-block')).to_contain_text('多个值')
                        readiness.update({'styleBlocks': 1, 'matrixVisible': True, 'mixedSkuValuesVisible': True})
                    elif name == 'techpack-bom':
                        if not pack['bomItems']:
                            raise AssertionError('Synthetic BOM must contain a real row')
                        row = page.locator('[data-testid="bom-row"]').filter(has_text=pack['bomItems'][0]['materialCode'])
                        expect(row.first).to_be_visible()
                        readiness.update({'bomApiRows': len(data['bomItems']), 'matchingVisibleRows': row.count()})
                    elif name == 'techpack-cost':
                        expect(page.locator('.cost-pane')).to_be_visible()
                        expect(page.locator('[data-testid="cost-buy-price"] input')).to_have_value(str(cost['buyPrice']))
                        expect(page.locator('.other-cost-table .el-table__row')).to_have_count(len(cost['otherItems']))
                        expect(page.locator('.other-cost-table input').first).to_have_value(cost['otherItems'][0]['name'])
                        expect(page.locator('.cost-total strong')).to_be_visible()
                        readiness.update({'costPaneVisible': True, 'costRows': len(cost['otherItems']), 'costInputsMatchRealApi': True})
                    elif name == 'count-detail':
                        expect(page.locator('.page-header h1')).to_contain_text(count['no'])
                        row = page.locator('.el-table__row').filter(has_text=count['items'][0]['skuCode'])
                        expect(row.first).to_be_visible()
                        readiness.update({'expectedDocumentNo': count['no'], 'itemApiRows': len(data['items']), 'matchingVisibleRows': row.count()})
                    elif name == 'purchases-empty':
                        if data['total'] != 0 or data['list']:
                            raise AssertionError('The controlled empty purchase fixture unexpectedly contains data')
                        expect(page.locator('.el-table__empty-block')).to_be_visible()
                        expect(page.locator('.el-table__row')).to_have_count(0)
                        readiness.update({'apiTotal': 0, 'intentionalEmptyState': True, 'matchingVisibleRows': 0})
                    elif name == 'stock-takes':
                        if data['total'] != 1 or not any(str(row['id']) == str(fixture['countId']) for row in data['list']):
                            raise AssertionError('The synthetic inventory count is absent from the real list response')
                        row = page.locator('.el-table__row').filter(has_text=count['no'])
                        expect(row.first).to_be_visible()
                        readiness.update({'apiTotal': data['total'], 'expectedDocumentNo': count['no'], 'matchingVisibleRows': row.count()})
                    stable()
                    readiness.update({'visibleLoadingMasks': page.locator('.el-loading-mask:visible').count(),
                                      'visibleSkeletons': page.locator('.el-skeleton:visible, .el-skeleton__item:visible').count(),
                                      'pendingApiRequests': len(pending), 'apiQuietMilliseconds': round((time.monotonic() - last_change) * 1000),
                                      'contentAssertionsPassed': True})
                    page.screenshot(path=str(shots / (name + '.png')), full_page=True, animations='disabled',
                                    mask=[page.locator('input[type="password"]'), page.locator('input[autocomplete="username"]')])
                    evidence['pages'].append({'name': name, 'route': route, 'url': urlsplit(page.url).path,
                                              'textChars': len(page.inner_text('body')), 'readiness': readiness})
                    print('BASELINE_PAGE_READY ' + name, flush=True)

                active_name = 'order-roundtrip'
                navigate(f'/orders/{order_id}/edit', f'/api/orders/{order_id}')
                expect(page.locator('.style-block')).to_have_count(1)
                expect(page.locator('.sku-matrix').first).to_be_visible()
                stable()
                with page.expect_response(lambda response: response.request.method == 'PUT' and urlsplit(response.url).path == f'/api/orders/{order_id}') as saved:
                    page.get_by_role('button', name='保存', exact=True).click()
                evidence['orderSaveCode'] = saved.value.json()['code']
                evidence['orderAfter'] = line_values(api.request('GET', f'/api/orders/{order_id}'))
                stable()
                if evidence['consoleErrors'] or evidence['pageErrors'] or evidence['externalRequests'] or evidence['requestFailures']:
                    raise AssertionError('Baseline browser emitted errors, failed a request or attempted external traffic')
                evidence['passed'] = True
                succeeded = True
            except Exception as error:
                evidence['failure'] = {'page': active_name, 'kind': type(error).__name__, 'message': sanitized_error(str(error), api.token)}
                if page is not None and not page.is_closed():
                    try:
                        page.screenshot(path=str(shots / ('diagnostic-' + active_name + '.png')), full_page=True,
                                        mask=[page.locator('input[type="password"]'), page.locator('input[autocomplete="username"]')])
                    except Exception:
                        evidence['diagnosticUnavailable'] = True
            finally:
                browser.close()
    finally:
        write_evidence(shots / 'evidence.json', evidence, api.token)
    print(json.dumps({'passed': succeeded, 'pages': len(evidence['pages']), 'consoleErrors': len(evidence['consoleErrors']),
                      'pageErrors': len(evidence['pageErrors']), 'requestFailures': len(evidence['requestFailures']),
                      'matrixChanged': evidence.get('orderBefore') != evidence.get('orderAfter')}, ensure_ascii=False))
    return 0 if succeeded else 1


if __name__ == '__main__':
    raise SystemExit(main())
