package com.lingmao.scm.module.price.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.exception.GlobalExceptionHandler;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceQuery;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.dto.PriceRowVO;
import com.lingmao.scm.module.price.dto.PriceSaveReq;
import com.lingmao.scm.module.price.entity.PriceType;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 价格档案在真库上（RFC 006 §10、§12）：取现价的 SQL（单条、DISTINCT ON 批量、"只看现价"列表）、作废的条件更新、
 * 外键拦删除。原来写在 CurrentPriceTest 里的取价规则，Mockito 测不出 SQL，都在这里。
 * 每个测试自己建供应商、物料（随机编码），日期按今天往前往后推，不依赖表是空的、不依赖今天是哪天
 */
@IntegrationTest
class PriceServiceImplIT {

    private static final long USER = 900_101L;

    @Autowired
    private PriceService priceService;
    @Autowired
    private MaterialService materialService;
    @Autowired
    private SupplierService supplierService;
    @Autowired
    private GlobalExceptionHandler handler;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        loginAll();
        today = LocalDate.now(clock);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：11-01 生效的 18.50，11-05 取得到、10-31 取不到（生效日期不晚于那一天）；批量版一样 */
    @Test
    void currentMaterial_effectiveDateDecides() {
        long supplier = supplier();
        long material = material("METER");
        LocalDate start = today.minusDays(14);
        record(materialPrice(supplier, material, null, "18.50", start, null));

        assertThat(priceOf(priceService.currentMaterial(supplier, material, null, start.plusDays(4)))).isEqualByComparingTo("18.50");
        assertThat(priceService.currentMaterial(supplier, material, null, start.minusDays(1))).isEmpty();
        MaterialPriceKey key = new MaterialPriceKey(supplier, material, null);
        assertThat(priceService.currentMaterials(List.of(key), start.minusDays(1))).isEmpty();
        assertThat(priceService.currentMaterials(List.of(key), start).get(key).price()).isEqualByComparingTo("18.50");
    }

    /**
     * 规则：卡其单独录 19.50 以后，卡其取 19.50（这个 SKU 的），藏青取物料层的 19.00；
     * 物料层后来又调到 20.00，卡其照样是它自己的 19.50（SKU 单独定过价就一直用 SKU 的）
     */
    @Test
    void currentMaterial_skuPriceBeforeMaterialLevel() {
        long supplier = supplier();
        long material = material("METER");
        long khaki = sku(material, "KQ");
        long navy = sku(material, "ZQ");
        record(materialPrice(supplier, material, null, "19.00", today.minusDays(10), null));
        record(materialPrice(supplier, material, khaki, "19.50", today.minusDays(8), null));

        PriceRef k = priceService.currentMaterial(supplier, material, khaki, today).orElseThrow();
        assertThat(k.price()).isEqualByComparingTo("19.50");
        assertThat(k.materialSkuId()).isEqualTo(khaki);
        PriceRef n = priceService.currentMaterial(supplier, material, navy, today).orElseThrow();
        assertThat(n.price()).isEqualByComparingTo("19.00");
        assertThat(n.materialSkuId()).isNull();
        assertThat(n.unit()).isEqualTo("METER");

        record(materialPrice(supplier, material, null, "20.00", today.minusDays(5), "测试：物料层调价"));
        assertThat(priceOf(priceService.currentMaterial(supplier, material, khaki, today))).isEqualByComparingTo("19.50");
        assertThat(priceOf(priceService.currentMaterial(supplier, material, navy, today))).isEqualByComparingTo("20.00");
    }

    /** 规则：同一天两条（第二条带原因），取后录的；作废后退回上一条；再作废同一条 → 1002，作废人、时间、原因记下了 */
    @Test
    void sameDayTakesLast_voidFallsBack_secondVoid1002() {
        long supplier = supplier();
        long material = material("METER");
        LocalDate day = today.minusDays(3);
        record(materialPrice(supplier, material, null, "18.50", day, null));
        long later = record(materialPrice(supplier, material, null, "19.00", day, "测试：同一天改口"));
        assertThat(priceOf(priceService.currentMaterial(supplier, material, null, today))).isEqualByComparingTo("19.00");

        priceService.voidPrice(later, "测试：录错了");
        assertThat(priceOf(priceService.currentMaterial(supplier, material, null, today))).isEqualByComparingTo("18.50");
        assertThat(jdbcTemplate.queryForObject("SELECT void_reason || '|' || void_by || '|' || (void_time IS NOT NULL) FROM prc_price WHERE id = ?",
                String.class, later)).isEqualTo("测试：录错了|" + USER + "|true");

        assertThatThrownBy(() -> priceService.voidPrice(later, "测试：再作废一次"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        assertThat(jdbcTemplate.queryForObject("SELECT void_reason FROM prc_price WHERE id = ?", String.class, later))
                .isEqualTo("测试：录错了");
    }

    /** 规则：两个人同时作废同一条：条件更新只让一个改到，另一个 1002（RFC 006 §10） */
    @Test
    void voidConcurrently_onlyOneWins() throws Exception {
        long supplier = supplier();
        long material = material("METER");
        long id = record(materialPrice(supplier, material, null, "18.50", today, null));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> voidIt = () -> {
                loginAll();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    priceService.voidPrice(id, "测试：并发作废");
                    return null;
                } catch (Throwable t) {
                    return t;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            Future<Throwable> a = pool.submit(voidIt);
            Future<Throwable> b = pool.submit(voidIt);
            start.countDown();
            List<Throwable> results = new ArrayList<>(List.of(Optional.ofNullable(a.get(10, TimeUnit.SECONDS)).orElse(new Ok()),
                    Optional.ofNullable(b.get(10, TimeUnit.SECONDS)).orElse(new Ok())));

            assertThat(results).filteredOn(t -> t instanceof Ok).hasSize(1);
            assertThat(results).filteredOn(t -> t instanceof BizException e && e.getCode() == ErrorCodes.STATE_CHANGED).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 规则：物料的采购单位从米改成公斤以后，"18.50 元/米"不再当现价（单条、批量、只看现价都取不到），
     * 历史里标"单位已变"；按公斤录的第一条不用原因
     */
    @Test
    void purchaseUnitChanged_oldPriceIgnored() {
        long supplier = supplier();
        long material = material("METER");
        record(materialPrice(supplier, material, null, "18.50", today.minusDays(10), null));
        jdbcTemplate.update("UPDATE bas_material SET purchase_unit = 'KG', conversion_factor = 3 WHERE id = ?", material);

        assertThat(priceService.currentMaterial(supplier, material, null, today)).isEmpty();
        assertThat(priceService.currentMaterials(List.of(new MaterialPriceKey(supplier, material, null)), today)).isEmpty();
        assertThat(page(material, true, false).getList()).isEmpty();
        PriceRowVO old = page(material, false, false).getList().getFirst();
        assertThat(old.isUnitChanged()).isTrue();
        assertThat(old.getUnit()).isEqualTo("METER");

        record(materialPrice(supplier, material, null, "55.00", today, null));
        PriceRef now = priceService.currentMaterial(supplier, material, null, today).orElseThrow();
        assertThat(now.price()).isEqualByComparingTo("55.00");
        assertThat(now.unit()).isEqualTo("KG");
    }

    /**
     * 规则：加工价按加工方式分开：清加工 38 不会被当成包工包料的价（PRD §6.10 验收 4）。
     * 草稿款式能录（打样就报价），列表里款号、名称由 StyleService 填；款式停用后再录 1000（RFC 006 §3）
     */
    @Test
    void currentProcess_modeSeparated() {
        long factory = supplier();
        long style = style();
        PriceSaveReq cmt = new PriceSaveReq();
        cmt.setType(PriceType.PROCESS);
        cmt.setSupplierId(factory);
        cmt.setStyleId(style);
        cmt.setProcessMode("CMT");
        cmt.setPrice(new BigDecimal("38"));
        cmt.setEffectiveDate(today.minusDays(5));
        record(cmt);

        assertThat(priceService.currentProcess(factory, style, "FULL", today)).isEmpty();
        PriceRef ref = priceService.currentProcess(factory, style, "CMT", today).orElseThrow();
        assertThat(ref.price()).isEqualByComparingTo("38");
        assertThat(ref.unit()).isEqualTo("PIECE");

        PriceQuery byStyle = new PriceQuery();
        byStyle.setStyleId(style);
        byStyle.setCurrent(true);
        PriceRowVO row = priceService.page(byStyle).getList().getFirst();
        assertThat(row.getStyleCode()).startsWith("ITP");
        assertThat(row.getStyleName()).isEqualTo("IT 价格款");

        jdbcTemplate.update("UPDATE sty_style SET status = 'DISABLED' WHERE id = ?", style);
        cmt.setPrice(new BigDecimal("40"));
        assertThat(catchThrowableOfType(BizException.class, () -> record(cmt)))
                .extracting(BizException::getCode).isEqualTo(ErrorCodes.BIZ_ERROR);
    }

    /**
     * 规则：批量版（VALUES + DISTINCT ON）和逐条取的结果一样：两家供应商、两个物料、三个 SKU，
     * 有 SKU 价、物料层的价、以后才生效的、作废的、旧单位的，按四个日期逐个 key 比
     */
    @Test
    void currentMaterials_sameAsOneByOne() {
        long s1 = supplier();
        long s2 = supplier();
        long m1 = material("METER");
        long m2 = material("METER");
        long khaki = sku(m1, "KQ");
        long navy = sku(m1, "ZQ");
        long m2sku = sku(m2, "HE");
        record(materialPrice(s1, m1, null, "19.00", today.minusDays(20), null));
        record(materialPrice(s1, m1, khaki, "19.50", today.minusDays(10), null));
        record(materialPrice(s1, m1, null, "19.20", today.minusDays(5), "测试：调价"));
        long voided = record(materialPrice(s1, m1, navy, "18.80", today.minusDays(4), null));
        priceService.voidPrice(voided, "测试：作废");
        record(materialPrice(s1, m1, null, "21.00", today.plusDays(10), "测试：下个月起"));
        record(materialPrice(s2, m1, khaki, "17.00", today.minusDays(2), null));
        record(materialPrice(s2, m2, null, "3.00", today.minusDays(30), null));
        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, 2.50, 'KG', 13, ?)""", s2, m2, today.minusDays(1));

        List<MaterialPriceKey> keys = new ArrayList<>();
        for (long s : List.of(s1, s2)) {
            for (long m : List.of(m1, m2)) {
                Stream.of((Long) null, khaki, navy, m2sku).forEach(sku -> keys.add(new MaterialPriceKey(s, m, sku)));
            }
        }
        for (LocalDate date : List.of(today.minusDays(25), today.minusDays(7), today, today.plusDays(15))) {
            Map<MaterialPriceKey, PriceRef> oneByOne = new HashMap<>();
            for (MaterialPriceKey k : keys) {
                priceService.currentMaterial(k.supplierId(), k.materialId(), k.materialSkuId(), date).ifPresent(r -> oneByOne.put(k, r));
            }
            assertThat(priceService.currentMaterials(keys, date)).as("日期 %s", date).isEqualTo(oneByOne);
        }
        // 抽查几个：今天 s1 的卡其是自己的 19.50，藏青的作废了、退到物料层 19.20；s2 的 m2 旧单位那条不算
        Map<MaterialPriceKey, PriceRef> now = priceService.currentMaterials(keys, today);
        assertThat(now.get(new MaterialPriceKey(s1, m1, khaki)).price()).isEqualByComparingTo("19.50");
        assertThat(now.get(new MaterialPriceKey(s1, m1, navy)).price()).isEqualByComparingTo("19.20");
        assertThat(now.get(new MaterialPriceKey(s2, m2, null)).price()).isEqualByComparingTo("3.00");
        assertThat(now).doesNotContainKey(new MaterialPriceKey(s2, m1, navy));
    }

    /**
     * 规则："只看现价"每组一行：物料层的那行带上一条（18.50）和以后才生效的（21.00）；卡其的那行是它自己的；
     * 只有以后才生效的那组（第二家）也列出来。历史默认不含作废的，includeVoided 才有；名称由各域填上
     */
    @Test
    void page_currentOnePerGroup_historyWithVoided() {
        long s1 = supplier();
        long s2 = supplier();
        long material = material("METER");
        long khaki = sku(material, "KQ");
        record(materialPrice(s1, material, null, "18.50", today.minusDays(20), null));
        record(materialPrice(s1, material, null, "19.00", today.minusDays(5), "测试：涨价"));
        record(materialPrice(s1, material, null, "21.00", today.plusDays(16), "测试：下个月起"));
        record(materialPrice(s1, material, khaki, "19.50", today.minusDays(3), null));
        long voided = record(materialPrice(s1, material, khaki, "30.00", today.minusDays(1), "测试：录错"));
        priceService.voidPrice(voided, "测试：多打了个 0");
        record(materialPrice(s2, material, null, "22.00", today.plusDays(3), null));

        List<PriceRowVO> current = page(material, true, false).getList();
        assertThat(current).hasSize(3);
        PriceRowVO level = current.stream().filter(r -> r.getSupplierId() == s1 && r.getMaterialSkuId() == null).findFirst().orElseThrow();
        assertThat(level.getPrice()).isEqualByComparingTo("19.00");
        assertThat(level.getPrevPrice()).isEqualByComparingTo("18.50");
        assertThat(level.getNextPrice()).isEqualByComparingTo("21.00");
        assertThat(level.getNextDate()).isEqualTo(today.plusDays(16));
        assertThat(level.getSupplierName()).startsWith("IT价格供应商");
        assertThat(level.getMaterialName()).isEqualTo("IT 价格面料");
        assertThat(level.getCreateByName()).isNull();   // USER 不是真账号，没有昵称
        PriceRowVO k = current.stream().filter(r -> Long.valueOf(khaki).equals(r.getMaterialSkuId())).findFirst().orElseThrow();
        assertThat(k.getPrice()).isEqualByComparingTo("19.50");
        assertThat(k.getPrevPrice()).isNull();
        assertThat(k.getSkuCode()).endsWith("-KQ");
        PriceRowVO future = current.stream().filter(r -> r.getSupplierId() == s2).findFirst().orElseThrow();
        assertThat(future.getEffectiveDate()).isEqualTo(today.plusDays(3));
        assertThat(future.getNextPrice()).isNull();

        assertThat(page(material, false, false).getTotal()).isEqualTo(5);
        PageResult<PriceRowVO> all = page(material, false, true);
        assertThat(all.getTotal()).isEqualTo(6);
        assertThat(all.getList().getFirst().getEffectiveDate()).isEqualTo(today.plusDays(16));
        PriceRowVO v = all.getList().stream().filter(r -> r.getId() == voided).findFirst().orElseThrow();
        assertThat(v.getVoided()).isTrue();
        assertThat(v.getVoidReason()).isEqualTo("测试：多打了个 0");

        PriceQuery bySupplier = new PriceQuery();
        bySupplier.setSupplierId(s1);
        bySupplier.setCurrent(true);
        assertThat(priceService.page(bySupplier).getTotal()).isEqualTo(2);
    }

    /** 规则：有价格记录的物料、供应商删不掉（作废的也算），外键拦住返回 1005"已被价格档案引用" */
    @Test
    void delete_materialOrSupplierWithPrice_1005() {
        long supplier = supplier();
        long material = material("METER");
        long id = record(materialPrice(supplier, material, null, "18.50", today, null));
        priceService.voidPrice(id, "测试：作废的也拦");

        assertThat(deleteFails(() -> materialService.delete(material), "/api/materials/" + material).getMsg())
                .isEqualTo("已被价格档案引用，不能删除，只能停用");
        assertThat(deleteFails(() -> supplierService.delete(supplier), "/api/suppliers/" + supplier).getCode())
                .isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bas_material WHERE id = ?", Integer.class, material)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sup_supplier WHERE id = ?", Integer.class, supplier)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 造数据

    private R<Void> deleteFails(Runnable delete, String uri) {
        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class, delete::run);
        R<Void> r = handler.handleDataIntegrity(e, new MockHttpServletRequest("DELETE", uri));
        assertThat(r.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        return r;
    }

    private PageResult<PriceRowVO> page(long materialId, boolean current, boolean includeVoided) {
        PriceQuery q = new PriceQuery();
        q.setMaterialId(materialId);
        q.setCurrent(current);
        q.setIncludeVoided(includeVoided);
        return priceService.page(q);
    }

    private long record(PriceSaveReq req) {
        return priceService.record(req);
    }

    private static PriceSaveReq materialPrice(long supplier, long material, Long sku, String price, LocalDate date, String reason) {
        PriceSaveReq req = new PriceSaveReq();
        req.setType(PriceType.MATERIAL);
        req.setSupplierId(supplier);
        req.setMaterialId(material);
        req.setMaterialSkuId(sku);
        req.setPrice(new BigDecimal(price));
        req.setEffectiveDate(date);
        req.setReason(reason);
        return req;
    }

    private static BigDecimal priceOf(Optional<PriceRef> ref) {
        return ref.orElseThrow().price();
    }

    /** 合作中、税率 13 的供应商 */
    private long supplier() {
        String suffix = suffix();
        return jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 13) RETURNING id""", Long.class,
                "ITP" + suffix, "IT价格供应商有限公司" + suffix, "IT价格供应商" + suffix);
    }

    /** 一个启用的面料，库存单位米，采购单位给定；不带 SKU */
    private long material(String purchaseUnit) {
        String suffix = suffix();
        Long category = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT价格分类" + suffix);
        return jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit)
                VALUES (?, 'IT 价格面料', ?, 'FABRIC', 'METER', ?) RETURNING id""", Long.class, "ITP" + suffix, category, purchaseUnit);
    }

    /** 物料的一个 SKU，不带颜色，用规格码区分 */
    private long sku(long material, String specCode) {
        String code = jdbcTemplate.queryForObject("SELECT code FROM bas_material WHERE id = ?", String.class, material);
        return jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_sku (material_id, code, spec, spec_code) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, material, code + "-" + specCode, specCode, specCode);
    }

    /** 一个草稿款式（只为了加工价有外键可指），品类、尺码组随手建 */
    private long style() {
        String suffix = suffix();
        Long category = jdbcTemplate.queryForObject(
                "INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT价格品类" + suffix);
        Long sizeGroup = jdbcTemplate.queryForObject(
                "INSERT INTO bas_size_group (name) VALUES (?) RETURNING id", Long.class, "IT价格尺码" + suffix);
        return jdbcTemplate.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id)
                VALUES (?, 'IT 价格款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?) RETURNING id""",
                Long.class, "ITP" + suffix, category, sizeGroup);
    }

    private static void loginAll() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(USER, "jti-it"), null, Stream.of("price:price:list", "price:material:edit", "price:process:edit")
                .map(SimpleGrantedAuthority::new).toList()));
    }

    private static String suffix() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
    }

    /** 并发测试里"没出错"的占位 */
    private static final class Ok extends RuntimeException {
    }
}
