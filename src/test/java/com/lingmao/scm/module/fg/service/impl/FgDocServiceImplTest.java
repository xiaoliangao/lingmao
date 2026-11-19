package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.service.ActionRequestService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.fg.dto.FgDocCreateReq;
import com.lingmao.scm.module.fg.dto.FgDocCreatedVO;
import com.lingmao.scm.module.fg.dto.FgDocItemReq;
import com.lingmao.scm.module.fg.dto.FgFromReceiptReq;
import com.lingmao.scm.module.fg.dto.FgOverRow;
import com.lingmao.scm.module.fg.entity.FgDoc;
import com.lingmao.scm.module.fg.entity.FgDocItem;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.mapper.FgDocItemMapper;
import com.lingmao.scm.module.fg.mapper.FgDocMapper;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import com.lingmao.scm.module.order.dto.OrderShipView;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ReceiptForStock;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.FileImportService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 成品入库单、出库单（RFC 008、docs/api/07 §3.2–§3.4） */
@ExtendWith(MockitoExtension.class)
class FgDocServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @Mock
    private FgDocMapper docMapper;
    @Mock
    private FgDocItemMapper itemMapper;
    @Mock
    private FgStockMapper stockMapper;
    @Mock
    private FgStockLogMapper logMapper;
    @Mock
    private FgLedger ledger;
    @Mock
    private FgStyles styles;
    @Mock
    private FgExcel excel;
    @Mock
    private ProductionReceiptService receiptService;
    @Mock
    private OrderService orderService;
    @Mock
    private WarehouseService warehouseService;
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
    private SerialNoGenerator serialNoGenerator;
    @Mock
    private ActionRequestService actionRequestService;
    @Mock
    private FileImportService imports;

    private FgDocServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FgDocServiceImpl(docMapper, itemMapper, stockMapper, logMapper, ledger, styles, excel, receiptService, orderService,
                warehouseService, companyService, dictDataService, userService, docHeaderService, docLogService, attachmentService,
                serialNoGenerator, actionRequestService, imports, CLOCK);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti"), null,
                Stream.of("fg:doc:add", "fg:doc:edit", "fg:doc:remove", "fg:doc:audit").map(SimpleGrantedAuthority::new).toList()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则（照物料，用户 11-17 定）：没过过账的草稿给"删除"，过过账又反审核回来的给"作废"；已审核只有反审核 */
    @Test
    void actions_postedDraftGetsVoidInsteadOfRemove() {
        assertThat(FgDocServiceImpl.actions(DocStatus.DRAFT, false)).containsExactly("edit", "audit", "remove");
        assertThat(FgDocServiceImpl.actions(DocStatus.DRAFT, true)).containsExactly("edit", "audit", "void");
        assertThat(FgDocServiceImpl.actions(DocStatus.APPROVED, true)).containsExactly("unaudit");
        assertThat(FgDocServiceImpl.actions(DocStatus.VOIDED, true)).isEmpty();
    }

    /** 规则（RFC 008 §3.4）：每个 SKU 最多发 ⌊订单数 × 1.03⌋，整数算、往下取 */
    @Test
    void shipCap_isFloorOfThreePercentOver() {
        assertThat(FgDocServiceImpl.shipCap(100)).isEqualTo(103);
        assertThat(FgDocServiceImpl.shipCap(120)).isEqualTo(123);
        assertThat(FgDocServiceImpl.shipCap(33)).isEqualTo(33);
        assertThat(FgDocServiceImpl.shipCap(34)).isEqualTo(35);
        assertThat(FgDocServiceImpl.shipCap(0)).isZero();
    }

    /** 规则：入库单加入仓、出库单减出仓，日志类型就是单据类型；反审核数量反过来 */
    @Test
    void posting_signFollowsKind() {
        FgDoc in = doc(FgDocKind.IN, FgDocType.RECEIPT_IN);
        FgDoc out = doc(FgDocKind.OUT, FgDocType.SHIP_OUT);
        List<FgDocItem> items = List.of(item(1, 501L, 30), item(2, 502L, 5));

        assertThat(FgDocServiceImpl.posting(in, items).moves()).extracting(FgLedger.Move::warehouseId, FgLedger.Move::delta)
                .containsExactly(tuple(3L, 30), tuple(3L, 5));
        FgLedger.Posting ship = FgDocServiceImpl.posting(out, items);
        assertThat(ship.type()).isEqualTo(FgDocType.SHIP_OUT);
        assertThat(ship.moves()).extracting(FgLedger.Move::delta).containsExactly(-30, -5);
        assertThat(ship.reversed().moves()).extracting(FgLedger.Move::delta).containsExactly(30, 5);
    }

    /**
     * 规则（RFC 010 §7，用户 11-17 定：能分几次入）：每行最多入"良品 − 别的单已入"，草稿也算已入。
     * 良品 100、别的草稿已占 60，这次填 50 → 1004，清单写最多还能入 40；不建单
     */
    @Test
    void fromReceipt_overRemainingGoodIs1004() {
        runActions();
        when(receiptService.forStock(9L)).thenReturn(receipt(new ReceiptForStock.Item(91L, 501L, 105, 5)));
        when(docMapper.stockedByReceipt(9L, null)).thenReturn(List.of(new FgDocMapper.LineQty(91L, 60)));
        when(warehouseService.getById(3L)).thenReturn(productWarehouse());
        when(styles.bySku(anyList())).thenReturn(Map.of());

        FgFromReceiptReq req = new FgFromReceiptReq();
        req.setClientActionId(UUID.randomUUID());
        req.setWarehouseId(3L);
        FgFromReceiptReq.Item line = new FgFromReceiptReq.Item();
        line.setPrdReceiptItemId(91L);
        line.setQty(50);
        req.setItems(List.of(line));

        assertThatThrownBy(() -> service.fromReceipt(9L, req))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT);
                    assertThat(e.getMessage()).isEqualTo("SKU 501 这次 50 件，最多还能入 40 件");
                    @SuppressWarnings("unchecked")
                    List<FgOverRow> rows = (List<FgOverRow>) e.getData();
                    assertThat(rows).extracting(FgOverRow::sourceNo, FgOverRow::maxQty, FgOverRow::reason)
                            .containsExactly(tuple("HH202611180001", 40, "良品 100，已入 60"));
                });
        verify(docMapper, never()).insert(any(FgDoc.class));
    }

    /** 规则：不填 items 就是把每行还没入的良品全入；都入过了报 1000，提示里有已入的单号 */
    @Test
    void fromReceipt_allStockedListsDocs() {
        runActions();
        when(receiptService.forStock(9L)).thenReturn(receipt(new ReceiptForStock.Item(91L, 501L, 100, 0)));
        when(docMapper.stockedByReceipt(9L, null)).thenReturn(List.of(new FgDocMapper.LineQty(91L, 100)));
        FgDoc cr = doc(FgDocKind.IN, FgDocType.RECEIPT_IN);
        cr.setNo("CR202611180002");
        when(docMapper.selectByReceipt(9L)).thenReturn(List.of(cr));
        FgFromReceiptReq req = new FgFromReceiptReq();
        req.setClientActionId(UUID.randomUUID());

        assertThatThrownBy(() -> service.fromReceipt(9L, req))
                .isInstanceOf(BizException.class)
                .hasMessage("回货单 HH202611180001 的良品都入过库了：CR202611180002");
    }

    /** 规则（RFC 008 §3.4）：发货不能超过 ⌊订单数 × 1.03⌋ − 已发；订单 100 件、已发 0，这次 104 → 1004，最多 103 */
    @Test
    void createShipOut_overThreePercentIs1004() {
        when(warehouseService.getById(3L)).thenReturn(productWarehouse());
        when(orderService.shipView(5L)).thenReturn(new OrderShipView(5L, "DD202611100001", DocStatus.RUNNING, 1L, null,
                List.of(new OrderShipView.Line(51L, 7L, 501L, 100, new BigDecimal("88.5000")))));
        when(docMapper.shippedByOrder(5L, null)).thenReturn(List.of());
        when(styles.bySku(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.create(shipReq(1L, 104)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.OVER_LIMIT);
                    assertThat(e.getMessage()).isEqualTo("SKU 501 这次 104 件，最多还能发 103 件");
                });
        verify(docMapper, never()).insert(any(FgDoc.class));
    }

    /** 规则：发货单的主体要和订单的一样（订单是哪个主体接的，就从哪个主体发） */
    @Test
    void createShipOut_companyMustMatchOrder() {
        when(warehouseService.getById(3L)).thenReturn(productWarehouse());
        when(orderService.shipView(5L)).thenReturn(new OrderShipView(5L, "DD202611100001", DocStatus.APPROVED, 2L, null, List.of()));

        assertThatThrownBy(() -> service.create(shipReq(1L, 10)))
                .isInstanceOf(BizException.class)
                .hasMessage("主体要和订单 DD202611100001 的一样");
    }

    /** 规则：作废只给过过账的草稿，没过过账的提示直接删；作废原因必填 */
    @Test
    void void_onlyForPostedDrafts() {
        FgDoc d = doc(FgDocKind.OUT, FgDocType.OTHER_OUT);
        when(docMapper.selectById(7L)).thenReturn(d);

        assertThatThrownBy(() -> service.act(7L, "void", new DocActionReq(0, " ")))
                .isInstanceOf(BizException.class).hasMessage("请写作废原因");
        when(logMapper.docPosted(7L)).thenReturn(false);
        assertThatThrownBy(() -> service.act(7L, "void", new DocActionReq(0, "录重了")))
                .isInstanceOf(BizException.class).hasMessage("这张单没过过账，直接删除就行");
        verify(docMapper, never()).update(any(FgDoc.class), any());
    }

    // ---------- 造数据 ----------

    @SuppressWarnings("unchecked")
    private void runActions() {
        when(actionRequestService.execute(anyString(), anyLong(), any(), any(), eq(FgDocCreatedVO.class), any()))
                .thenAnswer(inv -> ((Supplier<FgDocCreatedVO>) inv.getArgument(5)).get());
    }

    private static ReceiptForStock receipt(ReceiptForStock.Item... items) {
        return new ReceiptForStock(9L, "HH202611180001", 1L, 4L, "JG202611010001", "OUTSOURCE", 7L, 8L, TODAY.minusDays(1), 3L,
                List.of(items));
    }

    private static WarehouseVO productWarehouse() {
        WarehouseVO w = new WarehouseVO();
        w.setId(3L);
        w.setName("成品仓");
        w.setType("PRODUCT");
        w.setStatus(1);
        return w;
    }

    private static FgDocCreateReq shipReq(long companyId, int qty) {
        FgDocCreateReq req = new FgDocCreateReq();
        req.setCompanyId(companyId);
        req.setDocType(FgDocType.SHIP_OUT);
        req.setOrderId(5L);
        req.setWarehouseId(3L);
        req.setDocDate(TODAY);
        FgDocItemReq item = new FgDocItemReq();
        item.setStyleSkuId(501L);
        item.setQty(qty);
        req.setItems(List.of(item));
        return req;
    }

    private static FgDoc doc(FgDocKind kind, FgDocType type) {
        FgDoc d = new FgDoc();
        d.setId(7L);
        d.setNo("XX");
        d.setKind(kind);
        d.setDocType(type);
        d.setStatus(DocStatus.DRAFT);
        d.setVersion(0);
        d.setCompanyId(1L);
        d.setCreateBy(1L);
        d.setDocDate(TODAY);
        if (kind == FgDocKind.IN) {
            d.setToWarehouseId(3L);
        } else {
            d.setFromWarehouseId(3L);
        }
        return d;
    }

    private static FgDocItem item(int seq, long skuId, int qty) {
        FgDocItem i = new FgDocItem();
        i.setSeq(seq);
        i.setStyleSkuId(skuId);
        i.setQty(qty);
        return i;
    }
}
