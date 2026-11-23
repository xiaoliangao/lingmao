#!/usr/bin/env python3
"""Actual inventory detail and AppLayout; explicitly synthetic read-only HTTP."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
from urllib.parse import parse_qs, urlencode, urlsplit

from playwright.async_api import async_playwright, expect
from fg import production, ORDER

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/inv-compact'
ID = '9007199254748001'
BASE = '/api/inventory-docs/' + ID
ONLY = None
LOGS = [
    {'id': '9007199254749101', 'action': 'audit', 'fromStatus': 'DRAFT', 'toStatus': 'APPROVED',
     'operatorId': '9007199254740994', 'operatorName': '合成库存审核负责人 reviewer',
     'createTime': '2026-11-23 08:32:04', 'remark': '核对批次和收货数量后审核。\n批次凭证：BATCH-' + '0123456789' * 6},
    {'id': '9007199254749102', 'action': 'create', 'fromStatus': None, 'toStatus': 'DRAFT',
     'operatorId': None, 'operatorName': None, 'createTime': '2026-11-23 08:30:01', 'remark': None},
]
HEAD = {'id': ID, 'no': 'RK-SYN-COMPACT', 'version': 7, 'status': 'APPROVED', 'companyId': '1', 'companyName': '合成主体甲',
        'kind': 'IN', 'docType': 'OTHER_IN', 'docDate': '2026-11-23', 'reason': 'INITIAL', 'source': None,
        'fromWarehouseId': None, 'toWarehouseId': '31', 'toWarehouseName': '合成物料仓', 'toWarehouseType': 'MATERIAL',
        'totalQty': 0, 'posted': True, 'items': [], 'actions': [], 'createBy': '9007199254740993',
        'createByName': '合成制单员', 'createTime': '2026-11-23 08:30:01', 'remark': None}
COUNT = {**HEAD, 'no': 'PD-SYN-COMPACT', 'warehouseId': '31', 'warehouseName': '合成物料仓', 'warehouseType': 'MATERIAL',
         'purpose': 'NORMAL', 'scope': 'ALL', 'scopeText': '全仓', 'categories': [], 'countDate': '2026-11-23',
         'counterId': '9007199254740993', 'counterName': '合成盘点员', 'bookTime': '2026-11-23 08:00:00',
         'itemCount': 0, 'filledCount': 0, 'gainCount': 0, 'lossCount': 0, 'movesSinceBook': 0}
PRODUCTION = production()
PRODUCTION.update(status='APPROVED', actions=[], receiptCount=0, stockDocCount=0)
KINDS = {
    'inventory': {'base': BASE, 'route': '/stock-in/'+ID, 'tag': 'INV_DOC/'+ID, 'head': HEAD, 'width': 300},
    'count': {'base': '/api/inventory-counts/'+ID, 'route': '/stock-takes/'+ID, 'tag': 'INV_COUNT/'+ID, 'head': COUNT, 'width': 310},
    'production': {'base': '/api/production-orders/51', 'route': '/production/51', 'tag': 'PRODUCTION_ORDER/51', 'head': PRODUCTION, 'width': 300},
}


async def frame(page):
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')


async def capture(page, name):
    await frame(page)
    await page.screenshot(path=str(ARTIFACTS / name), animations='disabled')


class Api:
    def __init__(self, kind, failure=False):
        self.failure, self.errors, self.calls = failure, [], []
        self.kind, self.config = kind, KINDS[kind]
    async def route(self, route):
        request = route.request; url = urlsplit(request.url); path = url.path
        if not path.startswith('/api/'):
            await route.continue_(); return
        try:
            assert request.method == 'GET', request.method
            self.calls.append({'path': path, 'company': request.headers.get('x-company-id')})
            base = self.config['base']
            if path == base: data = self.config['head']
            elif path == base + '/attachments': data = []
            elif path == base + '/logs':
                assert request.headers.get('x-company-id') == '1'
                if self.failure:
                    await route.fulfill(status=503, json={'code':503,'msg':'合成库存日志故障','data':None},headers={'X-Request-Id':'synthetic-inv-log-fault'});return
                data = LOGS
            elif path == '/api/tags/objects/' + self.config['tag']:
                # Same test supports the independently frozen tags candidate.
                assert request.headers.get('x-company-id') == '1' and not parse_qs(url.query)
                data = {'version':0,'tags':[]}
            elif path == '/api/version': data = {'version':'synthetic-inv-compact','buildTime':None}
            elif path in ['/api/dict-data/by-type/unit','/api/dict-data/by-type/stock_in_reason','/api/dict-data/by-type/stock_out_reason','/api/dict-data/by-type/season',
                          '/api/dict-data/by-type/process_mode','/api/dict-data/by-type/settle_method','/api/dict-data/by-type/issue_type','/api/dict-data/by-type/prod_terms']: data=[]
            elif self.kind == 'production' and path == base+'/materials': data={'rows':[],'docs':[]}
            elif self.kind == 'production' and path in [base+'/issues',base+'/receipts']: data=[]
            elif self.kind == 'production' and path == '/api/order-kitting':
                assert parse_qs(url.query).get('orderIds') == [ORDER], url.query
                data=[{'orderId':ORDER,'status':'NONE','calcs':[]}]
            else: raise AssertionError('Unknown read: ' + path)
            await route.fulfill(json={'code':0,'msg':'','data':data})
        except BaseException as error:
            self.errors.append(type(error).__name__ + ': ' + str(error));raise


async def session(browser, base, report, kind, width, failure=False):
    context=await browser.new_context(viewport={'width':width,'height':1100});api=Api(kind, failure)
    await context.route('**/api/**',api.route);page=await context.new_page();page.set_default_timeout(12000)
    page.on('pageerror',lambda error:report['page_errors'].append(str(error)))
    def console(message):
        if message.type != 'error': return
        row={'text':message.text,'path':urlsplit(message.location.get('url','')).path}
        expected=row['path']==KINDS[kind]['base']+'/logs' and 'status of 503' in row['text']
        report['expected_console_errors' if expected else 'console_errors'].append(row)
    page.on('console',console)
    await page.goto(base+'/node_modules/.s3-inv-compact/inv-compact.html?'+urlencode({'path':KINDS[kind]['route']}))
    panel=page.locator('.doc-aside > .panel').filter(has=page.locator('.panel-head').filter(has_text='操作记录'))
    await expect(panel).to_be_visible();return context,page,api,panel


async def inspect(panel):
    return await panel.evaluate('''el=>{
      const box=el.getBoundingClientRect();const compact=el.querySelector('.doc-log-compact');
      const rect=node=>{const r=node.getBoundingClientRect();const range=document.createRange();range.selectNodeContents(node);
        const hit=document.elementFromPoint(r.left+r.width/2,r.top+Math.min(r.height/2,8));
        return {text:node.textContent.trim(),left:r.left,right:r.right,top:r.top,bottom:r.bottom,unobscured:!!hit&&node.contains(hit),
          textRects:[...range.getClientRects()].filter(x=>x.width&&x.height).map(x=>({left:x.left,right:x.right,top:x.top,bottom:x.bottom}))};};
      const nodes=compact?[...compact.querySelectorAll('.log-entry:first-child time,.log-entry:first-child .log-person,.log-entry:first-child .log-action,.log-entry:first-child .log-status,.log-entry:first-child .log-remark')]:[...el.querySelectorAll('tbody tr:first-child td .cell')];
      return {viewport:innerWidth,documentWidth:document.documentElement.scrollWidth,panel:{left:box.left,right:box.right,width:box.width},compact:!!compact,fields:nodes.map(rect)};
    }''')


async def verify(browser,base,report,kind):
    for width in [1440,1180]:
        context,page,api,panel=await session(browser,base,report,kind,width)
        try:
            await expect(panel).to_contain_text('核对批次和收货数量后审核')
            await panel.scroll_into_view_if_needed();await frame(page)
            metrics=await inspect(panel);report.setdefault('measurements',{})[kind+'-'+str(width)]=metrics
            await capture(page,kind+'-log-'+str(width)+'.png')
            assert metrics['compact'],metrics
            assert metrics['documentWidth']<=width+1 and metrics['panel']['right']<=width+1,metrics
            if width==1440: assert metrics['panel']['width']==KINDS[kind]['width'],metrics
            assert len(metrics['fields'])==5,metrics
            for field in metrics['fields']:
                assert field['unobscured'] and 0<=field['top']<field['bottom']<=1100,field
                assert all(metrics['panel']['left']<=r['left']<r['right']<=metrics['panel']['right'] for r in field['textRects']),field
            await expect(panel.locator('.log-action').first).to_contain_text('审核');await expect(panel).to_contain_text('草稿 → ' + ('已下单' if kind == 'production' else '已审核'));await expect(panel).to_contain_text('系统')
            report['checks'][kind+'_actual_detail_'+str(width)]={'passed':True,'metrics':metrics}
        finally:
            await context.close();assert not api.errors,api.errors
    context,page,api,panel=await session(browser,base,report,kind,1440,True)
    try:
        await expect(panel.get_by_role('alert')).to_contain_text('操作记录没加载出来')
        await expect(panel).not_to_contain_text('还没有操作记录')
        await panel.scroll_into_view_if_needed();await capture(page,kind+'-log-503.png')
        api.failure=False;await panel.get_by_role('button',name='再试一次',exact=True).click()
        await expect(panel).to_contain_text('核对批次和收货数量后审核')
        await expect(panel.get_by_role('alert')).to_have_count(0)
        await panel.scroll_into_view_if_needed();await capture(page,kind+'-log-recovered.png')
        report['checks'][kind+'_log_failure_retry']={'passed':True,'log_requests':len([c for c in api.calls if c['path']==KINDS[kind]['base']+'/logs'])}
    finally:
        await context.close();assert not api.errors,api.errors


async def run():
    ARTIFACTS.mkdir(parents=True,exist_ok=True);fixture=ROOT/'web/node_modules/.s3-inv-compact';fixture.mkdir(parents=True,exist_ok=True)
    for name in ['inv-compact.html','InvLogFixture.vue']:shutil.copyfile(Path(__file__).with_name(name),fixture/name)
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    report={'scope':'Actual inventory detail/AppLayout, synthetic HTTP only','checks':{},'page_errors':[],'console_errors':[],'expected_console_errors':[]}
    with (ARTIFACTS/'vite.log').open('w') as log:
        server=subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',str(port),'--strictPort'],cwd=ROOT/'web',stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if server.poll() is not None:raise RuntimeError('Vite exited')
                try:_,writer=await asyncio.open_connection('127.0.0.1',port);writer.close();await writer.wait_closed();break
                except OSError:await asyncio.sleep(.1)
            async with async_playwright() as p:
                options={'headless':True}
                if os.environ.get('SCM_UI_CHROMIUM'):options['executable_path']=os.environ['SCM_UI_CHROMIUM']
                browser=await p.chromium.launch(**options)
                try:
                    for kind in KINDS:
                        if ONLY and kind != ONLY: continue
                        await verify(browser,f'http://127.0.0.1:{port}',report,kind)
                finally:await browser.close()
        except BaseException as error:
            report['failure']=type(error).__name__+': '+str(error);raise
        finally:
            server.terminate();server.wait(timeout=10);shutil.rmtree(fixture,ignore_errors=True)
            files={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'web/src').rglob('*')) if p.is_file()}
            report['source_sha256']=hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()
            report['screenshots']=sorted(p.name for p in ARTIFACTS.glob('*.png'))
            (ARTIFACTS/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    assert len(report['checks'])==(3 if ONLY else 9) and not report['page_errors'] and not report['console_errors'],report
    print(json.dumps({'checks':len(report['checks']),'page_errors':0,'console_errors':0,'source_sha256':report['source_sha256']}))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--artifacts',type=Path);parser.add_argument('--only',choices=KINDS);args=parser.parse_args()
    if args.artifacts:ARTIFACTS=args.artifacts.resolve()
    ONLY=args.only
    asyncio.run(run())
