package com.lingmao.scm.module.board.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 加工厂的交期达成率、次品率（RFC 019 §3.1）：一行一个加工厂，合计行 factoryId 是 null。
 * 交期达成按生产单（外发加工单、成品采购单）：良品回齐的那天不晚于交期 = 按期；结案的按最后一次回货。
 * 件数达成率 = 交期当天及之前回的良品（不超过单子件数）÷ 件数。次品率按这段时间里登记的回货：次品 ÷ 回货
 */
@Data
public class FactoryPerfRow {
    private Long factoryId;
    private String factoryCode;
    private String factoryShortName;
    /** 算进来的单（按期 + 逾期） */
    private int orderCount;
    private int onTimeCount;
    private int lateCount;
    /** 交期在这段时间里、还没到期也没回齐的单（不算） */
    private int pendingCount;
    /** 按期 ÷ 算进来的，百分数一位小数 */
    private BigDecimal onTimeRate;
    /** 算进来的单一共多少件 */
    private long dueQty;
    /** 其中交期当天及之前回的良品 */
    private long qtyByDue;
    /** qtyByDue ÷ dueQty，百分数一位小数 */
    private BigDecimal qtyRate;
    /** 这段时间里登记的回货件数（含次品） */
    private long receiptQty;
    private long defectQty;
    /** 次品 ÷ 回货，百分数一位小数；没回货是 null */
    private BigDecimal defectRate;
}
