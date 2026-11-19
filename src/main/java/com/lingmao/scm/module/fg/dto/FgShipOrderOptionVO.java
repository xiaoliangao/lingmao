package com.lingmao.scm.module.fg.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/** 能发货的需求订单（前端 FgShipOrderOption） */
@Data
public class FgShipOrderOptionVO {
    private Long id;
    private String no;
    private String orderType;
    private String status;
    private String statusLabel;
    private String styleCodes;
    private Integer totalQty;
    private Integer shippedQty;
    /** 给了仓时：这个仓里订单上 SKU 的库存件数 */
    private Integer stockQty;
    @JsonIgnore
    private Long companyId;
}
