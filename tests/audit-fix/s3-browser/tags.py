#!/usr/bin/env python3
"""Actual tag Vue components and 12 persisted entry surfaces; explicitly synthetic HTTP."""
import argparse
import asyncio
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import unicodedata
from urllib.parse import parse_qs, urlencode, urlsplit

from playwright.async_api import async_playwright, expect
from fg import doc as fg_doc, production, receipts, STAMP, DOC, RECEIPT

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/tags'
OBJECT = '9007199254746001'
OTHER = '9007199254746002'
ARCHIVED, ACTIVE, SUMMER = ['9007199254741' + str(i).zfill(3) for i in range(1, 4)]
TYPES = ['STYLE', 'MATERIAL', 'SUPPLIER', 'TECH_PACK', 'ORDER', 'MRP_CALC', 'PURCHASE_ORDER',
         'INV_DOC', 'INV_COUNT', 'PRODUCTION_ORDER', 'PRODUCTION_RECEIPT', 'FG_DOC']
SHARED = {'STYLE', 'MATERIAL', 'SUPPLIER', 'TECH_PACK'}


def normalize(raw):
    value = unicodedata.normalize('NFKC', raw)
    assert not any(unicodedata.category(c) in {'Cc', 'Cs'} or c == ',' or '\u202a' <= c <= '\u202e' or '\u2066' <= c <= '\u2069' for c in value)
    value = value.strip()
    if value.startswith('#'): value = value[1:].strip()
    assert 1 <= len(value) <= 64
    return value


def header(no):
    return {'id': OBJECT, 'no': no, 'status': 'DRAFT', 'version': 7, 'companyId': '1', 'companyName': '合成主体甲',
            'remark': None, 'createBy': '9007199254740993', 'createByName': '合成制单员', 'createTime': STAMP,
            'updateTime': STAMP, 'auditBy': None, 'auditByName': None, 'auditTime': None, 'actions': ['edit'],
            'approval': None, 'pendingNotices': 0}


def entry_payloads():
    style = {'id': OBJECT, 'code': 'KS-TAG-SYN', 'name': '合成标签款式', 'status': 'ENABLED', 'styleClass': 'GARMENT',
             'brand': '', 'categoryId': '11', 'categoryName': '上装', 'categoryPath': '女装/上装', 'year': 2026, 'season': '',
             'source': '', 'sizeGroupId': '12', 'sizeGroupName': '合成尺码组', 'sizes': ['M'], 'techPackStatus': 'NONE',
             'colors': [], 'skuCount': 0, 'images': [], 'files': [], 'actions': ['edit'], 'legacyAttrs': {},
             'createTime': STAMP, 'updateTime': STAMP}
    material = {'material': {'id': OBJECT, 'code': 'ML-TAG-SYN', 'name': '合成标签物料', 'status': 1,
                            'materialType': 'FABRIC', 'stockUnit': 'M', 'purchaseUnit': 'M', 'conversionFactor': 1,
                            'defaultSource': 'PURCHASE', 'minPackQty': 1, 'createTime': STAMP, 'legacyAttrs': {}},
                'skus': [], 'images': [], 'categoryName': '面料', 'parentCategoryName': '物料'}
    supplier = {**header('GY-TAG-SYN'), 'code': 'GY-TAG-SYN', 'shortName': '合成标签供应商', 'name': '合成供应商',
                'status': 'ACTIVE', 'categories': [], 'goodAt': [], 'legacyAttrs': {}}
    order = {**header('DD-TAG-SYN'), 'orderType': 'STOCK', 'orderDate': '2026-11-21', 'requiredDate': '2026-12-01',
             'commitDate': None, 'salesId': '9007199254740993', 'merchId': '9007199254740993', 'currency': 'CNY',
             'totalQty': 0, 'source': 'MANUAL', 'scheduledQty': 0, 'returnedQty': 0, 'styles': [], 'items': [], 'pendingChange': None}
    mrp = {**header('SL-TAG-SYN'), 'companyId': None, 'orders': [], 'styleCodes': [], 'items': [], 'diff': None,
           'closeReason': None, 'status': 'CONFIRMED'}
    purchase = {**header('CG-TAG-SYN'), 'supplierId': '13', 'supplierCode': 'GY-TAG-SYN', 'supplierName': '合成供应商',
                'supplierShortName': '合成供应商', 'supplierStatus': 'ACTIVE', 'purchaseType': 'STOCK',
                'buyerId': '9007199254740993', 'buyerName': '合成采购', 'orderDate': '2026-11-21', 'deliveryDate': '2026-12-01',
                'warehouseId': '31', 'warehouseName': '合成仓库', 'warehouseType': 'MATERIAL',
                'settleMethod': None, 'taxPct': 0, 'taxIncluded': True, 'freightIncluded': True,
                'totalQty': 0, 'receivedQty': 0, 'returnedQty': 0, 'planStyles': [], 'items': [], 'stockDocs': []}
    inv = {**header('RK-TAG-SYN'), 'kind': 'IN', 'docType': 'OTHER_IN', 'source': None, 'fromWarehouseId': None,
           'toWarehouseId': '31', 'toWarehouseName': '合成仓库', 'toWarehouseType': 'MATERIAL',
           'docDate': '2026-11-21', 'reason': 'INITIAL', 'totalQty': 0, 'posted': False, 'items': []}
    count = {**header('PD-TAG-SYN'), 'warehouseId': '31', 'warehouseName': '合成仓库', 'warehouseType': 'MATERIAL',
             'purpose': 'NORMAL', 'scope': 'ALL', 'scopeText': '全仓', 'categories': [], 'countDate': '2026-11-21',
             'counterId': '9007199254740993', 'counterName': '合成盘点', 'bookTime': STAMP,
             'itemCount': 0, 'filledCount': 0, 'gainCount': 0, 'lossCount': 0, 'movesSinceBook': 0, 'posted': False, 'items': []}
    pack = {'id': OBJECT, 'styleId': OBJECT, 'styleCode': style['code'], 'styleName': style['name'], 'stage': 'PROD',
            'version': 1, 'versionLock': 7, 'status': 'DRAFT', 'sizes': ['M'], 'baseSize': 'M', 'colors': [], 'pointOptions': [],
            'specPoints': [], 'bomItems': [], 'craftSections': [], 'processes': [], 'stdSecondsTotal': 0,
            'actions': ['edit'], 'contextSource': 'LIVE', 'utilPct': None, 'packingMode': None,
            'createTime': STAMP, 'updateTime': STAMP}
    return {'STYLE': style, 'MATERIAL': material, 'SUPPLIER': supplier, 'ORDER': order, 'MRP_CALC': mrp,
            'PURCHASE_ORDER': purchase, 'INV_DOC': inv, 'INV_COUNT': count, 'TECH_PACK': pack}


class SyntheticTags:
    def __init__(self):
        self.tags = {
            ARCHIVED: {'id': ARCHIVED, 'name': '#蓝', 'archived': True, 'version': 2},
            ACTIVE: {'id': ACTIVE, 'name': '#蓝', 'archived': False, 'version': 1},
            SUMMER: {'id': SUMMER, 'name': 'Summer', 'archived': False, 'version': 3},
        }
        for i in range(105):
            identifier = str(9007199254750001 + i)
            self.tags[identifier] = {'id': identifier, 'name': '常用' + str(i + 1).zfill(3), 'archived': False, 'version': 0}
        self.objects, self.calls, self.writes, self.errors, self.expected_resources = {}, [], [], [], []
        self.attempts, self.aborted = [], []
        self.domain_logs = {}
        self.faults, self.holds, self.receipts = {}, {}, {}
        self.tasks = set()
        self.payloads = entry_payloads()
        self.filter_rows = [{**deepcopy(self.payloads['STYLE']), 'id': str(9007199254790000 + i),
                             'code': 'KS-FILTER-' + str(i).zfill(3), 'name': '合成筛选款式 ' + str(i),
                             '_tagIds': [ACTIVE, SUMMER] if i <= 24 else [ACTIVE] if i <= 32 else [SUMMER] if i <= 40 else [ARCHIVED] if i <= 45 else []}
                            for i in range(1, 47)]
        self.production = production()
        self.production.update(status='DRAFT', actions=['edit'])
        self.production['id'] = '51'
        self.production_receipts = receipts()

    def object(self, kind, identifier):
        key = (kind, identifier)
        if key not in self.objects:
            self.objects[key] = {'version': 4, 'ids': [ARCHIVED, ACTIVE, SUMMER] if identifier != OTHER else [SUMMER]}
        record = self.objects[key]
        return {'version': record['version'], 'tags': [deepcopy(self.tags[i]) for i in record['ids']]}

    def hold(self, path, keyword=None):
        hold = {key: asyncio.Event() for key in ['started', 'release', 'finished']}
        self.holds[(path, keyword)] = hold
        return hold

    async def fault(self, route, code):
        status = code if code in [400, 403, 404, 409, 500, 503] else 200
        if status >= 400: self.expected_resources.append({'path': urlsplit(route.request.url).path, 'status': status})
        await route.fulfill(status=status, json={'code': code, 'msg': '合成标签故障', 'data': None},
                            headers={'X-Request-Id': 'synthetic-tag-fault'})

    async def route(self, route):
        if not urlsplit(route.request.url).path.startswith('/api/'):
            await route.continue_()
            return
        task = asyncio.current_task()
        self.tasks.add(task)
        request = route.request
        path, params = urlsplit(request.url).path, parse_qs(urlsplit(request.url).query)
        call = {'method': request.method, 'path': path, 'params': params, 'company': request.headers.get('x-company-id'),
                'request_id': request.headers.get('x-request-id')}
        self.calls.append(call)
        if request.method == 'POST':
            self.attempts.append({**call, 'body': deepcopy(request.post_data_json), 'key': request.headers.get('idempotency-key')})
        held = self.holds.get((path, params.get('keyword', [None])[0]))
        if held and held['started'].is_set(): held = None
        try:
            if held:
                held['started'].set()
                await asyncio.wait_for(held['release'].wait(), timeout=15)
            faults = self.faults.get((request.method, path), [])
            if faults:
                await self.fault(route, faults.pop(0)); return
            match = re.fullmatch(r'/api/tags/objects/([A-Z_]+)/(\d+)(/replace)?', path)
            if match:
                kind, identifier, replace = match.groups()
                assert kind in TYPES
                if kind not in SHARED and kind != 'MRP_CALC': assert call['company'] in {'1', '2'}, call
                if replace:
                    assert request.method == 'POST'
                    body = request.post_data_json
                    assert set(body) == {'version', 'names', 'retainIds', 'tagVersions'}, body
                    assert re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', request.headers.get('idempotency-key', ''))
                    self.writes.append({**call, 'body': deepcopy(body), 'key': request.headers['idempotency-key']})
                    before = self.object(kind, identifier)
                    if body['version'] != before['version'] or body['tagVersions'] != {t['id']: t['version'] for t in before['tags']}:
                        await self.fault(route, 1002); return
                    assert len(body['names']) <= 30 and len(set(body['retainIds'])) == len(body['retainIds'])
                    assert set(body['retainIds']) <= set(self.objects[(kind, identifier)]['ids'])
                    normalized = [normalize(name) for name in body['names']]
                    ids = []
                    for name in normalized:
                        same = [i for i in body['retainIds'] if self.tags[i]['name'].lower() == name.lower()]
                        if not same:
                            active = next((t for t in self.tags.values() if not t['archived'] and t['name'].lower() == name.lower()), None)
                            if active is None:
                                new_id = str(9007199254760001 + len(self.tags))
                                active = {'id': new_id, 'name': name, 'archived': False, 'version': 0}
                                self.tags[new_id] = active
                            same = [active['id']]
                        ids.extend(i for i in same if i not in ids)
                    assert len(ids) <= 30
                    self.objects[(kind, identifier)] = {'version': before['version'] + 1, 'ids': ids}
                    self.domain_logs.setdefault((kind,identifier),[]).insert(0,{'id':str(9007199254780001+len(self.writes)),
                        'action':'replace-tags','fromStatus':None,'toStatus':None,'remark':'合成标签变更记录',
                        'operatorId':'9007199254740993','operatorName':'合成制单员','createTime':STAMP})
                else: assert request.method == 'GET'
                data = self.object(kind, identifier)
            elif path in {'/api/tags', '/api/tags/filter-options'}:
                assert request.method == 'GET' and set(params) <= {'keyword', 'size', 'includeIds'}
                size = int(params.get('size', ['50'])[0]); assert 1 <= size <= 100
                includes = list(dict.fromkeys(','.join(params.get('includeIds', [])).split(','))) if params.get('includeIds') else []
                assert len(includes) <= 100
                if any(i not in self.tags for i in includes): await self.fault(route, 404); return
                raw = params.get('keyword', [''])[0]
                try: prefix = normalize(raw).lower() if raw and raw != '#' else ''
                except AssertionError:
                    await self.fault(route, 400); return
                selected = [self.tags[i] for i in includes]
                rest = sorted((t for t in self.tags.values() if (path.endswith('/filter-options') or not t['archived']) and t['id'] not in includes and t['name'].lower().startswith(prefix)),
                              key=lambda tag: (tag['name'].lower(), tag['id']))
                data = selected + rest[:max(size, len(includes)) - len(selected)]
                assert len(data) <= 100
            elif (manage := re.fullmatch(r'/api/tags/(\d+)/(rename|archive)', path)):
                assert request.method == 'POST'
                identifier, operation = manage.groups(); body = request.post_data_json
                assert set(body) == ({'version', 'name'} if operation == 'rename' else {'version'})
                key = request.headers.get('idempotency-key'); assert re.fullmatch(r'[0-9a-f-]{36}', key or '')
                self.writes.append({**call, 'body': deepcopy(body), 'key': key})
                tag = self.tags[identifier]
                if body['version'] != tag['version']: await self.fault(route, 1002); return
                assert not tag['archived']
                if operation == 'rename': tag['name'] = normalize(body['name'])
                else: tag['archived'] = True
                tag['version'] += 1
                data = deepcopy(tag)
            elif (history := re.fullmatch(r'/api/tags/(\d+)/logs', path)):
                assert request.method == 'GET' and history.group(1) in self.tags
                page, size = int(params.get('page', ['1'])[0]), int(params.get('size', ['20'])[0]); assert 1 <= size <= 100
                data = {'list': [{'id': str(9007199254770001 + i), 'action': 'rename', 'fromStatus': None, 'toStatus': None,
                                 'remark': '合成标签记录 ' + str(i + 1), 'operatorId': '9007199254740993', 'operatorName': '合成主管',
                                 'createTime': STAMP} for i in range((page - 1) * size, min(page * size, 25))], 'total': 25}
            else:
                assert request.method == 'GET', 'Unexpected non-tag write ' + path
                data = self.entry_read(path, params)
            await route.fulfill(json={'code': 0, 'msg': '', 'data': data}, headers={'X-Request-Id': 'synthetic-tag-response'})
        except Exception as error:
            self.errors.append(type(error).__name__ + ': ' + str(error))
            await route.fulfill(status=500, json={'code': 500, 'msg': 'Unexpected synthetic tag fixture request', 'data': None})
        finally:
            if held: held['finished'].set()
            self.tasks.discard(task)

    def entry_read(self, path, params):
        if path == '/api/styles':
            selected = ','.join(params.get('tagIds', [])).split(',') if params.get('tagIds') else []
            assert len(selected) <= 30 and all(identifier in self.tags for identifier in selected)
            rows = [row for row in self.filter_rows if set(selected) <= set(row['_tagIds'])]
            number, size = int(params.get('page', ['1'])[0]), int(params.get('size', ['20'])[0])
            return {'list': [{key: value for key, value in row.items() if key != '_tagIds'} for row in rows[(number - 1) * size:number * size]], 'total': len(rows)}
        endpoints = {'/api/styles/': 'STYLE', '/api/materials/': 'MATERIAL', '/api/suppliers/': 'SUPPLIER', '/api/orders/': 'ORDER',
                     '/api/mrp-calcs/': 'MRP_CALC', '/api/purchase-orders/': 'PURCHASE_ORDER', '/api/inventory-docs/': 'INV_DOC',
                     '/api/inventory-counts/': 'INV_COUNT', '/api/tech-packs/': 'TECH_PACK'}
        for prefix, kind in endpoints.items():
            if path == prefix + OBJECT: return deepcopy(self.payloads[kind])
            if path == prefix + OBJECT + '/logs': return deepcopy(self.domain_logs.get((kind,OBJECT),[]))
            if path in [prefix + OBJECT + '/' + suffix for suffix in ['logs', 'attachments', 'references', 'notices', 'changes', 'certs', 'skus']]:
                return []
        if path == '/api/samples/by-style/' + OBJECT:
            assert params == {'companyId': ['1']}, params
            return None
        if path == '/api/tech-packs':
            return {'styleId': OBJECT, 'packs': [deepcopy(self.payloads['TECH_PACK'])], 'defaultPackId': OBJECT,
                    'creatableStages': [], 'unmatchedColors': [], 'unitCostPackId': None, 'unitCostStage': None}
        if path == '/api/production-orders/51': return deepcopy(self.production)
        if path == '/api/production-orders/51/receipts': return deepcopy(self.production_receipts)
        if path == '/api/production-orders/51/materials': return {'rows': [], 'docs': []}
        if path in ['/api/production-orders/51/' + suffix for suffix in ['issues', 'logs', 'attachments', 'notices']]: return []
        if path == '/api/fg-docs/' + DOC: return fg_doc(price=False)
        if path in ['/api/fg-docs/' + DOC + '/' + suffix for suffix in ['logs', 'attachments', 'references']]: return []
        if path == '/api/fg-docs/by-receipts':
            return [{'receiptId': identifier, 'documents': [], 'documentsTotal': 0, 'hasMoreDocuments': False,
                     'reservedQty': 0, 'postedQty': 0, 'remainingQty': 396 if identifier == RECEIPT else 0}
                    for identifier in params.get('receiptIds', [])]
        if path == '/api/fg-docs/shipped':
            assert params == {'orderId':[OBJECT],'companyId':['1']},params
            return []
        if path == '/api/version': return {'version': 'synthetic-tags', 'buildTime': None}
        if path.startswith('/api/dict-data/by-type/'): return []
        if path in ['/api/categories/tree', '/api/material-categories/tree', '/api/colors/options']: return []
        if path == '/api/companies/options': return [{'id': '1', 'shortName': '合成主体甲'}, {'id': '2', 'shortName': '合成主体乙'}]
        if path == '/api/warehouses/options': return [{'id': '31', 'name': '合成仓库', 'type': 'MATERIAL'}]
        if path == '/api/users/options': return [{'id': '9007199254740993', 'nickname': '合成制单员'}]
        if path == '/api/order-kitting': return []
        raise AssertionError('Unexpected synthetic API GET ' + path + ' ' + str(params))


def check(report, name, **facts): report['checks'][name] = {'passed': True, **facts}


async def capture(page, name):
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
    await page.screenshot(path=str(ARTIFACTS / name), full_page=True, animations='disabled')


async def tag_layout(page, panel, width):
    await page.set_viewport_size({'width': width, 'height': 1000})
    await panel.scroll_into_view_if_needed()
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
    return await panel.evaluate('''el => {
      const button = [...el.querySelectorAll('button')].find(node => node.textContent.trim() === '编辑标签');
      const rect = node => { const r = node.getBoundingClientRect(); return { left:r.left, right:r.right, top:r.top, bottom:r.bottom, width:r.width }; };
      const b = button.getBoundingClientRect(); const at = document.elementFromPoint((b.left+b.right)/2,(b.top+b.bottom)/2);
      return { viewport:innerWidth, documentWidth:document.documentElement.scrollWidth, panel:rect(el), button:rect(button), hit:button===at || button.contains(at) };
    }''')


def assert_tag_layout(metrics):
    assert metrics['documentWidth'] <= metrics['viewport'] + 1, metrics
    assert metrics['panel']['left'] >= 0 and metrics['panel']['right'] <= metrics['viewport'] + 1, metrics
    assert metrics['button']['left'] >= 0 and metrics['button']['right'] <= metrics['viewport'] + 1, metrics
    assert metrics['button']['top'] >= 0 and metrics['button']['bottom'] <= 1000 and metrics['hit'], metrics


async def start(browser, base, report, path='/tag-objects', permissions=None, api=None):
    api = api or SyntheticTags()
    context = await browser.new_context(viewport={'width': 1440, 'height': 1000})
    page = await context.new_page()
    page.set_default_timeout(12000)
    page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
    page.on('requestfailed', lambda request: api.aborted.append({'method': request.method, 'path': urlsplit(request.url).path, 'failure': request.failure}))
    console = []
    page.on('console', lambda message: console.append({'text': message.text, 'path': urlsplit(message.location.get('url', '')).path}) if message.type == 'error' else None)
    page.on('dialog', lambda dialog: asyncio.create_task(dialog.accept()))
    await context.route('**/api/**', api.route)
    params = {'path': path}
    if permissions is not None: params['permissions'] = ','.join(permissions)
    await page.goto(base + '/node_modules/.s3-tags/tags.html?' + urlencode(params))
    await page.locator('.fixture-controls').wait_for()
    return context, page, api, console


async def finish(context, api, console, report):
    for held in api.holds.values(): held['release'].set()
    if api.tasks: await asyncio.gather(*list(api.tasks))
    await context.close()
    for error in console:
        if error['text'].startswith('Failed to load resource:') and any(error['path'] == expected['path'] and 'status of ' + str(expected['status']) in error['text'] for expected in api.expected_resources):
            report['expected_console_errors'].append(error)
        else: report['console_errors'].append(error)
    assert not api.errors, api.errors


async def editor(page):
    await page.get_by_role('button', name='编辑标签', exact=True).click()
    dialog = page.get_by_role('dialog', name='编辑公共标签', exact=True)
    await expect(dialog).to_be_visible()
    return dialog


async def add(dialog, raw):
    await dialog.get_by_role('textbox', name='标签输入', exact=True).fill(raw)
    await dialog.get_by_role('button', name='添加标签', exact=True).click()


async def check_retention(browser, base, report):
    context, page, api, console = await start(browser, base, report)
    try:
        panel = page.locator('.object-tags')
        await expect(panel).to_contain_text('已归档')
        dialog = await editor(page)
        await expect(dialog.locator('.tag-selection li')).to_have_count(3)
        await dialog.get_by_role('button', name='移除标签 Summer', exact=True).click()
        await add(dialog, ' ＃新季 ')
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog).not_to_be_visible()
        write = api.writes[-1]
        assert write['body'] == {'version': 4, 'names': ['##蓝', '##蓝', ' ＃新季 '], 'retainIds': [ARCHIVED, ACTIVE],
                                 'tagVersions': {ARCHIVED: 2, ACTIVE: 1, SUMMER: 3}}, write
        await expect(panel).to_contain_text('#新季')
        assert write['company'] == '1' and write['request_id']
        await capture(page, 'archived-same-name-retained.png')
        check(report, 'exact_archived_ids_full_versions_and_single_normalization', write=write)
        dialog = await editor(page)
        await dialog.locator('li[data-tag-id="' + ARCHIVED + '"]').get_by_role('button', name='移除标签 #蓝', exact=True).click()
        await dialog.locator('li[data-tag-id="' + ACTIVE + '"]').get_by_role('button', name='移除标签 #蓝', exact=True).click()
        await add(dialog, '##蓝')
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog).not_to_be_visible()
        result = api.object('ORDER', OBJECT)
        assert ARCHIVED not in [tag['id'] for tag in result['tags']] and ACTIVE in [tag['id'] for tag in result['tags']]
        check(report, 'removed_archive_reentry_uses_active_id', write=api.writes[-1], result=result)
    except BaseException:
        await capture(page, 'retention-failure.png'); raise
    finally: await finish(context, api, console, report)


async def check_conflicts(browser, base, report):
    context, page, api, console = await start(browser, base, report)
    try:
        dialog = await editor(page)
        await add(dialog, '保留我的输入')
        api.tags[SUMMER].update(name='Autumn', version=4)
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog.get_by_role('alert')).to_contain_text('标签已被其他人修改')
        await expect(dialog).to_contain_text('保留我的输入')
        await expect(dialog.get_by_role('button', name='保存标签', exact=True)).to_be_disabled()
        assert len(api.writes) == 1
        await capture(page, 'object-cas-input-preserved.png')
        await dialog.get_by_role('button', name='重新读取并核对', exact=True).click()
        await expect(dialog).to_contain_text('#Autumn')
        await expect(dialog).to_contain_text('保留我的输入')
        await dialog.get_by_role('button', name='确认当前选择并保存', exact=True).click()
        await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['body']['tagVersions'][SUMMER] == 4
        assert '#Summer' not in api.writes[-1]['body']['names']
        check(report, 'real_cas_fault_retains_input_explicit_reload_no_stale_recreate', rejected=api.writes[0], accepted=api.writes[-1])
        dialog = await editor(page)
        await add(dialog, '失权时保留')
        post = '/api/tags/objects/ORDER/' + OBJECT + '/replace'
        api.faults[('POST', post)] = [403]
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog.get_by_role('alert')).to_contain_text('没有这项标签操作权限')
        await expect(dialog).to_contain_text('失权时保留')
        api.faults[('GET', '/api/tags/objects/ORDER/' + OBJECT)] = [404]
        await dialog.get_by_role('button', name='重新读取并核对', exact=True).click()
        await expect(dialog.get_by_role('alert')).to_contain_text('已不可见')
        await expect(dialog).to_contain_text('失权时保留')
        await capture(page, 'object-permission-fault-input-preserved.png')
        check(report, '403_404_are_not_empty_and_preserve_edit', input_preserved=True, save_disabled=True)
    except BaseException:
        await capture(page, 'conflict-failure.png'); raise
    finally: await finish(context, api, console, report)


async def check_permissions(browser, base, report):
    for kind in TYPES:
        context, page, api, console = await start(browser, base, report, '/tag-objects?type=' + kind, permissions=['biz:tag:list'])
        try:
            await expect(page.locator('.object-tags')).to_contain_text('没有读取此对象标签的权限')
            assert not any('/tags/objects/' in call['path'] for call in api.calls)
            check(report, 'no_domain_read_no_request_' + kind)
        finally: await finish(context, api, console, report)
    context, page, api, console = await start(browser, base, report)
    try:
        dialog = await editor(page)
        await add(dialog, '撤权保留')
        await page.get_by_test_id('tag-readonly').click()
        await expect(dialog.get_by_role('button', name='保存标签', exact=True)).to_be_disabled()
        await expect(dialog).to_contain_text('撤权保留')
        assert not api.writes
        await capture(page, 'runtime-role-downgrade.png')
        check(report, 'runtime_write_revocation_disables_without_discard', writes=0)
    except BaseException:
        await capture(page, 'runtime-role-downgrade-failure.png'); raise
    finally: await finish(context, api, console, report)


async def check_races(browser, base, report):
    old_path = '/api/tags/objects/ORDER/' + OBJECT
    api = SyntheticTags(); held = api.hold(old_path)
    context, page, api, console = await start(browser, base, report, api=api)
    try:
        await asyncio.wait_for(held['started'].wait(), 10)
        await page.get_by_test_id('tag-next-object').click()
        panel = page.locator('.object-tags')
        await expect(panel).to_have_attribute('data-tag-object', OTHER)
        await expect(panel).to_contain_text('#Summer')
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 10)
        await expect(panel.locator('.tag-chip')).to_have_count(1)
        assert any(row['path'] == old_path and 'ABORTED' in row['failure'] for row in api.aborted), api.aborted
        await capture(page, 'late-object-response-discarded.png')
        check(report, 'old_object_read_aborted_and_late_response_cannot_replace_new_object', aborted=api.aborted)
    finally: await finish(context, api, console, report)
    api = SyntheticTags(); held = api.hold(old_path)
    context, page, api, console = await start(browser, base, report, api=api)
    try:
        await asyncio.wait_for(held['started'].wait(), 10)
        await page.get_by_test_id('tag-company').click()
        await expect(page.locator('.object-tags')).to_contain_text('#Summer')
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 10)
        call = [row for row in api.calls if row['path'] == old_path][-1]
        assert call['company'] == '2'
        assert any(row['path'] == old_path and 'ABORTED' in row['failure'] for row in api.aborted)
        check(report, 'company_switch_aborts_read_and_uses_new_header', calls=[row for row in api.calls if row['path'] == old_path], aborted=api.aborted)
    finally: await finish(context, api, console, report)
    context, page, api, console = await start(browser, base, report)
    try:
        dialog = await editor(page)
        field = dialog.get_by_role('textbox', name='标签输入', exact=True)
        held = api.hold('/api/tags', '常用0')
        await field.fill('常用0'); await asyncio.wait_for(held['started'].wait(), 10)
        await field.fill('Summer')
        await expect(dialog.locator('.tag-options')).to_contain_text('#Summer')
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 10)
        await expect(dialog.locator('.tag-options .tag-chip')).to_have_count(1)
        await expect(dialog.locator('.tag-options')).not_to_contain_text('常用')
        assert any(row['path'] == '/api/tags' and 'ABORTED' in row['failure'] for row in api.aborted)
        before = await dialog.locator('.tag-selection li').count()
        await field.fill('中文输入')
        await field.evaluate("el => el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', isComposing:true, bubbles:true}))")
        await expect(dialog.locator('.tag-selection li')).to_have_count(before)
        await field.evaluate("el => el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:229, bubbles:true}))")
        await expect(dialog.locator('.tag-selection li')).to_have_count(before)
        await field.press('Enter')
        await expect(dialog.locator('.tag-selection li')).to_have_count(before + 1)
        await capture(page, 'tag-search-late-response-ime.png')
        check(report, 'debounced_options_abort_old_prefix_and_ignore_composing_enter', aborted=api.aborted, composing_added=0, real_enter_added=1)
    finally: await finish(context, api, console, report)
    context, page, api, console = await start(browser, base, report)
    try:
        dialog = await editor(page); await add(dialog, '旧身份未决输入')
        write_path = old_path + '/replace'; held = api.hold(write_path)
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 10)
        await page.get_by_test_id('tag-identity').click()
        await expect(dialog).not_to_be_visible()
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 10)
        await expect(page.locator('.object-tags')).not_to_contain_text('旧身份未决输入')
        await expect(page.locator('.el-message').filter(has_text='标签已保存')).to_have_count(0)
        assert any(row['method'] == 'POST' and row['path'] == write_path and 'ABORTED' in row['failure'] for row in api.aborted), api.aborted
        await capture(page, 'identity-changed-late-write-not-applied.png')
        check(report, 'identity_switch_cancels_write_and_discards_late_success', attempts=api.attempts, aborted=api.aborted)
    finally: await finish(context, api, console, report)


async def check_library(browser, base, report):
    context, page, api, console = await start(browser, base, report, '/tags', permissions=['biz:tag:list'])
    try:
        await expect(page.locator('.el-table__body-wrapper tbody tr')).to_have_count(100)
        await expect(page.locator('.bound-note')).to_contain_text('当前已达显示上限')
        assert await page.locator('.bound-note').evaluate('el => {const r=el.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight}')
        await expect(page.get_by_role('button', name='重命名', exact=True)).to_have_count(0)
        await expect(page.get_by_role('button', name='归档', exact=True)).to_have_count(0)
        assert api.calls[-1]['params']['size'] == ['100']
        await capture(page, 'library-bounded-readonly-100.png')
        check(report, 'library_bounded_100_readonly_no_fake_paging', rows=100, write_buttons=0)
    finally: await finish(context, api, console, report)
    context, page, api, console = await start(browser, base, report, '/tags')
    try:
        row = page.locator('.el-table__body-wrapper tbody tr').filter(has_text='##蓝')
        await row.get_by_role('button', name='重命名', exact=True).click()
        dialog = page.get_by_role('dialog', name='重命名公共标签', exact=True)
        field = dialog.get_by_role('textbox', name='标签新名称', exact=True)
        await expect(field).to_have_value('#蓝')
        await field.fill('#藏蓝')
        api.tags[ACTIVE].update(name='#深蓝', version=2)
        await dialog.get_by_role('button', name='保存名称', exact=True).click()
        await expect(dialog).to_contain_text('标签已被其他人修改')
        await expect(field).to_have_value('#藏蓝')
        await dialog.get_by_role('button', name='重新读取并核对', exact=True).click()
        await expect(dialog).to_contain_text('最新版本：##深蓝')
        await expect(field).to_have_value('#藏蓝')
        include = [call for call in api.calls if call['params'].get('includeIds')][-1]
        assert include['params'] == {'size': ['1'], 'includeIds': [ACTIVE]}
        await capture(page, 'library-rename-cas-reloaded.png')
        await dialog.get_by_role('button', name='保存名称', exact=True).click()
        await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['body'] == {'version': 2, 'name': '##藏蓝'}
        assert api.tags[ACTIVE]['name'] == '#藏蓝'
        check(report, 'library_rename_cas_explicit_reload_preserves_hash_name', rejected=api.writes[0], accepted=api.writes[-1], include_request=include)
        row = page.locator('.el-table__body-wrapper tbody tr').filter(has_text='##藏蓝')
        await row.get_by_role('button', name='记录', exact=True).click()
        history = page.get_by_role('dialog', name='标签记录 · ##藏蓝', exact=True)
        await expect(history.locator('.el-table__body-wrapper tbody tr')).to_have_count(20)
        await history.get_by_role('button', name='下一页', exact=True).click()
        await expect(history.locator('.el-table__body-wrapper tbody tr')).to_have_count(5)
        await expect(history).to_contain_text('合成标签记录 25')
        await capture(page, 'tag-history-page-two.png')
        await history.get_by_role('button', name='关闭', exact=True).click()
        api.faults[('GET', '/api/tags/' + ACTIVE + '/logs')] = [503]
        await row.get_by_role('button', name='记录', exact=True).click()
        await expect(history.get_by_role('alert')).to_contain_text('合成标签故障')
        await expect(history.locator('.el-table')).to_have_count(0)
        await history.get_by_role('button', name='重试', exact=True).click()
        await expect(history.locator('.el-table__body-wrapper tbody tr')).to_have_count(20)
        await history.get_by_role('button', name='关闭', exact=True).click()
        check(report, 'history_real_paging_25_rows_and_503_retry', pages=[20,5], fault_not_empty=True)
        post = '/api/tags/' + ACTIVE + '/archive'
        api.faults[('POST', post)] = [503]
        await row.get_by_role('button', name='归档', exact=True).click()
        await page.get_by_role('button', name='归档标签', exact=True).click()
        action = page.get_by_role('dialog', name='归档公共标签', exact=True)
        await expect(action).to_contain_text('结果还未确认')
        attempts = [call for call in api.attempts if call['path'] == post]
        assert len(attempts) == 1, attempts
        await action.get_by_role('button', name='重试原提交', exact=True).click()
        await expect(action).not_to_be_visible()
        attempts = [call for call in api.attempts if call['path'] == post]
        assert len(attempts) == 2 and attempts[0]['key'] == attempts[1]['key'] and attempts[0]['body'] == attempts[1]['body']
        assert api.tags[ACTIVE]['archived'] is True
        await expect(row).to_have_count(0)
        await capture(page, 'library-archive-manual-retry.png')
        check(report, 'archive_uncertain_result_manual_retry_reuses_exact_action', attempts=attempts, automatic_retries=0)
        row = page.locator('.el-table__body-wrapper tbody tr').filter(has_text='#Summer')
        await row.get_by_role('button', name='归档', exact=True).click()
        await expect(page.get_by_role('button', name='归档标签', exact=True)).to_be_visible()
        old_count = len(api.attempts)
        await page.get_by_test_id('tag-identity').click()
        await page.get_by_role('button', name='归档标签', exact=True).click()
        await expect(page.locator('.el-message-box')).not_to_be_visible()
        assert len(api.attempts) == old_count and api.tags[SUMMER]['archived'] is False
        check(report, 'late_archive_confirmation_after_identity_change_does_not_write', old_attempts=old_count, new_attempts=len(api.attempts))
    except BaseException:
        await capture(page, 'library-failure.png'); raise
    finally: await finish(context, api, console, report)


async def check_entries(browser, base, report):
    entries = [('STYLE','/styles/'+OBJECT,OBJECT),('MATERIAL','/materials/'+OBJECT,OBJECT),
        ('SUPPLIER','/suppliers/'+OBJECT,OBJECT),('ORDER','/orders/'+OBJECT,OBJECT),
        ('MRP_CALC','/mrp/'+OBJECT,OBJECT),('PURCHASE_ORDER','/purchases/'+OBJECT,OBJECT),
        ('INV_DOC','/stock-in/'+OBJECT,OBJECT),('INV_COUNT','/stock-takes/'+OBJECT,OBJECT),
        ('PRODUCTION_ORDER','/production/51','51'),('PRODUCTION_RECEIPT','/production/51?tab=receipts',RECEIPT),
        ('FG_DOC','/fg-in/'+DOC+'?companyId=1',DOC),('TECH_PACK','/styles/'+OBJECT+'?tab=techpack',OBJECT)]
    for kind, path, identifier in entries:
        api = SyntheticTags()
        if kind == 'PRODUCTION_RECEIPT': api.production.update(status='RUNNING', actions=['receive'])
        context, page, api, console = await start(browser, base, report, path, api=api)
        try:
            if kind == 'PRODUCTION_RECEIPT':
                await page.get_by_role('tab', name=re.compile('^回货')).click()
                await page.locator('.el-table__body-wrapper tbody tr').filter(has_text='HH-SYN-001').click()
                await expect(page.get_by_role('dialog', name='HH-SYN-001', exact=True)).to_be_visible()
            panel = page.locator('.object-tags[data-tag-type="'+kind+'"]')
            await expect(panel).to_have_attribute('data-tag-object', identifier)
            await expect(panel).to_contain_text('已归档')
            await panel.get_by_role('button', name='编辑标签', exact=True).click()
            dialog = page.get_by_role('dialog', name='编辑'+{'STYLE':'款式标签','TECH_PACK':'资料包标签'}.get(kind,'公共标签'), exact=True)
            await add(dialog, '入口验证'+kind)
            await dialog.get_by_role('button', name='保存标签', exact=True).click()
            await expect(dialog).not_to_be_visible()
            await expect(panel).to_contain_text('入口验证'+kind)
            write = api.writes[-1]
            assert write['path'] == '/api/tags/objects/'+kind+'/'+identifier+'/replace'
            assert set(write['body']) == {'version','names','retainIds','tagVersions'}
            if kind not in SHARED and kind != 'MRP_CALC': assert write['company'] == '1'
            wide = await tag_layout(page, panel, 1440)
            await capture(page, 'entry-'+kind.lower()+'.png')
            assert_tag_layout(wide)
            narrow = await tag_layout(page, panel, 1180)
            await capture(page, 'entry-'+kind.lower()+'-1180.png')
            assert_tag_layout(narrow)
            check(report, 'persisted_'+kind.lower()+'_actual_parent_entry', request=write, no_business_version_or_state_written=True, layout_1440=wide, layout_1180=narrow)
        except BaseException:
            await capture(page, 'entry-'+kind.lower()+'-failure.png'); raise
        finally: await finish(context, api, console, report)


async def check_validation(browser, base, report):
    context, page, api, console = await start(browser, base, report)
    try:
        dialog = await editor(page)
        for _ in range(3):
            await dialog.get_by_role('button', name=re.compile('^移除标签 ')).first.click()
        emoji = '🐱' * 64
        await add(dialog, emoji)
        await expect(dialog.locator('.tag-selection .tag-chip')).to_have_count(1)
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['body']['names'] == [emoji]
        await expect(page.locator('.object-tags .tag-chip')).to_have_text('#'+emoji)
        await capture(page, 'unicode-64-codepoints.png')
        check(report, '64_unicode_codepoints_128_utf16_preserved', codepoints=64, utf16_units=128, request=api.writes[-1])
        dialog = await editor(page)
        for raw, message in [('🐱'*65,'1 至 64'),('逗号,拒绝','逗号或控制字符'),('双向\u202e字符','逗号或控制字符')]:
            await add(dialog, raw)
            await expect(dialog.get_by_role('alert').filter(has_text=message)).to_have_count(1)
            await expect(dialog.locator('.tag-selection .tag-chip')).to_have_count(1)
        assert len(api.writes) == 1
        check(report, 'invalid_65_codepoints_comma_bidi_blocked_before_write', rejected_inputs=3, additional_writes=0)
        for i in range(29): await add(dialog, '上限验证'+str(i+1))
        await expect(dialog.locator('.tag-selection .tag-chip')).to_have_count(30)
        await add(dialog, '不能加入第31个')
        await expect(dialog.get_by_role('alert')).to_contain_text('最多 30 个标签')
        await expect(dialog.locator('.tag-selection .tag-chip')).to_have_count(30)
        await dialog.get_by_role('textbox', name='标签输入', exact=True).fill('')
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog).not_to_be_visible()
        assert len(api.writes[-1]['body']['names']) == 30
        check(report, 'object_30_raw_names_limit_with_no_31st_post', submitted_names=30)
    except BaseException:
        await capture(page, 'validation-failure.png'); raise
    finally: await finish(context, api, console, report)

    context, page, api, console = await start(browser, base, report)
    try:
        dialog = await editor(page); await add(dialog, '保留新输入')
        api.objects[('ORDER', OBJECT)] = {'version':5,'ids':[ARCHIVED,ACTIVE]}
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog).to_contain_text('标签已被其他人修改')
        await dialog.get_by_role('button', name='重新读取并核对', exact=True).click()
        await expect(dialog).to_contain_text('原关联已移除，请移除后重新选择')
        await expect(dialog).to_contain_text('保留新输入')
        await expect(dialog.get_by_role('button', name='确认当前选择并保存', exact=True)).to_be_disabled()
        await capture(page, 'removed-association-explicit-review.png')
        await dialog.get_by_role('button', name='移除标签 Summer', exact=True).click()
        await dialog.get_by_role('button', name='确认当前选择并保存', exact=True).click()
        await expect(dialog).not_to_be_visible()
        write = api.writes[-1]
        assert SUMMER not in write['body']['tagVersions'] and '#Summer' not in write['body']['names']
        assert write['body']['version'] == 5
        check(report, 'removed_association_cas_reload_needs_explicit_removal', accepted=write)
    except BaseException:
        await capture(page, 'removed-association-failure.png'); raise
    finally: await finish(context, api, console, report)
    api = SyntheticTags(); path='/api/tags/objects/ORDER/'+OBJECT
    api.faults[('GET',path)] = [503]
    context, page, api, console = await start(browser, base, report, api=api)
    try:
        panel = page.locator('.object-tags')
        await expect(panel.get_by_role('alert')).to_contain_text('合成标签故障')
        await expect(panel).not_to_contain_text('还没有公共标签')
        await panel.get_by_role('button', name='重试', exact=True).click()
        await expect(panel).to_contain_text('#Summer')
        dialog = await editor(page); await add(dialog, '未决原提交')
        api.faults[('POST',path+'/replace')] = [503]
        await dialog.get_by_role('button', name='保存标签', exact=True).click()
        await expect(dialog).to_contain_text('结果还未确认')
        await expect(dialog.get_by_role('textbox', name='标签输入', exact=True)).to_be_disabled()
        assert len(api.attempts) == 1
        await dialog.get_by_role('button', name='重试原提交', exact=True).click()
        await expect(dialog).not_to_be_visible()
        assert len(api.attempts) == 2 and api.attempts[0]['key'] == api.attempts[1]['key'] and api.attempts[0]['body'] == api.attempts[1]['body']
        check(report, 'read_503_retry_and_object_manual_replay_keep_original_action', attempts=api.attempts)
    finally: await finish(context, api, console, report)


async def check_states(browser, base, report):
    cases = ['supplier-admitting','order-approved','techpack-effective','style-disabled','receipt-voided','receipt-parent-closed','receipt-not-owner']
    for case in cases:
        api = SyntheticTags()
        if case == 'supplier-admitting':
            api.payloads['SUPPLIER']['status']='ADMITTING'; kind='SUPPLIER'; path='/suppliers/'+OBJECT
        elif case == 'order-approved':
            api.payloads['ORDER']['status']='APPROVED'; kind='ORDER'; path='/orders/'+OBJECT
        elif case in ['techpack-effective','style-disabled']:
            kind='TECH_PACK'; path='/styles/'+OBJECT+'?tab=techpack'
            if case == 'techpack-effective': api.payloads['TECH_PACK']['status']='EFFECTIVE'
            else: api.payloads['STYLE']['status']='DISABLED'
        else:
            kind='PRODUCTION_RECEIPT'; path='/production/51?tab=receipts'
            api.production.update(status='RUNNING',actions=['receive'])
            if case == 'receipt-voided': api.production_receipts[0]['status']='VOIDED'
            if case == 'receipt-parent-closed': api.production['status']='CLOSED'
            if case == 'receipt-not-owner': api.production.update(createBy='9007199254740995',merchId='9007199254740996')
        context,page,api,console=await start(browser,base,report,path,api=api)
        try:
            if kind == 'PRODUCTION_RECEIPT':
                await page.get_by_role('tab',name=re.compile('^回货')).click()
                await page.locator('.el-table__body-wrapper tbody tr').filter(has_text='HH-SYN-001').click()
            panel=page.locator('.object-tags[data-tag-type="'+kind+'"]')
            await expect(panel).to_contain_text('#Summer')
            await expect(panel.get_by_role('button',name='编辑标签',exact=True)).to_have_count(0)
            await expect(panel).to_contain_text('只读')
            assert not api.attempts
            if case in ['receipt-voided','style-disabled']:
                await panel.scroll_into_view_if_needed();await capture(page,'state-'+case+'.png')
            check(report,'actual_parent_readonly_'+case,kind=kind,write_attempts=0)
        except BaseException:
            await capture(page,'state-'+case+'-failure.png');raise
        finally:await finish(context,api,console,report)
    api=SyntheticTags();api.production.update(status='RUNNING',actions=['receive'])
    context,page,api,console=await start(browser,base,report,'/production/51',api=api)
    try:
        await page.get_by_role('button',name='登记回货',exact=True).click()
        dialog=page.get_by_role('dialog',name=re.compile('登记回货'))
        await expect(dialog).to_be_visible()
        await expect(dialog.locator('.object-tags')).to_have_count(0)
        assert not any('/objects/PRODUCTION_RECEIPT/' in call['path'] for call in api.calls)
        await capture(page,'new-receipt-no-persisted-tags.png')
        check(report,'new_receipt_form_never_targets_unpersisted_object',receipt_tag_requests=0)
    finally:await finish(context,api,console,report)

    permissions=['order:order:list','order:order:edit']
    context,page,api,console=await start(browser,base,report,permissions=permissions)
    try:
        dialog=await editor(page);await add(dialog,'无库权限仍可保存业务标签')
        await dialog.get_by_role('button',name='保存标签',exact=True).click()
        await expect(dialog).not_to_be_visible()
        assert not any(call['path']=='/api/tags' or call['path'].endswith('/logs') for call in api.calls)
        assert len(api.writes)==1
        check(report,'domain_writer_without_library_read_can_add_raw_name',request=api.writes[-1],option_requests=0)
    finally:await finish(context,api,console,report)


async def check_pack_log(browser, base, report):
    context,page,api,console=await start(browser,base,report,'/styles/'+OBJECT+'?tab=techpack')
    try:
        pack=page.locator('.tp-wrap')
        panel=pack.locator('.object-tags[data-tag-type="TECH_PACK"]')
        await expect(panel).to_contain_text('#Summer')
        await pack.get_by_role('tab',name='操作记录',exact=True).click()
        await expect(pack.locator('.doc-log-list')).to_contain_text('还没有操作记录')
        await panel.get_by_role('button',name='编辑标签',exact=True).click()
        dialog=page.get_by_role('dialog',name='编辑资料包标签',exact=True)
        await add(dialog,'标签修改应刷新当前记录')
        await dialog.get_by_role('button',name='保存标签',exact=True).click()
        await expect(dialog).not_to_be_visible()
        await expect(pack.locator('.doc-log-list')).to_contain_text('合成标签变更记录')
        await expect(pack.locator('.doc-log-list')).to_contain_text('修改标签')
        calls=[call for call in api.calls if call['path']=='/api/tech-packs/'+OBJECT+'/logs']
        assert len(calls)==2,calls
        await capture(page,'techpack-tag-log-refreshed.png')
        check(report,'techpack_tag_save_refreshes_already_open_domain_timeline',log_reads=len(calls))
    except BaseException:
        await capture(page,'techpack-tag-log-failure.png');raise
    finally:await finish(context,api,console,report)


async def check_labels(browser, base, report):
    context, page, api, console = await start(browser, base, report, '/styles/'+OBJECT+'?tab=techpack')
    try:
        panels = {kind: page.locator('.object-tags[data-tag-type="'+kind+'"]') for kind in ['STYLE', 'TECH_PACK']}
        titles = {'STYLE':'款式标签', 'TECH_PACK':'资料包标签'}
        hints = {'STYLE':'KS-TAG-SYN · 合成标签款式', 'TECH_PACK':'KS-TAG-SYN · 生产 V1'}
        for panel in panels.values(): await expect(panel).to_contain_text('#Summer')
        for width in [1440, 1180]:
            metrics = {}
            for kind, panel in panels.items():
                await panel.get_by_role('button', name='编辑标签', exact=True).click(trial=True)
                metrics[kind] = await tag_layout(page, panel, width)
                assert_tag_layout(metrics[kind])
            await capture(page, 'style-pack-labels-'+str(width)+'.png')
            report.setdefault('measurements', {})[str(width)] = metrics
            for kind, panel in panels.items():
                assert (await panel.locator('.panel-head > span').first.inner_text()).strip() == titles[kind], kind
                await expect(panel.locator('.object-hint')).to_have_text(hints[kind])
            check(report, 'same_page_distinct_object_labels_'+str(width), layout=metrics)
        for kind, panel in panels.items():
            await panel.get_by_role('button', name='编辑标签', exact=True).click()
            dialog = page.get_by_role('dialog', name='编辑'+titles[kind], exact=True)
            await expect(dialog.locator('.object-hint')).to_have_text(hints[kind])
            await add(dialog, '归属验证'+kind)
            await capture(page, kind.lower()+'-named-editor.png')
            await dialog.get_by_role('button', name='保存标签', exact=True).click()
            await expect(dialog).not_to_be_visible()
            await expect(panel).to_contain_text('归属验证'+kind)
            write = api.writes[-1]
            assert write['path'] == '/api/tags/objects/'+kind+'/'+OBJECT+'/replace', write
            assert set(write['body']) == {'version', 'names', 'retainIds', 'tagVersions'}, write
            check(report, 'named_editor_preserves_'+kind.lower()+'_api', request=write)
    except BaseException:
        await capture(page, 'labels-failure.png'); raise
    finally: await finish(context, api, console, report)
    context, page, api, console = await start(browser, base, report)
    try:
        panel=page.locator('.object-tags')
        await expect(panel.locator('.panel-head > span').first).to_have_text('公共标签')
        dialog=await editor(page)
        await expect(dialog.locator('.object-hint')).to_have_count(0)
        check(report, 'default_object_title_unchanged')
    finally: await finish(context, api, console, report)


async def check_filters(browser, base, report):
    context, page, api, console = await start(browser, base, report, '/styles')
    try:
        await expect(page.locator('.cards .card')).to_have_count(20)
        await expect(page.locator('.el-pagination__total')).to_contain_text('46')
        await page.locator('.pagination .btn-next').click()
        await expect(page.locator('.cards .code').first).to_have_text('KS-FILTER-021')
        await page.get_by_role('button', name='筛选', exact=True).click()
        dialog = page.get_by_role('dialog', name='筛选条件', exact=True)
        await expect(dialog.get_by_text('公共标签（同时包含）', exact=True)).to_be_visible()
        selector = dialog.get_by_role('combobox', name='公共标签筛选')
        await selector.click()
        await page.get_by_role('option', name='##蓝', exact=True).click()
        await page.get_by_role('option', name='#Summer', exact=True).click()
        await dialog.get_by_role('button', name='完成', exact=True).click()
        await expect(page.locator('.el-pagination__total')).to_contain_text('24')
        await expect(page.locator('.cards .code').first).to_have_text('KS-FILTER-001')
        await expect(page.locator('.cards .card')).to_have_count(20)
        selected = [call for call in api.calls if call['path'] == '/api/styles'][-1]
        assert set(selected['params']['tagIds']) == {ACTIVE, SUMMER}, selected
        assert selected['params']['page'] == ['1'], selected
        await page.locator('.pagination .btn-next').click()
        await expect(page.locator('.cards .card')).to_have_count(4)
        await expect(page.locator('.cards .code').last).to_have_text('KS-FILTER-024')
        await expect(page.locator('.el-pagination__total')).to_contain_text('24')
        next_page = [call for call in api.calls if call['path'] == '/api/styles'][-1]
        assert set(next_page['params']['tagIds']) == {ACTIVE, SUMMER} and next_page['params']['page'] == ['2']
        await capture(page, 'filter-and-second-page.png')
        check(report, 'business_list_two_tag_and_preserves_ids_total_and_pagination', first=selected, next=next_page,
              source='Actual StyleList + FilterBar + TagFilterItem + useTable; synthetic dataset')
        await page.get_by_role('button', name='去掉条件：公共标签（同时包含）', exact=True).click()
        await expect(page.locator('.el-pagination__total')).to_contain_text('46')
        await expect(page.locator('.cards .code').first).to_have_text('KS-FILTER-001')
        cleared = [call for call in api.calls if call['path'] == '/api/styles'][-1]
        assert 'tagIds' not in cleared['params'] and cleared['params']['page'] == ['1'], cleared
        check(report, 'clear_tag_filter_restores_query_and_first_page', request=cleared)
        await page.get_by_role('button', name='筛选', exact=True).click()
        await selector.click()
        await page.get_by_role('option', name=re.compile('已归档')).click()
        await dialog.get_by_role('button', name='完成', exact=True).click()
        await expect(page.locator('.cards .card')).to_have_count(5)
        await expect(page.locator('.el-pagination__total')).to_contain_text('5')
        await expect(page.locator('.cards .code').first).to_have_text('KS-FILTER-041')
        archived = [call for call in api.calls if call['path'] == '/api/styles'][-1]
        assert archived['params']['tagIds'] == [ARCHIVED]
        await expect(page.locator('.fchip')).to_contain_text('已归档 · ' + ARCHIVED)
        assert not api.writes
        await capture(page, 'filter-archived-history.png')
        check(report, 'archived_filter_uses_historical_id_without_creating_links', request=archived, writes=0)
    except BaseException:
        await capture(page, 'filter-failure.png'); raise
    finally: await finish(context, api, console, report)
    context, page, api, console = await start(browser, base, report, '/styles', permissions=['style:style:list'])
    try:
        await expect(page.locator('.cards .card')).to_have_count(20)
        await page.get_by_role('button', name='筛选', exact=True).click()
        await expect(page.get_by_role('combobox', name='公共标签筛选')).to_have_count(0)
        assert not any(call['path'].startswith('/api/tags') for call in api.calls), api.calls
        check(report, 'no_library_permission_hides_filter_and_never_requests_options')
    finally: await finish(context, api, console, report)


async def run(only):
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-tags'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['tags.html', 'TagsFixture.vue', 'TagsPanel.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'scope': 'Actual Vue tag components with explicitly synthetic HTTP; no backend/SQL validation',
              'checks': {}, 'page_errors': [], 'console_errors': [], 'expected_console_errors': []}
    commands = {'retention': check_retention, 'conflicts': check_conflicts, 'permissions': check_permissions,
                'races': check_races, 'library': check_library, 'entries': check_entries, 'validation': check_validation, 'states': check_states,
                'pack-log': check_pack_log, 'labels': check_labels, 'filters': check_filters}
    with (ARTIFACTS / 'vite.log').open('w') as log:
        process = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(port), '--strictPort'],
                                   cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None: raise RuntimeError('Vite exited')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close(); await writer.wait_closed(); break
                except OSError: await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'): options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                try:
                    for name, test in commands.items():
                        if only and name != only: continue
                        report['running'] = name
                        await test(browser, 'http://127.0.0.1:' + str(port), report)
                        print(json.dumps({'completed': name, 'checks': len(report['checks'])}), flush=True)
                finally: await browser.close()
        except BaseException as error:
            report['failure'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            fingerprints = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT / 'web/src').rglob('*')) if p.is_file()}
            report['source_sha256'] = hashlib.sha256(json.dumps(fingerprints, sort_keys=True).encode()).hexdigest()
            report['screenshots'] = sorted(p.name for p in ARTIFACTS.glob('*.png'))
            (ARTIFACTS / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert not report['page_errors'], report['page_errors']
    assert not report['console_errors'], report['console_errors']
    assert all(check['passed'] for check in report['checks'].values())


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--only', choices=['retention','conflicts','permissions','races','library','entries','validation','states','pack-log','labels','filters'])
    parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.artifacts: ARTIFACTS = args.artifacts.resolve()
    asyncio.run(run(args.only))
