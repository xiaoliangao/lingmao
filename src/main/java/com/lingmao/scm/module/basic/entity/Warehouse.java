package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 仓库（bas_warehouse，数据库设计 §5.11）：物料仓、成品仓在页面上手建；工厂仓由供应商同步（RFC 003 §6），
 * 页面上只能改它的地址、负责人、电话、备注和启用停用
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bas_warehouse")
public class Warehouse extends AuditEntity {

    /** 建好不改；工厂仓 = GC + 供应商编码里的数字 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    /** 工厂仓 = 供应商简称 + 仓，随供应商改名 */
    private String name;

    /** WarehouseType 的名字；建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String type;

    /** 工厂仓绑定的工厂（sup_supplier.id），别的仓为空；建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long factoryId;

    private String address;
    private Long keeperId;
    private String phone;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;

    private String remark;
}
