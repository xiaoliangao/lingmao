package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 进销存汇总（docs/api/04 §3.6.6，RFC 016 §4）：期间必填、最多一年；仓库类型、仓库限定算哪些仓 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockMovementQuery extends PageQuery {
    @NotNull(message = "请选期间")
    private LocalDate bizDateFrom;
    @NotNull(message = "请选期间")
    private LocalDate bizDateTo;
    /** MATERIAL 物料仓 / FACTORY 工厂仓 / 不传 = 两种都算（成品仓不在这里） */
    @Pattern(regexp = "MATERIAL|FACTORY", message = "仓库类型不对")
    private String warehouseType;
    private Long warehouseId;
    /** SKU 码、物料编码、名称；有空格 = 按几个编码查 */
    private String keyword;
}
