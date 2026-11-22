package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.purchase.dto.ReorderRowVO;
import com.lingmao.scm.module.purchase.dto.ReorderQuery;
import com.lingmao.scm.module.purchase.dto.SkuSupplyVO;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.purchase.mapper.SupplyMapper;
import com.lingmao.scm.module.purchase.service.SupplyService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 补货提醒、库存页的在途。口径（PRD §6.6.7、docs/api/03 §3.6）：库存只算物料仓；在途是送物料仓、已审核没入齐的采购行，
 * 不看赶不赶得上（直发工厂仓的不算，Q11）；已被占用 = 已确认的算料单占着的库存 + 在途。
 * 差多少 = 最小库存 −（库存 + 在途 − 已被占用），> 0 才进补货清单
 */
@Service
@RequiredArgsConstructor
public class SupplyServiceImpl implements SupplyService {

    /** 库存页一次最多查几个 SKU（一页最多 200 行，留余量） */
    static final int MAX_SKUS = 500;

    private final SupplyMapper supplyMapper;
    private final SupplyLoader supplyLoader;
    private final PurchaseOrderMapper poMapper;
    private final MrpEngine engine;
    private final MaterialService materialService;
    private final SupplierService supplierService;

    @Override
    public long reorderCount() { return supplyMapper.countReorderItems(); }

    @Override
    public PageResult<ReorderRowVO> reorderItems(ReorderQuery query) {
        if (query.getPage() < 1 || query.getSize() < 1 || query.getSize() > 100) {
            throw new BizException(400, "补货清单页码从 1 开始，每页 1 到 100 行");
        }
        long offset;
        try {
            offset = Math.multiplyExact(query.getPage() - 1, query.getSize());
        } catch (ArithmeticException overflow) {
            throw new BizException(400, "补货清单页码过大");
        }
        long total = supplyMapper.countReorderItems();
        if (offset >= total) return new PageResult<>(List.of(), total);
        // 数量与短缺排序在数据库中完成；JVM 只装这一页，绝不先装全量 SupplyBook 再截页。
        List<ReorderRowVO> rows = supplyMapper.selectReorderPage((int) query.getSize(), offset);
        Set<Long> ids = rows.stream().map(ReorderRowVO::getMaterialSkuId).collect(Collectors.toSet());
        if (ids.isEmpty()) return new PageResult<>(List.of(), total);
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(ids);
        for (ReorderRowVO row : rows) {
            MaterialPurchaseInfo info = infos.get(row.getMaterialSkuId());
            if (info == null) throw new BizException(409, "物料资料已变化，请刷新补货清单");
            row.setDefaultSupplierId(info.defaultSupplierId());
            row.setPurchaseUnit(info.purchaseUnit());
            row.setConversionFactor(info.conversionFactor());
            row.setSuggestQty(Num.suggestPurchase(row.getGapQty(), info.conversionFactor(), info.minPackQty(), info.moq()));
        }
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(ids);
        Map<Long, SupplierRef> suppliers = supplierService.refs(rows.stream().map(ReorderRowVO::getDefaultSupplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Function<MrpCalculator.PriceKey, BigDecimal> prices = engine.prices(rows.stream().filter(r -> r.getDefaultSupplierId() != null)
                .map(r -> new MrpCalculator.PriceKey(r.getDefaultSupplierId(), infos.get(r.getMaterialSkuId()).materialId(), r.getMaterialSkuId()))
                .toList());
        Map<Long, List<PurchaseOrderMapper.OpenDraftPreview>> drafts = poMapper.selectOpenDraftPreviews(ids, DataScope.current(false), 20)
                .stream().collect(Collectors.groupingBy(PurchaseOrderMapper.OpenDraftPreview::skuId));
        for (ReorderRowVO r : rows) {
            long skuId = r.getMaterialSkuId();
            MaterialPurchaseInfo info = infos.get(skuId);
            r.fillFrom(briefs.get(skuId));
            r.setMaterialSkuId(skuId);
            SupplierRef s = r.getDefaultSupplierId() == null ? null : suppliers.get(r.getDefaultSupplierId());
            r.setDefaultSupplierName(s == null ? null : s.shortName());
            r.setDefaultSupplierStatus(s == null ? null : s.status().name());
            BigDecimal archived = s == null ? null : prices.apply(new MrpCalculator.PriceKey(s.id(), info.materialId(), skuId));
            r.setPrice(Num.price(archived != null ? archived : info.price()));
            List<PurchaseOrderMapper.OpenDraftPreview> preview = drafts.getOrDefault(skuId, List.of());
            r.setOpenDraftsTotal(preview.isEmpty() ? 0 : preview.getFirst().total());
            r.setOpenDrafts(preview.stream().map(d -> new ReorderRowVO.OpenDraft(d.id(), d.no(), d.status())).toList());
        }
        return new PageResult<>(rows, total);
    }

    /** 一个 SKU 的库存、在途、已被占用、差多少（库存单位） */
    record Gap(BigDecimal stock, BigDecimal transit, BigDecimal held, BigDecimal gap) {
    }

    static Gap gap(SupplyBook book, long skuId, BigDecimal safetyStock) {
        BigDecimal stock = Num.qty(book.stockOf(skuId));
        BigDecimal transit = Num.qty(book.transitToMaterial(skuId));
        BigDecimal held = Num.qty(book.heldTotal(skuId));
        BigDecimal gap = safetyStock == null ? BigDecimal.ZERO : Num.qty(safetyStock.subtract(stock.add(transit).subtract(held)));
        return new Gap(stock, transit, held, gap);
    }

    @Override
    public List<SkuSupplyVO> materialSupply(Collection<Long> skuIds) {
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_SKUS) {
            throw new BizException(400, "一次最多查 " + MAX_SKUS + " 个物料");
        }
        SupplyBook book = supplyLoader.load(ids, List.of());
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(ids);
        List<SkuSupplyVO> out = new ArrayList<>();
        for (Long skuId : ids) {
            MaterialPurchaseInfo info = infos.get(skuId);
            BigDecimal safety = info == null || info.safetyStock() == null || info.safetyStock().signum() <= 0 ? null
                    : Num.qty(info.safetyStock());
            Gap g = gap(book, skuId, safety);
            List<SkuSupplyVO.Transit> transits = book.transitByWarehouse(skuId).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new SkuSupplyVO.Transit(e.getKey(), Num.qty(e.getValue()))).toList();
            out.add(new SkuSupplyVO(skuId, transits, g.held(), safety, safety != null && g.gap().signum() > 0));
        }
        return out;
    }
}
