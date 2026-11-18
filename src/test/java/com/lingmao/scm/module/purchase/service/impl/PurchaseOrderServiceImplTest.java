package com.lingmao.scm.module.purchase.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.price.dto.DocPrices;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceLine;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.dto.PriceSource;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.purchase.entity.PurchaseOrder;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.entity.PurchaseType;
import com.lingmao.scm.module.purchase.mapper.MrpCalcMapper;
import com.lingmao.scm.module.purchase.mapper.MrpCalcTransitMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.DefaultCompanyProvider;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 采购单（docs/api/03 §3.3，PRD §6.6.2、§6.10） */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceImplTest {

    @Mock
    private PurchaseOrderMapper poMapper;
    @Mock
    private PurchaseOrderItemMapper itemMapper;
    @Mock
    private MrpCalcMapper calcMapper;
    @Mock
    private MrpCalcTransitMapper transitMapper;
    @Mock
    private MrpService mrpService;
    @Mock
    private NeedDates needDates;
    @Mock
    private PurchaseExcel excel;
    @Mock
    private MaterialService materialService;
    @Mock
    private SupplierService supplierService;
    @Mock
    private PriceService priceService;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private CompanyService companyService;
    @Mock
    private DefaultCompanyProvider defaultCompanyProvider;
    @Mock
    private StyleService styleService;
    @Mock
    private DictDataService dictDataService;
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
    private ApprovalService approvalService;
    @Mock
    private ReferenceService referenceService;
    @Mock
    private SerialNoGenerator serialNoGenerator;

    private PurchaseOrderServiceImpl service;

    /** 审核用 LambdaUpdateWrapper.set，要表信息 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PurchaseOrder.class);
    }

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl(poMapper, itemMapper, calcMapper, transitMapper, mrpService, needDates, excel,
                materialService, supplierService, priceService, warehouseService, companyService, defaultCompanyProvider, styleService,
                dictDataService, userService, docHeaderService, docLogService, docNoticeService, attachmentService, approvalService,
                referenceService, serialNoGenerator, Clock.systemDefaultZone());
        login("purchase:order:edit", "purchase:order:submit", "purchase:order:audit", "purchase:order:close", "inventory:doc:add", "techpack:cost:query");
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String... perms) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti"), null,
                List.of(perms).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /**
     * 规则（§3.3.6）：提交时一次列全——结算方式、税率没填，单价和档案现价不一样又没写原因的行（保存以后档案变了的）；
     * 有问题不改状态、不开审批
     */
    @Test
    void submit_listsEveryProblem() {
        PurchaseOrder po = po(DocStatus.DRAFT);
        po.setSettleMethod(null);
        po.setTaxPct(null);
        when(poMapper.lockById(5L)).thenReturn(po);
        WarehouseVO w = new WarehouseVO();
        w.setStatus(1);
        w.setType("MATERIAL");
        when(warehouseService.getById(3L)).thenReturn(w);
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("19", null)));
        stubArchive("18");

        assertThatThrownBy(() -> service.act(5L, "submit", new DocActionReq(0, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat((List<?>) e.getData()).hasSize(3).last().asString().contains("要写改价原因");
                });
        verify(poMapper, never()).update(any(), any());
        verify(approvalService, never()).start(any(), anyLong(), any());
    }

    /**
     * 规则（PRD §6.10 规则 4、RFC 006 §5）：最后一级审过改成已审核，各行按单头的税率、含税含运和改价原因记进价格档案，
     * 来源单据是这张单
     */
    @Test
    void audit_lastLevel_recordsPrices() {
        PurchaseOrder po = po(DocStatus.PENDING);
        when(poMapper.lockById(5L)).thenReturn(po);
        when(itemMapper.selectByOrder(5L)).thenReturn(List.of(item("19", "布价涨了")));
        stubArchive("18");
        when(approvalService.pass(BizType.PURCHASE_ORDER, 5L)).thenReturn(true);
        when(poMapper.update(any(), any())).thenReturn(1);
        when(priceService.recordFromDoc(any(), any(), any())).thenReturn(new DocPrices(List.of(), List.of()));

        service.act(5L, "audit", new DocActionReq(0, null));

        ArgumentCaptor<List<PriceLine>> lines = ArgumentCaptor.captor();
        verify(priceService).recordFromDoc(eq(new PriceSource(BizType.PURCHASE_ORDER, 5L, "CG202611170001")),
                eq(LocalDate.of(2026, 11, 17)), lines.capture());
        assertThat(lines.getValue()).singleElement().satisfies(l -> {
            assertThat(l.price()).isEqualByComparingTo("19");
            assertThat(l.taxPct()).isEqualByComparingTo("13");
            assertThat(l.reason()).isEqualTo("布价涨了");
            assertThat(l.materialId()).isEqualTo(50L);
        });
        verify(docLogService).add(BizType.PURCHASE_ORDER, 5L, "audit", DocStatus.PENDING, DocStatus.APPROVED, null);
    }

    /** 按到货算状态：都到齐是已到齐，有一行到了是部分到货，退光了回到已审核 */
    @Test
    void byArrival() {
        PurchaseOrderItem done = item("10", null);
        done.setReceivedQty(new BigDecimal("100"));
        PurchaseOrderItem part = item("10", null);
        part.setReceivedQty(new BigDecimal("30"));
        PurchaseOrderItem returned = item("10", null);
        returned.setReceivedQty(new BigDecimal("30"));
        returned.setReturnedQty(new BigDecimal("30"));
        assertThat(PurchaseOrderServiceImpl.byArrival(List.of(done))).isEqualTo(DocStatus.DONE);
        assertThat(PurchaseOrderServiceImpl.byArrival(List.of(done, part))).isEqualTo(DocStatus.RUNNING);
        assertThat(PurchaseOrderServiceImpl.byArrival(List.of(returned))).isEqualTo(DocStatus.APPROVED);
    }

    /**
     * 按钮（前端 PurchaseAction）：有下游的不出反审核；到货入库看库存的权限；撤回只给制单人；待审核轮到我才出审核、驳回。
     * 跟进只在待到料的行上出，单据上没有
     */
    @Test
    void actions() {
        PurchaseOrderServiceImpl.Ctx none = new PurchaseOrderServiceImpl.Ctx(false, null, false);
        assertThat(PurchaseOrderServiceImpl.actions(DocStatus.APPROVED, none)).contains("unaudit", "close", "receive")
                .doesNotContain("follow");
        assertThat(PurchaseOrderServiceImpl.actions(DocStatus.APPROVED, new PurchaseOrderServiceImpl.Ctx(false, null, true)))
                .doesNotContain("unaudit");
        assertThat(PurchaseOrderServiceImpl.actions(DocStatus.PENDING, none)).doesNotContain("withdraw", "audit", "reject");
        ApprovalView myTurn = new ApprovalView(1, List.of(), null, true);
        assertThat(PurchaseOrderServiceImpl.actions(DocStatus.PENDING, new PurchaseOrderServiceImpl.Ctx(true, myTurn, false)))
                .contains("withdraw", "audit", "reject");

        login("purchase:order:edit");
        assertThat(PurchaseOrderServiceImpl.actions(DocStatus.APPROVED, none)).doesNotContain("receive", "unaudit");
    }

    private void stubArchive(String price) {
        MaterialPurchaseInfo info = new MaterialPurchaseInfo(100L, "ML0001-KQ", 50L, "FABRIC", "METER", BigDecimal.ONE,
                BigDecimal.ONE, null, null, null, null, 9L, null, null, new BigDecimal("20"), true);
        when(materialService.purchaseInfos(any())).thenReturn(Map.of(100L, info));
        when(priceService.currentMaterials(any(), any())).thenReturn(Map.of(new MaterialPriceKey(9L, 50L, 100L),
                new PriceRef(77L, new BigDecimal(price), "METER", new BigDecimal("13"), true, true, LocalDate.of(2026, 11, 1), 100L,
                        com.lingmao.scm.module.price.entity.PriceType.MATERIAL)));
    }

    private static PurchaseOrder po(DocStatus status) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(5L);
        po.setNo("CG202611170001");
        po.setStatus(status);
        po.setVersion(0);
        po.setCreateBy(1L);
        po.setBuyerId(1L);
        po.setSupplierId(9L);
        po.setPurchaseType(PurchaseType.ORDER);
        po.setWarehouseId(3L);
        po.setOrderDate(LocalDate.of(2026, 11, 17));
        po.setSettleMethod("MONTHLY");
        po.setTaxPct(new BigDecimal("13"));
        po.setTaxIncluded(true);
        po.setFreightIncluded(true);
        po.setTotalAmount(new BigDecimal("190"));
        return po;
    }

    private static PurchaseOrderItem item(String price, String reason) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setId(11L);
        i.setPurchaseOrderId(5L);
        i.setSeq(1);
        i.setMaterialSkuId(100L);
        i.setPurchaseUnit("METER");
        i.setConversionFactor(BigDecimal.ONE);
        i.setQty(new BigDecimal("100"));
        i.setPrice(new BigDecimal(price));
        i.setPriceChangeReason(reason);
        i.setReceivedQty(BigDecimal.ZERO);
        i.setReturnedQty(BigDecimal.ZERO);
        return i;
    }
}
