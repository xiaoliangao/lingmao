package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 成品库存明细、按款（docs/api/07 §3.6） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgStockQuery extends PageQuery {
    private Long warehouseId;
    private Long styleId;
    /** 款号、款名、SKU 码 */
    private String keyword;
    /** 列出 0 的行；默认不列 */
    private boolean includeZero;
    /** 导出选中的（明细） */
    private List<Long> ids;
}
