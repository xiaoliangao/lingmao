#!/usr/bin/env python3
"""P61 actual Style/TechPack and TagHistory in AppLayout; strict synthetic HTTP."""
import argparse
import asyncio
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import async_playwright, expect
import samples
spec = importlib.util.spec_from_file_location('techpack_reactivation', Path(__file__).with_name('techpack-reactivation.py'))
reactivation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reactivation)
PackHttp, PERMISSIONS = reactivation.PackHttp, reactivation.PERMISSIONS
from sample_http import PACK, STYLE

ROOT = Path(__file__).resolve().parents[3]
TAG = '9007199254741666'
RENAME = '标签名称「合成公共标签-bccd88a4」→「合成已改名-bccd88a4」'


class VisualHttp(PackHttp):
    def __init__(self):
        super().__init__()
        self.style_pack['actions'] = []
        self.style_pack['sizes'] = ['XS', 'S', 'M', 'L', 'XL']
        self.style_pack['bomItems'] = [{
            'id': '9007199254741777', 'seq': 1, 'part': 'BODY', 'materialId': '51',
            'materialCode': 'ML0001', 'materialName': '220g 精梳纯棉合成面料', 'materialType': 'FABRIC',
            'materialStatus': 1, 'skuMode': 'BY_COLOR', 'usageMode': 'UNIFORM', 'usageQty': 1.5,
            'sizeUsages': {}, 'unit': 'M', 'lossPct': 2.5, 'source': 'PURCHASE', 'supplierId': '31',
            'supplierName': '合成示例面料供应商', 'refPrice': 23.4567, 'unitCost': 36.06,
            'remark': '必须可达的最右备注', 'image': None,
            'skus': [{'id': '9007199254741888', 'colorId': '21', 'colorName': '黑色', 'sizeCode': None,
                      'materialSkuId': '61', 'skuCode': 'ML0001-BK', 'skuColorName': '黑色',
                      'skuSpec': '150cm', 'skuStatus': 1}],
        }]

    async def route(self, route):
        path = urlsplit(route.request.url).path
        if path == f'/api/tags/{TAG}/logs':
            assert route.request.method == 'GET'
            assert parse_qs(urlsplit(route.request.url).query) == {'page': ['1'], 'size': ['20']}
            assert route.request.headers.get('authorization', '').startswith('Bearer ')
            await route.fulfill(json={'code': 0, 'msg': '', 'data': {'total': 2, 'list': [
                {'id': '9007199254741991', 'createTime': '2026-11-23 12:04:00', 'operatorName': '管理员',
                 'action': 'archive', 'remark': '归档标签「合成已改名-bccd88a4」，已有对象关联保留'},
                {'id': '9007199254741992', 'createTime': '2026-11-23 12:04:00', 'operatorName': '管理员',
                 'action': 'rename', 'remark': RENAME},
            ]}})
            return
        if path == '/api/tags/objects/TECH_PACK/' + PACK:
            await route.fulfill(json={'code': 0, 'msg': '', 'data': {'version': 0, 'tags': []}})
            return
        await super().route(route)


async def font_info(page, selector):
    cdp = await page.context.new_cdp_session(page)
    try:
        await cdp.send('DOM.enable'); await cdp.send('CSS.enable')
        root = await cdp.send('DOM.getDocument')
        node = await cdp.send('DOM.querySelector', {'nodeId': root['root']['nodeId'], 'selector': selector})
        return await cdp.send('CSS.getPlatformFontsForNode', {'nodeId': node['nodeId']})
    finally:
        await cdp.detach()


async def geometry(table):
    return await table.evaluate("""table => {
      const wrap = table.querySelector('.el-scrollbar__wrap');
      const rect = table.getBoundingClientRect();
      const heads = [...table.querySelectorAll('.el-table__header-wrapper th')].map(el => el.textContent.trim());
      const row = table.querySelector('.el-table__body-wrapper tbody tr');
      const cells = Object.fromEntries(['物料', '来源', '备注'].map(name => {
        const at=heads.indexOf(name), el=at<0 ? null : row?.children[at], b=el?.getBoundingClientRect();
        const text = el?.querySelector('.cell');
        const range = document.createRange(); if (text) range.selectNodeContents(text);
        const glyphs = [...range.getClientRects()].map(r => ({left:r.left,right:r.right}));
        return [name, b ? {left:b.left,right:b.right,text:el.textContent.trim(),glyphs,
          hit:el.contains(document.elementFromPoint(Math.max(rect.left+1, Math.min(rect.right-1,b.x+b.width/2)),b.y+b.height/2))} : null];
      }));
      const bar = table.querySelector('.el-scrollbar__bar.is-horizontal');
      return {table:{left:rect.left,right:rect.right},width:wrap.clientWidth,scrollWidth:wrap.scrollWidth,
        scrollLeft:wrap.scrollLeft,overflow:getComputedStyle(wrap).overflowX,
        barOpacity:bar ? getComputedStyle(bar).opacity : null,barVisible:!!bar&&getComputedStyle(bar).display!=='none',
        pageOverflow:document.documentElement.scrollWidth>innerWidth,cells};
    }""")


async def check(browser, base, report, width, out):
    context, page, api, console = await samples.start(browser, base, report,
        '/styles/' + STYLE + '?tab=techpack&sub=bom', api=VisualHttp(),
        permissions=PERMISSIONS + ['biz:tag:list'], width=width)
    try:
        table = page.locator('.tp-wrap .el-tab-pane:visible .el-table').first
        await table.locator('.el-table__body-wrapper tbody tr').first.wait_for()
        await page.evaluate('document.fonts.ready')
        await page.mouse.move(30, 30)
        await page.wait_for_timeout(350)
        left = await geometry(table)
        await page.screenshot(path=str(out / f'bom-left-{width}.png'))
        wrap = table.locator('.el-scrollbar__wrap').first
        await wrap.hover(); await page.mouse.wheel(2500, 0); await page.wait_for_timeout(120)
        await page.evaluate('()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))')
        right = await geometry(table)
        await page.screenshot(path=str(out / f'bom-right-{width}.png'))
        source = right['cells']['来源']; remark = right['cells']['备注']
        readable = all(c and c['hit'] and c['left'] >= right['table']['left']-1 and c['right'] <= right['table']['right']+1 for c in [source, remark])
        report['checks'][f'bom_scroll_{width}'] = {'passed': readable and not right['pageOverflow'], 'left':left, 'right':right}
        report['checks'][f'bom_discoverable_{width}'] = {'passed':left['barVisible'] and float(left['barOpacity']) > 0, 'opacity':left['barOpacity']}
        pinned = right['cells']['物料']
        report['checks'][f'bom_pinned_{width}'] = {'passed':pinned['left'] == left['cells']['物料']['left'] and source['left'] == left['cells']['来源']['left'] and pinned['right'] < source['left'], 'material':pinned, 'source':source}
        await page.get_by_test_id('p61-history').click()
        dialog = page.get_by_role('dialog', name='标签记录 · #合成已改名-bccd88a4', exact=True)
        await expect(dialog.get_by_text(RENAME, exact=True)).to_be_visible()
        selector = '.el-dialog .el-table__body-wrapper tbody tr:nth-child(2) td:last-child .cell'
        before = await font_info(page, selector)
        await page.evaluate('document.fonts.ready')
        await page.wait_for_timeout(300)
        after = await font_info(page, selector)
        font = await page.locator(selector).evaluate('el=>({family:getComputedStyle(el).fontFamily,text:el.textContent,width:el.clientWidth,scrollWidth:el.scrollWidth,fontsStatus:document.fonts.status})')
        await page.screenshot(path=str(out / f'tag-fonts-ready-{width}.png'))
        report['checks'][f'tag_text_{width}'] = {'passed':font['text']==RENAME and font['scrollWidth']<=font['width'] and all('CJK' in f['familyName'] for f in after['fonts']),'computed':font,'before_ready':before,'after_ready':after}
        # Diagnostic only: compare explicit local CJK font without changing production source.
        await page.locator(selector).evaluate("el=>el.style.fontFamily='Noto Sans CJK SC, sans-serif'")
        report['font_diagnostic'] = await font_info(page, selector)
        await page.screenshot(path=str(out / f'tag-font-diagnostic-{width}.png'))
        await dialog.get_by_role('button',name='关闭',exact=True).click()
        await page.get_by_text('按颜色',exact=True).click()
        color=page.locator('.tp-wrap .el-tab-pane:visible .el-table').first
        await color.locator('.el-table__body-wrapper tbody tr').first.wait_for()
        color_left=await geometry(color)
        await color.locator('.el-scrollbar__wrap').first.hover(); await page.mouse.wheel(2500,0); await page.wait_for_timeout(120)
        color_right=await geometry(color)
        material=color_right['cells']['物料']; source=color_right['cells']['来源']
        await page.screenshot(path=str(out/f'bom-colors-right-{width}.png'))
        report['checks'][f'color_pinned_{width}']={'passed':material['left']==color_left['cells']['物料']['left'] and source['right']<=color_right['table']['right']+1 and material['right']<source['left'] and source['hit'] and not color_right['pageOverflow'],'left':color_left,'right':color_right}
    finally:
        await samples.finish(context, page, api, console, report)


async def run(label):
    out = ROOT / 'test-artifacts/full-audit/s3/sample-visual' / label
    out.mkdir(parents=True, exist_ok=False); samples.ARTIFACTS = out
    fixture = ROOT / 'web/node_modules/.s3-samples'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['SamplesFixture.vue','P61Fixture.vue']: shutil.copyfile(Path(__file__).with_name(name), fixture/name)
    html = Path(__file__).with_name('samples.html').read_text().replace("import Fixture from './SamplesFixture.vue'", "import Fixture from './P61Fixture.vue'")
    # Match production's global table clipping/tooltip configuration.
    html = html.replace("{locale:zhCn}","{locale:zhCn,table:{showOverflowTooltip:true,tooltipOptions:{popperClass:'table-tip'}}}")
    (fixture/'samples.html').write_text(html)
    with socket.socket() as sock: sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    report={'scope':'P61 actual Vue components, synthetic HTTP, no backend claim','checks':{},'http_checks':[],
            'page_errors':[],'console_errors':[],'expected_console_errors':[]}
    with (out/'vite.log').open('w') as log:
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
                    for width in [1440,1180]: await check(browser,f'http://127.0.0.1:{port}',report,width,out)
                finally: await browser.close()
        except BaseException as error:
            report['failure']=type(error).__name__+': '+str(error)
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            shutil.rmtree(fixture,ignore_errors=True)
            source={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'web/src').rglob('*')) if p.is_file()}
            report['source_sha256']=hashlib.sha256(json.dumps(source,sort_keys=True).encode()).hexdigest()
            report['screenshots']=sorted(p.name for p in out.glob('*.png'))
            (out/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return not report.get('failure') and not report['page_errors'] and not report['console_errors'] and all(c['passed'] for c in report['checks'].values())


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--label',required=True);args=parser.parse_args()
    raise SystemExit(0 if asyncio.run(run(args.label)) else 1)
