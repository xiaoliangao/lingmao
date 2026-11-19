package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.common.jobs.OutboxHandler;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.system.service.impl.OutboxDispatcher;
import com.lingmao.scm.module.system.service.impl.OutboxStore;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"lingmao.jobs.enabled=false"})
@ActiveProfiles("it")
@Import({IntegrationTestConfig.class, A2ExportHttpIT.Probes.class})
class A2ExportHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired OutboxStore outbox;
    @Autowired List<OutboxHandler> handlers;
    @Autowired QueryProbe probe;
    @Autowired com.lingmao.scm.module.system.service.ExportFileStore fileStore;
    @Autowired ExportJobServiceImpl jobs;
    private static final String PASSWORD = "Export-A2-Pass-123";
    private record Account(long id, long role, String token) {}
    @AfterEach void reset() { probe.clear(); }

    @Test void asyncBusinessJobWritesAValidPrivateWorkbookWithSnapshotColumnsAndChecksOwnership() throws Exception {
        long warehouse = fixture(1203);
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, false), other = account(http, false);
            String key = UUID.randomUUID().toString();
            long job = create(http, owner.token(), warehouse, key);
            assertThat(create(http, owner.token(), warehouse, key)).isEqualTo(job);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_export_job WHERE id=?", Long.class, job)).isEqualTo(1);
            var foreign = send(http, "GET", "/api/export-jobs/" + job, "", other.token(), null);
            assertThat(json.readTree(foreign.body()).path("code").intValue()).isEqualTo(404);
            OutboxDispatcher worker = worker();
            try {
                JsonNode done = await(http, owner.token(), job, "SUCCEEDED");
                assertThat(done.path("rows").longValue()).isEqualTo(1203);
                assertThat(done.path("requestId").stringValue()).isNotBlank();
                var download = download(http, owner.token(), job);
                assertThat(download.statusCode()).isEqualTo(200);
                assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains(".xlsx"));
                try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(download.body()))) {
                    var sheet = workbook.getSheetAt(0); assertThat(sheet.getLastRowNum()).isEqualTo(1203);
                    var headers = new java.util.ArrayList<String>(); sheet.getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
                    assertThat(headers).contains("变动", "结存", "物料名称").doesNotContain("单价", "金额");
                    int nameColumn = headers.indexOf("物料名称");
                    assertThat(sheet.getRow(1).getCell(nameColumn).getStringCellValue()).isEqualTo("'=test-export");
                    assertThat(sheet.getRow(1).getCell(nameColumn).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                }
                assertThat(download(http, other.token(), job).headers().firstValue("Content-Disposition")).isEmpty();
            } finally { worker.stop(); }
        }
    }

    @Test void permissionRevocationPreventsDownloadingOrRetryingAnExistingFinancialFile() throws Exception {
        long warehouse = fixture(2);
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, true); long job = create(http, owner.token(), warehouse, UUID.randomUUID().toString());
            OutboxDispatcher worker = worker();
            try {
                await(http, owner.token(), job, "SUCCEEDED");
                var download = download(http, owner.token(), job);
                try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(download.body()))) {
                    var headers = new java.util.ArrayList<String>(); workbook.getSheetAt(0).getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
                    assertThat(headers).contains("单价", "金额");
                }
                jdbc.update("DELETE FROM sys_role_menu WHERE role_id=? AND menu_id IN (SELECT id FROM sys_menu WHERE perms='techpack:cost:query')", owner.role());
                assertThat(download(http, owner.token(), job).statusCode()).isEqualTo(403);
                assertThat(send(http, "POST", "/api/export-jobs/" + job + "/retry", "{}", owner.token(), UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
            } finally { worker.stop(); }
        }
    }

    @Test void pendingCancellationAndRetryUseRealHttpAndTheUnfinishedQuotaIsTwo() throws Exception {
        long warehouse = fixture(1);
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, false);
            long first = create(http, owner.token(), warehouse, UUID.randomUUID().toString());
            long second = create(http, owner.token(), warehouse, UUID.randomUUID().toString());
            var third = send(http, "POST", "/api/export-jobs/stock-logs", json.writeValueAsString(Map.of("warehouseId", warehouse)), owner.token(), UUID.randomUUID().toString());
            assertThat(json.readTree(third.body()).path("code").intValue()).isEqualTo(429);
            var cancel = send(http, "POST", "/api/export-jobs/" + first + "/cancel", "{}", owner.token(), UUID.randomUUID().toString());
            assertThat(json.readTree(cancel.body()).path("data").path("status").stringValue()).isEqualTo("CANCELLED");
            send(http, "POST", "/api/export-jobs/" + second + "/cancel", "{}", owner.token(), UUID.randomUUID().toString());
            String retryKey = UUID.randomUUID().toString();
            assertThat(send(http, "POST", "/api/export-jobs/" + first + "/retry", "{}", owner.token(), retryKey).statusCode()).isEqualTo(202);
            assertThat(send(http, "POST", "/api/export-jobs/" + first + "/retry", "{}", owner.token(), retryKey).statusCode()).isEqualTo(202);
            OutboxDispatcher worker = worker();
            try { await(http, owner.token(), first, "SUCCEEDED"); assertThat(status(http, owner.token(), second).path("status").stringValue()).isEqualTo("CANCELLED"); }
            finally { worker.stop(); }
        }
    }

    @Test void disabledAndDeletedOwnersCannotBeExportedByTheBackgroundWorker() throws Exception {
        long warehouse = fixture(1);
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account disabled = account(http, false), deleted = account(http, false);
            long first = create(http, disabled.token(), warehouse, UUID.randomUUID().toString());
            long second = create(http, deleted.token(), warehouse, UUID.randomUUID().toString());
            jdbc.update("UPDATE sys_user SET status=0 WHERE id=?", disabled.id());
            jdbc.update("UPDATE sys_user SET deleted=TRUE WHERE id=?", deleted.id());
            OutboxDispatcher worker = worker();
            try {
                for (long id : List.of(first, second)) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    String state;
                    do {
                        state = jdbc.queryForObject("SELECT o.status FROM sys_outbox o JOIN sys_export_job j ON j.outbox_id=o.id WHERE j.id=?", String.class, id);
                        if (state.equals("DEAD_LETTER")) break;
                        Thread.sleep(25);
                    } while (System.nanoTime() < deadline);
                    assertThat(state).isEqualTo("DEAD_LETTER");
                    assertThat(jdbc.queryForObject("SELECT o.error_code FROM sys_outbox o JOIN sys_export_job j ON j.outbox_id=o.id WHERE j.id=?", String.class, id)).isEqualTo("PERMISSION_REVOKED");
                    assertThat(jdbc.queryForObject("SELECT file_token FROM sys_export_job WHERE id=?", UUID.class, id)).isNull();
                }
                assertThat(download(http, disabled.token(), first).statusCode()).isEqualTo(401);
                assertThat(download(http, deleted.token(), second).statusCode()).isEqualTo(401);
            } finally { worker.stop(); }
        }
    }

    @Test void aMissingFileBecomesRetryableAndTheSameJobPublishesANewFile() throws Exception {
        long warehouse = fixture(2);
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, false); long job = create(http, owner.token(), warehouse, UUID.randomUUID().toString());
            OutboxDispatcher worker = worker();
            try {
                await(http, owner.token(), job, "SUCCEEDED");
                UUID first = jdbc.queryForObject("SELECT file_token FROM sys_export_job WHERE id=?", UUID.class, job);
                fileStore.delete(job, first);
                assertThat(json.readTree(download(http, owner.token(), job).body()).path("code").intValue()).isEqualTo(410);
                assertThat(status(http, owner.token(), job).path("status").stringValue()).isEqualTo("EXPIRED");
                assertThat(status(http, owner.token(), job).path("errorCode").stringValue()).isEqualTo("FILE_MISSING");
                assertThat(send(http, "POST", "/api/export-jobs/" + job + "/retry", "{}", owner.token(), UUID.randomUUID().toString()).statusCode()).isEqualTo(202);
                await(http, owner.token(), job, "SUCCEEDED");
                UUID next = jdbc.queryForObject("SELECT file_token FROM sys_export_job WHERE id=?", UUID.class, job);
                assertThat(next).isNotEqualTo(first);
                assertThat(download(http, owner.token(), job).statusCode()).isEqualTo(200);
            } finally { worker.stop(); }
        }
    }

    @Test void staleExpirySnapshotCannotExpireOrDeleteASuccessfullyRetriedGeneration() throws Exception {
        long warehouse = fixture(2);
        try (HttpClient http = HttpClient.newHttpClient(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            Account owner = account(http, false); long job = create(http, owner.token(), warehouse, UUID.randomUUID().toString());
            OutboxDispatcher worker = worker();
            try {
                await(http, owner.token(), job, "SUCCEEDED");
                UUID first = jdbc.queryForObject("SELECT file_token FROM sys_export_job WHERE id=?", UUID.class, job);
                jdbc.update("UPDATE sys_export_job SET expires_at=clock_timestamp()-interval '1 minute' WHERE id=?", job);
                probe.blockExpiry = true;
                var cleaning = executor.submit(jobs::run);
                assertThat(probe.expiryRead.await(10, TimeUnit.SECONDS)).isTrue();
                // The real PostgreSQL cleanup query has read the old token and is paused before its CAS.
                assertThat(send(http, "POST", "/api/export-jobs/" + job + "/retry", "{}", owner.token(), UUID.randomUUID().toString()).statusCode()).isEqualTo(202);
                await(http, owner.token(), job, "SUCCEEDED");
                UUID next = jdbc.queryForObject("SELECT file_token FROM sys_export_job WHERE id=?", UUID.class, job);
                assertThat(next).isNotEqualTo(first);
                probe.resumeExpiry.countDown(); cleaning.get(10, TimeUnit.SECONDS);
                assertThat(status(http, owner.token(), job).path("status").stringValue()).isEqualTo("SUCCEEDED");
                assertThat(jdbc.queryForObject("SELECT file_token FROM sys_export_job WHERE id=?", UUID.class, job)).isEqualTo(next);
                assertThat(java.nio.file.Files.exists(fileStore.path(job, next))).isTrue();
                assertThat(java.nio.file.Files.exists(fileStore.path(job, first))).isFalse();
            } finally { probe.resumeExpiry.countDown(); worker.stop(); }
        }
    }

    @Test void syncThresholdIsCheckedBeforeResponseHeadersAndOnlyOneBoundedSeekIsRun() throws Exception {
        long warehouse = fixture(10_001); probe.target = warehouse;
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, false);
            var response = send(http, "GET", "/api/stock-logs/export?warehouseId=" + warehouse, "", owner.token(), null);
            assertThat(json.readTree(response.body()).path("code").intValue()).isEqualTo(1000);
            assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
            assertThat(probe.preflight).hasValue(1); assertThat(probe.batches).hasValue(0);
        }
    }

    @Test void cancellingAnActiveExportStopsItsNextBatchAndDeletesThePartialFile() throws Exception {
        long warehouse = fixture(5000); probe.target = warehouse; probe.blockSecond = true;
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, false); long job = create(http, owner.token(), warehouse, UUID.randomUUID().toString());
            OutboxDispatcher worker = worker();
            try {
                assertThat(probe.secondPrepared.await(10, TimeUnit.SECONDS)).isTrue();
                var cancelled = send(http, "POST", "/api/export-jobs/" + job + "/cancel", "{}", owner.token(), UUID.randomUUID().toString());
                assertThat(json.readTree(cancelled.body()).path("data").path("status").stringValue()).isEqualTo("PROCESSING");
                probe.disconnected.countDown(); await(http, owner.token(), job, "CANCELLED");
                assertThat(probe.batches.get()).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT file_token FROM sys_export_job WHERE id=?", UUID.class, job)).isNull();
                try (var files = java.nio.file.Files.list(fileStore.path(job, UUID.randomUUID()).getParent())) {
                    assertThat(files.filter(p -> p.getFileName().toString().startsWith("single-" + job + "-")).toList()).isEmpty();
                }
            } finally { probe.disconnected.countDown(); worker.stop(); }
        }
    }

    @Test void processAdmissionRejectsAThirdHeavyRequestBeforeParsingAndReturnsPermitsAfterCompletion() throws Exception {
        long warehouse = fixture(2); probe.target = warehouse; probe.blockEveryBatch = true;
        try (HttpClient http = HttpClient.newHttpClient(); var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            Account owner = account(http, false), other = account(http, false);
            var first = executor.submit(() -> send(http, "GET", "/api/stock-logs/export?warehouseId=" + warehouse, "", owner.token(), null));
            var second = executor.submit(() -> send(http, "GET", "/api/stock-logs/export?warehouseId=" + warehouse, "", other.token(), null));
            try {
                assertThat(probe.twoRequests.await(10, TimeUnit.SECONDS)).isTrue();
                // Invalid bodies would fail later. Saturated admission responds before either parser or controller.
                for (String route : List.of("/api/materials/import", "/api/files", "/api/tech-packs/1/bom-import", "/api/tech-packs/1/files/2/preview", "/api/stocks/export")) {
                    var response = send(http, route.endsWith("import") || route.equals("/api/files") ? "POST" : "GET", route, "", owner.token(), null);
                    assertThat(response.statusCode()).isEqualTo(429);
                    assertThat(json.readTree(response.body()).path("code").intValue()).isEqualTo(429);
                    assertThat(response.headers().firstValue("Retry-After")).contains("3");
                    assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
                }
                // Spring maps HEAD to the same GET controller, including its expensive generation.
                for (String route : List.of("/api/tech-packs/1/files/2/preview", "/api/stocks/export", "/api/inventory-counts/1/sheet")) {
                    var response = send(http, "HEAD", route, "", owner.token(), null);
                    assertThat(response.statusCode()).isEqualTo(429);
                    assertThat(response.headers().firstValue("Retry-After")).contains("3");
                    assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
                }
            } finally { probe.disconnected.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(send(http, "GET", "/api/stock-logs/export?warehouseId=" + warehouse, "", owner.token(), null).statusCode()).isEqualTo(200);
        }
    }

    @Test void headDownloadsConsumeTheSameSharedUserQuotaAsGet() throws Exception {
        long warehouse = fixture(2);
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, false), other = account(http, false);
            String path = "/api/stock-logs/export?warehouseId=" + warehouse;
            for (int i = 0; i < 12; i++) {
                var response = send(http, "HEAD", path, "", owner.token(), null);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEmpty();
            }
            var limited = send(http, "HEAD", path, "", owner.token(), null);
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
            assertThat(send(http, "GET", path, "", owner.token(), null).statusCode()).isEqualTo(429);
            assertThat(send(http, "HEAD", path, "", other.token(), null).statusCode()).isEqualTo(200);
        }
    }

    @Test void realTcpResetStopsTheStreamingProducerBeforeItsNextDatabaseBatch() throws Exception {
        long warehouse = fixture(9000); probe.target = warehouse; probe.blockSecond = true;
        try (HttpClient http = HttpClient.newHttpClient()) {
            Account owner = account(http, false);
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10000);
                String request = "GET /api/stock-logs/export?warehouseId=" + warehouse + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + owner.token() + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
                int tail = 0, bytes = 0;
                while (tail != 0x0d0a0d0a && ++bytes < 32768) { int b = socket.getInputStream().read(); assertThat(b).isNotNegative(); tail = (tail << 8) | b; }
                assertThat(socket.getInputStream().read()).isNotNegative();
                socket.setSoLinger(true, 0);
            } finally { probe.disconnected.countDown(); }
            assertThat(probe.finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.preflight).hasValue(1);
            assertThat(probe.batches.get()).isBetween(1, 2);
            // The filter has observed the real request end; there is no detached export producer left fetching rows.
        }
    }

    private OutboxDispatcher worker() { var worker = new OutboxDispatcher(outbox, handlers, List.of(), true); worker.start(); return worker; }
    private long fixture(int rows) {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long parent = jdbc.queryForObject("INSERT INTO bas_material_category(name,level) VALUES(?,1) RETURNING id", Long.class, "A2 export parent " + code);
        long category = jdbc.queryForObject("""
                INSERT INTO bas_material_category(code,name,level,parent_id)
                SELECT chr(65+a)||chr(65+b),?,2,? FROM generate_series(0,25) a,generate_series(0,25) b
                WHERE NOT EXISTS (SELECT 1 FROM bas_material_category WHERE code=chr(65+a)||chr(65+b))
                ORDER BY a,b LIMIT 1 RETURNING id
                """, Long.class, "A2 export child " + code, parent);
        long material = jdbc.queryForObject("INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit) VALUES(?,'=test-export',?,'FABRIC','PIECE','PIECE') RETURNING id", Long.class, code, category);
        long sku = jdbc.queryForObject("INSERT INTO bas_material_sku(material_id,code) VALUES(?,?) RETURNING id", Long.class, material, code);
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,?,'MATERIAL') RETURNING id", Long.class, code, "A2 export warehouse");
        jdbc.update("""
                INSERT INTO inv_stock_log(change_type,doc_no,biz_date,warehouse_id,material_sku_id,change_qty,balance_qty,dye_lot,unit_price,amount,stock_unit,currency,valuation_status)
                SELECT 'OTHER_IN',substr(md5(n::text),1,32),current_date,?,?,1,n,md5((n+999)::text),12.34,12.34,'PIECE','CNY','RECORDED'
                FROM generate_series(1,?) AS n
                """, warehouse, sku, rows);
        return warehouse;
    }
    private Account account(HttpClient http, boolean cost) throws Exception {
        String name = "a2e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long role = jdbc.queryForObject("INSERT INTO sys_role(code,name,data_scope) VALUES(?,?,'ALL') RETURNING id", Long.class, name, name);
        for (String permission : cost ? List.of("inventory:stock:export", "inventory:stock:list", "techpack:cost:query") : List.of("inventory:stock:export", "inventory:stock:list"))
            jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) SELECT ?,id FROM sys_menu WHERE perms=? AND NOT deleted", role, permission);
        long id = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES(?,?,?) RETURNING id", Long.class, name, passwords.encode(PASSWORD), name);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id, role);
        var response = send(http, "POST", "/api/auth/login", json.writeValueAsString(Map.of("username", name, "password", PASSWORD)), null, null);
        assertThat(json.readTree(response.body()).path("code").intValue()).isZero();
        return new Account(id, role, json.readTree(response.body()).path("data").stringValue());
    }
    private long create(HttpClient http, String token, long warehouse, String key) throws Exception {
        var response = send(http, "POST", "/api/export-jobs/stock-logs", json.writeValueAsString(Map.of("warehouseId", warehouse)), token, key);
        assertThat(response.statusCode()).isEqualTo(202); var node = json.readTree(response.body());
        assertThat(node.path("code").intValue()).isZero(); var id = node.path("data").path("id");
        return id.isString() ? Long.parseLong(id.stringValue()) : id.longValue();
    }
    private JsonNode status(HttpClient http, String token, long id) throws Exception {
        var response = send(http, "GET", "/api/export-jobs/" + id, "", token, null);
        var node = json.readTree(response.body()); assertThat(node.path("code").intValue()).isZero(); return node.path("data");
    }
    private JsonNode await(HttpClient http, String token, long id, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        JsonNode last;
        do { last = status(http, token, id); if (expected.equals(last.path("status").stringValue())) return last; Thread.sleep(50); }
        while (System.nanoTime() < deadline);
        throw new AssertionError("Job did not reach " + expected + "; status=" + last.path("status") + "; error=" + last.path("errorCode"));
    }
    private HttpResponse<byte[]> download(HttpClient http, String token, long id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/export-jobs/" + id + "/file")).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private String base() { return "http://127.0.0.1:" + port; }
    private HttpResponse<String> send(HttpClient http, String method, String path, String body, String token, String key) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json");
        if (token != null) req.header("Authorization", "Bearer " + token); if (key != null) req.header("Idempotency-Key", key);
        return http.send(req.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean QueryProbe exportQueryProbe() { return new QueryProbe(); }
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor exportJdbcProbe() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof javax.sql.DataSource source)) return bean;
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (p, method, args) -> {
                        Object result = invoke(source, method, args);
                        return result instanceof Connection connection ? connection(connection) : result;
                    });
                }
                private Connection connection(Connection delegate) {
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
                        Object result = invoke(delegate, method, args);
                        if (!(result instanceof java.sql.PreparedStatement statement) || args == null || args.length == 0 || !(args[0] instanceof String sql)) return result;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class}, (s, call, params) -> {
                            Object answer = invoke(statement, call, params);
                            QueryProbe current = QueryProbe.active;
                            if (current != null && current.blockExpiry && call.getName().equals("executeQuery")
                                    && sql.contains("expires_at<clock_timestamp() AND file_token IS NOT NULL ORDER BY expires_at LIMIT 128")) {
                                current.expiryRead.countDown();
                                if (!current.resumeExpiry.await(15, TimeUnit.SECONDS)) throw new java.sql.SQLException("Expiry race barrier timed out");
                            }
                            return answer;
                        });
                    });
                }
                private Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
                    try { return method.invoke(target, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                }
            };
        }
        @Bean FilterRegistrationBean<OncePerRequestFilter> exportCompletionProbe(QueryProbe probe) {
            return new FilterRegistrationBean<>(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res, jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    boolean target = req.getRequestURI().equals("/api/stock-logs/export") && Long.toString(probe.target).equals(req.getParameter("warehouseId"));
                    try { chain.doFilter(req, res); }
                    finally { if (target) probe.finished.countDown(); }
                }
            });
        }
    }
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static final class QueryProbe implements Interceptor {
        static volatile QueryProbe active;
        QueryProbe() { active = this; }
        volatile boolean blockExpiry;
        volatile CountDownLatch expiryRead = new CountDownLatch(1), resumeExpiry = new CountDownLatch(1);
        volatile long target = -1; volatile boolean blockSecond, blockEveryBatch;
        volatile CountDownLatch twoRequests = new CountDownLatch(2);
        final AtomicInteger preflight = new AtomicInteger(), batches = new AtomicInteger();
        volatile CountDownLatch disconnected = new CountDownLatch(1), finished = new CountDownLatch(1), secondPrepared = new CountDownLatch(1);
        void clear() { target = -1; blockSecond = false; blockEveryBatch = false; blockExpiry = false; preflight.set(0); batches.set(0); disconnected.countDown(); resumeExpiry.countDown(); disconnected = new CountDownLatch(1); finished = new CountDownLatch(1); secondPrepared = new CountDownLatch(1); expiryRead = new CountDownLatch(1); resumeExpiry = new CountDownLatch(1); twoRequests = new CountDownLatch(2); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var bound = ((StatementHandler) invocation.getTarget()).getBoundSql();
            if (bound.getSql().contains("FROM inv_stock_log l") && bound.getParameterObject() instanceof Map<?, ?> params
                    && params.get("q") instanceof StockLogQuery q && java.util.Objects.equals(q.getWarehouseId(), target)
                    && params.get("watermark") != null) {
                if (Integer.valueOf(10001).equals(params.get("limit"))) preflight.incrementAndGet();
                else if (Integer.valueOf(500).equals(params.get("limit"))) {
                    int batch = batches.incrementAndGet();
                    if (batch == 2) secondPrepared.countDown();
                    if (blockEveryBatch) { twoRequests.countDown(); if (!disconnected.await(10, TimeUnit.SECONDS)) throw new AssertionError("Admission barrier timed out"); }
                    if (blockSecond && batch == 2 && !disconnected.await(10, TimeUnit.SECONDS)) throw new AssertionError("client did not disconnect");
                }
            }
            return invocation.proceed();
        }
    }
}
