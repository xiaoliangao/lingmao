package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.techpack.dto.PackRequirement;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.purchase.entity.MrpSnapshot;
import com.lingmao.scm.module.purchase.mapper.MrpCalcMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 算料的取数（docs/api/03 §3.2.4）：查订单能不能算、订单 × 生产 BOM 展开成需求行、取物料的采购信息、备料比、参考单价、库存在途，
 * 交给 MrpCalculator 算。建草稿、就地重算、另算新单、确认前再算一遍都走 compute
 */
@Component
@RequiredArgsConstructor
class MrpEngine {

    /** 能算料的订单状态（02 的状态） */
    static final Set<String> CALC_ORDER_STATUS = Set.of("APPROVED", "RUNNING");

    private final MrpCalcMapper calcMapper;
    private final OrderService orderService;
    private final TechPackService techPackService;
    private final MaterialService materialService;
    private final PriceService priceService;
    private final DictDataService dictDataService;
    private final NeedDates needDates;
    private final SupplyLoader supplyLoader;
    private final Clock clock;

    /**
     * 算的结果
     *
     * @param infos 结果行里每个 SKU 的采购信息（改行、生成采购单还要用）
     */
    record Result(List<MrpCalculator.Row> rows, LocalDate needDate, MrpSnapshot snapshot, Map<Long, MaterialPurchaseInfo> infos) {
    }

    /** 订单的中文状态（02 的 ORDER_STATUS）：报"不能算料"时用 */
    private static final Map<String, String> ORDER_STATUS_TEXT = Map.of(
            "DRAFT", "草稿", "PENDING", "待审核", "APPROVED", "已审核", "RUNNING", "生产中",
            "DONE", "已完成", "CLOSED", "已结案", "VOIDED", "已取消");

    void checkSourceVisible(Collection<Long> ids) { orderService.checkSourceVisible(ids); }

    /** 订单都要在、都是已审核或生产中，不然 404 / 1000（选订单算料） */
    List<MrpCalcMapper.OrderHead> checkedHeads(Collection<Long> orderIds) {
        orderService.checkForMrp(orderIds);
        List<MrpCalcMapper.OrderHead> heads = calcMapper.selectOrderHeads(new LinkedHashSet<>(orderIds));
        if (heads.size() != new HashSet<>(orderIds).size()) {
            throw new BizException(404, "有订单不存在了，请刷新后再选");
        }
        List<String> bad = heads.stream().filter(h -> !CALC_ORDER_STATUS.contains(h.status()))
                .map(h -> "订单 " + h.no() + " 不能算料：它是" + ORDER_STATUS_TEXT.getOrDefault(h.status(), h.status()) + "状态").toList();
        if (!bad.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, bad.getFirst(), bad);
        }
        return heads;
    }

    /**
     * 按这几张订单现在的数算一遍
     *
     * @param excluded   不算"别人"的算料单（自己、上一张）：它们的使用不扣可用量，它们的采购行也取进来
     * @param prevCalcId 重新算料的上一张
     */
    Result compute(List<MrpCalcMapper.OrderHead> heads, Set<Long> excluded, Long prevCalcId) {
        List<Long> orderIds = heads.stream().map(MrpCalcMapper.OrderHead::id).toList();
        orderService.checkForMrp(orderIds);
        List<MrpCalcMapper.OrderLine> orderLines = calcMapper.selectOrderLines(orderIds);
        techPackService.checkLockedRequirements(orderLines.stream()
                .map(l -> new PackRequirement(l.techPackId(), l.styleSkuId())).toList());
        Map<Long, LocalDate> commits = new HashMap<>();
        heads.forEach(h -> commits.put(h.id(), h.commitDate()));
        Map<Long, LocalDate> needs = needDates.of(commits);
        LocalDate needDate = needs.values().stream().filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);

        Run run = run(demands(calcMapper.selectDemand(orderIds)), needDate, excluded, prevCalcId);
        List<MrpCalculator.Row> rows = run.rows();

        Map<Long, List<MrpSnapshot.Line>> lines = new LinkedHashMap<>();
        orderLines.forEach(l -> lines.computeIfAbsent(l.orderId(), k -> new ArrayList<>())
                .add(new MrpSnapshot.Line(l.styleSkuId(), l.qty(), l.techPackId())));
        List<MrpSnapshot.Order> orders = heads.stream().map(h -> new MrpSnapshot.Order(h.id(), h.no(), h.commitDate(),
                needs.get(h.id()), lines.getOrDefault(h.id(), List.of()))).toList();
        List<MrpSnapshot.Late> lates = new ArrayList<>();
        rows.forEach(r -> r.lates().forEach(l -> lates.add(new MrpSnapshot.Late(r.item().getMaterialSkuId(), l.lineId(), l.freeQty(), l.eta()))));
        return new Result(rows, needDate, new MrpSnapshot(orders, lates), run.infos());
    }

    /** 算出来的行和行里每个 SKU 的采购信息 */
    record Run(List<MrpCalculator.Row> rows, Map<Long, MaterialPurchaseInfo> infos) {
    }

    /**
     * 审核对话框的缺料粗算（docs/api/02 §3.8）：一张还没锁版本的订单，每个款按 stylePacks 给的生产 BOM 展开，和别人都算过以后的可用量比。
     * 只算不存；BOM 没配全的行跳过（资料包那一块会说不能审），不像算料那样整批报错
     *
     * @param dueDate 承诺交期 ?? 要求交期，推要料日期；null 时在途都当赶得上
     */
    Run preview(long orderId, Map<Long, Long> stylePacks, LocalDate dueDate) {
        if (stylePacks.isEmpty()) {
            return new Run(List.of(), Map.of());
        }
        List<MrpCalcMapper.DemandRow> rows = calcMapper.selectReviewDemand(orderId, stylePacks.entrySet().stream()
                .map(e -> new MrpCalcMapper.StylePack(e.getKey(), e.getValue())).toList());
        List<MrpCalcMapper.DemandRow> complete = rows.stream().filter(r -> r.materialId() == null || "CUSTOMER_SUPPLIED".equals(r.source())
                || (r.skuId() != null && r.usage() != null)).toList();
        Map<Long, LocalDate> commits = new HashMap<>();
        commits.put(orderId, dueDate);
        return run(demands(complete), needDates.of(commits).get(orderId), Set.of(), null);
    }

    /** 取物料的采购信息、参考单价、库存在途，交给 MrpCalculator 算 */
    private Run run(List<MrpCalculator.Demand> demands, LocalDate needDate, Set<Long> excluded, Long prevCalcId) {
        Set<Long> skuIds = new LinkedHashSet<>();
        demands.stream().filter(d -> !"CUSTOMER_SUPPLIED".equals(d.source())).forEach(d -> skuIds.add(d.skuId()));
        Map<Long, MaterialPurchaseInfo> infos = materialService.purchaseInfos(skuIds);

        Set<MrpCalculator.PriceKey> keys = new HashSet<>();
        for (MrpCalculator.Demand d : demands) {
            MaterialPurchaseInfo info = infos.get(d.skuId());
            if (info == null) {
                continue;
            }
            if (d.supplierId() != null) {
                keys.add(new MrpCalculator.PriceKey(d.supplierId(), info.materialId(), info.skuId()));
            }
            if (info.defaultSupplierId() != null) {
                keys.add(new MrpCalculator.PriceKey(info.defaultSupplierId(), info.materialId(), info.skuId()));
            }
        }
        SupplyBook book = supplyLoader.load(skuIds, excluded);
        MrpCalculator.Inputs in = new MrpCalculator.Inputs(infos, typePcts(), prices(keys), needDate, excluded, prevCalcId);
        return new Run(MrpCalculator.calculate(demands, book, in), infos);
    }

    /**
     * 需求行：配料、分码用量没配上的（资料包生效时查过，正常不会有）整批报出来，1000。
     * 客供的行不进结果，不查
     */
    static List<MrpCalculator.Demand> demands(List<MrpCalcMapper.DemandRow> rows) {
        List<MrpCalculator.Demand> out = new ArrayList<>();
        Set<String> problems = new LinkedHashSet<>();
        for (MrpCalcMapper.DemandRow r : rows) {
            if (r.materialId() == null) {
                continue;   // 生产 BOM 一行都没有：这个款不用料（资料包生效时会拦，这里不报）
            }
            if ("CUSTOMER_SUPPLIED".equals(r.source())) {
                continue;
            }
            if (r.skuId() == null) {
                problems.add(r.styleSkuCode() + "：BOM 第 " + r.bomSeq() + " 行 " + r.materialCode() + " 没给这个颜色 / 尺码配物料");
                continue;
            }
            if (r.usage() == null) {
                problems.add(r.styleSkuCode() + "：BOM 第 " + r.bomSeq() + " 行 " + r.materialCode() + " 没填这个尺码的用量");
                continue;
            }
            out.add(new MrpCalculator.Demand(r.orderId(), r.orderNo(), r.styleCode(), r.bomSeq(), r.source(), r.supplierId(),
                    r.materialId(), r.skuId(), r.qty(), r.usage(), r.lossPct()));
        }
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, "算不了：生产 BOM 有 " + problems.size() + " 处没配全，先改资料包", List.copyOf(problems));
        }
        return out;
    }

    /** 物料类型 → 备料比（字典 material_type 的数值列，没填的不在里面） */
    Map<String, BigDecimal> typePcts() {
        Map<String, BigDecimal> out = new HashMap<>();
        dictDataService.listWithText("material_type").forEach(d -> {
            if (d.getNumValue() != null) {
                out.put(d.getValue(), d.getNumValue());
            }
        });
        return out;
    }

    /** 一批 (供应商, 物料, SKU) 今天的价格档案现价，元 / 采购单位；没有的返回 null */
    Function<MrpCalculator.PriceKey, BigDecimal> prices(Collection<MrpCalculator.PriceKey> keys) {
        if (keys.isEmpty()) {
            return k -> null;
        }
        Map<MaterialPriceKey, PriceRef> refs = priceService.currentMaterials(keys.stream()
                .map(k -> new MaterialPriceKey(k.supplierId(), k.materialId(), k.skuId())).toList(), LocalDate.now(clock));
        return k -> {
            PriceRef ref = refs.get(new MaterialPriceKey(k.supplierId(), k.materialId(), k.skuId()));
            return ref == null ? null : ref.price();
        };
    }

    /** 一个 SKU 换了供应商以后的参考单价要的取价函数（只取这一家） */
    Function<MrpCalculator.PriceKey, BigDecimal> price(Long supplierId, MaterialPurchaseInfo info) {
        return supplierId == null ? k -> null : prices(List.of(new MrpCalculator.PriceKey(supplierId, info.materialId(), info.skuId())));
    }
}
