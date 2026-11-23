package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.dev.core.SampleNodePlanner;
import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.Sample;
import com.lingmao.scm.module.dev.entity.SampleOrder;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.module.dev.entity.SampleReview;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.dev.entity.SampleType;
import com.lingmao.scm.security.DataScope;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 合成基础数据；样衣流转本身使用真实 Repository/CAS/事务，没有伪造终态或关闭数据库守卫。 */
abstract class SampleTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired SampleRepository samples;
    @Autowired SerialNoGenerator serials;
    @Autowired DeploymentNamespace namespace;
    @Autowired Clock clock;
    @Autowired JsonMapper json;
    private static final AtomicInteger COLOR_CODE = new AtomicInteger(10_000);

    record Fixture(long company, long otherCompany, long category, long sizeGroup, long creator,
                   long designer, long maker, long otherUser, long factory, List<SampleRepository.ColorChoice> colors) { }
    record Created(long sampleId, long orderId, String sampleCode, String orderNo) { }

    @AfterEach
    void clearIdentity() { TestIdentity.clear(); }

    Fixture fixture() {
        long company = company(); long otherCompany = company();
        long creator = user("creator"); long designer = user("designer"); long maker = user("maker"); long other = user("other");
        long root = jdbc.queryForObject("INSERT INTO bas_category(name,level) VALUES(?,1) RETURNING id", Long.class, unique("cat"));
        long mid = jdbc.queryForObject("INSERT INTO bas_category(parent_id,name,level) VALUES(?,?,2) RETURNING id", Long.class, root, unique("cat"));
        long category = jdbc.queryForObject("INSERT INTO bas_category(parent_id,name,level) VALUES(?,?,3) RETURNING id", Long.class, mid, unique("cat"));
        long group = jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES(?,'M') RETURNING id", Long.class, unique("sizes"));
        List<String> sizeCodes = List.of("XS", "S", "M", "L", "XL");
        for (int i = 0; i < sizeCodes.size(); i++) jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,?,?)", group, i + 1, sizeCodes.get(i));
        var colors = new ArrayList<SampleRepository.ColorChoice>();
        for (int i = 0; i < 3; i++) {
            String code = Integer.toString(COLOR_CODE.incrementAndGet(), 36).toUpperCase(java.util.Locale.ROOT);
            String name = unique("color");
            long id = jdbc.queryForObject("INSERT INTO bas_color(code,name,hex) VALUES(?,?,'#123456') RETURNING id", Long.class, code, name);
            colors.add(new SampleRepository.ColorChoice(id, code, name, "#123456"));
        }
        long factory = jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name) VALUES(?,?,?) RETURNING id",
                Long.class, unique("SUP"), unique("工厂"), "S1 合成工厂");
        jdbc.update("INSERT INTO sup_supplier_category(supplier_id,category) VALUES(?,'GARMENT')", factory);
        return new Fixture(company, otherCompany, category, group, creator, designer, maker, other, factory, List.copyOf(colors));
    }

    long company() {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        return jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES(?,?,?) RETURNING id",
                Long.class, code, "S1 样衣 " + code, code);
    }

    long user(String prefix) {
        String name = unique(prefix);
        return jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES(?,'unusable-test-password',?) RETURNING id", Long.class, name, name);
    }

    void login(long actor, boolean all) {
        TestIdentity.login(actor, all ? List.of("data:all", "dev:sample:list", "dev:sample:edit")
                : List.of("dev:sample:list", "dev:sample:edit"));
    }

    Created draft(Fixture f) {
        login(f.creator(), true);
        return transactions.execute(status -> createDraftInTransaction(f));
    }

    Created createDraftInTransaction(Fixture f) {
        Sample sample = sample(f);
        sample.setCode(serials.next("YY" + LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyMM")), 4));
        samples.insertSample(sample);
        samples.replaceColors(sample, f.colors().stream().map(SampleRepository.ColorChoice::id).toList());
        SampleOrder order = order(f, sample.getId(), 1);
        samples.insertOrder(order);
        samples.replaceSelection(order, List.of(f.colors().getFirst()), List.of("M"));
        return new Created(sample.getId(), order.getId(), sample.getCode(), order.getNo());
    }

    Sample sample(Fixture f) {
        Sample sample = new Sample(); sample.setCompanyId(f.company()); sample.setName(unique("样衣"));
        sample.setBrand("S1_BRAND"); sample.setCategoryId(f.category()); sample.setYear(LocalDate.now(clock).getYear());
        sample.setSeason("SPRING"); sample.setDesignerId(f.designer()); sample.setPatternMakerId(f.maker());
        sample.setSource("OWN"); sample.setSizeGroupId(f.sizeGroup()); sample.setSampleFactoryId(f.factory());
        sample.setEstTagPrice(new BigDecimal("199.00")); sample.setEstCost(null);
        return sample;
    }

    SampleOrder order(Fixture f, long sampleId, int businessVersion) {
        SampleOrder order = new SampleOrder(); order.setCompanyId(f.company()); order.setNo(serials.daily("DY"));
        order.setSampleId(sampleId); order.setSampleVersion(businessVersion);
        order.setSampleType(businessVersion == 1 ? SampleType.FIRST : SampleType.REVISE);
        order.setQty(1); order.setNeedDate(LocalDate.now(clock).plusDays(10)); order.setSizeGroupId(f.sizeGroup());
        if (businessVersion > 1) order.setReason("S1 真实下一版");
        return order;
    }

    SampleRepository.Snapshot snapshot(Fixture f, long sampleId) {
        return samples.read(f.company(), sampleId, DataScope.current(false));
    }

    void dispatch(Fixture f, long sampleId, MakeWay way) {
        login(f.creator(), true);
        var before = snapshot(f, sampleId);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), sampleId, DataScope.current(false), before.sample().getVersion(), before.order().getVersion());
            SampleOrder order = lock.order(); order.setMakeWay(way);
            order.setFactoryId(way == MakeWay.EXTERNAL ? f.factory() : null);
            order.setMakerId(way == MakeWay.INTERNAL ? f.maker() : null);
            order.setDispatchedBy(f.creator()); order.setDispatchedTime(OffsetDateTime.now(clock)); order.setStatus(SampleOrderStatus.MAKING);
            samples.updateOrder(lock, order);
            samples.insertNodes(order, SampleNodePlanner.plan(order.getNeedDate(), defaultNodes()), way == MakeWay.INTERNAL ? f.maker() : f.designer());
            samples.touchSample(lock);
        });
    }

    void send(Fixture f, long sampleId) {
        var before = snapshot(f, sampleId);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), sampleId, DataScope.current(false), before.sample().getVersion(), before.order().getVersion());
            var sender = samples.lockNodes(lock).stream().filter(n -> n.getNodeCode().equals("SEND")).findFirst().orElseThrow();
            samples.setDone(lock, sender.getId(), LocalDate.now(clock)); lock.order().setStatus(SampleOrderStatus.WAIT_REVIEW);
            samples.updateOrder(lock, lock.order()); samples.touchSample(lock);
        });
    }

    void reviewLocked(Fixture f, SampleRepository.Locked lock, SampleReviewResult result) {
        var oldSelection = samples.bundle(f.company(), DataScope.current(false), List.of(lock.orderId()));
        lock.order().setStatus(switch (result) {
            case PASS -> SampleOrderStatus.PASSED; case REVISE -> SampleOrderStatus.REVISE; case ELIMINATE -> SampleOrderStatus.ELIMINATED;
        });
        samples.updateOrder(lock, lock.order());
        SampleReview review = new SampleReview(); review.setResult(result); review.setOpinion("S1 评审意见");
        review.setReviewDate(LocalDate.now(clock)); samples.insertReview(lock.order(), review, List.of(f.creator(), f.designer()));
        if (result == SampleReviewResult.REVISE) {
            SampleOrder next = order(f, lock.sampleId(), samples.nextVersion(lock));
            next.setMakeWay(lock.order().getMakeWay()); next.setFactoryId(lock.order().getFactoryId()); next.setMakerId(lock.order().getMakerId());
            next.setQty(lock.order().getQty()); next.setNeedDate(LocalDate.now(clock).plusDays(7)); next.setRequirement(lock.order().getRequirement());
            next.setReason(review.getOpinion()); samples.insertOrder(next);
            samples.replaceSelection(next, oldSelection.colors().stream().map(c -> new SampleRepository.ColorChoice(c.colorId(), c.colorCode(), c.colorName(), c.colorHex())).toList(),
                    oldSelection.sizes().stream().map(s -> s.sizeCode()).toList());
            lock.sample().setStatus(SampleStatus.DEVELOPING);
        } else lock.sample().setStatus(result == SampleReviewResult.PASS ? SampleStatus.PASSED : SampleStatus.ELIMINATED);
        samples.updateSample(lock, lock.sample());
    }

    void review(Fixture f, long sampleId, SampleReviewResult result) {
        var before = snapshot(f, sampleId);
        transactions.executeWithoutResult(status -> reviewLocked(f,
                samples.lock(f.company(), sampleId, DataScope.current(false), before.sample().getVersion(), before.order().getVersion()), result));
    }

    void stopAndReopen(Fixture f, long sampleId) {
        var before = snapshot(f, sampleId);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), sampleId, DataScope.current(false), before.sample().getVersion(), before.order().getVersion());
            lock.order().setStatus(SampleOrderStatus.CANCELLED); samples.updateOrder(lock, lock.order());
            lock.sample().setStatus(SampleStatus.ELIMINATED); samples.updateSample(lock, lock.sample());
        });
        var stopped = snapshot(f, sampleId);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), sampleId, DataScope.current(false), stopped.sample().getVersion(), null);
            SampleOrder next = order(f, sampleId, samples.nextVersion(lock)); samples.insertOrder(next);
            samples.replaceSelection(next, List.of(f.colors().getFirst()), List.of("M"));
            lock.sample().setStatus(SampleStatus.DEVELOPING); samples.updateSample(lock, lock.sample());
        });
    }

    static List<SampleNodePlanner.Template> defaultNodes() {
        return List.of(new SampleNodePlanner.Template("RECEIVE", "接单", BigDecimal.ONE),
                new SampleNodePlanner.Template("PATTERN", "纸样", new BigDecimal("2")),
                new SampleNodePlanner.Template("CUT", "裁剪", BigDecimal.ONE),
                new SampleNodePlanner.Template("SEW", "车版", new BigDecimal("3")),
                new SampleNodePlanner.Template("SEND", "送样", BigDecimal.ONE));
    }

    static String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    static <T> List<T> parallel(int count, IntFunction<T> operation) throws Exception {
        var barrier = new CyclicBarrier(count);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) { final int index = i; futures.add(pool.submit(() -> {
                try { barrier.await(10, TimeUnit.SECONDS); return operation.apply(index); }
                finally { TestIdentity.clear(); }
            })); }
            var values = new ArrayList<T>();
            for (Future<T> future : futures) values.add(future.get(30, TimeUnit.SECONDS));
            return values;
        }
    }

    static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) { if (current instanceof SQLException sql) return sql.getSQLState(); current = current.getCause(); }
        return null;
    }

    static void rejected(String state, Runnable statement) {
        Throwable failure = catchThrowable(statement::run); assertThat(failure).isNotNull();
        assertThat(sqlState(failure)).isEqualTo(state);
    }

    void evidence(String name, Object value) throws Exception {
        Path dir = Path.of("test-artifacts/full-audit/s1/sample"); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
