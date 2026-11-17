package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.entity.DocEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 回货单（数据库设计 §14.6）："登记"一步建 + 审核，作废一步完成；作废原因、谁、什么时候由动作改（NEVER） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prd_receipt")
public class ProductionReceipt extends DocEntity {

    private Long prdOrderId;

    private LocalDate receiptDate;

    /** 收货仓（成品仓），一期只记录 */
    private Long warehouseId;

    private String logisticsNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String voidReason;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long voidBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime voidTime;
}
