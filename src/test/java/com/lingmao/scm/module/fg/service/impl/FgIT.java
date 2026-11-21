package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.biz.service.SelfAudit;
import com.lingmao.scm.module.fg.dto.FgDocCreateReq;
import com.lingmao.scm.module.fg.dto.FgDocCreatedVO;
import com.lingmao.scm.module.fg.dto.FgDocItemReq;
import com.lingmao.scm.module.fg.dto.FgDocQuery;
import com.lingmao.scm.module.fg.dto.FgDocRowVO;
import com.lingmao.scm.module.fg.dto.FgFromReceiptReq;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptQuery;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptRowVO;
import com.lingmao.scm.module.fg.dto.FgReceiptStockedVO;
import com.lingmao.scm.module.fg.dto.FgShippedRow;
import com.lingmao.scm.module.fg.dto.FgStockLogQuery;
import com.lingmao.scm.module.fg.dto.FgStockQuery;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.service.FgDocService;
import com.lingmao.scm.module.fg.service.FgStockService;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderItemReq;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ReceiptReq;
import com.lingmao.scm.module.production.dto.ScheduleReq;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.production.service.ScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 成品在真库上（RFC 008 §9）：待入库、列表、日志的 SQL，库存的条件更新，回货单锁和发货咨询锁下的上限，
 * 回货单、订单有下游时作废、取消被拦（1005），库存和日志对得上账
 */
@IntegrationTest
class FgIT {

    private static final List<String> PERMS = List.of("order:order:list", "order:order:add", "order:order:submit",
            "order:order:audit", "production:order:list", "production:order:add", "production:order:edit",
            "production:order:submit", "production:order:audit", "production:receipt:add", "production:receipt:void",
            "fg:doc:list", "fg:doc:add", "fg:doc:edit", "fg:doc:remove", "fg:doc:audit", "fg:stock:list", "order:price:query", "production:price:query");

    @Autowired
    private FgDocService fgDocService;
    @Autowired
    private FgStockService fgStockService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ProductionOrderService productionOrderService;
    @Autowired
    private ProductionReceiptService receiptService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ReferenceService referenceService;
    @Autowired
    private FileUrlSigner signer;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void logout() {
        TestIdentity.clear();
    }

    /**
     * 订单 M 60、L 40 排一张外发单，回货 M 60（次品 2）、L 40 → 良品 98：
     * 先入 M 30 → 再入其余（M 28、L 40），回货单不在待入库里了，回货单作废 1005 →
     * 发货 M 50、L 40；再发 M 12 超 ⌊60 × 1.03⌋ − 50 = 11 → 1004 → 订单不能取消 1005 →
     * 第一张入库单反审核扣不回来 1001 → 发货反审核、入库反审核、删不了只能作废 → 回货单回到待入库（还差 30）→ 库存和日志对账
     */
    @Test
    void stockInPartlyShipUnauditVoid() {
        Fixture f = fixture();
        long order = approvedOrder(f);
        long prd = approvedProduction(f, order);
        long receipt = receiptService.receive(prd, receiptReq(prd, Map.of(f.skuM, 60, f.skuL, 40), f.skuM, 2)).id();

        FgPendingReceiptRowVO pending = pending(f, receipt);
        assertThat(pending).isNotNull();
        assertThat(pending.getGoodQty()).isEqualTo(98);
        assertThat(pending.getRemainQty()).isEqualTo(98);
        assertThat(fgDocService.receiptDraft(receipt).getItems()).hasSize(2);

        // 建草稿、换审核人过账（自己建的单不能自己审，甲方 11-21 答 Q10；建单时顺手过账也一样过不去）
        FgDocCreatedVO first = fgDocService.fromReceipt(receipt, fromReceipt(f, Map.of(receiptItem(receipt, f.skuM), 30), false));
        assertThat(first.status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> fgDocService.fromReceipt(receipt, fromReceipt(f, Map.of(receiptItem(receipt, f.skuM), 1), true)))
                .isInstanceOf(BizException.class).hasMessage(SelfAudit.MESSAGE);
        audit(first.id());
        assertThat(pending(f, receipt).getRemainQty()).isEqualTo(68);
        assertThatThrownBy(() -> fgDocService.fromReceipt(receipt, fromReceipt(f, Map.of(receiptItem(receipt, f.skuM), 29), false)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(ErrorCodes.OVER_LIMIT);
        FgDocCreatedVO rest = fgDocService.fromReceipt(receipt, fromReceipt(f, null, false));
        audit(rest.id());
        assertThat(stock(f, f.skuM)).isEqualTo(58);
        assertThat(stock(f, f.skuL)).isEqualTo(40);
        assertThat(pending(f, receipt)).isNull();
        assertThat(fgDocService.byReceipts(List.of(receipt))).extracting(FgReceiptStockedVO::no).containsExactly(first.no(), rest.no());
        assertThatThrownBy(() -> receiptService.voidReceipt(prd, receipt, "登记错了"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(ErrorCodes.HAS_DOWNSTREAM);

        FgDocCreatedVO ship = fgDocService.create(shipReq(f, order, Map.of(f.skuM, 50, f.skuL, 40)));
        audit(ship.id());
        assertThat(stock(f, f.skuM)).isEqualTo(8);
        assertThat(stock(f, f.skuL)).isZero();
        assertThat(fgDocService.shipped(order)).extracting(FgShippedRow::styleSkuId, FgShippedRow::shippedQty)
                .containsExactlyInAnyOrder(tuple(f.skuM, 50), tuple(f.skuL, 40));
        assertThatThrownBy(() -> fgDocService.create(shipReq(f, order, Map.of(f.skuM, 12))))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(ErrorCodes.OVER_LIMIT);
        // 订单变更取消订单前查的就是这个（OrderChangeServiceImpl.submit、OrderServiceImpl.applyChange）
        assertThatThrownBy(() -> referenceService.assertNoReferrer(BizType.ORDER, order, "取消"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(ErrorCodes.HAS_DOWNSTREAM);

        assertThatThrownBy(() -> fgDocService.act(first.id(), "unaudit", new DocActionReq(version(first.id()), null)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(ErrorCodes.STOCK_SHORTAGE);
        fgDocService.act(ship.id(), "unaudit", new DocActionReq(version(ship.id()), "发错仓"));
        fgDocService.act(first.id(), "unaudit", new DocActionReq(version(first.id()), null));
        assertThat(stock(f, f.skuM)).isEqualTo(28);
        assertThatThrownBy(() -> fgDocService.delete(first.id())).isInstanceOf(BizException.class).hasMessageContaining("只能作废");
        fgDocService.act(first.id(), "void", new DocActionReq(version(first.id()), "数错了"));
        assertThat(pending(f, receipt).getRemainQty()).isEqualTo(30);

        FgDocQuery q = new FgDocQuery();
        q.setKind(FgDocKind.IN);
        q.setKeyword(first.no());
        assertThat(fgDocService.page(q).getList()).extracting(FgDocRowVO::getStatus, FgDocRowVO::getTotalQty)
                .containsExactly(tuple("VOIDED", 30));
        FgStockLogQuery logs = new FgStockLogQuery();
        logs.setWarehouseId(f.warehouse);
        assertThat(fgStockService.logs(logs).getTotal()).isEqualTo(8); // 入 1 + 入 2 + 发 2 + 发反审 2 + 入反审 1；扣不回来的那次回滚了
        FgStockQuery stocks = new FgStockQuery();
        stocks.setWarehouseId(f.warehouse);
        assertThat(fgStockService.byStyle(stocks).getList()).singleElement()
                .satisfies(s -> assertThat(s.totalQty()).isEqualTo(68));
        assertReconciled(f);
    }

    /**
     * RFC 008 §7：同一张订单的两张发货单同时审核，各发 M 40（订单 60，最多 61）：订单咨询锁让后一张等前一张提交、重算已发，
     * 正好一张成功、一张 1004；库存只扣一次
     */
    @Test
    void concurrentShipAudits_neverExceedCap() throws Exception {
        Fixture f = fixture();
        long order = approvedOrder(f);
        FgDocCreatedVO in = fgDocService.create(otherInReq(f, Map.of(f.skuM, 100)));
        audit(in.id());
        long a = fgDocService.create(shipReq(f, order, Map.of(f.skuM, 40))).id();
        long b = fgDocService.create(shipReq(f, order, Map.of(f.skuM, 40))).id();

        TestIdentity.auditorOf(jdbcTemplate, f.user);   // 审核人先建好：两个线程同时建会撞唯一索引
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long id : List.of(a, b)) {
                int v = version(id);
                futures.add(pool.submit(() -> {
                    TestIdentity.login(f.user, PERMS);
                    TestIdentity.company(f.company);
                    try {
                        start.await();
                        TestIdentity.asAuditor(jdbcTemplate, () -> fgDocService.act(id, "audit", new DocActionReq(v, null)));
                        return null;
                    } finally {
                        TestIdentity.clear();
                    }
                }));
            }
            List<Integer> errors = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    errors.add(((BizException) e.getCause()).getCode());
                }
            }
            assertThat(errors).containsExactly(ErrorCodes.OVER_LIMIT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(f, f.skuM)).isEqualTo(60);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fg_doc WHERE order_id = ? AND status = 'APPROVED'", Integer.class,
                order)).isEqualTo(1);
        assertReconciled(f);
    }

    // ---------------------------------------------------------------- 检查

    /** 每个仓每个 SKU：库存 = 日志变动之和 = 最后一条日志的结存 */
    private void assertReconciled(Fixture f) {
        List<Map<String, Object>> bad = jdbcTemplate.queryForList("""
                SELECT s.style_sku_id, s.qty, l.sum_qty, l.last_balance FROM fg_stock s
                JOIN LATERAL (SELECT SUM(change_qty) AS sum_qty,
                                     (array_agg(balance_qty ORDER BY id DESC))[1] AS last_balance
                              FROM fg_stock_log WHERE warehouse_id = s.warehouse_id AND style_sku_id = s.style_sku_id) l ON true
                WHERE s.warehouse_id = ? AND (s.qty <> l.sum_qty OR s.qty <> l.last_balance)""", f.warehouse);
        assertThat(bad).isEmpty();
    }

    private int stock(Fixture f, long skuId) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM fg_stock WHERE warehouse_id = ? AND style_sku_id = ?",
                Integer.class, f.warehouse, skuId);
    }

    private FgPendingReceiptRowVO pending(Fixture f, long receiptId) {
        FgPendingReceiptQuery q = new FgPendingReceiptQuery();
        q.setFactoryId(f.factory);
        return fgDocService.pendingReceipts(q).getList().stream().filter(r -> r.getReceiptId() == receiptId).findFirst().orElse(null);
    }

    private int version(long docId) {
        return jdbcTemplate.queryForObject("SELECT version FROM fg_doc WHERE id = ?", Integer.class, docId);
    }

    private void audit(long docId) {
        TestIdentity.asAuditor(jdbcTemplate, () -> fgDocService.act(docId, "audit", new DocActionReq(version(docId), null)));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fg_doc WHERE id = ?", String.class, docId))
                .isEqualTo(DocStatus.APPROVED.name());
    }

    // ---------------------------------------------------------------- 数据

    record Fixture(long company, long user, long warehouse, long factory, long style, long skuM, long skuL) {
    }

    /** 主体、用户、成品仓、合作中的成衣厂、一个款（一色两码）、生效的生产 BOM（订单审核、排单要；1.5 米 / 件面料） */
    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbcTemplate.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "G" + s.substring(1), "IT 成品主体" + s, "IT 成品主体" + s);
        long user = jdbcTemplate.queryForObject(
                "INSERT INTO sys_user (username, password, nickname, default_company_id) VALUES (?, 'x', ?, ?) RETURNING id",
                Long.class, "it-fg-" + s, "IT 成品" + s, company);
        long warehouse = jdbcTemplate.queryForObject("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'PRODUCT') RETURNING id",
                Long.class, "ITGW" + s, "IT 成品仓" + s);
        long factory = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 3) RETURNING id""", Long.class, "ITGF" + s, "IT 成品制衣" + s + "厂", "IT 成品制衣" + s);
        jdbcTemplate.update("INSERT INTO sup_supplier_category (supplier_id, category) VALUES (?, 'GARMENT')", factory);
        long sizeGroup = jdbcTemplate.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 成品尺码" + s);
        jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M'), (?, 2, 'L')",
                sizeGroup, sizeGroup);
        long category = jdbcTemplate.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                "IT 成品品类" + s);
        String styleCode = "ITFG" + s;
        long style = jdbcTemplate.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, tag_price, status,
                                       default_factory_id)
                VALUES (?, 'IT 成品款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 299, 'ENABLED', ?) RETURNING id""",
                Long.class, styleCode, category, sizeGroup, factory);
        long color = TestColors.insert(jdbcTemplate, "IT 成品色" + s);
        jdbcTemplate.update("INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, 1)", style, color);
        long skuM = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, styleCode + "-A-M", color);
        long skuL = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'L') RETURNING id",
                Long.class, style, styleCode + "-A-L", color);
        long fabricSupplier = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "ITGS" + s, "IT 成品面料" + s + "厂", "IT 成品面料" + s);
        long materialCategory = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 成品物料分类" + s);
        long material = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, reserve_pct,
                                          default_supplier_id, ref_price)
                VALUES (?, 'IT 成品面料', ?, 'FABRIC', 'METER', 'METER', 0, ?, 20) RETURNING id""",
                Long.class, "ITGM" + s, materialCategory, fabricSupplier);
        long materialSku = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, material, "ITGM" + s + "-A", color);
        long pack = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id",
                Long.class, style);
        long bomItem = jdbcTemplate.queryForObject("""
                INSERT INTO tp_bom_item (tech_pack_id, seq, part, material_id, sku_mode, usage_qty, unit, supplier_id)
                VALUES (?, 1, '大身', ?, 'BY_COLOR', 1.5, 'METER', ?) RETURNING id""", Long.class, pack, material, fabricSupplier);
        jdbcTemplate.update("INSERT INTO tp_bom_item_sku (bom_item_id, color_id, material_sku_id) VALUES (?, ?, ?)",
                bomItem, color, materialSku);
        TestIdentity.login(user, PERMS);
        TestIdentity.grant(jdbcTemplate, user, PERMS);
        TestIdentity.company(company);
        return new Fixture(company, user, warehouse, factory, style, skuM, skuL);
    }

    /** 已审核的订单：M 60 件、L 40 件，单价 89 */
    private long approvedOrder(Fixture f) {
        LocalDate commit = LocalDate.now().plusDays(60);
        OrderCreateReq req = new OrderCreateReq();
        req.setCompanyId(f.company);
        req.setOrderType("STOCK");
        req.setOrderDate(LocalDate.now());
        req.setRequiredDate(commit);
        req.setSalesId(f.user);
        req.setMerchId(f.user);
        req.setCurrency("CNY");
        req.setItems(List.of(orderItem(f.skuM, 60), orderItem(f.skuL, 40)));
        long id = orderService.create(req).id();
        orderService.act(id, "submit", new DocActionReq(0, null));
        TestIdentity.asAuditor(jdbcTemplate, () -> orderService.audit(id, new OrderAuditReq(1, commit)));
        return id;
    }

    private static OrderItemReq orderItem(long skuId, int qty) {
        OrderItemReq i = new OrderItemReq();
        i.setStyleSkuId(skuId);
        i.setQty(qty);
        i.setPrice(new BigDecimal("89"));
        return i;
    }

    /** 整张订单排成一张外发加工单（清加工 38 元），提交、审核 */
    private long approvedProduction(Fixture f, long orderId) {
        ScheduleReq.Block b = new ScheduleReq.Block();
        b.setStyleId(f.style);
        b.setFactoryId(f.factory);
        b.setProcessMode("CMT");
        b.setUnitPrice(new BigDecimal("38"));
        b.setDueDate(LocalDate.now().plusDays(50));
        b.setMerchId(f.user);
        b.setItems(jdbcTemplate.queryForList("SELECT id, qty FROM ord_order_item WHERE order_id = ? ORDER BY seq", orderId).stream()
                .map(r -> {
                    ScheduleReq.Item i = new ScheduleReq.Item();
                    i.setOrderItemId(((Number) r.get("id")).longValue());
                    i.setQty(((Number) r.get("qty")).intValue());
                    return i;
                }).toList());
        ScheduleReq req = new ScheduleReq();
        req.setOrderId(orderId);
        req.setBlocks(List.of(b));
        long id = scheduleService.schedule(req).getFirst().id();
        productionOrderService.act(id, "submit", new DocActionReq(productionOrderService.detail(id).getVersion(), null));
        TestIdentity.asAuditor(jdbcTemplate, () -> productionOrderService.act(id, "audit", new DocActionReq(productionOrderService.detail(id).getVersion(), null)));
        return id;
    }

    /** 回货：按 SKU 给件数，defectSku 那一行有 defect 件次品 */
    private ReceiptReq receiptReq(long prdId, Map<Long, Integer> qtyBySku, long defectSku, int defect) {
        ReceiptReq r = new ReceiptReq();
        r.setClientActionId(UUID.randomUUID());
        r.setReceiptDate(LocalDate.now());
        AttachmentReq photo = new AttachmentReq();
        photo.setUrl(signer.sign("/files/2026/11/19/fg-receipt.jpg"));
        photo.setName("fg-receipt.jpg");
        photo.setSize(2048L);
        r.setPhotos(List.of(photo));
        r.setItems(qtyBySku.entrySet().stream().map(e -> {
            ReceiptReq.Item i = new ReceiptReq.Item();
            i.setPrdOrderItemId(jdbcTemplate.queryForObject(
                    "SELECT id FROM prd_order_item WHERE prd_order_id = ? AND style_sku_id = ?", Long.class, prdId, e.getKey()));
            i.setQty(e.getValue());
            i.setDefectQty(e.getKey() == defectSku ? defect : 0);
            return i;
        }).toList());
        return r;
    }

    private long receiptItem(long receiptId, long skuId) {
        return jdbcTemplate.queryForObject("SELECT id FROM prd_receipt_item WHERE receipt_id = ? AND style_sku_id = ?", Long.class,
                receiptId, skuId);
    }

    /** 由回货单入库：items 为 null 时入每行还没入的全部 */
    private static FgFromReceiptReq fromReceipt(Fixture f, Map<Long, Integer> qtyByLine, boolean post) {
        FgFromReceiptReq req = new FgFromReceiptReq();
        req.setClientActionId(UUID.randomUUID());
        req.setWarehouseId(f.warehouse);
        req.setPost(post);
        if (qtyByLine != null) {
            req.setItems(qtyByLine.entrySet().stream().map(e -> {
                FgFromReceiptReq.Item i = new FgFromReceiptReq.Item();
                i.setPrdReceiptItemId(e.getKey());
                i.setQty(e.getValue());
                return i;
            }).toList());
        }
        return req;
    }

    private static FgDocCreateReq shipReq(Fixture f, long orderId, Map<Long, Integer> qtyBySku) {
        FgDocCreateReq req = new FgDocCreateReq();
        req.setCompanyId(f.company);
        req.setDocType(FgDocType.SHIP_OUT);
        req.setOrderId(orderId);
        req.setWarehouseId(f.warehouse);
        req.setDocDate(LocalDate.now());
        req.setShipTo("IT 收货方");
        req.setItems(items(qtyBySku));
        return req;
    }

    private static FgDocCreateReq otherInReq(Fixture f, Map<Long, Integer> qtyBySku) {
        FgDocCreateReq req = new FgDocCreateReq();
        req.setCompanyId(f.company);
        req.setDocType(FgDocType.OTHER_IN);
        req.setWarehouseId(f.warehouse);
        req.setDocDate(LocalDate.now());
        req.setReason("OPENING");
        req.setItems(items(qtyBySku));
        return req;
    }

    private static List<FgDocItemReq> items(Map<Long, Integer> qtyBySku) {
        return qtyBySku.entrySet().stream().map(e -> {
            FgDocItemReq i = new FgDocItemReq();
            i.setStyleSkuId(e.getKey());
            i.setQty(e.getValue());
            return i;
        }).toList();
    }
}
