package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.purchase.mapper.SupplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 取一批物料 SKU 的库存、采购行、已确认的算料单占着的，装成 SupplyBook（算料、确认、详情、齐料、补货提醒共用） */
@Component
@RequiredArgsConstructor
class SupplyLoader {

    private static final Set<String> OPEN = Set.of("APPROVED", "RUNNING");

    private final SupplyMapper supplyMapper;

    /**
     * @param calcIds 这几张算料单生成的、用着的采购行也取（不管它们现在是什么状态）：算的是哪张就带哪张和它的上一张
     */
    SupplyBook load(Collection<Long> skuIds, Collection<Long> calcIds) {
        if (skuIds.isEmpty()) {
            return new SupplyBook(Map.of(), List.of(), List.of(), List.of());
        }
        Map<Long, BigDecimal> stock = new HashMap<>();
        supplyMapper.selectMaterialStock(skuIds).forEach(s -> stock.put(s.skuId(), s.qty()));
        List<SupplyBook.Line> lines = supplyMapper.selectLines(skuIds, calcIds).stream().map(SupplyLoader::line).toList();
        List<SupplyBook.Use> uses = supplyMapper.selectActiveUses(skuIds).stream()
                .map(u -> new SupplyBook.Use(u.id(), u.calcId(), u.calcItemId(), u.lineId(), u.qty())).toList();
        List<SupplyBook.HeldItem> items = supplyMapper.selectActiveItems(skuIds).stream()
                .map(i -> new SupplyBook.HeldItem(i.id(), i.calcId(), i.skuId(), i.useStockQty(), i.issuedQty())).toList();
        return new SupplyBook(stock, lines, uses, items);
    }

    private static SupplyBook.Line line(SupplyMapper.LineRow r) {
        return new SupplyBook.Line(r.id(), r.poId(), r.poNo(), r.purchaseType(), r.skuId(), r.warehouseId(), r.qty(),
                r.receivedQty(), r.returnedQty(), r.factor(), r.eta(), OPEN.contains(r.poStatus()), "MATERIAL".equals(r.warehouseType()),
                r.ownerItemId(), r.ownerCalcId(), "CONFIRMED".equals(r.ownerCalcStatus()));
    }
}
