package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.purchase.service.NeedDateSource;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 要料日期（PRD §6.6.1）：订单已排单的取生产单"裁剪"的计划开始（NeedDateSource，production 实现）；还没排单的按承诺交期倒推：
 * 生产单默认交期 = 承诺交期 − 10 天，再按字典 prod_node 启用的节点、数值列的默认天数（没填按 1 天）从交期往前倒排到"裁剪"的计划开始，
 * 和生产单审核时生成节点的排法一样（docs/api/05 §3.x）
 */
@Component
@RequiredArgsConstructor
class NeedDates {

    /** 生产单默认交期比承诺交期早几天（PRD §6.6.1） */
    static final int PRODUCTION_LEAD_DAYS = 10;

    static final String CUT = "CUT";

    private final ObjectProvider<NeedDateSource> source;
    private final DictDataService dictDataService;

    /** 生产节点：编码、默认天数 */
    record Node(String code, int days) {
    }

    /** 几张订单的要料日期：orderId → 承诺交期进来，orderId → 要料日期出去；算不出来的（没承诺交期、字典里没有裁剪）不在返回里 */
    Map<Long, LocalDate> of(Map<Long, LocalDate> commitDates) {
        Map<Long, LocalDate> out = new HashMap<>();
        if (commitDates.isEmpty()) {
            return out;
        }
        NeedDateSource s = source.getIfAvailable();
        if (s != null) {
            out.putAll(s.cutStartOf(commitDates.keySet()));
        }
        List<Node> nodes = null;
        for (Map.Entry<Long, LocalDate> e : commitDates.entrySet()) {
            if (out.containsKey(e.getKey()) || e.getValue() == null) {
                continue;
            }
            if (nodes == null) {
                nodes = nodes();
            }
            LocalDate d = fallback(e.getValue(), nodes);
            if (d != null) {
                out.put(e.getKey(), d);
            }
        }
        return out;
    }

    Integer fallbackLeadDays() {
        LocalDate reference = LocalDate.of(2000, 1, 1);
        LocalDate need = fallback(reference, nodes());
        return need == null ? null : Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(need, reference));
    }

    private List<Node> nodes() {
        return dictDataService.listWithText("prod_node").stream()
                .filter(d -> Integer.valueOf(1).equals(d.getStatus()))
                .map(d -> new Node(d.getValue(), days(d)))
                .toList();
    }

    private static int days(DictData d) {
        BigDecimal n = d.getNumValue();
        return n == null || n.signum() <= 0 ? 1 : n.intValue();
    }

    /**
     * 按承诺交期倒推的"裁剪"计划开始：最后一个节点的计划完成 = 生产单交期，计划开始 = 计划完成 −（天数 − 1）；
     * 前一个节点的计划完成 = 后一个的计划开始 − 1 天。节点里没有裁剪返回 null
     */
    static LocalDate fallback(LocalDate commitDate, List<Node> nodes) {
        LocalDate finish = commitDate.minusDays(PRODUCTION_LEAD_DAYS);
        for (int i = nodes.size() - 1; i >= 0; i--) {
            LocalDate start = finish.minusDays(nodes.get(i).days() - 1L);
            if (Objects.equals(nodes.get(i).code(), CUT)) {
                return start;
            }
            finish = start.minusDays(1);
        }
        return null;
    }
}
