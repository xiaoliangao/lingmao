package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.inventory.dto.InvDocFromSourceReq;
import com.lingmao.scm.module.inventory.dto.InvDocItemReq;
import com.lingmao.scm.module.inventory.dto.InvDocSaveReq;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.purchase.dto.PurchaseCreateReq;
import com.lingmao.scm.module.purchase.dto.PurchaseItemReq;
import com.lingmao.scm.module.purchase.entity.PurchaseType;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #06/T05：每项均真实建采购单、提交审核、采购收/退货过账；没有孤立分摊器替代业务断言。 */
@IntegrationTest
class A1PurchasePrecisionIT {
    private static final List<String> PERMISSIONS = List.of("purchase:order:list", "purchase:order:add",
            "purchase:order:submit", "purchase:order:audit", "inventory:doc:list", "inventory:doc:add",
            "inventory:doc:audit", "inventory:doc:edit", "techpack:cost:query");
    @Autowired PurchaseOrderService purchases;
    @Autowired InvDocService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired JsonMapper json;

    @AfterEach void logout() { TestIdentity.clear(); }

    @Test
    void threeFractionalReceiptsAndThreeReturnsEndAtExactQuantityAndMoney() throws Exception {
        Fixture f = fixture("0.333333", "0.0050", "3");
        for (int i = 0; i < 3; i++) post(f, InvDocType.PURCHASE_IN, "1");
        assertThat(stock(f)).isEqualByComparingTo("1.000");
        assertThat(netAmount(f)).isEqualByComparingTo("0.02");
        assertThat(jdbc.queryForList("SELECT change_qty FROM inv_stock_log WHERE material_sku_id=? ORDER BY id", BigDecimal.class, f.sku()))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("0.333"), new BigDecimal("0.334"), new BigDecimal("0.333"));
        assertThat(jdbc.queryForList("SELECT amount FROM inv_stock_log WHERE material_sku_id=? ORDER BY id", BigDecimal.class, f.sku()))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("0.01"), new BigDecimal("0.00"), new BigDecimal("0.01"));
        for (int i = 0; i < 3; i++) post(f, InvDocType.PURCHASE_RETURN, "1");
        assertThat(stock(f)).isEqualByComparingTo("0.000");
        assertThat(netAmount(f)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT received_qty-returned_qty FROM pur_order_item WHERE id=?", BigDecimal.class, f.sourceItem())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inv_stock_log WHERE material_sku_id=? AND purchase_rounding_version=1", Integer.class, f.sku())).isEqualTo(6);
        Files.createDirectories(Path.of("target/a1-production"));
        Files.writeString(Path.of("target/a1-production/t05-purchase-batches.json"), json.writeValueAsString(
                jdbc.queryForList("SELECT change_type,change_qty,amount,balance_qty,purchase_rounding_version FROM inv_stock_log WHERE material_sku_id=? ORDER BY id", f.sku())));
    }

    @Test
    void sameSourceConcurrentAuditsSerializeCumulativeAmountsUnderSourceLock() throws Exception {
        Fixture f = fixture("0.333333", "0.0050", "3");
        List<Long> drafts = new ArrayList<>();
        for (int i = 0; i < 3; i++) drafts.add(inventory.fromSource(BizType.PURCHASE_ORDER, f.purchase(), request(f, InvDocType.PURCHASE_IN, false, List.of("1"))).id());
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (long id : drafts) futures.add(pool.submit((Callable<Void>) () -> {
                login(f.user());
                TestIdentity.company(f.company());
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try { audit(id, new DocActionReq(0, null)); return null; }
                finally { TestIdentity.clear(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertThat(stock(f)).isEqualByComparingTo("1");
        assertThat(netAmount(f)).isEqualByComparingTo("0.02");
        assertThat(jdbc.queryForObject("SELECT received_qty FROM pur_order_item WHERE id=?", BigDecimal.class, f.sourceItem())).isEqualByComparingTo("3");
    }

    @Test
    void multiDyeLotsOnOneSourceLineAllocateInStableSequence() {
        Fixture f = fixture("0.333333", "0.0050", "3");
        long doc = inventory.fromSource(BizType.PURCHASE_ORDER, f.purchase(), request(f, InvDocType.PURCHASE_IN, false, List.of("1", "1", "1"))).id();
        audit(doc, new DocActionReq(0, null));
        assertThat(jdbc.queryForList("SELECT qty FROM inv_doc_item WHERE doc_id=? ORDER BY seq", BigDecimal.class, doc))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("0.333"), new BigDecimal("0.334"), new BigDecimal("0.333"));
        assertThat(jdbc.queryForList("SELECT amount FROM inv_doc_item WHERE doc_id=? ORDER BY seq", BigDecimal.class, doc))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("0.01"), new BigDecimal("0"), new BigDecimal("0.01"));
        assertThat(stock(f)).isEqualByComparingTo("1");
    }

    @Test
    void reversalCopiesOriginalEventAndReauditRestoresCumulativeTotal() {
        Fixture f = fixture("0.333333", "0.0050", "3");
        long first = post(f, InvDocType.PURCHASE_IN, "1");
        long middle = post(f, InvDocType.PURCHASE_IN, "1");
        long last = post(f, InvDocType.PURCHASE_IN, "1");
        long original = jdbc.queryForObject("SELECT id FROM inv_stock_log WHERE inv_doc_id=?", Long.class, middle);
        inventory.act(middle, "unaudit", new DocActionReq(inventory.detail(middle).getVersion(), "分批反审"));
        assertThat(stock(f)).isEqualByComparingTo("0.666");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inv_stock_log r JOIN inv_stock_log o ON o.id=r.reversal_of_log_id
                WHERE o.id=? AND r.change_qty=-o.change_qty AND r.amount=-o.amount
                  AND r.purchase_rounding_version=o.purchase_rounding_version
                """, Integer.class, original)).isEqualTo(1);
        audit(middle, new DocActionReq(inventory.detail(middle).getVersion(), null));
        assertThat(stock(f)).isEqualByComparingTo("1.000");
        assertThat(netAmount(f)).isEqualByComparingTo("0.02");
        assertThat(jdbc.queryForObject("SELECT change_qty FROM inv_stock_log WHERE id=?", BigDecimal.class, original)).isEqualByComparingTo("0.334");
        // 逆序全部冲销，原始金额一分不改；重审过的中间单冲销的是新事件。
        for (long id : List.of(last, middle, first)) inventory.act(id, "unaudit", new DocActionReq(inventory.detail(id).getVersion(), "清空本次入库"));
        assertThat(stock(f)).isEqualByComparingTo("0");
        assertThat(netAmount(f)).isEqualByComparingTo("0");
    }

    @Test
    void incompleteLegacySnapshotIsRejectedWithoutChangingExistingEventsOrSourceCounters() {
        Fixture f = fixture("0.333333", "0.0050", "3");
        long first = post(f, InvDocType.PURCHASE_IN, "1");
        jdbc.update("UPDATE inv_stock_log SET purchase_rounding_version=NULL WHERE inv_doc_id=?", first);
        long next = inventory.fromSource(BizType.PURCHASE_ORDER, f.purchase(), request(f, InvDocType.PURCHASE_IN, false, List.of("1"))).id();
        assertThatThrownBy(() -> audit(next, new DocActionReq(0, null)))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(1002))
                .hasMessageContaining("历史账");
        assertThat(stock(f)).isEqualByComparingTo("0.333");
        assertThat(netAmount(f)).isEqualByComparingTo("0.01");
        assertThat(jdbc.queryForObject("SELECT received_qty FROM pur_order_item WHERE id=?", BigDecimal.class, f.sourceItem())).isEqualByComparingTo("1");
        assertThat(inventory.detail(next).getStatus()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inv_stock_log WHERE inv_doc_id=?", Integer.class, next)).isZero();
    }

    @Test
    void legacyDraftMustReloadFrozenSourcePriceBeforePosting() {
        Fixture f = fixture("0.333333", "0.0050", "3");
        long draft = inventory.fromSource(BizType.PURCHASE_ORDER, f.purchase(), request(f, InvDocType.PURCHASE_IN, false, List.of("1"))).id();
        jdbc.update("UPDATE inv_doc_item SET purchase_price=NULL WHERE doc_id=?", draft);
        assertThatThrownBy(() -> audit(draft, new DocActionReq(0, null))).isInstanceOf(BizException.class).hasMessageContaining("重新载入来源");
        var detail = inventory.detail(draft);
        InvDocItemReq line = new InvDocItemReq();
        line.setId(detail.getItems().getFirst().getId());
        line.setSourceItemId(f.sourceItem());
        line.setMaterialSkuId(f.sku());
        line.setPurchaseQty(BigDecimal.ONE);
        InvDocSaveReq saved = new InvDocSaveReq();
        saved.setVersion(detail.getVersion());
        saved.setCompanyId(f.company());
        saved.setDocDate(detail.getDocDate());
        saved.setToWarehouseId(f.warehouse());
        saved.setItems(List.of(line));
        int version = inventory.update(draft, saved);
        audit(draft, new DocActionReq(version, null));
        assertThat(jdbc.queryForObject("SELECT purchase_price FROM inv_doc_item WHERE doc_id=?", BigDecimal.class, draft)).isEqualByComparingTo("0.0050");
        assertThat(stock(f)).isEqualByComparingTo("0.333");
    }

    @Test
    void tailAdjustmentCannotCreateZeroOrReverseQuantity() {
        Fixture f = fixture("0.500000", "0.0050", "0.002");
        long draft = inventory.fromSource(BizType.PURCHASE_ORDER, f.purchase(), request(f, InvDocType.PURCHASE_IN, false, List.of("0.001", "0.001"))).id();
        assertThatThrownBy(() -> audit(draft, new DocActionReq(0, null))).isInstanceOf(BizException.class).hasMessageContaining("合并小批");
        assertThat(jdbc.queryForObject("SELECT received_qty FROM pur_order_item WHERE id=?", BigDecimal.class, f.sourceItem())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inv_stock_log WHERE material_sku_id=?", Integer.class, f.sku())).isZero();
        assertThat(inventory.detail(draft).getStatus()).isEqualTo("DRAFT");
    }

    private Fixture fixture(String factor, String price, String quantity) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long company = jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES (?,?,'A1 精度') RETURNING id",
                Long.class, suffix.substring(0, 6).toUpperCase(), "A1 精度 " + suffix);
        long user = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname,default_company_id) VALUES (?,'x','A1 精度',?) RETURNING id", Long.class, "a1-precision-" + suffix, company);
        TestIdentity.grant(jdbc, user, PERMISSIONS);
        TestIdentity.company(company);
        login(user);
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES (?,?,'MATERIAL') RETURNING id", Long.class, "A1P" + suffix, "A1 精度仓 " + suffix);
        long supplier = jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name,status,settle_method,tax_pct) VALUES (?,?,'A1','ACTIVE','MONTHLY',13) RETURNING id", Long.class, "A1P" + suffix, "A1 精度供应商 " + suffix);
        long category = jdbc.queryForObject("INSERT INTO bas_material_category(name,level) VALUES (?,1) RETURNING id", Long.class, "A1 精度 " + suffix);
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit,conversion_factor,
                                         default_supplier_id,ref_price,reserve_pct)
                VALUES (?,'A1 精度料',?,'FABRIC','METER','KG',?::numeric,?,?::numeric,0) RETURNING id
                """, Long.class, "A1P" + suffix, category, factor, supplier, price);
        long sku = jdbc.queryForObject("INSERT INTO bas_material_sku(material_id,code) VALUES (?,?) RETURNING id", Long.class, material, "A1P" + suffix + "-A");
        PurchaseCreateReq req = new PurchaseCreateReq();
        req.setCompanyId(company);
        req.setSupplierId(supplier);
        req.setPurchaseType(PurchaseType.STOCK);
        req.setProductLine("APPAREL");
        req.setBuyerId(user);
        req.setOrderDate(LocalDate.now(clock));
        req.setWarehouseId(warehouse);
        req.setSettleMethod("MONTHLY");
        req.setTaxPct(new BigDecimal("13"));
        PurchaseItemReq line = new PurchaseItemReq();
        line.setMaterialSkuId(sku);
        line.setQty(new BigDecimal(quantity));
        line.setExpectDate(LocalDate.now(clock).plusDays(1));
        req.setItems(List.of(line));
        long purchase = purchases.create(req).id();
        purchases.act(purchase, "submit", new DocActionReq(0, null));
        TestIdentity.asAuditor(jdbc, () -> purchases.act(purchase, "audit", new DocActionReq(1, null)));
        long sourceItem = purchases.detail(purchase).getItems().getFirst().getId();
        return new Fixture(company, user, warehouse, sku, purchase, sourceItem);
    }

    private InvDocFromSourceReq request(Fixture f, InvDocType type, boolean post, List<String> quantities) {
        InvDocFromSourceReq req = new InvDocFromSourceReq();
        req.setClientActionId(UUID.randomUUID());
        req.setDocType(type);
        req.setPost(post);
        req.setItems(IntStreamItems(f, quantities));
        return req;
    }

    private static List<InvDocFromSourceReq.Item> IntStreamItems(Fixture f, List<String> quantities) {
        List<InvDocFromSourceReq.Item> lines = new ArrayList<>();
        for (int n = 0; n < quantities.size(); n++) {
            var line = new InvDocFromSourceReq.Item();
            line.setSourceItemId(f.sourceItem());
            line.setMaterialSkuId(f.sku());
            line.setQty(new BigDecimal(quantities.get(n)));
            line.setDyeLot("A1-LOT-" + (n + 1));
            lines.add(line);
        }
        return lines;
    }

    /** 按源单建一张草稿，换审核人审：建单时顺手过账（post）是自己审自己，甲方 11-21 答 Q10 起不行 */
    private long post(Fixture f, InvDocType type, String qty) {
        long id = inventory.fromSource(BizType.PURCHASE_ORDER, f.purchase(), request(f, type, false, List.of(qty))).id();
        audit(id, new DocActionReq(0, null));
        return id;
    }

    private void audit(long id, DocActionReq req) { TestIdentity.asAuditor(jdbc, () -> inventory.act(id, "audit", req)); }
    private BigDecimal stock(Fixture f) { return jdbc.queryForObject("SELECT qty FROM inv_stock WHERE warehouse_id=? AND material_sku_id=?", BigDecimal.class, f.warehouse(), f.sku()); }
    private BigDecimal netAmount(Fixture f) { return jdbc.queryForObject("SELECT sum(amount) FROM inv_stock_log WHERE material_sku_id=?", BigDecimal.class, f.sku()); }
    private static void login(long user) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(user, "a1-precision"), null, TestIdentity.withLines(PERMISSIONS).stream().map(SimpleGrantedAuthority::new).toList())); }
    private record Fixture(long company, long user, long warehouse, long sku, long purchase, long sourceItem) { }
}
