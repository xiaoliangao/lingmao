package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 修改一个物料 SKU（PUT 整体覆盖）：颜色、规格、SKU 码建好不改，只改这几列 */
@Data
public class MaterialSkuSaveReq {

    @Size(max = 64, message = "供应商色号最长 64 个字")
    private String supplierColorNo;

    /** SKU 级货号；空 = 用物料的 */
    @Size(max = 64, message = "货号最长 64 个字")
    private String supplierItemNo;

    /** 单价，按采购单位、含税；空 = 用物料的参考单价 */
    @DecimalMin(value = "0", message = "单价不能是负数")
    @Digits(integer = 10, fraction = 4, message = "单价最多 4 位小数")
    private BigDecimal price;

    /** 安全库存，库存单位 */
    @DecimalMin(value = "0", message = "安全库存不能是负数")
    @Digits(integer = 11, fraction = 3, message = "安全库存最多 3 位小数")
    private BigDecimal safetyStock;

    @Size(max = 255, message = "备注最长 255 个字")
    private String remark;
}
