package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.production.dto.LedgerMaterialQuery;
import com.lingmao.scm.module.production.dto.LedgerMaterialRowVO;
import com.lingmao.scm.module.production.dto.LedgerOrderQuery;
import com.lingmao.scm.module.production.dto.LedgerOrderRowVO;
import com.lingmao.scm.module.production.dto.LedgerStatus;
import com.lingmao.scm.module.production.mapper.FactoryLedgerMapper;
import com.lingmao.scm.module.production.service.FactoryLedgerService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.security.DataScope;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工厂料账（RFC 015 §4.2）。按生产单：SQL 分页，超耗、该退几种料按这一页的单现算。
 * 按工厂物料：工厂仓的账面 + 还没核销的单的应耗，在内存里合起来分页（工厂仓 × 料的行数不多）
 */
@Service
@RequiredArgsConstructor
public class FactoryLedgerServiceImpl implements FactoryLedgerService {

    private final FactoryLedgerMapper ledgerMapper;
    private final ProductionSupport support;
    private final MaterialService materialService;
    private final DictDataService dictDataService;

    @Override
    public PageResult<LedgerOrderRowVO> orders(LedgerOrderQuery query) {
        PageResult<LedgerOrderRowVO> page = PageResult.of(ledgerMapper.selectOrders(query.toPage(), query, DataScope.current(false)));
        List<Long> ids = page.getList().stream().map(LedgerOrderRowVO::getId).toList();
        if (ids.isEmpty()) {
            return page;
        }
        Map<Long, Map<Long, BigDecimal>> consume = support.consumes(ids);
        Map<Long, Map<Long, BigDecimal>> issued = support.issued(ids, null);
        Map<Long, Map<Long, BigDecimal>> settled = support.settled(ids);
        for (LedgerOrderRowVO r : page.getList()) {
            Map<Long, BigDecimal> c = consume.getOrDefault(r.getId(), Map.of());
            Map<Long, BigDecimal> got = issued.getOrDefault(r.getId(), Map.of());
            Map<Long, BigDecimal> s = r.getLedgerStatus() == LedgerStatus.SETTLED ? settled.getOrDefault(r.getId(), Map.of()) : null;
            r.setOverCount(s == null ? 0 : overCount(c, s));
            r.setReturnCount(returnCount(c, got, s));
        }
        return page;
    }

    /** 实耗 &gt; 应耗的料几种 */
    static int overCount(Map<Long, BigDecimal> consume, Map<Long, BigDecimal> settled) {
        return (int) settled.entrySet().stream()
                .filter(e -> e.getValue().compareTo(consume.getOrDefault(e.getKey(), BigDecimal.ZERO)) > 0).count();
    }

    /** 结余 &gt; 0（该退回来）的料几种：结余 = 已领 − 实耗，没核销（settled 是 null）用应耗估 */
    static int returnCount(Map<Long, BigDecimal> consume, Map<Long, BigDecimal> issued, Map<Long, BigDecimal> settled) {
        int n = 0;
        for (Map.Entry<Long, BigDecimal> e : issued.entrySet()) {
            BigDecimal used = settled != null ? settled.getOrDefault(e.getKey(), BigDecimal.ZERO)
                    : consume.getOrDefault(e.getKey(), BigDecimal.ZERO);
            if (e.getValue().subtract(used).signum() > 0) {
                n++;
            }
        }
        return n;
    }

    @Override
    public Map<String, Long> counts(LedgerOrderQuery query) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (LedgerStatus s : LedgerStatus.values()) {
            out.put(s.name(), 0L);
        }
        ledgerMapper.selectCounts(query, DataScope.current(false)).forEach(c -> out.put(c.status(), c.count()));
        return out;
    }

    @Override
    public PageResult<LedgerMaterialRowVO> materials(LedgerMaterialQuery query) {
        List<LedgerMaterialRowVO> all = materialRows(query);
        int from = (int) Math.min((query.getPage() - 1) * query.getSize(), all.size());
        int to = (int) Math.min(from + query.getSize(), all.size());
        return new PageResult<>(new ArrayList<>(all.subList(from, to)), all.size());
    }

    /** 全部行：只列账面不是 0、或者未核销的耗料不是 0 的；按加工厂、SKU 码排 */
    List<LedgerMaterialRowVO> materialRows(LedgerMaterialQuery query) {
        Map<Key, Acc> acc = new HashMap<>();
        for (FactoryLedgerMapper.FactoryStockRow s : ledgerMapper.selectFactoryStock(query.getFactoryId())) {
            Acc a = acc.computeIfAbsent(new Key(s.factoryId(), s.skuId()), k -> new Acc());
            a.book = a.book.add(s.qty());
            a.warehouseId = s.warehouseId();
        }
        List<FactoryLedgerMapper.OpenOrderRow> open = ledgerMapper.selectOpenOrders(query.getFactoryId());
        Map<Long, Long> factoryOf = open.stream().collect(Collectors.toMap(FactoryLedgerMapper.OpenOrderRow::id,
                FactoryLedgerMapper.OpenOrderRow::factoryId));
        support.consumes(factoryOf.keySet()).forEach((orderId, bySku) -> bySku.forEach((sku, qty) -> {
            if (qty.signum() == 0) {
                return;
            }
            Acc a = acc.computeIfAbsent(new Key(factoryOf.get(orderId), sku), k -> new Acc());
            a.pending = a.pending.add(qty);
            a.orders.add(orderId);
        }));
        acc.values().removeIf(a -> a.book.signum() == 0 && a.pending.signum() == 0);
        if (acc.isEmpty()) {
            return List.of();
        }
        Set<Long> factories = acc.keySet().stream().map(Key::factoryId).collect(Collectors.toSet());
        Map<Long, String> names = ledgerMapper.selectFactoryNames(factories).stream()
                .collect(Collectors.toMap(FactoryLedgerMapper.FactoryName::id, FactoryLedgerMapper.FactoryName::shortName));
        Map<Long, Long> warehouses = ledgerMapper.selectFactoryWarehouses(factories).stream()
                .collect(Collectors.toMap(FactoryLedgerMapper.FactoryWarehouse::factoryId, FactoryLedgerMapper.FactoryWarehouse::id,
                        (a, b) -> a));
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(acc.keySet().stream().map(Key::skuId).collect(Collectors.toSet()));
        String kw = query.getKeyword() == null || query.getKeyword().isBlank() ? null : query.getKeyword().strip().toUpperCase(Locale.ROOT);
        List<LedgerMaterialRowVO> rows = new ArrayList<>();
        acc.forEach((k, a) -> {
            MaterialSkuBrief b = briefs.get(k.skuId());
            if (kw != null && (b == null || !(contains(b.getSkuCode(), kw) || contains(b.getMaterialCode(), kw)
                    || contains(b.getMaterialName(), kw)))) {
                return;
            }
            LedgerMaterialRowVO r = new LedgerMaterialRowVO();
            r.fillFrom(b);
            r.setMaterialSkuId(k.skuId());
            r.setFactoryId(k.factoryId());
            r.setFactoryShortName(names.get(k.factoryId()));
            r.setWarehouseId(a.warehouseId != null ? a.warehouseId : warehouses.get(k.factoryId()));
            r.setBookQty(Num.qty(a.book));
            r.setPendingQty(Num.qty(a.pending));
            r.setEstimateQty(Num.qty(a.book.subtract(a.pending)));
            r.setOrderCount(a.orders.size());
            rows.add(r);
        });
        rows.sort(Comparator.comparing(LedgerMaterialRowVO::getFactoryShortName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LedgerMaterialRowVO::getSkuCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    private static boolean contains(String text, String kw) {
        return text != null && text.toUpperCase(Locale.ROOT).contains(kw);
    }

    private record Key(long factoryId, long skuId) {
    }

    private static final class Acc {
        BigDecimal book = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        Long warehouseId;
        final Set<Long> orders = new HashSet<>();
    }

    @Data
    static class ExcelRow {
        private String factory;
        private String skuCode;
        private String materialName;
        private String colorName;
        private String spec;
        private String unit;
        private BigDecimal bookQty;
        private BigDecimal pendingQty;
        private BigDecimal estimateQty;
        private Integer orderCount;
    }

    private static final ExcelSchema<ExcelRow> MATERIALS = ExcelSchema.of("工厂料账", ExcelRow::new,
            ExcelColumn.text("加工厂", ExcelRow::getFactory, ExcelRow::setFactory),
            ExcelColumn.text("SKU 码", ExcelRow::getSkuCode, ExcelRow::setSkuCode),
            ExcelColumn.text("物料名称", ExcelRow::getMaterialName, ExcelRow::setMaterialName),
            ExcelColumn.text("颜色", ExcelRow::getColorName, ExcelRow::setColorName),
            ExcelColumn.text("规格", ExcelRow::getSpec, ExcelRow::setSpec),
            ExcelColumn.text("单位", ExcelRow::getUnit, ExcelRow::setUnit),
            ExcelColumn.decimal("账面", 3, ExcelRow::getBookQty, ExcelRow::setBookQty),
            ExcelColumn.decimal("未核销的耗料", 3, ExcelRow::getPendingQty, ExcelRow::setPendingQty),
            ExcelColumn.decimal("估计手上", 3, ExcelRow::getEstimateQty, ExcelRow::setEstimateQty),
            ExcelColumn.integer("涉及几张单", ExcelRow::getOrderCount, ExcelRow::setOrderCount));

    @Override
    public byte[] exportMaterials(LedgerMaterialQuery query) {
        Map<String, String> units = dictDataService.listByType("unit").stream()
                .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
        return Excel.export(MATERIALS, materialRows(query).stream().limit(Excel.MAX_EXPORT_ROWS + 1L).map(r -> {
            ExcelRow e = new ExcelRow();
            e.setFactory(r.getFactoryShortName());
            e.setSkuCode(r.getSkuCode());
            e.setMaterialName(r.getMaterialName());
            e.setColorName(r.getColorName());
            e.setSpec(r.getSpec());
            e.setUnit(r.getStockUnit() == null ? null : units.getOrDefault(r.getStockUnit(), r.getStockUnit()));
            e.setBookQty(r.getBookQty());
            e.setPendingQty(r.getPendingQty());
            e.setEstimateQty(r.getEstimateQty());
            e.setOrderCount(r.getOrderCount());
            return e;
        }).toList());
    }

    @Override
    public long toSettleCount() {
        return ledgerMapper.countToSettle(DataScope.current(false));
    }
}
