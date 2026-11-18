"""Actual stock-page export workflow with synthetic queue/file responses (no backend claim)."""
import asyncio
import re
from urllib.parse import parse_qs, quote, urlsplit
from playwright.async_api import expect


async def run_exports(browser, url, artifacts, observe_context, metrics):
    context = await browser.new_context(viewport={'width': 1440, 'height': 1080}, accept_downloads=True)
    observe_context(context)
    requests, writes, failed, unknown = [], [], [], []
    jobs, keys, flows = {}, {}, {}
    state = {'next_status': 'PENDING', 'next_flow': ['PROCESSING', 'SUCCEEDED'], 'fail_create_once': False, 'file_denied': False, 'slow_read': None, 'slow_file': False}
    metrics['export_requests'] = requests
    metrics['export_writes'] = writes
    metrics['export_request_failures'] = failed
    counter = 9007199254740993
    trace = '43ecf519-1c58-4c22-bd73-30332e6f3dde'

    def make_job(job_id, status):
        return {'id': job_id, 'kind': 'STOCK_LOGS', 'status': status, 'rows': 12345 if status == 'SUCCEEDED' else 0, 'attempts': 1, 'requestId': trace, 'createdAt': '2026-11-18 10:00:00', 'expiresAt': '2026-11-19 10:00:00' if status == 'SUCCEEDED' else None, 'errorCode': None, 'downloadUrl': 'https://invalid.example/never-follow-this'}

    def status_of(job_id, status):
        jobs[job_id].update(status=status, rows=12345 if status == 'SUCCEEDED' else 0, expiresAt='2026-11-19 10:00:00' if status == 'SUCCEEDED' else None)

    async def route_api(route):
        nonlocal counter
        req = route.request
        path, query = urlsplit(req.url).path, parse_qs(urlsplit(req.url).query)
        if not path.startswith('/api/'):
            await route.continue_()
            return
        requests.append({'method': req.method, 'path': path, 'query': query})
        data, status = None, 200
        if path == '/api/stock-logs/seek':
            data = {'list': [{'id': '701', 'docNo': query.get('keyword', ['SYN-STOCK'])[0], 'bizDate': '2026-11-18', 'warehouseName': '合成原料仓', 'changeType': 'PURCHASE_IN', 'skuCode': 'ML-SYNTHETIC', 'materialName': '合成面料', 'changeQty': 2, 'balanceQty': 12, 'stockUnit': 'M', 'dyeLot': 'SYN-LOT', 'operatorName': '回归仓管'}], 'nextCursor': None, 'hasMore': False}
        elif path == '/api/warehouses/options': data = [{'id': '31', 'name': '合成原料仓', 'code': 'YL', 'type': 'MATERIAL', 'companyId': '1', 'status': 1}]
        elif path.startswith('/api/dict-data/by-type/'): data = [{'value': 'M', 'label': '米', 'status': 1}] if path.endswith('/unit') else []
        elif path == '/api/export-jobs/stock-logs' and req.method == 'POST':
            key = req.headers.get('idempotency-key')
            writes.append({'path': path, 'key': key, 'body': req.post_data_json})
            assert re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', key or '')
            assert req.headers.get('authorization') == 'Bearer synthetic-access'
            if key not in keys:
                job_id = str(counter)
                counter += 2
                keys[key] = job_id
                jobs[job_id] = make_job(job_id, state['next_status'])
                flows[job_id] = list(state['next_flow'])
            job_id = keys[key]
            await asyncio.sleep(.08)
            if state['fail_create_once']:
                state['fail_create_once'] = False
                await route.fulfill(headers={'X-Request-Id': trace}, json={'code': 500, 'msg': '合成结果未知，请重试原提交核对', 'data': None})
                return
            data, status = dict(jobs[job_id]), 202
        elif re.fullmatch(r'/api/export-jobs/[1-9]\d*(/(cancel|retry|file))?', path):
            job_id = path.split('/')[3]
            assert job_id in jobs, path
            if path.endswith('/file'):
                assert req.method == 'GET'
                if state['slow_file']: await asyncio.sleep(1)
                if state['file_denied']:
                    await route.fulfill(headers={'X-Request-Id': trace}, json={'code': 403, 'msg': '当前权限不可下载此文件', 'data': None})
                else:
                    try: await route.fulfill(body=b'synthetic stock-log export bytes', headers={'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'Content-Disposition': "attachment; filename*=UTF-8''" + quote('库存日志-合成.xlsx')})
                    except Exception: pass  # The close-window assertion deliberately aborts this download.
                return
            if path.endswith('/cancel') or path.endswith('/retry'):
                assert req.method == 'POST'
                key = req.headers.get('idempotency-key')
                assert re.fullmatch(r'[0-9a-f-]{36}', key or '') and req.post_data_json == {}
                writes.append({'path': path, 'key': key, 'body': req.post_data_json})
                if path.endswith('/cancel'):
                    status_of(job_id, 'PROCESSING')
                    flows[job_id] = ['CANCELLED']
                else:
                    status_of(job_id, 'PENDING')
                    jobs[job_id]['errorCode'] = None
                    jobs[job_id]['attempts'] += 1
                    flows[job_id] = ['SUCCEEDED']
                    status = 202
                data = dict(jobs[job_id])
            else:
                assert req.method == 'GET'
                if flows[job_id]: status_of(job_id, flows[job_id].pop(0))
                data = dict(jobs[job_id])
                if state['slow_read'] == job_id: await asyncio.sleep(1)
        else:
            unknown.append({'method': req.method, 'path': path})
            await route.fulfill(json={'code': 500, 'msg': 'unconfigured export fixture route', 'data': None})
            return
        try: await route.fulfill(status=status, json={'code': 0, 'msg': '', 'data': data})
        except Exception: pass  # Deliberate stale-read/close cancellation can win before this response.

    await context.route('**/api/**', route_api)

    async def new_page():
        page = await context.new_page()
        page.on('requestfailed', lambda req: failed.append({'path': urlsplit(req.url).path, 'failure': req.failure}))
        await page.goto(url + '?exports=1')
        await expect(page.locator('main.export-fixture .el-table-v2__main .el-table-v2__row')).to_have_count(1)
        return page

    async def search(page, value):
        field = page.get_by_placeholder('单号、SKU 码、物料名称、缸号')
        await field.fill(value)
        await field.press('Enter')
        await expect(page.locator('.el-table-v2__left .el-table-v2__row').get_by_role('button', name=value, exact=True)).to_be_visible()

    def dialog(page): return page.get_by_role('dialog', name='库存日志导出', exact=True)
    def heading(page): return dialog(page).locator('.export-status-heading strong')
    async def create(page):
        await page.get_by_role('button', name='导出', exact=True).click()
        await expect(dialog(page)).to_be_visible()
        await expect(heading(page)).to_be_visible()
        return next(reversed(jobs))

    page = await new_page()
    await search(page, 'FIRST-SNAPSHOT')
    await page.get_by_placeholder('单号、SKU 码、物料名称、缸号').fill('UNSEARCHED-CHANGE')
    await page.get_by_role('button', name='导出', exact=True).dblclick()
    await expect(heading(page)).to_have_text('等待处理')
    assert len(writes) == 1 and writes[0]['body'] == {'keyword': 'FIRST-SNAPSHOT'}, writes
    first_id = next(iter(jobs))
    assert first_id == '9007199254740993'
    metrics['checks']['export_snapshot_and_double_click'] = True

    await expect(heading(page)).to_have_text('文件已就绪', timeout=10000)
    await expect(dialog(page)).to_contain_text('12,345')
    await dialog(page).screenshot(path=str(artifacts / 'export-ready.png'))
    async with page.expect_download() as pending:
        await dialog(page).get_by_role('button', name='下载文件', exact=True).click()
    downloaded = await pending.value
    assert downloaded.suggested_filename == '库存日志-合成.xlsx'
    from pathlib import Path
    assert Path(await downloaded.path()).read_bytes() == b'synthetic stock-log export bytes'
    assert requests[-1]['path'] == f'/api/export-jobs/{first_id}/file'
    metrics['checks']['export_poll_and_same_origin_download'] = True

    await page.reload()
    await expect(page.locator('main.export-fixture .el-table-v2__main .el-table-v2__row')).to_have_count(1)
    await page.get_by_role('button', name='导出任务', exact=True).click()
    await expect(heading(page)).to_have_text('文件已就绪')
    await expect(dialog(page)).to_contain_text(first_id)
    assert len(writes) == 1, writes
    receipts = await page.evaluate("Object.entries(localStorage).filter(([key]) => key.startsWith('lingmao-export-job-v1:')).map(([key, raw]) => [key, JSON.parse(raw)])")
    assert len(receipts) == 1 and set(receipts[0][1]) == {'id', 'owner', 'createdAt', 'savedAt'}, receipts
    assert receipts[0][1]['owner'] == 'user:9007199254740993', receipts
    metrics['checks']['export_reload_resumes_owned_reference'] = True

    # Exercise the actual input's local guard without the document listener masking a missing guard.
    lookup_input = dialog(page).get_by_placeholder('输入本人创建的任务编号')
    await lookup_input.fill(first_id)
    await page.evaluate('window.__exportImeGuard(false)')
    try:
        before = len(requests)
        await lookup_input.dispatch_event('compositionstart', {'data': '查询'})
        await lookup_input.dispatch_event('keyup', {'key': 'Enter', 'code': 'Enter', 'keyCode': 13, 'isComposing': True})
        await lookup_input.dispatch_event('compositionend', {'data': '查询'})
        await lookup_input.dispatch_event('keyup', {'key': 'Enter', 'code': 'Enter', 'keyCode': 229, 'isComposing': False})
        await page.wait_for_timeout(100)
        assert len(requests) == before, requests[before:]
        async with page.expect_response(lambda r: urlsplit(r.url).path == f'/api/export-jobs/{first_id}'):
            await lookup_input.press('Enter')
        assert len(requests) == before + 1 and requests[-1]['path'] == f'/api/export-jobs/{first_id}', requests[before:]
    finally:
        await page.evaluate('window.__exportImeGuard(true)')
    metrics['checks']['export_lookup_ime_local_guard'] = True

    await dialog(page).get_by_role('button', name='关闭', exact=True).click()
    state.update(next_status='PROCESSING', next_flow=[])
    second_id = await create(page)
    await dialog(page).get_by_role('button', name='取消任务', exact=True).click()
    await expect(dialog(page)).to_contain_text('取消请求已收到')
    await expect(dialog(page).get_by_role('button', name='取消任务', exact=True)).to_be_disabled()
    await dialog(page).screenshot(path=str(artifacts / 'export-cancelling.png'))
    await expect(heading(page)).to_have_text('已取消', timeout=7000)
    assert writes[-1]['path'] == f'/api/export-jobs/{second_id}/cancel'
    metrics['checks']['export_cancel_waits_for_worker'] = True

    retry_keys = []
    for status, label in [('CANCELLED', '已取消'), ('DEAD_LETTER', '生成失败'), ('EXPIRED', '文件已过期')]:
        status_of(second_id, status)
        flows[second_id] = []
        jobs[second_id]['errorCode'] = 'SYNTHETIC_RETRY_EXHAUSTED' if status == 'DEAD_LETTER' else None
        await dialog(page).get_by_role('button', name='刷新状态', exact=True).click()
        await expect(heading(page)).to_have_text(label)
        if status == 'DEAD_LETTER':
            await expect(dialog(page)).to_contain_text('导出未完成，请联系管理员核查')
            assert 'SYNTHETIC_RETRY_EXHAUSTED' not in await dialog(page).inner_text()
            await expect(dialog(page).locator('.export-trace')).to_contain_text(trace)
            await dialog(page).screenshot(path=str(artifacts / 'export-failed.png'))
        await dialog(page).get_by_role('button', name='重新生成', exact=True).click()
        await expect(heading(page)).to_have_text('等待处理')
        retry_keys.append(writes[-1]['key'])
        assert writes[-1]['path'] == f'/api/export-jobs/{second_id}/retry' and writes[-1]['body'] == {}
        await expect(heading(page)).to_have_text('文件已就绪', timeout=7000)
    assert len(set(retry_keys)) == 3
    metrics['checks']['export_retry_failed_cancelled_and_expired'] = True

    messages = {
        'INVALID_INPUT': '导出条件无效', 'PERMISSION_REVOKED': '导出权限已变更',
        'ROW_LIMIT': '超过 100 万行', 'SIZE_LIMIT': '超过 250 MB', 'DEADLINE': '超过 15 分钟',
        'DISK_FULL': '服务器存储空间不足', 'FILE_MISSING': '导出文件已不可用', 'CANCELLED': '导出任务已取消',
        'LEASE_LOST': '后台处理暂时中断', 'LEASE_EXPIRED': '后台处理未及时完成',
        'SHUTDOWN': '后台服务正在重启', 'TEMPORARY_FAILURE': '后台服务暂时不可用',
        'SYNTHETIC_UNKNOWN_CODE': '导出未完成，请联系管理员核查',
    }
    for code, message in messages.items():
        status_of(second_id, 'CANCELLED' if code == 'CANCELLED' else 'DEAD_LETTER')
        jobs[second_id].update(errorCode=code, attempts=5)
        flows[second_id] = []
        async with page.expect_response(lambda r: urlsplit(r.url).path == f'/api/export-jobs/{second_id}'):
            await dialog(page).get_by_role('button', name='刷新状态', exact=True).click()
        await expect(dialog(page)).to_contain_text(message)
        assert code not in await dialog(page).inner_text(), code
        await expect(dialog(page).locator('.export-trace')).to_contain_text(trace)
        if code == 'DISK_FULL': await dialog(page).screenshot(path=str(artifacts / 'export-disk-full.png'))
    await dialog(page).screenshot(path=str(artifacts / 'export-error-unknown.png'))
    await context.grant_permissions(['clipboard-read', 'clipboard-write'])
    await dialog(page).locator('.export-trace').get_by_role('button', name='复制', exact=True).click()
    assert await page.evaluate('navigator.clipboard.readText()') == trace
    status_of(second_id, 'SUCCEEDED')
    jobs[second_id]['errorCode'] = None
    await dialog(page).get_by_role('button', name='刷新状态', exact=True).click()
    await expect(heading(page)).to_have_text('文件已就绪')
    metrics['checks']['export_chinese_errors_and_trace_copy'] = True

    state['file_denied'] = True
    await dialog(page).get_by_role('button', name='下载文件', exact=True).click()
    await expect(dialog(page).get_by_role('alert')).to_contain_text('当前权限不可下载此文件')
    await expect(dialog(page).locator('.export-trace')).to_contain_text(trace)
    state['file_denied'] = False
    metrics['checks']['export_permission_failure_keeps_server_trace'] = True

    state['slow_read'] = second_id
    async with page.expect_request(lambda r: urlsplit(r.url).path == f'/api/export-jobs/{second_id}'):
        await dialog(page).get_by_role('button', name='刷新状态', exact=True).click()
    await dialog(page).get_by_role('button', name='关闭', exact=True).click()
    await page.wait_for_timeout(200)
    assert any(r['path'] == f'/api/export-jobs/{second_id}' and r['failure'] == 'net::ERR_ABORTED' for r in failed), failed
    state['slow_read'] = None
    await page.get_by_role('button', name='导出任务', exact=True).click()
    await expect(heading(page)).to_have_text('文件已就绪')
    state['slow_file'] = True
    async with page.expect_request(lambda r: urlsplit(r.url).path == f'/api/export-jobs/{second_id}/file'):
        await dialog(page).get_by_role('button', name='下载文件', exact=True).click()
    await dialog(page).get_by_role('button', name='关闭', exact=True).click()
    await page.wait_for_timeout(200)
    assert any(r['path'] == f'/api/export-jobs/{second_id}/file' and r['failure'] == 'net::ERR_ABORTED' for r in failed), failed
    state['slow_file'] = False
    metrics['checks']['export_close_aborts_poll_and_download'] = True

    state.update(next_status='SUCCEEDED', next_flow=[], fail_create_once=True)
    await search(page, 'UNKNOWN-RESULT')
    before = len(writes)
    await page.get_by_role('button', name='导出', exact=True).click()
    await expect(dialog(page).get_by_role('alert')).to_contain_text('合成结果未知')
    # Another tab with the same original filters must record the key chosen by the shared receipt,
    # not its newly proposed UUID. It must retain that key after the first tab clears the receipt.
    recovery_tab = await new_page()
    await search(recovery_tab, 'UNKNOWN-RESULT')
    state['fail_create_once'] = True
    await recovery_tab.get_by_role('button', name='导出', exact=True).click()
    await expect(dialog(recovery_tab).get_by_role('alert')).to_contain_text('合成结果未知')
    snapshots = []
    for tab in [page, recovery_tab]:
        snapshots.append(await tab.evaluate("JSON.parse(sessionStorage.getItem('lingmao-export-create-v1:user:9007199254740993'))"))
    assert snapshots[0]['key'] == snapshots[1]['key'] == writes[before]['key'], snapshots
    assert set(snapshots[0]) == {'identity', 'key', 'filters', 'createdAt'} and snapshots[0]['filters'] == {'keyword': 'UNKNOWN-RESULT'}, snapshots
    await page.reload()
    await expect(page.locator('main.export-fixture .el-table-v2__main .el-table-v2__row')).to_have_count(1)
    await search(page, 'DIFFERENT-AFTER-RELOAD')
    await page.get_by_role('button', name='导出', exact=True).click()
    await expect(dialog(page).get_by_role('alert')).to_contain_text('前次创建结果尚未确认')
    await dialog(page).screenshot(path=str(artifacts / 'export-unknown-recovery.png'))
    assert len(writes) == before + 2, writes[before:]
    await dialog(page).get_by_role('button', name='重试创建', exact=True).click()
    await expect(heading(page)).to_have_text('文件已就绪')
    await dialog(recovery_tab).get_by_role('button', name='重试创建', exact=True).click()
    await expect(heading(recovery_tab)).to_have_text('文件已就绪')
    original = writes[before]
    assert len(writes[before:]) == 4 and all(item['key'] == original['key'] and item['body'] == {'keyword': 'UNKNOWN-RESULT'} for item in writes[before:]), writes[before:]
    for tab in [page, recovery_tab]:
        assert await tab.evaluate("sessionStorage.getItem('lingmao-export-create-v1:user:9007199254740993')") is None
    await recovery_tab.close()
    metrics['checks']['export_unknown_create_reuses_frozen_key_and_body'] = True
    metrics['checks']['export_unknown_reload_and_two_tab_key_recovery'] = True

    # Two real tabs refresh different jobs concurrently; independent receipt keys must retain both.
    other = await new_page()
    await other.get_by_role('button', name='导出任务', exact=True).click()
    await expect(heading(other)).to_be_visible()
    async def lookup(tab, job_id):
        await dialog(tab).get_by_placeholder('输入本人创建的任务编号').fill(job_id)
        await dialog(tab).get_by_role('button', name='查询任务', exact=True).click()
        await expect(heading(tab)).to_have_text('文件已就绪')
        await expect(dialog(tab)).to_contain_text(job_id)
    await asyncio.gather(lookup(page, first_id), lookup(other, second_id))
    cache = await page.evaluate("Object.entries(localStorage).filter(([key]) => key.startsWith('lingmao-export-job-v1:'))")
    assert {key for key, _ in cache} >= {f'lingmao-export-job-v1:{first_id}', f'lingmao-export-job-v1:{second_id}'}, cache
    await page.reload()
    await page.get_by_role('button', name='导出任务', exact=True).click()
    await expect(heading(page)).to_be_visible()
    assert await dialog(page).locator('.el-select').count() == 1
    await other.close()
    metrics['checks']['export_two_tab_history_preserves_each_job'] = True

    isolated = await browser.new_context(viewport={'width': 1440, 'height': 1080})
    observe_context(isolated)
    await isolated.route('**/api/**', route_api)
    await isolated.add_init_script("if (location.origin !== 'null') for (const [key, raw] of " + __import__('json').dumps(cache) + ') localStorage.setItem(key, raw)')
    foreign = await isolated.new_page()
    await foreign.goto(url + '?exports=1&owner=9007199254740995')
    await expect(foreign.locator('main.export-fixture .el-table-v2__main .el-table-v2__row')).to_have_count(1)
    before = len(requests)
    await foreign.get_by_role('button', name='导出任务', exact=True).click()
    await expect(dialog(foreign)).to_contain_text('暂无任务')
    assert await dialog(foreign).locator('.el-select').count() == 0
    assert not any('/api/export-jobs/' in r['path'] for r in requests[before:])
    await isolated.close()
    metrics['checks']['export_history_never_restores_other_account'] = True

    await dialog(page).get_by_role('button', name='关闭', exact=True).click()
    await page.evaluate("""() => { const original = Storage.prototype.setItem; Storage.prototype.setItem = function(key, value) { if (key.startsWith('lingmao-export-job-v1:')) throw new DOMException('synthetic quota', 'QuotaExceededError'); return original.call(this, key, value) } }""")
    state.update(next_status='SUCCEEDED', next_flow=[])
    last_id = await create(page)
    await expect(dialog(page).get_by_role('alert')).to_contain_text('本机无法记录任务')
    await expect(dialog(page)).to_contain_text(last_id)
    await dialog(page).screenshot(path=str(artifacts / 'export-storage-recovery.png'))
    await page.reload()
    await page.get_by_role('button', name='导出任务', exact=True).click()
    await expect(heading(page)).to_be_visible()
    await lookup(page, last_id)
    metrics['checks']['export_storage_failure_allows_manual_job_lookup'] = True

    await dialog(page).get_by_role('button', name='关闭', exact=True).click()
    await page.evaluate("""() => { const original = Storage.prototype.setItem; Storage.prototype.setItem = function(key, value) { if (this === sessionStorage && key.startsWith('lingmao-export-create-v1:')) throw new DOMException('synthetic quota', 'QuotaExceededError'); return original.call(this, key, value) } }""")
    before = len(writes)
    await page.get_by_role('button', name='导出', exact=True).click()
    await expect(dialog(page).get_by_role('alert')).to_contain_text('无法保存原提交凭据')
    await dialog(page).screenshot(path=str(artifacts / 'export-submit-storage-blocked.png'))
    assert len(writes) == before, writes[before:]
    metrics['checks']['export_cannot_send_without_recovery_snapshot'] = True

    assert not unknown, unknown
    assert not any(r['path'] == '/api/stock-logs/export' for r in requests), requests
    await context.close()
