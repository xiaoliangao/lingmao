package com.lingmao.scm.module.price.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 价格档案的一条（prc_price，数据库设计 §7.1，RFC 006 §2）。
 * <p>
 * 只插不改：调价插一条新的，录错了作废。插入后只有作废的四列（voided、void_reason、void_by、void_time）能变，
 * 而且只有 PriceMapper.voidById 那一条 UPDATE 改它们；BaseMapper 自带的 updateById 这些不要用。
 * 所以只有创建人、创建时间两个审计列（数据库设计 §1.2 只插表），不继承带修改人、修改时间的 AuditEntity。
 * <p>
 * 物料价、加工价各自的必填列由检查约束 ck_prc_price_type 兜底：物料价有物料、没有款式和加工方式；加工价有款式和加工方式、没有物料和 SKU
 */
@Data
@TableName("prc_price")
public class Price {

    @TableId(type = IdType.AUTO)
    private Long id;

    private PriceType type;

    /** 供应商，加工价是工厂 */
    private Long supplierId;

    /** 物料价必填 */
    private Long materialId;

    /** 空 = 这个物料所有颜色同价（物料层的价）；填了只管这个 SKU */
    private Long materialSkuId;

    /** 加工价必填 */
    private Long styleId;

    /** 加工价必填，字典 process_mode；FULL 包工包料 */
    private String processMode;

    /** 单价，> 0，4 位小数 */
    private BigDecimal price;

    /** 记录时的单位：物料价 = 物料当时的采购单位，加工价 = PIECE（件）。取现价只认物料现在的采购单位 */
    private String unit;

    /** 税率 %，默认带供应商的 */
    private BigDecimal taxPct;

    private Boolean taxIncluded;

    private Boolean freightIncluded;

    /** 生效日期：可以补录以前的，也可以填以后的 */
    private LocalDate effectiveDate;

    /** 调价原因：同一层级已有现价时必填 */
    private String reason;

    private String remark;

    /** 来源单据：BizType 的名字、id、单号；手工录的都是空 */
    private String sourceType;
    private Long sourceId;
    private String sourceNo;

    /** 作废的不参与取价，记录还留着 */
    private Boolean voided;
    private String voidReason;
    private Long voidBy;
    private OffsetDateTime voidTime;

    /** 记录人、记录时间：插入时由 AuditMetaObjectHandler 填 */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;
}
