package com.lingmao.scm.module.supplier.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import com.lingmao.scm.common.mybatis.StringArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 供应商（sup_supplier，数据库设计 §6.1）。
 * <p>
 * 标了 NEVER 的列普通保存（Updates.replaceById）写不到（RFC 004 §3、RFC 001 §4.4）：
 * <ul>
 *   <li>★ 关键字段：全称、信用代码、结算方式、账期、发票类型、税率、开户行、账号、收款户名。待准入时直接改、准入过的挂起等审批，
 *       都由 SupplierServiceImpl 单独的条件更新写（SupplierKeyField）</li>
 *   <li>状态和准入、拉黑的几列：只有动作（申请准入、通过、拉黑……）的比较并交换能改</li>
 *   <li>编码建好不改；领猫附加属性只在导入时写，之后只读</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sup_supplier", autoResultMap = true)
public class Supplier extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    /** ★ 全称 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String name;

    private String shortName;

    /** ★ 统一社会信用代码，存规整过的（去空格、大写） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String creditCode;

    /** 等级 A / B / C / D */
    private String level;

    /** 做哪几条品类线（字典 product_line 的 value，RFC 013 §2），至少一条：和我的线有交集才看得到；单据的线要在里面 */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> productLines;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private SupplierStatus status;

    private String contact;
    private String phone;
    private String wechat;
    private String address;

    /** ★ 字典 settle_method */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String settleMethod;

    /** ★ 账期（天） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer creditDays;

    /** ★ 字典 invoice_type */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String invoiceType;

    /** ★ 税率 %，13 表示 13% */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal taxPct;

    /** ★ 开户行 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String bankName;

    /** ★ 账号，全号；接口返回时遮成前 4 后 4（RFC 004 §5） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String bankAccount;

    /** ★ 收款户名 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String bankAccountName;

    /** 负责采购员 → sys_user */
    private Long buyerId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long admitApplyBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime admitApplyTime;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long admitBy;

    /** 准入时间；空 = 从没准入过，解除黑名单时回待准入（RFC 004 §2） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime admitTime;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String blackReason;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long blackBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime blackTime;

    private String remark;

    /** 领猫附加属性 {"领猫列名": "值"}，导入时写，之后只读（数据库设计 §1.8） */
    @TableField(typeHandler = JsonbTypeHandler.class, updateStrategy = FieldStrategy.NEVER)
    private Map<String, String> legacyAttrs;
}
