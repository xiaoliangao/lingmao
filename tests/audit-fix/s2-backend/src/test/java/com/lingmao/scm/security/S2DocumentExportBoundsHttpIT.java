package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Real database/HTTP controls for document export parent, detail and enrichment budgets. */
class S2DocumentExportBoundsHttpIT extends A2BusinessFixture {
    @Autowired InvDocService inventory;
    @Autowired PurchaseOrderService purchases;
    @Autowired ProductionOrderService production;
    @Autowired OrderService orders;

    @Test void inventoryDetailsAreLimitedBeforeReadingOrBuildingAllExportRows() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "inventory:doc:export");
        String prefix = "P37I" + suffix();
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO inv_doc(no,company_id,kind,doc_type,status,to_warehouse_id,doc_date,reason,create_by)
                SELECT ? || '-' || n,?,'IN','OTHER_IN','DRAFT',?,CURRENT_DATE,'OTHER',?
                FROM generate_series(1,101) n RETURNING id
                """, Long.class, prefix, f.company(), f.warehouse(), actor.id());
        try {
            assertThat(jdbc.update("""
                    INSERT INTO inv_doc_item(doc_id,seq,material_sku_id,qty,price,amount)
                    SELECT d.id, n, ?,1,2.34,2.34 FROM inv_doc d
                    CROSS JOIN generate_series(1,500) n
                    WHERE d.no LIKE ? AND (d.id <> ? OR n=1)
                    """, f.materialSku(), prefix + "-%", ids.getLast())).isEqualTo(50_001);
            assertThat(count("inv_doc_item", "doc_id", ids)).isEqualTo(50_001L);
            authenticate(actor);
            InvDocQuery query = new InvDocQuery(); query.setKind(InvDocKind.IN); query.setIds(ids);
            var captured = attempt(() -> inventory.export(query));
            sqlEvidence("inventory-details", captured, 101, 50_001);
            assertLiteralLimit(captured, "inv_doc_item", "order by doc_id asc,seq asc,id asc");
            assertThat(captured.result().code()).isEqualTo(1000);
            assertThat(sql(captured).stream().anyMatch(s -> s.contains("from bas_material_sku"))).isFalse();
            rejectHttp("inventory-details", actor, "/api/inventory-docs/export?kind=IN&ids=" + joined(ids), "物料出入库明细");
        } finally {
            delete("inv_doc_item", "doc_id", ids); delete("inv_doc", "id", ids);
        }
    }

    @Test void purchaseDetailsAreLimitedBeforeReadingOrBuildingAllExportRows() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "purchase:order:export");
        String prefix = "P37P" + suffix();
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO pur_order(no,company_id,supplier_id,purchase_type,buyer_id,order_date,delivery_date,
                                      warehouse_id,settle_method,tax_pct,status,create_by)
                SELECT ? || '-' || n,?,?,'STOCK',?,CURRENT_DATE,CURRENT_DATE+30,?,'MONTHLY',13,'DRAFT',?
                FROM generate_series(1,101) n RETURNING id
                """, Long.class, prefix, f.company(), f.supplier(), actor.id(), f.warehouse(), actor.id());
        try {
            assertThat(jdbc.update("""
                    INSERT INTO pur_order_item(purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount)
                    SELECT p.id,n,?,'METER',1,1,2.34,2.34 FROM pur_order p
                    CROSS JOIN generate_series(1,500) n
                    WHERE p.no LIKE ? AND (p.id <> ? OR n=1)
                    """, f.materialSku(), prefix + "-%", ids.getLast())).isEqualTo(50_001);
            assertThat(count("pur_order_item", "purchase_order_id", ids)).isEqualTo(50_001L);
            jdbc.update("""
                    UPDATE pur_order p SET total_qty=x.qty,total_amount=x.amount
                    FROM (SELECT purchase_order_id,sum(qty) qty,sum(amount) amount FROM pur_order_item
                          WHERE purchase_order_id IN (SELECT id FROM pur_order WHERE no LIKE ?) GROUP BY purchase_order_id) x
                    WHERE p.id=x.purchase_order_id
                    """, prefix + "-%");
            authenticate(actor);
            PurchaseQuery query = new PurchaseQuery(); query.setIds(ids);
            var captured = attempt(() -> purchases.export(query));
            sqlEvidence("purchase-details", captured, 101, 50_001);
            assertLiteralLimit(captured, "pur_order_item", "order by purchase_order_id asc,seq asc,id asc");
            assertThat(captured.result().code()).isEqualTo(1000);
            assertThat(sql(captured).stream().anyMatch(s -> s.contains("from bas_material_sku"))).isFalse();
            rejectHttp("purchase-details", actor, "/api/purchase-orders/export?ids=" + joined(ids), "采购单明细");
        } finally {
            delete("pur_order_item", "purchase_order_id", ids); delete("pur_order", "id", ids);
        }
    }

    @Test void orderStyleEnrichmentIsBoundedEvenWhenTheSheetHasFewParentRows() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "order:order:export");
        String prefix = "P37O" + suffix();
        List<Long> styleIds = jdbc.queryForList("""
                INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id,status)
                SELECT ? || '-' || n,'P37 synthetic style',style_class,brand,category_id,year,season,source,size_group_id,status
                FROM sty_style CROSS JOIN generate_series(1,200) n WHERE id=? RETURNING id
                """, Long.class, prefix, f.style());
        List<Long> ids = List.of();
        try {
            jdbc.update("""
                    INSERT INTO sty_style_color(style_id,color_id,seq)
                    SELECT id,?,1 FROM sty_style WHERE code LIKE ?
                    """, f.color(), prefix + "-%");
            jdbc.update("""
                    INSERT INTO sty_style_sku(style_id,code,color_id,size_code)
                    SELECT id,code || '-M',?,'M' FROM sty_style WHERE code LIKE ?
                    """, f.color(), prefix + "-%");
            ids = jdbc.queryForList("""
                    INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id,status,total_qty,create_by)
                    SELECT ? || '-' || n,?,'STOCK',CURRENT_DATE,CURRENT_DATE+30,?,?,'DRAFT',0,?
                    FROM generate_series(1,251) n RETURNING id
                    """, Long.class, prefix, f.company(), actor.id(), actor.id(), actor.id());
            assertThat(jdbc.update("""
                    INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty,price,amount)
                    SELECT o.id, row_number() OVER(PARTITION BY o.id ORDER BY k.id),k.style_id,k.id,1,2.34,2.34
                    FROM ord_order o CROSS JOIN sty_style_sku k
                    WHERE o.no LIKE ? AND k.code LIKE ? AND (o.id <> ? OR k.style_id=?)
                    """, prefix + "-%", prefix + "-%", ids.getLast(), styleIds.getFirst())).isEqualTo(50_001);
            jdbc.update("""
                    UPDATE ord_order o SET total_qty=x.qty,total_amount=x.amount
                    FROM (SELECT order_id,sum(qty) qty,sum(amount) amount FROM ord_order_item
                          WHERE order_id IN (SELECT id FROM ord_order WHERE no LIKE ?) GROUP BY order_id) x
                    WHERE o.id=x.order_id
                    """, prefix + "-%");
            authenticate(actor);
            OrderQuery query = new OrderQuery(); query.setIds(ids);
            var captured = attempt(() -> orders.export(query));
            sqlEvidence("order-style-enrichment", captured, 251, 50_001);
            assertLiteralLimit(captured, "ord_order_item", "order by order_id,min(seq),style_id");
            assertThat(captured.result().code()).isEqualTo(1000);
            rejectHttp("order-style-enrichment", actor, "/api/orders/export?ids=" + joined(ids), "需求订单款式关联");
        } finally {
            delete("ord_order_item", "order_id", ids); delete("ord_order", "id", ids);
            delete("sty_style_sku", "style_id", styleIds); delete("sty_style_color", "style_id", styleIds);
            delete("sty_style", "id", styleIds);
        }
    }

    @Test void purchaseHeadersUseRealSqlLimitsBeforeMetadata() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "purchase:order:export");
        long purchase = sourcePurchase(f, actor);
        authenticate(actor);
        PurchaseQuery pq = new PurchaseQuery(); pq.setIds(List.of(purchase));
        var pc = attempt(() -> purchases.export(pq));
        sqlEvidence("purchase-headers", pc, 1, 1);
        assertBoundPage(pc, "from pur_order o");
        assertThat(pc.result().code()).isZero();
        assertThat(raw(actor, "GET", "/api/purchase-orders/export?ids=" + purchase, null, null, null)
                .headers().firstValue("Content-Disposition")).isPresent();
    }

    @Test void productionHeadersUseRealSqlLimitsWithoutUnneededNodeReads() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "production:order:export");
        long order = sourceOrder(f, actor, "APPROVED", f.company());
        long prd = sourceProduction(f, actor, order, "DRAFT");
        authenticate(actor);
        ProductionQuery rq = new ProductionQuery(); rq.setKind("OUTSOURCE"); rq.setIds(List.of(prd));
        var rc = attempt(() -> production.export(rq));
        sqlEvidence("production-headers", rc, 1, 1);
        assertBoundPage(rc, "from prd_order p");
        assertThat(rc.result().code()).isZero();
        assertThat(sql(rc)).noneMatch(s -> s.startsWith("select * from prd_node "));
        assertThat(sql(rc)).noneMatch(s -> s.contains("from biz_attachment"));
        assertThat(raw(actor, "GET", "/api/production-orders/export?kind=OUTSOURCE&ids=" + prd, null, null, null)
                .headers().firstValue("Content-Disposition")).isPresent();
    }

    @Test void normalDocumentExportsKeepRowOrderFormulaEscapingAndPhysicalPriceProjection() throws Exception {
        Fixture f = fixture();
        String[] rights = {"order:order:export", "purchase:order:export", "production:order:export", "inventory:doc:export"};
        Account reader = custom(f.company(), "ALL", rights), admin = role("admin", f.company());
        long order = sourceOrder(f, admin, "APPROVED", f.company());
        long purchase = sourcePurchase(f, admin), prd = sourceProduction(f, admin, order, "DRAFT");
        long inv = jdbc.queryForObject("""
                INSERT INTO inv_doc(no,company_id,kind,doc_type,status,to_warehouse_id,doc_date,reason,create_by)
                VALUES(?,?,'IN','OTHER_IN','DRAFT',?,CURRENT_DATE,'OTHER',?) RETURNING id
                """, Long.class, "@P37" + suffix(), f.company(), f.warehouse(), admin.id());
        jdbc.update("UPDATE ord_order SET no=? WHERE id=?", "=P37" + suffix(), order);
        jdbc.update("UPDATE pur_order SET no=? WHERE id=?", "+P37" + suffix(), purchase);
        jdbc.update("UPDATE prd_order SET no=? WHERE id=?", "-P37" + suffix(), prd);
        jdbc.update("UPDATE pur_order_item SET seq=20 WHERE purchase_order_id=?", purchase);
        jdbc.update("""
                INSERT INTO pur_order_item(purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount)
                VALUES(?,10,?,'METER',1,3,2.34,7.02)
                """, purchase, f.materialSku());
        jdbc.update("""
                INSERT INTO inv_doc_item(doc_id,seq,material_sku_id,qty,price,amount,remark)
                VALUES(?,20,?,2,2.34,4.68,'=CMD|synthetic'),(?,10,?,3,2.34,7.02,'+synthetic')
                """, inv, f.materialSku(), inv, f.materialSku());
        List<String> paths = List.of("/api/orders/export?ids=" + order, "/api/purchase-orders/export?ids=" + purchase,
                "/api/production-orders/export?kind=OUTSOURCE&ids=" + prd, "/api/inventory-docs/export?kind=IN&ids=" + inv);
        List<List<String>> sensitive = List.of(List.of("金额"), List.of("单价", "金额", "改价原因"),
                List.of("加工费", "金额"), List.of("单价", "金额"));
        List<Object> evidence = new ArrayList<>();
        for (Account actor : List.of(reader, admin)) {
            boolean prices = actor.id() == admin.id();
            for (int i = 0; i < paths.size(); i++) {
                HttpResponse<byte[]> reply = raw(actor, "GET", paths.get(i), null, null, null);
                assertThat(reply.statusCode()).isEqualTo(200);
                assertThat(reply.headers().firstValue("Content-Disposition").orElse("")).contains("filename*=UTF-8''");
                String file = "normal-" + i + (prices ? "-with-price.xlsx" : "-without-price.xlsx");
                Files.createDirectories(evidenceDir()); Files.write(evidenceDir().resolve(file), reply.body());
                try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(reply.body()))) {
                    assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
                    Sheet sheet = workbook.getSheetAt(0);
                    List<String> headers = headers(sheet);
                    if (prices) assertThat(headers).containsAll(sensitive.get(i));
                    else assertThat(headers).doesNotContainAnyElementsOf(sensitive.get(i));
                    assertThat(sheet.getLastRowNum()).isEqualTo(i == 1 || i == 3 ? 2 : 1);
                    assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).startsWith("'");
                    if (i == 1 || i == 3) {
                        int seq = headers.indexOf(i == 1 ? "行号" : "行");
                        assertThat(seq).isNotNegative();
                        assertThat(sheet.getRow(1).getCell(seq).getNumericCellValue()).isEqualTo(10);
                        assertThat(sheet.getRow(2).getCell(seq).getNumericCellValue()).isEqualTo(20);
                    }
                    for (var row : sheet) for (var cell : row) assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                    evidence.add(map("file", file, "headers", headers, "rows", sheet.getLastRowNum(), "hasPricePermission", prices));
                }
            }
        }
        write("normal-workbooks.json", evidence);
    }

    private record Attempt(int code, String message, int bytes) { }
    private static SqlStatementCapture.Captured<Attempt> attempt(Supplier<byte[]> operation) {
        return SqlStatementCapture.capture(() -> {
            try { return new Attempt(0, "success", operation.get().length); }
            catch (BizException error) { return new Attempt(error.getCode(), error.getMessage(), 0); }
        });
    }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").replaceAll("\\s*,\\s*", ",").strip().toLowerCase(Locale.ROOT); }
    private static List<String> sql(SqlStatementCapture.Captured<?> capture) { return capture.statements().stream().map(s -> normalize(s.getSql())).toList(); }
    private static void assertLiteralLimit(SqlStatementCapture.Captured<?> capture, String table, String order) {
        assertThat(Excel.MAX_EXPORT_ROWS).isEqualTo(50_000);
        assertThat(sql(capture).stream().filter(s -> s.contains("from " + table + " where"))
                .filter(s -> !table.equals("ord_order_item") || s.contains("group by order_id,style_id"))).singleElement()
                .satisfies(s -> assertThat(s).endsWith("limit 50001").contains(order));
    }
    private static void assertBoundPage(SqlStatementCapture.Captured<?> capture, String from) {
        assertThat(capture.statements().stream().filter(s -> normalize(s.getSql()).contains(from))).singleElement().satisfies(bound -> {
            assertThat(normalize(bound.getSql())).endsWith("limit ?");
            assertThat(limitValue(bound)).isEqualTo(50_001L);
        });
    }
    private static Long limitValue(BoundSql bound) {
        if (!normalize(bound.getSql()).endsWith("limit ?") || bound.getParameterMappings().isEmpty()) return null;
        String property = bound.getParameterMappings().getLast().getProperty();
        Object value = bound.hasAdditionalParameter(property) ? bound.getAdditionalParameter(property)
                : SystemMetaObject.forObject(bound.getParameterObject()).getValue(property);
        return value instanceof Number number ? number.longValue() : null;
    }
    private void sqlEvidence(String name, SqlStatementCapture.Captured<Attempt> capture, int parents, int children) throws Exception {
        write(name + "-sql.json", map("database", jdbc.queryForObject("SELECT current_database()", String.class),
                "parentRows", parents, "relatedRows", children, "limit", Excel.MAX_EXPORT_ROWS, "attempt", capture.result(),
                "statements", capture.statements().stream().map(s -> map("sql", normalize(s.getSql()), "boundLimit", limitValue(s))).toList(),
                "scope", "Real SQL/database capacity boundary; not concurrency, OOM or cancellation proof."));
    }
    private void rejectHttp(String name, Account actor, String path, String label) throws Exception {
        HttpResponse<byte[]> response = raw(actor, "GET", path, null, null, null);
        write(name + "-http.json", map("status", response.statusCode(), "response", node(response),
                "downloadHeaderPresent", response.headers().firstValue("Content-Disposition").isPresent()));
        assertThat(node(response).path("code").intValue()).isEqualTo(1000);
        assertThat(node(response).path("msg").stringValue()).contains(label, "50000");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
    }
    private long count(String table, String column, List<Long> ids) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " IN (" + marks(ids) + ")", Long.class, ids.toArray());
    }
    private void delete(String table, String column, List<Long> ids) {
        if (!ids.isEmpty()) jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + marks(ids) + ")", ids.toArray());
    }
    private static String marks(List<Long> ids) { return String.join(",", Collections.nCopies(ids.size(), "?")); }
    private static String joined(List<Long> ids) { return ids.stream().map(Object::toString).collect(Collectors.joining(",")); }
    private static List<String> headers(Sheet sheet) {
        List<String> headers = new ArrayList<>(); sheet.getRow(0).forEach(cell -> headers.add(cell.getStringCellValue())); return headers;
    }
    private static Path evidenceDir() { return Path.of("test-artifacts/full-audit/s2/p37-documents"); }
    private void write(String name, Object value) throws Exception {
        Files.createDirectories(evidenceDir()); Files.writeString(evidenceDir().resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
