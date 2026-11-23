package com.lingmao.scm.module.dev.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 只增不改的评审；数据库同时阻止更新、删除和清空，评审人只可在创建评审的同一事务插入。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_sample_review")
public class SampleReview extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String tenantKey;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long companyId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sampleOrderId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate reviewDate;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private SampleReviewResult result;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String opinion;
}
