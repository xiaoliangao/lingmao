package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.module.supplier.entity.ChangeItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 关键字段改动给人看的样子：改动表里存的是原值（字典的 value、全号），页面、操作记录要的是
 * "结算方式 月结 → 预付""账号 6222****1234 → 6217****5678"。
 */
@Component
@RequiredArgsConstructor
class SupplierViews {

    /** 原来、或者改成空的显示成它 */
    static final String EMPTY = "空";

    /** 操作记录的备注最长 500（biz_doc_log.remark） */
    private static final int MAX_REMARK = 500;

    private final SupplierDicts dicts;

    /** 一组改动项翻成给人看的。showAccount：有 bank 权限的人看全号，别人看遮过的 */
    List<ChangeItem> display(List<ChangeItem> items, boolean showAccount) {
        return items.stream().map(item -> new ChangeItem(item.field(), item.label(),
                text(item.field(), item.before(), showAccount), text(item.field(), item.after(), showAccount))).toList();
    }

    /** 一个值给人看：字典翻成中文，税率带 %，账期带"天"，空的写"空" */
    String text(String field, String value, boolean showAccount) {
        if (value == null) {
            return EMPTY;
        }
        SupplierKeyField key = SupplierKeyField.of(field).orElse(null);
        if (key == null) {
            return value;
        }
        return switch (key) {
            case SETTLE_METHOD -> dicts.labelOf(SupplierDicts.SETTLE_METHOD, value);
            case INVOICE_TYPE -> dicts.labelOf(SupplierDicts.INVOICE_TYPE, value);
            case TAX_PCT -> value + "%";
            case CREDIT_DAYS -> value + " 天";
            case BANK_ACCOUNT -> showAccount ? value : BankAccounts.mask(value);
            default -> value;
        };
    }

    /** 操作记录的备注："账期 30 天 → 60 天；税率 13% → 9%"。操作记录谁都能看，账号一律遮（RFC 004 §4 审批第 4 步） */
    String describe(List<ChangeItem> items) {
        String text = display(items, false).stream()
                .map(item -> item.label() + " " + item.before() + " → " + item.after())
                .collect(Collectors.joining("；"));
        return text.length() > MAX_REMARK ? text.substring(0, MAX_REMARK - 1) + "…" : text;
    }
}
