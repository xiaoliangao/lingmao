package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.module.supplier.entity.Supplier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 供应商的九个关键字段（★，PRD §6.8.1、§6.8.2）：准入过的供应商改了要审批，待准入的直接改。
 * 原型的 SUP_KEY 漏了收款户名，这里照 PRD。顺序照 PRD 的字段表，改动清单、提示都按这个顺序。
 * <p>
 * 改动表里的值一律存成文字（ChangeItem），这里管文字和实体字段之间的来回：
 * text 取当前值的文字（核对 before、生成改动），set 把文字按列的类型写进条件更新（待准入直接改、批准写回）。
 * 数字规整成最短的写法（13.00 → 13），这样 13 和 13.00 不会被当成改了
 */
enum SupplierKeyField {
    NAME("name", "全称", Supplier::getName),
    CREDIT_CODE("creditCode", "统一社会信用代码", Supplier::getCreditCode),
    SETTLE_METHOD("settleMethod", "结算方式", Supplier::getSettleMethod),
    CREDIT_DAYS("creditDays", "账期", Supplier::getCreditDays),
    TAX_PCT("taxPct", "税率", Supplier::getTaxPct),
    INVOICE_TYPE("invoiceType", "发票类型", Supplier::getInvoiceType),
    BANK_NAME("bankName", "开户行", Supplier::getBankName),
    BANK_ACCOUNT("bankAccount", "账号", Supplier::getBankAccount),
    BANK_ACCOUNT_NAME("bankAccountName", "收款户名", Supplier::getBankAccountName);

    private final String field;
    private final String label;
    private final SFunction<Supplier, ?> column;

    SupplierKeyField(String field, String label, SFunction<Supplier, ?> column) {
        this.field = field;
        this.label = label;
        this.column = column;
    }

    String field() {
        return field;
    }

    String label() {
        return label;
    }

    /** 实体上这个字段现在的值，规整成文字；空是 null */
    String text(Supplier supplier) {
        return toText(column.apply(supplier));
    }

    /** 把文字按这一列的类型放进 SET */
    void set(LambdaUpdateWrapper<Supplier> wrapper, String value) {
        Object typed = value == null ? null : switch (this) {
            case CREDIT_DAYS -> Integer.valueOf(value);
            case TAX_PCT -> new BigDecimal(value);
            default -> value;
        };
        if (typed == null) {
            // 写成字面量 NULL：null 参数在 PostgreSQL 上要类型，不如直接写（和 Updates.replaceById 一样）
            wrapper.setSql(columnName() + " = NULL");
        } else {
            setTyped(wrapper, typed);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setTyped(LambdaUpdateWrapper<Supplier> wrapper, Object typed) {
        wrapper.set((SFunction) column, typed);
    }

    /** 库里的列名：creditDays → credit_days */
    String columnName() {
        return field.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }

    static Optional<SupplierKeyField> of(String field) {
        return Arrays.stream(values()).filter(f -> f.field.equals(field)).findFirst();
    }

    /**
     * 把一组改动的 after 写进条件更新的 SET：待准入时直接改、批准时写回都用它。
     * 返回的条件只有 id = ?，调用方再接着加别的条件（status = 'PENDING_ADMIT'、credit_code IS NULL）
     */
    static LambdaUpdateWrapper<Supplier> writeBack(long id, List<ChangeItem> items) {
        LambdaUpdateWrapper<Supplier> wrapper = new LambdaUpdateWrapper<Supplier>().eq(Supplier::getId, id);
        for (ChangeItem item : items) {
            of(item.field()).orElseThrow(() -> new IllegalArgumentException("不是关键字段: " + item.field()))
                    .set(wrapper, item.after());
        }
        return wrapper;
    }

    /** 两个实体（当前的、按请求改过的）比一遍，返回变了的项，按 PRD 的顺序 */
    static List<ChangeItem> diff(Supplier before, Supplier after) {
        List<ChangeItem> items = new ArrayList<>();
        for (SupplierKeyField key : values()) {
            String from = key.text(before);
            String to = key.text(after);
            if (!Objects.equals(from, to)) {
                items.add(new ChangeItem(key.field, key.label, from, to));
            }
        }
        return items;
    }

    /** 值 → 文字：数字去掉末尾的 0，空串当空 */
    static String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        String text = value.toString();
        return text.isEmpty() ? null : text;
    }
}
