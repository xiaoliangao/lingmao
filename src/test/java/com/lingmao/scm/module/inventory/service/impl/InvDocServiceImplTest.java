package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.inventory.dto.InvDocCreateReq;
import com.lingmao.scm.module.inventory.dto.InvDocFromSourceReq;
import com.lingmao.scm.module.inventory.dto.SourceDraft;
import com.lingmao.scm.module.inventory.dto.SourcePosting;
import com.lingmao.scm.module.inventory.entity.InvDoc;
import com.lingmao.scm.module.inventory.entity.InvDocItem;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import com.lingmao.scm.module.inventory.mapper.InvDocItemMapper;
import com.lingmao.scm.module.inventory.mapper.InvDocMapper;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.inventory.service.StockSourceHandler;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 物料入库单、出库单（docs/api/04 §3.2、§3.3） */
@ExtendWith(MockitoExtension.class)
class InvDocServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private InvDocMapper invDocMapper;
    @Mock
    private InvDocItemMapper itemMapper;
    @Mock
    private StockLogMapper stockLogMapper;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private StockLedger ledger;
    @Mock
    private InvDocExcel excel;
    @Mock
    private MaterialService materialService;
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
    private ReferenceService referenceService;
    @Mock
    private SerialNoGenerator serialNoGenerator;
    @Mock
    private com.lingmao.scm.module.biz.service.ActionRequestService actionRequestService;
    @Mock
    private StockSourceHandler purchaseHandler;

    private InvDocServiceImpl service;

    @BeforeEach
    void setUp() {
        when(purchaseHandler.sourceType()).thenReturn(BizType.PURCHASE_ORDER);
        service = new InvDocServiceImpl(org.mockito.Mockito.mock(com.lingmao.scm.module.biz.service.TagFilterService.class), invDocMapper, itemMapper, stockLogMapper, stockMapper, ledger, excel, materialService,
                warehouseService, companyService, dictDataService, userService, docHeaderService, docLogService, attachmentService,
                referenceService, serialNoGenerator, actionRequestService,
                CLOCK, List.of(purchaseHandler));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti"), null,
                Arrays.stream(new String[]{"inventory:doc:add", "inventory:doc:edit", "inventory:doc:remove", "inventory:doc:audit"})
                        .map(SimpleGrantedAuthority::new).toList()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则（用户 11-17 定）：没过过账的草稿给"删除"，过过账又反审核回来的给"作废"，两个不同时出；已审核只有反审核 */
    @Test
    void actions_postedDraftGetsVoidInsteadOfRemove() {
        assertThat(InvDocServiceImpl.actions(DocStatus.DRAFT, false)).containsExactly("edit", "audit", "remove");
        assertThat(InvDocServiceImpl.actions(DocStatus.DRAFT, true)).containsExactly("edit", "audit", "void");
        assertThat(InvDocServiceImpl.actions(DocStatus.APPROVED, true)).containsExactly("unaudit");
        assertThat(InvDocServiceImpl.actions(DocStatus.VOIDED, true)).isEmpty();
    }

    /** 规则：库存怎么动只看有没有出仓、入仓：调拨一行两笔（出仓减、入仓加），其他入库只加入仓；日志类型就是单据类型 */
    @Test
    void posting_followsWarehousesOfType() {
        InvDoc transfer = doc(InvDocType.TRANSFER, 1L, 2L);
        InvDocItem item = item(31L, 1, 10L, "5");

        StockLedger.Posting p = InvDocServiceImpl.posting(transfer, List.of(item));

        assertThat(p.invDocId()).isEqualTo(7L);
        assertThat(p.moves()).extracting(StockLedger.Move::warehouseId, StockLedger.Move::delta, StockLedger.Move::changeType)
                .containsExactly(tuple(1L, new BigDecimal("-5"), StockChangeType.TRANSFER),
                        tuple(2L, new BigDecimal("5"), StockChangeType.TRANSFER));
        assertThat(InvDocServiceImpl.posting(doc(InvDocType.OTHER_IN, null, 2L), List.of(item)).moves())
                .extracting(StockLedger.Move::warehouseId, StockLedger.Move::delta).containsExactly(tuple(2L, new BigDecimal("5")));
    }

    /** 规则：过过账（库存日志里有它）的草稿不能删，只能作废；手建不收必须从源单生成的类型（400） */
    @Test
    void delete_postedDraftIsRefused_andSourceTypesCannotBeHandMade() {
        InvDoc posted = doc(InvDocType.OTHER_OUT, 1L, null);
        posted.setCreateBy(1L);
        when(invDocMapper.lockById(7L)).thenReturn(posted);
        when(stockLogMapper.docPosted(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(BizException.class).hasMessageContaining("只能作废");
        verify(invDocMapper, never()).deleteById(anyLong());

        InvDocCreateReq req = new InvDocCreateReq();
        req.setDocType(InvDocType.PURCHASE_IN);
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400))
                .hasMessage("采购入库要从采购单生成");
        verify(invDocMapper, never()).insert(any(InvDoc.class));
    }

    /**
     * 规则（docs/api/04 §3.3.1）：由采购单入库，数量按采购单位来，换成库存单位 = 采购数量 × 换算系数（三位小数）；
     * 单价按采购单价 ÷ 换算系数（四位小数），金额 = 采购数量 × 采购单价；本次是 0 的行不建；建草稿时就让采购那边查上限
     */
    @Test
    void fromPurchase_convertsUnitsAndChecksSource() {
        when(actionRequestService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.<java.util.function.Supplier<?>>getArgument(5).get());
        SourceDraft.Item roll = new SourceDraft.Item(501L, 10L, "ROLL", new BigDecimal("45.5"), new BigDecimal("910"),
                new BigDecimal("2"), new BigDecimal("2.1"), new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        SourceDraft.Item other = new SourceDraft.Item(502L, 11L, "METER", BigDecimal.ONE, new BigDecimal("12"),
                new BigDecimal("5"), new BigDecimal("5.25"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        DocRef po = new DocRef(BizType.PURCHASE_ORDER, null, null, "采购单", 88L, "CG202611150001", "APPROVED", "已审核", null, 1L, null);
        when(purchaseHandler.prefill(InvDocType.PURCHASE_IN, 88L, List.of(501L, 502L), null))
                .thenReturn(new SourceDraft(3L, po, 40L, null, 2L, List.of(roll, other)));
        WarehouseVO material = new WarehouseVO();
        material.setId(2L);
        material.setType("MATERIAL");
        material.setStatus(1);
        when(warehouseService.getById(2L)).thenReturn(material);
        when(serialNoGenerator.daily("RK")).thenReturn("RK202611170001");
        when(invDocMapper.insert(any(InvDoc.class))).thenAnswer(inv -> {
            inv.<InvDoc>getArgument(0).setId(9L);
            return 1;
        });

        InvDocFromSourceReq req = new InvDocFromSourceReq();
        req.setClientActionId(java.util.UUID.randomUUID());
        req.setDocType(InvDocType.PURCHASE_IN);
        req.setItems(List.of(sourceItem(501L, 10L, "1.5"), sourceItem(502L, 11L, "0")));
        var created = service.fromSource(BizType.PURCHASE_ORDER, 88L, req);

        assertThat(created.status()).isEqualTo("DRAFT");
        ArgumentCaptor<InvDoc> doc = ArgumentCaptor.forClass(InvDoc.class);
        verify(invDocMapper).insert(doc.capture());
        assertThat(doc.getValue().getCompanyId()).isEqualTo(3L);
        assertThat(doc.getValue().getPartnerId()).isEqualTo(40L);
        assertThat(doc.getValue().getSourceNo()).isEqualTo("CG202611150001");
        assertThat(doc.getValue().getDocDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<InvDocItem>> items = ArgumentCaptor.forClass(Collection.class);
        verify(itemMapper).insert(items.capture());
        assertThat(items.getValue()).singleElement().satisfies(i -> {
            assertThat(i.getPurchaseQty()).isEqualByComparingTo("1.5");
            assertThat(i.getQty()).isEqualByComparingTo("68.25");
            assertThat(i.getPrice()).isEqualByComparingTo("20.0000");
            assertThat(i.getAmount()).isEqualByComparingTo("1365.00");
            assertThat(i.getSourceItemId()).isEqualTo(501L);
        });
        ArgumentCaptor<SourcePosting> checked = ArgumentCaptor.forClass(SourcePosting.class);
        verify(purchaseHandler).check(checked.capture());
        assertThat(checked.getValue().lines()).singleElement().satisfies(l -> assertThat(l.purchaseQty()).isEqualByComparingTo("1.5"));
        verify(ledger, never()).post(any());
    }

    private static InvDocFromSourceReq.Item sourceItem(long sourceItemId, long skuId, String qty) {
        InvDocFromSourceReq.Item i = new InvDocFromSourceReq.Item();
        i.setSourceItemId(sourceItemId);
        i.setMaterialSkuId(skuId);
        i.setQty(new BigDecimal(qty));
        return i;
    }

    private static InvDoc doc(InvDocType type, Long from, Long to) {
        InvDoc d = new InvDoc();
        d.setId(7L);
        d.setNo("XX1");
        d.setKind(type.getKind());
        d.setDocType(type);
        d.setStatus(DocStatus.DRAFT);
        d.setFromWarehouseId(from);
        d.setToWarehouseId(to);
        d.setDocDate(LocalDate.of(2026, 11, 17));
        return d;
    }

    private static InvDocItem item(long id, int seq, long skuId, String qty) {
        InvDocItem i = new InvDocItem();
        i.setId(id);
        i.setSeq(seq);
        i.setMaterialSkuId(skuId);
        i.setQty(new BigDecimal(qty));
        return i;
    }
}
