package com.lingmao.scm.module.dev.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 派单时生成的节点快照；动作通过父样衣和当前打样单双版本守卫后，按节点 ID 升序锁定。 */
@Data
@TableName("dev_sample_node")
public class SampleNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String tenantKey;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long companyId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sampleOrderId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer seq;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String nodeCode;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String nodeName;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer durationDays;
    private LocalDate planDate;
    private LocalDate doneDate;
    private Long doneBy;
    private Long ownerId;
    private String remark;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updateTime;
}
