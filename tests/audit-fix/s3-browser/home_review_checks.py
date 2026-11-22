"""Production Home/OrderAudit components with explicit network fault injection."""
import asyncio
from urllib.parse import parse_qs, urlsplit


async def run_home_review(browser, base, artifacts, metrics):
    context = await browser.new_context(viewport={'width': 1440, 'height': 1080}, timezone_id='America/Los_Angeles')
    await context.add_init_script("localStorage.setItem('scm.home.todoFilter', JSON.stringify({scope:'ALL',ownerId:'9007199254740993',ownerName:'前端回归'}))")
    page = await context.new_page()
    page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
    errors = []
    page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
    aborted = []
    page.on('requestfailed', lambda request: aborted.append({'path': urlsplit(request.url).path, 'failure': request.failure}))
    requested_owners = []
    review_started = {key: asyncio.Event() for key in ['9007199254741001', '9007199254741003']}

    async def api(route):
        parsed = urlsplit(route.request.url)
        path = parsed.path
        if not path.startswith('/api/'):
            await route.continue_()
            return
        if path == '/api/board/home/kpi':
            data = {
                'dev': {'styleCount': 12, 'packDraftCount': 2},
                'order': {'qty': 300, 'count': 3, 'pendingCount': 1, 'unscheduledCount': 1, 'changePendingCount': None},
                'production': {'qty': 200, 'count': 2, 'lateCount': 1, 'nodeLateCount': 0, 'issueCount': 0},
                'material': {'transitLineCount': 4, 'arrivalLateCount': 0, 'purchasePendingCount': 1, 'mrpDraftCount': None},
            }
        elif path == '/api/board/home/todos':
            requested_owners.append(parse_qs(parsed.query).get('ownerId', [None])[0])
            data = {'groups': [], 'counts': [], 'canSeeAll': True}
        elif path == '/api/board/home/deliveries':
            data = []
        elif path.startswith('/api/dict-data/by-type/'):
            data = [{'value': 'METER', 'label': '米', 'status': 1}]
        elif path == '/api/users/options':
            data = [{'id': '9007199254740993', 'nickname': '前端回归', 'username': 'synthetic'}]
        elif path.startswith('/api/board/orders/') and path.endswith('/review'):
            entity_id = path.split('/')[4]
            if entity_id in review_started:
                review_started[entity_id].set()
                await asyncio.sleep(1)
            if entity_id == '9007199254741007':
                await route.fulfill(json={'code': 503, 'msg': '合成评审服务暂不可用', 'data': None})
                return
            data = {
                'packs': [{'styleId': '9007199254741993', 'styleCode': f'KS-{entity_id}', 'styleName': '合成冬季风衣', 'techPackId': '9007199254742993', 'version': 3, 'problem': None}],
                'shortages': [{'materialSkuId': '9007199254743993', 'skuCode': 'ML-001-KQ', 'materialName': '精梳斜纹棉布', 'skuColorName': '卡其', 'skuSpec': '150cm', 'unit': 'METER', 'need': 310, 'available': 180, 'gap': 130, 'leadDays': 7, 'image': None}],
                'loads': [{'factoryId': '9007199254744993', 'factoryName': '合成加工厂', 'styleCodes': ['KS-001'], 'wipQty': 200, 'orderCount': 2}],
            }
        else:
            raise AssertionError(f'unexpected API: {path}')
        try:
            await route.fulfill(json={'code': 0, 'msg': '', 'data': data})
        except Exception:
            if '/api/board/orders/' not in path:
                raise

    await context.route('**/api/**', api)
    try:
        await page.goto(base + '/node_modules/.s3-audit/home-review.html')
        await page.wait_for_function('Boolean(window.__homeReview)')
        await page.get_by_text('在手订单 · 3 张', exact=True).wait_for()
        for _ in range(50):
            if requested_owners:
                break
            await asyncio.sleep(.05)
        assert requested_owners == ['9007199254740993'], requested_owners
        metrics['checks']['home_string_owner_restore'] = {'passed': True, 'owner_id': requested_owners[0]}
        await page.screenshot(path=str(artifacts / 'home-real-component.png'), full_page=True)

        await page.evaluate("window.__homeReview.open('9007199254741001')")
        await asyncio.wait_for(review_started['9007199254741001'].wait(), timeout=10)
        await page.get_by_role('dialog').get_by_role('button', name='取消', exact=True).click()
        await page.wait_for_timeout(200)
        assert any(item['path'].endswith('/9007199254741001/review') and item['failure'] == 'net::ERR_ABORTED' for item in aborted), aborted
        metrics['checks']['review_close_aborts_transport'] = {'passed': True}

        await page.evaluate("window.__homeReview.open('9007199254741003')")
        await asyncio.wait_for(review_started['9007199254741003'].wait(), timeout=10)
        await page.evaluate("window.__homeReview.open('9007199254741005')")
        await page.get_by_text('KS-9007199254741005', exact=True).wait_for()
        await page.wait_for_timeout(1200)
        assert any(item['path'].endswith('/9007199254741003/review') and item['failure'] == 'net::ERR_ABORTED' for item in aborted), aborted
        assert await page.get_by_text('KS-9007199254741003', exact=True).count() == 0
        metrics['checks']['review_order_switch_aborts_transport'] = {'passed': True}
        await page.screenshot(path=str(artifacts / 'order-review-current.png'), full_page=True)

        await page.evaluate("window.__homeReview.open('9007199254741007')")
        await page.get_by_text('评审信息暂不可用', exact=True).wait_for()
        await page.get_by_role('dialog').get_by_role('button', name='重试', exact=True).wait_for()
        await page.screenshot(path=str(artifacts / 'order-review-unavailable.png'), full_page=True)
        metrics['checks']['review_failure_recovery_state'] = {'passed': True}
        metrics['review_aborted_requests'] = aborted
        metrics['home_review_console_errors'] = errors
        assert not errors, errors
    finally:
        await context.close()
