package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.WarehouseOption;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.inventory.dto.InvOverRow;
import com.lingmao.scm.module.inventory.dto.SourceDraft;
import com.lingmao.scm.module.inventory.dto.SourcePosting;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.StockSourceHandler;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.mapper.FactoryLedgerMapper;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;

/**
 * 领料出库、退料入库的源单这一边（docs/api/05 §3.4.3、04 §3.3）：数量都是库存单位，按物料 SKU 对行（没有源单明细 id）。
 * <ul>
 *   <li>领料：已下单、生产中；带出资料包里自购的每种料，本次默认 = 还差，不限上限（超了库存那边要写超领原因）</li>
 *   <li>退料：已下单、生产中、已完成、已结案；带出已领 &gt; 0 的，本次 ≤ 已领 − 实耗（核销过的）</li>
 *   <li>耗料核销（RFC 015 §3）：已完成、已结案，同时只有一张没作废的；带出应耗或已领不是 0 的料，本次默认 = 应耗，
 *       超了要写超耗原因；从工厂仓扣，不回写生产单</li>
 * </ul>
 * check 先锁住生产单，过账、回写都在库存单的同一个事务里；过账后重算领料状态、回写算料单的已领（料都领完了的订单的算料单自动关闭）
 */
@Component
@RequiredArgsConstructor
class ProductionStockHandler implements StockSourceHandler {

    private static final Set<DocStatus> CAN_ISSUE = Set.of(APPROVED, RUNNING);
    private static final Set<DocStatus> CAN_RETURN = Set.of(APPROVED, RUNNING, DONE, CLOSED);
    private static final Set<DocStatus> CAN_SETTLE = Set.of(DONE, CLOSED);

    private final ProductionOrderMapper prdMapper;
    private final ProductionSupport support;
    private final WarehouseService warehouseService;
    private final MaterialService materialService;
    private final DictDataService dictDataService;
    private final DocLogService docLogService;

    @Override
    public BizType sourceType() {
        return BizType.PRODUCTION_ORDER;
    }

    @Override
    public SourceDraft prefill(InvDocType docType, long sourceId, List<Long> itemIds, Long fromWarehouseId, Long editingDocId) {
        // 和选生产单的下拉（issue-options）同一个范围：品类线、本人；看不到的当不存在（agy T-e035d4 #3）
        ProductionOrder p = support.visible(prdMapper.selectById(sourceId));
        if (docType == InvDocType.CONSUME_OUT) {
            return prefillConsume(p, editingDocId);
        }
        boolean issue = docType == InvDocType.ISSUE_OUT;
        checkStatus(p, issue);
        Map<Long, BigDecimal> need = support.needs(List.of(sourceId)).getOrDefault(sourceId, Map.of());
        Map<Long, BigDecimal> issued = support.issued(List.of(sourceId), null).getOrDefault(sourceId, Map.of());
        Map<Long, BigDecimal> settled = issue ? Map.of() : support.settled(List.of(sourceId)).getOrDefault(sourceId, Map.of());
        List<SourceDraft.Item> items = new ArrayList<>();
        if (issue) {
            need.forEach((sku, n) -> {
                BigDecimal got = issued.getOrDefault(sku, BigDecimal.ZERO);
                items.add(new SourceDraft.Item(null, sku, null, null, null, n.subtract(got).max(BigDecimal.ZERO), null,
                        null, null, null, n, got));
            });
        } else {
            issued.forEach((sku, got) -> {
                BigDecimal max = got.subtract(settled.getOrDefault(sku, BigDecimal.ZERO));
                if (max.signum() > 0) {
                    items.add(new SourceDraft.Item(null, sku, null, null, null, BigDecimal.ZERO, max,
                            null, null, null, need.get(sku), got));
                }
            });
        }
        sortByCode(items);

        Long factoryWh = factoryWarehouse(p);
        Long materialWh = fromWarehouseId != null && issue ? fromWarehouseId : firstMaterialWarehouse();
        DocRef source = DocRef.of(BizType.PRODUCTION_ORDER, p.getId(), p.getNo(), p.getStatus(), ProductionRules.STATUS, p.getMerchId())
                .withKind(p.getKind().name());
        return new SourceDraft(p.getCompanyId(), source, p.getFactoryId(), issue ? materialWh : factoryWh, issue ? factoryWh : materialWh,
                items);
    }

    private void sortByCode(List<SourceDraft.Item> items) {
        Map<Long, String> codes = materialService.skuBriefs(items.stream().map(SourceDraft.Item::materialSkuId).toList()).entrySet()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getSkuCode()));
        items.sort(Comparator.comparing(i -> codes.getOrDefault(i.materialSkuId(), "")));
    }

    /** 耗料核销：每种应耗或已领不是 0 的料一行，本次默认 = 应耗（needQty 也放应耗，前端显示成"应耗"），出仓是工厂仓、没有入仓 */
    private SourceDraft prefillConsume(ProductionOrder p, Long editingDocId) {
        checkSettle(p, editingDocId);
        long id = p.getId();
        Map<Long, BigDecimal> consume = support.consumes(List.of(id)).getOrDefault(id, Map.of());
        Map<Long, BigDecimal> issued = support.issued(List.of(id), null).getOrDefault(id, Map.of());
        List<SourceDraft.Item> items = new ArrayList<>();
        for (Long sku : settleSkus(consume, issued)) {
            BigDecimal c = consume.getOrDefault(sku, BigDecimal.ZERO);
            items.add(new SourceDraft.Item(null, sku, null, null, null, c, null, null, null, null, c,
                    issued.getOrDefault(sku, BigDecimal.ZERO)));
        }
        sortByCode(items);
        DocRef source = DocRef.of(BizType.PRODUCTION_ORDER, p.getId(), p.getNo(), p.getStatus(), ProductionRules.STATUS, p.getMerchId())
                .withKind(p.getKind().name());
        return new SourceDraft(p.getCompanyId(), source, p.getFactoryId(), factoryWarehouse(p), null, items);
    }

    /** 核销单能带哪些料：应耗或已领不是 0 的 */
    static Set<Long> settleSkus(Map<Long, BigDecimal> consume, Map<Long, BigDecimal> issued) {
        Set<Long> skus = new LinkedHashSet<>();
        consume.forEach((sku, q) -> {
            if (q.signum() != 0) {
                skus.add(sku);
            }
        });
        issued.forEach((sku, q) -> {
            if (q.signum() != 0) {
                skus.add(sku);
            }
        });
        return skus;
    }

    /** 能不能开耗料核销单：已完成、已结案，有自购料，没有别的没作废的核销单（excludeDocId 是自己） */
    private void checkSettle(ProductionOrder p, Long excludeDocId) {
        if (!CAN_SETTLE.contains(p.getStatus())) {
            throw new BizException(ProductionSupport.label(p) + " 现在是「" + ProductionRules.STATUS.label(p.getStatus()).text()
                    + "」，还没完成：交完货再核销耗料");
        }
        if (p.getIssueStatus() == null) {
            throw new BizException(ProductionSupport.label(p) + " 的资料包里没有自购的料，不用核销耗料");
        }
        FactoryLedgerMapper.SettleDocRow other = support.settleDoc(p.getId());
        if (other != null && !Long.valueOf(other.id()).equals(excludeDocId)) {
            throw new BizException(ProductionSupport.label(p) + " 已经有耗料核销单 " + other.no());
        }
    }

    private void checkStatus(ProductionOrder p, boolean issue) {
        if (!(issue ? CAN_ISSUE : CAN_RETURN).contains(p.getStatus())) {
            throw new BizException(ProductionSupport.label(p) + " 现在是「" + ProductionRules.STATUS.label(p.getStatus()).text()
                    + "」，不能" + (issue ? "领料" : "退料"));
        }
        if (p.getIssueStatus() == null) {
            throw new BizException(ProductionSupport.label(p) + " 的资料包里没有自购的料，不用领料");
        }
    }

    /** 加工厂的工厂仓：领料的入仓、退料的出仓 */
    private Long factoryWarehouse(ProductionOrder p) {
        WarehouseVO w = warehouseService.factoryWarehouse(p.getFactoryId());
        if (w == null) {
            throw new BizException("加工厂还没有工厂仓：在供应商档案里打开这家、保存一次就会建好");
        }
        return w.getId();
    }

    /** 默认的物料仓：第一个启用的；一个都没有是 null（库存那边让用户选） */
    private Long firstMaterialWarehouse() {
        List<WarehouseOption> options = warehouseService.options("MATERIAL");
        return options.isEmpty() ? null : options.getFirst().id();
    }

    @Override
    public void check(SourcePosting posting) {
        ProductionOrder p = lock(posting.sourceId());
        if (posting.docType() == InvDocType.CONSUME_OUT) {
            checkConsume(p, posting);
            return;
        }
        boolean issue = posting.docType() == InvDocType.ISSUE_OUT;
        checkStatus(p, issue);
        Map<Long, BigDecimal> need = support.needs(List.of(p.getId())).getOrDefault(p.getId(), Map.of());
        Map<Long, BigDecimal> thisQty = new LinkedHashMap<>();
        for (SourcePosting.Line l : posting.lines()) {
            if (issue && !need.containsKey(l.materialSkuId())) {
                throw new BizException(400, "第 " + l.seq() + " 行不是" + p.getNo() + "资料包里自购的料");
            }
            thisQty.merge(l.materialSkuId(), l.qty(), BigDecimal::add);
        }
        if (issue) {
            return;
        }
        Map<Long, BigDecimal> issued = support.issued(List.of(p.getId()), posting.docId()).getOrDefault(p.getId(), Map.of());
        Map<Long, BigDecimal> settled = support.settled(List.of(p.getId())).getOrDefault(p.getId(), Map.of());
        Map<Long, MaterialSkuBrief> briefs = null;
        Map<String, String> units = null;
        List<InvOverRow> over = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : thisQty.entrySet()) {
            BigDecimal max = issued.getOrDefault(e.getKey(), BigDecimal.ZERO)
                    .subtract(settled.getOrDefault(e.getKey(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            if (e.getValue().compareTo(max) <= 0) {
                continue;
            }
            if (briefs == null) {
                briefs = materialService.skuBriefs(thisQty.keySet());
                units = dictDataService.listByType("unit").stream()
                        .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
            }
            MaterialSkuBrief b = briefs.get(e.getKey());
            String unit = b == null ? null : units.getOrDefault(b.getStockUnit(), b.getStockUnit());
            int seq = posting.lines().stream().filter(l -> l.materialSkuId() == e.getKey()).mapToInt(SourcePosting.Line::seq)
                    .findFirst().orElse(0);
            over.add(new InvOverRow(seq, b == null ? null : b.getSkuCode(), b == null ? null : b.getMaterialName(), p.getNo(),
                    e.getValue(), max, unit, (settled.isEmpty() ? "已领 " : "已领 − 实耗 ") + ProdTexts.plain(max)));
        }
        if (!over.isEmpty()) {
            throw new BizException(ErrorCodes.OVER_LIMIT, "有 " + over.size() + " 种料退多了：最多退到已领的数（核销过的再减实耗）", over);
        }
    }

    /** 耗料核销单存草稿、审核前：生产单状态、没有别的核销单，每行要是这张单应耗或已领过的料 */
    private void checkConsume(ProductionOrder p, SourcePosting posting) {
        checkSettle(p, posting.docId());
        long id = p.getId();
        Set<Long> allowed = settleSkus(support.consumes(List.of(id)).getOrDefault(id, Map.of()),
                support.issued(List.of(id), null).getOrDefault(id, Map.of()));
        for (SourcePosting.Line l : posting.lines()) {
            if (!allowed.contains(l.materialSkuId())) {
                throw new BizException(400, "第 " + l.seq() + " 行不是" + p.getNo() + "用的料（资料包里自购、或者领过的）");
            }
        }
    }

    @Override
    public void onPosted(SourcePosting posting) {
        ProductionOrder p = lock(posting.sourceId());
        if (posting.docType() == InvDocType.CONSUME_OUT) {
            // 核销不回写生产单：料账是查询时算的，领料状态只看领料、退料
            docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), "consume-material", "耗料核销 " + posting.docNo());
            return;
        }
        support.syncIssued(p);
        boolean issue = posting.docType() == InvDocType.ISSUE_OUT;
        docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), issue ? "issue-material" : "return-material",
                (issue ? "领料出库 " : "退料入库 ") + posting.docNo());
    }

    @Override
    public void onUnposted(SourcePosting posting) {
        ProductionOrder p = lock(posting.sourceId());
        if (posting.docType() == InvDocType.CONSUME_OUT) {
            docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), "consume-material", posting.docNo() + " 反审核");
            return;
        }
        if (!CAN_RETURN.contains(p.getStatus())) {
            throw new BizException(ProductionSupport.label(p) + " 现在是「" + ProductionRules.STATUS.label(p.getStatus()).text()
                    + "」，领料、退料单不能反审核");
        }
        if (posting.docType() == InvDocType.ISSUE_OUT) {
            // 核销过的单，领的料已经按实耗扣掉了；再反审核领料，会把同一工厂仓里别的单的料扣走，料账结余变负
            support.checkNoSettle(p, "反审核领料单");
            Map<Long, BigDecimal> issued = support.issued(List.of(p.getId()), null).getOrDefault(p.getId(), Map.of());
            if (issued.values().stream().anyMatch(q -> q.signum() < 0)) {
                throw new BizException("这张领料单的料有的已经退回来了：先反审核对应的退料入库单");
            }
        }
        support.syncIssued(p);
        docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), posting.docType() == InvDocType.ISSUE_OUT ? "issue-material"
                : "return-material", posting.docNo() + " 反审核");
    }

    private ProductionOrder lock(long id) {
        ProductionOrder p = prdMapper.lockById(id);
        if (p == null) {
            throw new BizException(404, ProductionSupport.NOT_FOUND);
        }
        return p;
    }
}
