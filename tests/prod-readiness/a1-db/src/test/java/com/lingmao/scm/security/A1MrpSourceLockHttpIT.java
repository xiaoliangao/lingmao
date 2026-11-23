package com.lingmao.scm.security;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** P63：HTTP 事务与独立 PostgreSQL 连接真实竞争来源头；SQL 闸门只调度，不替换任何业务或查询结果。 */
class A1MrpSourceLockHttpIT extends A2BusinessFixture {
    @Autowired DataSource dataSource;
    @Autowired SqlSessionFactory sessions;
    private final SourceProjectionGate projection = new SourceProjectionGate();

    @BeforeEach void installProjectionGate() { sessions.getConfiguration().addInterceptor(projection); }

    @ParameterizedTest @ValueSource(strings = {"create", "draft", "confirmed"})
    void sourceWriterWaitsUntilAuthorizedCompanyProofCommits(String mode) throws Exception {
        Fixture f = fixture(); Account owner = writer(f.company()), nextOwner = writer(f.otherCompany());
        long order = sourceOrder(f, owner, "APPROVED", f.company());
        long calc = prepare(mode, owner, f.company(), order);
        Gate gate = new Gate();
        projection.active.set(gate);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor(); Connection change = dataSource.getConnection()) {
            change.setAutoCommit(false);
            int writerPid = pid(change);
            var request = pool.submit(() -> request(mode, owner, f.company(), order, calc));
            try {
                assertThat(gate.reached.await(10, TimeUnit.SECONDS)).as("reached company projection after source authorization").isTrue();
                assertThat(gate.pid).isNotEqualTo(writerPid);
                var changed = pool.submit(() -> {
                    updateSource(change, order, f.otherCompany(), nextOwner.id());
                    // 来源写取得锁时，算料业务必须已提交；此处是另一个连接，不能读到未提交结果。
                    Long persisted = calc == 0
                            ? jdbc.queryForObject("SELECT company_id FROM mrp_calc WHERE create_by=?", Long.class, owner.id())
                            : jdbc.queryForObject("SELECT company_id FROM mrp_calc WHERE id=?", Long.class, calc);
                    assertThat(persisted).isEqualTo(f.company());
                    change.commit();
                    return persisted;
                });
                assertBlocked(writerPid, gate.pid);
                assertThat(changed.isDone()).isFalse();
                gate.release.countDown();
                JsonNode response = node(request.get(15, TimeUnit.SECONDS));
                assertThat(response.path("code").intValue()).as(response.toString()).isZero();
                assertThat(changed.get(10, TimeUnit.SECONDS)).isEqualTo(f.company());
                long result = id(response.path("data").path("id"));
                assertThat(jdbc.queryForObject("SELECT company_id FROM mrp_calc WHERE id=?", Long.class, result)).isEqualTo(f.company());
                assertThat(jdbc.queryForObject("SELECT company_id FROM ord_order WHERE id=?", Long.class, order)).isEqualTo(f.otherCompany());
                assertThat(jdbc.queryForObject("SELECT create_by FROM ord_order WHERE id=?", Long.class, order)).isEqualTo(nextOwner.id());
                evidence("p63-source-waits-" + mode + ".json", map("httpBackendPid", gate.pid, "sourceBackendPid", writerPid,
                        "blockingObserved", true, "companyAtMrpCommit", Long.toString(f.company()), "httpCode", 0));
            } finally {
                gate.release.countDown();
                projection.active.set(null);
                change.rollback();
            }
        }
    }

    @ParameterizedTest @CsvSource({"create,false", "create,true", "draft,false", "draft,true", "confirmed,false", "confirmed,true"})
    void sourceChangeCommittedFirstIsReauthorizedUnderLock(String mode, boolean changeOwner) throws Exception {
        Fixture f = fixture(); Account owner = writer(f.company()), nextOwner = writer(f.company());
        long order = sourceOrder(f, owner, "APPROVED", f.company());
        long calc = prepare(mode, owner, f.company(), order);
        int version = mode.equals("confirmed") ? 1 : 0;
        int heads = jdbc.queryForObject("SELECT count(*) FROM mrp_calc WHERE create_by=?", Integer.class, owner.id());
        long logs = jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE table_name='mrp_calc' AND root_id=?", Long.class, calc);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor(); Connection change = dataSource.getConnection()) {
            change.setAutoCommit(false);
            int writerPid = pid(change);
            updateSource(change, order, changeOwner ? f.company() : f.otherCompany(), changeOwner ? nextOwner.id() : owner.id());
            var request = pool.submit(() -> request(mode, owner, f.company(), order, calc));
            try {
                int httpPid = blockedBy(writerPid);
                assertThat(httpPid).isNotEqualTo(writerPid);
                assertThat(request.isDone()).isFalse();
                change.commit();
                HttpResponse<byte[]> result = request.get(15, TimeUnit.SECONDS);
                int code = node(result).path("code").intValue();
                assertThat(code).isEqualTo(changeOwner ? 404 : 403);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM mrp_calc WHERE create_by=?", Integer.class, owner.id())).isEqualTo(heads);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE table_name='mrp_calc' AND root_id=?", Long.class, calc)).isEqualTo(logs);
                if (calc != 0) {
                    assertThat(jdbc.queryForObject("SELECT company_id FROM mrp_calc WHERE id=?", Long.class, calc)).isNull();
                    assertThat(jdbc.queryForObject("SELECT version FROM mrp_calc WHERE id=?", Integer.class, calc)).isEqualTo(version);
                }
                String trace = result.headers().firstValue("X-Request-Id").orElseThrow();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE request_id=?", Integer.class, trace)).isZero();
                evidence("p63-source-first-" + mode + "-" + changeOwner + ".json", map("httpBackendPid", httpPid,
                        "sourceBackendPid", writerPid, "blockingObserved", true, "httpCode", code));
            } finally { change.rollback(); }
        }
    }

    @Test void recalcRejectsSameCountSourceReplacementAfterLockPlanWasRead() throws Exception {
        Fixture f = fixture(); Account owner = writer(f.company());
        long before = sourceOrder(f, owner, "APPROVED", f.company()), after = sourceOrder(f, owner, "APPROVED", f.company());
        long calc = prepare("draft", owner, f.company(), before);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor(); Connection change = dataSource.getConnection()) {
            change.setAutoCommit(false);
            int sourcePid = pid(change);
            try (PreparedStatement lock = change.prepareStatement("SELECT id FROM ord_order WHERE id=? FOR UPDATE")) {
                lock.setLong(1, before); lock.executeQuery().close();
            }
            var request = pool.submit(() -> request("draft", owner, f.company(), before, calc));
            try {
                blockedBy(sourcePid);
                try (PreparedStatement replace = change.prepareStatement("UPDATE mrp_calc_order SET order_id=? WHERE calc_id=? AND order_id=?")) {
                    replace.setLong(1, after); replace.setLong(2, calc); replace.setLong(3, before);
                    assertThat(replace.executeUpdate()).isEqualTo(1);
                }
                change.commit();
                HttpResponse<byte[]> result = request.get(15, TimeUnit.SECONDS);
                assertThat(node(result).path("code").intValue()).isEqualTo(1002);
                assertThat(jdbc.queryForObject("SELECT company_id FROM mrp_calc WHERE id=?", Long.class, calc)).isNull();
                assertThat(jdbc.queryForObject("SELECT version FROM mrp_calc WHERE id=?", Integer.class, calc)).isZero();
                String trace = result.headers().firstValue("X-Request-Id").orElseThrow();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE request_id=?", Integer.class, trace)).isZero();
            } finally { change.rollback(); }
        }
    }

    private Account writer(long company) throws Exception {
        return custom(company, "SELF", "purchase:mrp:add", "purchase:mrp:list", "purchase:mrp:edit", "techpack:cost:query");
    }
    private long prepare(String mode, Account actor, long company, long order) throws Exception {
        if (mode.equals("create")) return 0;
        long calc = id(ok(actor, "POST", "/api/mrp-calcs", map("orderIds", List.of(order)), company).path("id"));
        if (mode.equals("confirmed")) ok(actor, "POST", "/api/mrp-calcs/" + calc + "/confirm", map("version", 0), company);
        jdbc.update("UPDATE mrp_calc SET company_id=NULL WHERE id=?", calc);
        return calc;
    }
    private HttpResponse<byte[]> request(String mode, Account actor, long company, long order, long calc) throws Exception {
        return raw(actor, "POST", mode.equals("create") ? "/api/mrp-calcs" : "/api/mrp-calcs/" + calc + "/recalc",
                mode.equals("create") ? map("orderIds", List.of(order)) : map("version", mode.equals("confirmed") ? 1 : 0),
                company, java.util.UUID.randomUUID().toString());
    }
    private static int pid(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next(); return result.getInt(1);
        }
    }
    private static void updateSource(Connection connection, long order, long company, long owner) throws Exception {
        try (PreparedStatement update = connection.prepareStatement("UPDATE ord_order SET company_id=?,create_by=?,sales_id=?,merch_id=? WHERE id=?")) {
            update.setLong(1, company); update.setLong(2, owner); update.setLong(3, owner); update.setLong(4, owner); update.setLong(5, order);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }
    private void assertBlocked(int waiting, int blocking) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, blocking, waiting))) return;
            TimeUnit.MILLISECONDS.sleep(10); // 轮询真实锁图，时间本身不是通过依据。
        }
        throw new AssertionError("Expected backend " + waiting + " to block on backend " + blocking);
    }
    private int blockedBy(int blocking) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<Integer> pids = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)) AND query ILIKE '%ord_order%FOR SHARE%'", Integer.class, blocking);
            if (!pids.isEmpty()) { assertThat(pids).hasSize(1); return pids.getFirst(); }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("Expected HTTP source lock to block on backend " + blocking);
    }
    private void evidence(String name, Map<String, Object> data) throws Exception {
        Path dir = Path.of("target", "a1-mrp-company"); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), json.writeValueAsString(data));
    }
    private static final class Gate {
        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean claimed = new AtomicBoolean();
        volatile int pid;
    }
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    private static final class SourceProjectionGate implements Interceptor {
        final AtomicReference<Gate> active = new AtomicReference<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Gate gate = active.get();
            String sql = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (gate != null && sql.contains("o.commit_date, o.company_id") && sql.contains("from ord_order o") && gate.claimed.compareAndSet(false, true)) {
                gate.pid = pid((Connection) invocation.getArgs()[0]);
                gate.reached.countDown();
                if (!gate.release.await(20, TimeUnit.SECONDS)) throw new AssertionError("MRP source projection gate timed out");
            }
            return invocation.proceed();
        }
    }
}
