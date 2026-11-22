package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.module.purchase.service.MrpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P34 object scope and query-count regression against real HTTP, services, and PostgreSQL. */
class S2KittingHttpIT extends A2BusinessFixture {
    @Autowired MrpService mrp;

    @Test void mixedInvisibleBatchIsRejectedBeforeAnyStateOrCalcIdentifierIsReturned() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "order:order:list");
        Account other = custom(f.company(), "SELF", "order:order:list");
        Account noList = custom(f.company(), "SELF");
        Account all = custom(f.company(), "ALL", "order:order:list");
        long own = sourceOrder(f, owner, "APPROVED", f.company());
        long hidden = sourceOrder(f, other, "APPROVED", f.company());
        long anotherCompany = sourceOrder(f, owner, "APPROVED", f.otherCompany());
        JsonNode visible = ok(owner, "GET", "/api/order-kitting?orderIds=" + own, null, f.company());
        assertThat(visible.size()).isEqualTo(1);
        assertThat(visible.get(0).path("status").stringValue()).isEqualTo("NONE");
        for (String ids : List.of(own + "," + hidden, own + ",9223372036854775807", own + "," + anotherCompany)) {
            rejected(owner, f.company(), ids);
        }
        rejected(noList, f.company(), Long.toString(own));
        assertThat(ok(all, "GET", "/api/order-kitting?orderIds=" + own + "," + hidden, null, f.company()).size()).isEqualTo(2);
        rejected(all, f.company(), own + "," + anotherCompany);
    }

    @Test void productionRelationAndCurrentApprovalExceptionsRequireTheirActualTrustedScope() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "order:order:list");
        Account production = custom(f.company(), "SELF", "production:order:list");
        Account hiddenProduction = custom(f.company(), "SELF", "production:order:list");
        Account noProductionList = custom(f.company(), "SELF", "order:order:list");
        Account reviewer = custom(f.company(), "SELF", "order:order:list", "order:order:audit");
        long linked = sourceOrder(f, owner, "APPROVED", f.company());
        long notLinked = sourceOrder(f, owner, "APPROVED", f.company());
        sourceProduction(f, production, linked, "APPROVED");
        sourceProduction(f, noProductionList, notLinked, "APPROVED");
        assertThat(ok(production, "GET", "/api/order-kitting?orderIds=" + linked, null, f.company()).size()).isEqualTo(1);
        rejected(hiddenProduction, f.company(), Long.toString(linked));
        rejected(noProductionList, f.company(), Long.toString(notLinked));
        rejected(production, f.company(), linked + "," + notLinked);

        long pending = sourceOrder(f, owner, "PENDING", f.company());
        jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,perm,status) VALUES('ORDER',?,1,1,'purchase:order:audit','CURRENT')", pending);
        rejected(reviewer, f.company(), Long.toString(pending));
        jdbc.update("UPDATE biz_approval_step SET perm='order:order:audit' WHERE biz_type='ORDER' AND doc_id=?", pending);
        assertThat(ok(reviewer, "GET", "/api/order-kitting?orderIds=" + pending, null, f.company()).size()).isEqualTo(1);
        rejected(reviewer, f.company(), Long.toString(linked)); // Approval authority never grants ALL.

        long pendingProduction = sourceProduction(f, owner, notLinked, "PENDING");
        jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status) VALUES('PRODUCTION_ORDER',?,1,1,?,'CURRENT')", pendingProduction, hiddenProduction.id());
        assertThat(ok(hiddenProduction, "GET", "/api/order-kitting?orderIds=" + notLinked, null, f.company()).size()).isEqualTo(1);
        jdbc.update("UPDATE biz_approval_step SET status='PASSED' WHERE biz_type='PRODUCTION_ORDER' AND doc_id=?", pendingProduction);
        rejected(hiddenProduction, f.company(), Long.toString(notLinked));
    }

    @Test void boardOnlyRoleCanReadKittingForVisibleActiveBoardRowsWithoutGainingDocumentAccess() throws Exception {
        Fixture f = fixture();
        Account boardOnly = custom(f.company(), "SELF", "production:board:list");
        Account boardAll = custom(f.company(), "ALL", "production:board:list");
        Account other = custom(f.company(), "SELF", "order:order:list", "production:order:list");
        List<Long> activeOrders = new ArrayList<>();
        for (String state : List.of("APPROVED", "RUNNING")) {
            long order = sourceOrder(f, other, "APPROVED", f.company());
            long production = sourceProduction(f, boardOnly, order, state);
            if (state.equals("APPROVED")) jdbc.update("UPDATE prd_order SET create_by=? WHERE id=?", other.id(), production);
            else jdbc.update("UPDATE prd_order SET merch_id=? WHERE id=?", other.id(), production);
            activeOrders.add(order);
        }
        List<Long> inactiveOrders = new ArrayList<>();
        for (String state : List.of("DRAFT", "PENDING", "DONE", "CLOSED", "VOIDED")) {
            long order = sourceOrder(f, other, "APPROVED", f.company());
            long production = sourceProduction(f, boardOnly, order, state);
            if (state.equals("PENDING")) {
                // Being explicitly named as approver must not broaden a board-only role's status window.
                jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status) VALUES('PRODUCTION_ORDER',?,1,1,?,'CURRENT')",
                        production, boardOnly.id());
            }
            inactiveOrders.add(order);
        }
        long hidden = sourceOrder(f, other, "APPROVED", f.company());
        sourceProduction(f, other, hidden, "APPROVED");

        JsonNode board = ok(boardOnly, "GET", "/api/production-board/orders", null, f.company());
        assertThat(board.path("total").longValue()).isEqualTo(2);
        List<Long> boardOrderIds = new ArrayList<>();
        for (JsonNode row : board.path("list")) boardOrderIds.add(id(row.path("orderId")));
        assertThat(boardOrderIds).containsExactlyInAnyOrderElementsOf(activeOrders);
        JsonNode kitting = call(boardOnly, "GET", "/api/order-kitting?orderIds=" + activeOrders.get(0) + "," + activeOrders.get(1), null, f.company());
        Path dir = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(dir);
        String evidence = kitting.path("code").intValue() == 0 ? "p35-board-only-green.json" : "p35-board-only-red.json";
        Files.writeString(dir.resolve(evidence), json.writerWithDefaultPrettyPrinter().writeValueAsString(map("board", board, "kitting", kitting)));
        assertThat(kitting.path("code").intValue()).as("the two actual visible board rows must have readable kitting").isZero();
        assertThat(kitting.path("data").size()).isEqualTo(2);
        for (JsonNode state : kitting.path("data")) assertThat(state.path("status").stringValue()).isEqualTo("NONE");

        for (long order : inactiveOrders) {
            rejected(boardOnly, f.company(), Long.toString(order));
            rejected(boardAll, f.company(), Long.toString(order));
        }
        rejected(boardOnly, f.company(), Long.toString(hidden));
        rejected(boardOnly, f.company(), activeOrders.getFirst() + "," + hidden);
        rejected(boardOnly, f.company(), activeOrders.getFirst() + "," + inactiveOrders.getFirst());
        assertThat(ok(boardAll, "GET", "/api/order-kitting?orderIds=" + hidden, null, f.company()).size()).isEqualTo(1);

        long anotherCompany = sourceOrder(f, other, "APPROVED", f.otherCompany());
        long foreignProduction = sourceProduction(f, boardOnly, anotherCompany, "APPROVED");
        // Inconsistent legacy linkage itself cannot authorize the target company.
        rejected(boardOnly, f.otherCompany(), Long.toString(anotherCompany));
        jdbc.update("UPDATE prd_order SET company_id=? WHERE id=?", f.otherCompany(), foreignProduction);
        rejected(boardOnly, f.company(), Long.toString(anotherCompany));
        rejected(boardOnly, f.company(), activeOrders.getFirst() + "," + anotherCompany);
        assertThat(ok(boardOnly, "GET", "/api/order-kitting?orderIds=" + anotherCompany, null, f.otherCompany()).size()).isEqualTo(1);
        assertThat(call(boardOnly, "GET", "/api/production-orders?kind=OUTSOURCE", null, f.company()).path("code").intValue()).isEqualTo(403);
        assertThat(call(boardOnly, "GET", "/api/orders/" + activeOrders.getFirst(), null, f.company()).path("code").intValue()).isEqualTo(403);
    }

    @Test void confirmedCalcTransitRowsArePreloadedOnceForTheWholeAuthorizedBatch() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "SELF", "order:order:list");
        long order = sourceOrder(f, actor, "APPROVED", f.company());
        long po = sourcePurchase(f, actor);
        long line = jdbc.queryForObject("SELECT id FROM pur_order_item WHERE purchase_order_id=?", Long.class, po);
        for (int i = 0; i < 24; i++) {
            long calc = jdbc.queryForObject("INSERT INTO mrp_calc(no,status,create_by) VALUES(?,'CONFIRMED',?) RETURNING id",
                    Long.class, "P34K" + suffix(), actor.id());
            jdbc.update("INSERT INTO mrp_calc_order(calc_id,order_id) VALUES(?,?)", calc, order);
            long item = jdbc.queryForObject("""
                    INSERT INTO mrp_calc_item(calc_id,seq,material_sku_id,gross_qty,need_qty,net_qty,use_transit_qty)
                    VALUES(?,1,?,100,100,100,1) RETURNING id
                    """, Long.class, calc, f.materialSku());
            jdbc.update("INSERT INTO mrp_calc_transit(calc_item_id,purchase_item_id,qty) VALUES(?,?,1)", item, line);
        }
        JsonNode response = ok(actor, "GET", "/api/order-kitting?orderIds=" + order, null, f.company());
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.get(0).path("status").stringValue()).isEqualTo("NOT");
        assertThat(response.get(0).path("totalCount").intValue()).isEqualTo(24);
        assertThat(response.get(0).path("calcIds").size()).isEqualTo(24);
        authenticate(actor);
        var captured = SqlStatementCapture.capture(() -> mrp.kitting(List.of(order)));
        assertThat(captured.result()).hasSize(1);
        List<String> transitReads = captured.statements().stream().map(sql -> sql.getSql().strip().replaceAll("\\s+", " "))
                .filter(sql -> sql.startsWith("SELECT t.* FROM mrp_calc_transit t JOIN mrp_calc_item i")).toList();
        assertThat(transitReads).as("24 confirmed calculations use one actual transit preload").hasSize(1);
        assertThat(transitReads.getFirst()).contains("i.calc_id IN");
        Path dir = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("p34-kitting-batch.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(response));
    }

    private void rejected(Account account, long company, String ids) throws Exception {
        JsonNode result = call(account, "GET", "/api/order-kitting?orderIds=" + ids, null, company);
        assertThat(result.path("code").intValue()).isEqualTo(404);
        assertThat(result.path("data").isNull() || result.path("data").isMissingNode()).isTrue();
    }
}
