package com.lingmao.scm.security;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP binding and PostgreSQL persistence for hidden BOM/process financial fields. */
class A2BomHttpIT extends A2BusinessFixture {
    @Test void repeatedBusinessKeysRetainTheirOwnPricesAfterReorderingByServerLineId() throws Exception {
        Fixture f = fixture(); seedDuplicates(f); Account editor = editor(f);
        long pack = revise(f, editor);
        JsonNode before = detail(editor, pack);
        assertHidden(before);
        var bom = bomForms(before); var processes = processForms(before);
        long firstOldId = id(before.path("bomItems").get(0).path("id"));
        int version = ok(editor, "PUT", path(pack), body(before, List.of(bom.get(1), bom.get(0)),
                List.of(processes.get(1), processes.get(0))), null).intValue();
        assertThat(version).isEqualTo(1);
        assertPrices("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", pack, "22.22", "11.11");
        assertPrices("SELECT s.ref_price FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id WHERE b.tech_pack_id=? ORDER BY b.seq", pack,
                "44.44", "33.33");
        assertPrices("SELECT price FROM tp_process WHERE tech_pack_id=? ORDER BY seq", pack, "6.66", "5.55");
        assertThat(jdbc.queryForList("SELECT price_tax_included FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", Boolean.class, pack))
                .containsExactly(true, false);
        assertThat(jdbc.queryForList("SELECT price_freight_included FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", Boolean.class, pack))
                .containsExactly(false, true);
        JsonNode saved = detail(editor, pack);
        assertHidden(saved);
        assertThat(id(saved.path("bomItems").get(0).path("id"))).isNotEqualTo(firstOldId);

        var changedBom = bomForms(saved); changedBom.get(0).put("refPrice", "0");
        assertThat(call(editor, "PUT", path(pack), body(saved, changedBom, processForms(saved)), null).path("code").intValue()).isEqualTo(403);
        var changedSku = bomForms(saved);
        @SuppressWarnings("unchecked") var skus = (List<Map<String, Object>>) changedSku.get(0).get("skus");
        skus.get(0).put("refPrice", "0");
        assertThat(call(editor, "PUT", path(pack), body(saved, changedSku, processForms(saved)), null).path("code").intValue()).isEqualTo(403);
        var changedProcesses = processForms(saved); changedProcesses.get(0).put("price", "0");
        assertThat(call(editor, "PUT", path(pack), body(saved, bomForms(saved), changedProcesses), null).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version_lock FROM tp_tech_pack WHERE id=?", Integer.class, pack)).isEqualTo(1);
        assertPrices("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", pack, "22.22", "11.11");
        assertPrices("SELECT price FROM tp_process WHERE tech_pack_id=? ORDER BY seq", pack, "6.66", "5.55");
    }

    @Test void ambiguousMissingDuplicateAndForeignLineIdentitiesRejectWithoutReplacingContent() throws Exception {
        Fixture f = fixture(); seedDuplicates(f); Account editor = editor(f); long pack = revise(f, editor);
        JsonNode before = detail(editor, pack);
        var missingBom = bomForms(before); missingBom.get(0).remove("id");
        assertThat(call(editor, "PUT", path(pack), body(before, missingBom, processForms(before)), null).path("code").intValue()).isEqualTo(400);
        var missingProcess = processForms(before); missingProcess.get(0).remove("id");
        assertThat(call(editor, "PUT", path(pack), body(before, bomForms(before), missingProcess), null).path("code").intValue()).isEqualTo(400);
        var duplicate = bomForms(before); duplicate.get(1).put("id", duplicate.get(0).get("id"));
        assertThat(call(editor, "PUT", path(pack), body(before, duplicate, processForms(before)), null).path("code").intValue()).isEqualTo(400);
        var foreign = bomForms(before); foreign.get(0).put("id", f.bom()); // The source version is a different pack.
        assertThat(call(editor, "PUT", path(pack), body(before, foreign, processForms(before)), null).path("code").intValue()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT version_lock FROM tp_tech_pack WHERE id=?", Integer.class, pack)).isZero();
        assertPrices("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=? ORDER BY seq", pack, "11.11", "22.22");
        assertPrices("SELECT price FROM tp_process WHERE tech_pack_id=? ORDER BY seq", pack, "5.55", "6.66");
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
        ok(editor, "PUT", path(pack), body(before, bom, processForms(before)), null);
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
        assertThat(call(editor, "PUT", path(pack), body(before, changed, processForms(before)), null).path("code").intValue()).isEqualTo(403);
        var cleared = bomForms(before); cleared.get(0).put("supplierId", null);
        assertThat(call(editor, "PUT", path(pack), body(before, cleared, processForms(before)), null).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version_lock FROM tp_tech_pack WHERE id=?", Integer.class, pack)).isZero();
        assertThat(jdbc.queryForObject("SELECT supplier_id FROM tp_bom_item WHERE tech_pack_id=?", Long.class, pack)).isEqualTo(f.supplier());
        assertThat(jdbc.queryForObject("SELECT ref_price FROM tp_bom_item WHERE tech_pack_id=?", BigDecimal.class, pack)).isNull();
        assertPrices("SELECT s.ref_price FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id WHERE b.tech_pack_id=?", pack, "42.42");
    }

    @Test void unpricedPdfRemainsUsableAndContainsNoProtectedFinancialValues() throws Exception {
        Fixture f = fixture();
        jdbc.update("UPDATE tp_bom_item_sku SET ref_price=999.99 WHERE bom_item_id=?", f.bom());
        jdbc.update("INSERT INTO tp_process(tech_pack_id,seq,category,name,price) VALUES(?,1,'SEWING','private process',6.66)", f.pack());
        Account reader = custom(f.company(), "ALL", "techpack:pack:list");
        var plain = raw(reader, "GET", path(f.pack()) + "/pdf?price=false&appendix=false", null, null, null);
        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(plain.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        try (var pdf = Loader.loadPDF(plain.body())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            assertThat(text).contains("工艺单", f.styleCode());
            assertThat(text).doesNotContain("777.77", "999.99", "6.66", "单件成本", "核价");
        }
        var priced = raw(reader, "GET", path(f.pack()) + "/pdf?price=true&appendix=false", null, null, null);
        assertThat(node(priced).path("code").intValue()).isEqualTo(403);
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
        jdbc.update("INSERT INTO tp_process(tech_pack_id,seq,category,name,std_seconds,price) VALUES(?,1,'SEWING','same seam',10,5.55),(?,2,'SEWING','same seam',20,6.66)", f.pack(), f.pack());
    }

    private static Map<String, Object> body(JsonNode detail, List<Map<String, Object>> bom, List<Map<String, Object>> processes) {
        return map("versionLock", detail.path("versionLock").intValue(), "versionNote", "A2 quantity and ordering edit",
                "specPoints", List.of(), "bomItems", bom, "craftSections", List.of(), "processes", processes);
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
    private static List<Map<String, Object>> processForms(JsonNode detail) {
        List<Map<String, Object>> forms = new ArrayList<>();
        for (JsonNode row : detail.path("processes")) forms.add(map("id", id(row.path("id")), "category", text(row.path("category")),
                "name", row.path("name").stringValue(), "stdSeconds", row.path("stdSeconds").intValue(), "images", List.of()));
        return forms;
    }
    private static Long nullableId(JsonNode value) { return value.isString() || value.isNumber() ? id(value) : null; }
    private static String text(JsonNode value) { return value.isString() ? value.stringValue() : null; }
    private static void assertHidden(JsonNode detail) {
        assertThat(detail.has("unitCost")).isFalse(); assertThat(detail.has("laborTotal")).isFalse();
        for (JsonNode row : detail.path("bomItems")) {
            assertThat(row.has("refPrice")).isFalse(); assertThat(row.has("unitCost")).isFalse();
            for (JsonNode sku : row.path("skus")) assertThat(sku.has("refPrice")).isFalse();
        }
        for (JsonNode row : detail.path("processes")) assertThat(row.has("price")).isFalse();
    }
    private void assertPrices(String sql, long pack, String... expected) {
        var prices = jdbc.queryForList(sql, BigDecimal.class, pack);
        assertThat(prices).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) assertThat(prices.get(i)).isEqualByComparingTo(expected[i]);
    }
}
