package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 采购域里几处共用的小工具：数字写进提示语、逗号连起来的单号拆开、字典翻中文 */
final class PurTexts {

    private PurTexts() {
    }

    /** 提示语里的数：去掉末尾的 0（"0.140" → "0.14"，"5.000" → "5"） */
    static String plain(BigDecimal n) {
        return n == null ? "" : n.stripTrailingZeros().toPlainString();
    }

    /** 算料行上逗号连起来的订单号、款号拆成列表；空的是空列表 */
    static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).filter(s -> !s.isBlank()).toList();
    }

    /** 一个字典的 value → 中文（停用的也翻：旧单据上还有） */
    static Map<String, String> labels(DictDataService dicts, String type) {
        return dicts.listByType(type).stream()
                .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** 几个行号连成"第 3、5 行" */
    static String rows(List<Integer> seqs) {
        return "第 " + seqs.stream().map(String::valueOf).collect(Collectors.joining("、")) + " 行";
    }
}
