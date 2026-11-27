package com.lingmao.scm.module.production.dto;

import lombok.Data;

import java.time.LocalDate;

/** 工厂料账 · 按生产单的一行（前端 LedgerOrderRow） */
@Data
public class LedgerOrderRowVO {
    private Long id;
    private String no;
    /** OUTSOURCE / BUY */
    private String kind;
    private Long factoryId;
    private String factoryShortName;
    private Long styleId;
    private String styleCode;
    private String styleName;
    /** 加工数 */
    private int totalQty;
    /** 良品 = 已回 − 次品 */
    private int goodQty;
    /** 生产单状态 */
    private String status;
    private LocalDate dueDate;
    private LedgerStatus ledgerStatus;
    private Long settleDocId;
    private String settleDocNo;
    private String settleDocStatus;
    /** 几种料超耗了（实耗 &gt; 应耗；没核销的是 0） */
    private int overCount;
    /** 几种料还有结余（该退回来的） */
    private int returnCount;
}
