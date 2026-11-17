package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 生产异常（数据库设计 §14.5）：上报人、上报时间是 create_by、create_time。状态和处理人、关闭人由动作改（NEVER） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prd_issue")
public class ProductionIssue extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String no;

    private Long prdOrderId;

    /** 字典 issue_type */
    private String issueType;

    private String description;

    private Boolean impactDelivery;

    private LocalDate newExpectDate;

    /** SELF / FACTORY / SUPPLIER / CUSTOMER；null = 待定（关闭时补） */
    private String responsibleParty;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String measure;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long handlerId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private IssueStatus status;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long closeBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime closeTime;
}
