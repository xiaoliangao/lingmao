"""Public tags through real UI and local HTTP, with no fabricated responses or SQL."""
from __future__ import annotations

import json
import re
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

from playwright.sync_api import expect

from harness import query_path, write_evidence


def run_tag_lifecycle(test):
    api, page = test.api, test.page
    order = test.domain.new_order(marker="AUDIT-TAGS")
    identity, company = order["id"], order["companyId"]
    marker = uuid4().hex[:8]
    original = "合成公共标签-" + marker
    literal = "#保留前缀-" + marker
    renamed = "合成已改名-" + marker
    late = "并发已保存-" + marker
    mine = "当前输入-" + marker
    object_path = "/api/tags/objects/ORDER/" + identity
    object_url = query_path(object_path, {"companyId": company})
    route = query_path("/orders/" + identity, {"companyId": company})
    test.facts["rawHttp"] = []

    def record(method, path, status, request_id, response, body=None, expected=0):
        test.assertTrue(request_id)
        test.assertEqual(response["code"], expected)
        test.facts["rawHttp"].append({"method": method, "path": path, "httpStatus": status,
                                     "requestId": request_id, "requestBody": body, "response": response})
        return response.get("data")

    def observed(client, method, path, body=None, expected=0):
        status, headers, raw = client.raw(method, path, body)
        # Freeze before a later caller can change any nested request value.
        frozen = None if body is None else json.loads(json.dumps(body, ensure_ascii=False))
        return record(method, path, status, headers.get("X-Request-Id"), json.loads(raw), frozen, expected)

    def browser_reply(response, expected=0):
        body = response.request.post_data_json if response.request.post_data else None
        return record(response.request.method, response.url.removeprefix(test.ui_base), response.status,
                      response.header_value("X-Request-Id"), response.json(), body, expected)

    def snapshot():
        result = observed(api, "GET", object_url)
        test.assertIsInstance(result["version"], int)
        test.assertTrue(all(isinstance(tag["id"], str) for tag in result["tags"]))
        return result

    def card():
        return page.locator('.object-tags[data-tag-type="ORDER"][data-tag-object="' + identity + '"]')

    def edit():
        card().get_by_role("button", name="编辑标签", exact=True).click()
        dialog = page.get_by_role("dialog", name="编辑公共标签", exact=True)
        expect(dialog).to_be_visible()
        return dialog

    def add(dialog, text):
        dialog.get_by_label("标签输入", exact=True).fill(text)
        dialog.get_by_role("button", name="添加标签", exact=True).click()
        expect(dialog.get_by_label("标签输入", exact=True)).to_have_value("")

    def save(dialog, label="保存标签", code=0):
        with page.expect_response(lambda response: test.response_for(response, "POST", object_path + "/replace")) as sent:
            dialog.get_by_role("button", name=label, exact=True).click()
        result = browser_reply(sent.value, code)
        if code == 0:
            expect(dialog).not_to_be_visible()
        return result

    page.goto(test.ui_base + route)
    expect(card().get_by_role("button", name="编辑标签", exact=True)).to_be_enabled()
    test.assertEqual(snapshot(), {"version": 0, "tags": []})
    dialog = edit()
    field = dialog.get_by_label("标签输入", exact=True)
    field.fill("#" + original)
    field.dispatch_event("keyup", {"key": "Enter", "code": "Enter", "isComposing": True, "keyCode": 229})
    expect(dialog.locator(".tag-selection [data-tag-id]")).to_have_count(0)
    expect(dialog.get_by_text("未选择标签", exact=True)).to_be_visible()
    expect(field).to_have_value("#" + original)
    field.press("Enter")
    expect(dialog.locator(".tag-selection li")).to_have_count(1)
    add(dialog, "#" + literal)
    test.shot("tags-add-ime-and-literal-prefix")
    first = save(dialog)
    test.assertEqual([tag["name"] for tag in first["tags"]], [original, literal])
    test.assertEqual(first["version"], 1)
    test.assertEqual(snapshot(), first)
    original_id = first["tags"][0]["id"]
    test.shot("tags-object-persisted")

    # Another real write commits after the editor has captured its version.
    dialog = edit()
    changed = observed(api, "POST", query_path(object_path + "/replace", {"companyId": company}), {
        "version": first["version"], "names": ["#" + tag["name"] for tag in first["tags"]] + ["#" + late],
        "retainIds": [tag["id"] for tag in first["tags"]],
        "tagVersions": {tag["id"]: tag["version"] for tag in first["tags"]}})
    add(dialog, "#" + mine)
    save(dialog, code=1002)
    expect(dialog.locator(".el-alert--error")).to_be_visible()
    expect(dialog.locator(".tag-selection")).to_contain_text(mine)
    test.shot("tags-real-version-conflict-preserves-input")
    with page.expect_response(lambda response: test.response_for(response, "GET", object_path)) as reread:
        dialog.get_by_role("button", name="重新读取并核对", exact=True).click()
    browser_reply(reread.value)
    latest = dialog.locator(".latest-tags")
    expect(latest).to_contain_text(late)
    latest.get_by_role("button", name="#" + late, exact=True).click()
    merged = save(dialog, "确认当前选择并保存")
    test.assertEqual(merged["version"], changed["version"] + 1)
    test.assertEqual({tag["name"] for tag in merged["tags"]}, {original, literal, mine, late})

    page.goto(test.ui_base + "/tags")
    search = page.get_by_label("搜索公共标签", exact=True)
    search.fill(original)
    with page.expect_response(lambda response: test.response_for(response, "GET", "/api/tags")
                              and parse_qs(urlsplit(response.url).query).get("keyword") == [original]) as searched:
        search.press("Enter")
    options = browser_reply(searched.value)
    test.assertEqual([tag["id"] for tag in options], [original_id])
    row = page.locator(".el-table__body-wrapper tr").filter(has_text="#" + original)
    expect(row).to_have_count(1)
    row.get_by_role("button", name="重命名", exact=True).click()
    rename = page.get_by_role("dialog", name="重命名公共标签", exact=True)
    rename.get_by_label("标签新名称", exact=True).fill(renamed)
    with page.expect_response(lambda response: test.response_for(response, "POST", "/api/tags/" + original_id + "/rename")) as sent:
        rename.get_by_role("button", name="保存名称", exact=True).click()
    renamed_row = browser_reply(sent.value)
    test.assertEqual(renamed_row["name"], renamed)
    expect(rename).not_to_be_visible()
    search.fill(renamed)
    with page.expect_response(lambda response: test.response_for(response, "GET", "/api/tags")
                              and parse_qs(urlsplit(response.url).query).get("keyword") == [renamed]) as searched:
        search.press("Enter")
    browser_reply(searched.value)
    row = page.locator(".el-table__body-wrapper tr").filter(has_text="#" + renamed)
    expect(row).to_have_count(1)
    test.shot("tags-library-renamed-real-row")
    row.get_by_role("button", name="归档", exact=True).click()
    with page.expect_response(lambda response: test.response_for(response, "POST", "/api/tags/" + original_id + "/archive")) as sent:
        page.get_by_role("button", name="归档标签", exact=True).click()
    archived = browser_reply(sent.value)
    test.assertTrue(archived["archived"])

    page.goto(test.ui_base + route)
    archived_chip = card().locator(".tag-chip.archived").filter(has_text=renamed)
    expect(archived_chip).to_be_visible()
    retained = snapshot()
    test.assertTrue(next(tag for tag in retained["tags"] if tag["id"] == original_id)["archived"])
    with page.expect_response(lambda response: test.response_for(response, "GET", "/api/tags/" + original_id + "/logs")) as history:
        archived_chip.click()
    history_data = browser_reply(history.value)
    test.assertTrue({"rename", "archive"}.issubset({row["action"] for row in history_data["list"]}))
    history_dialog = page.get_by_role("dialog", name="标签记录 · #" + renamed, exact=True)
    expect(history_dialog.get_by_text("重命名", exact=True)).to_be_visible()
    expect(history_dialog.get_by_text("归档", exact=True)).to_be_visible()
    test.shot("tags-real-history-and-archived-association")
    history_dialog.get_by_role("button", name="关闭", exact=True).click()
    dialog = edit()
    preserved = save(dialog)
    test.assertEqual([tag["id"] for tag in preserved["tags"]], [tag["id"] for tag in retained["tags"]])
    dialog = edit()
    dialog.get_by_role("button", name="移除标签 " + renamed, exact=True).click()
    removed = save(dialog)
    test.assertNotIn(original_id, [tag["id"] for tag in removed["tags"]])
    dialog = edit()
    add(dialog, "#" + renamed)
    recreated = save(dialog)
    new_tag = next(tag for tag in recreated["tags"] if tag["name"] == renamed)
    test.assertNotEqual(new_tag["id"], original_id)
    test.assertFalse(new_tag["archived"])
    test.assertEqual(next(tag for tag in recreated["tags"] if tag["name"] == literal)["id"], first["tags"][1]["id"])
    test.shot("tags-archived-name-recreated-with-new-identity")

    reader = test.actor("tag_reader")
    readonly_context = test.new_context(actor=reader)
    readonly_page = readonly_context.new_page()
    readonly_page.goto(test.ui_base + route)
    readonly = readonly_page.locator('.object-tags[data-tag-type="ORDER"]')
    expect(readonly).to_contain_text(renamed)
    expect(readonly.get_by_role("button", name="编辑标签", exact=True)).to_have_count(0)
    expect(readonly.get_by_text("只读", exact=True)).to_be_visible()
    observed(reader, "POST", query_path(object_path + "/replace", {"companyId": company}), {
        "version": recreated["version"], "names": [], "retainIds": [],
        "tagVersions": {tag["id"]: tag["version"] for tag in recreated["tags"]}}, expected=403)
    test.assertEqual(snapshot(), recreated)
    test.shot("tags-readonly-role-real-object", readonly_page)
    readonly_page.goto(test.ui_base + "/tags")
    expect(readonly_page.locator(".page-header h1")).to_have_text("公共标签库")
    expect(readonly_page.get_by_role("button", name="重命名", exact=True)).to_have_count(0)
    expect(readonly_page.get_by_role("button", name="归档", exact=True)).to_have_count(0)
    test.note("public tag real lifecycle", orderId=identity, archivedId=original_id, recreatedId=new_tag["id"],
              currentVersion=recreated["version"], imeEnterIgnored=True, staleVersionRejected=True,
              archivedAssociationRetained=True, readonlyWriteDenied=True)
    write_evidence(test.case_dir / "raw-tag-http.json", test.facts["rawHttp"], api.token)
