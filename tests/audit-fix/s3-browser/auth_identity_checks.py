"""Real auth API/request/user store: old /me responses must not alter a new login."""
import asyncio
from urllib.parse import urlsplit


async def run_auth_identity(browser, base, artifacts, metrics):
    for result in ['success', 'body_401', 'http_401']:
        context = await browser.new_context()
        page = await context.new_page()
        page.on('pageerror', lambda error: metrics['page_errors'].append(str(error)))
        started = asyncio.Event()
        release = asyncio.Event()
        counts = {'login': 0, 'me': 0}

        async def api(route):
            path = urlsplit(route.request.url).path
            if path == '/api/auth/login':
                counts['login'] += 1
                await route.fulfill(json={'code': 0, 'msg': '', 'data': 'synthetic-' + str(counts['login'])})
                return
            assert path == '/api/auth/me', path
            counts['me'] += 1
            old = counts['me'] == 1
            if old:
                started.set()
                await asyncio.wait_for(release.wait(), timeout=10)
            if old and result != 'success':
                await route.fulfill(status=401 if result == 'http_401' else 200, json={'code': 401, 'msg': '合成旧账号已过期', 'data': None})
                return
            await route.fulfill(json={'code': 0, 'msg': '', 'data': {
                'id': '9007199254740993' if old else '9007199254740995',
                'username': 'synthetic-original' if old else 'synthetic-next',
                'nickname': '合成旧账号' if old else '合成新账号', 'roles': [],
                'perms': ['synthetic:old:permission'] if old else ['synthetic:new:permission'],
                'menus': [], 'defaultCompany': {'id': '1', 'name': '合成主体'},
            }})

        await context.route('**/api/auth/**', api)
        try:
            await page.goto(base + '/node_modules/.s3-audit/auth-identity.html')
            await page.wait_for_function('Boolean(window.__authIdentity)')
            await page.evaluate('window.__oldMeOutcome = null; void window.__authIdentity.me().then(() => { window.__oldMeOutcome = 0 }, error => { window.__oldMeOutcome = error.code ?? -1 })')
            await asyncio.wait_for(started.wait(), timeout=10)
            await page.evaluate('window.__authIdentity.switchAccount()')
            release.set()
            await page.wait_for_function('window.__oldMeOutcome !== null')
            state = await page.evaluate('window.__authIdentity.state()')
            outcome = await page.evaluate('window.__oldMeOutcome')
            passed = (state['id'] == '9007199254740995'
                      and state['perms'] == ['synthetic:new:permission']
                      and state['identity'] == 'user:9007199254740995'
                      and state['token'] == 'synthetic-2' and outcome in [-1, 409]
                      and counts == {'login': 2, 'me': 2})
            metrics['checks'][f'auth_me_late_{result}'] = {
                'passed': passed, 'outcome': outcome, 'subject_id': state['id'],
                'new_permissions_preserved': state['perms'] == ['synthetic:new:permission'],
                'new_token_preserved': state['token'] == 'synthetic-2',
                'identity_preserved': state['identity'] == 'user:9007199254740995',
                'requests': counts,
            }
        finally:
            await context.close()
