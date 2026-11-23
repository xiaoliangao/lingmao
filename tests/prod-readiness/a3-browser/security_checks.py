"""Security integration exercises production forms and Axios with synthetic, omitted-field responses."""
import asyncio
import re
from urllib.parse import parse_qs, urlsplit
from playwright.async_api import expect


async def run_security(browser, url, artifacts, observe_context, metrics):
    context = await browser.new_context(viewport={'width': 1440, 'height': 1080})
    observe_context(context)
    state = {'perms': []}
    purchase_state = {'version': 3, 'company': '1', 'item': '9007199254740999'}
    requests, writes, unknown = [], [], []
    supplier_option_reads = []
    metrics['security_requests'] = requests
    metrics['security_writes'] = writes
    cost, order_price, prod_price = 'techpack:cost:query', 'order:price:query', 'production:price:query'
    companies = [{'id': '1', 'code': 'A', 'shortName': '测试主体 A', 'name': '测试主体 A'}, {'id': '2', 'code': 'B', 'shortName': '测试主体 B', 'name': '测试主体 B'}]
    style = {'styleId': '11', 'code': 'KS-SYNTHETIC', 'name': '合成测试款式', 'aliasName': None, 'image': None, 'status': 'ACTIVE', 'techPackStatus': 'ACTIVE', 'currentPackId': '51', 'currentPackVersion': 1, 'sizes': ['M'], 'colors': [{'id': '1', 'code': '01', 'name': '本白', 'hex': '#eeeeee', 'status': 1, 'orderable': True, 'reason': None}], 'skus': [{'id': '101', 'colorId': '1', 'sizeCode': 'M', 'code': 'KS-SYNTHETIC-01-M', 'status': 1}], 'tagPrice': 88, 'problem': None}
    dicts = {'order_type': [('STOCK', '自主备货')], 'currency': [('CNY', '人民币')], 'process_mode': [('CUT', '清加工')], 'settle_method': [('MONTHLY', '月结')], 'unit': [('M', '米')], 'supplier_category': [('FABRIC', '面料商')], 'material_type': [('FABRIC', '面料')]}
    source_item = {'sourceItemId': '801', 'materialSkuId': '901', 'skuCode': 'ML-SYNTHETIC', 'materialName': '合成面料', 'materialCode': 'ML01', 'materialId': '91', 'colorName': '本白', 'spec': '150cm', 'stockUnit': 'M', 'purchaseUnit': 'M', 'conversionFactor': 1, 'suggestQty': 1, 'maxQty': 10, 'orderedQty': 10, 'receivedQty': 9, 'returnedQty': 0, 'needQty': 10, 'issuedQty': 9, 'dyeLot': None}
    bom_item = {'part': 'BODY', 'materialId': '91', 'materialCode': 'ML-SYNTHETIC', 'materialName': '合成面料', 'materialType': 'FABRIC', 'materialStatus': 1, 'skuMode': 'FIXED', 'usageMode': 'UNIFORM', 'usageQty': 1, 'sizeUsages': {}, 'unit': 'M', 'lossPct': 3, 'source': 'PURCHASE', 'supplierId': '13', 'supplierName': '合成供应商', 'remark': None, 'image': None, 'skus': [{'id': '821', 'colorId': None, 'colorName': None, 'sizeCode': None, 'materialSkuId': '901', 'skuCode': 'ML-SYNTHETIC', 'skuColorName': '本白', 'skuSpec': '150cm', 'skuStatus': 1}]}
    pack_detail = {'contextSource': 'LIVE', 'id': '706', 'styleId': '11', 'styleCode': style['code'], 'styleName': style['name'], 'aliasName': None, 'stage': 'PROD', 'version': 1, 'status': 'DRAFT', 'versionNote': None, 'sourcePackId': None, 'sourceVersion': None, 'utilPct': 80, 'packingMode': None, 'publishBy': None, 'publishByName': None, 'publishTime': None, 'createByName': '回归工艺员', 'createTime': '2026-11-18 10:00:00', 'updateTime': '2026-11-18 10:00:00', 'versionLock': 3, 'sizes': ['M'], 'baseSize': 'M', 'colors': style['colors'], 'pointOptions': [], 'specPoints': [], 'bomItems': [{**bom_item, 'id': '9007199254740981', 'seq': 1}, {**bom_item, 'id': '9007199254740983', 'seq': 2}], 'craftSections': [], 'processes': [{'id': row_id, 'seq': seq, 'category': 'SEW', 'name': '重复工序', 'requirement': None, 'stdSeconds': 60, 'remark': None, 'images': []} for seq, row_id in enumerate(['9007199254740985', '9007199254740987'], 1)], 'stdSecondsTotal': 120, 'actions': ['edit']}

    async def route_api(route):
        req = route.request
        path, query = urlsplit(req.url).path, parse_qs(urlsplit(req.url).query)
        if not path.startswith('/api/'):
            await route.continue_()
            return
        requests.append({'method': req.method, 'path': path, 'query': query})
        data = None
        if req.method == 'POST' and path == '/api/production-orders/schedule':
            writes.append({'path': path, 'body': req.post_data_json, 'company': req.headers.get('x-company-id'), 'key': req.headers.get('idempotency-key')})
            data = []
        elif req.method == 'PUT' and path in ['/api/production-orders/701', '/api/orders/704', '/api/purchase-orders/705', '/api/tech-packs/706', '/api/order-changes/707']:
            writes.append({'path': path, 'body': req.post_data_json, 'company': req.headers.get('x-company-id'), 'key': req.headers.get('idempotency-key')})
            data = 4
            if path == '/api/purchase-orders/705':
                purchase_state.update(version=purchase_state['version'] + 1, company=req.post_data_json['companyId'], item='9007199254741001')
                data = purchase_state['version']
        elif path == '/api/order-changes/707/submit':
            writes.append({'path': path, 'body': req.post_data_json, 'company': req.headers.get('x-company-id'), 'key': req.headers.get('idempotency-key')})
            data = 5
        elif path == '/api/purchase-orders/705/submit':
            await route.fulfill(json={'code': 1000, 'msg': '合成提交失败，请核对交期后重试', 'data': None})
            return
        elif path.startswith('/api/inventory-docs/from-'):
            if req.method == 'POST':
                writes.append({'path': path, 'body': req.post_data_json, 'company': req.headers.get('x-company-id'), 'key': req.headers.get('idempotency-key')})
                data = {'id': '9011', 'no': 'RK-SYNTHETIC', 'version': 1, 'status': 'DRAFT'}
            else:
                await asyncio.sleep(.05)
                purchase = '/from-purchase/' in path
                data = {'companyId': '1', 'docType': 'PURCHASE_IN' if purchase else 'ISSUE_OUT', 'source': {'id': '702' if purchase else '703', 'no': 'CG-SYNTHETIC' if purchase else 'SC-SYNTHETIC', 'type': 'PURCHASE_ORDER' if purchase else 'PRODUCTION_ORDER'}, 'partnerId': '13', 'partnerName': '合成供应商', 'fromWarehouseId': None if purchase else '31', 'fromWarehouseName': None if purchase else '原料仓', 'toWarehouseId': '31' if purchase else '32', 'toWarehouseName': '原料仓' if purchase else '加工厂仓', 'items': [dict(source_item)]}
        elif path == '/api/orders/704':
            data = {'id': '704', 'no': 'DD-SYNTHETIC', 'version': 3, 'status': 'DRAFT', 'companyId': '1', 'orderType': 'STOCK', 'customerId': None, 'customerOrderNo': None, 'contractNo': None, 'brand': None, 'orderDate': '2026-11-18', 'requiredDate': '2026-12-18', 'salesId': '15', 'salesName': '回归业务员', 'merchId': '16', 'merchName': '回归跟单', 'currency': 'CNY', 'deliveryReq': None, 'remark': None, 'styles': [{'styleId': '11', 'styleCode': style['code']}], 'items': [{'id': '9007199254740997', 'styleId': '11', 'styleSkuId': '101', 'sizeCode': 'M', 'qty': 2, 'lineDueDate': None, 'remark': None}]}
        elif path == '/api/purchase-orders/705':
            data = {'id': '705', 'no': 'CG-SYNTHETIC', 'version': 3, 'status': 'DRAFT', 'companyId': '1', 'supplierId': '13', 'supplierShortName': '合成供应商', 'purchaseType': 'STOCK', 'buyerId': '15', 'buyerName': '回归采购员', 'orderDate': '2026-11-18', 'deliveryDate': '2026-12-18', 'warehouseId': '31', 'settleMethod': 'MONTHLY', 'taxPct': 13, 'taxIncluded': True, 'freightIncluded': True, 'remark': None, 'planStyles': [], 'items': [{**source_item, 'id': '9007199254740999', 'calcItemId': None, 'qty': 2, 'lineDueDate': None, 'expectDate': None, 'orderNos': [], 'styleCodes': []}]}
            data.update(version=purchase_state['version'], companyId=purchase_state['company'])
            data['items'][0]['id'] = purchase_state['item']
        elif path == '/api/tech-packs/706': data = pack_detail
        elif path == '/api/tags/objects/TECH_PACK/706':
            assert req.method == 'GET' and not query
            assert await req.frame.evaluate("async()=>{const{useUserStore}=await import('/src/stores/user.ts');return useUserStore().hasPerm('techpack:pack:list')&&!useUserStore().hasPerm('techpack:cost:query')}")
            data = {'version': 0, 'tags': []}
        elif path.startswith('/api/dict-data/by-type/'):
            data = [{'value': value, 'label': label, 'status': 1} for value, label in dicts.get(path.rsplit('/', 1)[-1], [])]
        elif path == '/api/companies/options': data = companies
        elif req.method == 'GET' and path == '/api/suppliers/options':
            assert set(query) <= {'includeId', 'usage', 'category'}, query
            assert query.get('includeId') == ['13'] and query.get('usage') == ['BULK'], query
            assert query.get('category') in [None, ['GARMENT,PROCESSOR']], query
            company = req.headers.get('x-company-id')
            assert company in {'1', '2'}, 'Supplier options must carry the selected company'
            identity_matches = await req.frame.evaluate("""async authorization => {
                const { getToken, sessionIdentity } = await import('/src/utils/token.ts')
                return authorization === `Bearer ${getToken()}` && sessionIdentity() === 'user:9007199254740993'
            }""", req.headers.get('authorization'))
            assert identity_matches, 'Supplier options must use the current fixture identity'
            data = [{'id': '13', 'code': 'GY-SYNTHETIC', 'shortName': '合成供应商', 'status': 'ACTIVE'}]
            assert set(data[0]) == {'id', 'code', 'shortName', 'status'}
            supplier_option_reads.append({'include_id': '13', 'company': company, 'current_identity_matches': identity_matches, 'fields': sorted(data[0])})
        elif path == '/api/warehouses/options': data = [{'id': '31', 'name': '原料仓', 'code': 'YL', 'type': 'MATERIAL', 'companyId': '1', 'status': 1}, {'id': '32', 'name': '加工厂仓', 'code': 'GC', 'type': 'FACTORY', 'companyId': '1', 'supplierId': '13', 'status': 1}]
        elif path == '/api/orders/style-options': data = [style]
        elif path in ['/api/categories/tree', '/api/material-categories/tree', '/api/colors/options']: data = []
        elif path == '/api/production-orders/schedule-quote':
            data = {'factoryId': '13', 'factoryName': '合成工厂', 'usable': True, 'reason': None, 'settleMethod': 'MONTHLY', 'taxPct': 13, 'defaultProcessMode': 'CUT'}
            if prod_price in state['perms']: data['prices'] = [{'processMode': 'CUT', 'price': 17.25, 'priceSourceId': '14'}]
        elif path == '/api/stocks/qty': data = [{'materialSkuId': '901', 'qty': 10}]
        elif path == '/api/prices': data = {'list': [], 'total': 0}
        else:
            unknown.append({'method': req.method, 'path': path})
            await route.fulfill(status=500, json={'code': 500, 'msg': 'unconfigured security fixture route', 'data': None})
            return
        await route.fulfill(json={'code': 0, 'msg': '', 'data': data})

    await context.route('**/api/**', route_api)

    async def new_page(mode=''):
        if mode.startswith('purchase-'): purchase_state.update(version=3, company='1', item='9007199254740999')
        page = await context.new_page()
        page.on('dialog', lambda dialog: asyncio.create_task(dialog.accept()))
        await page.goto(url + '?security=1' + ('&mode=' + mode if mode else ''))
        try:
            await page.wait_for_function('Boolean(window.__security)', timeout=15000)
        except Exception:
            await page.screenshot(path=str(artifacts / 'security-mount-failure.png'), full_page=True)
            raise
        await page.locator('main.security-fixture').wait_for()
        return page

    async def role(page, perms):
        state['perms'] = perms
        await page.evaluate('perms => window.__security.role(perms)', perms)

    def field(container, label):
        return container.locator('.el-form-item').filter(has=container.page.locator('.el-form-item__label', has_text=re.compile('^' + re.escape(label) + '$')))

    async def choose_company(page, container):
        await field(container, '主体').locator('.el-select').click()
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text='测试主体 B').click()

    page = await new_page()
    await page.evaluate('window.__security.seedOrder()')
    await page.locator('.style-block').wait_for()
    await role(page, [cost])
    assert await page.get_by_test_id('order-batch-price').count() == 0
    await role(page, [order_price])
    await expect(page.get_by_test_id('order-batch-price')).to_be_visible()
    assert '吊牌价 88' in (await page.get_by_test_id('order-batch-price').locator('input').get_attribute('placeholder'))
    await page.locator('.qty-input').fill('2')
    await page.screenshot(path=str(artifacts / 'security-sales-price.png'), full_page=True)
    await role(page, [prod_price])
    assert await page.get_by_test_id('order-batch-price').count() == 0
    metrics['checks']['security_price_domains_independent'] = True

    for grant in [prod_price, order_price, cost]:
        await role(page, [grant])
        await page.evaluate('window.__security.schedule()')
        saved = writes[-1]
        assert saved['path'] == '/api/production-orders/schedule' and saved['company'] == '1', saved
        block = saved['body']['blocks'][0]
        assert block['items'] == [{'orderItemId': '801', 'qty': 2}], block
        if grant == prod_price: assert block['unitPrice'] == 17.25 and block['priceSourceId'] == '9007199254740993', block
        else: assert not {'unitPrice', 'priceSourceId', 'priceChangeReason'}.intersection(block), block
    metrics['checks']['security_real_schedule_api_price_domains'] = True

    for grant in [cost, prod_price, order_price]:
        await role(page, [grant])
        await page.evaluate('priced => window.__security.change(priced)', grant == order_price)
        change = page.get_by_role('dialog', name='改变更单 BG-SYNTHETIC', exact=True)
        await expect(change.locator('.qty-input')).to_have_count(1)
        if grant == order_price:
            await expect(field(change, '费用说明')).to_be_visible()
            await expect(change.get_by_role('checkbox', name=re.compile('整款改单价'))).to_be_checked()
            await field(change, '费用说明').locator('input').fill('合成费用由我方承担')
        else:
            assert await field(change, '费用说明').count() == 0
            assert await change.get_by_role('checkbox', name=re.compile('整款改单价')).count() == 0
        await change.locator('.qty-input').fill('4')
        await field(change, '原因').locator('textarea').fill('权限域回归，只修改可见字段')
        if grant == order_price: await change.screenshot(path=str(artifacts / 'security-change-price-and-fee.png'))
        async with page.expect_response(lambda r: r.url.endswith('/api/order-changes/707/submit')):
            await change.get_by_role('button', name='重新提交', exact=True).click()
        await expect(change).not_to_be_visible()
        saved = next(record for record in reversed(writes) if record['path'] == '/api/order-changes/707')
        assert saved['company'] == '1' and saved['body']['version'] == 3 and saved['body']['quantities'] == [{'styleSkuId': '101', 'qty': 4}], saved
        if grant == order_price:
            assert saved['body']['feeNote'] == '合成费用由我方承担' and saved['body']['prices'] == [{'styleId': '11', 'price': 19.5}], saved
        else: assert 'feeNote' not in saved['body'] and 'prices' not in saved['body'], saved
        assert writes[-1]['path'].endswith('/submit') and writes[-1]['company'] == '1' and writes[-1]['body'] == {'version': 4}, writes[-1]
    metrics['checks']['security_order_change_fee_and_whole_price_array'] = True
    await role(page, [prod_price])

    await page.evaluate('window.__security.production(true)')
    dialog = page.get_by_role('dialog', name='编辑 SC-SYNTHETIC', exact=True)
    await expect(field(dialog, '加工费（元/件）')).to_be_visible()
    assert float(await field(dialog, '加工费（元/件）').locator('input').input_value()) == 17.25
    await dialog.screenshot(path=str(artifacts / 'security-production-price.png'))
    await dialog.get_by_role('button', name='取消', exact=True).click()
    await role(page, [cost])
    await page.evaluate('window.__security.production(false)')
    await expect(dialog).to_be_visible()
    assert await field(dialog, '加工费（元/件）').count() == 0
    await choose_company(page, dialog)
    await field(dialog, '备注').locator('input').fill('无生产价格权限，仅改备注与主体')
    async with page.expect_response(lambda r: r.url.endswith('/api/production-orders/701') and r.request.method == 'PUT'):
        await dialog.get_by_role('button', name='保存', exact=True).click()
    saved = writes[-1]
    assert saved['company'] == '1' and saved['body']['expectedCompanyId'] == '1' and saved['body']['companyId'] == '2', saved
    assert not {'unitPrice', 'priceSourceId', 'priceChangeReason'}.intersection(saved['body']), saved
    metrics['checks']['security_production_omission_and_company_change'] = True

    await role(page, [])
    await page.evaluate('window.__security.supplier()')
    supplier = page.get_by_role('dialog', name='新建供应商', exact=True)
    await expect(supplier).to_be_visible()
    for label in ['联系人', '电话', '微信', '地址', '开户行', '收款户名', '账号']: assert await field(supplier, label).count() == 0, label
    await role(page, ['supplier:supplier:contact'])
    for label in ['联系人', '电话', '微信', '地址']: await expect(field(supplier, label)).to_be_visible()
    for label in ['开户行', '收款户名', '账号']: assert await field(supplier, label).count() == 0, label
    await supplier.screenshot(path=str(artifacts / 'security-supplier-contact.png'))
    await role(page, ['supplier:supplier:bank'])
    for label in ['开户行', '收款户名', '账号']: await expect(field(supplier, label)).to_be_visible()
    for label in ['联系人', '电话', '微信', '地址']: assert await field(supplier, label).count() == 0, label
    await supplier.get_by_role('button', name='取消', exact=True).click()
    metrics['checks']['security_supplier_contact_and_bank_separate'] = True

    await role(page, [prod_price])
    await page.evaluate('window.__security.material()')
    material = page.get_by_role('dialog', name='新建物料', exact=True)
    await expect(material).to_be_visible()
    assert await field(material, '参考单价（元 / 采购单位）').count() == 0
    await role(page, [cost])
    await expect(field(material, '参考单价（元 / 采购单位）')).to_be_visible()
    await material.screenshot(path=str(artifacts / 'security-material-cost.png'))
    await material.get_by_role('button', name='取消', exact=True).click()
    metrics['checks']['security_material_cost_permission'] = True

    for permission, kind in [(prod_price, 'PROCESS'), (cost, 'MATERIAL')]:
        await role(page, [permission, 'price:price:list'])
        before = len(requests)
        await page.evaluate('window.__security.prices(true)')
        await expect(page.locator('.price-history .el-table__empty-text').first).to_be_visible()
        await page.wait_for_timeout(100)
        reads = [r for r in requests[before:] if r['path'] == '/api/prices']
        assert len(reads) == 2 and all(r['query'].get('type') == [kind] for r in reads), reads
        await page.evaluate('window.__security.prices(false)')
    metrics['checks']['security_price_archive_domain_filter'] = True

    await role(page, [])
    before = len(writes)
    for path, expected in [('/api/orders/704', None), ('/api/orders/704', '9'), ('/api/inventory-docs/704', '1'), ('/api/orders/704/attachments', '1')]:
        result = await page.evaluate('([path, expected]) => window.__security.invalidCompany(path, expected)', [path, expected])
        assert result != 'sent', (path, expected, result)
    assert len(writes) == before
    metrics['checks']['security_company_change_exception_is_narrow'] = True
    for kind, suffix in [('PURCHASE', 'from-purchase/702'), ('PRODUCTION', 'from-production/703')]:
        await page.evaluate('kind => window.__security.source(kind)', kind)
        source = page.get_by_role('dialog', name=re.compile('采购入库|领料出库'))
        await expect(source.locator('.el-table__row')).to_have_count(1)
        assert await source.get_by_role('columnheader', name='单价', exact=True).count() == 0
        try:
            async with page.expect_response(lambda r: r.url.endswith(suffix) and r.request.method == 'POST', timeout=8000):
                await source.get_by_role('button', name='存草稿', exact=True).click()
        except Exception:
            await page.screenshot(path=str(artifacts / 'security-source-failure.png'), full_page=True)
            metrics['security_source_failure'] = await page.locator('body').inner_text()
            raise
        sent = writes[-1]
        assert sent['company'] == '1' and sent['body']['items'][0]['qty'] == 1 and sent['key'] == sent['body']['clientActionId'], sent
        await expect(source).not_to_be_visible()
        await page.evaluate('kind => window.__security.sourceWithoutScope(kind)', kind)
        sent = writes[-1]
        assert sent['company'] == '1' and sent['body']['items'][0]['qty'] == 1 and sent['key'] == sent['body']['clientActionId'], sent
    assert not any(r['path'] in ['/api/purchase-orders/702', '/api/production-orders/703'] for r in requests)
    metrics['checks']['security_source_projection_company_and_snapshot'] = True
    await page.close()

    for mode, path in [('order-edit', '/api/orders/704'), ('purchase-edit', '/api/purchase-orders/705')]:
        state['perms'] = []
        page = await new_page(mode)
        await expect(page.get_by_role('button', name='保存', exact=True)).to_be_enabled()
        await choose_company(page, page.locator('main.security-fixture'))
        if mode == 'purchase-edit': await expect(field(page.locator('main.security-fixture'), '供应商')).to_contain_text('合成供应商')
        async with page.expect_response(lambda r: r.url.endswith(path) and r.request.method == 'PUT'):
            await page.get_by_role('button', name='保存', exact=True).click()
        saved = writes[-1]
        assert saved['company'] == '1' and saved['body']['expectedCompanyId'] == '1' and saved['body']['companyId'] == '2', saved
        assert saved['body']['version'] == 3 and saved['body']['items'][0]['qty'] == 2, saved
        assert all('price' not in row and 'priceChangeReason' not in row for row in saved['body']['items']), saved
        if mode == 'purchase-edit': assert saved['body']['items'][0]['id'] == '9007199254740999', saved
        metrics['checks']['security_' + mode.replace('-', '_') + '_origin_company_and_omission'] = True
        await page.close()
    page = await new_page('purchase-retry')
    await expect(page.get_by_role('button', name='保存并提交', exact=True)).to_be_enabled()
    await choose_company(page, page.locator('main.security-fixture'))
    await expect(field(page.locator('main.security-fixture'), '供应商')).to_contain_text('合成供应商')
    before = len(requests)
    async with page.expect_response(lambda r: r.url.endswith('/api/purchase-orders/705/submit')):
        await page.get_by_role('button', name='保存并提交', exact=True).click()
    reads_and_writes = [(r['method'], r['path']) for r in requests[before:] if r['path'].startswith('/api/purchase-orders/705')]
    assert reads_and_writes == [('PUT', '/api/purchase-orders/705'), ('GET', '/api/purchase-orders/705'), ('POST', '/api/purchase-orders/705/submit')], reads_and_writes
    await field(page.locator('main.security-fixture'), '备注').locator('input').fill('提交失败后再次保存')
    async with page.expect_response(lambda r: r.url.endswith('/api/purchase-orders/705') and r.request.method == 'PUT'):
        await page.get_by_role('button', name='保存', exact=True).click()
    saved = writes[-1]
    assert saved['company'] == '2' and saved['body']['expectedCompanyId'] == '2' and saved['body']['version'] == 4, saved
    assert saved['body']['items'][0]['id'] == '9007199254741001', saved
    metrics['checks']['security_purchase_refreshes_ids_before_submit'] = True
    await page.close()
    page = await new_page('pack-edit')
    await expect(page.get_by_test_id('bom-row')).to_have_count(2)
    assert await page.get_by_role('columnheader', name='参考单价', exact=True).count() == 0
    assert await page.get_by_role('tab', name='核价', exact=True).count() == 0
    await page.get_by_placeholder('本版说明（选填）').fill('保留重复行身份')
    # Move duplicate business-key rows: identity must follow each row, never its previous array index.
    await page.get_by_role('button', name='上移', exact=True).nth(1).click()
    await page.get_by_role('tab', name=re.compile('工艺指示')).click()
    await page.locator('.proc-block').get_by_role('button', name='上移', exact=True).nth(1).click()
    await page.locator('.proc-block').get_by_role('button', name='加工序', exact=True).click()
    new_process = page.locator('.proc-block .el-table__row').last
    await new_process.locator('input').nth(1).fill('新工序')
    async with page.expect_response(lambda r: r.url.endswith('/api/tech-packs/706') and r.request.method == 'PUT'):
        await page.get_by_role('button', name='保存', exact=True).click()
    saved = writes[-1]['body']
    assert [row['id'] for row in saved['bomItems']] == ['9007199254740983', '9007199254740981'], saved
    assert [row.get('id') for row in saved['processes']] == ['9007199254740987', '9007199254740985', None], saved
    for row in saved['bomItems'] + saved['processes']:
        assert not {'price', 'refPrice', 'priceTaxIncluded', 'priceFreightIncluded', 'priceSourceId'}.intersection(row), row
    assert 'cost' not in saved
    metrics['checks']['security_duplicate_bom_and_process_identity'] = True
    await page.close()
    assert not unknown, unknown
    assert len(supplier_option_reads) == 2 and all(read['company'] == '2' for read in supplier_option_reads), supplier_option_reads
    metrics['checks']['security_supplier_option_preselection_projection'] = True
    metrics['security_supplier_option_reads'] = supplier_option_reads
    metrics['security_requests'] = requests
    metrics['security_writes'] = writes
    await context.close()
