package com.lingmao.scm.module.settle.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 扣款登记（RFC 019 §5.2，数据库设计 §19.5）：延期、质量问题这类要扣的钱，平时登记，月底按日期拉进对账单。
 * 进没进对账单查 stl_statement_item，不在这里另记状态；进了没作废的对账单就不能改、不能删
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stl_deduction")
public class Deduction extends AuditEntity {

    private Long companyId;

    private Long partnerId;

    private LocalDate bizDate;

    private BigDecimal amount;

    private String reason;

    /** 相关单号（选填，写字）；质检登记的是质检单号 */
    private String refNo;

    /** 谁登记的：QC_INSPECTION 质检记录；手工登记的空（V68，RFC 019 §6.2） */
    private String sourceType;

    private Long sourceId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer version;
}
