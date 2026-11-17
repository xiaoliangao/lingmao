package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

/** 库存域里几处共用的小工具：数字写进提示语、字典翻中文 */
final class InvTexts {

    private InvTexts() {
    }

    /** 提示语里的数：去掉末尾的 0（"0.140" → "0.14"，"5.000" → "5"） */
    static String plain(BigDecimal n) {
        return n == null ? "" : n.stripTrailingZeros().toPlainString();
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
