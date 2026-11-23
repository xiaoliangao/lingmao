#!/usr/bin/env python3
"""Persist/reload actual FG dialog drafts; HTTP metadata is explicitly synthetic."""
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
from urllib.parse import parse_qs, urlsplit

from playwright.async_api import async_playwright, expect
import fg

ROOT = Path(__file__).resolve().parents[3]


class DraftApi(fg.SyntheticApi):
    missing_sku = False

    def prefill(self, receipt, company, doc_id=None):
        result = super().prefill(fg.RECEIPT, company, doc_id)
        result['receiptId'] = receipt
        result['receiptNo'] = 'HH-SYN-001' if receipt == fg.RECEIPT else 'HH-SYN-002'
        return result

    async def route(self, route):
        request = route.request
        url = urlsplit(request.url)
        if self.missing_sku and url.path == '/api/fg-docs/style-options':
            self.calls.append({'path': url.path, 'method': request.method, 'params': parse_qs(url.query), 'company': request.headers.get('x-company-id')})
            source = fg.style(); source['skus'] = source['skus'][1:]
            await route.fulfill(json={'code': 0, 'msg': '', 'data': [source]})
            return
        await super().route(route)


async def persisted(page, component):
    await page.wait_for_function("""component => Object.keys(localStorage).some(key => key.startsWith('lingmao-draft:') && key.includes(component) && JSON.parse(localStorage[key]).data)""", arg=component)
    return await page.evaluate("""component => Object.keys(localStorage).filter(key => key.startsWith('lingmao-draft:') && key.includes(component)).map(key => ({key, ...JSON.parse(localStorage[key])}))""", component)


async def reload(page):
    page.once('dialog', lambda dialog: dialog.accept())
    await page.reload()
    await page.locator('.shell-main').wait_for()


async def restore_offer(page):
    box = page.locator('.el-message-box').filter(has_text='找到这份表单未保存的草稿')
    await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
    shown = await box.is_visible()
    if shown:
        await box.get_by_role('button', name='恢复草稿', exact=True).click()
        await expect(box).not_to_be_visible()
    return shown


async def receipt_scope(browser, base, report, artifacts):
    context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-panels')
    try:
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
        await expect(dialog.locator('.grand')).to_have_text('396')
        await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('10')
        records = await persisted(page, 'ReceiveDialog')
        await reload(page)
        await page.get_by_role('button', name='换第二回货', exact=True).click()
        other = page.get_by_role('dialog', name='回货入库 · HH-SYN-002', exact=True)
        await expect(other.locator('.grand')).to_have_text('396')
        offered = await restore_offer(page)
        quantity = await other.get_by_role('textbox', name='蓝色 M 件数', exact=True).input_value()
        await fg.capture(page, 'receipt-other-source-draft.png')
        report['checks']['receipt_draft_source_isolation'] = {'passed': not offered and quantity == '120', 'old_source': fg.RECEIPT,
            'new_source': fg.OTHER_RECEIPT, 'same_company': '1', 'same_sku': fg.style()['skus'][0]['id'], 'same_warehouse': '31',
            'same_initial_quantity': 120, 'old_local_draft_quantity': 10, 'restore_offer_for_wrong_source': offered, 'actual_new_source_quantity': quantity,
            'draft_records': records}
        assert not [call for call in api.calls if call['method'] != 'GET']
    finally: await fg.finish(context, report, console)


async def manual_restore(browser, base, report, artifacts):
    context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-in?tab=docs&companyId=1')
    try:
        await page.get_by_role('button', name='其他入库', exact=True).click()
        dialog = page.get_by_role('dialog', name='新建其他入库', exact=True)
        await dialog.get_by_role('button', name='选款式', exact=True).click()
        picker = page.get_by_role('dialog', name='从款式库选', exact=True)
        await picker.locator('.el-table__body-wrapper tbody tr').first.click()
        await picker.get_by_role('button', name='加入 1 条', exact=True).click()
        await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
        await expect(dialog.locator('.grand')).to_have_text('17')
        records = await persisted(page, 'FgDocFormDialog')
        await reload(page)
        before = len(api.calls)
        await page.get_by_role('button', name='其他入库', exact=True).click()
        await expect(dialog.get_by_text('还没有款式：点「选款式」', exact=True)).to_be_visible()
        offered = await restore_offer(page)
        inputs = dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)
        quantity = await inputs.input_value() if await inputs.count() else None
        totals = await fg.assert_matrix_totals(dialog.locator('.sku-matrix'), [17, 0], [17, 0], 17) if quantity == '17' else None
        await fg.capture(page, 'manual-restored-style-and-quantity.png')
        report['checks']['manual_draft_restores_metadata_and_quantities'] = {'passed': offered and quantity == '17',
            'restore_offer': offered, 'saved_quantity': 17, 'actual_quantity': quantity, 'actual_matrix_count': await dialog.locator('.sku-matrix').count(),
            'metadata_reloaded': any(call['path'] == '/api/fg-docs/style-options' for call in api.calls[before:]), 'draft_records': records, 'matrix_totals': totals}
        assert not [call for call in api.calls if call['method'] != 'GET']
    finally: await fg.finish(context, report, console)


async def prepare_receipt(page):
    await page.get_by_role('button', name='打开第一回货', exact=True).click()
    dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
    await expect(dialog.locator('.grand')).to_have_text('396')
    await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
    await persisted(page, 'ReceiveDialog')
    await reload(page)


async def receipt_contexts(browser, base, report, artifacts):
    for mode in ['company', 'identity']:
        context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-panels')
        try:
            await prepare_receipt(page)
            if mode == 'company': await fg.choose_company(page, '测试主体', '合成主体乙')
            else: await page.get_by_test_id('next-identity').click()
            await page.get_by_role('button', name='打开第一回货', exact=True).click()
            dialog = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
            await expect(dialog.locator('.grand')).to_have_text('396')
            assert not await restore_offer(page)
            await expect(dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('120')
            fg.check(report, 'receipt_draft_' + mode + '_isolation', original_quantity=17, actual_quantity=120, wrong_scope_offer=False)
            assert not [call for call in api.calls if call['method'] != 'GET']
        finally: await fg.finish(context, report, console)
    context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-in/' + fg.DOC + '?companyId=1')
    try:
        await page.get_by_role('button', name='编辑', exact=True).click()
        dialog = page.get_by_role('dialog', name='编辑 CR-SYN-001（回货入库）', exact=True)
        await expect(dialog.locator('.grand')).to_have_text('396')
        await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
        records = await persisted(page, 'ReceiveDialog')
        api.created[fg.DOC] = fg.doc(); api.created[fg.DOC]['version'] = 8
        await reload(page)
        await page.get_by_role('button', name='编辑', exact=True).click()
        await expect(dialog.locator('.grand')).to_have_text('396')
        assert not await restore_offer(page)
        await expect(dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('120')
        fg.check(report, 'receipt_draft_version_isolation', old_version=7, current_version=8, original_quantity=17, actual_quantity=120, old_keys=[row['key'] for row in records])
    finally: await fg.finish(context, report, console)


async def receipt_late_confirm(browser, base, report, artifacts):
    context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-panels')
    try:
        await prepare_receipt(page)
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        box = page.locator('.el-message-box').filter(has_text='找到这份表单未保存的草稿')
        await expect(box).to_be_visible()
        # The real parent replaces the source while an asynchronous confirmation is pending.
        await page.get_by_test_id('second-receipt').evaluate('(button) => button.click()')
        other = page.get_by_role('dialog', name='回货入库 · HH-SYN-002', exact=True)
        await expect(other.locator('.grand')).to_have_text('396')
        await box.get_by_role('button', name='恢复草稿', exact=True).click()
        await expect(box).not_to_be_visible()
        await expect(other.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('120')
        await fg.capture(page, 'receipt-late-confirm-discarded.png')
        await reload(page)
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        same = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
        await expect(same.locator('.grand')).to_have_text('396')
        assert await restore_offer(page)
        await expect(same.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('17')
        fg.check(report, 'receipt_late_confirm_rejects_changed_source_and_preserves_original', changed_source_quantity=120, restored_original_quantity=17)
    finally: await fg.finish(context, report, console)


async def prepare_manual(page):
    await page.get_by_role('button', name='其他入库', exact=True).click()
    dialog = page.get_by_role('dialog', name='新建其他入库', exact=True)
    await dialog.get_by_role('button', name='选款式', exact=True).click()
    picker = page.get_by_role('dialog', name='从款式库选', exact=True)
    await picker.locator('.el-table__body-wrapper tbody tr').first.click()
    await picker.get_by_role('button', name='加入 1 条', exact=True).click()
    await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
    await persisted(page, 'FgDocFormDialog')
    await reload(page)
    await page.get_by_role('button', name='其他入库', exact=True).click()
    await expect(page.locator('.el-message-box').filter(has_text='找到这份表单未保存的草稿')).to_be_visible()
    return dialog


async def manual_failures(browser, base, report, artifacts):
    for mode in ['missing-sku', '503']:
        context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-in?tab=docs&companyId=1')
        try:
            dialog = await prepare_manual(page)
            if mode == 'missing-sku': api.missing_sku = True
            else: api.failures[('/api/fg-docs/style-options', '1')] = 503
            assert await restore_offer(page)
            alert = dialog.get_by_role('alert')
            await expect(alert).to_contain_text('已不可用的色码' if mode == 'missing-sku' else '读取故障')
            await expect(dialog.locator('.sku-matrix')).to_have_count(0)
            await expect(dialog.get_by_role('button', name='存草稿', exact=True)).to_be_disabled()
            records = await persisted(page, 'FgDocFormDialog')
            assert records[0]['data']['blocks'][0]['qtys'][fg.style()['skus'][0]['id']] == 17
            await fg.capture(page, f'manual-draft-{mode}-retained.png')
            api.missing_sku = False; api.failures.clear()
            await alert.get_by_role('button', name='重试', exact=True).click()
            assert await restore_offer(page)
            await expect(dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('17')
            await expect(alert).to_have_count(0)
            assert not [call for call in api.calls if call['method'] != 'GET']
            fg.check(report, 'manual_draft_' + mode + '_retains_all_data_and_retry', lost_rows=0, partial_matrix_count=0, recovered_quantity=17)
        finally: await fg.finish(context, report, console)


async def cancellations(browser, base, report, artifacts):
    context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-in?tab=docs&companyId=1')
    try:
        dialog = await prepare_manual(page)
        held = api.hold('/api/fg-docs/style-options', '1')
        assert await restore_offer(page)
        await asyncio.wait_for(held['started'].wait(), 5)
        await dialog.get_by_role('button', name='取消', exact=True).click()
        await expect(dialog).not_to_be_visible()
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        await expect(dialog).not_to_be_visible()
        assert any(row['path'] == '/api/fg-docs/style-options' for row in aborted), aborted
        fg.check(report, 'manual_restore_close_aborts_metadata_and_discards_late_response', aborted=aborted)
    finally: await fg.finish(context, report, console)


async def ship_restore(browser, base, report, artifacts):
    context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-out?companyId=1')
    try:
        await page.get_by_role('button', name='发货出库', exact=True).click()
        dialog = page.get_by_role('dialog', name='新建发货出库', exact=True)
        order = dialog.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').filter(has_text=re.compile('^需求订单$')))
        await order.locator('.el-select__wrapper').click()
        await page.get_by_role('option', name=re.compile('^DD-SYN-001')).click()
        await dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
        await persisted(page, 'FgDocFormDialog')
        await reload(page)
        before = len(api.calls)
        await page.get_by_role('button', name='发货出库', exact=True).click()
        await expect(page.locator('.el-message-box').filter(has_text='找到这份表单未保存的草稿')).to_be_visible()
        assert await restore_offer(page)
        await expect(dialog.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('17')
        await expect(order).to_contain_text('DD-SYN-001')
        await expect(dialog.locator('.qty-input')).to_have_count(4)
        totals = await fg.assert_matrix_totals(dialog.locator('.sku-matrix'), [17, 0], [17, 0], 17)
        reads = [call for call in api.calls[before:] if call['path'] == '/api/fg-docs/ship-prefill']
        assert len(reads) == 1 and reads[0]['company'] == '1' and reads[0]['params']['orderId'] == [fg.ORDER], reads
        assert not [call for call in api.calls if call['method'] != 'GET']
        await fg.capture(page, 'ship-draft-refetched-source.png')
        fg.check(report, 'ship_draft_refetches_source_and_restores_quantities', quantity=17, source_skus=4, request=reads[0], matrix_totals=totals)
    finally: await fg.finish(context, report, console)


async def warehouse_cancellation(browser, base, report, artifacts):
    context, page, api, aborted, console = await fg.start_page(browser, base, report, '/fg-panels')
    try:
        held = api.hold('/api/warehouses/options', None)
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        dialog = page.get_by_role('dialog', name='回货入库', exact=True)
        await dialog.get_by_role('button', name='取消', exact=True).click()
        await expect(dialog).not_to_be_visible()
        held['release'].set(); await asyncio.wait_for(held['finished'].wait(), 5)
        assert any(row['path'] == '/api/warehouses/options' for row in aborted), aborted
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        await expect(page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True).locator('.grand')).to_have_text('396')
        fg.check(report, 'warehouse_options_abort_on_close_and_fresh_reopen', aborted=aborted, reopened_quantity=396)
    finally: await fg.finish(context, report, console)


async def late_discard_confirmation(browser, base, report, artifacts):
    for mode in ['close', 'route']:
        context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-panels')
        try:
            await page.get_by_role('button', name='打开第一回货', exact=True).click()
            original = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
            await expect(original.locator('.grand')).to_have_text('396')
            await original.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('17')
            await persisted(page, 'ReceiveDialog')
            if mode == 'close': await original.locator('.el-dialog__headerbtn').click()
            else: await page.get_by_test_id('leave-receipt').evaluate('(button) => button.click()')
            box = page.locator('.el-message-box').filter(has_text='有没保存的修改')
            await expect(box).to_be_visible()
            await page.get_by_test_id('second-receipt').evaluate('(button) => button.click()')
            current = page.get_by_role('dialog', name='回货入库 · HH-SYN-002', exact=True)
            await expect(current.locator('.grand')).to_have_text('396')
            await current.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('23')
            await page.wait_for_function("""() => Object.keys(localStorage).some(key => key.startsWith('lingmao-draft:') && Object.values(JSON.parse(localStorage[key]).data.qtys ?? {}).includes(23))""")
            await box.get_by_role('button', name='不保存，关掉' if mode == 'close' else '不保存，离开', exact=True).click()
            await expect(box).not_to_be_visible()
            await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
            visible = await current.is_visible()
            path = await page.get_by_test_id('route').inner_text()
            retained = await page.evaluate("""() => Object.keys(localStorage).filter(key => key.startsWith('lingmao-draft:')).map(key => ({key, ...JSON.parse(localStorage[key])})).filter(row => Object.values(row.data.qtys ?? {}).includes(23))""")
            passed = visible and path == '/fg-panels' and len(retained) == 1
            report['checks']['late_' + mode + '_confirmation_cannot_discard_new_source'] = {'passed': passed,
                'old_receipt': fg.RECEIPT, 'current_receipt': fg.OTHER_RECEIPT, 'new_quantity': 23,
                'current_dialog_visible': visible, 'actual_path': path, 'new_draft_retained': len(retained) == 1,
                'new_draft_records': retained}
            await fg.capture(page, 'late-' + mode + '-confirmation.png')
            if report['phase'] != 'before':
                assert passed, report['checks']
                if mode == 'close': await current.locator('.el-dialog__headerbtn').click()
                else: await page.get_by_test_id('leave-receipt').evaluate('(button) => button.click()')
                await expect(box).to_be_visible()
                await box.get_by_role('button', name='继续编辑', exact=True).click()
                await expect(box).not_to_be_visible()
                await expect(current).to_be_visible()
                await expect(current.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('23')
                if mode == 'close': await current.locator('.el-dialog__headerbtn').click()
                else: await page.get_by_test_id('leave-receipt').evaluate('(button) => button.click()')
                await expect(box).to_be_visible()
                await box.get_by_role('button', name='不保存，关掉' if mode == 'close' else '不保存，离开', exact=True).click()
                await expect(current).not_to_be_visible()
                if mode == 'route': await expect(page.get_by_test_id('route')).to_have_text('/synthetic-leave')
                assert not await page.evaluate("""() => Object.keys(localStorage).some(key => key.startsWith('lingmao-draft:') && Object.values(JSON.parse(localStorage[key]).data.qtys ?? {}).includes(23))""")
                fg.check(report, 'current_' + mode + '_confirmation_preserves_cancel_and_discards_only_on_confirm', canceled_quantity=23, explicit_discard=True)
            assert not [call for call in api.calls if call['method'] != 'GET']
        finally: await fg.finish(context, report, console)


async def receipt_restart_confirmation(browser, base, report, artifacts):
    context, page, api, _, console = await fg.start_page(browser, base, report, '/fg-panels')
    try:
        await page.get_by_role('button', name='打开第一回货', exact=True).click()
        original = page.get_by_role('dialog', name='回货入库 · HH-SYN-001', exact=True)
        await expect(original.locator('.grand')).to_have_text('396')
        old_path = '/api/fg-docs/from-receipt/' + fg.RECEIPT
        api.failures[(old_path, '1')] = 503
        await original.get_by_role('button', name='存草稿', exact=True).click()
        await expect(original.get_by_role('button', name='重试原来那笔', exact=True)).to_be_visible()
        old_writes = [call for call in api.calls if call['path'] == old_path and call['method'] == 'POST']
        assert old_writes and len({call['idempotency_key'] for call in old_writes}) == 1
        await original.get_by_role('button', name='重新入一笔', exact=True).click()
        box = page.locator('.el-message-box').filter(has_text='上一笔结果还没确认')
        await expect(box).to_be_visible()
        await page.get_by_test_id('second-receipt').evaluate('(button) => button.click()')
        current = page.get_by_role('dialog', name='回货入库 · HH-SYN-002', exact=True)
        await expect(current.locator('.grand')).to_have_text('396')
        await current.get_by_role('textbox', name='蓝色 M 件数', exact=True).fill('23')
        await page.wait_for_function("""() => Object.keys(localStorage).some(key => key.startsWith('lingmao-draft:') && Object.values(JSON.parse(localStorage[key]).data.qtys ?? {}).includes(23))""")
        new_path = '/api/fg-docs/from-receipt/' + fg.OTHER_RECEIPT
        reads_before = len([call for call in api.calls if call['path'] == new_path and call['method'] == 'GET'])
        await box.get_by_role('button', name='确认再入一笔', exact=True).click()
        await expect(box).not_to_be_visible()
        await page.wait_for_load_state('networkidle')
        actual = await current.get_by_role('textbox', name='蓝色 M 件数', exact=True).input_value()
        retained = await page.evaluate("""() => Object.keys(localStorage).some(key => key.startsWith('lingmao-draft:') && Object.values(JSON.parse(localStorage[key]).data.qtys ?? {}).includes(23))""")
        reads_after = len([call for call in api.calls if call['path'] == new_path and call['method'] == 'GET'])
        passed = actual == '23' and retained and reads_after == reads_before
        report['checks']['late_restart_confirmation_cannot_reset_new_source'] = {'passed': passed, 'new_source_quantity': actual, 'new_draft_retained': retained, 'reads_before': reads_before, 'reads_after': reads_after, 'old_write_attempts': len(old_writes), 'one_idempotency_key': True}
        await fg.capture(page, 'receipt-late-restart-confirmation.png')
        if report['phase'] == 'before': return
        assert passed, report['checks']
        api.failures[(new_path, '1')] = 503
        await current.get_by_role('button', name='存草稿', exact=True).click()
        await expect(current.get_by_role('button', name='重试原来那笔', exact=True)).to_be_visible()
        await current.get_by_role('button', name='重新入一笔', exact=True).click()
        await expect(box).to_be_visible()
        await box.get_by_role('button', name='保留原来那笔', exact=True).click()
        await expect(box).not_to_be_visible()
        await expect(current.get_by_role('button', name='重试原来那笔', exact=True)).to_be_visible()
        await expect(current.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('23')
        api.failures.clear()
        await current.get_by_role('button', name='重新入一笔', exact=True).click()
        await box.get_by_role('button', name='确认再入一笔', exact=True).click()
        await expect(current.get_by_role('button', name='存草稿', exact=True)).to_be_visible()
        await expect(current.locator('.grand')).to_have_text('396')
        await expect(current.get_by_role('textbox', name='蓝色 M 件数', exact=True)).to_have_value('120')
        fg.check(report, 'current_restart_confirmation_preserves_cancel_and_reloads_only_on_confirm', canceled_quantity=23, confirmed_quantity=120)
    finally: await fg.finish(context, report, console)


async def run(baseline, confirm_only=False, artifacts=None, restart_only=False):
    artifacts = artifacts or ROOT / 'test-artifacts/full-audit/s3' / ('fg-restart' if restart_only else 'fg-confirmation' if confirm_only else 'fg-drafts') / ('before' if baseline else 'after')
    artifacts.mkdir(parents=True, exist_ok=True)
    fg.ARTIFACTS = artifacts
    fg.SyntheticApi = DraftApi
    fixture = ROOT / 'web/node_modules/.s3-fg'
    fixture.mkdir(parents=True, exist_ok=True)
    for name in ['fg.html', 'FgFixture.vue', 'FgPanels.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'phase': 'before' if baseline else 'after', 'scope': 'Actual FG dialogs, localStorage write and browser reload; synthetic HTTP metadata only',
        'checks': {}, 'page_errors': [], 'console_errors': [], 'expected_console_errors': []}
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
                    checks = [receipt_restart_confirmation] if restart_only else [late_discard_confirmation] if confirm_only else [receipt_scope, manual_restore]
                    if not baseline and not confirm_only and not restart_only: checks += [receipt_contexts, receipt_late_confirm, manual_failures, cancellations, ship_restore, warehouse_cancellation, late_discard_confirmation, receipt_restart_confirmation]
                    for check in checks:
                        report['running'] = check.__name__
                        await check(browser, f'http://127.0.0.1:{port}', report, artifacts)
                        print(json.dumps({'completed': check.__name__, 'checks': len(report['checks'])}), flush=True)
                finally: await browser.close()
        except BaseException as error:
            report['failure'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            source_files = [ROOT / 'web/src/composables/useUnsavedChanges.ts', ROOT / 'web/src/views/fg/stock-in/ReceiveDialog.vue', ROOT / 'web/src/views/fg/doc/FgDocFormDialog.vue', ROOT / 'web/src/views/fg/fgDraft.ts', ROOT / 'web/src/api/basic.ts']
            report['source_files'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files}
            (artifacts / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert not report['page_errors'] and not report['console_errors'], report
    assert all(row['passed'] for row in report['checks'].values()), report['checks']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--baseline', action='store_true'); parser.add_argument('--confirm-only', action='store_true')
    parser.add_argument('--artifacts', type=Path); parser.add_argument('--restart-only', action='store_true')
    args = parser.parse_args()
    asyncio.run(run(args.baseline, args.confirm_only, args.artifacts.resolve() if args.artifacts else None, args.restart_only))
