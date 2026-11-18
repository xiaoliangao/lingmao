package com.lingmao.scm.module.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.ActionRequestService;
import com.lingmao.scm.module.biz.service.DocActions;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.inventory.dto.InvDocCreateReq;
import com.lingmao.scm.module.inventory.dto.InvDocCreatedVO;
import com.lingmao.scm.module.inventory.dto.InvDocDetailVO;
import com.lingmao.scm.module.inventory.dto.InvDocDraftVO;
import com.lingmao.scm.module.inventory.dto.InvDocForm;
import com.lingmao.scm.module.inventory.dto.InvDocFromSourceReq;
import com.lingmao.scm.module.inventory.dto.InvDocItemReq;
import com.lingmao.scm.module.inventory.dto.InvDocItemVO;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.dto.InvDocRowVO;
import com.lingmao.scm.module.inventory.dto.InvDocSaveReq;
import com.lingmao.scm.module.inventory.dto.SourceDraft;
import com.lingmao.scm.module.inventory.dto.SourcePosting;
import com.lingmao.scm.module.inventory.entity.InvDoc;
import com.lingmao.scm.module.inventory.entity.InvDocItem;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.entity.Stock;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import com.lingmao.scm.module.inventory.mapper.InvDocItemMapper;
import com.lingmao.scm.module.inventory.mapper.InvDocMapper;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.inventory.service.StockSourceHandler;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/**
 * 物料入库单、物料出库单（docs/api/04 §3.2、§3.3）。一张表两种单据（kind），类型决定出仓、入仓、往来、源单（InvDocType）。
 * <p>
 * 审核即过账：比较并交换把草稿改成已审核（顺带锁住这张单），然后 StockLedger 改库存、记日志，源单那边回写，都在一个事务里。
 * 反审核把变动反过来做一遍、日志再记一条反方向的。过过账（有库存日志）的单再回到草稿后不能删、只能作废（用户 11-17 定）：
 * 日志指着它和它的明细，删了就对不上账
 */
@Service
public class InvDocServiceImpl implements InvDocService {

    /** 状态转换表（docs/api/04 §2.1）。remove、void 都从草稿来，过没过过账决定给哪个（actions 里只出一个） */
    static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("audit", DRAFT).to(APPROVED)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("remove", DRAFT).to(DRAFT)
            .on("void", DRAFT).to(VOIDED)
            .build();

    static final String PERM = "inventory:doc:";

    private final InvDocMapper invDocMapper;
    private final InvDocItemMapper itemMapper;
    private final StockLogMapper stockLogMapper;
    private final StockMapper stockMapper;
    private final StockLedger ledger;
    private final InvDocExcel excel;
    private final MaterialService materialService;
    private final WarehouseService warehouseService;
    private final CompanyService companyService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final AttachmentService attachmentService;
    private final ReferenceService referenceService;
    private final SerialNoGenerator serialNoGenerator;
    private final ActionRequestService actionRequestService;
    private final Clock clock;
    private final Map<BizType, StockSourceHandler> handlers = new EnumMap<>(BizType.class);

    public InvDocServiceImpl(InvDocMapper invDocMapper, InvDocItemMapper itemMapper, StockLogMapper stockLogMapper,
                             StockMapper stockMapper, StockLedger ledger, InvDocExcel excel, MaterialService materialService,
                             WarehouseService warehouseService, CompanyService companyService, DictDataService dictDataService,
                             UserService userService, DocHeaderService docHeaderService, DocLogService docLogService,
                             AttachmentService attachmentService, ReferenceService referenceService,
                             SerialNoGenerator serialNoGenerator, ActionRequestService actionRequestService,
                             Clock clock, List<StockSourceHandler> sourceHandlers) {
        this.invDocMapper = invDocMapper;
        this.itemMapper = itemMapper;
        this.stockLogMapper = stockLogMapper;
        this.stockMapper = stockMapper;
        this.ledger = ledger;
        this.excel = excel;
        this.materialService = materialService;
        this.warehouseService = warehouseService;
        this.companyService = companyService;
        this.dictDataService = dictDataService;
        this.userService = userService;
        this.docHeaderService = docHeaderService;
        this.docLogService = docLogService;
        this.attachmentService = attachmentService;
        this.referenceService = referenceService;
        this.serialNoGenerator = serialNoGenerator;
        this.actionRequestService = actionRequestService;
        this.clock = clock;
        for (StockSourceHandler h : sourceHandlers) {
            if (handlers.put(h.sourceType(), h) != null) {
                throw new IllegalStateException(h.sourceType() + " 有两个 StockSourceHandler");
            }
        }
    }

    // ---------- 查询 ----------

    @Override
    public PageResult<InvDocRowVO> page(InvDocQuery query) {
        IPage<InvDocRowVO> page = invDocMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()));
        fillRows(page.getRecords());
        return PageResult.of(page);
    }

    private void fillRows(List<InvDocRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(rows.stream().map(InvDocRowVO::getFirstSkuId).toList());
        Map<Long, String> names = userService.nicknames(rows.stream().map(InvDocRowVO::getCreateBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        List<Long> drafts = rows.stream().filter(r -> DRAFT.name().equals(r.getStatus())).map(InvDocRowVO::getId).toList();
        Set<Long> posted = drafts.isEmpty() ? Set.of() : stockLogMapper.postedDocs(drafts);
        for (InvDocRowVO r : rows) {
            r.setFirstItem(briefs.get(r.getFirstSkuId()));
            r.setCreateByName(r.getCreateBy() == null ? null : names.get(r.getCreateBy()));
            r.setActions(actions(DocStatus.valueOf(r.getStatus()), posted.contains(r.getId())));
        }
    }

    /** 按钮（docs/api/04 §2.1）：没过过账的草稿给"删除"，过过账的给"作废"，都要 inventory:doc:remove */
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
    public InvDocDetailVO detail(long id) {
        InvDoc doc = getVisible(id);
        List<InvDocItem> items = itemMapper.selectByDoc(id);
        Set<Long> postedItems = stockLogMapper.postedDocItems(id);
        List<Long> skuIds = items.stream().map(InvDocItem::getMaterialSkuId).toList();
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(skuIds);
        Map<Long, BigDecimal> fromStock = doc.getStatus() == DRAFT && doc.getFromWarehouseId() != null
                ? stockOf(doc.getFromWarehouseId(), skuIds) : Map.of();

        InvDocDetailVO vo = docHeaderService.fill(new InvDocDetailVO(), BizType.INV_DOC, doc, doc.getStatus());
        vo.setCompanyName(companyService.getById(doc.getCompanyId()).getShortName());
        vo.setKind(doc.getKind());
        vo.setDocType(doc.getDocType());
        vo.setSource(sourceRef(doc));
        if (doc.getFromWarehouseId() != null) {
            WarehouseVO w = warehouseService.getById(doc.getFromWarehouseId());
            vo.setFromWarehouseId(w.getId());
            vo.setFromWarehouseName(w.getName());
            vo.setFromWarehouseType(w.getType());
        }
        if (doc.getToWarehouseId() != null) {
            WarehouseVO w = warehouseService.getById(doc.getToWarehouseId());
            vo.setToWarehouseId(w.getId());
            vo.setToWarehouseName(w.getName());
            vo.setToWarehouseType(w.getType());
        }
        vo.setPartnerId(doc.getPartnerId());
        vo.setPartnerName(doc.getPartnerId() == null ? null : invDocMapper.partnerName(doc.getPartnerId()));
        vo.setDocDate(doc.getDocDate());
        vo.setReason(doc.getReason());
        vo.setSupplierDeliveryNo(doc.getSupplierDeliveryNo());
        vo.setLogisticsNo(doc.getLogisticsNo());
        vo.setItems(items.stream().map(i -> {
            InvDocItemVO v = new InvDocItemVO();
            v.fillFrom(briefs.get(i.getMaterialSkuId()));
            v.setId(i.getId());
            v.setSeq(i.getSeq());
            v.setQty(i.getQty());
            v.setPurchaseUnit(i.getPurchaseUnit());
            v.setPurchaseQty(i.getPurchaseQty());
            v.setConversionFactor(i.getConversionFactor());
            v.setPrice(i.getPrice());
            v.setAmount(i.getAmount());
            v.setDyeLot(i.getDyeLot());
            v.setRolls(i.getRolls());
            v.setSourceItemId(i.getSourceItemId());
            v.setOverReason(i.getOverReason());
            v.setRemark(i.getRemark());
            v.setFromStockQty(doc.getStatus() == DRAFT && doc.getFromWarehouseId() != null
                    ? fromStock.getOrDefault(i.getMaterialSkuId(), BigDecimal.ZERO) : null);
            v.setPosted(postedItems.contains(i.getId()));
            return v;
        }).toList());
        vo.setTotalQty(items.stream().map(InvDocItem::getQty).reduce(BigDecimal.ZERO, BigDecimal::add));
        vo.setTotalAmount(items.stream().map(InvDocItem::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        boolean posted = !postedItems.isEmpty();
        vo.setPosted(posted);
        vo.setActions(actions(doc.getStatus(), posted));
        return vo;
    }

    private Map<Long, BigDecimal> stockOf(long warehouseId, Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return stockMapper.selectBySkus(warehouseId, skuIds).stream()
                .collect(Collectors.toMap(Stock::getMaterialSkuId, Stock::getQty));
    }

    /** 源单的 DocRef：由源单那边的 ReferrerProvider 描述；那边还没上线时只有单号 */
    private DocRef sourceRef(InvDoc doc) {
        if (doc.getSourceType() == null) {
            return null;
        }
        BizType type = BizType.valueOf(doc.getSourceType());
        DocRef ref = referenceService.describe(type, List.of(doc.getSourceId())).get(doc.getSourceId());
        return ref != null ? ref
                : new DocRef(type, null, null, type.getLabel(), doc.getSourceId(), doc.getSourceNo(), null, null, null, null, null);
    }

    @Override
    public void checkVisible(long id) {
        getVisible(id);
    }

    private InvDoc getVisible(long id) {
        InvDoc doc = invDocMapper.selectById(id);
        if (doc == null) {
            throw new BizException(404, "单据不存在");
        }
        DataScope.current(false).check(doc.getCreateBy());
        return doc;
    }

    /** 改、删之前锁住单头：和审核、反审核排队，后到的看到的是改完的状态 */
    private InvDoc lock(long id) {
        InvDoc doc = invDocMapper.lockById(id);
        if (doc == null) {
            throw new BizException(404, "单据不存在");
        }
        DataScope.current(false).check(doc.getCreateBy());
        return doc;
    }

    // ---------- 手工新建、改、删 ----------

    @Override
    @Transactional
    public InvDocCreatedVO create(InvDocCreateReq req) {
        InvDocType type = req.getDocType();
        if (type.isSourceRequired()) {
            throw new BizException(400, type.getLabel() + "要从" + type.getSource().getLabel() + "生成");
        }
        InvDoc doc = new InvDoc();
        doc.setKind(type.getKind());
        doc.setDocType(type);
        doc.setStatus(DRAFT);
        applyForm(doc, req);
        checkCompany(req.getCompanyId());
        checkHeader(doc);
        List<InvDocItem> items = manualItems(req.getItems(), Map.of(), Set.of());
        insert(doc, items);
        docLogService.add(BizType.INV_DOC, doc.getId(), "create", null);
        return new InvDocCreatedVO(doc.getId(), doc.getNo(), 0, DRAFT.name());
    }

    @Override
    @Transactional
    public int update(long id, InvDocSaveReq req) {
        InvDoc doc = lock(id);
        FLOW.next("edit", doc.getStatus());
        if (!doc.getVersion().equals(req.getVersion())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单已经被别人改过，请刷新后再改");
        }
        boolean fromSource = doc.getSourceType() != null;
        Long oldCompany = doc.getCompanyId();
        applyForm(doc, req);
        if (fromSource) {
            // 源单生成的：主体、往来跟着源单，不能改
            doc.setCompanyId(oldCompany);
        } else if (!Objects.equals(oldCompany, doc.getCompanyId())) {
            checkCompany(doc.getCompanyId());
        }
        checkHeader(doc);

        Map<Long, InvDocItem> existing = itemMapper.selectByDoc(id).stream()
                .collect(Collectors.toMap(InvDocItem::getId, Function.identity()));
        Set<Long> posted = stockLogMapper.postedDocItems(id);
        List<InvDocItem> items = fromSource
                ? sourceItems(doc, handler(BizType.valueOf(doc.getSourceType())), req.getItems(), existing, posted)
                : manualItems(req.getItems(), existing, posted);
        Set<Long> kept = items.stream().map(InvDocItem::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<InvDocItem> removed = existing.values().stream().filter(i -> !kept.contains(i.getId())).toList();
        for (InvDocItem r : removed) {
            if (posted.contains(r.getId())) {
                throw new BizException("第 " + r.getSeq() + " 行过过账，不能删（数量可以改）");
            }
        }

        UpdateWrapper<InvDoc> update = Updates.replaceById(doc)
                .eq("status", DRAFT.name())
                .eq("version", req.getVersion())
                .setSql("version = version + 1");
        if (invDocMapper.update(doc, update) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张单已经被别人改过或者审核了，请刷新后再改");
        }
        if (!removed.isEmpty()) {
            itemMapper.deleteByIds(removed.stream().map(InvDocItem::getId).toList());
        }
        for (InvDocItem item : items) {
            item.setDocId(id);
            if (item.getId() == null) {
                itemMapper.insert(item);
            } else {
                itemMapper.update(item, Updates.replaceById(item));
            }
        }
        if (fromSource) {
            handler(BizType.valueOf(doc.getSourceType())).check(sourcePosting(doc, items));
        }
        docLogService.add(BizType.INV_DOC, id, "update", null);
        return req.getVersion() + 1;
    }

    @Override
    @Transactional
    public void delete(long id) {
        InvDoc doc = lock(id);
        FLOW.next("remove", doc.getStatus());
        if (stockLogMapper.docPosted(id)) {
            throw new BizException("这张单过过账（库存日志里有它），不能删，只能作废");
        }
        itemMapper.deleteByDoc(id);
        attachmentService.deleteByDoc(BizType.INV_DOC, id);
        docLogService.deleteByDoc(BizType.INV_DOC, id);
        invDocMapper.deleteById(id);
    }

    /** 单头上能改的字段抄到实体上（新建、改草稿共用）。类型、kind、源单不在这里 */
    private void applyForm(InvDoc doc, InvDocForm form) {
        doc.setCompanyId(form.getCompanyId());
        doc.setDocDate(form.getDocDate());
        doc.setFromWarehouseId(form.getFromWarehouseId());
        doc.setToWarehouseId(form.getToWarehouseId());
        doc.setReason(InvTexts.blankToNull(form.getReason()));
        doc.setSupplierDeliveryNo(InvTexts.blankToNull(form.getSupplierDeliveryNo()));
        doc.setLogisticsNo(InvTexts.blankToNull(form.getLogisticsNo()));
        doc.setRemark(InvTexts.blankToNull(form.getRemark()));
    }

    private void checkCompany(long companyId) {
        Company company = companyService.getById(companyId);
        if (!Integer.valueOf(1).equals(company.getStatus())) {
            throw new BizException("主体" + company.getShortName() + "已停用，请换一个");
        }
    }

    /**
     * 单头的检查（docs/api/04 §3.2.3）：日期、出仓入仓（按类型该有的必填、类型对、启用）、调拨同种不同仓、原因字典、往来单位。
     * 手建单的往来由工厂仓带出；源单生成的往来跟着源单，工厂仓要是那个加工厂的
     */
    private void checkHeader(InvDoc doc) {
        InvDocType type = doc.getDocType();
        if (doc.getDocDate().isAfter(LocalDate.now(clock))) {
            throw new BizException(400, "日期不能晚于今天");
        }
        WarehouseVO from = warehouse(type.getFrom(), doc.getFromWarehouseId(), "出仓");
        WarehouseVO to = warehouse(type.getTo(), doc.getToWarehouseId(), "入仓");
        doc.setFromWarehouseId(from == null ? null : from.getId());
        doc.setToWarehouseId(to == null ? null : to.getId());
        if (type == InvDocType.TRANSFER) {
            if (from.getId().equals(to.getId())) {
                throw new BizException(400, "出仓、入仓不能是同一个仓");
            }
            if (!from.getType().equals(to.getType())) {
                throw new BizException(400, "调拨只能在同一种仓之间：物料仓到物料仓、工厂仓到工厂仓");
            }
        }
        if (type.getReasonDict() != null) {
            if (doc.getReason() == null) {
                throw new BizException(400, "请选原因");
            }
            if (!InvTexts.labels(dictDataService, type.getReasonDict()).containsKey(doc.getReason())) {
                throw new BizException(400, "原因不在字典里：" + doc.getReason());
            }
        }
        if (type.getPartner() == InvDocType.Partner.FACTORY) {
            WarehouseVO factory = from != null && "FACTORY".equals(from.getType()) ? from : to;
            if (doc.getSourceType() == null) {
                doc.setPartnerId(factory.getFactoryId());
            } else if (!Objects.equals(factory.getFactoryId(), doc.getPartnerId())) {
                throw new BizException(400, "工厂仓要选这张生产单的加工厂的");
            }
        } else if (doc.getSourceType() == null) {
            doc.setPartnerId(null);
        }
    }

    private static final Map<String, String> WAREHOUSE_TYPE = Map.of("MATERIAL", "物料仓", "FACTORY", "工厂仓", "PRODUCT", "成品仓");

    /** 类型上没有这一格（allowed 是空的）返回 null，发了什么都不要 */
    private WarehouseVO warehouse(Set<String> allowed, Long id, String label) {
        if (allowed.isEmpty()) {
            return null;
        }
        if (id == null) {
            throw new BizException(400, "请选" + label);
        }
        WarehouseVO w = warehouseService.getById(id);
        if (!allowed.contains(w.getType())) {
            throw new BizException(400, label + "要选" + allowed.stream().sorted().map(WAREHOUSE_TYPE::get)
                    .collect(Collectors.joining("或")));
        }
        if (!Integer.valueOf(1).equals(w.getStatus())) {
            throw new BizException("仓库" + w.getName() + "已停用");
        }
        return w;
    }

    /**
     * 手建单的明细：数量必填 > 0，单价选填，金额 = 数量 × 单价。新加的行物料要启用；
     * 带 id 的行要是这张单上的，过过账的行不能换物料
     */
    private List<InvDocItem> manualItems(List<InvDocItemReq> reqs, Map<Long, InvDocItem> existing, Set<Long> posted) {
        checkSkus(reqs, existing);
        List<InvDocItem> items = new ArrayList<>();
        for (int n = 0; n < reqs.size(); n++) {
            InvDocItemReq r = reqs.get(n);
            int seq = n + 1;
            InvDocItem item = keptOrNew(r, seq, existing, posted);
            if (r.getQty() == null) {
                throw new BizException(400, "第 " + seq + " 行要填数量");
            }
            item.setQty(NumericLimits.quantity(r.getQty(), "第 " + seq + " 行："));
            item.setPrice(NumericLimits.price(r.getPrice(), "第 " + seq + " 行："));
            item.setAmount(NumericLimits.amount(item.getPrice() == null ? null : r.getQty().multiply(item.getPrice())
                    .setScale(2, RoundingMode.HALF_UP), "第 " + seq + " 行："));
            item.setPurchaseUnit(null);
            item.setPurchaseQty(null);
            item.setConversionFactor(null);
            item.setSourceItemId(null);
            item.setOverReason(null);
            items.add(item);
        }
        return items;
    }

    /** 新加的行、换了物料的行：物料要存在、启用 */
    private void checkSkus(List<InvDocItemReq> reqs, Map<Long, InvDocItem> existing) {
        List<Long> changed = reqs.stream()
                .filter(r -> r.getId() == null || existing.get(r.getId()) == null
                        || !existing.get(r.getId()).getMaterialSkuId().equals(r.getMaterialSkuId()))
                .map(InvDocItemReq::getMaterialSkuId).distinct().toList();
        for (MaterialSkuRef ref : materialService.skuRefs(changed)) {
            if (!ref.selectable()) {
                throw new BizException("物料 " + ref.code() + " 已停用，不能加到单上");
            }
        }
    }

    /** 带 id 的行沿用（id、物权不变），新行建一个；行号、物料、缸号、卷数、备注照请求 */
    private static InvDocItem keptOrNew(InvDocItemReq r, int seq, Map<Long, InvDocItem> existing, Set<Long> posted) {
        InvDocItem item;
        if (r.getId() != null) {
            item = existing.get(r.getId());
            if (item == null) {
                throw new BizException(400, "第 " + seq + " 行不是这张单上的，请刷新后再改");
            }
            if (posted.contains(item.getId()) && !item.getMaterialSkuId().equals(r.getMaterialSkuId())) {
                throw new BizException("第 " + seq + " 行过过账，不能换物料（可以删了这行再加一行）");
            }
        } else {
            item = new InvDocItem();
            item.setOwnerType(Stock.OWN);
            item.setOwnerId(0L);
        }
        item.setSeq(seq);
        item.setMaterialSkuId(r.getMaterialSkuId());
        item.setDyeLot(InvTexts.blankToNull(r.getDyeLot()));
        item.setRolls(r.getRolls());
        item.setRemark(InvTexts.blankToNull(r.getRemark()));
        return item;
    }

    private void insert(InvDoc doc, List<InvDocItem> items) {
        doc.setNo(serialNoGenerator.daily(BizType.INV_DOC.prefix(doc.getKind().name())));
        invDocMapper.insert(doc);
        for (InvDocItem item : items) {
            item.setDocId(doc.getId());
        }
        itemMapper.insert(items);
    }

    // ---------- 状态动作 ----------

    @Override
    @Transactional
    public void act(long id, String action, DocActionReq req) {
        InvDoc doc = getVisible(id);
        switch (action) {
            case "audit" -> audit(doc, req.version(), req.reason());
            case "unaudit" -> unaudit(doc, req);
            case "void" -> voidDoc(doc, req);
            default -> throw new BizException(404, "入库单、出库单没有动作 " + action);
        }
    }

    /** 审核过账：比较并交换（锁住这张单）→ 源单查上限 → 改库存、记日志 → 回写源单 */
    private void audit(InvDoc doc, int version, String remark) {
        FLOW.next("audit", doc.getStatus());
        StatusCas.update(invDocMapper, new InvDoc(), StatusCas.of(InvDoc::getId, doc.getId(), InvDoc::getStatus, DRAFT, APPROVED)
                .set(InvDoc::getAuditBy, LoginUser.currentUserId())
                .set(InvDoc::getAuditTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(InvDoc::getVersion, version));
        List<InvDocItem> items = itemMapper.selectByDoc(doc.getId());
        StockSourceHandler handler = doc.getSourceType() == null ? null : handler(BizType.valueOf(doc.getSourceType()));
        SourcePosting sourcePosting = handler == null ? null : sourcePosting(doc, items);
        if (handler != null) {
            handler.preparePosting(sourcePosting, false);
        }
        ledger.post(posting(doc, items));
        docLogService.add(BizType.INV_DOC, doc.getId(), "audit", DRAFT, APPROVED, remark);
    }

    /** 反审核：回到草稿，库存反过来做一遍（不够 1001），源单退回去 */
    private void unaudit(InvDoc doc, DocActionReq req) {
        FLOW.next("unaudit", doc.getStatus());
        StatusCas.update(invDocMapper, new InvDoc(), StatusCas.of(InvDoc::getId, doc.getId(), InvDoc::getStatus, APPROVED, DRAFT)
                .set(InvDoc::getAuditBy, null)
                .set(InvDoc::getAuditTime, null)
                .setSql("version = version + 1")
                .eq(InvDoc::getVersion, req.version()));
        List<InvDocItem> items = itemMapper.selectByDoc(doc.getId());
        if (doc.getSourceType() != null) {
            handler(BizType.valueOf(doc.getSourceType())).preparePosting(sourcePosting(doc, items), true);
        }
        ledger.post(posting(doc, items).reversed());
        docLogService.add(BizType.INV_DOC, doc.getId(), "unaudit", APPROVED, DRAFT, req.reason());
    }

    /** 作废：只给过过账又反审核回来的草稿（没过过账的直接删） */
    private void voidDoc(InvDoc doc, DocActionReq req) {
        FLOW.next("void", doc.getStatus());
        if (!stockLogMapper.docPosted(doc.getId())) {
            throw new BizException("这张单没过过账，直接删除就行");
        }
        StatusCas.update(invDocMapper, new InvDoc(), StatusCas.of(InvDoc::getId, doc.getId(), InvDoc::getStatus, DRAFT, VOIDED)
                .setSql("version = version + 1")
                .eq(InvDoc::getVersion, req.version()));
        docLogService.add(BizType.INV_DOC, doc.getId(), "void", DRAFT, VOIDED, req.reason());
    }

    /** 一张单的库存变动：有出仓的出仓减、有入仓的入仓加（移库两笔），日志类型就是单据类型 */
    static StockLedger.Posting posting(InvDoc doc, List<InvDocItem> items) {
        InvDocType type = doc.getDocType();
        StockChangeType change = StockChangeType.of(type);
        List<StockLedger.Move> moves = new ArrayList<>();
        for (InvDocItem i : items) {
            if (type.hasFrom()) {
                moves.add(new StockLedger.Move(doc.getFromWarehouseId(), i.getMaterialSkuId(), i.getQty().negate(), change,
                        i.getSeq(), i.getId(), null, i.getDyeLot(), i.getPrice(), i.getAmount() == null ? null : i.getAmount().negate()));
            }
            if (type.hasTo()) {
                moves.add(new StockLedger.Move(doc.getToWarehouseId(), i.getMaterialSkuId(), i.getQty(), change,
                        i.getSeq(), i.getId(), null, i.getDyeLot(), i.getPrice(), i.getAmount()));
            }
        }
        return new StockLedger.Posting(doc.getId(), null, doc.getNo(), doc.getDocDate(), moves);
    }

    private static SourcePosting sourcePosting(InvDoc doc, List<InvDocItem> items) {
        return new SourcePosting(doc.getId(), doc.getNo(), doc.getDocType(), doc.getSourceId(), items.stream()
                .map(i -> new SourcePosting.Line(i.getSeq(), i.getSourceItemId(), i.getMaterialSkuId(), i.getQty(), i.getPurchaseQty()))
                .toList());
    }

    // ---------- 由源单生成 ----------

    private StockSourceHandler handler(BizType source) {
        StockSourceHandler h = handlers.get(source);
        if (h == null) {
            throw new BizException(source.getLabel() + "还没上线，暂时不能从它生成入库单、出库单");
        }
        return h;
    }

    private static void checkSource(BizType source, InvDocType type) {
        if (type.getSource() != source) {
            throw new BizException(400, type.getLabel() + "不能从" + source.getLabel() + "生成");
        }
    }

    @Override
    public InvDocDraftVO prefill(BizType source, long sourceId, InvDocType docType, List<Long> itemIds, Long fromWarehouseId) {
        checkSource(source, docType);
        SourceDraft d = handler(source).prefill(docType, sourceId, itemIds, fromWarehouseId);
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(d.items().stream().map(SourceDraft.Item::materialSkuId).toList());
        InvDocDraftVO vo = new InvDocDraftVO();
        vo.setDocType(docType);
        vo.setSource(d.source());
        vo.setPartnerId(d.partnerId());
        vo.setPartnerName(d.partnerId() == null ? null : invDocMapper.partnerName(d.partnerId()));
        if (d.fromWarehouseId() != null) {
            vo.setFromWarehouseId(d.fromWarehouseId());
            vo.setFromWarehouseName(warehouseService.getById(d.fromWarehouseId()).getName());
        }
        if (d.toWarehouseId() != null) {
            vo.setToWarehouseId(d.toWarehouseId());
            vo.setToWarehouseName(warehouseService.getById(d.toWarehouseId()).getName());
        }
        vo.setItems(d.items().stream().map(i -> {
            InvDocDraftVO.Item v = new InvDocDraftVO.Item();
            v.fillFrom(briefs.get(i.materialSkuId()));
            v.setSourceItemId(i.sourceItemId());
            v.setPurchaseUnit(i.purchaseUnit());
            v.setConversionFactor(i.conversionFactor());
            v.setPrice(i.price());
            v.setSuggestQty(i.suggestQty());
            v.setMaxQty(i.maxQty());
            v.setOrderedQty(i.orderedQty());
            v.setReceivedQty(i.receivedQty());
            v.setReturnedQty(i.returnedQty());
            v.setNeedQty(i.needQty());
            v.setIssuedQty(i.issuedQty());
            return v;
        }).toList());
        return vo;
    }

    @Override
    @Transactional
    public InvDocCreatedVO fromSource(BizType source, long sourceId, InvDocFromSourceReq req) {
        checkSource(source, req.getDocType());
        if (!LoginUser.hasAuthority("inventory:doc:add") || (req.isPost() && !LoginUser.hasAuthority("inventory:doc:audit"))) {
            throw new BizException(403, "没有新建或审核库存单的权限");
        }
        String operation = source == BizType.PURCHASE_ORDER ? "INV_FROM_PURCHASE" : "INV_FROM_PRODUCTION";
        return actionRequestService.execute(operation, sourceId, req.getClientActionId(), req, InvDocCreatedVO.class,
                () -> createFromSource(source, sourceId, req));
    }

    private InvDocCreatedVO createFromSource(BizType source, long sourceId, InvDocFromSourceReq req) {
        InvDocType type = req.getDocType();
        checkSource(source, type);
        StockSourceHandler handler = handler(source);
        List<Long> itemIds = req.getItems() == null ? req.getItemIds()
                : nullIfEmpty(req.getItems().stream().map(InvDocFromSourceReq.Item::getSourceItemId).filter(Objects::nonNull).toList());
        SourceDraft d = handler.prefill(type, sourceId, itemIds, req.getFromWarehouseId());

        InvDoc doc = new InvDoc();
        doc.setKind(type.getKind());
        doc.setDocType(type);
        doc.setStatus(DRAFT);
        doc.setSourceType(source.name());
        doc.setSourceId(sourceId);
        doc.setSourceNo(d.source().no());
        doc.setCompanyId(d.companyId());
        doc.setPartnerId(d.partnerId());
        doc.setDocDate(req.getDocDate() == null ? LocalDate.now(clock) : req.getDocDate());
        doc.setFromWarehouseId(req.getFromWarehouseId() == null ? d.fromWarehouseId() : req.getFromWarehouseId());
        doc.setToWarehouseId(req.getToWarehouseId() == null ? d.toWarehouseId() : req.getToWarehouseId());
        doc.setReason(InvTexts.blankToNull(req.getReason()));
        doc.setSupplierDeliveryNo(InvTexts.blankToNull(req.getSupplierDeliveryNo()));
        doc.setLogisticsNo(InvTexts.blankToNull(req.getLogisticsNo()));
        doc.setRemark(InvTexts.blankToNull(req.getRemark()));
        checkHeader(doc);

        List<InvDocItem> items = new ArrayList<>();
        if (req.getItems() != null) {
            for (int n = 0; n < req.getItems().size(); n++) {
                InvDocFromSourceReq.Item r = req.getItems().get(n);
                SourceDraft.Item s = match(d, r.getSourceItemId(), r.getMaterialSkuId(), n + 1);
                if (r.getQty().signum() > 0) {
                    InvDocItem item = sourceItem(type, s, r.getQty(), items.size() + 1, r.getOverReason());
                    item.setDyeLot(InvTexts.blankToNull(r.getDyeLot()));
                    item.setRolls(r.getRolls());
                    item.setRemark(InvTexts.blankToNull(r.getRemark()));
                    items.add(item);
                }
            }
        } else {
            for (SourceDraft.Item s : d.items()) {
                if (s.suggestQty() != null && s.suggestQty().signum() > 0) {
                    items.add(sourceItem(type, s, s.suggestQty(), items.size() + 1, null));
                }
            }
        }
        if (items.isEmpty()) {
            throw new BizException(400, "没有要" + (type.getKind() == InvDocKind.IN ? "入库" : "出库") + "的数");
        }
        insert(doc, items);
        handler.check(sourcePosting(doc, items));
        docLogService.add(BizType.INV_DOC, doc.getId(), "create", "由" + source.getLabel() + " " + doc.getSourceNo() + " 生成");
        if (req.isPost()) {
            audit(doc, 0, null);
            return new InvDocCreatedVO(doc.getId(), doc.getNo(), 1, APPROVED.name());
        }
        return new InvDocCreatedVO(doc.getId(), doc.getNo(), 0, DRAFT.name());
    }

    /**
     * 改源单生成的草稿：每行要对得上源单（不能加源单以外的行），数量按源单的单位换算、单价按源单的取，前端传的不信。
     * 带 id 的行沿用，过过账的行不能换物料
     */
    private List<InvDocItem> sourceItems(InvDoc doc, StockSourceHandler handler, List<InvDocItemReq> reqs,
                                         Map<Long, InvDocItem> existing, Set<Long> posted) {
        InvDocType type = doc.getDocType();
        List<Long> itemIds = nullIfEmpty(reqs.stream().map(InvDocItemReq::getSourceItemId).filter(Objects::nonNull).toList());
        SourceDraft d = handler.prefill(type, doc.getSourceId(), itemIds, doc.getFromWarehouseId());
        List<InvDocItem> items = new ArrayList<>();
        for (int n = 0; n < reqs.size(); n++) {
            InvDocItemReq r = reqs.get(n);
            int seq = n + 1;
            SourceDraft.Item s = match(d, r.getSourceItemId(), r.getMaterialSkuId(), seq);
            BigDecimal qty = isPurchase(type) ? r.getPurchaseQty() : r.getQty();
            if (qty == null) {
                throw new BizException(400, "第 " + seq + " 行要填数量");
            }
            InvDocItem built = sourceItem(type, s, qty, seq, r.getOverReason());
            InvDocItem item = keptOrNew(r, seq, existing, posted);
            item.setSourceItemId(built.getSourceItemId());
            item.setQty(built.getQty());
            item.setPurchaseUnit(built.getPurchaseUnit());
            item.setPurchaseQty(built.getPurchaseQty());
            item.setConversionFactor(built.getConversionFactor());
            item.setPrice(built.getPrice());
            item.setAmount(built.getAmount());
            item.setOverReason(built.getOverReason());
            items.add(item);
        }
        return items;
    }

    private static boolean isPurchase(InvDocType type) {
        return type.getPartner() == InvDocType.Partner.SUPPLIER;
    }

    private static <T> List<T> nullIfEmpty(List<T> list) {
        return list.isEmpty() ? null : list;
    }

    /** 采购的两种按采购明细 id 对，领料、退料按 SKU 对 */
    private static SourceDraft.Item match(SourceDraft d, Long sourceItemId, long skuId, int seq) {
        return d.items().stream()
                .filter(s -> s.materialSkuId() == skuId && Objects.equals(s.sourceItemId(), sourceItemId))
                .findFirst()
                .orElseThrow(() -> new BizException(400, "第 " + seq + " 行不是" + d.source().no() + "上的物料"));
    }

    /**
     * 按源单的一行建明细（docs/api/04 §3.3）。采购的两种：qty 是采购单位，换成库存单位（三位小数），单价换成元 / 库存单位（四位小数），
     * 金额 = 采购数量 × 采购单价；领料、退料不记单价。领料超过还差的数要写超领原因
     */
    private static InvDocItem sourceItem(InvDocType type, SourceDraft.Item s, BigDecimal qty, int seq, String overReason) {
        InvDocItem item = new InvDocItem();
        item.setSeq(seq);
        item.setMaterialSkuId(s.materialSkuId());
        item.setOwnerType(Stock.OWN);
        item.setOwnerId(0L);
        item.setSourceItemId(s.sourceItemId());
        if (isPurchase(type)) {
            item.setPurchaseUnit(s.purchaseUnit());
            item.setPurchaseQty(qty);
            item.setConversionFactor(s.conversionFactor());
            item.setQty(qty.multiply(s.conversionFactor()).setScale(3, RoundingMode.HALF_UP));
            if (s.price() != null) {
                item.setPrice(s.price().divide(s.conversionFactor(), 4, RoundingMode.HALF_UP));
                item.setAmount(qty.multiply(s.price()).setScale(2, RoundingMode.HALF_UP));
            }
        } else {
            item.setQty(qty);
        }
        NumericLimits.quantity(item.getQty(), "第 " + seq + " 行：");
        NumericLimits.price(item.getPrice(), "第 " + seq + " 行换算后：");
        NumericLimits.amount(item.getAmount(), "第 " + seq + " 行：");
        if (type == InvDocType.ISSUE_OUT && s.suggestQty() != null && qty.compareTo(s.suggestQty()) > 0) {
            if (overReason == null || overReason.isBlank()) {
                throw new BizException(400, "第 " + seq + " 行领多了，要写原因");
            }
            item.setOverReason(overReason.strip());
        }
        if (item.getQty().signum() <= 0) {
            throw new BizException(400, "第 " + seq + " 行数量要大于 0");
        }
        return item;
    }

    // ---------- 导出 ----------

    @Override
    public byte[] export(InvDocQuery query) {
        Page<InvDocRowVO> all = new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false);
        List<InvDocRowVO> docs = invDocMapper.selectRowPage(all, query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine())).getRecords();
        Map<Long, List<InvDocItem>> items = docs.isEmpty() ? Map.of()
                : itemMapper.selectByDocs(docs.stream().map(InvDocRowVO::getId).toList()).stream()
                .collect(Collectors.groupingBy(InvDocItem::getDocId));
        Set<Long> skuIds = new HashSet<>();
        items.values().forEach(list -> list.forEach(i -> skuIds.add(i.getMaterialSkuId())));
        Map<Long, String> names = userService.nicknames(docs.stream().map(InvDocRowVO::getCreateBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return excel.export(query.getKind(), docs, items, materialService.skuBriefs(skuIds), names);
    }
}
