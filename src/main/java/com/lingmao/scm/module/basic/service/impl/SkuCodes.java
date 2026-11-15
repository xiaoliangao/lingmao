package com.lingmao.scm.module.basic.service.impl;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 物料 SKU 码的规则（RFC 003 §7.1）：物料编码-颜色码[-规格码]；规格码默认由规格算 */
final class SkuCodes {

    /** 规格末尾的单位：去掉以后才是规格码（60cm → 60） */
    private static final Pattern UNIT_SUFFIX = Pattern.compile("(cm|mm|厘米|毫米|码)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 页面上新建的规格码：进 SKU 码，只收字母、数字、小数点。导入的照用领猫的，不查 */
    static final Pattern SPEC_CODE = Pattern.compile("^[A-Z0-9.]{1,16}$");

    private SkuCodes() {
    }

    /** 规格 → 规格码：去掉空格和末尾的单位，转大写。"15 cm" → "15"，"60CM" → "60"；算完是空的返回 null */
    static String specCodeOf(String spec) {
        if (spec == null) {
            return null;
        }
        String code = UNIT_SUFFIX.matcher(WHITESPACE.matcher(spec).replaceAll("")).replaceAll("").toUpperCase(Locale.ROOT);
        return code.isEmpty() ? null : code;
    }

    /** 物料编码-颜色码[-规格码]；没颜色没规格就是物料编码本身 */
    static String compose(String materialCode, String colorCode, String specCode) {
        return Stream.of(materialCode, colorCode, specCode).filter(Objects::nonNull).collect(Collectors.joining("-"));
    }

    /** 同一物料下"颜色 + 规格码"的组合，和 uk_bas_mat_sku_combo 一样把空当 0 和空串 */
    static String combo(Long colorId, String specCode) {
        return (colorId == null ? 0 : colorId) + "|" + (specCode == null ? "" : specCode);
    }
}
