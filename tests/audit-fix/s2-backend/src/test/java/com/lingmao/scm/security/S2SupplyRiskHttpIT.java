package com.lingmao.scm.security;

import com.lingmao.scm.module.purchase.service.SupplyService;
import com.lingmao.scm.module.purchase.dto.ReorderQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class S2SupplyRiskHttpIT extends A2BusinessFixture {
    @Autowired SupplyService supply;

    @Test void reorderAggregateMatchesRealRowsAcrossPartialArrivalReturnHeldAndClosedStates() throws Exception {
        Fixture f = fixture(); Account owner = custom(f.company(), "SELF", "purchase:order:list"); authenticate(owner);
        jdbc.update("UPDATE bas_material_sku SET safety_stock=14 WHERE id=?", f.materialSku());
        jdbc.update("INSERT INTO inv_stock(warehouse_id,material_sku_id,qty) VALUES(?,?,5)", f.warehouse(), f.materialSku());
        long purchase = sourcePurchase(f, owner);
        long line = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, purchase);
        long baseline = supply.reorderCount();
        assertGap("stock plus open transit", f.materialSku(), baseline, null);
        jdbc.update("UPDATE pur_order_item SET received_qty=7,returned_qty=2 WHERE id=?", line);
        jdbc.update("UPDATE inv_stock SET qty=10 WHERE warehouse_id=? AND material_sku_id=?", f.warehouse(), f.materialSku());
        assertGap("partial arrival and return", f.materialSku(), baseline, null);
        long calcA = calc(owner), itemA = calcItem(calcA, f, null, 4, 0);
        long calcB = calc(owner), itemB = calcItem(calcB, f, null, 1, 0);
        jdbc.update("INSERT INTO mrp_calc_transit(calc_item_id,purchase_item_id,qty) VALUES(?,?,4),(?,?,4)", itemA, line, itemB, line);
        assertGap("shared transit allocates arrivals in use ID order", f.materialSku(), baseline + 1, "12");
        jdbc.update("UPDATE mrp_calc_item SET issued_qty=30 WHERE id=?", itemA);
        assertGap("issued exceeds stock and arrived hold without negative held", f.materialSku(), baseline + 1, "5");
        jdbc.update("UPDATE pur_order_item SET calc_item_id=? WHERE id=?", itemB, line);
        assertGap("confirmed source calc owns remaining transit", f.materialSku(), baseline + 1, "10");
        jdbc.update("UPDATE pur_order SET status='CLOSED' WHERE id=?", purchase);
        assertGap("closed unarrived transit excluded while arrived held remains", f.materialSku(), baseline + 1, "10");
    }

    @Test void cutRiskUsesBoundedSqlPagesAndLateFirstOrderingBeforeLimit() throws Exception {
        Fixture f = fixture();
        Account merch = custom(f.company(), "SELF", "production:order:list");
        Account buyer = custom(f.company(), "SELF", "purchase:order:list");
        List<Long> productionIds = new ArrayList<>(), purchaseIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long order = sourceOrder(f, merch, "APPROVED", f.company());
            long production = sourceProduction(f, merch, order, "APPROVED"); productionIds.add(production);
            long purchase = sourcePurchase(f, buyer); purchaseIds.add(purchase);
            long calc = calc(buyer), item = calcItem(calc, f, jdbc.queryForObject("SELECT no FROM ord_order WHERE id=?", String.class, order), 0, 0);
            jdbc.update("INSERT INTO mrp_calc_order(calc_id,order_id) VALUES(?,?)", calc, order);
            // All the same actual CUT start. Arrival risk rises as IDs rise, reversing normal date ordering.
            jdbc.update("INSERT INTO prd_node(prd_order_id,node_code,node_name,seq,plan_start,plan_finish) VALUES(?,'CUT','裁剪',1,?,?)", production, today(), today());
            jdbc.update("UPDATE pur_order_item SET calc_item_id=?,expect_date=? WHERE purchase_order_id=?", item, today().plusDays(i + 1), purchase);
        }
        JsonNode pending = ok(buyer, "GET", "/api/purchase-orders/pending-items?tab=CUT&size=20", null, f.company());
        assertThat(pending.path("total").longValue()).isEqualTo(25);
        assertThat(pending.path("list").size()).isEqualTo(20);
        assertThat(id(pending.path("list").get(0).path("purchaseOrderId"))).isEqualTo(purchaseIds.getLast());
        assertThat(pending.path("list").get(0).path("cutLateDays").longValue()).isEqualTo(25);
        assertThat(pending.path("list").get(0).path("needDate").stringValue()).isEqualTo(today().toString());
        JsonNode counts = ok(buyer, "GET", "/api/purchase-orders/pending-items/counts", null, f.company());
        assertThat(counts.path("all").longValue()).isEqualTo(25);
        assertThat(counts.path("cut").longValue()).isEqualTo(25);
        JsonNode todos = ok(merch, "GET", "/api/board/home/todos", null, f.company());
        JsonNode cut = null; for (JsonNode group : todos.path("groups")) if ("CUT_RISK".equals(group.path("key").stringValue())) cut = group;
        assertThat(cut).isNotNull();
        assertThat(cut.path("total").longValue()).isEqualTo(25);
        assertThat(cut.path("items").size()).isEqualTo(20);
        assertThat(id(cut.path("items").get(0).path("ref").path("id"))).isEqualTo(productionIds.getLast());
        assertThat(cut.toString()).doesNotContain("13.37", "price", "refCost");
        assertThat(call(buyer, "GET", "/api/purchase-orders/pending-items?tab=INVALID", null, f.company()).path("code").intValue()).isEqualTo(400);
    }

    @Test void blankArrivalTabIsCanonicalAndInternalCutFlagCannotBeBoundFromHttp() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "SELF", "production:order:list", "production:board:list", "purchase:order:list");
        long source = sourceOrder(f, actor, "APPROVED", f.company());
        sourceProduction(f, actor, source, "APPROVED"); sourcePurchase(f, actor);
        JsonNode page = ok(actor, "GET", "/api/purchase-orders/pending-items?tab=%20%20", null, f.company());
        JsonNode counts = ok(actor, "GET", "/api/purchase-orders/pending-items/counts?tab=%20%20", null, f.company());
        assertThat(page.path("total").longValue()).isEqualTo(1);
        assertThat(counts.path("all").longValue()).isEqualTo(1);
        JsonNode ordinary = ok(actor, "GET", "/api/production-orders?kind=OUTSOURCE", null, f.company());
        JsonNode injected = ok(actor, "GET", "/api/production-orders?kind=OUTSOURCE&cutRiskFirst=true&oldestFirst=true&delayedFirst=true&factoryIds=-1", null, f.company());
        assertThat(injected).isEqualTo(ordinary);
        assertThat(injected.path("total").longValue()).isEqualTo(1);
        JsonNode ordinaryCounts = ok(actor, "GET", "/api/production-orders/counts?kind=OUTSOURCE", null, f.company());
        JsonNode injectedCounts = ok(actor, "GET", "/api/production-orders/counts?kind=OUTSOURCE&cutRiskFirst=true", null, f.company());
        assertThat(injectedCounts).isEqualTo(ordinaryCounts);
        JsonNode indexed = ok(actor, "GET", "/api/production-orders?kind=OUTSOURCE&factoryIds%5B300%5D=-1&orderNos%5B300%5D=missing&approvalIds%5B300%5D=-1&approvalScope.roleIds%5B300%5D=-1", null, f.company());
        assertThat(indexed).isEqualTo(ordinary);
        for (String endpoint : List.of("cards", "orders", "factories")) {
            JsonNode baseline = ok(actor, "GET", "/api/production-board/" + endpoint, null, f.company());
            JsonNode blocked = ok(actor, "GET", "/api/production-board/" + endpoint + "?factoryIds%5B300%5D=-1", null, f.company());
            assertThat(blocked).as(endpoint + " indexed internal field is ignored").isEqualTo(baseline);
        }
    }

    private long calc(Account owner) {
        return jdbc.queryForObject("INSERT INTO mrp_calc(no,status,create_by) VALUES(?,'CONFIRMED',?) RETURNING id", Long.class, "S2C" + suffix(), owner.id());
    }
    private long calcItem(long calc, Fixture f, String orderNo, int stock, int issued) {
        return jdbc.queryForObject("""
                INSERT INTO mrp_calc_item(calc_id,seq,material_sku_id,gross_qty,need_qty,net_qty,use_stock_qty,issued_qty,order_nos)
                VALUES(?,1,?,100,100,100,?,?,?) RETURNING id
                """, Long.class, calc, f.materialSku(), stock, issued, orderNo);
    }
    private void assertGap(String stage, long sku, long expectedTotal, String expectedGap) {
        assertThat(supply.reorderCount()).as(stage + " independent count").isEqualTo(expectedTotal);
        ReorderQuery query = new ReorderQuery(); query.setSize(100);
        for (int page = 1; page <= 1000; page++) {
            query.setPage(page);
            var result = supply.reorderItems(query);
            assertThat(result.getTotal()).as(stage + " page total").isEqualTo(expectedTotal);
            var found = result.getList().stream().filter(row -> row.getMaterialSkuId() == sku).findFirst();
            if (found.isPresent()) {
                assertThat(expectedGap).as(stage + " should be in reorder list").isNotNull();
                assertThat(found.get().getGapQty()).as(stage + " hand-calculated Decimal gap").isEqualByComparingTo(expectedGap);
                return;
            }
            if (page * query.getSize() >= result.getTotal()) {
                assertThat(expectedGap).as(stage + " should not be in reorder list").isNull();
                return;
            }
            assertThat(result.getList()).hasSize(100);
        }
        throw new AssertionError("reorder pagination did not terminate");
    }
}
