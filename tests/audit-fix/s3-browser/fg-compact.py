#!/usr/bin/env python3
"""Actual FG detail/DocLogList and AppLayout, synthetic HTTP. No backend evidence."""
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
from urllib.parse import parse_qs, urlencode, urlsplit

from playwright.async_api import async_playwright, expect
from fg import DOC, SyntheticApi, doc

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/fg-compact'
LOGS = [
    {'id': '9007199254749101', 'action': 'audit', 'fromStatus': 'DRAFT', 'toStatus': 'APPROVED',
     'remark': '核对分批到货及色码数量后审核。\n批次凭证：BATCH-' + '0123456789' * 6,
     'operatorId': '9007199254740994', 'operatorName': '合成审核负责人 reviewer', 'createTime': '2026-11-23 08:32:04'},
    {'id': '9007199254749102', 'action': 'create', 'fromStatus': None, 'toStatus': 'DRAFT',
     'remark': None, 'operatorId': None, 'operatorName': None, 'createTime': '2026-11-23 08:30:01'},
]


class CompactApi(SyntheticApi):
    def __init__(self):
        super().__init__()
        head = doc()
        head['receipt']['no'] = 'HH202611230003'
        head['receipt']['receiptDate'] = '2026-11-23'
        head['status'] = 'APPROVED'
        head['actions'] = []
        self.created[DOC] = head
        self.log_rows = LOGS
        self.log_error = False
        self.log_hold = None
        self.tasks = set()
        self.errors = []

    async def route(self, route):
        task = asyncio.current_task()
        self.tasks.add(task)
        try:
            request = route.request
            url = urlsplit(request.url)
            if url.path == f'/api/fg-docs/{DOC}/logs':
                params = parse_qs(url.query)
                assert request.method == 'GET' and request.headers.get('x-company-id') == '1' and params.get('companyId') == ['1']
                self.calls.append({'method': request.method, 'path': url.path, 'company': '1', 'params': params})
                held = self.log_hold
                if held:
                    held['started'].set()
                    await asyncio.wait_for(held['release'].wait(), 15)
                if self.log_error:
                    await route.fulfill(status=503, json={'code': 503, 'msg': '合成日志故障', 'data': None}, headers={'X-Request-Id': 'synthetic-log-fault'})
                else:
                    await route.fulfill(json={'code': 0, 'msg': '', 'data': self.log_rows})
                if held: held['finished'].set()
            else:
                await super().route(route)
        except BaseException as error:
            self.errors.append(type(error).__name__ + ': ' + str(error))
            raise
        finally:
            self.tasks.discard(task)

    async def finish(self):
        if self.log_hold: self.log_hold['release'].set()
        if self.tasks: await asyncio.wait_for(asyncio.gather(*list(self.tasks)), 20)
        assert not self.errors, self.errors


async def ready_frame(page):
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')


async def shot(page, name):
    await ready_frame(page)
    await page.screenshot(path=str(ARTIFACTS / name), animations='disabled')


async def element_metrics(locator):
    return await locator.evaluate("""el => {
      const r = el.getBoundingClientRect(), s = getComputedStyle(el);
      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + Math.min(r.height / 2, 8));
      const range=document.createRange();range.selectNodeContents(el);
      const textRects=[...range.getClientRects()].filter(x=>x.width&&x.height).map(x=>({left:x.left,right:x.right,top:x.top,bottom:x.bottom}));
      return {text:el.textContent.trim(),left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,
        clientWidth:el.clientWidth,scrollWidth:el.scrollWidth,whiteSpace:s.whiteSpace,overflowWrap:s.overflowWrap,
        insideViewport:r.left>=0&&r.right<=innerWidth+1&&r.top>=0&&r.bottom<=innerHeight+1,
        unobscured:!!hit&&el.contains(hit),textRects};
    }""")


def passed(report, name, **details):
    report['checks'][name] = {'passed': True, **details}


async def session(browser, base, report, path, width=1440):
    context = await browser.new_context(viewport={'width': width, 'height': 1100})
    api = CompactApi()
    await context.route('**/api/**', api.route)
    page = await context.new_page()
    page.set_default_timeout(12000)
    page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
    def console(message):
        if message.type != 'error': return
        row = {'text': message.text, 'path': urlsplit(message.location.get('url', '')).path}
        expected = row['path'] == f'/api/fg-docs/{DOC}/logs' and '503' in row['text']
        report['expected_console_errors' if expected else 'console_errors'].append(row)
    page.on('console', console)
    await page.goto(base + '/node_modules/.s3-fg-compact/fg-compact.html?' + urlencode({'path': path}))
    await page.locator('.shell-main').wait_for()
    return context, page, api


async def check_detail(browser, base, report):
    for width in [1440, 1180]:
        context, page, api = await session(browser, base, report, f'/fg-in/{DOC}?companyId=1', width)
        try:
            await expect(page.locator('.doc-log-list')).to_contain_text('合成审核负责人 reviewer')
            receipt = page.locator('.doc-props dt').filter(has_text=re.compile('^回货单$')).locator('xpath=following-sibling::dd[1]')
            await expect(receipt).to_contain_text('HH202611230003')
            date = receipt.locator('.dim.small')
            no = receipt.locator('.code')
            await page.locator('.doc-log-list').scroll_into_view_if_needed()
            await ready_frame(page)
            state = {'sidebar': await element_metrics(page.locator('.doc-aside')),
                'receipt_no': await element_metrics(no), 'receipt_date': await element_metrics(date),
                'log_table_count': await page.locator('.doc-log-list.el-table').count(),
                'page': await page.evaluate('({viewport:innerWidth,clientWidth:document.documentElement.clientWidth,scrollWidth:document.documentElement.scrollWidth,mainWidth:document.querySelector(".shell-main").clientWidth,mainScroll:document.querySelector(".shell-main").scrollWidth})')}
            compact = page.locator('.doc-log-compact')
            state['compact_count'] = await compact.count()
            if state['compact_count']:
                entry = compact.locator('.log-entry').first
                state['fields'] = {selector: await element_metrics(entry.locator(selector)) for selector in ['.log-time','.log-person','.log-action','.log-status','.log-remark']}
            report['checks'][f'fg_detail_layout_{width}'] = {'passed': False, **state}
            await shot(page, f'fg-detail-{width}.png')
            assert state['receipt_date']['top'] >= state['receipt_no']['bottom'] - .5, state
            assert state['compact_count'] == 1 and state['log_table_count'] == 0, state
            assert all(row['insideViewport'] and row['unobscured'] and row['scrollWidth'] <= row['clientWidth']+1 for row in state['fields'].values()), state
            assert state['page']['scrollWidth'] <= state['page']['clientWidth']+1 and state['page']['mainScroll'] <= state['page']['mainWidth']+1, state
            assert state['fields']['.log-status']['text'] == '草稿 → 已审核'
            assert state['fields']['.log-remark']['text'] == LOGS[0]['remark']
            report['checks'][f'fg_detail_layout_{width}']['passed'] = True
        finally:
            await api.finish()
            await context.close()


async def check_panel(browser, base, report):
    context, page, api = await session(browser, base, report, '/fg-compact-logs', 1180)
    try:
        panel = page.get_by_test_id('log-panel')
        compact = panel.locator('.doc-log-compact')
        await expect(compact.locator('.log-entry')).to_have_count(2)
        await expect(compact.locator('.log-entry').last).to_contain_text('系统')
        metrics = await element_metrics(panel)
        assert abs(metrics['width'] - 300) <= .5
        for selector in ['.log-time','.log-person','.log-action','.log-status','.log-remark']:
            metric = await element_metrics(compact.locator('.log-entry').first.locator(selector))
            assert metric['insideViewport'] and metric['unobscured'] and metric['scrollWidth'] <= metric['clientWidth']+1, metric
        await shot(page, 'standalone-300px.png')
        passed(report, 'compact_300px_all_fields_and_system_actor', panel=metrics, fields=['time','person','action','from_to_status','remark'])
        api.log_error = True
        await page.get_by_role('button', name='重新加载记录', exact=True).click()
        await expect(compact.get_by_role('alert')).to_contain_text('操作记录没加载出来')
        await expect(compact.locator('.log-entry')).to_have_count(0)
        assert '还没有操作记录' not in await compact.inner_text()
        await shot(page, 'compact-log-503-retry.png')
        api.log_error = False
        api.log_hold = {name: asyncio.Event() for name in ['started','release','finished']}
        await compact.get_by_role('button', name='再试一次', exact=True).click()
        await asyncio.wait_for(api.log_hold['started'].wait(), 10)
        await expect(compact.get_by_role('status')).to_contain_text('正在加载操作记录')
        await expect(compact.locator('.log-entry')).to_have_count(0)
        await shot(page, 'compact-log-loading.png')
        api.log_hold['release'].set()
        await asyncio.wait_for(api.log_hold['finished'].wait(), 10)
        api.log_hold = None
        await expect(compact.locator('.log-entry')).to_have_count(2)
        await expect(compact.get_by_role('alert')).to_have_count(0)
        await shot(page, 'compact-log-recovered.png')
        passed(report, 'compact_http_503_loading_retry_restores_all_fields', calls=len([c for c in api.calls if c['path'].endswith('/logs')]))
        api.log_rows = []
        await page.get_by_role('button', name='重新加载记录', exact=True).click()
        await expect(compact).to_contain_text('还没有操作记录')
        await expect(compact.locator('.log-entry')).to_have_count(0)
        passed(report, 'compact_empty_distinct_from_loading_and_failure')
    finally:
        await api.finish()
        await context.close()
    context, page, api = await session(browser, base, report, '/fg-compact-logs?layout=table')
    try:
        table = page.locator('.doc-log-list.el-table')
        await expect(table.locator('.el-table__body-wrapper tbody tr')).to_have_count(2)
        assert await table.locator('.el-table__header-wrapper th').all_text_contents() == ['时间','操作人','操作','状态','备注']
        await expect(page.locator('.doc-log-compact')).to_have_count(0)
        await shot(page, 'default-table-unchanged.png')
        passed(report, 'default_table_five_columns_unchanged')
    finally:
        await api.finish()
        await context.close()


async def run():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT/'web/node_modules/.s3-fg-compact'
    fixture.mkdir(parents=True, exist_ok=True)
    html = Path(__file__).with_name('fg.html').read_text()
    html = html.replace("import Panels from './FgPanels.vue'", "import Panels from './FgPanels.vue'\n      import LogPanel from './FgLogPanel.vue'")
    html = html.replace("{ path: '/fg-panels'", "{ path: '/fg-compact-logs', component: LogPanel, meta: { title: '操作记录' } },\n          { path: '/fg-panels'")
    (fixture/'fg-compact.html').write_text(html)
    for name in ['FgFixture.vue','FgPanels.vue','FgLogPanel.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture/name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    report={'scope':'Actual FG detail, shared DocLogList and AppLayout; synthetic HTTP only, no backend validation',
            'checks':{},'page_errors':[],'console_errors':[],'expected_console_errors':[]}
    with (ARTIFACTS/'vite.log').open('w') as log:
        process=subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',str(port),'--strictPort'],cwd=ROOT/'web',stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None: raise RuntimeError('Vite exited')
                try:
                    _,writer=await asyncio.open_connection('127.0.0.1',port);writer.close();await writer.wait_closed();break
                except OSError: await asyncio.sleep(.1)
            async with async_playwright() as p:
                options={'headless':True}
                if os.environ.get('SCM_UI_CHROMIUM'): options['executable_path']=os.environ['SCM_UI_CHROMIUM']
                browser=await p.chromium.launch(**options)
                try:
                    await check_detail(browser,f'http://127.0.0.1:{port}',report)
                    await check_panel(browser,f'http://127.0.0.1:{port}',report)
                finally: await browser.close()
        except BaseException as error:
            report['failure']=type(error).__name__+': '+str(error)
            raise
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            shutil.rmtree(fixture,ignore_errors=True)
            source={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'web/src').rglob('*')) if p.is_file()}
            report['source_sha256']=hashlib.sha256(json.dumps(source,sort_keys=True).encode()).hexdigest()
            report['screenshots']=sorted(p.name for p in ARTIFACTS.glob('*.png'))
            (ARTIFACTS/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    assert not report['page_errors'] and not report['console_errors'], report
    assert len(report['checks']) == 6 and all(row['passed'] for row in report['checks'].values()), report
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--artifacts',type=Path)
    args=parser.parse_args()
    if args.artifacts:ARTIFACTS=args.artifacts.resolve()
    asyncio.run(run())
