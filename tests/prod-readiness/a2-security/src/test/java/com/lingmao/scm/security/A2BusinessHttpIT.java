package com.lingmao.scm.security;

import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real roles, actual HTTP DTO binding, PostgreSQL mutations and binary exports. No mocked service responses. */
class A2BusinessHttpIT extends A2BusinessFixture {
    @Test void salesCanQuoteWhileAQuantityEditorPreservesHiddenSkuPricesAndOptionalNewQuotesStayNull() throws Exception {
        Fixture f = fixture(); Account sales = role("sales", f.company());
        var body = orderBody(f, sales, sales, "89.12");
        long order = id(ok(sales, "POST", "/api/orders", body, f.company()).path("id"));
        JsonNode quoted = ok(sales, "GET", "/api/orders/" + order, null, f.company());
        assertThat(quoted.path("items").get(0).path("price").decimalValue()).isEqualByComparingTo("89.12");
        JsonNode style = ok(sales, "GET", "/api/styles/" + f.style(), null, null);
        assertThat(style.has("tagPrice")).isTrue(); assertThat(style.has("refCost")).isFalse();
        Account editor = custom(f.company(), "ALL", "order:order:add", "order:order:edit", "order:order:list");
        JsonNode hidden = ok(editor, "GET", "/api/orders/" + order, null, f.company());
        assertThat(hidden.has("totalAmount")).isFalse();
        assertThat(hidden.path("items").get(0).has("price")).isFalse();
        assertThat(hidden.path("items").get(0).has("amount")).isFalse();
        body.put("version", 0); body.put("items", List.of(map("styleSkuId", f.styleSku(), "qty", 12, "price", null)));
        ok(editor, "PUT", "/api/orders/" + order, body, f.company());
        assertThat(jdbc.queryForObject("SELECT price FROM ord_order_item WHERE order_id=?", BigDecimal.class, order)).isEqualByComparingTo("89.12");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM ord_order WHERE id=?", BigDecimal.class, order)).isEqualByComparingTo("1069.44");
        body.put("version", 1); body.put("items", List.of(map("styleSkuId", f.styleSku(), "qty", 12, "price", "0")));
        assertThat(call(editor, "PUT", "/api/orders/" + order, body, f.company()).path("code").intValue()).isEqualTo(403);
        long unquoted = id(ok(editor, "POST", "/api/orders", orderBody(f, editor, editor, null), f.company()).path("id"));
        assertThat(jdbc.queryForObject("SELECT price FROM ord_order_item WHERE order_id=?", BigDecimal.class, unquoted)).isNull();
    }

    @Test void companyDraftSwitchNeedsPersistedExpectationAndUnknownServerFieldsNeverBind() throws Exception {
        Fixture f = fixture(); Account sales = role("sales", f.company());
        var body = orderBody(f, sales, sales, null);
        assertThat(call(sales, "POST", "/api/orders", body, f.otherCompany()).path("code").intValue()).isEqualTo(409);
        body.put("status", "APPROVED");
        assertThat(raw(sales, "POST", "/api/orders", body, f.company(), UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
        body.remove("status"); long order = id(ok(sales, "POST", "/api/orders", body, f.company()).path("id"));
        body.put("version", 0); body.put("companyId", f.otherCompany());
        assertThat(call(sales, "PUT", "/api/orders/" + order, body, f.company()).path("code").intValue()).isEqualTo(403);
        body.put("expectedCompanyId", f.company()); ok(sales, "PUT", "/api/orders/" + order, body, f.company());
        assertThat(jdbc.queryForObject("SELECT company_id FROM ord_order WHERE id=?", Long.class, order)).isEqualTo(f.otherCompany());
        body.put("version", 1);
        assertThat(call(sales, "PUT", "/api/orders/" + order, body, f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version FROM ord_order WHERE id=?", Integer.class, order)).isEqualTo(1);
    }

    @Test void firstMrpAndScheduleCallsApplySourceDataScopeAndMixedCompanyBatchesWriteNothing() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "purchase:mrp:add", "purchase:mrp:list", "production:order:add", "production:price:query");
        Account foreign = role("sales", f.company());
        long mine = sourceOrder(f, owner, "APPROVED", f.company());
        long theirs = sourceOrder(f, foreign, "APPROVED", f.company());
        assertThat(call(owner, "POST", "/api/mrp-calcs", Map.of("orderIds", List.of(mine, theirs)), f.company()).path("code").intValue()).isEqualTo(404);
        assertThat(call(owner, "GET", "/api/production-orders/schedule-prefill?orderId=" + theirs, null, f.company()).path("code").intValue()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mrp_calc WHERE create_by=?", Long.class, owner.id())).isZero();
        Account all = custom(f.company(), "ALL", "purchase:mrp:add", "purchase:mrp:list");
        long other = sourceOrder(f, all, "APPROVED", f.otherCompany());
        assertThat(call(all, "POST", "/api/mrp-calcs", Map.of("orderIds", List.of(mine, other)), f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mrp_calc WHERE create_by=?", Long.class, all.id())).isZero();
    }

    @Test void merchandiserKeepsProcessPriceBusinessWithoutMaterialCostAccess() throws Exception {
        Fixture f = fixture(); Account merch = role("merchandiser", f.company()); Account admin = role("admin", f.company());
        long order = sourceOrder(f, merch, "APPROVED", f.company());
        long line = jdbc.queryForObject("SELECT id FROM ord_order_item WHERE order_id=?", Long.class, order);
        long processPrice = id(ok(merch, "POST", "/api/prices", map("type", "PROCESS", "supplierId", f.supplier(), "styleId", f.style(),
                "processMode", "CMT", "price", "38.31"), null));
        long materialPrice = id(ok(admin, "POST", "/api/prices", map("type", "MATERIAL", "supplierId", f.supplier(), "materialId", f.material(),
                "price", "13.37"), null));
        JsonNode quote = ok(merch, "GET", "/api/production-orders/schedule-quote?orderId=" + order + "&styleId=" + f.style() + "&factoryId=" + f.supplier(), null, f.company());
        assertThat(quote.path("prices").isArray()).isTrue();
        assertThat(quote.path("prices").toString()).contains("38.31");
        JsonNode prices = ok(merch, "GET", "/api/prices?supplierId=" + f.supplier(), null, null);
        assertThat(prices.path("list").size()).isEqualTo(1);
        assertThat(id(prices.path("list").get(0).path("id"))).isEqualTo(processPrice);
        assertThat(call(merch, "GET", "/api/prices?type=MATERIAL&supplierId=" + f.supplier(), null, null).path("code").intValue()).isEqualTo(403);
        assertThat(call(merch, "POST", "/api/prices/" + materialPrice + "/void", Map.of("reason", "wrong domain"), null).path("code").intValue()).isEqualTo(403);
        var block = map("styleId", f.style(), "factoryId", f.supplier(), "processMode", "CMT", "unitPrice", "38.31",
                "dueDate", today().plusDays(30), "merchId", merch.id(), "items", List.of(Map.of("orderItemId", line, "qty", 2)));
        JsonNode scheduled = ok(merch, "POST", "/api/production-orders/schedule", Map.of("orderId", order, "blocks", List.of(block)), f.company());
        long production = id(scheduled.get(0).path("id"));
        JsonNode detail = ok(merch, "GET", "/api/production-orders/" + production, null, f.company());
        assertThat(detail.path("unitPrice").decimalValue()).isEqualByComparingTo("38.31");
        assertThat(detail.path("archivePrice").decimalValue()).isEqualByComparingTo("38.31");
        Account logistics = custom(f.company(), "ALL", "production:order:list", "production:order:edit");
        var edit = map("version", 0, "companyId", f.company(), "factoryId", f.supplier(), "processMode", "CMT", "unitPrice", null,
                "dueDate", today().plusDays(31), "merchId", merch.id(), "settleMethod", "MONTHLY", "taxPct", 13, "taxIncluded", true);
        ok(logistics, "PUT", "/api/production-orders/" + production, edit, f.company());
        assertThat(jdbc.queryForObject("SELECT unit_price FROM prd_order WHERE id=?", BigDecimal.class, production)).isEqualByComparingTo("38.31");
        assertThat(ok(logistics, "GET", "/api/production-orders/" + production, null, f.company()).has("unitPrice")).isFalse();
        edit.put("version", 1); edit.put("unitPrice", "1");
        assertThat(call(logistics, "PUT", "/api/production-orders/" + production, edit, f.company()).path("code").intValue()).isEqualTo(403);
    }

    @Test void warehouseSourceProjectionAllowsReceivingAndIssuingWithoutOrdinarySourceListRights() throws Exception {
        Fixture f = fixture(); Account manager = role("manager", f.company());
        Account warehouse = custom(f.company(), "ALL", "inventory:doc:add", "inventory:doc:list", "inventory:doc:audit");
        long purchase = sourcePurchase(f, manager), order = sourceOrder(f, manager, "APPROVED", f.company());
        long production = sourceProduction(f, manager, order, "APPROVED");
        assertThat(call(warehouse, "GET", "/api/purchase-orders/" + purchase, null, f.company()).path("code").intValue()).isEqualTo(403);
        JsonNode prefill = ok(warehouse, "GET", "/api/inventory-docs/from-purchase/" + purchase + "?docType=PURCHASE_IN", null, null);
        assertThat(id(prefill.path("companyId"))).isEqualTo(f.company());
        assertThat(prefill.path("items").get(0).has("price")).isFalse();
        String action = UUID.randomUUID().toString();
        var receipt = map("clientActionId", action, "docType", "PURCHASE_IN", "post", true,
                "items", List.of(Map.of("sourceItemId", id(prefill.path("items").get(0).path("sourceItemId")), "materialSkuId", f.materialSku(), "qty", "4")));
        JsonNode received = node(raw(warehouse, "POST", "/api/inventory-docs/from-purchase/" + purchase, receipt, f.company(), action));
        assertThat(received.path("code").intValue()).as(received.path("msg").asText()).isZero();
        assertThat(jdbc.queryForObject("SELECT unit_price FROM inv_stock_log WHERE material_sku_id=? AND change_type='PURCHASE_IN'", BigDecimal.class, f.materialSku())).isEqualByComparingTo("13.37");
        assertThat(call(warehouse, "GET", "/api/production-orders/" + production, null, f.company()).path("code").intValue()).isEqualTo(403);
        JsonNode issue = ok(warehouse, "GET", "/api/inventory-docs/from-production/" + production + "?docType=ISSUE_OUT&fromWarehouseId=" + f.warehouse(), null, null);
        assertThat(issue.path("items").get(0).has("price")).isFalse();
        String issueAction = UUID.randomUUID().toString();
        var request = map("clientActionId", issueAction, "docType", "ISSUE_OUT", "fromWarehouseId", f.warehouse(), "post", true,
                "items", List.of(Map.of("materialSkuId", f.materialSku(), "qty", "1")));
        JsonNode issued = node(raw(warehouse, "POST", "/api/inventory-docs/from-production/" + production, request, f.company(), issueAction));
        assertThat(issued.path("code").intValue()).as(issued.path("msg").asText()).isZero();
        assertThat(jdbc.queryForObject("SELECT qty FROM inv_stock WHERE warehouse_id=? AND material_sku_id=?", BigDecimal.class, f.warehouse(), f.materialSku())).isEqualByComparingTo("3");
        // The current ledger does not calculate moving-average valuation. An ordinary quantity issue
        // remains explicitly UNKNOWN; it must not invent the last purchase quote as an issue cost.
        assertThat(jdbc.queryForObject("SELECT unit_price FROM inv_stock_log WHERE material_sku_id=? AND change_type='ISSUE_OUT' AND warehouse_id=?", BigDecimal.class, f.materialSku(), f.warehouse())).isNull();
        assertThat(jdbc.queryForObject("SELECT valuation_status FROM inv_stock_log WHERE material_sku_id=? AND change_type='ISSUE_OUT' AND warehouse_id=?", String.class, f.materialSku(), f.warehouse())).isEqualTo("UNKNOWN");
    }

    @Test void supplierPrivateFieldsAreAbsentAndAnUnprivilegedEditDoesNotClearThem() throws Exception {
        Fixture f = fixture(); Account reader = custom(f.company(), "ALL", "supplier:supplier:list", "supplier:supplier:edit", "supplier:supplier:export");
        JsonNode detail = ok(reader, "GET", "/api/suppliers/" + f.supplier(), null, null);
        for (String field : List.of("contact", "phone", "wechat", "address", "bankName", "bankAccountName", "bankAccount")) assertThat(detail.has(field)).as(field).isFalse();
        assertThat(detail.path("legacyAttrs").toString()).doesNotContain("private-", "传真", "地址电话");
        String name = jdbc.queryForObject("SELECT name FROM sup_supplier WHERE id=?", String.class, f.supplier());
        var edit = map("name", name, "shortName", "Updated public name", "categories", List.of("GARMENT", "FABRIC"),
                "productLines", List.of("APPAREL", "FOOTWEAR", "GENERAL"), "settleMethod", "MONTHLY", "taxPct", 13);
        ok(reader, "PUT", "/api/suppliers/" + f.supplier(), edit, null);
        assertThat(jdbc.queryForObject("SELECT contact FROM sup_supplier WHERE id=?", String.class, f.supplier())).isEqualTo("private-contact");
        assertThat(jdbc.queryForObject("SELECT bank_account FROM sup_supplier WHERE id=?", String.class, f.supplier())).isEqualTo("6228480000000012345");
        edit.put("phone", "13900000000");
        assertThat(call(reader, "PUT", "/api/suppliers/" + f.supplier(), edit, null).path("code").intValue()).isEqualTo(403);
        var exported = raw(reader, "GET", "/api/suppliers/export?ids=" + f.supplier(), null, null, null);
        assertThat(headers(exported.body(), 0)).doesNotContain("联系人", "联系电话", "微信", "公司地址", "地址电话", "传真", "账户");
    }

    static List<String> headers(byte[] bytes, int sheet) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<String> headers = new ArrayList<>(); workbook.getSheetAt(sheet).getRow(0).forEach(c -> headers.add(c.getStringCellValue())); return headers;
        }
    }
}
