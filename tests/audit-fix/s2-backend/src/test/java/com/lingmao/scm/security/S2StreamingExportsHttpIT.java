package com.lingmao.scm.security;

import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.basic.entity.MaterialSku;
import com.lingmao.scm.module.basic.mapper.MaterialSkuMapper;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleColor;
import com.lingmao.scm.module.style.entity.StyleSku;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.filter.OncePerRequestFilter;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual Tomcat TCP + PostgreSQL: no mocked HTTP responses or mapper results. */
@Import(S2StreamingExportsHttpIT.Probes.class)
class S2StreamingExportsHttpIT extends A2BusinessFixture {
    @Autowired QueryProbe probe;
    private static final String PRODUCTION = "ProductionOrderMapper.selectRowPage";
    private static final String PURCHASE_ITEMS = "PurchaseOrderItemMapper.selectExportBatch";
    private static final String MATERIAL = "MaterialMapper.selectByIds";
    private static final String MATERIAL_SKU = "MaterialSkuMapper.selectByIds";
    private static final String MATERIAL_SKU_ORDER = "MaterialSkuMapper.selectExportOrderPage";

    @AfterEach void releaseBarriers() { probe.states.values().forEach(s -> s.resume.countDown()); probe.states.clear(); }

    @Test void tcpResetStopsTheNextProductionPageAndReleasesThePermit() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "ALL", "production:order:export");
        String prefix = "STREAMPRD" + suffix(); long order = sourceOrder(f, actor, "APPROVED", f.company());
        int inserted = jdbc.update("""
                INSERT INTO prd_order(no,company_id,kind,factory_id,process_mode,order_id,style_id,tech_pack_id,
                                      order_date,due_date,unit_price,tax_pct,total_qty,total_amount,merch_id,
                                      settle_method,status,issue_status,create_by)
                SELECT ? || '-' || n,?,'OUTSOURCE',?,'CMT',?,?,?,CURRENT_DATE,CURRENT_DATE+30,38.31,13,2,76.62,
                       ?,'MONTHLY','DRAFT','NONE',? FROM generate_series(1,1201) n
                """, prefix, f.company(), f.supplier(), order, f.style(), f.pack(), actor.id(), actor.id());
        assertThat(inserted).isEqualTo(1201);
        String url = "/api/production-orders/export?kind=OUTSOURCE&keyword=" + prefix;
        try {
            State state = probe.add(PRODUCTION, true);
            resetDownload(actor, url, state, "xl/worksheets/sheet1.xml");
            assertThat(state.streamCount(PRODUCTION)).isEqualTo(1);
            assertThat(state.validationCount(PRODUCTION)).isEqualTo(3);
            assertPagesAndSnapshot(state);
            var retry = download(actor, url, probe.add(null, true));
            assertThat(retry.statusCode()).isEqualTo(200);
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(retry.body()))) {
                assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1201);
            }
            evidence("production-reset", map("insertedRows", inserted, "transport", "real TCP RST", "request", state.snapshot(), "retryRows", 1201));
        } finally {
            jdbc.update("DELETE FROM prd_order WHERE no LIKE ?", prefix + "-%");
            jdbc.update("DELETE FROM ord_order_item WHERE order_id=?", order); jdbc.update("DELETE FROM ord_order WHERE id=?", order);
        }
    }

    @Test void resetInEitherMaterialSheetStopsFurtherQueries() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "ALL", "basic:material:export");
        String prefix = "STRSKU" + suffix();
        jdbc.update("""
                INSERT INTO bas_material_sku(material_id,code,color_id,spec_code,price)
                SELECT ?,? || '-' || n,?,'S' || n,13.37 FROM generate_series(1,1200) n
                """, f.material(), prefix, f.color());
        try {
            String url = "/api/materials/export?ids=" + f.material();
            State first = probe.add(MATERIAL, true), second = probe.add(MATERIAL_SKU, true);
            resetDownload(actor, url, first, "xl/worksheets/sheet1.xml");
            assertThat(first.streamCount(MATERIAL)).isEqualTo(1);
            assertThat(first.streamCount(MATERIAL_SKU)).isZero();
            resetDownload(actor, url, second, "xl/worksheets/sheet2.xml");
            assertThat(second.streamCount(MATERIAL_SKU)).isEqualTo(1);
            assertPagesAndSnapshot(first); assertPagesAndSnapshot(second);
            evidence("material-two-sheet-reset", map("transport", "real TCP RST", "firstSheet", first.snapshot(), "secondSheet", second.snapshot()));
        } finally { jdbc.update("DELETE FROM bas_material_sku WHERE material_id=? AND code LIKE ?", f.material(), prefix + "-%"); }
    }

    @Test void materialSkuOrderingPreservesJavaUtf16AcrossThePageBoundary() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "ALL", "basic:material:export");
        String prefix = "STRSORT" + suffix();
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit)
                VALUES(?,'Synthetic UTF-16 export ordering',?,'FABRIC','METER','METER') RETURNING id
                """, Long.class, prefix, f.materialCategory());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 497; i++) expected.add(prefix + "-" + String.format(java.util.Locale.ROOT, "%04d", i));
        expected.addAll(List.of(prefix + "-P-1.2", prefix + "-P-11", prefix + "-P-\uE000", prefix + "-P-😀"));
        for (int i = 0; i < 100; i++) expected.add(prefix + "-Z" + String.format(java.util.Locale.ROOT, "%04d", i));
        expected.sort(String::compareTo);
        assertThat(expected.get(499)).endsWith("😀");
        assertThat(expected.get(500)).endsWith("\uE000");
        List<String> reversed = new ArrayList<>(expected); java.util.Collections.reverse(reversed);
        try {
            // Reverse the ID order too: sorting a single metadata page cannot accidentally satisfy this fixture.
            for (int from = 0; from < reversed.size(); from += 500) {
                List<Object[]> inserts = new ArrayList<>();
                for (int i = from; i < Math.min(reversed.size(), from + 500); i++)
                    inserts.add(new Object[]{material, reversed.get(i), f.color(), "SORT" + i});
                int[] inserted = jdbc.batchUpdate("INSERT INTO bas_material_sku(material_id,code,color_id,spec_code) VALUES(?,?,?,?)", inserts);
                for (int changed : inserted) assertThat(changed).isIn(1, Statement.SUCCESS_NO_INFO);
            }
            State state = probe.add(null, false);
            var response = download(actor, "/api/materials/export?ids=" + material, state);
            assertThat(response.statusCode()).isEqualTo(200);
            List<String> actual = new ArrayList<>();
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
                assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
                var sheet = book.getSheetAt(1); var headers = new LinkedHashMap<String, Integer>();
                sheet.getRow(0).forEach(cell -> headers.put(cell.getStringCellValue(), cell.getColumnIndex()));
                assertThat(sheet.getLastRowNum()).isEqualTo(601);
                for (int row = 1; row <= sheet.getLastRowNum(); row++)
                    actual.add(sheet.getRow(row).getCell(headers.get("物料SKU编码")).getStringCellValue());
            }
            assertThat(actual).containsExactlyElementsOf(expected);
            assertPagesAndSnapshot(state);
            assertThat(state.maxPage.get()).isEqualTo(500);
            assertThat(state.validationCount(MATERIAL_SKU_ORDER)).isEqualTo(2);
            assertThat(state.streamCount(MATERIAL_SKU_ORDER)).isZero();
            assertThat(state.validationCount(MATERIAL_SKU)).isEqualTo(2);
            assertThat(state.streamCount(MATERIAL_SKU)).isEqualTo(2);
            evidence("material-utf16-ordering", map("request", state.snapshot(), "rows", actual.size(),
                    "verifiedOrder", "parent order then Java String.compareTo, all 601 rows exactly once",
                    "row498", actual.get(497), "row499", actual.get(498), "row500", actual.get(499), "row501", actual.get(500)));
        } finally {
            jdbc.update("DELETE FROM bas_material_sku WHERE material_id=?", material);
            jdbc.update("DELETE FROM bas_material WHERE id=?", material);
        }
    }

    @Test void supplierOrderAndStyleExportsStopAtTheirFirstStreamedMainPage() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "ALL", "supplier:supplier:export", "order:order:export", "style:style:export");
        String prefix = "STRMATRIX" + suffix();
        assertThat(jdbc.update("""
                INSERT INTO sup_supplier(code,name,short_name,status)
                SELECT ? || '-' || n,? || '-' || n,'Stream supplier','ACTIVE' FROM generate_series(1,1201) n
                """, prefix, prefix)).isEqualTo(1201);
        assertThat(jdbc.update("""
                INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id,status,total_qty,total_amount,create_by)
                SELECT ? || '-' || n,?,'STOCK',CURRENT_DATE,CURRENT_DATE+30,?,?,'DRAFT',0,0,? FROM generate_series(1,1201) n
                """, prefix, f.company(), actor.id(), actor.id(), actor.id())).isEqualTo(1201);
        assertThat(jdbc.update("""
                INSERT INTO sty_style(code,name,brand,style_class,category_id,year,season,source,size_group_id,tag_price,ref_cost,status)
                SELECT ? || '-' || n,? || '-' || n,'A2','FS',?,2026,'AUTUMN','OWN',?,299,456.78,'ENABLED'
                FROM generate_series(1,1201) n
                """, prefix, prefix, f.category(), f.sizeGroup())).isEqualTo(1201);
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (String[] target : List.of(new String[]{"/api/suppliers/export", "SupplierMapper.selectByIds"},
                    new String[]{"/api/orders/export", "OrderMapper.selectRowPage"},
                    new String[]{"/api/styles/export", "StyleMapper.selectByIds"})) {
                State state = probe.add(target[1], true);
                resetDownload(actor, target[0] + "?keyword=" + prefix, state, "xl/worksheets/sheet1.xml");
                assertThat(state.streamCount(target[1])).as(target[0]).isEqualTo(1);
                assertThat(state.maxPage.get()).isEqualTo(500);
                assertPagesAndSnapshot(state);
                results.add(map("path", target[0], "insertedRows", 1201, "request", state.snapshot()));
            }
            evidence("supplier-order-style-reset", map("transport", "real TCP RST", "requests", results));
        } finally {
            jdbc.update("DELETE FROM ord_order WHERE no LIKE ?", prefix + "-%");
            jdbc.update("DELETE FROM sty_style WHERE code LIKE ?", prefix + "-%");
            jdbc.update("DELETE FROM sup_supplier WHERE code LIKE ?", prefix + "-%");
        }
    }

    @Test void tcpResetStopsPurchaseDetailsBeforeTheSecondBatch() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "ALL", "purchase:order:export");
        long purchase = purchase(f, actor, 1200);
        try {
            State state = probe.add(PURCHASE_ITEMS, true);
            resetDownload(actor, "/api/purchase-orders/export?ids=" + purchase, state, "xl/worksheets/sheet1.xml");
            assertThat(state.validationCount(PURCHASE_ITEMS)).isEqualTo(3);
            assertThat(state.streamCount(PURCHASE_ITEMS)).isEqualTo(1);
            assertPagesAndSnapshot(state);
            evidence("purchase-reset", map("transport", "real TCP RST", "insertedDetailRows", 1200,
                    "request", state.snapshot(), "remainingRowsNotFetchedAfterReset", 700));
        } finally { deletePurchase(purchase); }
    }

    @Test void concurrentEditsAfterValidationDoNotChangeThePurchaseDownloadSnapshot() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "ALL", "purchase:order:export");
        long purchase = purchase(f, actor, 1200);
        String originalName = jdbc.queryForObject("SELECT name FROM bas_material WHERE id=?", String.class, f.material());
        State state = probe.add(PURCHASE_ITEMS, true);
        var pending = http.sendAsync(request(actor, "/api/purchase-orders/export?ids=" + purchase, state), HttpResponse.BodyHandlers.ofByteArray());
        try {
            assertThat(state.blocked.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(state.validationCount(PURCHASE_ITEMS)).isEqualTo(3);
            jdbc.update("UPDATE pur_order_item SET qty=9,follow_note='after validation' WHERE purchase_order_id=? AND seq=1200", purchase);
            jdbc.update("UPDATE bas_material SET name='after validation' WHERE id=?", f.material());
            state.resume.countDown();
            var response = pending.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("filename*=UTF-8''");
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
                var sheet = book.getSheetAt(0); var headers = new LinkedHashMap<String, Integer>();
                sheet.getRow(0).forEach(cell -> headers.put(cell.getStringCellValue(), cell.getColumnIndex()));
                assertThat(sheet.getLastRowNum()).isEqualTo(1200);
                assertThat(sheet.getRow(1200).getCell(headers.get("数量")).getNumericCellValue()).isEqualTo(1);
                assertThat(sheet.getRow(1200).getCell(headers.get("物料名称")).getStringCellValue()).isEqualTo(originalName);
                assertThat(sheet.getRow(1200).getCell(headers.get("跟进备注")).getStringCellValue()).isEqualTo("before validation");
                assertThat(headers).doesNotContainKeys("单价", "金额", "改价原因");
            }
            assertThat(jdbc.queryForObject("SELECT qty FROM pur_order_item WHERE purchase_order_id=? AND seq=1200", Integer.class, purchase)).isEqualTo(9);
            assertPagesAndSnapshot(state);
            evidence("purchase-snapshot", map("request", state.snapshot(), "workbookRows", 1200,
                    "downloadLastQuantity", 1, "databaseLastQuantity", 9, "financialColumns", false));
        } finally {
            state.resume.countDown(); jdbc.update("UPDATE bas_material SET name=? WHERE id=?", originalName, f.material());
            deletePurchase(purchase);
        }
    }

    @Test void oneAndTwoFiftyThousandRowDownloadsStayBoundedAndAThirdIsRejectedBeforeQueries() throws Exception {
        Fixture f = fixture(); Account actor = custom(f.company(), "ALL", "purchase:order:export");
        long purchase = purchase(f, actor, 50_000); String url = "/api/purchase-orders/export?ids=" + purchase;
        Path directory = Path.of("target/a2-exports/streaming"); Files.createDirectories(directory);
        List<Path> files = new ArrayList<>();
        try {
            Path single = directory.resolve("single-" + suffix() + ".xlsx"); files.add(single);
            State one = probe.add(null, false); Map<String, Object> singleHeap;
            try (HeapProbe heap = new HeapProbe()) {
                var response = http.send(request(actor, url, one), HttpResponse.BodyHandlers.ofFile(single));
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("spreadsheetml.sheet");
                singleHeap = heap.snapshot();
            }
            assertThat(purchaseRowsInSequence(single)).isEqualTo(50_000); assertPagesAndSnapshot(one);
            assertThat(one.streamCount(PURCHASE_ITEMS)).isEqualTo(101);
            State left = probe.add(PURCHASE_ITEMS, false), right = probe.add(PURCHASE_ITEMS, false), rejected = probe.add(null, false);
            Path a = directory.resolve("parallel-a-" + suffix() + ".xlsx"), b = directory.resolve("parallel-b-" + suffix() + ".xlsx");
            files.add(a); files.add(b);
            Map<String, Object> doubleHeap;
            try (HeapProbe heap = new HeapProbe()) {
                var first = http.sendAsync(request(actor, url, left), HttpResponse.BodyHandlers.ofFile(a));
                var second = http.sendAsync(request(actor, url, right), HttpResponse.BodyHandlers.ofFile(b));
                try {
                    assertThat(left.blocked.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(right.blocked.await(15, TimeUnit.SECONDS)).isTrue();
                    var denied = download(actor, url, rejected);
                    assertThat(denied.statusCode()).isEqualTo(429); assertThat(node(denied).path("code").intValue()).isEqualTo(429);
                    assertThat(rejected.finished.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(rejected.queryCount.get()).isZero();
                } finally { left.resume.countDown(); right.resume.countDown(); }
                assertThat(first.get(90, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(second.get(90, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                doubleHeap = heap.snapshot();
            }
            assertThat(purchaseRowsInSequence(a)).isEqualTo(50_000); assertThat(purchaseRowsInSequence(b)).isEqualTo(50_000);
            assertPagesAndSnapshot(left); assertPagesAndSnapshot(right);
            assertThat(left.streamCount(PURCHASE_ITEMS)).isEqualTo(101); assertThat(right.streamCount(PURCHASE_ITEMS)).isEqualTo(101);
            // Small follow-up proves both permits and the database transaction were released after successful completion.
            var followup = download(actor, "/api/purchase-orders/export?ids=" + purchase + "&keyword=NO_MATCH_" + suffix(), probe.add(null, false));
            assertThat(node(followup).path("code").intValue()).isEqualTo(404);
            evidence("purchase-capacity", map("rowsPerDownload", 50_000, "verifiedSequence", "行号 1..50000, in order and exactly once", "clientBodyStorage", "files (not byte arrays)",
                    "singleHeap", singleHeap, "doubleHeap", doubleHeap, "single", one.snapshot(),
                    "parallelA", left.snapshot(), "parallelB", right.snapshot(), "rejected", rejected.snapshot(), "followupCode", 404));
        } finally { files.forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }); deletePurchase(purchase); }
    }

    private long purchase(Fixture f, Account actor, int count) {
        long id = jdbc.queryForObject("""
                INSERT INTO pur_order(no,company_id,supplier_id,purchase_type,buyer_id,order_date,delivery_date,
                                      warehouse_id,settle_method,tax_pct,status,create_by)
                VALUES(?,?,?,'STOCK',?,CURRENT_DATE,CURRENT_DATE+30,?,'MONTHLY',13,'DRAFT',?) RETURNING id
                """, Long.class, "STRPUR" + suffix(), f.company(), f.supplier(), actor.id(), f.warehouse(), actor.id());
        for (int from = 1; from <= count; from += 2500) {
            int to = Math.min(from + 2499, count);
            assertThat(jdbc.update("""
                    INSERT INTO pur_order_item(purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount,follow_note)
                    SELECT ?,n,?,'METER',1,1,2.34,2.34,'before validation' FROM generate_series(?,?) n
                    """, id, f.materialSku(), from, to)).isEqualTo(to - from + 1);
        }
        return id;
    }
    private void deletePurchase(long id) {
        while (jdbc.update("DELETE FROM pur_order_item WHERE id IN (SELECT id FROM pur_order_item WHERE purchase_order_id=? ORDER BY id LIMIT 2500)", id) > 0) { }
        jdbc.update("DELETE FROM pur_order WHERE id=?", id);
    }
    private HttpRequest request(Account actor, String path, State state) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + actor.token()).header("X-Export-Test", state.id).GET().build();
    }
    private HttpResponse<byte[]> download(Account actor, String path, State state) throws Exception {
        return http.send(request(actor, path, state), HttpResponse.BodyHandlers.ofByteArray());
    }
    private void resetDownload(Account actor, String path, State state, String zipEntry) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + actor.token()
                    + "\r\nX-Export-Test: " + state.id + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            var input = socket.getInputStream(); var headers = new ByteArrayOutputStream(); int tail = 0;
            while (tail != 0x0d0a0d0a && headers.size() < 32_768) { int value = input.read(); assertThat(value).isNotNegative(); headers.write(value); tail = (tail << 8) | value; }
            assertThat(headers.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 200").contains("filename*=UTF-8''");
            byte[] needle = zipEntry.getBytes(StandardCharsets.US_ASCII); int matched = 0, received = 0;
            while (matched < needle.length && received++ < 1_048_576) {
                int value = input.read(); assertThat(value).isNotNegative(); matched = value == needle[matched] ? matched + 1 : value == needle[0] ? 1 : 0;
            }
            assertThat(matched).isEqualTo(needle.length);
            assertThat(state.blocked.await(15, TimeUnit.SECONDS)).isTrue();
            socket.setSoLinger(true, 0);
        } finally { state.resume.countDown(); }
        assertThat(state.finished.await(15, TimeUnit.SECONDS)).isTrue();
    }
    private void assertPagesAndSnapshot(State state) throws InterruptedException {
        assertThat(state.finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(state.maxPage.get()).isBetween(1, 500);
        assertThat(state.isolations).containsExactly(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(state.readOnlyFailures.get()).isZero();
    }
    private static int purchaseRowsInSequence(Path path) throws Exception {
        var factory = SAXParserFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        AtomicInteger count = new AtomicInteger();
        try (var zip = new ZipFile(path.toFile()); var input = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {
            factory.newSAXParser().parse(input, new DefaultHandler() {
                int physicalRow, sequenceCells;
                String cell, sequenceColumn;
                boolean capture;
                final StringBuilder value = new StringBuilder();
                @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                    if ("row".equals(qName)) {
                        physicalRow++; sequenceCells = 0;
                        assertThat(attributes.getValue("r")).as("consecutive worksheet row").isEqualTo(Integer.toString(physicalRow));
                    } else if ("c".equals(qName)) {
                        cell = attributes.getValue("r"); value.setLength(0);
                    } else if ("t".equals(qName) || "v".equals(qName)) capture = true;
                }
                @Override public void characters(char[] chars, int start, int length) {
                    if (capture) value.append(chars, start, length);
                }
                @Override public void endElement(String uri, String local, String qName) {
                    if ("t".equals(qName) || "v".equals(qName)) capture = false;
                    else if ("c".equals(qName)) {
                        if (physicalRow == 1 && "行号".contentEquals(value)) {
                            assertThat(sequenceColumn).as("single 行号 header").isNull();
                            sequenceColumn = cell.replaceFirst("[0-9]+$", "");
                        } else if (physicalRow > 1 && cell.equals(sequenceColumn + physicalRow)) {
                            assertThat(value.toString()).as("purchase detail sequence at worksheet row %s", physicalRow)
                                    .isEqualTo(Integer.toString(physicalRow - 1));
                            sequenceCells++;
                        }
                    } else if ("row".equals(qName)) {
                        assertThat(sequenceColumn).as("行号 column exists").isNotNull();
                        if (physicalRow > 1) {
                            assertThat(sequenceCells).as("one detail sequence per worksheet row").isEqualTo(1);
                            count.incrementAndGet();
                        }
                    }
                }
            });
        }
        return count.get();
    }
    private void evidence(String name, Object value) throws IOException {
        Path output = Path.of("target/a2-exports/streaming/" + name + ".json"); Files.createDirectories(output.getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    static final class HeapProbe implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        private final long baseline;
        private final AtomicLong peak = new AtomicLong();
        HeapProbe() {
            ManagementFactory.getMemoryMXBean().gc(); baseline = used(); peak.set(baseline);
            sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(used(), Math::max), 0, 5, TimeUnit.MILLISECONDS);
        }
        private static long used() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
        Map<String, Object> snapshot() {
            peak.accumulateAndGet(used(), Math::max);
            assertThat(peak.get()).as("measured whole-process heap ceiling for this capacity fixture").isLessThan(600L * 1024 * 1024);
            return map("baselineBytes", baseline, "peakBytes", peak.get(), "peakIncreaseBytes", peak.get() - baseline,
                    "maxHeapBytes", Runtime.getRuntime().maxMemory(), "samplingIntervalMillis", 5,
                    "scope", "entire integration-test JVM, including Tomcat, PostgreSQL client and file-streaming HTTP clients");
        }
        @Override public void close() { sampler.shutdownNow(); }
    }
    static final class State {
        final String id = java.util.UUID.randomUUID().toString(); final String stopAt; final boolean stopWhileStreaming;
        final CountDownLatch blocked = new CountDownLatch(1), resume = new CountDownLatch(1), finished = new CountDownLatch(1);
        final AtomicInteger queryCount = new AtomicInteger(), maxPage = new AtomicInteger(), readOnlyFailures = new AtomicInteger();
        final Map<String, AtomicInteger> validation = new ConcurrentHashMap<>(), streaming = new ConcurrentHashMap<>();
        final Set<Integer> isolations = ConcurrentHashMap.newKeySet(); volatile HttpServletResponse response;
        State(String stopAt, boolean stopWhileStreaming) { this.stopAt = stopAt; this.stopWhileStreaming = stopWhileStreaming; }
        int streamCount(String suffix) { return count(streaming, suffix); }
        int validationCount(String suffix) { return count(validation, suffix); }
        private static int count(Map<String, AtomicInteger> queries, String suffix) { return queries.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).mapToInt(e -> e.getValue().get()).sum(); }
        Map<String, Object> snapshot() { return map("queryScope", "read-only export transaction (authentication excluded)",
                "queries", queryCount.get(), "maxMaterializedPageRows", maxPage.get(),
                "validationQueries", values(validation), "streamingQueries", values(streaming), "transactionIsolations", isolations,
                "readOnlyFailures", readOnlyFailures.get(), "finished", finished.getCount() == 0); }
        private static Map<String, Integer> values(Map<String, AtomicInteger> source) { Map<String, Integer> values = new java.util.TreeMap<>(); source.forEach((k, v) -> values.put(k, v.get())); return values; }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean QueryProbe streamingExportQueries() { return new QueryProbe(); }
        @Bean FilterRegistrationBean<OncePerRequestFilter> streamingExportRequestProbe(QueryProbe probe) {
            var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                    String trace = request.getHeader("X-Export-Test");
                    State state = trace == null ? null : probe.states.get(trace);
                    if (state == null) { chain.doFilter(request, response); return; }
                    state.response = response; probe.active.set(state);
                    try { chain.doFilter(request, response); } finally { probe.active.remove(); state.finished.countDown(); }
                }
            });
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }
    }
    @Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
    static final class QueryProbe implements Interceptor {
        final Map<String, State> states = new ConcurrentHashMap<>(); final ThreadLocal<State> active = new ThreadLocal<>();
        State add(String stopAt, boolean streaming) { State state = new State(stopAt, streaming); states.put(state.id, state); return state; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            State state = active.get(); if (state == null) return invocation.proceed();
            var handler = (StatementHandler) invocation.getTarget();
            MappedStatement mapped = com.baomidou.mybatisplus.core.toolkit.PluginUtils.mpStatementHandler(handler).mappedStatement();
            String id = mapped.getId(); boolean streaming = state.response.isCommitted();
            Object answer = invocation.proceed();
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return answer;
            state.queryCount.incrementAndGet();
            var counts = streaming ? state.streaming : state.validation; int sequence = counts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            if (answer instanceof List<?> rows && !rows.isEmpty() && isPage(rows.getFirst())) {
                state.maxPage.accumulateAndGet(rows.size(), Math::max);
                Connection connection = ((Statement) invocation.getArgs()[0]).getConnection();
                state.isolations.add(connection.getTransactionIsolation()); if (!connection.isReadOnly()) state.readOnlyFailures.incrementAndGet();
            }
            if (state.stopAt != null && id.endsWith(state.stopAt) && streaming == state.stopWhileStreaming && sequence == 1) {
                state.blocked.countDown(); if (!state.resume.await(20, TimeUnit.SECONDS)) throw new AssertionError("export test barrier timed out");
            }
            return answer;
        }
        private static boolean isPage(Object row) {
            return row instanceof ProductionRowVO || row instanceof PurchaseRowVO || row instanceof PurchaseOrderItem
                    || row instanceof OrderRowVO || row instanceof Material || row instanceof MaterialSku
                    || row instanceof MaterialSkuMapper.ExportOrder
                    || row instanceof Style || row instanceof StyleColor || row instanceof StyleSku
                    || row instanceof Supplier supplier && supplier.getCode() != null;
        }
    }
}
