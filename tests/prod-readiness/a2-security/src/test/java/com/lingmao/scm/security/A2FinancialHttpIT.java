package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP DTOs, current role permissions and committed financial values. */
class A2FinancialHttpIT extends A2BusinessFixture {
    @Test void quantityEditsKeepPurchaseQuoteAndItsOriginalUnitDespiteChangedMasterData() throws Exception {
        Fixture f = fixture(); Account manager = role("manager", f.company());
        Account editor = custom(f.company(), "ALL", "purchase:order:list", "purchase:order:edit", "purchase:order:add");
        long purchase = sourcePurchase(f, manager); jdbc.update("UPDATE pur_order SET status='DRAFT' WHERE id=?", purchase);
        long line = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, purchase);
        jdbc.update("UPDATE bas_material SET purchase_unit='ROLL',conversion_factor=100 WHERE id=?", f.material());
        JsonNode detail = ok(editor, "GET", "/api/purchase-orders/" + purchase, null, f.company());
        assertThat(detail.has("totalAmount")).isFalse(); assertThat(detail.path("items").get(0).has("price")).isFalse();
        var body = purchase(f, manager, List.of(map("id", line, "materialSkuId", f.materialSku(), "qty", 12, "price", null)));
        body.put("version", 0); ok(editor, "PUT", "/api/purchase-orders/" + purchase, body, f.company());
        assertThat(jdbc.queryForObject("SELECT price FROM pur_order_item WHERE purchase_order_id=?", BigDecimal.class, purchase)).isEqualByComparingTo("13.37");
        assertThat(jdbc.queryForObject("SELECT purchase_unit FROM pur_order_item WHERE purchase_order_id=?", String.class, purchase)).isEqualTo("METER");
        assertThat(jdbc.queryForObject("SELECT conversion_factor FROM pur_order_item WHERE purchase_order_id=?", BigDecimal.class, purchase)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM pur_order WHERE id=?", BigDecimal.class, purchase)).isEqualByComparingTo("160.44");
        long current = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, purchase);
        body.put("version", 1); body.put("items", List.of(map("id", current, "materialSkuId", f.materialSku(), "qty", 12, "price", "0")));
        assertThat(call(editor, "PUT", "/api/purchase-orders/" + purchase, body, f.company()).path("code").intValue()).isEqualTo(403);
        body.put("items", List.of(map("id", current, "materialSkuId", f.materialSku(), "qty", 12)));
        body.put("expectedCompanyId", f.company()); body.put("companyId", f.otherCompany());
        ok(editor, "PUT", "/api/purchase-orders/" + purchase, body, f.company());
        assertThat(jdbc.queryForObject("SELECT company_id FROM pur_order WHERE id=?", Long.class, purchase)).isEqualTo(f.otherCompany());
        var fresh = purchase(f, manager, List.of(map("materialSkuId", f.materialSku(), "qty", 1)));
        assertThat(call(editor, "POST", "/api/purchase-orders", fresh, f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pur_order WHERE create_by=?", Long.class, editor.id())).isZero();
    }

    @Test void quantityOnlyOrderChangeEditsPreservePendingPriceAndFeeNoteWithoutReturningEither() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company());
        Account editor = custom(f.company(), "ALL", "order:change:list", "order:change:edit");
        long order = sourceOrder(f, admin, "APPROVED", f.company());
        var body = map("orderId", order, "reason", "requested change", "responsibleParty", "SELF", "feeNote", "fee 987.65",
                "prices", List.of(map("styleId", f.style(), "price", "95.43")));
        long change = id(ok(admin, "POST", "/api/order-changes", body, f.company()).path("id"));
        JsonNode hidden = ok(editor, "GET", "/api/order-changes/" + change, null, f.company());
        assertThat(hidden.has("summary")).isFalse(); assertThat(hidden.has("feeNote")).isFalse();
        assertThat(hidden.path("items").get(0).has("text")).isFalse();
        assertThat(hidden.path("items").get(0).has("fromPrice")).isFalse(); assertThat(hidden.path("items").get(0).has("toPrice")).isFalse();
        var edit = map("version", 0, "reason", "increase quantity", "responsibleParty", "SELF",
                "quantities", List.of(map("styleSkuId", f.styleSku(), "qty", 14)));
        ok(editor, "PUT", "/api/order-changes/" + change, edit, f.company());
        assertThat(jdbc.queryForObject("SELECT to_price FROM ord_change_item WHERE change_id=? AND change_type='PRICE'", BigDecimal.class, change)).isEqualByComparingTo("95.43");
        assertThat(jdbc.queryForObject("SELECT fee_note FROM ord_change WHERE id=?", String.class, change)).isEqualTo("fee 987.65");
        assertThat(jdbc.queryForObject("SELECT to_qty FROM ord_change_item WHERE change_id=? AND change_type='QTY'", Integer.class, change)).isEqualTo(14);
        edit.put("version", 1); edit.put("feeNote", "replace protected fee");
        assertThat(call(editor, "PUT", "/api/order-changes/" + change, edit, f.company()).path("code").intValue()).isEqualTo(403);
        edit.remove("feeNote"); edit.put("prices", List.of(map("styleId", f.style(), "price", 0)));
        assertThat(call(editor, "PUT", "/api/order-changes/" + change, edit, f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version FROM ord_change WHERE id=?", Integer.class, change)).isEqualTo(1);
    }

    @Test void restoringAZeroQuantitySkuDoesNotCopyAnotherSkusDifferentQuote() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company());
        Account editor = custom(f.company(), "ALL", "order:change:add", "order:change:list", "order:change:submit", "order:change:audit");
        long order = sourceOrder(f, admin, "APPROVED", f.company());
        jdbc.update("UPDATE ord_order_item SET qty=0,price=10,amount=0 WHERE order_id=?", order);
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,2,'L')", f.sizeGroup());
        long otherSku = jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES(?,?,?,'L') RETURNING id", Long.class, f.style(), f.styleCode() + "-L", f.color());
        jdbc.update("INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty,price,amount,tech_pack_id) VALUES(?,2,?,?,5,20,100,?)", order, f.style(), otherSku, f.pack());
        jdbc.update("UPDATE ord_order SET total_qty=5,total_amount=100 WHERE id=?", order);
        var body = map("orderId", order, "reason", "restore quantity", "responsibleParty", "SELF",
                "quantities", List.of(map("styleSkuId", f.styleSku(), "qty", 1)));
        long change = id(ok(editor, "POST", "/api/order-changes", body, f.company()).path("id"));
        assertThat(jdbc.queryForObject("SELECT to_price FROM ord_change_item WHERE change_id=?", BigDecimal.class, change)).isEqualByComparingTo("10");
        ok(editor, "POST", "/api/order-changes/" + change + "/submit", Map.of("version", 0), f.company());
        ok(editor, "POST", "/api/order-changes/" + change + "/audit", Map.of("version", 1), f.company());
        assertThat(jdbc.queryForObject("SELECT price FROM ord_order_item WHERE order_id=? AND style_sku_id=?", BigDecimal.class, order, f.styleSku())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM ord_order WHERE id=?", BigDecimal.class, order)).isEqualByComparingTo("110");
    }

    @Test void ordinaryMaterialAndStyleEditsRetainHiddenCostWhilePublicTagPriceRemainsUsable() throws Exception {
        Fixture f = fixture(); Account editor = custom(f.company(), "ALL", "basic:material:list", "basic:material:edit", "style:style:list", "style:style:edit");
        var material = map("name", "ordinary material edit", "categoryId", f.materialCategory(), "materialType", "FABRIC", "stockUnit", "METER", "refPrice", null);
        ok(editor, "PUT", "/api/materials/" + f.material(), material, null);
        assertThat(jdbc.queryForObject("SELECT ref_price FROM bas_material WHERE id=?", BigDecimal.class, f.material())).isEqualByComparingTo("12.34");
        material.put("refPrice", 0);
        assertThat(call(editor, "PUT", "/api/materials/" + f.material(), material, null).path("code").intValue()).isEqualTo(403);
        JsonNode hidden = ok(editor, "GET", "/api/materials/" + f.material(), null, null);
        assertThat(hidden.has("refPrice")).isFalse(); assertThat(hidden.path("legacyAttrs").has("其他价格")).isFalse();
        var style = map("name", "ordinary style edit", "styleClass", "FS", "brand", "A2", "categoryId", f.category(),
                "year", 2026, "season", "AUTUMN", "sizeGroupId", f.sizeGroup(), "tagPrice", "329", "refCost", null);
        ok(editor, "PUT", "/api/styles/" + f.style(), style, null);
        assertThat(jdbc.queryForObject("SELECT ref_cost FROM sty_style WHERE id=?", BigDecimal.class, f.style())).isEqualByComparingTo("456.78");
        assertThat(jdbc.queryForObject("SELECT tag_price FROM sty_style WHERE id=?", BigDecimal.class, f.style())).isEqualByComparingTo("329");
        style.put("refCost", 0);
        assertThat(call(editor, "PUT", "/api/styles/" + f.style(), style, null).path("code").intValue()).isEqualTo(403);
    }

    @Test void binaryExportsDropEntireProtectedColumnsAccordingToTheirOwnPriceDomain() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company());
        Account reader = custom(f.company(), "ALL", "basic:material:export", "style:style:export", "order:order:export", "purchase:order:export", "production:order:export", "inventory:doc:export");
        long order = sourceOrder(f, admin, "APPROVED", f.company()), purchase = sourcePurchase(f, admin);
        long production = sourceProduction(f, admin, order, "DRAFT");
        String action = UUID.randomUUID().toString();
        var received = node(raw(admin, "POST", "/api/inventory-docs/from-purchase/" + purchase,
                map("clientActionId", action, "docType", "PURCHASE_IN", "post", false), f.company(), action));
        assertThat(received.path("code").intValue()).as(received.path("msg").asText()).isZero();
        long inventory = id(received.path("data").path("id"));
        assertColumns(reader, admin, "/api/materials/export?ids=" + f.material(), 0, List.of("成本价", "其他价格", "倍率"));
        assertColumns(reader, admin, "/api/materials/export?ids=" + f.material(), 1, List.of("SKU成本价"));
        assertColumns(reader, admin, "/api/styles/export?ids=" + f.style(), 0, List.of("参考成本价", "财务成本价"));
        assertColumns(reader, admin, "/api/orders/export?ids=" + order, 0, List.of("金额"));
        assertColumns(reader, admin, "/api/purchase-orders/export?ids=" + purchase, 0, List.of("单价", "金额", "改价原因"));
        assertColumns(reader, admin, "/api/production-orders/export?kind=OUTSOURCE&ids=" + production, 0, List.of("加工费", "金额"));
        assertColumns(reader, admin, "/api/inventory-docs/export?kind=IN&ids=" + inventory, 0, List.of("单价", "金额"));
        Account sales = custom(f.company(), "ALL", "order:order:export", FieldPermissions.ORDER);
        assertThat(exportHeaders(sales, "/api/orders/export?ids=" + order, 0)).contains("金额");
        Account merch = custom(f.company(), "ALL", "production:order:export", FieldPermissions.PRODUCTION);
        assertThat(exportHeaders(merch, "/api/production-orders/export?kind=OUTSOURCE&ids=" + production, 0)).contains("加工费", "金额");
    }

    private void assertColumns(Account reader, Account admin, String path, int sheet, List<String> financial) throws Exception {
        assertThat(exportHeaders(reader, path, sheet)).doesNotContainAnyElementsOf(financial);
        assertThat(exportHeaders(admin, path, sheet)).containsAll(financial);
    }
    private List<String> exportHeaders(Account account, String path, int sheet) throws Exception {
        var response = raw(account, "GET", path, null, null, null);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("spreadsheet");
        return A2BusinessHttpIT.headers(response.body(), sheet);
    }
    private Map<String, Object> purchase(Fixture f, Account buyer, List<?> items) {
        return map("companyId", f.company(), "supplierId", f.supplier(), "purchaseType", "STOCK", "productLine", "APPAREL", "buyerId", buyer.id(),
                "orderDate", today(), "deliveryDate", today().plusDays(30), "warehouseId", f.warehouse(), "settleMethod", "MONTHLY", "taxPct", 13,
                "taxIncluded", true, "freightIncluded", true, "items", items);
    }
}
