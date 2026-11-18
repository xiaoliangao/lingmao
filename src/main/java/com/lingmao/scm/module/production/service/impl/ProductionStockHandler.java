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
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;

/**
 * 领料出库、退料入库的源单这一边（docs/api/05 §3.4.3、04 §3.3）：数量都是库存单位，按物料 SKU 对行（没有源单明细 id）。
 * <ul>
 *   <li>领料：已下单、生产中；带出资料包里自购的每种料，本次默认 = 还差，不限上限（超了库存那边要写超领原因）</li>
 *   <li>退料：已下单、生产中、已完成；带出已领 &gt; 0 的，本次 ≤ 已领</li>
 * </ul>
 * check 先锁住生产单，过账、回写都在库存单的同一个事务里；过账后重算领料状态、回写算料单的已领（料都领完了的订单的算料单自动关闭）
 */
@Component
@RequiredArgsConstructor
class ProductionStockHandler implements StockSourceHandler {

    private static final Set<DocStatus> CAN_ISSUE = Set.of(APPROVED, RUNNING);
    private static final Set<DocStatus> CAN_RETURN = Set.of(APPROVED, RUNNING, DONE);

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
    public SourceDraft prefill(InvDocType docType, long sourceId, List<Long> itemIds, Long fromWarehouseId) {
        ProductionOrder p = prdMapper.selectById(sourceId);
        if (p == null) {
            throw new BizException(404, ProductionSupport.NOT_FOUND);
        }
        boolean issue = docType == InvDocType.ISSUE_OUT;
        checkStatus(p, issue);
        Map<Long, BigDecimal> need = support.needs(List.of(sourceId)).getOrDefault(sourceId, Map.of());
        Map<Long, BigDecimal> issued = support.issued(List.of(sourceId), null).getOrDefault(sourceId, Map.of());
        List<SourceDraft.Item> items = new ArrayList<>();
        if (issue) {
            need.forEach((sku, n) -> {
                BigDecimal got = issued.getOrDefault(sku, BigDecimal.ZERO);
                items.add(new SourceDraft.Item(null, sku, null, null, null, n.subtract(got).max(BigDecimal.ZERO), null,
                        null, null, null, n, got));
            });
        } else {
            issued.forEach((sku, got) -> {
                if (got.signum() > 0) {
                    items.add(new SourceDraft.Item(null, sku, null, null, null, BigDecimal.ZERO, got,
                            null, null, null, need.get(sku), got));
                }
            });
        }
        Map<Long, String> codes = materialService.skuBriefs(items.stream().map(SourceDraft.Item::materialSkuId).toList()).entrySet()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getSkuCode()));
        items.sort(Comparator.comparing(i -> codes.getOrDefault(i.materialSkuId(), "")));

        Long factoryWh = factoryWarehouse(p);
        Long materialWh = fromWarehouseId != null && issue ? fromWarehouseId : firstMaterialWarehouse();
        DocRef source = DocRef.of(BizType.PRODUCTION_ORDER, p.getId(), p.getNo(), p.getStatus(), ProductionRules.STATUS, p.getMerchId())
                .withKind(p.getKind().name());
        return new SourceDraft(p.getCompanyId(), source, p.getFactoryId(), issue ? materialWh : factoryWh, issue ? factoryWh : materialWh,
                items);
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
        Map<Long, MaterialSkuBrief> briefs = null;
        Map<String, String> units = null;
        List<InvOverRow> over = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : thisQty.entrySet()) {
            BigDecimal max = issued.getOrDefault(e.getKey(), BigDecimal.ZERO).max(BigDecimal.ZERO);
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
                    e.getValue(), max, unit, "已领 " + ProdTexts.plain(max)));
        }
        if (!over.isEmpty()) {
            throw new BizException(ErrorCodes.OVER_LIMIT, "有 " + over.size() + " 种料退多了：最多退到已领的数", over);
        }
    }

    @Override
    public void onPosted(SourcePosting posting) {
        ProductionOrder p = lock(posting.sourceId());
        support.syncIssued(p);
        boolean issue = posting.docType() == InvDocType.ISSUE_OUT;
        docLogService.add(BizType.PRODUCTION_ORDER, p.getId(), issue ? "issue-material" : "return-material",
                (issue ? "领料出库 " : "退料入库 ") + posting.docNo());
    }

    @Override
    public void onUnposted(SourcePosting posting) {
        ProductionOrder p = lock(posting.sourceId());
        if (!CAN_RETURN.contains(p.getStatus())) {
            throw new BizException(ProductionSupport.label(p) + " 现在是「" + ProductionRules.STATUS.label(p.getStatus()).text()
                    + "」，领料、退料单不能反审核");
        }
        if (posting.docType() == InvDocType.ISSUE_OUT) {
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
