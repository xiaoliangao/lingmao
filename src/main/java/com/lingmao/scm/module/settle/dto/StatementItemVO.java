package com.lingmao.scm.module.settle.dto;

import com.lingmao.scm.module.settle.entity.StatementSourceType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 对账单的一行明细 */
@Data
public class StatementItemVO {
    private Long id;
    private Integer seq;
    /** PURCHASE_IN 采购入库 / PURCHASE_RETURN 采购退货 / PRODUCTION_RECEIPT 回货 / DEDUCTION 扣款 */
    private StatementSourceType sourceType;
    /** 入库单、退货单、回货单、扣款的 id */
    private Long sourceId;
    private Long sourceItemId;
    /** 回货单所在的生产单 id（链接用）；别的是空 */
    private Long parentId;
    /** 单号；扣款是登记时写的相关单号 */
    private String sourceNo;
    /** 采购单号、生产单号 */
    private String orderNo;
    /** 供应商送货单号 */
    private String deliveryNo;
    private LocalDate bizDate;
    private String description;
    /** 采购单位（字典 unit 的值）；回货是件，空着 */
    private String unit;
    private BigDecimal qty;
    /** 没有单价是空（金额是 0，页面上标"缺单价"） */
    private BigDecimal price;
    private BigDecimal amount;
    private BigDecimal deductAmount;
}
