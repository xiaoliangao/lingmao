#!/usr/bin/env python3
"""Four actual kitting consumers with synthetic HTTP errors, retry and delayed-page faults."""
import asyncio
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/p35-kitting'
ORDER_BASE = 9007199254742000


def document_header(doc_id, no):
    return {'id': doc_id, 'no': no, 'status': 'RUNNING', 'version': 1, 'companyId': '1', 'companyName': '合成主体',
            'remark': None, 'createBy': '9007199254740993', 'createByName': '合成跟单员',
            'createTime': '2026-11-22 10:00:00', 'updateTime': '2026-11-22 10:00:00',
            'auditBy': None, 'auditByName': None, 'auditTime': None, 'actions': [], 'approval': None, 'pendingNotices': 0}


def order_document():
    return {**document_header(str(ORDER_BASE + 1), 'DD-SYN-001'), 'orderType': 'OWN', 'customerId': None,
            'customerName': None, 'customerOrderNo': None, 'contractNo': None, 'brand': None, 'orderDate': '2026-11-22',
            'requiredDate': '2026-12-18', 'commitDate': '2026-12-18', 'salesId': '9007199254740993',
            'salesName': '合成业务员', 'merchId': '9007199254740993', 'merchName': '合成跟单员', 'currency': 'CNY',
            'deliveryReq': None, 'totalQty': 100, 'source': 'MANUAL', 'scheduledQty': 100, 'returnedQty': 0,
            'styles': [{'styleId': '501', 'styleCode': 'KS-SYN-001', 'styleName': '合成测试款', 'aliasName': None,
                        'image': None, 'sizes': ['M'], 'techPackId': '601', 'techPackVersion': 1,
                        'currentPackId': '601', 'currentPackVersion': 1, 'qty': 100}],
            'items': [{'id': '701', 'seq': 1, 'styleId': '501', 'styleSkuId': '801', 'skuCode': 'KS-SYN-001-B-M',
                       'colorId': '901', 'colorCode': 'B', 'colorName': '蓝色', 'colorHex': '#2255aa', 'sizeCode': 'M',
                       'qty': 100, 'lineDueDate': None, 'techPackId': '601', 'scheduledQty': 100, 'returnedQty': 0, 'remark': None}],
            'pendingChange': None}


def production_document():
    item = order_document()['items'][0]
    return {**document_header('9007199254741001', 'JG-SYN-001'), 'planRevision': 1, 'kind': 'OUTSOURCE',
            'factoryId': '401', 'factoryCode': 'GC-SYN', 'factoryName': '合成加工厂', 'factoryShortName': '合成加工厂',
            'factoryStatus': 'ACTIVE', 'processMode': 'CMT', 'orderId': str(ORDER_BASE + 1), 'orderNo': 'DD-SYN-001',
            'styleId': '501', 'styleCode': 'KS-SYN-001', 'styleName': '合成测试款', 'aliasName': None, 'brand': None,
            'image': None, 'techPackId': '601', 'techPackVersion': 1, 'orderDate': '2026-11-22', 'dueDate': '2026-12-18',
            'expectDate': None, 'daysLeft': 26, 'priceSourceId': None, 'priceChangeReason': None, 'taxPct': 0, 'taxIncluded': True,
            'totalQty': 100, 'merchId': '9007199254740993', 'merchName': '合成跟单员', 'settleMethod': 'MONTHLY', 'terms': None,
            'issueStatus': None, 'returnedQty': 0, 'defectQty': 0, 'currentNodeName': None,
            'risk': {'late': False, 'near': False, 'nodeLate': False, 'issue': False, 'upstream': False}, 'nearDays': 7,
            'sizes': ['M'], 'items': [{**item, 'orderItemId': '701', 'defectQty': 0, 'remainQty': 100, 'maxReceiveQty': 103}],
            'nodes': [], 'openIssues': [], 'issueCount': 0, 'receiptCount': 0, 'stockDocCount': 0}


async def check_kitting(browser, base, metrics):
    context = await browser.new_context(viewport={'width': 1280, 'height': 800})
    page = await context.new_page()
    error_mode = 503
    success_status = 'NONE'
    request_done = asyncio.Event()
    slow_started = asyncio.Event()
    slow_release = asyncio.Event()
    errors = []
    requests = []
    aborted = []
    page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
    page.on('console', lambda message: errors.append({'text': message.text, 'path': urlsplit(message.location.get('url', '')).path}) if message.type == 'error' else None)
    page.on('requestfailed', lambda request: aborted.append({'path': urlsplit(request.url).path, 'query': urlsplit(request.url).query, 'failure': request.failure}))

    def kit(order_id, status):
        return {'orderId': order_id, 'status': status, 'readyCount': 3 if status == 'PARTIAL' else 5 if status == 'DONE' else 0,
                'totalCount': 0 if status == 'NONE' else 5, 'label': {'NONE': '未算料', 'DONE': '齐料', 'PARTIAL': '部分到料 3/5'}[status], 'calcIds': []}

    async def api(route):
        path = urlsplit(route.request.url).path
        if not path.startswith('/api/'):
            await route.continue_(); return
        if path.startswith('/api/dict-data/by-type/'):
            await route.fulfill(json={'code': 0, 'msg': '', 'data': []}); return
        assert path == '/api/order-kitting', path
        params = parse_qs(urlsplit(route.request.url).query)
        ids = params.get('orderIds', [])
        assert len(ids) == 1 and ids[0].isdigit(), params
        order_id = ids[0]
        requests.append(order_id)
        if order_id == str(ORDER_BASE + 3):
            slow_started.set()
            await asyncio.wait_for(slow_release.wait(), timeout=10)
            try:
                await route.fulfill(json={'code': 0, 'msg': '', 'data': [kit(order_id, 'DONE')]})
            except Exception:
                pass
            return
        status = 0 if order_id == str(ORDER_BASE + 4) else error_mode
        if status:
            await route.fulfill(status=status, json={'code': status, 'msg': '合成齐料读取故障', 'data': None})
        else:
            await route.fulfill(json={'code': 0, 'msg': '', 'data': [kit(order_id, 'PARTIAL' if order_id == str(ORDER_BASE + 4) else success_status)]})
        request_done.set()

    await context.route('**/api/**', api)
    try:
        await page.goto(base + '/node_modules/.s3-kitting/production-kitting.html')
        await asyncio.wait_for(request_done.wait(), timeout=15)
        await page.wait_for_timeout(150)
        rows = page.locator('.el-table__body-wrapper tbody tr')
        cell = rows.first.locator('td').nth(4)
        metrics['initial_503_cell'] = await cell.inner_text()
        assert '读取失败' in metrics['initial_503_cell'] and '重试' in metrics['initial_503_cell'], metrics['initial_503_cell']
        assert await rows.nth(1).locator('td').nth(4).inner_text() == '—'
        await page.screenshot(path=str(ARTIFACTS / 'kitting-503.png'))
        error_mode = 0
        await cell.get_by_role('button', name='重试齐料状态').click()
        await cell.get_by_text('未算料', exact=True).wait_for()
        assert await page.get_by_test_id('route').inner_text() == '/board'
        await page.screenshot(path=str(ARTIFACTS / 'kitting-none.png'))
        metrics['checks']['http_503_retry_to_real_none'] = {'passed': True, 'failure_shown': True, 'normal_no_bom_label': '未算料', 'retry_keeps_parent_route': True}

        error_mode = 404
        await page.get_by_role('button', name='第二页', exact=True).click()
        await cell.get_by_text('不可查看', exact=True).wait_for()
        await page.screenshot(path=str(ARTIFACTS / 'kitting-404.png'))
        error_mode = 0; success_status = 'DONE'
        await cell.get_by_role('button', name='重试齐料状态').click()
        await cell.get_by_text('齐料', exact=True).wait_for()
        assert await page.get_by_test_id('route').inner_text() == '/board'
        await page.screenshot(path=str(ARTIFACTS / 'kitting-recovered.png'))
        metrics['checks']['http_404_retry_to_real_done'] = {'passed': True, 'unavailable_shown': True, 'recovered_label': '齐料', 'retry_keeps_parent_route': True}

        await page.get_by_role('button', name='慢页', exact=True).click()
        await asyncio.wait_for(slow_started.wait(), timeout=10)
        assert '读取中' in await cell.inner_text()
        await page.get_by_role('button', name='新页', exact=True).click()
        await cell.get_by_text('部分到料 3/5', exact=True).wait_for()
        slow_release.set()
        await page.wait_for_timeout(250)
        assert '部分到料 3/5' in await cell.inner_text()
        assert any(item['path'] == '/api/order-kitting' and str(ORDER_BASE + 3) in item['query'] and item['failure'] == 'net::ERR_ABORTED' for item in aborted), aborted
        await page.screenshot(path=str(ARTIFACTS / 'kitting-latest-page.png'))
        metrics['checks']['row_switch_aborts_stale_kitting'] = {'passed': True, 'loading_shown': True, 'old_done_response_discarded': True, 'current_label': '部分到料 3/5'}
        metrics['request_order_ids'] = requests
        metrics['aborted_requests'] = aborted
        metrics['expected_console_errors'] = [e for e in errors if e['path'] == '/api/order-kitting' and e['text'].startswith('Failed to load resource:') and ('503' in e['text'] or '404' in e['text'])]
        metrics['unexpected_console_errors'] = [e for e in errors if e not in metrics['expected_console_errors']]
        assert not metrics['unexpected_console_errors'], metrics['unexpected_console_errors']
    except Exception:
        await page.screenshot(path=str(ARTIFACTS / 'failure.png'))
        raise
    finally:
        await context.close()


async def check_page_consumers(browser, base, metrics):
    for view, failure in [('order-list', 403), ('order-detail', 503), ('production-detail', 404)]:
        context = await browser.new_context(viewport={'width': 1440, 'height': 1000})
        page = await context.new_page()
        mode = failure
        errors = []
        paths = []
        kitting_requests = []
        page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
        page.on('console', lambda message: errors.append({'text': message.text, 'path': urlsplit(message.location.get('url', '')).path}) if message.type == 'error' else None)

        async def api(route):
            path = urlsplit(route.request.url).path
            if not path.startswith('/api/'):
                await route.continue_(); return
            paths.append(path)
            if path.startswith('/api/dict-data/by-type/'):
                data = []
            elif path == '/api/orders':
                data = {'list': [order_document()], 'total': 1}
            elif path == '/api/orders/counts':
                data = {'RUNNING': 1}
            elif path == f'/api/orders/{ORDER_BASE + 1}':
                data = order_document()
            elif path == '/api/production-orders/9007199254741001':
                data = production_document()
            elif path == '/api/production-orders/9007199254741001/materials':
                data = {'rows': [], 'docs': []}
            elif path in (f'/api/tags/objects/ORDER/{ORDER_BASE + 1}',
                          '/api/tags/objects/PRODUCTION_ORDER/9007199254741001'):
                assert route.request.method == 'GET', route.request.method
                data = {'version': 0, 'tags': []}
            elif path.endswith(('/attachments', '/logs', '/references', '/issues', '/receipts')):
                data = []
            elif path == '/api/order-kitting':
                ids = parse_qs(urlsplit(route.request.url).query).get('orderIds', [])
                assert ids == [str(ORDER_BASE + 1)], ids
                kitting_requests.append({'ids': ids, 'status': mode or 200})
                if mode:
                    await route.fulfill(status=mode, json={'code': mode, 'msg': '合成齐料读取故障', 'data': None})
                    return
                data = [{'orderId': ids[0], 'status': 'PARTIAL', 'readyCount': 3, 'totalCount': 5,
                         'label': '部分到料 3/5', 'calcIds': ['9007199254743001']}]
            else:
                raise AssertionError(f'unexpected API in {view}: {path}')
            await route.fulfill(json={'code': 0, 'msg': '', 'data': data})

        await context.route('**/api/**', api)
        try:
            await page.goto(base + '/node_modules/.s3-kitting/kitting-pages.html?view=' + view)
            failed = page.get_by_test_id('kitting-failure')
            await failed.wait_for()
            assert ('读取失败' if failure == 503 else '不可查看') in await failed.inner_text()
            assert str(ORDER_BASE + 1) not in await failed.inner_text()
            before_route = await page.get_by_test_id('route').inner_text()
            await page.screenshot(path=str(ARTIFACTS / f'{view}-failure.png'), full_page=True)
            mode = 0
            await failed.get_by_role('button', name='重试齐料状态').click()
            await page.get_by_text('部分到料 3/5', exact=True).wait_for()
            assert await page.get_by_test_id('kitting-failure').count() == 0
            assert await page.get_by_test_id('route').inner_text() == before_route
            await page.screenshot(path=str(ARTIFACTS / f'{view}-recovered.png'), full_page=True)
            navigation = None
            if view in ('order-list', 'order-detail'):
                await page.get_by_role('button', name='部分到料 3/5', exact=True).click()
                await page.get_by_test_id('route').filter(has_text='/mrp/9007199254743001').wait_for()
                navigation = '/mrp/9007199254743001'
            expected = [e for e in errors if e['path'] == '/api/order-kitting' and e['text'].startswith('Failed to load resource:') and str(failure) in e['text']]
            unexpected = [e for e in errors if e not in expected]
            assert not unexpected, unexpected
            assert len(kitting_requests) == 2, kitting_requests
            metrics['checks'][f'{view}_failure_retry'] = {'passed': True, 'injected_http_status': failure,
                'recovered_label': '部分到料 3/5', 'retry_preserved_route': True, 'retained_mrp_navigation': navigation,
                'kitting_requests': kitting_requests, 'expected_console_errors': expected, 'unexpected_console_errors': unexpected,
                'api_paths': sorted(set(paths))}
        except Exception:
            await page.screenshot(path=str(ARTIFACTS / f'{view}-unexpected-failure.png'), full_page=True)
            raise
        finally:
            await context.close()


async def run():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-kitting'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['production-kitting.html', 'ProductionKittingFixture.vue', 'kitting-pages.html', 'KittingPagesFixture.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    metrics = {'scope': 'actual ProductionTable, order list, order detail and production detail with synthetic HTTP; backend authorization is verified separately', 'checks': {}, 'page_errors': []}
    with (ARTIFACTS / 'vite.log').open('w') as log:
        process = subprocess.Popen(['pnpm', 'exec', 'vite', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None: raise RuntimeError('Vite exited; see p35-kitting/vite.log')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close(); await writer.wait_closed(); break
                except OSError: await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'): options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                try:
                    await check_kitting(browser, f'http://127.0.0.1:{port}', metrics)
                    await check_page_consumers(browser, f'http://127.0.0.1:{port}', metrics)
                finally: await browser.close()
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            (ARTIFACTS / 'results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    assert not metrics['page_errors'], metrics['page_errors']
    assert len(metrics['checks']) == 6 and all(item['passed'] for item in metrics['checks'].values()), metrics['checks']


if __name__ == '__main__': asyncio.run(run())
