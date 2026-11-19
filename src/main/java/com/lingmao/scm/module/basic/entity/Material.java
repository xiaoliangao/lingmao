package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 物料（bas_material，数据库设计 §5.9）：面料、辅料、包材统一建档，一物一码。采购、库存、BOM 落在 SKU 上，这里放各 SKU 共用的属性。
 * 编码、分类、库存单位建好不改（RFC 003 §7.2）；采购单位、换算系数能改（单据存了当时的单位和系数）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "bas_material", autoResultMap = true)
public class Material extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    /** 领猫编码：从领猫导入的物料原来的编码，只做对应（RFC 012 §4.2）；只有导入时写 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String legacyCode;

    private String name;

    /** 必须是启用的二级分类；建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long categoryId;

    /** 字典 material_type */
    private String materialType;

    /** 库存单位，字典 unit；库存、BOM 用量都按它记，建好不改 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String stockUnit;

    /** 采购单位，字典 unit */
    private String purchaseUnit;

    /** 1 采购单位 = n 库存单位 */
    private BigDecimal conversionFactor;

    private BigDecimal widthCm;
    private BigDecimal weightGsm;
    private String composition;
    private String texture;

    /** 面料功能，文字，如"防水、抗皱"；面料、里料才有（RFC 012 §5.1） */
    private String fabricFunction;

    /** 是否带吊牌，空 = 没填；只记录（RFC 012 §5.1） */
    private Boolean withTag;

    /** 默认供应商：只当默认值，保存时不查状态（架构 A23）；basic 不调 supplier */
    private Long defaultSupplierId;

    private String supplierItemNo;

    /** 参考单价：按采购单位、含税 */
    private BigDecimal refPrice;

    /** 起订量，采购单位 */
    private BigDecimal moq;

    /** 最小包装量，采购单位 */
    private BigDecimal minPackQty;

    /** 采购周期（天） */
    private Integer leadDays;

    private Integer year;

    /** 字典 season */
    private String season;

    /** 默认物料来源：MaterialSource 的名字 */
    private String defaultSource;

    /** 默认损耗 %；空 = 0 */
    private BigDecimal defaultLossPct;

    /** 备料比 %；空 = 用物料类型字典项的数值 */
    private BigDecimal reservePct;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer status;

    private String remark;

    /** 领猫附加属性 {"领猫列名": "值"}，只读显示；只有导入时写，保存不碰 */
    @TableField(typeHandler = JsonbTypeHandler.class, updateStrategy = FieldStrategy.NEVER)
    private Map<String, String> legacyAttrs;
}
