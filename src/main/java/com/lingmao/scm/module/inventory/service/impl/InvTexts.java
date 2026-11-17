package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** 库存域里几处共用的小工具：数字写进提示语、关键字拆单号、字典翻中文 */
final class InvTexts {

    private InvTexts() {
    }

    /** 提示语里的数：去掉末尾的 0（"0.140" → "0.14"，"5.000" → "5"） */
    static String plain(BigDecimal n) {
        return n == null ? "" : n.stripTrailingZeros().toPlainString();
    }

    /** 关键字有空格 = 几个单号 / 编码一起精确查（README §3.2），名称里带空格的照样按整句模糊查；返回大写的，没有空格是 null */
    static List<String> codesIn(String keyword) {
        if (keyword == null || !keyword.strip().matches(".*\\s.*")) {
            return null;
        }
        return new ArrayList<>(Arrays.stream(keyword.strip().split("\\s+"))
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /** 一个字典的 value → 中文（停用的也翻：旧单据上还有） */
    static Map<String, String> labels(DictDataService dicts, String type) {
        return dicts.listByType(type).stream()
                .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
