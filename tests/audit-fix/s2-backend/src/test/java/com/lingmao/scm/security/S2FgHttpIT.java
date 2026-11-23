package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.fg.dto.FgDocItemReq;
import com.lingmao.scm.module.fg.dto.FgDocSaveReq;
import com.lingmao.scm.module.fg.service.FgDocService;
import com.lingmao.scm.module.style.service.StyleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP/PG tests. Synthetic business fixtures only; no production credentials or external system. */
class S2FgHttpIT extends A2BusinessFixture {
    @Autowired FgDocService fg;
    @Autowired StyleService styles;
    @Autowired ReferenceService references;
    @Autowired com.lingmao.scm.module.fg.mapper.FgStockMapper stockMapper;

    private record Source(Fixture fixture, long warehouse, long order, long production, long receipt, long receiptItem) { }

    @Test void partialReceipt300Plus96ReservesAndPostsTheReal396AndSeparatesCompanies() throws Exception {
        Fixture f = fixture();
        Account owner = actor(f.company(), "SELF", "production:order:list", "production:receipt:add", "production:receipt:void");
        Account reviewer = actor(f.company(), "ALL");
        Source source = source(f, owner, 396, true);
        JsonNode prefill = fgOk(owner, "GET", "/api/fg-docs/from-receipt/" + source.receipt() + "?companyId=" + f.company(), null, f.company());
        assertThat(prefill.path("items").get(0).path("remainingQty").intValue()).isEqualTo(396);
        long first = from(source, owner, 300); long second = from(source, owner, 96);
        JsonNode group = association(source, owner).getFirst();
        assertThat(group.path("documentsTotal").longValue()).isEqualTo(2);
        assertThat(group.path("reservedQty").longValue()).isEqualTo(396);
        assertThat(group.path("postedQty").longValue()).isZero();
        assertThat(group.path("remainingQty").longValue()).isZero();
        assertThat(fromResponse(source, owner, 1).path("code").intValue()).isEqualTo(1004);
        assertThat(action(owner, first, f.company(), "audit", 0).path("code").intValue()).isEqualTo(403);
        assertThat(action(reviewer, first, f.company(), "audit", 0).path("code").intValue()).isZero();
        group = association(source, owner).getFirst();
        assertThat(group.path("postedQty").longValue()).isEqualTo(300);
        assertThat(group.path("reservedQty").longValue()).isEqualTo(96);
        assertThat(qty(owner, f.company(), source.warehouse(), f.styleSku())).isEqualTo(300);
        assertThat(qty(owner, f.otherCompany(), source.warehouse(), f.styleSku())).isZero();
        JsonNode blocked = fgCall(owner, "POST", "/api/production-orders/" + source.production() + "/receipts/" + source.receipt() + "/void",
                Map.of("reason", "synthetic source reversal"), f.company());
        assertThat(blocked.path("code").intValue()).isEqualTo(1005);
        assertThat(jdbc.queryForObject("SELECT status FROM prd_receipt WHERE id=?", String.class, source.receipt())).isEqualTo("APPROVED");
        String reversePath = "/api/fg-docs/" + first + "/unaudit?companyId=" + f.company(), reverseKey = UUID.randomUUID().toString();
        assertThat(node(raw(reviewer, "POST", reversePath, Map.of("version", 1), f.company(), reverseKey)).path("code").intValue()).isZero();
        assertThat(node(raw(reviewer, "POST", reversePath, Map.of("version", 1), f.company(), reverseKey)).path("code").intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE fg_doc_id=? AND reversal_of_log_id IS NOT NULL", Long.class, first)).isEqualTo(1);
        assertThat(association(source, owner).getFirst().path("reservedQty").longValue()).isEqualTo(396);
        String voidPath = "/api/fg-docs/" + first + "/void?companyId=" + f.company(), voidKey = UUID.randomUUID().toString();
        Object voidBody = Map.of("version", 2, "reason", "synthetic reversal");
        assertThat(node(raw(owner, "POST", voidPath, voidBody, f.company(), voidKey)).path("code").intValue()).isZero();
        assertThat(node(raw(owner, "POST", voidPath, voidBody, f.company(), voidKey)).path("code").intValue()).isZero();
        String deleteKey = UUID.randomUUID().toString();
        String deletePath = "/api/fg-docs/" + second + "?companyId=" + f.company() + "&version=0";
        assertThat(node(raw(owner, "DELETE", deletePath, null, f.company(), deleteKey)).path("code").intValue()).isZero();
        assertThat(node(raw(owner, "DELETE", deletePath, null, f.company(), deleteKey)).path("code").intValue()).isZero();
        assertThat(node(raw(owner, "DELETE", deletePath.replace("version=0", "version=1"), null, f.company(), deleteKey)).path("code").intValue()).isEqualTo(1002);
        assertThat(node(raw(owner, "DELETE", deletePath, null, f.otherCompany(), deleteKey)).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='FG_DOC' AND doc_id=? AND action='remove'", Long.class, second)).isEqualTo(1);
        assertThat(association(source, owner).getFirst().path("remainingQty").longValue()).isEqualTo(396);
        write("partial-396.json", map("prefill", prefill, "afterRelease", association(source, owner), "sourceVoidBlocked", blocked));
    }

    @Test void twentyRealConcurrentCreateAndAuditRequestsUseOneReceiptAndOneLedgerCycle() throws Exception {
        Fixture f = fixture(); Account owner = actor(f.company(), "SELF"); Account reviewer = actor(f.company(), "ALL");
        Source source = source(f, owner, 396, false);
        String createKey = UUID.randomUUID().toString();
        Object body = receiptBody(source, 300, createKey);
        String createPath = "/api/fg-docs/from-receipt/" + source.receipt();
        var createStatuses = new java.util.concurrent.ConcurrentLinkedQueue<Map<String, Integer>>();
        List<JsonNode> creates = concurrent(20, n -> {
            try { var response = raw(owner, "POST", createPath, body, f.company(), createKey); var result = node(response);
                createStatuses.add(Map.of("httpStatus", response.statusCode(), "code", result.path("code").intValue())); return result; }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertThat(creates).allSatisfy(r -> assertThat(r.path("code").intValue()).isIn(0, 409));
        JsonNode replay = node(raw(owner, "POST", createPath, body, f.company(), createKey));
        assertThat(replay.path("code").intValue()).isZero(); long doc = id(replay.path("data").path("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_doc WHERE prd_receipt_id=?", Long.class, source.receipt())).isEqualTo(1);
        String auditKey = UUID.randomUUID().toString();
        String auditPath = "/api/fg-docs/" + doc + "/audit?companyId=" + f.company();
        var auditStatuses = new java.util.concurrent.ConcurrentLinkedQueue<Map<String, Integer>>();
        List<JsonNode> audits = concurrent(20, n -> {
            try { var response = raw(reviewer, "POST", auditPath, Map.of("version", 0), f.company(), auditKey); var result = node(response);
                auditStatuses.add(Map.of("httpStatus", response.statusCode(), "code", result.path("code").intValue())); return result; }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertThat(audits).allSatisfy(r -> assertThat(r.path("code").intValue()).isIn(0, 409));
        JsonNode auditReplay = node(raw(reviewer, "POST", auditPath, Map.of("version", 0), f.company(), auditKey));
        assertThat(auditReplay.path("code").intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE fg_doc_id=?", Long.class, doc)).isEqualTo(1);
        assertThat(qty(owner, f.company(), source.warehouse(), f.styleSku())).isEqualTo(300);
        assertThat(node(raw(reviewer, "POST", auditPath, Map.of("version", 1), f.company(), auditKey)).path("code").intValue()).isEqualTo(1002);
        assertThat(action(reviewer, doc, f.company(), "audit", 0).path("code").intValue()).isEqualTo(1002);
        write("concurrent-receipts.json", map("createCalls", creates.size(), "auditCalls", audits.size(),
                "createCodes", creates.stream().map(r -> r.path("code").intValue()).toList(),
                "auditCodes", audits.stream().map(r -> r.path("code").intValue()).toList(),
                "createHttp", createStatuses, "auditHttp", auditStatuses, "createReplay", replay, "auditReplay", auditReplay, "ledgerRows", 1));
    }

    @Test void sourceVoidAndReservationRaceHaveExactlyOneWinner() throws Exception {
        Fixture f = fixture(); Account owner = actor(f.company(), "SELF", "production:order:list", "production:receipt:void");
        Source source = source(f, owner, 10, false);
        List<JsonNode> results = concurrent(2, n -> {
            try {
                if (n == 0) return fromResponse(source, owner, 10);
                return fgCall(owner, "POST", "/api/production-orders/" + source.production() + "/receipts/" + source.receipt() + "/void",
                        Map.of("reason", "synthetic race"), f.company());
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertThat(results.stream().filter(r -> r.path("code").intValue() == 0)).hasSize(1);
        String state = jdbc.queryForObject("SELECT status FROM prd_receipt WHERE id=?", String.class, source.receipt());
        long docs = jdbc.queryForObject("SELECT count(*) FROM fg_doc WHERE prd_receipt_id=? AND status<>'VOIDED'", Long.class, source.receipt());
        if (state.equals("VOIDED")) assertThat(docs).isZero(); else { assertThat(state).isEqualTo("APPROVED"); assertThat(docs).isEqualTo(1); }
        write("source-void-race.json", map("results", results, "sourceStatus", state, "effectiveDocuments", docs));
    }

    @Test void skuIdentitySurvivesPutAndAuditRecomputesTrustedPriceAndPhysicalProjection() throws Exception {
        Fixture f = fixture(); Account maker = actor(f.company(), "SELF"); Account reviewer = actor(f.company(), "ALL", "order:price:query");
        long warehouse = productWarehouse();
        long stockDoc = manual(maker, f, warehouse, "OTHER_IN", 10);
        assertThat(action(reviewer, stockDoc, f.company(), "audit", 0).path("code").intValue()).isZero();
        long order = sourceOrder(f, maker, "APPROVED", f.company());
        jdbc.update("UPDATE ord_order_item SET price=.125,amount=1.25 WHERE order_id=?", order);
        Object body = map("companyId", f.company(), "docType", "SHIP_OUT", "orderId", order, "warehouseId", warehouse, "docDate", today(),
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", 1)));
        long doc = id(fgOk(maker, "POST", "/api/fg-docs", body, f.company()).path("id"));
        long item = jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc);
        Object save = map("companyId", f.company(), "warehouseId", warehouse, "docDate", today(), "version", 0,
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", 2)));
        String saveKey = UUID.randomUUID().toString();
        JsonNode saved = node(raw(maker, "PUT", "/api/fg-docs/" + doc, save, f.company(), saveKey));
        assertThat(saved.path("code").intValue()).as("%s", saved).isZero(); assertThat(saved.path("data").intValue()).isEqualTo(1);
        assertThat(node(raw(maker, "PUT", "/api/fg-docs/" + doc, save, f.company(), saveKey)).path("data").intValue()).isEqualTo(1);
        Object changedSave = map("companyId", f.company(), "warehouseId", warehouse, "docDate", today(), "version", 0,
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", 3)));
        assertThat(node(raw(maker, "PUT", "/api/fg-docs/" + doc, changedSave, f.company(), saveKey)).path("code").intValue()).isEqualTo(1002);
        assertThat(node(raw(maker, "PUT", "/api/fg-docs/" + doc, save, f.otherCompany(), saveKey)).path("code").intValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc)).isEqualTo(item);
        assertThat(jdbc.queryForObject("SELECT qty FROM fg_doc_item WHERE id=?", Integer.class, item)).isEqualTo(2);
        assertThat(fgCall(maker, "PUT", "/api/fg-docs/" + doc, save, f.company()).path("code").intValue()).isEqualTo(1002);
        jdbc.update("UPDATE ord_order_item SET price=.135,amount=1.35 WHERE order_id=?", order);
        assertThat(action(reviewer, doc, f.company(), "audit", 1).path("code").intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT amount FROM fg_doc_item WHERE id=?", BigDecimal.class, item)).isEqualByComparingTo("0.27");
        JsonNode hidden = detail(maker, doc, f.company()), priced = detail(reviewer, doc, f.company());
        assertThat(hidden.path("items").get(0).has("price")).isFalse();
        assertThat(hidden.path("items").get(0).has("amount")).isFalse(); assertThat(hidden.has("totalAmount")).isFalse();
        assertThat(priced.path("items").get(0).path("amount").decimalValue()).isEqualByComparingTo("0.27");
        assertThat(qty(maker, f.company(), warehouse, f.styleSku())).isEqualTo(8);
        var download = raw(maker, "GET", "/api/fg-docs/export?kind=OUT&companyId=" + f.company() + "&ids=" + doc, null, f.company(), null);
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(download.body()))) {
            List<String> headers = new ArrayList<>(); workbook.getSheetAt(0).getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
            assertThat(headers).doesNotContain("单价", "金额");
        }
        // The new audited stock is consumed; reversing its incoming source would create negative inventory.
        assertThat(action(reviewer, stockDoc, f.company(), "unaudit", 1).path("code").intValue()).isEqualTo(1001);
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=? AND menu_id IN (SELECT id FROM sys_menu WHERE perms='fg:doc:edit')", maker.role());
        JsonNode revoked = node(raw(maker, "PUT", "/api/fg-docs/" + doc, save, f.company(), saveKey));
        assertThat(revoked.path("code").intValue()).isIn(401, 403);
        write("price-projection-and-put.json", map("itemId", Long.toString(item), "saved", saved, "withoutPrice", hidden, "withPrice", priced, "revokedReplay", revoked));
    }

    @Test void byReceiptPreviewIsBoundedAuthorizedAndCountsAllReservationsIncludingHiddenDrafts() throws Exception {
        Fixture f = fixture(); Account self = actor(f.company(), "SELF"); Account all = actor(f.company(), "ALL");
        Account other = actor(f.company(), "SELF"); Source source = source(f, self, 100, false);
        for (int i = 0; i < 25; i++) from(source, self, 1);
        from(source, all, 5); // Hidden to SELF despite its visible source: still consumes real capacity.
        JsonNode group = association(source, self).getFirst();
        assertThat(group.path("documents").size()).isEqualTo(20);
        assertThat(group.path("documentsTotal").longValue()).isEqualTo(25); assertThat(group.path("hasMoreDocuments").booleanValue()).isTrue();
        assertThat(group.path("reservedQty").longValue()).isEqualTo(30); assertThat(group.path("remainingQty").longValue()).isEqualTo(70);
        var seen = new HashSet<String>();
        for (int page = 1; page <= 3; page++) {
            JsonNode result = fgOk(self, "GET", "/api/fg-docs?kind=IN&companyId=" + f.company() + "&receiptId=" + source.receipt() + "&size=10&page=" + page, null, f.company());
            assertThat(result.path("total").longValue()).isEqualTo(25);
            for (var row : result.path("list")) assertThat(seen.add(row.path("id").stringValue())).isTrue();
        }
        assertThat(seen).hasSize(25);
        Source invisible = source(f, other, 10, false);
        JsonNode mixed = fgCall(self, "GET", "/api/fg-docs/by-receipts?companyId=" + f.company() + "&receiptIds=" + source.receipt() + "," + invisible.receipt(), null, f.company());
        assertThat(mixed.path("code").intValue()).isEqualTo(404);
        String tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> Long.toString(source.receipt())).collect(java.util.stream.Collectors.joining(","));
        assertThat(fgCall(self, "GET", "/api/fg-docs/by-receipts?companyId=" + f.company() + "&receiptIds=" + tooMany, null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(fgCall(self, "GET", "/api/fg-docs/by-receipts?companyId=" + f.otherCompany() + "&receiptIds=" + source.receipt(), null, f.otherCompany()).path("code").intValue()).isEqualTo(404);
        long hiddenDoc = jdbc.queryForObject("SELECT id FROM fg_doc WHERE prd_receipt_id=? AND create_by=?", Long.class, source.receipt(), all.id());
        authenticate(self);
        assertThatThrownBy(() -> references.describe(BizType.FG_DOC, List.of(Long.parseLong(seen.iterator().next()), hiddenDoc)))
                .isInstanceOf(com.lingmao.scm.common.exception.BizException.class).hasMessageContaining("不存在");
        jdbc.update("UPDATE prd_receipt SET status='VOIDED' WHERE id=?", invisible.receipt());
        JsonNode historical = association(invisible, other).getFirst();
        assertThat(historical.path("remainingQty").longValue()).isZero();
        write("by-receipts-preview.json", map("self", group, "all", association(source, all).getFirst(), "mixedRejected", mixed,
                "visibleVoided", historical, "pagedIds", seen));
    }

    @Test void seekPassesOneHundredRowsWithoutDuplicatesAndBindsCompanyUserAndFilters() throws Exception {
        Fixture f = fixture(); Account owner = actor(f.company(), "SELF"); Account reviewer = actor(f.company(), "ALL");
        long warehouse = productWarehouse(); long doc = manual(owner, f, warehouse, "OTHER_IN", 1);
        for (int i = 0; i < 55; i++) {
            assertThat(action(reviewer, doc, f.company(), "audit", i * 2).path("code").intValue()).isZero();
            assertThat(action(reviewer, doc, f.company(), "unaudit", i * 2 + 1).path("code").intValue()).isZero();
        }
        String base = "/api/fg-stock-logs?companyId=" + f.company() + "&warehouseId=" + warehouse + "&size=100";
        JsonNode first = fgOk(owner, "GET", base, null, f.company());
        assertThat(first.path("list").size()).isEqualTo(100); assertThat(first.path("hasMore").booleanValue()).isTrue();
        String cursor = first.path("nextCursor").stringValue();
        JsonNode last = fgOk(owner, "GET", base + "&cursor=" + cursor, null, f.company());
        assertThat(last.path("list").size()).isEqualTo(10); assertThat(last.path("hasMore").booleanValue()).isFalse();
        var ids = new HashSet<String>();
        for (var row : first.path("list")) assertThat(ids.add(row.path("id").stringValue())).isTrue();
        for (var row : last.path("list")) assertThat(ids.add(row.path("id").stringValue())).isTrue();
        assertThat(ids).hasSize(110);
        assertThat(fgCall(reviewer, "GET", base + "&cursor=" + cursor, null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(fgCall(owner, "GET", base.replace("companyId=" + f.company(), "companyId=" + f.otherCompany()) + "&cursor=" + cursor, null, f.otherCompany()).path("code").intValue()).isEqualTo(400);
        assertThat(fgCall(owner, "GET", base + "&styleId=" + f.style() + "&cursor=" + cursor, null, f.company()).path("code").intValue()).isEqualTo(400);
        write("seek-110.json", map("first", first, "last", last, "uniqueIds", ids.size()));
    }

    @Test void pureFgActorUsesWhitelistedStylePickAndExactSkuProjectionDoesNotExpandHugeStyle() throws Exception {
        Fixture f = fixture(); Account owner = actor(f.company(), "SELF"); long warehouse = productWarehouse();
        JsonNode pick = fgOk(owner, "GET", "/api/styles/pick?keyword=" + f.styleCode(), null, null);
        assertThat(pick.path("list").size()).isEqualTo(1);
        JsonNode row = pick.path("list").get(0);
        assertThat(row.has("tagPrice")).as("public retail tag price remains an intentional existing field").isTrue();
        assertThat(row.has("refCost")).isFalse(); assertThat(row.has("price")).isFalse(); assertThat(row.has("legacyAttrs")).isFalse();
        assertThat(fgCall(owner, "GET", "/api/styles/" + f.style(), null, null).path("code").intValue()).isEqualTo(403);
        String prefix = "FGH" + suffix();
        try {
            assertThat(jdbc.update("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) SELECT ?,?||n,?,'Q'||n FROM generate_series(1,50001)n", f.style(), prefix, f.color())).isEqualTo(50001);
            authenticate(owner);
            var capture = SqlStatementCapture.capture(() -> styles.boundedOrderInfosOfSkus(List.of(f.styleSku()), 50_000));
            assertThat(capture.result()).hasSize(1); assertThat(capture.result().get(f.styleSku()).skus()).hasSize(1);
            var skuQueries = capture.statements().stream().map(q -> q.getSql().replaceAll("\\s+", " ").toLowerCase())
                    .filter(sql -> sql.contains("from sty_style_sku")).toList();
            assertThat(skuQueries).hasSize(1); assertThat(skuQueries.getFirst()).contains("id in", "limit 50001");
            long doc = manual(owner, f, warehouse, "OTHER_IN", 1);
            JsonNode actualDetail = detail(owner, doc, f.company());
            assertThat(actualDetail.path("styles").size()).isEqualTo(1);
            assertThat(actualDetail.path("styles").get(0).path("skus").size()).isEqualTo(1);
            assertThat(id(actualDetail.path("styles").get(0).path("skus").get(0).path("id"))).isEqualTo(f.styleSku());
            authenticate(owner);
            var detailSql = SqlStatementCapture.capture(() -> fg.detail(doc, f.company()));
            var actualQueries = detailSql.statements().stream().map(q -> q.getSql().replaceAll("\\s+", " ").toLowerCase())
                    .filter(sql -> sql.contains("from sty_style_sku")).toList();
            assertThat(actualQueries).hasSize(1); assertThat(actualQueries.getFirst()).contains("id in", "limit 50001");
            Source source = source(f, owner, 10, false);
            JsonNode prefill = fgOk(owner, "GET", "/api/fg-docs/from-receipt/" + source.receipt() + "?companyId=" + f.company(), null, f.company());
            assertThat(prefill.path("style").path("skus").size()).isEqualTo(1);
            JsonNode ship = fgOk(owner, "GET", "/api/fg-docs/ship-prefill?companyId=" + f.company() + "&orderId=" + source.order(), null, f.company());
            assertThat(ship.path("styles").get(0).path("skus").size()).isEqualTo(1);
            var export = raw(owner, "GET", "/api/fg-docs/export?kind=IN&companyId=" + f.company() + "&ids=" + doc, null, f.company(), null);
            assertThat(export.headers().firstValue("Content-Disposition")).isPresent();
            JsonNode matrix = fgCall(owner, "GET", "/api/fg-docs/style-options?companyId=" + f.company() + "&styleIds=" + f.style(), null, f.company());
            assertThat(matrix.path("code").intValue()).isEqualTo(400); assertThat(matrix.path("msg").stringValue()).contains("50000");
            write("exact-sku-projection.json", map("stylePick", pick, "actualSkuRows", 50002, "projectedSkus", 1,
                    "skuQueries", skuQueries, "detailQueries", actualQueries, "actualDetail", actualDetail, "receiptPrefill", prefill, "shipPrefill", ship,
                    "matrixControlledError", matrix, "exportBytes", export.body().length));
        } finally { jdbc.update("DELETE FROM sty_style_sku WHERE style_id=? AND code LIKE ?", f.style(), prefix + "%"); }
    }

    @Test void pureReadOnlySourceProjectionAndEditPrefillKeepSeparatePermissionsAndRealRemaining() throws Exception {
        Fixture f = fixture(); Account maker = actor(f.company(), "SELF");
        Account reader = custom(f.company(), "SELF", "fg:doc:list");
        Account editor = custom(f.company(), "SELF", "fg:doc:list", "fg:doc:edit");
        Source source = source(f, maker, 10, false);
        long sku2 = addSizeSku(f, "L");
        long orderItem = jdbc.queryForObject("INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty,price,amount,tech_pack_id,scheduled_qty,returned_qty) VALUES(?,2,?,?,6,7,42,?,6,6) RETURNING id",
                Long.class, source.order(), f.style(), sku2, f.pack());
        long productionItem = jdbc.queryForObject("INSERT INTO prd_order_item(prd_order_id,seq,order_item_id,style_sku_id,qty,returned_qty) VALUES(?,2,?,?,6,6) RETURNING id",
                Long.class, source.production(), orderItem, sku2);
        jdbc.update("INSERT INTO prd_receipt_item(receipt_id,seq,prd_order_item_id,style_sku_id,qty,defect_qty) VALUES(?,2,?,?,6,0)", source.receipt(), productionItem, sku2);
        long doc = from(source, maker, 10);
        jdbc.update("UPDATE prd_order SET merch_id=? WHERE id=?", reader.id(), source.production());
        JsonNode read = association(source, reader).getFirst();
        assertThat(read.path("reservedQty").longValue()).isEqualTo(10);
        assertThat(read.path("remainingQty").longValue()).isEqualTo(6);
        assertThat(read.path("documentsTotal").longValue()).isZero();
        assertThat(fgCall(reader, "GET", "/api/fg-docs/from-receipt/" + source.receipt() + "?companyId=" + f.company(), null, f.company()).path("code").intValue()).isEqualTo(403);
        Source hidden = source(f, maker, 1, false);
        assertThat(fgCall(reader, "GET", "/api/fg-docs/by-receipts?companyId=" + f.company() + "&receiptIds=" + source.receipt() + "," + hidden.receipt(), null, f.company()).path("code").intValue()).isEqualTo(404);
        jdbc.update("UPDATE fg_doc SET create_by=? WHERE id=?", editor.id(), doc);
        jdbc.update("UPDATE prd_order SET merch_id=? WHERE id=?", editor.id(), source.production());
        String endpoint = "/api/fg-docs/from-receipt/" + source.receipt() + "?companyId=" + f.company();
        assertThat(fgCall(editor, "GET", endpoint, null, f.company()).path("code").intValue()).isEqualTo(403);
        JsonNode prefill = fgOk(editor, "GET", endpoint + "&docId=" + doc, null, f.company());
        assertThat(prefill.path("items").size()).isEqualTo(2);
        assertThat(prefill.path("style").path("skus").size()).isEqualTo(2);
        assertThat(prefill.path("items").get(0).path("remainingQty").intValue()).isZero();
        assertThat(prefill.path("items").get(0).path("maxQty").intValue()).isEqualTo(10);
        assertThat(prefill.path("items").get(1).path("maxQty").intValue()).isEqualTo(6);
        assertThat(fgCall(editor, "GET", endpoint + "&docId=" + (doc + 1_000_000), null, f.company()).path("code").intValue()).isEqualTo(404);
        fgOk(editor, "PUT", "/api/fg-docs/" + doc, map("companyId", f.company(), "warehouseId", source.warehouse(), "docDate", today(), "version", 0,
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", 10), map("styleSkuId", sku2, "qty", 6))), f.company());
        assertThat(association(source, editor).getFirst().path("reservedQty").longValue()).isEqualTo(16);
        JsonNode options = fgOk(editor, "GET", "/api/fg-docs/style-options?companyId=" + f.company() + "&styleIds=" + f.style(), null, f.company());
        assertThat(options.get(0).path("skus").size()).isEqualTo(2);
        assertThat(qty(editor, f.company(), source.warehouse(), f.styleSku())).isZero();
        assertThat(fgCall(editor, "GET", "/api/fg-stocks?companyId=" + f.company(), null, f.company()).path("code").intValue()).isEqualTo(403);
        write("source-readonly-edit.json", map("reader", read, "editPrefill", prefill, "edited", detail(editor, doc, f.company())));
    }

    @Test void nullableSourcePricesRemainUnknownInDetailAndListAndLongIdsStayStrings() throws Exception {
        Fixture f = fixture(); Account maker = actor(f.company(), "SELF", "order:price:query"); long warehouse = productWarehouse();
        long sku2 = addSizeSku(f, "L"); long order = sourceOrder(f, maker, "APPROVED", f.company());
        jdbc.update("INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty,price,amount,tech_pack_id) VALUES(?,2,?,?,10,NULL,NULL,?)", order, f.style(), sku2, f.pack());
        JsonNode created = fgOk(maker, "POST", "/api/fg-docs", map("companyId", f.company(), "docType", "SHIP_OUT", "orderId", order,
                "warehouseId", warehouse, "docDate", today(), "items", List.of(map("styleSkuId", f.styleSku(), "qty", 1), map("styleSkuId", sku2, "qty", 1))), f.company());
        long doc = id(created.path("id"));
        JsonNode detail = detail(maker, doc, f.company());
        assertThat(detail.path("items").get(0).path("amount").decimalValue()).isEqualByComparingTo("89.00");
        assertThat(detail.path("items").get(1).path("price").isNull()).isTrue();
        assertThat(detail.path("items").get(1).path("amount").isNull()).isTrue();
        assertThat(detail.path("totalAmount").isNull()).isTrue();
        JsonNode list = fgOk(maker, "GET", "/api/fg-docs?kind=OUT&companyId=" + f.company() + "&ids=" + doc, null, f.company());
        assertThat(list.path("list").get(0).path("totalAmount").isNull()).isTrue();
        long large = 9_007_199_254_740_993L;
        long bigDoc = jdbc.queryForObject("INSERT INTO fg_doc(id,no,kind,doc_type,status,company_id,to_warehouse_id,doc_date,reason,create_by) OVERRIDING SYSTEM VALUE VALUES(?,?,'IN','OTHER_IN','DRAFT',?,?,CURRENT_DATE,'OTHER',?) RETURNING id",
                Long.class, large, "FGBIG" + suffix(), f.company(), warehouse, maker.id());
        jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES(?,1,?,1)", bigDoc, f.styleSku());
        JsonNode big = detail(maker, bigDoc, f.company());
        assertThat(big.path("id").isString()).isTrue(); assertThat(big.path("id").stringValue()).isEqualTo("9007199254740993");
        write("unknown-money-and-id.json", map("detail", detail, "list", list, "bigId", big));
    }

    @Test void stockAggregationUsesOnlyRequestedSkusAndCompanyAndMatrixWidthIsRejectedBeforeWriting() throws Exception {
        Fixture f = fixture(); Account owner = actor(f.company(), "ALL");
        long w1 = productWarehouse(), w2 = productWarehouse(); long otherSku = addSizeSku(f, "L");
        jdbc.update("INSERT INTO fg_stock(company_id,warehouse_id,style_sku_id,qty) VALUES(?,?,?,2000000000),(?,?,?,2000000000),(?,?,?,90),(?,?,?,80)",
                f.company(), w1, f.styleSku(), f.company(), w2, f.styleSku(), f.otherCompany(), w1, f.styleSku(), f.company(), w1, otherSku);
        var sum = SqlStatementCapture.capture(() -> stockMapper.sumBySkus(List.of(f.styleSku()), null, f.company()));
        assertThat(sum.result()).hasSize(1); assertThat(sum.result().getFirst().qty()).isEqualTo(4_000_000_000L);
        assertThat(stockMapper.sumBySkus(List.of(f.styleSku()), w1, f.company()).getFirst().qty()).isEqualTo(2_000_000_000L);
        assertThat(stockMapper.sumBySkus(List.of(f.styleSku()), null, f.otherCompany()).getFirst().qty()).isEqualTo(90);
        assertThat(stockMapper.sumBySkus(List.of(), null, f.company())).isEmpty();
        assertThat(stockMapper.sumBySkus(null, null, f.company())).isEmpty();
        JsonNode matrix = fgOk(owner, "GET", "/api/fg-stocks/by-style?companyId=" + f.company() + "&styleId=" + f.style(), null, f.company());
        assertThat(matrix.path("list").get(0).path("totalQty").longValue()).isEqualTo(4_000_000_080L);
        // The budget is production configuration: 16,381 distinct sizes + 4 fixed columns.
        try {
            jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) SELECT ?,100+n,'Q'||n FROM generate_series(1,16379)n", f.sizeGroup());
            var download = raw(owner, "GET", "/api/fg-stocks/by-style/export?companyId=" + f.company() + "&styleId=" + f.style(), null, f.company(), null);
            JsonNode rejected = node(download);
            assertThat(rejected.path("code").intValue()).isEqualTo(1000); assertThat(rejected.path("msg").stringValue()).contains("16384");
            assertThat(download.headers().firstValue("Content-Disposition")).isEmpty();
            write("stock-matrix-bounds.json", map("sumSql", sum.statements().stream().map(q -> q.getSql()).toList(), "sum", sum.result(),
                    "companyA", matrix, "requestedSkus", List.of(Long.toString(f.styleSku())), "overflow", rejected));
        } finally { jdbc.update("DELETE FROM bas_size_group_item WHERE size_group_id=? AND size_code LIKE 'Q%'", f.sizeGroup()); }
    }

    @Test void fileHashReplayPreservesOneMutationAndFormulaImportReportsExactCellWithoutPartialWrites() throws Exception {
        Fixture f = fixture(); Account owner = actor(f.company(), "SELF"); long warehouse = productWarehouse();
        long doc = manual(owner, f, warehouse, "OTHER_IN", 1);
        long item = jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc);
        String skuCode = jdbc.queryForObject("SELECT code FROM sty_style_sku WHERE id=?", String.class, f.styleSku());
        byte[] file = importBook(skuCode, 5, "synthetic import");
        JsonNode imported = importFile(owner, doc, f.company(), 0, file);
        assertThat(imported.path("code").intValue()).as("%s", imported).isZero();
        JsonNode replay = importFile(owner, doc, f.company(), 0, file);
        assertThat(replay.path("code").intValue()).as("%s", replay).isZero();
        assertThat(replay.path("data")).isEqualTo(imported.path("data"));
        assertThat(jdbc.queryForObject("SELECT version FROM fg_doc WHERE id=?", Integer.class, doc)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM fg_doc_item WHERE doc_id=?", Long.class, doc)).isEqualTo(item);
        assertThat(jdbc.queryForObject("SELECT qty FROM fg_doc_item WHERE id=?", Integer.class, item)).isEqualTo(5);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_import_receipt WHERE scope_key=? AND operation='fg-items' AND file_hash=?", Long.class, "fg-doc:" + doc, hash)).isEqualTo(1);
        JsonNode formula = importFile(owner, doc, f.company(), 1, importBook(skuCode, 9, "=CMD|'/C synthetic'!A0"));
        write("file-import-formula-response.json", formula);
        assertThat(formula.path("code").intValue()).as("%s", formula).isEqualTo(1003);
        assertThat(formula.toString()).contains("备注");
        assertThat(formula.path("data").get(0).path("row").intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT qty FROM fg_doc_item WHERE id=?", Integer.class, item)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT version FROM fg_doc WHERE id=?", Integer.class, doc)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='FG_DOC' AND doc_id=? AND action='import'", Long.class, doc)).isEqualTo(1);
        write("file-hash-import.json", map("sha256", hash, "first", imported, "replay", replay, "formulaRejected", formula, "version", 1, "itemId", Long.toString(item)));
    }

    @Test void homePendingCountRequiresExplicitCompanyAndForgedStateAndNegativeQuantitiesCannotWrite() throws Exception {
        Fixture f = fixture(); Account owner = actor(f.company(), "SELF"); Source source = source(f, owner, 10, false);
        JsonNode without = fgOk(owner, "GET", "/api/board/home/todos", null, null);
        assertThat(without.path("counts").toString()).doesNotContain("FG_PENDING");
        JsonNode home = fgOk(owner, "GET", "/api/board/home/todos?companyId=" + f.company(), null, f.company());
        List<JsonNode> counts = new ArrayList<>(); home.path("counts").forEach(counts::add);
        JsonNode fgCount = counts.stream().filter(c -> c.path("key").stringValue().equals("FG_PENDING")).findFirst().orElseThrow();
        assertThat(fgCount.path("count").longValue()).isEqualTo(1); assertThat(fgCount.path("companyId").isString()).isTrue();
        assertThat(id(fgCount.path("companyId"))).isEqualTo(f.company());
        assertThat(fgCall(owner, "GET", "/api/board/home/todos?companyId=" + f.company(), null, f.otherCompany()).path("code").intValue()).isEqualTo(403);
        Object bad = map("companyId", f.company(), "docType", "OTHER_IN", "warehouseId", source.warehouse(), "docDate", today(), "reason", "OTHER",
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", -1)));
        assertThat(fgCall(owner, "POST", "/api/fg-docs", bad, f.company()).path("code").intValue()).isEqualTo(400);
        JsonNode forged = fgCall(owner, "POST", "/api/fg-docs", map("companyId", f.company(), "docType", "SHIP_OUT", "orderId", source.order(),
                "warehouseId", source.warehouse(), "docDate", today(), "status", "APPROVED", "auditBy", owner.id(), "totalAmount", "0.01",
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", 1, "price", "0.01", "amount", "0.01"))), f.company());
        assertThat(forged.path("code").intValue()).isIn(0, 400);
        if (forged.path("code").intValue() == 0) {
            long doc = id(forged.path("data").path("id"));
            assertThat(jdbc.queryForObject("SELECT status FROM fg_doc WHERE id=?", String.class, doc)).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("SELECT audit_by FROM fg_doc WHERE id=?", Long.class, doc)).isNull();
            assertThat(jdbc.queryForObject("SELECT amount FROM fg_doc_item WHERE doc_id=?", BigDecimal.class, doc)).isEqualByComparingTo("89.00");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE fg_doc_id=?", Long.class, doc)).isZero();
        }
        write("home-and-untrusted-dto.json", map("withoutCompany", without, "withCompany", home, "forgedReply", forged));
    }

    private byte[] importBook(String sku, int qty, String remark) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("成品入库明细"); var head = sheet.createRow(0);
            head.createCell(0).setCellValue("SKU 码"); head.createCell(1).setCellValue("件数"); head.createCell(2).setCellValue("备注");
            var row = sheet.createRow(1); row.createCell(0).setCellValue(sku); row.createCell(1).setCellValue(qty); row.createCell(2).setCellValue(remark);
            workbook.write(output); return output.toByteArray();
        }
    }
    private JsonNode importFile(Account actor, long doc, long company, int version, byte[] file) throws Exception {
        String boundary = "FgBoundary" + suffix();
        byte[] before = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/fg-docs/" + doc + "/items/import?companyId=" + company + "&version=" + version))
                .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + actor.token())
                .header("X-Company-Id", Long.toString(company)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.concat(java.net.http.HttpRequest.BodyPublishers.ofByteArray(before),
                        java.net.http.HttpRequest.BodyPublishers.ofByteArray(file), java.net.http.HttpRequest.BodyPublishers.ofByteArray(after))).build();
        return node(http.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray()));
    }

    private long addSizeSku(Fixture f, String size) {
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,2,?)", f.sizeGroup(), size);
        return jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES(?,?,?,?) RETURNING id", Long.class,
                f.style(), f.styleCode() + "-" + f.colorCode() + "-" + size, f.color(), size);
    }

    private Account actor(long company, String scope, String... extra) throws Exception {
        List<String> perms = new ArrayList<>(List.of("fg:doc:list", "fg:doc:add", "fg:doc:edit", "fg:doc:audit", "fg:doc:remove", "fg:doc:export", "fg:stock:list", "fg:stock:export"));
        perms.addAll(List.of(extra)); return custom(company, scope, perms.toArray(String[]::new));
    }
    private long productWarehouse() {
        return jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,?,'PRODUCT') RETURNING id", Long.class, "FGW" + suffix(), "FG synthetic warehouse");
    }
    private Source source(Fixture f, Account owner, int good, boolean receiveHttp) throws Exception {
        long warehouse = productWarehouse(), order = sourceOrder(f, owner, "RUNNING", f.company());
        int total = good + 4;
        jdbc.update("UPDATE ord_order_item SET qty=?,scheduled_qty=?,returned_qty=0,amount=?*price WHERE order_id=?", total, total, total, order);
        jdbc.update("UPDATE ord_order SET total_qty=?,total_amount=?*89 WHERE id=?", total, total, order);
        long production = sourceProduction(f, owner, order, "RUNNING");
        jdbc.update("UPDATE ord_order_item SET scheduled_qty=? WHERE order_id=?", total, order);
        jdbc.update("UPDATE prd_order_item SET qty=?,returned_qty=0,defect_qty=0 WHERE prd_order_id=?", total, production);
        jdbc.update("UPDATE prd_order SET total_qty=?,total_amount=?*unit_price WHERE id=?", total, total, production);
        long sourceLine = jdbc.queryForObject("SELECT id FROM prd_order_item WHERE prd_order_id=?", Long.class, production);
        long receipt;
        if (receiveHttp) {
            String key = UUID.randomUUID().toString();
            Object body = map("clientActionId", key, "receiptDate", today(), "warehouseId", warehouse,
                    "items", List.of(map("prdOrderItemId", sourceLine, "qty", total, "defectQty", 4)));
            JsonNode created = node(raw(owner, "POST", "/api/production-orders/" + production + "/receipts", body, f.company(), key));
            assertThat(created.path("code").intValue()).as("real production receipt: %s", created).isZero(); receipt = id(created.path("data").path("id"));
        } else {
            receipt = jdbc.queryForObject("INSERT INTO prd_receipt(no,company_id,prd_order_id,receipt_date,warehouse_id,status,create_by) VALUES(?,?,?,?,?,'APPROVED',?) RETURNING id",
                    Long.class, "FGHR" + suffix(), f.company(), production, today(), warehouse, owner.id());
            jdbc.update("INSERT INTO prd_receipt_item(receipt_id,seq,prd_order_item_id,style_sku_id,qty,defect_qty) VALUES(?,1,?,?,?,4)", receipt, sourceLine, f.styleSku(), total);
            jdbc.update("UPDATE prd_order_item SET returned_qty=?,defect_qty=4 WHERE prd_order_id=?", total, production);
            jdbc.update("UPDATE ord_order_item SET returned_qty=? WHERE order_id=?", total, order);
        }
        long receiptItem = jdbc.queryForObject("SELECT id FROM prd_receipt_item WHERE receipt_id=?", Long.class, receipt);
        return new Source(f, warehouse, order, production, receipt, receiptItem);
    }
    private Object receiptBody(Source s, int qty, String key) {
        return map("companyId", s.fixture().company(), "clientActionId", key, "post", false,
                "items", List.of(map("prdReceiptItemId", s.receiptItem(), "qty", qty)));
    }
    private JsonNode fromResponse(Source source, Account actor, int qty) throws Exception {
        String key = UUID.randomUUID().toString();
        return node(raw(actor, "POST", "/api/fg-docs/from-receipt/" + source.receipt(), receiptBody(source, qty, key), source.fixture().company(), key));
    }
    private long from(Source source, Account actor, int qty) throws Exception {
        JsonNode result = fromResponse(source, actor, qty); assertThat(result.path("code").intValue()).as("%s", result).isZero();
        assertThat(result.path("data").path("id").isString()).isTrue(); return id(result.path("data").path("id"));
    }
    private long manual(Account actor, Fixture f, long warehouse, String type, int qty) throws Exception {
        Object body = map("companyId", f.company(), "docType", type, "warehouseId", warehouse, "docDate", today(), "reason", "OTHER",
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", qty)));
        return id(fgOk(actor, "POST", "/api/fg-docs", body, f.company()).path("id"));
    }
    private JsonNode action(Account actor, long id, long company, String action, int version) throws Exception {
        return fgCall(actor, "POST", "/api/fg-docs/" + id + "/" + action + "?companyId=" + company,
                map("version", version, "reason", action.equals("void") ? "synthetic reversal" : null), company);
    }
    private JsonNode detail(Account actor, long id, long company) throws Exception {
        return fgOk(actor, "GET", "/api/fg-docs/" + id + "?companyId=" + company, null, company);
    }
    private long qty(Account actor, long company, long warehouse, long sku) throws Exception {
        return fgOk(actor, "GET", "/api/fg-stocks/qty?companyId=" + company + "&warehouseId=" + warehouse + "&skuIds=" + sku, null, company).get(0).path("qty").longValue();
    }
    private List<JsonNode> association(Source source, Account actor) throws Exception {
        JsonNode rows = fgOk(actor, "GET", "/api/fg-docs/by-receipts?companyId=" + source.fixture().company() + "&receiptIds=" + source.receipt(), null, source.fixture().company());
        List<JsonNode> result = new ArrayList<>(); rows.forEach(result::add); return result;
    }
    private JsonNode fgCall(Account actor, String method, String path, Object body, Long company) throws Exception {
        return node(raw(actor, method, path, body, company, method.equals("GET") ? null : UUID.randomUUID().toString()));
    }
    private JsonNode fgOk(Account actor, String method, String path, Object body, Long company) throws Exception {
        JsonNode result = fgCall(actor, method, path, body, company);
        assertThat(result.path("code").intValue()).as("%s %s: %s", method, path, result).isZero(); return result.path("data");
    }
    private List<JsonNode> concurrent(int count, IntFunction<JsonNode> operation) throws Exception {
        var start = new CountDownLatch(1); var ready = new CountDownLatch(count);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonNode>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) { final int index = i; tasks.add(executor.submit(() -> { ready.countDown(); start.await(); return operation.apply(index); })); }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<JsonNode> out = new ArrayList<>(); for (var task : tasks) out.add(task.get(60, TimeUnit.SECONDS)); return out;
        } finally { start.countDown(); }
    }
    private void write(String name, Object value) throws Exception {
        Path dir = Path.of("test-artifacts/full-audit/fg/s2"); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
