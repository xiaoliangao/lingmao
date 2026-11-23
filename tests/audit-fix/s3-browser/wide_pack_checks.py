"""Actual TechPackTab: 30 sizes, 100 rows, edits, fixed columns, hover and failure."""
import asyncio
import re
from urllib.parse import urlsplit


async def run_wide_pack(browser, base, artifacts, metrics):
    context = await browser.new_context(viewport={'width': 1440, 'height': 1080})
    page = await context.new_page()
    page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
    errors = []
    page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
    page.on('dialog', lambda dialog: asyncio.create_task(dialog.accept()))
    sizes = [f'S{i}' for i in range(30)]
    spec_points = [
        {'id': str(9007199254741993 + row), 'seq': row + 1, 'point': f'部位{row + 1:03d}', 'method': '平量', 'toleranceCm': .5, 'gradeCm': 1, 'remark': None, 'values': {size: 50 + row + col for col, size in enumerate(sizes)}}
        for row in range(100)
    ]
    bom_items = [
        {'id': str(9007199254742993 + row), 'seq': row + 1, 'part': 'BODY', 'materialId': str(910 + row), 'materialCode': f'ML-{row + 1:03d}', 'materialName': f'合成面料{row + 1:03d}', 'materialType': 'FABRIC', 'materialStatus': 1, 'skuMode': 'FIXED', 'usageMode': 'BY_SIZE', 'usageQty': None, 'sizeUsages': {size: round(1 + col / 10, 1) for col, size in enumerate(sizes)}, 'unit': 'M', 'lossPct': 3, 'source': 'PURCHASE', 'supplierId': '13', 'supplierName': '合成供应商', 'remark': None, 'image': None, 'skus': [{'id': str(9007199254743993 + row), 'colorId': None, 'colorName': None, 'sizeCode': None, 'materialSkuId': str(9010 + row), 'skuCode': f'ML-{row + 1:03d}-KQ', 'skuColorName': '卡其', 'skuSpec': '150cm', 'skuStatus': 1}]}
        for row in range(100)
    ]
    bom_items[0].update({'usageMode': 'UNIFORM', 'usageQty': .018, 'lossPct': 0, 'refPrice': 7.5, 'unitCost': .14})
    colors = [{'id': '1', 'code': '01', 'name': '卡其', 'hex': '#c7b69d', 'status': 1}, {'id': '2', 'code': '02', 'name': '黑色', 'hex': '#222222', 'status': 1}]
    detail = {'contextSource': 'LIVE', 'id': '706', 'styleId': '11', 'styleCode': 'KS-WIDE', 'styleName': '合成多尺码风衣', 'aliasName': None, 'stage': 'PROD', 'version': 1, 'status': 'DRAFT', 'versionNote': None, 'sourcePackId': None, 'sourceVersion': None, 'utilPct': 80, 'packingMode': None, 'publishBy': None, 'publishByName': None, 'publishTime': None, 'createByName': '回归工艺员', 'createTime': '2026-11-22 10:00:00', 'updateTime': '2026-11-22 10:00:00', 'versionLock': 3, 'sizes': sizes, 'baseSize': 'S0', 'colors': colors, 'pointOptions': [{'label': row['point'], 'method': '平量'} for row in spec_points], 'specPoints': spec_points, 'bomItems': bom_items, 'craftSections': [], 'processes': [], 'stdSecondsTotal': 0, 'actions': ['edit']}
    detail['processes'] = [{'id': str(920 + index), 'seq': index + 1, 'category': None, 'name': '合成工序' + str(index + 1), 'requirement': '', 'stdSeconds': 10, 'price': price, 'remark': '', 'images': []} for index, price in enumerate([.0001, .0249])]
    detail.update({'laborTotal': .03, 'unitCost': .17, 'stdSecondsTotal': 20})
    cost = {'techPackId': '706', 'baseSize': 'S0', 'colorId': '1', 'costMode': 'OUTSOURCE', 'buyPrice': None, 'buyPriceDefault': None, 'defaultFactoryId': None, 'defaultFactoryName': None, 'fabricRows': [], 'trimRows': [], 'laborTotal': .025, 'processCount': 2, 'otherItems': [], 'summary': {'fabric': .14, 'trim': 0, 'labor': .03, 'other': 0, 'total': .17}, 'tagPrice': None, 'ratio': None, 'mixedBasis': False, 'dev': {'packId': '705', 'version': 1, 'summary': {'fabric': .10, 'trim': 0, 'labor': .05, 'other': 0, 'total': .15}}}
    writes = []
    requests = []
    metrics['wide_pack_requests'] = requests

    async def api(route):
        path = urlsplit(route.request.url).path
        if not path.startswith('/api/'):
            await route.continue_()
            return
        requests.append({'method': route.request.method, 'path': path})
        if path in ['/api/tech-packs/706/cost', '/api/tech-packs/707/cost']:
            data = {**cost, 'techPackId': path.split('/')[3]}
        elif path == '/api/tech-packs/706' and route.request.method == 'PUT':
            body = route.request.post_data_json
            writes.append(body)
            detail['versionLock'] += 1
            detail['specPoints'] = [{**row, 'id': str(9007199254741993 + index), 'seq': index + 1} for index, row in enumerate(body['specPoints'])]
            data = detail['versionLock']
        elif path == '/api/tech-packs/706':
            data = detail
        elif path == '/api/tech-packs/707':
            data = {**detail, 'id': '707', 'version': 2, 'status': 'EFFECTIVE', 'actions': []}
        elif path == '/api/tech-packs/709':
            await route.fulfill(json={'code': 503, 'msg': '合成资料包临时失败', 'data': None})
            return
        elif path in ['/api/tags/objects/TECH_PACK/706', '/api/tags/objects/TECH_PACK/707']:
            assert route.request.method == 'GET'
            assert await route.request.frame.evaluate("async()=>{const{useUserStore}=await import('/src/stores/user.ts');return useUserStore().hasPerm('techpack:pack:list')}")
            data = {'version': 0, 'tags': []}
        elif path.startswith('/api/dict-data/by-type/'):
            data = [{'value': 'BODY', 'label': '大身', 'status': 1}] if path.endswith('/bom_part') else [{'value': 'M', 'label': '米', 'status': 1}] if path.endswith('/unit') else []
        else:
            raise AssertionError(f'unexpected wide-pack API: {path}')
        await route.fulfill(json={'code': 0, 'msg': '', 'data': data})

    async def fixed_alignment(table):
        positions = {}
        for side in ['left', 'right']:
            region = table.locator(f'.el-table-v2__{side}')
            header = await region.locator('.el-table-v2__header-cell').first.bounding_box()
            body = await region.locator('.el-table-v2__row-cell').first.bounding_box()
            assert header and body and abs(header['x'] - body['x']) < 1, (side, header, body)
            positions[side] = {'header_x': header['x'], 'body_x': body['x']}
        return positions

    await context.route('**/api/**', api)
    try:
        await page.goto(base + '/node_modules/.s3-audit/home-review.html?wide-pack=1')
        await page.wait_for_function('Boolean(window.__widePack)')
        bom_list = page.locator('.el-tab-pane:visible .el-table').first
        await bom_list.locator('.el-table__body-wrapper tbody tr').first.wait_for()
        headers = await bom_list.locator('.el-table__header-wrapper th').all_text_contents()
        cost_column = next(index for index, label in enumerate(headers) if label.strip() == '单件成本')
        cost_cell = bom_list.locator('.el-table__body-wrapper tbody tr').first.locator('td').nth(cost_column)
        displayed_cost = (await cost_cell.inner_text()).strip()
        await cost_cell.scroll_into_view_if_needed()
        await page.screenshot(path=str(artifacts / 'pack-bom-decimal.png'))
        assert displayed_cost == '0.14', displayed_cost
        seq_column = next(index for index, label in enumerate(headers) if label.strip() == '#')
        last_seq = bom_list.locator('.el-table__body-wrapper tbody tr').last.locator('td').nth(seq_column).locator('.cell')
        await last_seq.scroll_into_view_if_needed()
        sequence = await last_seq.evaluate("element => { const range = document.createRange(); range.selectNodeContents(element); return { text: element.textContent.trim(), lines: new Set([...range.getClientRects()].map(rect => Math.round(rect.top))).size, width: element.clientWidth, scrollWidth: element.scrollWidth }; }")
        metrics['checks']['pack_bom_three_digit_sequence'] = {'passed': sequence['text'] == '100' and sequence['lines'] == 1 and sequence['scrollWidth'] <= sequence['width'], **sequence}
        await page.screenshot(path=str(artifacts / 'pack-bom-last-row.png'))
        await page.get_by_role('tab', name='工艺指示', exact=True).click()
        labor = page.locator('.proc-total')
        assert '参考加工价 ¥0.03' in await labor.inner_text()
        await labor.scroll_into_view_if_needed()
        await page.screenshot(path=str(artifacts / 'pack-labor-decimal.png'))
        await page.get_by_role('tab', name='核价', exact=True).click()
        difference = page.locator('.cost-total')
        assert '比研发估高 0.02' in await difference.inner_text()
        metrics['checks']['pack_decimal_previews'] = {'passed': True, 'bom_unit_cost': displayed_cost, 'labor_total': '0.03', 'development_difference': '0.02'}
        await page.get_by_role('tab', name='尺寸指示（100）', exact=True).click()
        spec = page.get_by_test_id('wide-spec-table')
        await spec.locator('.el-table-v2__main .el-table-v2__row').first.wait_for()
        rows = await spec.locator('.el-table-v2__main .el-table-v2__row').count()
        assert 1 <= rows < 30, rows
        await spec.get_by_test_id('spec-size-0-S0').locator('input').fill('12.3')
        await spec.get_by_test_id('spec-size-0-S0').locator('input').blur()
        fixed_rows = spec.locator('.el-table-v2__right .el-table-v2__row')
        await fixed_rows.nth(0).get_by_role('button', name='下移', exact=True).click()
        assert await spec.get_by_test_id('spec-size-1-S0').locator('input').input_value() == '12.3'
        await fixed_rows.nth(1).get_by_role('button', name='上移', exact=True).click()
        assert await spec.get_by_test_id('spec-size-0-S0').locator('input').input_value() == '12.3'
        await fixed_rows.nth(2).get_by_role('button', name='删', exact=True).click()
        await page.get_by_role('tab', name='尺寸指示（99）', exact=True).wait_for()
        await page.get_by_role('button', name='保存', exact=True).click()
        await page.get_by_text('资料包已保存', exact=True).wait_for()
        assert len(writes) == 1 and len(writes[0]['specPoints']) == 99, writes
        assert writes[0]['specPoints'][0]['values']['S0'] == 12.3
        assert writes[0]['specPoints'][2]['point'] == '部位004'
        assert all(len(row['values']) == 30 for row in writes[0]['specPoints'])
        metrics['wide_pack_saved'] = {'rows': len(writes[0]['specPoints']), 'first_row': writes[0]['specPoints'][0], 'third_point': writes[0]['specPoints'][2]['point']}
        await spec.get_by_test_id('spec-size-0-S0').locator('input').wait_for()
        assert await spec.get_by_test_id('spec-size-0-S0').locator('input').input_value() == '12.3'
        await page.screenshot(path=str(artifacts / 'wide-pack-spec-edit.png'), full_page=True)
        grid = spec.locator('.el-table-v2__main .el-vl__window')
        if await grid.count() == 0:
            grid = spec.locator('.el-table-v2__main').first
        await grid.hover()
        await page.mouse.wheel(0, 2200)
        await page.wait_for_timeout(200)
        await page.mouse.wheel(10000, 0)
        await page.wait_for_timeout(250)
        header = await spec.locator('.el-table-v2__main .el-table-v2__header-cell').filter(has_text=re.compile('^S29$')).bounding_box()
        cell = await spec.get_by_test_id(re.compile('^spec-size-\\d+-S29$')).first.evaluate("element => { const box = element.closest('.el-table-v2__row-cell').getBoundingClientRect(); return {x:box.x,width:box.width}; }")
        assert header and 200 < header['x'] < 1300 and abs(header['x'] - cell['x']) < 1 and abs(header['width'] - cell['width']) < 1, (header, cell)
        metrics['checks']['wide_spec_edit_reorder_delete_save'] = {'passed': True, 'sizes': 30, 'rows': 100, 'rendered_main_rows': rows, 'fixed_alignment': await fixed_alignment(spec)}
        await page.screenshot(path=str(artifacts / 'wide-pack-spec-scrolled.png'), full_page=True)

        await page.get_by_role('tab', name='物料清单（100）', exact=True).click()
        await page.get_by_text('按颜色', exact=True).click()
        bom = page.get_by_test_id('wide-bom-colors')
        await bom.locator('.el-table-v2__main .el-table-v2__row').first.wait_for()
        rows = await bom.locator('.el-table-v2__main .el-table-v2__row').count()
        assert 1 <= rows < 30, rows
        await bom.get_by_test_id('bom-row').nth(2).hover()
        await page.get_by_test_id('bom-row-card').get_by_text('ML-003 合成面料003', exact=True).wait_for()
        await page.screenshot(path=str(artifacts / 'wide-pack-color-hover.png'), full_page=True)
        grid = bom.locator('.el-table-v2__main .el-vl__window')
        if await grid.count() == 0:
            grid = bom.locator('.el-table-v2__main').first
        await grid.hover()
        await page.mouse.wheel(0, 2200)
        await page.wait_for_timeout(200)
        await page.mouse.wheel(10000, 0)
        await page.wait_for_timeout(250)
        await bom.get_by_test_id('bom-row').nth(4).hover()
        assert '合成面料003' not in await page.get_by_test_id('bom-row-card').inner_text()
        metrics['checks']['wide_color_bom_scroll_hover'] = {'passed': True, 'sizes': 30, 'rows': 100, 'rendered_main_rows': rows, 'fixed_alignment': await fixed_alignment(bom)}
        await page.screenshot(path=str(artifacts / 'wide-pack-color-scrolled.png'), full_page=True)

        await page.evaluate("window.__widePack.open('707', 'spec')")
        await spec.locator('.el-table-v2__main .el-table-v2__row').first.wait_for()
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        assert await spec.locator('input').count() == 0
        assert await spec.get_by_role('button', name='删', exact=True).count() == 0
        metrics['checks']['wide_spec_read_only'] = {'passed': True}
        await page.screenshot(path=str(artifacts / 'wide-pack-spec-readonly.png'), full_page=True)

        await page.evaluate("window.__widePack.open('709', 'spec')")
        await page.get_by_text('合成资料包临时失败', exact=True).first.wait_for()
        await page.get_by_role('button', name='重试', exact=True).wait_for()
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        await page.locator('.el-message').wait_for(state='hidden')
        assert await page.get_by_test_id('wide-spec-table').count() == 0
        metrics['checks']['wide_pack_load_failure'] = {'passed': True}
        await page.screenshot(path=str(artifacts / 'wide-pack-load-error.png'), full_page=True)
        metrics['wide_pack_console_errors'] = errors
        assert not errors, errors
    except Exception:
        await page.screenshot(path=str(artifacts / 'wide-pack-failure.png'), full_page=True)
        raise
    finally:
        await context.close()
