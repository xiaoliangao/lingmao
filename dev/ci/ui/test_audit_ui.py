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
                self.shot("failure-state")
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
            context.add_init_script("localStorage.setItem('lingmao-token', " + json.dumps((actor or self.api).token) + ")")
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

    def shot(self, name, page=None):
        page = page or self.page
        destination = self.case_dir / f"{name}.png"
        # Failed login assertions must still never capture password/username values.
        page.screenshot(path=str(destination), full_page=True, animations="disabled",
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
        with page.expect_response(lambda r: self.response_for(r, "GET", path)) as response:
            field.press("Enter")
        return response.value

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
        with self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as response:
            self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(response.value.json()["code"], 0)
        after = self.api.request("GET", path)
        self.assertEqual(line_values(after), line_values(before))
        assert_decimal_equal(after["items"][0]["price"], 12.3456)
        self.note("per-SKU roundtrip", before=line_values(before), after=line_values(after))
        self.page.wait_for_url(self.ui_base + f"/orders/{before['id']}")
        self.shot("mixed-sku-saved")

        self.page.goto(f"{self.ui_base}/orders/{before['id']}/edit")
        expect(self.page.locator(".style-block")).to_have_count(1)
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
        self.page.get_by_role("button", name="继续编辑", exact=True).click()
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
        expect(dialog).to_contain_text("清空")
        self.shot("count-refresh-clears-warning")
        with self.page.expect_response(lambda r: self.response_for(r, "POST", path + "/refresh-book")) as refreshed:
            dialog.get_by_role("button", name="重新取", exact=True).click()
        self.assertEqual(refreshed.value.json()["code"], 0)
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
        self.assertIsNone(returned["items"][0]["bookRevision"])
        self.note("post-submit movement retained; unaudit invalidates measurement", approved=True, bookRevisionAfterUnaudit=None)

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
                shown = response.json()["data"]["list"]
                self.assertEqual({x["id"] for x in shown}, {draft["id"]})
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
        self.shot("receipt-outcome-unknown")
        dialog.get_by_role("button", name="取消", exact=True).click()
        expect(dialog).not_to_be_visible()
        self.page.get_by_role("button", name="登记回货", exact=True).click()
        expect(dialog.get_by_role("button", name="重试原登记", exact=True)).to_be_visible()
        with self.page.expect_response(lambda r: self.response_for(r, "POST", route_path)) as retried:
            dialog.get_by_role("button", name="重试原登记", exact=True).click()
        self.assertEqual(retried.value.json()["code"], 0)
        self.assertEqual(sent[0], sent[1])
        self.assertTrue(re.fullmatch(r"[0-9a-fA-F-]{36}", sent[0]["clientActionId"]))
        self.assertEqual(retried.value.json()["data"]["id"], saved_result[0]["id"])
        after = self.api.request("GET", f"/api/production-orders/{prod_id}")
        expected_increment = sum(x["qty"] for x in sent[0]["items"])
        self.assertEqual(after["returnedQty"] - before["returnedQty"], expected_increment)
        new_action = copy.deepcopy(sent[0])
        new_action["clientActionId"] = str(uuid4())
        new_result = self.api.request("POST", route_path, new_action)
        self.assertNotEqual(new_result["id"], saved_result[0]["id"])
        final = self.api.request("GET", f"/api/production-orders/{prod_id}")
        self.assertEqual(final["returnedQty"] - before["returnedQty"], expected_increment * 2)
        self.page.reload()
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
        self.shot("node-plan-conflict-keeps-local")
        self.note("two sessions cannot overwrite independent plan revision", old=before["planRevision"], current=remote["planRevision"], code=1002)
        mobile = self.new_context(mobile=True).new_page()
        mobile.goto(f"{self.ui_base}/mobile/{prod_id}")
        expect(mobile.get_by_role("button", name="上报异常", exact=True)).to_be_visible()
        self.assertTrue(mobile.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"))
        self.shot("mobile-390-no-horizontal-overflow", mobile)

    def test_08_keywords_limits_real_empty_and_error_feedback(self):
        for count in (1, 200, 201):
            codes = ["ML0001"] + [f"AUDITABSENT{i:03d}" for i in range(count - 1)]
            result = self.api.envelope("GET", query_path("/api/materials", {"keyword": " ".join(codes), "page": 1, "size": 500}))
            self.assertEqual(result["code"], 0 if count <= 200 else 400)
            if count <= 200:
                self.assertIn("ML0001", {x["code"] for x in result["data"]["list"]})
            self.note("multi-code boundary", tokenCount=count, code=result["code"])
        self.page.goto(self.ui_base + "/materials")
        search = self.page.locator(".filter-bar .search input")
        search.evaluate("el => { const data = new DataTransfer(); data.setData('text/plain', 'ML0001\\nFL0008'); el.dispatchEvent(new ClipboardEvent('paste', {clipboardData:data,bubbles:true,cancelable:true})); }")
        expect(search).to_have_value("ML0001 FL0008")
        with self.page.expect_response(lambda r: self.response_for(r, "GET", "/api/materials")) as selected:
            search.press("Enter")
        self.assertEqual({x["code"] for x in selected.value.json()["data"]["list"]}, {"ML0001", "FL0008"})
        self.shot("multi-code-paste-results")
        empty = self.search("/api/materials", "AUDIT-NO-MATCH-" + uuid4().hex)
        self.assertEqual(empty.json()["data"]["total"], 0)
        expect(self.page.get_by_text("没有符合条件的物料", exact=True)).to_be_visible()
        self.shot("real-query-empty-state")
        error = self.search("/api/materials", " ".join("BAD" + str(i) for i in range(201)))
        self.assertEqual(error.json()["code"], 400)
        expect(self.page.locator(".el-message--error")).to_be_visible()
        self.shot("201-codes-error-feedback")
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
        with self.page.expect_response(lambda r: self.response_for(r, "GET", f"/api/tech-packs/{old_id}/cost")):
            route.fulfill(response=real_response)
        self.page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
        expect(price).to_have_value(str(current_cost["buyPrice"]))
        expect(note).to_have_value("新版本本地说明不受旧响应覆盖")
        expect(self.page.get_by_test_id(f"techpack-version-{current_id}")).to_have_attribute("aria-selected", "true")
        with self.page.expect_response(lambda r: self.response_for(r, "PUT", f"/api/tech-packs/{current_id}")) as saved:
            self.page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(saved.value.json()["code"], 0)
        assert_decimal_equal(saved.value.request.post_data_json["cost"]["buyPrice"], current_cost["buyPrice"])
        self.assertEqual(cost_values(self.api.request("GET", f"/api/tech-packs/{current_id}/cost")), cost_values(current_cost))
        self.assertEqual(cost_values(self.api.request("GET", f"/api/tech-packs/{old_id}/cost")), cost_values(old_cost))
        self.shot("late-cost-discarded-current-pack-saved")
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
            with self.page.expect_response(lambda r: self.response_for(r, "PUT", path)) as saved:
                self.page.get_by_role("button", name="保存", exact=True).click()
            self.assertEqual(saved.value.json()["code"], 0)
            self.page.wait_for_url(f"{self.ui_base}/orders/{made['id']}")
            return self.api.request("GET", path)
        block = edit()
        block.get_by_role("checkbox", name="按尺码填单价", exact=True).check()
        block.get_by_test_id("order-size-price-M").locator("input").fill("45.6789")
        self.shot("sku-batch-input-not-applied")
        self.assertEqual(line_values(save()), line_values(original), "Typing into an unapplied batch control cannot rewrite SKU values")
        block = edit()
        block.get_by_role("checkbox", name="按尺码填单价", exact=True).check()
        block.get_by_test_id("order-size-price-M").locator("input").fill("45.6789")
        block.get_by_test_id("order-apply-size-price-M").click()
        self.shot("explicit-M-price-applied")
        size_edited = save()
        for row in size_edited["items"]:
            old = original_rows[row["styleSkuId"]]
            assert_decimal_equal(row["price"], 45.6789 if row["sizeCode"] == "M" else old["price"])
            self.assertEqual((row["lineDueDate"], row["remark"]), (old["lineDueDate"], old["remark"]))
        block = edit()
        block.get_by_role("checkbox", name="按尺码填单价", exact=True).uncheck()
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
                for actor_key in ("outsider", "auditor", "designated"):
                    rows = actors[actor_key].rows(base, {"keyword": foreign["no"], "mine": False})
                    self.assertEqual(rows, [], f"{actor_key} SELF must not become ALL")
                    self.assertEqual(actors[actor_key].envelope("GET", f"{base}/{foreign['id']}")["code"], 404)
                self.assertEqual({row["id"] for row in actors["owner"].rows(base, {"keyword": own["no"]})}, {own["id"]})
                self.assertEqual({row["id"] for row in actors["all_reader"].rows(base, {"keyword": foreign["no"]})}, {foreign["id"]})
                self.assertNotIn("audit", actors["all_reader"].request("GET", f"{base}/{foreign['id']}")["actions"])
                self.assertEqual(actors["no_list"].envelope("GET", base)["code"], 403)
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
                    contradictory = {"keyword": pending["no"], "auditForMe": True, "mine": True, "ownerId": -999}
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
                        with page.expect_response(lambda r: self.response_for(r, "GET", f"/api/board/orders/{current['id']}/review")) as review:
                            page.get_by_role("button", name="审核", exact=True).click()
                        self.assertEqual(review.value.json()["code"], 404)
                        dialog = page.get_by_role("dialog", name=re.compile("审核"))
                        expect(dialog.get_by_text("评审信息暂不可用", exact=False)).to_be_visible()
                        expect(dialog.get_by_role("button", name="通过", exact=True)).to_be_enabled()
                        self.shot("order-review-unavailable-authoritative-audit-remains", page)
                        with page.expect_response(lambda r: self.response_for(r, "POST", audit_path)) as passed:
                            dialog.get_by_role("button", name="通过", exact=True).click()
                    else:
                        with page.expect_response(lambda r: self.response_for(r, "POST", audit_path)) as passed:
                            page.get_by_role("button", name="审核", exact=True).click()
                    self.assertEqual(passed.value.json()["code"], 0)
                    self.assertEqual(self.api.request("GET", f"{base}/{current['id']}")["status"], "APPROVED")
                    self.assertEqual(actors["designated"].rows(base, contradictory), [])
                    self.assertEqual(actors["designated"].envelope("GET", f"{base}/{current['id']}")["code"], 404)
                    page.goto(self.ui_base + ui_route + "?status=PENDING&auditForMe=1")
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
        self.assertIsNone(detail["unitCost"])
        forged = pack_update(detail, {"costMode": "BUY", "buyPrice": 999.9999,
                                      "items": [{"name": "无权限伪造成本", "amount": 99.99, "remark": None}]})
        forged["versionNote"] = "有包编辑权限的合法说明"
        editor.request("PUT", path, forged)
        self.assertEqual(cost_values(self.api.request("GET", path + "/cost")), cost_values(before))
        page = self.new_context(actor=editor).new_page()
        page.goto(f"{self.ui_base}/styles/{self.fixture['styleId']}?tab=techpack&techPackId={pack_id}&sub=craft")
        expect(page.locator(".pack-meta .note-input input")).to_be_visible()
        expect(page.get_by_role("tab", name="核价", exact=True)).to_have_count(0)
        page.locator(".pack-meta .note-input input").fill("无成本权限只存资料包说明")
        with page.expect_response(lambda r: self.response_for(r, "PUT", path)) as saved:
            page.get_by_role("button", name="保存", exact=True).click()
        self.assertEqual(saved.value.json()["code"], 0)
        self.assertIsNone(saved.value.request.post_data_json["cost"])
        self.assertEqual(cost_values(self.api.request("GET", path + "/cost")), cost_values(before))
        self.shot("pack-editor-without-cost-authority", page)
        self.note("Service ignores forged cost while saving permitted fields; UI sends null", costReadCode=403,
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
        dialog.get_by_role("checkbox", name="附工艺标准", exact=True).uncheck()
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
            expect(scope.get_by_role("columnheader", name=column, exact=True).first).to_be_visible()
            rows = data.get("list", []) if isinstance(data, dict) else data
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
        go("/styles", "/api/styles", "styles-list", "款号")
        reads_during(["/api/categories/tree", "/api/size-groups/options"],
                     lambda: page.get_by_role("button", name="新建款式", exact=True).click())
        expect(page.locator(".page-header h1")).to_have_text("新建款式")
        expect(page.get_by_label("款式名称", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="建款式", exact=True)).to_be_enabled()
        self.shot("style-new-head-and-files")
        sku_picker(lambda: page.get_by_role("button", name="从物料库选", exact=True).click(), "style-new-material-picker")
        page.locator(".page-header").get_by_role("button", name="取消", exact=True).click()
        page.wait_for_url(self.ui_base + "/styles")

        go("/suppliers", "/api/suppliers", "suppliers-list", "简称")
        reads_during(["/api/categories/tree"], lambda: page.get_by_role("button", name="新建供应商", exact=True).click())
        dialog = page.get_by_role("dialog", name="新建供应商", exact=True)
        expect(dialog.get_by_label("简称", exact=True)).to_be_visible()
        self.shot("supplier-new-open-cancel")
        cancel(dialog)

        go("/costing", "/api/style-costs", "costing-list", "核算版本")
        go("/mrp", "/api/mrp-calcs", "mrp-list", "单号")
        reads_during(["/api/mrp-calcs/reserve-pcts"], lambda: page.get_by_role("button", name="备料比", exact=True).click())
        dialog = page.get_by_role("dialog", name="备料比", exact=True)
        expect(dialog.get_by_role("button", name="保存", exact=True)).to_be_visible()
        self.shot("mrp-reserve-dialog")
        cancel(dialog)
        pick = reads_during(["/api/mrp-calcs/order-options"], lambda: page.get_by_role("button", name="选订单算料", exact=True).click())
        dialog = page.get_by_role("dialog", name="选订单算料", exact=True)
        ready_table(dialog, "订单号", pick["/api/mrp-calcs/order-options"])
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
        stock_logs = reads_during(["/api/stock-logs"],
            lambda: page.locator(".page-header").get_by_text("日志", exact=True).click())
        ready_table(page, "单价 / 金额", stock_logs["/api/stock-logs"])
        if any(row["valuationStatus"] == "UNKNOWN" for row in stock_logs["/api/stock-logs"]["list"]):
            expect(page.get_by_text("估值未知", exact=True).first).to_be_visible()
        self.shot("stock-ledger-valued-or-unknown")

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
                    reads_during(["/api/companies/options", "/api/warehouses/options"],
                        lambda target=label: page.get_by_role("menuitem", name=target, exact=True).click())
                    dialog = page.get_by_role("dialog", name="新建" + label, exact=True)
                    expect(dialog.get_by_role("columnheader", name="物料", exact=True)).to_be_visible()
                    expect(dialog.get_by_role("button", name="存草稿", exact=True)).to_be_visible()
                    self.shot("inventory-new-" + next(code.lower() for text, code in label_pairs if text == label))
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
        expect(dialog.get_by_role("button", name="选文件", exact=True)).to_be_visible()
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
        expect(dialog.get_by_role("checkbox", name="整款改单价", exact=True).first).to_be_visible()
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

        # Unimplemented API is a visible failure, never a fabricated successful empty list.
        with page.expect_response(lambda response: self.response_for(response, "GET", "/api/board/upstream")) as unavailable:
            page.goto(self.ui_base + "/upstream")
        read_result(unavailable.value, "/api/board/upstream", expected_code=404)
        alert = page.get_by_test_id("upstream-load-error")
        expect(alert).to_contain_text("接口暂未提供")
        expect(page.get_by_text("没有待处理的上游变更。", exact=False)).not_to_be_visible()
        self.shot("upstream-real-unimplemented-error")
        with page.expect_response(lambda response: self.response_for(response, "GET", "/api/board/upstream")) as retried:
            alert.get_by_role("button", name="重试", exact=True).click()
        read_result(retried.value, "/api/board/upstream", expected_code=404)
        expect(alert).to_be_visible()
        self.note("unimplemented board contract stays explicit", route="/upstream", code=404, retry=True, fabricatedEmpty=False)

        self.assertEqual(mutations, [], "Open/cancel smoke unexpectedly sent a business mutation")
        self.note("all added forms cancelled without mutation", mutationCount=0)
        # Parent tearDown still asserts zero console errors, zero page errors and local-only requests.


if __name__ == "__main__":
    unittest.main(verbosity=2)
