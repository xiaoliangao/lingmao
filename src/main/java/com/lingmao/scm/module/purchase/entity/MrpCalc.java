package com.lingmao.scm.module.purchase.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.common.entity.DocEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 算料单（数据库设计 §11.1，docs/api/03 §2.1）。auditBy、auditTime 是确认人、确认时间；不分主体（companyId 留空）。
 * 数字都在结果行上（MrpCalcItem），是算料时存下的快照，草稿里能改
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "mrp_calc", autoResultMap = true)
public class MrpCalc extends DocEntity {

    /** 要料日期：几张订单里最早的"裁剪"计划开始（没排单按承诺交期倒推），算料时的快照；算不出来是 null */
    private LocalDate needDate;

    /** 重新算料的上一张：建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long prevCalcId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private MrpStatus status;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private MrpCloseReason closeReason;

    /** 手动关闭的人；自动关闭是 null */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long closeBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime closeTime;

    /** 算料时的输入（架构 §6）：各订单每个 SKU 的件数、用的生产 BOM、承诺交期 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private MrpSnapshot inputSnapshot;
}
