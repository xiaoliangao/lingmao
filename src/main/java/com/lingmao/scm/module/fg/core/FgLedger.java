package com.lingmao.scm.module.fg.core;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.fg.dto.FgShortRow;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.entity.FgStockLog;
import com.lingmao.scm.module.fg.mapper.FgStockLogMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
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
 * 成品过账核心。调用方在同一事务中先锁来源，再 CAS 单头，最后调用本类。
 * 全部库存键先收集，再按 SKU、主体、仓库、货位递增处理；任何一键失败整单回滚。
 * 反审只冲销未冲销原事件，不能从后来可编辑的草稿行重建历史数量。
 */
@Component
@RequiredArgsConstructor
public class FgLedger {
    private static final int MAX_SHORTAGE_LOOKUP_ROWS = 50_000;
    private static final Comparator<Key> ORDER = Comparator.comparingLong(Key::skuId)
            .thenComparingLong(Key::companyId).thenComparingLong(Key::warehouseId).thenComparingLong(Key::locationId);

    private final FgStockMapper stockMapper;
    private final FgStockLogMapper logMapper;
    private final StyleService styleService;
    private final WarehouseService warehouseService;

    public record Move(long warehouseId, long skuId, long locationId, int delta, int seq) {
        public Move {
            if (locationId != 0) throw new BizException(400, "成品单据尚未启用库位，库存键必须使用预留库位 0");
        }

        public Move(long warehouseId, long skuId, int delta, int seq) {
            this(warehouseId, skuId, 0, delta, seq);
        }
    }

    public record Posting(long companyId, long docId, String docNo, FgDocType type, LocalDate bizDate, List<Move> moves) {
        public Posting {
            if (companyId <= 0 || docId <= 0 || docNo == null || docNo.isBlank() || type == null || bizDate == null
                    || moves == null || moves.isEmpty() || moves.stream().anyMatch(java.util.Objects::isNull)) {
                throw new BizException(400, "成品过账缺少主体、单据或明细");
            }
            moves = List.copyOf(moves);
        }
    }

    private record Key(long companyId, long warehouseId, long skuId, long locationId) { }
    private record Movement(Key key, int delta, List<Integer> seqs, FgStockLog original) { }
    private record Shortage(Movement movement, int stockQty) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(Posting posting) {
        if (!logMapper.reversibleOf(posting.companyId(), posting.docId()).isEmpty()) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张成品单已过账，请刷新后重试");
        }
        Map<Key, List<Move>> groups = new TreeMap<>(ORDER);
        for (Move move : posting.moves()) {
            if (move == null || move.warehouseId() <= 0 || move.skuId() <= 0 || move.locationId() != 0 || move.seq() <= 0
                    || move.delta() == 0 || move.delta() == Integer.MIN_VALUE
                    || (posting.type().getKind() == FgDocKind.IN ? move.delta() < 0 : move.delta() > 0)) {
                throw new BizException(400, "成品过账明细的库存键、数量或方向不正确");
            }
            Key key = new Key(posting.companyId(), move.warehouseId(), move.skuId(), move.locationId());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(move);
        }
        List<Movement> movements = new ArrayList<>();
        for (Map.Entry<Key, List<Move>> entry : groups.entrySet()) {
            long delta = entry.getValue().stream().mapToLong(Move::delta).sum();
            if (delta > Integer.MAX_VALUE || delta < -Integer.MAX_VALUE) {
                throw new BizException(400, "单个 SKU 的过账数量超出允许范围");
            }
            movements.add(new Movement(entry.getKey(), (int) delta,
                    entry.getValue().stream().map(Move::seq).distinct().sorted().toList(), null));
        }
        apply(posting.docId(), posting.docNo(), posting.type(), posting.bizDate(), movements);
    }

    /** 反审的数量、仓库、货位、日期均来自原事件；调用方已经完成来源锁及 APPROVED→DRAFT CAS。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(long companyId, long docId) {
        List<FgStockLog> originals = logMapper.reversibleOf(companyId, docId);
        if (originals.isEmpty()) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张成品单没有可冲销的库存流水，请刷新后重试");
        }
        Map<Key, Movement> ordered = new TreeMap<>(ORDER);
        for (FgStockLog original : originals) {
            Key key = new Key(original.getCompanyId(), original.getWarehouseId(), original.getStyleSkuId(), original.getLocationId());
            if (original.getChangeQty() == Integer.MIN_VALUE || ordered.containsKey(key)) {
                throw new BizException(ErrorCodes.STATE_CHANGED, "这张成品单的库存流水需要对账，不能重复冲销");
            }
            ordered.put(key, new Movement(key, -original.getChangeQty(), List.of(), original));
        }
        FgStockLog first = originals.getFirst();
        apply(docId, first.getDocNo(), first.getChangeType(), first.getBizDate(), new ArrayList<>(ordered.values()));
    }

    private void apply(long docId, String docNo, FgDocType type, LocalDate date, List<Movement> movements) {
        Map<Key, Integer> balances = new HashMap<>();
        List<Shortage> shortages = new ArrayList<>();
        for (Movement move : movements) {
            Key key = move.key();
            Integer qty = move.delta() > 0
                    ? stockMapper.add(key.companyId(), key.warehouseId(), key.skuId(), key.locationId(), move.delta())
                    : stockMapper.deduct(key.companyId(), key.warehouseId(), key.skuId(), key.locationId(), move.delta());
            if (qty == null) {
                if (move.delta() > 0) throw new BizException(400, "成品库存数量超出允许范围，请核对单据");
                Integer current = stockMapper.current(key.companyId(), key.warehouseId(), key.skuId(), key.locationId());
                // 条件扣减失败后可能刚好有另一事务补货；不能把新余额计算成负的“缺货数”。
                if (current != null && current >= -move.delta()) {
                    throw new BizException(ErrorCodes.STATE_CHANGED, "成品库存已变化，请刷新后重试");
                }
                shortages.add(new Shortage(move, current == null ? 0 : current));
            } else {
                balances.put(key, qty);
            }
        }
        if (!shortages.isEmpty()) throw shortage(shortages);
        for (Movement move : movements) {
            Key key = move.key();
            FgStockLog original = move.original();
            FgStockLog log = new FgStockLog();
            log.setFgDocId(docId);
            log.setCompanyId(key.companyId());
            log.setChangeType(type);
            log.setDocNo(docNo);
            log.setBizDate(original == null ? date : original.getBizDate());
            log.setWarehouseId(key.warehouseId());
            log.setStyleSkuId(key.skuId());
            log.setLocationId(key.locationId());
            log.setChangeQty(move.delta());
            log.setBalanceQty(balances.get(key));
            log.setReversalOfLogId(original == null ? null : original.getId());
            logMapper.insert(log);
        }
    }

    private BizException shortage(List<Shortage> shortages) {
        List<Long> skuIds = shortages.stream().map(s -> s.movement().key().skuId()).distinct().toList();
        Map<Long, StyleOrderInfo> styles = styleService.boundedOrderInfosOfSkus(skuIds, MAX_SHORTAGE_LOOKUP_ROWS);
        Map<Long, String> warehouses = warehouseService.names(shortages.stream()
                .map(s -> s.movement().key().warehouseId()).distinct().toList());
        List<FgShortRow> rows = shortages.stream().map(s -> {
            Key key = s.movement().key();
            StyleOrderInfo style = styles.get(key.skuId());
            StyleOrderInfo.Sku sku = style == null ? null : style.skus().stream().filter(k -> k.id() == key.skuId()).findFirst().orElse(null);
            String color = style == null || sku == null ? null : style.colors().stream()
                    .filter(c -> c.id() == sku.colorId()).map(StyleOrderInfo.Color::name).findFirst().orElse(null);
            String seqs = s.movement().seqs().stream().map(String::valueOf).collect(Collectors.joining("、"));
            int need = -s.movement().delta();
            return new FgShortRow(seqs, warehouses.get(key.warehouseId()), sku == null ? "SKU " + key.skuId() : sku.code(),
                    style == null ? null : style.name(), color, sku == null ? null : sku.sizeCode(), s.stockQty(), need, need - s.stockQty());
        }).toList();
        FgShortRow first = rows.getFirst();
        return new BizException(ErrorCodes.STOCK_SHORTAGE,
                "库存不够：" + first.skuCode() + " 库存 " + first.stockQty() + "，差 " + first.shortQty() + " 件"
                        + (rows.size() > 1 ? "，等 " + rows.size() + " 个" : ""), rows);
    }
}
