package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.inventory.dto.StockShortRow;
import com.lingmao.scm.module.inventory.entity.Stock;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import com.lingmao.scm.module.inventory.entity.StockLog;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 过账引擎（架构 §5.2）：入库单、出库单、盘点单审核时把自己变成一张"变动"清单交给这里，反审核交反过来的那张。
 * <ol>
 *   <li>同仓同 SKU 的几笔合成一笔，按（仓库、SKU）排好序再改：两张单同时过账，加锁的顺序一样，不会死锁</li>
 *   <li>加的：没有库存行就建、有就加（一条 INSERT … ON CONFLICT）；减的：条件更新 qty + 变动 >= 0，不够就不改</li>
 *   <li>不够的不马上停，整张单都试一遍、收集起来一起报 1001；事务回滚，前面加减成功的也一起退掉</li>
 *   <li>都够了再写库存日志：一笔一条，不合并（同 SKU 几行缸号不同，要能按缸号查），结存按这一笔之后算</li>
 * </ol>
 * 只在调用方的事务里跑（单据的比较并交换已经锁住了这张单），自己不开事务。
 * 物权一期都是自有、不分货位；TRADE 的客供料来了再给 Move 加物权
 */
@Component
@RequiredArgsConstructor
class StockLedger {

    private static final Comparator<Key> ORDER = Comparator.comparingLong(Key::warehouseId).thenComparingLong(Key::skuId);

    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final MaterialService materialService;
    private final WarehouseService warehouseService;
    private final DictDataService dictDataService;

    /**
     * 一笔变动
     *
     * @param delta       入为正、出为负；0 的不记
     * @param seq         单上第几行（1001 的清单里写）
     * @param docItemId   入库出库单的明细，盘点单是 null
     * @param countItemId 盘点明细，入库出库单是 null
     */
    record Move(long warehouseId, long skuId, BigDecimal delta, StockChangeType changeType, int seq,
                Long docItemId, Long countItemId, String dyeLot, BigDecimal unitPrice, BigDecimal amount) {

        Move(long warehouseId, long skuId, BigDecimal delta, StockChangeType changeType, int seq,
             Long docItemId, Long countItemId, String dyeLot) {
            this(warehouseId, skuId, delta, changeType, seq, docItemId, countItemId, dyeLot, null, null);
        }

        Move reversed() {
            return new Move(warehouseId, skuId, delta.negate(), changeType, seq, docItemId, countItemId, dyeLot,
                    unitPrice, amount == null ? null : amount.negate());
        }
    }

    /** 一张单的过账：invDocId、countId 二选一 */
    record Posting(Long invDocId, Long countId, String docNo, LocalDate bizDate, List<Move> moves, boolean reverse) {

        Posting(Long invDocId, Long countId, String docNo, LocalDate bizDate, List<Move> moves) {
            this(invDocId, countId, docNo, bizDate, moves, false);
        }

        /** 反审核：每笔反过来，日志类型不变（数量是反方向的） */
        Posting reversed() {
            return new Posting(invDocId, countId, docNo, bizDate, moves.stream().map(Move::reversed).toList(), !reverse);
        }
    }

    private record Key(long warehouseId, long skuId) {
    }

    private record EventKey(long warehouseId, long skuId, Long docItemId, Long countItemId) {
    }

    private record Short(Key key, List<Move> moves, BigDecimal stockQty, BigDecimal needQty) {
    }

    /** 盘点拍账/提交共用的键锁；每个键ensure后立即锁，不能先插全部缺行再倒回锁旧行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Stock> lockBalances(long warehouseId, java.util.Collection<Long> skuIds) {
        Map<Long, Stock> out = new java.util.LinkedHashMap<>();
        for (Long skuId : new java.util.TreeSet<>(skuIds)) {
            stockMapper.ensureOwn(warehouseId, skuId);
            out.put(skuId, stockMapper.lockOwn(warehouseId, skuId));
        }
        return out;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(Posting posting) {
        Map<EventKey, StockLog> originals = new HashMap<>();
        if (posting.reverse()) {
            stockLogMapper.reversibleOf(posting.invDocId(), posting.countId()).forEach(l ->
                    originals.put(new EventKey(l.getWarehouseId(), l.getMaterialSkuId(), l.getInvDocItemId(), l.getCountItemId()), l));
        }
        Map<Key, List<Move>> groups = new TreeMap<>(ORDER);
        for (Move m : posting.moves()) {
            if (m.delta().signum() != 0) {
                groups.computeIfAbsent(new Key(m.warehouseId(), m.skuId()), k -> new ArrayList<>()).add(m);
            }
        }
        Map<Key, BigDecimal> after = new HashMap<>();
        List<Short> shorts = new ArrayList<>();
        for (Map.Entry<Key, List<Move>> e : groups.entrySet()) {
            Key key = e.getKey();
            BigDecimal delta = sum(e.getValue());
            BigDecimal qty = delta.signum() >= 0
                    ? stockMapper.add(key.warehouseId(), key.skuId(), Stock.OWN, 0, 0, delta)
                    : stockMapper.deduct(key.warehouseId(), key.skuId(), Stock.OWN, 0, 0, delta);
            if (qty == null) {
                shorts.add(new Short(key, e.getValue(), current(key), delta.negate()));
            } else {
                after.put(key, qty);
            }
        }
        if (!shorts.isEmpty()) {
            throw shortage(shorts);
        }
        Map<Long, MaterialSkuBrief> briefs = posting.reverse() || groups.isEmpty() ? Map.of()
                : materialService.skuBriefs(groups.keySet().stream().map(Key::skuId).distinct().toList());
        for (Map.Entry<Key, List<Move>> e : groups.entrySet()) {
            BigDecimal balance = after.get(e.getKey()).subtract(sum(e.getValue()));
            for (Move m : e.getValue()) {
                balance = balance.add(m.delta());
                StockLog event = log(posting, m, balance);
                if (posting.reverse()) {
                    StockLog original = originals.get(new EventKey(m.warehouseId(), m.skuId(), m.docItemId(), m.countItemId()));
                    if (original == null || original.getChangeQty().negate().compareTo(m.delta()) != 0) {
                        throw new BizException(ErrorCodes.STATE_CHANGED, "找不到本次反审核对应的原库存流水，请刷新并核对原单");
                    }
                    event.setReversalOfLogId(original.getId());
                    event.setStockUnit(original.getStockUnit());
                    event.setCurrency(original.getCurrency());
                    event.setValuationStatus(original.getValuationStatus() == null ? "UNKNOWN" : original.getValuationStatus());
                    event.setUnitPrice(original.getUnitPrice());
                    event.setAmount(original.getAmount() == null ? null : original.getAmount().negate());
                } else {
                    MaterialSkuBrief brief = briefs.get(m.skuId());
                    event.setStockUnit(brief == null ? null : brief.getStockUnit());
                    if (m.unitPrice() != null && m.amount() != null && event.getStockUnit() != null) {
                        event.setUnitPrice(m.unitPrice());
                        event.setAmount(m.amount());
                        event.setCurrency("CNY");
                        event.setValuationStatus("RECORDED");
                    }
                }
                stockLogMapper.insert(event);
            }
        }
    }

    private static BigDecimal sum(List<Move> moves) {
        return moves.stream().map(Move::delta).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal current(Key key) {
        return stockMapper.selectBySkus(key.warehouseId(), List.of(key.skuId())).stream()
                .map(Stock::getQty).findFirst().orElse(BigDecimal.ZERO);
    }

    private static StockLog log(Posting posting, Move m, BigDecimal balance) {
        StockLog log = new StockLog();
        log.setInvDocId(posting.invDocId());
        log.setInvDocItemId(m.docItemId());
        log.setCountId(posting.countId());
        log.setCountItemId(m.countItemId());
        log.setChangeType(m.changeType());
        log.setDocNo(posting.docNo());
        log.setBizDate(posting.bizDate());
        log.setWarehouseId(m.warehouseId());
        log.setMaterialSkuId(m.skuId());
        log.setOwnerType(Stock.OWN);
        log.setOwnerId(0L);
        log.setLocationId(0L);
        log.setChangeQty(m.delta());
        log.setBalanceQty(balance);
        log.setDyeLot(m.dyeLot());
        log.setValuationStatus("UNKNOWN");
        return log;
    }

    /** 1001：data 是 StockShortRow[]，msg 一句话能单独看懂（批量审核只显示 msg，docs/api/04 §3.4.1） */
    private BizException shortage(List<Short> shorts) {
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(shorts.stream().map(s -> s.key().skuId()).toList());
        Map<Long, String> warehouses = shorts.stream().map(s -> s.key().warehouseId()).distinct()
                .collect(Collectors.toMap(Function.identity(), id -> warehouseService.getById(id).getName()));
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        List<StockShortRow> rows = shorts.stream().map(s -> {
            MaterialSkuBrief b = briefs.get(s.key().skuId());
            String seqs = s.moves().stream().map(Move::seq).distinct().sorted().map(String::valueOf)
                    .collect(Collectors.joining("、"));
            return new StockShortRow(seqs, warehouses.get(s.key().warehouseId()), b == null ? null : b.getSkuCode(),
                    b == null ? null : b.fullName(), s.stockQty(), s.needQty(), s.needQty().subtract(s.stockQty()),
                    b == null ? null : units.getOrDefault(b.getStockUnit(), b.getStockUnit()));
        }).toList();
        StockShortRow first = rows.getFirst();
        String msg = "库存不够：" + first.materialName() + " 库存 " + InvTexts.plain(first.stockQty()) + "，差 "
                + InvTexts.plain(first.shortQty()) + " " + first.unit() + (rows.size() > 1 ? "，等 " + rows.size() + " 种" : "");
        return new BizException(ErrorCodes.STOCK_SHORTAGE, msg, rows);
    }
}
