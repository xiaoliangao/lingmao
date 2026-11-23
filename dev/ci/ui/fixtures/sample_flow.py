"""Sample lifecycle through the production UI and actual local HTTP endpoints.

The shared runner owns an isolated database and synthetic identities. This module
does not use SQL, route fulfilment, fabricated API responses or production data.
"""
from __future__ import annotations

from datetime import timedelta
from decimal import Decimal, ROUND_HALF_UP
import json
import re
from uuid import uuid4

from playwright.sync_api import expect

from harness import business_day, query_path, write_evidence


def run_sample_lifecycle(test):
    api, page, fixture = test.api, test.page, test.fixture
    company = fixture["companyId"]
    day = business_day()
    marker = uuid4().hex[:8]
    name = "合成样衣闭环 " + marker
    revised_name = "合成样衣修订 " + marker
    sizes = ["S", "M", "L", "XL", "XXL"]
    size_name = "样衣闭环五码 " + marker
    group = api.request("POST", "/api/size-groups", {"name": size_name, "sizes": " ".join(sizes), "baseSize": "L"})
    test.assertIsInstance(group, str)
    colors = api.request("GET", "/api/colors/options")[:3]
    test.assertEqual(len(colors), 3)
    test.assertTrue(all(isinstance(color["id"], str) for color in colors))
    brand = next(row for row in api.request("GET", "/api/dict-data/by-type/brand") if row["value"] == fixture["brand"])
    season = next(row for row in api.request("GET", "/api/dict-data/by-type/season") if row["value"] == "AUTUMN")
    tree = api.request("GET", "/api/categories/tree")

    def leaves(nodes):
        for node in nodes:
            if node.get("children"):
                yield from leaves(node["children"])
            else:
                yield node

    category = next(leaves(tree))
    test.facts["rawHttp"] = []

    def observed(client, method, path, body=None):
        status, headers, raw = client.raw(method, path, body)
        response = json.loads(raw)
        request_id = headers.get("X-Request-Id")
        test.assertTrue(request_id)
        test.facts["rawHttp"].append({"method": method, "path": path, "httpStatus": status,
                                     "requestId": request_id, "response": response})
        test.assertEqual(response["code"], 0)
        return response["data"]

    def field(dialog, label):
        return dialog.locator(".el-form-item").filter(
            has=page.locator(".el-form-item__label", has_text=re.compile("^" + re.escape(label) + "$")))

    def select(dialog, label, option):
        field(dialog, label).locator(".el-select").first.click()
        page.get_by_role("option", name=option, exact=True).last.click()

    def ui_reply(response):
        value = response.json()
        test.assertEqual(value["code"], 0)
        test.assertTrue(response.header_value("X-Request-Id"))
        test.facts["rawHttp"].append({"method": response.request.method, "path": response.url.removeprefix(test.ui_base),
                                     "httpStatus": response.status, "requestId": response.header_value("X-Request-Id"),
                                     "response": value})
        return value["data"]

    page.goto(test.ui_base + query_path("/samples", {"companyId": company}))
    page.get_by_role("button", name="新建样衣", exact=True).click()
    dialog = page.get_by_role("dialog", name="新建样衣", exact=True)
    expect(dialog.get_by_role("button", name="建样衣", exact=True)).to_be_enabled()
    dialog.get_by_label("样衣名称", exact=True).fill(name)
    select(dialog, "品牌", brand["label"])
    field(dialog, "品类").locator(".el-select").click()
    field(dialog, "品类").get_by_role("combobox").fill(category["name"])
    page.get_by_role("option", name=category["name"], exact=True).last.click()
    select(dialog, "季节", season["label"])
    select(dialog, "尺码组", size_name)
    for color in colors:
        dialog.get_by_text(color["code"] + " " + color["name"], exact=True).click()
    dialog.get_by_label("预估吊牌价", exact=True).fill("199.99")
    dialog.get_by_label("预估成本", exact=True).fill("45.6789")
    dialog.get_by_label("首版要样日期", exact=True).fill((day + timedelta(days=10)).isoformat())
    dialog.get_by_label("首版要样日期", exact=True).press("Tab")
    expect(dialog.get_by_text("已选 3 色 · 5 码", exact=False)).to_be_visible()
    test.shot("sample-create-three-colors-five-sizes")
    with page.expect_response(lambda response: test.response_for(response, "POST", "/api/samples")) as created_http:
        dialog.get_by_role("button", name="建样衣", exact=True).click()
    made = ui_reply(created_http.value)
    sample_id = made["id"]
    test.assertIsInstance(sample_id, str)
    test.assertIsInstance(made["currentOrderId"], str)
    detail_path = query_path("/api/samples/" + sample_id, {"companyId": company})
    route = query_path("/samples/" + sample_id, {"companyId": company})
    expect(page.locator(".sample-detail h2")).to_have_text(name)

    def detail(client=api):
        result = observed(client, "GET", detail_path)
        test.assertIsInstance(result["id"], str)
        test.assertIsInstance(result["version"], int)
        return result

    initial = detail()
    test.assertEqual(initial["colorIds"], [color["id"] for color in colors])
    test.assertEqual(initial["sizes"], sizes)
    test.assertEqual(initial["estCost"], "45.6789")
    test.assertEqual(initial["currentOrder"]["status"], "WAIT_ASSIGN")
    test.shot("sample-initial-persisted-version")
    page.get_by_role("button", name="编辑", exact=True).click()
    edit = page.get_by_role("dialog", name=re.compile("编辑样衣"))
    expect(edit.get_by_role("button", name="保存", exact=True)).to_be_enabled()
    edit.get_by_label("样衣名称", exact=True).fill(revised_name)
    edit.get_by_label("预估成本", exact=True).fill("67.8912")
    with page.expect_response(lambda response: test.response_for(response, "PUT", "/api/samples/" + sample_id)) as edited:
        edit.get_by_role("button", name="保存", exact=True).click()
    ui_reply(edited.value)
    expect(page.locator(".sample-detail h2")).to_have_text(revised_name)
    changed = detail()
    test.assertEqual(changed["version"], initial["version"] + 1)
    test.assertEqual(changed["estCost"], "67.8912")
    audit = observed(api, "GET", query_path("/api/data-logs", {"rootTable": "dev_sample", "rootId": sample_id, "limit": 100}))
    name_changes = [row for row in audit if row["table"] == "dev_sample" and row.get("diff", {}).get("name") == {"old": name, "new": revised_name}]
    test.assertEqual(len(name_changes), 1)
    cost_diff = name_changes[0]["diff"]["est_cost"]
    test.assertEqual(Decimal(str(cost_diff["old"])), Decimal("45.6789"))
    test.assertEqual(Decimal(str(cost_diff["new"])), Decimal("67.8912"))
    reader = test.actor("sample_reader")
    protected = detail(reader)
    test.assertNotIn("estCost", protected)
    test.assertEqual(protected["estTagPrice"], "199.99")
    test.note("real sample edit CAS and field diff", sampleId=sample_id, auditId=name_changes[0]["id"], version=changed["version"])

    pack_id = observed(api, "POST", "/api/tech-packs", {"sampleId": sample_id, "companyId": company, "stage": "DEV"})
    pack = observed(api, "GET", "/api/tech-packs/" + pack_id)
    original_price = "11.0000"
    observed(api, "PUT", "/api/tech-packs/" + pack_id, {
        "versionLock": pack["versionLock"], "versionNote": "真实样衣研发BOM", "utilPct": "100",
        "specPoints": [{"point": "胸围", "method": "平铺量取", "toleranceCm": "0.50", "gradeCm": "2.00",
                        "remark": "合成测量", "values": {size: str(46 + index * 2) for index, size in enumerate(sizes)}}],
        "bomItems": [{"part": "合成主料", "materialId": fixture["materialId"], "skuMode": "BY_COLOR", "usageMode": "UNIFORM",
                      "usageQty": "1.5000", "lossPct": "2.50", "source": "PURCHASE", "supplierId": fixture["supplierId"],
                      "refPrice": original_price, "skus": [{"colorId": color["id"], "materialSkuId": fixture["materialSkuId"]} for color in colors]}],
        "craftSections": [], "processes": []})
    pack = observed(api, "GET", "/api/tech-packs/" + pack_id)
    observed(api, "POST", "/api/tech-packs/" + pack_id + "/publish", {"versionLock": pack["versionLock"]})
    page.goto(test.ui_base + query_path("/samples/" + sample_id, {"companyId": company, "tab": "techpack"}))
    expect(page.locator(".tp-wrap .pack-meta")).to_be_visible()
    expect(page.get_by_test_id("bom-inspector")).to_be_visible()
    expect(page.locator(".bom-style-image small")).to_contain_text("当前样衣主图")
    test.shot("sample-real-development-bom")

    def send_for_review():
        value = detail()
        order = value["currentOrder"]
        observed(api, "POST", query_path("/api/sample-orders/" + order["id"] + "/dispatch", {"companyId": company}),
                 {"version": order["version"], "sampleVersionLock": value["version"], "makeWay": "INTERNAL",
                  "makerId": fixture["userId"], "needDate": (day + timedelta(days=10)).isoformat()})
        value = detail(); order = value["currentOrder"]
        send = next(node for node in order["nodes"] if node["nodeCode"] == "SEND")
        result = observed(api, "POST", query_path("/api/sample-orders/" + order["id"] + "/nodes/" + send["id"] + "/done", {"companyId": company}),
                          {"version": order["version"], "sampleVersionLock": value["version"], "doneDate": day.isoformat()})
        test.assertEqual(result["orderStatus"], "WAIT_REVIEW")
        page.goto(test.ui_base + route)
        expect(page.get_by_role("button", name="评审", exact=True)).to_be_visible()
        return order["id"]

    first_order = send_for_review()
    page.get_by_role("button", name="评审", exact=True).click()
    review = page.get_by_role("dialog", name="样衣评审", exact=True)
    review.get_by_text("改版", exact=True).click()
    opinion = "合成领型需要调整，完整保留改版意见。"
    review.get_by_label("评审意见", exact=True).fill(opinion)
    test.shot("sample-real-review-revise")
    with page.expect_response(lambda response: test.response_for(response, "POST", "/api/sample-orders/" + first_order + "/review")) as reviewed:
        review.get_by_role("button", name="改版，生成下一版", exact=True).click()
    revision = ui_reply(reviewed.value)
    test.assertEqual(revision["sampleVersion"], 2)
    expect(page.locator('[data-sample-version="1"]')).to_contain_text("历史版 · 只读")
    second = detail()
    test.assertEqual(second["currentOrder"]["reason"], opinion)
    test.assertNotEqual(second["currentOrder"]["id"], first_order)
    test.shot("sample-revision-history-persisted")
    second_order = send_for_review()
    page.get_by_role("button", name="评审", exact=True).click()
    review = page.get_by_role("dialog", name="样衣评审", exact=True)
    review.get_by_label("评审意见", exact=True).fill("合成二版评审通过")
    with page.expect_response(lambda response: test.response_for(response, "POST", "/api/sample-orders/" + second_order + "/review")) as passed_http:
        review.get_by_role("button", name="通过", exact=True).click()
    passed = ui_reply(passed_http.value)
    test.assertEqual(passed["sampleStatus"], "PASSED")
    # Create a real current supplier price; a material SKU's nullable fallback is not the current-price contract.
    archived_price = "23.4567"
    price_id = observed(api, "POST", "/api/prices", {
        "type": "MATERIAL", "supplierId": fixture["supplierId"], "materialId": fixture["materialId"],
        "materialSkuId": fixture["materialSkuId"], "price": archived_price, "taxPct": "13",
        "taxIncluded": True, "freightIncluded": True, "effectiveDate": day.isoformat(),
        "reason": "合成样衣转款现价校验"})
    current = observed(api, "GET", query_path("/api/prices/current", {
        "type": "MATERIAL", "supplierId": fixture["supplierId"], "materialId": fixture["materialId"],
        "materialSkuId": fixture["materialSkuId"], "date": day.isoformat()}))
    test.assertEqual(current["current"]["id"], price_id)
    test.assertEqual(Decimal(str(current["current"]["price"])), Decimal(archived_price))
    material = observed(api, "GET", "/api/materials/" + fixture["materialId"])
    factor = Decimal(str(material.get("conversionFactor") or "1"))
    current_price = (Decimal(archived_price) / factor).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    expect(page.get_by_role("button", name="转大货", exact=True)).to_be_visible()
    page.get_by_role("button", name="转大货", exact=True).click()
    conversion = page.get_by_role("dialog", name="转大货", exact=True)
    expect(conversion.get_by_role("button", name="生成款式", exact=True)).to_be_enabled()
    conversion.get_by_text("手填款号", exact=True).click()
    conversion.get_by_label("转换款号", exact=True).fill("AUS" + marker.upper())
    expect(conversion).to_contain_text("将生成 15 个 SKU")
    test.shot("sample-convert-three-by-five-preview")
    with page.expect_response(lambda response: test.response_for(response, "POST", "/api/samples/" + sample_id + "/convert")) as converted_http:
        conversion.get_by_role("button", name="生成款式", exact=True).click()
    converted = ui_reply(converted_http.value)
    test.assertEqual(converted["skuCount"], 15)
    test.assertIsInstance(converted["styleId"], str)
    test.assertIsInstance(converted["prodPackId"], str)
    expect(page).to_have_url(re.compile("/styles/" + converted["styleId"] + r"\?"))
    expect(page.locator(".tp-wrap .pack-meta")).to_be_visible()
    production = observed(api, "GET", "/api/tech-packs/" + converted["prodPackId"])
    development = observed(api, "GET", "/api/tech-packs/" + pack_id)
    test.assertEqual(production["stage"], "PROD")
    test.assertEqual(production["status"], "DRAFT")
    test.assertEqual(production["version"], 1)
    test.assertEqual(development["styleId"], converted["styleId"])
    test.assertEqual(Decimal(str(development["bomItems"][0]["refPrice"])), Decimal(original_price))
    test.assertEqual(Decimal(str(production["bomItems"][0]["refPrice"])), current_price)
    test.assertEqual(production["bomItems"][0]["priceSourceId"], price_id)
    skus = observed(api, "GET", "/api/styles/" + converted["styleId"] + "/skus")
    test.assertEqual(len(skus), 15)
    test.assertEqual(len({(sku["colorId"], sku["sizeCode"]) for sku in skus}), 15)
    test.assertTrue(all(isinstance(sku["id"], str) for sku in skus))
    test.shot("sample-converted-production-draft")
    origin = observed(api, "GET", query_path("/api/samples/by-style/" + converted["styleId"], {"companyId": company}))
    test.assertEqual(origin["id"], sample_id)
    test.assertEqual(detail()["status"], "CONVERTED")
    test.note("sample created, edited, revised, passed and converted through real persistence", sampleId=sample_id,
              styleId=converted["styleId"], developmentPackId=pack_id, productionPackId=converted["prodPackId"],
              skuCount=15, privateCostPhysicallyAbsent=True, sourcePrice=original_price, currentPrice=str(current_price), currentPriceId=price_id)
    write_evidence(test.case_dir / "raw-sample-http.json", test.facts["rawHttp"], api.token)
