package com.lingmao.scm.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.entity.DocEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 物料盘点单（数据库设计 §13.5）：草稿 → 待审核 → 已审核（审核即过账），反审核回草稿；过过账又反审核回来的不能删、只能作废。
 * 仓库、用途、范围建好不改
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_count")
public class InvCount extends DocEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long warehouseId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private CountPurpose purpose;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private CountScope scope;

    private LocalDate countDate;

    private Long counterId;

    /** 取账面数的时间：建单、重新取账面数时改（动作改） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime bookTime;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;
}
