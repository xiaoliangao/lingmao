package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 呆滞料（GET /api/reports/stale-materials，RFC 019 §3.3）：自己的物料仓里有库存、多少天没出没入的 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StaleMaterialQuery extends PageQuery {
    /** 多少天没动算呆滞；不填按设置的默认（GET /stale-materials/days，没设过是 90） */
    @Min(value = 1, message = "天数至少 1")
    @Max(value = 3650, message = "天数最多 3650")
    private Integer days;
    /** 物料仓 */
    private Long warehouseId;
    /** 查物料编码、SKU 编码、名称 */
    private String keyword;
}
