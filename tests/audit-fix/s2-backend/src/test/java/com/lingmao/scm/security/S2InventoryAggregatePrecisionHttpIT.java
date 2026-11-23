package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP creation with legal line values; observes the untouched PostgreSQL sum and raw JSON boundary. */
class S2InventoryAggregatePrecisionHttpIT extends A2BusinessFixture {
    private static final Path EVIDENCE = Path.of("test-artifacts/full-audit/p45/http");

    @Test void twoThousandLegalLineAmountsStayExactThroughListAndDetail() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "inventory:doc:add", "inventory:doc:list", FieldPermissions.COST);
        List<Long> skus = materialSkus(f, 2000);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            lines.add(map("materialSkuId", skus.get(i).toString(), "qty", 100,
                    "price", new BigDecimal(i == skus.size() - 1 ? "9999999999.9998" : "9999999999.9999")));
        }
        long doc = create(f, actor, lines);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inv_doc_item WHERE doc_id=?", Integer.class, doc)).isEqualTo(2000);
        String sqlAmount = jdbc.queryForObject("SELECT sum(amount)::text FROM inv_doc_item WHERE doc_id=?", String.class, doc);
        assertThat(sqlAmount).isEqualTo("1999999999999979.99");
        JsonNode detail = read(actor, f.company(), "/api/inventory-docs/" + doc, "amount-detail.json");
        JsonNode list = read(actor, f.company(), "/api/inventory-docs?kind=IN&ids=" + doc, "amount-list.json");
        evidence("amount-sql.json", map("id", Long.toString(doc), "rows", 2000, "sum", sqlAmount,
                "detailAmountIsString", detail.path("totalAmount").isString(),
                "listAmountIsString", list.path("list").get(0).path("totalAmount").isString()));
        assertThat(detail.path("totalAmount").isString()).as("aggregate JSON must preserve every decimal digit").isTrue();
        assertThat(detail.path("totalAmount").stringValue()).isEqualTo(sqlAmount);
        assertThat(list.path("list").get(0).path("totalAmount").stringValue()).isEqualTo(sqlAmount);
        assertThat(detail.path("totalQty").isString()).isTrue();
        assertThat(new BigDecimal(detail.path("totalQty").stringValue())).isEqualByComparingTo("200000");
        assertThat(detail.path("items").get(0).path("price").isNumber()).isTrue();
        assertThat(detail.path("items").get(0).path("amount").isNumber()).isTrue();
        assertThat(detail.path("version").isIntegralNumber()).isTrue();
        assertThat(list.path("total").isIntegralNumber()).isTrue();

        Account reader = custom(f.company(), "ALL", "inventory:doc:list");
        JsonNode protectedDetail = read(reader, f.company(), "/api/inventory-docs/" + doc, "amount-no-cost.json");
        JsonNode protectedList = read(reader, f.company(), "/api/inventory-docs?kind=IN&ids=" + doc, "amount-list-no-cost.json");
        assertThat(protectedDetail.has("totalAmount")).isFalse();
        assertThat(protectedList.path("list").get(0).has("totalAmount")).isFalse();
        for (JsonNode item : protectedDetail.path("items")) {
            assertThat(item.has("price")).isFalse();
            assertThat(item.has("amount")).isFalse();
        }
    }

    @Test void legalLargeQuantitiesKeepTheirLastThousandth() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "inventory:doc:add", "inventory:doc:list", FieldPermissions.COST);
        List<Long> skus = materialSkus(f, 2000);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            lines.add(map("materialSkuId", skus.get(i).toString(),
                    "qty", new BigDecimal(i == skus.size() - 1 ? "99999999999.998" : "99999999999.999"), "price", BigDecimal.ZERO));
        }
        long doc = create(f, actor, lines);
        String sqlQty = jdbc.queryForObject("SELECT sum(qty)::text FROM inv_doc_item WHERE doc_id=?", String.class, doc);
        assertThat(sqlQty).isEqualTo("199999999999997.999");
        JsonNode detail = read(actor, f.company(), "/api/inventory-docs/" + doc, "quantity-detail.json");
        JsonNode list = read(actor, f.company(), "/api/inventory-docs?kind=IN&ids=" + doc, "quantity-list.json");
        evidence("quantity-sql.json", map("id", Long.toString(doc), "rows", 2000, "sum", sqlQty,
                "detailQuantityIsString", detail.path("totalQty").isString(),
                "listQuantityIsString", list.path("list").get(0).path("totalQty").isString()));
        assertThat(detail.path("totalQty").isString()).isTrue();
        assertThat(detail.path("totalQty").stringValue()).isEqualTo(sqlQty);
        assertThat(list.path("list").get(0).path("totalQty").stringValue()).isEqualTo(sqlQty);
        assertThat(detail.path("totalAmount").stringValue()).isEqualTo("0.00");
        assertThat(list.path("list").get(0).path("totalAmount").stringValue()).isEqualTo("0.00");
    }

    @Test void unknownValuationIsNotAZeroOrPartialTotal() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "inventory:doc:add", "inventory:doc:list", FieldPermissions.COST);
        List<Long> skus = materialSkus(f, 2);
        long unknown = create(f, actor, List.of(map("materialSkuId", skus.getFirst(), "qty", 1)));
        long mixed = create(f, actor, List.of(map("materialSkuId", skus.getFirst(), "qty", 1),
                map("materialSkuId", skus.getLast(), "qty", 1, "price", new BigDecimal("12.34"))));
        JsonNode first = read(actor, f.company(), "/api/inventory-docs/" + unknown, "unknown-detail.json");
        JsonNode second = read(actor, f.company(), "/api/inventory-docs/" + mixed, "mixed-detail.json");
        JsonNode list = read(actor, f.company(), "/api/inventory-docs?kind=IN&ids=" + unknown + "," + mixed, "unknown-mixed-list.json");
        evidence("unknown-sql.json", map("unknownId", Long.toString(unknown), "mixedId", Long.toString(mixed),
                "unknownNullRows", jdbc.queryForObject("SELECT count(*) FROM inv_doc_item WHERE doc_id=? AND amount IS NULL", Integer.class, unknown),
                "mixedNullRows", jdbc.queryForObject("SELECT count(*) FROM inv_doc_item WHERE doc_id=? AND amount IS NULL", Integer.class, mixed),
                "mixedKnownSum", jdbc.queryForObject("SELECT sum(amount)::text FROM inv_doc_item WHERE doc_id=?", String.class, mixed)));
        assertThat(first.path("totalAmount").isNull()).as("unknown valuation is not zero").isTrue();
        assertThat(second.path("totalAmount").isNull()).as("partial valuation is not the complete total").isTrue();
        assertThat(list.path("list").size()).isEqualTo(2);
        for (JsonNode row : list.path("list")) assertThat(row.path("totalAmount").isNull()).isTrue();
    }

    private long create(Fixture f, Account actor, List<Map<String, Object>> lines) throws Exception {
        JsonNode created = ok(actor, "POST", "/api/inventory-docs", map("companyId", Long.toString(f.company()),
                "docType", "OTHER_IN", "docDate", today(), "toWarehouseId", Long.toString(f.warehouse()),
                "reason", "OTHER", "items", lines), f.company());
        assertThat(created.path("id").isString()).isTrue();
        return id(created.path("id"));
    }

    private List<Long> materialSkus(Fixture f, int count) {
        String prefix = "P45" + suffix();
        assertThat(jdbc.update("""
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit,default_supplier_id,ref_price)
                SELECT ? || n,name,category_id,material_type,stock_unit,purchase_unit,default_supplier_id,ref_price
                FROM bas_material CROSS JOIN generate_series(1,?) n WHERE id=?
                """, prefix, count, f.material())).isEqualTo(count);
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO bas_material_sku(material_id,code,color_id,price)
                SELECT id,code || '-A',?,13.37 FROM bas_material WHERE code LIKE ? RETURNING id
                """, Long.class, f.color(), prefix + "%");
        assertThat(ids).hasSize(count).doesNotHaveDuplicates();
        return ids;
    }

    private JsonNode read(Account actor, long company, String endpoint, String filename) throws Exception {
        var response = raw(actor, "GET", endpoint, null, company, null);
        Files.createDirectories(EVIDENCE);
        Files.write(EVIDENCE.resolve(filename), response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = node(response);
        assertThat(body.path("code").intValue()).as("%s: %s", endpoint, body.path("msg")).isZero();
        return body.path("data");
    }

    private void evidence(String filename, Object value) throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve(filename), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
