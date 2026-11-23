package com.lingmao.scm.module.dev.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 一版一张打样单；sampleVersion 是业务版次，version 是该单的乐观锁。qty 是整版总件数。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_sample_order")
public class SampleOrder extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String tenantKey;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long companyId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String no;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sampleId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer sampleVersion;

    private SampleType sampleType;

    private MakeWay makeWay;

    private Long factoryId;

    private Long makerId;

    private LocalDate needDate;

    private Integer qty;

    private Long sizeGroupId;

    private String reason;

    private String requirement;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private SampleOrderStatus status;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer version;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long dispatchedBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime dispatchedTime;
}
