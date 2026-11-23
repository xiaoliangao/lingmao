package com.lingmao.scm.module.fg.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.excel.BoundedExportRows;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.module.fg.core.FgLedger;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.BusinessDateRange;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.common.scope.ExportScope;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.WarehouseOption;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ActionRequestService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocActions;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.fg.dto.FgDocCreateReq;
import com.lingmao.scm.module.fg.dto.FgDocCreatedVO;
import com.lingmao.scm.module.fg.dto.FgDocDetailVO;
import com.lingmao.scm.module.fg.dto.FgDocForm;
import com.lingmao.scm.module.fg.dto.FgDocItemReq;
import com.lingmao.scm.module.fg.dto.FgDocItemVO;
import com.lingmao.scm.module.fg.dto.FgDocQuery;
import com.lingmao.scm.module.fg.dto.FgDocRowVO;
import com.lingmao.scm.module.fg.dto.FgDocSaveReq;
import com.lingmao.scm.module.fg.dto.FgFromReceiptReq;
import com.lingmao.scm.module.fg.dto.FgOverRow;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptQuery;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptRowVO;
import com.lingmao.scm.module.fg.dto.FgReceiptDraftVO;
import com.lingmao.scm.module.fg.dto.FgReceiptStockedVO;
import com.lingmao.scm.module.fg.dto.FgShipOrderOptionVO;
import com.lingmao.scm.module.fg.dto.FgShipPrefillVO;
import com.lingmao.scm.module.fg.dto.FgShippedRow;
import com.lingmao.scm.module.fg.dto.FgStyleOptionVO;
import com.lingmao.scm.module.fg.entity.FgDoc;
import com.lingmao.scm.module.fg.entity.FgDocItem;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.entity.FgStock;
import com.lingmao.scm.module.fg.mapper.FgDocItemMapper;
import com.lingmao.scm.module.fg.mapper.FgDocMapper;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import com.lingmao.scm.module.fg.service.FgDocService;
import com.lingmao.scm.module.order.dto.OrderShipView;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.production.dto.ReceiptForStock;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.FileImportService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/** 成品单据：来源行锁 → 单头行锁 → 有序余额锁；重算在草稿状态完成，CAS、流水和业务日志同事务提交。 */
@Service
@RequiredArgsConstructor
public class FgDocServiceImpl implements FgDocService {

    /** 状态转换表（docs/api/07 §2）。remove、void 都从草稿来，过没过过账决定给哪个 */
    static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("audit", DRAFT).to(APPROVED)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("remove", DRAFT).to(DRAFT)
            .on("void", DRAFT).to(VOIDED)
            .build();

    static final String PERM = "fg:doc:";

    /** 发货溢装 3%（借 Q09 回货的比例，RFC 008 §3.4）：每个 SKU 最多发 ⌊订单数 × 1.03⌋ */
    static final int OVER_SHIP_PCT = 3;

    /** 能发货的订单状态（PRD §6.12）：已审核、生产中、已完成、已结案 */
    static final Set<DocStatus> SHIPPABLE = Set.of(APPROVED, RUNNING, DONE, CLOSED);

    private static final Map<DocStatus, String> ORDER_STATUS = Map.of(APPROVED, "已审核", RUNNING, "生产中", DONE, "已完成",
            CLOSED, "已结案");

    private static final String NOT_FOUND = "单据不存在";

    private final FgDocMapper docMapper;
    private final FgDocItemMapper itemMapper;
    private final FgStockMapper stockMapper;
    private final FgStockLogMapper logMapper;
    private final FgLedger ledger;
    private final FgStyles styles;
    private final FgExcel excel;
    private final ProductionReceiptService receiptService;
    private final OrderService orderService;
    private final WarehouseService warehouseService;
    private final CompanyService companyService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final AttachmentService attachmentService;
    private final SerialNoGenerator serialNoGenerator;
    private final ActionRequestService actionRequestService;
    private final FileImportService imports;
    private final Clock clock;

    // ---------- 查询 ----------

    @Override
    public PageResult<FgDocRowVO> page(FgDocQuery query) {
        BusinessDateRange.validate(query.getDocDateFrom(), query.getDocDateTo());
        IPage<FgDocRowVO> page = docMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()), FgScope.company(query.getCompanyId()));
        fillRows(page.getRecords());
        return PageResult.of(page);
    }

    private void fillRows(List<FgDocRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, StyleOrderInfo> bySku = styles.bySku(rows.stream().map(FgDocRowVO::getFirstSkuId).filter(Objects::nonNull).toList());
        Set<Long> userIds = new HashSet<>();
        rows.forEach(r -> {
            userIds.add(r.getCreateBy());
            userIds.add(r.getAuditBy());
        });
        userIds.remove(null);
        Map<Long, String> names = userService.nicknames(userIds);
        List<Long> drafts = rows.stream().filter(r -> DRAFT.name().equals(r.getStatus())).map(FgDocRowVO::getId).toList();
        Set<Long> posted = drafts.isEmpty() ? Set.of() : logMapper.postedDocs(drafts);
        for (FgDocRowVO r : rows) {
            StyleOrderInfo s = r.getFirstSkuId() == null ? null : bySku.get(r.getFirstSkuId());
            r.setFirstStyle(s == null ? null : new FgDocRowVO.Style(s.id(), s.code(), s.name(), s.image()));
            r.setCreateByName(r.getCreateBy() == null ? null : names.get(r.getCreateBy()));
            r.setAuditByName(r.getAuditBy() == null ? null : names.get(r.getAuditBy()));
            r.setActions(actions(DocStatus.valueOf(r.getStatus()), posted.contains(r.getId()), r.getCreateBy()));
        }
    }

    /** 按钮：没过过账的草稿给"删除"，过过账的给"作废"，都要 fg:doc:remove */
    static List<String> actions(DocStatus status, boolean posted, Long createBy) {
        return DocActions.of(FLOW, status, PERM)
                .perm("edit", PERM + "edit")
                .perm("remove", PERM + "remove")
                .perm("void", PERM + "remove")
                .when("audit", !Objects.equals(createBy, LoginUser.currentUserId()))
                .when("remove", !posted)
                .when("void", posted)
                .list();
    }

    @Override
    public FgDocDetailVO detail(long id, long companyId) {
        FgDoc doc = getVisible(id, companyId);
        List<FgDocItem> items = readItems(id);
        List<Long> skuIds = items.stream().map(FgDocItem::getStyleSkuId).toList();
        Map<Long, StyleOrderInfo> bySku = styles.bySku(skuIds);
        boolean draft = doc.getStatus() == DRAFT;

        FgDocDetailVO vo = docHeaderService.fill(new FgDocDetailVO(), BizType.FG_DOC, doc, doc.getStatus());
        vo.setCompanyName(companyService.getById(doc.getCompanyId()).getShortName());
        vo.setKind(doc.getKind());
        vo.setDocType(doc.getDocType());
        WarehouseVO w = warehouseService.getById(doc.warehouseId());
        vo.setWarehouseId(w.getId());
        vo.setWarehouseName(w.getName());
        if (doc.getPrdReceiptId() != null) {
            FgDocMapper.ReceiptHead r = docMapper.receiptHead(doc.getPrdReceiptId());
            vo.setReceipt(new FgDocDetailVO.Receipt(r.id(), r.no(), r.prdOrderId(), r.prdOrderNo(), r.prdOrderKind(), r.factoryName(),
                    r.receiptDate()));
        }
        if (doc.getOrderId() != null) {
            FgDocMapper.OrderHead o = docMapper.orderHead(doc.getOrderId());
            vo.setOrder(new FgDocDetailVO.Order(o.id(), o.no(), o.deliveryReq()));
        }
        vo.setDocDate(doc.getDocDate());
        vo.setReason(doc.getReason());
        vo.setShipTo(doc.getShipTo());
        vo.setLogisticsNo(doc.getLogisticsNo());

        Map<Long, Integer> stock = draft && doc.getKind() == FgDocKind.OUT ? stockOf(doc.getCompanyId(), doc.warehouseId(), skuIds) : Map.of();
        Map<Long, Long> max = draft ? draftMax(doc, items) : Map.of();
        vo.setItems(items.stream().map(i -> {
            FgDocItemVO v = new FgDocItemVO();
            v.fillSku(i.getStyleSkuId(), bySku.get(i.getStyleSkuId()));
            v.setId(i.getId());
            v.setSeq(i.getSeq());
            v.setQty(i.getQty());
            v.setPrdReceiptItemId(i.getPrdReceiptItemId());
            v.setOrderItemId(i.getOrderItemId());
            v.setPrice(i.getPrice());
            v.setAmount(i.getAmount());
            v.setRemark(i.getRemark());
            v.setStockQty(draft && doc.getKind() == FgDocKind.OUT ? stock.getOrDefault(i.getStyleSkuId(), 0) : null);
            v.setMaxQty(max.get(i.getStyleSkuId()));
            return v;
        }).toList());
        vo.setStyles(usedStyleOptions(bySku.values()));
        vo.setTotalQty(items.stream().mapToLong(FgDocItem::getQty).sum());
        vo.setTotalAmount(doc.getDocType() == FgDocType.SHIP_OUT && items.stream().allMatch(i -> i.getAmount() != null)
                ? items.stream().map(FgDocItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add) : null);
        boolean posted = logMapper.docPosted(id);
        vo.setPosted(posted);
        vo.setActions(actions(doc.getStatus(), posted, doc.getCreateBy()));
        return vo;
    }

    /**
     * 草稿每个 SKU 最多能填多少（只显示，不锁）：回货入库 = 良品 − 别的单已入；发货出库 = ⌊订单数 × 1.03⌋ − 别的单已发。
     * 回货单、订单行只读 JOIN 取（看得到这张单的人不一定看得到生产单、订单）
     */
    private Map<Long, Long> draftMax(FgDoc doc, List<FgDocItem> items) {
        Map<Long, Long> out = new HashMap<>();
        if (doc.getDocType() == FgDocType.RECEIPT_IN) {
            List<Long> lineIds = items.stream().map(FgDocItem::getPrdReceiptItemId).filter(Objects::nonNull).toList();
            Map<Long, Long> stocked = lineQty(docMapper.stockedByReceiptLines(doc.getPrdReceiptId(), doc.getId(), doc.getCompanyId(), lineIds));
            for (FgDocMapper.ReceiptLine l : docMapper.receiptLines(doc.getPrdReceiptId(), lineIds, doc.getCompanyId()))
                out.merge(l.styleSkuId(), Math.max(0L, (long) l.qty() - l.defectQty() - stocked.getOrDefault(l.id(), 0L)), Long::sum);
        } else if (doc.getDocType() == FgDocType.SHIP_OUT) {
            List<Long> lineIds = items.stream().map(FgDocItem::getOrderItemId).filter(Objects::nonNull).toList();
            Map<Long, Long> shipped = lineQty(docMapper.shippedByOrderLines(doc.getOrderId(), doc.getId(), doc.getCompanyId(), lineIds));
            for (FgDocMapper.OrderLine l : docMapper.orderLines(doc.getOrderId(), lineIds, doc.getCompanyId()))
                out.merge(l.styleSkuId(), Math.max(0L, shipCap(l.qty()) - shipped.getOrDefault(l.id(), 0L)), Long::sum);
        }
        return out;
    }

    private Map<Long, Integer> stockOf(long companyId, long warehouseId, Collection<Long> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        return stockMapper.selectBySkus(companyId, warehouseId, skuIds).stream()
                .collect(Collectors.toMap(FgStock::getStyleSkuId, FgStock::getQty));
    }

    private static Map<Long, Long> lineQty(List<FgDocMapper.LineQty> rows) {
        return rows.stream().collect(Collectors.toMap(FgDocMapper.LineQty::lineId, FgDocMapper.LineQty::qty));
    }

    /** ⌊订单数 × 1.03⌋，上限比较保留 long，不能在比较之前截成 int。 */
    static long shipCap(int orderQty) {
        return (long) orderQty * (100 + OVER_SHIP_PCT) / 100;
    }

    /** 部分投影已经按款合并所请求的 SKU；详情和来源预填不得补读整款色码。 */
    private static List<FgStyleOptionVO> usedStyleOptions(Collection<StyleOrderInfo> infos) {
        Map<Long, StyleOrderInfo> byStyle = new LinkedHashMap<>();
        infos.forEach(info -> byStyle.putIfAbsent(info.id(), info));
        return byStyle.values().stream().map(FgStyleOptionVO::of).toList();
    }

    @Override
    public void checkVisible(long id) {
        FgDoc doc = visible(docMapper.selectById(id));
        Long company = CompanyContext.header(true);
        if (company != null && !company.equals(doc.getCompanyId())) throw new BizException(404, NOT_FOUND);
    }

    @Override
    public void checkVisible(long id, long companyId) { getVisible(id, companyId); }

    private FgDoc getVisible(long id, long companyId) {
        FgScope.company(companyId);
        FgDoc doc = visible(docMapper.selectById(id));
        if (!Objects.equals(doc.getCompanyId(), companyId)) throw new BizException(404, NOT_FOUND);
        return doc;
    }

    /** 只检查普通 FG DataScope；FG 没有上游的当前审批人例外。 */
    private static FgDoc visible(FgDoc doc) {
        if (doc == null) throw new BizException(404, NOT_FOUND);
        DataScope.current(false).check(doc.getCreateBy());
        return doc;
    }

    private record Locked(FgDoc doc, ReceiptForStock receipt, OrderShipView order) { }

    /** 所有写路径共用：来源先锁；不可把编辑/反审/删除变成 header→source。 */
    private Locked lockWithSource(long id, long companyId) {
        FgDoc before = getVisible(id, companyId);
        ReceiptForStock receipt = before.getPrdReceiptId() == null ? null
                : receiptService.forStock(before.getPrdReceiptId(), companyId);
        OrderShipView order = before.getOrderId() == null ? null : orderService.shipView(before.getOrderId(), companyId);
        FgDoc doc = visible(docMapper.lockById(id));
        if (!Objects.equals(doc.getCompanyId(), companyId)
                || doc.getDocType() != before.getDocType()
                || !Objects.equals(doc.getPrdReceiptId(), before.getPrdReceiptId())
                || !Objects.equals(doc.getOrderId(), before.getOrderId()))
            throw new BizException(ErrorCodes.STATE_CHANGED, "来源或主体已变化，请重新打开单据");
        return new Locked(doc, receipt, order);
    }

    private static void require(String permission) {
        if (!LoginUser.hasAuthority(permission)) throw new BizException(403, "没有该成品操作的权限");
    }

    private static void version(FgDoc doc, Integer expected) {
        if (expected == null || expected < 0 || !Objects.equals(doc.getVersion(), expected))
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单已经被别人改过，请刷新后再操作");
    }

    // ---------- 手工新建、改、删 ----------

    @Override
    @Transactional
    public FgDocCreatedVO create(FgDocCreateReq req) {
        require(PERM + "add");
        FgScope.company(req.getCompanyId());
        FgDocType type = req.getDocType();
        if (type == null) throw new BizException(400, "请选类型");
        if (!type.isManual()) {
            throw new BizException(400, type.getLabel() + "要从「待入库」的回货单生成");
        }
        FgDoc doc = new FgDoc();
        doc.setKind(type.getKind());
        doc.setDocType(type);
        doc.setStatus(DRAFT);
        doc.setCompanyId(req.getCompanyId());
        applyForm(doc, req);
        checkHeader(doc);
        List<FgDocItem> items;
        if (type == FgDocType.SHIP_OUT) {
            if (req.getOrderId() == null) {
                throw new BizException(400, "请选需求订单");
            }
            OrderShipView order = shippableOrder(req.getOrderId(), req.getCompanyId());
            if (order.companyId() != req.getCompanyId()) {
                throw new BizException(400, "主体要和订单 " + order.no() + " 的一样");
            }
            doc.setOrderId(order.id());
            items = shipItems(order, req.getItems(), null);
        } else {
            checkCompany(req.getCompanyId());
            items = manualItems(req.getItems());
        }
        insert(doc, items);
        docLogService.add(BizType.FG_DOC, doc.getId(), "create", null);
        return new FgDocCreatedVO(doc.getId(), doc.getNo(), 0, DRAFT.name());
    }

    @Override
    @Transactional
    public int update(long id, FgDocSaveReq req) {
        require(PERM + "edit");
        long companyId = FgScope.company(req.getCompanyId());
        Locked locked = lockWithSource(id, companyId);
        return withReceipt("FG_UPDATE", id, req, MutationReceipt.class, () -> new MutationReceipt(updateLocked(locked, req), true)).version();
    }

    private int updateLocked(Locked locked, FgDocSaveReq req) {
        FgDoc doc = locked.doc();
        FLOW.next("edit", doc.getStatus());
        version(doc, req.getVersion());
        applyForm(doc, req);
        checkHeader(doc);
        List<FgDocItem> items = recheckItems(locked, req.getItems());
        UpdateWrapper<FgDoc> update = Updates.replaceById(doc)
                .eq("company_id", doc.getCompanyId()).eq("status", DRAFT.name()).eq("version", req.getVersion())
                .setSql("version = version + 1");
        if (docMapper.update(doc, update) == 0) throw new BizException(ErrorCodes.STATE_CHANGED, "单据已变化，请刷新后再改");
        replaceItems(doc.getId(), items);
        docLogService.add(BizType.FG_DOC, doc.getId(), "update", null);
        return req.getVersion() + 1;
    }

    /** 身份按数据库唯一(doc_id,style_sku_id)保持；只删移除行，不用删除+插入冒充修改。 */
    private void replaceItems(long docId, List<FgDocItem> desired) {
        Map<Long, FgDocItem> old = readItems(docId).stream().collect(Collectors.toMap(FgDocItem::getStyleSkuId, Function.identity()));
        List<FgDocItem> added = new ArrayList<>(); List<FgDocItem> changed = new ArrayList<>();
        for (FgDocItem item : desired) {
            item.setDocId(docId);
            FgDocItem before = old.remove(item.getStyleSkuId());
            if (before == null) added.add(item);
            else { item.setId(before.getId()); changed.add(item); }
        }
        if (!old.isEmpty()) {
            int removed = itemMapper.delete(new LambdaQueryWrapper<FgDocItem>().eq(FgDocItem::getDocId, docId)
                    .in(FgDocItem::getId, old.values().stream().map(FgDocItem::getId).toList()));
            if (removed != old.size()) throw new BizException(ErrorCodes.STATE_CHANGED, "明细已变化，请刷新后重试");
        }
        if (!changed.isEmpty() && itemMapper.updateExisting(docId, changed) != changed.size())
            throw new BizException(ErrorCodes.STATE_CHANGED, "明细已变化，请刷新后重试");
        if (!added.isEmpty()) itemMapper.insert(added);
    }

    private List<FgDocItem> readItems(long docId) {
        var rows = itemMapper.selectList(new LambdaQueryWrapper<FgDocItem>().eq(FgDocItem::getDocId, docId)
                .orderByAsc(FgDocItem::getSeq, FgDocItem::getId).last("LIMIT 2001"));
        if (rows.size() > 2000) throw new BizException(400, "成品单明细超过 2000 行，需要分单处理");
        return rows;
    }

    @Override
    @Transactional
    public void delete(long id, long companyId, int expectedVersion) {
        require(PERM + "remove");
        FgScope.company(companyId);
        // 收据只属于当前已授权用户/操作/主体；删除后重放不读取已不存在对象，也不返回它的信息。
        withReceipt("FG_DELETE", id, Map.of("companyId", companyId, "version", expectedVersion), MutationReceipt.class, () -> {
            FgDoc doc = lockWithSource(id, companyId).doc();
            FLOW.next("remove", doc.getStatus()); version(doc, expectedVersion);
            if (logMapper.docPosted(id)) throw new BizException("这张单有过账记录，不能删；请反审后作废");
            itemMapper.deleteByDoc(id); attachmentService.deleteByDoc(BizType.FG_DOC, id);
            docLogService.add(BizType.FG_DOC, id, "remove", "删除未过账草稿");
            if (docMapper.delete(new LambdaQueryWrapper<FgDoc>().eq(FgDoc::getId, id)
                    .eq(FgDoc::getCompanyId, companyId).eq(FgDoc::getStatus, DRAFT).eq(FgDoc::getVersion, expectedVersion)) != 1)
                throw new BizException(ErrorCodes.STATE_CHANGED, "单据已变化，请刷新后再操作");
            return new MutationReceipt(null, true);
        });
    }

    /** 既有 biz_action_request 只保存 JSON 对象，不能写 int/boolean 标量。 */
    private record MutationReceipt(Integer version, boolean completed) { }

    /** HTTP 动作使用既有持久收据；纯服务调用仍按 version/CAS 校验，不伪造请求编号。 */
    private <T> T withReceipt(String operation, long id, Object request, Class<T> resultType, java.util.function.Supplier<T> action) {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attrs)) return action.get();
        String key = attrs.getRequest().getHeader("Idempotency-Key");
        java.util.UUID uuid;
        try {
            if (key == null || key.length() != 36) throw new IllegalArgumentException();
            uuid = java.util.UUID.fromString(key);
            if (!uuid.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new BizException(400, "缺少或无效的 Idempotency-Key"); }
        return actionRequestService.execute(operation, id, uuid, request, resultType, action);
    }

    private List<FgDocItem> recheckItems(Locked locked, List<FgDocItemReq> reqs) {
        FgDoc doc = locked.doc();
        return switch (doc.getDocType()) {
            case RECEIPT_IN -> receiptItems(locked.receipt(), reqs, doc.getId());
            case SHIP_OUT -> { checkShippable(locked.order()); yield shipItems(locked.order(), reqs, doc.getId()); }
            default -> manualItems(reqs);
        };
    }

    /** 单头上能改的抄到实体上（新建、改草稿共用）。类型、主体、回货单、订单不在这里 */
    private static void applyForm(FgDoc doc, FgDocForm form) {
        if (doc.getKind() == FgDocKind.IN) {
            doc.setToWarehouseId(form.getWarehouseId());
            doc.setFromWarehouseId(null);
        } else {
            doc.setFromWarehouseId(form.getWarehouseId());
            doc.setToWarehouseId(null);
        }
        doc.setDocDate(form.getDocDate());
        doc.setReason(blankToNull(form.getReason()));
        doc.setShipTo(blankToNull(form.getShipTo()));
        doc.setLogisticsNo(blankToNull(form.getLogisticsNo()));
        doc.setRemark(blankToNull(form.getRemark()));
    }

    private void checkCompany(long companyId) {
        Company company = companyService.getById(companyId);
        if (!Integer.valueOf(1).equals(company.getStatus())) {
            throw new BizException("主体" + company.getShortName() + "已停用，请换一个");
        }
    }

    /** 单头的检查：日期不晚于今天；仓是启用的成品仓；其他入库、其他出库的原因必填、在字典里，别的类型不要原因 */
    private void checkHeader(FgDoc doc) {
        if (doc.getDocDate() == null) throw new BizException(400, "请填日期");
        if (doc.warehouseId() == null) throw new BizException(400, "请选仓库");
        checkCompany(doc.getCompanyId());
        if (doc.getDocDate().isAfter(LocalDate.now(clock))) {
            throw new BizException(400, "日期不能晚于今天");
        }
        productWarehouse(doc.warehouseId());
        FgDocType type = doc.getDocType();
        if (type.getReasonDict() == null) {
            doc.setReason(null);
        } else if (doc.getReason() == null) {
            throw new BizException(400, "请选原因");
        } else if (!dictDataService.labels(type.getReasonDict()).containsKey(doc.getReason())) {
            throw new BizException(400, "原因不在字典里：" + doc.getReason());
        }
        if (type != FgDocType.SHIP_OUT) {
            doc.setShipTo(null);
        }
    }

    private WarehouseVO productWarehouse(long id) {
        WarehouseVO w = warehouseService.getById(id);
        if (!"PRODUCT".equals(w.getType())) {
            throw new BizException(400, "仓库要选成品仓：" + w.getName() + " 不是");
        }
        if (!Integer.valueOf(1).equals(w.getStatus())) {
            throw new BizException("仓库" + w.getName() + "已停用");
        }
        return w;
    }

    /** 其他入库、其他出库的明细：SKU 要存在（停用的也收：老款的期初、退回的货），一个 SKU 一行 */
    private List<FgDocItem> manualItems(List<FgDocItemReq> reqs) {
        checkUniqueSkus(reqs);
        Map<Long, StyleOrderInfo> bySku = styles.bySku(reqs.stream().map(FgDocItemReq::getStyleSkuId).toList());
        List<FgDocItem> items = new ArrayList<>();
        for (int n = 0; n < reqs.size(); n++) {
            FgDocItemReq r = reqs.get(n);
            if (FgStyles.sku(bySku.get(r.getStyleSkuId()), r.getStyleSkuId()) == null) {
                throw new BizException(400, "第 " + (n + 1) + " 行的 SKU 不存在，请刷新后重选");
            }
            items.add(item(n + 1, r.getStyleSkuId(), r.getQty(), r.getRemark()));
        }
        return items;
    }

    private static void checkUniqueSkus(List<FgDocItemReq> reqs) {
        if (reqs == null || reqs.isEmpty() || reqs.size() > 2000) throw new BizException(400, "明细需要 1 至 2000 行");
        Set<Long> seen = new HashSet<>();
        for (int n = 0; n < reqs.size(); n++) {
            FgDocItemReq row = reqs.get(n);
            if (row == null || row.getStyleSkuId() == null || row.getStyleSkuId() <= 0
                    || row.getQty() == null || row.getQty() <= 0 || row.getQty() > 99_999_999)
                throw new BizException(400, "第 " + (n + 1) + " 行需要有效 SKU 和 1 至 99,999,999 件");
            if (row.getRemark() != null && row.getRemark().length() > 255)
                throw new BizException(400, "第 " + (n + 1) + " 行备注最多 255 个字");
            if (!seen.add(row.getStyleSkuId())) {
                throw new BizException(400, "第 " + (n + 1) + " 行的 SKU 和前面的重复了：一个 SKU 一行");
            }
        }
    }

    private static FgDocItem item(int seq, long skuId, int qty, String remark) {
        FgDocItem item = new FgDocItem();
        item.setSeq(seq);
        item.setStyleSkuId(skuId);
        item.setQty(qty);
        item.setRemark(blankToNull(remark));
        return item;
    }

    private void insert(FgDoc doc, List<FgDocItem> items) {
        doc.setNo(serialNoGenerator.daily(BizType.FG_DOC.prefix(doc.getKind().name())));
        docMapper.insert(doc);
        items.forEach(i -> i.setDocId(doc.getId()));
        itemMapper.insert(items);
    }

    // ---------- 状态动作 ----------

    @Override
    @Transactional
    public void act(long id, long companyId, String action, DocActionReq req) {
        require(PERM + ("void".equals(action) ? "remove" : "audit"));
        Locked locked = lockWithSource(id, companyId);
        version(locked.doc(), req.version());
        switch (action) {
            case "audit" -> audit(locked, req.version(), req.reason());
            case "unaudit" -> unaudit(locked.doc(), req);
            case "void" -> voidDoc(locked.doc(), req);
            default -> throw new BizException(404, "成品单据没有此动作");
        }
    }

    /** 重算发生在 DRAFT；数据库明细保护与审核 CAS 使用同一单头锁。 */
    private void audit(Locked locked, int expectedVersion, String remark) {
        FgDoc doc = locked.doc();
        require(PERM + "audit");
        FLOW.next("audit", doc.getStatus());
        version(doc, expectedVersion);
        if (Objects.equals(doc.getCreateBy(), LoginUser.currentUserId()))
            throw new BizException(403, "制单人不能审核自己的成品单，请交由其他审核人处理");
        checkHeader(doc);
        List<FgDocItem> old = readItems(doc.getId());
        List<FgDocItemReq> reqs = old.stream().map(i -> {
            FgDocItemReq r = itemReq(i.getStyleSkuId(), i.getQty()); r.setRemark(i.getRemark()); return r;
        }).toList();
        List<FgDocItem> items = recheckItems(locked, reqs);
        Map<Long, Long> ids = old.stream().collect(Collectors.toMap(FgDocItem::getStyleSkuId, FgDocItem::getId));
        for (FgDocItem fresh : items) { fresh.setId(ids.get(fresh.getStyleSkuId())); fresh.setDocId(doc.getId()); }
        if (!items.isEmpty() && itemMapper.updateExisting(doc.getId(), items) != items.size())
            throw new BizException(ErrorCodes.STATE_CHANGED, "明细已变化，请刷新后再审核");
        StatusCas.update(docMapper, new FgDoc(), StatusCas.of(FgDoc::getId, doc.getId(), FgDoc::getStatus, DRAFT, APPROVED)
                .eq(FgDoc::getCompanyId, doc.getCompanyId()).eq(FgDoc::getVersion, expectedVersion)
                .set(FgDoc::getAuditBy, LoginUser.currentUserId()).set(FgDoc::getAuditTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1"));
        ledger.post(posting(doc, items));
        docLogService.add(BizType.FG_DOC, doc.getId(), "audit", DRAFT, APPROVED, remark);
    }

    private void unaudit(FgDoc doc, DocActionReq req) {
        FLOW.next("unaudit", doc.getStatus());
        StatusCas.update(docMapper, new FgDoc(), StatusCas.of(FgDoc::getId, doc.getId(), FgDoc::getStatus, APPROVED, DRAFT)
                .eq(FgDoc::getCompanyId, doc.getCompanyId()).eq(FgDoc::getVersion, req.version())
                .set(FgDoc::getAuditBy, null).set(FgDoc::getAuditTime, null).setSql("version = version + 1"));
        ledger.reverse(doc.getCompanyId(), doc.getId());
        docLogService.add(BizType.FG_DOC, doc.getId(), "unaudit", APPROVED, DRAFT, req.reason());
    }

    private void voidDoc(FgDoc doc, DocActionReq req) {
        FLOW.next("void", doc.getStatus());
        String reason = blankToNull(req.reason());
        if (reason == null) throw new BizException(400, "请写作废原因");
        if (!logMapper.docPosted(doc.getId())) throw new BizException("这张单没过过账，直接删除即可");
        StatusCas.update(docMapper, new FgDoc(), StatusCas.of(FgDoc::getId, doc.getId(), FgDoc::getStatus, DRAFT, VOIDED)
                .eq(FgDoc::getCompanyId, doc.getCompanyId()).eq(FgDoc::getVersion, req.version())
                .setSql("version = version + 1"));
        docLogService.add(BizType.FG_DOC, doc.getId(), "void", DRAFT, VOIDED, reason);
    }

    static FgLedger.Posting posting(FgDoc doc, List<FgDocItem> items) {
        int sign = doc.getKind() == FgDocKind.IN ? 1 : -1;
        List<FgLedger.Move> moves = items.stream()
                .map(i -> new FgLedger.Move(doc.warehouseId(), i.getStyleSkuId(), sign * i.getQty(), i.getSeq())).toList();
        return new FgLedger.Posting(doc.getCompanyId(), doc.getId(), doc.getNo(), doc.getDocType(), doc.getDocDate(), moves);
    }

    // ---------- 回货入库 ----------

    @Override
    public PageResult<FgPendingReceiptRowVO> pendingReceipts(FgPendingReceiptQuery query) {
        BusinessDateRange.validate(query.getReceiptDateFrom(), query.getReceiptDateTo());
        IPage<FgPendingReceiptRowVO> page = docMapper.selectPendingPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(false), FgScope.company(query.getCompanyId()));
        var images = attachmentService.firsts(BizType.STYLE,
                page.getRecords().stream().map(FgPendingReceiptRowVO::getStyleId).distinct().toList(),
                com.lingmao.scm.module.biz.entity.Attachment.IMAGE);
        page.getRecords().forEach(r -> r.setImage(images.get(r.getStyleId())));
        return PageResult.of(page);
    }

    @Override
    public long pendingReceiptCount(long companyId) {
        require(PERM + "add");
        return docMapper.countPending(DataScope.current(false), FgScope.company(companyId));
    }

    @Override
    @Transactional
    public FgReceiptDraftVO receiptDraft(long receiptId, long companyId, Long docId) {
        require(PERM + (docId == null ? "add" : "edit"));
        ReceiptForStock r = receiptService.forStock(receiptId, FgScope.company(companyId));
        Map<Long, Integer> ownQty = Map.of();
        if (docId != null) {
            FgDoc doc = getVisible(docId, companyId);
            if (doc.getDocType() != FgDocType.RECEIPT_IN || !Objects.equals(doc.getPrdReceiptId(), receiptId)
                    || doc.getStatus() != DRAFT) throw new BizException(404, NOT_FOUND);
            ownQty = readItems(docId).stream().collect(Collectors.toMap(FgDocItem::getPrdReceiptItemId, FgDocItem::getQty));
        }
        Map<Long, Integer> currentQty = ownQty;
        Map<Long, FgDocMapper.ReceiptLineOccupancy> occupancy = docMapper.selectReceiptLineOccupancy(receiptId, companyId)
                .stream().collect(Collectors.toMap(FgDocMapper.ReceiptLineOccupancy::lineId, Function.identity()));
        WarehouseOption w = defaultWarehouse(r.warehouseId());
        List<FgStyleOptionVO> sourceStyles = usedStyleOptions(styles.bySku(r.items().stream()
                .map(ReceiptForStock.Item::styleSkuId).toList()).values());
        FgReceiptDraftVO vo = new FgReceiptDraftVO();
        vo.setCompanyId(r.companyId()); vo.setReceiptId(r.id()); vo.setReceiptNo(r.no());
        vo.setPrdOrderNo(r.prdOrderNo()); vo.setPrdOrderKind(r.prdOrderKind());
        vo.setFactoryName(docMapper.factoryName(r.factoryId())); vo.setReceiptDate(r.receiptDate());
        vo.setWarehouseId(w.id()); vo.setWarehouseName(w.name());
        vo.setStyle(sourceStyles.isEmpty() ? null : sourceStyles.getFirst());
        vo.setItems(r.items().stream().map(i -> {
            var o = occupancy.get(i.id());
            int reserved = NumericLimits.pieces(o == null ? 0 : o.reservedQty(), "回货已占用：");
            int posted = NumericLimits.pieces(o == null ? 0 : o.postedQty(), "回货已入库：");
            int used = NumericLimits.pieces((long) reserved + posted, "回货已占用：");
            int left = NumericLimits.pieces(Math.max(0L, (long) i.goodQty() - used), "回货剩余：");
            return new FgReceiptDraftVO.Item(i.id(), i.styleSkuId(), i.qty(), i.defectQty(), i.goodQty(),
                    used, NumericLimits.pieces(Math.max(0L, (long) i.goodQty() - used + currentQty.getOrDefault(i.id(), 0)), "回货可编辑上限："), reserved, posted, left);
        }).toList());
        if (vo.getItems().stream().noneMatch(i -> i.maxQty() > 0)) throw allStocked(r);
        return vo;
    }

    private Map<Long, Long> remaining(ReceiptForStock r, Long excludeDocId) {
        Map<Long, Long> stocked = lineQty(docMapper.stockedByReceipt(r.id(), excludeDocId, r.companyId()));
        Map<Long, Long> out = new LinkedHashMap<>();
        r.items().forEach(i -> out.put(i.id(), (long) i.goodQty() - stocked.getOrDefault(i.id(), 0L)));
        return out;
    }

    private BizException allStocked(ReceiptForStock r) {
        return new BizException("回货单 " + r.no() + " 的良品已全部入库或被有效草稿占用，请刷新关联单据");
    }

    /** 默认入仓：回货单的收货仓（是启用的成品仓时），否则第一个启用的成品仓 */
    private WarehouseOption defaultWarehouse(Long receiptWarehouseId) {
        List<WarehouseOption> options = warehouseService.options("PRODUCT");
        if (options.isEmpty()) {
            throw new BizException("还没有成品仓，先在 基础 › 仓库 建一个");
        }
        return options.stream().filter(o -> o.id().equals(receiptWarehouseId)).findFirst().orElse(options.getFirst());
    }

    @Override
    @Transactional
    public FgDocCreatedVO fromReceipt(long receiptId, FgFromReceiptReq req) {
        require(PERM + "add");
        long companyId = FgScope.company(req.getCompanyId());
        ReceiptForStock receipt = receiptService.forStock(receiptId, companyId);
        if (req.isPost()) {
            require(PERM + "audit");
            throw new BizException(403, "制单人不能审核自己的成品单，请先存草稿再由其他审核人处理");
        }
        FgDocCreatedVO result = actionRequestService.execute("FG_FROM_RECEIPT", receiptId, req.getClientActionId(), req,
                FgDocCreatedVO.class, () -> createFromReceipt(receipt, req));
        checkVisible(result.id(), companyId);
        return result;
    }

    private FgDocCreatedVO createFromReceipt(ReceiptForStock r, FgFromReceiptReq req) {
        Map<Long, Long> remain = remaining(r, null);
        if (req.getItems() == null && remain.values().stream().allMatch(q -> q <= 0)) throw allStocked(r);
        Map<Long, ReceiptForStock.Item> lines = r.items().stream().collect(Collectors.toMap(ReceiptForStock.Item::id, Function.identity()));
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        if (req.getItems() == null) {
            remain.forEach((lineId, q) -> { if (q > 0) wanted.put(lines.get(lineId).styleSkuId(), NumericLimits.pieces(q, "可入数量：")); });
        } else {
            if (req.getItems().size() > 2000) throw new BizException(400, "一张单最多 2000 行");
            Set<Long> seen = new HashSet<>();
            for (FgFromReceiptReq.Item i : req.getItems()) {
                if (i == null || i.getQty() == null || i.getQty() < 0 || i.getQty() > 99_999_999)
                    throw new BizException(400, "回货入库件数需为 0 至 99,999,999 的整数");
                ReceiptForStock.Item line = lines.get(i.getPrdReceiptItemId());
                if (line == null || !seen.add(i.getPrdReceiptItemId()))
                    throw new BizException(400, "回货明细不存在或重复，请刷新后重新入库");
                if (i.getQty() > 0) wanted.put(line.styleSkuId(), i.getQty());
            }
        }
        if (wanted.isEmpty()) throw new BizException(400, "没有要入库的数");
        FgDoc doc = new FgDoc(); doc.setKind(FgDocKind.IN); doc.setDocType(FgDocType.RECEIPT_IN);
        doc.setStatus(DRAFT); doc.setCompanyId(r.companyId()); doc.setPrdReceiptId(r.id());
        doc.setToWarehouseId(req.getWarehouseId() == null ? defaultWarehouse(r.warehouseId()).id() : req.getWarehouseId());
        doc.setDocDate(req.getDocDate() == null ? LocalDate.now(clock) : req.getDocDate()); doc.setRemark(blankToNull(req.getRemark()));
        checkHeader(doc);
        List<FgDocItem> items = receiptItems(r, wanted.entrySet().stream().map(e -> itemReq(e.getKey(), e.getValue())).toList(), null);
        insert(doc, items);
        docLogService.add(BizType.FG_DOC, doc.getId(), "create", "由回货单 " + r.no() + " 生成");
        return new FgDocCreatedVO(doc.getId(), doc.getNo(), 0, DRAFT.name());
    }

    private static FgDocItemReq itemReq(long skuId, int qty) {
        FgDocItemReq r = new FgDocItemReq();
        r.setStyleSkuId(skuId);
        r.setQty(qty);
        return r;
    }

    private static List<FgDocItemReq> bySkuReq(List<FgDocItemReq> reqs) {
        checkUniqueSkus(reqs);
        return reqs;
    }

    /**
     * 回货入库的明细：每行按 SKU 对上回货明细（回货单一个 SKU 一行），不能填回货单以外的 SKU（400），
     * 不能超过这一行还没入的良品（1004，不算 excludeDocId 这张单自己）。调之前已经锁住回货单
     */
    private List<FgDocItem> receiptItems(ReceiptForStock r, List<FgDocItemReq> reqs, Long excludeDocId) {
        checkUniqueSkus(reqs);
        Map<Long, ReceiptForStock.Item> bySku = r.items().stream()
                .collect(Collectors.toMap(ReceiptForStock.Item::styleSkuId, Function.identity()));
        List<FgDocItem> items = new ArrayList<>();
        for (int n = 0; n < reqs.size(); n++) {
            FgDocItemReq q = reqs.get(n);
            ReceiptForStock.Item line = bySku.get(q.getStyleSkuId());
            if (line == null) {
                throw new BizException(400, "第 " + (n + 1) + " 行的 SKU 不在回货单 " + r.no() + " 上");
            }
            FgDocItem item = item(n + 1, q.getStyleSkuId(), q.getQty(), q.getRemark());
            item.setPrdReceiptItemId(line.id());
            items.add(item);
        }
        checkReceiptLimits(r, items, excludeDocId);
        return items;
    }

    /** 每行不能超过还没入的良品（1004）：两个人同时入同一张回货单，在回货单锁里算，加起来不会超过良品 */
    private void checkReceiptLimits(ReceiptForStock r, List<FgDocItem> items, Long excludeDocId) {
        Map<Long, Long> remain = remaining(r, excludeDocId);
        Map<Long, Long> stocked = lineQty(docMapper.stockedByReceipt(r.id(), excludeDocId, r.companyId()));
        Map<Long, ReceiptForStock.Item> lines = r.items().stream()
                .collect(Collectors.toMap(ReceiptForStock.Item::id, Function.identity()));
        List<FgOverRow> over = new ArrayList<>();
        Map<Long, StyleOrderInfo> bySku = null;
        for (FgDocItem i : items) {
            long max = Math.max(0L, remain.getOrDefault(i.getPrdReceiptItemId(), 0L));
            if (i.getQty() > max) {
                if (bySku == null) {
                    bySku = styles.bySku(items.stream().map(FgDocItem::getStyleSkuId).toList());
                }
                ReceiptForStock.Item line = lines.get(i.getPrdReceiptItemId());
                over.add(new FgOverRow(i.getSeq(), FgStyles.code(bySku.get(i.getStyleSkuId()), i.getStyleSkuId()), r.no(),
                        i.getQty(), max, "良品 " + line.goodQty() + "，已入 " + stocked.getOrDefault(line.id(), 0L)));
            }
        }
        if (!over.isEmpty()) {
            FgOverRow first = over.getFirst();
            throw new BizException(ErrorCodes.OVER_LIMIT, first.skuCode() + " 这次 " + first.thisQty() + " 件，最多还能入 "
                    + first.maxQty() + " 件" + (over.size() > 1 ? "，等 " + over.size() + " 个" : ""), over);
        }
    }

    // ---------- 发货出库 ----------

    @Override
    public List<FgShipOrderOptionVO> shipOrders(String keyword, Long warehouseId, long companyId) {
        require(PERM + "add");
        if (warehouseId != null) productWarehouse(warehouseId);
        List<FgShipOrderOptionVO> rows = docMapper.selectShipOrders(keyword, Keywords.codes(keyword), warehouseId, DataScope.current(false), FgScope.company(companyId));
        rows.forEach(r -> r.setStatusLabel(ORDER_STATUS.get(DocStatus.valueOf(r.getStatus()))));
        return rows;
    }

    @Override
    @Transactional
    public FgShipPrefillVO shipPrefill(long orderId, Long warehouseId, Long docId, long companyId) {
        FgScope.company(companyId);
        OrderShipView order = shippableOrder(orderId, companyId);
        if (docId != null) {
            FgDoc doc = getVisible(docId, companyId);
            if (doc.getDocType() != FgDocType.SHIP_OUT || !Objects.equals(doc.getOrderId(), orderId) || doc.getStatus() != DRAFT)
                throw new BizException(404, NOT_FOUND);
        }
        if (warehouseId != null) {
            productWarehouse(warehouseId);
        }
        Map<Long, Long> shipped = lineQty(docMapper.shippedByOrder(orderId, docId, companyId));
        List<OrderShipView.Line> lines = order.lines().stream().filter(l -> l.qty() > 0).toList();
        Map<Long, Integer> stock = warehouseId == null ? Map.of()
                : stockOf(companyId, warehouseId, lines.stream().map(OrderShipView.Line::styleSkuId).toList());
        FgShipPrefillVO vo = new FgShipPrefillVO();
        vo.setOrderId(order.id());
        vo.setOrderNo(order.no());
        vo.setCompanyId(order.companyId());
        vo.setDeliveryReq(order.deliveryReq());
        vo.setStyles(usedStyleOptions(styles.bySku(lines.stream().map(OrderShipView.Line::styleSkuId).toList()).values()));
        vo.setLines(lines.stream().map(l -> {
            long done = shipped.getOrDefault(l.orderItemId(), 0L);
            return new FgShipPrefillVO.Line(l.orderItemId(), l.styleSkuId(), l.qty(), done, Math.max(0, shipCap(l.qty()) - done),
                    stock.getOrDefault(l.styleSkuId(), 0), l.price());
        }).toList());
        return vo;
    }

    private OrderShipView shippableOrder(long orderId, long companyId) {
        OrderShipView order = orderService.shipView(orderId, companyId);
        checkShippable(order);
        return order;
    }

    private static void checkShippable(OrderShipView order) {
        if (order == null || !SHIPPABLE.contains(order.status()))
            throw new BizException("只有已审核、生产中、已完成或已结案的需求订单才能发货");
    }

    /**
     * 发货出库的明细：只能发订单上的 SKU（400），单价、金额按订单行算（前端发的不信），
     * 每个 SKU 不超过最多还能发（1004，已发只算已审核的、不算 excludeDocId 这张单）
     */
    private List<FgDocItem> shipItems(OrderShipView order, List<FgDocItemReq> reqs, Long excludeDocId) {
        checkUniqueSkus(reqs);
        Map<Long, OrderShipView.Line> bySku = order.lines().stream()
                .collect(Collectors.toMap(OrderShipView.Line::styleSkuId, Function.identity(), (a, b) -> a));
        List<FgDocItem> items = new ArrayList<>();
        for (int n = 0; n < reqs.size(); n++) {
            FgDocItemReq q = reqs.get(n);
            OrderShipView.Line line = bySku.get(q.getStyleSkuId());
            if (line == null) {
                throw new BizException(400, "第 " + (n + 1) + " 行的 SKU 不在需求订单 " + order.no() + " 上");
            }
            FgDocItem item = item(n + 1, q.getStyleSkuId(), q.getQty(), q.getRemark());
            item.setOrderItemId(line.orderItemId());
            item.setPrice(NumericLimits.price(line.price(), "第 " + (n + 1) + " 行："));
            item.setAmount(NumericLimits.amount(Num.amount(q.getQty(), item.getPrice()), "第 " + (n + 1) + " 行："));
            items.add(item);
        }
        checkShipLimits(order, items, excludeDocId);
        return items;
    }

    private void checkShipLimits(OrderShipView order, List<FgDocItem> items, Long excludeDocId) {
        Map<Long, Long> shipped = lineQty(docMapper.shippedByOrder(order.id(), excludeDocId, order.companyId()));
        Map<Long, OrderShipView.Line> lines = order.lines().stream()
                .collect(Collectors.toMap(OrderShipView.Line::orderItemId, Function.identity()));
        List<FgOverRow> over = new ArrayList<>();
        Map<Long, StyleOrderInfo> bySku = null;
        for (FgDocItem i : items) {
            OrderShipView.Line line = lines.get(i.getOrderItemId());
            if (line == null || line.styleSkuId() != i.getStyleSkuId()) throw new BizException(400, "发货明细和来源不一致");
            long done = shipped.getOrDefault(i.getOrderItemId(), 0L);
            long max = Math.max(0, shipCap(line.qty()) - done);
            if (i.getQty() > max) {
                if (bySku == null) {
                    bySku = styles.bySku(items.stream().map(FgDocItem::getStyleSkuId).toList());
                }
                over.add(new FgOverRow(i.getSeq(), FgStyles.code(bySku.get(i.getStyleSkuId()), i.getStyleSkuId()), order.no(),
                        i.getQty(), max, "订单 " + line.qty() + " 件，溢装 " + OVER_SHIP_PCT + "%，已发 " + done));
            }
        }
        if (!over.isEmpty()) {
            FgOverRow first = over.getFirst();
            throw new BizException(ErrorCodes.OVER_LIMIT, first.skuCode() + " 这次 " + first.thisQty() + " 件，最多还能发 "
                    + first.maxQty() + " 件" + (over.size() > 1 ? "，等 " + over.size() + " 个" : ""), over);
        }
    }

    // ---------- 款式、导入 ----------

    @Override
    public List<FgStyleOptionVO> styleOptions(List<Long> styleIds, long companyId) {
        FgScope.company(companyId);
        return styles.byId(FgScope.ids(styleIds, 100, "款式")).values().stream().map(FgStyleOptionVO::of).toList();
    }

    @Override
    public byte[] itemsTemplate() {
        return excel.itemsTemplate();
    }

    @Override
    public ImportResult importItems(long id, long companyId, int expectedVersion, MultipartFile file) {
        require(PERM + "edit");
        FgDoc doc = getVisible(id, companyId);
        FLOW.next("edit", doc.getStatus());
        if (doc.getDocType() != FgDocType.OTHER_IN) {
            throw new BizException("只有其他入库能从 Excel 导入明细");
        }
        return imports.execute("fg-items", "fg-doc:" + id, PERM + "edit", file, Map.of("companyId", companyId, "version", expectedVersion),
                () -> excel.readItems(file), rows -> writeImport(id, companyId, expectedVersion, rows));
    }

    /** 按 SKU 码对上（停用的也收）：单上已有的改成表里的件数，没有的加一行。有一行对不上整批不导（1003） */
    private ImportResult writeImport(long id, long companyId, int expectedVersion, ExcelImport<FgExcel.ItemRow> rows) {
        if (rows.lines().size() > 2000) throw new BizException(400, "一张成品单最多导入 2000 行");
        rows.checkUnique(r -> FgExcel.code(r.getSkuCode()), "SKU 码");
        Map<String, Long> skuIds = new HashMap<>();
        List<String> codes = rows.lines().stream().map(l -> FgExcel.code(l.data().getSkuCode())).filter(Objects::nonNull).distinct().toList();
        if (!codes.isEmpty()) {
            docMapper.skuIdsByCodes(codes).forEach(s -> skuIds.put(s.code(), s.id()));
        }
        for (ExcelImport.Line<FgExcel.ItemRow> line : rows.lines()) {
            String code = FgExcel.code(line.data().getSkuCode());
            if (code != null && !skuIds.containsKey(code)) {
                rows.error(line.row(), "SKU 码", "款式库里没有这个 SKU 码");
            }
        }
        rows.dataOrThrow();

        FgDoc doc = lockWithSource(id, companyId).doc();
        FLOW.next("edit", doc.getStatus());
        version(doc, expectedVersion);
        if (doc.getDocType() != FgDocType.OTHER_IN) throw new BizException(400, "只有其他入库能导入明细");
        List<FgDocItem> existing = readItems(id);
        Map<Long, FgDocItem> bySku = existing.stream().collect(Collectors.toMap(FgDocItem::getStyleSkuId, Function.identity()));
        long newRows = rows.lines().stream().map(l -> skuIds.get(FgExcel.code(l.data().getSkuCode())))
                .filter(sku -> !bySku.containsKey(sku)).distinct().count();
        if (existing.size() + newRows > 2000) throw new BizException(400, "导入后超过一张单 2000 行的上限");
        for (var line : rows.lines()) {
            Integer qty = line.data().getQty();
            if (qty == null || qty < 1 || qty > 99_999_999) rows.error(line.row(), "件数", "需要 1 至 99,999,999 的整数");
        }
        rows.dataOrThrow();
        int seq = existing.stream().mapToInt(FgDocItem::getSeq).max().orElse(0);
        int added = 0, changed = 0;
        List<FgDocItem> fresh = new ArrayList<>(); List<FgDocItem> updated = new ArrayList<>();
        for (ExcelImport.Line<FgExcel.ItemRow> line : rows.lines()) {
            FgExcel.ItemRow r = line.data(); long skuId = skuIds.get(FgExcel.code(r.getSkuCode()));
            FgDocItem old = bySku.get(skuId);
            if (old == null) {
                FgDocItem item = item(++seq, skuId, r.getQty(), r.getRemark()); item.setDocId(id); fresh.add(item); added++;
            } else {
                old.setQty(r.getQty()); old.setRemark(blankToNull(r.getRemark())); updated.add(old); changed++;
            }
        }
        if (!updated.isEmpty() && itemMapper.updateExisting(id, updated) != updated.size())
            throw new BizException(ErrorCodes.STATE_CHANGED, "明细已变化，请刷新后重新导入");
        if (!fresh.isEmpty()) itemMapper.insert(fresh);
        if (docMapper.update(new FgDoc(), new UpdateWrapper<FgDoc>().eq("id", id).eq("company_id", companyId)
                .eq("status", DRAFT.name()).eq("version", expectedVersion).setSql("version = version + 1")) != 1)
            throw new BizException(ErrorCodes.STATE_CHANGED, "单据已变化，请刷新后重新导入");
        if (changed > 0) {
            rows.note(0, null, changed + " 个 SKU 单上原来就有，件数改成了表里的");
        }
        docLogService.add(BizType.FG_DOC, id, "import", "加了 " + added + " 行，改了 " + changed + " 行");
        return ImportResult.of(added + changed, rows.notes());
    }

    // ---------- 给上游页面 ----------

    @Override
    @Transactional
    public List<FgShippedRow> shipped(long orderId, long companyId) {
        require("order:order:list");
        OrderShipView order = orderService.shipView(orderId, FgScope.company(companyId));
        return docMapper.shippedBySku(order.id(), companyId).stream().map(r -> new FgShippedRow(r.lineId(), r.qty())).toList();
    }

    @Override
    public List<FgReceiptStockedVO> byReceipts(Collection<Long> receiptIds, long companyId) {
        require(PERM + "list");
        List<Long> ids = FgScope.ids(receiptIds, 100, "回货单");
        FgScope.company(companyId);
        // 来源任一不可见/失效整批 404；该公共方法在 SQL 中汇总，不把所有来源明细装入 JVM。
        Map<Long, Long> good = receiptService.stockSourceQuantities(ids, companyId);
        var occupied = docMapper.selectReceiptOccupancy(ids, companyId).stream()
                .collect(Collectors.toMap(FgDocMapper.ReceiptOccupancy::receiptId, Function.identity()));
        var counts = docMapper.selectReceiptDocumentCounts(ids, DataScope.current(false), companyId).stream()
                .collect(Collectors.toMap(FgDocMapper.ReceiptDocumentCount::receiptId, FgDocMapper.ReceiptDocumentCount::total));
        var previews = docMapper.selectReceiptDocumentPreviews(ids, DataScope.current(false), companyId).stream()
                .collect(Collectors.groupingBy(FgDocMapper.ReceiptDocument::receiptId));
        return ids.stream().map(id -> {
            var o = occupied.get(id);
            long reserved = o == null ? 0 : o.reservedQty();
            long posted = o == null ? 0 : o.postedQty();
            List<FgReceiptStockedVO.Document> docs = previews.getOrDefault(id, List.of()).stream()
                    .map(d -> new FgReceiptStockedVO.Document(d.docId(), d.no(), d.status(), d.qty())).toList();
            long total = counts.getOrDefault(id, 0L);
            return new FgReceiptStockedVO(id, docs, reserved, posted, Math.max(0L, good.get(id) - reserved - posted),
                    total, total > docs.size());
        }).toList();
    }

    // ---------- 导出 ----------

    @Override
    public byte[] export(FgDocQuery query) {
        BusinessDateRange.validate(query.getDocDateFrom(), query.getDocDateTo());
        Page<FgDocRowVO> all = new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false);
        List<FgDocRowVO> docs = docMapper.selectRowPage(all, query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()), FgScope.company(query.getCompanyId())).getRecords();
        ExportScope.check(query.getIds(), docs, FgDocRowVO::getId, FgDocRowVO::getCompanyId);
        BoundedExportRows.requireWithinLimit(docs.size(), Excel.MAX_EXPORT_ROWS, "成品单据");
        Map<Long, List<FgDocItem>> items = BoundedExportRows.read(docs.stream().map(FgDocRowVO::getId).toList(),
                1000, Excel.MAX_EXPORT_ROWS, "成品单据明细", (ids, limit) -> itemMapper.selectList(new LambdaQueryWrapper<FgDocItem>()
                        .in(FgDocItem::getDocId, ids).orderByAsc(FgDocItem::getDocId, FgDocItem::getSeq, FgDocItem::getId)
                        .last("LIMIT " + limit))).stream().collect(Collectors.groupingBy(FgDocItem::getDocId));

        Set<Long> skuIds = new HashSet<>();
        items.values().forEach(list -> list.forEach(i -> skuIds.add(i.getStyleSkuId())));
        Set<Long> userIds = docs.stream().map(FgDocRowVO::getCreateBy).filter(Objects::nonNull).collect(Collectors.toSet());
        return excel.exportDocs(query.getKind(), docs, items, styles.bySku(skuIds), userService.nicknames(userIds));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
