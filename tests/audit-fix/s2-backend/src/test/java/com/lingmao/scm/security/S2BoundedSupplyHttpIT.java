package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.ReorderQuery;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.module.purchase.service.SupplyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** P34: real HTTP pages and database-bounded nested previews, with totals independent of page size. */
class S2BoundedSupplyHttpIT extends A2BusinessFixture {
    @Autowired SupplyService supply;
    @Autowired PurchaseOrderService purchases;

    @Test void reorderPagesSortAllShortagesBeforeLimitAndBoundVisibleDraftsPerSku() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "SELF", "purchase:order:list");
        Account other = custom(f.company(), "SELF", "purchase:order:list");
        authenticate(actor);
        long before = supply.reorderCount();
        String prefix = "P34" + suffix() + "-";
        jdbc.update("""
                INSERT INTO bas_material_sku(material_id,code,spec_code,safety_stock)
                SELECT ?,? || lpad(n::text,4,'0'),'P34-' || n,100000000-floor((n-1)/2.0)
                FROM generate_series(1,235) n
                """, f.material(), prefix);
        jdbc.update("INSERT INTO bas_material_sku(material_id,code,spec_code,safety_stock,status) VALUES(?,?,?,200000000,0)",
                f.material(), prefix + "DISABLED", "P34-DISABLED");
        long firstSku = jdbc.queryForObject("SELECT id FROM bas_material_sku WHERE code=?", Long.class, prefix + "0001");
        drafts(f, actor, firstSku, 25);
        drafts(f, other, firstSku, 7);
        long repeated = jdbc.queryForObject("SELECT MIN(id) FROM pur_order WHERE create_by=?", Long.class, actor.id());
        jdbc.update("""
                INSERT INTO pur_order_item(purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount)
                VALUES(?,2,?,'METER',1,2,1,2)
                """, repeated, firstSku);

        JsonNode first = ok(actor, "GET", "/api/reorder-items", null, f.company());
        assertThat(first.path("total").longValue()).isEqualTo(before + 235);
        assertThat(first.path("list").size()).isEqualTo(50);
        JsonNode top = first.path("list").get(0);
        assertThat(id(top.path("materialSkuId"))).isEqualTo(firstSku);
        assertThat(top.path("gapQty").decimalValue()).isEqualByComparingTo("100000000");
        assertThat(top.path("openDraftsTotal").longValue()).isEqualTo(25);
        assertThat(top.path("openDrafts").size()).isEqualTo(20);
        List<Long> visibleDrafts = jdbc.queryForList("SELECT id FROM pur_order WHERE create_by=? ORDER BY id DESC LIMIT 20", Long.class, actor.id());
        assertThat(longIds(top.path("openDrafts"), "id")).containsExactlyElementsOf(visibleDrafts);

        List<String> codes = new ArrayList<>(); Set<Long> seen = new HashSet<>();
        for (int page = 1; page <= 5; page++) {
            JsonNode result = page == 1 ? first : ok(actor, "GET", "/api/reorder-items?page=" + page + "&size=50", null, f.company());
            assertThat(result.path("total").longValue()).isEqualTo(before + 235);
            assertThat(result.path("list").size()).isLessThanOrEqualTo(50);
            for (JsonNode row : result.path("list")) {
                assertThat(seen.add(id(row.path("materialSkuId")))).as("no repeated SKU between pages").isTrue();
                if (row.path("skuCode").stringValue().startsWith(prefix)) codes.add(row.path("skuCode").stringValue());
            }
        }
        assertThat(codes).containsExactlyElementsOf(IntStream.rangeClosed(1, 235).mapToObj(i -> prefix + String.format("%04d", i)).toList());
        assertThat(ok(actor, "GET", "/api/reorder-items?page=1000&size=100", null, f.company()).path("list").isEmpty()).isTrue();
        for (String params : List.of("size=101", "size=0", "page=0", "page=9223372036854775807&size=100")) {
            assertThat(call(actor, "GET", "/api/reorder-items?" + params, null, f.company()).path("code").intValue()).isEqualTo(400);
        }

        ReorderQuery query = new ReorderQuery(); query.setSize(1);
        var captured = SqlStatementCapture.capture(() -> supply.reorderItems(query));
        assertThat(captured.result().getList()).hasSize(1);
        // This was previously an unbounded SupplyLoader fetch of every safety SKU's purchase and stock rows.
        assertThat(captured.statements().stream().map(sql -> sql.getSql().strip().replaceAll("\\s+", " ")))
                .noneMatch(sql -> sql.startsWith("SELECT s.material_sku_id AS sku_id, SUM(s.qty) AS qty"))
                .anyMatch(sql -> sql.contains("LIMIT") && sql.contains("OFFSET"));
        evidence("p34-reorder-page.json", first);
    }

    @Test void pendingSourceCodesAreLimitedInSqlWithCompleteDistinctCounts() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "SELF", "purchase:order:list");
        long po = sourcePurchase(f, actor);
        long line = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, po);
        long ownerItem = 0;
        for (int i = 0; i < 125; i++) {
            long calc = jdbc.queryForObject("INSERT INTO mrp_calc(no,status,create_by) VALUES(?,'CONFIRMED',?) RETURNING id",
                    Long.class, "P34C" + suffix(), actor.id());
            long item = jdbc.queryForObject("""
                    INSERT INTO mrp_calc_item(calc_id,seq,material_sku_id,gross_qty,need_qty,net_qty,order_nos,style_codes)
                    VALUES(?,1,?,1,1,1,?,?) RETURNING id
                    """, Long.class, calc, f.materialSku(), String.format("S2O%03d, DUP , ,S2O%03d", i, i), String.format("S2S%03d,DUP", i));
            if (i == 0) ownerItem = item;
            jdbc.update("INSERT INTO mrp_calc_transit(calc_item_id,purchase_item_id,qty) VALUES(?,?,1)", item, line);
        }
        // The owner is also a transit user: count each source calc once.
        jdbc.update("UPDATE pur_order_item SET calc_item_id=? WHERE id=?", ownerItem, line);
        JsonNode result = ok(actor, "GET", "/api/purchase-orders/pending-items", null, f.company());
        assertThat(result.path("list").size()).isEqualTo(1);
        JsonNode row = result.path("list").get(0);
        assertThat(row.path("sourcesTotal").longValue()).isEqualTo(125);
        assertThat(row.path("orderNosTotal").longValue()).isEqualTo(126);
        assertThat(row.path("styleCodesTotal").longValue()).isEqualTo(126);
        assertThat(row.path("orderNos").size()).isEqualTo(20);
        assertThat(row.path("styleCodes").size()).isEqualTo(20);
        assertThat(row.path("orderNos").get(0).stringValue()).isEqualTo("DUP");
        assertThat(row.path("orderNos").get(19).stringValue()).isEqualTo("S2O018");
        assertThat(row.toString()).doesNotContain("S2O124", "S2S124");
        authenticate(actor);
        var captured = SqlStatementCapture.capture(() -> purchases.pendingItems(new PendingArrivalQuery()));
        assertThat(captured.result().getList()).hasSize(1);
        assertThat(captured.statements().stream().map(sql -> sql.getSql().strip().replaceAll("\\s+", " ")))
                .noneMatch(sql -> sql.startsWith("SELECT t.purchase_item_id AS line_id, c.id AS calc_id"))
                .anyMatch(sql -> sql.contains("position") && sql.toLowerCase().contains("string_agg"));
        evidence("p34-pending-source-preview.json", result);
    }

    private void drafts(Fixture f, Account owner, long sku, int count) {
        jdbc.update("""
                WITH heads AS (
                    INSERT INTO pur_order(no,company_id,supplier_id,purchase_type,buyer_id,order_date,delivery_date,warehouse_id,settle_method,tax_pct,status,total_qty,total_amount,create_by)
                    SELECT ? || n,?,?,'STOCK',?,CURRENT_DATE,CURRENT_DATE+30,?,'MONTHLY',13,'DRAFT',1,1,?
                    FROM generate_series(1,?) n RETURNING id
                ) INSERT INTO pur_order_item(purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount)
                  SELECT id,1,?,'METER',1,1,1,1 FROM heads
                """, "P34D" + suffix(), f.company(), f.supplier(), owner.id(), f.warehouse(), owner.id(), count, sku);
    }
    private static List<Long> longIds(JsonNode array, String key) {
        List<Long> ids = new ArrayList<>(); for (JsonNode row : array) ids.add(id(row.path(key))); return ids;
    }
    private void evidence(String file, JsonNode data) throws Exception {
        Path dir = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), json.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }
}
