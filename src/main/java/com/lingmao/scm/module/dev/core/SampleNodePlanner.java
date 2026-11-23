package com.lingmao.scm.module.dev.core;

import com.lingmao.scm.common.exception.BizException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** RFC007 逆排；Service 将已经按字典 sort/id 排好的启用项传入，不在这里重新读取或猜测配置。 */
public final class SampleNodePlanner {
    public static final String SEND = "SEND";
    public static final int MAX_NODES = 100;

    private SampleNodePlanner() { }

    public record Template(String code, String name, BigDecimal durationDays) { }
    public record Planned(int seq, String code, String name, int durationDays, LocalDate planDate) { }

    public static List<Planned> plan(LocalDate needDate, List<Template> ordered) {
        if (needDate == null) throw invalid("要样日期不能为空");
        if (ordered == null || ordered.isEmpty() || ordered.size() > MAX_NODES) {
            throw invalid("打样节点须有 1–100 个启用项");
        }
        var codes = new HashSet<String>();
        var durations = new ArrayList<Integer>(ordered.size());
        int sends = 0;
        for (Template item : ordered) {
            if (item == null || item.code() == null || item.code().isBlank()
                    || item.code().length() > 64 || item.name() == null || item.name().isBlank()
                    || item.name().codePointCount(0, item.name().length()) > 64
                    || !codes.add(item.code())) throw invalid("打样节点编码/名称为空、过长或重复");
            if (SEND.equals(item.code())) sends++;
            try {
                if (item.durationDays() == null || item.durationDays().signum() < 0) {
                    throw invalid("打样节点默认天数须为非负整数");
                }
                durations.add(item.durationDays().intValueExact());
            } catch (ArithmeticException ex) {
                throw invalid("打样节点默认天数须为允许范围内的整数");
            }
        }
        if (sends != 1 || !SEND.equals(ordered.getLast().code())) {
            throw invalid("打样节点必须有唯一启用的 SEND（送样），且排在最后");
        }
        var result = new ArrayList<Planned>(ordered.size());
        LocalDate date = needDate;
        try {
            for (int i = ordered.size() - 1; i >= 0; i--) {
                if (i < ordered.size() - 1) date = date.minusDays(durations.get(i + 1));
                Template item = ordered.get(i);
                result.addFirst(new Planned(i + 1, item.code(), item.name(), durations.get(i), date));
            }
        } catch (DateTimeException ex) {
            throw invalid("节点天数导致计划日期超出允许范围");
        }
        return List.copyOf(result);
    }

    private static BizException invalid(String message) { return new BizException(400, message); }
}
