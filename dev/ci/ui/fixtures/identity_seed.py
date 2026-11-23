"""Anonymous UI identities with real roles/menu permissions; no credential files."""
from __future__ import annotations

import os

from harness import ApiClient


def create_identities(admin: ApiClient, company_id: int) -> dict:
    tree = admin.request("GET", "/api/menus/tree")
    nodes = {}
    def visit(rows):
        for row in rows:
            nodes[row["id"]] = row
            visit(row.get("children") or [])
    visit(tree)
    lists = {"order:order:list", "purchase:order:list", "production:order:list"}
    creators = lists | {f"{domain}:order:{action}" for domain in ("order", "purchase", "production")
                       for action in ("add", "edit", "submit")}
    # Creating ordinary priced fixtures requires each independent financial domain.
    creators |= {"order:price:query", "production:price:query", "techpack:cost:query"}
    definitions = {
        "owner": ("SELF", creators),
        "outsider": ("SELF", lists),
        "auditor": ("SELF", lists | {"order:order:audit", "purchase:order:audit", "production:order:audit"}),
        "designated": ("SELF", lists),
        "all_reader": ("ALL", lists),
        "no_list": ("SELF", set()),
        "pack_editor": ("SELF", {"style:style:list", "techpack:pack:list", "techpack:pack:edit"}),
        "mobile_only": ("SELF", {"production:mobile:list"}),
        "warehouse_only": ("SELF", {"inventory:doc:list", "inventory:doc:add"}),
        "fg_maker": ("ALL", {"fg:doc:list", "fg:doc:add", "fg:doc:edit", "fg:doc:remove", "fg:doc:audit", "fg:stock:list"}),
        "fg_auditor": ("ALL", {"fg:doc:list", "fg:doc:audit", "fg:stock:list"}),
    }
    password = os.environ.get("SCM_UI_PASSWORD")
    if not password:
        raise RuntimeError("The isolated runner must provide its in-memory synthetic password")
    if not 8 <= len(password) <= 32:
        raise RuntimeError("Synthetic identity password must meet UserCreateReq's 8–32 character limit")
    result = {}
    for key, (scope, permissions) in definitions.items():
        matches = {row["id"] for row in nodes.values() if row.get("perms") in permissions}
        found = {nodes[identity].get("perms") for identity in matches}
        if not permissions.issubset(found):
            raise AssertionError(f"Existing menu definitions are missing a required {key} permission")
        for identity in tuple(matches):
            parent = nodes[identity]["parentId"]
            while parent and parent in nodes:
                matches.add(parent)
                parent = nodes[parent]["parentId"]
        role_id = admin.request("POST", "/api/roles", {
            "code": "audit_ui_" + key, "name": "合成回归角色 " + key,
            "sort": 99, "status": 1, "dataScope": scope, "remark": "仅隔离回归"})
        admin.request("PUT", f"/api/roles/{role_id}/menus", {"ids": sorted(matches)})
        username = "audit_ui_" + key
        user_id = admin.request("POST", "/api/users", {
            "username": username, "password": password, "nickname": "合成回归 " + key,
            "defaultCompanyId": company_id, "phone": None, "dept": "合成回归", "post": None, "designerNo": None})
        admin.request("PUT", f"/api/users/{user_id}/roles", {"ids": [role_id]})
        actor = ApiClient(admin.origin)
        me = actor.login(username)
        if me["id"] != user_id or not permissions.issubset(set(me["perms"])):
            raise AssertionError(f"Anonymous {key} identity does not have its declared permissions")
        if key == "pack_editor" and "techpack:cost:query" in me["perms"]:
            raise AssertionError("The no-cost editor must not have cost authority")
        if key in {"designated", "all_reader", "outsider", "no_list"} and any(p.endswith(":audit") for p in me["perms"]):
            raise AssertionError("Read-only identity accidentally received an audit permission")
        if key == "mobile_only" and set(me["perms"]) != permissions:
            raise AssertionError("The mobile-only identity must not inherit desktop or ALL authority")
        if key == "warehouse_only" and set(me["perms"]) != permissions:
            raise AssertionError("The independent warehouse identity must not inherit purchase, audit or ALL authority")
        result[key] = {"userId": user_id, "roleId": role_id, "username": username,
                       "dataScope": scope, "permissions": sorted(permissions), "menuIds": sorted(matches)}
        if key == "mobile_only":
            # Shared report buttons live under the desktop menu. C19 grants only
            # these button IDs later, never their production:order:list parent.
            report_permissions = {"production:node:edit", "production:issue:edit"}
            report_ids = {row["id"] for row in nodes.values() if row.get("perms") in report_permissions}
            if {nodes[identity]["perms"] for identity in report_ids} != report_permissions:
                raise AssertionError("Mobile report permissions must use the existing shared button definitions")
            result[key]["reportMenuIds"] = sorted(report_ids)
    return result
