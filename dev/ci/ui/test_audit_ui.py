"""Real-API browser regressions for RFC010 (run after backend + frontend fixes).

Fault injections are explicitly recorded: a cost read failure, withheld/delayed
real responses, and a paused real file upload. Browser time is fixed separately.
No global mocks, production origins, storage_state dumps, login traces or videos.
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import time
import unittest
from datetime import date, datetime, timedelta, timezone
from contextlib import ExitStack
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit
from uuid import uuid4

from playwright.sync_api import expect, sync_playwright

from fixtures.domain_seed import DomainFixture
from fixtures.sample_flow import run_sample_lifecycle
from fixtures.tag_flow import run_tag_lifecycle
from harness import (
    LOCAL_HOSTS, ApiClient, anonymous_png, assert_decimal_equal, business_day, cost_values,
    count_update, line_values, local_url, node_plan, order_update, pack_update, pdf_text, query_path, read_fixture,
    sanitized_error, spreadsheet_rows, write_evidence,
)


class AuditUi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ui_base = local_url("SCM_UI_BASE_URL")
        cls.api = ApiClient(local_url("SCM_UI_API_BASE", required_port=18080))
        fixture_path = os.environ.get("SCM_UI_FIXTURE")
        if not fixture_path:
            raise RuntimeError("SCM_UI_FIXTURE must name a prepared anonymous manifest")
        cls.fixture = read_fixture(fixture_path)
        for key in ("orderTemplate", "packId", "productionPackId", "countId", "approvedCountId", "lifecycle",
                    "alternatePackId", "matrixOrderId", "history", "identities"):
            if key not in cls.fixture:
                raise RuntimeError(f"Prepared fixture is missing {key}")
        me = cls.api.login()
        if me.get("id") != cls.fixture["userId"]:
            raise RuntimeError("Fixture account mismatch")
        cls.domain = DomainFixture(cls.api, cls.fixture)
        cls.artifacts = Path(os.environ.get("SCM_UI_SCREENSHOTS", str(Path(__file__).parent / "screenshots")))
        cls.artifacts.mkdir(parents=True, exist_ok=True)
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(
            executable_path=os.environ.get("SCM_UI_CHROMIUM"), headless=True)
        expect.set_options(timeout=12000)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "browser"):
            cls.browser.close()
        if hasattr(cls, "pw"):
            cls.pw.stop()

    def setUp(self):
        self.case_id = self._testMethodName
        self.case_dir = self.artifacts / self.case_id
        self.case_dir.mkdir(parents=True, exist_ok=True)
        self.contexts = []
        self.pages = []
        self.console_errors = []
        self.page_errors = []
        self.external_requests = []
        self.actor_traces = []
        self.trace_start = len(self.api.calls)
        self.facts = {"schemaVersion": 1, "synthetic": True, "test": self.case_id,
                      "database": self.fixture["database"], "verification": "real API + real Vue UI",
                      "screenshots": [], "faultInjections": [], "assertions": []}
        self.context = self.new_context()
        self.page = self.context.new_page()

    def tearDown(self):
        result = self._outcome.result
        failed = any(case is self or getattr(case, "test_case", None) is self
                     for case, _ in result.failures + result.errors)
        if failed and not self.page.is_closed():
            try:
                self.shot("failure-state", allow_loading=True)
            except Exception:
                self.facts["failureScreenshotUnavailable"] = True
        for context in self.contexts:
            context.close()
        # No network/body/storage logs are persisted. Console failures are sanitized.
        self.facts.update({"passed": not failed and not self.console_errors and not self.page_errors and not self.external_requests,
                           "consoleErrors": self.console_errors, "pageErrors": self.page_errors,
                           "externalRequests": self.external_requests, "apiTrace": self.api.calls[self.trace_start:],
                           "roleApiTrace": [{"role": role, "calls": actor.calls} for role, actor in self.actor_traces]})
        write_evidence(self.case_dir / "evidence.json", self.facts, self.api.token)
        self.assertEqual(self.console_errors, [], "Unexpected browser console errors; inspect sanitized case evidence")
        self.assertEqual(self.page_errors, [], "Unhandled browser exceptions; inspect sanitized case evidence")
        self.assertEqual(self.external_requests, [], "A request attempted to leave the local regression environment")

    def new_context(self, *, zone="Asia/Shanghai", mobile=False, authenticated=True, actor=None):
        context = self.browser.new_context(
            viewport={"width": 390, "height": 844} if mobile else {"width": 1440, "height": 900},
            timezone_id=zone, accept_downloads=True)
        self.contexts.append(context)
        def local_only(route):
            if urlsplit(route.request.url).hostname in LOCAL_HOSTS:
                route.continue_()
            else:
                self.external_requests.append({"method": route.request.method, "host": urlsplit(route.request.url).hostname})
                route.abort("blockedbyclient")
        context.route(re.compile(r"^https?://"), local_only)
        if authenticated:
            # Token comes from the real local login and exists only in memory/browser localStorage.
            # The script also runs in about:blank: only our actual application origin has storage.
            context.add_init_script("if (location.origin === " + json.dumps(self.ui_base) + ") { "
                                    "localStorage.setItem('lingmao-token', " + json.dumps((actor or self.api).token) + "); }")
        def attach(page):
            self.pages.append(page)
            page.on("pageerror", lambda error: self.page_errors.append(sanitized_error(str(error), self.api.token)))
            def console(message):
                if message.type == "error":
                    self.console_errors.append(sanitized_error(message.text, self.api.token))
            page.on("console", console)
        context.on("page", attach)
        return context

    def actor(self, key):
        client = ApiClient(self.api.origin)
        me = client.login(self.fixture["identities"][key]["username"])
        self.assertEqual(me["id"], self.fixture["identities"][key]["userId"])
        self.actor_traces.append((key, client))
        return client

    def shot(self, name, page=None, *, allow_loading=False):
        page = page or self.page
        destination = self.case_dir / f"{name}.png"
        # A diagnostic failure image may intentionally show an unfinished load;
        # acceptance images must show the completed real UI state.
        if not allow_loading:
            expect(page.locator(".el-loading-mask:visible")).to_have_count(0)
        # Failed login assertions must still never capture password/username values.
        page.screenshot(path=str(destination), full_page=not getattr(self, "viewport_only", False), animations="disabled",
                        mask=[page.locator('input[type="password"]'), page.locator('input[autocomplete="username"]')])
        self.facts["screenshots"].append({"file": destination.name, "route": urlsplit(page.url).path,
                                          "width": page.viewport_size["width"]})

    def note(self, name, **data):
        self.facts["assertions"].append({"name": name, **data})

    def poll(self, condition, timeout=12):
        deadline = time.monotonic() + timeout
        while not condition():
            if time.monotonic() > deadline:
                self.fail("Timed out waiting for an explicit regression condition")
            self.page.wait_for_timeout(40)

    @staticmethod
    def response_for(response, method, path):
        return response.request.method == method and urlsplit(response.url).path == path

    def login_in_browser(self, page):
        # Playwright failure call logs can include fill() values. Strip credentials
        # before unittest/CI captures an exception; never persist an auth response.
        try:
            page.get_by_label("用户名", exact=True).fill(os.environ["SCM_UI_USERNAME"])
            page.get_by_label("密码", exact=True).fill(os.environ["SCM_UI_PASSWORD"])
            page.get_by_role("button", name="登录", exact=True).click()
        except Exception as error:
            raise AssertionError(sanitized_error(str(error), self.api.token)) from None

    def open_more(self, label, page=None):
        page = page or self.page
        page.get_by_role("button", name="更多操作", exact=True).click()
        page.get_by_role("menuitem", name=label, exact=True).click()

    def search(self, path, value, page=None):
        page = page or self.page
        field = page.locator(".filter-bar .search input")
        field.fill(value)
        # A page's initial unfiltered request can still be in flight after goto().
        # Match the actual submitted keyword, not merely the list endpoint.
        with page.expect_response(lambda r: self.response_for(r, "GET", path)
                                  and parse_qs(urlsplit(r.url).query).get("keyword") == [value]) as response:
            field.press("Enter")
        return response.value

    @staticmethod
    def checkbox(scope, label, checked):
        # Element Plus exposes a hidden native input and a visible label. Exercise
        # the label a person clicks, then assert the native control's real state.
        visible_label = scope.locator(".el-checkbox").filter(has_text=re.compile(r"^\s*" + re.escape(label) + r"\s*$"))
        expect(visible_label).to_be_visible()
        control = visible_label.get_by_role("checkbox", name=label, exact=True)
        if control.is_checked() != checked:
            visible_label.click()
        if checked:
            expect(control).to_be_checked()
        else:
            expect(control).not_to_be_checked()

    def test_01_order_sku_roundtrip_precision_and_conflict(self):
        before = self.domain.new_order(marker="AUDIT-SKU")
        path = f"/api/orders/{before['id']}"
        self.page.goto(f"{self.ui_base}/orders/{before['id']}/edit")
        expect(self.page.locator(".style-block")).to_have_count(1)
        remark = self.page.locator(".order-head .el-form-item").filter(
            has=self.page.locator("label", has_text=re.compile(r"^备注$"))).locator("input")
        remark.fill("只改单头，逐SKU原值保留")
        mixed = self.page.get_by_text("多个值", exact=False).or_(self.page.get_by_placeholder("多个值", exact=False))
        expect(mixed.first).to_be_visible()
        self.shot("mixed-sku-before")
        with self.page.expect_response(lambda r: self.response_for(r, "GET", path)) as loaded, \
                self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as response:
            self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(response.value.json()["code"], 0)
        self.assertEqual(loaded.value.json()["code"], 0)
        after = self.api.request("GET", path)
        self.assertEqual(line_values(after), line_values(before))
        assert_decimal_equal(after["items"][0]["price"], 12.3456)
        self.note("per-SKU roundtrip", before=line_values(before), after=line_values(after))
        self.page.wait_for_url(self.ui_base + f"/orders/{before['id']}")
        expect(self.page.locator(".page-header h1")).to_contain_text(before["no"])
        expect(self.page.locator(".sku-matrix").first).to_be_visible()
        self.shot("mixed-sku-saved")

        self.page.goto(f"{self.ui_base}/orders/{before['id']}/edit")
        expect(self.page.locator(".style-block")).to_have_count(1)
        values = self.page.locator(".sku-values")
        values.get_by_text("逐 SKU 单价 / 行交期 / 备注", exact=True).click()
        expect(values.locator(".el-table__body-wrapper tr.el-table__row")).to_have_count(len(after["items"]))
        for row in after["items"]:
            sku_row = values.locator(".el-table__body-wrapper tr.el-table__row").filter(has_text=row["skuCode"])
            expect(sku_row.locator(".el-input-number input")).to_have_value(str(row["price"]))
            expect(sku_row.locator(".el-date-editor input")).to_have_value(row["lineDueDate"] or "")
            expect(sku_row.locator("td").last.locator("input")).to_have_value(row["remark"] or "")
        values.scroll_into_view_if_needed()
        self.shot("mixed-sku-reopened-line-values")
        remark.fill("本地冲突草稿必须保留")
        remote = order_update(after)
        remote["contractNo"] = "AUDIT-REMOTE-" + uuid4().hex[:8]
        self.api.request("PUT", path, remote)
        with self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as response:
            self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(response.value.json()["code"], 1002)
        expect(remark).to_have_value("本地冲突草稿必须保留")
        self.assertEqual(self.api.request("GET", path)["contractNo"], remote["contractNo"])
        self.shot("order-1002-keeps-local")
        self.note("stale version rejected without clobbering local or server", code=1002)
        for label, qty, price in (("unit-price", 1, 10000000000), ("line-amount", 1000000, 1000000000),
                                   ("total-amount", 100, 6000000000)):
            body = copy.deepcopy(self.fixture["orderTemplate"])
            for item in body["items"]:
                item.update(qty=qty, price=price)
            result = self.api.envelope("POST", "/api/orders", body)
            self.assertEqual(result["code"], 400)
            self.note("numeric capacity rejected", boundary=label, code=400)

    def test_02_cost_failure_preserves_data_retry_and_bom_panel(self):
        pack_id = self.fixture["packId"]
        before = self.api.request("GET", f"/api/tech-packs/{pack_id}/cost")
        fail = {"enabled": True, "count": 0}
        def cost_fault(route):
            if fail["enabled"] and route.request.method == "GET":
                fail["count"] += 1
                route.fulfill(status=200, content_type="application/json",
                              body=json.dumps({"code": 500, "msg": "合成故障：核价暂不可用", "data": None}))
            else:
                route.continue_()
        self.facts["faultInjections"].append({"kind": "cost GET envelope failure", "path": f"/api/tech-packs/{pack_id}/cost"})
        self.page.route(re.compile(rf"/api/tech-packs/{pack_id}/cost(?:\?.*)?$"), cost_fault)
        self.page.goto(f"{self.ui_base}/styles/{self.fixture['styleId']}?tab=techpack&techPackId={pack_id}&sub=cost")
        note = self.page.locator(".pack-meta .note-input input")
        expect(note).to_be_visible()
        expect(self.page.get_by_test_id("cost-load-error")).to_be_visible()
        note.fill("成本失败仅保存说明 " + uuid4().hex[:8])
        self.shot("cost-error-with-retry")
        path = f"/api/tech-packs/{pack_id}"
        # Keep the fault active through saveDraft's independent cost reload.
        with self.page.expect_response(lambda r: self.response_for(r, "GET", path + "/cost")) as automatic_cost:
            with self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as response:
                self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(response.value.json()["code"], 0)
        self.assertEqual(automatic_cost.value.json()["code"], 500)
        expect(self.page.get_by_test_id("cost-load-error")).to_be_visible()
        self.assertIsNone(response.value.request.post_data_json["cost"])
        self.assertEqual(cost_values(self.api.request("GET", path + "/cost")), cost_values(before))
        note.fill("成本重试时仍未保存的说明")
        fail["enabled"] = False
        with self.page.expect_response(lambda r: self.response_for(r, "GET", path + "/cost")) as retried:
            self.page.get_by_test_id("cost-load-error").get_by_role("button", name=re.compile("重试")).click()
        self.assertEqual(retried.value.json()["code"], 0)
        expect(self.page.get_by_test_id("cost-load-error")).not_to_be_visible()
        expect(note).to_have_value("成本重试时仍未保存的说明")
        self.note("cost:null retained real cost", faultCount=fail["count"], before=cost_values(before), retryCode=0)
        expect(self.page.locator(".el-message:visible")).to_have_count(0)
        self.shot("cost-retry-success")
        self.page.get_by_role("tab", name=re.compile("物料清单")).click()
        panel = self.page.get_by_test_id("bom-inspector")
        expect(panel).to_be_visible()
        row = self.page.get_by_test_id("bom-row").first
        row.hover()
        card = self.page.get_by_test_id("bom-row-card")
        expect(card).to_contain_text("ML0001")
        row.focus()
        expect(card).to_contain_text("ML0001")
        expect(panel.get_by_text(re.compile("暂无.*图|没有.*图|未上传")).first).to_be_visible()
        self.shot("bom-keyboard-card-empty-image")

    def test_03_count_dirty_watermark_refresh_and_post_submit_movement(self):
        count_id = self.fixture["countId"]
        path = f"/api/inventory-counts/{count_id}"
        self.page.goto(f"{self.ui_base}/stock-takes/{count_id}")
        quantity = self.page.locator(".el-table__body-wrapper input[role=spinbutton]").first
        expect(quantity).to_be_visible()
        quantity.fill("17.125")
        quantity.press("Tab")
        self.page.locator('.page-header a[href="/stock-takes"]').click()
        self.page.locator(f'.ptabs a[href="/stock-takes/{count_id}"]').click()
        expect(quantity).to_have_value("17.125")
        self.page.locator(".ptab").filter(has=self.page.locator(f'a[href="/stock-takes/{count_id}"]')).get_by_role("button").click()
        expect(self.page.get_by_text("有没保存的修改", exact=False)).to_be_visible()
        warning = self.page.locator(".el-message-box:visible")
        expect(warning).to_have_count(1)
        self.page.wait_for_function("""element => {
            for (let node = element; node; node = node.parentElement) {
                const style = getComputedStyle(node);
                if (Number(style.opacity) < 1 || style.visibility !== 'visible' || style.display === 'none') return false;
            }
            return element.getAnimations({subtree: true}).every(animation => animation.playState === 'finished');
        }""", arg=warning.element_handle())
        self.shot("count-close-unsaved-warning")
        self.page.get_by_role("button", name="继续编辑", exact=True).click()
        expect(self.page.locator(".el-message-box:visible")).to_have_count(0)
        expect(quantity).to_have_value("17.125")
        self.shot("count-dirty-survives-activation")
        with self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as response:
            self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(response.value.json()["code"], 0)
        before = self.api.request("GET", path)
        self.assertIsInstance(before["items"][0]["bookRevision"], int)
        balance = self.domain.stock_qty()
        self.domain.stock_movement(1)
        self.domain.stock_movement(-1)
        assert_decimal_equal(self.domain.stock_qty(), balance)
        conflict = self.api.envelope("POST", path + "/submit", {"version": before["version"]})
        self.assertEqual(conflict["code"], 1002)
        self.assertEqual(self.api.request("GET", path)["status"], "DRAFT")
        self.note("net-zero movement still invalidates count", balance=balance, code=1002)
        self.open_more("重新取账面数")
        dialog = self.page.locator(".el-message-box")
        expect(dialog).to_be_visible()
        expect(self.page.get_by_role("menuitem", name="重新取账面数", exact=True)).not_to_be_visible()
        expect(dialog).to_contain_text("清空")
        self.shot("count-refresh-clears-warning")
        with self.page.expect_response(lambda r: self.response_for(r, "POST", path + "/refresh-book")) as refreshed:
            dialog.get_by_role("button", name="重新取", exact=True).click()
        self.assertEqual(refreshed.value.json()["code"], 0)
        expect(dialog).not_to_be_visible()
        expect(quantity).to_have_value("")
        current = self.api.request("GET", path)
        self.assertIsNone(current["items"][0]["countedQty"])
        self.assertGreater(current["items"][0]["bookRevision"], before["items"][0]["bookRevision"])
        self.shot("count-refresh-cleared")
        self.api.request("PUT", path, count_update(current, balance))
        pending = self.domain.submit("/api/inventory-counts", count_id)
        self.domain.stock_movement(1)
        self.api.request("POST", path + "/audit", {"version": pending["version"]})
        approved = self.api.request("GET", path)
        self.assertEqual(approved["status"], "APPROVED")
        assert_decimal_equal(self.domain.stock_qty(), balance + 1)
        self.api.request("POST", path + "/unaudit", {"version": approved["version"]})
        returned = self.api.request("GET", path)
        self.assertEqual(returned["status"], "DRAFT")
        assert_decimal_equal(self.domain.stock_qty(), balance + 1)
        assert_decimal_equal(returned["items"][0]["countedQty"], balance)
        self.assertIsInstance(returned["items"][0]["bookRevision"], int)
        # 不在这里再提交：test_04 要这张单还是草稿；反审核后直接再提交由 InventoryIT 覆盖
        self.note("post-submit movement retained; unaudit keeps counts and re-baselines", approved=True,
                  bookRevisionAfterUnaudit=returned["items"][0]["bookRevision"])

    def test_04_exports_match_successful_filter_snapshot(self):
        order_draft = self.domain.new_order(marker="AUDIT-EXPORT")
        order_pending = self.domain.submit("/api/orders", self.domain.new_order(marker="AUDIT-EXPORT")["id"])
        purchase_draft = self.domain.new_purchase()
        purchase_other = self.api.request("GET", f"/api/purchase-orders/{self.fixture['lifecycle']['purchaseId']}")
        count_draft = self.api.request("GET", f"/api/inventory-counts/{self.fixture['countId']}")
        count_other = self.api.request("GET", f"/api/inventory-counts/{self.fixture['approvedCountId']}")
        modules = [("orders", "/orders", "/api/orders", order_draft, order_pending, "DD"),
                   ("purchases", "/purchases", "/api/purchase-orders", purchase_draft, purchase_other, "CG"),
                   ("counts", "/stock-takes", "/api/inventory-counts", count_draft, count_other, "PD")]
        for name, route, api_path, draft, other, prefix in modules:
            with self.subTest(module=name):
                self.page.goto(self.ui_base + route + "?status=DRAFT")
                response = self.search(api_path, draft["no"] + " " + other["no"])
                self.assertEqual(response.json()["code"], 0)
                requested = parse_qs(urlsplit(response.url).query)
                self.assertEqual(requested.get("keyword"), [draft["no"] + " " + other["no"]])
                self.assertEqual(requested.get("status"), ["DRAFT"])
                shown = response.json()["data"]["list"]
                self.assertEqual({x["id"] for x in shown}, {draft["id"]})
                expect(self.page.locator(".el-table__body-wrapper tr.el-table__row")).to_have_count(len(shown))
                expect(self.page.locator(".el-table__body-wrapper").get_by_text(draft["no"], exact=True)).to_be_visible()
                self.shot(name + "-applied-filter")
                # Editing a search box without Enter must not change the export snapshot.
                self.page.locator(".filter-bar .search input").fill("AUDIT-UNAPPLIED-" + uuid4().hex[:8])
                with self.page.expect_download() as download, self.page.expect_response(
                    lambda r: self.response_for(r, "GET", api_path + "/export")) as exported:
                    self.page.get_by_role("button", name="导出", exact=True).click()
                params = parse_qs(urlsplit(exported.value.url).query)
                self.assertEqual(params.get("status"), ["DRAFT"])
                self.assertEqual(params.get("keyword"), [draft["no"] + " " + other["no"]])
                self.assertNotIn("statusTab", params)
                file_path = self.case_dir / (name + ".xlsx")
                download.value.save_as(str(file_path))
                raw = file_path.read_bytes()
                nos = {cell for row in spreadsheet_rows(raw) for cell in row if re.fullmatch(prefix + r"\d+", cell)}
                self.assertEqual(nos, {row["no"] for row in shown})
                self.note("export equals real successful list", module=name, expectedNos=sorted(nos),
                          sha256=hashlib.sha256(raw).hexdigest(), size=len(raw))
                if name == "orders":
                    self.page.locator(".el-table__body-wrapper .el-checkbox").first.click()
                    with self.page.expect_download() as selected_download, self.page.expect_response(
                        lambda r: self.response_for(r, "GET", api_path + "/export")) as selected:
                        self.page.get_by_role("button", name="导出选中", exact=True).click()
                    selected_params = parse_qs(urlsplit(selected.value.url).query)
                    self.assertEqual(selected_params.get("status"), ["DRAFT"])
                    self.assertEqual(selected_params.get("keyword"), params.get("keyword"))
                    selected_file = self.case_dir / "orders-selected.xlsx"
                    selected_download.value.save_as(str(selected_file))
                    selected_nos = {cell for row in spreadsheet_rows(selected_file.read_bytes()) for cell in row
                                    if re.fullmatch(r"DD\d+", cell)}
                    self.assertEqual(selected_nos, {draft["no"]})
                    self.note("selected export stays inside query snapshot", ids=[draft["id"]])

    def test_05_login_rejects_unsafe_redirects(self):
        cases = [("internal", "/orders?status=DRAFT#audit", "/orders"),
                 ("protocol-relative", "//example.invalid/path", "/home"),
                 ("absolute", "https://example.invalid/path", "/home"),
                 ("script", "javascript:window.__auditBad=1", "/home"),
                 ("backslash", "/\\example.invalid/path", "/home"),
                 ("control", "/\norders", "/home"),
                 ("array", None, "/home")]
        for label, destination, expected in cases:
            with self.subTest(case=label):
                context = self.new_context(authenticated=False)
                page = context.new_page()
                query = ("redirect=" + quote(destination, safe="")) if destination is not None else "redirect=%2Forders&redirect=%2Fpurchases"
                page.goto(f"{self.ui_base}/login?{query}")
                self.login_in_browser(page)
                page.wait_for_url(re.compile(rf"{re.escape(self.ui_base + expected)}(?:[?#].*)?$"))
                self.assertEqual(urlsplit(page.url).netloc, urlsplit(self.ui_base).netloc)
                self.assertIsNone(page.evaluate("window.__auditBad"))
                expect(page.locator(".page-header h1")).to_have_text("需求订单" if expected == "/orders" else "首页")
                self.shot("redirect-" + label, page)
                self.note("same-origin post-login navigation", case=label, path=expected)
                context.close()
                self.contexts.remove(context)

    def test_06_real_lifecycle_receipt_retry_and_ledger_values(self):
        flow = self.fixture["lifecycle"]
        before_qty = self.domain.stock_qty()
        source = self.api.request("POST", f"/api/inventory-docs/from-purchase/{flow['purchaseId']}", flow["sourceRequest"])
        self.assertEqual(source["id"], flow["sourceReceiptId"])
        assert_decimal_equal(self.domain.stock_qty(), before_qty)
        changed = copy.deepcopy(flow["sourceRequest"])
        changed["remark"] = "不同请求不得复用动作ID"
        self.assertEqual(self.api.envelope("POST", f"/api/inventory-docs/from-purchase/{flow['purchaseId']}", changed)["code"], 1002)
        logs = self.api.rows("/api/stock-logs", {"keyword": flow["sourceReceiptNo"]})
        self.assertTrue(logs)
        self.assertTrue(all(row["valuationStatus"] == "RECORDED" for row in logs))
        self.assertTrue(all(row["unitPrice"] is not None and row["amount"] is not None and row["stockUnit"] for row in logs))
        self.note("source action replay is idempotent and ledger is valued", receiptId=source["id"], logCount=len(logs), unchangedQty=before_qty)
        prod_id = flow["productionIds"]["OUTSOURCE"]
        before = self.api.request("GET", f"/api/production-orders/{prod_id}")
        route_path = f"/api/production-orders/{prod_id}/receipts"
        sent = []
        saved_result = []
        def lost_response(route):
            if route.request.method != "POST":
                route.continue_()
                return
            body = route.request.post_data_json
            sent.append(copy.deepcopy(body))
            response = route.fetch()  # REAL service writes before the first response is withheld.
            envelope = response.json()
            self.assertEqual(envelope["code"], 0)
            if len(sent) == 1:
                saved_result.append(envelope["data"])
                route.fulfill(status=200, content_type="application/json", body=json.dumps(
                    {"code": 500, "msg": "合成故障：服务已处理但响应结果未知，请重试原登记", "data": None}))
            else:
                route.fulfill(response=response)
        self.page.route(re.compile(rf"/api/production-orders/{prod_id}/receipts$"), lost_response)
        self.facts["faultInjections"].append({"kind": "withhold first result after real receipt POST", "path": route_path,
                                              "transport": "HTTP 200 error envelope; not a browser network-timeout test"})
        self.page.goto(f"{self.ui_base}/production/{prod_id}")
        self.page.get_by_role("button", name="登记回货", exact=True).click()
        dialog = self.page.get_by_role("dialog", name=re.compile("登记回货"))
        for field in dialog.locator(".sku-matrix").first.locator("input").all():
            field.fill("2")
        with self.page.expect_response(lambda r: self.response_for(r, "POST", route_path)) as failed:
            dialog.get_by_role("button", name="登记", exact=True).click()
        self.assertEqual(failed.value.json()["code"], 500)
        expect(dialog).to_be_visible()
        expect(dialog.locator(".el-dialog__title")).to_be_in_viewport(ratio=1)
        expect(dialog.get_by_role("button", name="重试原登记", exact=True)).to_be_in_viewport(ratio=1)
        self.shot("receipt-outcome-unknown")
        dialog.get_by_role("button", name="取消", exact=True).click()
        expect(dialog).not_to_be_visible()
        self.page.get_by_role("button", name="登记回货", exact=True).click()
        expect(dialog.get_by_role("button", name="重试原登记", exact=True)).to_be_visible()
        with self.page.expect_response(lambda r: self.response_for(r, "GET", f"/api/production-orders/{prod_id}")) as retry_loaded, \
                self.page.expect_response(lambda r: self.response_for(r, "POST", route_path)) as retried:
            dialog.get_by_role("button", name="重试原登记", exact=True).click()
        self.assertEqual(retried.value.json()["code"], 0)
        # The successful retry refreshes the detail asynchronously. Consume that
        # response before the later browser reload can discard its resource ID.
        retry_detail = retry_loaded.value.json()
        self.assertEqual(retry_detail["code"], 0)
        expect(dialog).not_to_be_visible()
        self.assertEqual(sent[0], sent[1])
        self.assertTrue(re.fullmatch(r"[0-9a-fA-F-]{36}", sent[0]["clientActionId"]))
        self.assertEqual(retried.value.json()["data"]["id"], saved_result[0]["id"])
        after = self.api.request("GET", f"/api/production-orders/{prod_id}")
        expected_increment = sum(x["qty"] for x in sent[0]["items"])
        self.assertEqual(after["returnedQty"] - before["returnedQty"], expected_increment)
        self.assertEqual(retry_detail["data"]["returnedQty"], after["returnedQty"])
        new_action = copy.deepcopy(sent[0])
        new_action["clientActionId"] = str(uuid4())
        new_result = self.api.request("POST", route_path, new_action)
        self.assertNotEqual(new_result["id"], saved_result[0]["id"])
        final = self.api.request("GET", f"/api/production-orders/{prod_id}")
        self.assertEqual(final["returnedQty"] - before["returnedQty"], expected_increment * 2)
        with self.page.expect_response(lambda r: self.response_for(r, "GET", f"/api/production-orders/{prod_id}")) as reloaded:
            self.page.reload()
        reloaded_detail = reloaded.value.json()
        self.assertEqual(reloaded_detail["code"], 0)
        self.assertEqual(reloaded_detail["data"]["returnedQty"], final["returnedQty"])
        expect(self.page.locator(".page-header")).to_contain_text(before["no"])
        # A production detail opens its progress tab; the SKU table is on a
        # different tab. Wait for the live detail action instead of a hidden pane.
        expect(self.page.get_by_role("button", name="登记回货", exact=True)).to_be_visible()
        self.shot("receipt-retry-recorded-once")
        self.note("receipt same action once, genuine next batch twice", firstId=saved_result[0]["id"],
                  secondId=new_result["id"], qtyIncrement=expected_increment, finalIncrement=expected_increment * 2)
        for name, route, endpoint in (("mrp", f"/mrp/{flow['mrpId']}", f"/api/mrp-calcs/{flow['mrpId']}"),
                            ("purchase", f"/purchases/{flow['purchaseId']}", f"/api/purchase-orders/{flow['purchaseId']}"),
                            ("inventory", f"/stock-in/{flow['sourceReceiptId']}", f"/api/inventory-docs/{flow['sourceReceiptId']}"),
                            ("buy-production", f"/fp-purchases/{flow['productionIds']['BUY']}",
                             f"/api/production-orders/{flow['productionIds']['BUY']}")):
            expected_no = self.api.request("GET", endpoint)["no"]
            self.page.goto(self.ui_base + route)
            self.assertEqual(urlsplit(self.page.url).path, route)
            header = ".page-header" if name == "buy-production" else ".page-header h1"
            expect(self.page.locator(header)).to_contain_text(expected_no)
            self.shot("real-flow-" + name)

    def test_07_node_revision_conflict_and_mobile_layout(self):
        prod_id = self.fixture["lifecycle"]["productionIds"]["BUY"]
        path = f"/api/production-orders/{prod_id}"
        before = self.api.request("GET", path)
        self.assertIsInstance(before["planRevision"], int)
        self.page.goto(f"{self.ui_base}/fp-purchases/{prod_id}")
        self.open_more("改节点计划")
        dialog = self.page.get_by_role("dialog", name="改节点计划", exact=True)
        local = (date.fromisoformat(before["nodes"][0]["planStart"]) - timedelta(days=1)).isoformat()
        first_input = dialog.locator(".el-table__body-wrapper input").first
        first_input.fill(local)
        first_input.press("Tab")
        remote = self.api.request("PUT", path + "/nodes", node_plan(before, 1))
        self.assertGreater(remote["planRevision"], before["planRevision"])
        self.assertEqual(self.api.request("GET", path)["version"], before["version"])
        with self.page.expect_response(lambda r: self.response_for(r, "PUT", path + "/nodes")) as response:
            dialog.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(response.value.json()["code"], 1002)
        self.assertEqual(response.value.request.post_data_json["planRevision"], before["planRevision"])
        expect(first_input).to_have_value(local)
        self.assertEqual(self.api.request("GET", path)["planRevision"], remote["planRevision"])
        editors = dialog.locator(".node-plan-table .el-date-editor")
        expect(editors).to_have_count(2 * len(before["nodes"]))
        contained = editors.evaluate_all("""elements => elements.map(element => {
            const cell = element.closest('td');
            if (!cell) return false;
            const input = element.getBoundingClientRect(), bounds = cell.getBoundingClientRect();
            return input.width > 0 && input.left >= bounds.left - 1 && input.right <= bounds.right + 1;
        })""")
        self.assertTrue(contained and all(contained), "Node date editors must stay inside their own table cells")
        self.note("node-plan date editors fit their cells", editorCount=len(contained), tolerancePx=1)
        self.shot("node-plan-conflict-keeps-local")
        self.page.get_by_role("button", name="继续编辑本地草稿", exact=True).click()
        expect(self.page.locator(".el-message-box:visible")).to_have_count(0)
        expect(first_input).to_have_value(local)
        self.shot("node-plan-date-controls-contained")
        self.note("two sessions cannot overwrite independent plan revision", old=before["planRevision"], current=remote["planRevision"], code=1002)
        mobile = self.new_context(mobile=True).new_page()
        mobile.goto(f"{self.ui_base}/mobile/{prod_id}")
        expect(mobile.get_by_role("button", name="上报异常", exact=True)).to_be_visible()
        self.assertTrue(mobile.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"))
        self.shot("mobile-390-no-horizontal-overflow", mobile)

    def test_08_keywords_limits_real_empty_and_error_feedback(self):
        for count in (1, 100, 101):
            codes = ["ML0001"] + [f"AUDITABSENT{i:03d}" for i in range(count - 1)]
            result = self.api.envelope("GET", query_path("/api/materials", {"keyword": " ".join(codes), "page": 1, "size": 500}))
            self.assertEqual(result["code"], 0 if count <= 100 else 400)
            if count <= 100:
                self.assertIn("ML0001", {x["code"] for x in result["data"]["list"]})
            self.note("multi-code boundary", tokenCount=count, code=result["code"])
        self.page.goto(self.ui_base + "/materials")
        search = self.page.locator(".filter-bar .search input")
        search.evaluate("el => { const data = new DataTransfer(); data.setData('text/plain', 'ML0001\\nFL0008'); el.dispatchEvent(new ClipboardEvent('paste', {clipboardData:data,bubbles:true,cancelable:true})); }")
        expect(search).to_have_value("ML0001 FL0008")
        with self.page.expect_response(lambda r: self.response_for(r, "GET", "/api/materials")
                                       and parse_qs(urlsplit(r.url).query).get("keyword") == ["ML0001 FL0008"]) as selected:
            search.press("Enter")
        self.assertEqual({x["code"] for x in selected.value.json()["data"]["list"]}, {"ML0001", "FL0008"})
        expect(self.page.locator(".el-table__body-wrapper tr.el-table__row")).to_have_count(2)
        for code in ("ML0001", "FL0008"):
            expect(self.page.locator(".el-table__body-wrapper").get_by_text(code, exact=True)).to_be_visible()
        self.shot("multi-code-paste-results")
        empty = self.search("/api/materials", "AUDIT-NO-MATCH-" + uuid4().hex)
        self.assertEqual(empty.json()["data"]["total"], 0)
        expect(self.page.locator(".el-table__body-wrapper tr.el-table__row")).to_have_count(0)
        expect(self.page.get_by_text("没有符合条件的物料", exact=True)).to_be_visible()
        self.shot("real-query-empty-state")
        # Both the API and current UI must expose the same 100-code boundary.
        # The UI may reject locally before transport; the real API rejection was
        # asserted above, so no network response is required for this click.
        search.fill(" ".join("BAD" + str(i) for i in range(101)))
        search.press("Enter")
        expect(self.page.locator(".el-loading-mask:visible")).to_have_count(0)
        error_message = self.page.locator(".el-message--error")
        expect(error_message).to_contain_text("100")
        # Real pointer hover pauses Element Plus's normal dismissal timer while
        # the visible error is captured; the error text/state are never injected.
        error_message.hover()
        expect(error_message).to_be_in_viewport(ratio=1)
        self.shot("101-codes-error-feedback")
        expect(error_message).to_be_visible()
        recovered = self.search("/api/materials", "220g 精梳斜纹棉布")
        self.assertEqual(recovered.json()["code"], 0)
        self.assertIn("ML0001", {x["code"] for x in recovered.json()["data"]["list"]})
        self.note("paste normalized, real empty and error recovered", codes=["ML0001", "FL0008"], nameWithSpace=True)

    def test_09_business_dates_and_range_shortcuts_across_timezones(self):
        instant = datetime(2026, 12, 31, 16, 30, tzinfo=timezone.utc)
        expected_ranges = {"今天": ["2027-01-01", "2027-01-01"], "近 7 天": ["2026-12-26", "2027-01-01"],
                           "近 30 天": ["2026-12-03", "2027-01-01"], "本月": ["2027-01-01", "2027-01-31"]}
        self.facts["faultInjections"].append({"kind": "fixed browser clock only", "instant": instant.isoformat(), "businessWrites": False})
        for zone in ("Asia/Shanghai", "UTC", "America/Los_Angeles"):
            with self.subTest(zone=zone):
                context = self.new_context(zone=zone)
                page = context.new_page()
                page.clock.set_fixed_time(instant)
                page.goto(self.ui_base + "/orders/new")
                field = page.locator(".order-head .el-form-item").filter(
                    has=page.locator("label", has_text=re.compile(r"^下单日期$"))).locator("input")
                expect(field).to_have_value("2027-01-01")
                self.shot("default-date-" + zone.replace("/", "-"), page)
                page.goto(self.ui_base + "/orders")
                page.get_by_role("button", name="筛选", exact=True).click()
                for shortcut, expected in expected_ranges.items():
                    page.get_by_placeholder("开始日期", exact=True).click()
                    with page.expect_response(lambda r: self.response_for(r, "GET", "/api/orders")) as response:
                        page.get_by_role("button", name=shortcut, exact=True).click()
                    params = parse_qs(urlsplit(response.value.url).query)
                    self.assertEqual(params.get("orderDateFrom"), [expected[0]])
                    self.assertEqual(params.get("orderDateTo"), [expected[1]])
                    self.note("Shanghai shortcut independent of browser zone", zone=zone, shortcut=shortcut, dates=expected)
                self.shot("range-month-" + zone.replace("/", "-"), page)
                context.close()
                self.contexts.remove(context)

    def test_10_pending_upload_cannot_be_lost_during_save(self):
        pack_id = self.fixture["packId"]
        path = f"/api/tech-packs/{pack_id}"
        before = self.api.request("GET", path)
        original_images = sum(len(x["images"]) for x in before["craftSections"])
        pending = []
        writes = []
        def hold_upload(route):
            if route.request.method == "POST":
                pending.append(route)  # Keep the real request paused until the explicit release below.
            else:
                route.continue_()
        self.page.route(re.compile(r"/api/files$"), hold_upload)
        self.page.on("request", lambda request: writes.append(request) if request.method == "PUT" and urlsplit(request.url).path == path else None)
        self.facts["faultInjections"].append({"kind": "pause real file upload before forwarding", "path": "/api/files"})
        self.page.goto(f"{self.ui_base}/styles/{self.fixture['styleId']}?tab=techpack&techPackId={pack_id}&sub=craft")
        note = self.page.locator(".pack-meta .note-input input")
        expect(note).to_be_visible()
        note.fill("等待真实上传完成后保存 " + uuid4().hex[:8])
        self.page.locator('.tp-wrap .image-upload input[type="file"]').first.set_input_files(
            {"name": "audit-synthetic-swatch.png", "mimeType": "image/png", "buffer": anonymous_png()})
        self.poll(lambda: len(pending) == 1)
        expect(self.page.locator(".image-upload .pending")).to_be_visible()
        save = self.page.get_by_role("button", name="保存", exact=True)
        waiting_save = save.is_enabled()
        if waiting_save:
            save.click()
        self.page.wait_for_timeout(150)
        self.assertEqual(len(writes), 0, "Saving must wait or remain disabled while a real upload is pending")
        self.shot("pending-upload-save-protected")
        with self.page.expect_response(lambda r: self.response_for(r, "POST", "/api/files")) as uploaded:
            pending.pop().continue_()
        self.assertEqual(uploaded.value.json()["code"], 0)
        expect(self.page.locator(".image-upload .pending")).to_have_count(0)
        if not waiting_save:
            expect(save).to_be_enabled()
            with self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as saved:
                save.click()
            self.assertEqual(saved.value.json()["code"], 0)
        else:
            self.poll(lambda: len(writes) == 1)
            self.poll(lambda: sum(len(x["images"]) for x in self.api.request("GET", path)["craftSections"]) == original_images + 1)
        after = self.api.request("GET", path)
        self.assertEqual(sum(len(x["images"]) for x in after["craftSections"]), original_images + 1)
        self.shot("upload-completed-and-persisted")
        self.note("pending real upload waited before whole-pack save", savedImageCount=original_images + 1, pendingWriteCount=0)


    def test_11_late_cost_response_cannot_overwrite_another_pack(self):
        old_id, current_id = self.fixture["packId"], self.fixture["alternatePackId"]
        old_cost = self.api.request("GET", f"/api/tech-packs/{old_id}/cost")
        current_cost = self.api.request("GET", f"/api/tech-packs/{current_id}/cost")
        self.assertNotEqual(old_cost["buyPrice"], current_cost["buyPrice"])
        held = []
        aborted_costs = []
        self.page.on("requestfailed", lambda request: aborted_costs.append(request.failure)
                     if request.method == "GET" and urlsplit(request.url).path == f"/api/tech-packs/{old_id}/cost" else None)
        delayed_once = {"done": False}
        def delay_cost(route):
            if route.request.method == "GET" and not delayed_once["done"]:
                delayed_once["done"] = True
                held.append((route, route.fetch()))  # Preserve a REAL response, then deliver it late.
            else:
                route.continue_()
        self.page.route(re.compile(rf"/api/tech-packs/{old_id}/cost(?:\?.*)?$"), delay_cost)
        self.facts["faultInjections"].append({"kind": "delay real cost response until another pack is current",
                                              "path": f"/api/tech-packs/{old_id}/cost"})
        self.page.goto(f"{self.ui_base}/styles/{self.fixture['styleId']}?tab=techpack&techPackId={old_id}&sub=cost")
        self.poll(lambda: len(held) == 1)
        expect(self.page.locator(".pack-meta .note-input input")).to_be_visible()
        self.page.get_by_test_id(f"techpack-version-{current_id}").click()
        price = self.page.get_by_test_id("cost-buy-price").locator("input")
        expect(price).to_have_value(str(current_cost["buyPrice"]))
        note = self.page.locator(".pack-meta .note-input input")
        note.fill("新版本本地说明不受旧响应覆盖")
        self.shot("current-pack-before-old-cost-arrives")
        route, real_response = held.pop()
        route.fulfill(response=real_response)
        self.poll(lambda: any("ERR_ABORTED" in (reason or "") for reason in aborted_costs))
        self.note("superseded real cost request cancelled", failure="net::ERR_ABORTED")
        self.page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
        expect(price).to_have_value(str(current_cost["buyPrice"]))
        expect(note).to_have_value("新版本本地说明不受旧响应覆盖")
        expect(self.page.get_by_test_id(f"techpack-version-{current_id}")).to_have_attribute("aria-selected", "true")
        with self.page.expect_response(lambda r: self.response_for(r, "GET", f"/api/tech-packs/{current_id}/cost")) as cost_reloaded, \
                self.page.expect_response(lambda r: self.response_for(r, "PUT", f"/api/tech-packs/{current_id}")) as saved:
            self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(saved.value.json()["code"], 0)
        self.assertEqual(cost_reloaded.value.json()["code"], 0)
        assert_decimal_equal(saved.value.request.post_data_json["cost"]["buyPrice"], current_cost["buyPrice"])
        self.assertEqual(cost_values(self.api.request("GET", f"/api/tech-packs/{current_id}/cost")), cost_values(current_cost))
        self.assertEqual(cost_values(self.api.request("GET", f"/api/tech-packs/{old_id}/cost")), cost_values(old_cost))
        self.shot("late-cost-discarded-current-pack-saved")
        costs = self.page.locator(".other-cost-table")
        expect(costs.locator(".el-table__body-wrapper tr.el-table__row")).to_have_count(len(current_cost["otherItems"]))
        cost_rows = costs.locator(".el-table__body-wrapper tr.el-table__row")
        cost_rows.first.evaluate("element => element.scrollIntoView({block: 'center', inline: 'nearest'})")
        editors = costs.locator(".el-table__body-wrapper .el-input-number")
        expect(editors).to_have_count(len(current_cost["otherItems"]))
        contained = editors.evaluate_all("""elements => elements.map(element => {
            const cell = element.closest('td');
            if (!cell) return false;
            const input = element.getBoundingClientRect(), bounds = cell.getBoundingClientRect();
            return input.width > 0 && input.left >= bounds.left - 1 && input.right <= bounds.right + 1;
        })""")
        self.assertTrue(contained and all(contained), "Cost amount editors must stay inside their own table cells")
        # A table row includes border pixels at the scroll viewport edge. Check
        # the actual editable contents against every clipping ancestor instead
        # of requiring the decorative row rectangle to have an exact 1.0 ratio.
        controls = cost_rows.locator("input, button")
        expect(controls).to_have_count(4 * len(current_cost["otherItems"]))
        visible_controls = controls.evaluate_all("""elements => elements.map(element => {
            const rect = element.getBoundingClientRect();
            const clip = {left: 0, top: 0, right: innerWidth, bottom: innerHeight};
            for (let parent = element.parentElement; parent; parent = parent.parentElement) {
                const style = getComputedStyle(parent), box = parent.getBoundingClientRect();
                if (/^(auto|scroll|hidden|clip)$/.test(style.overflowX)) {
                    clip.left = Math.max(clip.left, box.left + parent.clientLeft);
                    clip.right = Math.min(clip.right, box.left + parent.clientLeft + parent.clientWidth);
                }
                if (/^(auto|scroll|hidden|clip)$/.test(style.overflowY)) {
                    clip.top = Math.max(clip.top, box.top + parent.clientTop);
                    clip.bottom = Math.min(clip.bottom, box.top + parent.clientTop + parent.clientHeight);
                }
            }
            return rect.width > 0 && rect.height > 0 && rect.left >= clip.left - 1 &&
                rect.right <= clip.right + 1 && rect.top >= clip.top - 1 && rect.bottom <= clip.bottom + 1 &&
                element.scrollWidth <= element.clientWidth + 1;
        })""")
        self.assertTrue(all(visible_controls), "Cost controls and their text must remain fully visible within clipping ancestors")
        for index, item in enumerate(current_cost["otherItems"]):
            expect(editors.nth(index).locator("input")).to_have_value(str(item["amount"]))
        self.shot("other-cost-row-complete-and-contained")
        self.note("other-cost amount editors fit their cells", editorCount=len(contained),
                  visibleControlCount=len(visible_controls), tolerancePx=1)
        self.note("late response cannot hydrate another pack or erase its dirty input", oldPackId=old_id, currentPackId=current_id)

    def test_12_sku_batch_edit_requires_action_and_respects_size_scope(self):
        body = copy.deepcopy(self.fixture["orderTemplate"])
        options = self.api.request("GET", query_path("/api/orders/style-options", {"styleIds": [self.fixture["styleId"]]}))[0]
        third = next(row for row in options["skus"] if row["sizeCode"] == "S" and row["colorId"] == options["skus"][0]["colorId"])
        body["items"].append({"styleSkuId": third["id"], "qty": 4, "price": 34.5678,
                              "lineDueDate": None, "remark": "S码必须保留的行备注"})
        made = self.api.request("POST", "/api/orders", body)
        path = f"/api/orders/{made['id']}"
        original = self.api.request("GET", path)
        original_rows = {row["styleSkuId"]: row for row in original["items"]}
        def edit():
            self.page.goto(f"{self.ui_base}/orders/{made['id']}/edit")
            expect(self.page.locator(".style-block")).to_have_count(1)
            return self.page.locator(".style-block").first
        def save():
            with self.page.expect_response(lambda r: self.response_for(r, "GET", path)) as loaded, \
                    self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as saved:
                self.page.get_by_role("button", name="保存", exact=True).click()
            self.assertEqual(saved.value.json()["code"], 0)
            self.assertEqual(loaded.value.json()["code"], 0)
            self.page.wait_for_url(f"{self.ui_base}/orders/{made['id']}")
            expect(self.page.locator(".page-header h1")).to_contain_text(made["no"])
            expect(self.page.locator(".sku-matrix").first).to_be_visible()
            return self.api.request("GET", path)
        block = edit()
        self.checkbox(block, "按尺码填单价", True)
        block.get_by_test_id("order-size-price-M").locator("input").fill("45.6789")
        self.shot("sku-batch-input-not-applied")
        self.assertEqual(line_values(save()), line_values(original), "Typing into an unapplied batch control cannot rewrite SKU values")
        block = edit()
        self.checkbox(block, "按尺码填单价", True)
        block.get_by_test_id("order-size-price-M").locator("input").fill("45.6789")
        block.get_by_test_id("order-apply-size-price-M").click()
        self.shot("explicit-M-price-applied")
        size_edited = save()
        for row in size_edited["items"]:
            old = original_rows[row["styleSkuId"]]
            assert_decimal_equal(row["price"], 45.6789 if row["sizeCode"] == "M" else old["price"])
            self.assertEqual((row["lineDueDate"], row["remark"]), (old["lineDueDate"], old["remark"]))
        block = edit()
        self.checkbox(block, "按尺码填单价", False)
        block.get_by_test_id("order-batch-price").locator("input").fill("67.8912")
        block.get_by_test_id("order-apply-price").click()
        all_edited = save()
        for row in all_edited["items"]:
            old = original_rows[row["styleSkuId"]]
            assert_decimal_equal(row["price"], 67.8912)
            self.assertEqual((row["lineDueDate"], row["remark"]), (old["lineDueDate"], old["remark"]))
        self.shot("explicit-whole-style-price-saved")
        self.note("unapplied batch leaves originals; size M only; whole style only price",
                  orderId=made["id"], before=line_values(original), afterSize=line_values(size_edited), afterAll=line_values(all_edited))

    def test_13_discarded_upload_late_response_cannot_attach_to_new_pack(self):
        old_id, current_id = self.fixture["packId"], self.fixture["alternatePackId"]
        old_path, current_path = f"/api/tech-packs/{old_id}", f"/api/tech-packs/{current_id}"
        old_before, current_before = self.api.request("GET", old_path), self.api.request("GET", current_path)
        images = lambda detail: [image["url"] for section in detail["craftSections"] for image in section["images"]]
        held = []
        def late_upload(route):
            if route.request.method == "POST":
                held.append((route, route.fetch()))  # File really exists; only the browser completion is delayed.
            else:
                route.continue_()
        self.page.route(re.compile(r"/api/files$"), late_upload)
        self.facts["faultInjections"].append({"kind": "delay real upload success until version is explicitly discarded",
                                              "path": "/api/files"})
        self.page.goto(f"{self.ui_base}/styles/{self.fixture['styleId']}?tab=techpack&techPackId={old_id}&sub=craft")
        self.page.locator('.tp-wrap .image-upload input[type="file"]').first.set_input_files(
            {"name": "audit-discarded-swatch.png", "mimeType": "image/png", "buffer": anonymous_png()})
        self.poll(lambda: len(held) == 1)
        expect(self.page.locator(".image-upload .pending")).to_be_visible()
        self.page.get_by_test_id(f"techpack-version-{current_id}").click()
        confirm = self.page.locator(".el-message-box")
        expect(confirm).to_contain_text(re.compile("上传|没保存|未保存"))
        self.shot("upload-pending-is-dirty-before-discard")
        confirm.get_by_role("button", name="不要了", exact=True).click()
        expect(self.page.get_by_test_id(f"techpack-version-{current_id}")).to_have_attribute("aria-selected", "true")
        note = self.page.locator(".pack-meta .note-input input")
        note.fill("换版后独立说明必须保留")
        route, response = held.pop()
        with self.page.expect_response(lambda r: self.response_for(r, "POST", "/api/files")) as arrived:
            route.fulfill(response=response)
        self.assertEqual(arrived.value.json()["code"], 0)
        self.page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
        expect(self.page.locator(".image-upload .pending")).to_have_count(0)
        expect(note).to_have_value("换版后独立说明必须保留")
        with self.page.expect_response(lambda r: self.response_for(r, "PUT", current_path)) as saved:
            self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(saved.value.json()["code"], 0)
        self.assertEqual(images(self.api.request("GET", current_path)), images(current_before))
        self.assertEqual(images(self.api.request("GET", old_path)), images(old_before))
        self.shot("late-upload-ignored-after-version-discard")
        self.note("discarded file completion cannot attach to either pack", oldPackId=old_id, currentPackId=current_id,
                  serverFileRetained=True, crossPackAttachmentCount=0)

    def test_14_self_roles_current_approver_and_real_list_entry(self):
        actors = {key: self.actor(key) for key in ("owner", "outsider", "auditor", "designated", "all_reader", "no_list")}
        owner_domain = DomainFixture(actors["owner"], self.fixture)
        identity = self.fixture["identities"]["designated"]
        types = [("ORDER", "/api/orders", "/orders", self.domain.new_order, owner_domain.new_order),
                 ("PURCHASE_ORDER", "/api/purchase-orders", "/purchases", self.domain.new_purchase, owner_domain.new_purchase),
                 ("PRODUCTION_ORDER", "/api/production-orders", "/production", self.domain.new_production, owner_domain.new_production)]
        for biz_type, base, ui_route, create_foreign, create_owned in types:
            with self.subTest(domain=biz_type):
                foreign, own = create_foreign(), create_owned()
                # The shared production list requires the menu's kind; both
                # anonymous role fixtures are CMT / OUTSOURCE drafts.
                list_scope = {"kind": "OUTSOURCE"} if biz_type == "PRODUCTION_ORDER" else {}
                if list_scope:
                    self.assertEqual((foreign["kind"], own["kind"]), ("OUTSOURCE", "OUTSOURCE"))
                for actor_key in ("outsider", "auditor", "designated"):
                    rows = actors[actor_key].rows(base, {**list_scope, "keyword": foreign["no"], "mine": False})
                    self.assertEqual(rows, [], f"{actor_key} SELF must not become ALL")
                    self.assertEqual(actors[actor_key].envelope("GET", f"{base}/{foreign['id']}")["code"], 404)
                self.assertEqual({row["id"] for row in actors["owner"].rows(base, {**list_scope, "keyword": own["no"]})}, {own["id"]})
                self.assertEqual({row["id"] for row in actors["all_reader"].rows(base, {**list_scope, "keyword": foreign["no"]})}, {foreign["id"]})
                self.assertNotIn("audit", actors["all_reader"].request("GET", f"{base}/{foreign['id']}")["actions"])
                self.assertEqual(actors["no_list"].envelope("GET", query_path(base, list_scope))["code"], 403)
                denied = self.api.envelope("POST", "/api/approval-rules", {
                    "bizType": biz_type, "overAmount": 0, "roleId": None,
                    "userId": self.fixture["identities"]["no_list"]["userId"]})
                self.assertEqual(denied["code"], 1000)
                rule = self.api.request("POST", "/api/approval-rules", {
                    "bizType": biz_type, "overAmount": 0, "roleId": None, "userId": identity["userId"]})
                try:
                    candidate = create_foreign()
                    # Permission removal after configuration must also block submission.
                    self.api.request("PUT", f"/api/users/{identity['userId']}/roles", {"ids": []})
                    try:
                        refused = self.api.envelope("POST", f"{base}/{candidate['id']}/submit", {"version": candidate["version"]})
                        self.assertEqual(refused["code"], 1000)
                        self.assertEqual(self.api.request("GET", f"{base}/{candidate['id']}")["status"], "DRAFT")
                    finally:
                        self.api.request("PUT", f"/api/users/{identity['userId']}/roles", {"ids": [identity["roleId"]]})
                    pending = self.domain.submit(base, candidate["id"])
                    contradictory = {**list_scope, "keyword": pending["no"], "auditForMe": True, "mine": True, "ownerId": -999}
                    self.assertEqual({row["id"] for row in actors["auditor"].rows(base, contradictory)}, {pending["id"]})
                    self.assertEqual(actors["designated"].rows(base, contradictory), [])
                    first_pass = {"version": pending["version"]}
                    if biz_type == "ORDER":
                        first_pass["commitDate"] = pending["requiredDate"]
                    self.api.request("POST", f"{base}/{pending['id']}/audit", first_pass)
                    current = self.api.request("GET", f"{base}/{pending['id']}")
                    self.assertEqual(current["status"], "PENDING")
                    self.assertEqual(actors["auditor"].rows(base, contradictory), [])
                    self.assertEqual({row["id"] for row in actors["designated"].rows(base, contradictory)}, {current["id"]})
                    self.assertIn("audit", actors["designated"].request("GET", f"{base}/{current['id']}")["actions"])
                    self.assertEqual(actors["outsider"].envelope("GET", f"{base}/{current['id']}")["code"], 404)
                    page = self.new_context(actor=actors["designated"]).new_page()
                    change_reads = []
                    if biz_type == "ORDER":
                        self.assertEqual(actors["designated"].envelope("GET", query_path(
                            "/api/order-changes", {"orderId": current["id"], "page": 1, "size": 100}))["code"], 403)
                        page.on("request", lambda request, captured=change_reads: captured.append(urlsplit(request.url).path)
                                if request.method == "GET" and urlsplit(request.url).path == "/api/order-changes" else None)
                    with page.expect_response(lambda r: self.response_for(r, "GET", base)) as listed:
                        page.goto(self.ui_base + ui_route + "?status=PENDING&auditForMe=1")
                    self.assertEqual(listed.value.json()["code"], 0)
                    self.assertIn(parse_qs(urlsplit(listed.value.url).query).get("auditForMe"), (["true"], ["1"]))
                    expect(page.get_by_test_id("audit-for-me-toggle")).to_be_visible()
                    expect(page.locator(".el-table__body-wrapper").get_by_text(current["no"], exact=True).first).to_be_visible()
                    self.shot(biz_type.lower() + "-designated-in-real-list", page)
                    page.locator(".el-table__body-wrapper").get_by_text(current["no"], exact=True).first.click()
                    header = ".page-header" if biz_type == "PRODUCTION_ORDER" else ".page-header h1"
                    expect(page.locator(header)).to_contain_text(current["no"])
                    expect(page.get_by_role("button", name="审核", exact=True)).to_be_visible()
                    self.shot(biz_type.lower() + "-designated-can-audit", page)
                    audit_path = f"{base}/{current['id']}/audit"
                    if biz_type == "ORDER":
                        page.get_by_role("tab", name="变更", exact=True).click()
                        expect(page.get_by_text("当前账号没有查看变更单的权限", exact=True)).to_be_visible()
                        self.assertEqual(change_reads, [], "Order access must not request a separately forbidden change list")
                        expect(page.locator(".el-message--error").filter(has_text="没有权限")).to_have_count(0)
                        self.shot("order-approver-change-permission-explained", page)
                        page.get_by_role("tab", name=re.compile(r"^明细")).click()
                        self.note("order approver can view order without requesting forbidden change list",
                                  changesReadCode=403, browserChangeReadCount=len(change_reads))
                        with page.expect_response(lambda r: self.response_for(r, "GET", f"/api/board/orders/{current['id']}/review")) as review:
                            page.get_by_role("button", name="审核", exact=True).click()
                        self.assertEqual(review.value.json()["code"], 0)
                        review_data = review.value.json()["data"]
                        self.assertGreater(len(review_data["packs"]), 0)
                        self.assertTrue(all(isinstance(pack["styleId"], str) for pack in review_data["packs"]))
                        self.assertFalse(any(pack.get("problem") for pack in review_data["packs"]))
                        self.assertIsInstance(review_data["shortages"], list)
                        self.assertIsInstance(review_data["loads"], list)
                        dialog = page.get_by_role("dialog", name=re.compile("审核"))
                        expect(dialog.get_by_text("评审信息暂不可用", exact=False)).not_to_be_visible()
                        expect(dialog.get_by_role("heading", name="资料包", exact=True)).to_be_visible()
                        expect(dialog.get_by_role("button", name="通过", exact=True)).to_be_enabled()
                        self.note("current approver receives real order review", code=0, packs=review_data["packs"],
                                  shortages=len(review_data["shortages"]), factoryLoads=len(review_data["loads"]))
                        self.shot("order-review-real-current-approver", page)
                        with page.expect_response(lambda r: self.response_for(r, "POST", audit_path)) as passed:
                            dialog.get_by_role("button", name="通过", exact=True).click()
                    else:
                        with page.expect_response(lambda r: self.response_for(r, "POST", audit_path)) as passed:
                            page.get_by_role("button", name="审核", exact=True).click()
                    self.assertEqual(passed.value.json()["code"], 0)
                    self.assertEqual(self.api.request("GET", f"{base}/{current['id']}")["status"], "APPROVED")
                    self.assertEqual(actors["designated"].rows(base, contradictory), [])
                    self.assertEqual(actors["designated"].envelope("GET", f"{base}/{current['id']}")["code"], 404)
                    with page.expect_response(lambda r: self.response_for(r, "GET", base)) as refreshed:
                        page.goto(self.ui_base + ui_route + "?status=PENDING&auditForMe=1")
                    self.assertEqual(refreshed.value.json()["code"], 0)
                    self.assertNotIn(current["id"], {row["id"] for row in refreshed.value.json()["data"]["list"]})
                    expect(page.locator(".el-table__body-wrapper").get_by_text(current["no"], exact=True)).to_have_count(0)
                    self.shot(biz_type.lower() + "-approval-left-pending-list", page)
                    self.note("six identities and current approval scope", domain=biz_type, foreignDraftId=foreign["id"],
                              ownedDraftId=own["id"], approvedId=current["id"], configurationGuard=1000,
                              submissionGuard=1000, noListCode=403, noAuditPermissionRequiredForAssignedLevel=True)
                finally:
                    self.api.request("DELETE", f"/api/approval-rules/{rule}")

    def test_15_no_cost_authority_cannot_replace_cost_fields(self):
        editor = self.actor("pack_editor")
        pack_id = self.fixture["packId"]
        path = f"/api/tech-packs/{pack_id}"
        before = self.api.request("GET", path + "/cost")
        self.assertEqual(editor.envelope("GET", path + "/cost")["code"], 403)
        detail = editor.request("GET", path)
        self.assertNotIn("unitCost", detail)
        self.assertNotIn("buyPrice", detail)
        for item in detail["bomItems"]:
            self.assertNotIn("refPrice", item)
            for sku in item["skus"]:
                self.assertNotIn("refPrice", sku)
        # Preserve an unchanged response subtree and all top-level field names for the final review.
        # This is an explicit excerpt of anonymous fixture data, not an authentication/session dump.
        write_evidence(self.case_dir / "no-cost-real-api.json", {
            "synthetic": True, "source": "GET " + path, "responseTopLevelKeys": sorted(detail),
            "responseExcerpt": {"id": detail["id"], "bomItems": detail["bomItems"]},
            "verification": "Actual response before any UI projection; top-level unitCost/buyPrice and nested refPrice are physically absent",
        }, editor.token)
        forged = pack_update(detail, {"costMode": "BUY", "buyPrice": 999.9999,
                                      "items": [{"name": "无权限伪造成本", "amount": 99.99, "remark": None}]})
        forged["versionNote"] = "有包编辑权限的合法说明"
        self.assertEqual(editor.envelope("PUT", path, forged)["code"], 403)
        self.assertEqual(editor.request("GET", path)["versionLock"], detail["versionLock"])
        self.assertEqual(cost_values(self.api.request("GET", path + "/cost")), cost_values(before))
        page = self.new_context(actor=editor).new_page()
        page.goto(f"{self.ui_base}/styles/{self.fixture['styleId']}?tab=techpack&techPackId={pack_id}&sub=craft")
        expect(page.locator(".pack-meta .note-input input")).to_be_visible()
        expect(page.get_by_role("tab", name="核价", exact=True)).to_have_count(0)
        page.locator(".pack-meta .note-input input").fill("无成本权限只存资料包说明")
        with page.expect_response(lambda r: self.response_for(r, "PUT", path)) as saved:
            page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(saved.value.json()["code"], 0)
        self.assertNotIn("cost", saved.value.request.post_data_json)
        self.assertEqual(cost_values(self.api.request("GET", path + "/cost")), cost_values(before))
        self.shot("pack-editor-without-cost-authority", page)
        self.note("Service rejects forged cost without a version change; ordinary UI edit omits financial fields", costReadCode=403,
                  original=cost_values(before), final=cost_values(self.api.request("GET", path + "/cost")))

    def test_16_historical_snapshot_survives_disabled_color_and_real_pdf(self):
        history = self.fixture["history"]
        style_id, pack_id = history["styleId"], history["packId"]
        path = f"/api/tech-packs/{pack_id}"
        original = self.api.request("GET", path)
        self.assertEqual(original["contextSource"], "SNAPSHOT")
        old_style_name = original["styleName"]
        old_color = next(row for row in original["colors"] if row["id"] == history["colorIds"][1])
        self.api.request("POST", f"/api/styles/{style_id}/colors/{old_color['id']}/disable")
        self.api.request("PUT", f"/api/colors/{old_color['id']}", {**history["colorForms"][1], "name": "合成历史乙色新名"})
        self.api.request("PUT", f"/api/styles/{style_id}", {**history["styleForm"], "name": "合成历史款新名"})
        frozen = self.api.request("GET", path)
        self.assertEqual((frozen["styleName"], frozen["colors"], frozen["sizes"], frozen["baseSize"]),
                         (original["styleName"], original["colors"], original["sizes"], original["baseSize"]))
        private = DomainFixture(self.api, {**self.fixture, "styleId": style_id})
        replacement = private.create_pack("PROD")
        voided = self.api.request("GET", path)
        self.assertEqual(voided["status"], "VOIDED")
        self.assertEqual(voided["contextSource"], "SNAPSHOT")
        self.assertEqual(voided["colors"], original["colors"])
        status, headers, raw = self.api.raw("GET", query_path(path + "/pdf", {"colorId": old_color["id"], "price": False, "appendix": False}))
        self.assertEqual(status, 200)
        self.assertIn("application/pdf", headers.get("Content-Type", ""))
        extracted = pdf_text(raw)
        self.assertIn(old_style_name, extracted)
        self.assertIn(old_color["name"], extracted)
        self.assertNotIn("合成历史款新名", extracted)
        self.assertNotIn("合成历史乙色新名", extracted)
        (self.case_dir / "snapshot-voided-api.pdf").write_bytes(raw)
        self.page.goto(f"{self.ui_base}/styles/{style_id}?tab=techpack&techPackId={pack_id}&sub=bom")
        expect(self.page.get_by_test_id(f"techpack-version-{pack_id}")).to_have_attribute("aria-selected", "true")
        self.shot("voided-pack-with-frozen-context")
        self.page.get_by_role("button", name="导出 PDF", exact=True).click()
        dialog = self.page.get_by_role("dialog", name="导出工艺单", exact=True)
        dialog.get_by_role("combobox").click()
        self.page.get_by_role("option", name=f"{old_color['code']} {old_color['name']}", exact=True).click()
        self.checkbox(dialog, "附工艺标准", False)
        self.shot("snapshot-disabled-color-export-option")
        with self.page.expect_download() as downloaded:
            dialog.get_by_role("button", name="导出", exact=True).click()
        target = self.case_dir / "snapshot-voided-ui.pdf"
        downloaded.value.save_as(str(target))
        actual = pdf_text(target.read_bytes())
        self.assertIn(old_style_name, actual)
        self.assertIn(old_color["name"], actual)
        self.assertNotIn("合成历史款新名", actual)
        self.assertNotIn("合成历史乙色新名", actual)
        self.note("real API and browser PDF preserve published owner/color context", historicalPackId=pack_id,
                  replacementPackId=replacement, colorId=old_color["id"], contextSource="SNAPSHOT", status="VOIDED",
                  apiPdfSha256=hashlib.sha256(raw).hexdigest(), uiPdfSha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                  containsOriginalNames=True, containsLiveRenamedValues=False)


    def test_17_remaining_changed_pages_and_open_cancel(self):
        page = self.page
        mutations = []

        def record_mutation(request):
            if request.method in {"POST", "PUT", "PATCH", "DELETE"} and urlsplit(request.url).path.startswith("/api/"):
                mutations.append({"method": request.method, "path": urlsplit(request.url).path})

        page.on("request", record_mutation)

        def read_result(response, path, expected_code=0):
            envelope = response.json()
            self.assertEqual(envelope["code"], expected_code, f"Real GET failed: {path}")
            self.note("real smoke GET", path=path, code=envelope["code"])
            return envelope.get("data")

        def reads_during(paths, action):
            # All expectations are armed before the real UI action; no timing sleeps.
            with ExitStack() as stack:
                waits = [(path, stack.enter_context(page.expect_response(
                    lambda response, target=path: self.response_for(response, "GET", target)))) for path in paths]
                action()
            return {path: read_result(wait.value, path) for path, wait in waits}

        def ready_table(scope, column, data):
            rows = data.get("list", []) if isinstance(data, dict) else data
            virtual = scope.locator(".virtual-data-table:visible")
            if virtual.count():
                expect(virtual.locator(".el-table-v2__header-cell").filter(has_text=column).first).to_be_visible()
                if rows:
                    expect(virtual.locator(".el-table-v2__row:visible").first).to_be_visible()
                else:
                    expect(virtual.locator(".virtual-empty:visible").first).to_have_text(re.compile(r"\S"))
                return
            expect(scope.get_by_role("columnheader", name=column, exact=True).first).to_be_visible()
            if rows:
                expect(scope.locator(".el-table__body-wrapper tr.el-table__row:visible").first).to_be_visible()
            else:
                expect(scope.locator(".el-table__empty-text:visible").first).to_have_text(re.compile(r"\S"))

        def go(route, api_path, shot_name=None, column=None):
            result = reads_during([api_path], lambda: page.goto(self.ui_base + route))[api_path]
            self.assertEqual(urlsplit(page.url).path, urlsplit(route).path)
            expect(page.locator(".page-header h1")).to_have_text(re.compile(r"\S"))
            if column:
                ready_table(page, column, result)
            if shot_name:
                self.shot(shot_name)
            return result

        def cancel(dialog):
            dialog.get_by_role("button", name="取消", exact=True).click()
            expect(dialog).not_to_be_visible()

        def sku_picker(trigger, name):
            result = reads_during(["/api/materials/pick", "/api/material-categories/tree"], trigger)
            picker = page.get_by_role("dialog", name="从物料库选", exact=True)
            ready_table(picker, "物料", result["/api/materials/pick"])
            self.shot(name)
            cancel(picker)

        # Master lists and a new style's actual head/detail selectors, without saving.
        styles = go("/styles", "/api/styles")
        expect(page.locator(".cards .card")).to_have_count(len(styles["list"]))
        self.shot("styles-default-cards")
        page.locator('.filter-bar [aria-label="视图"]').get_by_text("表格", exact=True).click()
        ready_table(page, "款号", styles)
        self.shot("styles-list")
        with page.expect_response(lambda r: self.response_for(r, "GET", "/api/styles/code-preview")) as previewed:
            reads_during(["/api/categories/tree", "/api/size-groups/options"],
                         lambda: page.get_by_role("button", name="新建款式", exact=True).click())
        preview = previewed.value.json()
        self.assertIn(preview["code"], (0, 1000))
        expect(page.locator(".page-header h1")).to_have_text("新建款式")
        expect(page.get_by_label("款式名称", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="建款式", exact=True)).to_be_enabled()
        expect(page.get_by_text("正在算款号……", exact=True)).not_to_be_visible()
        if preview["code"] == 0:
            expect(page.get_by_text("保存时编号，大概是", exact=False)).to_be_visible()
        else:
            expect(page.get_by_text(preview["msg"], exact=True)).to_be_visible()
        self.note("real new-style preview completed", code=preview["code"])
        self.shot("style-new-head-and-files")
        files_heading = page.get_by_role("heading", name="颜色、图片、附件", exact=True)
        files_heading.scroll_into_view_if_needed()
        expect(page.locator(".image-upload")).to_be_visible()
        expect(page.get_by_text("设计附件（图稿、设计说明）", exact=True)).to_be_visible()
        self.shot("style-new-color-image-and-files-surface")
        sku_picker(lambda: page.get_by_role("button", name="从物料库选", exact=True).click(), "style-new-material-picker")
        page.locator(".page-header").get_by_role("button", name="取消", exact=True).click()
        page.wait_for_url(self.ui_base + "/styles")

        go("/suppliers", "/api/suppliers", "suppliers-list", "简称")
        reads_during(["/api/categories/tree"], lambda: page.get_by_role("button", name="新建供应商", exact=True).click())
        dialog = page.get_by_role("dialog", name="新建供应商", exact=True)
        expect(dialog.get_by_label("简称", exact=True)).to_be_visible()
        self.shot("supplier-new-open-cancel")
        dialog.get_by_role("button", name="取消", exact=True).scroll_into_view_if_needed()
        expect(dialog.get_by_role("button", name="取消", exact=True)).to_be_in_viewport(ratio=1)
        expect(dialog.get_by_role("button", name="新建供应商", exact=True)).to_be_in_viewport(ratio=1)
        self.shot("supplier-new-lower-fields-and-footer")
        cancel(dialog)

        costing = go("/costing", "/api/style-costs", column="核算版本")
        expected_codes = [row["styleCode"] for row in costing["list"]]
        self.assertTrue(any(len(code) >= 15 for code in expected_codes), "The real fixture must exercise a long style code")
        codes = page.locator(".el-table__body-wrapper .name-cell .code")
        expect(codes).to_have_text(expected_codes)
        code_bounds = codes.evaluate_all("""elements => elements.map(element => {
            const range = document.createRange();
            range.selectNodeContents(element);
            const text = range.getBoundingClientRect(), cell = element.closest('td');
            if (!cell || text.width <= 0 || text.height <= 0) return false;
            const bounds = cell.getBoundingClientRect();
            if (text.left < Math.max(0, bounds.left) - 1 || text.right > Math.min(innerWidth, bounds.right) + 1) return false;
            for (let node = element.parentElement; node; node = node.parentElement) {
                const style = getComputedStyle(node);
                if (!['hidden', 'auto', 'scroll', 'clip'].includes(style.overflowX)) continue;
                const box = node.getBoundingClientRect(), left = box.left + node.clientLeft;
                if (text.left < left - 1 || text.right > left + node.clientWidth + 1) return false;
            }
            const line = parseFloat(getComputedStyle(element).lineHeight);
            return Number.isFinite(line) && text.height <= line + 1;
        })""")
        self.assertTrue(all(code_bounds), "Costing style codes must be fully visible beside their thumbnails")
        self.note("costing complete style codes fit beside thumbnails", count=len(code_bounds), longestCodeLength=max(map(len, expected_codes)), tolerancePx=1)
        self.shot("costing-list")
        go("/mrp", "/api/mrp-calcs", "mrp-list", "单号")
        reads_during(["/api/mrp-calcs/reserve-pcts"], lambda: page.get_by_role("button", name="备料比", exact=True).click())
        dialog = page.get_by_role("dialog", name="备料比", exact=True)
        expect(dialog.get_by_role("button", name="保存", exact=True)).to_be_visible()
        self.shot("mrp-reserve-dialog")
        cancel(dialog)
        pick = reads_during(["/api/mrp-calcs/order-options"], lambda: page.get_by_role("button", name="选订单算料", exact=True).click())
        dialog = page.get_by_role("dialog", name="选订单算料", exact=True)
        options = pick["/api/mrp-calcs/order-options"]
        ready_table(dialog, "订单号", options)
        option_rows = options["list"] if isinstance(options, dict) else options
        labels = {"DRAFT": "草稿", "CONFIRMED": "已确认", "CLOSED": "已关闭"}
        expected_calcs = [f"{calc['no']} {labels[calc['status']]}" for option in option_rows for calc in option["calcs"]]
        self.assertTrue(expected_calcs, "The real fixture must exercise an already calculated order")
        calcs = dialog.locator(".calc")
        expect(calcs).to_have_text(expected_calcs)
        fits = calcs.evaluate_all("""elements => elements.map(element => {
            const cell = element.closest('td'), table = element.closest('.el-table__body-wrapper');
            if (!cell || !table) return false;
            const box = element.getBoundingClientRect(), bounds = cell.getBoundingClientRect(), area = table.getBoundingClientRect();
            const line = parseFloat(getComputedStyle(element).lineHeight);
            return box.width > 0 && box.left >= Math.max(0, bounds.left, area.left) - 1 &&
                box.right <= Math.min(innerWidth, bounds.right, area.right) + 1 &&
                box.height <= line + 1 && element.scrollWidth <= element.clientWidth + 1;
        })""")
        self.assertTrue(all(fits), "MRP numbers and complete statuses must fit on one visible line")
        self.note("MRP picker number and status visible on one line", count=len(fits), tolerancePx=1)
        self.shot("mrp-order-picker")
        cancel(dialog)
        go("/fp-purchases", "/api/production-orders", "buy-production-list", "单号")

        # One stock page, three independent real endpoints. UNKNOWN remains unknown.
        go("/stock", "/api/stocks", "stock-detail", "仓库")
        stock_summary = reads_during(["/api/stocks/summary"],
            lambda: page.locator(".page-header").get_by_text("按物料", exact=True).click())
        ready_table(page, "工厂净托管", stock_summary["/api/stocks/summary"])
        expect(page.get_by_text("不代表工厂当前实盘余料", exact=False)).to_be_visible()
        self.shot("stock-material-summary-custody")
        stock_logs = reads_during(["/api/stock-logs/seek"],
            lambda: page.locator(".page-header").get_by_text("日志", exact=True).click())
        log_page = stock_logs["/api/stock-logs/seek"]
        self.assertEqual(set(log_page), {"list", "nextCursor", "hasMore"})
        self.assertIsInstance(log_page["hasMore"], bool)
        if log_page["hasMore"]:
            self.assertIsInstance(log_page["nextCursor"], str)
        else:
            self.assertIsNone(log_page["nextCursor"])
        ready_table(page, "单价 / 金额", log_page)
        if any(row["valuationStatus"] == "UNKNOWN" for row in log_page["list"]):
            expect(page.get_by_text("估值未知", exact=True).first).to_be_visible()
        self.shot("stock-ledger-valued-or-unknown")
        export_create_path = "/api/export-jobs/stock-logs"
        job_reads = []

        def record_export_read(response):
            if response.request.method == "GET" and re.fullmatch(r"/api/export-jobs/[0-9]+", urlsplit(response.url).path):
                job_reads.append(response)

        # Observe the UI's real polling before creation, including a fast worker.
        page.on("response", record_export_read)
        try:
            with page.expect_response(lambda response: self.response_for(response, "POST", export_create_path)) as created:
                page.locator(".page-header").get_by_role("button", name="导出", exact=True).click()
            self.assertEqual(created.value.status, 202)
            created_body = created.value.json()
            self.assertEqual(created_body["code"], 0)
            job = created_body["data"]
            self.assertIsInstance(job["id"], str)
            self.assertRegex(job["id"], r"^[1-9][0-9]*$")
            self.assertEqual(job["kind"], "STOCK_LOGS")
            self.assertIn(job["status"], ("PENDING", "PROCESSING", "RETRY_WAIT", "SUCCEEDED"))
            self.assertRegex(created.value.request.headers.get("idempotency-key", ""),
                             r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            self.assertFalse({"page", "size", "cursor"}.intersection(created.value.request.post_data_json))
            job_path = f"/api/export-jobs/{job['id']}"
            export_dialog = page.get_by_role("dialog", name="库存日志导出", exact=True)
            expect(export_dialog).to_be_visible()
            expect(export_dialog.locator(".export-status dd.code")).to_contain_text(job["id"])
            # A create response may already be complete; still verify the actual status endpoint through the UI.
            if job["status"] == "SUCCEEDED":
                export_dialog.get_by_role("button", name="刷新状态", exact=True).click()
            expect(export_dialog.locator(".export-status-heading strong")).to_have_text("文件已就绪", timeout=60000)
            self.poll(lambda: any(self.response_for(response, "GET", job_path) for response in job_reads))
            observed = []
            for response in job_reads:
                self.assertTrue(self.response_for(response, "GET", job_path), "The export dialog polled a different task")
                self.assertEqual(response.status, 200)
                status = read_result(response, job_path)
                self.assertEqual(status["id"], job["id"])
                self.assertEqual(status["kind"], "STOCK_LOGS")
                observed.append(status)
            completed = observed[-1]
            self.assertEqual(completed["status"], "SUCCEEDED")
            self.assertGreater(completed["rows"], 0)
            self.assertIsNone(completed["errorCode"])
            self.assertEqual(completed["downloadUrl"], job_path + "/file")
            expect(export_dialog.locator(".export-status")).to_have_attribute("aria-busy", "false")
            expect(export_dialog.get_by_role("button", name="下载文件", exact=True)).to_be_enabled()
            self.shot("stock-ledger-export-job-ready")
        finally:
            page.remove_listener("response", record_export_read)
        with page.expect_download() as ledger_download, page.expect_response(
                lambda response: self.response_for(response, "GET", job_path + "/file")) as ledger_export:
            export_dialog.get_by_role("button", name="下载文件", exact=True).click()
        self.assertEqual(ledger_export.value.status, 200)
        ledger_file = self.case_dir / "stock-ledger-values.xlsx"
        ledger_download.value.save_as(str(ledger_file))
        ledger_bytes = ledger_file.read_bytes()
        ledger_rows = spreadsheet_rows(ledger_bytes)
        self.assertGreater(len(ledger_rows), 1)
        value_headers = ["单价", "金额", "币种", "估值状态", "冲销原流水 ID"]
        self.assertTrue(set(value_headers).issubset(ledger_rows[0]))
        self.assertTrue(any("RECORDED" in row for row in ledger_rows[1:]))
        self.assertTrue(any("UNKNOWN" in row for row in ledger_rows[1:]))
        self.assertEqual(completed["rows"], len(ledger_rows) - 1)
        self.note("real browser stock ledger export contains valuation facts", headers=value_headers,
                  recordedPresent=True, unknownPresent=True, sha256=hashlib.sha256(ledger_bytes).hexdigest(),
                  jobId=job["id"], createStatus=202, polledStatuses=[status["status"] for status in observed],
                  finalStatus=completed["status"], exportedRows=completed["rows"])
        export_dialog.get_by_role("button", name="关闭", exact=True).click()
        expect(export_dialog).not_to_be_visible()

        go("/stock-in?tab=pending", "/api/purchase-orders/pending-items", "pending-arrivals", "采购单")
        # Both panels mount under stock-in; full navigation gives each shown panel a
        # captured real GET instead of assuming v-show tab changes refetch.
        for route, kind, label_pairs, new_types in (
            ("/stock-in?tab=docs", "IN", (("采购入库", "PURCHASE_IN"), ("退料入库", "RETURN_IN"), ("其他入库", "OTHER_IN")), ("退料入库", "其他入库")),
            ("/stock-out", "OUT", (("领料出库", "ISSUE_OUT"), ("采购退货", "PURCHASE_RETURN"), ("调拨", "TRANSFER"), ("其他出库", "OTHER_OUT")), ("调拨", "其他出库")),
        ):
            with self.subTest(surface=kind):
                go(route, "/api/inventory-docs", "inventory-" + kind.lower() + "-all-types", "单号")
                for label, doc_type in label_pairs:
                    with page.expect_response(lambda response: self.response_for(response, "GET", "/api/inventory-docs")) as filtered:
                        page.locator(".filter-bar:visible .el-segmented").first.get_by_text(label, exact=True).click()
                    data = read_result(filtered.value, "/api/inventory-docs")
                    params = parse_qs(urlsplit(filtered.value.url).query)
                    self.assertEqual(params.get("kind"), [kind])
                    self.assertEqual(params.get("docType"), [doc_type])
                    ready_table(page, "单号", data)
                    self.shot("inventory-list-" + doc_type.lower())
                for label in new_types:
                    page.locator(".page-header").get_by_role("button", name="新建", exact=True).click()
                    options = reads_during(["/api/companies/options", "/api/warehouses/options"],
                        lambda target=label: page.get_by_role("menuitem", name=target, exact=True).click())
                    dialog = page.get_by_role("dialog", name="新建" + label, exact=True)
                    expect(dialog.get_by_role("columnheader", name="物料", exact=True)).to_be_visible()
                    expect(dialog.get_by_role("button", name="存草稿", exact=True)).to_be_visible()
                    if label == "退料入库":
                        factory = next(warehouse for warehouse in options["/api/warehouses/options"]
                                       if warehouse["type"] == "FACTORY")
                        supplier_options = self.api.request("GET", query_path("/api/suppliers/options", {
                            "includeId": factory["factoryId"], "category": "GARMENT,PROCESSOR"}))
                        supplier = next(option for option in supplier_options if option["id"] == factory["factoryId"])
                        selected = dialog.locator(".el-form-item").filter(
                            has=page.locator("label", has_text=re.compile(r"^往来加工厂$")))
                        # The automatic warehouse selection must resolve its label without
                        # requiring the user to open the supplier dropdown first.
                        expect(selected.locator(".el-select__selected-item").filter(has_text=supplier["shortName"]))\
                            .to_be_visible()
                        self.note("return warehouse preselect resolves its real supplier name without opening dropdown",
                                  warehouseId=factory["id"], supplierId=supplier["id"],
                                  supplierName=supplier["shortName"])
                    self.shot("inventory-new-" + next(code.lower() for text, code in label_pairs if text == label))
                    table = dialog.locator(".el-table:visible").first
                    scroll = table.locator(".el-table__body-wrapper .el-scrollbar__wrap")
                    width = scroll.evaluate("element => ({viewport: element.clientWidth, content: element.scrollWidth})")
                    if width["content"] > width["viewport"]:
                        scroll.evaluate("element => { element.scrollLeft = element.scrollWidth }")
                        self.poll(lambda: scroll.evaluate("element => element.scrollLeft") > 0)
                        body_left = scroll.evaluate("element => element.scrollLeft")
                        self.poll(lambda: abs(table.locator(".el-table__header-wrapper")
                                              .evaluate("element => element.scrollLeft") - body_left) <= 1)
                        self.note("inventory form rightmost columns are reachable and header follows horizontal scroll",
                                  docType=label, viewport=width["viewport"], content=width["content"], scrollLeft=body_left)
                        self.shot("inventory-new-right-edge-" + next(code.lower() for text, code in label_pairs if text == label))
                        scroll.evaluate("element => { element.scrollLeft = 0 }")
                    if label in {"其他入库", "调拨"}:
                        sku_picker(lambda: dialog.get_by_role("button", name="从物料库选", exact=True).click(),
                                   "inventory-picker-" + kind.lower())
                    cancel(dialog)
                if kind == "OUT":
                    page.locator(".page-header").get_by_role("button", name="新建", exact=True).click()
                    data = reads_during(["/api/production-orders/issue-options"],
                        lambda: page.get_by_role("menuitem", name="领料出库（选生产单）", exact=True).click())
                    picker = page.get_by_role("dialog", name="选择生产单领料出库", exact=True)
                    ready_table(picker, "生产单号", data["/api/production-orders/issue-options"])
                    self.shot("inventory-production-source-picker")
                    picker.locator(".el-dialog__headerbtn").click()
                    expect(picker).not_to_be_visible()

        # Previously changed attachment-bearing material SKU and supplier cert forms.
        material_id = self.fixture.get("materialId", 1)
        material = go(f"/materials/{material_id}", f"/api/materials/{material_id}")
        expect(page.get_by_role("columnheader", name="SKU 码", exact=True)).to_be_visible()
        self.shot("material-detail-skus")
        sku = next(item for item in material["skus"] if item["id"] == self.fixture["materialSkuId"])
        row = page.locator(".el-table__body-wrapper tr.el-table__row").filter(has=page.get_by_text(sku["code"], exact=True)).first
        row.get_by_role("button", name="编辑", exact=True).click()
        dialog = page.get_by_role("dialog", name="编辑 SKU " + sku["code"], exact=True)
        expect(dialog.get_by_role("button", name="保存", exact=True)).to_be_visible()
        self.shot("material-sku-edit-upload-surface")
        cancel(dialog)

        supplier_id = self.fixture.get("supplierId", 1)
        reads_during([f"/api/suppliers/{supplier_id}", f"/api/suppliers/{supplier_id}/certs"],
                     lambda: page.goto(f"{self.ui_base}/suppliers/{supplier_id}"))
        expect(page.get_by_role("tab", name=re.compile(r"^资质"))).to_be_visible()
        self.shot("supplier-detail-certificates")
        page.get_by_role("button", name="加一份证照", exact=True).click()
        dialog = page.get_by_role("dialog", name="加一份证照", exact=True)
        # Element Plus gives both its upload wrapper and the native trigger the
        # button role. Assert the actual trigger rather than an ambiguous pair.
        expect(dialog.locator("button").filter(has_text=re.compile(r"^选文件$"))).to_be_visible()
        self.shot("supplier-certificate-new")
        cancel(dialog)

        # Procurement head/lines + selector: never click store/submit or select rows.
        reads_during(["/api/companies/options", "/api/warehouses/options"], lambda: page.goto(self.ui_base + "/purchases/new"))
        expect(page.locator(".page-header h1")).to_have_text("新建采购单")
        expect(page.get_by_role("button", name="存草稿", exact=True)).to_be_enabled()
        expect(page.get_by_role("columnheader", name="单价", exact=True)).to_be_visible()
        self.shot("purchase-new-head-lines")
        sku_picker(lambda: page.get_by_role("button", name="从物料库选", exact=True).click(), "purchase-material-picker")
        page.locator(".page-header").get_by_role("button", name="取消", exact=True).click()
        page.wait_for_url(self.ui_base + "/purchases")

        # Previously opened detail routes, but these changed dialogs were not pictured.
        matrix_id = self.fixture["matrixOrderId"]
        go(f"/orders/{matrix_id}", f"/api/orders/{matrix_id}")
        reads_during(["/api/orders/style-options"], lambda: page.get_by_role("button", name="提变更", exact=True).click())
        dialog = page.get_by_role("dialog", name=re.compile(r"^提变更 "))
        expect(dialog.locator(".el-checkbox").filter(has_text=re.compile(r"^\s*整款改单价\s*$")).first).to_be_visible()
        self.shot("order-change-explicit-price-and-files")
        cancel(dialog)
        reads_during(["/api/production-orders/schedule-prefill"], lambda: page.get_by_role("button", name="排单", exact=True).click())
        dialog = page.get_by_role("dialog", name=re.compile(r"^排单 "))
        expect(dialog.locator(".sku-matrix").first).to_be_visible()
        expect(dialog.locator(".el-loading-mask:visible")).to_have_count(0)
        self.shot("order-schedule-four-place-price")
        cancel(dialog)

        prod_id = self.fixture["lifecycle"]["productionIds"]["OUTSOURCE"]
        go(f"/production/{prod_id}", f"/api/production-orders/{prod_id}")
        page.get_by_role("button", name="报进度", exact=True).click()
        dialog = page.get_by_role("dialog", name="报进度", exact=True)
        expect(dialog.get_by_role("button", name="取消", exact=True)).to_be_visible()
        self.shot("production-node-report-open-cancel")
        cancel(dialog)
        self.open_more("上报异常")
        dialog = page.get_by_role("dialog", name="上报异常", exact=True)
        expect(dialog.get_by_role("button", name="取消", exact=True)).to_be_visible()
        self.shot("production-issue-open-cancel")
        cancel(dialog)

        with page.expect_response(lambda response: self.response_for(response, "GET", "/api/board/upstream")) as upstream:
            page.goto(self.ui_base + "/upstream")
        read_result(upstream.value, "/api/board/upstream")
        upstream_data = upstream.value.json()["data"]
        self.assertIsInstance(upstream_data["canSeeAll"], bool)
        self.assertIsInstance(upstream_data["rows"], list)
        expect(page.get_by_test_id("upstream-load-error")).not_to_be_visible()
        if upstream_data["rows"]:
            for row in upstream_data["rows"][:3]:
                expect(page.locator(".el-table__body-wrapper").get_by_text(row["doc"]["no"], exact=True).first).to_be_visible()
        else:
            expect(page.get_by_text("没有待处理的上游变更。", exact=False)).to_be_visible()
        self.shot("upstream-real-api-results")
        self.note("real upstream aggregate", code=0, rows=len(upstream_data["rows"]),
                  canSeeAll=upstream_data["canSeeAll"])

        self.assertEqual(mutations, [{"method": "POST", "path": export_create_path}],
                         "Open/cancel smoke sent a business mutation or duplicated the intentional export job")
        self.note("all added forms cancelled without mutation", businessMutationCount=0, exportJobCreateCount=1)
        # Parent tearDown still asserts zero console errors, zero page errors and local-only requests.

    def test_18_home_real_aggregates_and_independent_failure_recovery(self):
        page = self.page
        panels = (
            ("/api/board/home/kpi", "home-kpi-error", "重试概况", "概况暂不可用"),
            ("/api/board/home/todos", "home-todos-error", "重试待办", "待处理事项暂不可用"),
            ("/api/board/home/deliveries", "home-deliveries-error", "重试交期", "近期交期暂不可用"),
        )

        def open_home():
            with ExitStack() as stack:
                waits = [(path, stack.enter_context(page.expect_response(
                    lambda response, target=path: self.response_for(response, "GET", target))))
                         for path, _, _, _ in panels]
                page.goto(self.ui_base + "/home")
            return {path: wait.value.json() for path, wait in waits}

        actual = open_home()
        self.assertTrue(all(result["code"] == 0 for result in actual.values()))
        kpi = actual[panels[0][0]]["data"]
        self.assertGreater(kpi["dev"]["styleCount"], 0)
        self.assertGreater(kpi["order"]["count"], 0)
        todos = actual[panels[1][0]]["data"]
        self.assertIsInstance(todos["canSeeAll"], bool)
        self.assertTrue(all(group["total"] >= len(group["items"]) for group in todos["groups"]))
        deliveries = actual[panels[2][0]]["data"]
        self.assertIsInstance(deliveries, list)
        self.assertLessEqual(len(deliveries), 8)
        expect(page.locator(".page-header h1")).to_have_text("首页")
        expect(page.locator(".kpi-grid")).to_be_visible()
        for _, test_id, _, _ in panels:
            expect(page.get_by_test_id(test_id)).not_to_be_visible()
        self.note("real home aggregates", codes=[0, 0, 0], kpi=kpi,
                  groups=[{"key": group["key"], "total": group["total"], "items": len(group["items"])}
                          for group in todos["groups"]], deliveries=len(deliveries))
        self.shot("home-real-aggregates")

        # Only this explicitly recorded HTTP 200 failure envelope is injected.
        # Each retry below is released to the actual backend independently.
        failed_paths = {path for path, _, _, _ in panels}
        pattern = re.compile(r"/api/board/home/(kpi|todos|deliveries)(?:\?|$)")
        def unavailable(route):
            if urlsplit(route.request.url).path in failed_paths:
                route.fulfill(status=200, content_type="application/json", body=json.dumps(
                    {"code": 503, "msg": "合成聚合故障，请重试", "data": None}))
            else:
                route.continue_()
        page.route(pattern, unavailable)
        self.facts["faultInjections"].append({"kind": "home aggregate failure envelopes; retries use real backend",
                                              "paths": sorted(failed_paths), "httpStatus": 200, "businessCode": 503})
        try:
            failures = open_home()
            self.assertTrue(all(result["code"] == 503 for result in failures.values()))
            for _, test_id, retry, message in panels:
                alert = page.get_by_test_id(test_id)
                expect(alert).to_contain_text(message)
                expect(alert.get_by_role("button", name=retry, exact=True)).to_be_visible()
            expect(page.get_by_text("其他提醒暂不可用，未取得提醒数据", exact=True)).to_be_visible()
            for fabricated_empty in ("暂无待处理的事", "近 7 天没有要交的单", "没有别的要处理"):
                expect(page.get_by_text(fabricated_empty, exact=True)).not_to_be_visible()
            expect(page.locator(".kpi-grid")).not_to_be_visible()
            self.shot("home-injected-failures-without-fabricated-zeros")
            for path, test_id, retry, _ in panels:
                with self.subTest(panel=test_id):
                    failed_paths.remove(path)
                    with page.expect_response(lambda response, target=path: self.response_for(response, "GET", target)) as retried:
                        page.get_by_test_id(test_id).get_by_role("button", name=retry, exact=True).click()
                    self.assertEqual(retried.value.json()["code"], 0)
                    expect(page.get_by_test_id(test_id)).not_to_be_visible()
                    for pending_path, pending_id, _, _ in panels:
                        if pending_path in failed_paths:
                            expect(page.get_by_test_id(pending_id)).to_be_visible()
                    self.note("home panel independently recovers from real API", path=path, code=0,
                              otherFailedPanels=len(failed_paths))
            expect(page.locator(".kpi-grid")).to_be_visible()
            self.shot("home-independent-retries-real-recovery")
        finally:
            page.unroute(pattern, unavailable)

    def test_19_mobile_only_self_scope_and_real_report_issue(self):
        identity = self.fixture["identities"]["mobile_only"]
        actor = self.actor("mobile_only")
        base = "/api/production-orders"
        read_permissions = {"production:mobile:list"}
        report_permissions = read_permissions | {"production:node:edit", "production:issue:edit"}

        def assert_permissions(expected):
            me = actor.request("GET", "/api/auth/me")
            self.assertEqual(set(me["perms"]), expected)
            self.assertNotIn("admin", me["roles"])
            def paths(menus):
                return {menu.get("path") for menu in menus} | {
                    path for menu in menus for path in paths(menu.get("children") or [])}
            menu_paths = paths(me["menus"])
            self.assertIn("/mobile", menu_paths)
            self.assertTrue({"/production", "/fp-purchases", "/board"}.isdisjoint(menu_paths))

        assert_permissions(read_permissions)
        owned = self.domain.approve(base, self.domain.new_production(merch_id=identity["userId"])["id"])
        foreign = self.domain.approve(base, self.domain.new_production()["id"])
        pending = self.domain.submit(base, self.domain.new_production(merch_id=identity["userId"])["id"])
        self.assertEqual(owned["merchId"], identity["userId"])
        self.assertNotEqual(foreign["merchId"], identity["userId"])
        self.assertGreater(len(owned["nodes"]), 0)
        own_path = f"{base}/{owned['id']}"
        pending_path = f"{base}/{pending['id']}"
        node = owned["nodes"][0]
        start_body = {"action": "START", "actualStart": business_day().isoformat(), "actualFinish": None,
                      "doneQty": owned["totalQty"], "remark": "合成手机开始", "photos": []}
        receipt_body = {"clientActionId": str(uuid4()), "receiptDate": business_day().isoformat(),
                        "warehouseId": self.fixture["productWarehouseId"], "logisticsNo": None,
                        "remark": "合成权限拒绝检查", "photos": [],
                        "items": [{"prdOrderItemId": owned["items"][0]["id"], "qty": 1, "defectQty": 0, "remark": None}]}

        def assert_forbidden(phase):
            for path in (query_path(base, {"kind": "OUTSOURCE"}),
                         query_path(base + "/counts", {"kind": "OUTSOURCE"})):
                self.assertEqual(actor.envelope("GET", path)["code"], 403)
            self.assertEqual(actor.envelope("GET", f"{base}/{foreign['id']}")["code"], 404)
            self.assertEqual(actor.envelope("POST", own_path + "/receipts", receipt_body)["code"], 403)
            # A visible PENDING document reaches the real current-level check;
            # an APPROVED document would only prove a state-transition rejection.
            self.assertEqual(actor.envelope("POST", pending_path + "/audit", {"version": pending["version"]})["code"], 403)
            unchanged = self.api.request("GET", pending_path)
            self.assertEqual((unchanged["status"], unchanged["version"]), ("PENDING", pending["version"]))
            self.assertEqual(self.api.request("GET", own_path)["returnedQty"], 0)
            self.assertEqual(self.api.request("GET", own_path + "/receipts"), [])
            self.note("mobile-only denied desktop, foreign object, receipt and approval", phase=phase,
                      desktopListCode=403, desktopCountsCode=403, foreignDetailCode=404,
                      receiptCode=403, pendingAuditCode=403, unchangedPendingVersion=pending["version"])

        assert_forbidden("read-only")
        self.assertEqual(actor.envelope("POST", f"{own_path}/nodes/{node['id']}/reports", start_body)["code"], 403)

        def fit_phone(page):
            self.assertEqual(page.viewport_size, {"width": 390, "height": 844})
            self.assertTrue(page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"))

        # The existing desktop-authorized path remains available at the same width.
        desktop = self.new_context(mobile=True).new_page()
        with desktop.expect_response(lambda r: self.response_for(r, "GET", own_path)) as desktop_read:
            desktop.goto(f"{self.ui_base}/mobile/{owned['id']}")
        self.assertEqual(desktop_read.value.json()["code"], 0)
        expect(desktop.get_by_role("button", name="在电脑上打开", exact=True)).to_be_visible()
        fit_phone(desktop)
        self.shot("mobile-desktop-authority-keeps-entry", desktop)
        with desktop.expect_response(lambda r: self.response_for(r, "GET", own_path)) as desktop_opened:
            desktop.get_by_role("button", name="在电脑上打开", exact=True).click()
        self.assertEqual(desktop_opened.value.json()["code"], 0)
        desktop.wait_for_url(f"{self.ui_base}/production/{owned['id']}")
        expect(desktop.locator(".page-header")).to_contain_text(owned["no"])

        page = self.new_context(mobile=True, actor=actor).new_page()
        self.page = page  # A failure image must capture the actual mobile state.
        with page.expect_response(lambda r: self.response_for(r, "GET", base + "/my-follow")) as listed:
            page.goto(self.ui_base + "/")
        self.assertEqual(listed.value.json()["code"], 0)
        self.assertEqual({row["id"] for row in listed.value.json()["data"]}, {owned["id"]})
        page.wait_for_url(self.ui_base + "/mobile")
        expect(page.get_by_role("heading", name="我的跟单", exact=True)).to_be_visible()
        expect(page.locator(".m-card")).to_have_count(1)
        expect(page.locator(".m-card")).to_contain_text(owned["no"])
        fit_phone(page)
        self.shot("mobile-only-owned-active-list", page)
        with page.expect_response(lambda r: self.response_for(r, "GET", own_path)) as opened:
            page.locator(".m-card").click()
        own_read = opened.value.json()
        self.assertEqual(own_read["code"], 0)
        self.assertTrue({"report", "reportIssue", "receive", "audit"}.isdisjoint(own_read["data"]["actions"]))
        expect(page.locator(".big-node")).to_have_count(len(owned["nodes"]))
        for button in page.locator(".big-node").all():
            expect(button).to_be_disabled()
        expect(page.get_by_role("button", name="上报异常", exact=True)).not_to_be_visible()
        expect(page.get_by_role("button", name="在电脑上打开", exact=True)).not_to_be_visible()
        fit_phone(page)
        self.shot("mobile-only-read-only-detail", page)

        with page.expect_response(lambda r: self.response_for(r, "GET", f"{base}/{foreign['id']}")) as inaccessible:
            page.goto(f"{self.ui_base}/mobile/{foreign['id']}")
        self.assertEqual(inaccessible.value.json()["code"], 404)
        expect(page.get_by_text("这张单不存在，或者不归你看", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="回我的跟单", exact=True)).to_be_visible()
        expect(page.locator(".summary-card")).not_to_be_visible()
        fit_phone(page)
        self.shot("mobile-only-foreign-detail-denied", page)

        # Grant shared action IDs directly. Including their desktop menu ancestor
        # would silently turn this into a desktop-user test and defeat T09.
        report_menu_ids = sorted(set(identity["menuIds"]) | set(identity["reportMenuIds"]))
        self.api.request("PUT", f"/api/roles/{identity['roleId']}/menus", {"ids": report_menu_ids})
        try:
            assert_permissions(report_permissions)
            assert_forbidden("report-enabled")
            foreign_node = foreign["nodes"][0]
            self.assertEqual(actor.envelope("POST", f"{base}/{foreign['id']}/nodes/{foreign_node['id']}/reports", start_body)["code"], 404)
            foreign_after = self.api.request("GET", f"{base}/{foreign['id']}")
            self.assertEqual(foreign_after["nodes"], foreign["nodes"])
            with page.expect_response(lambda r: self.response_for(r, "GET", own_path)) as reloaded:
                page.goto(f"{self.ui_base}/mobile/{owned['id']}")
            allowed = reloaded.value.json()
            self.assertEqual(allowed["code"], 0)
            self.assertTrue({"report", "reportIssue"}.issubset(allowed["data"]["actions"]))
            self.assertTrue({"receive", "audit"}.isdisjoint(allowed["data"]["actions"]))
            expect(page.get_by_role("button", name="在电脑上打开", exact=True)).not_to_be_visible()
            node_button = page.locator(".big-node").filter(has=page.get_by_text(node["nodeName"], exact=True))
            expect(node_button).to_be_enabled()
            node_button.click()
            page.get_by_label("实际开始", exact=True).fill(start_body["actualStart"])
            page.get_by_label("备注", exact=True).fill(start_body["remark"])
            start = page.get_by_role("button", name="只报开始", exact=True)
            start.scroll_into_view_if_needed()
            expect(start).to_be_in_viewport(ratio=1)
            fit_phone(page)
            self.shot("mobile-only-real-start-form", page)
            report_path = f"{own_path}/nodes/{node['id']}/reports"
            with page.expect_response(lambda r: self.response_for(r, "POST", report_path)) as reported, \
                    page.expect_response(lambda r: self.response_for(r, "GET", own_path)) as report_read:
                start.click()
            self.assertEqual(reported.value.json()["code"], 0)
            self.assertEqual(report_read.value.json()["code"], 0)
            after_start = actor.request("GET", own_path)
            saved_node = next(item for item in after_start["nodes"] if item["id"] == node["id"])
            self.assertEqual((saved_node["actualStart"], saved_node["actualFinish"]), (start_body["actualStart"], None))
            self.assertEqual(saved_node["reportCount"], node["reportCount"] + 1)
            self.assertEqual(after_start["status"], "RUNNING")
            self.assertEqual(after_start["planRevision"], owned["planRevision"])
            reports = actor.request("GET", report_path)
            self.assertEqual(len(reports), node["reportCount"] + 1)
            self.assertEqual((reports[0]["action"], reports[0]["remark"]), ("START", start_body["remark"]))
            expect(page.locator(".big-node").filter(has=page.get_by_text(node["nodeName"], exact=True))).to_contain_text("进行中")
            self.shot("mobile-only-start-persisted", page)

            issue_types = actor.request("GET", "/api/dict-data/by-type/issue_type")
            issue_type = next(item for item in issue_types if item["value"] == "OTHER" and item["status"] == 1)
            page.get_by_role("button", name="上报异常", exact=True).click()
            page.locator(".m-body .el-select").get_by_role("combobox").click()
            page.get_by_role("option", name=issue_type["label"], exact=True).click()
            description = "合成手机异常：仅作权限与图片回归"
            page.get_by_label("描述", exact=True).fill(description)
            with page.expect_response(lambda r: self.response_for(r, "POST", "/api/files")) as uploaded:
                page.locator('.image-upload input[type="file"]').set_input_files(
                    {"name": "audit-mobile-anonymous.png", "mimeType": "image/png", "buffer": anonymous_png()})
            uploaded_result = uploaded.value.json()
            self.assertEqual(uploaded_result["code"], 0)
            expect(page.locator(".image-upload .pending")).to_have_count(0)
            expect(page.locator(".image-upload li.tile")).to_have_count(1)
            expect(page.locator(".image-upload .el-image__inner")).to_be_visible()
            issue_submit = page.get_by_role("button", name="上报", exact=True)
            issue_submit.scroll_into_view_if_needed()
            expect(issue_submit).to_be_in_viewport(ratio=1)
            fit_phone(page)
            self.shot("mobile-only-anonymous-image-issue-form", page)
            with page.expect_response(lambda r: self.response_for(r, "POST", own_path + "/issues")) as issue_saved, \
                    page.expect_response(lambda r: self.response_for(r, "GET", own_path)) as issue_read:
                issue_submit.click()
            issue_result = issue_saved.value.json()
            self.assertEqual(issue_result["code"], 0)
            self.assertEqual(issue_read.value.json()["code"], 0)
            issue_id = issue_result["data"]["id"]
            issues = actor.request("GET", own_path + "/issues")
            created_issue = next(item for item in issues if item["id"] == issue_id)
            self.assertEqual((created_issue["issueType"], created_issue["description"]), ("OTHER", description))
            self.assertEqual((created_issue["responsibleParty"], created_issue["impactDelivery"]), ("SELF", False))
            self.assertEqual([photo["url"] for photo in created_issue["photos"]], [uploaded_result["data"]["url"]])
            persisted = actor.request("GET", own_path)
            self.assertIn(issue_id, {item["id"] for item in persisted["openIssues"]})
            self.assertEqual(persisted["planRevision"], owned["planRevision"])
            expect(page.locator(".open-issues")).to_contain_text(description)
            expect(page.get_by_role("button", name="在电脑上打开", exact=True)).not_to_be_visible()
            fit_phone(page)
            self.shot("mobile-only-issue-persisted", page)
            assert_forbidden("after-real-actions")
            self.note("mobile-only real report and image issue stay within SELF", ownedId=owned["id"],
                      foreignId=foreign["id"], pendingId=pending["id"], nodeId=node["id"], issueId=issue_id,
                      initialPermissions=sorted(read_permissions), reportPermissions=sorted(report_permissions),
                      reportCount=saved_node["reportCount"], savedPhotoCount=len(created_issue["photos"]),
                      planRevisionBefore=owned["planRevision"], planRevisionAfter=persisted["planRevision"],
                      desktopEntryHidden=True, viewportWidth=390, responseMocks=False)
        finally:
            self.api.request("PUT", f"/api/roles/{identity['roleId']}/menus", {"ids": identity["menuIds"]})

    def test_20_independent_warehouse_source_state_and_post_authority(self):
        # API-only intersection of inventory authority and purchase source state.
        # No response replacement, UI assertion or complete status enumeration.
        self.facts["verification"] = "real API permission/source-state matrix; no UI assertions"
        identity = self.fixture["identities"]["warehouse_only"]
        warehouse = self.actor("warehouse_only")
        no_add = self.actor("no_list")
        expected_permissions = {"inventory:doc:list", "inventory:doc:add"}
        self.assertEqual(set(warehouse.request("GET", "/api/auth/me")["perms"]), expected_permissions)
        self.assertNotIn("inventory:doc:add", no_add.request("GET", "/api/auth/me")["perms"])
        purchase_base = "/api/purchase-orders"
        inventory_base = "/api/inventory-docs"
        draft = self.domain.new_purchase(qty=10)
        pending = self.domain.submit(purchase_base, self.domain.new_purchase(qty=10)["id"])
        approved = self.domain.approve(purchase_base, self.domain.new_purchase(qty=10)["id"])

        def source_path(source):
            return f"{inventory_base}/from-purchase/{source['id']}"

        def doc_ids(source):
            rows = self.api.rows(inventory_base, {"kind": "IN", "keyword": source["no"]})
            self.assertTrue(all(row["sourceNo"] == source["no"] for row in rows))
            return {row["id"] for row in rows}

        def payload(source, *, post=False, quantity=2):
            return {"clientActionId": str(uuid4()), "docType": "PURCHASE_IN",
                    "docDate": business_day().isoformat(), "fromWarehouseId": None,
                    "toWarehouseId": self.fixture["warehouseId"], "supplierDeliveryNo": None,
                    "logisticsNo": None, "reason": None, "remark": "合成独立仓管来源验收",
                    "items": [{"sourceItemId": source["items"][0]["id"],
                               "materialSkuId": self.fixture["materialSkuId"], "qty": quantity,
                               "dyeLot": None, "rolls": None, "overReason": None, "remark": None}],
                    "itemIds": None, "post": post}

        self.assertEqual(warehouse.envelope("GET", purchase_base)["code"], 403)
        for source in (draft, pending, approved):
            self.assertNotEqual(source["buyerId"], identity["userId"])
            self.assertEqual(warehouse.envelope("GET", f"{purchase_base}/{source['id']}")["code"], 403)
            self.assertEqual(doc_ids(source), set())
        for source, status in ((draft, "DRAFT"), (pending, "PENDING")):
            self.assertEqual(source["status"], status)
            before_qty = self.domain.stock_qty()
            self.assertEqual(warehouse.envelope("GET", query_path(source_path(source), {"docType": "PURCHASE_IN"}))["code"], 1000)
            self.assertEqual(warehouse.envelope("POST", source_path(source), payload(source))["code"], 1000)
            self.assertEqual(doc_ids(source), set())
            after = self.api.request("GET", f"{purchase_base}/{source['id']}")
            self.assertEqual((after["status"], after["version"]), (status, source["version"]))
            self.assertEqual(self.domain.stock_qty(), before_qty)
            self.note("warehouse authority cannot bypass purchase source state", sourceId=source["id"],
                      state=status, prefillCode=1000, createCode=1000, generatedDocumentCount=0)

        prefill_url = query_path(source_path(approved), {"docType": "PURCHASE_IN"})
        prefill = warehouse.request("GET", prefill_url)
        self.assertEqual(prefill["source"]["id"], approved["id"])
        self.assertEqual(prefill["source"]["status"], "APPROVED")
        self.assertEqual([item["sourceItemId"] for item in prefill["items"]], [approved["items"][0]["id"]])
        assert_decimal_equal(prefill["items"][0]["suggestQty"], 10)
        before_qty = self.domain.stock_qty()
        self.assertEqual(no_add.envelope("GET", prefill_url)["code"], 403)
        self.assertEqual(no_add.envelope("POST", source_path(approved), payload(approved))["code"], 403)
        self.assertEqual(warehouse.envelope("POST", source_path(approved), payload(approved, post=True))["code"], 403)
        self.assertEqual(doc_ids(approved), set())
        self.assertEqual(self.domain.stock_qty(), before_qty)
        self.assertEqual(self.api.request("GET", f"{purchase_base}/{approved['id']}")["version"], approved["version"])
        self.note("source access does not imply stock posting authority", sourceId=approved["id"],
                  warehousePermissions=sorted(expected_permissions), purchaseDetailCode=403,
                  approvedPrefillCode=0, noAddPrefillCode=403, noAddCreateCode=403,
                  noAuditAutoPostCode=403, generatedDocumentCountAfterDenials=0)

        # A separate legitimate draft proves this is not an all-denied fixture.
        made = warehouse.request("POST", source_path(approved), payload(approved))
        self.assertEqual(made["status"], "DRAFT")
        saved = warehouse.request("GET", f"{inventory_base}/{made['id']}")
        self.assertEqual((saved["status"], saved["source"]["id"]), ("DRAFT", approved["id"]))
        self.assertEqual(doc_ids(approved), {made["id"]})
        self.assertEqual(self.domain.stock_qty(), before_qty)
        self.assertEqual(self.api.request("GET", f"{purchase_base}/{approved['id']}")["status"], "APPROVED")

        # Admin posts an actual partial receipt; the warehouse role still cannot
        # open the purchase, but can prefill its remaining permitted source lines.
        posted = self.api.request("POST", source_path(approved), payload(approved, post=True))
        self.assertEqual(posted["status"], "APPROVED")
        partial = self.api.request("GET", f"{purchase_base}/{approved['id']}")
        self.assertEqual(partial["status"], "RUNNING")  # UI calls this 部分到货 / PARTIAL.
        assert_decimal_equal(partial["items"][0]["receivedQty"], 2)
        partial_prefill = warehouse.request("GET", prefill_url)
        self.assertEqual(partial_prefill["source"]["status"], "RUNNING")
        assert_decimal_equal(partial_prefill["items"][0]["suggestQty"], 8)
        self.assertEqual(warehouse.envelope("GET", f"{purchase_base}/{approved['id']}")["code"], 403)
        self.assertEqual(doc_ids(approved), {made["id"], posted["id"]})
        self.note("independent SELF warehouse source flow", approvedSourceId=approved["id"],
                  buyerId=approved["buyerId"], warehouseUserId=identity["userId"],
                  draftInventoryId=made["id"], postedInventoryId=posted["id"],
                  actualPartialStatus="RUNNING", partialPrefillCode=0, remainingPurchaseQty=8,
                  coveredSourceStates=["DRAFT", "PENDING", "APPROVED", "RUNNING"],
                  notEnumeratedSourceStates=["DONE", "CLOSED", "VOIDED"], responseMocks=False)

    def test_21_fixed_point_form_previews_match_real_backend(self):
        """Actual stored drafts, production form components, no response mocking."""
        material = self.api.request("GET", f"/api/materials/{self.fixture.get('materialId', 1)}")
        sku_ids = [sku["id"] for sku in material["skus"]][:2]
        self.assertEqual(len(sku_ids), 2, "The real fixture must supply two distinct material SKUs")
        today = business_day()
        made = self.api.request("POST", "/api/purchase-orders", {
            "companyId": self.fixture["companyId"], "supplierId": self.fixture.get("supplierId", 1),
            "purchaseType": "STOCK", "buyerId": self.fixture["userId"],
            "orderDate": today.isoformat(), "deliveryDate": (today + timedelta(days=7)).isoformat(),
            "warehouseId": self.fixture["warehouseId"], "settleMethod": "MONTHLY", "taxPct": 13,
            "taxIncluded": True, "freightIncluded": True, "remark": "匿名定点预览回归",
            "planStyleIds": [], "items": [{"calcItemId": None, "materialSkuId": sku_id,
                "qty": 0.018, "price": 7.5, "priceChangeReason": "匿名尾差验证",
                "lineDueDate": None, "expectDate": None} for sku_id in sku_ids],
        })
        purchase_path = f"/api/purchase-orders/{made['id']}"
        purchase = self.api.request("GET", purchase_path)
        assert_decimal_equal(purchase["totalAmount"], "0.28")
        for row in purchase["items"]:
            assert_decimal_equal(row["amount"], "0.14")

        def amount_cells(scope):
            table = scope.locator(".el-table:visible").first
            expect(table.get_by_role("columnheader", name="金额", exact=True)).to_be_visible()
            headers = table.locator(".el-table__header-wrapper th")
            labels = [text.strip() for text in headers.all_text_contents()]
            index = labels.index("金额")
            return table, table.locator(f".el-table__body-wrapper tr.el-table__row > td:nth-child({index + 1}) .cell")

        with self.subTest(form="purchase"):
            with self.page.expect_response(lambda response: self.response_for(response, "GET", purchase_path)):
                self.page.goto(f"{self.ui_base}/purchases/{made['id']}/edit")
            expect(self.page.locator(".page-header h1")).to_have_text("编辑 " + purchase["no"])
            table, cells = amount_cells(self.page)
            expect(cells).to_have_count(2)
            table.locator(".el-table__body-wrapper .el-scrollbar__wrap").evaluate(
                "element => { element.scrollLeft = element.scrollWidth }")
            self.shot("purchase-two-rounded-lines-before-assert")
            expect(cells).to_have_text(["0.14", "0.14"])
            expect(self.page.locator(".num strong")).to_have_text("0.28")
            edge_columns = table.locator(".el-table__body-wrapper tr.el-table__row").evaluate_all("""rows => rows.map(row => {
                const first = row.querySelector('td:first-child .cell');
                const last = row.querySelector('td:last-child .cell');
                const button = last.querySelector('button');
                const width = cell => { const css = getComputedStyle(cell); return cell.clientWidth
                    - parseFloat(css.paddingLeft) - parseFloat(css.paddingRight); };
                const range = document.createRange(); range.selectNodeContents(first);
                const text = range.getBoundingClientRect();
                return { sequence: first.textContent.trim(), sequenceContentWidth: width(first),
                    sequenceTextWidth: text.width, sequenceTextHeight: text.height,
                    sequenceLineHeight: parseFloat(getComputedStyle(first).lineHeight),
                    deleteContentWidth: width(last), deleteButtonWidth: button.getBoundingClientRect().width };
            })""")
            for edge in edge_columns:
                self.assertLessEqual(edge["sequenceTextWidth"], edge["sequenceContentWidth"] + 1)
                self.assertLessEqual(edge["sequenceTextHeight"], edge["sequenceLineHeight"] + 1)
                self.assertLessEqual(edge["deleteButtonWidth"], edge["deleteContentWidth"] + 1)
            self.note("production purchase table edge columns fit with the real sidebar",
                      edgeColumns=edge_columns, responseMocks=False)
            self.note("purchase preview matches independently calculated stored line amounts",
                      purchaseId=made["id"], qty="0.018", price="7.5", lineAmounts=["0.14", "0.14"],
                      totalAmount="0.28", responseMocks=False)

        reasons = self.api.request("GET", "/api/dict-data/by-type/stock_in_reason")
        self.assertTrue(reasons)
        made_inventory = self.api.request("POST", "/api/inventory-docs", {
            "docType": "OTHER_IN", "companyId": self.fixture["companyId"], "docDate": today.isoformat(),
            "fromWarehouseId": None, "toWarehouseId": self.fixture["warehouseId"], "partnerId": None,
            "reason": reasons[0]["value"], "supplierDeliveryNo": None, "logisticsNo": None,
            "remark": "匿名定点与未知估值预览回归",
            "items": [{"id": None, "sourceItemId": None, "materialSkuId": sku_id,
                       "qty": 0.018, "purchaseQty": None, "price": 7.5 if index == 0 else None,
                       "dyeLot": None, "rolls": None, "overReason": None, "remark": None}
                      for index, sku_id in enumerate(sku_ids)],
        })
        inventory_path = f"/api/inventory-docs/{made_inventory['id']}"
        inventory = self.api.request("GET", inventory_path)
        assert_decimal_equal(inventory["items"][0]["amount"], "0.14")
        self.assertIsNone(inventory["items"][1]["amount"])
        with self.subTest(form="inventory"):
            with self.page.expect_response(lambda response: self.response_for(response, "GET", inventory_path)):
                self.page.goto(f"{self.ui_base}/stock-in/{made_inventory['id']}")
            self.page.locator(".page-header").get_by_role("button", name="编辑", exact=True).click()
            dialog = self.page.get_by_role("dialog", name=f"编辑 {inventory['no']}（其他入库）", exact=True)
            expect(dialog).to_be_visible()
            table, cells = amount_cells(dialog)
            expect(cells).to_have_count(2)
            table.locator(".el-table__body-wrapper .el-scrollbar__wrap").evaluate(
                "element => { element.scrollLeft = element.scrollWidth }")
            self.shot("inventory-rounded-and-unknown-before-assert")
            expect(cells).to_have_text(["0.14", "—"])
            self.note("inventory preview matches stored decimal amount and preserves unknown valuation",
                      inventoryId=made_inventory["id"], qty="0.018", price="7.5", lineAmounts=["0.14", None],
                      responseMocks=False)
            dialog.get_by_role("button", name="取消", exact=True).click()
            expect(dialog).not_to_be_visible()


    def test_22_finished_goods_real_partial_receipts_ship_and_reverse(self):
        """Separate real makers/reviewers; no route mocks or SQL business writes."""
        maker, reviewer = self.actor("fg_maker"), self.actor("fg_auditor")
        company = self.fixture["companyId"]
        day = business_day()
        product_warehouses = maker.request("GET", "/api/warehouses/options?type=PRODUCT")
        self.assertTrue(product_warehouses)
        warehouse = product_warehouses[0]["id"]
        order = self.domain.new_order((200, 200), marker="AUDIT-FG")
        order = self.domain.approve("/api/orders", order["id"])
        made_production = self.api.request("POST", "/api/production-orders/schedule", {
            "orderId": order["id"], "blocks": [{
                "styleId": self.fixture["styleId"], "factoryId": self.fixture.get("factoryId", 3),
                "processMode": "CMT", "unitPrice": 12.3456, "priceSourceId": None,
                "priceChangeReason": "合成成品闭环", "dueDate": (day + timedelta(days=30)).isoformat(),
                "merchId": self.fixture["userId"], "settleMethod": "MONTHLY", "terms": None,
                "remark": "仅隔离回归", "items": [{"orderItemId": row["id"], "qty": 200} for row in order["items"]],
            }]})
        production = self.domain.approve("/api/production-orders", made_production[0]["id"])
        receipt = self.api.request("POST", f"/api/production-orders/{production['id']}/receipts", {
            "clientActionId": str(uuid4()), "receiptDate": day.isoformat(), "warehouseId": warehouse,
            "logisticsNo": None, "remark": "合成400件回货，4件次品", "photos": [],
            "items": [{"prdOrderItemId": row["id"], "qty": 200, "defectQty": 4 if n == 0 else 0,
                       "remark": None} for n, row in enumerate(production["items"])],
        })
        prefix = f"/api/fg-docs/from-receipt/{receipt['id']}"
        source = maker.request("GET", query_path(prefix, {"companyId": company}))
        self.assertEqual(sum(row["goodQty"] for row in source["items"]), 396)
        self.assertEqual(len(source["items"]), 2)
        sku_ids = [row["styleSkuId"] for row in source["items"]]
        styles = {sku["id"]: sku for sku in source["style"]["skus"]}
        color_names = {color["id"]: color["name"] for color in source["style"]["colors"]}
        qty_path = query_path("/api/fg-stocks/qty", {"companyId": company, "warehouseId": warehouse, "skuIds": sku_ids})
        initial = maker.request("GET", qty_path)
        self.assertEqual(sum(row["qty"] for row in initial), 0)
        self.facts["rawHttp"] = []

        def observed(client, method, path, body=None):
            status, headers, raw = client.raw(method, path, body)
            result = json.loads(raw)
            self.facts["rawHttp"].append({"method": method, "path": path, "httpStatus": status,
                                         "requestId": headers.get("X-Request-Id"), "response": result})
            self.assertTrue(headers.get("X-Request-Id"))
            return result

        def detail(identity, client=maker):
            value = observed(client, "GET", query_path(f"/api/fg-docs/{identity}", {"companyId": company}))
            self.assertEqual(value["code"], 0)
            self.assertIsInstance(value["data"]["id"], str)
            self.assertIsInstance(value["data"]["version"], int)
            return value["data"]

        def fill_matrix(dialog, amounts):
            for sku_id, amount in amounts.items():
                sku = styles[sku_id]
                label = f"{color_names[sku['colorId']]} {sku['sizeCode']} 件数"
                dialog.get_by_role("textbox", name=label, exact=True).fill(str(amount))

        def stock_total(expected):
            rows = observed(maker, "GET", qty_path)
            self.assertEqual(rows["code"], 0)
            self.assertEqual(sum(row["qty"] for row in rows["data"]), expected)
            self.assertTrue(all(row["qty"] >= 0 for row in rows["data"]))

        make_page = self.new_context(actor=maker).new_page()
        review_page = self.new_context(actor=reviewer).new_page()
        self.page = make_page

        def receive_in_ui(amounts, label):
            make_page.goto(self.ui_base + query_path("/fg-in", {"companyId": company, "tab": "pending"}))
            row = make_page.locator(".el-table__body tr").filter(has_text=receipt["no"]).first
            expect(row).to_be_visible()
            row.get_by_role("button", name="入库", exact=True).click()
            dialog = make_page.get_by_role("dialog", name="回货入库 · " + receipt["no"], exact=True)
            expect(dialog.locator(".sku-matrix")).to_be_visible()
            fill_matrix(dialog, amounts)
            expect(dialog.get_by_text(f"这次入 {sum(amounts.values())} 件", exact=True)).to_be_visible()
            self.shot(label + "-matrix", page=make_page)
            with make_page.expect_response(lambda r: self.response_for(r, "POST", prefix)) as saving:
                dialog.get_by_role("button", name="存草稿", exact=True).click()
            result = saving.value.json()
            self.assertEqual(result["code"], 0)
            value = result["data"]
            self.assertIsInstance(value["id"], str)
            expect(make_page).to_have_url(re.compile(r"/fg-in/" + value["id"] + r"\?companyId=" + str(company)))
            expect(make_page.locator(".sku-matrix")).to_be_visible()
            self.assertEqual(detail(value["id"])["totalQty"], sum(amounts.values()))
            self.shot(label + "-saved", page=make_page)
            return value["id"]

        def action_in_ui(identity, action, kind="in"):
            review_page.goto(self.ui_base + query_path(f"/fg-{kind}/{identity}", {"companyId": company}))
            button_label = "审核过账" if action == "audit" else "反审核"
            review_page.get_by_role("button", name=button_label, exact=True).click()
            confirm = review_page.get_by_role("dialog")
            expect(confirm).to_be_visible()
            with review_page.expect_response(lambda r: self.response_for(r, "POST", f"/api/fg-docs/{identity}/{action}")) as acted:
                confirm.get_by_role("button", name="审核" if action == "audit" else "反审核", exact=True).click()
            self.assertEqual(acted.value.json()["code"], 0)
            expect(confirm).not_to_be_visible()
            expect(review_page.get_by_role("button", name="反审核" if action == "audit" else "审核过账", exact=True)).to_be_visible()
            self.shot(f"{identity}-{action}", page=review_page)
            return detail(identity, reviewer)

        first_amounts = {row["styleSkuId"]: 100 if row["defectQty"] else 200 for row in source["items"]}
        first = receive_in_ui(first_amounts, "first-300")
        own = observed(maker, "POST", query_path(f"/api/fg-docs/{first}/audit", {"companyId": company}), {"version": 0})
        self.assertEqual(own["code"], 403)
        stock_total(0)
        # Exercise editing the real persisted source matrix before a different user posts it.
        make_page.get_by_role("button", name="编辑", exact=True).click()
        editing = make_page.get_by_role("dialog")
        expect(editing.locator(".sku-matrix")).to_be_visible()
        fill_matrix(editing, first_amounts)
        with make_page.expect_response(lambda r: self.response_for(r, "PUT", f"/api/fg-docs/{first}")) as saving:
            editing.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(saving.value.json()["code"], 0)
        expect(editing).not_to_be_visible()
        self.assertEqual(action_in_ui(first, "audit")["status"], "APPROVED")
        stock_total(300)
        remaining = maker.request("GET", query_path(prefix, {"companyId": company}))
        second_amounts = {row["styleSkuId"]: row["remainingQty"] for row in remaining["items"]}
        self.assertEqual(sum(second_amounts.values()), 96)
        second = receive_in_ui(second_amounts, "remainder-96")
        self.assertEqual(action_in_ui(second, "audit")["status"], "APPROVED")
        stock_total(396)
        links = observed(maker, "GET", query_path("/api/fg-docs/by-receipts", {"companyId": company, "receiptIds": receipt["id"]}))
        linked = links["data"][0]
        self.assertEqual((linked["postedQty"], linked["reservedQty"], linked["remainingQty"], linked["documentsTotal"]), (396, 0, 0, 2))
        self.assertEqual({row["id"] for row in linked["documents"]}, {first, second})
        blocked_void = observed(self.api, "POST", f"/api/production-orders/{production['id']}/receipts/{receipt['id']}/void", {"reason": "合成下游引用阻断验证"})
        self.assertEqual(blocked_void["code"], 1005)

        make_page.goto(self.ui_base + query_path("/fg-out", {"companyId": company}))
        make_page.get_by_role("button", name="发货出库", exact=True).click()
        ship_dialog = make_page.get_by_role("dialog", name="新建发货出库", exact=True)
        expect(ship_dialog).to_be_visible()
        order_field = ship_dialog.locator(".el-form-item").filter(
            has=make_page.locator(".el-form-item__label").filter(has_text=re.compile(r"^需求订单$")))
        order_input = order_field.get_by_role("combobox")
        order_input.fill(order["no"])
        make_page.get_by_role("option").filter(has_text=order["no"]).click()
        expect(ship_dialog.locator(".sku-matrix")).to_be_visible()
        fill_matrix(ship_dialog, {sku: 100 for sku in sku_ids})
        self.shot("ship-200-matrix", page=make_page)
        with make_page.expect_response(lambda r: self.response_for(r, "POST", "/api/fg-docs")) as saved_ship:
            ship_dialog.get_by_role("button", name="存草稿", exact=True).click()
        shipment = saved_ship.value.json()
        self.assertEqual(shipment["code"], 0)
        ship_id = shipment["data"]["id"]
        hidden = detail(ship_id)
        self.assertNotIn("totalAmount", hidden)
        for item in hidden["items"]:
            self.assertNotIn("price", item)
            self.assertNotIn("amount", item)
        self.assertEqual(action_in_ui(ship_id, "audit", kind="out")["status"], "APPROVED")
        stock_total(196)
        shipped_path = query_path("/api/fg-docs/shipped", {"companyId": company, "orderId": order["id"]})
        self.assertEqual(observed(maker, "GET", shipped_path)["code"], 403)
        shipped = observed(self.api, "GET", shipped_path)
        self.assertEqual(shipped["code"], 0)
        self.assertEqual(sum(row["shippedQty"] for row in shipped["data"]), 200)
        incoming = detail(first, reviewer)
        blocked_reverse = observed(reviewer, "POST", query_path(f"/api/fg-docs/{first}/unaudit", {"companyId": company}), {"version": incoming["version"]})
        self.assertEqual(blocked_reverse["code"], 1001)
        stock_total(196)
        action_in_ui(ship_id, "unaudit", kind="out")
        stock_total(396)
        action_in_ui(first, "unaudit")
        stock_total(96)
        action_in_ui(second, "unaudit")
        stock_total(0)
        ledger = observed(maker, "GET", query_path("/api/fg-stock-logs", {
            "companyId": company, "warehouseId": warehouse, "keyword": f"{detail(first)['no']} {detail(second)['no']} {detail(ship_id)['no']}", "size": 100}))
        self.assertEqual(ledger["code"], 0)
        entries = ledger["data"]["list"]
        self.assertEqual(len(entries), 10)
        self.assertEqual(sum(row["changeQty"] for row in entries), 0)
        reversal_ids = [row["reversalOfLogId"] for row in entries if row.get("reversalOfLogId")]
        original_ids = {row["id"] for row in entries if not row.get("reversalOfLogId")}
        self.assertEqual(set(reversal_ids), original_ids)
        self.assertEqual(len(reversal_ids), len(original_ids))
        self.assertTrue(all(isinstance(row["id"], str) and row["requestId"] for row in entries))
        make_page.goto(self.ui_base + query_path("/fg-stock", {"companyId": company}))
        with make_page.expect_response(lambda r: self.response_for(r, "GET", "/api/fg-stock-logs")) as viewed_logs:
            make_page.locator(".el-segmented__item").filter(has_text=re.compile(r"^日志$")).click()
        self.assertEqual(viewed_logs.value.json()["code"], 0)
        expect(make_page.locator(".el-table").first).to_be_visible()
        self.shot("stock-after-real-reversals", page=make_page)
        expect(make_page.locator(".ledger-time")).to_have_count(10)
        expect(make_page.locator(".ledger-reversal")).to_have_count(5)
        layout = make_page.locator(".ledger-time").evaluate_all("""elements => elements.map(element => {
          const range = document.createRange(); range.selectNodeContents(element);
          return {text:element.textContent.trim(), textWidth:range.getBoundingClientRect().width,
            availableWidth:element.clientWidth, height:element.getBoundingClientRect().height};
        })""")
        for cell in layout:
            self.assertRegex(cell["text"], r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$")
            self.assertLessEqual(cell["textWidth"], cell["availableWidth"] + 1)
        self.note("real stock ledger timestamps and reversal markers", timestamps=layout,
                  visibleReversals=5, responseMocks=False)
        self.note("real order to partial goods receipts and reversible shipment", orderId=order["id"],
                  productionId=production["id"], receiptId=receipt["id"], goodQty=396, defectQty=4,
                  incomingDocuments=[first, second], incomingQuantities=[300, 96], shipmentId=ship_id,
                  shippedQty=200, finalStock=0, immutableLedgerEntries=10, separateReviewer=True,
                  selfAuditCode=403, downstreamVoidCode=1005, negativeStockReverseCode=1001,
                  priceFieldsPhysicallyAbsent=True, responseMocks=False)


    def test_25_public_tags_create_conflict_rename_archive_and_permissions(self):
        run_tag_lifecycle(self)

    def test_24_sample_create_revise_convert_and_field_audit(self):
        run_sample_lifecycle(self)

    def test_23_inventory_large_aggregates(self):
        """Two thousand real legal SKUs/lines, exact totals and actual visible cells."""
        self.viewport_only = True  # A 2000-row detail is inspected in bounded viewport images.
        day = business_day().isoformat()
        base = self.api.request("GET", f"/api/materials/{self.fixture['materialId']}")["material"]
        colors = self.api.request("GET", "/api/colors/options")
        selected_colors = [row["id"] for row in colors[:20]]
        occupied = {row["code"].upper() for row in colors}
        alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        for left in alphabet:
            for right in alphabet:
                if len(selected_colors) == 20:
                    break
                code = "Q" + left + right
                if code in occupied:
                    continue
                identity = self.api.request("POST", "/api/colors", {
                    "code": code, "name": "精度回归色" + code, "family": None, "hex": None})
                self.assertIsInstance(identity, str)
                selected_colors.append(identity)
                occupied.add(code)
            if len(selected_colors) == 20:
                break
        self.assertEqual(len(set(selected_colors)), 20)
        material_id = self.api.request("POST", "/api/materials", {
            "name": "匿名大额精度矩阵", "categoryId": base["categoryId"],
            "materialType": base["materialType"], "stockUnit": base["stockUnit"],
            "purchaseUnit": base["purchaseUnit"], "colorIds": selected_colors,
            "specs": [{"spec": f"P45 {n:03d}", "specCode": f"P45{n:03d}"} for n in range(100)]})
        self.assertIsInstance(material_id, str)
        material = self.api.request("GET", f"/api/materials/{material_id}")
        skus = material["skus"]
        self.assertEqual(len(skus), 2000)
        self.assertEqual(len({row["id"] for row in skus}), 2000)
        self.assertTrue(all(isinstance(row["id"], str) for row in skus))
        self.facts["rawHttp"] = []

        def create(label, lines):
            made = self.api.request("POST", "/api/inventory-docs", {
                "docType": "OTHER_IN", "companyId": self.fixture["companyId"], "docDate": day,
                "toWarehouseId": self.fixture["warehouseId"], "reason": "OTHER",
                "remark": "匿名精度回归 " + label, "items": lines})
            path = f"/api/inventory-docs/{made['id']}"
            status, headers, raw = self.api.raw("GET", path)
            response = json.loads(raw)
            self.assertEqual(status, 200)
            self.assertEqual(response["code"], 0)
            self.assertTrue(headers.get("X-Request-Id"))
            write_evidence(self.case_dir / (label + "-raw-detail.json"), response, self.api.token)
            self.facts["rawHttp"].append({"path": path, "requestId": headers.get("X-Request-Id"),
                                         "file": label + "-raw-detail.json", "rows": len(response["data"]["items"])})
            return response["data"]

        amount = create("amount", [{"materialSkuId": row["id"], "qty": "100",
            "price": "9999999999.9998" if n == 1999 else "9999999999.9999"} for n, row in enumerate(skus)])
        quantity = create("quantity", [{"materialSkuId": row["id"],
            "qty": "99999999999.998" if n == 1999 else "99999999999.999", "price": "0"}
            for n, row in enumerate(skus)])
        unknown = create("mixed-unknown", [{"materialSkuId": skus[0]["id"], "qty": "1"},
            {"materialSkuId": skus[1]["id"], "qty": "1", "price": "12.34"}])
        self.assertEqual(amount["totalAmount"], "1999999999999979.99")
        self.assertEqual(amount["totalQty"], "200000.000")
        self.assertEqual(quantity["totalQty"], "199999999999997.999")
        self.assertEqual(quantity["totalAmount"], "0.00")
        self.assertIsNone(unknown["totalAmount"])
        cases = [("amount", amount, "200000", "1,999,999,999,999,979.99"),
                 ("quantity", quantity, "199999999999997.999", "0.00"),
                 ("mixed-unknown", unknown, "2", "—")]

        def cell(label):
            table = self.page.locator(".el-table:visible").first
            headers = table.locator(".el-table__header-wrapper th")
            labels = [value.strip() for value in headers.all_text_contents()]
            self.assertIn(label, labels)
            return table.locator(".el-table__body-wrapper tbody tr").first.locator("td").nth(labels.index(label)).locator(".cell")

        def measure(element):
            return element.evaluate("""element => {
              const range = document.createRange(); range.selectNodeContents(element);
              const bounds = range.getBoundingClientRect(), style = getComputedStyle(element);
              return {text: element.textContent.trim(), textWidth: bounds.width,
                availableWidth: element.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight),
                whiteSpace: style.whiteSpace, overflow: style.overflow, clientHeight: element.clientHeight,
                viewportWidth: innerWidth};
            }""")

        self.page.goto(self.ui_base + "/stock-in?tab=docs")
        expect(self.page.locator(".filter-bar:visible")).to_be_visible()
        self.page.get_by_role("button", name="显示列", exact=True).click()
        columns = self.page.get_by_role("group", name="显示哪些列", exact=True)
        self.checkbox(columns, "数量", True)
        self.checkbox(columns, "金额", True)
        self.page.get_by_role("button", name="显示列", exact=True).click()
        for label, doc, qty_text, amount_text in cases:
            with self.subTest(view="list", case=label):
                search_field = self.page.locator(".filter-bar:visible .search input")
                search_field.fill(doc["no"])
                with self.page.expect_response(lambda response: self.response_for(response, "GET", "/api/inventory-docs")
                    and parse_qs(urlsplit(response.url).query).get("keyword") == [doc["no"]]) as found:
                    search_field.press("Enter")
                response = found.value.json()
                self.assertEqual(response["code"], 0)
                rows = response["data"]["list"]
                self.assertEqual([row["id"] for row in rows], [doc["id"]])
                self.assertEqual(rows[0]["totalQty"], doc["totalQty"])
                self.assertEqual(rows[0]["totalAmount"], doc["totalAmount"])
                expect(cell("数量")).to_have_text(qty_text)
                expect(cell("金额")).to_have_text(amount_text)
                self.page.locator(".el-table:visible .el-table__body-wrapper .el-scrollbar__wrap").first.evaluate(
                    "element => { element.scrollLeft = element.scrollWidth }")
                # Wait for the actual selected row and composited frame before measuring/capturing.
                active_row = self.page.locator(".el-table:visible .el-table__body-wrapper tbody tr").first
                expect(active_row.locator("td").nth(1)).to_have_text(doc["no"])
                self.page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
                measurements = {name: measure(cell(name)) for name in ("数量", "金额")}
                self.note("actual aggregate list cells", case=label, measurements=measurements,
                          totalQty=doc["totalQty"], totalAmount=doc["totalAmount"], responseMocks=False)
                self.shot(label + "-list-before-width-assert")
                expect(active_row.locator("td").nth(1)).to_have_text(doc["no"])
                expect(cell("数量")).to_have_text(qty_text)
                expect(cell("金额")).to_have_text(amount_text)
                for name, measured in measurements.items():
                    self.assertLessEqual(measured["textWidth"], measured["availableWidth"] + 1,
                                         f"{label} {name} must remain completely visible")

        for label, doc, qty_text, amount_text in cases:
            with self.subTest(view="detail", case=label):
                path = f"/api/inventory-docs/{doc['id']}"
                with self.page.expect_response(lambda response: self.response_for(response, "GET", path)):
                    self.page.goto(self.ui_base + f"/stock-in/{doc['id']}")
                summary = self.page.locator(".sub .num")
                expect(summary).to_have_text(qty_text + (" · " + amount_text if amount_text != "—" else ""))
                item_values = {
                    "amount": ("100", "9999999999.9999", "999,999,999,999.99"),
                    "quantity": ("99999999999.999", "0", "0.00"),
                    "mixed-unknown": ("1", "—", "—"),
                }[label]
                expect(cell("数量")).to_have_text(re.compile(r"^" + re.escape(item_values[0]) + r"\s+米$"))
                expect(cell("单价")).to_have_text(item_values[1])
                expect(cell("金额")).to_have_text(item_values[2])
                item_measurements = {name: measure(cell(name)) for name in ("数量", "单价", "金额")}
                self.note("actual detail item numeric cells", case=label, measurements=item_measurements,
                          expectedValues=item_values, responseMocks=False)
                self.shot(label + "-detail")
                for name, measured in item_measurements.items():
                    self.assertLessEqual(measured["textWidth"], measured["availableWidth"] + 1,
                                         f"{label} item {name} must remain completely visible")
                self.note("actual aggregate detail summary", case=label, visibleText=summary.inner_text(),
                          rowCount=len(doc["items"]), responseMocks=False)
        self.note("real 20 colors by 100 specifications aggregate precision", materialId=material_id,
                  skuCount=2000, documentIds=[doc["id"] for _, doc, _, _ in cases],
                  amount="1999999999999979.99", quantity="199999999999997.999",
                  mixedUnknownTotal=None, zeroDisplayedAs="0.00", responseMocks=False)



if __name__ == "__main__":
    unittest.main(verbosity=2)
