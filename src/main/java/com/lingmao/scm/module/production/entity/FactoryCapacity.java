package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 工厂产能（prd_factory_capacity，PRD 08.02，甲方 11-27 答 Q60）：加工厂 × 品类的日产能，从 startMonth 起到下一次登记为止 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prd_factory_capacity")
public class FactoryCapacity extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long factoryId;

    /** null = 不分品类，这家厂所有款合计 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long categoryId;

    /** 从哪个月起，每月 1 号 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate startMonth;

    /** 件 / 天，按自然日 */
    private Integer dailyQty;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;
}
