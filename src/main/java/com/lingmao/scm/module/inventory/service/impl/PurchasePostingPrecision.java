package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.module.inventory.dto.PurchasePostingBasis;
import com.lingmao.scm.module.inventory.dto.PurchasePostingHistory;
import com.lingmao.scm.module.inventory.entity.InvDoc;
import com.lingmao.scm.module.inventory.entity.InvDocItem;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.mapper.InvDocItemMapper;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 已持有库存单头和采购来源单锁，尚未获取库存余额锁。每次以累计净量为目标，差额落当前行；
 * 原事件永不更新，反审核由 StockLedger 逐笔复制原值冲销，重审产生新事件。
 */
final class PurchasePostingPrecision {
    private PurchasePostingPrecision() { }

    static void allocate(InvDoc document, List<InvDocItem> lines, Map<Long, PurchasePostingBasis> basis,
                         StockLogMapper logs, InvDocItemMapper items) {
        if (lines.isEmpty() || lines.stream().anyMatch(line -> line.getSourceItemId() == null)) {
            throw changed("采购明细缺少来源，请重新载入来源单");
        }
        var groups = lines.stream().collect(Collectors.groupingBy(InvDocItem::getSourceItemId, TreeMap::new, Collectors.toList()));
        var history = logs.purchaseHistory(document.getSourceId(), groups.keySet()).stream()
                .collect(Collectors.groupingBy(PurchasePostingHistory::sourceItemId));
        int direction = document.getDocType() == InvDocType.PURCHASE_IN ? 1 : -1;
        for (var entry : groups.entrySet()) {
            PurchasePostingBasis frozen = basis.get(entry.getKey());
            if (frozen == null || frozen.conversionFactor() == null || frozen.conversionFactor().signum() <= 0
                    || frozen.purchasePrice() == null || frozen.purchasePrice().signum() < 0) {
                throw changed("来源换算系数或采购价不完整，请核对来源单");
            }
            List<InvDocItem> current = entry.getValue().stream().sorted(Comparator.comparing(InvDocItem::getSeq)
                    .thenComparing(InvDocItem::getId)).toList();
            for (InvDocItem line : current) {
                if (line.getMaterialSkuId() != frozen.materialSkuId() || !same(line.getConversionFactor(), frozen.conversionFactor())
                        || !same(line.getPurchasePrice(), frozen.purchasePrice())) {
                    throw changed("草稿的冻结换算系数或采购价与来源不一致，请重新载入来源后再审核");
                }
                if (line.getPurchaseQty() == null || line.getPurchaseQty().signum() <= 0) {
                    throw new BizException(400, "采购单位数量必须大于零");
                }
            }
            BigDecimal netPurchase = BigDecimal.ZERO;
            BigDecimal netStock = BigDecimal.ZERO;
            BigDecimal netAmount = BigDecimal.ZERO;
            for (PurchasePostingHistory previous : history.getOrDefault(entry.getKey(), List.of())) {
                if (!Objects.equals(previous.roundingVersion(), 1) || previous.materialSkuId() != frozen.materialSkuId()
                        || !same(previous.conversionFactor(), frozen.conversionFactor())
                        || !same(previous.purchasePrice(), frozen.purchasePrice())
                        || previous.purchaseQty() == null || previous.quantity() == null || previous.amount() == null) {
                    throw changed("来源已有流水缺少一致的冻结换算快照，请先核对并办理调整，不能自动改写历史账");
                }
                int sign = "PURCHASE_IN".equals(previous.changeType()) ? 1 : -1;
                if (previous.purchaseQty().signum() <= 0 || previous.quantity().signum() != sign
                        || previous.amount().signum() * sign < 0) {
                    throw changed("来源历史流水数量或金额方向不一致，请先核对调整");
                }
                netPurchase = netPurchase.add(previous.purchaseQty().multiply(BigDecimal.valueOf(sign)));
                netStock = netStock.add(previous.quantity());
                netAmount = netAmount.add(previous.amount());
            }
            BigDecimal thisPurchase = current.stream().map(InvDocItem::getPurchaseQty).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(BigDecimal.valueOf(direction));
            if (netPurchase.signum() < 0 || netStock.signum() < 0 || netAmount.signum() < 0
                    || !same(netPurchase.add(thisPurchase), frozen.netPurchaseQty())) {
                throw changed("来源累计数与未冲销库存流水不一致，请先核对并办理调整");
            }
            for (InvDocItem line : current) {
                BigDecimal nextPurchase = netPurchase.add(line.getPurchaseQty().multiply(BigDecimal.valueOf(direction)));
                BigDecimal nextStock = nextPurchase.multiply(frozen.conversionFactor()).setScale(3, RoundingMode.HALF_UP);
                BigDecimal nextAmount = nextPurchase.multiply(frozen.purchasePrice()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal stockDelta = nextStock.subtract(netStock).multiply(BigDecimal.valueOf(direction));
                BigDecimal amountDelta = nextAmount.subtract(netAmount).multiply(BigDecimal.valueOf(direction));
                if (nextPurchase.signum() < 0 || stockDelta.signum() <= 0 || amountDelta.signum() < 0) {
                    throw changed("本批换算尾差会使明细数量或金额反向，请合并小批数量或先核对调整");
                }
                line.setQty(NumericLimits.quantity(stockDelta, "第 " + line.getSeq() + " 行分批换算："));
                line.setAmount(NumericLimits.amount(amountDelta, "第 " + line.getSeq() + " 行分批计价："));
                line.setPrice(NumericLimits.price(frozen.purchasePrice().divide(frozen.conversionFactor(), 4, RoundingMode.HALF_UP), "分批换算："));
                if (items.applyPurchasePrecision(line) != 1) throw changed("采购入库明细已变化，请刷新后重试");
                netPurchase = nextPurchase;
                netStock = nextStock;
                netAmount = nextAmount;
            }
        }
    }

    private static boolean same(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) == 0; }
    private static BizException changed(String message) { return new BizException(ErrorCodes.STATE_CHANGED, message); }
}
