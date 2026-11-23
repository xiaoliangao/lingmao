#!/usr/bin/env python3
"""FG actual Vue components/AppLayout, explicitly synthetic HTTP; no database claims."""
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
import sys
from urllib.parse import parse_qs, urlencode, urlsplit

from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/fg'
RECEIPT = '9007199254742001'
OTHER_RECEIPT = '9007199254742002'
DOC = '9007199254743001'
ORDER = '9007199254744001'
STAMP = '2026-11-21 10:00:00'


def style(large=False):
    sizes = [f'S{i:02}' for i in range(1, 52)] if large else ['M', 'L']
    colors = [{'id': '61', 'code': 'B', 'name': '蓝色', 'hex': '#3677a8'}, {'id': '62', 'code': 'W', 'name': '白色', 'hex': '#ffffff'}]
    skus = [{'id': str(9007199254746001 + c * len(sizes) + s), 'colorId': color['id'], 'sizeCode': size,
             'code': f'STY-SYN-{color["code"]}-{size}', 'status': 1}
            for c, color in enumerate(colors) for s, size in enumerate(sizes)]
    return {'styleId': '9007199254745001', 'code': 'STY-SYN', 'name': '合成棉衫', 'image': None, 'sizes': sizes, 'colors': colors, 'skus': skus}


def brief(sku, source):
    color = next(c for c in source['colors'] if c['id'] == sku['colorId'])
    return {'styleSkuId': sku['id'], 'skuCode': sku['code'], 'styleId': source['styleId'], 'styleCode': source['code'],
            'styleName': source['name'], 'colorId': color['id'], 'colorName': color['name'], 'colorHex': color['hex'], 'sizeCode': sku['sizeCode'], 'image': None}


def doc(doc_id=DOC, company='1', kind='IN', doc_type='RECEIPT_IN', *, large=False, price=True, audit=False):
    source = style(large)
    items = [{**brief(sku, source), 'id': str(701 + i), 'seq': i + 1, 'qty': 1 if large else [120, 120, 78, 78][i],
              'prdReceiptItemId': str(801 + i) if doc_type == 'RECEIPT_IN' else None,
              'orderItemId': str(901 + i) if doc_type == 'SHIP_OUT' else None, 'remark': None, 'stockQty': 20 if kind == 'OUT' else None,
              'maxQty': None, **({'price': None, 'amount': None} if price else {})}
             for i, sku in enumerate(source['skus'])]
    return {'id': doc_id, 'no': ('CC' if kind == 'OUT' else 'CR') + '-SYN-001', 'version': 7, 'status': 'DRAFT',
            'companyId': company, 'companyName': '合成主体' + ('甲' if company == '1' else '乙'), 'kind': kind, 'docType': doc_type,
            'warehouseId': '31', 'warehouseName': '合成成品仓', 'docDate': '2026-11-21', 'reason': 'INITIAL' if doc_type.startswith('OTHER') else None,
            'receipt': {'id': RECEIPT, 'no': 'HH-SYN-001', 'prdOrderId': '51', 'prdOrderNo': 'WF-SYN-001', 'prdOrderKind': 'OUTSOURCE',
                        'factoryName': '合成工厂', 'receiptDate': '2026-11-21'} if doc_type == 'RECEIPT_IN' else None,
            'order': {'id': ORDER, 'no': 'DD-SYN-001', 'deliveryReq': '合成收货处'} if doc_type == 'SHIP_OUT' else None,
            'shipTo': '合成收货处' if kind == 'OUT' else None, 'logisticsNo': None, 'remark': None, 'posted': False,
            'totalQty': sum(row['qty'] for row in items), **({'totalAmount': None} if price else {}), 'items': items, 'styles': [source],
            'createBy': '9007199254740993', 'createByName': '合成制单员', 'createTime': STAMP, 'updateTime': STAMP,
            'auditBy': None, 'auditByName': None, 'auditTime': None, 'approval': None, 'pendingNotices': 0,
            'actions': ['edit', 'remove'] + (['audit'] if audit else [])}


def doc_row(index, company='1', kind='IN'):
    return {'id': str(int(DOC) + index), 'no': f'{"CR" if kind == "IN" else "CC"}-SYN-{index + 1:03}', 'version': 7,
            'companyId': company, 'kind': kind, 'docType': 'RECEIPT_IN' if kind == 'IN' else 'SHIP_OUT', 'status': 'DRAFT',
            'warehouseId': '31', 'warehouseName': '合成成品仓', 'receiptId': RECEIPT if kind == 'IN' else None,
            'receiptNo': 'HH-SYN-001' if kind == 'IN' else None, 'prdOrderNo': 'WF-SYN-001', 'prdOrderKind': 'OUTSOURCE', 'factoryName': '合成工厂',
            'orderId': ORDER if kind == 'OUT' else None, 'orderNo': 'DD-SYN-001' if kind == 'OUT' else None, 'reason': None,
            'shipTo': None, 'logisticsNo': None, 'firstStyle': {'styleId': '9007199254745001', 'styleCode': 'STY-SYN', 'styleName': '合成棉衫', 'image': None},
            'styleCount': 1, 'totalQty': index + 1, 'totalAmount': None, 'docDate': '2026-11-21', 'remark': None,
            'createByName': '合成制单员', 'auditByName': None, 'createTime': STAMP, 'actions': ['audit']}


def production():
    source = style()
    head = doc()
    head.update({'id': '51', 'no': 'WF-SYN-001', 'kind': 'OUTSOURCE', 'status': 'RUNNING', 'actions': [],
        'planRevision': 1, 'factoryId': '3', 'factoryCode': 'GC-SYN', 'factoryName': '合成工厂', 'factoryShortName': '合成工厂',
        'factoryStatus': 'ACTIVE', 'processMode': 'CMT', 'orderId': ORDER, 'orderNo': 'DD-SYN-001',
        'styleId': source['styleId'], 'styleCode': source['code'], 'styleName': source['name'], 'aliasName': None, 'brand': None,
        'image': None, 'techPackId': '52', 'techPackVersion': 1, 'orderDate': '2026-11-01', 'dueDate': '2026-11-30',
        'expectDate': None, 'daysLeft': 9, 'priceSourceId': None, 'priceChangeReason': None, 'taxPct': 0, 'taxIncluded': True,
        'merchId': '9007199254740993', 'merchName': '合成跟单员', 'settleMethod': 'MONTHLY', 'terms': None, 'issueStatus': None,
        'returnedQty': 400, 'defectQty': 4, 'currentNodeName': None, 'risk': {'late': False, 'near': False, 'nodeLate': False, 'issue': False, 'upstream': False},
        'nearDays': 7, 'sizes': source['sizes'], 'nodes': [], 'openIssues': [], 'issueCount': 0, 'receiptCount': 1, 'stockDocCount': 0})
    head['items'] = [{**row, 'orderItemId': str(901+i), 'colorCode': source['colors'][i//2]['code'], 'returnedQty': 0, 'defectQty': 0,
                      'remainQty': row['qty'], 'maxReceiveQty': row['qty']} for i,row in enumerate(head['items'])]
    return head


def receipts():
    return [{'id': rid, 'no': 'HH-SYN-001' if i == 0 else 'HH-VOID-002', 'status': 'APPROVED' if i == 0 else 'VOIDED',
             'receiptDate': '2026-11-21', 'warehouseId': '31', 'warehouseName': '合成成品仓', 'logisticsNo': None,
             'remark': None, 'qty': 400, 'defectQty': 4, 'photos': [], 'createByName': '合成制单员', 'createTime': STAMP,
             'voidReason': '合成作废原因' if i else None, 'voidByName': '合成主管' if i else None, 'voidTime': STAMP if i else None,
             'items': [], 'actions': []} for i, rid in enumerate([RECEIPT, OTHER_RECEIPT])]


class SyntheticApi:
    def __init__(self, *, large=False, price=True, audit=False, mixed=False, receipt_edit=None, warehouse_fault=None):
        self.large, self.price, self.audit, self.mixed = large, price, audit, mixed
        self.receipt_edit = receipt_edit
        self.calls, self.failures, self.holds = [], {}, {}
        self.reserved = [120, 0, 0, 0] if receipt_edit == 'sparse' else [120, 120, 78, 78] if receipt_edit == 'full' else [0, 0, 0, 0]
        self.created = {}
        self.preview_count = 23
        self.warehouse_hold = self.hold('/api/warehouses/options', None) if warehouse_fault == 'hold' else None
        if warehouse_fault == '503': self.failures[('/api/warehouses/options', None)] = 503

    def hold(self, path, company='1'):
        value = {name: asyncio.Event() for name in ('started', 'release', 'finished')}
        self.holds[(path, company)] = value
        return value

    def pending(self, company):
        remaining = 396 - sum(self.reserved)
        return {'companyId': company, 'receiptId': RECEIPT, 'receiptNo': 'HH-SYN-001', 'prdOrderId': '51', 'prdOrderNo': 'WF-SYN-001',
                'prdOrderKind': 'OUTSOURCE', 'styleId': '9007199254745001', 'styleCode': 'STY-SYN', 'styleName': '合成棉衫', 'image': None,
                'factoryId': '3', 'factoryName': '合成工厂', 'qty': 400, 'defectQty': 4, 'goodQty': 396,
                'reservedQty': sum(self.reserved), 'postedQty': 0, 'remainingQty': remaining, 'stockedQty': sum(self.reserved), 'remainQty': remaining,
                'warehouseId': '31', 'warehouseName': '合成成品仓', 'receiptDate': '2026-11-21'}

    def prefill(self, receipt, company, doc_id=None):
        source = style()
        good = [120, 120, 78, 78] if receipt == RECEIPT else [2, 3, 4, 5]
        reserved = self.reserved if receipt == RECEIPT else [0, 0, 0, 0]
        return {'receiptId': receipt, 'receiptNo': 'HH-SYN-001' if receipt == RECEIPT else 'HH-SYN-002', 'companyId': company,
                'prdOrderNo': 'WF-SYN-001', 'prdOrderKind': 'OUTSOURCE', 'factoryName': '合成工厂', 'receiptDate': '2026-11-21',
                'warehouseId': '31', 'warehouseName': '合成成品仓', 'style': source,
                'items': [{'prdReceiptItemId': str(801 + i), 'styleSkuId': sku['id'], 'goodQty': good[i], 'stockedQty': reserved[i],
                           'reservedQty': reserved[i], 'postedQty': 0, 'remainingQty': good[i] - reserved[i], 'maxQty': good[i] - reserved[i] + (120 if doc_id and self.receipt_edit and i == 0 else 0)}
                          for i, sku in enumerate(source['skus'])]}

    async def route(self, route):
        request = route.request
        url = urlsplit(request.url)
        path, params = url.path, parse_qs(url.query)
        if not path.startswith('/api/'):
            await route.continue_(); return
        company = request.headers.get('x-company-id')
        data_body = request.post_data_json if request.method in ['POST', 'PUT'] and request.post_data else None
        call = {'path': path, 'method': request.method, 'params': params, 'company': company,
                'idempotency_key': request.headers.get('idempotency-key'), 'request_id': request.headers.get('x-request-id'), 'body': data_body}
        self.calls.append(call)
        if path.startswith(('/api/fg-docs', '/api/fg-stock')):
            assert company in ['1', '2'], call
            if request.method == 'GET' or request.method == 'DELETE' or (request.method == 'POST' and path != '/api/fg-docs'):
                assert params.get('companyId') == [company], call
        held = self.holds.pop((path, company), None)
        if held:
            held['started'].set()
            await asyncio.wait_for(held['release'].wait(), 20)
        try:
            failure = self.failures.get((path, company))
            if path == '/api/fg-stocks/qty' and self.failures.get('second-qty') and len(params.get('skuIds', [])) < 100:
                failure = 503
            if failure:
                await route.fulfill(status=failure, json={'code': failure, 'msg': '合成读取故障', 'data': None}, headers={'X-Request-Id': 'synthetic-fg-fault'})
                return
            if path == '/api/version': data = {'version': 'synthetic-fg', 'buildTime': None}
            elif path.startswith('/api/dict-data/by-type/'):
                data = [{'value': 'INITIAL', 'label': '期初', 'status': 1}] if path.endswith(('fg_in_reason', 'fg_out_reason')) else []
            elif path == '/api/companies/options': data = [{'id': '1', 'code': 'C-1', 'shortName': '合成主体甲'}, {'id': '2', 'code': 'C-2', 'shortName': '合成主体乙'}]
            elif re.fullmatch(r'/api/tags/objects/FG_DOC/\d+', path):
                # Public tags add one legitimate read to the actual FG detail. Keep this
                # fixture projection explicit; all other unknown routes still fail.
                selected = path.rsplit('/', 1)[1]
                known = {DOC, DOC[:-1] + '9', str(int(DOC) + 19), *self.created}
                assert request.method == 'GET' and not params and selected in known, call
                assert company in ['1', '2'], call
                if selected in self.created: assert self.created[selected]['companyId'] == company, call
                data = {'version': 0, 'tags': []}
            elif path == '/api/warehouses/options': data = [{'id': '31', 'code': 'FG-SYN', 'name': '合成成品仓', 'type': 'PRODUCT', 'factoryId': None}]
            elif path == '/api/categories/tree': data = []
            elif path == '/api/styles/pick':
                data = {'list': [{'id': '9007199254745001', 'code': 'STY-SYN', 'name': '合成棉衫', 'status': 'ACTIVE', 'aliasName': None,
                    'brand': '', 'categoryId': '11', 'categoryName': '上装', 'year': 2026, 'season': '', 'tagPrice': None,
                    'techPackStatus': 'NONE', 'image': None, 'colors': style()['colors']}], 'total': 1}
            elif path == '/api/production-orders/51': data = production()
            elif path == '/api/production-orders/51/receipts': data = receipts()
            elif path == '/api/production-orders/51/materials': data = {'rows': [], 'docs': []}
            elif path.startswith('/api/production-orders/51/'): data = []
            elif path == '/api/order-kitting': data = [{'orderId': ORDER, 'status': 'NONE', 'calcs': []}]
            elif path == '/api/board/home/kpi': data = {'dev': None, 'order': None, 'production': None, 'material': None}
            elif path == '/api/board/home/deliveries': data = []
            elif path == '/api/board/home/todos':
                assert params.get('companyId') == ['1'] and company == '1'
                data = {'groups': [], 'canSeeAll': False, 'counts': [{'key': 'FG_PENDING', 'count': 1, 'companyId': company}]}
            elif path == '/api/fg-docs/pending-receipts':
                row = self.pending(company); data = {'list': [row] if row['remainingQty'] > 0 else [], 'total': 1 if row['remainingQty'] > 0 else 0}
            elif re.fullmatch(r'/api/fg-docs/from-receipt/\d+', path):
                receipt = path.rsplit('/', 1)[1]
                if request.method == 'GET': data = self.prefill(receipt, company, params.get('docId', [None])[0])
                else:
                    assert data_body['post'] is False and data_body['companyId'] == company
                    assert data_body['clientActionId'] == call['idempotency_key']
                    new_id = str(int(DOC) + len(self.created))
                    document = doc(new_id, company)
                    for line in data_body['items']:
                        idx = int(line['prdReceiptItemId']) - 801; self.reserved[idx] += line['qty']
                        document['items'][idx]['qty'] = line['qty']
                    document['totalQty'] = sum(line['qty'] for line in data_body['items'])
                    document['version'] = 1
                    self.created[new_id] = document
                    data = {'id': new_id, 'no': document['no'], 'version': 1, 'status': 'DRAFT'}
            elif path == '/api/fg-docs/by-receipts':
                ids = params.get('receiptIds', []); assert len(ids) <= 100
                data = [{'receiptId': rid, 'documents': [{'id': str(int(DOC) + i), 'no': f'CR-SYN-{i + 1:03}', 'status': 'DRAFT', 'qty': 10 if i else 80} for i in range(min(20, self.preview_count))],
                         'documentsTotal': self.preview_count, 'hasMoreDocuments': self.preview_count > 20,
                         'reservedQty': 300, 'postedQty': 0, 'remainingQty': 96} for rid in ids]
                for row in data:
                    if row['receiptId'] == OTHER_RECEIPT:
                        row.update({'documents': [], 'documentsTotal': 0, 'hasMoreDocuments': False, 'reservedQty': 0, 'postedQty': 0, 'remainingQty': 0})
            elif path == '/api/fg-docs/style-options':
                assert len(params.get('styleIds', [])) <= 100; data = [style(self.large)]
            elif path == '/api/fg-docs/ship-orders':
                data = [{'id': ORDER, 'no': 'DD-SYN-001', 'companyId': company, 'orderType': 'CMT', 'status': 'APPROVED', 'statusLabel': '已审核',
                         'styleCodes': 'STY-SYN', 'totalQty': 396, 'shippedQty': 0, 'stockQty': 396}]
            elif path == '/api/fg-docs/ship-prefill':
                source = style(); data = {'orderId': ORDER, 'orderNo': 'DD-SYN-001', 'companyId': company, 'deliveryReq': '合成收货处', 'styles': [source],
                    'lines': [{'orderItemId': str(901 + i), 'styleSkuId': sku['id'], 'orderQty': [120, 120, 78, 78][i], 'shippedQty': 0, 'maxQty': [123, 123, 80, 80][i],
                               'stockQty': 200, **({'price': None} if self.price else {})} for i, sku in enumerate(source['skus'])]}
            elif path == '/api/fg-docs':
                if request.method == 'POST':
                    assert data_body['companyId'] == company
                    document = doc(DOC, company, 'IN' if data_body['docType'] == 'OTHER_IN' else 'OUT', data_body['docType'])
                    by_sku = {row['styleSkuId']: row for row in document['items']}
                    document['items'] = [{**by_sku[row['styleSkuId']], 'qty': row['qty']} for row in data_body['items']]
                    document['totalQty'] = sum(row['qty'] for row in data_body['items'])
                    document['version'] = 1
                    self.created[DOC] = document
                    data = {'id': DOC, 'no': document['no'], 'version': 1, 'status': 'DRAFT'}
                else:
                    page, size = int(params.get('page', ['1'])[0]), int(params.get('size', ['20'])[0])
                    assert 1 <= size <= 100
                    count = 23 if params.get('receiptId') else 56
                    kind = params.get('kind', ['IN'])[0]
                    start = (page - 1) * size
                    data = {'list': [doc_row(i, company, kind) for i in range(start, min(start + size, count))], 'total': count}
            elif re.fullmatch(r'/api/fg-docs/\d+', path):
                selected = path.rsplit('/', 1)[1]
                if request.method == 'GET':
                    data = deepcopy(self.created.get(selected) or doc(selected, company, 'OUT' if self.large or selected.endswith('9') else 'IN',
                        'OTHER_OUT' if self.large else 'SHIP_OUT' if selected.endswith('9') else 'RECEIPT_IN', large=self.large, price=self.price, audit=self.audit))
                    if self.mixed and data['items']: data['items'][0]['amount'] = 0.14
                    if self.receipt_edit:
                        data['items'] = data['items'][:1]; data['totalQty'] = 120; data['styles'][0]['skus'] = data['styles'][0]['skus'][:1]
                else: data = 8
            elif re.fullmatch(r'/api/fg-docs/\d+/(audit|unaudit|void)', path): data = None
            elif re.fullmatch(r'/api/fg-docs/\d+/(attachments|logs|references)', path): data = []
            elif path == '/api/fg-stocks/qty':
                ids = params.get('skuIds', []); assert len(ids) <= 100
                data = [{'companyId': company, 'warehouseId': params['warehouseId'][0], 'styleSkuId': sku, 'qty': 17 if company == '1' else 29} for sku in ids]
            elif path == '/api/fg-stocks':
                source = style(); data = {'list': [{**brief(source['skus'][0], source), 'id': '9007199254747001', 'companyId': company, 'warehouseId': '31',
                    'warehouseName': '合成成品仓', 'qty': 12 if company == '1' else 7, 'updateTime': STAMP}], 'total': 1}
            elif path == '/api/fg-stocks/by-style':
                source = style(); data = {'list': [{**source, 'companyId': company, 'totalQty': 12, 'qtys': {source['skus'][0]['id']: 12}}], 'total': 1}
            elif path == '/api/fg-stock-logs':
                assert 'page' not in params and 'offset' not in params
                start = int(params.get('cursor', ['0'])[0]); size = int(params.get('size', ['50'])[0]); stop = min(start + size, 125)
                source = style(); data = {'list': [{**brief(source['skus'][0], source), 'id': str(9007199254748001 + i), 'companyId': company,
                    'warehouseId': '31', 'warehouseName': '合成成品仓', 'locationId': '0', 'docKind': 'IN', 'docId': DOC, 'docNo': f'CR-LOG-{i + 1:03}',
                    'changeType': 'RECEIPT_IN', 'bizDate': '2026-11-21', 'changeQty': 1, 'balanceQty': 125 - i, 'createTime': STAMP,
                    'createByName': '合成仓管', 'reversalOfLogId': None, 'requestId': f'synthetic-log-{i + 1}'} for i in range(start, stop)],
                    'hasMore': stop < 125, 'nextCursor': str(stop) if stop < 125 else None}
            else: raise AssertionError('Unexpected synthetic API ' + request.method + ' ' + path)
            await route.fulfill(json={'code': 0, 'msg': '', 'data': data}, headers={'X-Request-Id': 'synthetic-fg-response'})
        finally:
            if held: held['finished'].set()


async def start_page(browser, base, report, path='/fg-in?companyId=1', **api_options):
    context = await browser.new_context(viewport={'width': 1440, 'height': 1000}, timezone_id='America/Los_Angeles')
    api = SyntheticApi(**api_options)
    aborted, console = [], []
    await context.route('**/api/**', api.route)
    page = await context.new_page()
    page.set_default_timeout(12000)
    page.on('pageerror', lambda error: report['page_errors'].append(str(error)))
    page.on('requestfailed', lambda request: aborted.append({'path': urlsplit(request.url).path, 'query': urlsplit(request.url).query, 'failure': request.failure}))
    page.on('console', lambda message: console.append({'text': message.text, 'path': urlsplit(message.location.get('url', '')).path}) if message.type == 'error' else None)
    query = urlencode({'path': path, 'price': 'yes' if api.price else 'no'})
    await page.goto(base + '/node_modules/.s3-fg/fg.html?' + query)
    await page.locator('.shell-main').wait_for()
    return context, page, api, aborted, console


async def finish(context, report, console):
    if sys.exc_info()[0] and context.pages:
        await context.pages[0].screenshot(path=str(ARTIFACTS / (report.get('running', 'fixture') + '-failure.png')), animations='disabled')
    expected = [row for row in console if row['path'].startswith('/api/') and re.search(r'\b(403|404|503)\b', row['text'])]
    report['expected_console_errors'].extend(expected)
    report['console_errors'].extend([row for row in console if row not in expected])
    await context.close()


async def capture(page, name):
    await expect(page.locator('.el-loading-mask:visible')).to_have_count(0)
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
    await page.screenshot(path=str(ARTIFACTS / name), animations='disabled')


async def choose_company(page, label, name):
    await page.get_by_role('combobox', name=label, exact=True).click()
    await page.get_by_role('option', name=re.compile('^' + re.escape(name))).click()


def check(report, name, **details): report['checks'][name] = {'passed': True, **details}


async def assert_matrix_totals(matrix, rows, columns, grand):
    await expect(matrix.locator('tbody .total-col')).to_have_text([str(value) for value in rows])
    await expect(matrix.locator('tfoot td:not(:first-child):not(:last-child)')).to_have_text([str(value) for value in columns])
    await expect(matrix.locator('tfoot .grand')).to_have_text(str(grand))
    return {'rows': rows, 'columns': columns, 'grand': grand}


async def check_partial(browser, base, report):
    context, page, api, _, console = await start_page(browser, base, report)
    try:
        await page.get_by_role('button', name='入库', exact=True).click()
        dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
        await expect(dialog.locator('.sku-matrix .grand')).to_have_text('396')
        for label, qty in [('蓝色 M', 100), ('蓝色 L', 100), ('白色 M', 50), ('白色 L', 50)]:
            await dialog.get_by_role('textbox', name=label + ' 件数', exact=True).fill(str(qty))
        await expect(dialog.locator('.sku-matrix .grand')).to_have_text('300')
        await capture(page, 'receipt-first-300.png')
        await dialog.get_by_role('button', name='存草稿', exact=True).click()
        await expect(page.get_by_test_id('route')).to_have_text('/fg-in/' + DOC + '?companyId=1')
        await expect(page.locator('.detail .sub')).to_contain_text('300 件')
        await expect(page.get_by_role('button', name='审核过账', exact=True)).to_have_count(0)
        await page.locator('.page-header:visible .back').click()
        await page.get_by_role('tab', name='待入库', exact=True).click()
        await page.get_by_role('button', name='入库', exact=True).click()
        dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
        await expect(dialog.locator('.sku-matrix .grand')).to_have_text('96')
        await expect(dialog).to_contain_text('草稿占用 100')
        await capture(page, 'receipt-remainder-96.png')
        await dialog.get_by_role('button', name='存草稿', exact=True).click()
        await expect(page.get_by_test_id('route')).to_have_text('/fg-in/' + str(int(DOC) + 1) + '?companyId=1')
        posts = [call for call in api.calls if call['method'] == 'POST']
        assert len(posts) == 2 and [sum(line['qty'] for line in call['body']['items']) for call in posts] == [300, 96]
        assert all(call['body']['post'] is False and call['body']['clientActionId'] == call['idempotency_key'] and call['request_id'] for call in posts)
        assert all(re.fullmatch(r'\d{4}-\d{2}-\d{2}', call['body']['docDate']) for call in posts)
        check(report, 'partial_receipt_300_then_96_draft', quantities=[300, 96], bodies=posts, self_audit_hidden=True, timezone='America/Los_Angeles')
    finally: await finish(context, report, console)


async def check_company_and_seek(browser, base, report):
    context, page, api, aborted, console = await start_page(browser, base, report, '/fg-stock?companyId=1')
    try:
        row = page.locator('.el-table:visible .el-table__body-wrapper tbody tr').first
        await expect(row.locator('td').nth(5)).to_have_text('12')
        held = api.hold('/api/fg-stocks', '1')
        await page.locator('.flt-keyword:visible input, input[aria-label="款号、款名、SKU 码"], input[aria-label="单号、回货单号、生产单号、款号"]').first.press('Enter')
        await asyncio.wait_for(held['started'].wait(), 5)
        await choose_company(page, '成品库存主体', '合成主体乙')
        await expect(row.locator('td').nth(5)).to_have_text('7')
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await expect(row.locator('td').nth(5)).to_have_text('7')
        assert any(item['path'] == '/api/fg-stocks' for item in aborted), aborted
        await capture(page, 'stock-company-b.png')
        check(report, 'company_switch_discards_late_stock', same_sku=style()['skus'][0]['id'], quantities={'1': 12, '2': 7}, aborted=aborted)
        await row.click()
        dialog = page.get_by_role('dialog', name=re.compile('库存日志'))
        await expect(dialog.get_by_role('button', name='继续加载', exact=True)).to_be_enabled()
        await dialog.get_by_role('button', name='继续加载', exact=True).click()
        await expect(dialog.locator('.el-table__body-wrapper tbody tr').first).to_contain_text('CR-LOG-051')
        await dialog.get_by_role('button', name='继续加载', exact=True).click()
        await expect(dialog.locator('.el-table__body-wrapper tbody tr')).to_have_count(25)
        await expect(dialog.locator('.el-table__body-wrapper tbody tr').first).to_contain_text('CR-LOG-101')
        await expect(dialog.get_by_role('button', name='继续加载', exact=True)).to_be_disabled()
        await capture(page, 'cell-seek-third-page.png')
        await dialog.locator('.el-dialog__headerbtn').click()
        await page.locator('.el-segmented__item').filter(has_text='日志').click()
        for first in ['CR-LOG-001', 'CR-LOG-051']:
            await expect(page.locator('.el-table:visible .el-table__body-wrapper tbody tr').first).to_contain_text(first)
            await page.get_by_role('button', name='下一页', exact=True).click()
        await expect(page.locator('.el-table:visible .el-table__body-wrapper tbody tr')).to_have_count(25)
        await expect(page.locator('.el-table:visible .el-table__body-wrapper tbody tr').first).to_contain_text('CR-LOG-101')
        await capture(page, 'stock-seek-third-page.png')
        calls = [call for call in api.calls if call['path'] == '/api/fg-stock-logs']
        assert all('page' not in call['params'] and call['company'] == '2' for call in calls)
        assert [call['params'].get('cursor', ['0'])[0] for call in calls] == ['0', '50', '100', '0', '50', '100'], calls
        check(report, 'main_and_cell_seek_over_100', shown_rows=25, total_fixture_rows=125, cursors=['0', '50', '100'], calls=calls)
    finally: await finish(context, report, console)


async def check_error_and_selection(browser, base, report):
    context, page, api, _, console = await start_page(browser, base, report, '/fg-in?tab=docs&companyId=1')
    try:
        rows = page.locator('.el-table:visible .el-table__body-wrapper tbody tr')
        await expect(rows).to_have_count(20)
        await rows.first.locator('.el-checkbox').click()
        await expect(page.get_by_role('toolbar', name='批量操作')).to_be_visible()
        await page.locator('.el-pagination:visible .btn-next').click()
        await expect(rows.first).to_contain_text('CR-SYN-021')
        await expect(page.get_by_role('toolbar', name='批量操作')).not_to_be_visible()
        assert await rows.locator('.el-checkbox.is-checked').count() == 0
        await rows.first.locator('.el-checkbox').click()
        await choose_company(page, '成品主体', '合成主体乙')
        await expect(rows.first).to_contain_text('CR-SYN-001')
        assert await rows.locator('.el-checkbox.is-checked').count() == 0
        assert not [call for call in api.calls if call['method'] != 'GET']
        check(report, 'current_page_selection_clears', page_one=20, page_two_start='CR-SYN-021', company_changed='2', writes=0)
        api.failures[('/api/fg-docs', '2')] = 404
        await page.locator('.flt-keyword:visible input, input[aria-label="款号、款名、SKU 码"], input[aria-label="单号、回货单号、生产单号、款号"]').first.press('Enter')
        await expect(page.get_by_role('alert')).to_contain_text('无权限')
        await expect(page.locator('.el-table:visible')).to_have_count(0)
        await capture(page, 'docs-forbidden-state.png')
        api.failures.clear()
        await page.get_by_role('button', name='重试', exact=True).click()
        await expect(rows).to_have_count(20)
        check(report, 'list_404_is_failure_and_retry_recovers', empty_state_not_used=True, recovered_rows=20)
    finally: await finish(context, report, console)


async def check_grouped_and_receive_races(browser, base, report):
    context, page, api, aborted, console = await start_page(browser, base, report, '/fg-panels')
    try:
        await expect(page.locator('.fg-receipt-link')).to_have_count(20)
        await expect(page.get_by_role('link', name='查看全部 23 张', exact=True)).to_have_attribute('href', '/fg-in?tab=docs&receiptId=' + RECEIPT + '&companyId=1')
        assert all('companyId=1' in href for href in [await link.get_attribute('href') for link in await page.locator('.fg-receipt-link').all()])
        await expect(page.locator('.fg-receipt-links')).to_contain_text('已过账 0 · 草稿 300 · 剩余 96')
        await capture(page, 'receipt-multiple-documents-preview.png')
        await page.get_by_role('link', name='查看全部 23 张', exact=True).click()
        await expect(page.locator('.el-table:visible .el-table__body-wrapper tbody tr')).to_have_count(20)
        await page.locator('.el-pagination:visible .btn-next').click()
        await expect(page.locator('.el-table:visible .el-table__body-wrapper tbody tr')).to_have_count(3)
        call = [c for c in api.calls if c['path'] == '/api/fg-docs'][-1]
        assert call['params'].get('receiptId') == [RECEIPT] and call['params']['page'] == ['2']
        check(report, 'receipt_groups_preview_and_all_pages', preview=20, total=23, last_page=3, scoped_query=call)
    finally: await finish(context, report, console)
    context, page, api, aborted, console = await start_page(browser, base, report, '/fg-panels')
    try:
        held = api.hold('/api/fg-docs/from-receipt/' + RECEIPT)
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        # Prop change while modal is open is a parent transition, not a click through its backdrop.
        await page.get_by_test_id('second-receipt').evaluate('(button) => button.click()')
        dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-002', exact=True)
        await expect(dialog.locator('.sku-matrix .grand')).to_have_text('14')
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await expect(dialog.locator('.sku-matrix .grand')).to_have_text('14')
        await capture(page, 'receipt-late-source-discarded.png')
        assert any(item['path'].endswith(RECEIPT) for item in aborted), aborted
        await page.get_by_test_id('close-receipt').evaluate('(button) => button.click()')
        await expect(dialog).not_to_be_visible()
        held = api.hold('/api/fg-docs/from-receipt/' + RECEIPT)
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        await page.get_by_test_id('next-identity').click()
        await expect(page.get_by_role('dialog')).not_to_be_visible()
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await expect(page.get_by_role('dialog')).not_to_be_visible()
        check(report, 'receive_source_and_identity_cancel', old_source=RECEIPT, final_source=OTHER_RECEIPT, final_qty=14, aborted=aborted)
    finally: await finish(context, report, console)


async def check_large_matrix_and_limit(browser, base, report):
    context, page, api, _, console = await start_page(browser, base, report, '/fg-out/' + DOC + '?companyId=1', large=True)
    try:
        await page.get_by_role('button', name='编辑', exact=True).click()
        dialog = page.get_by_role('dialog', name='编辑 CC-SYN-001（其他出库）', exact=True)
        await expect(dialog.locator('.sku-matrix .sub')).to_have_count(102)
        calls = [c for c in api.calls if c['path'] == '/api/fg-stocks/qty']
        assert [len(c['params']['skuIds']) for c in calls] == [100, 2]
        await dialog.locator('.sku-matrix').evaluate('(el) => {el.scrollLeft = el.scrollWidth}')
        await capture(page, 'large-matrix-102-skus.png')
        await dialog.get_by_role('button', name='取消', exact=True).click()
        api.failures['second-qty'] = True
        await page.get_by_role('button', name='编辑', exact=True).click()
        await expect(dialog.get_by_role('alert')).to_contain_text('读取故障')
        await expect(dialog.locator('.sku-matrix .sub')).to_have_count(0)
        await capture(page, 'large-matrix-failed-batch-unknown.png')
        api.failures.clear()
        await dialog.get_by_role('button', name='重试', exact=True).click()
        await expect(dialog.locator('.sku-matrix .sub')).to_have_count(102)
        check(report, 'sku_batches_complete_before_publish', sku_count=102, batch_sizes=[100, 2], partial_failure_subtitles=0, restored_subtitles=102)
        rejected = await page.evaluate("""async () => {
          const {fgDocApi,fgStockApi} = await import('/src/api/fg.ts');
          const ids=Array.from({length:101},(_,i)=>String(9000+i));
          const result=[];
          for (const fn of [()=>fgDocApi.byReceipts(ids,{companyId:'1'}),()=>fgDocApi.styleOptions(ids,{companyId:'1'}),()=>fgStockApi.qty('31',ids,{companyId:'1'})]) {
            try {await fn(); result.push({accepted:true})} catch(error) {result.push({code:error.code,message:error.message})}
          }
          return result;
        }""")
        assert len(rejected) == 3 and all(row.get('code') == 400 for row in rejected)
        check(report, 'batch_101_rejected_before_transport', rejections=rejected)
    finally: await finish(context, report, console)


async def check_detail_and_write(browser, base, report):
    context, page, api, _, console = await start_page(browser, base, report, '/fg-in/' + DOC)
    try:
        await expect(page.get_by_text('先选择主体，再读取这张成品单。')).to_be_visible()
        assert not [c for c in api.calls if c['path'] == '/api/fg-docs/' + DOC]
        await capture(page, 'deep-link-company-required.png')
        await choose_company(page, '单据主体', '合成主体乙')
        await expect(page.locator('.doc-props')).to_contain_text('合成主体乙')
        await expect(page.get_by_test_id('route')).to_have_text('/fg-in/' + DOC + '?companyId=2')
        check(report, 'deep_link_explicit_company', before_selection_requests=0, selected_company='2', id=DOC)
    finally: await finish(context, report, console)
    context, page, api, _, console = await start_page(browser, base, report, '/fg-in/' + DOC + '?companyId=1', audit=True)
    try:
        await page.get_by_role('button', name='审核过账', exact=True).click()
        await page.get_by_role('button', name='审核', exact=True).click()
        await expect(page.locator('.el-message--success')).to_be_visible()
        call = next(c for c in api.calls if c['method'] == 'POST')
        assert call['body'] == {'version': 7, 'reason': None} and call['company'] == '1' and call['idempotency_key'] and call['request_id']
        check(report, 'action_version_idempotency_and_company', request=call)
    finally: await finish(context, report, console)
    for allowed, mixed in [(False, False), (True, False), (True, True)]:
        context, page, api, _, console = await start_page(browser, base, report, '/fg-out/' + DOC[:-1] + '9?companyId=1', price=allowed, mixed=mixed)
        try:
            await expect(page.locator('.detail .block-head')).to_contain_text('396 件')
            money = page.locator('.detail .block-head .num.dim')
            if allowed: await expect(money).to_have_text('—')
            else: await expect(money).to_have_count(0)
            assert '0.00' not in await page.locator('.detail .block-head').inner_text()
            await expect(page.locator('.doc-props a[href^="/orders/"]')).to_have_count(0)
            await capture(page, ('price-mixed-null.png' if mixed else 'price-null.png' if allowed else 'price-omitted.png'))
        finally: await finish(context, report, console)
    check(report, 'price_permission_and_unknown_preserved', unauthorized='no money element', allowed_unknown='—', mixed_amounts=[0.14, None, None, None], mixed_subtotal='—', source_full_detail_link=False)


async def check_receipt_edit(browser, base, report):
    for mode in ['sparse', 'full']:
        context, page, api, _, console = await start_page(browser, base, report, '/fg-in/' + DOC + '?companyId=1', receipt_edit=mode)
        try:
            await expect(page.locator('.detail .sku-matrix tbody .cell')).to_have_count(1)
            await page.get_by_role('button', name='编辑', exact=True).click()
            dialog = page.get_by_role('dialog', name='编辑 CR-SYN-001（回货入库）', exact=True)
            await expect(dialog.locator('.qty-input')).to_have_count(4)
            await expect(dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('120')
            await expect(dialog.get_by_role('textbox', name='蓝色 L 件数', exact=True)).to_have_value('')
            await expect(dialog.locator('.sku-matrix .grand')).to_have_text('120')
            prefill = [call for call in api.calls if call['path'].endswith('/from-receipt/' + RECEIPT)][-1]
            assert prefill['params']['docId'] == [DOC] and prefill['company'] == '1'
            if mode == 'sparse':
                await dialog.get_by_role('textbox', name='蓝色 L 件数', exact=True).fill('5')
                await expect(dialog.locator('.sku-matrix .grand')).to_have_text('125')
            else:
                await expect(dialog.locator('.cell.bad')).to_have_count(0)
                await expect(dialog).to_contain_text('草稿占用 120')
            await capture(page, f'receipt-edit-{mode}.png')
            await dialog.get_by_role('button', name='保存', exact=True).click()
            await expect(dialog).not_to_be_visible()
            saved = next(call for call in api.calls if call['method'] == 'PUT')
            quantities = [line['qty'] for line in saved['body']['items']]
            assert quantities == ([120, 5] if mode == 'sparse' else [120]), saved
            assert saved['body']['version'] == 7 and saved['body']['companyId'] == '1'
            check(report, 'receipt_edit_' + mode, actual_detail_skus=1, editing_source_skus=4,
                  initial_total=120, global_remaining=0 if mode == 'full' else 276, saved_quantities=quantities, request=saved)
        finally: await finish(context, report, console)


async def check_manual_forms(browser, base, report):
    context, page, api, _, console = await start_page(browser, base, report, '/fg-in?tab=docs&companyId=1')
    try:
        await page.get_by_role('button', name='其他入库', exact=True).click()
        dialog = page.get_by_role('dialog', name='新建其他入库', exact=True)
        reason = dialog.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').filter(has_text=re.compile('^原因$')))
        await reason.locator('.el-select__wrapper').click()
        await page.get_by_role('option', name='期初', exact=True).click()
        await dialog.get_by_role('button', name='选款式', exact=True).click()
        picker = page.get_by_role('dialog', name='从款式库选', exact=True)
        await picker.locator('.el-table__body-wrapper tbody tr').first.click()
        await picker.get_by_role('button', name='加入 1 条', exact=True).click()
        quantity = dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)
        await quantity.fill('99999999')
        await expect(dialog.locator('.sku-matrix .grand')).to_have_text('99999999')
        width = await quantity.evaluate("""input => {const s=getComputedStyle(input),canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');ctx.font=s.font;return {available:input.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight),textWidth:ctx.measureText(input.value).width,scrollWidth:input.scrollWidth,clientWidth:input.clientWidth,scrollLeft:input.scrollLeft}}""")
        assert width['textWidth'] <= width['available'] and width['scrollWidth'] <= width['clientWidth'] and width['scrollLeft'] == 0, width
        await quantity.fill('100000000')
        await expect(quantity).to_have_value('99999999')
        await capture(page, 'manual-other-in-eight-digits.png')
        await quantity.fill('10')
        await expect(dialog.locator('.sku-matrix .grand')).to_have_text('10')
        await assert_matrix_totals(dialog.locator('.sku-matrix'), [10, 0], [10, 0], 10)
        await capture(page, 'manual-other-in.png')
        held = api.hold('/api/fg-docs')
        await dialog.get_by_role('button', name='存草稿', exact=True).evaluate('(button) => {button.click();button.click()}')
        await asyncio.wait_for(held['started'].wait(), 5)
        assert len([call for call in api.calls if call['method'] == 'POST']) == 1
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await expect(page.get_by_test_id('route')).to_have_text('/fg-in/' + DOC + '?companyId=1')
        created = next(call for call in api.calls if call['method'] == 'POST')
        assert created['body']['docType'] == 'OTHER_IN' and created['body']['reason'] == 'INITIAL'
        assert created['body']['items'] == [{'styleSkuId': style()['skus'][0]['id'], 'qty': 10, 'remark': None}]
        check(report, 'manual_other_in_picker_and_mutex', request=created, calls_after_double_click=1, accepted_quantity=99999999, rejected_quantity=100000000, eight_digit_width=width)
    finally: await finish(context, report, console)
    context, page, api, _, console = await start_page(browser, base, report, '/fg-out?companyId=1', price=False)
    try:
        await page.get_by_role('button', name='发货出库', exact=True).click()
        dialog = page.get_by_role('dialog', name='新建发货出库', exact=True)
        order = dialog.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').filter(has_text=re.compile('^需求订单$')))
        await order.locator('.el-select__wrapper').click()
        await page.get_by_role('option', name=re.compile('^DD-SYN-001')).click()
        await expect(dialog.locator('.qty-input')).to_have_count(4)
        await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('12')
        await assert_matrix_totals(dialog.locator('.sku-matrix'), [12, 0], [12, 0], 12)
        company = dialog.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').filter(has_text=re.compile('^主体$')))
        await company.locator('.el-select__wrapper').click()
        await page.get_by_role('option', name=re.compile('^合成主体乙')).click()
        await expect(dialog.locator('.qty-input')).to_have_count(0)
        await order.locator('.el-select__wrapper').click()
        await page.get_by_role('option', name=re.compile('^DD-SYN-001')).click()
        await expect(dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('')
        await assert_matrix_totals(dialog.locator('.sku-matrix'), [0, 0], [0, 0], 0)
        await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('10')
        totals = await assert_matrix_totals(dialog.locator('.sku-matrix'), [10, 0], [10, 0], 10)
        await capture(page, 'manual-ship-company-b.png')
        report['manual_ship_matrix_totals'] = totals
        await dialog.get_by_role('button', name='存草稿', exact=True).click()
        await expect(page.get_by_test_id('route')).to_have_text('/fg-out/' + DOC + '?companyId=2')
        created = next(call for call in api.calls if call['method'] == 'POST')
        assert created['company'] == '2' and created['body']['companyId'] == '2' and created['body']['orderId'] == ORDER
        assert created['body']['docType'] == 'SHIP_OUT' and 'totalAmount' not in created['body']
        assert all('price' not in row and 'amount' not in row for row in created['body']['items'])
        assert created['body']['items'][0]['qty'] == 10
        check(report, 'manual_ship_company_reset_and_no_client_money', request=created, old_company_quantity=12, saved_company_quantity=10)
    finally: await finish(context, report, console)


async def check_palette(browser, base, report):
    context, page, api, aborted, console = await start_page(browser, base, report, '/fg-stock?companyId=1')
    try:
        await page.keyboard.press('Control+k')
        palette = page.get_by_role('dialog', name='快捷跳转', exact=True)
        await palette.get_by_role('combobox', name='搜索页面、单号、款号、物料、供应商').fill('CR')
        await expect(palette.get_by_role('option')).to_have_count(10)
        before = len(api.calls)
        await palette.get_by_role('combobox', name='搜索页面、单号、款号、物料、供应商').dispatch_event('keydown', {'key':'Enter','isComposing':True,'keyCode':229})
        await expect(palette).to_be_visible()
        await choose_company(page, '成品搜索主体', '合成主体乙')
        await expect(palette.get_by_role('option')).to_have_count(10)
        await palette.get_by_role('option').first.click()
        await expect(page.get_by_test_id('route')).to_have_text('/fg-in/' + DOC + '?companyId=2')
        calls = [c for c in api.calls[before:] if c['path'] == '/api/fg-docs']
        assert calls and all(c['company'] == '2' and c['params']['size'] == ['5'] for c in calls)
        check(report, 'command_palette_company_and_ime', company='2', results_per_kind=5, composing_kept_open=True)
    finally: await finish(context, report, console)


async def check_warehouse_options(browser, base, report):
    context, page, api, _, console = await start_page(browser, base, report, '/fg-in?tab=docs&companyId=1', warehouse_fault='503')
    try:
        alert = page.get_by_role('alert').filter(has_text='仓库选项')
        try: await expect(alert).to_contain_text('读取故障', timeout=3000)
        except AssertionError: pass
        shown = await alert.is_visible()
        report['checks']['warehouse_option_failure_visible_and_retry'] = {'passed': shown, 'failure_visible': shown, 'requested': [row for row in api.calls if row['path'] == '/api/warehouses/options']}
        await capture(page, 'warehouse-options-failure.png')
        if not shown: return
        api.failures.clear()
        await alert.get_by_role('button', name='重试', exact=True).click()
        await expect(alert).to_have_count(0)
        await page.get_by_role('button', name='筛选', exact=True).click()
        panel = page.get_by_role('dialog', name='筛选条件', exact=True)
        warehouse = panel.locator('.filter-item').filter(has=page.locator('.label').filter(has_text=re.compile('^仓库$')))
        await warehouse.locator('.el-select__wrapper').click()
        await expect(page.get_by_role('option', name='合成成品仓', exact=True)).to_be_visible()
        await page.get_by_role('option', name='合成成品仓', exact=True).click()
        await capture(page, 'warehouse-options-recovered.png')
        report['checks']['warehouse_option_failure_visible_and_retry'].update({'recovered_option': '合成成品仓', 'request_count': len([row for row in api.calls if row['path'] == '/api/warehouses/options'])})
    finally: await finish(context, report, console)
    context, page, api, aborted, console = await start_page(browser, base, report, '/fg-in?tab=docs&companyId=1', warehouse_fault='hold')
    try:
        held = api.warehouse_hold
        await asyncio.wait_for(held['started'].wait(), 5)
        await page.get_by_test_id('next-identity').click()
        api.failures[('/api/warehouses/options', None)] = 503
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await expect(page.get_by_role('alert').filter(has_text='仓库选项')).to_have_count(0)
        await expect(page.get_by_role('status').filter(has_text='仓库选项')).to_have_count(0)
        assert any(row['path'] == '/api/warehouses/options' for row in aborted), aborted
        check(report, 'warehouse_late_failure_after_identity_cancel_discarded', aborted=aborted, stale_error_shown=False)
    finally: await finish(context, report, console)


async def check_upstream(browser, base, report):
    context, page, api, _, console = await start_page(browser, base, report, '/production/51?tab=receipts')
    try:
        pane = page.get_by_role('tabpanel', name='回货 1', exact=True)
        approved = pane.locator('.el-table__body-wrapper tbody tr').filter(has_text='HH-SYN-001')
        voided = pane.locator('.el-table__body-wrapper tbody tr').filter(has_text='HH-VOID-002')
        await expect(approved.locator('.fg-receipt-link')).to_have_count(20)
        await expect(voided).to_contain_text('已作废')
        await expect(voided.locator('.fg-receipt-links')).to_contain_text('已过账 0 · 草稿 0 · 剩余 0')
        await expect(voided.locator('.fg-receipt-links')).to_contain_text('暂无可见入库单')
        assert '待入库' not in await voided.inner_text()
        assert not await voided.get_by_role('button', name='入库', exact=True).count()
        call = [c for c in api.calls if c['path'] == '/api/fg-docs/by-receipts'][-1]
        assert call['params']['receiptIds'] == [RECEIPT, OTHER_RECEIPT]
        await pane.locator('.el-table__body-wrapper .el-scrollbar__wrap').evaluate('(el) => {el.scrollLeft=el.scrollWidth}')
        await capture(page, 'production-approved-voided-receipts.png')
        preview = approved.get_by_role('region', name='相关入库单预览')
        await preview.evaluate('(el) => {el.scrollTop=el.scrollHeight}')
        last = approved.locator('.fg-receipt-link').last
        await expect(last).to_contain_text('CR-SYN-020')
        reachable = await last.evaluate("""el => {const r=el.getBoundingClientRect(),hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2); return !!hit&&el.contains(hit)}""")
        assert reachable
        for width in [1440, 1180]:
            await page.set_viewport_size({'width': width, 'height': 1000})
            await page.evaluate("window.scrollTo(0, window.scrollY); document.querySelector('.shell-main').scrollLeft=0")
            await pane.locator('.el-table__body-wrapper .el-scrollbar__wrap').evaluate('(el) => {el.scrollLeft=el.scrollWidth}')
            await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
            widths = await approved.locator('td').last.locator('.cell').evaluate("""cell => {const s=getComputedStyle(cell),tag=cell.firstElementChild,r=tag.getBoundingClientRect();return {available:cell.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight),tag:r.width,scrollWidth:tag.scrollWidth,clientWidth:tag.clientWidth,text:tag.textContent.trim()}}""")
            geometry = await approved.evaluate("""row => {
                const metric=el=>{const r=el.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,unobscured:!!hit&&el.contains(hit),insideViewport:r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight}};
                const root=document.documentElement,main=document.querySelector('.shell-main'),detail=document.querySelector('.page.detail'),body=detail.querySelector('.body'),table=row.closest('.el-table'),wrap=table.querySelector('.el-table__body-wrapper .el-scrollbar__wrap');
                return {viewport:innerWidth,document:{clientWidth:root.clientWidth,scrollWidth:root.scrollWidth},main:{clientWidth:main.clientWidth,scrollWidth:main.scrollWidth,scrollLeft:main.scrollLeft},table:metric(table),body:metric(body),gridColumns:getComputedStyle(detail).gridTemplateColumns,
                    tableScroll:{clientWidth:wrap.clientWidth,scrollWidth:wrap.scrollWidth,scrollLeft:wrap.scrollLeft},lastLink:metric([...row.querySelectorAll('.fg-receipt-link')].at(-1)),status:metric(row.lastElementChild.querySelector('.cell').firstElementChild)};
            }""")
            passed = widths['text'] == '已登记' and widths['tag'] <= widths['available'] + .5 and widths['scrollWidth'] <= widths['clientWidth'] + 1
            passed = passed and geometry['document']['scrollWidth'] <= geometry['document']['clientWidth'] + 1 and geometry['main']['scrollWidth'] <= geometry['main']['clientWidth'] + 1
            passed = passed and all(geometry[key]['insideViewport'] and geometry[key]['unobscured'] for key in ['lastLink', 'status'])
            report['checks'][f'production_receipt_layout_{width}'] = {'passed': passed, 'status_width': widths, 'geometry': geometry}
            await capture(page, f'production-receipt-preview-{width}-last.png')
            assert passed, report['checks'][f'production_receipt_layout_{width}']
        await last.click()
        await expect(page.get_by_test_id('route')).to_have_text('/fg-in/' + str(int(DOC) + 19) + '?companyId=1')
        await page.locator('.ptab a[href="/production/51"]').click()
        api.failures[('/api/fg-docs/by-receipts', '1')] = 503
        # Re-activate the actual production detail, whose own onActivated refreshes subsidiaries.
        await page.locator('.side').get_by_text('成品库存', exact=True).click()
        await page.locator('.ptab a[href="/production/51"]').click()
        await expect(approved.get_by_role('alert')).to_contain_text('读取故障')
        assert not await approved.locator('.fg-receipt-link').count()
        api.failures.clear()
        await approved.get_by_role('button', name='重试', exact=True).click()
        await expect(approved.locator('.fg-receipt-link')).to_have_count(20)
        check(report, 'actual_production_mixed_receipt_status_and_retry', states=['APPROVED', 'VOIDED'], voided_remaining=0, batch=call, failure_not_zero=True, preview_last_link_reachable=True, narrow_viewport=1180, status_width=widths)
    finally: await finish(context, report, console)
    context, page, api, _, console = await start_page(browser, base, report, '/home')
    try:
        await page.get_by_role('button', name=re.compile('回货待入成品仓')).click()
        await expect(page.get_by_test_id('route')).to_have_text('/fg-in?tab=pending&companyId=1')
        call = next(c for c in api.calls if c['path'] == '/api/board/home/todos')
        check(report, 'home_count_company_snapshot_navigation', request=call, route='/fg-in?tab=pending&companyId=1')
    finally: await finish(context, report, console)


async def run(only):
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'web/node_modules/.s3-fg'; fixture.mkdir(parents=True, exist_ok=True)
    for name in ['fg.html', 'FgFixture.vue', 'FgPanels.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'scope': 'Actual FG Vue components, AppLayout, HTTP boundary faults; synthetic API only, no database validation',
              'checks': {}, 'page_errors': [], 'console_errors': [], 'expected_console_errors': []}
    commands = {'partial': check_partial, 'stock': check_company_and_seek, 'selection': check_error_and_selection,
                'source': check_grouped_and_receive_races, 'batch': check_large_matrix_and_limit, 'detail': check_detail_and_write, 'palette': check_palette,
                'upstream': check_upstream, 'receipt-edit': check_receipt_edit, 'forms': check_manual_forms, 'warehouse': check_warehouse_options}
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
                        await test(browser, f'http://127.0.0.1:{port}', report)
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
            source_files = sorted((ROOT / 'web/src').rglob('*'))
            fingerprints = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files if p.is_file()}
            report['source_sha256'] = hashlib.sha256(json.dumps(fingerprints, sort_keys=True).encode()).hexdigest()
            report['screenshots'] = sorted(p.name for p in ARTIFACTS.glob('*.png'))
            (ARTIFACTS / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert not report['page_errors'], report['page_errors']
    assert not report['console_errors'], report['console_errors']
    assert all(row['passed'] for row in report['checks'].values())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--only', choices=['partial', 'stock', 'selection', 'source', 'batch', 'detail', 'palette', 'upstream', 'receipt-edit', 'forms', 'warehouse'])
    parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.artifacts: ARTIFACTS = args.artifacts.resolve()
    asyncio.run(run(args.only))
