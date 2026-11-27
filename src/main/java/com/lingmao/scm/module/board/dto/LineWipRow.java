package com.lingmao.scm.module.board.dto;

import lombok.Data;

/**
 * 各品类线在做的件数（RFC 019 §3.2）：一行一个品类线 × 一级品类（款式没挂品类的 categoryId 是 null）。
 * 在做 = 没作废、没完成、没结案的生产单（草稿也算），件数是还没回的良品
 */
@Data
public class LineWipRow {
    /** 字典 product_line */
    private String productLine;
    private Long categoryId;
    private String categoryName;
    private int orderCount;
    /** 还没回的件数（数量 − 良品） */
    private long wipQty;
    /** 其中已经过了交期（有预计交期按预计）的 */
    private long lateQty;
    /** 这个月新排的件数（下单日期在这个月、没作废） */
    private long monthNewQty;
    /** 这个月回的良品 */
    private long monthGoodQty;
}
