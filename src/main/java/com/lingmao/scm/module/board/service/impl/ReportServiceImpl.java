package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.board.dto.FactoryPerfRow;
import com.lingmao.scm.module.board.dto.LineWipRow;
import com.lingmao.scm.module.board.dto.OrderMarginPage;
import com.lingmao.scm.module.board.dto.OrderMarginQuery;
import com.lingmao.scm.module.board.dto.OrderMarginRow;
import com.lingmao.scm.module.board.dto.PerfQuery;
import com.lingmao.scm.module.board.dto.PerfResult;
import com.lingmao.scm.module.board.dto.PurchasePerfRow;
import com.lingmao.scm.module.board.dto.StaleMaterialQuery;
import com.lingmao.scm.module.board.dto.StaleMaterialRow;
import com.lingmao.scm.module.board.mapper.ReportMapper;
import com.lingmao.scm.module.board.service.ReportService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.SysConfigService;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LineScope;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    /** sys_config 里呆滞天数的键；值是一个整数 */
    static final String STALE_DAYS_KEY = "report.stale_days";
    static final int DEFAULT_STALE_DAYS = 90;
    /** 订单毛利一次最多算多少张（超了让缩小日期范围） */
    static final int MAX_MARGIN_ORDERS = 2000;
    /** 绩效、毛利的日期范围最长多少天 */
    static final int MAX_RANGE_DAYS = 366;
    /** 本位币：订单毛利只算它（成本是人民币） */
    private static final String CNY = "CNY";

    private final ReportMapper reportMapper;
    private final SupplierService supplierService;
    private final CategoryService categoryService;
    private final MaterialService materialService;
    private final DictDataService dictDataService;
    private final SysConfigService configService;
    private final TechPackCostService techPackCostService;
    private final Clock clock;

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BizException(400, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new BizException(400, "日期范围最长一年");
        }
    }

    // ---------------------------------------------------------------- 供应商绩效

    @Override
    public PerfResult<PurchasePerfRow> purchasePerf(PerfQuery query) {
        checkRange(query.getFrom(), query.getTo());
        LocalDate today = LocalDate.now(clock);
        Map<Long, PurchasePerfRow> rows = new HashMap<>();
        Map<Long, long[]> lateDays = new HashMap<>();
        for (ReportMapper.PurchaseLineFact f : reportMapper.selectPurchaseLines(query.getFrom(), query.getTo(), LineScope.current())) {
            ReportRules.Judged j = ReportRules.purchase(f, today);
            if (j.outcome() == ReportRules.Outcome.SKIP) {
                continue;
            }
            PurchasePerfRow r = rows.computeIfAbsent(f.supplierId(), id -> {
                PurchasePerfRow n = new PurchasePerfRow();
                n.setSupplierId(id);
                return n;
            });
            switch (j.outcome()) {
                case ON_TIME -> r.setOnTimeCount(r.getOnTimeCount() + 1);
                case LATE -> {
                    r.setLateCount(r.getLateCount() + 1);
                    lateDays.computeIfAbsent(f.supplierId(), k -> new long[1])[0] += j.lateDays();
                }
                default -> r.setPendingCount(r.getPendingCount() + 1);
            }
        }
        Map<Long, SupplierRef> refs = supplierService.refs(rows.keySet());
        PurchasePerfRow total = new PurchasePerfRow();
        long totalLate = 0;
        List<PurchasePerfRow> out = new ArrayList<>();
        for (PurchasePerfRow r : rows.values()) {
            SupplierRef s = refs.get(r.getSupplierId());
            r.setSupplierCode(s == null ? null : s.code());
            r.setSupplierShortName(s == null ? String.valueOf(r.getSupplierId()) : s.shortName());
            if (!matches(query.getKeyword(), s)) {
                continue;
            }
            long late = lateDays.getOrDefault(r.getSupplierId(), new long[1])[0];
            finish(r, late);
            total.setOnTimeCount(total.getOnTimeCount() + r.getOnTimeCount());
            total.setLateCount(total.getLateCount() + r.getLateCount());
            total.setPendingCount(total.getPendingCount() + r.getPendingCount());
            totalLate += late;
            out.add(r);
        }
        finish(total, totalLate);
        out.sort(Comparator.comparing(PurchasePerfRow::getOnTimeRate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PurchasePerfRow::getLineCount, Comparator.reverseOrder()));
        return new PerfResult<>(out, total);
    }

    private static void finish(PurchasePerfRow r, long lateDaysSum) {
        r.setLineCount(r.getOnTimeCount() + r.getLateCount());
        r.setOnTimeRate(ReportRules.pct(r.getOnTimeCount(), r.getLineCount()));
        r.setAvgLateDays(r.getLateCount() == 0 ? null
                : BigDecimal.valueOf(lateDaysSum).divide(BigDecimal.valueOf(r.getLateCount()), 1, java.math.RoundingMode.HALF_UP));
    }

    @Override
    public PerfResult<FactoryPerfRow> factoryPerf(PerfQuery query) {
        checkRange(query.getFrom(), query.getTo());
        LocalDate today = LocalDate.now(clock);
        LineScope lines = LineScope.current();
        Map<Long, FactoryPerfRow> rows = new HashMap<>();
        Function<Long, FactoryPerfRow> row = id -> rows.computeIfAbsent(id, k -> {
            FactoryPerfRow n = new FactoryPerfRow();
            n.setFactoryId(k);
            return n;
        });
        for (ReportMapper.FactoryOrderFact f : reportMapper.selectFactoryOrders(query.getFrom(), query.getTo(), lines)) {
            ReportRules.Judged j = ReportRules.production(f, today);
            if (j.outcome() == ReportRules.Outcome.SKIP) {
                continue;
            }
            FactoryPerfRow r = row.apply(f.factoryId());
            if (j.outcome() == ReportRules.Outcome.PENDING) {
                r.setPendingCount(r.getPendingCount() + 1);
                continue;
            }
            if (j.outcome() == ReportRules.Outcome.ON_TIME) {
                r.setOnTimeCount(r.getOnTimeCount() + 1);
            } else {
                r.setLateCount(r.getLateCount() + 1);
            }
            r.setDueQty(r.getDueQty() + f.qty());
            r.setQtyByDue(r.getQtyByDue() + Math.min(f.goodByDue(), f.qty()));
        }
        for (ReportMapper.DefectFact d : reportMapper.selectDefects(query.getFrom(), query.getTo(), lines)) {
            FactoryPerfRow r = row.apply(d.factoryId());
            r.setReceiptQty(d.qty());
            r.setDefectQty(d.defect());
        }
        Map<Long, SupplierRef> refs = supplierService.refs(rows.keySet());
        FactoryPerfRow total = new FactoryPerfRow();
        List<FactoryPerfRow> out = new ArrayList<>();
        for (FactoryPerfRow r : rows.values()) {
            SupplierRef s = refs.get(r.getFactoryId());
            r.setFactoryCode(s == null ? null : s.code());
            r.setFactoryShortName(s == null ? String.valueOf(r.getFactoryId()) : s.shortName());
            if (!matches(query.getKeyword(), s)) {
                continue;
            }
            finish(r);
            total.setOnTimeCount(total.getOnTimeCount() + r.getOnTimeCount());
            total.setLateCount(total.getLateCount() + r.getLateCount());
            total.setPendingCount(total.getPendingCount() + r.getPendingCount());
            total.setDueQty(total.getDueQty() + r.getDueQty());
            total.setQtyByDue(total.getQtyByDue() + r.getQtyByDue());
            total.setReceiptQty(total.getReceiptQty() + r.getReceiptQty());
            total.setDefectQty(total.getDefectQty() + r.getDefectQty());
            out.add(r);
        }
        finish(total);
        out.sort(Comparator.comparing(FactoryPerfRow::getOnTimeRate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FactoryPerfRow::getDueQty, Comparator.reverseOrder()));
        return new PerfResult<>(out, total);
    }

    private static void finish(FactoryPerfRow r) {
        r.setOrderCount(r.getOnTimeCount() + r.getLateCount());
        r.setOnTimeRate(ReportRules.pct(r.getOnTimeCount(), r.getOrderCount()));
        r.setQtyRate(ReportRules.pct(r.getQtyByDue(), r.getDueQty()));
        r.setDefectRate(ReportRules.pct(r.getDefectQty(), r.getReceiptQty()));
    }

    private static boolean matches(String keyword, SupplierRef s) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.strip().toLowerCase(Locale.ROOT);
        return s != null && (contains(s.code(), kw) || contains(s.shortName(), kw) || contains(s.name(), kw));
    }

    private static boolean contains(String text, String kw) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(kw);
    }

    // ---------------------------------------------------------------- 在做件数

    @Override
    public List<LineWipRow> lineWip(String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now(clock) : parseMonth(month);
        LocalDate today = LocalDate.now(clock);
        LineScope lines = LineScope.current();
        Map<Long, TreeNode> roots = categoryRoots();
        Map<String, LineWipRow> rows = new LinkedHashMap<>();
        Function<String[], LineWipRow> row = k -> rows.computeIfAbsent(k[0] + "|" + k[1], x -> {
            LineWipRow n = new LineWipRow();
            n.setProductLine(k[0]);
            if (!k[1].isEmpty()) {
                TreeNode root = roots.get(Long.valueOf(k[1]));
                n.setCategoryId(root == null ? null : root.getId());
                n.setCategoryName(root == null ? null : root.getName());
            }
            return n;
        });
        Function<Long, String> rootKey = cat -> {
            TreeNode root = cat == null ? null : roots.get(cat);
            return root == null ? "" : String.valueOf(root.getId());
        };
        for (ReportMapper.WipFact f : reportMapper.selectWip(today, lines)) {
            LineWipRow r = row.apply(new String[]{f.productLine(), rootKey.apply(f.categoryId())});
            r.setOrderCount(r.getOrderCount() + f.orderCount());
            r.setWipQty(r.getWipQty() + f.wipQty());
            r.setLateQty(r.getLateQty() + f.lateQty());
        }
        for (ReportMapper.LineQtyFact f : reportMapper.selectNewQty(ym.atDay(1), ym.atEndOfMonth(), lines)) {
            LineWipRow r = row.apply(new String[]{f.productLine(), rootKey.apply(f.categoryId())});
            r.setMonthNewQty(r.getMonthNewQty() + f.qty());
        }
        for (ReportMapper.LineQtyFact f : reportMapper.selectGoodQty(ym.atDay(1), ym.atEndOfMonth(), lines)) {
            LineWipRow r = row.apply(new String[]{f.productLine(), rootKey.apply(f.categoryId())});
            r.setMonthGoodQty(r.getMonthGoodQty() + f.qty());
        }
        Map<String, Integer> lineOrder = new HashMap<>();
        List<DictOption> lineDict = dictDataService.listByType(LineScope.DICT_TYPE);
        for (int i = 0; i < lineDict.size(); i++) {
            lineOrder.put(lineDict.get(i).value(), i);
        }
        List<LineWipRow> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparing((LineWipRow r) -> lineOrder.getOrDefault(r.getProductLine(), Integer.MAX_VALUE))
                .thenComparing(LineWipRow::getWipQty, Comparator.reverseOrder()));
        return out;
    }

    /** 每个品类 → 它的一级品类（停用的也在） */
    private Map<Long, TreeNode> categoryRoots() {
        Map<Long, TreeNode> out = new HashMap<>();
        for (TreeNode root : categoryService.tree()) {
            List<TreeNode> stack = new ArrayList<>(List.of(root));
            while (!stack.isEmpty()) {
                TreeNode n = stack.removeLast();
                out.put(n.getId(), root);
                stack.addAll(n.getChildren());
            }
        }
        return out;
    }

    private static YearMonth parseMonth(String s) {
        try {
            return YearMonth.parse(s);
        } catch (RuntimeException e) {
            throw new BizException(400, "月份写成 2026-12 这样");
        }
    }

    // ---------------------------------------------------------------- 呆滞料

    @Override
    public int staleDays() {
        String v = configService.get(STALE_DAYS_KEY);
        if (v == null) {
            return DEFAULT_STALE_DAYS;
        }
        try {
            int days = Integer.parseInt(v.strip());
            return days > 0 ? days : DEFAULT_STALE_DAYS;
        } catch (NumberFormatException e) {
            return DEFAULT_STALE_DAYS;
        }
    }

    @Override
    public void setStaleDays(int days) {
        if (days < 1 || days > 3650) {
            throw new BizException(400, "天数要在 1 到 3650 之间");
        }
        configService.put(STALE_DAYS_KEY, String.valueOf(days));
    }

    @Override
    public long staleCount() {
        return reportMapper.selectStale(LocalDate.now(clock).minusDays(staleDays()), null, false).size();
    }

    @Override
    public PageResult<StaleMaterialRow> staleMaterials(StaleMaterialQuery query) {
        List<StaleMaterialRow> all = staleRows(query);
        int from = (int) Math.min((query.getPage() - 1) * query.getSize(), all.size());
        int to = (int) Math.min(from + query.getSize(), all.size());
        return new PageResult<>(all.subList(from, to), all.size());
    }

    private List<StaleMaterialRow> staleRows(StaleMaterialQuery query) {
        LocalDate today = LocalDate.now(clock);
        int days = query.getDays() != null ? query.getDays() : staleDays();
        List<ReportMapper.StaleFact> facts = reportMapper.selectStale(today.minusDays(days), query.getWarehouseId(), true);
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(facts.stream().map(ReportMapper.StaleFact::skuId)
                .collect(Collectors.toSet()));
        String kw = query.getKeyword() == null || query.getKeyword().isBlank() ? null : query.getKeyword().strip().toLowerCase(Locale.ROOT);
        List<StaleMaterialRow> out = new ArrayList<>();
        for (ReportMapper.StaleFact f : facts) {
            MaterialSkuBrief b = briefs.get(f.skuId());
            if (kw != null && (b == null || !(contains(b.getSkuCode(), kw) || contains(b.getMaterialCode(), kw)
                    || contains(b.getMaterialName(), kw)))) {
                continue;
            }
            StaleMaterialRow r = new StaleMaterialRow();
            r.fillFrom(b);
            r.setMaterialSkuId(f.skuId());
            r.setWarehouseId(f.warehouseId());
            r.setWarehouseName(f.warehouseName());
            r.setQty(Num.qty(f.qty()));
            r.setLastDate(f.lastDate());
            r.setIdleDays(f.lastDate() == null ? null : (int) ChronoUnit.DAYS.between(f.lastDate(), today));
            r.setUnitPrice(f.unitPrice() == null ? null : Num.price(f.unitPrice()));
            r.setAmount(f.unitPrice() == null ? null : Num.amount(f.qty(), f.unitPrice()));
            out.add(r);
        }
        return out;
    }

    @Data
    static class StaleExcelRow {
        private String warehouse;
        private String skuCode;
        private String materialName;
        private String colorName;
        private String spec;
        private String unit;
        private BigDecimal qty;
        private LocalDate lastDate;
        private Integer idleDays;
        private BigDecimal unitPrice;
        private BigDecimal amount;
    }

    private static final ExcelSchema<StaleExcelRow> STALE = ExcelSchema.of("呆滞料", StaleExcelRow::new,
            ExcelColumn.text("仓库", StaleExcelRow::getWarehouse, StaleExcelRow::setWarehouse),
            ExcelColumn.text("SKU 码", StaleExcelRow::getSkuCode, StaleExcelRow::setSkuCode),
            ExcelColumn.text("物料名称", StaleExcelRow::getMaterialName, StaleExcelRow::setMaterialName),
            ExcelColumn.text("颜色", StaleExcelRow::getColorName, StaleExcelRow::setColorName),
            ExcelColumn.text("规格", StaleExcelRow::getSpec, StaleExcelRow::setSpec),
            ExcelColumn.text("单位", StaleExcelRow::getUnit, StaleExcelRow::setUnit),
            ExcelColumn.decimal("库存", 3, StaleExcelRow::getQty, StaleExcelRow::setQty),
            ExcelColumn.date("最后出入库", StaleExcelRow::getLastDate, StaleExcelRow::setLastDate),
            ExcelColumn.integer("多少天没动", StaleExcelRow::getIdleDays, StaleExcelRow::setIdleDays),
            ExcelColumn.decimal("单价", 4, StaleExcelRow::getUnitPrice, StaleExcelRow::setUnitPrice),
            ExcelColumn.decimal("估算金额", 2, StaleExcelRow::getAmount, StaleExcelRow::setAmount));

    @Override
    public byte[] exportStaleMaterials(StaleMaterialQuery query) {
        Map<String, String> units = dictDataService.listByType("unit").stream()
                .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
        // 导出不走 FieldProjection：没有看成本权限的（仓管）不出单价、估算金额两列，和页面一样（agy T-e19e38 #1）
        boolean cost = com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.COST);
        return Excel.export(cost ? STALE : STALE.without("单价", "估算金额"), staleRows(query).stream().limit(Excel.MAX_EXPORT_ROWS + 1L).map(r -> {
            StaleExcelRow e = new StaleExcelRow();
            e.setWarehouse(r.getWarehouseName());
            e.setSkuCode(r.getSkuCode());
            e.setMaterialName(r.getMaterialName());
            e.setColorName(r.getColorName());
            e.setSpec(r.getSpec());
            e.setUnit(r.getStockUnit() == null ? null : units.getOrDefault(r.getStockUnit(), r.getStockUnit()));
            e.setQty(r.getQty());
            e.setLastDate(r.getLastDate());
            e.setIdleDays(r.getIdleDays());
            e.setUnitPrice(cost ? r.getUnitPrice() : null);
            e.setAmount(cost ? r.getAmount() : null);
            return e;
        }).toList());
    }

    // ---------------------------------------------------------------- 订单毛利

    @Override
    public OrderMarginPage orderMargins(OrderMarginQuery query) {
        checkRange(query.getFrom(), query.getTo());
        List<OrderMarginRow> all = reportMapper.selectOrders(query.getFrom(), query.getTo(), Keywords.contains(query.getKeyword()),
                query.getProductLine(), LineScope.current(), DataScope.current(false), MAX_MARGIN_ORDERS + 1);
        if (all.size() > MAX_MARGIN_ORDERS) {
            throw new BizException("这段时间的订单超过 " + MAX_MARGIN_ORDERS + " 张，请缩小日期范围");
        }
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        long missingOrders = 0;
        long foreignOrders = 0;
        if (!all.isEmpty()) {
            List<ReportMapper.PackQty> packQty = reportMapper.selectOrderPackQty(all.stream().map(OrderMarginRow::getId).toList());
            Set<Long> packIds = packQty.stream().map(ReportMapper.PackQty::techPackId).filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, BigDecimal> unitCosts = packIds.isEmpty() ? Map.of() : techPackCostService.unitCosts(packIds);
            Map<Long, List<ReportMapper.PackQty>> byOrder = packQty.stream().collect(Collectors.groupingBy(ReportMapper.PackQty::orderId));
            for (OrderMarginRow r : all) {
                BigDecimal c = BigDecimal.ZERO;
                long missing = 0;
                for (ReportMapper.PackQty pq : byOrder.getOrDefault(r.getId(), List.of())) {
                    BigDecimal unit = pq.techPackId() == null ? null : unitCosts.get(pq.techPackId());
                    if (unit == null) {
                        missing += pq.qty();
                    } else {
                        c = c.add(unit.multiply(BigDecimal.valueOf(pq.qty())));
                    }
                }
                r.setSalesAmount(Num.money(r.getSalesAmount() == null ? BigDecimal.ZERO : r.getSalesAmount()));
                r.setCostAmount(Num.money(c));
                r.setCostMissingQty((int) missing);
                if (r.getCurrency() != null && !CNY.equals(r.getCurrency())) {
                    // 外币单：销售额是外币、成本是人民币，一期没有汇率，不算毛利、不进合计
                    foreignOrders++;
                    continue;
                }
                r.setMargin(r.getSalesAmount().subtract(r.getCostAmount()));
                r.setMarginRate(ReportRules.pct(r.getMargin(), r.getSalesAmount()));
                sales = sales.add(r.getSalesAmount());
                cost = cost.add(r.getCostAmount());
                if (missing > 0) {
                    missingOrders++;
                }
            }
        }
        int from = (int) Math.min((query.getPage() - 1) * query.getSize(), all.size());
        int to = (int) Math.min(from + query.getSize(), all.size());
        BigDecimal margin = sales.subtract(cost);
        return new OrderMarginPage(all.subList(from, to), all.size(), Num.money(sales), Num.money(cost), Num.money(margin),
                ReportRules.pct(margin, sales), missingOrders, foreignOrders);
    }
}
