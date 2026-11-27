package com.lingmao.scm.module.settle.entity;

import lombok.Getter;

/** 对账单一行从哪来（RFC 019 §5.1）。存进 stl_statement_item.source_type，建好不改名 */
@Getter
public enum StatementSourceType {
    /** 采购入库：一行一条入库明细 */
    PURCHASE_IN("采购入库"),
    /** 采购退货：一行一条明细，数量、金额记负数 */
    PURCHASE_RETURN("采购退货"),
    /** 回货：一张回货单一行，次品按单价扣 */
    PRODUCTION_RECEIPT("回货"),
    /** 扣款登记的一条 */
    DEDUCTION("扣款");

    private final String label;

    StatementSourceType(String label) {
        this.label = label;
    }

    /** 货款行（不是扣款登记） */
    public boolean goods() {
        return this != DEDUCTION;
    }
}
