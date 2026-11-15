package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.math.BigDecimal;

/** 选物料 SKU 弹窗的一行：启用物料的启用 SKU。price 是 SKU 单价，空的用物料参考单价；image 是 SKU 图，没有用物料主图 */
@Data
public class MaterialPickVO {
    private Long skuId;
    private String skuCode;
    private Long materialId;
    private String materialCode;
    private String materialName;
    private String materialType;
    private Long categoryId;
    private String categoryName;
    private Long colorId;
    private String colorCode;
    private String colorName;
    private String colorHex;
    private String spec;
    private String stockUnit;
    private String purchaseUnit;
    private BigDecimal conversionFactor;
    private BigDecimal price;
    private AttachmentVO image;
}
