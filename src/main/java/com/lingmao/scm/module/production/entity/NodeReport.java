package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 报工记录（数据库设计 §14.4）：只插不改。汇报人、汇报时间是 create_by、create_time，插入时自动填 */
@Data
@TableName("prd_node_report")
public class NodeReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long nodeId;

    private Long prdOrderId;

    private NodeReportAction action;

    private LocalDate actualStart;

    private LocalDate actualFinish;

    private Integer doneQty;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;
}
