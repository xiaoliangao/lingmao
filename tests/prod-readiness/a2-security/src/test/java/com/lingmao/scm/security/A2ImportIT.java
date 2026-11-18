package com.lingmao.scm.security;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.inventory.dto.CountCreateReq;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import com.lingmao.scm.module.inventory.service.CountService;
import com.lingmao.scm.module.order.dto.OrderImportHead;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.service.SupplierExcelService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class A2ImportIT extends A2BusinessFixture {
    @Autowired MaterialService materials;
    @Autowired StyleService styles;
    @Autowired SupplierExcelService suppliers;
    @Autowired OrderService orders;
    @Autowired CountService counts;
    @Autowired com.lingmao.scm.module.system.cache.DictCache dictionaries;
    private final List<String> brands = new ArrayList<>();

    @AfterEach void removeOnlyFixtureBrands() {
        for (String brand : brands) jdbc.update("DELETE FROM sys_dict_data WHERE type_code='brand' AND label=?", brand);
        if (!brands.isEmpty()) dictionaries.evictAfterCommit("brand");
    }

    @Test void allFiveRealImportsReadAndCloseFilesOutsideTransactionsAndCommitSuccessfulReceipts() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company()); authenticate(admin);
        TrackingFile material = materialFile(f, f.materialCategoryCode() + "0002", "new import material");
        assertThat(materials.importExcel(material).total()).isEqualTo(1); material.assertOutside();
        String brand = "A2 import brand " + suffix(); brands.add(brand);
        TrackingFile style = file("款式", new Object[][] {
                {"款号", "款式名称", "大类", "品牌", "品类", "年份", "季节", "尺码组", "颜色"},
                {"A2IM" + suffix(), "new import style", "服饰", brand, f.categoryName(), 2026, "秋", f.sizeGroupName(), f.colorCode()}});
        assertThat(styles.importExcel(style).total()).isEqualTo(1); style.assertOutside();
        TrackingFile supplier = file("供应商管理", new Object[][] {
                {"编码", "全称", "简称", "分类"}, {"A2IS" + suffix(), "new supplier " + suffix(), "new supplier", "面料商"}});
        assertThat(suppliers.importExcel(supplier, SupplierStatus.PENDING_ADMIT).total()).isEqualTo(1); supplier.assertOutside();
        OrderImportHead head = new OrderImportHead(); head.setCompanyId(f.company()); head.setOrderType("STOCK"); head.setRequiredDate(today().plusDays(30));
        TrackingFile order = file("需求订单", new Object[][] {
                {"款号", "颜色", "尺码", "数量", "单价"}, {f.styleCode(), f.colorCode(), "M", 3, 89.12}});
        assertThat(orders.importExcel(head, order).total()).isEqualTo(1); order.assertOutside();
        long count = opening(f, admin);
        TrackingFile sheet = file("盘点表", new Object[][] {{"SKU 码", "实盘数", "备注"}, {f.materialSkuCode(), 5, "counted"}});
        assertThat(counts.importSheet(count, sheet).total()).isEqualTo(1); sheet.assertOutside();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_import_receipt WHERE user_id=?", Long.class, admin.id())).isEqualTo(5);
        assertThat(materials.importExcel(material).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material WHERE code=?", Long.class, f.materialCategoryCode() + "0002")).isEqualTo(1);
    }

    @Test void actualMultipartHashReplayAcrossTwoHttpClientsCreatesOnlyOneBatchAndReportsExactBadCell() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company());
        byte[] bytes = materialFile(f, f.materialCategoryCode() + "0003", "concurrent import").getBytes();
        try (var second = HttpClient.newHttpClient(); var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var first = pool.submit(() -> { ready.countDown(); start.await(); return multipart(http, admin, bytes); });
            var other = pool.submit(() -> { ready.countDown(); start.await(); return multipart(second, admin, bytes); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (var pending : List.of(first, other)) assertThat(pending.get(15, TimeUnit.SECONDS).path("code").intValue()).isIn(0, 409);
            JsonNode replay = multipart(second, admin, bytes);
            assertThat(replay.path("code").intValue()).isZero(); assertThat(replay.path("data").path("total").intValue()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material WHERE code=?", Long.class, f.materialCategoryCode() + "0003")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_import_receipt WHERE user_id=?", Long.class, admin.id())).isEqualTo(1);
        }
        byte[] invalid = workbook("物料信息", new Object[][] {
                {"物料类型", "物料分类", "物料编码", "物料来源", "物料名称", "单位"},
                {"面料", f.materialPath(), f.materialCategoryCode() + "0004", "自购", "valid first row", "米"},
                {"面料", f.materialPath(), f.materialCategoryCode() + "0005", "自购", "invalid second row", "unrecognised-unit"}});
        JsonNode rejected = multipart(http, admin, invalid);
        assertThat(rejected.path("code").intValue()).isEqualTo(1003);
        assertThat(rejected.path("data").get(0).path("row").intValue()).isEqualTo(3);
        assertThat(rejected.path("data").get(0).path("column").stringValue()).isEqualTo("单位");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material WHERE code IN (?,?)", Long.class,
                f.materialCategoryCode() + "0004", f.materialCategoryCode() + "0005")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_import_receipt WHERE user_id=?", Long.class, admin.id())).isEqualTo(1);
        byte[] formula = workbook("物料信息", new Object[][] {
                {"物料类型", "物料分类", "物料编码", "物料来源", "物料名称", "单位"},
                {"面料", f.materialPath(), f.materialCategoryCode() + "0008", "自购", "=CMD|' /C calc'!A0", "米"}});
        JsonNode unsafe = multipart(http, admin, formula);
        assertThat(unsafe.path("code").intValue()).isEqualTo(1003);
        assertThat(unsafe.path("data").get(0).path("row").intValue()).isEqualTo(2);
        assertThat(unsafe.path("data").get(0).path("column").stringValue()).isEqualTo("物料名称");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material WHERE code=?", Long.class, f.materialCategoryCode() + "0008")).isZero();
    }

    @Test void staleRequestRightsCannotAuthorizeFinancialColumnsAndMissingLoginNeverStartsParsing() throws Exception {
        Fixture f = fixture(); Account importer = custom(f.company(), "ALL", "basic:material:import", FieldPermissions.COST);
        authenticate(importer);
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=? AND menu_id IN(SELECT id FROM sys_menu WHERE perms=?)", importer.role(), FieldPermissions.COST);
        TrackingFile priced = file("物料信息", new Object[][] {
                {"物料类型", "物料分类", "物料编码", "物料来源", "物料名称", "单位", "成本价"},
                {"面料", f.materialPath(), f.materialCategoryCode() + "0009", "自购", "priced import", "米", 42.42}});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> materials.importExcel(priced)).isInstanceOf(FieldPermissionDenied.class);
        assertThat(FieldPermissions.has(FieldPermissions.COST)).isTrue(); // Temporary current-rights context restored on failure.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material WHERE code=?", Long.class, f.materialCategoryCode() + "0009")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_import_receipt WHERE user_id=?", Long.class, importer.id())).isZero();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        TrackingFile anonymous = materialFile(f, f.materialCategoryCode() + "0010", "anonymous import");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> materials.importExcel(anonymous)).isInstanceOf(FieldPermissionDenied.class);
        assertThat(anonymous.opens).hasValue(0);
    }

    @Test void dataScopeRevokedDuringRealCountParsingIsRecheckedInsideTheWritePhase() throws Exception {
        Fixture f = fixture(); Account owner = role("admin", f.company()); authenticate(owner);
        long count = opening(f, owner);
        Account importer = custom(f.company(), "ALL", "inventory:count:import", "inventory:count:list");
        TrackingFile file = file("盘点表", new Object[][] {{"SKU 码", "实盘数"}, {f.materialSkuCode(), 7}}); file.blockParser = true;
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> {
                authenticate(importer);
                try { counts.importSheet(count, file); return 0; }
                catch (com.lingmao.scm.common.exception.BizException rejected) {
                    assertThat(LoginUser.hasAuthority("data:all")).isTrue(); // Fresh temporary context was restored.
                    return rejected.getCode();
                } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(file.parsing.await(10, TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE sys_role SET data_scope='SELF' WHERE id=?", importer.role());
            } finally { file.resume.countDown(); }
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM inv_count WHERE id=?", Integer.class, count)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inv_count_item WHERE count_id=? AND counted_qty IS NOT NULL", Long.class, count)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_import_receipt WHERE user_id=?", Long.class, importer.id())).isZero();
        file.assertOutside();
    }

    @Test void aDatabaseFailureInTheLastRowRollsBackEarlierRowsAndTheSuccessReceipt() throws Exception {
        Fixture f = fixture(); Account admin = role("admin", f.company()); authenticate(admin);
        String marker = "a2_fail_" + suffix(), function = marker + "_fn";
        // Real PostgreSQL failure after the first valid insert, not a mocked write callback.
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.name='" + marker
                + "' THEN RAISE EXCEPTION 'injected import write failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + marker + " BEFORE INSERT ON bas_material FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            TrackingFile file = file("物料信息", new Object[][] {
                    {"物料类型", "物料分类", "物料编码", "物料来源", "物料名称", "单位"},
                    {"面料", f.materialPath(), f.materialCategoryCode() + "0006", "自购", "first valid insert", "米"},
                    {"面料", f.materialPath(), f.materialCategoryCode() + "0007", "自购", marker, "米"}});
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> materials.importExcel(file)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            file.assertOutside();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material WHERE code IN (?,?)", Long.class,
                    f.materialCategoryCode() + "0006", f.materialCategoryCode() + "0007")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_import_receipt WHERE user_id=?", Long.class, admin.id())).isZero();
        } finally { jdbc.execute("DROP TRIGGER " + marker + " ON bas_material"); jdbc.execute("DROP FUNCTION " + function + "()"); }
    }

    private long opening(Fixture f, Account user) {
        CountCreateReq req = new CountCreateReq(); req.setCompanyId(f.company()); req.setWarehouseId(f.warehouse());
        req.setPurpose(CountPurpose.OPENING); req.setScope(CountScope.PICKED); req.setSkuIds(List.of(f.materialSku()));
        req.setCountDate(today()); req.setCounterId(user.id()); return counts.create(req).id();
    }
    private TrackingFile materialFile(Fixture f, String code, String name) throws IOException {
        return file("物料信息", new Object[][] {{"物料类型", "物料分类", "物料编码", "物料来源", "物料名称", "单位"},
                {"面料", f.materialPath(), code, "自购", name, "米"}});
    }
    private JsonNode multipart(HttpClient client, Account actor, byte[] bytes) throws Exception {
        String boundary = "a2boundary" + suffix(); ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"import.xlsx\"\r\nContent-Type: " + Excel.CONTENT_TYPE + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/materials/import"))
                .header("Authorization", "Bearer " + actor.token()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return node(client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }
    static TrackingFile file(String sheet, Object[][] rows) throws IOException { return new TrackingFile(workbook(sheet, rows)); }
    static byte[] workbook(String sheet, Object[][] rows) throws IOException {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var page = book.createSheet(sheet);
            for (int r = 0; r < rows.length; r++) for (int c = 0; c < rows[r].length; c++) {
                var row = page.getRow(r); if (row == null) row = page.createRow(r); var cell = row.createCell(c); Object value = rows[r][c];
                if (value instanceof Number number) cell.setCellValue(number.doubleValue()); else if (value != null) cell.setCellValue(value.toString());
            }
            book.write(bytes); return bytes.toByteArray();
        }
    }
    static final class TrackingFile extends MockMultipartFile {
        final AtomicInteger opens = new AtomicInteger(), reads = new AtomicInteger(), closes = new AtomicInteger();
        final CountDownLatch parsing = new CountDownLatch(1), resume = new CountDownLatch(1);
        volatile boolean blockParser;
        TrackingFile(byte[] data) { super("file", "import.xlsx", Excel.CONTENT_TYPE, data); }
        @Override public InputStream getInputStream() throws IOException {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); int opened = opens.incrementAndGet();
            return new FilterInputStream(super.getInputStream()) {
                boolean waited;
                private void check() throws IOException {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); reads.incrementAndGet();
                    if (blockParser && opened == 2 && !waited) {
                        waited = true; parsing.countDown();
                        try { if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("Parser barrier timed out"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                    }
                }
                @Override public int read() throws IOException { check(); return in.read(); }
                @Override public int read(byte[] b, int offset, int size) throws IOException { check(); return in.read(b, offset, size); }
                @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
            };
        }
        void assertOutside() { assertThat(opens.get()).isGreaterThanOrEqualTo(2); assertThat(reads.get()).isPositive(); assertThat(closes.get()).isEqualTo(opens.get()); }
    }
}
