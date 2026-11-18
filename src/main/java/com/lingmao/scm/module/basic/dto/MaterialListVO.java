package com.lingmao.scm.module.basic.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 物料列表的一行（resources/mapper/basic/MaterialMapper.xml）：分类名、默认供应商简称是 JOIN 出来的，
 * 供应商只读 JOIN sup_supplier（架构 §3.2 规则 4）；image 是主图，查完一页再一次取
 */
@Data
public class MaterialListVO {
    private Long id;
    private String code;
    private String name;
    private Long categoryId;
    /** 二级分类的编码、名称，一级分类的名称 */
    private String categoryCode;
    private String categoryName;
    private String parentCategoryName;
    private String materialType;
    private String stockUnit;
    private String purchaseUnit;
    private BigDecimal conversionFactor;
    private BigDecimal widthCm;
    private BigDecimal weightGsm;
    private String composition;
    private Long defaultSupplierId;
    /** 默认供应商的简称 */
    private String defaultSupplierName;
    private String supplierItemNo;
    private BigDecimal refPrice;
    private Integer status;
    private Integer skuCount;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;

    private AttachmentVO image;
}
