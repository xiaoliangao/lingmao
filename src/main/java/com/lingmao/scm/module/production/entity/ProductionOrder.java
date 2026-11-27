package com.lingmao.scm.module.production.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.entity.DocEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 外发加工单、成品采购单（数据库设计 §14.1），统称生产单。种类、来源订单、款式、资料包版本排单时定，之后不改（NEVER）；
 * 预计交期由上报异常改、领料状态由领料过账改、件数合计排单时算好，也都是 NEVER
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prd_order")
public class ProductionOrder extends DocEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private ProductionKind kind;

    private Long factoryId;

    /** 字典 process_mode：只能在同一种单里换 */
    private String processMode;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long orderId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long styleId;

    /** 品类线（RFC 013 §2）：排单时取款式的，不变；加工厂要做这条线 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String productLine;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long techPackId;

    private LocalDate orderDate;

    private LocalDate dueDate;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDate expectDate;

    private BigDecimal unitPrice;

    private Long priceSourceId;

    private String priceChangeReason;

    /** 改价时价格档案的现价（V70 快照）：审核后新价记进档案，详情还能看出原来档案是多少；没改价、没档案为空 */
    private BigDecimal archivePrice;

    private BigDecimal taxPct;

    private Boolean taxIncluded;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer totalQty;

    private BigDecimal totalAmount;

    private Long merchId;

    private String settleMethod;

    private String terms;

    /** 这张单加的"按单选"节点（字典 prod_node 里用法是 OPTIONAL 的，如二次加工），逗号分隔；null = 没加（甲方 11-27 答 Q61） */
    private String optionalNodes;

    /** null = 不用领料（资料包里没有自购的料） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private MaterialIssueStatus issueStatus;

    /** 节点整计划版本；报工和普通单据动作不修改。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer planRevision = 0;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;
}
