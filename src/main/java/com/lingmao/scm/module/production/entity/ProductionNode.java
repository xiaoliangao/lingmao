package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 进度节点（数据库设计 §14.3）：最后一级审核时生成，反审核删掉。实际开始、实际完成、完成数量、备注是最近一次报的，
 * 全部历史在 prd_node_report。改计划、报进度都用条件更新（ProductionNodeMapper），不整条写回
 */
@Data
@TableName("prd_node")
public class ProductionNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long prdOrderId;

    private Integer seq;

    /** 字典 prod_node 的 value */
    private String nodeCode;

    /** 生成时的名字 */
    private String nodeName;

    private LocalDate planStart;

    private LocalDate planFinish;

    private LocalDate actualStart;

    private LocalDate actualFinish;

    private Integer doneQty;

    private Long lastReportBy;

    private OffsetDateTime lastReportTime;

    private String remark;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updateTime;
}
