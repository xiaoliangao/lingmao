package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real writes and reads: no audit event is fabricated, and field permissions are deliberately independent. */
class A4AuditBusinessHttpIT extends A2BusinessFixture {
    @Test void eachPriceDomainIsIndependentAndPublicTagPriceRemainsVisible() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company());
        Account cost = auditor(f, FieldPermissions.COST), sales = auditor(f, FieldPermissions.ORDER), production = auditor(f, FieldPermissions.PRODUCTION);
        long materialPrice = id(ok(admin, "POST", "/api/prices", map("type", "MATERIAL", "supplierId", f.supplier(), "materialId", f.material(), "price", "13.37"), null));
        long processPrice = id(ok(admin, "POST", "/api/prices", map("type", "PROCESS", "supplierId", f.supplier(), "styleId", f.style(), "processMode", "CMT", "price", "38.31"), null));
        long order = id(ok(admin, "POST", "/api/orders", orderBody(f, admin, admin, "89.23"), f.company()).path("id"));
        long orderLine = jdbc.queryForObject("SELECT id FROM ord_order_item WHERE order_id=?", Long.class, order);
        long approved = sourceOrder(f, admin, "APPROVED", f.company());
        long line = jdbc.queryForObject("SELECT id FROM ord_order_item WHERE order_id=?", Long.class, approved);
        var block = map("styleId", f.style(), "factoryId", f.supplier(), "processMode", "CMT", "unitPrice", "38.31",
                "dueDate", today().plusDays(30), "merchId", admin.id(), "items", List.of(Map.of("orderItemId", line, "qty", 2)));
        long scheduled = id(ok(admin, "POST", "/api/production-orders/schedule", map("orderId", approved, "blocks", List.of(block)), f.company()).get(0).path("id"));
        var style = map("name", "A4 public retail price", "styleClass", "FS", "brand", "A2", "categoryId", f.category(),
                "year", 2026, "season", "AUTUMN", "sizeGroupId", f.sizeGroup(), "tagPrice", "329", "refCost", "456.78");
        ok(admin, "PUT", "/api/styles/" + f.style(), style, null);
        for (Account reader : List.of(cost, sales, production)) {
            assertPrice(reader, "prc_price", materialPrice, "price", "13.37", reader == cost);
            assertPrice(reader, "prc_price", processPrice, "price", "38.31", reader == production);
            assertPrice(reader, "ord_order_item", orderLine, "price", "89.23", reader == sales);
            assertPrice(reader, "prd_order", scheduled, "unit_price", "38.31", reader == production);
            assertThat(history(reader, "sty_style", f.style()).get(0).path("diff").path("tag_price").path("new").decimalValue()).isEqualByComparingTo("329");
        }
    }

    @Test void duplicateBomAndProcessRowsReorderedByIdentityKeepExactOldAndNewAuditValues() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company()); Account cost = auditor(f, FieldPermissions.COST);
        jdbc.update("UPDATE tp_bom_item SET ref_price=10 WHERE id=?", f.bom());
        jdbc.update("UPDATE tp_bom_item_sku SET ref_price=100 WHERE bom_item_id=?", f.bom());
        long second = jdbc.queryForObject("""
                INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,sku_mode,usage_qty,unit,supplier_id,ref_price)
                VALUES(?,2,'body',?,'BY_COLOR',2.5,'METER',?,20) RETURNING id
                """, Long.class, f.pack(), f.material(), f.supplier());
        jdbc.update("INSERT INTO tp_bom_item_sku(bom_item_id,color_id,material_sku_id,ref_price) VALUES(?,?,?,200)", second, f.color(), f.materialSku());
        jdbc.update("INSERT INTO tp_process(tech_pack_id,seq,category,name,std_seconds,price) VALUES(?,1,'SEWING','same seam',10,5),(?,2,'SEWING','same seam',20,6)", f.pack(), f.pack());
        long pack = id(ok(admin, "POST", "/api/tech-packs/" + f.pack() + "/revise", Map.of(), null));
        JsonNode before = ok(admin, "GET", "/api/tech-packs/" + pack, null, null);
        var bom = new ArrayList<Map<String,Object>>();
        var processes = new ArrayList<Map<String,Object>>();
        for (int i = 1; i >= 0; i--) {
            JsonNode row = before.path("bomItems").get(i), sku = row.path("skus").get(0), process = before.path("processes").get(i);
            bom.add(map("id", id(row.path("id")), "part", "body", "materialId", f.material(), "supplierId", f.supplier(),
                    "skuMode", "BY_COLOR", "usageMode", "UNIFORM", "usageQty", row.path("usageQty").decimalValue().add(BigDecimal.ONE),
                    "source", "PURCHASE", "refPrice", row.path("refPrice").decimalValue().add(new BigDecimal("2")),
                    "skus", List.of(map("materialSkuId", f.materialSku(), "colorId", f.color(), "refPrice", sku.path("refPrice").decimalValue().add(new BigDecimal("2"))))));
            processes.add(map("id", id(process.path("id")), "category", "SEWING", "name", "same seam", "stdSeconds", process.path("stdSeconds").intValue(),
                    "price", process.path("price").decimalValue().add(BigDecimal.ONE), "images", List.of()));
        }
        String path = "/api/tech-packs/" + pack;
        var body = map("versionLock", 0, "versionNote", "A4 reorder and reprice", "specPoints", List.of(), "bomItems", bom,
                "craftSections", List.of(), "processes", processes);
        ok(admin, "PUT", path, body, null);
        JsonNode current = ok(admin, "GET", path, null, null);
        long currentLine = id(current.path("bomItems").get(0).path("id"));
        JsonNode replacement = find(history(cost, "tp_bom_item", currentLine), "tp_bom_item", "REPLACE");
        assertThat(replacement.path("previousRowId").asText()).isEqualTo(before.path("bomItems").get(1).path("id").asText());
        assertDiff(replacement, "ref_price", "20", "22"); assertDiff(replacement, "usage_qty", "2.5", "3.5");
        JsonNode root = ok(cost, "GET", "/api/data-logs?rootTable=tp_tech_pack&rootId=" + pack + "&transactionId=" + replacement.path("transactionId").asText(), null, null);
        assertThat(root.valueStream().filter(row -> row.path("action").asText().equals("REPLACE")).count()).isEqualTo(6);
        assertThat(root.valueStream().filter(row -> row.path("action").asText().equals("DELETE")).count()).isEqualTo(6);
        assertThat(root.valueStream().filter(row -> row.path("action").asText().equals("INSERT")).count()).isEqualTo(6);
        JsonNode skuReplacement = root.valueStream().filter(row -> row.path("table").asText().equals("tp_bom_item_sku")
                && row.path("action").asText().equals("REPLACE") && row.path("diff").path("ref_price").path("new").decimalValue().compareTo(new BigDecimal("202")) == 0).findFirst().orElseThrow();
        assertDiff(skuReplacement, "ref_price", "200", "202");
        int count = jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE root_table='tp_tech_pack' AND root_id=?", Integer.class, pack);
        assertThat(call(admin, "PUT", path, body, null).path("code").intValue()).isEqualTo(1002);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE root_table='tp_tech_pack' AND root_id=?", Integer.class, pack)).isEqualTo(count);
        evidence("bom-replacement-history.json", root);
    }

    @Test void purchaseDeleteAndReinsertRetainsOriginalLinePriceInCommittedReplacementDiff() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company()); Account cost = auditor(f, FieldPermissions.COST);
        long purchase = sourcePurchase(f, admin);
        jdbc.update("UPDATE pur_order SET status='DRAFT',total_amount=100 WHERE id=?", purchase);
        jdbc.update("UPDATE pur_order_item SET price=10,amount=100 WHERE purchase_order_id=?", purchase);
        long oldLine = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, purchase);
        var body = map("version", 0, "companyId", f.company(), "supplierId", f.supplier(), "purchaseType", "STOCK", "buyerId", admin.id(),
                "orderDate", today(), "deliveryDate", today().plusDays(30), "warehouseId", f.warehouse(), "settleMethod", "MONTHLY", "taxPct", 13,
                "taxIncluded", true, "freightIncluded", true, "items", List.of(map("id", oldLine, "materialSkuId", f.materialSku(), "qty", 12, "price", "12", "priceChangeReason", "audit test")));
        ok(admin, "PUT", "/api/purchase-orders/" + purchase, body, f.company());
        long current = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, purchase);
        assertThat(current).isNotEqualTo(oldLine);
        JsonNode replacement = find(history(cost, "pur_order_item", current), "pur_order_item", "REPLACE");
        assertThat(replacement.path("previousRowId").asText()).isEqualTo(Long.toString(oldLine));
        assertDiff(replacement, "price", "10", "12"); assertDiff(replacement, "amount", "100", "144");
        assertThat(replacement.path("rootTable").asText()).isEqualTo("pur_order");
        assertThat(replacement.path("rootId").asText()).isEqualTo(Long.toString(purchase));
        evidence("purchase-replacement-diff.json", replacement);
    }

    @Test void pendingSupplierBankChangeIsVisibleOnlyWithBankPermissionAndItsReadIsAudited() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company());
        Account bank = auditor(f, FieldPermissions.BANK), ordinary = auditor(f, FieldPermissions.ORDER);
        String name = jdbc.queryForObject("SELECT name FROM sup_supplier WHERE id=?", String.class, f.supplier());
        ok(admin, "PUT", "/api/suppliers/" + f.supplier(), map("name", name, "shortName", "A4 bank change", "categories", List.of("GARMENT", "FABRIC"),
                "settleMethod", "MONTHLY", "taxPct", 13, "bankAccount", "6228480000000076543"), null);
        long change = jdbc.queryForObject("SELECT id FROM sup_supplier_change WHERE supplier_id=? ORDER BY id DESC LIMIT 1", Long.class, f.supplier());
        JsonNode permitted = history(bank, "sup_supplier_change", change);
        assertThat(permitted.toString()).contains("6228480000000076543", "6228480000000012345");
        assertThat(history(ordinary, "sup_supplier_change", change).toString()).doesNotContain("bankAccount", "622848");
        var read = jdbc.queryForMap("SELECT * FROM sys_oper_log WHERE user_id=? AND method='DataLogService.list' ORDER BY id DESC LIMIT 1", bank.id());
        assertThat(read.get("params").toString()).contains("sup_supplier_change", Long.toString(change)).doesNotContain("622848");
        assertThat(read.get("ip")).isEqualTo("127.0.0.1"); assertThat(read.get("trace_id")).isNotNull();
    }

    private Account auditor(Fixture f, String permission) throws Exception { return custom(f.company(), "ALL", "system:audit:query", permission); }
    private void evidence(String name, JsonNode value) throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of("target/test-artifacts/a4", name);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
    private JsonNode history(Account account, String table, long row) throws Exception { return ok(account, "GET", "/api/data-logs?table="+table+"&rowId="+row, null, null); }
    private void assertPrice(Account reader, String table, long row, String field, String value, boolean visible) throws Exception {
        JsonNode entries = history(reader, table, row);
        assertThat(entries.size()).isPositive();
        var prices = entries.valueStream().map(entry -> entry.path("diff")).filter(diff -> diff.has(field)).toList();
        if (visible) {
            assertThat(prices).as(table+"."+field).isNotEmpty();
            assertThat(prices.get(0).path(field).path("new").decimalValue()).isEqualByComparingTo(value);
        } else assertThat(prices).as(table+"."+field).isEmpty();
    }
    private static JsonNode find(JsonNode rows, String table, String action) {
        return rows.valueStream().filter(row -> row.path("table").asText().equals(table) && row.path("action").asText().equals(action)).findFirst().orElseThrow();
    }
    private static void assertDiff(JsonNode row, String field, String old, String next) {
        assertThat(row.path("diff").path(field).path("old").decimalValue()).isEqualByComparingTo(old);
        assertThat(row.path("diff").path(field).path("new").decimalValue()).isEqualByComparingTo(next);
    }
}
