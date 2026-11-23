#!/usr/bin/env python3
"""Actual sample pages/dialogs under AppLayout. Synthetic API09 only; not backend/SQL evidence."""
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
from urllib.parse import urlencode,urlsplit
from playwright.async_api import async_playwright,expect
from sample_http import SampleHttp,SAMPLE,ORDER,PACK,STYLE,USER,COLORS,STAMP,IMAGE

ROOT=Path(__file__).resolve().parents[3]
ARTIFACTS=ROOT/'test-artifacts/full-audit/s3/samples'


def check(report,name,**facts):report['checks'][name]={'passed':True,**facts}


async def capture(page,name):
    await page.evaluate('()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))')
    await page.screenshot(path=str(ARTIFACTS/name),full_page=True,animations='disabled')


async def start(browser,base,report,path=None,api=None,permissions=None,width=1440,roles=None):
    api=api or SampleHttp()
    context=await browser.new_context(viewport={'width':width,'height':1000},timezone_id='Pacific/Honolulu')
    page=await context.new_page();page.set_default_timeout(12000)
    page.on('pageerror',lambda error:report['page_errors'].append(str(error)))
    page.on('requestfailed',lambda request:api.aborted.append({'path':urlsplit(request.url).path,'failure':request.failure}))
    console=[]
    page.on('console',lambda message:console.append({'text':message.text,'path':urlsplit(message.location.get('url','')).path}) if message.type=='error' else None)
    await context.route(re.compile(r'^http://127\.0\.0\.1:\d+/api/'),api.route)
    await context.route('**/files/sample-proof.png',lambda route:route.fulfill(body=IMAGE,content_type='image/png'))
    params={'path':path or '/samples/'+SAMPLE+'?companyId=1'}
    if permissions is not None:params['permissions']=','.join(permissions)
    if roles is not None:params['roles']=','.join(roles)
    await page.goto(base+'/node_modules/.s3-samples/samples.html?'+urlencode(params))
    try:await page.locator('.sample-fixture-controls').wait_for()
    except BaseException:
        await capture(page,'fixture-start-failure.png')
        report['fixture_start_console']=console;report['fixture_start_http']=api.calls
        (ARTIFACTS/'fixture-start.html').write_text(await page.content())
        await context.close();raise
    return context,page,api,console


async def finish(context,page,api,console,report):
    if sys.exc_info()[0] is not None and not page.is_closed():await capture(page,report.get('running','check')+'-failure.png')
    for held in api.holds.values():held['release'].set()
    if api.tasks:await asyncio.wait_for(asyncio.gather(*list(api.tasks)),20)
    if api.errors:await capture(page,'contract-failure.png')
    await context.close()
    for error in console:
        if any(error['path']==resource['path'] and 'Failed to load resource' in error['text'] and str(resource['status']) in error['text'] for resource in api.expected_resources):report['expected_console_errors'].append(error)
        else:report['console_errors'].append(error)
    report['http_checks'].append({'calls':len(api.calls),'writes':len(api.writes),'attempts':len(api.attempts),'contract_errors':api.errors})
    assert not api.errors,api.errors


async def loaded(page):await expect(page.locator('.sample-detail h2')).to_have_text('合成雾蓝衬衫样衣')


def field(dialog,label):return dialog.locator('.el-form-item').filter(has=dialog.page.locator('.el-form-item__label',has_text=re.compile('^'+re.escape(label)+'$')))


async def select(page,box,label):
    await box.locator('.el-select').first.click()
    await page.get_by_role('option',name=label,exact=True).last.click()


async def fill_create(page):
    dialog=page.get_by_role('dialog',name='新建样衣',exact=True)
    await expect(dialog.get_by_role('button',name='建样衣',exact=True)).to_be_enabled()
    await dialog.get_by_label('样衣名称',exact=True).fill('合成新样衣')
    await select(page,field(dialog,'品牌'),'合成品牌')
    await field(dialog,'品类').locator('.el-select').click()
    await page.get_by_text('上装',exact=True).last.click()
    await select(page,field(dialog,'季节'),'春季')
    await select(page,field(dialog,'尺码组'),'合成尺码组')
    await dialog.get_by_text('BK 黑色',exact=True).click();await dialog.get_by_text('BL 雾蓝',exact=True).click()
    await dialog.get_by_label('预估吊牌价',exact=True).fill('199.99')
    if await dialog.get_by_label('预估成本',exact=True).count():await dialog.get_by_label('预估成本',exact=True).fill('45.6789')
    await dialog.get_by_label('首版要样日期',exact=True).fill('2026-12-10');await dialog.get_by_label('首版要样日期',exact=True).press('Tab')
    return dialog


async def check_list(browser,base,report):
    context,page,api,console=await start(browser,base,report,'/samples?companyId=1')
    try:
        await expect(page.get_by_text('YY-SYN-001',exact=True)).to_be_visible()
        await expect(page.get_by_role('radio',name='全部 65',exact=True)).to_be_checked()
        assert len([c for c in api.calls if c['path']=='/api/samples/counts'])==1
        await page.locator('.el-pagination .btn-next').click();await expect(page.get_by_text('YY-SYN-021',exact=True)).to_be_visible()
        assert len([c for c in api.calls if c['path']=='/api/samples/counts'])==1
        check(report,'paged_list_counts_separate',page2_rows=await page.locator('.sample-table .el-table__body-wrapper tbody tr').count(),total=65)
        await page.get_by_text('打样中 18',exact=True).click();await expect(page.get_by_text('YY-SYN-001',exact=True)).to_be_visible()
        call=next(c for c in reversed(api.calls) if c['path']=='/api/samples');assert call['params']['page']==['1'] and call['params']['status']==['MAKING']
        check(report,'status_resets_page_and_real_query',params=call['params'])
        for width in [1440,1180]:
            await page.set_viewport_size({'width':width,'height':1000});await capture(page,f'sample-list-{width}.png')
            metric=await page.evaluate('()=>({view:innerWidth,width:document.documentElement.scrollWidth})');assert metric['width']<=metric['view']+1,metric
        search=page.get_by_placeholder('样衣号、打样单号、名称')
        await search.fill('YY001 YY002,YY003')
        async with page.expect_response(lambda r: urlsplit(r.url).path=='/api/samples' and 'keyword=' in r.url):await search.press('Enter')
        await page.wait_for_function("()=>!document.querySelector('.sample-table .el-loading-mask')")
        call=next(c for c in reversed(api.calls) if c['path']=='/api/samples');assert call['params']['keyword']==['YY001 YY002,YY003']
        count=len([c for c in api.calls if c['path']=='/api/samples'])
        await search.fill(' '.join('YY'+str(i) for i in range(101)));await search.press('Enter')
        await expect(page.get_by_text(re.compile('最多.*100')).first).to_be_visible()
        assert len([c for c in api.calls if c['path']=='/api/samples'])==count
        check(report,'keywords_exact_wire_and_limit_100')
    finally:await finish(context,page,api,console,report)


async def check_create(browser,base,report):
    api=SampleHttp();api.lose_after_commit.add('/api/samples')
    context,page,api,console=await start(browser,base,report,'/samples?companyId=1',api)
    try:
        await page.get_by_role('button',name='新建样衣',exact=True).click();dialog=await fill_create(page)
        await capture(page,'sample-create.png')
        await dialog.get_by_role('button',name='建样衣',exact=True).click()
        await expect(dialog.get_by_text('结果尚未确认',exact=False)).to_be_visible()
        await expect(dialog.get_by_label('样衣名称',exact=True)).to_be_disabled()
        assert len(api.writes)==1
        await dialog.get_by_role('button',name='重试原提交',exact=True).last.click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/samples/'+SAMPLE+'?companyId=1')
        assert len(api.writes)==1 and len(api.attempts)==4
        assert all(a['key']==api.attempts[0]['key'] and a['body']==api.attempts[0]['body'] for a in api.attempts)
        body=api.writes[0]['body'];assert body['estCost']=='45.6789' and body['estTagPrice']=='199.99' and body['needDate']=='2026-12-10'
        assert all(isinstance(i,str) for i in body['colorIds']);assert body['sizeGroupId']=='12'
        check(report,'create_unknown_result_manual_same_key_body',writes=1,attempts=4,money_types={'estCost':type(body['estCost']).__name__,'estTagPrice':type(body['estTagPrice']).__name__},calendar=body['needDate'])
        await expect(page.locator('.sample-detail h2')).to_have_text('合成新样衣')
        check(report,'create_success_receipt_routes_string_id',route=await page.get_by_test_id('sample-path').inner_text())
    finally:await finish(context,page,api,console,report)


async def check_edit(browser,base,report):
    context,page,api,console=await start(browser,base,report)
    try:
        await loaded(page);await page.get_by_role('button',name='编辑',exact=True).click()
        dialog=page.get_by_role('dialog',name=re.compile('编辑样衣'))
        await expect(dialog.get_by_role('button',name='保存',exact=True)).to_be_enabled()
        await dialog.get_by_label('样衣名称',exact=True).fill('保留冲突中的输入')
        api.faults[('PUT','/api/samples/'+SAMPLE)]=[1002]
        await dialog.get_by_role('button',name='保存',exact=True).click()
        await expect(dialog.get_by_text('合成受控失败，请重读核对',exact=False)).to_be_visible()
        await expect(dialog.get_by_label('样衣名称',exact=True)).to_have_value('保留冲突中的输入')
        assert not api.writes
        await capture(page,'sample-main-cas-conflict.png')
        error_box=await dialog.locator('[role=alert]').filter(has_text='合成受控失败').evaluate('element=>{const box=element.getBoundingClientRect(),parent=element.closest(".el-dialog"),visible=parent.querySelector(".el-dialog__body").getBoundingClientRect();return {top:box.top,bottom:box.bottom,scrollTop:visible.top,scrollBottom:visible.bottom,viewport:innerHeight,inFooter:!!element.closest(".el-dialog__footer")}}')
        report['main_error_bounds']=error_box
        assert error_box['top']>=0 and error_box['bottom']<=error_box['viewport'] and (error_box['inFooter'] or error_box['top']>=error_box['scrollTop'] and error_box['bottom']<=error_box['scrollBottom']),error_box
        check(report,'main_cas_conflict_retains_input',sent_version=api.attempts[0]['body']['version'],error_bounds=error_box)
        await page.get_by_test_id('sample-readonly').click()
        await expect(dialog.get_by_role('button',name='保存',exact=True)).to_be_disabled();await expect(dialog.get_by_label('样衣名称',exact=True)).to_have_value('保留冲突中的输入')
        check(report,'permission_loss_keeps_input_disables_write')
        await page.get_by_test_id('sample-full-permission').click();await dialog.get_by_role('button',name='保存',exact=True).click()
        await expect(dialog).not_to_be_visible();assert api.writes[-1]['body']['version']==7
        check(report,'main_edit_whitelist_money_strings',body_keys=sorted(api.writes[-1]['body']))
    finally:await finish(context,page,api,console,report)


async def check_company_edit(browser,base,report):
    context,page,api,console=await start(browser,base,report)
    try:
        await loaded(page);await page.get_by_role('button',name='编辑',exact=True).click();dialog=page.get_by_role('dialog',name=re.compile('编辑样衣'))
        await expect(dialog.get_by_role('button',name='保存',exact=True)).to_be_enabled()
        await select(page,field(dialog,'主体公司'),'合成主体乙')
        await dialog.get_by_role('button',name='保存',exact=True).click();await expect(dialog).not_to_be_visible()
        await loaded(page)
        write=api.writes[-1];assert write['company']=='1' and write['params']['companyId']==['1'] and write['body']['companyId']=='2'
        reads=[call for call in api.calls if call['path']=='/api/samples/'+SAMPLE and call['method']=='GET']
        assert reads[-1]['company']=='2' and reads[-1]['params']['companyId']==['2']
        await expect(page.get_by_test_id('sample-path')).to_have_text('/samples/'+SAMPLE+'?companyId=2')
        await expect(page.locator('.result-notice')).to_contain_text('操作已成功保存')
        check(report,'main_company_update_old_query_header_new_body_then_scoped_reload',write_company='1',new_company='2')
    finally:await finish(context,page,api,console,report)


async def check_nullable_designer(browser,base,report):
    api=SampleHttp();api.value['designerId']=None;api.value['designerName']=None
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('button',name='编辑',exact=True).click()
        dialog=page.get_by_role('dialog',name=re.compile('编辑样衣'))
        await expect(dialog.get_by_role('button',name='保存',exact=True)).to_be_enabled()
        await dialog.get_by_label('样衣名称',exact=True).fill('只改名称且不改派设计师')
        await capture(page,'sample-null-designer-edit.png')
        await dialog.get_by_role('button',name='保存',exact=True).click();await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['body']['designerId'] is None,api.writes[-1]['body']['designerId']
        check(report,'editing_nullable_designer_preserves_null',submitted_designer=None)
    finally:await finish(context,page,api,console,report)


async def check_order_edit(browser,base,report):
    context,page,api,console=await start(browser,base,report,api=SampleHttp('WAIT_ASSIGN',2))
    try:
        await loaded(page);await page.get_by_role('button',name='编辑本版',exact=True).click()
        dialog=page.get_by_role('dialog',name=re.compile('编辑第 2 版'))
        await dialog.get_by_label('打样总件数',exact=True).fill('99999999');await dialog.get_by_label('打样总件数',exact=True).press('Tab')
        await expect(dialog.get_by_label('打样总件数',exact=True)).to_have_value('99999999')
        await field(dialog,'改版原因').get_by_role('textbox').fill('')
        await dialog.get_by_role('button',name='保存',exact=True).click()
        await expect(dialog.get_by_text('第 2 版起必须填写改版原因',exact=True)).to_be_visible()
        assert not api.attempts,api.attempts
        check(report,'revision_reason_required_before_write_and_eight_digit_quantity')
        await field(dialog,'改版原因').get_by_role('textbox').fill('合成第 2 版原因')
        api.faults[('PUT','/api/sample-orders/'+str(int(ORDER)+1))]=[1002]
        await dialog.get_by_role('button',name='保存',exact=True).click()
        await expect(dialog.get_by_text('合成受控失败，请重读核对',exact=False)).to_be_visible()
        await expect(dialog.get_by_label('打样总件数',exact=True)).to_have_value('99999999')
        assert api.attempts[-1]['body']['version']==3 and api.attempts[-1]['body']['sampleVersionLock']==7
        await capture(page,'sample-order-edit-conflict.png')
        await dialog.get_by_role('button',name='保存',exact=True).click();await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['body']['qty']==99999999 and api.writes[-1]['body']['reason']=='合成第 2 版原因'
        check(report,'order_edit_dual_cas_conflict_retains_then_saves',qty=99999999)
    finally:await finish(context,page,api,console,report)
    api=SampleHttp('MAKING');api.value['colors']=[deepcopy(COLORS[1])];api.value['colorIds']=['22']
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('button',name='编辑本版',exact=True).click()
        dialog=page.get_by_role('dialog',name=re.compile('编辑第 1 版'))
        await expect(dialog.get_by_label('打样总件数',exact=True)).to_be_disabled()
        await expect(field(dialog,'颜色').get_by_role('checkbox',name='BK 黑色',exact=True)).to_be_disabled()
        await expect(field(dialog,'改版原因').get_by_role('textbox')).to_be_disabled()
        await field(dialog,'打样要求').get_by_role('textbox').fill('只修改当前派单要求')
        await capture(page,'sample-making-edit-readonly-fields.png')
        await dialog.get_by_role('button',name='保存',exact=True).click();await expect(dialog).not_to_be_visible()
        body=api.writes[-1]['body'];assert body['qty']==1 and body['colorIds']==['21','22'] and body['sizeCodes']==['M','L'] and body['requirement']=='只修改当前派单要求'
        check(report,'making_keeps_snapshot_fields_while_editing_requirement',color_ids=body['colorIds'])
    finally:await finish(context,page,api,console,report)


async def check_dispatch(browser,base,report):
    for way in ['EXTERNAL','INTERNAL']:
        context,page,api,console=await start(browser,base,report)
        try:
            await loaded(page);api.faults[('GET','/api/sample-orders/'+ORDER+'/dispatch-preview')]=[503]
            await page.get_by_role('button',name='派单',exact=True).first.click()
            dialog=page.get_by_role('dialog',name='派单',exact=True)
            await expect(dialog.get_by_text('合成受控失败，请重读核对',exact=False)).to_be_visible();await expect(dialog.get_by_role('button',name='派单',exact=True)).to_be_disabled()
            await dialog.get_by_role('button',name='重试',exact=True).click();await expect(dialog.locator('.plans li')).to_have_count(5)
            if way=='INTERNAL':
                await dialog.get_by_text('内部版房',exact=True).click()
                box=field(dialog,'版师').get_by_role('combobox');await box.fill('合成');await page.get_by_role('option',name='合成版师 synthetic-maker').click()
            await dialog.get_by_label('派单要样日期',exact=True).fill('2026-12-11');await dialog.get_by_label('派单要样日期',exact=True).press('Tab')
            await expect(dialog.locator('.plans li').last).to_contain_text('2026-12-11')
            await capture(page,'sample-dispatch-'+way.lower()+'.png')
            await dialog.get_by_role('button',name='派单',exact=True).click()
            await expect(dialog).not_to_be_visible();await expect(page.locator('.nodes li')).to_have_count(5)
            body=api.writes[-1]['body'];assert body['version']==3 and body['sampleVersionLock']==7 and body['makeWay']==way and body['needDate']=='2026-12-11'
            assert body['makerId'] is None if way=='EXTERNAL' else body['factoryId'] is None
            check(report,'dispatch_'+way.lower()+'_dual_cas_preview_retry',body=body)
        finally:await finish(context,page,api,console,report)


async def check_nodes(browser,base,report):
    context,page,api,console=await start(browser,base,report,api=SampleHttp('MAKING'))
    try:
        await loaded(page);await expect(page.locator('.nodes li')).to_have_count(5)
        await page.get_by_role('button',name='节点计划',exact=True).click();dialog=page.get_by_role('dialog',name='节点计划',exact=True)
        await expect(dialog.locator('.el-table__body-wrapper tbody tr')).to_have_count(5)
        await dialog.locator('.el-table__body-wrapper tbody tr').first.get_by_role('textbox').last.fill('合成计划备注')
        await capture(page,'sample-node-plans.png')
        date_boxes=await dialog.locator('.el-table__body-wrapper .el-date-editor').evaluate_all('elements=>elements.map(element=>{const box=element.getBoundingClientRect(),cell=element.closest(".cell"),padding=getComputedStyle(cell),available=cell.clientWidth-parseFloat(padding.paddingLeft)-parseFloat(padding.paddingRight);return {width:box.width,available,top:box.top,right:box.right,viewport:innerWidth}})')
        report['node_plan_date_bounds']=date_boxes
        assert len(date_boxes)==5 and all(box['width']<=box['available']+.5 and box['right']<=box['viewport'] for box in date_boxes),date_boxes
        await dialog.get_by_role('button',name='保存计划',exact=True).click();await expect(dialog).not_to_be_visible()
        body=api.writes[-1]['body'];assert body['version']==3 and body['sampleVersionLock']==7 and len(body['nodes'])==5
        check(report,'node_plans_all_ids_dual_cas',ids=[n['id'] for n in body['nodes']])
        send=page.locator('.nodes li').filter(has=page.get_by_text('送样',exact=True));await send.get_by_role('button',name='报完成',exact=True).click()
        dialog=page.get_by_role('dialog',name='报节点',exact=True);await dialog.get_by_role('button',name='报完成',exact=True).click()
        await expect(dialog).not_to_be_visible();await expect(page.locator('.result-notice')).to_contain_text('前置节点尚未报完成')
        assert api.writes[-1]['body']['version']==4 and api.writes[-1]['body']['sampleVersionLock']==8
        check(report,'send_skipped_nodes_warning_after_success',state=api.value['currentOrder']['status'])
        await capture(page,'sample-send-warning.png')
        pattern=page.locator('.nodes li').filter(has=page.get_by_text('纸样',exact=True))
        await pattern.get_by_role('button',name='报完成',exact=True).click();dialog=page.get_by_role('dialog',name='报节点',exact=True)
        await dialog.get_by_role('button',name='报完成',exact=True).click();await expect(dialog).not_to_be_visible()
        assert api.orders[0]['status']=='WAIT_REVIEW' and api.writes[-1]['body']['version']==5
        await pattern.get_by_role('button',name='改完成日期',exact=True).click();dialog=page.get_by_role('dialog',name='改完成日期',exact=True)
        await dialog.get_by_label('节点完成日期',exact=True).fill('2026-11-20');await dialog.get_by_label('节点完成日期',exact=True).press('Tab')
        await capture(page,'sample-node-correct-date.png');await dialog.get_by_role('button',name='保存日期',exact=True).click();await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['body']['doneDate']=='2026-11-20' and api.writes[-1]['body']['version']==6
        await pattern.get_by_role('button',name='撤回完成',exact=True).click();dialog=page.get_by_role('dialog',name='撤回节点完成',exact=True)
        await dialog.get_by_role('button',name='确认撤回',exact=True).click();await expect(dialog).not_to_be_visible()
        assert api.orders[0]['status']=='WAIT_REVIEW' and api.writes[-1]['params']['version']==['7']
        check(report,'waiting_review_reports_corrects_and_undoes_non_send',final_order_status='WAIT_REVIEW')
        await page.locator('.nodes li').filter(has=page.get_by_text('送样',exact=True)).get_by_role('button',name='撤回完成',exact=True).click()
        dialog=page.get_by_role('dialog',name='撤回节点完成',exact=True);await dialog.get_by_role('button',name='确认撤回',exact=True).click();await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['params']['version']==['8'] and api.writes[-1]['params']['sampleVersionLock']==['12']
        await expect(page.get_by_role('button',name='报节点',exact=True)).to_be_visible()
        check(report,'undo_send_query_cas_returns_making')
    finally:await finish(context,page,api,console,report)


async def check_review_history(browser,base,report):
    api=SampleHttp('WAIT_REVIEW');api.void_colors=True
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('button',name='评审',exact=True).click();dialog=page.get_by_role('dialog',name='样衣评审',exact=True)
        await dialog.get_by_text('改版',exact=True).click();await dialog.get_by_role('button',name='改版，生成下一版',exact=True).click()
        await expect(dialog.get_by_text('改版或淘汰必须填写意见',exact=True)).to_be_visible();assert not api.attempts
        await dialog.get_by_label('评审意见',exact=True).fill('领型需调整，保留草稿')
        await capture(page,'sample-review-revise.png');await dialog.get_by_role('button',name='改版，生成下一版',exact=True).click();await expect(dialog).not_to_be_visible()
        await expect(page.locator('.result-notice')).to_contain_text('原选色黑色已失效')
        await expect(page.locator('[data-sample-version="1"]')).to_contain_text('历史版 · 只读')
        assert await page.locator('[data-sample-version="1"]').get_by_role('button',name='编辑本版',exact=True).count()==0
        assert api.writes[-1]['body']['reviewerIds']==[USER]
        check(report,'revise_warning_old_readonly_new_version',current_version=2,selected_colors=[x['id'] for x in api.orders[0]['colors']])
        await capture(page,'sample-review-revised-history.png')
    finally:await finish(context,page,api,console,report)
    context,page,api,console=await start(browser,base,report,api=SampleHttp('WAIT_ASSIGN',23))
    try:
        await loaded(page);await expect(page.locator('.sample-version')).to_have_count(20)
        await page.get_by_role('button',name='更早版本',exact=True).click();await expect(page.locator('.sample-version')).to_have_count(3)
        c=next(c for c in reversed(api.calls) if c['path'].endswith('/orders'));assert c['params']['beforeVersion']==['4']
        await capture(page,'sample-history-second-page.png')
        await page.get_by_role('button',name='上一页',exact=True).click();await expect(page.locator('.sample-version')).to_have_count(20)
        check(report,'history_seek_replaces_bounded_page',first=20,second=3,before_version=4)
    finally:await finish(context,page,api,console,report)


async def check_convert_reopen(browser,base,report):
    api=SampleHttp('PASSED');api.convert_code_error='合成编码规则尚未配置'
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('button',name='转大货',exact=True).click();dialog=page.get_by_role('dialog',name='转大货',exact=True)
        await expect(dialog.get_by_text('合成编码规则尚未配置',exact=False)).to_be_visible();await expect(dialog).to_contain_text('将生成 4 个 SKU')
        await dialog.get_by_role('button',name='生成款式',exact=True).click();await expect(dialog.get_by_text('无法自动编款号',exact=False)).to_be_visible();assert not api.attempts
        await dialog.get_by_text('手填款号',exact=True).click();await dialog.get_by_label('转换款号',exact=True).fill('KS-MANUAL-SYN')
        await capture(page,'sample-convert-manual-preview.png');await dialog.get_by_role('button',name='生成款式',exact=True).click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/styles/'+STYLE)
        assert api.writes[-1]['body']['version']==7 and api.writes[-1]['body']['code']=='KS-MANUAL-SYN'
        origin=page.locator('.sample-origin').get_by_role('link',name=re.compile('YY-SYN-001'))
        await expect(origin).to_be_visible()
        await expect(origin).to_have_attribute('href','/samples/'+SAMPLE+'?companyId=1')
        check(report,'convert_real_preview_manual_fallback_receipt_and_origin',sku_count=4)
    finally:await finish(context,page,api,console,report)
    api=SampleHttp('MAKING');api.void_colors=True
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('button',name='终止',exact=True).click();dialog=page.get_by_role('dialog',name='终止开发',exact=True)
        await dialog.get_by_role('textbox').fill('项目延期');await dialog.get_by_role('button',name='确认终止',exact=True).click();await expect(dialog).not_to_be_visible()
        await page.get_by_role('button',name='恢复开发',exact=True).click();dialog=page.get_by_role('dialog',name='恢复开发',exact=True)
        await dialog.get_by_role('textbox').fill('恢复本季设计');await dialog.get_by_role('button',name='恢复开发',exact=True).click();await expect(dialog).not_to_be_visible()
        await expect(page.locator('.result-notice')).to_contain_text('原选色黑色已失效')
        assert api.writes[-1]['body']['version']==8;check(report,'stop_reopen_parent_cas_new_version_warning',new_version=2)
    finally:await finish(context,page,api,console,report)


async def check_review_decisions(browser,base,report):
    for result,label in [('PASS','通过'),('ELIMINATE','淘汰')]:
        context,page,api,console=await start(browser,base,report,api=SampleHttp('WAIT_REVIEW'))
        try:
            await loaded(page);await page.get_by_role('button',name='评审',exact=True).click()
            dialog=page.get_by_role('dialog',name='样衣评审',exact=True)
            await dialog.locator('.el-segmented__item').filter(has_text=label).click()
            await dialog.get_by_label('评审意见',exact=True).fill('合成'+label+'意见')
            await dialog.get_by_role('button',name=label,exact=True).click();await expect(dialog).not_to_be_visible()
            assert api.writes[-1]['body']['result']==result and len(api.writes)==1
            await expect(page.get_by_role('button',name='转大货' if result=='PASS' else '恢复开发',exact=True)).to_be_visible()
            assert await page.get_by_role('button',name='评审',exact=True).count()==0
            await capture(page,f'sample-review-{result.lower()}.png')
            check(report,f'review_{result.lower()}_updates_actions_and_history',parent=api.value['status'],child=api.orders[0]['status'])
        finally:await finish(context,page,api,console,report)


async def check_action_unknown(browser,base,report):
    api=SampleHttp();api.lose_after_commit.add('/api/samples/'+SAMPLE+'/stop')
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('button',name='终止',exact=True).click()
        dialog=page.get_by_role('dialog',name='终止开发',exact=True)
        await dialog.get_by_role('textbox').fill('结果不明时保留原版本与原因')
        await dialog.get_by_role('button',name='确认终止',exact=True).click()
        await expect(dialog.get_by_text('结果尚未确认',exact=False)).to_be_visible()
        assert len(api.attempts)==1 and len(api.writes)==1
        await expect(dialog.get_by_role('textbox')).to_be_disabled()
        for index in range(3):
            await dialog.get_by_role('button',name='重试原提交',exact=True).last.click()
            if index<2:await expect(dialog.get_by_role('button',name='重试原提交',exact=True).last).to_be_enabled()
        await expect(dialog).not_to_be_visible();await expect(page.get_by_role('button',name='恢复开发',exact=True)).to_be_visible()
        assert len(api.writes)==1 and len(api.attempts)==4
        assert all(a['key']==api.attempts[0]['key'] and a['body']==api.attempts[0]['body'] for a in api.attempts)
        check(report,'non_create_action_never_auto_replays_manual_retry_frozen_key_body',writes=1,explicit_attempts=4)
    finally:await finish(context,page,api,console,report)


async def check_late_reads(browser,base,report):
    api=SampleHttp();held=api.hold('/api/samples/'+SAMPLE)
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await asyncio.wait_for(held['started'].wait(),10);await page.get_by_test_id('sample-company').click();held['release'].set();await asyncio.wait_for(held['finished'].wait(),10)
        await expect(page.locator('.sample-detail')).to_have_count(0);await expect(page.get_by_text('先选择主体，再读取样衣。')).to_be_visible()
        assert any(c['path']=='/api/samples/'+SAMPLE for c in api.aborted),api.aborted
        check(report,'late_detail_company_change_aborts_and_discards',aborted=api.aborted)
    finally:await finish(context,page,api,console,report)
    context,page,api,console=await start(browser,base,report)
    try:
        await loaded(page);await page.get_by_role('button',name='编辑',exact=True).click();dialog=page.get_by_role('dialog',name=re.compile('编辑样衣'))
        await expect(dialog.get_by_role('button',name='保存',exact=True)).to_be_enabled();await dialog.get_by_label('样衣名称',exact=True).fill('刷新会话仍保留表单')
        await page.get_by_test_id('sample-refresh').click();await expect(dialog).to_be_visible();await expect(dialog.get_by_label('样衣名称',exact=True)).to_have_value('刷新会话仍保留表单')
        await page.get_by_test_id('sample-identity').click();await expect(dialog).not_to_be_visible();await expect(page.locator('.sample-detail')).to_have_count(0)
        check(report,'refresh_same_epoch_preserves_but_identity_clears_form')
    finally:await finish(context,page,api,console,report)


async def check_scope_change(browser,base,report):
    context,page,api,console=await start(browser,base,report)
    try:
        await loaded(page);held=api.hold('/api/samples/'+SAMPLE)
        await page.get_by_role('button',name='重新读取',exact=True).click();await asyncio.wait_for(held['started'].wait(),10)
        await page.get_by_test_id('sample-scope').click();held['release'].set();await asyncio.wait_for(held['finished'].wait(),10)
        await expect(page.locator('.sample-detail')).to_have_count(0)
        await expect(page.get_by_text('先选择主体，再读取样衣。')).to_be_visible()
        assert any(c['path']=='/api/samples/'+SAMPLE for c in api.aborted),api.aborted
        await capture(page,'sample-scope-change-cleared.png')
        check(report,'same_user_scope_narrows_clears_and_aborts_old_detail',aborted=api.aborted)
    finally:await finish(context,page,api,console,report)


async def check_visibility_receipt(browser,base,report):
    api=SampleHttp();api.hidden_after_write=True
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('button',name='编辑',exact=True).click();dialog=page.get_by_role('dialog',name=re.compile('编辑样衣'))
        await expect(dialog.get_by_role('button',name='保存',exact=True)).to_be_enabled();await dialog.get_by_label('样衣名称',exact=True).fill('改派后只读收据')
        await dialog.get_by_role('button',name='保存',exact=True).click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/samples?companyId=1')
        assert len(api.writes)==1 and len(api.attempts)==1
        check(report,'successful_write_then_404_no_resend_returns_list',writes=1)
    finally:await finish(context,page,api,console,report)
    api=SampleHttp('PASSED');api.hidden_after_write=True
    context,page,api,console=await start(browser,base,report,api=api,permissions=['dev:sample:list','dev:sample:convert'])
    try:
        await loaded(page);await page.get_by_role('button',name='转大货',exact=True).click()
        dialog=page.get_by_role('dialog',name='转大货',exact=True)
        await expect(dialog.get_by_role('button',name='生成款式',exact=True)).to_be_enabled()
        await dialog.get_by_role('button',name='生成款式',exact=True).click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/samples?companyId=1')
        assert len(api.writes)==1 and len(api.attempts)==1
        check(report,'convert_success_then_404_without_style_permission_no_resend',writes=1)
    finally:await finish(context,page,api,console,report)


async def check_created_visibility_receipt(browser,base,report):
    api=SampleHttp();api.hidden_after_write=True
    context,page,api,console=await start(browser,base,report,'/samples?companyId=1',api)
    try:
        await page.get_by_role('button',name='新建样衣',exact=True).click();dialog=await fill_create(page)
        await dialog.get_by_role('button',name='建样衣',exact=True).click();await expect(dialog).not_to_be_visible()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/samples?companyId=1')
        await expect(page.locator('.el-message').filter(has_text='样衣已创建')).to_be_visible()
        assert len(api.writes)==1 and len(api.attempts)==1
        await capture(page,'sample-created-hidden-success.png')
        check(report,'created_success_then_404_preserves_receipt_returns_list',writes=1)
    finally:await finish(context,page,api,console,report)


async def check_files_tags(browser,base,report):
    context,page,api,console=await start(browser,base,report)
    try:
        await loaded(page);await page.get_by_role('button',name='打样附件',exact=True).click();dialog=page.get_by_role('dialog',name='第 1 版 · 打样附件',exact=True)
        await expect(dialog.get_by_role('button',name='保存附件',exact=True)).to_be_enabled()
        await dialog.locator('input[type=file]').set_input_files({'name':'sample-proof.png','mimeType':'image/png','buffer':IMAGE})
        await expect(dialog.get_by_role('link',name='sample-proof.png',exact=True)).to_be_visible()
        await dialog.get_by_role('button',name='保存附件',exact=True).click();await expect(dialog).not_to_be_visible()
        body=api.writes[-1]['body'];assert body['version']==3 and body['sampleVersionLock']==7 and len(body['files'])==1
        assert body['files'][0]['size']==len(IMAGE) and 'id' not in body['files'][0]
        await page.get_by_role('button',name='打样附件',exact=True).click();dialog=page.get_by_role('dialog',name='第 1 版 · 打样附件',exact=True)
        await expect(dialog.get_by_role('link',name='sample-proof.png',exact=True)).to_be_visible();await capture(page,'sample-order-files-roundtrip.png')
        await dialog.get_by_role('button',name='关闭',exact=True).click()
        check(report,'attachments_upload_roundtrip_and_dual_cas_replace',file_size=len(IMAGE))
        panel=page.get_by_role('region',name='样衣标签',exact=True);await panel.get_by_role('button',name='编辑标签',exact=True).click()
        dialog=page.get_by_role('dialog',name='编辑样衣标签',exact=True)
        text=dialog.get_by_role('textbox',name='标签输入',exact=True);await text.fill('#合成开发');await text.press('Enter')
        await dialog.get_by_role('button',name='保存标签',exact=True).click();await expect(dialog).not_to_be_visible()
        assert api.writes[-1]['path'].endswith('/replace') and api.writes[-1]['company']=='1'
        check(report,'persisted_sample_public_tags_company_cas',body=api.writes[-1]['body'])
    finally:await finish(context,page,api,console,report)


async def check_techpack(browser,base,report):
    context,page,api,console=await start(browser,base,report)
    try:
        await loaded(page);await page.get_by_role('tab',name='研发资料包',exact=True).click()
        await page.get_by_role('button',name='建研发 BOM',exact=True).click();await expect(page.get_by_role('button',name='保存',exact=True)).to_be_visible()
        assert api.writes[-1]['body']=={'sampleId':SAMPLE,'companyId':'1','stage':'DEV'}
        await page.get_by_role('tab',name=re.compile('^尺寸指示')).click()
        await page.get_by_role('button',name='加一行',exact=True).click()
        table=page.locator('.tp-wrap .el-tab-pane:visible .el-table');await table.get_by_role('combobox').click();await table.get_by_role('combobox').fill('胸围');await page.get_by_role('option',name='胸围',exact=True).last.click()
        await page.get_by_role('button',name='保存',exact=True).click()
        await expect(page.locator('.tp-wrap .el-loading-mask')).to_have_count(0)
        body=next(w['body'] for w in reversed(api.writes) if w['method']=='PUT');assert body['versionLock']==4 and len(body['specPoints'])==1
        await capture(page,'sample-techpack-dev-spec.png')
        await page.get_by_role('tab',name=re.compile('^核价')).click();await expect(page.get_by_role('tabpanel',name='核价',exact=True)).to_contain_text('单件成本')
        await page.get_by_role('tab',name='操作记录',exact=True).click();await page.get_by_role('button',name='导出 PDF',exact=True).click()
        dialog=page.get_by_role('dialog',name='导出工艺单',exact=True)
        async with page.expect_download():await dialog.get_by_role('button',name='导出',exact=True).click()
        calls=[c for c in api.calls if c['path'].startswith('/api/tech-packs')]
        assert all(c['company']=='1' for c in calls)
        assert not any(c['path'].endswith('/to-prod') for c in calls)
        check(report,'sample_dev_pack_create_save_cost_logs_pdf_scoped',paths=sorted({c['path'] for c in calls}))
    finally:await finish(context,page,api,console,report)
    api=SampleHttp(packs=23)
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('tab',name='研发资料包',exact=True).click()
        await expect(page.locator('.ver-pill')).to_have_count(20)
        await page.get_by_role('button',name='更早版本',exact=True).click();await expect(page.locator('.ver-pill')).to_have_count(3)
        call=next(c for c in reversed(api.calls) if c['path']=='/api/tech-packs');assert call['params']['beforeVersion']==['4']
        check(report,'sample_techpack_history_seek_20_replace',second=3,before=4)
    finally:await finish(context,page,api,console,report)


async def check_techpack_layout(browser,base,report):
    api=SampleHttp(packs=1)
    api.packs[0]['specPoints']=[{'id':str(9007199254747001+i),'seq':i+1,'point':'胸围','method':'合成测量方法','toleranceCm':0.5,'gradeCm':1.5,'remark':None,'values':{'M':123.4,'L':124.9}} for i in range(100)]
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('tab',name='研发资料包',exact=True).click()
        await page.get_by_role('tab',name=re.compile('^尺寸指示')).click()
        table=page.locator('.tp-wrap .el-tab-pane:visible .el-table')
        await expect(table.locator('.el-table__body-wrapper tbody tr')).to_have_count(100)
        inputs=await table.evaluate('''table=>[...table.querySelectorAll('tbody tr:first-child .el-input-number')].map(input=>{
            const box=input.getBoundingClientRect(),cell=input.closest('.cell').getBoundingClientRect();
            const actual=input.querySelector('input'),s=getComputedStyle(actual),ctx=document.createElement('canvas').getContext('2d');ctx.font=s.font;
            return {inputLeft:box.left,inputRight:box.right,cellLeft:cell.left,cellRight:cell.right,value:actual.value,textWidth:ctx.measureText(actual.value).width,textAvailable:actual.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight)};
        })''')
        await capture(page,'sample-pack-spec-controls.png')
        last=table.locator('.el-table__body-wrapper tbody tr').last;await last.scroll_into_view_if_needed()
        metrics=await last.evaluate('''row=>{
            const cell=row.cells[0].querySelector('.cell'),s=getComputedStyle(cell),canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');ctx.font=s.font;
            const buttons=[...row.cells[row.cells.length-1].querySelectorAll('button')].map(b=>({top:b.getBoundingClientRect().top,text:b.textContent}));
            return {sequence:cell.textContent.trim(),sequenceWidth:ctx.measureText(cell.textContent.trim()).width,available:cell.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight),buttons};
        }''')
        report['techpack_layout']={'inputs':inputs,'last_row':metrics}
        await capture(page,'sample-pack-spec-sequence-100.png')
        assert all(x['inputLeft']>=x['cellLeft']-1 and x['inputRight']<=x['cellRight']+1 for x in inputs),inputs
        assert all(x['textWidth']<=x['textAvailable']+1 for x in inputs),inputs
        assert metrics['sequence']=='100' and metrics['sequenceWidth']<=metrics['available']+1,metrics
        assert max(b['top'] for b in metrics['buttons'])-min(b['top'] for b in metrics['buttons'])<=2,metrics
        await table.locator('.el-table__body-wrapper .el-scrollbar__wrap').evaluate('(el)=>{el.scrollLeft=el.scrollWidth}')
        delete=last.get_by_role('button',name='删',exact=True);await expect(delete).to_be_in_viewport()
        await capture(page,'sample-pack-spec-sequence-100-right.png')
        await delete.click();await expect(table.locator('.el-table__body-wrapper tbody tr')).to_have_count(99)
        check(report,'sample_pack_narrow_numeric_controls_and_sequence_100_fit',metrics=metrics)
    finally:await finish(context,page,api,console,report)


async def check_drafts_and_read_errors(browser,base,report):
    context,page,api,console=await start(browser,base,report,'/samples?companyId=1')
    try:
        await page.get_by_role('button',name='新建样衣',exact=True).click();dialog=await fill_create(page)
        await dialog.get_by_label('样衣名称',exact=True).fill('需要恢复的样衣草稿')
        await page.wait_for_function("()=>Object.keys(localStorage).some(k=>k.startsWith('lingmao-draft:')&&k.includes('SampleFormDialog')&&JSON.parse(localStorage[k]).data?.form?.name==='需要恢复的样衣草稿')")
        page.once('dialog',lambda dialog:dialog.accept());await page.reload();await page.locator('.sample-fixture-controls').wait_for()
        await page.get_by_role('button',name='新建样衣',exact=True).click()
        offer=page.locator('.el-message-box').filter(has_text='找到这份表单未保存的草稿')
        await expect(offer).to_be_visible();await offer.get_by_role('button',name='恢复草稿',exact=True).click()
        dialog=page.get_by_role('dialog',name='新建样衣',exact=True)
        await expect(dialog.get_by_label('样衣名称',exact=True)).to_have_value('需要恢复的样衣草稿')
        await expect(dialog.get_by_label('预估成本',exact=True)).to_have_value('45.6789')
        await expect(dialog.locator('.el-check-tag.is-checked')).to_have_count(2)
        await capture(page,'sample-create-draft-restored.png')
        check(report,'sample_create_draft_real_reload_restores_money_colors')
        await page.get_by_test_id('sample-identity').click();await expect(dialog).not_to_be_visible()
        assert not api.attempts
        check(report,'draft_identity_change_closes_without_writes')
    finally:await finish(context,page,api,console,report)
    api=SampleHttp();api.faults[('GET','/api/samples/'+SAMPLE+'/references')]=[503]
    context,page,api,console=await start(browser,base,report,api=api)
    try:
        await loaded(page);await page.get_by_role('tab',name='下游引用',exact=True).click()
        pane=page.locator('.el-tab-pane:visible').first
        await expect(pane.get_by_text('合成受控失败，请重读核对',exact=False)).to_be_visible()
        await pane.get_by_role('button',name='重试',exact=True).click();await expect(pane.get_by_text('还没有可见的关联单据',exact=True)).to_be_visible()
        check(report,'references_failure_not_empty_retry_recovers')
        for width in [1440,1180]:
            await page.set_viewport_size({'width':width,'height':1000});await capture(page,f'sample-detail-{width}.png')
            metric=await page.evaluate('()=>({view:innerWidth,width:document.documentElement.scrollWidth})');assert metric['width']<=metric['view']+1,metric
        check(report,'detail_actual_layout_1440_1180_no_document_overflow')
    finally:await finish(context,page,api,console,report)


async def check_draft_people(browser,base,report):
    for kind in ['order','form','dispatch']:
        api=SampleHttp('MAKING' if kind=='order' else 'WAIT_ASSIGN')
        if kind in {'order','dispatch'}:
            api.orders[0].update(makeWay='INTERNAL',makerId=USER,makerName='合成版师',factoryId=None,factoryName=None)
        context,page,api,console=await start(browser,base,report,api=api)
        try:
            async def open_dialog():
                await loaded(page)
                label={'order':'编辑本版','form':'编辑','dispatch':'派单'}[kind]
                await page.get_by_role('button',name=label,exact=True).first.click()
                title={'order':re.compile('编辑第 1 版'),'form':re.compile('编辑样衣'),'dispatch':re.compile('^派单$')}[kind]
                dialog=page.get_by_role('dialog',name=title)
                await expect(dialog.get_by_role('button',name='派单' if kind=='dispatch' else '保存',exact=True)).to_be_enabled()
                return dialog
            dialog=await open_dialog();person=field(dialog,'设计师' if kind=='form' else '版师')
            await person.get_by_role('combobox').fill('合成')
            await page.get_by_role('option',name='合成评审人 synthetic-reviewer').last.click()
            component={'order':'SampleOrderDialog','form':'SampleFormDialog','dispatch':'SampleDispatchDialog'}[kind]
            await page.wait_for_function("(component)=>Object.keys(localStorage).some(k=>k.startsWith('lingmao-draft:')&&k.includes(component)&&JSON.stringify(JSON.parse(localStorage[k]).data).includes('9007199254740994'))",arg=component)
            page.once('dialog',lambda dialog:dialog.accept());await page.reload();await page.locator('.sample-fixture-controls').wait_for()
            dialog=await open_dialog()
            offer=page.locator('.el-message-box').filter(has_text='找到这份表单未保存的草稿')
            await expect(offer).to_be_visible();await offer.get_by_role('button',name='恢复草稿',exact=True).click()
            person=field(dialog,'设计师' if kind=='form' else '版师')
            await capture(page,f'sample-{kind}-person-draft-restored.png')
            await expect(person.locator('.el-select__selected-item').filter(has_text='合成评审人')).to_be_visible()
            await dialog.get_by_role('button',name='派单' if kind=='dispatch' else '保存',exact=True).click();await expect(dialog).not_to_be_visible()
            body=api.writes[-1]['body'];identifier='designerId' if kind=='form' else 'makerId'
            assert body[identifier]=='9007199254740994'
            check(report,f'{kind}_draft_person_label_matches_restored_id',person_id=body[identifier])
        finally:await finish(context,page,api,console,report)


async def check_home_palette(browser,base,report):
    api=SampleHttp();api.suppress_cost=True
    permissions=['dev:sample:list','dev:sample:review']
    context,page,api,console=await start(browser,base,report,'/home',api,permissions)
    try:
        await expect(page.locator('.kpi-top')).to_contain_text('打样中');await expect(page.locator('.kpi-num')).to_contain_text('18')
        assert not await page.get_by_role('button',name='款式库',exact=True).count()
        await capture(page,'sample-home-null-style-kpi.png')
        await page.get_by_role('button',name=re.compile('打样中')).first.click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/samples?status=MAKING&companyId=1')
        check(report,'home_nullable_kpi_no_fake_zero_company_navigation')
        await page.keyboard.press('Control+k');palette=page.get_by_role('dialog',name='快捷跳转',exact=True)
        await expect(palette).to_be_visible();await palette.get_by_role('combobox',name='搜索页面、单号、款号、物料、供应商',exact=True).fill('YY-SYN')
        await expect(palette.get_by_role('option',name=re.compile('YY-SYN-001'))).to_be_visible()
        call=next(c for c in reversed(api.calls) if c['path']=='/api/samples');assert call['params']['size']==['5'] and call['params']['companyId']==['1']
        await palette.get_by_role('option',name=re.compile('YY-SYN-001')).click();await loaded(page)
        check(report,'palette_bounded_sample_search_carries_company')
    finally:await finish(context,page,api,console,report)


async def check_list_context_options(browser,base,report):
    context,page,api,console=await start(browser,base,report,'/samples?companyId=1')
    try:
        await expect(page.get_by_text('YY-SYN-001',exact=True)).to_be_visible()
        before=sum(call['path']=='/api/categories/tree' for call in api.calls)
        await page.get_by_test_id('sample-identity').click()
        await page.locator('.page-header .el-select').first.click()
        await page.get_by_role('option',name='合成主体甲',exact=True).click()
        await expect(page.get_by_text('YY-SYN-001',exact=True)).to_be_visible()
        await page.get_by_role('button',name='筛选',exact=True).click()
        filters=page.get_by_role('dialog',name='筛选条件',exact=True)
        await filters.locator('.filter-item').filter(has=page.locator('.label',has_text=re.compile('^品类$'))).get_by_role('combobox').click()
        await capture(page,'sample-list-identity-category-options.png')
        after=sum(call['path']=='/api/categories/tree' for call in api.calls)
        report['list_context_option_reads']={'before':before,'after':after}
        bounds=await filters.evaluate('element=>{const box=element.getBoundingClientRect(),header=document.querySelector(".page-header").getBoundingClientRect();return {top:box.top,bottom:box.bottom,left:box.left,right:box.right,headerBottom:header.bottom,viewportHeight:innerHeight,viewportWidth:innerWidth}}')
        report['list_filter_bounds']=bounds
        assert bounds['top']>=bounds['headerBottom'] and bounds['bottom']<=bounds['viewportHeight'] and bounds['left']>=0 and bounds['right']<=bounds['viewportWidth'],bounds
        assert after>before,report['list_context_option_reads']
        await expect(page.get_by_role('treeitem',name='上装',exact=True)).to_be_visible()
        await page.set_viewport_size({'width':1180,'height':1000})
        await capture(page,'sample-list-identity-category-options-1180.png')
        narrow=await filters.evaluate('element=>{const box=element.getBoundingClientRect(),header=document.querySelector(".page-header").getBoundingClientRect();return {top:box.top,bottom:box.bottom,left:box.left,right:box.right,headerBottom:header.bottom,viewportHeight:innerHeight,viewportWidth:innerWidth}}')
        assert narrow['top']>=narrow['headerBottom'] and narrow['bottom']<=narrow['viewportHeight'] and narrow['left']>=0 and narrow['right']<=narrow['viewportWidth'],narrow
        await page.get_by_role('treeitem',name='上装',exact=True).click()
        await filters.get_by_role('button',name='完成',exact=True).click();await expect(filters).not_to_be_visible()
        await expect(page.get_by_role('button',name='去掉条件：品类',exact=True)).to_be_visible()
        check(report,'list_identity_company_reselection_restores_category_options',before=before,after=after,filter_bounds=[bounds,narrow])
    finally:await finish(context,page,api,console,report)


async def check_home_pack_only(browser,base,report):
    api=SampleHttp();api.home_pack_only=True
    context,page,api,console=await start(browser,base,report,'/home',api,['techpack:pack:list'])
    try:
        await expect(page.get_by_text('正在取得概况…',exact=True)).not_to_be_visible()
        await capture(page,'sample-home-pack-only-kpi.png')
        await expect(page.locator('.kpi-card')).to_contain_text('资料包草稿',timeout=3000)
        await expect(page.locator('.kpi-num')).to_have_text('3份')
        assert await page.get_by_text('选择主体后查看',exact=True).count()==0
        assert await page.locator('.kpi-card button').count()==0
        check(report,'home_pack_only_permission_displays_independent_count_without_sample_link',pack_drafts=3)
    finally:await finish(context,page,api,console,report)


async def check_home_context_reload(browser,base,report):
    api=SampleHttp();api.home_deliveries=True;api.home_backup=True;api.suppress_cost=True
    permissions=['dev:sample:list','dev:sample:review','order:order:list','data:all']
    context,page,api,console=await start(browser,base,report,'/home',api,permissions,roles=['admin'])
    try:
        delivery=page.get_by_text('DD-HOME-SYN-001',exact=True)
        await expect(delivery).to_be_visible();await expect(page.locator('.backup-line')).to_contain_text('最近备份')
        paths=['/api/board/home/kpi','/api/board/home/todos','/api/board/home/deliveries','/api/admin/backup-status']
        counts=lambda:{path:sum(call['path']==path for call in api.calls) for path in paths}
        before=counts();await page.get_by_test_id('sample-company').click()
        await expect(page.locator('.kpi-num')).to_contain_text('18')
        after=counts();report['home_company_reload_counts']={'before':before,'after':after}
        await capture(page,'sample-home-company-reloaded.png')
        assert all(after[path]==before[path]+1 for path in paths),report['home_company_reload_counts']
        await expect(delivery).to_be_visible();await expect(page.locator('.backup-line')).to_contain_text('最近备份')
        check(report,'home_company_reload_restores_deliveries_and_admin_backup',before=before,after=after)
        before=counts();held=api.hold('/api/board/home/kpi');await page.get_by_test_id('sample-scope').click()
        await asyncio.wait_for(held['started'].wait(),3)
        await expect(page.get_by_text('正在取得概况…',exact=True)).to_be_visible()
        held['release'].set();await expect(page.locator('.kpi-num')).to_contain_text('18')
        after=counts();assert all(after[path]==before[path]+1 for path in paths[:3]),{'before':before,'after':after}
        assert after[paths[3]]==before[paths[3]],{'before':before,'after':after}
        await expect(delivery).to_be_visible();await expect(page.locator('.backup-line')).to_have_count(0)
        await capture(page,'sample-home-role-reloaded.png')
        before_refresh=counts();await page.get_by_test_id('sample-refresh').click();await page.evaluate('()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))')
        assert counts()==before_refresh,{'before':before_refresh,'after':counts()}
        check(report,'home_role_scope_reload_and_same_epoch_refresh_preserves_data',before=before,after=after,refresh_requests=0)
    finally:await finish(context,page,api,console,report)


async def run(only,collect_failures=False):
    ARTIFACTS.mkdir(parents=True,exist_ok=True)
    fixture=ROOT/'web/node_modules/.s3-samples';fixture.mkdir(parents=True,exist_ok=True)
    for name in ['samples.html','SamplesFixture.vue']:shutil.copyfile(Path(__file__).with_name(name),fixture/name)
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    report={'scope':'Actual Vue sample pages/dialogs under AppLayout with strict synthetic API09. No backend or SQL claim.','checks':{},'http_checks':[],'page_errors':[],'console_errors':[],'expected_console_errors':[]}
    commands={'list':check_list,'create':check_create,'edit':check_edit,'company':check_company_edit,'nullable':check_nullable_designer,'order':check_order_edit,'dispatch':check_dispatch,'nodes':check_nodes,
              'review':check_review_history,'decisions':check_review_decisions,'convert':check_convert_reopen,'unknown-action':check_action_unknown,'races':check_late_reads,'receipt':check_visibility_receipt,'created-receipt':check_created_visibility_receipt,
              'files':check_files_tags,'techpack':check_techpack,'techpack-layout':check_techpack_layout,'drafts':check_drafts_and_read_errors,'draft-people':check_draft_people,'home':check_home_palette,'scope':check_scope_change,'list-context':check_list_context_options,'home-pack-only':check_home_pack_only,'home-context':check_home_context_reload}
    with (ARTIFACTS/'vite.log').open('w') as log:
        process=subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',str(port),'--strictPort'],cwd=ROOT/'web',stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None:raise RuntimeError('Vite exited')
                try:
                    _,writer=await asyncio.open_connection('127.0.0.1',port);writer.close();await writer.wait_closed();break
                except OSError:await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options={'headless':True}
                if os.environ.get('SCM_UI_CHROMIUM'):options['executable_path']=os.environ['SCM_UI_CHROMIUM']
                browser=await playwright.chromium.launch(**options)
                try:
                    for name,test in commands.items():
                        if only and name not in only:continue
                        report['running']=name
                        try:await test(browser,'http://127.0.0.1:'+str(port),report)
                        except Exception as error:
                            if not collect_failures:raise
                            report.setdefault('failures',[]).append({'group':name,'message':type(error).__name__+': '+str(error)})
                        print(json.dumps({'completed':name,'checks':len(report['checks'])}),flush=True)
                finally:await browser.close()
        except BaseException as error:report['failure']=type(error).__name__+': '+str(error);raise
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            shutil.rmtree(fixture,ignore_errors=True)
            files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'web/src').rglob('*')) if p.is_file()}
            report['source_sha256']=hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()
            report['screenshots']=sorted(p.name for p in ARTIFACTS.glob('*.png'))
            (ARTIFACTS/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps(report,ensure_ascii=False,indent=2))
    assert not report['page_errors'],report['page_errors']
    assert not report['console_errors'],report['console_errors']
    assert not report.get('failures'),report.get('failures')
    assert all(item['passed'] for item in report['checks'].values())


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--only',nargs='+',choices=['list','create','edit','company','nullable','order','dispatch','nodes','review','decisions','convert','unknown-action','races','receipt','created-receipt','files','techpack','techpack-layout','drafts','draft-people','home','scope','list-context','home-pack-only','home-context']);parser.add_argument('--artifacts',type=Path)
    parser.add_argument('--collect-failures',action='store_true');args=parser.parse_args()
    if args.artifacts:ARTIFACTS=args.artifacts.resolve()
    asyncio.run(run(args.only,args.collect_failures))
