package com.lingmao.scm.module.dev.core;

import com.lingmao.scm.common.exception.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 只做有界输入和定点数校验，不读取 HTTP/公司身份、不执行业务状态动作。 */
public final class SampleRules {
    public static final int MAX_SELECTIONS = 100;
    public static final int MAX_COMBINATIONS = 10_000;
    public static final int MAX_QTY = 99_999_999;
    public static final int MAX_REVIEWERS = 100;

    private SampleRules() { }

    /** 打样数量是整版总件数，不能在每个颜色尺码格重复展开。 */
    public static int quantity(Integer qty) {
        if (qty == null || qty < 1 || qty > MAX_QTY) throw invalid("打样数量须为 1–99999999 的整数");
        return qty;
    }

    public static List<Long> colorIds(Collection<Long> values, Collection<Long> available) {
        List<Long> ids = ids(values, MAX_SELECTIONS, "颜色");
        if (available != null && !new LinkedHashSet<>(available).containsAll(ids)) {
            throw invalid("打样颜色必须属于样衣档案");
        }
        return ids;
    }

    /** 父样衣锁内读取当前颜色后调用；不改旧版快照，业务层用 removedIds 对照旧 code/name 生成明确 warning。 */
    public static RevisionColors revisionColors(Collection<Long> previous, Collection<Long> current) {
        List<Long> oldIds = colorIds(previous, null); List<Long> currentIds = colorIds(current, null);
        var available = new LinkedHashSet<>(currentIds);
        List<Long> kept = oldIds.stream().filter(available::contains).toList();
        List<Long> removed = oldIds.stream().filter(id -> !available.contains(id)).toList();
        return new RevisionColors(kept.isEmpty() ? List.of(currentIds.getFirst()) : kept, removed, kept.isEmpty());
    }

    public record RevisionColors(List<Long> colorIds, List<Long> removedIds, boolean usedFallback) {
        public RevisionColors { colorIds = List.copyOf(colorIds); removedIds = List.copyOf(removedIds); }
    }

    public static List<String> sizeCodes(Collection<String> values, Collection<String> available) {
        if (values == null || values.isEmpty() || values.size() > MAX_SELECTIONS) {
            throw invalid("请选择 1–100 个尺码");
        }
        var unique = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 16
                    || !value.equals(value.strip()) || !unique.add(value)) {
                throw invalid("尺码不能为空、重复或超过 16 个字符");
            }
        }
        if (available != null && !new LinkedHashSet<>(available).containsAll(unique)) {
            throw invalid("打样尺码必须属于本版尺码组");
        }
        return List.copyOf(unique);
    }

    public static List<Long> reviewerIds(Collection<Long> values) {
        if (values == null || values.isEmpty()) throw invalid("至少选择一位评审人");
        if (values.size() > MAX_REVIEWERS) throw invalid("评审人原始列表最多 100 项");
        var unique = new LinkedHashSet<Long>();
        for (Long value : values) {
            if (value == null || value <= 0) throw invalid("评审人编号不合法");
            unique.add(value);
            if (unique.size() > MAX_REVIEWERS) throw invalid("评审人最多 100 位");
        }
        return List.copyOf(unique);
    }

    public static int combinations(int colors, int sizes) {
        if (colors < 1 || colors > MAX_SELECTIONS || sizes < 1 || sizes > MAX_SELECTIONS
                || (long) colors * sizes > MAX_COMBINATIONS) {
            throw invalid("颜色、尺码各最多 100 项，组合最多 10000 项");
        }
        return Math.multiplyExact(colors, sizes);
    }

    /** NUMERIC(14,scale)；禁止通过隐式四舍五入掩盖超精度输入，未知价格继续保留 null。 */
    public static BigDecimal price(BigDecimal value, int scale, String label) {
        if (scale != 2 && scale != 4) throw new IllegalArgumentException("Only price scales 2 and 4 are supported");
        if (value == null) return null;
        try {
            BigDecimal exact = value.setScale(scale, RoundingMode.UNNECESSARY);
            if (exact.signum() < 0 || exact.precision() > 14) throw invalid(label + "超出允许范围");
            return exact;
        } catch (ArithmeticException ex) {
            throw invalid(label + "最多 " + scale + " 位小数");
        }
    }

    public static LocalDate notFuture(LocalDate date, LocalDate today, String label) {
        Objects.requireNonNull(today, "today");
        if (date == null || date.isAfter(today)) throw invalid(label + "不能为空或晚于今天");
        return date;
    }

    private static List<Long> ids(Collection<Long> values, int maximum, String label) {
        if (values == null || values.isEmpty() || values.size() > maximum) {
            throw invalid("请选择 1–" + maximum + " 个" + label);
        }
        var unique = new LinkedHashSet<Long>();
        for (Long value : values) {
            if (value == null || value <= 0 || !unique.add(value)) throw invalid(label + "编号不合法或重复");
        }
        return List.copyOf(unique);
    }

    private static BizException invalid(String message) { return new BizException(400, message); }
}
