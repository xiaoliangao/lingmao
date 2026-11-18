#!/usr/bin/env python3
"""Browser faults use actual production components/network wrappers; no fixtures enter the production build."""
import asyncio
import json
import os
import re
from pathlib import Path
import shutil
import socket
import subprocess
import time
from urllib.parse import urlsplit
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'target/test-artifacts/a3'
FIXTURE = ROOT / 'web/node_modules/.a3-prod-readiness'

async def run():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    FIXTURE.mkdir(parents=True, exist_ok=True)
    for name in ['fixture.html', 'fixture.ts', 'Fixture.vue']:
        shutil.copyfile(Path(__file__).with_name(name), FIXTURE / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    log = (ARTIFACTS / 'vite.log').open('w')
    process = subprocess.Popen(['pnpm', 'exec', 'vite', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
    base = f'http://127.0.0.1:{port}'
    url = base + '/node_modules/.a3-prod-readiness/fixture.html'
    metrics = {'checks': {}, 'refresh_calls': 0, 'write_calls': 0, 'file_calls': 0, 'request_failures': []}
    try:
        for _ in range(200):
            if process.poll() is not None:
                raise RuntimeError('Vite exited; see artifact log')
            try:
                reader, writer = await asyncio.open_connection('127.0.0.1', port)
                writer.close(); await writer.wait_closed(); break
            except OSError: await asyncio.sleep(.1)
        async with async_playwright() as playwright:
            options = {'headless': True}
            if os.environ.get('SCM_UI_CHROMIUM'): options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
            browser = await playwright.chromium.launch(**options)
            context = await browser.new_context(viewport={'width': 1440, 'height': 1080}, timezone_id='America/Los_Angeles')
            errors = []
            console_errors = []
            def observe_context(observed):
                def observe_page(tab):
                    tab.on('pageerror', lambda error: errors.append({'page': tab.url, 'message': str(error)}))
                    def record_console(message):
                        if message.type == 'error':
                            console_errors.append({'page': tab.url, 'text': message.text, 'location': message.location})
                    tab.on('console', record_console)
                observed.on('page', observe_page)
            observe_context(context)
            aborted = []
            write_keys = []
            write_bodies = []
            write_companies = []
            wrapper_writes = []
            state = {'uncertain': False, 'refresh_fail': False}
            async def route_probe(route):
                path = route.request.url.split(base, 1)[-1]
                if path.startswith('/probe/auth-replay/'):
                    from urllib.parse import parse_qs, urlsplit
                    code = int(urlsplit(path).path.rsplit('/', 1)[-1])
                    transport = parse_qs(urlsplit(path).query).get('transport', ['business'])[0]
                    renewed = route.request.headers.get('authorization') == 'Bearer synthetic-renewed'
                    result = code if renewed else 401
                    await route.fulfill(status=result if transport == 'http' else 200, json={'code': result, 'msg': 'synthetic business failure', 'data': None})
                elif path.startswith('/probe/file'):
                    metrics['file_calls'] += 1
                    assert route.request.headers.get('x-company-id') == '1'
                    if 'slow=1' in path: await asyncio.sleep(1)
                    try: await route.fulfill(body='synthetic file', content_type='application/octet-stream')
                    except Exception: pass
                elif path.startswith('/probe/auth/'):

                    ok = route.request.headers.get('authorization') == 'Bearer synthetic-renewed'
                    await route.fulfill(json={'code': 0 if ok else 401, 'msg': '', 'data': path if ok else None})
                elif path == '/probe/refresh':
                    metrics['refresh_calls'] += 1
                    await asyncio.sleep(.15)
                    await route.fulfill(json={'code': 401 if state['refresh_fail'] else 0, 'msg': '', 'data': 'synthetic-renewed'})
                elif path.startswith('/probe/search'):
                    from urllib.parse import parse_qs, urlsplit
                    keyword = parse_qs(urlsplit(path).query).get('keyword', [''])[0]
                    await asyncio.sleep(.8 if keyword in ['slow', 'cached'] else .03)
                    try: await route.fulfill(json={'code': 0, 'msg': '', 'data': {'list': [{'id': '9007199254740993', 'keyword': keyword}], 'total': 1}})
                    except Exception: pass  # A cancelled transport is an expected fault outcome.
                elif path.startswith('/probe/id/'):
                    await route.fulfill(json={'code': 0, 'msg': '', 'data': path.rsplit('/', 1)[-1]})
                elif path.endswith('.png'):
                    await route.fulfill(status=404, body='missing synthetic image')
                else: await route.fulfill(status=404, body='unknown test route')
            async def route_write(route):
                metrics['write_calls'] += 1
                write_keys.append(route.request.headers.get('idempotency-key'))
                write_bodies.append(route.request.post_data)
                write_companies.append(route.request.headers.get('x-company-id'))
                if json.loads(route.request.post_data or '{}').get('amount') == 401 and route.request.headers.get('authorization') != 'Bearer synthetic-renewed':
                    await route.fulfill(json={'code': 401, 'msg': '', 'data': None})
                    return
                if state['uncertain']:
                    state['uncertain'] = False
                    await route.abort('timedout')
                else:
                    await asyncio.sleep(.08)
                    await route.fulfill(json={'code': 0, 'msg': '', 'data': {'id': '9007199254740993', 'no': 'DD-SYNTHETIC'}})
            await context.route('**/probe/**', route_probe)
            await context.route('**/api/orders', route_write)
            await context.route('**/api/tech-packs/*/bom-defaults', lambda route: route.fulfill(json={'code': 0, 'msg': '', 'data': []}))
            async def wrapper_detail(route):
                await asyncio.sleep(.15)
                await route.fulfill(json={'code': 0, 'msg': '', 'data': {'id': '9007199254740993', 'companyId': '1'}})
            async def wrapper_write(route):
                path = urlsplit(route.request.url).path
                wrapper_writes.append({'path': path, 'body': route.request.post_data_json, 'company': route.request.headers.get('x-company-id'), 'key': route.request.headers.get('idempotency-key')})
                expired = path.endswith('/schedule') and route.request.headers.get('authorization') != 'Bearer synthetic-renewed'
                await route.fulfill(json={'code': 401 if expired else 0, 'msg': '', 'data': None if expired else []})
            await context.route('**/api/orders/9007199254740993', wrapper_detail)
            await context.route('**/api/orders/9007199254740993/submit', wrapper_write)
            await context.route('**/api/production-orders/schedule', wrapper_write)
            page = await context.new_page()
            page.on('requestfailed', lambda request: aborted.append({'path': request.url.split(base, 1)[-1], 'reason': request.failure}))
            page.on('dialog', lambda dialog: asyncio.create_task(dialog.accept()))
            await page.goto(url)
            await page.wait_for_function('Boolean(window.__a3)', timeout=60000)
            await page.locator('.el-table-v2__row').first.wait_for()
            assert await page.locator('.el-table-v2__row').count() < 100, 'row virtualization failed'
            metrics['virtual_rows'] = await page.locator('.el-table-v2__row').count()
            metrics['dom_nodes'] = await page.locator('*').count()
            await page.screenshot(path=str(ARTIFACTS / 'wide-table-before.png'), full_page=True)
            # Exercise the real grid: vertical, middle columns, then near the final columns.
            grid = page.locator('.el-table-v2__main .el-vl__window')
            if await grid.count() == 0: grid = page.locator('.el-table-v2__main').first
            await grid.hover()
            await page.mouse.wheel(0, 10000)
            await page.wait_for_timeout(150)
            await page.mouse.wheel(3000, 0)
            await page.wait_for_timeout(250)
            async def alignment(column):
                main = page.locator('.el-table-v2__main')
                header = main.locator('.el-table-v2__header-cell').filter(has_text=f'字段 {column}').first
                cell = main.locator('.el-table-v2__row-cell').filter(has_text=f'230-{column}').first
                h, b = await header.bounding_box(), await cell.bounding_box()
                assert h and b and 180 <= h['x'] < 1300, (column, h, b)
                assert abs(h['x'] - b['x']) < 1 and abs(h['width'] - b['width']) < 1, (h, b)
                frozen = {}
                for side in ['left', 'right']:
                    block = page.locator(f'.el-table-v2__{side}')
                    fh = await block.locator('.el-table-v2__header-cell').first.bounding_box()
                    fb = await block.locator('.el-table-v2__row-cell').first.bounding_box()
                    assert fh and fb and abs(fh['x'] - fb['x']) < 1, (side, fh, fb)
                    frozen[side] = {'header_x': fh['x'], 'body_x': fb['x']}
                return {'column': column, 'header_x': h['x'], 'body_x': b['x'], 'fixed': frozen}
            metrics['middle_alignment'] = await alignment(28)
            assert await page.locator('.el-table-v2__row').count() < 100
            await page.screenshot(path=str(ARTIFACTS / 'wide-table-middle.png'), full_page=True)
            await page.mouse.wheel(10000, 0)
            await page.wait_for_timeout(250)
            metrics['end_alignment'] = await alignment(50)
            await page.screenshot(path=str(ARTIFACTS / 'wide-table-end.png'), full_page=True)
            metrics['checks']['virtual_table'] = True
            await page.evaluate('window.__a3Updates = {}')
            start = time.perf_counter()
            await page.locator('.qty-input').first.fill('1234567')
            await page.wait_for_function("document.querySelector('#matrix-total').textContent === '1234567'")
            metrics['matrix_input_ms'] = round((time.perf_counter() - start) * 1000, 2)
            metrics['matrix_component_updates'] = await page.evaluate('window.__a3Updates')
            assert metrics['matrix_component_updates'].get('SkuMatrixRow') == 1, metrics['matrix_component_updates']
            assert await page.locator('.qty-input').nth(1).input_value() == ''
            async def quantity_visibility():
                return await page.locator('.qty-input').first.evaluate("""input => {
                    const style = getComputedStyle(input);
                    const canvas = document.createElement('canvas');
                    const context = canvas.getContext('2d');
                    context.font = style.font;
                    return { text: input.value, textWidth: context.measureText(input.value).width,
                      available: input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight),
                      scrollWidth: input.scrollWidth, clientWidth: input.clientWidth, scrollLeft: input.scrollLeft };
                }""")
            metrics['matrix_quantity_focused'] = await quantity_visibility()
            await page.locator('.qty-input').first.blur()
            metrics['matrix_quantity_blurred'] = await quantity_visibility()
            for measured in [metrics['matrix_quantity_focused'], metrics['matrix_quantity_blurred']]:
                assert measured['text'] == '1234567' and measured['available'] >= measured['textWidth'] + 1, measured
                assert measured['scrollWidth'] <= measured['clientWidth'] and measured['scrollLeft'] == 0, measured
            await page.locator('.matrix-scroll').screenshot(path=str(ARTIFACTS / 'matrix-input.png'))
            metrics['checks']['matrix_input'] = True
            search = page.locator('.filter-bar input').first
            baseline = (await page.evaluate('window.__a3.state()'))['searches']
            await search.dispatch_event('keyup', {'key': 'Enter', 'code': 'Enter', 'keyCode': 13, 'isComposing': True})
            await search.dispatch_event('keyup', {'key': 'Enter', 'code': 'Enter', 'keyCode': 229, 'isComposing': False})
            assert (await page.evaluate('window.__a3.state()'))['searches'] == baseline
            ime_input = page.locator('#native-ime-input')
            await ime_input.focus()
            await ime_input.dispatch_event('compositionstart', {'data': 'ni'})
            keyboard_default = await ime_input.evaluate("element => { const event = new KeyboardEvent('keydown', { key: 'Enter', keyCode: 229, isComposing: true, bubbles: true, cancelable: true }); element.dispatchEvent(event); return event.defaultPrevented; }")
            assert keyboard_default is False
            await ime_input.press('Enter')
            assert await page.evaluate('window.__a3.nativeSubmits()') == 0
            await ime_input.dispatch_event('compositionend', {'data': '你好'})
            await ime_input.fill('你好')
            await ime_input.press('Enter')
            assert await page.evaluate('window.__a3.nativeSubmits()') == 1
            assert await ime_input.input_value() == '你好'
            committed_default = await ime_input.evaluate("""element => {
                element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, data: 'ni' }));
                const key = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 229, isComposing: true, bubbles: true, cancelable: true });
                element.dispatchEvent(key);
                element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '你' }));
                element.form.requestSubmit();
                return key.defaultPrevented;
            }""")
            assert committed_default is False
            assert await page.evaluate('window.__a3.nativeSubmits()') == 1
            await page.wait_for_timeout(10)
            await page.locator('#native-ime-form').evaluate('form => form.requestSubmit()')
            assert await page.evaluate('window.__a3.nativeSubmits()') == 2
            await ime_input.dispatch_event('compositionstart', {'data': 'a'})
            await ime_input.dispatch_event('keydown', {'key': 'a', 'code': 'KeyA', 'keyCode': 229, 'isComposing': True})
            await ime_input.dispatch_event('keyup', {'key': 'a', 'code': 'KeyA', 'keyCode': 65, 'isComposing': True})
            await ime_input.dispatch_event('compositionend', {'data': '啊'})
            await page.wait_for_timeout(10)
            await page.locator('#native-ime-form').evaluate('form => form.requestSubmit()')
            assert await page.evaluate('window.__a3.nativeSubmits()') == 3
            await page.get_by_role('button', name='原生表单提交', exact=True).click()
            assert await page.evaluate('window.__a3.nativeSubmits()') == 4
            metrics['checks']['ime_native_form'] = True
            await page.evaluate("window.__a3.search('slow')")
            await page.wait_for_timeout(30)
            await page.evaluate("window.__a3.search('fast')")
            await page.wait_for_function("document.querySelector('#search-result').textContent === 'fast'")
            await page.wait_for_timeout(900)
            assert (await page.evaluate('window.__a3.state()'))['rows'][0]['keyword'] == 'fast'
            assert any('/probe/search' in record['path'] for record in aborted), 'transport was not aborted'
            metrics['checks']['ime_abort_race'] = True
            values = await page.evaluate('window.__a3.refresh()')
            assert len(values) == 8 and metrics['refresh_calls'] == 1
            metrics['checks']['single_flight_refresh'] = True
            for transport in ['business', 'http']:
                for code in [403, 500]:
                    await page.evaluate("window.__a3.setToken('synthetic-expired')")
                    returned = await page.evaluate('([code, mode]) => window.__a3.businessAfterRefresh(code, mode)', [code, transport])
                    assert returned == code and '/login' not in page.url, (code, transport, returned, page.url)
            metrics['checks']['replay_business_error_preserved'] = True
            await page.evaluate("window.__a3.setToken('synthetic-expired')")
            await page.evaluate('window.__a3.writeWith401()')
            assert write_keys[-1] == write_keys[-2] and write_bodies[-1] == write_bodies[-2]
            metrics['checks']['write_auth_replay_frozen'] = True
            await page.evaluate('window.__a3.readOnlyPost()')
            assert not (await page.evaluate('window.__a3.state()'))['pending']
            metrics['checks']['read_only_post'] = True
            await page.evaluate('window.__a3.cached(true)')
            await page.wait_for_timeout(30)
            await page.evaluate('window.__a3.cached(false)')
            await page.wait_for_timeout(30)
            await page.evaluate('window.__a3.cached(true)')
            await page.wait_for_function("document.querySelector('#cached-result')?.textContent === 'cached'")
            metrics['checks']['keepalive_pending_read'] = True
            assert await page.evaluate('window.__a3.file()') == 'synthetic file'
            assert await page.evaluate('window.__a3.id()') == '9007199254740993'
            metrics['checks']['bigint_route'] = True
            assert await page.evaluate('window.__a3.dates()') == ['2026-11-18', '2026-11-17']
            metrics['checks']['calendar_date'] = True
            before_write = metrics['write_calls']
            outcomes = await page.evaluate('window.__a3.parallelWrite()')
            assert len(outcomes) == 20 and metrics['write_calls'] == before_write + 1 and write_keys[-1]
            await page.evaluate('window.__a3.frozenCompany()')
            assert write_companies[-1] == '1' and json.loads(write_bodies[-1]) == {'companyId': '1', 'amount': 7}
            metrics['checks']['company_and_body_frozen'] = True
            for known_company in [False, True]:
                await page.evaluate("window.__a3.setToken('synthetic-expired')")
                before_wrapper = len(wrapper_writes)
                keys = await page.evaluate('known => window.__a3.frozenWrapper(known)', known_company)
                records = wrapper_writes[before_wrapper:]
                actions = [record for record in records if record['path'].endswith('/submit')]
                schedules = [record for record in records if record['path'].endswith('/schedule')]
                assert len(actions) == 1 and len(schedules) == 2, records
                assert actions[0]['body'] == {'version': 3, 'reason': 'original'} and actions[0]['key'] == keys['action']
                assert schedules[0] == schedules[1] and schedules[0]['key'] == keys['schedule']
                assert all(record['company'] == '1' for record in records)
                assert schedules[0]['body']['blocks'][0]['factoryId'] == '12'
                assert schedules[0]['body']['blocks'][0]['items'] == [{'orderItemId': '14', 'qty': 7}]
                assert schedules[0]['body']['blocks'][0]['remark'] == 'original'
            before_wrapper = len(wrapper_writes)
            assert await page.evaluate('window.__a3.wrapperIdentitySwitch()') == 409
            assert len(wrapper_writes) == before_wrapper
            await page.evaluate("window.__a3.setToken('synthetic-renewed')")
            metrics['checks']['api_wrapper_body_key_company_and_epoch_frozen'] = True
            metrics['checks']['write_mutex'] = True
            state['uncertain'] = True
            await page.evaluate('window.__a3.write(2).catch(() => null)')
            unknown_key = write_keys[-1]
            result = await page.evaluate('window.__a3.write(3).then(() => 0, e => e.code)')
            assert result == 1002
            assert await page.evaluate('window.__a3.changedCompany()') == 1002
            await page.get_by_role('button', name='重试原提交并核对').click()
            await page.get_by_role('button', name='已核对，开始新动作').wait_for()
            assert write_keys[-1] == unknown_key and write_bodies[-1] == write_bodies[-2]
            await page.get_by_role('button', name='已核对，开始新动作').click()
            await page.evaluate('window.__a3.write(3)')
            assert write_keys[-1] != unknown_key
            metrics['checks']['uncertain_write_recovery'] = True
            # Reload after an unknown result: metadata persists, request body/token do not.
            state['uncertain'] = True
            await page.evaluate('window.__a3.write(11).catch(() => null)')
            persisted_key = write_keys[-1]
            receipt = await page.evaluate("Object.keys(localStorage).filter(k => k.startsWith('lingmao-write-v1:')).map(k => JSON.parse(localStorage.getItem(k)))")
            assert len(receipt) == 1 and receipt[0]['key'] == persisted_key
            assert not any(field in receipt[0] for field in ['body', 'token', 'amount'])
            await page.reload()
            await page.wait_for_function('Boolean(window.__a3)')
            before_write = metrics['write_calls']
            assert await page.evaluate('window.__a3.write(12).then(() => 0, e => e.code)') == 1002
            assert metrics['write_calls'] == before_write
            await page.get_by_role('button', name='重试原提交并核对').wait_for()
            assert await page.get_by_role('button', name='重试原提交并核对').is_disabled()
            await page.evaluate('window.__a3.write(11)')
            assert write_keys[-1] == persisted_key
            assert await page.evaluate("Object.keys(localStorage).filter(k => k.startsWith('lingmao-write-v1:')).map(k => JSON.parse(localStorage.getItem(k))).length") == 0
            metrics['checks']['unknown_write_reload_preserves_key'] = True
            await page.locator('#draft-remark').fill('未保存的合成草稿')
            await page.wait_for_timeout(900)
            await page.reload()
            await page.get_by_role('button', name='恢复草稿', exact=True).click(timeout=30000)
            assert await page.locator('#draft-remark').input_value() == '未保存的合成草稿'
            metrics['checks']['draft_recovery'] = True
            await page.evaluate('window.__a3.discardDraft()')
            await page.wait_for_timeout(850)
            assert not await page.evaluate("Object.keys(localStorage).some(k => k.startsWith('lingmao-draft:'))")
            metrics['checks']['explicit_draft_discard'] = True
            await page.evaluate('window.__a3.break(true)')
            await page.get_by_text('这一部分暂时无法显示').wait_for()
            assert await page.locator('#nav-button').is_visible()
            await page.screenshot(path=str(ARTIFACTS / 'local-error-boundary.png'), full_page=True)
            await page.evaluate('window.__a3.break(false)')
            await page.get_by_role('button', name='重试', exact=True).click()
            await page.locator('#healthy-module').wait_for()
            metrics['checks']['local_boundary'] = True
            assert await page.locator('#broken-thumbnail').text_content() == '无图'
            await page.evaluate("sessionStorage.removeItem('lingmao-chunk-recovery')")
            await page.locator('#draft-remark').fill('更新故障时不能丢失')
            navigations = []
            page.on('framenavigated', lambda frame: navigations.append(frame.url) if frame == page.main_frame else None)
            await page.evaluate("window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))")
            await page.locator('.recovery-banner').wait_for()
            await page.wait_for_timeout(200)
            assert not navigations and await page.locator('#draft-remark').input_value() == '更新故障时不能丢失'
            await page.evaluate('window.__a3.discardDraft()')
            await page.evaluate("window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))")
            await page.wait_for_function('Boolean(window.__a3)')
            await page.wait_for_timeout(250)
            assert len(navigations) == 1, navigations
            await page.evaluate("window.dispatchEvent(new Event('vite:preloadError', { cancelable: true }))")
            await page.locator('.recovery-banner').wait_for()
            await page.wait_for_timeout(250)
            assert len(navigations) == 1, navigations
            await page.evaluate('window.__a3LazyFailure()')
            await page.locator('.recovery-banner').wait_for()
            assert len(navigations) == 1
            await page.screenshot(path=str(ARTIFACTS / 'chunk-recovery-banner.png'), full_page=True)
            metrics['checks']['chunk_real_reload_once_and_dirty_guard'] = True
            second = await context.new_page()
            await second.goto(url)
            await second.wait_for_function('Boolean(window.__a3)')
            await page.evaluate('window.__fileFailure = null; void window.__a3.file(true).catch(e => { window.__fileFailure = e.code })')
            await page.wait_for_timeout(50)
            await second.evaluate("window.__a3.setToken('synthetic-other-account')")
            await page.wait_for_function('window.__a3.state().sessionChange > 0')
            before = metrics['write_calls']
            assert await page.evaluate('window.__a3.write(99).then(() => 0, e => e.code)') == 409
            assert metrics['write_calls'] == before
            metrics['checks']['cross_tab_identity'] = True
            await page.wait_for_function('window.__fileFailure !== null')
            files_before = metrics['file_calls']
            assert await page.evaluate('window.__a3.file().then(() => 0, e => e.code)') == 409
            assert files_before == metrics['file_calls']
            metrics['checks']['download_identity_and_abort'] = True
            await second.close()
            # A new clean tab checks refresh failure through the real R.code=401 response path.
            failure = await context.new_page()
            await failure.route('**/login?**', lambda route: route.fulfill(body='<h1>登录</h1>', content_type='text/html'))
            await failure.goto(url)
            await failure.wait_for_function('Boolean(window.__a3)')
            await failure.evaluate("window.__a3.setToken('synthetic-expired')")
            state['refresh_fail'] = True
            before_refresh = metrics['refresh_calls']
            await failure.evaluate('void window.__a3.refresh().catch(() => {})')
            await failure.wait_for_url('**/login?**')
            assert metrics['refresh_calls'] == before_refresh + 1
            metrics['checks']['refresh_failure_logs_out'] = True
            # Optional storage may fail without a blank screen; irreversible writes fail before transport.
            storage_context = await browser.new_context()
            observe_context(storage_context)
            await storage_context.add_init_script("""
              const originalGet = Storage.prototype.getItem;
              Storage.prototype.getItem = function(key) { return key.startsWith('lingmao-cols:') ? '{broken' : originalGet.call(this, key); };
              Storage.prototype.setItem = function() { throw new DOMException('synthetic quota', 'QuotaExceededError'); };
            """)
            storage_page = await storage_context.new_page()
            await storage_page.goto(url)
            await storage_page.wait_for_function('Boolean(window.__a3)')
            assert await storage_page.evaluate('window.__a3.columns()') == {'date': True, 'note': False}
            await storage_page.evaluate('window.__a3.hideColumn()')
            assert await storage_page.evaluate('window.__a3.columns()') == {'date': False, 'note': False}
            assert await storage_page.locator('#nav-button').is_visible()
            assert await storage_page.evaluate('window.__a3.write(99).then(() => 0, e => e.code)') == 409
            assert await storage_page.evaluate('window.__a3.chunk()') is False
            await storage_context.close()
            metrics['checks']['corrupt_storage_and_quota'] = True
            # Two real tabs persist different uncertain actions at the same time.
            fault_context = await browser.new_context()
            observe_context(fault_context)
            fault_calls = []
            async def uncertain_write(route):
                fault_calls.append(route.request.url)
                await asyncio.sleep(.12)
                await route.abort('timedout')
            await fault_context.route('**/api/orders', uncertain_write)
            await fault_context.route('**/api/materials', uncertain_write)
            fault_tabs = [await fault_context.new_page(), await fault_context.new_page()]
            for tab in fault_tabs:
                await tab.goto(url)
                await tab.wait_for_function('Boolean(window.__a3)')
            await asyncio.gather(fault_tabs[0].evaluate('window.__a3.write(55).catch(() => null)'), fault_tabs[1].evaluate('window.__a3.otherWrite(66).catch(() => null)'))
            assert len(fault_calls) == 2
            await asyncio.gather(*(tab.reload() for tab in fault_tabs))
            for tab in fault_tabs:
                await tab.wait_for_function('Boolean(window.__a3)')
                assert await tab.evaluate("Object.keys(localStorage).filter(k => k.startsWith('lingmao-write-v1:')).length") == 2
                assert len((await tab.evaluate('window.__a3.state()'))['pending']) == 2
            # A duplicate scope must retain both receipts and refuse to pick an arbitrary key.
            await fault_tabs[0].evaluate("""() => {
              const rows = Object.keys(localStorage).filter(k => k.startsWith('lingmao-write-v1:')).map(k => JSON.parse(localStorage.getItem(k)));
              const order = rows.find(row => row.url === '/api/orders');
              const copy = { ...order, key: crypto.randomUUID() };
              localStorage.setItem('lingmao-write-v1:' + copy.key, JSON.stringify(copy));
            }""")
            await fault_tabs[0].reload()
            await fault_tabs[0].wait_for_function('Boolean(window.__a3)')
            assert len((await fault_tabs[0].evaluate('window.__a3.state()'))['pending']) == 3
            assert await fault_tabs[0].evaluate('window.__a3.write(55).then(() => 0, e => e.code)') == 1002
            assert len(fault_calls) == 2
            await fault_context.close()
            metrics['checks']['two_tab_receipts_and_duplicate_scope'] = True
            # The cookie contract is exercised through two actual tabs in one browser context.
            refresh_context = await browser.new_context()
            observe_context(refresh_context)
            await refresh_context.add_cookies([
                {'name': 'scm_refresh', 'value': 'synthetic-refresh-cookie', 'url': base + '/api/auth', 'httpOnly': True, 'sameSite': 'Strict'},
                {'name': 'scm_browser', 'value': 'synthetic-browser-cookie', 'url': base + '/api/auth', 'httpOnly': True, 'sameSite': 'Strict'},
            ])
            metrics['cookie_refresh_calls'] = 0
            metrics['cookie_logout_calls'] = 0
            cookie_started = asyncio.Event()
            cookie_release = asyncio.Event()
            cookie_release.set()
            async def cookie_refresh(route):
                metrics['cookie_refresh_calls'] += 1
                assert route.request.method == 'POST' and route.request.post_data_json == {}
                assert route.request.headers.get('x-requested-with') == 'XMLHttpRequest'
                assert 'scm_refresh=synthetic-refresh-cookie' in route.request.headers.get('cookie', '')
                cookie_started.set()
                await cookie_release.wait()
                await asyncio.sleep(.15)
                await route.fulfill(json={'code': 0, 'msg': '', 'data': 'synthetic-renewed'})
            async def cookie_logout(route):
                metrics['cookie_logout_calls'] += 1
                assert route.request.method == 'POST' and route.request.post_data_json == {}
                assert route.request.headers.get('x-requested-with') == 'XMLHttpRequest'
                assert route.request.headers.get('authorization') == 'Bearer synthetic-expired'
                assert 'scm_refresh=synthetic-refresh-cookie' in route.request.headers.get('cookie', '')
                await route.fulfill(json={'code': 0, 'msg': '', 'data': None})
            await refresh_context.route('**/probe/**', route_probe)
            await refresh_context.route('**/api/auth/refresh', cookie_refresh)
            await refresh_context.route('**/api/auth/logout', cookie_logout)
            refresh_tabs = [await refresh_context.new_page(), await refresh_context.new_page()]
            await refresh_tabs[0].goto(url)
            await refresh_tabs[0].wait_for_function('Boolean(window.__a3)')
            await refresh_tabs[0].evaluate("window.__a3.setToken('synthetic-expired')")
            await refresh_tabs[1].goto(url)
            for tab in refresh_tabs:
                await tab.wait_for_function('Boolean(window.__a3)')
                assert await tab.evaluate('Boolean(navigator.locks?.request)')
                await tab.evaluate('window.__a3.cookieRefresh()')
            results = await asyncio.gather(*(tab.evaluate('window.__a3.refresh()') for tab in refresh_tabs))
            assert all(len(value) == 8 for value in results) and metrics['cookie_refresh_calls'] == 1
            metrics['two_tab_cookie_refresh_calls'] = metrics['cookie_refresh_calls']
            assert await refresh_tabs[0].evaluate('window.__a3.session()') == await refresh_tabs[1].evaluate('window.__a3.session()')
            metrics['checks']['two_tab_cookie_refresh'] = True
            async def reset_refresh_tabs():
                await refresh_tabs[0].evaluate("window.__a3.setToken('synthetic-expired')")
                await asyncio.gather(*(tab.reload() for tab in refresh_tabs))
                for tab in refresh_tabs:
                    await tab.wait_for_function('Boolean(window.__a3)')
                    await tab.evaluate('window.__a3.cookieRefresh()')
            await reset_refresh_tabs()
            await refresh_tabs[0].evaluate("""() => {
              window.__lockHeld = false;
              void navigator.locks.request('lingmao:session-refresh:' + window.__a3.session().epoch, () =>
                new Promise(resolve => { window.__releaseLock = resolve; window.__lockHeld = true; }));
            }""")
            await refresh_tabs[0].wait_for_function('window.__lockHeld')
            await refresh_tabs[1].evaluate('window.__refreshOutcome = null; void window.__a3.refresh().then(() => { window.__refreshOutcome = 0 }, error => { window.__refreshOutcome = error.code })')
            await refresh_tabs[1].wait_for_function('navigator.locks.query().then(state => state.pending.length > 0)')
            before_refresh = metrics['cookie_refresh_calls']
            await refresh_tabs[0].evaluate("window.__a3.setToken('synthetic-other-account'); window.__releaseLock()")
            await refresh_tabs[1].wait_for_function('window.__refreshOutcome !== null')
            assert await refresh_tabs[1].evaluate('window.__refreshOutcome') == 409
            assert metrics['cookie_refresh_calls'] == before_refresh
            assert await refresh_tabs[0].evaluate('window.__a3.session().token') == 'synthetic-other-account'
            metrics['checks']['refresh_lock_epoch_guard'] = True
            await reset_refresh_tabs()
            cookie_started.clear(); cookie_release.clear()
            await refresh_tabs[1].evaluate('window.__refreshOutcome = null; void window.__a3.refresh().then(() => { window.__refreshOutcome = 0 }, error => { window.__refreshOutcome = error.code })')
            await asyncio.wait_for(cookie_started.wait(), timeout=10)
            await refresh_tabs[0].evaluate("window.__a3.setToken('synthetic-other-account')")
            cookie_release.set()
            await refresh_tabs[1].wait_for_function('window.__refreshOutcome !== null')
            assert await refresh_tabs[1].evaluate('window.__refreshOutcome') == 409
            assert await refresh_tabs[0].evaluate('window.__a3.session().token') == 'synthetic-other-account'
            assert await refresh_tabs[0].evaluate("JSON.parse(localStorage.getItem('lingmao-token')).token") == 'synthetic-other-account'
            metrics['checks']['refresh_rotation_epoch_guard'] = True
            await refresh_tabs[0].evaluate("window.__a3.setToken('synthetic-expired')")
            logout_epoch = await refresh_tabs[0].evaluate('window.__a3.session().epoch')
            await refresh_tabs[0].evaluate('window.__a3.logout()')
            session = await refresh_tabs[0].evaluate('window.__a3.session()')
            assert metrics['cookie_logout_calls'] == 1 and session['token'] == '' and session['epoch'] != logout_epoch
            metrics['checks']['cookie_logout_contract'] = True
            await refresh_context.close()
            metrics['request_failures'] = aborted
            metrics['page_errors'] = errors
            def expected_console(record):
                text = record['text']
                path = urlsplit(record['location'].get('url', '')).path
                if re.fullmatch(r'Module render failure \[[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\]', text):
                    return 'injected Vue render failure' if path.endswith('/src/components/ModuleBoundary.vue') else None
                if not text.startswith('Failed to load resource:'): return None
                if path in ['/probe/auth-replay/403', '/probe/auth-replay/500'] and re.search(r'status of (401|403|500)', text): return 'injected HTTP rejection'
                if path in ['/probe/missing-lazy.js', '/probe/broken-image.png'] and ('404' in text or 'ERR_ABORTED' in text): return 'injected missing resource'
                if path in ['/api/orders', '/api/materials'] and 'ERR_TIMED_OUT' in text: return 'injected uncertain write'
                return None
            for record in console_errors: record['expected_reason'] = expected_console(record)
            metrics['console_errors_expected'] = [record for record in console_errors if record['expected_reason']]
            metrics['console_errors_unexpected'] = [record for record in console_errors if not record['expected_reason']]
            metrics['console_error_counts'] = {'expected': len(metrics['console_errors_expected']), 'unexpected': len(metrics['console_errors_unexpected'])}
            (ARTIFACTS / 'results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
            assert not errors, errors
            assert not metrics['console_errors_unexpected'], metrics['console_errors_unexpected']
            metrics['checks']['all_pages_console_audit'] = True
            await browser.close()
        (ARTIFACTS / 'results.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2))
        print(json.dumps(metrics, ensure_ascii=False, indent=2))
    finally:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.close()
        shutil.rmtree(FIXTURE, ignore_errors=True)

if __name__ == '__main__': asyncio.run(run())
