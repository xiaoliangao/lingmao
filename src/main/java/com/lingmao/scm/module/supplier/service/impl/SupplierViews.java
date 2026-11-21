package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.common.doc.FieldChange;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.security.FieldPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
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

    /** 开户行、账号、收款户名：操作记录里要有"看银行信息"的权限才看得到值 */
    private static final Set<String> BANK_FIELDS = Set.of(SupplierKeyField.BANK_NAME.field(), SupplierKeyField.BANK_ACCOUNT.field(),
            SupplierKeyField.BANK_ACCOUNT_NAME.field());

    /**
     * 关键字段改动 → 操作记录的改前改后（RFC 016 §3）：字典翻中文、账号一律存遮过的（操作记录不留全号），
     * 银行三项另外要 bank 权限才看得到值；空是 null
     */
    List<FieldChange> changes(List<ChangeItem> items) {
        return items.stream().map(item -> new FieldChange(item.field(), item.label(),
                item.before() == null ? null : text(item.field(), item.before(), false),
                item.after() == null ? null : text(item.field(), item.after(), false),
                BANK_FIELDS.contains(item.field()) ? FieldPermissions.BANK : null)).toList();
    }

    /** 操作记录的备注："账期 30 天 → 60 天；税率 13% → 9%"。操作记录谁都能看，账号一律遮（RFC 004 §4 审批第 4 步） */
    String describe(List<ChangeItem> items) {
        String text = display(items, false).stream()
                .map(item -> item.label() + " " + item.before() + " → " + item.after())
                .collect(Collectors.joining("；"));
        return text.length() > MAX_REMARK ? text.substring(0, MAX_REMARK - 1) + "…" : text;
    }
}
