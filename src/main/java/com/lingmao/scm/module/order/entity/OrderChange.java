package com.lingmao.scm.module.order.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.entity.DocEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单变更单（数据库设计 §10.3，docs/api/02 §2.2）：草稿 → 待审核 → 已审核（写回订单）；驳回回到草稿、记下原因；草稿、待审核能作废。
 * 订单建好不改；变更类型由改动算出来
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_change")
public class OrderChange extends DocEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long orderId;

    /** 逗号分隔的 ChangeType 名字 */
    private String changeTypes;

    private String reason;

    private ResponsibleParty responsibleParty;

    private String feeNote;

    /** 最近一次驳回的原因，重新提交时清掉（动作改） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String rejectReason;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;
}
