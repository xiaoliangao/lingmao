package com.lingmao.scm.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.entity.DocEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 物料入库单、物料出库单（数据库设计 §13.1）。状态：草稿 / 已审核（审核即过账）/ 已作废（过过账又反审核回来的才能作废） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_doc")
public class InvDoc extends DocEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private InvDocKind kind;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private InvDocType docType;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;

    /** 源单：BizType 的名字（PURCHASE_ORDER / PRODUCTION_ORDER）；建单时定 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceType;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sourceId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceNo;

    private Long fromWarehouseId;

    private Long toWarehouseId;

    private Long partnerId;

    /** 客供入库用（TRADE），一期空 */
    private Long customerId;

    private LocalDate docDate;

    private String reason;

    private String supplierDeliveryNo;

    private String logisticsNo;
}
