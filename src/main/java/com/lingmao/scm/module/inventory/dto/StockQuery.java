package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 库存明细、按物料汇总（docs/api/04 §3.6）；导出多一个 ids（明细） */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockQuery extends PageQuery {
    /** MATERIAL 物料仓 / FACTORY 工厂仓 / 不传 = 两种都要（成品仓不在这里） */
    private String warehouseType;
    private Long warehouseId;
    /** SKU 码、物料编码、名称、仓库名；有空格 = 按几个编码查 */
    private String keyword;
    /** 默认不列数量是 0 的行 */
    private boolean includeZero;
    private List<Long> ids;
}
