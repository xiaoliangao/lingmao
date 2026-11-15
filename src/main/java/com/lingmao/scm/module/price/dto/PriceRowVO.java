package com.lingmao.scm.module.price.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.price.entity.PriceType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 价格列表的一行（详情页"价格记录"页签）。供应商、物料、SKU、款式的编码和名称由各域的 Service 批量取来填（RFC 006 §7），不跨域 JOIN。
 * prevPrice、nextPrice、nextDate 只有"只看现价"时有
 */
@Data
public class PriceRowVO {

    private Long id;
    private PriceType type;

    private Long supplierId;
    private String supplierCode;
    /** 供应商简称 */
    private String supplierName;

    private Long materialId;
    private String materialCode;
    private String materialName;

    /** 空 = 物料层的价（所有颜色同价） */
    private Long materialSkuId;
    private String skuCode;
    private String skuColorName;
    private String skuSpec;

    private Long styleId;
    /** 款号、款式名称（StyleService.refs） */
    private String styleCode;
    private String styleName;

    /** 字典 process_mode 的 value */
    private String processMode;

    private BigDecimal price;
    /** 字典 unit 的 value */
    private String unit;
    /** 物料改过采购单位，这条的单位和现在的不一样：不再当现价，页签里标"单位已变"（RFC 006 §4） */
    private boolean unitChanged;

    private BigDecimal taxPct;
    private Boolean taxIncluded;
    private Boolean freightIncluded;
    private LocalDate effectiveDate;
    private String reason;
    private String remark;

    /** 来源单据：BizType 的名字、id、单号；手工录的都是空 */
    private String sourceType;
    private Long sourceId;
    private String sourceNo;

    private Boolean voided;
    private String voidReason;
    private Long voidBy;
    private String voidByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime voidTime;

    private Long createBy;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;

    /** 只看现价：这一组的上一条（同一单位，作废的不算） */
    private BigDecimal prevPrice;
    /** 只看现价：这一组以后才生效的最早一条（这一行本身已经生效时才给） */
    private BigDecimal nextPrice;
    private LocalDate nextDate;
}
