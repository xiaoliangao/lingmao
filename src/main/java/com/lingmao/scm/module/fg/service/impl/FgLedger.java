package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.fg.dto.FgShortRow;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.entity.FgStock;
import com.lingmao.scm.module.fg.entity.FgStockLog;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 成品的过账引擎（RFC 008 §3.6，照物料的 StockLedger 写一份：fg_stock 只能 fg 写，物料那边还有物权、缸号、盘点）。
 * <ol>
 *   <li>同仓同 SKU 的几笔合成一笔，按（仓库、SKU）排好序再改：两张单同时过账，加锁的顺序一样，不会死锁</li>
 *   <li>加的：没有库存行就建、有就加；减的：条件更新 qty + 变动 >= 0，不够就不改</li>
 *   <li>不够的不马上停，整张单都试一遍、收集起来一起报 1001；事务回滚，前面改成功的也一起退掉</li>
 *   <li>都够了再写日志：每个仓每个 SKU 一条（一张单一个 SKU 只有一行），结存是改完的数</li>
 * </ol>
 * 只在调用方的事务里跑（单据的比较并交换已经锁住了这张单）
 */
@Component
@RequiredArgsConstructor
class FgLedger {

    private static final Comparator<Key> ORDER = Comparator.comparingLong(Key::warehouseId).thenComparingLong(Key::skuId);

    private final FgStockMapper stockMapper;
    private final FgStockLogMapper logMapper;
    private final FgStyles styles;
    private final WarehouseService warehouseService;

    /** 一笔变动：入为正、出为负，0 的不记；seq 是单上第几行（1001 的清单里写） */
    record Move(long warehouseId, long skuId, int delta, int seq) {
    }

    /** 一张单的过账；反审核用 reversed()，日志类型不变、数量反方向 */
    record Posting(long docId, String docNo, FgDocType type, LocalDate bizDate, List<Move> moves) {

        Posting reversed() {
            return new Posting(docId, docNo, type, bizDate,
                    moves.stream().map(m -> new Move(m.warehouseId(), m.skuId(), -m.delta(), m.seq())).toList());
        }
    }

    private record Key(long warehouseId, long skuId) {
    }

    private record Short(Key key, List<Move> moves, int stockQty, int needQty) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(Posting posting) {
        Map<Key, List<Move>> groups = new TreeMap<>(ORDER);
        for (Move m : posting.moves()) {
            if (m.delta() != 0) {
                groups.computeIfAbsent(new Key(m.warehouseId(), m.skuId()), k -> new ArrayList<>()).add(m);
            }
        }
        Map<Key, Integer> after = new HashMap<>();
        List<Short> shorts = new ArrayList<>();
        for (Map.Entry<Key, List<Move>> e : groups.entrySet()) {
            Key key = e.getKey();
            int delta = Math.toIntExact(e.getValue().stream().mapToLong(Move::delta).sum());
            Integer qty = delta >= 0
                    ? stockMapper.add(key.warehouseId(), key.skuId(), delta)
                    : stockMapper.deduct(key.warehouseId(), key.skuId(), delta);
            if (qty == null) {
                shorts.add(new Short(key, e.getValue(), current(key), -delta));
            } else {
                after.put(key, qty);
            }
        }
        if (!shorts.isEmpty()) {
            throw shortage(shorts);
        }
        for (Map.Entry<Key, List<Move>> e : groups.entrySet()) {
            FgStockLog log = new FgStockLog();
            log.setFgDocId(posting.docId());
            log.setChangeType(posting.type());
            log.setDocNo(posting.docNo());
            log.setBizDate(posting.bizDate());
            log.setWarehouseId(e.getKey().warehouseId());
            log.setStyleSkuId(e.getKey().skuId());
            log.setLocationId(0L);
            log.setChangeQty(e.getValue().stream().mapToInt(Move::delta).sum());
            log.setBalanceQty(after.get(e.getKey()));
            logMapper.insert(log);
        }
    }

    private int current(Key key) {
        return stockMapper.selectBySkus(key.warehouseId(), List.of(key.skuId())).stream()
                .map(FgStock::getQty).findFirst().orElse(0);
    }

    /** 1001：data 是 FgShortRow[]，msg 一句话能单独看懂（批量审核只显示 msg） */
    private BizException shortage(List<Short> shorts) {
        Map<Long, StyleOrderInfo> bySku = styles.bySku(shorts.stream().map(s -> s.key().skuId()).toList());
        Map<Long, String> warehouses = warehouseService.names(shorts.stream().map(s -> s.key().warehouseId()).distinct().toList());
        List<FgShortRow> rows = shorts.stream().map(s -> {
            long skuId = s.key().skuId();
            StyleOrderInfo style = bySku.get(skuId);
            StyleOrderInfo.Sku sku = FgStyles.sku(style, skuId);
            String seqs = s.moves().stream().map(Move::seq).distinct().sorted().map(String::valueOf).collect(Collectors.joining("、"));
            return new FgShortRow(seqs, warehouses.get(s.key().warehouseId()), FgStyles.code(style, skuId),
                    style == null ? null : style.name(), FgStyles.colorName(style, sku), sku == null ? null : sku.sizeCode(),
                    s.stockQty(), s.needQty(), s.needQty() - s.stockQty());
        }).toList();
        FgShortRow first = rows.getFirst();
        String msg = "库存不够：" + first.skuCode() + " 库存 " + first.stockQty() + "，差 " + first.shortQty() + " 件"
                + (rows.size() > 1 ? "，等 " + rows.size() + " 个" : "");
        return new BizException(ErrorCodes.STOCK_SHORTAGE, msg, rows);
    }
}
