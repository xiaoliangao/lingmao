package com.lingmao.scm.module.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.WarehouseOption;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocActions;
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
import com.lingmao.scm.module.purchase.dto.FollowReq;
import com.lingmao.scm.module.purchase.dto.FromMrpReq;
import com.lingmao.scm.module.purchase.dto.FromReorderReq;
import com.lingmao.scm.module.purchase.dto.OrderCutRisk;
import com.lingmao.scm.module.purchase.dto.PendingArrivalCounts;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.PendingArrivalRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseCreateReq;
import com.lingmao.scm.module.purchase.dto.PurchaseDefaultsQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseDefaultsVO;
import com.lingmao.scm.module.purchase.dto.PurchaseDetailVO;
import com.lingmao.scm.module.purchase.dto.PurchaseForm;
import com.lingmao.scm.module.purchase.dto.PurchaseItemReq;
import com.lingmao.scm.module.purchase.dto.PurchaseItemVO;
import com.lingmao.scm.module.purchase.dto.PurchaseLineDefault;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseSaveReq;
import com.lingmao.scm.module.purchase.dto.PurchaseStockDocVO;
import com.lingmao.scm.module.purchase.dto.ReorderPickItem;
import com.lingmao.scm.module.purchase.entity.MrpCalc;
import com.lingmao.scm.module.purchase.entity.MrpCalcItem;
import com.lingmao.scm.module.purchase.entity.MrpStatus;
import com.lingmao.scm.module.purchase.entity.PurchaseOrder;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.entity.PurchaseType;
import com.lingmao.scm.module.purchase.mapper.MrpCalcMapper;
import com.lingmao.scm.module.purchase.mapper.MrpCalcTransitMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
import com.lingmao.scm.module.style.dto.StyleRef;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.CompanyBrief;
import com.lingmao.scm.module.system.service.DefaultCompanyProvider;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/**
 * 采购单（docs/api/03 §3.3）、待到料（§3.4）。草稿就查供应商、交货地点、单价（Q19、Q11、Q25）；提交、每一级审核再查一遍；
 * 最后一级审过时单价和档案不一样的行记进价格档案（RFC 006 §5）。已审核以后只有预计到货、跟进备注能改（用户 11-15 定），
 * 到货状态由入库单、出库单过账时回写（PurchaseStockHandler）。
 * <p>
 * 从算料单生成的行带 calcItemId，算料行的已转采购 = 指着它的没作废的采购行合计：改数量、删、作废以后由 MrpService 重算。
 * 改这种单之前先锁它的算料单再锁它（MrpServiceImpl 类注释里的锁顺序）
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    /**
     * 状态转换表（docs/api/03 §2.2）。receive、return、follow 不改状态（到货状态由过账回写），写在这里是为了出按钮、查能不能做；
     * audit 中间一级审过还是待审核；reopen 按到货回到已审核、部分到货或已到齐
     */
    static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("submit", DRAFT).to(PENDING)
            .on("withdraw", PENDING).to(DRAFT)
            .on("reject", PENDING).to(DRAFT)
            .on("audit", PENDING).to(PENDING, APPROVED)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("receive", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("return", RUNNING, DONE).to(RUNNING, DONE)
            .on("follow", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("close", APPROVED, RUNNING).to(CLOSED)
            .on("reopen", CLOSED).to(APPROVED, RUNNING, DONE)
            .on("void", PENDING).to(VOIDED)
            .on("remove", DRAFT).to(DRAFT)
            .build();

    static final String PERM = "purchase:order:";

    /** 和前端 types/purchase.ts 的 PURCHASE_STATUS 一样 */
    static final StatusLabels<DocStatus> PO_STATUS = StatusLabels.DOC.rename(RUNNING, "部分到货").rename(DONE, "已到齐");

    /** 交货日期默认 = 下单日 + 各行物料采购周期最长的；没设采购周期的按 7 天 */
    static final int DEFAULT_LEAD_DAYS = 7;

    /** 待到料：几天内到算临期 */
    static final int NEAR_DAYS = 3;

    private static final String NOT_FOUND = "采购单不存在";
    private static final String STALE = "这张采购单已经被别人改过，请刷新后再改";

    private final PurchaseOrderMapper poMapper;
    private final PurchaseOrderItemMapper itemMapper;
    private final MrpCalcMapper calcMapper;
    private final MrpCalcTransitMapper transitMapper;
    private final MrpService mrpService;
    private final NeedDates needDates;
    private final PurchaseExcel excel;
    private final MaterialService materialService;
    private final SupplierService supplierService;
    private final PriceService priceService;
    private final WarehouseService warehouseService;
    private final CompanyService companyService;
    private final DefaultCompanyProvider defaultCompanyProvider;
    private final StyleService styleService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final DocNoticeService docNoticeService;
    private final AttachmentService attachmentService;
    private final ApprovalService approvalService;
    private final ReferenceService referenceService;
    private final SerialNoGenerator serialNoGenerator;
    private final Clock clock;

    // ---------- 查询 ----------

    @Override
    public PageResult<PurchaseRowVO> page(PurchaseQuery query) {
        query.setApprovalIds(query.isAuditForMe() ? approvalService.waitingForMe(BizType.PURCHASE_ORDER) : List.of());
        IPage<PurchaseRowVO> page = poMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()));
        fillRows(page.getRecords());
        return PageResult.of(page);
    }

    /** 列表行的第一行物料、采购员、按钮 */
    private void fillRows(List<PurchaseRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(rows.stream().map(PurchaseRowVO::getFirstSkuId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> names = userService.nicknames(rows.stream().map(PurchaseRowVO::getBuyerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Set<Long> mine = rows.stream().anyMatch(r -> PENDING.name().equals(r.getStatus()))
                ? new HashSet<>(approvalService.waitingForMe(BizType.PURCHASE_ORDER)) : Set.of();
        for (PurchaseRowVO r : rows) {
            r.setFirstItem(r.getFirstSkuId() == null ? null : briefs.get(r.getFirstSkuId()));
            r.setBuyerName(names.get(r.getBuyerId()));
            r.setActions(actions(DocStatus.valueOf(r.getStatus()), new Ctx(isCreator(r.getCreateBy()),
                    new ApprovalView(0, List.of(), null, mine.contains(r.getId())), false)));
        }
    }

    /** 算按钮要的业务条件：creator 我是制单人（撤回），approval 审批块，referenced 有入库单、出库单或被算料单用着（不出反审核；列表不查） */
    record Ctx(boolean creator, ApprovalView approval, boolean referenced) {
    }

    /** 按钮（前端 PurchaseAction）。到货入库、退货看 inventory:doc:add */
    static List<String> actions(DocStatus status, Ctx c) {
        return DocActions.of(FLOW, status, PERM)
                .perm("edit", PERM + "edit")
                .perm("remove", PERM + "remove")
                .perm("receive", "inventory:doc:add")
                .perm("return", "inventory:doc:add")
                .when("withdraw", c.creator())
                .when("unaudit", !c.referenced())
                .approval(c.approval())
                .list();
    }

    private static boolean isCreator(Long createBy) {
        return createBy != null && createBy.equals(LoginUser.currentUserId());
    }

    @Override
    public PurchaseDetailVO detail(long id) {
        PurchaseOrder po = getVisible(id);
        List<PurchaseOrderItem> items = itemMapper.selectByOrder(id);
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(items.stream().map(PurchaseOrderItem::getMaterialSkuId)
                .collect(Collectors.toSet()));
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(items.stream().map(PurchaseOrderItem::getMaterialSkuId)
                .collect(Collectors.toSet()));
        Map<Long, PriceRef> archive = archive(po.getSupplierId(), po.getOrderDate(), infos.values());
        Map<Long, PurchaseOrderItemMapper.CalcSource> sources = calcSources(items);

        PurchaseDetailVO vo = docHeaderService.fill(new PurchaseDetailVO(), BizType.PURCHASE_ORDER, po, po.getStatus());
        vo.setCompanyName(companyService.getById(po.getCompanyId()).getShortName());
        SupplierRef s = supplierService.getRef(po.getSupplierId());
        vo.setSupplierId(s.id());
        vo.setSupplierCode(s.code());
        vo.setSupplierName(s.name());
        vo.setSupplierShortName(s.shortName());
        vo.setSupplierStatus(s.status().name());
        vo.setSupplierContact(s.contact());
        vo.setSupplierPhone(s.phone());
        vo.setPurchaseType(po.getPurchaseType().name());
        vo.setProductLine(po.getProductLine());
        vo.setCalcId(po.getCalcId());
        vo.setCalcNo(po.getCalcId() == null ? null : calcMapper.selectById(po.getCalcId()).getNo());
        vo.setBuyerId(po.getBuyerId());
        vo.setBuyerName(userService.nicknames(List.of(po.getBuyerId())).get(po.getBuyerId()));
        vo.setOrderDate(po.getOrderDate());
        vo.setDeliveryDate(po.getDeliveryDate());
        WarehouseVO w = warehouseService.getById(po.getWarehouseId());
        vo.setWarehouseId(w.getId());
        vo.setWarehouseName(w.getName());
        vo.setWarehouseType(w.getType());
        vo.setWarehouseAddress(w.getAddress());
        vo.setSettleMethod(po.getSettleMethod());
        vo.setTaxPct(po.getTaxPct());
        vo.setTaxIncluded(Boolean.TRUE.equals(po.getTaxIncluded()));
        vo.setFreightIncluded(Boolean.TRUE.equals(po.getFreightIncluded()));
        vo.setTotalQty(po.getTotalQty());
        vo.setTotalAmount(po.getTotalAmount());
        vo.setReceivedQty(Num.qty(sum(items.stream().map(PurchaseOrderItem::netReceived).toList())));
        vo.setReturnedQty(Num.qty(sum(items.stream().map(PurchaseOrderItem::getReturnedQty).toList())));
        vo.setPlanStyles(poMapper.selectPlanStyles(id));
        vo.setItems(items.stream().map(i -> itemVO(po, i, briefs.get(i.getMaterialSkuId()), archive.get(i.getMaterialSkuId()),
                i.getCalcItemId() == null ? null : sources.get(i.getCalcItemId()))).toList());
        vo.setStockDocs(poMapper.selectStockDocs(id).stream().peek(d -> d.setDyeLots(PurTexts.split(d.getDyeLotsText()))).toList());
        boolean referenced = po.getStatus() == APPROVED && !referenceService.referrers(BizType.PURCHASE_ORDER, id).isEmpty();
        vo.setActions(actions(po.getStatus(), new Ctx(isCreator(po.getCreateBy()), vo.getApproval(), referenced)));
        return vo;
    }

    private static PurchaseItemVO itemVO(PurchaseOrder po, PurchaseOrderItem i, MaterialSkuBrief brief, PriceRef archive,
                                         PurchaseOrderItemMapper.CalcSource source) {
        PurchaseItemVO v = new PurchaseItemVO();
        v.fillFrom(brief);
        v.setId(i.getId());
        v.setSeq(i.getSeq());
        v.setPurchaseUnit(i.getPurchaseUnit());
        v.setConversionFactor(i.getConversionFactor());
        v.setQty(i.getQty());
        v.setPrice(i.getPrice());
        v.setAmount(i.getAmount());
        v.setPriceSourceId(i.getPriceSourceId());
        v.setArchivePrice(archive == null ? null : Num.price(archive.price()));
        v.setPriceChangeReason(i.getPriceChangeReason());
        v.setLineDueDate(i.getLineDueDate());
        v.setDueDate(i.getLineDueDate() != null ? i.getLineDueDate() : po.getDeliveryDate());
        v.setCalcItemId(i.getCalcItemId());
        v.setCalcId(source == null ? null : source.calcId());
        v.setCalcNo(source == null ? null : source.calcNo());
        v.setOrderNos(source == null ? List.of() : PurTexts.split(source.orderNos()));
        v.setStyleCodes(source == null ? List.of() : PurTexts.split(source.styleCodes()));
        v.setReceivedQty(i.getReceivedQty());
        v.setReturnedQty(i.getReturnedQty());
        v.setPendingQty(i.pending());
        v.setExpectDate(i.getExpectDate());
        v.setFollowNote(i.getFollowNote());
        return v;
    }

    private Map<Long, PurchaseOrderItemMapper.CalcSource> calcSources(List<PurchaseOrderItem> items) {
        Set<Long> ids = items.stream().map(PurchaseOrderItem::getCalcItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : itemMapper.selectCalcSources(ids).stream()
                .collect(Collectors.toMap(PurchaseOrderItemMapper.CalcSource::calcItemId, s -> s));
    }

    /** 这家供应商在某天的价格档案现价，按 SKU（先 SKU 的、再物料层的，只认物料现在的采购单位） */
    private Map<Long, PriceRef> archive(long supplierId, LocalDate date, Collection<MaterialPurchaseInfo> infos) {
        if (infos.isEmpty()) {
            return Map.of();
        }
        Map<MaterialPriceKey, PriceRef> refs = priceService.currentMaterials(infos.stream()
                .map(i -> new MaterialPriceKey(supplierId, i.materialId(), i.skuId())).toList(), date);
        Map<Long, PriceRef> out = new HashMap<>();
        infos.forEach(i -> {
            PriceRef r = refs.get(new MaterialPriceKey(supplierId, i.materialId(), i.skuId()));
            if (r != null) {
                out.put(i.skuId(), r);
            }
        });
        return out;
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return SupplyBook.sum(values);
    }

    @Override
    public void checkVisible(long id) {
        getVisible(id);
    }

    private PurchaseOrder getVisible(long id) {
        return visible(poMapper.selectById(id));
    }

    /** 改、删、动作之前锁住单头：和审核、过账回写排队 */
    private PurchaseOrder lock(long id) {
        return visible(poMapper.lockById(id));
    }

    /** 数据范围（PRD §3.2）：制单人、采购员；待审核时轮到审的人也能看（规则加的审批人不一定在范围里） */
    private PurchaseOrder visible(PurchaseOrder po) {
        if (po == null || !LineScope.current().canSee(po.getProductLine())) {
            throw new BizException(404, NOT_FOUND);
        }
        if (!DataScope.current(false).canSee(po.getCreateBy(), po.getBuyerId())
                && !(po.getStatus() == PENDING && isMyTurn(po.getId()))) {
            throw new BizException(404, NOT_FOUND);
        }
        com.lingmao.scm.common.scope.CompanyContext.target(po.getCompanyId());
        return po;
    }

    private boolean isMyTurn(long id) {
        ApprovalView view = approvalService.view(BizType.PURCHASE_ORDER, id);
        return view != null && view.canPass();
    }

    @Override
    public PurchaseDefaultsVO defaults(PurchaseDefaultsQuery query) {
        SupplierRef s = supplierService.getRef(query.getSupplierId());
        LocalDate orderDate = query.getOrderDate() == null ? LocalDate.now(clock) : query.getOrderDate();
        List<Long> skuIds = query.getSkuIds() == null ? List.of() : query.getSkuIds().stream().distinct().toList();
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(skuIds);
        Map<Long, PriceRef> archive = archive(s.id(), orderDate, infos.values());
        List<PurchaseLineDefault> lines = skuIds.stream().map(infos::get).filter(Objects::nonNull).map(i -> {
            PriceRef a = archive.get(i.skuId());
            BigDecimal archivePrice = a == null ? null : Num.price(a.price());
            return new PurchaseLineDefault(i.skuId(), archivePrice != null ? archivePrice : Num.price(i.price()), a == null ? null : a.id(),
                    archivePrice, i.purchaseUnit(), i.conversionFactor(), i.leadDays(), i.supplierItemNo(), i.supplierColorNo());
        }).toList();
        String buyerName = s.buyerId() == null ? null : userService.nicknames(List.of(s.buyerId())).get(s.buyerId());
        return new PurchaseDefaultsVO(s.settleMethod(), s.taxPct(), s.buyerId(), buyerName,
                lines.isEmpty() ? null : deliveryDate(orderDate, infos.values()), lines);
    }

    /** 交货日期默认值 = 下单日 + 最长的采购周期（没设的按 7 天） */
    static LocalDate deliveryDate(LocalDate orderDate, Collection<MaterialPurchaseInfo> infos) {
        int days = infos.stream().mapToInt(i -> i.leadDays() == null ? DEFAULT_LEAD_DAYS : i.leadDays()).max().orElse(DEFAULT_LEAD_DAYS);
        return orderDate.plusDays(days);
    }

    // ---------- 新建、改、删 ----------

    @Override
    @Transactional
    public DocSaved create(PurchaseCreateReq req) {
        com.lingmao.scm.common.scope.CompanyContext.create(req.getCompanyId());
        PurchaseOrder po = new PurchaseOrder();
        applyForm(po, req, null);
        po.setStatus(DRAFT);
        Built b = buildItems(po, req.getItems(), List.of());
        if (po.getDeliveryDate() == null) {
            po.setDeliveryDate(deliveryDate(po.getOrderDate(), b.infos().values()));
        }
        insert(po, b.items());
        replacePlanStyles(po, req.getPlanStyleIds(), false);
        docLogService.add(BizType.PURCHASE_ORDER, po.getId(), "create", null);
        return new DocSaved(po.getId(), po.getNo(), 0);
    }

    /** 取单号、算合计、插单头和明细 */
    private void insert(PurchaseOrder po, List<PurchaseOrderItem> items) {
        totals(po, items);
        po.setNo(serialNoGenerator.daily(BizType.PURCHASE_ORDER.getPrefix()));
        poMapper.insert(po);
        items.forEach(i -> i.setPurchaseOrderId(po.getId()));
        itemMapper.insert(items);
    }

    static void totals(PurchaseOrder po, List<PurchaseOrderItem> items) {
        items.forEach(i -> {
            NumericLimits.price(i.getPrice(), "第 " + i.getSeq() + " 行：");
            NumericLimits.quantity(i.getQty(), "第 " + i.getSeq() + " 行：");
            NumericLimits.amount(i.getAmount(), "第 " + i.getSeq() + " 行：");
        });
        po.setTotalQty(NumericLimits.quantity(Num.qty(sum(items.stream().map(PurchaseOrderItem::getQty).toList())), "采购合计"));
        po.setTotalAmount(NumericLimits.amount(Num.money(sum(items.stream().map(PurchaseOrderItem::getAmount).toList())), "采购合计"));
    }

    @Override
    @Transactional
    public int update(long id, PurchaseSaveReq req) {
        Set<Long> calcItems = calcItemIds(itemMapper.selectByOrder(id));
        mrpService.lockCalcsOfItems(calcItems);
        PurchaseOrder po = lock(id);
        com.lingmao.scm.common.scope.CompanyContext.save(po.getCompanyId(), req.getCompanyId(), req.getExpectedCompanyId());
        FLOW.next("edit", po.getStatus());
        if (!po.getVersion().equals(req.getVersion())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        List<PurchaseOrderItem> old = itemMapper.selectByOrder(id);
        if (!calcItemIds(old).equals(calcItems)) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        PurchaseOrder before = new PurchaseOrder();
        BeanUtils.copyProperties(po, before);
        if (!Objects.equals(po.getSupplierId(), req.getSupplierId()))
            com.lingmao.scm.security.FieldPermissions.require(com.lingmao.scm.security.FieldPermissions.COST);
        applyForm(po, req, before);
        Built b = buildItems(po, req.getItems(), old);
        if (po.getDeliveryDate() == null) {
            po.setDeliveryDate(deliveryDate(po.getOrderDate(), b.infos().values()));
        }
        totals(po, b.items());
        UpdateWrapper<PurchaseOrder> update = Updates.replaceById(po)
                .eq("status", DRAFT.name())
                .eq("version", req.getVersion())
                .setSql("version = version + 1");
        if (poMapper.update(po, update) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        deleteLines(id, old);
        b.items().forEach(i -> i.setPurchaseOrderId(id));
        itemMapper.insert(b.items());
        replacePlanStyles(po, req.getPlanStyleIds(), true);
        mrpService.syncConverted(calcItems);
        docLogService.add(BizType.PURCHASE_ORDER, id, "update", null);
        return req.getVersion() + 1;
    }

    /** 删掉草稿原来的行：没确认的算料单记着用了它们的先删（外键），见 MrpCalcTransitMapper.deleteUsesOfLines */
    private void deleteLines(long poId, List<PurchaseOrderItem> old) {
        if (old.isEmpty()) {
            return;
        }
        transitMapper.deleteUsesOfLines(old.stream().map(PurchaseOrderItem::getId).toList());
        itemMapper.deleteByOrder(poId);
    }

    private static Set<Long> calcItemIds(List<PurchaseOrderItem> items) {
        return items.stream().map(PurchaseOrderItem::getCalcItemId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    @Override
    @Transactional
    public void delete(long id) {
        Set<Long> calcItems = calcItemIds(itemMapper.selectByOrder(id));
        mrpService.lockCalcsOfItems(calcItems);
        PurchaseOrder po = lock(id);
        FLOW.next("remove", po.getStatus());
        List<PurchaseOrderItem> old = itemMapper.selectByOrder(id);
        deleteLines(id, old);
        poMapper.deletePlanStyles(id);
        attachmentService.deleteByDoc(BizType.PURCHASE_ORDER, id);
        docLogService.deleteByDoc(BizType.PURCHASE_ORDER, id);
        docNoticeService.deleteByDoc(BizType.PURCHASE_ORDER, id);
        poMapper.deleteById(id);
        Set<Long> now = calcItemIds(old);
        now.addAll(calcItems);
        mrpService.syncConverted(now);
    }

    /**
     * 单头（新建、改草稿，docs/api/03 §3.3.4）。old 是保存前的（新建传 null）：没改的主体、采购员、结算方式后来停用了照样留着，
     * 新选的要启用。供应商每次都要合作中（Q19），交货地点每次都要启用的物料仓或工厂仓（Q11）
     */
    private void applyForm(PurchaseOrder po, PurchaseForm f, PurchaseOrder old) {
        if (old == null || !Objects.equals(old.getCompanyId(), f.getCompanyId())) {
            Company company = companyService.getById(f.getCompanyId());
            if (!Integer.valueOf(1).equals(company.getStatus())) {
                throw new BizException("主体" + company.getShortName() + "已停用，请换一个");
            }
        }
        po.setProductLine(old != null && old.getCalcId() != null ? old.getProductLine()
                : lineFor(f.getProductLine(), old == null ? null : old.getProductLine()));
        supplierService.checkServes(f.getSupplierId(), po.getProductLine());
        supplierService.checkUsable(f.getSupplierId(), SupplierService.Usage.BULK);
        checkWarehouse(f.getWarehouseId());
        if ((old == null || !Objects.equals(old.getBuyerId(), f.getBuyerId())) && !userService.isEnabled(f.getBuyerId())) {
            throw new BizException(400, "采购员的账号不存在或已停用，请换一个人");
        }
        String settle = PurTexts.blankToNull(f.getSettleMethod());
        if (settle != null && (old == null || !settle.equals(old.getSettleMethod()))
                && dictDataService.listByType("settle_method").stream().noneMatch(d -> d.value().equals(settle) && d.enabled())) {
            throw new BizException(400, "结算方式「" + settle + "」不在字典里或已停用");
        }
        if (f.getPurchaseType() != PurchaseType.STOCK && f.getPlanStyleIds() != null && !f.getPlanStyleIds().isEmpty()) {
            throw new BizException(400, "只有备货采购能填计划用于款号");
        }
        po.setCompanyId(f.getCompanyId());
        po.setSupplierId(f.getSupplierId());
        po.setPurchaseType(f.getPurchaseType());
        po.setBuyerId(f.getBuyerId());
        po.setOrderDate(f.getOrderDate());
        po.setDeliveryDate(f.getDeliveryDate());
        po.setWarehouseId(f.getWarehouseId());
        po.setSettleMethod(settle);
        po.setTaxPct(f.getTaxPct());
        po.setTaxIncluded(f.isTaxIncluded());
        po.setFreightIncluded(f.isFreightIncluded());
        po.setRemark(PurTexts.blankToNull(f.getRemark()));
    }

    /**
     * 手工建的采购单的品类线（RFC 013 §2）：选的；没选的沿用原来的，新建的只有一条线的人用他那条，几条的要选。
     * 要是字典里启用的（原来就是它的不管），要在我的线里
     */
    private String lineFor(String picked, String old) {
        LineScope mine = LineScope.current();
        String line = PurTexts.blankToNull(picked);
        if (line == null) {
            line = old != null ? old : mine.only();
        }
        if (line == null) {
            throw new BizException(400, "请选品类线");
        }
        String chosen = line;
        if (!chosen.equals(old) && dictDataService.listByType(LineScope.DICT_TYPE).stream()
                .noneMatch(d -> d.value().equals(chosen) && d.enabled())) {
            throw new BizException(400, "品类线「" + chosen + "」不在字典里或已停用");
        }
        if (!mine.canSee(chosen)) {
            throw new BizException(400, "不能建你看不到的品类线的采购单");
        }
        return chosen;
    }

    /** 交货地点：启用的物料仓或工厂仓（Q11） */
    private void checkWarehouse(long warehouseId) {
        WarehouseVO w = warehouseService.getById(warehouseId);
        if (!Integer.valueOf(1).equals(w.getStatus()) || !Set.of("MATERIAL", "FACTORY").contains(w.getType())) {
            throw new BizException(400, "交货地点要选启用的物料仓或工厂仓");
        }
    }

    private void replacePlanStyles(PurchaseOrder po, List<Long> styleIds, boolean replace) {
        if (replace) {
            poMapper.deletePlanStyles(po.getId());
        }
        if (po.getPurchaseType() != PurchaseType.STOCK || styleIds == null || styleIds.isEmpty()) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>(styleIds);
        Map<Long, StyleRef> styles = styleService.refs(ids);
        if (styles.size() != ids.size()) {
            throw new BizException(400, "计划用于的款号有的不存在了，请刷新后再选");
        }
        // 品类线（RFC 013 §2）：计划用于的款要和采购单同一条线（别的线的款我也看不到）
        Map<Long, String> lines = styleService.productLines(ids);
        List<String> others = ids.stream().filter(id -> !Objects.equals(lines.get(id), po.getProductLine()))
                .map(id -> styles.get(id).code()).toList();
        if (!others.isEmpty()) {
            throw new BizException(400, "计划用于的款 " + String.join("、", others) + " 和这张采购单不是同一条品类线");
        }
        poMapper.insertPlanStyles(po.getId(), ids);
    }

    /** 建好的明细，和用到的物料采购信息（交货日期的默认值要用） */
    record Built(List<PurchaseOrderItem> items, Map<Long, MaterialPurchaseInfo> infos) {
    }

    /**
     * 明细（§3.3.4）：数量 > 0；单价 null 用默认（档案现价 → SKU 单价 → 物料参考单价），都没有 400；和档案现价不一样要写原因（Q25）。
     * old 是这张单原来的行：带 calcItemId 的行要是原来就有的、物料不能换（新建时 old 是空的，带了就报）；停用的物料原来就在单上的照样留着
     */
    private Built buildItems(PurchaseOrder po, List<PurchaseItemReq> reqs, List<PurchaseOrderItem> old) {
        if (reqs.isEmpty()) {
            throw new BizException(400, "明细至少要一行");
        }
        Map<Long, PurchaseOrderItem> oldByCalcItem = old.stream().filter(i -> i.getCalcItemId() != null)
                .collect(Collectors.toMap(PurchaseOrderItem::getCalcItemId, i -> i, (a, b) -> a));
        Map<Long, PurchaseOrderItem> oldById = old.stream().collect(Collectors.toMap(PurchaseOrderItem::getId, i -> i));
        Map<Long, List<PurchaseOrderItem>> oldBySku = old.stream().collect(Collectors.groupingBy(PurchaseOrderItem::getMaterialSkuId));
        boolean priceVisible = com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.COST);
        Set<Long> oldSkus = old.stream().map(PurchaseOrderItem::getMaterialSkuId).collect(Collectors.toSet());
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(reqs.stream().map(PurchaseItemReq::getMaterialSkuId)
                .collect(Collectors.toSet()));
        Map<Long, PriceRef> archive = archive(po.getSupplierId(), po.getOrderDate(), infos.values());
        Set<Long> seenCalcItems = new HashSet<>();
        Set<Long> seenIdentities = new HashSet<>();
        List<PurchaseOrderItem> items = new ArrayList<>();
        for (int n = 0; n < reqs.size(); n++) {
            PurchaseItemReq r = reqs.get(n);
            int seq = n + 1;
            MaterialPurchaseInfo info = infos.get(r.getMaterialSkuId());
            if (info == null) {
                throw new BizException(400, "第 " + seq + " 行的物料不存在了，请刷新后再选");
            }
            if (!info.selectable() && !oldSkus.contains(info.skuId())) {
                throw new BizException(400, "第 " + seq + " 行 " + info.skuCode() + " 已停用，请换一个");
            }
            if (r.getQty() == null || r.getQty().signum() <= 0) {
                throw new BizException(400, "第 " + seq + " 行数量要大于 0");
            }
            if (r.getCalcItemId() != null) {
                PurchaseOrderItem o = oldByCalcItem.get(r.getCalcItemId());
                if (o == null || !seenCalcItems.add(r.getCalcItemId())) {
                    throw new BizException(400, "第 " + seq + " 行的来源算料行不对，请刷新后再改");
                }
                if (!o.getMaterialSkuId().equals(info.skuId())) {
                    throw new BizException(400, "第 " + seq + " 行是从算料单生成的，物料不能换");
                }
            }
            PriceRef a = archive.get(info.skuId());
            BigDecimal archivePrice = a == null ? null : Num.price(a.price());
            PurchaseOrderItem prior = r.getId() == null ? null : oldById.get(r.getId());
            if (r.getId() != null && (prior == null || !Objects.equals(prior.getMaterialSkuId(), r.getMaterialSkuId())))
                throw new BizException(400, "第 " + seq + " 行身份不属于当前采购明细，请刷新后重试");
            if (prior == null && r.getCalcItemId() != null) prior = oldByCalcItem.get(r.getCalcItemId());
            if (prior == null && oldBySku.getOrDefault(r.getMaterialSkuId(), List.of()).size() == 1)
                prior = oldBySku.get(r.getMaterialSkuId()).getFirst();
            if (prior != null && !seenIdentities.add(prior.getId())) throw new BizException(400, "采购明细身份重复，请刷新后重试");
            if (!priceVisible && prior == null)
                com.lingmao.scm.security.FieldPermissions.require(com.lingmao.scm.security.FieldPermissions.COST);
            BigDecimal price = r.getPrice() != null ? NumericLimits.price(r.getPrice(), "第 " + (items.size() + 1) + " 行：")
                    : archivePrice != null ? archivePrice : Num.price(info.price());
            if (!priceVisible) price = com.lingmao.scm.security.FieldPermissions.price(
                    com.lingmao.scm.security.FieldPermissions.COST, prior.getPrice(), r.getPrice());
            if (price == null) {
                throw new BizException(400, "第 " + seq + " 行没有单价");
            }
            boolean changed = archivePrice != null && price.compareTo(archivePrice) != 0;
            String reason = priceVisible ? changed ? PurTexts.blankToNull(r.getPriceChangeReason()) : null : prior.getPriceChangeReason();
            if (priceVisible && changed && reason == null) {
                throw new BizException(400, "第 " + seq + " 行改了单价，要写原因");
            }
            PurchaseOrderItem item = line(seq, info, Num.qty(r.getQty()), price,
                    !priceVisible ? prior.getPriceSourceId() : a != null && !changed ? a.id() : null);
            if (!priceVisible) {
                // 保住的报价带着它的单位。不能按现在主数据的换算，
                // 把原来每米的价悄悄变成每卷的价。
                item.setPurchaseUnit(prior.getPurchaseUnit());
                item.setConversionFactor(prior.getConversionFactor());
            }
            item.setPriceChangeReason(reason);
            item.setLineDueDate(r.getLineDueDate());
            item.setExpectDate(r.getExpectDate());
            item.setCalcItemId(r.getCalcItemId());
            items.add(item);
        }
        return new Built(items, infos);
    }

    private static PurchaseOrderItem line(int seq, MaterialPurchaseInfo info, BigDecimal qty, BigDecimal price, Long priceSourceId) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setSeq(seq);
        item.setMaterialSkuId(info.skuId());
        item.setPurchaseUnit(info.purchaseUnit());
        item.setConversionFactor(info.conversionFactor());
        item.setQty(qty);
        item.setPrice(price);
        item.setAmount(Num.amount(qty, price));
        item.setPriceSourceId(priceSourceId);
        return item;
    }

    // ---------- 状态动作 ----------

    @Override
    @Transactional
    public void act(long id, String action, DocActionReq req) {
        if ("void".equals(action)) {
            mrpService.lockCalcsOfItems(calcItemIds(itemMapper.selectByOrder(id)));
        }
        PurchaseOrder po = lock(id);
        switch (action) {
            case "submit" -> submit(po, req);
            case "withdraw" -> {
                if (!isCreator(po.getCreateBy())) {
                    throw new BizException(403, "只有制单人能撤回");
                }
                change(po, "withdraw", DRAFT, req);
                approvalService.cancel(BizType.PURCHASE_ORDER, id);
            }
            case "reject" -> {
                if (PurTexts.blankToNull(req.reason()) == null) {
                    throw new BizException(400, "请写驳回原因");
                }
                FLOW.next("reject", po.getStatus());
                ApprovalView view = approvalService.view(BizType.PURCHASE_ORDER, id);
                if (view == null || !view.canPass()) {
                    throw new BizException(403, view == null || view.waitingFor() == null ? "现在不是你审"
                            : "现在等" + view.waitingFor() + "审");
                }
                change(po, "reject", DRAFT, req);
                approvalService.cancel(BizType.PURCHASE_ORDER, id);
            }
            case "audit" -> audit(po, req);
            case "unaudit" -> unaudit(po, req);
            case "close" -> {
                FLOW.next("close", po.getStatus());
                List<String> open = poMapper.selectOpenInDocs(id);
                if (!open.isEmpty()) {
                    throw new BizException("还有入库单 " + String.join("、", open) + " 没审核：先审核或删掉再结案");
                }
                change(po, "close", CLOSED, req);
            }
            case "reopen" -> change(po, "reopen", byArrival(itemMapper.selectByOrder(id)), req);
            case "void" -> {
                change(po, "void", VOIDED, req);
                approvalService.cancel(BizType.PURCHASE_ORDER, id);
                mrpService.syncConverted(calcItemIds(itemMapper.selectByOrder(id)));
            }
            default -> throw new BizException(404, "采购单没有动作 " + action);
        }
    }

    /** 一次状态转换：查转换表、比较并交换、版本号 +1、操作记录 */
    private void change(PurchaseOrder po, String action, DocStatus to, DocActionReq req) {
        FLOW.next(action, po.getStatus(), to);
        StatusCas.update(poMapper, new PurchaseOrder(), StatusCas.of(PurchaseOrder::getId, po.getId(), PurchaseOrder::getStatus,
                        po.getStatus(), to)
                .setSql("version = version + 1")
                .eq(PurchaseOrder::getVersion, req.version()));
        docLogService.add(BizType.PURCHASE_ORDER, po.getId(), action, po.getStatus(), to, PurTexts.blankToNull(req.reason()));
    }

    /** 按到货算状态：每行净入库 ≥ 采购数是已到齐，有一行到了是部分到货，都没到是已审核（审核后、过账回写、取消结案都按它） */
    static DocStatus byArrival(List<PurchaseOrderItem> items) {
        if (items.stream().allMatch(i -> i.netReceived().compareTo(i.getQty()) >= 0)) {
            return DONE;
        }
        return items.stream().anyMatch(i -> i.netReceived().signum() > 0) ? RUNNING : APPROVED;
    }

    private void submit(PurchaseOrder po, DocActionReq req) {
        FLOW.next("submit", po.getStatus());
        if (!po.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        supplierService.checkServes(po.getSupplierId(), po.getProductLine());
        supplierService.checkUsable(po.getSupplierId(), SupplierService.Usage.BULK);
        checkWarehouse(po.getWarehouseId());
        List<String> problems = new ArrayList<>();
        if (po.getSettleMethod() == null) {
            problems.add("结算方式没填");
        }
        if (po.getTaxPct() == null) {
            problems.add("税率没填");
        }
        problems.addAll(priceProblems(po, itemMapper.selectByOrder(po.getId())));
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, "提交不了：有 " + problems.size() + " 处要先处理", problems);
        }
        change(po, "submit", PENDING, req);
        approvalService.start(BizType.PURCHASE_ORDER, po.getId(), po.getTotalAmount());
    }

    /**
     * 单价和下单日的档案现价不一样、又没写原因的行（保存草稿以后档案又变了的）：提交、审核再查一遍，
     * 不然审核记价时缺原因（RFC 006 §5）
     */
    private List<String> priceProblems(PurchaseOrder po, List<PurchaseOrderItem> items) {
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(items.stream().map(PurchaseOrderItem::getMaterialSkuId)
                .collect(Collectors.toSet()));
        Map<Long, PriceRef> archive = archive(po.getSupplierId(), po.getOrderDate(), infos.values());
        List<String> out = new ArrayList<>();
        for (PurchaseOrderItem i : items) {
            PriceRef a = archive.get(i.getMaterialSkuId());
            if (a != null && i.getPrice().compareTo(a.price()) != 0 && i.getPriceChangeReason() == null) {
                MaterialPurchaseInfo info = infos.get(i.getMaterialSkuId());
                String detail = com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.COST)
                        ? "单价 " + PurTexts.plain(i.getPrice()) + " 和价格档案 " + PurTexts.plain(a.price()) + " 不一样，要写改价原因"
                        : "单价和价格档案不一致，请交由有成本权限的人员处理";
                out.add("第 " + i.getSeq() + " 行 " + (info == null ? "" : info.skuCode()) + "：" + detail);
            }
        }
        return out;
    }

    private void audit(PurchaseOrder po, DocActionReq req) {
        long id = po.getId();
        FLOW.next("audit", po.getStatus(), APPROVED);
        if (!po.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张采购单刚被别人审过或改过，请刷新后再审");
        }
        supplierService.checkServes(po.getSupplierId(), po.getProductLine());
        supplierService.checkUsable(po.getSupplierId(), SupplierService.Usage.BULK);
        List<PurchaseOrderItem> items = itemMapper.selectByOrder(id);
        List<String> problems = priceProblems(po, items);
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, "审核不了：价格档案在提交以后变了，驳回让制单人补上改价原因", problems);
        }
        if (!approvalService.pass(BizType.PURCHASE_ORDER, id)) {
            StatusCas.update(poMapper, new PurchaseOrder(), StatusCas.of(PurchaseOrder::getId, id, PurchaseOrder::getStatus, PENDING, PENDING)
                    .setSql("version = version + 1")
                    .eq(PurchaseOrder::getVersion, req.version()));
            docLogService.add(BizType.PURCHASE_ORDER, id, "audit", "这一级审过了，等下一级审");
            return;
        }
        StatusCas.update(poMapper, new PurchaseOrder(), StatusCas.of(PurchaseOrder::getId, id, PurchaseOrder::getStatus, PENDING, APPROVED)
                .set(PurchaseOrder::getAuditBy, LoginUser.currentUserId())
                .set(PurchaseOrder::getAuditTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(PurchaseOrder::getVersion, req.version()));
        docLogService.add(BizType.PURCHASE_ORDER, id, "audit", PENDING, APPROVED, PurTexts.blankToNull(req.reason()));
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(items.stream().map(PurchaseOrderItem::getMaterialSkuId)
                .collect(Collectors.toSet()));
        List<PriceLine> lines = items.stream().map(i -> new PriceLine(po.getSupplierId(), infos.get(i.getMaterialSkuId()).materialId(),
                i.getMaterialSkuId(), i.getPrice(), i.getPurchaseUnit(), po.getTaxPct(), Boolean.TRUE.equals(po.getTaxIncluded()),
                Boolean.TRUE.equals(po.getFreightIncluded()), i.getPriceChangeReason())).toList();
        DocPrices recorded = priceService.recordFromDoc(new PriceSource(BizType.PURCHASE_ORDER, id, po.getNo()), po.getOrderDate(), lines);
        if (!recorded.recorded().isEmpty() || !recorded.unitChanged().isEmpty()) {
            String text = recorded.recorded().size() + " 条单价记进价格档案";
            if (!recorded.unitChanged().isEmpty()) {
                text += "；" + recorded.unitChanged().size() + " 行的物料后来换了采购单位，没记";
            }
            docLogService.add(BizType.PURCHASE_ORDER, id, "price-archive", text);
        }
    }

    /** 反审核：有入库单、出库单，或被已确认的算料单用作本次使用在途，1005；审核时记的价格档案一起作废 */
    private void unaudit(PurchaseOrder po, DocActionReq req) {
        FLOW.next("unaudit", po.getStatus());
        if (!po.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        referenceService.assertNoReferrer(BizType.PURCHASE_ORDER, po.getId(), "反审核");
        StatusCas.update(poMapper, new PurchaseOrder(), StatusCas.of(PurchaseOrder::getId, po.getId(), PurchaseOrder::getStatus, APPROVED, DRAFT)
                .set(PurchaseOrder::getAuditBy, null)
                .set(PurchaseOrder::getAuditTime, null)
                .setSql("version = version + 1")
                .eq(PurchaseOrder::getVersion, req.version()));
        int voided = priceService.voidFromDoc(new PriceSource(BizType.PURCHASE_ORDER, po.getId(), po.getNo()),
                "采购单 " + po.getNo() + " 反审核");
        String reason = PurTexts.blankToNull(req.reason());
        String text = voided == 0 ? reason : (reason == null ? "" : reason + "；") + "作废了审核时记的 " + voided + " 条价格档案";
        docLogService.add(BizType.PURCHASE_ORDER, po.getId(), "unaudit", APPROVED, DRAFT, text);
    }

    // ---------- 由算料单、补货清单生成 ----------

    /** 生成的一行：来源算料行（补货的是 null）、物料、数量（采购单位）、报错时说哪一行 */
    private record GenLine(Long calcItemId, long skuId, BigDecimal qty, String label) {
    }

    @Override
    public void checkSourceMrpVisible(long id) { mrpService.checkVisible(id); }

    @Override
    @Transactional
    public List<DocSaved> fromMrp(FromMrpReq req) {
        List<MrpCalcItem> items = mrpService.lockForConvert(req.getCalcId(), req.getItemIds(), req.getVersion());
        MrpCalc calc = mrpService.getById(req.getCalcId());
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(items.stream().map(MrpCalcItem::getMaterialSkuId)
                .collect(Collectors.toSet()));
        List<String> problems = new ArrayList<>();
        Map<Long, List<GenLine>> bySupplier = new LinkedHashMap<>();
        for (MrpCalcItem i : items.stream().sorted(Comparator.comparing(MrpCalcItem::getSeq)).toList()) {
            MaterialPurchaseInfo info = infos.get(i.getMaterialSkuId());
            String label = "第 " + i.getSeq() + " 行 " + info.skuCode();
            if (!Objects.equals(i.getPurchaseUnit(), info.purchaseUnit())) {
                problems.add(label + "：物料的采购单位改过了，先重新算料");
                continue;
            }
            bySupplier.computeIfAbsent(i.getSupplierId(), k -> new ArrayList<>())
                    .add(new GenLine(i.getId(), i.getMaterialSkuId(), i.getSuggestQty().subtract(i.getConvertedQty()), label));
        }
        List<DocSaved> saved = generate(bySupplier, infos, PurchaseType.ORDER, calc.getId(), calc.getProductLine(), problems,
                "from-mrp", "由算料单 " + calc.getNo() + " 生成");
        mrpService.afterConvert(calc, items, "生成采购单 " + saved.stream().map(DocSaved::no).collect(Collectors.joining("、")));
        return saved;
    }

    @Override
    @Transactional
    public List<DocSaved> fromReorder(FromReorderReq req) {
        Set<Long> seen = new HashSet<>();
        for (ReorderPickItem i : req.getItems()) {
            if (!seen.add(i.getMaterialSkuId())) {
                throw new BizException(400, "同一个物料勾了两次，请刷新后再选");
            }
        }
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(seen);
        Map<Long, List<GenLine>> bySupplier = new LinkedHashMap<>();
        for (ReorderPickItem i : req.getItems()) {
            MaterialPurchaseInfo info = infos.get(i.getMaterialSkuId());
            if (info == null) {
                throw new BizException(400, "有物料不存在了，请刷新后再选");
            }
            bySupplier.computeIfAbsent(i.getSupplierId(), k -> new ArrayList<>())
                    .add(new GenLine(null, i.getMaterialSkuId(), Num.qty(i.getQty()), info.skuCode()));
        }
        return generate(bySupplier, infos, PurchaseType.STOCK, null, lineFor(req.getProductLine(), null), new ArrayList<>(),
                "from-reorder", "由补货提醒生成");
    }

    /**
     * 按供应商一家一张草稿（§3.3.7）：主体 = 我的默认主体，交货地点 = 第一个启用的物料仓，采购员 = 我，下单日 = 今天，
     * 交货日期按默认算，结算方式、税率带供应商的，单价按默认（档案现价 → SKU 单价 → 物料参考单价）。
     * 供应商不是合作中、没有默认单价的整批报出来（1000），一张都不建
     */
    private List<DocSaved> generate(Map<Long, List<GenLine>> bySupplier, Map<Long, MaterialPurchaseInfo> infos, PurchaseType type,
                                    Long calcId, String productLine, List<String> problems, String logAction, String logText) {
        com.lingmao.scm.security.FieldPermissions.require(com.lingmao.scm.security.FieldPermissions.COST);
        Map<Long, SupplierRef> suppliers = supplierService.refs(bySupplier.keySet());
        bySupplier.keySet().forEach(sid -> {
            SupplierRef s = suppliers.get(sid);
            if (s == null || s.status() != SupplierStatus.ACTIVE) {
                problems.add("供应商 " + (s == null ? sid : s.shortName() + "（" + s.status().getLabel() + "）") + " 不是合作中，不能下采购单");
                return;
            }
            String lineProblem = supplierService.lineProblem(sid, productLine);   // 品类线（RFC 013 §2）
            if (lineProblem != null) {
                problems.add(lineProblem);
            }
        });
        LocalDate today = LocalDate.now(clock);
        Map<Long, List<PurchaseOrderItem>> lines = new LinkedHashMap<>();
        for (Map.Entry<Long, List<GenLine>> e : bySupplier.entrySet()) {
            Map<Long, PriceRef> archive = archive(e.getKey(), today, e.getValue().stream().map(l -> infos.get(l.skuId())).toList());
            List<PurchaseOrderItem> items = new ArrayList<>();
            for (GenLine l : e.getValue()) {
                MaterialPurchaseInfo info = infos.get(l.skuId());
                PriceRef a = archive.get(l.skuId());
                BigDecimal price = a != null ? Num.price(a.price()) : Num.price(info.price());
                if (price == null) {
                    problems.add(l.label() + "：没有默认单价（价格档案、物料上都没有），先录价格或在采购单里手建");
                    continue;
                }
                PurchaseOrderItem item = line(items.size() + 1, info, l.qty(), price, a == null ? null : a.id());
                item.setCalcItemId(l.calcItemId());
                items.add(item);
            }
            lines.put(e.getKey(), items);
        }
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, "生成不了：有 " + problems.size() + " 处要先处理", problems);
        }
        long companyId = defaultCompanyId();
        long warehouseId = firstMaterialWarehouse();
        Long me = LoginUser.currentUserId();
        List<DocSaved> out = new ArrayList<>();
        for (Map.Entry<Long, List<PurchaseOrderItem>> e : lines.entrySet()) {
            SupplierRef s = suppliers.get(e.getKey());
            PurchaseOrder po = new PurchaseOrder();
            po.setCompanyId(companyId);
            po.setSupplierId(s.id());
            po.setPurchaseType(type);
            po.setProductLine(productLine);
            po.setCalcId(calcId);
            po.setBuyerId(me);
            po.setOrderDate(today);
            po.setDeliveryDate(deliveryDate(today, e.getValue().stream().map(i -> infos.get(i.getMaterialSkuId())).toList()));
            po.setWarehouseId(warehouseId);
            po.setSettleMethod(s.settleMethod());
            po.setTaxPct(s.taxPct());
            po.setTaxIncluded(true);
            po.setFreightIncluded(true);
            po.setStatus(DRAFT);
            insert(po, e.getValue());
            docLogService.add(BizType.PURCHASE_ORDER, po.getId(), logAction, logText);
            out.add(new DocSaved(po.getId(), po.getNo(), 0));
        }
        return out;
    }

    /** 我的默认主体（用户上设的启用就用它，不然启用的只有一个就用那个）；没有 1000 */
    private long defaultCompanyId() {
        if (com.lingmao.scm.common.scope.CompanyContext.isHttpRequest()) {
            long requested = com.lingmao.scm.common.scope.CompanyContext.header(true);
            Company company = companyService.getById(requested);
            if (!Integer.valueOf(1).equals(company.getStatus())) throw new BizException(400, "所选主体已停用");
            return requested;
        }
        Long me = LoginUser.currentUserId();
        CompanyBrief c = defaultCompanyProvider.resolve(userService.getById(me).defaultCompanyId());
        if (c == null) {
            throw new BizException("没有默认主体：启用的主体不止一个，先在个人设置里选一个默认主体");
        }
        return c.id();
    }

    /** 第一个启用的物料仓（按编码） */
    private long firstMaterialWarehouse() {
        List<WarehouseOption> list = warehouseService.options("MATERIAL");
        if (list.isEmpty()) {
            throw new BizException("还没有启用的物料仓，先在仓库里建一个");
        }
        return list.getFirst().id();
    }

    // ---------- 跟进、导出 ----------

    @Override
    @Transactional
    public void follow(long id, long itemId, FollowReq req) {
        PurchaseOrder po = getVisible(id);
        if (!FLOW.allows("follow", po.getStatus())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "只有已审核、部分到货的采购单能改预计到货");
        }
        PurchaseOrderItem item = itemMapper.selectById(itemId);
        if (item == null || !item.getPurchaseOrderId().equals(id)) {
            throw new BizException(404, "这一行不在这张采购单上了，请刷新");
        }
        String note = PurTexts.blankToNull(req.getFollowNote());
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(item.getExpectDate(), req.getExpectDate())) {
            changes.add("预计到货 " + (item.getExpectDate() == null ? "（没填）" : item.getExpectDate()) + " → "
                    + (req.getExpectDate() == null ? "（没填）" : req.getExpectDate()));
        }
        if (!Objects.equals(item.getFollowNote(), note)) {
            changes.add("跟进备注：" + (note == null ? "（清空）" : note));
        }
        if (changes.isEmpty()) {
            return;
        }
        itemMapper.updateFollow(itemId, req.getExpectDate(), note);
        String sku = materialService.skuBriefs(List.of(item.getMaterialSkuId())).values().stream()
                .map(MaterialSkuBrief::getSkuCode).findFirst().orElse("");
        docLogService.add(BizType.PURCHASE_ORDER, id, "follow", "第 " + item.getSeq() + " 行 " + sku + "：" + String.join("，", changes));
    }

    @Override
    public byte[] export(PurchaseQuery query) {
        query.setApprovalIds(query.isAuditForMe() ? approvalService.waitingForMe(BizType.PURCHASE_ORDER) : List.of());
        List<PurchaseRowVO> rows = poMapper.selectRows(query, Keywords.codes(query.getKeyword()), DataScope.current(query.isMine()));
        if (rows.size() > Excel.MAX_EXPORT_ROWS) {
            throw new BizException(400, "一次最多导出 " + Excel.MAX_EXPORT_ROWS + " 张，加个筛选条件再导");
        }
        com.lingmao.scm.common.scope.ExportScope.check(query.getIds(), rows, PurchaseRowVO::getId, PurchaseRowVO::getCompanyId);
        fillRows(rows);
        return excel.export(rows);
    }

    // ---------- 待到料 ----------

    @Override
    public PageResult<PendingArrivalRowVO> pendingItems(PendingArrivalQuery query) {
        List<PendingArrivalRowVO> rows = pendingRows(query).stream().filter(r -> inTab(r, query.getTab())).toList();
        int from = (int) Math.min(rows.size(), (query.getPage() - 1) * query.getSize());
        int to = (int) Math.min(rows.size(), from + query.getSize());
        List<PendingArrivalRowVO> page = new ArrayList<>(rows.subList(from, to));
        fillPendingDisplay(page);
        return new PageResult<>(page, rows.size());
    }

    @Override
    public PendingArrivalCounts pendingCounts(PendingArrivalQuery query) {
        List<PendingArrivalRowVO> rows = pendingRows(query);
        return new PendingArrivalCounts(rows.size(), rows.stream().filter(r -> inTab(r, "LATE")).count(),
                rows.stream().filter(r -> inTab(r, "NEAR")).count(), rows.stream().filter(r -> inTab(r, "CUT")).count());
    }

    @Override
    public Map<String, OrderCutRisk> orderCutRisks() {
        Map<String, PendingArrivalRowVO> worst = new HashMap<>();
        for (PendingArrivalRowVO r : pendingRows(new PendingArrivalQuery(), DataScope.current(false).linesOnly())) {
            if (r.getCutLateDays() <= 0) {
                continue;
            }
            for (String no : r.getOrderNos()) {
                worst.merge(no, r, (a, b) -> b.getCutLateDays() > a.getCutLateDays() ? b : a);
            }
        }
        if (worst.isEmpty()) {
            return Map.of();
        }
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(worst.values().stream()
                .map(PendingArrivalRowVO::getMaterialSkuId).collect(Collectors.toSet()));
        Map<String, OrderCutRisk> out = new HashMap<>();
        worst.forEach((no, r) -> {
            MaterialSkuBrief brief = briefs.get(r.getMaterialSkuId());
            out.put(no, new OrderCutRisk(no, r.getMaterialSkuId(), brief == null ? "" : brief.getMaterialName(), r.getArriveDate(),
                    r.getNeedDate(), r.getCutLateDays()));
        });
        return out;
    }

    private static boolean inTab(PendingArrivalRowVO r, String tab) {
        if (tab == null || tab.isBlank()) {
            return true;
        }
        return switch (tab) {
            case "LATE" -> "LATE".equals(r.getRisk());
            case "NEAR" -> "NEAR".equals(r.getRisk());
            case "CUT" -> r.getCutLateDays() > 0;
            default -> throw new BizException(400, "没有这个页签：" + tab);
        };
    }

    /**
     * 待到料的全部行（PRD §6.6.3）：未入库、哪天算到（预计到货 ?? 交期）、剩几天、风险；要料日期看用它的订单——
     * 它是已确认的算料单生成的，或者被已确认的算料单用作在途，取那些算料单的订单最早的要料日期（NeedDates），没有是 null。
     * 页签、要料日期都要现算，所以全取出来在这里排、分页（待到料只有没到齐的行，量不大）
     */
    private List<PendingArrivalRowVO> pendingRows(PendingArrivalQuery query) {
        return pendingRows(query, DataScope.current(false));
    }

    private List<PendingArrivalRowVO> pendingRows(PendingArrivalQuery query, DataScope scope) {
        List<PendingArrivalRowVO> rows = new ArrayList<>(poMapper.selectPendingRows(query, Keywords.codes(query.getKeyword()), scope));
        if (rows.isEmpty()) {
            return rows;
        }
        LocalDate today = LocalDate.now(clock);
        List<Long> lineIds = rows.stream().map(PendingArrivalRowVO::getPurchaseItemId).toList();
        Map<Long, PurchaseOrderItemMapper.CalcSource> sources = new HashMap<>();
        Set<Long> calcItemIds = rows.stream().map(PendingArrivalRowVO::getCalcItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!calcItemIds.isEmpty()) {
            itemMapper.selectCalcSources(calcItemIds).forEach(s -> sources.put(s.calcItemId(), s));
        }
        Map<Long, List<MrpCalcTransitMapper.LineUser>> users = transitMapper.selectLineUsers(lineIds).stream()
                .collect(Collectors.groupingBy(MrpCalcTransitMapper.LineUser::lineId));
        Set<Long> ownerCalcIds = sources.values().stream().map(PurchaseOrderItemMapper.CalcSource::calcId).collect(Collectors.toSet());
        Set<Long> confirmedOwners = ownerCalcIds.isEmpty() ? Set.of() : calcMapper.selectByIds(ownerCalcIds).stream()
                .filter(c -> c.getStatus() == MrpStatus.CONFIRMED).map(MrpCalc::getId)
                .collect(Collectors.toSet());
        Set<Long> calcIds = new HashSet<>(confirmedOwners);
        users.values().forEach(list -> list.forEach(u -> calcIds.add(u.calcId())));
        Map<Long, LocalDate> calcNeed = calcNeedDates(calcIds);

        for (PendingArrivalRowVO r : rows) {
            BigDecimal net = r.getReceivedQty().subtract(r.getReturnedQty());
            r.setPendingQty(r.getQty().subtract(net).max(BigDecimal.ZERO));
            LocalDate arrive = r.getExpectDate() != null ? r.getExpectDate() : r.getDueDate();
            r.setArriveDate(arrive);
            r.setDaysLeft(ChronoUnit.DAYS.between(today, arrive));
            r.setRisk(r.getDaysLeft() < 0 ? "LATE" : r.getDaysLeft() <= NEAR_DAYS ? "NEAR" : "NORMAL");
            PurchaseOrderItemMapper.CalcSource s = r.getCalcItemId() == null ? null : sources.get(r.getCalcItemId());
            Set<String> orderNos = new LinkedHashSet<>();
            Set<String> styleCodes = new LinkedHashSet<>();
            LocalDate need = null;
            if (s != null) {
                orderNos.addAll(PurTexts.split(s.orderNos()));
                styleCodes.addAll(PurTexts.split(s.styleCodes()));
                r.setCalcNo(s.calcNo());
                if (confirmedOwners.contains(s.calcId())) {
                    need = calcNeed.get(s.calcId());
                }
            }
            for (MrpCalcTransitMapper.LineUser u : users.getOrDefault(r.getPurchaseItemId(), List.of())) {
                orderNos.addAll(PurTexts.split(u.orderNos()));
                styleCodes.addAll(PurTexts.split(u.styleCodes()));
                if (r.getCalcNo() == null) {
                    r.setCalcNo(u.calcNo());
                }
                need = earlier(need, calcNeed.get(u.calcId()));
            }
            r.setOrderNos(List.copyOf(orderNos));
            r.setStyleCodes(List.copyOf(styleCodes));
            r.setNeedDate(need);
            r.setCutLateDays(need != null && arrive.isAfter(need) ? ChronoUnit.DAYS.between(need, arrive) : 0);
        }
        rows.sort(Comparator.comparing(PendingArrivalRowVO::getArriveDate)
                .thenComparing(PendingArrivalRowVO::getPurchaseNo)
                .thenComparing(PendingArrivalRowVO::getPurchaseItemId));
        return rows;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    /** 算料单 → 它的订单里最早的要料日期（现算：排了单的按裁剪计划开始，没排的按承诺交期倒推） */
    private Map<Long, LocalDate> calcNeedDates(Collection<Long> calcIds) {
        if (calcIds.isEmpty()) {
            return Map.of();
        }
        List<MrpCalcMapper.CalcOrder> orders = calcMapper.selectCalcOrders(calcIds);
        Map<Long, LocalDate> commits = new HashMap<>();
        orders.forEach(o -> commits.put(o.orderId(), o.commitDate()));
        Map<Long, LocalDate> needs = needDates.of(commits);
        Map<Long, LocalDate> out = new HashMap<>();
        orders.forEach(o -> {
            LocalDate d = needs.get(o.orderId());
            if (d != null) {
                out.merge(o.calcId(), d, PurchaseOrderServiceImpl::earlier);
            }
        });
        return out;
    }

    /** 当前页的物料显示字段、采购员、按钮 */
    private void fillPendingDisplay(List<PendingArrivalRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(rows.stream().map(PendingArrivalRowVO::getMaterialSkuId)
                .collect(Collectors.toSet()));
        Map<Long, String> names = userService.nicknames(rows.stream().map(PendingArrivalRowVO::getBuyerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        boolean canReceive = LoginUser.hasAuthority("inventory:doc:add");
        boolean canFollow = LoginUser.hasAuthority(PERM + "edit");
        List<String> actions = new ArrayList<>();
        if (canReceive) {
            actions.add("receive");
        }
        if (canFollow) {
            actions.add("follow");
        }
        for (PendingArrivalRowVO r : rows) {
            MaterialSkuBrief brief = briefs.get(r.getMaterialSkuId());
            Long skuId = r.getMaterialSkuId();
            r.fillFrom(brief);
            r.setMaterialSkuId(skuId);
            r.setBuyerName(names.get(r.getBuyerId()));
            r.setActions(List.copyOf(actions));
        }
    }
}
