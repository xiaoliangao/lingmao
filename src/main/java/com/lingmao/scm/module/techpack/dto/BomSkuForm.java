package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 保存时一条配料 */
@Data
public class BomSkuForm {
    private Long colorId;
    private String sizeCode;
    @NotNull(message = "配料要选物料 SKU")
    private Long materialSkuId;
    private BigDecimal refPrice;
}
