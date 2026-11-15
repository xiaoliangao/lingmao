package com.lingmao.scm.module.supplier.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 关键字段的待批改动（sup_supplier_change，数据库设计 §6.5、RFC 004 §4）：
 * 准入过的供应商（合作中、停用、黑名单）改关键字段，先插一条 PENDING，另一个有准入权限的人批准才写回 sup_supplier。
 * 只插不整条改：批准、驳回、撤回都是比较并交换，所以除了 id 以外的列都标 NEVER
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sup_supplier_change", autoResultMap = true)
public class SupplierChange extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long supplierId;

    /** [{field, label, before, after}]，存原值（账号全号），返回给页面时遮 */
    @TableField(typeHandler = JsonbTypeHandler.class, updateStrategy = FieldStrategy.NEVER)
    private List<ChangeItem> changes;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private ChangeStatus status;

    /** 申请人；审批人不能是他 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long applyBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime applyTime;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long auditBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime auditTime;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String rejectReason;
}
