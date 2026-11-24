package com.lingmao.scm.security;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 真 PostgreSQL + HTTP：库存/盘点表及矩阵跨 500 行边界，记录实际 mapper 返回行数。 */
@Import(S2InventoryStreamingHttpIT.Probes.class)
class S2InventoryStreamingHttpIT extends A2BusinessFixture {
    @Autowired QueryProbe probe;

    @Test void inventoryAndCountExportsKeepEveryRowAndLogThePreparedSheetOutsideTheReadOnlySnapshot() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "inventory:stock:export", "inventory:count:export", "inventory:doc:export");
        String prefix = "P37R" + suffix();
        assertThat(jdbc.update("""
                INSERT INTO bas_material_sku(material_id,code,color_id,spec_code)
                SELECT ?,?||lpad(n::text,4,'0'),?,'S'||n FROM generate_series(1,1203)n
                """, f.material(), prefix, f.color())).isEqualTo(1203);
        jdbc.update("""
                INSERT INTO inv_stock(warehouse_id,material_sku_id,qty)
                SELECT ?,id,7 FROM bas_material_sku WHERE material_id=? AND code LIKE ?
                """, f.warehouse(), f.material(), prefix + "%");
        long count = jdbc.queryForObject("""
                INSERT INTO inv_count(no,company_id,warehouse_id,purpose,scope,count_date,counter_id,create_by,status)
                VALUES(?,?,?,'DAILY','PICKED',CURRENT_DATE,?,?,'DRAFT') RETURNING id
                """, Long.class, prefix + "C0000", f.company(), f.warehouse(), actor.id(), actor.id());
        jdbc.update("""
                INSERT INTO inv_count_item(count_id,seq,material_sku_id,book_qty,counted_qty,remark)
                SELECT ?,row_number() OVER(ORDER BY code),id,7,9,'=line-'||row_number() OVER(ORDER BY code)
                FROM bas_material_sku WHERE material_id=? AND code LIKE ?
                """, count, f.material(), prefix + "%");
        jdbc.update("""
                INSERT INTO inv_count(no,company_id,warehouse_id,purpose,scope,count_date,counter_id,create_by,status)
                SELECT ?||'C'||lpad(n::text,4,'0'),?,?,'DAILY','PICKED',CURRENT_DATE,?,?,'VOIDED'
                FROM generate_series(1,1202)n
                """, prefix, f.company(), f.warehouse(), actor.id(), actor.id());
        long first = document(f, actor, "P37I" + suffix()), second = document(f, actor, "P37I" + suffix());
        for (long doc : List.of(first, second)) {
            jdbc.update("""
                    INSERT INTO inv_doc_item(doc_id,seq,material_sku_id,qty,price,amount)
                    SELECT ?,row_number() OVER(ORDER BY code),id,1,2.34,2.34 FROM bas_material_sku
                    WHERE material_id=? AND code LIKE ? ORDER BY code LIMIT 601
                    """, doc, f.material(), prefix + "%");
        }
        probe.clear();
        var sheetResponse = raw(actor, "GET", "/api/inventory-counts/" + count + "/sheet", null, f.company(), null);
        try (Workbook workbook = workbook(sheetResponse)) {
            Sheet sheet = workbook.getSheetAt(0); var h = headers(sheet);
            assertThat(sheet.getLastRowNum()).isEqualTo(1203);
            for (int row = 1; row <= 1203; row++) {
                assertThat(sheet.getRow(row).getCell(h.indexOf("SKU 码")).getStringCellValue()).isEqualTo(prefix + String.format(Locale.ROOT, "%04d", row));
                assertThat(sheet.getRow(row).getCell(h.indexOf("实盘数")).getNumericCellValue()).isEqualTo(9);
                assertThat(sheet.getRow(row).getCell(h.indexOf("备注")).getStringCellValue()).isEqualTo("'=line-" + row);
            }
        }
        int audit = sheetAudits(count, actor);
        assertThat(audit).isEqualTo(1);
        var stockResponse = raw(actor, "GET", "/api/stocks/export?warehouseId=" + f.warehouse(), null, null, null);
        try (Workbook workbook = workbook(stockResponse)) {
            Sheet sheet = workbook.getSheetAt(0); var h = headers(sheet);
            assertThat(sheet.getLastRowNum()).isEqualTo(1203);
            for (int row = 1; row <= 1203; row++) {
                assertThat(sheet.getRow(row).getCell(h.indexOf("SKU 码")).getStringCellValue()).isEqualTo(prefix + String.format(Locale.ROOT, "%04d", row));
                assertThat(sheet.getRow(row).getCell(h.indexOf("库存")).getNumericCellValue()).isEqualTo(7);
            }
        }
        String firstNo = jdbc.queryForObject("SELECT no FROM inv_doc WHERE id=?", String.class, first);
        String secondNo = jdbc.queryForObject("SELECT no FROM inv_doc WHERE id=?", String.class, second);
        var docResponse = raw(actor, "GET", "/api/inventory-docs/export?kind=IN&ids=" + first + "," + second, null, f.company(), null);
        try (Workbook workbook = workbook(docResponse)) {
            Sheet sheet = workbook.getSheetAt(0); var h = headers(sheet);
            assertThat(sheet.getLastRowNum()).isEqualTo(1202);
            assertThat(h).doesNotContain("单价", "金额");
            for (int row = 1; row <= 1202; row++) {
                assertThat(sheet.getRow(row).getCell(h.indexOf("单号")).getStringCellValue()).isEqualTo(row <= 601 ? secondNo : firstNo);
                assertThat(sheet.getRow(row).getCell(h.indexOf("行")).getNumericCellValue()).isEqualTo((row - 1) % 601 + 1);
            }
        }
        assertBounded();
        assertThat(probe.results.stream().filter(r -> r.sql().contains("from inv_count_item") && r.rows() == 500).count()).isGreaterThanOrEqualTo(4);
        assertThat(probe.results.stream().filter(r -> r.sql().contains("from inv_doc_item") && r.rows() == 500).count()).isGreaterThanOrEqualTo(4);
        Account resetActor = custom(f.company(), "ALL", "inventory:stock:export", "inventory:count:export", "inventory:doc:export");
        List<Map<String, Object>> reset = new ArrayList<>();
        reset.add(resetDuringSecondStreamingPage(resetActor, "/api/stocks/export?warehouseId=" + f.warehouse(), f.company(), "from inv_stock s"));
        reset.add(resetDuringSecondStreamingPage(resetActor, "/api/stocks/summary/export?warehouseId=" + f.warehouse(), f.company(), "from inv_stock s"));
        reset.add(resetDuringSecondStreamingPage(resetActor, "/api/inventory-counts/" + count + "/sheet", f.company(), "from inv_count_item"));
        reset.add(resetDuringSecondStreamingPage(resetActor, "/api/inventory-counts/export?keyword=" + prefix, f.company(), "select c.id, c.company_id, c.no"));
        reset.add(resetDuringSecondStreamingPage(resetActor, "/api/inventory-docs/export?kind=IN&ids=" + first + "," + second,
                f.company(), "select * from inv_doc_item"));
        // 沿用旧接口在成功生成后、发送前的审计语义：RST 及之后成功重试各记一次。
        assertThat(sheetAudits(count, resetActor)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='INV_COUNT' AND doc_id=? AND action='export-sheet'", Integer.class, count)).isEqualTo(3);
        write("inventory-pages.json", map("tcpReset", reset, "http", "actual PostgreSQL and HTTP, no response mocks", "countRows", 1203,
                "stockRows", 1203, "documentRows", 1202, "initialAuditRows", audit, "auditRowsIncludingResetAndRetry", 3,
                "maxMapperRows", maxRows(), "queries", probe.results));
    }

    @Test void countSheetRejectsInvalidPlansAndAuditFailuresBeforeCommittingDownloadHeaders() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "inventory:count:export");
        String prefix = "P37A" + suffix();
        long count = jdbc.queryForObject("""
                INSERT INTO inv_count(no,company_id,warehouse_id,purpose,scope,count_date,counter_id,create_by,status)
                VALUES(?,?,?,'DAILY','PICKED',CURRENT_DATE,?,?,'DRAFT') RETURNING id
                """, Long.class, prefix, f.company(), f.warehouse(), actor.id(), actor.id());
        String path = "/api/inventory-counts/" + count + "/sheet";
        try {
            assertThat(jdbc.update("""
                    INSERT INTO bas_material_sku(material_id,code,color_id,spec_code)
                    SELECT ?,?||lpad(n::text,5,'0'),?,'S'||n FROM generate_series(1,50001)n
                    """, f.material(), prefix, f.color())).isEqualTo(50001);
            assertThat(jdbc.update("""
                    INSERT INTO inv_count_item(count_id,seq,material_sku_id,book_qty,counted_qty)
                    SELECT ?,row_number() OVER(ORDER BY code),id,1,1
                    FROM bas_material_sku WHERE material_id=? AND code LIKE ?
                    """, count, f.material(), prefix + "%")).isEqualTo(50001);
            probe.clear();
            var invalid = raw(actor, "GET", path, null, f.company(), null);
            assertJsonWithoutDownload(invalid, 1000);
            assertThat(node(invalid).path("msg").stringValue()).contains("盘点表", "50000");
            assertThat(sheetAudits(count, actor)).isZero();
            assertThat(probe.results.stream().filter(r -> r.sql().contains("bas_material_sku"))).isEmpty();

            assertThat(deleteCountRowsAfter(count, 1)).isEqualTo(50000);
            String function = "p37_count_audit_fail_" + suffix();
            jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.doc_type='INV_COUNT' AND NEW.doc_id=" + count
                    + " AND NEW.operator_id=" + actor.id() + " AND NEW.action='export-sheet' THEN RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
            HttpResponse<byte[]> failed;
            try {
                jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON biz_doc_log FOR EACH ROW EXECUTE FUNCTION " + function + "()");
                failed = raw(actor, "GET", path, null, f.company(), null);
                assertJsonWithoutDownload(failed, 500);
                assertThat(node(failed).toString()).doesNotContain("synthetic audit failure", "biz_doc_log", "SQLException");
                assertThat(sheetAudits(count, actor)).isZero();
            } finally {
                try { jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON biz_doc_log"); }
                finally { jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()"); }
            }
            try (Workbook workbook = workbook(raw(actor, "GET", path, null, f.company(), null))) {
                assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            }
            assertThat(sheetAudits(count, actor)).isEqualTo(1);
            write("count-audit-failures.json", map("http", "actual PostgreSQL and HTTP, no response mocks",
                    "invalidPlan", node(invalid), "invalidPlanRows", 50001, "invalidPlanAuditRows", 0,
                    "auditFailure", node(failed), "auditFailureDownloadHeader", failed.headers().firstValue("Content-Disposition").isPresent(),
                    "auditFailureAuditRows", 0, "retryAuditRows", 1));
        } finally {
            deleteCountRowsAfter(count, 0);
            while (jdbc.update("DELETE FROM bas_material_sku WHERE id IN (SELECT id FROM bas_material_sku WHERE material_id=? AND code LIKE ? ORDER BY id LIMIT 1000)",
                    f.material(), prefix + "%") != 0) { /* 每批限制外键触发器工作量。 */ }
        }
    }

    @Test void finishedGoodsMatrixStreamsAcrossColorAndSkuPagesWithoutMixingCompaniesOrDroppingDisabledAxes() throws Exception {
        Fixture f = fixture();
        Account actor = custom(f.company(), "ALL", "fg:stock:export", "fg:doc:export");
        String prefix = "P37G" + suffix();
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,?,'PRODUCT') RETURNING id", Long.class, prefix, prefix);
        List<Long> colors = jdbc.queryForList("""
                INSERT INTO bas_color(code,name)
                SELECT code,?||code FROM (
                    SELECT chr(65+a)||chr(65+b)||chr(65+c) code FROM generate_series(0,25)a,
                        generate_series(0,25)b,generate_series(0,25)c
                    WHERE NOT EXISTS(SELECT 1 FROM bas_color old WHERE old.code=chr(65+a)||chr(65+b)||chr(65+c))
                    ORDER BY 1 LIMIT 600) candidate RETURNING id
                """, Long.class, prefix);
        assertThat(colors).hasSize(600);
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,2,'L'),(?,3,'XL')", f.sizeGroup(), f.sizeGroup());
        jdbc.update("""
                INSERT INTO sty_style_color(style_id,color_id,seq,status)
                SELECT ?,id,1+row_number() OVER(ORDER BY id),0 FROM bas_color WHERE name LIKE ?
                """, f.style(), prefix + "%");
        jdbc.update("""
                INSERT INTO sty_style_sku(style_id,code,color_id,size_code,status)
                SELECT ?,?||'-'||c.id||'-'||z.size,c.id,z.size,0
                FROM sty_style_color sc JOIN bas_color c ON c.id=sc.color_id CROSS JOIN (VALUES('M'),('L'),('XL'))z(size)
                WHERE sc.style_id=? AND NOT EXISTS(SELECT 1 FROM sty_style_sku k WHERE k.style_id=? AND k.color_id=c.id AND k.size_code=z.size)
                """, f.style(), prefix, f.style(), f.style());
        jdbc.update("""
                INSERT INTO fg_stock(company_id,warehouse_id,style_sku_id,qty)
                SELECT ?,?,id,CASE size_code WHEN 'M' THEN 1 WHEN 'L' THEN 2 ELSE 3 END FROM sty_style_sku WHERE style_id=?
                """, f.company(), warehouse, f.style());
        jdbc.update("INSERT INTO fg_stock(company_id,warehouse_id,style_sku_id,qty) VALUES(?,?,?,999)", f.otherCompany(), warehouse, f.styleSku());
        long doc = jdbc.queryForObject("INSERT INTO fg_doc(no,kind,doc_type,status,company_id,to_warehouse_id,doc_date,reason,create_by) VALUES(?,'IN','OTHER_IN','DRAFT',?,?,CURRENT_DATE,'OTHER',?) RETURNING id",
                Long.class, prefix + "DOC", f.company(), warehouse, actor.id());
        jdbc.update("""
                INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty)
                SELECT ?,row_number() OVER(ORDER BY id),id,CASE size_code WHEN 'M' THEN 1 WHEN 'L' THEN 2 ELSE 3 END
                FROM sty_style_sku WHERE style_id=?
                """, doc, f.style());
        jdbc.update("UPDATE fg_doc SET status='APPROVED',version=1,audit_by=?,audit_time=now() WHERE id=?", actor.id(), doc);
        jdbc.update("""
                INSERT INTO fg_stock_log(company_id,fg_doc_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,change_qty,balance_qty,create_by)
                SELECT d.company_id,d.id,d.doc_type,d.no,d.doc_date,d.to_warehouse_id,i.style_sku_id,i.qty,i.qty,d.create_by
                FROM fg_doc d JOIN fg_doc_item i ON i.doc_id=d.id WHERE d.id=?
                """, doc);
        var expectedColors = jdbc.queryForList("SELECT c.name FROM sty_style_color sc JOIN bas_color c ON c.id=sc.color_id WHERE sc.style_id=? ORDER BY sc.seq,sc.id", String.class, f.style());
        probe.clear();
        var response = raw(actor, "GET", "/api/fg-stocks/by-style/export?companyId=" + f.company() + "&styleId=" + f.style(), null, f.company(), null);
        try (Workbook workbook = workbook(response)) {
            Sheet sheet = workbook.getSheetAt(0); var h = headers(sheet);
            assertThat(h).containsExactly("款号", "款名", "颜色", "M", "L", "XL", "合计");
            assertThat(sheet.getLastRowNum()).isEqualTo(601);
            for (int row = 1; row <= 601; row++) {
                assertThat(sheet.getRow(row).getCell(2).getStringCellValue()).isEqualTo(expectedColors.get(row - 1));
                assertThat(sheet.getRow(row).getCell(3).getNumericCellValue()).isEqualTo(1);
                assertThat(sheet.getRow(row).getCell(4).getNumericCellValue()).isEqualTo(2);
                assertThat(sheet.getRow(row).getCell(5).getNumericCellValue()).isEqualTo(3);
                assertThat(sheet.getRow(row).getCell(6).getNumericCellValue()).isEqualTo(6);
            }
        }
        var detailResponse = raw(actor, "GET", "/api/fg-stocks/export?companyId=" + f.company() + "&styleId=" + f.style(), null, f.company(), null);
        try (Workbook workbook = workbook(detailResponse)) { assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1803); }
        assertBounded();
        assertThat(probe.results.stream().filter(r -> r.sql().contains("from sty_style_color") && r.rows() == 500).count()).isGreaterThanOrEqualTo(2);
        List<Map<String, Object>> reset = new ArrayList<>();
        reset.add(resetDuringSecondStreamingPage(actor, "/api/fg-stocks/by-style/export?companyId=" + f.company() + "&styleId=" + f.style(),
                f.company(), "from sty_style_color sc join sty_style_sku k"));
        reset.add(resetDuringSecondStreamingPage(actor, "/api/fg-stocks/export?companyId=" + f.company() + "&styleId=" + f.style(),
                f.company(), "from fg_stock s"));
        reset.add(resetDuringSecondStreamingPage(actor, "/api/fg-stock-logs/export?companyId=" + f.company() + "&styleId=" + f.style(),
                f.company(), "from fg_stock_log l"));
        reset.add(resetDuringSecondStreamingPage(actor, "/api/fg-docs/export?kind=IN&companyId=" + f.company() + "&ids=" + doc,
                f.company(), "select * from fg_doc_item"));
        write("fg-matrix-pages.json", map("tcpReset", reset, "http", "actual PostgreSQL and HTTP, no response mocks", "matrixColors", 601,
                "matrixSizes", List.of("M", "L", "XL"), "detailSkus", 1803, "otherCompanyQtyExcluded", 999,
                "disabledAxesRetained", true, "maxMapperRows", maxRows(), "queries", probe.results));
    }

    private Map<String, Object> resetDuringSecondStreamingPage(Account actor, String path, long company, String sqlFragment) throws Exception {
        CancelControl control = new CancelControl(UUID.randomUUID().toString(), sqlFragment);
        probe.cancel = control;
        try {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + actor.token()
                        + "\r\nX-Company-Id: " + company + "\r\nX-P37-Reset: " + control.key + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                var headers = new java.io.ByteArrayOutputStream();
                int tail = 0;
                while (tail != 0x0d0a0d0a && headers.size() < 32768) {
                    int value = socket.getInputStream().read(); assertThat(value).isNotNegative();
                    headers.write(value); tail = (tail << 8) | value;
                }
                assertThat(tail).isEqualTo(0x0d0a0d0a);
                assertThat(headers.toString(StandardCharsets.ISO_8859_1)).contains("200", "Content-Disposition:");
                assertThat(socket.getInputStream().read()).isNotNegative();
                assertThat(control.blocked.await(15, TimeUnit.SECONDS)).as("second committed-response database page reached").isTrue();
                socket.setSoLinger(true, 0);
            } finally { control.resume.countDown(); }
            assertThat(control.finished.await(15, TimeUnit.SECONDS)).as("actual servlet request finished after TCP reset").isTrue();
            assertThat(control.streamingQueries).hasValue(2);
            try (Workbook retry = workbook(raw(actor, "GET", path, null, company, null))) {
                assertThat(retry.getNumberOfSheets()).isPositive();
            }
            return map("path", path.substring(0, path.indexOf('?') < 0 ? path.length() : path.indexOf('?')), "transport", "real Socket SO_LINGER=0", "streamingDatabaseBatches", control.streamingQueries.get(),
                    "requestFinallyObserved", true, "followingRequestSucceeded", true);
        } finally { control.resume.countDown(); probe.cancel = null; }
    }

    private long document(Fixture f, Account actor, String no) {
        return jdbc.queryForObject("INSERT INTO inv_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,create_by) VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,?) RETURNING id", Long.class, no, f.company(), f.warehouse(), actor.id());
    }
    private Workbook workbook(HttpResponse<byte[]> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("filename*=UTF-8''"));
        assertThat(response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
        return WorkbookFactory.create(new ByteArrayInputStream(response.body()));
    }
    private static List<String> headers(Sheet sheet) { List<String> headers = new ArrayList<>(); sheet.getRow(0).forEach(c -> headers.add(c.getStringCellValue())); return headers; }
    private int sheetAudits(long count, Account actor) {
        return jdbc.queryForObject("SELECT count(*) FROM biz_doc_log WHERE doc_type='INV_COUNT' AND doc_id=? AND action='export-sheet' AND operator_id=?",
                Integer.class, count, actor.id());
    }
    private int deleteCountRowsAfter(long count, int seq) {
        int total = 0;
        for (int removed; (removed = jdbc.update("DELETE FROM inv_count_item WHERE id IN (SELECT id FROM inv_count_item WHERE count_id=? AND seq>? ORDER BY id LIMIT 1000)",
                count, seq)) != 0; ) total += removed;
        return total;
    }
    private void assertJsonWithoutDownload(HttpResponse<byte[]> response, int code) {
        assertRejected(response, 200, code);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.headers().firstValue("X-Accel-Buffering")).isEmpty();
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotStartWith("PK");
    }
    private int maxRows() { return probe.results.stream().mapToInt(QueryResult::rows).max().orElse(0); }
    private void assertBounded() { assertThat(probe.results).isNotEmpty(); assertThat(maxRows()).isLessThanOrEqualTo(500); }
    private void write(String name, Object value) throws Exception { Path path = Path.of("test-artifacts/full-audit/s2/p37-streaming-inventory", name); Files.createDirectories(path.getParent()); Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    record QueryResult(String sql, int rows) { }
    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean QueryProbe inventoryExportQueryProbe() { return new QueryProbe(); }
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> inventoryExportCompletion(QueryProbe probe) {
            return new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new org.springframework.web.filter.OncePerRequestFilter() {
                @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
                        throws java.io.IOException, jakarta.servlet.ServletException {
                    CancelControl active = probe.cancel;
                    boolean target = active != null && active.key.equals(request.getHeader("X-P37-Reset"));
                    try { chain.doFilter(request, response); }
                    finally { if (target) active.finished.countDown(); }
                }
            });
        }
    }
    static final class CancelControl {
        final String key, sqlFragment;
        final AtomicInteger streamingQueries = new AtomicInteger();
        final CountDownLatch blocked = new CountDownLatch(1), resume = new CountDownLatch(1), finished = new CountDownLatch(1);
        CancelControl(String key, String sqlFragment) { this.key = key; this.sqlFragment = sqlFragment; }
    }
    @Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
    static final class QueryProbe implements Interceptor {
        final List<QueryResult> results = new CopyOnWriteArrayList<>();
        volatile CancelControl cancel;
        void clear() { results.clear(); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String sql = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql().replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            CancelControl active = cancel;
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (active != null && sql.contains(active.sqlFragment)
                    && attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                    && active.key.equals(servlet.getRequest().getHeader("X-P37-Reset"))
                    && servlet.getResponse() != null && servlet.getResponse().isCommitted()) {
                if (active.streamingQueries.incrementAndGet() == 2) {
                    active.blocked.countDown();
                    if (!active.resume.await(20, TimeUnit.SECONDS)) throw new AssertionError("client TCP reset barrier timed out");
                }
            }
            Object result = invocation.proceed();
            if (result instanceof List<?> rows && List.of("inv_count_item", "inv_stock", "inv_doc_item", "bas_material_sku", "sty_style_color", "sty_style_sku", "bas_size_group_item", "fg_stock").stream().anyMatch(sql::contains))
                results.add(new QueryResult(sql, rows.size()));
            return result;
        }
    }
}
