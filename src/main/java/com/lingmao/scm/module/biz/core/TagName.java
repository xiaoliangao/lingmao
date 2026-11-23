package com.lingmao.scm.module.biz.core;

import com.lingmao.scm.common.exception.BizException;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 标签文本的唯一规范化入口；canonical 不依赖数据库或服务器默认 Locale。 */
public final class TagName {
    public static final int MAX_PER_OBJECT = 30;
    public static final int MAX_DISPLAY_CODE_POINTS = 64;
    public static final int MAX_CANONICAL_CODE_POINTS = 256;

    private final String display;
    private final String canonical;

    private TagName(String display, String canonical) {
        this.display = display;
        this.canonical = canonical;
    }

    public String display() { return display; }
    public String canonical() { return canonical; }

    public static TagName of(String input) {
        String display = display(input, false);
        return new TagName(display, canonical(display));
    }

    /** 去重保留第一次输入的显示文本和顺序；原始请求也最多 30 个，避免大重复列表消耗。 */
    public static List<TagName> distinct(Collection<String> inputs) {
        if (inputs == null || inputs.size() > MAX_PER_OBJECT) {
            throw new BizException(400, "每个对象最多关联 30 个标签");
        }
        var unique = new LinkedHashMap<String, TagName>();
        for (String input : inputs) {
            TagName name = of(input);
            unique.putIfAbsent(name.canonical(), name);
        }
        return List.copyOf(unique.values());
    }

    /** 空前缀合法；LIKE 的通配符由 Repository 当字面字符转义。 */
    public static String prefix(String input) {
        return canonical(display(input == null ? "" : input, true));
    }

    private static String display(String input, boolean allowEmpty) {
        if (input == null) throw new BizException(400, "标签不能为空");
        if (input.length() > 1024) throw new BizException(400, "标签输入过长");
        // 在 strip 之前拒绝控制字符，换行/制表符不能通过首尾裁剪悄悄逃过校验。
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        if (normalized.codePoints().anyMatch(TagName::forbidden)) {
            throw new BizException(400, "标签不能包含控制字符、双向覆盖符或逗号");
        }
        normalized = normalized.strip();
        if (normalized.startsWith("#")) normalized = normalized.substring(1).strip();
        int length = normalized.codePointCount(0, normalized.length());
        if ((!allowEmpty && length == 0) || length > MAX_DISPLAY_CODE_POINTS) {
            throw new BizException(400, "标签必须为 1–64 个 Unicode 字符");
        }
        return normalized;
    }

    private static boolean forbidden(int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == ','
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint >= 0x2066 && codePoint <= 0x2069
                || codePoint >= 0xD800 && codePoint <= 0xDFFF;
    }

    private static String canonical(String display) {
        String canonical = display.toLowerCase(Locale.ROOT);
        if (canonical.codePointCount(0, canonical.length()) > MAX_CANONICAL_CODE_POINTS) {
            throw new BizException(400, "标签规范化名称过长");
        }
        return canonical;
    }

    @Override public boolean equals(Object other) {
        return other instanceof TagName name && display.equals(name.display) && canonical.equals(name.canonical);
    }
    @Override public int hashCode() { return Objects.hash(display, canonical); }
    @Override public String toString() { return display; }
}
