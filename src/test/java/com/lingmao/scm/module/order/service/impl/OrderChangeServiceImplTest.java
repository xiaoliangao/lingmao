package com.lingmao.scm.module.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.order.dto.OrderChangeCreateReq;
import com.lingmao.scm.module.order.dto.OrderChangeForm;
import com.lingmao.scm.module.order.entity.ChangeType;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.module.order.entity.OrderChange;
import com.lingmao.scm.module.order.entity.OrderChangeItem;
import com.lingmao.scm.module.order.entity.OrderItem;
import com.lingmao.scm.module.order.entity.ResponsibleParty;
import com.lingmao.scm.module.order.mapper.OrderChangeItemMapper;
import com.lingmao.scm.module.order.mapper.OrderChangeMapper;
import com.lingmao.scm.module.order.mapper.OrderItemMapper;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.module.biz.service.SelfAudit;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 订单变更单（docs/api/02 §3.14，PRD §6.5.6） */
@ExtendWith(MockitoExtension.class)
class OrderChangeServiceImplTest {

    @Mock
    private OrderChangeMapper changeMapper;
    @Mock
    private OrderChangeItemMapper changeItemMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper itemMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private ChangeTexts changeTexts;
    @Mock
    private StyleService styleService;
    @Mock
    private TechPackService techPackService;
    @Mock
    private CompanyService companyService;
    @Mock
    private UserService userService;
    @Mock
    private DocHeaderService docHeaderService;
    @Mock
    private DocLogService docLogService;
    @Mock
    private DocNoticeService docNoticeService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private ReferenceService referenceService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    private OrderChangeServiceImpl service;

    /** 状态动作用 LambdaUpdateWrapper.set，要表信息 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderChange.class);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new OrderChangeServiceImpl(changeMapper, changeItemMapper, orderMapper, itemMapper, orderService, changeTexts,
                styleService, techPackService, companyService, userService, new TradeFeature(false), docHeaderService, docLogService,
                docNoticeService, attachmentService, referenceService, serialNoGenerator, clock);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti"), null,
                        List.of(new SimpleGrantedAuthority(LineScope.ALL_AUTHORITY))));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则（docs/api/02 §3.14）：表单是改完以后的样子，和订单现在比，一样的不算——资料包没换、件数没改的不出；
     * 新加的 SKU 是配色配码，跟着这个款的新单价；顺序是交期、资料包、单价、件数
     */
    @Test
    void diff_onlyWhatChanged() {
        Order o = order();
        when(itemMapper.selectByOrder(7L)).thenReturn(List.of(row(11, 100), row(12, 50)));
        when(styleService.styleIdsOfSkus(List.of(21L))).thenReturn(Map.of(21L, 1L));
        OrderChangeCreateReq f = new OrderChangeCreateReq();
        f.setRequiredDate(LocalDate.of(2026, 12, 10));
        f.setTechPacks(List.of(new OrderChangeForm.TechPack(1L, 501L)));
        f.setPrices(List.of(new OrderChangeForm.Price(1L, new BigDecimal("99"))));
        f.setQuantities(List.of(new OrderChangeForm.Qty(11L, 100), new OrderChangeForm.Qty(12L, 30), new OrderChangeForm.Qty(21L, 20)));

        List<OrderChangeItem> items = service.diff(o, f);

        assertThat(items).extracting(OrderChangeItem::getChangeType, OrderChangeItem::getStyleSkuId,
                        OrderChangeItem::getFromQty, OrderChangeItem::getToQty, OrderChangeItem::getToPrice)
                .containsExactly(tuple(ChangeType.DUE, null, null, null, null),
                        tuple(ChangeType.PRICE, null, null, null, new BigDecimal("99.0000")),
                        tuple(ChangeType.QTY, 12L, 50, 30, null),
                        tuple(ChangeType.COLOR_SIZE, 21L, 0, 20, new BigDecimal("99.0000")));
        assertThat(items.get(1).getFromPrice()).isEqualByComparingTo("89");
        assertThat(OrderChangeServiceImpl.typesText(false, items)).isEqualTo("QTY,DUE,COLOR_SIZE,PRICE");
    }

    /**
     * 规则（docs/api/02 §3.14 的表、PRD §6.5.6 规则 2）：件数不能小于已排产（带上要调的生产单号）；新加的颜色要在锁定的资料包里配了料。
     * 一次列全，消息用"；"连起来，data 是清单
     */
    @Test
    void check_listsEveryProblem() {
        Order o = order();
        OrderItem scheduled = row(11, 100);
        scheduled.setScheduledQty(80);
        when(itemMapper.selectByOrder(7L)).thenReturn(List.of(scheduled));
        when(styleService.orderInfos(any())).thenReturn(Map.of(1L, style()));
        when(techPackService.briefs(Set.of(501L)))
                .thenReturn(Map.of(501L, new PackBrief(501, 1, PackStage.PROD, 2, PackStatus.EFFECTIVE, Set.of(10L))));
        when(referenceService.referrers(BizType.ORDER, 7L)).thenReturn(List.of(
                new DocRef(BizType.PRODUCTION_ORDER, "OUTSOURCE", null, "外发加工单", 30, "JG202611180001", "DRAFT", "草稿", null, 4L, null)));
        List<OrderChangeItem> items = List.of(qty(ChangeType.QTY, 11, 100, 60), qty(ChangeType.COLOR_SIZE, 21, 0, 20));

        assertThatThrownBy(() -> service.check(o, null, false, ResponsibleParty.SELF, items))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getData()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                            .hasSize(2).satisfies(problems -> {
                        assertThat(problems.getFirst()).contains("27CF001-KQ-M", "已排 80 件", "不能改成 60 件",
                                "JG202611180001（草稿）", "没开始做的撤回或反审核后作废", "结案后另下新单");
                        assertThat(problems.get(1)).isEqualTo("27CF001 酒红：资料包 V2 里没给这个颜色配料，先换资料包版本");
                    });
                });
    }

    /** 规则（docs/api/02 §2.2）：一张订单同时最多一张没审完的变更单；改自己这张不算 */
    @Test
    void check_oneOpenChangePerOrder() {
        OrderChange open = new OrderChange();
        open.setId(9L);
        open.setNo("BG202611170001");
        when(changeMapper.selectOpen(7L)).thenReturn(open);

        assertThatThrownBy(() -> service.check(order(), null, true, ResponsibleParty.SELF, List.of()))
                .isInstanceOf(BizException.class).hasMessage("已经有变更单 BG202611170001 没审完，先处理它");
        service.check(order(), 9L, true, ResponsibleParty.SELF, List.of());
    }

    /**
     * 规则（docs/api/02 §3.14）：审核先写回订单，再给每张下游单据插一条变更提醒，提醒给那张单的负责人。
     * 甲方 11-21（Q10）：自己建的变更单自己审不了，什么都不写；别人（2 号）建的才审得过
     */
    @Test
    void audit_writesBackThenNotifies() {
        OrderChange c = new OrderChange();
        c.setId(5L);
        c.setNo("BG202611200001");
        c.setOrderId(7L);
        c.setStatus(DocStatus.PENDING);
        c.setVersion(2);
        c.setCreateBy(2L);
        c.setChangeTypes("QTY");
        List<OrderChangeItem> items = List.of(qty(ChangeType.QTY, 12, 50, 30));
        when(changeMapper.lockById(5L)).thenReturn(c);
        when(orderMapper.selectById(7L)).thenReturn(order());
        when(changeItemMapper.selectByChange(5L)).thenReturn(items);
        when(changeMapper.update(any(), any())).thenReturn(1);
        when(referenceService.referrers(BizType.ORDER, 7L)).thenReturn(List.of(
                new DocRef(BizType.PRODUCTION_ORDER, "OUTSOURCE", null, "外发加工单", 30, "JG202611180001", "RUNNING", "生产中", null, 4L, null)));
        when(changeTexts.names(items)).thenReturn(new ChangeTexts.Names(Map.of(1L, "27CF001"), Map.of(12L, "27CF001-KQ-L"), Map.of()));

        c.setCreateBy(1L);
        assertThatThrownBy(() -> service.act(5L, "audit", new DocActionReq(2, null)))
                .isInstanceOf(BizException.class).hasMessage(SelfAudit.MESSAGE);
        verify(orderService, never()).applyChange(any(), any());

        c.setCreateBy(2L);
        service.act(5L, "audit", new DocActionReq(2, null));

        verify(orderService).applyChange(c, items);
        verify(docNoticeService).add(BizType.PRODUCTION_ORDER, 30L, BizType.ORDER_CHANGE, 5L,
                "上游订单 DD202611010001 于 11-20 变更（BG202611200001）：27CF001-KQ-L：50 → 30", 4L);
    }

    private static Order order() {
        Order o = new Order();
        o.setId(7L);
        o.setNo("DD202611010001");
        o.setStatus(DocStatus.APPROVED);
        o.setCreateBy(1L);
        o.setOrderDate(LocalDate.of(2026, 11, 1));
        o.setRequiredDate(LocalDate.of(2026, 12, 1));
        return o;
    }

    /** 款 1 的一行：卡其（颜色 10）的 SKU 11、12，单价 89，锁定资料包 501 */
    private static OrderItem row(long skuId, int qty) {
        OrderItem i = new OrderItem();
        i.setId(100 + skuId);
        i.setSeq((int) skuId);
        i.setStyleId(1L);
        i.setStyleSkuId(skuId);
        i.setQty(qty);
        i.setPrice(new BigDecimal("89"));
        i.setTechPackId(501L);
        i.setScheduledQty(0);
        i.setReturnedQty(0);
        return i;
    }

    private static StyleOrderInfo style() {
        return new StyleOrderInfo(1, "27CF001", "风衣", null, null, StyleStatus.ENABLED, null, List.of("M", "L"),
                List.of(new StyleOrderInfo.Color(10, "KQ", "卡其", null, true), new StyleOrderInfo.Color(20, "JH", "酒红", null, true)),
                List.of(new StyleOrderInfo.Sku(11, 10, "M", "27CF001-KQ-M", true), new StyleOrderInfo.Sku(12, 10, "L", "27CF001-KQ-L", true),
                        new StyleOrderInfo.Sku(21, 20, "M", "27CF001-JH-M", true)),
                null, null);
    }

    private static OrderChangeItem qty(ChangeType type, long skuId, int from, int to) {
        OrderChangeItem ci = new OrderChangeItem();
        ci.setChangeType(type);
        ci.setStyleId(1L);
        ci.setStyleSkuId(skuId);
        ci.setFromQty(from);
        ci.setToQty(to);
        return ci;
    }
}
