"""Build document lifecycles exclusively through real, loopback API calls.

Master data is bootstrapped by dev/seed-demo.sql. This module never connects to SQL,
changes credentials, deletes posted documents, or operates against a remote origin.
"""
from __future__ import annotations

import copy
from datetime import timedelta
from uuid import uuid4

from harness import ApiClient, business_day, count_update, query_path


class DomainFixture:
    def __init__(self, api: ApiClient, fixture: dict):
        self.api = api
        self.f = fixture
        self.today = business_day()

    def new_order(self, quantities: tuple[int, int] = (3, 5), marker: str = "AUDIT") -> dict:
        body = copy.deepcopy(self.f["orderTemplate"])
        body["contractNo"] = marker + "-" + uuid4().hex[:12]
        for row, qty in zip(body["items"], quantities):
            row["qty"] = qty
        result = self.api.request("POST", "/api/orders", body)
        return self.api.request("GET", f"/api/orders/{result['id']}")

    def submit(self, base: str, identity: int) -> dict:
        detail = self.api.request("GET", f"{base}/{identity}")
        self.api.request("POST", f"{base}/{identity}/submit", {"version": detail["version"]})
        return self.api.request("GET", f"{base}/{identity}")

    def approve(self, base: str, identity: int) -> dict:
        detail = self.api.request("GET", f"{base}/{identity}")
        if detail["status"] == "DRAFT":
            detail = self.submit(base, identity)
        for _ in range(8):
            if detail["status"] != "PENDING":
                break
            body = {"version": detail["version"]}
            if base == "/api/orders":
                body["commitDate"] = detail["requiredDate"]
            self.api.request("POST", f"{base}/{identity}/audit", body)
            detail = self.api.request("GET", f"{base}/{identity}")
        if detail["status"] != "APPROVED":
            raise AssertionError(f"Synthetic {base} did not reach APPROVED")
        return detail

    def create_pack(self, stage: str, *, with_cost: bool = False, publish: bool = True,
                    buy_price: float = 12.3456, fee: float = 7.25) -> int:
        identity = self.api.request("POST", "/api/tech-packs", {"styleId": self.f["styleId"], "stage": stage})
        detail = self.api.request("GET", f"/api/tech-packs/{identity}")
        cost = ({"costMode": "BUY", "buyPrice": buy_price,
                 "items": [{"name": "合成费用", "amount": fee, "remark": "仅测试"}]}
                if with_cost else None)
        self.api.request("PUT", f"/api/tech-packs/{identity}", {
            "versionLock": detail["versionLock"], "versionNote": "匿名回归资料包",
            "utilPct": None,
            "bomItems": [{"part": "合成主料", "materialId": self.f.get("materialId", 1),
                          "skuMode": "FIXED", "usageMode": "UNIFORM", "usageQty": 1,
                          "sizeUsages": {}, "lossPct": 0, "source": "PURCHASE",
                          "supplierId": self.f.get("supplierId", 1), "refPrice": 18.5,
                          "priceSourceId": None, "priceTaxIncluded": True,
                          "priceFreightIncluded": True, "remark": "合成BOM",
                          "skus": [{"colorId": None, "sizeCode": None,
                                    "materialSkuId": self.f["materialSkuId"], "refPrice": None}]}],
            "files": [], "cost": cost,
        })
        if stage == "PROD" and publish:
            current = self.api.request("GET", f"/api/tech-packs/{identity}")
            self.api.request("POST", f"/api/tech-packs/{identity}/publish", {"versionLock": current["versionLock"]})
        return identity

    def new_production(self, *, merch_id: int | None = None) -> dict:
        """A tiny draft from the dedicated approved matrix order; no direct SQL."""
        order = self.api.request("GET", f"/api/orders/{self.f['matrixOrderId']}")
        result = self.api.request("POST", "/api/production-orders/schedule", {
            "orderId": order["id"], "blocks": [{
                "styleId": self.f["styleId"], "factoryId": self.f.get("factoryId", 3), "processMode": "CMT",
                "unitPrice": 12.3456, "priceSourceId": None, "priceChangeReason": "匿名权限回归",
                "dueDate": (self.today + timedelta(days=30)).isoformat(),
                "merchId": self.f["userId"] if merch_id is None else merch_id,
                "settleMethod": "MONTHLY", "terms": None, "remark": "匿名权限单据",
                "items": [{"orderItemId": row["id"], "qty": 2} for row in order["items"]],
            }]})
        return self.api.request("GET", f"/api/production-orders/{result[0]['id']}")

    def history_pack(self) -> dict:
        color_forms = [{"code": "HJ", "name": "合成历史甲色", "family": None, "hex": "#b5a494"},
                       {"code": "HY", "name": "合成历史乙色", "family": None, "hex": "#213447"}]
        colors = [self.api.request("POST", "/api/colors", form) for form in color_forms]
        style = {"name": "合成历史款初名", "aliasName": None, "styleClass": "FS", "brand": self.f["brand"],
                 "categoryId": 3, "year": self.today.year, "season": "AUTUMN", "wave": "W1", "gender": "WOMEN",
                 "designerId": self.f["userId"], "source": "OWN", "sizeGroupId": 1, "mainMaterialId": 1,
                 "productName": "合成历史品名", "composition": "100% 棉", "tagPrice": 499, "defaultFactoryId": 3,
                 "remark": "只供历史快照回归"}
        made = self.api.request("POST", "/api/styles", {**style, "codeMode": "MANUAL", "code": "AUDITHISTORY0001",
            "colorIds": colors, "images": [], "files": []})
        self.api.request("POST", f"/api/styles/{made['id']}/enable")
        private = DomainFixture(self.api, {**self.f, "styleId": made["id"]})
        pack_id = private.create_pack("PROD")
        return {"styleId": made["id"], "styleForm": style, "colorIds": colors,
                "colorForms": color_forms, "packId": pack_id}

    def new_purchase(self, *, qty: int = 10) -> dict:
        result = self.api.request("POST", "/api/purchase-orders", {
            "companyId": self.f["companyId"], "supplierId": self.f.get("supplierId", 1),
            "purchaseType": "STOCK", "buyerId": self.f["userId"],
            "orderDate": self.today.isoformat(), "deliveryDate": (self.today + timedelta(days=7)).isoformat(),
            "warehouseId": self.f["warehouseId"], "settleMethod": "MONTHLY", "taxPct": 13,
            "taxIncluded": True, "freightIncluded": True, "remark": "匿名导出对照采购",
            "planStyleIds": [], "items": [{"calcItemId": None, "materialSkuId": self.f["materialSkuId"],
               "qty": qty, "price": 18.5, "priceChangeReason": "合成回归", "lineDueDate": None, "expectDate": None}],
        })
        return self.api.request("GET", f"/api/purchase-orders/{result['id']}")

    def new_count(self, warehouse_id: int) -> dict:
        result = self.api.request("POST", "/api/inventory-counts", {
            "companyId": self.f["companyId"], "warehouseId": warehouse_id, "purpose": "DAILY",
            "scope": "PICKED", "categoryIds": [], "skuIds": [self.f["materialSkuId"]],
            "countDate": self.today.isoformat(), "counterId": self.f["userId"], "remark": "匿名盘点回归",
        })
        return self.api.request("GET", f"/api/inventory-counts/{result['id']}")

    def stock_movement(self, delta: int) -> dict:
        if delta == 0:
            raise ValueError("A real nonzero stock movement is required")
        inbound = delta > 0
        reasons = self.api.request("GET", "/api/dict-data/by-type/" + ("stock_in_reason" if inbound else "stock_out_reason"))
        if not reasons:
            raise AssertionError("Stock reason dictionary must be bootstrapped by migrations")
        reason = reasons[0]["value"]
        created = self.api.request("POST", "/api/inventory-docs", {
            "docType": "OTHER_IN" if inbound else "OTHER_OUT", "companyId": self.f["companyId"],
            "docDate": self.today.isoformat(), "fromWarehouseId": None if inbound else self.f["warehouseId"],
            "toWarehouseId": self.f["warehouseId"] if inbound else None, "partnerId": None,
            "reason": reason, "supplierDeliveryNo": None, "logisticsNo": None, "remark": "匿名水位回归",
            "items": [{"id": None, "sourceItemId": None, "materialSkuId": self.f["materialSkuId"],
                       "qty": abs(delta), "purchaseQty": None, "price": None, "dyeLot": None,
                       "rolls": None, "overReason": None, "remark": None}],
        })
        self.api.request("POST", f"/api/inventory-docs/{created['id']}/audit", {"version": created["version"]})
        return self.api.request("GET", f"/api/inventory-docs/{created['id']}")

    def stock_qty(self) -> float:
        rows = self.api.request("GET", query_path("/api/stocks/qty", {
            "warehouseId": self.f["warehouseId"], "skuIds": [self.f["materialSkuId"]]}))
        return rows[0]["qty"]

    def build_lifecycle(self) -> dict:
        """Order → MRP → purchase → posted material receipt → two production kinds."""
        order = self.approve("/api/orders", self.new_order((40, 60), "AUDIT-FLOW")["id"])
        calc = self.api.request("POST", "/api/mrp-calcs", {"orderIds": [order["id"]]})
        calc_detail = self.api.request("GET", f"/api/mrp-calcs/{calc['id']}")
        picks = [row["id"] for row in calc_detail["items"] if row["canConvert"]]
        if not picks:
            raise AssertionError("Fresh anonymous graph needs a purchasable MRP line")
        purchases = self.api.request("POST", "/api/purchase-orders/from-mrp", {
            "calcId": calc["id"], "itemIds": picks, "version": calc_detail["version"]})
        if len(purchases) != 1:
            raise AssertionError("One synthetic material supplier should produce one purchase")
        purchase = self.approve("/api/purchase-orders", purchases[0]["id"])
        source_request = {"clientActionId": str(uuid4()), "docType": "PURCHASE_IN",
                          "docDate": self.today.isoformat(), "fromWarehouseId": None,
                          "toWarehouseId": self.f["warehouseId"], "supplierDeliveryNo": None,
                          "logisticsNo": None, "reason": None, "remark": "匿名链路到货",
                          "items": None, "itemIds": [x["id"] for x in purchase["items"]], "post": True}
        received = self.api.request("POST", f"/api/inventory-docs/from-purchase/{purchase['id']}", source_request)
        blocks = []
        for factory, mode, price in ((self.f.get("factoryId", 3), "CMT", 12.3456),
                                     (self.f.get("buyFactoryId", 4), "FULL", 23.4567)):
            blocks.append({"styleId": self.f["styleId"], "factoryId": factory, "processMode": mode,
                           "unitPrice": price, "priceSourceId": None, "priceChangeReason": "匿名精度回归",
                           "dueDate": (self.today + timedelta(days=30)).isoformat(), "merchId": self.f["userId"],
                           "settleMethod": "MONTHLY", "terms": None, "remark": "匿名双类型生产",
                           "items": [{"orderItemId": x["id"], "qty": x["qty"] // 2} for x in order["items"]]})
        scheduled = self.api.request("POST", "/api/production-orders/schedule", {"orderId": order["id"], "blocks": blocks})
        production = [self.approve("/api/production-orders", x["id"]) for x in scheduled]
        by_kind = {x["kind"]: x["id"] for x in production}
        if set(by_kind) != {"OUTSOURCE", "BUY"}:
            raise AssertionError("Lifecycle must cover the actual OUTSOURCE and BUY kinds")
        return {"orderId": order["id"], "mrpId": calc["id"], "purchaseId": purchase["id"],
                "sourceReceiptId": received["id"], "sourceReceiptNo": received["no"],
                "sourceRequest": source_request, "productionIds": by_kind}
