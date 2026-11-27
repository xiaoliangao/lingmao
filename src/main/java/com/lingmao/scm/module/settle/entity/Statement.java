package com.lingmao.scm.module.settle.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.common.entity.DocEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import com.lingmao.scm.module.settle.dto.CompareResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 供应商对账单（RFC 019 §5.1，数据库设计 §19.1）：一个主体 × 一家供应商或加工厂 × 一段期间。
 * 审核人、审核时间（DocEntity）在这里是确认人、确认时间。合计、比对结果、状态都由动作改（NEVER）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "stl_statement", autoResultMap = true)
public class Statement extends DocEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long partnerId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate periodStart;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate periodEnd;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate statementDate;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private StatementStatus status;

    /** 货款合计，退货是负的 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal totalAmount;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal deductAmount;

    /** 实付 = 货款 − 扣款 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal payableAmount;

    /** 对方对账单上的合计（上传比对后有） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal theirAmount;

    @TableField(typeHandler = JsonbTypeHandler.class, updateStrategy = FieldStrategy.NEVER)
    private CompareResult compareResult;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sentBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime sentTime;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String confirmNote;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String voidReason;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long voidBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime voidTime;
}
