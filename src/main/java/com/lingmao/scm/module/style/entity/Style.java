package com.lingmao.scm.module.style.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 款式（sty_style，数据库设计 §8.1、RFC 005）：下单、生产、装箱取数的唯一来源。
 * 款号只在插入时写；状态走动作（StatusCas）；资料包状态由资料包回写；样衣编号、领猫附加属性只有导入时写。
 * 这几列标 NEVER，整条写回（Updates.replaceById）时碰不到它们
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sty_style", autoResultMap = true)
public class Style extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String code;

    private String name;

    /** 花名：俗称，不查重（Q43） */
    private String aliasName;

    /** 大类，字典 style_class：value 是款号开头的 2 位字母 */
    private String styleClass;

    /** 字典 brand */
    private String brand;

    /** 品类：启用的叶子（CategoryService.checkSelectable） */
    private Long categoryId;

    private Integer year;

    /** 字典 season */
    private String season;

    /** 字典 wave */
    private String wave;

    /** 风格，字典 style_tag */
    private String styleTag;

    /** 字典 series */
    private String series;

    /** 字典 gender */
    private String gender;

    private Long designerId;

    private Long patternMakerId;

    /** 来源，字典 style_source；CUSTOMER_SAMPLE 只在 TRADE 开时收 */
    private String source;

    /** TRADE 留口子，关着时必须为空 */
    private Long customerId;

    private String customerStyleNo;

    /** 样衣编号：导入时照填，二期转大货时自动带 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sampleNo;

    /** 已经有 SKU 的不能换（RFC 005 §5） */
    private Long sizeGroupId;

    /** 主力面料：只当默认值（架构 A23） */
    private Long mainMaterialId;

    /** 吊牌品名 */
    private String productName;

    private String composition;

    /** 字典 exec_standard */
    private String execStandard;

    /** 字典 safety_category */
    private String safetyCategory;

    private BigDecimal tagPrice;

    /** 参考成本价，元/件，手填 */
    private BigDecimal refCost;

    /** 默认加工厂：只当默认值，存的时候不查供应商状态（架构 A23） */
    private Long defaultFactoryId;

    private LocalDate launchDate;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private StyleStatus status;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private TechPackStatus techPackStatus;

    private String remark;

    /** 领猫附加属性 {"列名": "值"}，只读显示；只有导入时写 */
    @TableField(typeHandler = JsonbTypeHandler.class, updateStrategy = FieldStrategy.NEVER)
    private Map<String, String> legacyAttrs;
}
