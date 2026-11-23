#!/usr/bin/env python3
"""Measure actual Vue row/cell updates and cell VNode identity; not arithmetic-only tests."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[3]


async def sticky_checks(page, base, report, artifacts):
    for sizes, viewport in [(51, 1440), (51, 1180), (63, 1440), (63, 1180)]:
        await page.set_viewport_size({'width': viewport, 'height': 900})
        await page.goto(f'{base}/node_modules/.s3-matrix-cells/matrix-cells.html?wide={sizes}')
        await expect(page.locator('.qty-input')).to_have_count(sizes * 2)
        await page.get_by_role('textbox', name='颜色1 S1 件数', exact=True).fill('99999999')
        matrix = page.locator('.sku-matrix')
        measurements = []
        for segment in ['start', 'middle', 'end']:
            await matrix.evaluate("""(element, segment) => { element.scrollLeft = segment === 'start' ? 0 : (element.scrollWidth - element.clientWidth) * (segment === 'middle' ? .5 : 1) }""", segment)
            await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
            target = await matrix.evaluate("""element => {
                const bounds = element.getBoundingClientRect(), row = element.querySelector('tbody tr');
                const left = Math.max(bounds.left, row.firstElementChild.getBoundingClientRect().right), right = Math.min(bounds.left + element.clientWidth, row.lastElementChild.getBoundingClientRect().left);
                return [...row.querySelectorAll('input')].findIndex(input => { const rect = input.getBoundingClientRect(); return rect.left >= left + 1 && rect.right <= right - 1 });
            }""")
            assert target >= 0, {'sizes': sizes, 'viewport': viewport, 'segment': segment}
            field = matrix.locator('tbody tr').first.locator('input').nth(target)
            await field.fill('99999999'); await field.blur()
            await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
            measure = await matrix.evaluate("""(element, target) => {
                const bounds=element.getBoundingClientRect(), row=element.querySelector('tbody tr'), input=row.querySelectorAll('input')[target];
                const rect = input.getBoundingClientRect(), style=getComputedStyle(input), canvas=document.createElement('canvas'), ctx=canvas.getContext('2d'); ctx.font=style.font;
                const metric=cell=>{const r=cell.getBoundingClientRect(),s=getComputedStyle(cell),top=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {left:r.left,right:r.right,width:r.width,background:s.backgroundColor,position:s.position,text:cell.textContent.trim(),unobscured:!!top&&cell.contains(top)}};
                const first=[...element.querySelectorAll('thead tr > :first-child,tbody tr > :first-child,tfoot tr > :first-child')].map(metric);
                const last=[...element.querySelectorAll('thead tr > :last-child,tbody tr > :last-child,tfoot tr > :last-child')].map(metric);
                return {container:{left:bounds.left,right:bounds.left+element.clientWidth,width:element.clientWidth,scrollLeft:element.scrollLeft,scrollWidth:element.scrollWidth},first,last,
                    footerMiddle:[...element.querySelectorAll('tfoot td')].slice(1,-1).map(cell=>({left:cell.getBoundingClientRect().left,position:getComputedStyle(cell).position})),
                    input:{name:input.getAttribute('aria-label'),value:input.value,left:rect.left,right:rect.right,available:input.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight),textWidth:ctx.measureText(input.value).width,scrollWidth:input.scrollWidth,clientWidth:input.clientWidth,scrollLeft:input.scrollLeft,unobscured:document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2)===input}};
            }""", target)
            measurements.append({'segment': segment, **measure})
            if segment != 'start': await page.screenshot(path=str(artifacts / f'matrix-{sizes}-sizes-{viewport}-{segment}.png'), animations='disabled')
        expected_left = measurements[0]['first'][0]['left']
        expected_right = measurements[0]['container']['right']
        frozen = all(abs(cell['left']-expected_left) <= 1 and cell['position']=='sticky' and cell['unobscured'] for m in measurements for cell in m['first'])
        frozen = frozen and all(abs(cell['right']-expected_right) <= 1 and cell['position']=='sticky' and cell['unobscured'] for m in measurements for cell in m['last'])
        opaque = all(cell['background'] not in ['rgba(0, 0, 0, 0)', 'transparent'] for m in measurements for cell in m['first']+m['last'])
        no_overlay = all(m['input']['value']=='99999999' and m['input']['textWidth']<=m['input']['available'] and m['input']['scrollWidth']<=m['input']['clientWidth'] and m['input']['scrollLeft']==0 and m['input']['unobscured'] and m['input']['left']>=m['first'][2]['right'] and m['input']['right']<=m['last'][2]['left'] for m in measurements)
        scrolling_totals = all(cell['position']!='sticky' for m in measurements for cell in m['footerMiddle']) and measurements[-1]['footerMiddle'][0]['left']<measurements[0]['footerMiddle'][0]['left']-100
        report['checks'][f'sticky_edges_{sizes}_sizes_{viewport}'] = {'passed': frozen and opaque and no_overlay and scrolling_totals,
            'sizes': sizes, 'table_columns': sizes+2, 'viewport': viewport, 'sidebar': 240, 'frozen_edges': frozen, 'opaque_backgrounds': opaque,
            'eight_digits_unobscured': no_overlay, 'only_final_footer_total_sticky': scrolling_totals, 'measurements': measurements}


async def topology_checks(page, base, report, artifacts):
    await page.set_viewport_size({'width':1440,'height':900})
    await page.goto(f'{base}/node_modules/.s3-matrix-cells/matrix-cells.html?small=true')
    await expect(page.locator('.qty-input')).to_have_count(4)
    async def totals():
        await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
        return await page.locator('.sku-matrix').evaluate("""element => ({grand:element.querySelector('tfoot .grand').textContent.trim(),rows:[...element.querySelectorAll('tbody .total-col')].map(e=>e.textContent.trim()),columns:[...element.querySelectorAll('tfoot .total-col')].slice(0,-1).map(e=>e.textContent.trim()),sizes:[...element.querySelectorAll('thead tr:first-child th')].slice(1,-1).map(e=>e.textContent.trim()),inputs:[...element.querySelectorAll('tbody input')].map(e=>({label:e.getAttribute('aria-label'),value:e.value}))})""")
    await page.get_by_role('button', name='下次输入同时换码序与映射', exact=True).click()
    await page.get_by_role('textbox', name='颜色1 S1 件数', exact=True).fill('10')
    first = await totals()
    await page.screenshot(path=str(artifacts/'matrix-same-tick-topology.png'),animations='disabled')
    report['checks']['same_tick_model_cell_mapping_and_size_order']={'passed':first['grand']=='10' and first['rows']==['10','0'] and first['columns']==['10','0'] and first['sizes']==['S2','S1'] and first['inputs'][0]['value']=='10','actual':first}
    await page.get_by_role('button', name='替换完整来源并预填17', exact=True).click()
    replaced = await totals()
    report['checks']['replaced_source_prefill_totals']={'passed':replaced['grand']=='17' and replaced['rows']==['17','0'] and replaced['columns']==['17','0'] and replaced['sizes']==['S1','S2'],'actual':replaced}
    await page.get_by_role('button', name='清空数量并换顺序', exact=True).click()
    cleared=await totals()
    report['checks']['clear_and_reorder_all_totals']={'passed':cleared['grand']=='0' and cleared['rows']==['0','0'] and cleared['columns']==['0','0'] and all(not i['value'] for i in cleared['inputs']),'actual':cleared}
    await page.get_by_role('textbox',name='颜色1 S2 件数',exact=True).fill('10')
    edited=await totals()
    await page.screenshot(path=str(artifacts/'matrix-reset-then-input.png'),animations='disabled')
    report['checks']['input_after_reset_all_totals_agree']={'passed':edited['grand']=='10' and edited['rows']==['10','0'] and edited['columns']==['10','0'],'actual':edited}


async def run(baseline, sticky_only=False, artifacts=None, topology_only=False):
    artifacts = artifacts or ROOT / 'test-artifacts/full-audit/s3' / ('matrix-topology' if topology_only else 'matrix-sticky' if sticky_only else 'matrix-cells') / ('before' if baseline else 'after')
    artifacts.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-matrix-cells'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['matrix-cells.html', 'MatrixCellsFixture.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'phase': 'before' if baseline else 'after', 'scope': 'Actual SkuMatrix 50 colors x 20 sizes and 2 x 2; Vue beforeUpdate/updated hooks and real cell VNodes',
              'checks': {}, 'page_errors': [], 'console_errors': []}
    with (artifacts / 'vite.log').open('w') as log:
        process = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(port), '--strictPort'],
                                   cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
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
                    page = await browser.new_page(viewport={'width': 1440, 'height': 900})
                    page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
                    page.on('console', lambda message: report['console_errors'].append(message.text) if message.type == 'error' else None)
                    if not sticky_only and not topology_only:
                        await page.goto(f'http://127.0.0.1:{port}/node_modules/.s3-matrix-cells/matrix-cells.html')
                        await expect(page.locator('.qty-input')).to_have_count(1000)
                        await page.evaluate('window.__matrixProbe={updates:{},cellIds:[],recreatedCellVNodes:0}')
                        first = page.get_by_role('textbox', name='颜色1 S1 件数', exact=True)
                        await first.fill('1234567')
                        await expect(page.get_by_test_id('grand-total')).to_have_text('1234567')
                        await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
                        counts = await page.evaluate('window.__matrixProbe')
                        await expect(page.locator('tbody tr').first.locator('.total-col')).to_have_text('1234567')
                        await expect(page.locator('tfoot td').nth(1)).to_have_text('1234567')
                        await expect(page.locator('tfoot .grand')).to_have_text('1234567')
                        await expect(page.get_by_role('textbox', name='颜色1 S2 件数', exact=True)).to_have_value('')
                        report['checks']['single_cell_render_isolation'] = {'passed': counts['updates'].get('SkuMatrixCell') == 1 and counts['updates'].get('SkuMatrixRow') == 1 and counts['recreatedCellVNodes'] == 1,
                            'actual_updates': counts, 'total_cells': 1000, 'row_cells': 20, 'target_id': '9007199254740993', 'row_column_grand_totals': 1234567}
                        width = await first.evaluate("""input => {const s=getComputedStyle(input),canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');ctx.font=s.font;return {text:input.value,available:input.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight),textWidth:ctx.measureText(input.value).width,scrollWidth:input.scrollWidth,clientWidth:input.clientWidth,scrollLeft:input.scrollLeft}}""")
                        assert width['textWidth'] <= width['available'] and width['scrollWidth'] <= width['clientWidth'] and width['scrollLeft'] == 0, width
                        await page.locator('.matrix-scroll').screenshot(path=str(artifacts / 'matrix-single-cell.png'), animations='disabled')
                        report['quantity_width'] = width
                        if not baseline:
                            await page.evaluate('window.__matrixProbe={updates:{},cellIds:[],recreatedCellVNodes:0}')
                            await page.get_by_role('textbox', name='颜色2 S3 件数', exact=True).fill('7')
                            await expect(page.get_by_test_id('grand-total')).to_have_text('1234574')
                            await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
                            second = await page.evaluate('window.__matrixProbe')
                            assert second['updates'].get('SkuMatrixCell') == 1 and second['updates'].get('SkuMatrixRow') == 1 and second['recreatedCellVNodes'] == 1, second
                            assert second['cellIds'] == ['9007199254741015'], second
                            report['checks']['another_row_only_target_cell'] = {'passed': True, 'actual_updates': second, 'grand_total': 1234574}
                            for invalid in ['1.5', '-1', 'abcdefgh', '12345678']:
                                await first.fill(invalid); await expect(first).to_have_value('1234567')
                            await page.get_by_role('button', name='上限改为100', exact=True).click()
                            await expect(first.locator('..')).to_have_attribute('title', '最多 100 件')
                            await page.get_by_role('button', name='业务错误', exact=True).click()
                            await expect(first.locator('..')).to_have_attribute('title', '合成业务校验失败')
                            await page.get_by_role('button', name='增加格子说明', exact=True).click()
                            await expect(first.locator('..').locator('.sub')).to_have_text('库存 100')
                            await page.get_by_role('button', name='停用第二SKU', exact=True).click()
                            await expect(page.get_by_role('textbox', name='颜色1 S2 件数', exact=True)).to_have_count(0)
                            await expect(page.locator('tbody tr').first.locator('td').nth(2)).to_have_attribute('title', '这个 SKU 已停用')
                            await page.get_by_role('button', name='停用第二颜色', exact=True).click()
                            await expect(page.locator('tbody tr').nth(1).locator('input')).to_have_count(0)
                            await expect(page.locator('tbody tr').nth(1).locator('.total-col')).to_have_text('7')
                            await page.get_by_role('button', name='切换只读', exact=True).click()
                            await expect(page.locator('.qty-input')).to_have_count(0)
                            await expect(page.locator('.qty-text').first).to_have_text('1234567')
                            await expect(page.locator('.custom-prepend td')).to_have_count(22)
                            await page.locator('.matrix-scroll').screenshot(path=str(artifacts / 'matrix-readonly-errors-stopped.png'), animations='disabled')
                            await page.get_by_role('button', name='切换只读', exact=True).click()
                            await expect(first).to_have_value('1234567')
                            report['checks']['existing_input_readonly_constraints_and_slot'] = {'passed': True, 'rejected_inputs': ['1.5', '-1', 'abcdefgh', '12345678'], 'readonly_preserved': True, 'stopped_row_qty': 7, 'prepend_columns': 22}
                            await page.goto(f'http://127.0.0.1:{port}/node_modules/.s3-matrix-cells/matrix-cells.html?small=true')
                            await expect(page.locator('.qty-input')).to_have_count(4)
                            await page.evaluate('window.__matrixProbe={updates:{},cellIds:[],recreatedCellVNodes:0}')
                            eight = page.get_by_role('textbox', name='颜色1 S1 件数', exact=True)
                            await eight.fill('99999999')
                            await expect(page.get_by_test_id('grand-total')).to_have_text('99999999')
                            await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
                            small_counts = await page.evaluate('window.__matrixProbe')
                            assert small_counts['updates'].get('SkuMatrixCell') == 1 and small_counts['updates'].get('SkuMatrixRow') == 1 and small_counts['recreatedCellVNodes'] == 1, small_counts
                            assert small_counts['cellIds'] == ['9007199254740993'], small_counts
                            for name in ['颜色1 S2 件数', '颜色2 S1 件数', '颜色2 S2 件数']:
                                await expect(page.get_by_role('textbox', name=name, exact=True)).to_have_value('')
                            measurements = []
                            for focused in [True, False]:
                                if not focused: await eight.blur()
                                measure = await eight.evaluate("""input => {const s=getComputedStyle(input),canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');ctx.font=s.font;return {text:input.value,available:input.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight),textWidth:ctx.measureText(input.value).width,scrollWidth:input.scrollWidth,clientWidth:input.clientWidth,scrollLeft:input.scrollLeft}}""")
                                assert measure['text'] == '99999999' and measure['textWidth'] <= measure['available'] and measure['scrollWidth'] <= measure['clientWidth'] and measure['scrollLeft'] == 0, measure
                                measurements.append({'focused': focused, **measure})
                            await eight.fill('100000000')
                            await expect(eight).to_have_value('99999999')
                            await expect(page.locator('tbody tr').first.locator('.total-col')).to_have_text('99999999')
                            await expect(page.locator('tfoot td').nth(1)).to_have_text('99999999')
                            await expect(page.locator('tfoot .grand')).to_have_text('99999999')
                            await page.locator('.matrix-scroll').screenshot(path=str(artifacts / 'matrix-eight-digits-2x2.png'), animations='disabled')
                            report['checks']['eight_digits_2x2_other_cells_unchanged'] = {'passed': True, 'actual_updates': small_counts, 'other_cell_updates': 0, 'accepted': 99999999, 'rejected': 100000000, 'width': measurements}
                    if sticky_only or (not baseline and not topology_only):
                        await sticky_checks(page, f'http://127.0.0.1:{port}', report, artifacts)
                    if topology_only or not baseline:
                        await topology_checks(page, f'http://127.0.0.1:{port}', report, artifacts)
                finally: await browser.close()
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            report['source_files'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'web/src/components/SkuMatrix.vue', ROOT/'web/src/components/SkuMatrixRow.vue', ROOT/'web/src/components/SkuMatrixCell.vue'] if p.exists()}
            (artifacts / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert not report['page_errors'] and not report['console_errors'], report
    assert all(row['passed'] for row in report['checks'].values()), report['checks']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--baseline', action='store_true'); parser.add_argument('--sticky-only', action='store_true')
    parser.add_argument('--artifacts', type=Path); parser.add_argument('--topology-only', action='store_true')
    args = parser.parse_args()
    asyncio.run(run(args.baseline, args.sticky_only, args.artifacts.resolve() if args.artifacts else None, args.topology_only))
