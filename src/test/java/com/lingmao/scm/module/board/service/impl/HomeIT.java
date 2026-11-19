package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.board.dto.DeliveryRow;
import com.lingmao.scm.module.board.dto.HomeKpi;
import com.lingmao.scm.module.board.dto.HomeTodoQuery;
import com.lingmao.scm.module.board.dto.HomeTodos;
import com.lingmao.scm.module.board.dto.OrderReview;
import com.lingmao.scm.module.board.dto.TodoCount;
import com.lingmao.scm.module.board.dto.TodoGroup;
import com.lingmao.scm.module.board.dto.TodoItem;
import com.lingmao.scm.module.board.dto.UpstreamQuery;
import com.lingmao.scm.module.board.service.HomeService;
import com.lingmao.scm.module.board.service.OrderReviewService;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderItemReq;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.ScheduleReq;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.production.service.ScheduleService;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.module.system.entity.RoleDataScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首页在真库上（docs/api/06、02 §3.8）：各块问的是各域列表的 SQL（交期排序、件数合计、已延期、订单号筛、没关的异常、资料包草稿、
 * 现在生效版本的需求展开），数据范围、负责人筛选、品类线叠在一起要真库才测得出
 */
@IntegrationTest
class HomeIT {

    private static final List<String> PERMS = List.of("style:style:list", "techpack:pack:list", "techpack:pack:publish",
            "order:order:list", "order:order:add", "order:order:submit", "order:order:audit", "production:order:list",
            "production:order:add", "production:order:submit", "production:order:audit", "production:board:list",
            "purchase:order:list", "purchase:mrp:list", "order:price:query", "production:price:query", "techpack:cost:query");

    @Autowired
    private HomeService homeService;
    @Autowired
    private OrderReviewService reviewService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ProductionOrderService productionOrderService;
    @Autowired
    private PurchaseOrderService purchaseOrderService;
    @Autowired
    private MrpService mrpService;
    @Autowired
    private DocNoticeService docNoticeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void logout() {
        TestIdentity.clear();
    }

    /**
     * 一个跟单（本人范围）从头走一遍：提交订单 → 审核对话框的评审信息、待我审核；审核 → 概况、待排单、近期交期；
     * 排成生产单、交期改成昨天 → 生产延期、在制、近期交期排最前；上报异常、收到上游提醒、留一个资料包草稿 → 各自的组
     */
    @Test
    void blocksFollowTheDocuments() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now();
        long order = submittedOrder(f, today.plusDays(5));
        String orderNo = jdbcTemplate.queryForObject("SELECT no FROM ord_order WHERE id = ?", String.class, order);

        OrderReview review = reviewService.review(order);
        assertThat(review.packs()).singleElement().satisfies(p -> {
            assertThat(p.techPackId()).isEqualTo(f.pack);
            assertThat(p.problem()).isNull();
        });
        // 100 件 × 1.5 米，新物料没有库存、在途：全缺
        assertThat(review.shortages()).singleElement().satisfies(s -> {
            assertThat(s.materialSkuId()).isEqualTo(f.sku);
            assertThat(s.need()).isEqualByComparingTo("150");
            assertThat(s.available()).isEqualByComparingTo("0");
            assertThat(s.gap()).isEqualByComparingTo("150");
            assertThat(s.unit()).isEqualTo("METER");
        });
        assertThat(review.loads()).singleElement().satisfies(l -> {
            assertThat(l.factoryId()).isEqualTo(f.factory);
            assertThat(l.wipQty()).isZero();
        });
        assertThat(items(homeService.todos(new HomeTodoQuery()), "AUDIT")).extracting(TodoItem::title).contains("需求订单 " + orderNo);

        orderService.audit(order, new OrderAuditReq(1, today.plusDays(5)));
        HomeKpi kpi = homeService.kpi();
        assertThat(kpi.order()).isEqualTo(new HomeKpi.Order(100, 1, 0, 1, null));
        HomeTodos todos = homeService.todos(new HomeTodoQuery());
        assertThat(items(todos, "UNSCHEDULED")).singleElement().satisfies(i -> assertThat(i.ref().id()).isEqualTo(order));
        assertThat(todos.counts()).contains(new TodoCount("UNSCHEDULED", 1));
        assertThat(homeService.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.ref().id()).isEqualTo(order);
            assertThat(d.daysLeft()).isEqualTo(5);
            assertThat(d.totalQty()).isEqualTo(100);
        });

        long prd = approvedProduction(f, order, today.plusDays(60));
        jdbcTemplate.update("UPDATE prd_order SET due_date = ? WHERE id = ?", today.minusDays(1), prd);
        kpi = homeService.kpi();
        assertThat(kpi.production()).isEqualTo(new HomeKpi.Production(100, 1, 1, 0, 0));
        assertThat(kpi.order().unscheduledCount()).isZero();
        todos = homeService.todos(new HomeTodoQuery());
        assertThat(items(todos, "PRODUCTION_LATE")).singleElement().satisfies(i -> {
            assertThat(i.ref().id()).isEqualTo(prd);
            assertThat(i.sub()).contains("已过");
        });
        assertThat(todos.counts()).contains(new TodoCount("PRODUCTION_LATE", 1));
        List<DeliveryRow> deliveries = homeService.deliveries();
        assertThat(deliveries).extracting(d -> d.ref().id()).containsExactly(prd, order);
        assertThat(deliveries.getFirst().daysLeft()).isEqualTo(-1);

        jdbcTemplate.update("INSERT INTO prd_issue (no, prd_order_id, issue_type, description, create_by) VALUES (?, ?, 'SHORTAGE', '辅料没到', ?)",
                "YCIT" + f.suffix, prd, f.user);
        docNoticeService.add(BizType.PRODUCTION_ORDER, prd, BizType.ORDER, order, "订单 " + orderNo + " 的件数改了", f.user);
        jdbcTemplate.update("INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 2, 'DRAFT')", f.style);
        todos = homeService.todos(new HomeTodoQuery());
        assertThat(items(todos, "ISSUE")).singleElement().satisfies(i -> {
            assertThat(i.ref().type()).isEqualTo(BizType.PRODUCTION_ISSUE);
            assertThat(i.ref().parentId()).isEqualTo(prd);
            assertThat(i.title()).isEqualTo("缺料：辅料没到");
        });
        assertThat(items(todos, "UPSTREAM")).singleElement().satisfies(i -> assertThat(i.ref().id()).isEqualTo(prd));
        assertThat(items(todos, "AUDIT")).extracting(TodoItem::title).contains("资料包 " + f.styleCode + " V2");
        assertThat(homeService.upstream(new UpstreamQuery()).rows()).singleElement()
                .satisfies(r -> assertThat(r.notices()).singleElement().satisfies(n -> assertThat(n.content()).contains(orderNo)));
        assertThat(homeService.kpi().dev().packDraftCount()).isPositive();

        // 首页另加的几个查询条件在真库上能跑
        ProductionQuery byOrder = new ProductionQuery();
        byOrder.setKind("OUTSOURCE");
        byOrder.setOrderNos(Set.of(orderNo));
        assertThat(productionOrderService.page(byOrder).getList()).extracting(ProductionRowVO::getId).containsExactly(prd);
        assertThat(purchaseOrderService.orderCutRisks()).doesNotContainKey(orderNo);
        MrpCalcQuery calcs = new MrpCalcQuery();
        calcs.setIds(List.of(-1L));
        assertThat(mrpService.page(calcs).getTotal()).isZero();
    }

    /**
     * 规则：负责人筛选只给数据范围"全部"的人；本人范围的别人传了 ownerId 也看不到；"全部"的人能筛到某个人，
     * 品类线还要另外过
     */
    @Test
    void ownerFilterOnlyWidensForAllScope() {
        Fixture f = fixture();
        LocalDate today = LocalDate.now();
        long order = submittedOrder(f, today.plusDays(20));
        orderService.audit(order, new OrderAuditReq(1, today.plusDays(20)));
        long prd = approvedProduction(f, order, today.plusDays(60));
        jdbcTemplate.update("UPDATE prd_order SET due_date = ? WHERE id = ?", today.minusDays(2), prd);
        docNoticeService.add(BizType.PRODUCTION_ORDER, prd, BizType.ORDER, order, "订单改了", f.user);

        long other = newUser("other" + f.suffix);
        HomeTodoQuery onF = new HomeTodoQuery();
        onF.setOwnerId(f.user);
        TestIdentity.login(other, PERMS);
        HomeTodos self = homeService.todos(onF);
        assertThat(self.canSeeAll()).isFalse();
        assertThat(items(self, "PRODUCTION_LATE")).extracting(i -> i.ref().id()).doesNotContain(prd);
        assertThat(items(self, "UPSTREAM")).extracting(i -> i.ref().id()).doesNotContain(prd);

        long boss = newUser("boss" + f.suffix);
        List<String> bossPerms = Stream.concat(PERMS.stream(), Stream.of(RoleDataScope.ALL_AUTHORITY)).toList();
        TestIdentity.login(boss, bossPerms);
        HomeTodos all = homeService.todos(new HomeTodoQuery());
        assertThat(all.canSeeAll()).isTrue();
        assertThat(items(all, "PRODUCTION_LATE")).extracting(i -> i.ref().id()).contains(prd);
        assertThat(items(homeService.todos(onF), "PRODUCTION_LATE")).extracting(i -> i.ref().id()).contains(prd);
        HomeTodoQuery mine = new HomeTodoQuery();
        mine.setMine(true);
        assertThat(items(homeService.todos(mine), "PRODUCTION_LATE")).extracting(i -> i.ref().id()).doesNotContain(prd);
        UpstreamQuery upstreamOnOther = new UpstreamQuery();
        upstreamOnOther.setOwnerId(other);
        assertThat(homeService.upstream(upstreamOnOther).rows()).extracting(r -> r.doc().id()).doesNotContain(prd);
        UpstreamQuery upstreamOnF = new UpstreamQuery();
        upstreamOnF.setOwnerId(f.user);
        assertThat(homeService.upstream(upstreamOnF).rows()).extracting(r -> r.doc().id()).containsExactly(prd);

        List<String> footwear = new ArrayList<>(bossPerms);
        footwear.add("line:FOOTWEAR");
        TestIdentity.login(boss, footwear);
        assertThat(items(homeService.todos(onF), "PRODUCTION_LATE")).extracting(i -> i.ref().id()).doesNotContain(prd);
        assertThat(homeService.upstream(upstreamOnF).rows()).isEmpty();
    }

    // ---------------------------------------------------------------- 数据

    private static List<TodoItem> items(HomeTodos todos, String key) {
        return todos.groups().stream().filter(g -> g.key().equals(key)).findFirst().map(TodoGroup::items).orElse(List.of());
    }

    record Fixture(String suffix, long company, long user, long factory, long style, String styleCode, long skuM, long skuL, long sku,
                   long pack) {
    }

    /** 主体、跟单（本人范围，默认主体）、合作中的成衣厂（款式的默认加工厂）、一个款（一色两码）、一种面料、生效的生产 BOM（1.5 米 / 件，自购） */
    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbcTemplate.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "H" + s.substring(1), "IT 首页主体" + s, "IT 首页主体" + s);
        long user = jdbcTemplate.queryForObject(
                "INSERT INTO sys_user (username, password, nickname, default_company_id) VALUES (?, 'x', ?, ?) RETURNING id",
                Long.class, "it-home-" + s, "IT 首页" + s, company);
        long fabricSupplier = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "ITHS" + s, "IT 首页面料" + s, "IT 首页面料" + s);
        long factory = jdbcTemplate.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 3) RETURNING id""", Long.class, "ITHF" + s, "IT 首页制衣" + s, "IT 首页制衣" + s);
        jdbcTemplate.update("INSERT INTO sup_supplier_category (supplier_id, category) VALUES (?, 'GARMENT')", factory);
        jdbcTemplate.update("INSERT INTO bas_warehouse (code, name, type, factory_id) VALUES (?, ?, 'FACTORY', ?)",
                "ITHC" + s, "IT 首页制衣" + s + "仓", factory);
        long sizeGroup = jdbcTemplate.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 首页尺码" + s);
        jdbcTemplate.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M'), (?, 2, 'L')",
                sizeGroup, sizeGroup);
        long category = jdbcTemplate.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                "IT 首页品类" + s);
        String styleCode = "ITHM" + s;
        long style = jdbcTemplate.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, tag_price, status,
                                       default_factory_id)
                VALUES (?, 'IT 首页款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 299, 'ENABLED', ?) RETURNING id""",
                Long.class, styleCode, category, sizeGroup, factory);
        long color = TestColors.insert(jdbcTemplate, "IT 首页色" + s);
        jdbcTemplate.update("INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, 1)", style, color);
        long skuM = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, styleCode + "-A-M", color);
        long skuL = jdbcTemplate.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'L') RETURNING id",
                Long.class, style, styleCode + "-A-L", color);
        long materialCategory = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 首页物料分类" + s);
        long material = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, reserve_pct,
                                          default_supplier_id, ref_price)
                VALUES (?, 'IT 首页面料', ?, 'FABRIC', 'METER', 'METER', 0, ?, 20) RETURNING id""",
                Long.class, "ITHM" + s, materialCategory, fabricSupplier);
        long sku = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, material, "ITHM" + s + "-A", color);
        long pack = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id",
                Long.class, style);
        long item = jdbcTemplate.queryForObject("""
                INSERT INTO tp_bom_item (tech_pack_id, seq, part, material_id, sku_mode, usage_qty, unit, supplier_id)
                VALUES (?, 1, '大身', ?, 'BY_COLOR', 1.5, 'METER', ?) RETURNING id""", Long.class, pack, material, fabricSupplier);
        jdbcTemplate.update("INSERT INTO tp_bom_item_sku (bom_item_id, color_id, material_sku_id) VALUES (?, ?, ?)", item, color, sku);
        TestIdentity.grant(jdbcTemplate, user, PERMS);
        TestIdentity.login(user, PERMS);
        TestIdentity.company(company);
        return new Fixture(s, company, user, factory, style, styleCode, skuM, skuL, sku, pack);
    }

    private long newUser(String name) {
        return jdbcTemplate.queryForObject("INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', ?) RETURNING id",
                Long.class, "it-home-" + name, "IT " + name);
    }

    /** 提交了的订单：M 60 件、L 40 件，跟单是 f.user */
    private long submittedOrder(Fixture f, LocalDate requiredDate) {
        OrderCreateReq req = new OrderCreateReq();
        req.setCompanyId(f.company);
        req.setOrderType("STOCK");
        req.setOrderDate(LocalDate.now());
        req.setRequiredDate(requiredDate);
        req.setSalesId(f.user);
        req.setMerchId(f.user);
        req.setCurrency("CNY");
        req.setItems(List.of(orderItem(f.skuM, 60), orderItem(f.skuL, 40)));
        long id = orderService.create(req).id();
        orderService.act(id, "submit", new DocActionReq(0, null));
        return id;
    }

    private static OrderItemReq orderItem(long skuId, int qty) {
        OrderItemReq i = new OrderItemReq();
        i.setStyleSkuId(skuId);
        i.setQty(qty);
        i.setPrice(new BigDecimal("89"));
        return i;
    }

    /** 整张订单排给默认加工厂（清加工），提交、审核；交期给远一点，节点计划都在以后（测逾期时再把交期改到以前） */
    private long approvedProduction(Fixture f, long orderId, LocalDate due) {
        ScheduleReq.Block b = new ScheduleReq.Block();
        b.setStyleId(f.style);
        b.setFactoryId(f.factory);
        b.setProcessMode("CMT");
        b.setUnitPrice(new BigDecimal("38"));
        b.setDueDate(due);
        b.setMerchId(f.user);
        b.setItems(Map.of(f.skuM, 60, f.skuL, 40).entrySet().stream().map(e -> {
            ScheduleReq.Item i = new ScheduleReq.Item();
            i.setOrderItemId(jdbcTemplate.queryForObject("SELECT id FROM ord_order_item WHERE order_id = ? AND style_sku_id = ?",
                    Long.class, orderId, e.getKey()));
            i.setQty(e.getValue());
            return i;
        }).toList());
        ScheduleReq req = new ScheduleReq();
        req.setOrderId(orderId);
        req.setBlocks(List.of(b));
        long id = scheduleService.schedule(req).getFirst().id();
        productionOrderService.act(id, "submit", new DocActionReq(productionOrderService.detail(id).getVersion(), null));
        productionOrderService.act(id, "audit", new DocActionReq(productionOrderService.detail(id).getVersion(), null));
        return id;
    }
}
