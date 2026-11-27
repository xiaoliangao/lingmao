package com.lingmao.scm.module.settle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 对账单明细（数据库设计 §19.1）：一行一条入库明细、退货明细、一张回货单、一条扣款。
 * 单号、摘要、单价都是生成时的快照。一条源单明细只能进一张没作废的对账单（uk_stl_item_source WHERE active）
 */
@Data
@TableName("stl_statement_item")
public class StatementItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long statementId;

    private Integer seq;

    private StatementSourceType sourceType;

    /** 入库单、退货单（inv_doc）、回货单、扣款的 id */
    private Long sourceId;

    /** 入库、退货的明细 id；回货、扣款是空 */
    private Long sourceItemId;

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

    /** 退货是负的；扣款是空 */
    private BigDecimal qty;

    private BigDecimal price;

    /** 货款，退货是负的，扣款是 0 */
    private BigDecimal amount;

    private BigDecimal deductAmount;

    private Boolean active;
}
