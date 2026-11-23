package com.lingmao.scm.module.order.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.entity.ChangeType;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.module.order.entity.OrderChange;
import com.lingmao.scm.module.order.entity.OrderChangeItem;
import com.lingmao.scm.module.order.entity.OrderItem;
import com.lingmao.scm.module.order.mapper.OrderChangeItemMapper;
import com.lingmao.scm.module.order.mapper.OrderChangeMapper;
import com.lingmao.scm.module.order.mapper.OrderItemMapper;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.LoginUser;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 需求订单（docs/api/02 §3.6、§3.7，PRD §6.5） */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper itemMapper;
    @Mock
    private OrderChangeMapper changeMapper;
    @Mock
    private OrderChangeItemMapper changeItemMapper;
    @Mock
    private OrderExcel excel;
    @Mock
    private ChangeTexts changeTexts;
    @Mock
    private StyleService styleService;
    @Mock
    private TechPackService techPackService;
    @Mock
    private CompanyService companyService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private UserService userService;
    @Mock
    private DocHeaderService docHeaderService;
    @Mock
    private DocLogService docLogService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private ApprovalService approvalService;
    @Mock
    private ReferenceService referenceService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    private OrderServiceImpl service;

    /** 审核、写回用 LambdaUpdateWrapper.set，要表信息 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(org.mockito.Mockito.mock(com.lingmao.scm.module.biz.service.TagFilterService.class), orderMapper, itemMapper, changeMapper, changeItemMapper, excel, changeTexts, styleService,
                techPackService, companyService, dictDataService, userService, new TradeFeature(false), docHeaderService,
                docLogService, attachmentService, approvalService, referenceService, serialNoGenerator, Clock.systemDefaultZone(), com.lingmao.scm.DirectFileImports.INSTANCE);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti"), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则（docs/api/02 §3.6）：提交的检查一次列全——草稿款、停用的 SKU、没配料的颜色（同一个颜色只说一次）；
     * 配了料、启用的不出
     */
    @Test
    void packProblems_listsEveryProblemOnce() {
        StyleOrderInfo coat = style(1, "27CF001", StyleStatus.ENABLED);
        StyleOrderInfo draft = style(2, "27CF002", StyleStatus.DRAFT);
        PackBrief pack = new PackBrief(501, 1, PackStage.PROD, 2, PackStatus.EFFECTIVE, Set.of(10L));
        List<OrderItem> items = List.of(
                item(1, 1, 11, 100),    // 卡其 M：没问题
                item(2, 1, 12, 50),     // 卡其 L：SKU 停用
                item(3, 1, 21, 30),     // 酒红 M：没配料
                item(4, 1, 22, 30),     // 酒红 L：同一个颜色，不再说
                item(5, 2, 31, 10));    // 草稿款

        assertThat(OrderServiceImpl.packProblems(items, Map.of(1L, coat, 2L, draft), Map.of(1L, pack)))
                .containsExactly("27CF001-KQ-L 已停用",
                        "27CF001 酒红：资料包 V2 里没给这个颜色配料",
                        "27CF002 草稿款不能下单：先在款式里启用");
        assertThat(OrderServiceImpl.packProblems(List.of(), Map.of(), Map.of())).containsExactly("明细一行都没有：先加款式、填件数");
    }

    /** 规则（docs/api/02 §3.7，架构 §4.8）：中间一级审过还是待审核，只记承诺交期、version + 1，不锁资料包 */
    @Test
    void audit_middleLevelStaysPending() {
        Order o = order(DocStatus.PENDING);
        when(orderMapper.lockById(7L)).thenReturn(o);
        when(itemMapper.selectByOrder(7L)).thenReturn(List.of(item(1, 1, 11, 100)));
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style(1, "27CF001", StyleStatus.ENABLED)));
        when(techPackService.effectiveProd(List.of(1L)))
                .thenReturn(Map.of(1L, new PackBrief(501, 1, PackStage.PROD, 2, PackStatus.EFFECTIVE, null)));
        when(approvalService.pass(BizType.ORDER, 7L)).thenReturn(false);
        when(orderMapper.update(any(), any())).thenReturn(1);

        service.audit(7L, new OrderAuditReq(3, LocalDate.of(2026, 12, 20)));

        verify(itemMapper, never()).update(any(), any());
        verify(docLogService).add(eq(BizType.ORDER), eq(7L), eq("audit"), contains("等下一级审"));
    }

    /** 规则（docs/api/02 §3.6）：审核后提过变更单、又反审核回来的草稿不能删，只能作废（变更单要指着它） */
    @Test
    void delete_orderWithChangesOnlyVoid() {
        when(orderMapper.lockById(7L)).thenReturn(order(DocStatus.DRAFT));
        when(changeMapper.orderHasChanges(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(BizException.class).hasMessage("这张订单审核后提过变更单，不能删除，只能作废");
        verify(itemMapper, never()).deleteByOrder(anyLong());
        verify(orderMapper, never()).deleteById(anyLong());
    }

    /**
     * 规则（PRD §6.5.6 规则 2、docs/api/02 §3.14）：变更写回前核对——件数不能小于已排产；"原"不等于订单现在的值 1002
     */
    @Test
    void applyChange_checksScheduledAndStale() {
        Order o = order(DocStatus.RUNNING);
        OrderItem row = item(1, 1, 11, 120);
        row.setScheduledQty(80);
        when(orderMapper.lockById(7L)).thenReturn(o);
        when(itemMapper.selectByOrder(7L)).thenReturn(List.of(row));
        OrderChange change = new OrderChange();
        change.setOrderId(7L);
        change.setNo("BG202611170001");
        change.setChangeTypes("QTY");
        when(styleService.orderInfos(List.of(1L))).thenReturn(Map.of(1L, style(1, "27CF001", StyleStatus.ENABLED)));

        assertThatThrownBy(() -> service.applyChange(change, List.of(qty(11, 120, 60))))
                .isInstanceOf(BizException.class).hasMessageStartingWith("27CF001-KQ-M：已排 80 件、已回 0 件，不能改成 60 件")
                .hasMessageContaining("没开始做的撤回或反审核后作废").hasMessageContaining("结案后另下新单");
        assertThatThrownBy(() -> service.applyChange(change, List.of(qty(11, 100, 90))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED));
        verify(orderMapper, never()).update(any(), any());
    }

    /** 款 1 两个颜色（卡其 10、酒红 20）、两个尺码；卡其 L 停用。款 2 一个 SKU */
    private static StyleOrderInfo style(long id, String code, StyleStatus status) {
        if (id == 2) {
            return new StyleOrderInfo(2, code, "风衣", null, null, status, null, List.of("M"),
                    List.of(new StyleOrderInfo.Color(30, "HS", "黑色", null, true)),
                    List.of(new StyleOrderInfo.Sku(31, 30, "M", code + "-HS-M", true)), null, null);
        }
        return new StyleOrderInfo(id, code, "风衣", null, null, status, null, List.of("M", "L"),
                List.of(new StyleOrderInfo.Color(10, "KQ", "卡其", null, true), new StyleOrderInfo.Color(20, "JH", "酒红", null, true)),
                List.of(new StyleOrderInfo.Sku(11, 10, "M", code + "-KQ-M", true), new StyleOrderInfo.Sku(12, 10, "L", code + "-KQ-L", false),
                        new StyleOrderInfo.Sku(21, 20, "M", code + "-JH-M", true), new StyleOrderInfo.Sku(22, 20, "L", code + "-JH-L", true)),
                null, null);
    }

    private static Order order(DocStatus status) {
        Order o = new Order();
        o.setId(7L);
        o.setNo("DD202611010001");
        o.setStatus(status);
        o.setVersion(3);
        o.setCreateBy(1L);
        o.setOrderDate(LocalDate.of(2026, 11, 1));
        o.setRequiredDate(LocalDate.of(2026, 12, 31));
        return o;
    }

    private static OrderItem item(int seq, long styleId, long skuId, int qty) {
        OrderItem i = new OrderItem();
        i.setId(100L + seq);
        i.setOrderId(7L);
        i.setSeq(seq);
        i.setStyleId(styleId);
        i.setStyleSkuId(skuId);
        i.setQty(qty);
        i.setPrice(new BigDecimal("89"));
        i.setScheduledQty(0);
        i.setReturnedQty(0);
        return i;
    }

    private static OrderChangeItem qty(long skuId, int from, int to) {
        OrderChangeItem ci = new OrderChangeItem();
        ci.setChangeType(ChangeType.QTY);
        ci.setStyleId(1L);
        ci.setStyleSkuId(skuId);
        ci.setFromQty(from);
        ci.setToQty(to);
        return ci;
    }
}
