import json,os,re
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright,expect
from harness import ApiClient,read_fixture,line_values
base=os.environ['SCM_UI_BASE_URL'];api=ApiClient(os.environ['SCM_UI_API_BASE']);api.login();f=read_fixture(os.environ['SCM_UI_FIXTURE'])
order=api.request('POST','/api/orders',f['orderTemplate']);order_id=order['id']
shots=Path(os.environ['SCM_UI_SCREENSHOTS']);shots.mkdir(parents=True,exist_ok=True)
evidence={'synthetic':True,'phase':'before','orderBefore':line_values(api.request('GET',f'/api/orders/{order_id}')),'pages':[],'consoleErrors':[],'draftDecisions':[]}
with sync_playwright() as pw:
 browser=pw.chromium.launch(executable_path=os.environ.get('SCM_UI_CHROMIUM'),headless=True)
 context=browser.new_context(viewport={'width':1440,'height':900},timezone_id='Asia/Shanghai')
 # Init scripts also run on opaque about:blank documents before navigation.
 # Write the real local login only on the exact test UI origin; retain errors
 # from that application document instead of swallowing storage exceptions.
 context.add_init_script("if (location.origin === "+json.dumps(base)+") { localStorage.setItem('lingmao-token', "+json.dumps(api.token)+"); }")
 context.route(re.compile(r'^https?://'),lambda route:route.continue_() if urlsplit(route.request.url).hostname in {'127.0.0.1','localhost'} else route.abort())
 page=context.new_page();page.on('pageerror',lambda e:evidence['consoleErrors'].append(str(e)))
 # Revisited forms may offer the synthetic draft saved during the screenshot tour.
 # Capture server state through the normal UI choice; keep all other dialogs fatal.
 draft_dialog=page.get_by_role('dialog',name='恢复草稿',exact=True)
 def discard_browser_draft(dialog):
  dialog.get_by_role('button',name='放弃草稿',exact=True).click()
  evidence['draftDecisions'].append({'route':urlsplit(page.url).path,'decision':'discard'})
 # The handler also covers a prompt that appears after navigation has gone idle.
 page.add_locator_handler(draft_dialog,discard_browser_draft)
 for name,path in [('orders','/orders'),('order-matrix',f'/orders/{order_id}/edit'),('techpack-bom',f"/styles/{f['styleId']}?tab=techpack&techPackId={f['packId']}&sub=bom"),('techpack-cost',f"/styles/{f['styleId']}?tab=techpack&techPackId={f['packId']}&sub=cost"),('count-detail',f"/stock-takes/{f['countId']}"),('purchases-empty','/purchases'),('stock-takes','/stock-takes')]:
  page.goto(base+path);page.wait_for_load_state('networkidle');expect(draft_dialog).not_to_be_visible();page.screenshot(path=str(shots/(name+'.png')),full_page=True,animations='disabled');evidence['pages'].append({'name':name,'route':path,'url':urlsplit(page.url).path,'textChars':len(page.inner_text('body'))})
 # Record the original SKU corruption using the real form and API; anonymous draft only.
 page.goto(base+f'/orders/{order_id}/edit');page.wait_for_load_state('networkidle')
 expect(draft_dialog).not_to_be_visible()
 if page.locator('.style-block').count():
  with page.expect_response(lambda r:r.request.method=='PUT' and urlsplit(r.url).path==f'/api/orders/{order_id}') as response:
   page.get_by_role('button',name='保存',exact=True).click()
  evidence['orderSaveCode']=response.value.json()['code'];evidence['orderAfter']=line_values(api.request('GET',f'/api/orders/{order_id}'))
 browser.close()
(shots/'evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'pages':len(evidence['pages']),'consoleErrors':len(evidence['consoleErrors']),'matrixChanged':evidence.get('orderBefore')!=evidence.get('orderAfter'),'shots':str(shots)},ensure_ascii=False))
