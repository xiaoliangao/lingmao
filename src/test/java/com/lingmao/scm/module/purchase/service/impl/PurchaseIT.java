package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.inventory.dto.InvDocFromSourceReq;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderItemReq;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.purchase.dto.FromMrpReq;
import com.lingmao.scm.module.purchase.dto.FromReorderReq;
import com.lingmao.scm.module.purchase.dto.MrpCalcDetailVO;
import com.lingmao.scm.module.purchase.dto.MrpCreateReq;
import com.lingmao.scm.module.purchase.dto.MrpItemVO;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.PendingArrivalRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseCreateReq;
import com.lingmao.scm.module.purchase.dto.PurchaseDetailVO;
import com.lingmao.scm.module.purchase.dto.PurchaseItemReq;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseSaveReq;
import com.lingmao.scm.module.purchase.dto.ReorderPickItem;
import com.lingmao.scm.module.purchase.dto.ReorderRowVO;
import com.lingmao.scm.module.purchase.dto.SkuSupplyVO;
import com.lingmao.scm.module.purchase.entity.PurchaseType;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.module.purchase.service.SupplyService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 算料采购在真库上（docs/api/03）：XML 里的需求展开、可用量、列表、待到料都要真库才测得出；
 * 锁的顺序、外键（采购行被算料单用着时删不掉）、过账回写也是
 */
@IntegrationTest
class PurchaseIT {

    private static final List<String> PERMS = List.of("order:order:list", "order:order:add", "order:order:submit",
            "order:order:audit", "purchase:mrp:list", "purchase:mrp:add", "purchase:mrp:edit", "purchase:mrp:remove",
            "purchase:order:list", "purchase:order:add", "purchase:order:edit", "purchase:order:remove", "purchase:order:submit",
            "purchase:order:audit", "purchase:order:close", "purchase:order:void", "purchase:order:export", "inventory:doc:add",
            "inventory:doc:audit");

    @Autowired
    private MrpService mrpService;
    @Autowired
    private PurchaseOrderService purchaseOrderService;
    @Autowired
    private SupplyService supplyService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private InvDocService invDocService;
    @Autowired
    private ReferenceService referenceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "jti-it"), null, PERMS.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /**
     * PRD §6.6.1、§6.6.2 验收 1、2 的口径，数换小：订单 100 件 × 1.5 米 = 150 米；物料仓 20 米；
     * 备货单 A 100 米赶得上（扣），备货单 B 50 米赶不上（列出来、不扣）；净需求 30，生成采购单 C。
     * 然后：C 改数量后已转采购跟着变；A 被已确认的算料单用着不能反审核（1005）；A 入库（超收 5% 以上 1004）、到齐；
     * 待到料、齐料、下游单据；重新算料确认后，C 的行挪到新单上，旧单关闭
     */
    @Test
    void mrpConvertAuditReceiveRecalc() {
        Fixture f = fixture(null);
        jdbcTemplate.update("INSERT INTO inv_stock (warehouse_id, material_sku_id, qty) VALUES (?, ?, 20)", f.warehouse, f.sku);
        long orderId = approvedOrder(f, LocalDate.now().plusDays(60));
        long poA = approvedStockPo(f, "100", LocalDate.now().plusDays(3));
        long poB = approvedStockPo(f, "50", LocalDate.now().plusDays(200));

        MrpCreateReq create = new MrpCreateReq();
        create.setOrderIds(List.of(orderId));
        DocSaved calc = mrpService.create(create);
        MrpCalcDetailVO d = mrpService.detail(calc.id());
        assertThat(d.getNeedDate()).isAfter(LocalDate.now().plusDays(3)).isBefore(LocalDate.now().plusDays(200));
        MrpItemVO row = d.getItems().getFirst();
        assertThat(d.getItems()).hasSize(1);
        assertThat(row.getBlock()).isEqualTo("PURCHASE");
        assertThat(row.getGrossQty()).isEqualByComparingTo("150");
        assertThat(row.getNeedQty()).isEqualByComparingTo("150");
        assertThat(row.getUseStockQty()).isEqualByComparingTo("20");
        assertThat(row.getAvailTransitQty()).isEqualByComparingTo("100");
        assertThat(row.getUseTransitQty()).isEqualByComparingTo("100");
        assertThat(row.getLateTransitQty()).isEqualByComparingTo("50");
        assertThat(row.getNetQty()).isEqualByComparingTo("30");
        assertThat(row.getSuggestQty()).isEqualByComparingTo("30");
        assertThat(row.getRefPrice()).isEqualByComparingTo("20");
        assertThat(row.getSupplierId()).isEqualTo(f.supplier);
        assertThat(row.getTransitUses()).singleElement().satisfies(u -> assertThat(u.qty()).isEqualByComparingTo("100"));
        assertThat(row.getLateTransits()).singleElement().satisfies(l -> assertThat(l.freeQty()).isEqualByComparingTo("50"));

        mrpService.confirm(calc.id(), new DocActionReq(d.getVersion(), null));
        assertThatThrownBy(() -> purchaseOrderService.act(poA, "unaudit", new DocActionReq(purchaseOrderService.detail(poA).getVersion(), null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM));

        // 生成采购单 C：一家供应商一张，带算料行、默认单价
        d = mrpService.detail(calc.id());
        FromMrpReq from = new FromMrpReq();
        from.setCalcId(calc.id());
        from.setItemIds(List.of(row.getId()));
        from.setVersion(d.getVersion());
        List<DocSaved> created = purchaseOrderService.fromMrp(from);
        assertThat(created).hasSize(1);
        long poC = created.getFirst().id();
        PurchaseDetailVO c = purchaseOrderService.detail(poC);
        assertThat(c.getPurchaseType()).isEqualTo("ORDER");
        assertThat(c.getCalcId()).isEqualTo(calc.id());
        assertThat(c.getSettleMethod()).isEqualTo("MONTHLY");
        assertThat(c.getItems()).singleElement().satisfies(i -> {
            assertThat(i.getQty()).isEqualByComparingTo("30");
            assertThat(i.getPrice()).isEqualByComparingTo("20");
            assertThat(i.getCalcItemId()).isEqualTo(row.getId());
            assertThat(i.getOrderNos()).isNotEmpty();
        });
        assertThat(mrpService.detail(calc.id()).getItems().getFirst()).satisfies(i -> {
            assertThat(i.getConvertedQty()).isEqualByComparingTo("30");
            assertThat(i.isCanConvert()).isFalse();
        });

        // C 改成 35：已转采购跟着变；物料不能换、单价改了要写原因
        PurchaseSaveReq save = saveReq(c, "35");
        int version = purchaseOrderService.update(poC, save);
        assertThat(mrpService.detail(calc.id()).getItems().getFirst().getConvertedQty()).isEqualByComparingTo("35");
        purchaseOrderService.act(poC, "submit", new DocActionReq(version, null));
        purchaseOrderService.act(poC, "audit", new DocActionReq(version + 1, null));
        assertThat(purchaseOrderService.detail(poC).getStatus()).isEqualTo("APPROVED");

        // 待到料：三行按哪天到排；C 是已确认的算料单生成的，有要料日期
        PendingArrivalQuery pq = new PendingArrivalQuery();
        pq.setSupplierId(f.supplier);
        List<PendingArrivalRowVO> pending = purchaseOrderService.pendingItems(pq).getList();
        assertThat(pending).extracting(PendingArrivalRowVO::getPurchaseOrderId).containsExactly(poA, poC, poB);
        assertThat(pending.get(1)).satisfies(r -> {
            assertThat(r.getPendingQty()).isEqualByComparingTo("35");
            assertThat(r.getCalcNo()).isEqualTo(calc.no());
            assertThat(r.getNeedDate()).isNotNull();
            assertThat(r.getSkuCode()).isEqualTo(f.skuCode);
        });
        assertThat(pending.get(2).getNeedDate()).isNull();
        assertThat(purchaseOrderService.pendingCounts(pq).all()).isEqualTo(3);

        // A 入库：60，再 50 超过 105（溢收 5%）1004，再 45 到齐
        receive(f, poA, "60");
        assertThat(purchaseOrderService.detail(poA).getStatus()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> receive(f, poA, "50"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT));
        receive(f, poA, "45");
        PurchaseDetailVO a = purchaseOrderService.detail(poA);
        assertThat(a.getStatus()).isEqualTo("DONE");
        assertThat(a.getReceivedQty()).isEqualByComparingTo("105");
        assertThat(a.getStockDocs()).hasSize(2);
        assertThat(referenceService.referrers(BizType.PURCHASE_ORDER, poA)).extracting(DocRef::type)
                .contains(BizType.INV_DOC, BizType.MRP_CALC);

        // B 结案、取消结案
        int vb = purchaseOrderService.detail(poB).getVersion();
        purchaseOrderService.act(poB, "close", new DocActionReq(vb, null));
        purchaseOrderService.act(poB, "reopen", new DocActionReq(vb + 1, null));
        assertThat(purchaseOrderService.detail(poB).getStatus()).isEqualTo("APPROVED");

        // 齐料、订单的下游、列表、导出
        assertThat(mrpService.kitting(List.of(orderId))).singleElement().satisfies(k -> {
            assertThat(k.status()).isNotEqualTo("NONE");
            assertThat(k.calcIds()).containsExactly(calc.id());
        });
        assertThat(referenceService.referrers(BizType.ORDER, orderId)).extracting(DocRef::id).contains(calc.id(), poC);
        PurchaseQuery q = new PurchaseQuery();
        q.setSupplierId(f.supplier);
        assertThat(purchaseOrderService.page(q).getList()).hasSize(3)
                .allSatisfy(r -> assertThat(r.getFirstItem().getSkuCode()).isEqualTo(f.skuCode));
        assertThat(purchaseOrderService.export(q)).isNotEmpty();

        // 重新算料：新单确认后旧单关闭（RECALC），C 的行挪到新单的同一个 SKU 上
        DocSaved next = mrpService.recalc(calc.id(), new DocActionReq(mrpService.detail(calc.id()).getVersion(), null));
        MrpCalcDetailVO n = mrpService.detail(next.id());
        assertThat(n.getPrevCalcId()).isEqualTo(calc.id());
        mrpService.confirm(next.id(), new DocActionReq(n.getVersion(), null));
        assertThat(mrpService.detail(calc.id())).satisfies(old -> {
            assertThat(old.getStatus()).isEqualTo("CLOSED");
            assertThat(old.getCloseReason()).isEqualTo("RECALC");
        });
        long newItem = mrpService.detail(next.id()).getItems().getFirst().getId();
        assertThat(purchaseOrderService.detail(poC).getItems().getFirst().getCalcItemId()).isEqualTo(newItem);
        assertThat(mrpService.detail(next.id()).getItems().getFirst().getConvertedQty()).isEqualByComparingTo("35");
    }

    @Test
    void reverseReturnAfterReplacementCannotBreakReceiptCap_andRollsBackAll() {
        Fixture f = fixture(null);
        long po = approvedStockPo(f, "100", LocalDate.now().plusDays(3));
        receive(f, po, "100");
        long returned = invDocService.fromSource(BizType.PURCHASE_ORDER, po, stockReq(f, po, InvDocType.PURCHASE_RETURN, "50")).id();
        long replacement = invDocService.fromSource(BizType.PURCHASE_ORDER, po, stockReq(f, po, InvDocType.PURCHASE_IN, "50")).id();
        long logs = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inv_stock_log WHERE material_sku_id=?", Long.class, f.sku);
        int version = invDocService.detail(returned).getVersion();
        assertThatThrownBy(() -> invDocService.act(returned, "unaudit", new DocActionReq(version, null)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(1004);
        assertThat(invDocService.detail(returned).getStatus()).isEqualTo("APPROVED");
        assertThat(invDocService.detail(returned).getVersion()).isEqualTo(version);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inv_stock_log WHERE material_sku_id=?", Long.class, f.sku)).isEqualTo(logs);
        assertThat(purchaseOrderService.detail(po).getItems().getFirst().getReturnedQty()).isEqualByComparingTo("50");
        assertThat(purchaseOrderService.detail(po).getReceivedQty()).isEqualByComparingTo("100");
        invDocService.act(replacement, "unaudit", new DocActionReq(invDocService.detail(replacement).getVersion(), null));
        invDocService.act(returned, "unaudit", new DocActionReq(version, null));
        assertThat(purchaseOrderService.detail(po).getItems().getFirst().getReturnedQty()).isZero();
        assertThat(purchaseOrderService.detail(po).getReceivedQty()).isEqualByComparingTo("100");
    }

    @Test
    void fromSourceRetryIsIdempotentIncludingConcurrentCallsAndRollback() throws Exception {
        Fixture f = fixture(null);
        long po = approvedStockPo(f, "100", LocalDate.now().plusDays(3));
        InvDocFromSourceReq req = stockReq(f, po, InvDocType.PURCHASE_IN, "60");
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<com.lingmao.scm.module.inventory.dto.InvDocCreatedVO> action = () -> {
                loginAs(f.user); start.await();
                try { return invDocService.fromSource(BizType.PURCHASE_ORDER, po, req); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var one = pool.submit(action); var two = pool.submit(action); start.countDown();
            assertThat(one.get(15, TimeUnit.SECONDS)).isEqualTo(two.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inv_doc WHERE source_type='PURCHASE_ORDER' AND source_id=?", Long.class, po)).isEqualTo(1);
        assertThat(purchaseOrderService.detail(po).getReceivedQty()).isEqualByComparingTo("60");
        req.getItems().getFirst().setQty(new BigDecimal("61"));
        assertThatThrownBy(() -> invDocService.fromSource(BizType.PURCHASE_ORDER, po, req))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(1002);
        InvDocFromSourceReq rejected = stockReq(f, po, InvDocType.PURCHASE_IN, "50");
        assertThatThrownBy(() -> invDocService.fromSource(BizType.PURCHASE_ORDER, po, rejected))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(1004);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_action_request WHERE client_action_id=?", Long.class, rejected.getClientActionId())).isZero();
        rejected.getItems().getFirst().setQty(new BigDecimal("30"));
        var retry = invDocService.fromSource(BizType.PURCHASE_ORDER, po, rejected);
        assertThat(invDocService.fromSource(BizType.PURCHASE_ORDER, po, rejected)).isEqualTo(retry);
        assertThat(purchaseOrderService.detail(po).getReceivedQty()).isEqualByComparingTo("90");
    }

    /** RFC010：等待采购源单锁的过账事务不能先占库存行，避免与采购审核的锁序相反。 */
    @Test
    void postingWaitsForSourceBeforeTakingStockLock() throws Exception {
        Fixture f = fixture(null);
        long po = approvedStockPo(f, "100", LocalDate.now().plusDays(3));
        jdbcTemplate.update("INSERT INTO inv_stock (warehouse_id, material_sku_id, qty) VALUES (?, ?, 0)", f.warehouse, f.sku);
        InvDocFromSourceReq req = stockReq(f, po, InvDocType.PURCHASE_IN, "20");
        req.setPost(false);
        long doc = invDocService.fromSource(BizType.PURCHASE_ORDER, po, req).id();
        assertWaitsOnPoBeforeRow(f.user, po, () -> invDocService.act(doc, "audit", new DocActionReq(0, null)), """
                SELECT id FROM inv_stock WHERE warehouse_id=? AND material_sku_id=?
                  AND owner_type='OWN' AND owner_id=0 AND location_id=0 FOR UPDATE NOWAIT""", f.warehouse, f.sku);
        assertThat(purchaseOrderService.detail(po).getReceivedQty()).isEqualByComparingTo("20");
        assertThat(jdbcTemplate.queryForObject("SELECT qty FROM inv_stock WHERE warehouse_id=? AND material_sku_id=?",
                BigDecimal.class, f.warehouse, f.sku)).isEqualByComparingTo("20");
    }

    /** RFC010：重算确认必须先锁待改挂采购行的单头，再锁行，不能与采购审核形成头/行反序。 */
    @Test
    void recalcWaitsForPurchaseHeadBeforeRepointingItsItems() throws Exception {
        Fixture f = fixture(null);
        long orderId = approvedOrder(f, LocalDate.now().plusDays(60));
        MrpCreateReq req = new MrpCreateReq(); req.setOrderIds(List.of(orderId));
        long old = mrpService.create(req).id();
        var original = mrpService.detail(old);
        FromMrpReq from = new FromMrpReq(); from.setCalcId(old); from.setVersion(original.getVersion());
        from.setItemIds(List.of(original.getItems().getFirst().getId()));
        long po = purchaseOrderService.fromMrp(from).getFirst().id();
        long next = mrpService.recalc(old, new DocActionReq(mrpService.detail(old).getVersion(), null)).id();
        int version = mrpService.detail(next).getVersion();
        assertWaitsOnPoBeforeRow(f.user, po, () -> mrpService.confirm(next, new DocActionReq(version, null)),
                "SELECT id FROM pur_order_item WHERE purchase_order_id=? FOR UPDATE NOWAIT", po);
        assertThat(purchaseOrderService.detail(po).getItems().getFirst().getCalcItemId())
                .isEqualTo(mrpService.detail(next).getItems().getFirst().getId());
        assertThat(mrpService.detail(old).getStatus()).isEqualTo("CLOSED");
    }

    private void assertWaitsOnPoBeforeRow(long user, long po, Runnable action, String probeSql, long... keys) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> workerPid = new CompletableFuture<>();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next(); blockerPid = rows.getInt(1);
            }
            try (var statement = blocker.prepareStatement("SELECT id FROM pur_order WHERE id=? FOR UPDATE")) {
                statement.setLong(1, po); statement.executeQuery().close();
            }
            var posting = pool.submit(() -> {
                loginAs(user);
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout='8s'");
                        workerPid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        action.run();
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                int pid = workerPid.get(5, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    blocked = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                            "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blockerPid, pid));
                    if (blocked) break;
                    Thread.sleep(20);
                }
                assertThat(blocked).as("worker must be waiting on the held purchase head").isTrue();
                try (var probe = dataSource.getConnection()) {
                    probe.setAutoCommit(false);
                    try (var statement = probe.prepareStatement(probeSql)) {
                        for (int i = 0; i < keys.length; i++) statement.setLong(i + 1, keys[i]);
                        try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                    } finally { probe.rollback(); }
                }
            } finally { blocker.rollback(); }
            posting.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** RFC010：算料调用同一实际 SKU 完整性门禁，档案停用不能通过需求展开 SQL 悄悄漏料。 */
    @Test
    void mrpRejectsDisabledMaterialSkuBeforeWritingCalc() {
        Fixture f = fixture(null);
        long orderId = approvedOrder(f, LocalDate.now().plusDays(60));
        jdbcTemplate.update("UPDATE bas_material_sku SET status=0 WHERE id=?", f.sku);
        MrpCreateReq req = new MrpCreateReq(); req.setOrderIds(List.of(orderId));
        assertThatThrownBy(() -> mrpService.create(req)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getCode()).isEqualTo(1000);
            assertThat(e.getData()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                    .hasSize(2).allSatisfy(problem -> assertThat(problem).contains("配料 SKU", "停用"));
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mrp_calc WHERE create_by=?", Long.class, f.user)).isZero();
    }

    /**
     * 补货（PRD §6.6.7 验收 3）：最小库存 300、库存 120、起订量 100 → 差 180、建议 180；生成备货采购单草稿后行上挂着它；
     * 审核后库存 + 在途 = 300，不再提醒
     */
    @Test
    void reorder() {
        Fixture f = fixture(new BigDecimal("300"));
        jdbcTemplate.update("UPDATE bas_material SET moq = 100 WHERE id = ?", f.material);
        jdbcTemplate.update("INSERT INTO inv_stock (warehouse_id, material_sku_id, qty) VALUES (?, ?, 120)", f.warehouse, f.sku);

        ReorderRowVO r = reorderRow(f);
        assertThat(r.getGapQty()).isEqualByComparingTo("180");
        assertThat(r.getSuggestQty()).isEqualByComparingTo("180");
        assertThat(r.getDefaultSupplierId()).isEqualTo(f.supplier);
        assertThat(r.getPrice()).isEqualByComparingTo("20");
        assertThat(r.getOpenDrafts()).isEmpty();

        ReorderPickItem pick = new ReorderPickItem();
        pick.setMaterialSkuId(f.sku);
        pick.setSupplierId(f.supplier);
        pick.setQty(r.getSuggestQty());
        FromReorderReq req = new FromReorderReq();
        req.setItems(List.of(pick));
        long po = purchaseOrderService.fromReorder(req).getFirst().id();
        assertThat(purchaseOrderService.detail(po).getPurchaseType()).isEqualTo("STOCK");
        assertThat(reorderRow(f).getOpenDrafts()).extracting(ReorderRowVO.OpenDraft::id).containsExactly(po);

        purchaseOrderService.act(po, "submit", new DocActionReq(0, null));
        purchaseOrderService.act(po, "audit", new DocActionReq(1, null));
        assertThat(supplyService.reorderItems()).noneMatch(x -> x.getMaterialSkuId().equals(f.sku));
        assertThat(supplyService.materialSupply(List.of(f.sku))).singleElement().satisfies((SkuSupplyVO s) -> {
            assertThat(s.transits()).singleElement().satisfies(t -> assertThat(t.qty()).isEqualByComparingTo("180"));
            assertThat(s.belowSafety()).isFalse();
        });
    }

    /** 草稿规则：不是合作中的供应商存不了；单价和档案现价不一样要写原因；删草稿连同行一起删 */
    @Test
    void draftRules() {
        Fixture f = fixture(null);
        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, material_sku_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, ?, 18, 'METER', 13, ?)""",
                f.supplier, f.material, f.sku, LocalDate.now().minusDays(1));

        PurchaseCreateReq req = poReq(f, "10", null);
        req.getItems().getFirst().setPrice(new BigDecimal("19"));
        assertThatThrownBy(() -> purchaseOrderService.create(req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("要写原因"));
        req.getItems().getFirst().setPriceChangeReason("涨价");
        long id = purchaseOrderService.create(req).id();
        assertThat(purchaseOrderService.detail(id).getItems().getFirst().getArchivePrice()).isEqualByComparingTo("18");

        PurchaseCreateReq plain = poReq(f, "10", null);
        assertThat(purchaseOrderService.detail(purchaseOrderService.create(plain).id()).getItems().getFirst().getPrice())
                .isEqualByComparingTo("18");

        purchaseOrderService.delete(id);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pur_order_item WHERE purchase_order_id = ?", Integer.class, id))
                .isZero();

        jdbcTemplate.update("UPDATE sup_supplier SET status = 'DISABLED' WHERE id = ?", f.supplier);
        assertThatThrownBy(() -> purchaseOrderService.create(poReq(f, "10", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getMessage()).contains("合作中"));
    }

    // ---------- 造数 ----------

    record Fixture(long company, long user, long warehouse, long supplier, long style, long skuM, long skuL, long material,
                   long sku, String skuCode) {
    }

    /** 主体、采购员（默认主体）、物料仓、合作中的供应商、一个款（一色两码）、一种面料（一个 SKU）、生效的生产 BOM（1.5 米 / 件） */
    private Fixture fixture(BigDecimal safetyStock) {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbcTemplate.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "P" + s.substring(1), "IT 采购主体" + s, "IT 采购主体" + s);
        long user = jdbcTemplate.queryForObject(
                "INSERT INTO sys_user (username, password, nickname, default_company_id) VALUES (?, 'x', ?, ?) RETURNING id",
                Long.class, "it-pur-" + s, "IT 采购" + s, company);
        long warehouse = jdbcTemplate.queryForObject("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'MATERIAL') RETURNING id",
                Long.class, "ITPW" + s, "IT 物料仓" + s);
        long supplier = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "ITPS" + s, "IT 面料厂" + s + "有限公司", "IT 面料厂" + s);
        long sizeGroup = jdbcTemplate.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 采购尺码" + s);
        jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M'), (?, 2, 'L')",
                sizeGroup, sizeGroup);
        long category = jdbcTemplate.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                "IT 采购品类" + s);
        String styleCode = "ITPU" + s;
        long style = jdbcTemplate.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, tag_price, status)
                VALUES (?, 'IT 采购款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 299, 'ENABLED') RETURNING id""",
                Long.class, styleCode, category, sizeGroup);
        long color = jdbcTemplate.queryForObject("INSERT INTO bas_color (code, name) VALUES (?, ?) RETURNING id", Long.class,
                code3(), "IT 采购色" + s);
        jdbcTemplate.update("INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, 1)", style, color);
        long skuM = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, styleCode + "-A-M", color);
        long skuL = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'L') RETURNING id",
                Long.class, style, styleCode + "-A-L", color);
        long materialCategory = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 采购物料分类" + s);
        long material = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, reserve_pct,
                                          default_supplier_id, ref_price)
                VALUES (?, 'IT 采购面料', ?, 'FABRIC', 'METER', 'METER', 0, ?, 20) RETURNING id""",
                Long.class, "ITPM" + s, materialCategory, supplier);
        String skuCode = "ITPM" + s + "-A";
        long sku = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_sku (material_id, code, color_id, safety_stock) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, material, skuCode, color, safetyStock);
        long pack = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id",
                Long.class, style);
        long item = jdbcTemplate.queryForObject("""
                INSERT INTO tp_bom_item (tech_pack_id, seq, part, material_id, sku_mode, usage_qty, unit, supplier_id)
                VALUES (?, 1, '大身', ?, 'BY_COLOR', 1.5, 'METER', ?) RETURNING id""", Long.class, pack, material, supplier);
        jdbcTemplate.update("INSERT INTO tp_bom_item_sku (bom_item_id, color_id, material_sku_id) VALUES (?, ?, ?)", item, color, sku);
        loginAs(user);
        return new Fixture(company, user, warehouse, supplier, style, skuM, skuL, material, sku, skuCode);
    }

    /** 已审核的订单：M 60 件、L 40 件 */
    private long approvedOrder(Fixture f, LocalDate commitDate) {
        OrderCreateReq req = new OrderCreateReq();
        req.setCompanyId(f.company);
        req.setOrderType("STOCK");
        req.setOrderDate(LocalDate.now());
        req.setRequiredDate(commitDate);
        req.setSalesId(f.user);
        req.setMerchId(f.user);
        req.setCurrency("CNY");
        req.setItems(List.of(orderItem(f.skuM, 60), orderItem(f.skuL, 40)));
        long id = orderService.create(req).id();
        orderService.act(id, "submit", new DocActionReq(0, null));
        orderService.audit(id, new OrderAuditReq(1, commitDate));
        return id;
    }

    private static OrderItemReq orderItem(long skuId, int qty) {
        OrderItemReq i = new OrderItemReq();
        i.setStyleSkuId(skuId);
        i.setQty(qty);
        i.setPrice(new BigDecimal("89"));
        return i;
    }

    private PurchaseCreateReq poReq(Fixture f, String qty, LocalDate expectDate) {
        PurchaseCreateReq req = new PurchaseCreateReq();
        req.setCompanyId(f.company);
        req.setSupplierId(f.supplier);
        req.setPurchaseType(PurchaseType.STOCK);
        req.setBuyerId(f.user);
        req.setOrderDate(LocalDate.now());
        req.setWarehouseId(f.warehouse);
        req.setSettleMethod("MONTHLY");
        req.setTaxPct(new BigDecimal("13"));
        PurchaseItemReq i = new PurchaseItemReq();
        i.setMaterialSkuId(f.sku);
        i.setQty(new BigDecimal(qty));
        i.setExpectDate(expectDate);
        req.setItems(List.of(i));
        return req;
    }

    /** 已审核的备货采购单（单价按物料参考单价 20） */
    private long approvedStockPo(Fixture f, String qty, LocalDate expectDate) {
        long id = purchaseOrderService.create(poReq(f, qty, expectDate)).id();
        purchaseOrderService.act(id, "submit", new DocActionReq(0, null));
        purchaseOrderService.act(id, "audit", new DocActionReq(1, null));
        return id;
    }

    /** 详情原样存回，只改第一行的数量 */
    private static PurchaseSaveReq saveReq(PurchaseDetailVO d, String qty) {
        PurchaseSaveReq req = new PurchaseSaveReq();
        req.setVersion(d.getVersion());
        req.setCompanyId(d.getCompanyId());
        req.setSupplierId(d.getSupplierId());
        req.setPurchaseType(PurchaseType.valueOf(d.getPurchaseType()));
        req.setBuyerId(d.getBuyerId());
        req.setOrderDate(d.getOrderDate());
        req.setDeliveryDate(d.getDeliveryDate());
        req.setWarehouseId(d.getWarehouseId());
        req.setSettleMethod(d.getSettleMethod());
        req.setTaxPct(d.getTaxPct());
        req.setItems(d.getItems().stream().map(i -> {
            PurchaseItemReq r = new PurchaseItemReq();
            r.setCalcItemId(i.getCalcItemId());
            r.setMaterialSkuId(i.getMaterialSkuId());
            r.setQty(new BigDecimal(qty));
            r.setPrice(i.getPrice());
            return r;
        }).toList());
        return req;
    }

    /** 到货入库并马上过账（数量按采购单位） */
    private void receive(Fixture f, long poId, String qty) {
        invDocService.fromSource(BizType.PURCHASE_ORDER, poId, stockReq(f, poId, InvDocType.PURCHASE_IN, qty));
    }

    private InvDocFromSourceReq stockReq(Fixture f, long poId, InvDocType type, String qty) {
        long line = purchaseOrderService.detail(poId).getItems().getFirst().getId();
        InvDocFromSourceReq req = new InvDocFromSourceReq();
        req.setClientActionId(java.util.UUID.randomUUID());
        req.setDocType(type);
        InvDocFromSourceReq.Item i = new InvDocFromSourceReq.Item();
        i.setSourceItemId(line);
        i.setMaterialSkuId(f.sku);
        i.setQty(new BigDecimal(qty));
        req.setItems(List.of(i));
        req.setPost(true);
        return req;
    }

    /** bas_color 的编码是 3 位、全局唯一 */
    private static String code3() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "" + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26));
    }

    private ReorderRowVO reorderRow(Fixture f) {
        return supplyService.reorderItems().stream().filter(x -> x.getMaterialSkuId().equals(f.sku)).findFirst().orElseThrow();
    }
}
