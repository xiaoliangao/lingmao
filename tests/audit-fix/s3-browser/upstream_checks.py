"""Real Upstream page: seek paging, stale network cancellation and bounded handling."""
import asyncio
import re
from urllib.parse import parse_qs, urlsplit


async def run_upstream(browser, base, artifacts, metrics):
    context = await browser.new_context(viewport={'width': 1440, 'height': 1080})
    requests = []
    aborted = []
    errors = []
    handled = []
    outstanding = {index: set(range(105 if index == 1 else 110 if index == 2 else 1)) for index in range(1, 53)}
    slow_started = asyncio.Event()
    fail_mrp = True

    def ref(index, kind='PRODUCTION_ORDER'):
        return {'type': kind, 'kind': 'OUTSOURCE' if kind == 'PRODUCTION_ORDER' else None, 'parentId': None, 'typeLabel': {'PRODUCTION_ORDER': '外发加工单', 'PURCHASE_ORDER': '采购单', 'MRP_CALC': '算料单'}[kind], 'id': str(9007199254741000 + index), 'no': f'JG-{index:04d}', 'status': 'RUNNING', 'statusLabel': '生产中', 'statusTone': 'progress', 'ownerId': '9007199254740993', 'ownerName': '回归跟单员'}

    def row(index, kind='PRODUCTION_ORDER'):
        notices = sorted(outstanding[index], reverse=True)[:100]
        source = {**ref(index), 'type': 'ORDER_CHANGE', 'kind': None, 'typeLabel': '订单变更单', 'no': f'BG-{index:04d}', 'status': 'APPROVED', 'statusLabel': '已审核', 'statusTone': 'active'}
        return {'doc': ref(index, kind), 'noticesTotal': len(outstanding[index]), 'notices': [{'id': str(9007199254800000 + index * 1000 + notice), 'source': source, 'content': f'合成变更提醒 {notice + 1}', 'createTime': '2026-11-22 10:00:00', 'handledByName': None, 'handledTime': None, 'handleRemark': None} for notice in notices]}

    async def api(route):
        nonlocal fail_mrp
        parsed = urlsplit(route.request.url)
        path = parsed.path
        if not path.startswith('/api/'):
            await route.continue_()
            return
        if path == '/api/board/upstream':
            params = {key: value[0] for key, value in parse_qs(parsed.query).items()}
            requests.append(params)
            assert params.get('size') == '50', params
            if params.get('type') == 'PRODUCTION_ORDER':
                slow_started.set()
                await asyncio.sleep(1)
                data = {'canSeeAll': False, 'rows': [row(52)], 'total': 999, 'nextCursor': None, 'hasMore': False}
            elif params.get('type') == 'PURCHASE_ORDER':
                data = {'canSeeAll': True, 'rows': [row(index, 'PURCHASE_ORDER') for index in range(3, 8)], 'total': 5, 'nextCursor': None, 'hasMore': False}
            elif params.get('type') == 'MRP_CALC':
                if fail_mrp:
                    await route.fulfill(json={'code': 503, 'msg': '合成上游提醒暂不可用', 'data': None})
                    return
                data = {'canSeeAll': True, 'rows': [row(3, 'MRP_CALC')], 'total': 1, 'nextCursor': None, 'hasMore': False}
            elif params.get('mine') == 'true' or params.get('ownerId'):
                assert 'cursor' not in params, params
                data = {'canSeeAll': True, 'rows': [row(index) for index in range(3, 12)], 'total': 9, 'nextCursor': None, 'hasMore': False}
            else:
                cursor = params.get('cursor')
                assert cursor in [None, 'synthetic-page-2'], params
                indices = range(51, 53) if cursor else range(1, 51)
                data = {'canSeeAll': True, 'rows': [row(index) for index in indices], 'total': 52, 'nextCursor': None if cursor else 'synthetic-page-2', 'hasMore': cursor is None}
            try:
                await route.fulfill(json={'code': 0, 'msg': '', 'data': data})
            except Exception:
                if params.get('type') != 'PRODUCTION_ORDER':
                    raise
        elif path == '/api/users/options':
            await route.fulfill(json={'code': 0, 'msg': '', 'data': [{'id': '9007199254740999', 'nickname': '回归负责人', 'username': 'synthetic-owner'}]})
        elif re.fullmatch(r'/api/production-orders/\d+/notices/\d+/handle', path):
            parts = path.split('/')
            index = int(parts[3]) - 9007199254741000
            notice = int(parts[5]) - 9007199254800000 - index * 1000
            assert notice in outstanding[index], (index, notice)
            outstanding[index].remove(notice)
            handled.append({'doc': index, 'notice': notice})
            await route.fulfill(json={'code': 0, 'msg': '', 'data': None})
        else:
            raise AssertionError(f'unexpected upstream API: {path}')

    async def open_page():
        page = await context.new_page()
        page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
        page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
        page.on('requestfailed', lambda request: aborted.append({'query': urlsplit(request.url).query, 'failure': request.failure}))
        await page.goto(base + '/node_modules/.s3-audit/home-review.html?upstream=1')
        await page.get_by_role('button', name='JG-0001', exact=True).wait_for()
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        return page

    await context.route('**/api/**', api)
    try:
        page = await open_page()
        body_rows = page.locator('.el-table__body-wrapper tbody tr')
        assert await body_rows.count() == 50
        await page.get_by_role('button', name='下一页', exact=True).click()
        await page.get_by_role('button', name='JG-0051', exact=True).wait_for()
        assert await body_rows.count() == 2
        assert await page.get_by_role('button', name='JG-0001', exact=True).count() == 0
        await page.screenshot(path=str(artifacts / 'upstream-page-two.png'), full_page=True)
        await page.get_by_role('button', name='上一页', exact=True).click()
        await page.get_by_role('button', name='JG-0001', exact=True).wait_for()
        assert await body_rows.count() == 50
        assert [item.get('cursor') for item in requests[:3]] == [None, 'synthetic-page-2', None], requests
        metrics['checks']['upstream_cursor_pages_replace'] = {'passed': True, 'page_row_counts': [50, 2, 50]}

        await page.get_by_role('button', name='下一页', exact=True).click()
        await page.get_by_role('button', name='JG-0051', exact=True).wait_for()
        await page.locator('.el-segmented').nth(1).get_by_text('我的', exact=True).click()
        await page.get_by_text('共 9 张', exact=True).wait_for()
        assert requests[-1].get('mine') == 'true' and 'cursor' not in requests[-1]
        assert await page.get_by_role('button', name='上一页', exact=True).is_disabled()
        await page.get_by_role('button', name='筛选', exact=True).click()
        await page.get_by_role('combobox').fill('回归')
        await page.get_by_role('option').filter(has_text='回归负责人').click()
        for _ in range(100):
            if requests[-1].get('ownerId') == '9007199254740999':
                break
            await asyncio.sleep(.05)
        assert requests[-1].get('ownerId') == '9007199254740999' and 'cursor' not in requests[-1] and 'mine' not in requests[-1], requests[-1]
        metrics['checks']['upstream_filter_cursor_reset'] = {'passed': True, 'owner_id': requests[-1]['ownerId']}
        await page.close()

        page = await open_page()
        await page.locator('.el-segmented').first.get_by_text('生产单', exact=True).click()
        await asyncio.wait_for(slow_started.wait(), timeout=10)
        await page.locator('.el-segmented').first.get_by_text('采购单', exact=True).click()
        await page.get_by_text('共 5 张', exact=True).wait_for()
        await page.wait_for_timeout(1200)
        assert await page.get_by_text('共 999 张', exact=True).count() == 0
        assert await page.locator('.el-segmented').count() == 2
        assert any('type=PRODUCTION_ORDER' in request['query'] and request['failure'] == 'net::ERR_ABORTED' for request in aborted), aborted
        metrics['checks']['upstream_filter_transport_abort'] = {'passed': True}
        await page.close()

        page = await open_page()
        await page.get_by_text('显示最近 100 条，共 105 条', exact=True).wait_for()
        await page.screenshot(path=str(artifacts / 'upstream-notice-limit.png'))
        body_rows = page.locator('.el-table__body-wrapper tbody tr')
        await body_rows.nth(0).get_by_role('button', name='已处理', exact=True).click()
        dialog = page.get_by_role('dialog', name='标成已处理', exact=True)
        await dialog.get_by_text('外发加工单 JG-0001 当前展示的 100 条提醒（共 105 条，其余继续保留）', exact=True).wait_for()
        await page.screenshot(path=str(artifacts / 'upstream-handle-100.png'))
        await dialog.get_by_role('button', name='标成已处理', exact=True).click()
        await dialog.wait_for(state='hidden')
        await page.wait_for_function("[...document.querySelectorAll('.el-table__body-wrapper tbody tr')][0]?.querySelectorAll('.notice-line').length === 5")
        assert sum(item['doc'] == 1 for item in handled) == 100 and len(outstanding[1]) == 5
        await body_rows.nth(1).locator('label.el-checkbox').click()
        assert await body_rows.nth(1).get_by_role('checkbox').is_checked()
        await page.get_by_role('toolbar', name='批量操作').get_by_role('button', name='标成已处理', exact=True).click()
        await page.get_by_role('dialog').get_by_role('button', name='标成已处理', exact=True).click()
        await page.wait_for_function("[...document.querySelectorAll('.el-table__body-wrapper tbody tr')][1]?.querySelectorAll('.notice-line').length === 10")
        assert sum(item['doc'] == 2 for item in handled) == 100 and len(outstanding[2]) == 10
        assert await page.get_by_role('button', name='JG-0001', exact=True).count() == 1
        assert await page.get_by_role('button', name='JG-0002', exact=True).count() == 1
        await page.locator('.el-loading-mask').wait_for(state='hidden')
        await page.wait_for_function("document.querySelectorAll('.el-message').length === 0")
        await page.evaluate("window.scrollTo({ top: 0, behavior: 'instant' })")
        await page.screenshot(path=str(artifacts / 'upstream-notices-after.png'))
        metrics['checks']['upstream_bounded_notice_handling'] = {'passed': True, 'single_handled': 100, 'single_remaining': 5, 'batch_handled': 100, 'batch_remaining': 10}

        await page.locator('.el-segmented').first.get_by_text('算料单', exact=True).click()
        await page.get_by_test_id('upstream-load-error').wait_for()
        fail_mrp = False
        await page.get_by_test_id('upstream-load-error').get_by_role('button', name='重试').click()
        await page.get_by_text('共 1 张', exact=True).wait_for()
        assert await page.get_by_test_id('upstream-load-error').count() == 0
        metrics['checks']['upstream_error_retry'] = {'passed': True}
        await page.close()
        metrics['upstream_requests'] = requests
        metrics['upstream_aborted_requests'] = aborted
        metrics['upstream_console_errors'] = errors
        assert not errors, errors
    except Exception:
        await page.screenshot(path=str(artifacts / 'upstream-failure.png'))
        raise
    finally:
        await context.close()
