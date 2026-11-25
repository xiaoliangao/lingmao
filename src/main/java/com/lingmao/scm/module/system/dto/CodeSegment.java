package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * 编码规则的一段（RFC 018 §4.4），存进 sys_code_rule.segments 的 JSON 数组。
 *
 * @param type   段的种类
 * @param text   固定文字（TEXT）：1-8 位大写字母、数字、横杠
 * @param format 日期格式（DATE）：yy / yyMM / yyMMdd / yyyy
 * @param width  流水位数（SEQ）：2-6
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeSegment(@NotNull(message = "段的种类不能为空") Type type, String text, String format, Integer width) {

    public enum Type {
        TEXT("固定文字", EnumSet.allOf(CodeTarget.class), 8),
        STYLE_CLASS("大类编码", EnumSet.of(CodeTarget.STYLE), 4),
        BRAND("品牌编码", EnumSet.of(CodeTarget.STYLE), 8),
        CATEGORY("品类编码", EnumSet.of(CodeTarget.STYLE), 8),
        STYLE_YEAR("款式年份", EnumSet.of(CodeTarget.STYLE), 2),
        DATE("日期", EnumSet.allOf(CodeTarget.class), 6),
        DESIGNER("设计师编号", EnumSet.of(CodeTarget.STYLE), 2),
        /** 物料所在二级分类的上级（一级）的编码（RFC 018 §8.3） */
        MAT_PARENT_CATEGORY("一级分类编码", EnumSet.of(CodeTarget.MATERIAL), 2),
        /** 物料所在二级分类的编码；原来叫"物料分类编码" */
        MAT_CATEGORY("二级分类编码", EnumSet.of(CodeTarget.MATERIAL), 2),
        SEQ("流水", EnumSet.allOf(CodeTarget.class), 6);

        private final String label;
        private final Set<CodeTarget> targets;
        /** 算"最长有多长"时这一段按几位算：字典值、品类编码按常见的最长，TEXT、DATE、SEQ 按实际的 */
        private final int assumedMax;

        Type(String label, Set<CodeTarget> targets, int assumedMax) {
            this.label = label;
            this.targets = targets;
            this.assumedMax = assumedMax;
        }

        public String label() {
            return label;
        }

        public boolean allowedIn(CodeTarget target) {
            return targets.contains(target);
        }

        int assumedMax() {
            return assumedMax;
        }
    }

    /** 日期段能选的格式 */
    public static final Set<String> DATE_FORMATS = Set.of("yy", "yyMM", "yyMMdd", "yyyy");

    /** 这一段最长几位：保存规则时算整条最长多长，不超过编码列（32） */
    public int maxLength() {
        return switch (type) {
            case TEXT -> text == null ? 0 : text.length();
            case DATE -> format == null ? 6 : format.length();
            case SEQ -> width == null ? 0 : width;
            default -> type.assumedMax();
        };
    }
}
