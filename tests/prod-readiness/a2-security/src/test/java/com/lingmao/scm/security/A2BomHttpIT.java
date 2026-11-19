package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP binding and PostgreSQL persistence for hidden BOM and labor-price financial fields. */
class A2BomHttpIT extends A2BusinessFixture {
    @Test void repeatedBusinessKeysRetainTheirOwnPricesAfterReorderingByServerLineId() throws Exception {
        Fixture f = fixture(); seedDuplicates(f); Account editor = editor(f);
        long pack = revise(f, editor);
        JsonNode before = detail(editor, pack);
        assertHidden(before);
        var bom = bomForms(before);
        long firstOldId = id(before.path("bomItems").get(0).path("id"));
        int version = ok(editor, "PUT", path(pack), body(before, List.of(bom.get(1), bom.get(0))), null).intValue();
        assertThat(version).isEqualTo(1);
        assertPrices("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", pack, "22.22", "11.11");
        assertPrices("SELECT s.ref_price FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id WHERE b.tech_pack_id=? ORDER BY b.seq", pack,
                "44.44", "33.33");
        assertThat(jdbc.queryForList("SELECT price_tax_included FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", Boolean.class, pack))
                .containsExactly(true, false);
        assertThat(jdbc.queryForList("SELECT price_freight_included FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", Boolean.class, pack))
                .containsExactly(false, true);
        JsonNode saved = detail(editor, pack);
        assertHidden(saved);
        assertThat(id(saved.path("bomItems").get(0).path("id"))).isNotEqualTo(firstOldId);

        var changedBom = bomForms(saved); changedBom.get(0).put("refPrice", "0");
        assertThat(call(editor, "PUT", path(pack), body(saved, changedBom), null).path("code").intValue()).isEqualTo(403);
        var changedSku = bomForms(saved);
        @SuppressWarnings("unchecked") var skus = (List<Map<String, Object>>) changedSku.get(0).get("skus");
        skus.get(0).put("refPrice", "0");
        assertThat(call(editor, "PUT", path(pack), body(saved, changedSku), null).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version_lock FROM tp_tech_pack WHERE id=?", Integer.class, pack)).isEqualTo(1);
        assertPrices("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", pack, "22.22", "11.11");
    }

    @Test void ambiguousMissingDuplicateAndForeignLineIdentitiesRejectWithoutReplacingContent() throws Exception {
        Fixture f = fixture(); seedDuplicates(f); Account editor = editor(f); long pack = revise(f, editor);
        JsonNode before = detail(editor, pack);
        var missingBom = bomForms(before); missingBom.get(0).remove("id");
        assertThat(call(editor, "PUT", path(pack), body(before, missingBom), null).path("code").intValue()).isEqualTo(400);
        var duplicate = bomForms(before); duplicate.get(1).put("id", duplicate.get(0).get("id"));
        assertThat(call(editor, "PUT", path(pack), body(before, duplicate), null).path("code").intValue()).isEqualTo(400);
        var foreign = bomForms(before); foreign.get(0).put("id", f.bom()); // The source version is a different pack.
        assertThat(call(editor, "PUT", path(pack), body(before, foreign), null).path("code").intValue()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT version_lock FROM tp_tech_pack WHERE id=?", Integer.class, pack)).isZero();
        assertPrices("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", pack, "11.11", "22.22");
    }

    @Test void dimensionsIgnoredByTheSkuModeCannotEraseTheExistingCellPrice() throws Exception {
        Fixture f = fixture(); seedDuplicates(f);
        jdbc.update("UPDATE tp_bom_item SET sku_mode='FIXED' WHERE id=?", f.bom());
        jdbc.update("UPDATE tp_bom_item_sku SET color_id=NULL,size_code=NULL WHERE bom_item_id=?", f.bom());
        Account editor = editor(f); long pack = revise(f, editor); JsonNode before = detail(editor, pack);
        var bom = bomForms(before);
        @SuppressWarnings("unchecked") var fixed = (List<Map<String, Object>>) bom.get(0).get("skus");
        fixed.get(0).put("colorId", f.color()); fixed.get(0).put("sizeCode", "M");
        @SuppressWarnings("unchecked") var byColor = (List<Map<String, Object>>) bom.get(1).get("skus");
        byColor.get(0).put("sizeCode", "M");
        ok(editor, "PUT", path(pack), body(before, bom), null);
        assertPrices("SELECT s.ref_price FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id WHERE b.tech_pack_id=? ORDER BY b.seq", pack,
                "33.33", "44.44");
        var stored = jdbc.queryForList("SELECT b.sku_mode,s.color_id,s.size_code FROM tp_bom_item b JOIN tp_bom_item_sku s ON s.bom_item_id=b.id WHERE b.tech_pack_id=? ORDER BY b.seq", pack);
        assertThat(stored.get(0).get("sku_mode")).isEqualTo("FIXED");
        assertThat(stored.get(0).get("color_id")).isNull(); assertThat(stored.get(0).get("size_code")).isNull();
        assertThat(stored.get(1).get("color_id")).isEqualTo(f.color()); assertThat(stored.get(1).get("size_code")).isNull();
    }

    @Test void changingSupplierRequiresCostRightsWhenOnlySkuOverridesContainPrices() throws Exception {
        Fixture f = fixture();
        jdbc.update("UPDATE tp_bom_item SET ref_price=NULL WHERE id=?", f.bom());
        jdbc.update("UPDATE tp_bom_item_sku SET ref_price=42.42 WHERE bom_item_id=?", f.bom());
        String name = "A2 alternative " + suffix();
        long alternative = jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name,status) VALUES(?,?,?,'ACTIVE') RETURNING id",
                Long.class, "A2ALT" + suffix(), name, name);
        Account editor = editor(f); long pack = revise(f, editor); JsonNode before = detail(editor, pack);
        var changed = bomForms(before); changed.get(0).put("supplierId", alternative);
        assertThat(call(editor, "PUT", path(pack), body(before, changed), null).path("code").intValue()).isEqualTo(403);
        var cleared = bomForms(before); cleared.get(0).put("supplierId", null);
        assertThat(call(editor, "PUT", path(pack), body(before, cleared), null).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version_lock FROM tp_tech_pack WHERE id=?", Integer.class, pack)).isZero();
        assertThat(jdbc.queryForObject("SELECT supplier_id FROM tp_bom_item WHERE tech_pack_id=?", Long.class, pack)).isEqualTo(f.supplier());
        assertThat(jdbc.queryForObject("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=?", BigDecimal.class, pack)).isNull();
        assertPrices("SELECT s.ref_price FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id WHERE b.tech_pack_id=?", pack, "42.42");
    }

    @Test void laborPriceIsCopiedOnReviseButOnlyCostRightsMayChangeIt() throws Exception {
        Fixture f = fixture();
        jdbc.update("UPDATE tp_tech_pack SET labor_price=6.66,labor_note='清加工 6.66' WHERE id=?", f.pack());
        Account editor = editor(f); long pack = revise(f, editor); JsonNode before = detail(editor, pack);
        assertHidden(before);
        assertThat(jdbc.queryForObject("SELECT labor_price FROM tp_tech_pack WHERE id=?", BigDecimal.class, pack)).isEqualByComparingTo("6.66");
        var changed = body(before, bomForms(before));
        changed.put("cost", map("costMode", "OUTSOURCE", "laborPrice", "0", "items", List.of()));
        assertThat(call(editor, "PUT", path(pack), changed, null).path("code").intValue()).isEqualTo(403);
        // 加工费备注（V40，Q50）和加工费一样：修订带过去，没有看成本权限的人改不了
        var noteChanged = body(before, bomForms(before));
        noteChanged.put("cost", map("costMode", "OUTSOURCE", "laborPrice", "6.66", "laborNote", "半包 9", "items", List.of()));
        assertThat(call(editor, "PUT", path(pack), noteChanged, null).path("code").intValue()).isEqualTo(403);
        ok(editor, "PUT", path(pack), body(before, bomForms(before)), null);
        assertThat(jdbc.queryForObject("SELECT labor_price FROM tp_tech_pack WHERE id=?", BigDecimal.class, pack)).isEqualByComparingTo("6.66");
        assertThat(jdbc.queryForObject("SELECT labor_note FROM tp_tech_pack WHERE id=?", String.class, pack)).isEqualTo("清加工 6.66");
        assertThat(call(editor, "GET", path(pack) + "/cost", null, null).path("code").intValue()).isEqualTo(403);
    }

    private Account editor(Fixture f) throws Exception {
        return custom(f.company(), "ALL", "techpack:pack:list", "techpack:pack:add", "techpack:pack:edit");
    }
    private long revise(Fixture f, Account editor) throws Exception {
        return id(ok(editor, "POST", path(f.pack()) + "/revise", Map.of(), null));
    }
    private JsonNode detail(Account editor, long pack) throws Exception { return ok(editor, "GET", path(pack), null, null); }
    private static String path(long pack) { return "/api/tech-packs/" + pack; }

    private void seedDuplicates(Fixture f) {
        jdbc.update("UPDATE tp_bom_item SET ref_price=11.11,price_tax_included=false,price_freight_included=true WHERE id=?", f.bom());
        jdbc.update("UPDATE tp_bom_item_sku SET ref_price=33.33 WHERE bom_item_id=?", f.bom());
        long second = jdbc.queryForObject("""
                INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,sku_mode,usage_qty,unit,supplier_id,ref_price,price_tax_included,price_freight_included)
                VALUES(?,2,'body',?,'BY_COLOR',2.5,'METER',?,22.22,true,false) RETURNING id
                """, Long.class, f.pack(), f.material(), f.supplier());
        jdbc.update("INSERT INTO tp_bom_item_sku(bom_item_id,color_id,material_sku_id,ref_price) VALUES(?,?,?,44.44)", second, f.color(), f.materialSku());
    }

    private static Map<String, Object> body(JsonNode detail, List<Map<String, Object>> bom) {
        return map("versionLock", detail.path("versionLock").intValue(), "versionNote", "A2 quantity and ordering edit", "bomItems", bom);
    }
    private static List<Map<String, Object>> bomForms(JsonNode detail) {
        List<Map<String, Object>> forms = new ArrayList<>();
        for (JsonNode row : detail.path("bomItems")) {
            List<Map<String, Object>> skus = new ArrayList<>();
            for (JsonNode sku : row.path("skus")) skus.add(map("materialSkuId", id(sku.path("materialSkuId")),
                    "colorId", nullableId(sku.path("colorId")), "sizeCode", text(sku.path("sizeCode"))));
            forms.add(map("id", id(row.path("id")), "part", row.path("part").stringValue(),
                    "materialId", id(row.path("materialId")), "supplierId", nullableId(row.path("supplierId")),
                    "skuMode", row.path("skuMode").stringValue(), "usageMode", "UNIFORM", "usageQty", row.path("usageQty").decimalValue(),
                    "source", row.path("source").stringValue(), "skus", skus));
        }
        return forms;
    }
    private static Long nullableId(JsonNode value) { return value.isString() || value.isNumber() ? id(value) : null; }
    private static String text(JsonNode value) { return value.isString() ? value.stringValue() : null; }
    private static void assertHidden(JsonNode detail) {
        assertThat(detail.has("unitCost")).isFalse(); assertThat(detail.has("laborPrice")).isFalse(); assertThat(detail.has("laborNote")).isFalse();
        for (JsonNode row : detail.path("bomItems")) {
            assertThat(row.has("refPrice")).isFalse(); assertThat(row.has("unitCost")).isFalse();
            for (JsonNode sku : row.path("skus")) assertThat(sku.has("refPrice")).isFalse();
        }
    }
    private void assertPrices(String sql, long pack, String... expected) {
        var prices = jdbc.queryForList(sql, BigDecimal.class, pack);
        assertThat(prices).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) assertThat(prices.get(i)).isEqualByComparingTo(expected[i]);
    }
}
