#!/usr/bin/env python3
"""Actual FG dialogs and transport; synthetic HTTP, no backend/database claim."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess

from playwright.async_api import async_playwright, expect
import fg

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/fg-session'


async def settle(page):
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')


async def session_change(page, mode):
    return await page.evaluate("""async mode => {
      const { useUserStore } = await import('/src/stores/user.ts');
      const token = await import('/src/utils/token.ts');
      const user = useUserStore();
      const snapshot = () => ({ epoch: token.sessionEpoch(), identity: token.sessionIdentity(), company: user.defaultCompanyId,
        roles: [...(user.info?.roles ?? [])].sort(), data_all: user.hasPerm('data:all') });
      const before = snapshot();
      if (mode === 'company') user.info = { ...user.info, defaultCompany: { id: '2', name: '合成主体乙' } };
      else if (mode === 'roles') user.info = { ...user.info, roles: user.info.roles.filter(role => role !== 'finance') };
      else if (mode === 'data-all') user.info = { ...user.info, perms: user.info.perms.filter(perm => perm !== 'data:all') };
      else {
        token.setToken('synthetic-fg-session-' + crypto.randomUUID(), mode === 'refresh');
        user.token = token.getToken();
        if (mode === 'identity') user.info = { ...user.info, id: '9007199254740994', username: 'synthetic-next' };
        token.setSessionSubject(user.info.id);
      }
      await Promise.resolve();
      return { before, after: snapshot() };
    }""", mode)


def field(dialog, label):
    return dialog.locator('.el-form-item').filter(has=dialog.page.locator('.el-form-item__label', has_text=re.compile('^' + label + '$')))


async def manual(page):
    await page.get_by_role('button', name='其他入库', exact=True).click()
    dialog = page.get_by_role('dialog', name='新建其他入库', exact=True)
    await dialog.get_by_role('button', name='选款式', exact=True).click()
    picker = page.get_by_role('dialog', name='从款式库选', exact=True)
    await picker.locator('.el-table__body-wrapper tbody tr').first.click()
    await picker.get_by_role('button', name='加入 1 条', exact=True).click()
    await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
    await field(dialog, '备注').get_by_role('textbox').fill('刷新后保留的其他入库备注')
    await expect(dialog.locator('.grand')).to_have_text('17')
    return dialog


async def released(held):
    held['release'].set()
    if held['started'].is_set(): await asyncio.wait_for(held['finished'].wait(), 5)


async def check_inputs(browser, base, report):
    for kind in ['manual', 'receipt']:
        context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-in?tab=docs&companyId=1' if kind == 'manual' else '/fg-panels')
        try:
            if kind == 'manual': dialog = await manual(page)
            else:
                await page.get_by_role('button', name='打开第一回货', exact=True).click()
                dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
                await expect(dialog.locator('.grand')).to_have_text('396')
                await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
                await field(dialog, '备注').get_by_role('textbox').fill('刷新后保留的回货备注')
            scope = await session_change(page, 'refresh'); await settle(page)
            await fg.capture(page, kind + '-refresh-input.png')
            visible = await dialog.is_visible()
            quantity_box = dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)
            quantity = await quantity_box.input_value() if visible and await quantity_box.count() else None
            remark = await field(dialog, '备注').get_by_role('textbox').input_value() if visible else None
            passed = scope['before'] == scope['after'] and visible and quantity == '17' and '刷新后保留' in (remark or '')
            report['checks'][kind + '_refresh_preserves_input'] = {'passed': passed, 'scope': scope, 'visible': visible, 'quantity': quantity, 'remark': remark, 'aborted': aborted}
            assert passed, report['checks'][kind + '_refresh_preserves_input']
            assert not [call for call in api.calls if call['method'] != 'GET']
        finally: await fg.finish(context, report, console)


async def check_profile(browser, base, report):
    context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-in?tab=docs&companyId=1')
    try:
        dialog = await manual(page)
        info = await page.evaluate("async () => { const { useUserStore } = await import('/src/stores/user.ts'); return JSON.parse(JSON.stringify(useUserStore().info)); }")
        profile_reads = []

        async def me(route):
            assert route.request.method == 'GET' and route.request.headers.get('x-request-id')
            profile_reads.append(True)
            await route.fulfill(json={'code': 0, 'msg': '', 'data': info}, headers={'X-Request-Id': 'synthetic-fg-profile'})

        await context.route('**/api/auth/me', me)
        unchanged = await page.evaluate("""async () => {
          const { useUserStore } = await import('/src/stores/user.ts');
          const token = await import('/src/utils/token.ts'); const user = useUserStore();
          const snapshot = () => JSON.stringify([token.sessionEpoch(), token.sessionIdentity(), user.defaultCompanyId]);
          const before = snapshot(); await user.fetchMe(); return snapshot() === before;
        }""")
        await settle(page)
        await fg.capture(page, 'same-profile-input.png')
        visible = await dialog.is_visible()
        quantity_box = dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)
        quantity = await quantity_box.input_value() if visible and await quantity_box.count() else None
        passed = unchanged and len(profile_reads) == 1 and visible and quantity == '17'
        report['checks']['same_profile_fetch_preserves_input'] = {'passed': passed, 'scope_unchanged': unchanged, 'profile_reads': len(profile_reads), 'visible': visible, 'quantity': quantity, 'aborted': aborted}
        assert passed, report['checks']['same_profile_fetch_preserves_input']
    finally: await fg.finish(context, report, console)


async def check_automatic_refresh(browser, base, report):
    context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-panels')
    held = api.hold('/api/fg-docs/from-receipt/' + fg.RECEIPT)
    refresh_calls, protected_calls = [], []
    try:
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)

        async def refresh(route):
            request = route.request
            assert request.method == 'POST' and request.post_data_json == {}
            assert request.headers.get('x-session-mode') == 'cookie' and request.headers.get('x-request-id')
            refresh_calls.append(True)
            await route.fulfill(json={'code': 0, 'msg': '', 'data': 'synthetic-automatic-refresh-result'}, headers={'X-Request-Id': 'synthetic-fg-refresh'})

        async def protected(route):
            protected_calls.append(True)
            if len(protected_calls) == 1:
                await route.fulfill(status=401, json={'code': 401, 'msg': '合成过期令牌', 'data': None})
            else: await route.fallback()

        await context.route('**/api/auth/refresh', refresh)
        await context.route('**/api/fg-stocks/qty*', protected)
        result = await page.evaluate("""async sku => {
          const { enableCookieSessionRefresh } = await import('/src/utils/request.ts');
          const { fgStockApi } = await import('/src/api/fg.ts');
          const { useUserStore } = await import('/src/stores/user.ts');
          const token = await import('/src/utils/token.ts'); const user = useUserStore();
          const before = { epoch: token.sessionEpoch(), identity: token.sessionIdentity(), company: user.defaultCompanyId };
          const storeToken = user.token; enableCookieSessionRefresh();
          const rows = await fgStockApi.qty('31', [sku], { companyId: '1' });
          return { before, after: { epoch: token.sessionEpoch(), identity: token.sessionIdentity(), company: user.defaultCompanyId }, store_token_unchanged: storeToken === user.token, rows: rows.length };
        }""", fg.style()['skus'][0]['id'])
        await released(held)
        dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
        await expect(dialog.locator('.grand')).to_have_text('396')
        await fg.capture(page, 'automatic-401-refresh-read.png')
        source_aborts = [row for row in aborted if row['path'] == '/api/fg-docs/from-receipt/' + fg.RECEIPT]
        passed = result['before'] == result['after'] and len(refresh_calls) == 1 and len(protected_calls) == 2 and result['rows'] == 1 and not source_aborts
        report['checks']['actual_401_refresh_preserves_pending_read'] = {'passed': passed, **result, 'refresh_calls': len(refresh_calls), 'protected_calls': len(protected_calls), 'source_aborts': source_aborts}
        assert passed, report['checks']['actual_401_refresh_preserves_pending_read']
    finally:
        await released(held)
        # This exact injected 401 is expected; do not suppress unrelated console messages.
        expected = [row for row in console if row['path'] == '/api/fg-stocks/qty' and 'Failed to load resource' in row['text'] and '401' in row['text']]
        report['expected_console_errors'].extend(expected)
        await fg.finish(context, report, [row for row in console if row not in expected])


async def check_pending_read(browser, base, report):
    context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-panels')
    held = api.hold('/api/fg-docs/from-receipt/' + fg.RECEIPT)
    try:
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        scope = await session_change(page, 'refresh'); await settle(page)
        loading_visible = await page.get_by_role('dialog').is_visible()
        await released(held); await settle(page)
        dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
        visible = await dialog.is_visible()
        if visible: await expect(dialog.locator('.grand')).to_have_text('396')
        await fg.capture(page, 'refresh-pending-read.png')
        source_aborts = [row for row in aborted if row['path'] == '/api/fg-docs/from-receipt/' + fg.RECEIPT]
        passed = scope['before'] == scope['after'] and loading_visible and visible and not source_aborts
        report['checks']['refresh_preserves_pending_read'] = {'passed': passed, 'scope': scope, 'loading_visible': loading_visible, 'result_visible': visible, 'source_aborts': source_aborts}
        assert passed, report['checks']['refresh_preserves_pending_read']
    finally:
        await released(held); await fg.finish(context, report, console)


async def check_pending_write(browser, base, report):
    context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-in?tab=docs&companyId=1')
    held = None
    try:
        dialog = await manual(page)
        await field(dialog, '入仓').locator('.el-select').click()
        await page.get_by_role('option', name='合成成品仓', exact=True).click()
        await field(dialog, '原因').locator('.el-select').click()
        await page.get_by_role('option', name='期初', exact=True).click()
        held = api.hold('/api/fg-docs')
        await dialog.get_by_role('button', name='存草稿', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        scope = await session_change(page, 'refresh'); await settle(page)
        await page.screenshot(path=str(ARTIFACTS / 'refresh-pending-write.png'), animations='disabled')
        visible_pending = await dialog.is_visible()
        await released(held); await settle(page)
        if visible_pending: await expect(page.get_by_test_id('route')).to_have_text('/fg-in/' + fg.DOC + '?companyId=1')
        writes = [call for call in api.calls if call['method'] == 'POST' and call['path'] == '/api/fg-docs']
        passed = scope['before'] == scope['after'] and visible_pending and len(writes) == 1 and len(api.created) == 1
        report['checks']['refresh_preserves_pending_write'] = {'passed': passed, 'scope': scope, 'visible_pending': visible_pending, 'write_count': len(writes), 'created': len(api.created), 'body_quantity': sum(line['qty'] for line in writes[0]['body']['items']) if writes else None, 'key_present': bool(writes and writes[0]['idempotency_key']), 'aborted': aborted}
        assert passed, report['checks']['refresh_preserves_pending_write']
    finally:
        if held: await released(held)
        await fg.finish(context, report, console)


async def check_invalidated(browser, base, report):
    for mode in ['identity', 'company', 'new-epoch', 'roles', 'data-all']:
        context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-panels')
        held = api.hold('/api/fg-docs/from-receipt/' + fg.RECEIPT)
        try:
            if mode in ['roles', 'data-all']:
                await page.evaluate("""async () => {
                  const { useUserStore } = await import('/src/stores/user.ts'); const user = useUserStore();
                  user.info = { ...user.info, roles: ['warehouse', 'finance'], perms: [...user.info.perms, 'data:all'] };
                }""")
            await page.get_by_role('button', name='打开第一回货', exact=True).click()
            await asyncio.wait_for(held['started'].wait(), 5)
            scope = await session_change(page, mode)
            await released(held); await settle(page)
            await expect(page.get_by_role('dialog')).not_to_be_visible()
            source_aborts = [row for row in aborted if row['path'] == '/api/fg-docs/from-receipt/' + fg.RECEIPT]
            await fg.capture(page, mode + '-pending-read-closed.png')
            passed = scope['before'] != scope['after'] and bool(source_aborts)
            report['checks'][mode + '_cancels_and_closes'] = {'passed': passed, 'scope': scope, 'source_aborts': source_aborts, 'writes': len([call for call in api.calls if call['method'] != 'GET'])}
            assert passed, report['checks'][mode + '_cancels_and_closes']
        finally:
            await released(held); await fg.finish(context, report, console)


async def run(only, collect_failures):
    ARTIFACTS.mkdir(parents=True, exist_ok=True); fg.ARTIFACTS = ARTIFACTS
    fixture = ROOT / 'web/node_modules/.s3-fg'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['fg.html', 'FgFixture.vue', 'FgPanels.vue']: shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'scope': 'Actual FG Vue, session store, setToken(refresh=true), and pending browser requests with synthetic HTTP. No backend/database claim.', 'checks': {}, 'page_errors': [], 'console_errors': [], 'expected_console_errors': []}
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
                    for name, test in {'inputs': check_inputs, 'profile': check_profile, 'automatic': check_automatic_refresh, 'read': check_pending_read, 'write': check_pending_write, 'context': check_invalidated}.items():
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
    parser = argparse.ArgumentParser(); parser.add_argument('--only', nargs='+', choices=['inputs', 'profile', 'automatic', 'read', 'write', 'context']); parser.add_argument('--collect-failures', action='store_true'); parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.artifacts: ARTIFACTS = args.artifacts.resolve()
    asyncio.run(run(args.only, args.collect_failures))
