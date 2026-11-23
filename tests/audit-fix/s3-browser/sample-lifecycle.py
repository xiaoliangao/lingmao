#!/usr/bin/env python3
"""Actual cached Vue pages with controlled HTTP; no backend/database claim."""
import argparse
import asyncio
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess

from playwright.async_api import async_playwright, expect
import samples
from sample_http import IMAGE, PACK, SAMPLE, STYLE, USER, SampleHttp

ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = ROOT / 'test-artifacts/full-audit/s3/sample-lifecycle'
TEST_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
spec = importlib.util.spec_from_file_location('techpack_reactivation', Path(__file__).with_name('techpack-reactivation.py'))
assert spec and spec.loader
reactivation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reactivation)
PERMISSIONS = reactivation.PERMISSIONS + ['dev:sample:list', 'data:all']


def source_sha():
    files = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in sorted((ROOT / 'web/src').rglob('*')) if path.is_file()}
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def requests(api, path):
    return [call for call in api.calls if call['path'] == path]


def aborted(api, path):
    return [row for row in api.aborted if row['path'] == path]


async def visible(locator):
    try:
        await expect(locator).to_be_visible(timeout=3000)
        return True
    except AssertionError:
        return False


async def return_to_tab(page, held, label, path):
    await page.get_by_test_id('sample-home').click()
    await expect(page.get_by_test_id('sample-path')).to_have_text('/home')
    held['release'].set()
    await asyncio.wait_for(held['finished'].wait(), 5)
    await page.get_by_role('navigation', name='打开的页面').get_by_role('link', name=label, exact=True).click()
    await expect(page.get_by_test_id('sample-path')).to_have_text(path)


async def check_origin(browser, base, report):
    api = reactivation.PackHttp()
    target = '/api/samples/by-style/' + STYLE
    held = api.hold(target)
    context, page, api, console = await samples.start(browser, base, report, '/styles/' + STYLE,
                                                     api=api, permissions=PERMISSIONS)
    try:
        await asyncio.wait_for(held['started'].wait(), 5)
        origin = page.get_by_role('region', name='来源样衣')
        await expect(origin.get_by_role('status')).to_have_text('正在读取来源样衣…')
        await return_to_tab(page, held, '款式 KS-SYN-001', '/styles/' + STYLE)
        link = origin.get_by_role('link', name='YY-SYN-001 · 合成雾蓝衬衫样衣', exact=True)
        found = await visible(link)
        await samples.capture(page, 'style-origin-return.png')
        facts = {'reads': len(requests(api, target)), 'source_visible': found,
                 'loading': await origin.get_by_role('status').count(), 'aborted': aborted(api, target),
                 'writes': len(api.writes)}
        passed = found and facts['reads'] == 2 and facts['loading'] == 0 and bool(facts['aborted']) and not api.writes
        report['checks']['style_sample_origin_resumes'] = {'passed': passed, **facts}
        assert passed, facts
        await expect(link).to_have_attribute('href', '/samples/' + SAMPLE + '?companyId=1')
    finally:
        held['release'].set()
        await samples.finish(context, page, api, console, report)


async def check_options(browser, base, report):
    api = SampleHttp()
    target = '/api/categories/tree'
    held = api.hold(target)
    context, page, api, console = await samples.start(browser, base, report, '/samples?companyId=1',
                                                     api=api, permissions=PERMISSIONS)
    try:
        await asyncio.wait_for(held['started'].wait(), 5)
        await expect(page.get_by_text('YY-SYN-001', exact=True)).to_be_visible()
        await return_to_tab(page, held, '样衣', '/samples')
        await expect(page.get_by_text('YY-SYN-001', exact=True)).to_be_visible()
        await page.get_by_role('button', name='筛选', exact=True).click()
        filters = page.get_by_role('dialog', name='筛选条件', exact=True)
        category = filters.locator('.filter-item').filter(has=page.locator('.label', has_text='品类'))
        await category.locator('.el-select').click()
        option = page.get_by_role('option', name='上装', exact=True)
        found = await visible(option)
        await samples.capture(page, 'sample-category-return.png')
        selected = False
        if found:
            await option.click()
            selected = await visible(category.get_by_text('上装', exact=True))
            await samples.capture(page, 'sample-category-selected.png')
        facts = {'reads': len(requests(api, target)), 'category_visible': found,
                 'category_selected': selected, 'aborted': aborted(api, target), 'writes': len(api.writes)}
        passed = found and selected and facts['reads'] == 2 and bool(facts['aborted']) and not api.writes
        report['checks']['sample_category_options_resume'] = {'passed': passed, **facts}
        assert passed, facts
    finally:
        held['release'].set()
        await samples.finish(context, page, api, console, report)


class ReferenceHttp(SampleHttp):
    def read(self, path, params):
        if path == '/api/samples/' + SAMPLE + '/references':
            assert params == {'companyId': ['1']}
            return [{'type': 'STYLE', 'kind': None, 'parentId': None, 'typeLabel': '款式', 'id': STYLE,
                     'no': 'KS-SYN-001', 'status': 'ENABLED', 'statusLabel': '启用', 'statusTone': 'active',
                     'ownerId': USER, 'ownerName': '合成设计师'}]
        return super().read(path, params)


async def check_references(browser, base, report):
    api = ReferenceHttp()
    target = '/api/samples/' + SAMPLE + '/references'
    held = api.hold(target)
    context, page, api, console = await samples.start(browser, base, report, '/samples/' + SAMPLE + '?companyId=1',
                                                     api=api, permissions=PERMISSIONS)
    try:
        await samples.loaded(page)
        await page.get_by_role('tab', name='下游引用', exact=True).click()
        await asyncio.wait_for(held['started'].wait(), 5)
        await expect(page.get_by_text('正在读取下游引用…', exact=True)).to_be_visible()
        await return_to_tab(page, held, '样衣 YY-SYN-001', '/samples/' + SAMPLE)
        link = page.get_by_role('link', name='KS-SYN-001', exact=True)
        found = await visible(link)
        await samples.capture(page, 'sample-references-return.png')
        facts = {'reads': len(requests(api, target)), 'reference_visible': found,
                 'loading': await page.get_by_text('正在读取下游引用…', exact=True).count(),
                 'aborted': aborted(api, target), 'writes': len(api.writes)}
        passed = found and facts['reads'] == 2 and facts['loading'] == 0 and bool(facts['aborted']) and not api.writes
        report['checks']['sample_references_resume'] = {'passed': passed, **facts}
        assert passed, facts
        await expect(link).to_have_attribute('href', '/styles/' + STYLE)
    finally:
        held['release'].set()
        await samples.finish(context, page, api, console, report)


async def check_upload_wait(browser, base, report):
    api = reactivation.PackHttp()
    held = api.hold('/api/files')
    context, page, api, console = await samples.start(browser, base, report,
        '/styles/' + STYLE + '?tab=techpack&sub=craft', api=api, permissions=PERMISSIONS)
    try:
        await expect(page.locator('.tp-wrap .pack-meta')).to_be_visible()
        await page.locator('.tp-wrap .craft-box input[type=file]').first.set_input_files(
            {'name': 'sample-proof.png', 'mimeType': 'image/png', 'buffer': IMAGE})
        await asyncio.wait_for(held['started'].wait(), 5)
        save = page.locator('.tp-wrap .ver-acts').get_by_role('button', name='保存', exact=True)
        await expect(save).to_be_enabled()
        await save.click()
        await expect(save).to_have_class(re.compile(r'is-loading'))
        await samples.capture(page, 'style-saving-waits-for-upload.png')
        await page.get_by_test_id('sample-scope').click()
        await expect(page.locator('.tp-wrap .pack-meta')).to_have_count(0)
        retained = await page.evaluate("async()=>{const{useUserStore}=await import('/src/stores/user.ts');const user=useUserStore();return {id:user.info.id,style:user.hasPerm('style:style:list'),pack:user.hasPerm('techpack:pack:list'),edit:user.hasPerm('techpack:pack:edit'),all:user.hasPerm('data:all')}}")
        assert retained == {'id': USER, 'style': True, 'pack': True, 'edit': True, 'all': False}, retained
        held['release'].set()
        await asyncio.wait_for(held['finished'].wait(), 5)
        await page.get_by_test_id('sample-home').click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/home')
        await page.get_by_role('navigation', name='打开的页面').get_by_role('link', name='款式 KS-SYN-001', exact=True).click()
        await expect(page.get_by_test_id('sample-path')).to_have_text('/styles/' + STYLE)
        recovered = await visible(page.locator('.tp-wrap .pack-meta'))
        if recovered:
            await page.get_by_placeholder('本版说明（选填）', exact=True).fill('新作用域可以继续编辑')
            await expect(save).to_be_enabled()
            await expect(save).not_to_have_class(re.compile(r'is-loading'))
        await samples.capture(page, 'style-upload-scope-return.png')
        business_writes = [call for call in api.calls if call['method'] != 'GET' and call['path'].startswith('/api/tech-packs')]
        facts = {'recovered': recovered, 'retained_permissions': retained,
                 'file_uploads': len(requests(api, '/api/files')), 'business_writes': len(business_writes),
                 'old_uploaded_images': await page.locator('.tp-wrap .image-upload .main-badge').count()}
        passed = recovered and facts['file_uploads'] == 1 and not business_writes and facts['old_uploaded_images'] == 0
        report['checks']['style_upload_wait_clears_on_scope_change'] = {'passed': passed, **facts}
        assert passed, facts
    finally:
        held['release'].set()
        await samples.finish(context, page, api, console, report)


async def check_owner_labels(browser, base, report):
    for kind in ['style', 'sample']:
        is_style = kind == 'style'
        api = reactivation.PackHttp() if is_style else SampleHttp(packs=1)
        path = '/styles/' + STYLE + '?tab=techpack' if is_style else '/samples/' + SAMPLE + '?companyId=1&tab=techpack'
        context, page, api, console = await samples.start(browser, base, report, path, api=api, permissions=PERMISSIONS)
        try:
            box = page.get_by_test_id('bom-inspector')
            await expect(box).to_be_visible()
            label = '当前款式主图' if is_style else '当前样衣主图'
            text = await box.locator('.bom-style-image small').inner_text()
            passed = text.endswith(label)
            await samples.capture(page, kind + '-main-image-caption.png')
            report['checks'][kind + '_main_image_caption'] = {'passed': passed, 'text': text, 'writes': len(api.writes)}
            assert passed and not api.writes, text
        finally:
            await samples.finish(context, page, api, console, report)


async def run(only, collect_failures):
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    samples.ARTIFACTS = ARTIFACTS
    initial_source = source_sha()
    fixture = ROOT / 'web/node_modules/.s3-samples'
    fixture.mkdir(parents=True, exist_ok=True)
    for name in ['samples.html', 'SamplesFixture.vue']:
        shutil.copyfile(Path(__file__).with_name(name), fixture / name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    report = {'scope': 'Actual Vue pages under AppLayout/KeepAlive, strict synthetic HTTP only; no backend or SQL claim.',
              'checks': {}, 'http_checks': [], 'page_errors': [], 'console_errors': [], 'expected_console_errors': [],
              'test_sha256': TEST_SHA256, 'source_sha256': initial_source}
    with (ARTIFACTS / 'vite.log').open('w') as log:
        process = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(port), '--strictPort'],
                                   cwd=ROOT / 'web', stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(200):
                if process.poll() is not None:
                    raise RuntimeError('Vite exited')
                try:
                    _, writer = await asyncio.open_connection('127.0.0.1', port)
                    writer.close(); await writer.wait_closed(); break
                except OSError:
                    await asyncio.sleep(.1)
            async with async_playwright() as playwright:
                options = {'headless': True}
                if os.environ.get('SCM_UI_CHROMIUM'):
                    options['executable_path'] = os.environ['SCM_UI_CHROMIUM']
                browser = await playwright.chromium.launch(**options)
                try:
                    groups = {'origin': check_origin, 'options': check_options, 'references': check_references,
                              'upload': check_upload_wait, 'labels': check_owner_labels}
                    for name, check in groups.items():
                        if only and name not in only:
                            continue
                        report['running'] = name
                        try:
                            await check(browser, f'http://127.0.0.1:{port}', report)
                        except Exception as error:
                            if not collect_failures:
                                raise
                            report.setdefault('failures', []).append({'group': name, 'message': type(error).__name__ + ': ' + str(error)})
                finally:
                    await browser.close()
        except BaseException as error:
            report['failure'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
            shutil.rmtree(fixture, ignore_errors=True)
            report['source_changed'] = source_sha() != initial_source
            report['test_changed'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest() != TEST_SHA256
            report['screenshots'] = sorted(path.name for path in ARTIFACTS.glob('*.png'))
            (ARTIFACTS / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert not report.get('failures'), report.get('failures')
    assert not report['page_errors'], report['page_errors']
    assert not report['console_errors'], report['console_errors']
    assert not report['source_changed'] and not report['test_changed'], report
    assert all(check['passed'] for check in report['checks'].values())


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--only', nargs='+', choices=['origin', 'options', 'references', 'upload', 'labels'])
    parser.add_argument('--collect-failures', action='store_true')
    parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.artifacts:
        ARTIFACTS = args.artifacts.resolve()
    asyncio.run(run(args.only, args.collect_failures))
