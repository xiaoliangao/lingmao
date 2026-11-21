package com.lingmao.scm.common.doc;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 比较一条档案改前、改后，列出变了的字段（RFC 016 §3，物料、款式、供应商的"操作记录"用）。每种档案在自己的 Service 里列一张字段表：
 * <pre>
 * List&lt;FieldChange&gt; diff = FieldDiff.of(before, after)
 *         .field("name", "名称", Material::getName)
 *         .field("materialType", "物料类型", Material::getMaterialType, types::get)   // 字典值 → 中文
 *         .masked("refPrice", "参考单价", Material::getRefPrice, FieldPermissions.COST)
 *         .list();
 * docLogService.add(BizType.MATERIAL, id, "update", null, diff);
 * </pre>
 * 先把两边都转成显示的文字再比：10.00 和 10 一样，空串和 null 一样。转文字的函数拿到的是非空的原值，
 * 返回 null（字典里没有）时显示原值
 */
public final class FieldDiff<T> {

    private final T before;
    private final T after;
    private final List<FieldChange> changes = new ArrayList<>();

    private FieldDiff(T before, T after) {
        this.before = Objects.requireNonNull(before);
        this.after = Objects.requireNonNull(after);
    }

    public static <T> FieldDiff<T> of(T before, T after) {
        return new FieldDiff<>(before, after);
    }

    /** 按通用规则转文字（数字去尾 0、是 / 否、日期、几个值用顿号连起来） */
    public FieldDiff<T> field(String field, String label, Function<T, ?> getter) {
        return compare(field, label, getter, FieldDiff::text, null);
    }

    /** 自己转文字：字典值 → 中文、id → 名字、枚举 → 中文 */
    public <V> FieldDiff<T> field(String field, String label, Function<T, V> getter, Function<? super V, String> format) {
        return compare(field, label, getter, format, null);
    }

    /** 要权限才能看值的字段（价格、银行账号）：没有权限的人看到"改了"，看不到值 */
    public FieldDiff<T> masked(String field, String label, Function<T, ?> getter, String permission) {
        return compare(field, label, getter, FieldDiff::text, permission);
    }

    /** 子表、关联这些比不了字段的，自己算好前后的文字加进来；两边一样不加 */
    public FieldDiff<T> add(String field, String label, String beforeText, String afterText) {
        String b = blankToNull(beforeText);
        String a = blankToNull(afterText);
        if (!Objects.equals(b, a)) {
            changes.add(new FieldChange(field, label, b, a, null));
        }
        return this;
    }

    public List<FieldChange> list() {
        return List.copyOf(changes);
    }

    private <V> FieldDiff<T> compare(String field, String label, Function<T, V> getter, Function<? super V, String> format,
                                     String permission) {
        String b = display(getter.apply(before), format);
        String a = display(getter.apply(after), format);
        if (!Objects.equals(b, a)) {
            changes.add(new FieldChange(field, label, b, a, permission));
        }
        return this;
    }

    private static <V> String display(V value, Function<? super V, String> format) {
        if (value == null) return null;
        String formatted = format.apply(value);
        return blankToNull(formatted == null ? text(value) : formatted);
    }

    /** 通用的转文字：空、空串、空集合是 null */
    public static String text(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> blankToNull(s);
            case BigDecimal d -> d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
            case Boolean b -> b ? "是" : "否";
            case TemporalAccessor t -> t.toString();
            case Collection<?> c -> blankToNull(c.stream().map(FieldDiff::text).filter(Objects::nonNull)
                    .collect(Collectors.joining("、")));
            case Enum<?> e -> e.name();
            default -> blankToNull(value.toString());
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
