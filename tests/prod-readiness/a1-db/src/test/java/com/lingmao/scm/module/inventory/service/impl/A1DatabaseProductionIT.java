package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.inventory.dto.InvDocCreateReq;
import com.lingmao.scm.module.inventory.dto.InvDocDetailVO;
import com.lingmao.scm.module.inventory.dto.InvDocItemReq;
import com.lingmao.scm.module.inventory.dto.InvDocSaveReq;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.inventory.service.StockService;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** T01–T07：真实 PostgreSQL、真实 MVC/Service/Mapper；不替换业务 Bean，也不模拟事务。 */
@IntegrationTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class A1DatabaseProductionIT {
    private static final List<String> PERMISSIONS = List.of("inventory:doc:list", "inventory:doc:add",
            "inventory:doc:edit", "inventory:doc:remove", "inventory:doc:audit", "inventory:stock:list", "techpack:cost:query");
    private static final Path ARTIFACTS = Path.of("target", "a1-production");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired SerialNoGenerator serial;
    @Autowired InvDocService documents;
    @Autowired StockService stocks;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired SqlSessionFactory sessions;
    @Autowired Flyway flyway;
    @Autowired Clock clock;
    @Autowired javax.sql.DataSource dataSource;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.hikari.maximum-pool-size:5}") int poolSize;
    private final SqlProbe sqlProbe = new SqlProbe();
    private long user;
    private long currentCompany;

    @BeforeAll
    void installSqlProbe() throws Exception {
        Files.createDirectories(ARTIFACTS);
        sessions.getConfiguration().addInterceptor(sqlProbe);
    }

    @BeforeEach
    void login() {
        TestIdentity.clear();
        currentCompany = 0;
        user = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'x','A1 真实事务') RETURNING id",
                Long.class, "a1-" + UUID.randomUUID());
        TestIdentity.grant(jdbc, user, PERMISSIONS);
        login(user);
    }

    @AfterEach
    void logout() { TestIdentity.clear(); }

    @Test
    void t01_twentyConcurrentTransactionsAllocateUniqueNumbersAndRollbackTogether() throws Exception {
        String prefix = "A1" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        List<String> numbers = race(20, () -> tx.execute(status -> serial.daily(prefix)));
        assertThat(numbers).doesNotHaveDuplicates().hasSize(20);
        String dayKey = numbers.getFirst().substring(0, numbers.getFirst().length() - 4);
        assertThat(numbers).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 20)
                .mapToObj(n -> dayKey + String.format("%04d", n)).toList());
        assertThat(jdbc.queryForObject("SELECT seq FROM sys_serial WHERE seq_key=?", Integer.class, dayKey)).isEqualTo(20);
        String rolledBack = tx.execute(status -> {
            String no = serial.daily(prefix);
            status.setRollbackOnly();
            return no;
        });
        String retried = tx.execute(status -> serial.daily(prefix));
        assertThat(retried).isEqualTo(rolledBack);
        Files.writeString(ARTIFACTS.resolve("t01-concurrent-sequence.json"), json.writeValueAsString(numbers));
    }

    @Test
    void t02_twentySameKeyHttpRequestsObserve409ThenReplayOneCommittedDocument() throws Exception {
        Fixture f = fixture();
        String key = UUID.randomUUID().toString();
        String body = json.writeValueAsString(createRequest(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "2"))));
        // 仅测试触发器让获胜事务确定保持处理中；并发请求仍走同一真实 MVC、advisory 锁和事务。
        String hook = "a1_pause_" + key.replace("-", "");
        jdbc.execute("CREATE FUNCTION " + hook + "() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN IF NEW.request_key='"
                + key + "'::uuid THEN PERFORM pg_sleep(0.7); END IF; RETURN NEW; END $f$");
        jdbc.execute("CREATE TRIGGER " + hook + " BEFORE INSERT ON biz_http_action FOR EACH ROW EXECUTE FUNCTION " + hook + "()");
        List<HttpResult> results;
        try {
            results = race(20, () -> createHttp(user, body, key, null, PERMISSIONS));
        } finally {
            jdbc.execute("DROP TRIGGER " + hook + " ON biz_http_action");
            jdbc.execute("DROP FUNCTION " + hook + "()");
        }
        assertThat(results).anyMatch(r -> r.status() == 409 && r.code() == 409);
        assertThat(results).allMatch(r -> (r.status() == 200 && r.code() == 0) || (r.status() == 409 && r.code() == 409));
        HttpResult success = results.stream().filter(r -> r.code() == 0).findFirst().orElseThrow();
        assertThat(success.body().get("data").get("id").isString()).isTrue();
        for (int i = 0; i < 20; i++) {
            HttpResult replay = createHttp(user, body, key, null, PERMISSIONS);
            assertThat(replay.code()).isZero();
            assertThat(replay.body()).isEqualTo(success.body());
        }
        assertThat(count("SELECT count(*) FROM inv_doc WHERE company_id=?", f.company())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM biz_http_action WHERE request_key=?::uuid AND state='COMPLETED'", key)).isEqualTo(1);
        Files.writeString(ARTIFACTS.resolve("t02-http-race.json"), json.writeValueAsString(Map.of(
                "statuses", results.stream().map(HttpResult::status).toList(), "committedResponse", success.body())));
    }

    @Test
    void t02_keyValidationBodyCompanyAndAuthorizationRemainEnforcedOnReplay() throws Exception {
        Fixture f = fixture();
        InvDocCreateReq request = createRequest(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "1")));
        String original = json.writeValueAsString(request);
        assertThat(createHttp(user, original, null, null, PERMISSIONS).code()).isEqualTo(400);
        assertThat(createHttp(user, original, "not-a-uuid", null, PERMISSIONS).code()).isEqualTo(400);
        String key = UUID.randomUUID().toString();
        HttpResult success = createHttp(user, original, key, null, PERMISSIONS);
        assertThat(success.code()).isZero();
        assertThat(createHttp(user, original, key, Long.toString(f.company()), PERMISSIONS).body()).isEqualTo(success.body());
        assertThat(createHttp(user, original, key, Long.toString(f.company() + 1), PERMISSIONS).code()).isEqualTo(409);
        request.getItems().getFirst().setQty(new BigDecimal("2"));
        assertThat(createHttp(user, json.writeValueAsString(request), key, null, PERMISSIONS).code()).isEqualTo(1002);
        assertThat(createHttp(user, original, key, null, List.of("inventory:doc:list")).code()).isEqualTo(403);
        long id = Long.parseLong(success.body().get("data").get("id").stringValue());
        jdbc.update("UPDATE inv_doc SET create_by=NULL WHERE id=?", id);
        assertThat(createHttp(user, original, key, null, PERMISSIONS).code()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM inv_doc WHERE company_id=?", f.company())).isEqualTo(1);
    }

    @Test
    void t02_failedBusinessWriteRollsBackClaimAndSameKeyCanRetry() throws Exception {
        Fixture f = fixture();
        InvDocCreateReq request = createRequest(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "1")));
        request.setToWarehouseId(Long.MAX_VALUE);
        String key = UUID.randomUUID().toString();
        assertThat(createHttp(user, json.writeValueAsString(request), key, null, PERMISSIONS).code()).isNotZero();
        assertThat(count("SELECT count(*) FROM biz_http_action WHERE request_key=?::uuid", key)).isZero();
        assertThat(count("SELECT count(*) FROM inv_doc WHERE company_id=?", f.company())).isZero();
        request.setToWarehouseId(f.warehouse());
        assertThat(createHttp(user, json.writeValueAsString(request), key, null, PERMISSIONS).code()).isZero();
    }

    @Test
    void t03_twoRealDocumentWritersWithSameVersionHaveExactlyOneWinner() throws Exception {
        Fixture f = fixture();
        long id = create(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "1")));
        InvDocDetailVO before = documents.detail(id);
        InvDocSaveReq a = saveRequest(before, f, "2");
        InvDocSaveReq b = saveRequest(before, f, "3");
        List<Integer> results = race(List.of(() -> trySave(id, a), () -> trySave(id, b)));
        assertThat(results).containsExactlyInAnyOrder(0, 1002);
        assertThat(documents.detail(id).getVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(jdbc.queryForObject("SELECT qty FROM inv_doc_item WHERE doc_id=?", BigDecimal.class, id))
                .isIn(new BigDecimal("2.000"), new BigDecimal("3.000"));
    }

    @Test
    void t04_fiftyTransactionsCompeteForTenUnitsWithoutNegativeBalances() throws Exception {
        Fixture f = fixture();
        audit(create(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "10"))));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) ids.add(create(f, InvDocType.OTHER_OUT, List.of(item(f.skuA(), "1"))));
        List<Integer> outcomes = race(ids.stream().<Callable<Integer>>map(id -> () -> tryAudit(id)).toList());
        assertThat(outcomes.stream().filter(i -> i == 0).count()).isEqualTo(10);
        assertThat(outcomes.stream().filter(i -> i == 1001).count()).isEqualTo(40);
        assertThat(balance(f, f.skuA())).isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM inv_stock_log WHERE warehouse_id=? AND change_qty<0", f.warehouse())).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM inv_stock_log WHERE warehouse_id=? AND balance_qty<0", f.warehouse())).isZero();
        assertThat(count("SELECT count(*) FROM inv_doc WHERE company_id=? AND status='DRAFT'", f.company())).isEqualTo(40);
        Files.writeString(ARTIFACTS.resolve("t04-fifty-stock-writers.json"), json.writeValueAsString(outcomes));
    }

    @Test
    void t04_oppositeSkuInputOrderUsesOneDatabaseLockOrder() throws Exception {
        Fixture f = fixture();
        audit(create(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "10"), item(f.skuB(), "10"))));
        long a = create(f, InvDocType.OTHER_OUT, List.of(item(f.skuA(), "1"), item(f.skuB(), "1")));
        long b = create(f, InvDocType.OTHER_OUT, List.of(item(f.skuB(), "2"), item(f.skuA(), "2")));
        assertThat(race(List.of(() -> tryAudit(a), () -> tryAudit(b)))).containsExactly(0, 0);
        assertThat(balance(f, f.skuA())).isEqualByComparingTo("7");
        assertThat(balance(f, f.skuB())).isEqualByComparingTo("7");
    }

    @Test
    void t05_realMvcKeepsBigintIdentifiersExactAndVersionNumeric() throws Exception {
        Fixture f = fixture();
        long huge = 9_007_199_254_740_993L;
        jdbc.update("""
                INSERT INTO inv_doc(id,no,company_id,kind,doc_type,to_warehouse_id,doc_date,reason,create_by)
                OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, 'IN','OTHER_IN',?,CURRENT_DATE,'OTHER',?)
                """, huge, "A1-HUGE-" + UUID.randomUUID().toString().substring(0, 8), f.company(), f.warehouse(), user);
        jdbc.update("INSERT INTO inv_doc_item(doc_id,seq,material_sku_id,qty) VALUES (?,1,?,1)", huge, f.skuA());
        HttpResult detail = perform(get("/api/inventory-docs/" + huge), user, PERMISSIONS);
        assertThat(detail.code()).isZero();
        JsonNode data = detail.body().get("data");
        assertThat(data.get("id").stringValue()).isEqualTo(Long.toString(huge));
        assertThat(data.get("companyId").isString()).isTrue();
        assertThat(data.get("version").isIntegralNumber()).isTrue();
        assertThat(data.get("createTime").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+08:00");
        assertThat(data.get("docDate").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}");
        HttpResult page = perform(get("/api/inventory-docs").param("kind", "IN").param("keyword", "A1-HUGE"), user, PERMISSIONS);
        assertThat(page.code()).isZero();
        assertThat(page.body().get("data").get("total").isIntegralNumber()).isTrue();
        Files.writeString(ARTIFACTS.resolve("t05-bigint-mvc.json"), json.writeValueAsString(detail.body()));
    }

    @Test
    void t05_invalidNumbersCannotReachDatabaseAndServerRecomputesAmounts() throws Exception {
        Fixture f = fixture();
        InvDocCreateReq request = createRequest(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "-1")));
        assertThat(createHttp(user, json.writeValueAsString(request), UUID.randomUUID().toString(), null, PERMISSIONS).code()).isEqualTo(400);
        request.getItems().getFirst().setQty(new BigDecimal("3"));
        request.getItems().getFirst().setPrice(new BigDecimal("0.3350"));
        String forged = json.writeValueAsString(request).replace("\"price\":0.3350", "\"price\":0.3350,\"amount\":999999");
        assertThat(createHttp(user, forged, UUID.randomUUID().toString(), null, PERMISSIONS).code()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM inv_doc WHERE company_id=?", f.company())).isZero();
        HttpResult created = createHttp(user, json.writeValueAsString(request), UUID.randomUUID().toString(), null, PERMISSIONS);
        assertThat(created.code()).isZero();
        long id = Long.parseLong(created.body().get("data").get("id").stringValue());
        assertThat(jdbc.queryForObject("SELECT amount FROM inv_doc_item WHERE doc_id=?", BigDecimal.class, id)).isEqualByComparingTo("1.01");
        for (String invalid : List.of("-1", "NaN", "Infinity", "-Infinity")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE inv_doc_item SET qty=?::numeric WHERE doc_id=?", invalid, id))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE inv_doc_item SET conversion_factor=0 WHERE doc_id=?", id))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT qty FROM inv_doc_item WHERE doc_id=?", BigDecimal.class, id)).isEqualByComparingTo("3");
    }

    @Test
    void t06_closedInventoryPeriodBlocksRealMvcUnauditAndHistoricalDateChangesSafely() throws Exception {
        Fixture f = fixture();
        InvDocCreateReq request = createRequest(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "2")));
        request.setDocDate(LocalDate.of(2020, 1, 31));
        long id = documents.create(request).id();
        audit(id);
        int version = documents.detail(id).getVersion();
        jdbc.update("INSERT INTO inv_closed_period(company_id,period_start,closed_by,reason) VALUES (?,'2020-01-01',?,'A1 冻结验证')", f.company(), user);
        HttpResult result = perform(post("/api/inventory-docs/" + id + "/unaudit").contentType(MediaType.APPLICATION_JSON)
                .header("X-Company-Id", Long.toString(f.company()))
                .content(json.writeValueAsString(new DocActionReq(version, "冻结后反审核"))), user, PERMISSIONS);
        assertThat(result.code()).isEqualTo(1002);
        String response = json.writeValueAsString(result.body());
        assertThat(response).contains("冻结").doesNotContain("inv_doc", "UPDATE", "SELECT", "P2007", "org.postgresql");
        assertThat(jdbc.queryForObject("SELECT status FROM inv_doc WHERE id=?", String.class, id)).isEqualTo("APPROVED");
        assertThat(balance(f, f.skuA())).isEqualByComparingTo("2");
        assertThatThrownBy(() -> jdbc.update("UPDATE inv_doc SET doc_date='2020-02-01' WHERE id=?", id))
                .isInstanceOf(DataAccessException.class);
        Files.writeString(ARTIFACTS.resolve("t06-frozen-period-mvc.json"), response);
    }

    @Test
    void t06_softDeletedNaturalKeyCanBeReusedAndReferencedMasterCannotDisappear() {
        String code = "a1-" + UUID.randomUUID();
        long old = jdbc.queryForObject("INSERT INTO sys_dict_type(code,name) VALUES (?,'旧') RETURNING id", Long.class, code);
        jdbc.update("UPDATE sys_dict_type SET deleted=true WHERE id=?", old);
        long current = jdbc.queryForObject("INSERT INTO sys_dict_type(code,name) VALUES (?,'新') RETURNING id", Long.class, code);
        assertThat(current).isNotEqualTo(old);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sys_dict_type(code,name) VALUES (?,'重复')", code)).isInstanceOf(DataAccessException.class);
        Fixture f = fixture();
        create(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "1")));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bas_company WHERE id=?", f.company())).isInstanceOf(DataAccessException.class);
        assertThat(count("SELECT count(*) FROM bas_company WHERE id=?", f.company())).isEqualTo(1);
    }

    @Test
    void t06_periodCloseWaitsForAnActualPostingTransactionThenRejectsLaterWrites() throws Exception {
        Fixture f = fixture();
        InvDocCreateReq request = createRequest(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "1")));
        request.setDocDate(LocalDate.of(2020, 4, 30));
        long id = documents.create(request).id();
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        AtomicInteger closePid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> writer = pool.submit(() -> {
                login(user);
                TestIdentity.company(f.company());
                try {
                    tx.executeWithoutResult(status -> {
                        documents.act(id, "audit", new DocActionReq(0, null));
                        posted.countDown();
                        try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    });
                } finally { TestIdentity.clear(); }
            });
            try {
                assertThat(posted.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> closer = pool.submit(() -> tx.executeWithoutResult(status -> {
                    closePid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    closing.countDown();
                    jdbc.update("INSERT INTO inv_closed_period(company_id,period_start,closed_by,reason) VALUES (?,'2020-04-01',?,'并发关期间')", f.company(), user);
                }));
                assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !waiting) {
                    waiting = count("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted", closePid.get()) == 1;
                }
                assertThat(waiting).as("关闭事务在真实 PostgreSQL 等待过账的共享期间锁").isTrue();
                assertThat(closer.isDone()).isFalse();
                release.countDown();
                writer.get(10, TimeUnit.SECONDS);
                closer.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(balance(f, f.skuA())).isEqualByComparingTo("1");
        assertThatThrownBy(() -> jdbc.update("UPDATE inv_doc SET remark='冻结后改写' WHERE id=?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void t07_seekDateBoundaryConcurrentInsertAndFilterBinding() throws Exception {
        Fixture f = fixture();
        long doc = create(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "1")));
        OffsetDateTime lastSecond = OffsetDateTime.parse("2026-03-21T23:59:59+08:00");
        List<Long> original = new ArrayList<>();
        for (int i = 0; i < 4; i++) original.add(log(f, doc, lastSecond));
        log(f, doc, OffsetDateTime.parse("2026-03-22T00:00:00+08:00"));
        StockLogQuery query = logQuery(f);
        query.setSize(2);
        query.setCreatedDateFrom(LocalDate.of(2026, 3, 21));
        query.setCreatedDateTo(LocalDate.of(2026, 3, 21));
        var first = stocks.seekLogs(query);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.list()).hasSize(2);
        query.setCursor(first.nextCursor());
        List<Object> raced = race(List.<Callable<Object>>of(() -> stocks.seekLogs(query), () -> log(f, doc, lastSecond)));
        @SuppressWarnings("unchecked") var second = (com.lingmao.scm.common.page.SeekPage<StockLogRowVO>) raced.getFirst();
        List<Long> all = new ArrayList<>(first.list().stream().map(StockLogRowVO::getId).toList());
        all.addAll(second.list().stream().map(StockLogRowVO::getId).toList());
        assertThat(all).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(original);
        assertThat(second.hasMore()).isFalse();
        query.setWarehouseId(f.warehouse() + 1);
        assertThatThrownBy(() -> stocks.seekLogs(query)).isInstanceOf(BizException.class).hasMessageContaining("游标");
        query.setWarehouseId(f.warehouse());
        login(user + 1);
        assertThatThrownBy(() -> stocks.seekLogs(query)).isInstanceOf(BizException.class).hasMessageContaining("游标");
        login(user);
        query.setCursor(first.nextCursor().substring(0, first.nextCursor().length() - 3) + "bad");
        assertThatThrownBy(() -> stocks.seekLogs(query)).isInstanceOf(BizException.class);
        HttpResult route = perform(get("/api/stock-logs/seek").param("warehouseId", Long.toString(f.warehouse()))
                .param("size", "2"), user, PERMISSIONS);
        assertThat(route.code()).isZero();
        assertThat(route.body().get("data").has("total")).isFalse();
        assertThat(route.body().get("data").get("list").get(0).get("id").isString()).isTrue();
        Files.writeString(ARTIFACTS.resolve("t07-seek-http.json"), json.writeValueAsString(route.body()));
    }

    @Test
    void t07_boundActualSeekSqlUsesIndexAndFixedQueryCountOn100000Rows() throws Exception {
        Fixture f = fixture();
        long doc = create(f, InvDocType.OTHER_IN, List.of(item(f.skuA(), "1")));
        jdbc.update("""
                INSERT INTO bas_material_sku(material_id,code,spec,spec_code)
                SELECT material_id, 'A1-GRID-' || ? || '-' || n, 'A1-' || n, 'T' || n
                FROM bas_material_sku CROSS JOIN generate_series(1,100) n WHERE id=?
                """, f.warehouse(), f.skuA());
        jdbc.update("""
                WITH skus AS (
                    SELECT id, row_number() OVER (ORDER BY id) AS pos FROM bas_material_sku
                    WHERE material_id=(SELECT material_id FROM bas_material_sku WHERE id=?)
                )
                INSERT INTO inv_stock_log(inv_doc_id,change_type,doc_no,biz_date,warehouse_id,material_sku_id,
                                          change_qty,balance_qty,create_time,create_by)
                SELECT ?, 'OTHER_IN', 'A1-SEEK', CURRENT_DATE, ?, skus.id, 1, n,
                       '2026-01-01 00:00:00+00'::timestamptz + n*interval '1 second', ?
                FROM generate_series(1,100000) n JOIN skus ON skus.pos=(n%102)+1
                """, f.skuA(), doc, f.warehouse(), user);
        jdbc.execute("ANALYZE inv_stock_log");
        StockLogQuery query = logQuery(f);
        query.setMaterialSkuId(null);
        query.setSize(1);
        stocks.seekLogs(query); // 缓存预热不计入固定查询数的比较。
        List<String> small = sqlProbe.capture(() -> stocks.seekLogs(query));
        query.setSize(100);
        List<String> large = sqlProbe.capture(() -> stocks.seekLogs(query));
        // 主查询、SKU、物料、两种附件、人员：100 个不同 SKU 仍是固定 6 条，不随行数增长。
        assertThat(large).hasSize(small.size()).hasSize(6);
        assertThat(large).noneMatch(sql -> sql.toUpperCase().contains("COUNT(") || sql.toUpperCase().contains("OFFSET"));
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("q", query);
        parameters.put("codes", null);
        parameters.put("prefix", null);
        parameters.put("afterTime", OffsetDateTime.parse("2026-01-01T00:16:40Z"));
        parameters.put("afterId", Long.MAX_VALUE);
        parameters.put("limit", 101);
        MappedStatement statement = sessions.getConfiguration().getMappedStatement(StockLogMapper.class.getName() + ".selectSeek");
        var sql = statement.getBoundSql(parameters);
        String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var prepared = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql.getSql())) {
                new DefaultParameterHandler(statement, parameters, sql).setParameters(prepared);
                try (var rows = prepared.executeQuery()) { rows.next(); return rows.getString(1); }
            }
        });
        List<JsonNode> nodes = planNodes(json.readTree(plan).get(0).get("Plan"));
        assertThat(nodes.stream().filter(n -> n.has("Relation Name") && n.get("Relation Name").stringValue().equals("inv_stock_log")).toList())
                .singleElement().satisfies(node -> {
                    assertThat(node.get("Node Type").stringValue()).contains("Index");
                    assertThat(node.get("Index Name").stringValue()).isIn("idx_inv_log_wh_seek", "idx_inv_log_seek");
                    assertThat(node.get("Actual Rows").intValue()).isLessThanOrEqualTo(101);
                    assertThat(node.get("Index Cond").stringValue()).contains("create_time", "id");
                });
        Files.writeString(ARTIFACTS.resolve("t07-seek-explain-analyze.json"), plan);
        Files.writeString(ARTIFACTS.resolve("t07-fixed-query-count.json"), json.writeValueAsString(Map.of("oneRow", small, "hundredRows", large)));
        String hundred = String.join(",", IntStream.range(0, 100).mapToObj(i -> "A1" + i).toList());
        assertThat(Keywords.codes(hundred)).hasSize(100);
        assertThatThrownBy(() -> Keywords.codes(hundred + ",A1101")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> Keywords.codes("x".repeat(8193))).isInstanceOf(BizException.class);
        query.setPage(1000000);
        assertThatThrownBy(() -> stocks.logs(query)).isInstanceOf(BizException.class);
    }

    @Test
    void migrationsAreRepeatableAndEveryOnlineIndexIsValid() throws Exception {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(count("""
                SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
                WHERE c.relname IN ('idx_inv_log_seek','idx_inv_log_wh_seek','idx_inv_log_sku_seek',
                   'idx_inv_log_doc_prefix','idx_mrp_calc_status_time','idx_ord_order_status_date','idx_pur_order_status_date')
                  AND i.indisvalid AND i.indisready
                """)).isEqualTo(7);
        // Check every pooled session: a migration must not RESET one borrowed connection to unbounded waits.
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < poolSize; i++) borrowed.add(dataSource.getConnection());
            for (Connection connection : borrowed) try (var statement = connection.createStatement()) {
                for (var setting : Map.of("statement_timeout", "30s", "lock_timeout", "5s",
                        "idle_in_transaction_session_timeout", "30s").entrySet()) {
                    try (var result = statement.executeQuery("SHOW " + setting.getKey())) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).as(setting.getKey()).isEqualTo(setting.getValue());
                    }
                }
            }
        } finally {
            for (Connection connection : borrowed) connection.close();
        }
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long company = jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES (?,?,'A1') RETURNING id",
                Long.class, suffix.substring(0, 6).toUpperCase(), "A1 主体 " + suffix);
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES (?,?,'MATERIAL') RETURNING id",
                Long.class, "A1" + suffix, "A1 仓 " + suffix);
        long category = jdbc.queryForObject("INSERT INTO bas_material_category(name,level) VALUES (?,1) RETURNING id", Long.class, "A1 " + suffix);
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit)
                VALUES (?,'A1 面料',?,'FABRIC','METER','METER') RETURNING id
                """, Long.class, "A1M" + suffix, category);
        long a = jdbc.queryForObject("INSERT INTO bas_material_sku(material_id,code,spec,spec_code) VALUES (?,?,'A','A') RETURNING id", Long.class, material, "A1A" + suffix);
        long b = jdbc.queryForObject("INSERT INTO bas_material_sku(material_id,code,spec,spec_code) VALUES (?,?,'B','B') RETURNING id", Long.class, material, "A1B" + suffix);
        currentCompany = company;
        TestIdentity.company(company);
        return new Fixture(company, warehouse, a, b);
    }

    private record Fixture(long company, long warehouse, long skuA, long skuB) { }
    private record HttpResult(int status, int code, JsonNode body) { }

    private static List<JsonNode> planNodes(JsonNode node) {
        List<JsonNode> nodes = new ArrayList<>();
        nodes.add(node);
        if (node.has("Plans")) node.get("Plans").forEach(child -> nodes.addAll(planNodes(child)));
        return nodes;
    }

    private static void login(long id) { SecurityContextHolder.getContext().setAuthentication(auth(id, PERMISSIONS)); }
    private static UsernamePasswordAuthenticationToken auth(long id, List<String> permissions) {
        return new UsernamePasswordAuthenticationToken(new LoginUser(id, "a1-it-session"), null,
                permissions.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private HttpResult perform(MockHttpServletRequestBuilder request, long id, List<String> permissions) throws Exception {
        MvcResult result = mvc.perform(request.with(authentication(auth(id, permissions)))).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return new HttpResult(result.getResponse().getStatus(), body.get("code").intValue(), body);
    }

    private HttpResult createHttp(long id, String body, String key, String company, List<String> permissions) throws Exception {
        var request = post("/api/inventory-docs").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        request.header("X-Company-Id", company == null ? Long.toString(currentCompany) : company);
        return perform(request, id, permissions);
    }

    private InvDocCreateReq createRequest(Fixture f, InvDocType type, List<InvDocItemReq> items) {
        var req = new InvDocCreateReq();
        req.setCompanyId(f.company());
        req.setDocDate(LocalDate.now(clock));
        req.setDocType(type);
        if (type.hasTo()) req.setToWarehouseId(f.warehouse());
        if (type.hasFrom()) req.setFromWarehouseId(f.warehouse());
        req.setReason("OTHER");
        req.setItems(items);
        return req;
    }

    private static InvDocItemReq item(long sku, String qty) {
        var item = new InvDocItemReq();
        item.setMaterialSkuId(sku);
        item.setQty(new BigDecimal(qty));
        return item;
    }

    private InvDocSaveReq saveRequest(InvDocDetailVO before, Fixture f, String qty) {
        var req = new InvDocSaveReq();
        req.setVersion(before.getVersion());
        req.setCompanyId(f.company());
        req.setDocDate(before.getDocDate());
        req.setToWarehouseId(f.warehouse());
        req.setReason("OTHER");
        req.setItems(List.of(item(f.skuA(), qty)));
        return req;
    }

    private long create(Fixture f, InvDocType type, List<InvDocItemReq> items) { return documents.create(createRequest(f, type, items)).id(); }
    private void audit(long id) { documents.act(id, "audit", new DocActionReq(documents.detail(id).getVersion(), null)); }
    private int tryAudit(long id) { try { audit(id); return 0; } catch (BizException failure) { return failure.getCode(); } }
    private int trySave(long id, InvDocSaveReq req) { try { documents.update(id, req); return 0; } catch (BizException failure) { return failure.getCode(); } }
    private int count(String query, Object... args) { return jdbc.queryForObject(query, Integer.class, args); }
    private BigDecimal balance(Fixture f, long sku) { return jdbc.queryForObject("SELECT qty FROM inv_stock WHERE warehouse_id=? AND material_sku_id=?", BigDecimal.class, f.warehouse(), sku); }
    private StockLogQuery logQuery(Fixture f) {
        var query = new StockLogQuery();
        query.setWarehouseId(f.warehouse());
        query.setMaterialSkuId(f.skuA());
        return query;
    }

    private long log(Fixture f, long doc, OffsetDateTime instant) {
        return jdbc.queryForObject("""
                INSERT INTO inv_stock_log(inv_doc_id,change_type,doc_no,biz_date,warehouse_id,material_sku_id,
                                          change_qty,balance_qty,create_time,create_by)
                VALUES (?,'OTHER_IN','A1-DATE','2026-03-21',?,?,1,1,?,?) RETURNING id
                """, Long.class, doc, f.warehouse(), f.skuA(), instant, user);
    }

    private <T> List<T> race(int size, Callable<T> task) throws Exception { return race(IntStream.range(0, size).mapToObj(i -> task).toList()); }
    private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) futures.add(pool.submit(() -> {
                login(user);
                if (currentCompany != 0) TestIdentity.company(currentCompany);
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try { return task.call(); } finally { TestIdentity.clear(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(45, TimeUnit.SECONDS));
            return results;
        }
    }

    /** 数真实 JDBC prepare；其他测试与异步审计线程不计入本线程。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static final class SqlProbe implements Interceptor {
        private final ThreadLocal<List<String>> current = new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (current.get() != null) current.get().add(((StatementHandler) invocation.getTarget()).getBoundSql().getSql());
            return invocation.proceed();
        }
        List<String> capture(Runnable task) {
            List<String> statements = new ArrayList<>();
            current.set(statements);
            try { task.run(); return statements; } finally { current.remove(); }
        }
    }
}
