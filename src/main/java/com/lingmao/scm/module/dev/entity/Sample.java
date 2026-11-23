package com.lingmao.scm.module.dev.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 样衣主档；version 是乐观锁，deletedToken=0 为有效，删除草稿只设置为自身 id，审计身份永久保留。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_sample")
public class Sample extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String tenantKey;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long companyId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    private String name;

    private String brand;

    private Long categoryId;

    private Integer year;

    private String season;

    private String wave;

    private Long designerId;

    private Long patternMakerId;

    private String source;

    private Long customerId;

    private String customerStyleNo;

    private Long sizeGroupId;

    private Long sampleFactoryId;

    private Long fabricSupplierId;

    private String fabricDesc;

    private LocalDate expectFinishDate;

    private BigDecimal estTagPrice;

    private BigDecimal estCost;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private SampleStatus status;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long convertedStyleId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long convertBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime convertTime;

    private String remark;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer version;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long deletedToken;
}
