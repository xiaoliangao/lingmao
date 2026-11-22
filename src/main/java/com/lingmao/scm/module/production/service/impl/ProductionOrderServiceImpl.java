package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.excel.BoundedExportRows;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.dto.PriceSource;
import com.lingmao.scm.module.price.dto.ProcessPriceLine;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.production.dto.IssueOptionQuery;
import com.lingmao.scm.module.production.dto.IssueOptionVO;
import com.lingmao.scm.module.production.dto.MaterialPlanRowVO;
import com.lingmao.scm.module.production.dto.MobileOrderRow;
import com.lingmao.scm.module.production.dto.OpenIssueBrief;
import com.lingmao.scm.module.production.dto.ProductionDetailVO;
import com.lingmao.scm.module.production.dto.ProductionItemVO;
import com.lingmao.scm.module.production.dto.ProductionMaterialsVO;
import com.lingmao.scm.module.production.dto.ProductionNodeVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.ProductionSaveReq;
import com.lingmao.scm.module.production.dto.RiskVO;
import com.lingmao.scm.module.production.entity.IssueStatus;
import com.lingmao.scm.module.production.entity.NodeReport;
import com.lingmao.scm.module.production.entity.ProductionIssue;
import com.lingmao.scm.module.production.entity.ProductionKind;
import com.lingmao.scm.module.production.entity.ProductionNode;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;
import com.lingmao.scm.module.production.entity.ProductionReceipt;
import com.lingmao.scm.module.production.mapper.NodeReportMapper;
import com.lingmao.scm.module.production.mapper.ProductionIssueMapper;
import com.lingmao.scm.module.production.mapper.ProductionNodeMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptMapper;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;
import static com.lingmao.scm.module.production.service.impl.ProductionRules.FLOW;

@Service
@RequiredArgsConstructor
public class ProductionOrderServiceImpl implements ProductionOrderService {

    private final ProductionOrderMapper prdMapper;
    private final ProductionOrderItemMapper itemMapper;
    private final ProductionNodeMapper nodeMapper;
    private final NodeReportMapper reportMapper;
    private final ProductionIssueMapper issueMapper;
    private final ProductionReceiptMapper receiptMapper;
    private final ProductionSupport support;
    private final ProductionExcel excel;
    private final OrderService orderService;
    private final StyleService styleService;
    private final TechPackService techPackService;
    private final SupplierService supplierService;
    private final PriceService priceService;
    private final MaterialService materialService;
    private final CompanyService companyService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final DocNoticeService docNoticeService;
    private final AttachmentService attachmentService;
    private final ApprovalService approvalService;
    private final ReferenceService referenceService;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<com.lingmao.scm.module.purchase.service.PurchaseOrderService> purchaseOrders;

    // ---------- 查询 ----------

    @Override
    public PageResult<ProductionRowVO> page(ProductionQuery query) {
        query.setApprovalIds(List.of());
        query.setApprovalScope(query.isAuditForMe() ? approvalService.waitingScope(BizType.PRODUCTION_ORDER) : null);
        requireKind(query);
        query.setCard(null);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductionRowVO> request = query.toPage();
        request.setOptimizeJoinOfCountSql(false);
        IPage<ProductionRowVO> page = query.isCutRiskFirst()
                ? prdMapper.selectCutRiskPage(request, query, Keywords.codes(query.getKeyword()), DataScope.current(query.isMine()),
                        support.today(), support.nearUntil(), purchaseOrders.getObject().fallbackLeadDays())
                : prdMapper.selectRowPage(request, query, Keywords.codes(query.getKeyword()),
                        DataScope.current(query.isMine()), support.today(), support.nearUntil());
        support.fillRows(page.getRecords(), true);
        return PageResult.of(page);
    }

    @Override
    public Map<String, Long> counts(ProductionQuery query) {
        query.setApprovalIds(List.of());
        query.setApprovalScope(query.isAuditForMe() ? approvalService.waitingScope(BizType.PRODUCTION_ORDER) : null);
        requireKind(query);
        query.setCard(null);
        Map<String, Long> out = new LinkedHashMap<>();
        for (ProductionOrderMapper.CountRow r : prdMapper.selectCounts(query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()), support.today(), support.nearUntil())) {
            out.put(r.status(), r.count());
            if (RUNNING.name().equals(r.status())) {
                out.put("PARTIAL", r.partial());
            }
        }
        return out;
    }

    private static ProductionKind requireKind(ProductionQuery query) {
        if (query.getKind() == null || query.getKind().isBlank()) {
            throw new BizException(400, "kind 必传：OUTSOURCE 外发加工单 / BUY 成品采购单");
        }
        try {
            return ProductionKind.valueOf(query.getKind());
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "kind 只能是 OUTSOURCE 或 BUY");
        }
    }

    @Override
    public ProductionDetailVO detail(long id) {
        ProductionOrder p = support.getVisible(id);
        List<ProductionOrderItem> items = itemMapper.selectByOrder(id);
        List<ProductionNode> nodes = nodeMapper.selectByOrder(id);
        List<NodeReport> reports = reportMapper.selectByOrder(id);
        StyleOrderInfo style = styleService.orderInfos(List.of(p.getStyleId())).get(p.getStyleId());
        ProductionOrderMapper.DetailRefs refs = prdMapper.selectDetailRefs(id);
        PackBrief pack = p.getTechPackId() == null ? null : techPackService.briefs(List.of(p.getTechPackId())).get(p.getTechPackId());

        ProductionDetailVO vo = docHeaderService.fill(new ProductionDetailVO(), BizType.PRODUCTION_ORDER, p, p.getStatus());
        vo.setPlanRevision(p.getPlanRevision());
        vo.setCompanyName(companyService.getById(p.getCompanyId()).getShortName());
        vo.setKind(p.getKind().name());
        SupplierRef f = supplierService.getRef(p.getFactoryId());
        vo.setFactoryId(f.id());
        vo.setFactoryCode(f.code());
        vo.setFactoryName(f.name());
        vo.setFactoryShortName(f.shortName());
        vo.setFactoryStatus(f.status().name());
        vo.setFactoryContact(f.contact());
        vo.setFactoryPhone(f.phone());
        vo.setProcessMode(p.getProcessMode());
        vo.setOrderId(p.getOrderId());
        vo.setOrderNo(refs.orderNo());
        vo.setStyleId(p.getStyleId());
        vo.setStyleCode(style.code());
        vo.setStyleName(style.name());
        vo.setAliasName(style.aliasName());
        vo.setBrand(refs.brand());
        vo.setImage(style.image());
        vo.setTechPackId(p.getTechPackId());
        vo.setTechPackVersion(pack == null ? null : pack.version());
        vo.setOrderDate(p.getOrderDate());
        vo.setDueDate(p.getDueDate());
        vo.setExpectDate(p.getExpectDate());
        vo.setDaysLeft(ProductionRules.daysLeft(p.getStatus(), p.getDueDate(), p.getExpectDate(), support.today()));
        vo.setUnitPrice(p.getUnitPrice());
        vo.setPriceSourceId(p.getPriceSourceId());
        vo.setArchivePrice(priceService.currentProcess(p.getFactoryId(), p.getStyleId(), p.getProcessMode(), p.getOrderDate())
                .map(PriceRef::price).orElse(null));
        vo.setPriceChangeReason(p.getPriceChangeReason());
        vo.setTaxPct(p.getTaxPct());
        vo.setTaxIncluded(Boolean.TRUE.equals(p.getTaxIncluded()));
        vo.setTotalQty(p.getTotalQty());
        vo.setTotalAmount(p.getTotalAmount());
        vo.setMerchId(p.getMerchId());
        vo.setMerchName(userService.nicknames(List.of(p.getMerchId())).get(p.getMerchId()));
        vo.setSettleMethod(p.getSettleMethod());
        vo.setTerms(p.getTerms());
        vo.setIssueStatus(p.getIssueStatus() == null ? null : p.getIssueStatus().name());
        vo.setReturnedQty(items.stream().mapToInt(ProductionOrderItem::getReturnedQty).sum());
        vo.setDefectQty(items.stream().mapToInt(ProductionOrderItem::getDefectQty).sum());
        vo.setCurrentNodeName(ProductionRules.currentNodeName(nodes));
        ProductionOrderMapper.RiskRow risk = prdMapper.selectRisk(id, support.today(), support.nearUntil());
        vo.setRisk(new RiskVO(risk.late(), risk.near(), risk.nodeLate(), risk.issue(), risk.upstream()));
        vo.setNearDays(ProductionRules.NEAR_DAYS);
        vo.setSizes(style.sizes());
        vo.setItems(itemVOs(items, style));
        vo.setNodes(nodeVOs(nodes, reports));
        List<ProductionIssue> issues = issueMapper.selectByOrder(id);
        vo.setOpenIssues(issues.stream().filter(i -> i.getStatus() != IssueStatus.CLOSED)
                .map(i -> new OpenIssueBrief(i.getId(), i.getIssueType(), i.getDescription(), i.getNewExpectDate())).toList());
        vo.setIssueCount(issues.size());
        vo.setReceiptCount((int) receiptMapper.selectByOrder(id).stream().filter(r -> r.getStatus() != VOIDED).count());
        vo.setStockDocCount(prdMapper.countStockDocs(id));
        boolean blocked = p.getStatus() == APPROVED
                && (!reports.isEmpty() || !referenceService.referrers(BizType.PRODUCTION_ORDER, id).isEmpty());
        vo.setActions(ProductionSupport.actions(p.getStatus(), new ProductionSupport.Ctx(ProductionSupport.isCreator(p.getCreateBy()),
                vo.getApproval(), blocked, p.getIssueStatus())));
        return vo;
    }

    private static List<ProductionItemVO> itemVOs(List<ProductionOrderItem> items, StyleOrderInfo style) {
        Map<Long, StyleOrderInfo.Sku> skus = style.skus().stream().collect(Collectors.toMap(StyleOrderInfo.Sku::id, Function.identity()));
        Map<Long, StyleOrderInfo.Color> colors = style.colors().stream()
                .collect(Collectors.toMap(StyleOrderInfo.Color::id, Function.identity()));
        List<ProductionItemVO> out = new ArrayList<>();
        for (ProductionOrderItem i : items) {
            StyleOrderInfo.Sku sku = skus.get(i.getStyleSkuId());
            StyleOrderInfo.Color color = sku == null ? null : colors.get(sku.colorId());
            ProductionItemVO v = new ProductionItemVO();
            v.setId(i.getId());
            v.setSeq(i.getSeq());
            v.setOrderItemId(i.getOrderItemId());
            v.setStyleSkuId(i.getStyleSkuId());
            v.setSkuCode(sku == null ? null : sku.code());
            v.setSizeCode(sku == null ? null : sku.sizeCode());
            if (color != null) {
                v.setColorId(color.id());
                v.setColorCode(color.code());
                v.setColorName(color.name());
                v.setColorHex(color.hex());
            }
            v.setQty(i.getQty());
            v.setReturnedQty(i.getReturnedQty());
            v.setDefectQty(i.getDefectQty());
            v.setRemainQty(Math.max(i.getQty() - i.getReturnedQty(), 0));
            v.setMaxReceiveQty(Math.max(ProductionRules.maxPieces(i.getQty()) - i.getReturnedQty(), 0));
            out.add(v);
        }
        return out;
    }

    /** 节点：状态、晚几天现算；报过几次、照片按报工取（新的在前） */
    private List<ProductionNodeVO> nodeVOs(List<ProductionNode> nodes, List<NodeReport> reports) {
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<Long, List<NodeReport>> byNode = reports.stream().collect(Collectors.groupingBy(NodeReport::getNodeId));
        Map<Long, List<AttachmentVO>> photos = attachmentService.lists(BizType.PRODUCTION_NODE_REPORT,
                reports.stream().map(NodeReport::getId).toList(), Attachment.IMAGE);
        // 没人报过的节点 lastReportBy 是 null：拷成 HashMap 才能拿 null 去查
        Map<Long, String> names = new HashMap<>(userService.nicknames(nodes.stream().map(ProductionNode::getLastReportBy)
                .filter(Objects::nonNull).collect(Collectors.toSet())));
        List<ProductionNodeVO> out = new ArrayList<>();
        for (ProductionNode n : nodes) {
            List<NodeReport> rs = byNode.getOrDefault(n.getId(), List.of());
            ProductionNodeVO v = new ProductionNodeVO();
            v.setId(n.getId());
            v.setSeq(n.getSeq());
            v.setNodeCode(n.getNodeCode());
            v.setNodeName(n.getNodeName());
            v.setPlanStart(n.getPlanStart());
            v.setPlanFinish(n.getPlanFinish());
            v.setActualStart(n.getActualStart());
            v.setActualFinish(n.getActualFinish());
            v.setDoneQty(n.getDoneQty());
            v.setRemark(n.getRemark());
            v.setState(ProductionRules.nodeState(n, support.today()));
            v.setLateDays(ProductionRules.lateDays(n, support.today()));
            v.setLastReportByName(names.get(n.getLastReportBy()));
            v.setLastReportTime(n.getLastReportTime());
            v.setReportCount(rs.size());
            v.setPhotos(rs.stream().sorted(Comparator.comparing(NodeReport::getId).reversed())
                    .flatMap(r -> photos.getOrDefault(r.getId(), List.of()).stream()).toList());
            out.add(v);
        }
        return out;
    }

    @Override
    public void checkVisible(long id) {
        support.getVisible(id);
    }

    @Override
    public byte[] export(ProductionQuery query) {
        query.setApprovalIds(List.of());
        query.setApprovalScope(query.isAuditForMe() ? approvalService.waitingScope(BizType.PRODUCTION_ORDER) : null);
        ProductionKind kind = requireKind(query);
        query.setCard(null);
        List<ProductionRowVO> rows = prdMapper.selectRowPage(new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false),
                query, Keywords.codes(query.getKeyword()), DataScope.current(query.isMine()),
                support.today(), support.nearUntil()).getRecords();
        com.lingmao.scm.common.scope.ExportScope.check(query.getIds(), rows, ProductionRowVO::getId, ProductionRowVO::getCompanyId);
        BoundedExportRows.requireWithinLimit(rows.size(), Excel.MAX_EXPORT_ROWS, "生产单");
        // 导出没有节点、缩略图和操作列；只补实际输出的人名，避免加载全量关联节点。
        Map<Long, String> names = userService.nicknames(rows.stream().map(ProductionRowVO::getMerchId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        rows.forEach(row -> row.setMerchName(names.get(row.getMerchId())));
        return excel.export(kind, rows);
    }

    @Override
    public List<MobileOrderRow> myFollow() {
        ProductionQuery q = new ProductionQuery();
        q.setStatus(List.of(APPROVED, RUNNING));
        q.setMerchId(LoginUser.currentUserId());
        List<ProductionRowVO> rows = prdMapper.selectRows(q, null, DataScope.current(false), support.today(), support.nearUntil());
        support.fillRows(rows, false);
        return rows.stream().map(r -> new MobileOrderRow(r.getId(), r.getNo(), r.getKind(), r.getStatus(), r.getStyleCode(),
                r.getStyleName(), r.getImage(), r.getFactoryShortName(), r.getTotalQty(), r.getDueDate(), r.getExpectDate(),
                r.getDaysLeft(), r.getNodes(), r.getCurrentNodeName(), r.getRisk())).toList();
    }

    @Override
    public PageResult<IssueOptionVO> issueOptions(IssueOptionQuery query) {
        return PageResult.of(prdMapper.selectIssueOptions(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(false)));
    }

    @Override
    public ProductionMaterialsVO materials(long id) {
        support.getVisible(id);
        Map<Long, BigDecimal> need = support.needs(List.of(id)).getOrDefault(id, Map.of());
        Map<Long, BigDecimal> issued = support.issued(List.of(id), null).getOrDefault(id, Map.of());
        Set<Long> skuIds = new HashSet<>(need.keySet());
        issued.forEach((sku, qty) -> {
            if (qty.signum() != 0) {
                skuIds.add(sku);
            }
        });
        List<MaterialPlanRowVO> rows = new ArrayList<>();
        if (!skuIds.isEmpty()) {
            Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(skuIds);
            Map<Long, BigDecimal> stock = prdMapper.selectMaterialStock(skuIds).stream()
                    .collect(Collectors.toMap(ProductionOrderMapper.StockRow::skuId, ProductionOrderMapper.StockRow::qty));
            for (Long sku : skuIds) {
                MaterialPlanRowVO r = new MaterialPlanRowVO();
                r.fillFrom(briefs.get(sku));
                BigDecimal n = need.getOrDefault(sku, BigDecimal.ZERO);
                BigDecimal got = issued.getOrDefault(sku, BigDecimal.ZERO);
                r.setNeedQty(Num.qty(n));
                r.setIssuedQty(Num.qty(got));
                r.setRemainQty(Num.qty(n.subtract(got).max(BigDecimal.ZERO)));
                r.setStockQty(Num.qty(stock.getOrDefault(sku, BigDecimal.ZERO)));
                rows.add(r);
            }
            rows.sort(Comparator.comparing(MaterialPlanRowVO::getSkuCode, Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return new ProductionMaterialsVO(rows, prdMapper.selectStockDocs(id));
    }

    // ---------- 改草稿、删除 ----------

    @Override
    @Transactional
    public int update(long id, ProductionSaveReq req) {
        ProductionOrder p = support.lock(id);
        com.lingmao.scm.common.scope.CompanyContext.save(p.getCompanyId(), req.getCompanyId(), req.getExpectedCompanyId());
        FLOW.next("edit", p.getStatus());
        if (!p.getVersion().equals(req.getVersion())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, ProductionSupport.STALE);
        }
        String mode = req.getProcessMode().strip();
        if (ProductionKind.of(mode) != p.getKind()) {
            throw new BizException(p.getKind() == ProductionKind.OUTSOURCE
                    ? "外发加工单的加工方式只能选清加工、半包；要改成成品采购单，作废这张，回需求订单重新排单"
                    : "成品采购单的加工方式只能是包工包料；要改成外发加工，作废这张，回需求订单重新排单");
        }
        if (!mode.equals(p.getProcessMode())) {
            checkDict("process_mode", mode, "加工方式");
        }
        support.checkFactory(req.getFactoryId(), p.getKind());
        if (!Objects.equals(p.getCompanyId(), req.getCompanyId())) {
            Company company = companyService.getById(req.getCompanyId());
            if (!Integer.valueOf(1).equals(company.getStatus())) {
                throw new BizException("主体" + company.getShortName() + "已停用，请换一个");
            }
        }
        if (!Objects.equals(p.getMerchId(), req.getMerchId()) && !userService.isEnabled(req.getMerchId())) {
            throw new BizException(400, "生产跟单的账号不存在或已停用，请换一个人");
        }
        String settle = req.getSettleMethod().strip();
        if (!settle.equals(p.getSettleMethod())) {
            checkDict("settle_method", settle, "结算方式");
        }
        boolean priceVisible = com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.PRODUCTION);
        if (!priceVisible && (!mode.equals(p.getProcessMode()) || !Objects.equals(req.getFactoryId(), p.getFactoryId())))
            com.lingmao.scm.security.FieldPermissions.require(com.lingmao.scm.security.FieldPermissions.PRODUCTION);
        BigDecimal price = NumericLimits.price(com.lingmao.scm.security.FieldPermissions.price(
                com.lingmao.scm.security.FieldPermissions.PRODUCTION, p.getUnitPrice(), req.getUnitPrice()), "生产单：");
        if (price == null) throw new BizException(400, "生产单必须填写成交单价");
        PriceRef archive = priceService.currentProcess(req.getFactoryId(), p.getStyleId(), mode, p.getOrderDate()).orElse(null);
        boolean changed = archive != null && price.compareTo(archive.price()) != 0;
        String reason = priceVisible ? changed ? ProdTexts.blankToNull(req.getPriceChangeReason()) : null : p.getPriceChangeReason();
        if (priceVisible && changed && reason == null) {
            throw new BizException(400, "单价和档案现价 " + ProdTexts.money(archive.price()) + " 不一样，要写改价原因");
        }

        List<String> what = changedFields(p, req, mode, settle, price);
        p.setCompanyId(req.getCompanyId());
        p.setFactoryId(req.getFactoryId());
        p.setProcessMode(mode);
        p.setUnitPrice(price);
        if (priceVisible) p.setPriceSourceId(archive != null && !changed ? archive.id() : null);
        p.setPriceChangeReason(reason);
        p.setDueDate(req.getDueDate());
        p.setMerchId(req.getMerchId());
        p.setSettleMethod(settle);
        p.setTaxPct(req.getTaxPct());
        p.setTaxIncluded(req.isTaxIncluded());
        p.setTerms(ProdTexts.blankToNull(req.getTerms()));
        p.setRemark(ProdTexts.blankToNull(req.getRemark()));
        p.setTotalAmount(NumericLimits.amount(Num.amount(BigDecimal.valueOf(p.getTotalQty()), price), "生产单："));
        UpdateWrapper<ProductionOrder> update = Updates.replaceById(p)
                .eq("status", DRAFT.name())
                .eq("version", req.getVersion())
                .setSql("version = version + 1");
        if (prdMapper.update(p, update) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, ProductionSupport.STALE);
        }
        docLogService.add(BizType.PRODUCTION_ORDER, id, "update", what.isEmpty() ? null : "改了" + String.join("、", what));
        return req.getVersion() + 1;
    }

    private static List<String> changedFields(ProductionOrder p, ProductionSaveReq req, String mode, String settle, BigDecimal price) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(p.getCompanyId(), req.getCompanyId())) {
            out.add("主体");
        }
        if (!Objects.equals(p.getFactoryId(), req.getFactoryId())) {
            out.add("加工厂");
        }
        if (!mode.equals(p.getProcessMode())) {
            out.add("加工方式");
        }
        if (p.getUnitPrice().compareTo(price) != 0) {
            out.add(p.getKind().getPriceLabel() + " " + ProdTexts.plain(p.getUnitPrice()) + " → " + ProdTexts.plain(price));
        }
        if (!p.getDueDate().equals(req.getDueDate())) {
            out.add("交期 " + ProdTexts.md(p.getDueDate()) + " → " + ProdTexts.md(req.getDueDate()));
        }
        if (!Objects.equals(p.getMerchId(), req.getMerchId())) {
            out.add("生产跟单");
        }
        if (!settle.equals(p.getSettleMethod())) {
            out.add("结算方式");
        }
        if (p.getTaxPct().compareTo(req.getTaxPct()) != 0 || !Objects.equals(p.getTaxIncluded(), req.isTaxIncluded())) {
            out.add("税率");
        }
        if (!Objects.equals(p.getTerms(), ProdTexts.blankToNull(req.getTerms()))) {
            out.add("条款");
        }
        if (!Objects.equals(p.getRemark(), ProdTexts.blankToNull(req.getRemark()))) {
            out.add("备注");
        }
        return out;
    }

    /** 新选的字典值要在字典里、启用（没改的停用了照样留着） */
    private void checkDict(String type, String value, String label) {
        if (dictDataService.listByType(type).stream().noneMatch(d -> d.value().equals(value) && d.enabled())) {
            throw new BizException(400, label + "「" + value + "」不在字典里或已停用");
        }
    }

    @Override
    @Transactional
    public void delete(long id) {
        ProductionSupport.Locked l = support.lockWithOrder(id);
        ProductionOrder p = l.prd();
        FLOW.next("remove", p.getStatus());
        int refunded = refundScheduled(p);
        itemMapper.deleteByOrder(id);
        attachmentService.deleteByDoc(BizType.PRODUCTION_ORDER, id);
        docLogService.deleteByDoc(BizType.PRODUCTION_ORDER, id);
        docNoticeService.deleteByDoc(BizType.PRODUCTION_ORDER, id);
        prdMapper.deleteById(id);
        docLogService.add(BizType.ORDER, p.getOrderId(), "schedule", ProductionSupport.label(p) + " 删除，退回 " + refunded + " 件");
        support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 删除");
    }

    /** 件数退回订单的待排单（订单行的已排产减回去）；返回退回几件 */
    private int refundScheduled(ProductionOrder p) {
        Map<Long, Integer> delta = new HashMap<>();
        int total = 0;
        for (ProductionOrderItem i : itemMapper.selectByOrder(p.getId())) {
            delta.merge(i.getOrderItemId(), -i.getQty(), Integer::sum);
            total += i.getQty();
        }
        orderService.addScheduled(delta);
        return total;
    }

    // ---------- 状态动作 ----------

    @Override
    @Transactional
    public void act(long id, String action, DocActionReq req) {
        ProductionSupport.Locked l = support.lockWithOrder(id);
        ProductionOrder p = l.prd();
        switch (action) {
            case "submit" -> submit(p, req);
            case "withdraw" -> {
                if (!ProductionSupport.isCreator(p.getCreateBy())) {
                    throw new BizException(403, "只有制单人能撤回");
                }
                change(p, "withdraw", DRAFT, req, ProdTexts.blankToNull(req.reason()));
                approvalService.cancel(BizType.PRODUCTION_ORDER, id);
            }
            case "reject" -> {
                if (ProdTexts.blankToNull(req.reason()) == null) {
                    throw new BizException(400, "请写驳回原因");
                }
                FLOW.next("reject", p.getStatus());
                ApprovalView view = approvalService.view(BizType.PRODUCTION_ORDER, id);
                if (view == null || !view.canPass()) {
                    throw new BizException(403, view == null || view.waitingFor() == null ? "现在不是你审"
                            : "现在等" + view.waitingFor() + "审");
                }
                change(p, "reject", DRAFT, req, ProdTexts.blankToNull(req.reason()));
                approvalService.cancel(BizType.PRODUCTION_ORDER, id);
            }
            case "audit" -> audit(p, req);
            case "unaudit" -> unaudit(p, req);
            case "close" -> {
                change(p, "close", CLOSED, req, ProdTexts.blankToNull(req.reason()));
                support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 结案");
            }
            case "reopen" -> {
                DocStatus to = ProductionRules.byProgress(itemMapper.selectByOrder(id), nodeMapper.selectByOrder(id));
                change(p, "reopen", to, req, ProdTexts.blankToNull(req.reason()));
                support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 取消结案");
            }
            case "void" -> {
                change(p, "void", VOIDED, req, ProdTexts.blankToNull(req.reason()));
                approvalService.cancel(BizType.PRODUCTION_ORDER, id);
                int refunded = refundScheduled(p);
                docLogService.add(BizType.ORDER, p.getOrderId(), "schedule", ProductionSupport.label(p) + " 作废，退回 " + refunded + " 件");
                support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 作废");
            }
            default -> throw new BizException(404, "生产单没有动作 " + action);
        }
    }

    /** 一次状态转换：查转换表、比较并交换（带 version）、版本号 +1、操作记录 */
    private void change(ProductionOrder p, String action, DocStatus to, DocActionReq req, String remark) {
        FLOW.next(action, p.getStatus(), to);
        StatusCas.update(prdMapper, new ProductionOrder(), StatusCas.of(ProductionOrder::getId, p.getId(), ProductionOrder::getStatus,
                        p.getStatus(), to)
                .setSql("version = version + 1")
                .eq(ProductionOrder::getVersion, req.version()));
        docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), action, p.getStatus(), to, remark);
    }

    private void submit(ProductionOrder p, DocActionReq req) {
        FLOW.next("submit", p.getStatus());
        if (!p.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, ProductionSupport.STALE);
        }
        support.checkFactory(p.getFactoryId(), p.getKind());
        String problem = priceProblem(p);
        if (problem != null) {
            throw new BizException("提交不了：" + problem);
        }
        change(p, "submit", PENDING, req, ProdTexts.blankToNull(req.reason()));
        approvalService.start(BizType.PRODUCTION_ORDER, p.getId(), p.getTotalAmount());
    }

    /** 单价和下单日的档案现价不一样、又没写原因（保存草稿以后档案又变了）：提交、审核再查一遍，不然审核记价时缺原因 */
    private String priceProblem(ProductionOrder p) {
        PriceRef a = priceService.currentProcess(p.getFactoryId(), p.getStyleId(), p.getProcessMode(), p.getOrderDate()).orElse(null);
        if (a != null && p.getUnitPrice().compareTo(a.price()) != 0 && p.getPriceChangeReason() == null) {
            if (!com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.PRODUCTION))
                return "成交单价和价格档案不一致，请交由有生产成交价权限的人员处理";
            return p.getKind().getPriceLabel() + " " + ProdTexts.plain(p.getUnitPrice()) + " 和价格档案 " + ProdTexts.money(a.price())
                    + " 不一样，要写改价原因";
        }
        return null;
    }

    /**
     * 审核（§3.2.5）：中间一级审过还是待审核。最后一级审过在同一个事务里：生成节点、单价和档案现价不一样时记价格档案、回写订单（生产中）
     */
    private void audit(ProductionOrder p, DocActionReq req) {
        long id = p.getId();
        FLOW.next("audit", p.getStatus(), APPROVED);
        if (!p.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单刚被别人审过或改过，请刷新后再审");
        }
        support.checkFactory(p.getFactoryId(), p.getKind());
        String problem = priceProblem(p);
        if (problem != null) {
            throw new BizException("审核不了：价格档案在提交以后变了，驳回让制单人补上改价原因（" + problem + "）");
        }
        if (!approvalService.pass(BizType.PRODUCTION_ORDER, id)) {
            StatusCas.update(prdMapper, new ProductionOrder(), StatusCas.of(ProductionOrder::getId, id, ProductionOrder::getStatus,
                            PENDING, PENDING)
                    .setSql("version = version + 1")
                    .eq(ProductionOrder::getVersion, req.version()));
            docLogService.add(BizType.PRODUCTION_ORDER, id, "audit", "这一级审过了，等下一级审");
            return;
        }
        StatusCas.update(prdMapper, new ProductionOrder(), StatusCas.of(ProductionOrder::getId, id, ProductionOrder::getStatus,
                        PENDING, APPROVED)
                .set(ProductionOrder::getAuditBy, LoginUser.currentUserId())
                .set(ProductionOrder::getAuditTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(ProductionOrder::getVersion, req.version()));
        int nodes = createNodes(p);
        List<String> notes = new ArrayList<>();
        notes.add("生成 " + nodes + " 个进度节点");
        priceService.recordProcessFromDoc(new PriceSource(BizType.PRODUCTION_ORDER, id, p.getNo()), p.getOrderDate(),
                        new ProcessPriceLine(p.getFactoryId(), p.getStyleId(), p.getProcessMode(), p.getUnitPrice(), p.getTaxPct(),
                                Boolean.TRUE.equals(p.getTaxIncluded()), p.getPriceChangeReason()))
                .ifPresent(r -> notes.add(p.getKind().getPriceLabel() + " " + ProdTexts.plain(r.price()) + " 记进价格档案"));
        String reason = ProdTexts.blankToNull(req.reason());
        if (reason != null) {
            notes.addFirst(reason);
        }
        docLogService.add(BizType.PRODUCTION_ORDER, id, "audit", PENDING, APPROVED, String.join("；", notes));
        support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 审核");
    }

    /** 按字典 prod_node 的启用项从交期往前倒排（§3.3.1），返回生成了几个 */
    private int createNodes(ProductionOrder p) {
        List<ProductionRules.NodeDef> defs = new ArrayList<>();
        for (DictData d : dictDataService.listWithText("prod_node")) {
            if (Integer.valueOf(1).equals(d.getStatus())) {
                int days = d.getNumValue() == null ? ProductionRules.DEFAULT_NODE_DAYS : d.getNumValue().intValue();
                defs.add(new ProductionRules.NodeDef(d.getValue(), d.getLabel(), days));
            }
        }
        List<ProductionRules.Plan> plans = ProductionRules.backSchedule(defs, p.getDueDate());
        List<ProductionNode> nodes = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            ProductionRules.Plan plan = plans.get(i);
            ProductionNode n = new ProductionNode();
            n.setPrdOrderId(p.getId());
            n.setSeq(i + 1);
            n.setNodeCode(plan.def().code());
            n.setNodeName(plan.def().name());
            n.setPlanStart(plan.start());
            n.setPlanFinish(plan.finish());
            nodes.add(n);
        }
        if (!nodes.isEmpty()) {
            nodeMapper.insert(nodes);
        }
        return nodes.size();
    }

    /**
     * 反审核（§3.2.5）：报过进度 1000；有领料退料单、回货单、异常 1005。删节点，审核时记的价格档案作废，
     * 订单没有别的审核过的生产单了退回已审核。报过进度的单已经是生产中、不出这个按钮，页面没刷新点了也说清是报过进度，
     * 所以先查它、再查状态（节点只有审核过的单有）
     */
    private void unaudit(ProductionOrder p, DocActionReq req) {
        long id = p.getId();
        nodeMapper.selectByOrder(id).stream().filter(n -> n.getLastReportTime() != null).findFirst().ifPresent(n -> {
            throw new BizException("已经报过进度（" + n.getNodeName() + " " + ProdTexts.md(n.getLastReportTime().atZoneSameInstant(clock.getZone()).toLocalDate())
                    + "），不能反审核");
        });
        FLOW.next("unaudit", p.getStatus());
        if (!p.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, ProductionSupport.STALE);
        }
        referenceService.assertNoReferrer(BizType.PRODUCTION_ORDER, id, "反审核");
        StatusCas.update(prdMapper, new ProductionOrder(), StatusCas.of(ProductionOrder::getId, id, ProductionOrder::getStatus,
                        APPROVED, DRAFT)
                .set(ProductionOrder::getAuditBy, null)
                .set(ProductionOrder::getAuditTime, null)
                .setSql("version = version + 1")
                .eq(ProductionOrder::getVersion, req.version()));
        nodeMapper.deleteByOrder(id);
        int voided = priceService.voidFromDoc(new PriceSource(BizType.PRODUCTION_ORDER, id, p.getNo()),
                ProductionSupport.label(p) + " 反审核");
        String reason = ProdTexts.blankToNull(req.reason());
        String text = voided == 0 ? reason : (reason == null ? "" : reason + "；") + "作废了审核时记的价格档案";
        docLogService.add(BizType.PRODUCTION_ORDER, id, "unaudit", APPROVED, DRAFT, text);
        support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 反审核");
    }
}
