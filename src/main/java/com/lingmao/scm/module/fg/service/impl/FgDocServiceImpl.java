package com.lingmao.scm.module.fg.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * 成品入库单、成品出库单（RFC 008、docs/api/07）。一张表两种单据，照物料入库单、出库单：审核即过账，比较并交换把草稿改成已审核
 * （顺带锁住这张单），然后 FgLedger 改库存、记日志，一个事务；反审核把变动反过来做一遍。过过账的单再回到草稿后不能删、只能作废。
 * <p>
 * 和上游的约定（RFC 008 §6.2）：回货入库建单、改草稿、审核都先 {@link ProductionReceiptService#forStock} 锁住回货单，
 * 再按最新的已入量算还没入的良品（一张回货单能分几次入，草稿也算已入）；发货出库先 {@link OrderService#shipView} 共享锁住订单，
 * 审核时再按订单加咨询锁、重算已发（只算已审核的），两张发货单同时审核加起来也超不了 ⌊订单数 × 1.03⌋
 */
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
        IPage<FgDocRowVO> page = docMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()));
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
            r.setActions(actions(DocStatus.valueOf(r.getStatus()), posted.contains(r.getId())));
        }
    }

    /** 按钮：没过过账的草稿给"删除"，过过账的给"作废"，都要 fg:doc:remove */
    static List<String> actions(DocStatus status, boolean posted) {
        return DocActions.of(FLOW, status, PERM)
                .perm("edit", PERM + "edit")
                .perm("remove", PERM + "remove")
                .perm("void", PERM + "remove")
                .when("remove", !posted)
                .when("void", posted)
                .list();
    }

    @Override
    public FgDocDetailVO detail(long id) {
        FgDoc doc = getVisible(id);
        List<FgDocItem> items = itemMapper.selectByDoc(id);
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

        Map<Long, Integer> stock = draft && doc.getKind() == FgDocKind.OUT ? stockOf(doc.warehouseId(), skuIds) : Map.of();
        Map<Long, Integer> max = draft ? draftMax(doc) : Map.of();
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
        vo.setStyles(styleOptions(items.stream().map(i -> bySku.get(i.getStyleSkuId())).filter(Objects::nonNull)
                .map(StyleOrderInfo::id).toList(), bySku.values()));
        vo.setTotalQty(items.stream().mapToInt(FgDocItem::getQty).sum());
        vo.setTotalAmount(doc.getDocType() == FgDocType.SHIP_OUT ? items.stream().map(FgDocItem::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null);
        boolean posted = logMapper.docPosted(id);
        vo.setPosted(posted);
        vo.setActions(actions(doc.getStatus(), posted));
        return vo;
    }

    /**
     * 草稿每个 SKU 最多能填多少（只显示，不锁）：回货入库 = 良品 − 别的单已入；发货出库 = ⌊订单数 × 1.03⌋ − 别的单已发。
     * 回货单、订单行只读 JOIN 取（看得到这张单的人不一定看得到生产单、订单）
     */
    private Map<Long, Integer> draftMax(FgDoc doc) {
        Map<Long, Integer> out = new HashMap<>();
        if (doc.getDocType() == FgDocType.RECEIPT_IN) {
            Map<Long, Integer> stocked = lineQty(docMapper.stockedByReceipt(doc.getPrdReceiptId(), doc.getId()));
            for (FgDocMapper.ReceiptLine l : docMapper.receiptLines(doc.getPrdReceiptId())) {
                out.merge(l.styleSkuId(), Math.max(0, l.qty() - l.defectQty() - stocked.getOrDefault(l.id(), 0)), Integer::sum);
            }
        } else if (doc.getDocType() == FgDocType.SHIP_OUT) {
            Map<Long, Integer> shipped = lineQty(docMapper.shippedByOrder(doc.getOrderId(), doc.getId()));
            for (FgDocMapper.OrderLine l : docMapper.orderLines(doc.getOrderId())) {
                out.merge(l.styleSkuId(), Math.max(0, shipCap(l.qty()) - shipped.getOrDefault(l.id(), 0)), Integer::sum);
            }
        }
        return out;
    }

    private Map<Long, Integer> stockOf(long warehouseId, Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return stockMapper.selectBySkus(warehouseId, skuIds).stream().collect(Collectors.toMap(FgStock::getStyleSkuId, FgStock::getQty));
    }

    private static Map<Long, Integer> lineQty(List<FgDocMapper.LineQty> rows) {
        return rows.stream().collect(Collectors.toMap(FgDocMapper.LineQty::lineId, FgDocMapper.LineQty::qty));
    }

    /** ⌊订单数 × 1.03⌋（整数算，不用浮点） */
    static int shipCap(int orderQty) {
        return Math.toIntExact((long) orderQty * (100 + OVER_SHIP_PCT) / 100);
    }

    /** 画矩阵的款式，按 styleIds 的顺序去重；infos 里没有的现取 */
    private List<FgStyleOptionVO> styleOptions(List<Long> styleIds, Collection<StyleOrderInfo> infos) {
        Map<Long, StyleOrderInfo> known = new HashMap<>();
        infos.forEach(s -> known.putIfAbsent(s.id(), s));
        List<Long> missing = styleIds.stream().filter(id -> !known.containsKey(id)).distinct().toList();
        known.putAll(styles.byId(missing));
        return new LinkedHashSet<>(styleIds).stream().map(known::get).filter(Objects::nonNull).map(FgStyleOptionVO::of).toList();
    }

    @Override
    public void checkVisible(long id) {
        getVisible(id);
    }

    private FgDoc getVisible(long id) {
        return visible(docMapper.selectById(id));
    }

    /** 改、删之前锁住单头：和审核、反审核排队 */
    private FgDoc lock(long id) {
        return visible(docMapper.lockById(id));
    }

    /** 数据范围：本人范围的人只看制单人是自己的单；成品单据不分品类线 */
    private static FgDoc visible(FgDoc doc) {
        if (doc == null) {
            throw new BizException(404, NOT_FOUND);
        }
        DataScope.current(false).check(doc.getCreateBy());
        CompanyContext.target(doc.getCompanyId());
        return doc;
    }

    // ---------- 手工新建、改、删 ----------

    @Override
    @Transactional
    public FgDocCreatedVO create(FgDocCreateReq req) {
        CompanyContext.create(req.getCompanyId());
        FgDocType type = req.getDocType();
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
            OrderShipView order = shippableOrder(req.getOrderId());
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
        FgDoc doc = lock(id);
        FLOW.next("edit", doc.getStatus());
        if (!doc.getVersion().equals(req.getVersion())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单已经被别人改过，请刷新后再改");
        }
        applyForm(doc, req);
        checkHeader(doc);
        List<FgDocItem> items = switch (doc.getDocType()) {
            case RECEIPT_IN -> receiptItems(receiptService.forStock(doc.getPrdReceiptId()), bySkuReq(req.getItems()), id);
            case SHIP_OUT -> shipItems(shippableOrder(doc.getOrderId()), req.getItems(), id);
            default -> manualItems(req.getItems());
        };
        UpdateWrapper<FgDoc> update = Updates.replaceById(doc)
                .eq("status", DRAFT.name())
                .eq("version", req.getVersion())
                .setSql("version = version + 1");
        if (docMapper.update(doc, update) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单已经被别人改过或者审核了，请刷新后再改");
        }
        // 日志不指向明细（RFC 008 §2），整单删了重插
        itemMapper.deleteByDoc(id);
        items.forEach(i -> i.setDocId(id));
        itemMapper.insert(items);
        docLogService.add(BizType.FG_DOC, id, "update", null);
        return req.getVersion() + 1;
    }

    @Override
    @Transactional
    public void delete(long id) {
        FgDoc doc = lock(id);
        FLOW.next("remove", doc.getStatus());
        if (logMapper.docPosted(id)) {
            throw new BizException("这张单过过账（库存日志里有它），不能删，只能作废");
        }
        itemMapper.deleteByDoc(id);
        attachmentService.deleteByDoc(BizType.FG_DOC, id);
        docLogService.deleteByDoc(BizType.FG_DOC, id);
        docMapper.deleteById(id);
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
        Set<Long> seen = new HashSet<>();
        for (int n = 0; n < reqs.size(); n++) {
            if (!seen.add(reqs.get(n).getStyleSkuId())) {
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
    public void act(long id, String action, DocActionReq req) {
        FgDoc doc = getVisible(id);
        switch (action) {
            case "audit" -> audit(doc, req.version(), req.reason());
            case "unaudit" -> unaudit(doc, req);
            case "void" -> voidDoc(doc, req);
            default -> throw new BizException(404, "成品单据没有动作 " + action);
        }
    }

    /** 审核过账：比较并交换（锁住这张单）→ 回货单、订单按最新的数再查一遍上限 → 改库存、记日志 */
    private void audit(FgDoc doc, int version, String remark) {
        FLOW.next("audit", doc.getStatus());
        StatusCas.update(docMapper, new FgDoc(), StatusCas.of(FgDoc::getId, doc.getId(), FgDoc::getStatus, DRAFT, APPROVED)
                .set(FgDoc::getAuditBy, LoginUser.currentUserId())
                .set(FgDoc::getAuditTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(FgDoc::getVersion, version));
        List<FgDocItem> items = itemMapper.selectByDoc(doc.getId());
        if (doc.getDocType() == FgDocType.RECEIPT_IN) {
            ReceiptForStock receipt = receiptService.forStock(doc.getPrdReceiptId());
            checkReceiptLimits(receipt, items, doc.getId());
        } else if (doc.getDocType() == FgDocType.SHIP_OUT) {
            docMapper.lockShip(doc.getOrderId());
            OrderShipView order = shippableOrder(doc.getOrderId());
            checkShipLimits(order, items, doc.getId());
        }
        ledger.post(posting(doc, items));
        docLogService.add(BizType.FG_DOC, doc.getId(), "audit", DRAFT, APPROVED, remark);
    }

    /** 反审核：回到草稿，库存反过来做一遍（入的货已经发走了扣不回来，1001） */
    private void unaudit(FgDoc doc, DocActionReq req) {
        FLOW.next("unaudit", doc.getStatus());
        StatusCas.update(docMapper, new FgDoc(), StatusCas.of(FgDoc::getId, doc.getId(), FgDoc::getStatus, APPROVED, DRAFT)
                .set(FgDoc::getAuditBy, null)
                .set(FgDoc::getAuditTime, null)
                .setSql("version = version + 1")
                .eq(FgDoc::getVersion, req.version()));
        ledger.post(posting(doc, itemMapper.selectByDoc(doc.getId())).reversed());
        docLogService.add(BizType.FG_DOC, doc.getId(), "unaudit", APPROVED, DRAFT, req.reason());
    }

    /** 作废：只给过过账又反审核回来的草稿（没过过账的直接删）。作废的回货入库单不算已入 */
    private void voidDoc(FgDoc doc, DocActionReq req) {
        FLOW.next("void", doc.getStatus());
        String reason = blankToNull(req.reason());
        if (reason == null) {
            throw new BizException(400, "请写作废原因");
        }
        if (!logMapper.docPosted(doc.getId())) {
            throw new BizException("这张单没过过账，直接删除就行");
        }
        StatusCas.update(docMapper, new FgDoc(), StatusCas.of(FgDoc::getId, doc.getId(), FgDoc::getStatus, DRAFT, VOIDED)
                .setSql("version = version + 1")
                .eq(FgDoc::getVersion, req.version()));
        docLogService.add(BizType.FG_DOC, doc.getId(), "void", DRAFT, VOIDED, reason);
    }

    /** 一张单的库存变动：入库单入仓加，出库单出仓减，日志类型是单据类型 */
    static FgLedger.Posting posting(FgDoc doc, List<FgDocItem> items) {
        int sign = doc.getKind() == FgDocKind.IN ? 1 : -1;
        List<FgLedger.Move> moves = items.stream()
                .map(i -> new FgLedger.Move(doc.warehouseId(), i.getStyleSkuId(), sign * i.getQty(), i.getSeq()))
                .toList();
        return new FgLedger.Posting(doc.getId(), doc.getNo(), doc.getDocType(), doc.getDocDate(), moves);
    }

    // ---------- 回货入库 ----------

    @Override
    public PageResult<FgPendingReceiptRowVO> pendingReceipts(FgPendingReceiptQuery query) {
        IPage<FgPendingReceiptRowVO> page = docMapper.selectPendingPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                LineScope.current());
        Map<Long, StyleOrderInfo> infos = styles.byId(page.getRecords().stream().map(FgPendingReceiptRowVO::getStyleId).toList());
        page.getRecords().forEach(r -> {
            StyleOrderInfo s = infos.get(r.getStyleId());
            r.setImage(s == null ? null : s.image());
        });
        return PageResult.of(page);
    }

    @Override
    public long pendingReceiptCount() {
        return docMapper.countPending(LineScope.current());
    }

    @Override
    public FgReceiptDraftVO receiptDraft(long receiptId) {
        ReceiptForStock r = receiptService.forStock(receiptId);
        Map<Long, Integer> remain = remaining(r, null);
        if (remain.values().stream().allMatch(q -> q <= 0)) {
            throw allStocked(r);
        }
        Map<Long, Integer> stocked = lineQty(docMapper.stockedByReceipt(receiptId, null));
        WarehouseOption w = defaultWarehouse(r.warehouseId());
        StyleOrderInfo style = styles.byId(List.of(r.styleId())).get(r.styleId());
        FgReceiptDraftVO vo = new FgReceiptDraftVO();
        vo.setCompanyId(r.companyId());
        vo.setReceiptId(r.id());
        vo.setReceiptNo(r.no());
        vo.setPrdOrderNo(r.prdOrderNo());
        vo.setPrdOrderKind(r.prdOrderKind());
        vo.setFactoryName(docMapper.factoryName(r.factoryId()));
        vo.setReceiptDate(r.receiptDate());
        vo.setWarehouseId(w.id());
        vo.setWarehouseName(w.name());
        vo.setStyle(style == null ? null : FgStyleOptionVO.of(style));
        vo.setItems(r.items().stream().filter(i -> remain.get(i.id()) > 0)
                .map(i -> new FgReceiptDraftVO.Item(i.id(), i.styleSkuId(), i.qty(), i.defectQty(), i.goodQty(),
                        stocked.getOrDefault(i.id(), 0), remain.get(i.id())))
                .toList());
        return vo;
    }

    /** 回货单每行还没入的良品：良品 − 没作废的成品入库单已入（草稿也算），不算 excludeDocId */
    private Map<Long, Integer> remaining(ReceiptForStock r, Long excludeDocId) {
        Map<Long, Integer> stocked = lineQty(docMapper.stockedByReceipt(r.id(), excludeDocId));
        Map<Long, Integer> out = new LinkedHashMap<>();
        r.items().forEach(i -> out.put(i.id(), i.goodQty() - stocked.getOrDefault(i.id(), 0)));
        return out;
    }

    private BizException allStocked(ReceiptForStock r) {
        String nos = docMapper.selectByReceipt(r.id()).stream().map(FgDoc::getNo).collect(Collectors.joining("、"));
        return new BizException("回货单 " + r.no() + " 的良品都入过库了" + (nos.isEmpty() ? "" : "：" + nos));
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
        return actionRequestService.execute("FG_FROM_RECEIPT", receiptId, req.getClientActionId(), req, FgDocCreatedVO.class,
                () -> createFromReceipt(receiptId, req));
    }

    private FgDocCreatedVO createFromReceipt(long receiptId, FgFromReceiptReq req) {
        ReceiptForStock r = receiptService.forStock(receiptId);
        Map<Long, Integer> remain = remaining(r, null);
        if (remain.values().stream().allMatch(q -> q <= 0)) {
            throw allStocked(r);
        }
        Map<Long, ReceiptForStock.Item> lines = r.items().stream()
                .collect(Collectors.toMap(ReceiptForStock.Item::id, Function.identity()));
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        if (req.getItems() == null) {
            remain.forEach((lineId, q) -> {
                if (q > 0) {
                    wanted.put(lines.get(lineId).styleSkuId(), q);
                }
            });
        } else {
            for (FgFromReceiptReq.Item i : req.getItems()) {
                ReceiptForStock.Item line = lines.get(i.getPrdReceiptItemId());
                if (line == null) {
                    throw new BizException(400, "有一行不是回货单 " + r.no() + " 上的，请刷新后重新入库");
                }
                if (i.getQty() > 0 && wanted.put(line.styleSkuId(), i.getQty()) != null) {
                    throw new BizException(400, "回货单 " + r.no() + " 的同一行填了两次，请刷新后重新入库");
                }
            }
        }
        if (wanted.isEmpty()) {
            throw new BizException(400, "没有要入库的数");
        }
        FgDoc doc = new FgDoc();
        doc.setKind(FgDocKind.IN);
        doc.setDocType(FgDocType.RECEIPT_IN);
        doc.setStatus(DRAFT);
        doc.setCompanyId(r.companyId());
        doc.setPrdReceiptId(r.id());
        doc.setToWarehouseId(req.getWarehouseId() == null ? defaultWarehouse(r.warehouseId()).id() : req.getWarehouseId());
        doc.setDocDate(req.getDocDate() == null ? LocalDate.now(clock) : req.getDocDate());
        doc.setRemark(blankToNull(req.getRemark()));
        checkHeader(doc);
        List<FgDocItem> items = receiptItems(r, wanted.entrySet().stream()
                .map(e -> itemReq(e.getKey(), e.getValue())).toList(), null);
        insert(doc, items);
        docLogService.add(BizType.FG_DOC, doc.getId(), "create", "由回货单 " + r.no() + " 生成");
        if (req.isPost()) {
            audit(doc, 0, null);
            return new FgDocCreatedVO(doc.getId(), doc.getNo(), 1, APPROVED.name());
        }
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
        Map<Long, Integer> remain = remaining(r, excludeDocId);
        Map<Long, Integer> stocked = lineQty(docMapper.stockedByReceipt(r.id(), excludeDocId));
        Map<Long, ReceiptForStock.Item> lines = r.items().stream()
                .collect(Collectors.toMap(ReceiptForStock.Item::id, Function.identity()));
        List<FgOverRow> over = new ArrayList<>();
        Map<Long, StyleOrderInfo> bySku = null;
        for (FgDocItem i : items) {
            int max = Math.max(0, remain.getOrDefault(i.getPrdReceiptItemId(), 0));
            if (i.getQty() > max) {
                if (bySku == null) {
                    bySku = styles.bySku(items.stream().map(FgDocItem::getStyleSkuId).toList());
                }
                ReceiptForStock.Item line = lines.get(i.getPrdReceiptItemId());
                over.add(new FgOverRow(i.getSeq(), FgStyles.code(bySku.get(i.getStyleSkuId()), i.getStyleSkuId()), r.no(),
                        i.getQty(), max, "良品 " + line.goodQty() + "，已入 " + stocked.getOrDefault(line.id(), 0)));
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
    public List<FgShipOrderOptionVO> shipOrders(String keyword, Long warehouseId) {
        List<FgShipOrderOptionVO> rows = docMapper.selectShipOrders(keyword, Keywords.codes(keyword), warehouseId, LineScope.current());
        rows.forEach(r -> r.setStatusLabel(ORDER_STATUS.get(DocStatus.valueOf(r.getStatus()))));
        return rows;
    }

    @Override
    public FgShipPrefillVO shipPrefill(long orderId, Long warehouseId, Long docId) {
        OrderShipView order = shippableOrder(orderId);
        if (warehouseId != null) {
            productWarehouse(warehouseId);
        }
        Map<Long, Integer> shipped = lineQty(docMapper.shippedByOrder(orderId, docId));
        List<OrderShipView.Line> lines = order.lines().stream().filter(l -> l.qty() > 0).toList();
        Map<Long, Integer> stock = warehouseId == null ? Map.of()
                : stockOf(warehouseId, lines.stream().map(OrderShipView.Line::styleSkuId).toList());
        FgShipPrefillVO vo = new FgShipPrefillVO();
        vo.setOrderId(order.id());
        vo.setOrderNo(order.no());
        vo.setCompanyId(order.companyId());
        vo.setDeliveryReq(order.deliveryReq());
        vo.setStyles(styleOptions(lines.stream().map(OrderShipView.Line::styleId).toList(), List.of()));
        vo.setLines(lines.stream().map(l -> {
            int done = shipped.getOrDefault(l.orderItemId(), 0);
            return new FgShipPrefillVO.Line(l.orderItemId(), l.styleSkuId(), l.qty(), done, Math.max(0, shipCap(l.qty()) - done),
                    stock.getOrDefault(l.styleSkuId(), 0), l.price());
        }).toList());
        return vo;
    }

    /** 订单要是已审核、生产中、已完成、已结案的（1000）。shipView 共享锁住订单，和取消互斥 */
    private OrderShipView shippableOrder(long orderId) {
        OrderShipView order = orderService.shipView(orderId);
        if (!SHIPPABLE.contains(order.status())) {
            throw new BizException("需求订单 " + order.no() + " 不能发货：已审核、生产中、已完成、已结案的才能发");
        }
        return order;
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
            item.setPrice(line.price());
            item.setAmount(line.price() == null ? null : line.price().multiply(BigDecimal.valueOf(q.getQty())).setScale(2, RoundingMode.HALF_UP));
            items.add(item);
        }
        checkShipLimits(order, items, excludeDocId);
        return items;
    }

    private void checkShipLimits(OrderShipView order, List<FgDocItem> items, Long excludeDocId) {
        Map<Long, Integer> shipped = lineQty(docMapper.shippedByOrder(order.id(), excludeDocId));
        Map<Long, OrderShipView.Line> lines = order.lines().stream()
                .collect(Collectors.toMap(OrderShipView.Line::orderItemId, Function.identity()));
        List<FgOverRow> over = new ArrayList<>();
        Map<Long, StyleOrderInfo> bySku = null;
        for (FgDocItem i : items) {
            OrderShipView.Line line = lines.get(i.getOrderItemId());
            int done = shipped.getOrDefault(i.getOrderItemId(), 0);
            int max = Math.max(0, shipCap(line.qty()) - done);
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
    public List<FgStyleOptionVO> styleOptions(List<Long> styleIds) {
        if (styleIds.size() > 100) {
            throw new BizException(400, "一次最多取 100 个款");
        }
        return styleOptions(styleIds, List.of());
    }

    @Override
    public byte[] itemsTemplate() {
        return excel.itemsTemplate();
    }

    @Override
    public ImportResult importItems(long id, MultipartFile file) {
        FgDoc doc = getVisible(id);
        FLOW.next("edit", doc.getStatus());
        if (doc.getDocType() != FgDocType.OTHER_IN) {
            throw new BizException("只有其他入库能从 Excel 导入明细");
        }
        return imports.execute("fg-items", "fg-doc:" + id, PERM + "edit", file, Map.of("companyId", doc.getCompanyId()),
                () -> excel.readItems(file), rows -> writeImport(id, rows));
    }

    /** 按 SKU 码对上（停用的也收）：单上已有的改成表里的件数，没有的加一行。有一行对不上整批不导（1003） */
    private ImportResult writeImport(long id, ExcelImport<FgExcel.ItemRow> rows) {
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

        FgDoc doc = lock(id);
        FLOW.next("edit", doc.getStatus());
        List<FgDocItem> existing = itemMapper.selectByDoc(id);
        Map<Long, FgDocItem> bySku = existing.stream().collect(Collectors.toMap(FgDocItem::getStyleSkuId, Function.identity()));
        int seq = existing.stream().mapToInt(FgDocItem::getSeq).max().orElse(0);
        int added = 0;
        int changed = 0;
        for (ExcelImport.Line<FgExcel.ItemRow> line : rows.lines()) {
            FgExcel.ItemRow r = line.data();
            long skuId = skuIds.get(FgExcel.code(r.getSkuCode()));
            FgDocItem old = bySku.get(skuId);
            if (old == null) {
                FgDocItem item = item(++seq, skuId, r.getQty(), r.getRemark());
                item.setDocId(id);
                itemMapper.insert(item);
                bySku.put(skuId, item);
                added++;
            } else {
                old.setQty(r.getQty());
                old.setRemark(blankToNull(r.getRemark()));
                itemMapper.updateById(old);
                changed++;
            }
        }
        docMapper.update(new FgDoc(), new UpdateWrapper<FgDoc>().eq("id", id).eq("status", DRAFT.name())
                .setSql("version = version + 1"));
        if (changed > 0) {
            rows.note(0, null, changed + " 个 SKU 单上原来就有，件数改成了表里的");
        }
        docLogService.add(BizType.FG_DOC, id, "import", "加了 " + added + " 行，改了 " + changed + " 行");
        return ImportResult.of(added + changed, rows.notes());
    }

    // ---------- 给上游页面 ----------

    @Override
    public List<FgShippedRow> shipped(long orderId) {
        orderService.checkVisible(orderId);
        return docMapper.shippedBySku(orderId).stream().map(r -> new FgShippedRow(r.lineId(), r.qty())).toList();
    }

    @Override
    public List<FgReceiptStockedVO> byReceipts(Collection<Long> receiptIds) {
        if (receiptIds.isEmpty()) {
            return List.of();
        }
        if (receiptIds.size() > 200) {
            throw new BizException(400, "一次最多查 200 张回货单");
        }
        return docMapper.selectByReceipts(receiptIds).stream()
                .map(d -> new FgReceiptStockedVO(d.getPrdReceiptId(), d.getId(), d.getNo(), d.getStatus().name())).toList();
    }

    // ---------- 导出 ----------

    @Override
    public byte[] export(FgDocQuery query) {
        Page<FgDocRowVO> all = new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false);
        List<FgDocRowVO> docs = docMapper.selectRowPage(all, query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine())).getRecords();
        ExportScope.check(query.getIds(), docs, FgDocRowVO::getId, FgDocRowVO::getCompanyId);
        Map<Long, List<FgDocItem>> items = docs.isEmpty() ? Map.of()
                : itemMapper.selectByDocs(docs.stream().map(FgDocRowVO::getId).toList()).stream()
                .collect(Collectors.groupingBy(FgDocItem::getDocId));
        Set<Long> skuIds = new HashSet<>();
        items.values().forEach(list -> list.forEach(i -> skuIds.add(i.getStyleSkuId())));
        Set<Long> userIds = docs.stream().map(FgDocRowVO::getCreateBy).filter(Objects::nonNull).collect(Collectors.toSet());
        return excel.exportDocs(query.getKind(), docs, items, styles.bySku(skuIds), userService.nicknames(userIds));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
