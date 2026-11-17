"""Build anonymous real-API fixtures after the runner seeds an isolated *_test DB.

No credential is written, no server lifecycle is controlled, and no production URL
is accepted. A fresh DB/master seed is required for each complete suite run.
"""
from __future__ import annotations

import os
from datetime import timedelta
from pathlib import Path

from fixtures.domain_seed import DomainFixture
from fixtures.identity_seed import create_identities
from harness import ApiClient, business_day, count_update, local_url, read_fixture, write_evidence


def main():
    source, destination = os.environ.get("SCM_UI_FIXTURE_INPUT"), os.environ.get("SCM_UI_FIXTURE")
    if not source or not destination:
        raise RuntimeError("Set SCM_UI_FIXTURE_INPUT and SCM_UI_FIXTURE explicitly")
    f = read_fixture(source)
    for key in ("companyId", "userId", "styleId", "warehouseId", "materialSkuId"):
        if not isinstance(f.get(key), int) or f[key] <= 0:
            raise RuntimeError(f"A positive synthetic {key} is required")
    sku_ids = f.get("styleSkuIdsSameSizeDifferentColor", [])
    if len(sku_ids) != 2 or len(set(sku_ids)) != 2 or any(not isinstance(x, int) or x <= 0 for x in sku_ids):
        raise RuntimeError("Two distinct synthetic style SKU IDs are required")
    api = ApiClient(local_url("SCM_UI_API_BASE", required_port=18080))
    me = api.login()
    if me.get("id") != f["userId"]:
        raise RuntimeError("Synthetic login identity must match fixture.userId")
    options = api.request("GET", f"/api/orders/style-options?styleIds={f['styleId']}")
    skus = {x["id"]: x for x in options[0]["skus"]}
    a, b = (skus[x] for x in sku_ids)
    if a["sizeCode"] != b["sizeCode"] or a["colorId"] == b["colorId"]:
        raise AssertionError("Fixture must use the same size in different colors")
    today = business_day()
    f.update({"materialId": f.get("materialId", 1), "supplierId": f.get("supplierId", 1),
              "factoryId": f.get("factoryId", 3), "buyFactoryId": f.get("buyFactoryId", 4),
              "factoryWarehouseId": f.get("factoryWarehouseId", 3), "productWarehouseId": f.get("productWarehouseId", 2),
              "businessDate": today.isoformat(), "orderTemplate": {
        "companyId": f["companyId"], "orderType": "STOCK", "customerId": None, "customerOrderNo": None,
        "contractNo": None, "brand": f.get("brand"), "orderDate": today.isoformat(),
        "requiredDate": (today + timedelta(days=60)).isoformat(), "salesId": f["userId"], "merchId": f["userId"],
        "currency": "CNY", "deliveryReq": None, "remark": "匿名回归", "files": [],
        "items": [{"styleSkuId": sku_ids[0], "qty": 3, "price": 12.3456,
                   "lineDueDate": (today + timedelta(days=30)).isoformat(), "remark": "合成色码甲"},
                  {"styleSkuId": sku_ids[1], "qty": 5, "price": 23.4567, "lineDueDate": None, "remark": None}],
    }})
    domain = DomainFixture(api, f)
    f["packId"] = domain.create_pack("DEV", with_cost=True)
    f["productionPackId"] = domain.create_pack("PROD")
    f["lifecycle"] = domain.build_lifecycle()
    f["identities"] = create_identities(api, f["companyId"])
    # This order is visible to the SELF owner so that the production-role test
    # can legitimately schedule its own draft through the real API.
    matrix_fixture = {**f, "orderTemplate": {**f["orderTemplate"],
                      "salesId": f["identities"]["owner"]["userId"], "merchId": f["identities"]["owner"]["userId"]}}
    matrix_domain = DomainFixture(api, matrix_fixture)
    f["matrixOrderId"] = domain.approve("/api/orders", matrix_domain.new_order((40, 60), "AUDIT-MATRIX")["id"])["id"]
    f["alternatePackId"] = domain.create_pack("PROD", with_cost=True, publish=False, buy_price=45.6789, fee=8.75)
    f["history"] = domain.history_pack()
    approved_count = domain.new_count(f["factoryWarehouseId"])
    api.request("PUT", f"/api/inventory-counts/{approved_count['id']}", count_update(approved_count, 0))
    f["approvedCountId"] = domain.approve("/api/inventory-counts", approved_count["id"])["id"]
    f["countId"] = domain.new_count(f["warehouseId"])["id"]
    f["notes"] = "dev/seed-demo.sql synthetic masters; all business documents created through real API; fresh isolated DB per run"
    write_evidence(Path(destination), f, api.token)
    evidence = {"schemaVersion": 1, "synthetic": True, "kind": "fixture-preparation", "database": f["database"],
                "flow": ["PROD+BOM", "order-approved", "MRP", "purchase-approved", "material-receipt-posted",
                         "OUTSOURCE-approved", "BUY-approved", "count-approved", "count-draft"],
                "ids": {"packId": f["packId"], "productionPackId": f["productionPackId"],
                        "orderId": f["lifecycle"]["orderId"], "mrpId": f["lifecycle"]["mrpId"],
                        "purchaseId": f["lifecycle"]["purchaseId"], "productionIds": f["lifecycle"]["productionIds"],
                        "countId": f["countId"], "approvedCountId": f["approvedCountId"],
                        "alternatePackId": f["alternatePackId"], "matrixOrderId": f["matrixOrderId"],
                        "historyPackId": f["history"]["packId"]},
                "roles": {key: {"userId": actor["userId"], "dataScope": actor["dataScope"], "permissions": actor["permissions"]}
                          for key, actor in f["identities"].items()}, "apiTrace": api.calls}
    write_evidence(Path(destination).with_name("fixture-evidence.json"), evidence, api.token)
    print("Prepared anonymous real-API lifecycle and fixture evidence; credentials remain in memory")


if __name__ == "__main__":
    main()
